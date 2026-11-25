package com.raster.moneycraft.extension.service;

import com.raster.moneycraft.accounting.spi.VoucherExtensionValidator;
import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.extension.dto.ExtensionDtos;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Visual & JSON Schema 2020-12 Extension Workbench service (EXT-001..007) implementing
 * immutable revision authoring, bounded DMN 1.6 / FEEL validation, company activation bindings,
 * and {@link VoucherExtensionValidator} enforcement across UI, REST API, and Spreadsheet Import.
 */
@Service
public class ExtensionWorkbenchService implements VoucherExtensionValidator {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,95}$");
    private static final Pattern ACTION_PAIR_PATTERN = Pattern.compile("^([A-Z])/([a-z])$");
    private static final Set<String> ADMITTED_PROJECTIONS = Set.of("TRIAL_BALANCE", "LEDGERS", "VOUCHERS");
    private static final Set<String> ADMITTED_PRINT_PROFILES = Set.of(
        "TAX_INVOICE", "RECEIPT", "JOURNAL_VOUCHER", "TRIAL_BALANCE"
    );
    private static final Set<String> ADMITTED_CONNECTOR_DIRECTIONS = Set.of(
        "OUTBOUND_HTTP", "OUTBOUND_DB_READ", "OUTBOUND_DB_WRITE", "INBOUND_ODBC_QUERY"
    );

    private final AuthorityGuard authorityGuard;
    private final ExtensionAccessHelper accessHelper;
    private final CustomFieldService customFieldService;
    private final BoundedFeelRuleEvaluator feelEvaluator;
    private final TinkCryptoService tinkCryptoService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final AuditChainService auditChainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public ExtensionWorkbenchService(
        AuthorityGuard authorityGuard,
        ExtensionAccessHelper accessHelper,
        CustomFieldService customFieldService,
        BoundedFeelRuleEvaluator feelEvaluator,
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer,
        AuditChainService auditChainService,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.accessHelper = accessHelper;
        this.customFieldService = customFieldService;
        this.feelEvaluator = feelEvaluator;
        this.tinkCryptoService = tinkCryptoService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.auditChainService = auditChainService;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public List<ExtensionDtos.ExtensionPackageDto> listPackages(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        accessHelper.requireCompanyRead(principal, companyId);
        List<PackageRow> rows = businessJdbcTemplate.query(
            """
            SELECT p.id, p.company_id, p.namespace, p.extension_uuid, p.display_name,
                   p.revision_no, p.schema_dialect, p.definition_cipher_gcm, p.definition_sha256,
                   p.summary_json, p.validation_diagnostics_json, p.status,
                   p.version_no, p.created_by, p.created_at,
                   b.state AS binding_state, b.extension_package_id AS bound_pkg_id
              FROM mc_extension_package p
              LEFT JOIN mc_extension_company_binding b
                ON b.company_id = p.company_id AND b.extension_uuid = p.extension_uuid
             WHERE p.company_id = ?
             ORDER BY p.namespace ASC, p.display_name ASC, p.revision_no DESC
            """,
            (rs, rowNum) -> mapPackageRow(rs),
            companyId
        );
        List<ExtensionDtos.ExtensionPackageDto> result = new ArrayList<>(rows.size());
        for (PackageRow row : rows) {
            result.add(toPackageDto(row, false));
        }
        return List.copyOf(result);
    }

    public ExtensionDtos.ExtensionPackageDto getPackage(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID packageId
    ) {
        accessHelper.requireCompanyRead(principal, companyId);
        return toPackageDto(requirePackageRow(companyId, packageId, false), true);
    }

    public ExtensionDtos.ExtensionPackageDto createDraftPackage(
        MoneyCraftPrincipal actor,
        UUID companyId,
        ExtensionDtos.CreateExtensionPackageRequest request
    ) {
        authorityGuard.assertWritable();
        accessHelper.requireExtensionAuthor(actor, companyId);
        ExtensionDtos.ExtensionPackageDefinitionDto def = normalizeIncomingDefinition(request);

        return businessTxTemplate.execute(tx -> {
            accessHelper.ensurePrincipalProjected(actor);
            UUID pkgId = persistDraftPackageInCurrentTx(actor, companyId, def);
            return toPackageDto(requirePackageRow(companyId, pkgId, false), true);
        });
    }

    public UUID persistDraftPackageInCurrentTx(
        MoneyCraftPrincipal actor,
        UUID companyId,
        ExtensionDtos.ExtensionPackageDefinitionDto def
    ) {
        Integer maxRev = businessJdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(revision_no), 0)
              FROM mc_extension_package
             WHERE company_id = ? AND namespace = ? AND extension_uuid = ?
            """,
            Integer.class,
            companyId,
            def.namespace(),
            def.extensionUuid()
        );
        int nextRev = (maxRev == null ? 0 : maxRev) + 1;

        String rawJson = toJson(def);
        byte[] canonicalBytes = jcsCanonicalizer.canonicalize(rawJson.getBytes(StandardCharsets.UTF_8));
        byte[] sha256 = jcsCanonicalizer.sha256(canonicalBytes);
        byte[] aad = buildPackageAad(companyId, def.namespace(), def.extensionUuid(), nextRev);
        byte[] cipherGcm = tinkCryptoService.encryptGcm(aad, canonicalBytes);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("customFieldCount", def.customFields().size());
        summary.put("uiAnchorCount", def.uiAnchors().size());
        summary.put("feelRuleCount", def.feelRules().size());
        summary.put("decisionTableCount", def.decisionTables().size());
        summary.put("reportCount", def.reports().size());
        summary.put("printLayoutCount", def.printLayouts().size());
        summary.put("connectorRefCount", def.connectorRefs().size());

        UUID id = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_extension_package (
                id, company_id, namespace, extension_uuid, display_name,
                revision_no, schema_dialect, definition_cipher_gcm, definition_sha256,
                summary_json, validation_diagnostics_json, status, version_no, created_by
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?::jsonb, '[]'::jsonb, 'DRAFT', 1, ?
            )
            """,
            id,
            companyId,
            def.namespace(),
            def.extensionUuid(),
            def.displayName(),
            nextRev,
            def.schemaDialect(),
            cipherGcm,
            sha256,
            toJson(summary),
            actor.principalId()
        );

        auditChainService.appendCompanyEvent(
            companyId,
            actor.principalId(),
            actor.kind(),
            "EXTENSION_DRAFT_CREATE",
            "EXTENSION_PACKAGE",
            id.toString(),
            "SUCCESS",
            null,
            Map.of(
                "namespace", def.namespace(),
                "extensionUuid", def.extensionUuid().toString(),
                "revisionNo", nextRev,
                "sha256", JcsCanonicalizer.toHex(sha256)
            )
        );
        return id;
    }

    public ExtensionDtos.ExtensionPackageDto validatePackage(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID packageId
    ) {
        authorityGuard.assertWritable();
        accessHelper.requireExtensionAuthor(actor, companyId);

        return businessTxTemplate.execute(tx -> {
            accessHelper.ensurePrincipalProjected(actor);
            PackageRow row = requirePackageRow(companyId, packageId, true);
            if ("RETIRED".equals(row.status())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-EXT-PACKAGE-RETIRED",
                    "Extension Revision Retired",
                    "Retired extension package " + packageId + " cannot be validated."
                );
            }
            ExtensionDtos.ExtensionPackageDefinitionDto def = decryptDefinition(row);
            List<ExtensionDtos.ValidationDiagnosticDto> diagnostics = runStaticPackageChecks(companyId, def);

            boolean hasErrors = diagnostics.stream().anyMatch(d -> "ERROR".equals(d.severity()));
            if (hasErrors) {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_extension_package
                       SET validation_diagnostics_json = ?::jsonb,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    toJson(diagnostics),
                    companyId,
                    packageId
                );
                ExtensionDtos.ValidationDiagnosticDto firstError = diagnostics.stream()
                    .filter(d -> "ERROR".equals(d.severity()))
                    .findFirst()
                    .orElseThrow();
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    firstError.code(),
                    "Extension Package Validation Failed",
                    firstError.location() + ": " + firstError.message()
                );
            }

            if (!"VALIDATED".equals(row.status())) {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_extension_package
                       SET status = 'VALIDATED',
                           validation_diagnostics_json = ?::jsonb,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    toJson(diagnostics),
                    companyId,
                    packageId
                );
            }

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "EXTENSION_VALIDATE",
                "EXTENSION_PACKAGE",
                packageId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "namespace", row.namespace(),
                    "revisionNo", row.revisionNo(),
                    "sha256", JcsCanonicalizer.toHex(row.definitionSha256())
                )
            );
            return toPackageDto(requirePackageRow(companyId, packageId, false), true);
        });
    }

    public ExtensionDtos.ExtensionBindingDto activatePackage(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID packageId,
        String ifMatchHeader
    ) {
        authorityGuard.assertWritable();
        accessHelper.requireExtensionManage(actor, companyId);

        return businessTxTemplate.execute(tx -> {
            accessHelper.ensurePrincipalProjected(actor);
            PackageRow pkg = requirePackageRow(companyId, packageId, true);
            if (!"VALIDATED".equals(pkg.status())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-NOT-VALIDATED",
                    "Extension Revision Must Be Validated Before Activation",
                    "Validate extension package " + packageId + " before company activation."
                );
            }

            ExtensionDtos.ExtensionPackageDefinitionDto def = decryptDefinition(pkg);
            assertNoActiveAnchorConflicts(companyId, pkg.extensionUuid(), def.uiAnchors());

            for (ExtensionDtos.UpsertCustomFieldDefRequest cf : def.customFields()) {
                ExtensionDtos.UpsertCustomFieldDefRequest scoped = new ExtensionDtos.UpsertCustomFieldDefRequest(
                    cf.namespace() == null || cf.namespace().isBlank() ? pkg.namespace() : cf.namespace(),
                    cf.fieldUuid(),
                    cf.ownerEntityType(),
                    cf.fieldCode(),
                    cf.label(),
                    cf.dataType(),
                    cf.decimalScale(),
                    cf.enumOptions(),
                    cf.defaultValue(),
                    cf.protectionClass(),
                    cf.uiAnchor(),
                    cf.requiredFlag()
                );
                customFieldService.upsertNormalizedInCurrentTx(actor, companyId, packageId, scoped);
            }

            List<ExtensionDtos.ExtensionBindingDto> existingBindings = queryBindingByExtensionUuid(
                companyId, pkg.extensionUuid(), true
            );
            UUID bindingId;
            if (existingBindings.isEmpty()) {
                bindingId = UUID.randomUUID();
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_extension_company_binding (
                        id, company_id, namespace, extension_uuid, extension_package_id,
                        bound_definition_sha256, state, activated_by, activated_at, version_no
                    ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, clock_timestamp(), 1)
                    """,
                    bindingId,
                    companyId,
                    pkg.namespace(),
                    pkg.extensionUuid(),
                    packageId,
                    pkg.definitionSha256(),
                    actor.principalId()
                );
            } else {
                ExtensionDtos.ExtensionBindingDto cur = existingBindings.getFirst();
                if (ifMatchHeader != null && !ifMatchHeader.isBlank()) {
                    long expected = CompanyMasterSequenceHelper.requireIfMatchVersion(ifMatchHeader, null);
                    CompanyMasterSequenceHelper.assertExpectedVersion(expected, cur.versionNo(), "extensionBinding");
                }
                bindingId = cur.id();
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_extension_company_binding
                       SET extension_package_id = ?,
                           bound_definition_sha256 = ?,
                           state = 'ACTIVE',
                           activated_by = ?,
                           activated_at = clock_timestamp(),
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    packageId,
                    pkg.definitionSha256(),
                    actor.principalId(),
                    companyId,
                    bindingId
                );
            }

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "EXTENSION_ACTIVATE",
                "EXTENSION_BINDING",
                bindingId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "extensionPackageId", packageId.toString(),
                    "namespace", pkg.namespace(),
                    "revisionNo", pkg.revisionNo(),
                    "boundSha256", JcsCanonicalizer.toHex(pkg.definitionSha256())
                )
            );

            return queryBindingByExtensionUuid(companyId, pkg.extensionUuid(), false).getFirst();
        });
    }

    public ExtensionDtos.ExtensionBindingDto suspendPackage(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID packageId,
        String ifMatchHeader
    ) {
        authorityGuard.assertWritable();
        accessHelper.requireExtensionManage(actor, companyId);

        return businessTxTemplate.execute(tx -> {
            accessHelper.ensurePrincipalProjected(actor);
            PackageRow pkg = requirePackageRow(companyId, packageId, false);
            List<ExtensionDtos.ExtensionBindingDto> existingBindings = queryBindingByExtensionUuid(
                companyId, pkg.extensionUuid(), true
            );
            if (existingBindings.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-EXT-BINDING-NOT-FOUND",
                    "Extension Binding Not Found",
                    "Extension " + pkg.extensionUuid() + " has not been bound on company " + companyId + "."
                );
            }
            ExtensionDtos.ExtensionBindingDto cur = existingBindings.getFirst();
            if (ifMatchHeader != null && !ifMatchHeader.isBlank()) {
                long expected = CompanyMasterSequenceHelper.requireIfMatchVersion(ifMatchHeader, null);
                CompanyMasterSequenceHelper.assertExpectedVersion(expected, cur.versionNo(), "extensionBinding");
            }
            businessJdbcTemplate.update(
                """
                UPDATE mc_extension_company_binding
                   SET state = 'SUSPENDED',
                       suspended_by = ?,
                       suspended_at = clock_timestamp(),
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                actor.principalId(),
                companyId,
                cur.id()
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "EXTENSION_SUSPEND",
                "EXTENSION_BINDING",
                cur.id().toString(),
                "SUCCESS",
                null,
                Map.of(
                    "extensionPackageId", packageId.toString(),
                    "namespace", pkg.namespace()
                )
            );
            return queryBindingByExtensionUuid(companyId, pkg.extensionUuid(), false).getFirst();
        });
    }

    public List<ExtensionDtos.ExtensionBindingDto> listBindings(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        accessHelper.requireCompanyRead(principal, companyId);
        return businessJdbcTemplate.query(
            """
            SELECT b.id, b.company_id, b.namespace, b.extension_uuid, b.extension_package_id,
                   p.revision_no, p.display_name, b.bound_definition_sha256, b.state,
                   b.activated_by, b.activated_at, b.suspended_by, b.suspended_at, b.version_no
              FROM mc_extension_company_binding b
              JOIN mc_extension_package p
                ON p.company_id = b.company_id AND p.id = b.extension_package_id
             WHERE b.company_id = ?
             ORDER BY b.namespace ASC, p.display_name ASC
            """,
            (rs, rowNum) -> mapBindingRow(rs),
            companyId
        );
    }

    public ExtensionDtos.EvaluatePreviewResponse evaluatePreview(
        MoneyCraftPrincipal principal,
        UUID companyId,
        ExtensionDtos.EvaluatePreviewRequest request
    ) {
        accessHelper.requireExtensionAuthor(principal, companyId);
        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXT-PREVIEW-EMPTY",
                "Preview Request Required",
                "Provide a FEEL expression or DMN decisionTable to evaluate."
            );
        }
        Map<String, Object> safeInputs = sanitizePreviewInputs(request.inputs());
        if (request.decisionTable() != null) {
            BoundedFeelRuleEvaluator.DecisionTableOutcome out =
                feelEvaluator.evaluateDecisionTable(request.decisionTable(), safeInputs);
            return new ExtensionDtos.EvaluatePreviewResponse(
                !"BLOCK".equals(out.outcome()),
                "DECISION_TABLE",
                out.outcome(),
                out.matchedRuleIds(),
                out.outcome(),
                out.messages(),
                out.astDepth(),
                out.stepsConsumed()
            );
        }
        BoundedFeelRuleEvaluator.EvaluationOutcome eval =
            feelEvaluator.evaluateExpression(request.expression(), safeInputs);
        String formatted = BoundedFeelRuleEvaluator.formatTypedResult(eval.value());
        String type = BoundedFeelRuleEvaluator.describeResultType(eval.value());
        String outcome = Boolean.FALSE.equals(eval.value()) ? "BLOCK" : "ALLOW";
        return new ExtensionDtos.EvaluatePreviewResponse(
            true,
            type,
            formatted,
            List.of(),
            outcome,
            List.of(),
            eval.astDepth(),
            eval.stepsConsumed()
        );
    }

    @Override
    public void validateVoucher(VoucherExtensionContext context) {
        List<String> blockingErrors = evaluateActiveVoucherRules(context, true);
        if (!blockingErrors.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-VOUCHER-RULE-BLOCKED",
                "Active Extension Validation Rule Blocked Voucher",
                String.join("; ", blockingErrors)
            );
        }
    }

    public List<String> evaluateDryRunVoucherBlockingMessages(VoucherExtensionContext context) {
        return evaluateActiveVoucherRules(context, false);
    }

    private List<String> evaluateActiveVoucherRules(VoucherExtensionContext context, boolean recordWarnings) {
        List<PackageRow> activePackages = businessJdbcTemplate.query(
            """
            SELECT p.id, p.company_id, p.namespace, p.extension_uuid, p.display_name,
                   p.revision_no, p.schema_dialect, p.definition_cipher_gcm, p.definition_sha256,
                   p.summary_json, p.validation_diagnostics_json, p.status,
                   p.version_no, p.created_by, p.created_at,
                   b.state AS binding_state, b.extension_package_id AS bound_pkg_id
              FROM mc_extension_company_binding b
              JOIN mc_extension_package p
                ON p.company_id = b.company_id AND p.id = b.extension_package_id
             WHERE b.company_id = ? AND b.state = 'ACTIVE'
             ORDER BY b.namespace ASC, p.revision_no ASC
            """,
            (rs, rowNum) -> mapPackageRow(rs),
            context.companyId()
        );
        if (activePackages.isEmpty()) {
            return List.of();
        }

        Map<String, Object> evalCtx = buildVoucherEvaluationContext(context);
        List<String> blockingMessages = new ArrayList<>();
        List<String> warningMessages = new ArrayList<>();

        for (PackageRow pkg : activePackages) {
            ExtensionDtos.ExtensionPackageDefinitionDto def = decryptDefinition(pkg);

            List<ExtensionDtos.FeelRuleSpecDto> sortedRules = new ArrayList<>(def.feelRules());
            sortedRules.sort(Comparator.comparingInt(r -> r.orderNo() == null ? 100 : r.orderNo()));

            for (ExtensionDtos.FeelRuleSpecDto rule : sortedRules) {
                String target = rule.targetEntityType() == null
                    ? "VOUCHER"
                    : rule.targetEntityType().trim().toUpperCase(Locale.ROOT);
                if (!"VOUCHER".equals(target) && !"JOURNAL".equals(target)) {
                    continue;
                }
                BoundedFeelRuleEvaluator.EvaluationOutcome out =
                    feelEvaluator.evaluateExpression(rule.conditionExpression(), evalCtx);
                boolean passed = Boolean.TRUE.equals(out.value());
                if (!passed) {
                    String severity = rule.severity() == null
                        ? "BLOCKING"
                        : rule.severity().trim().toUpperCase(Locale.ROOT);
                    String msg = (rule.errorMessage() != null && !rule.errorMessage().isBlank())
                        ? rule.errorMessage().trim()
                        : ("Rule '" + rule.ruleCode() + "' failed on voucher");
                    if ("WARNING".equals(severity) || "WARN".equals(severity)) {
                        warningMessages.add("[" + pkg.namespace() + ":" + rule.ruleCode() + "] " + msg);
                    } else {
                        blockingMessages.add("[" + pkg.namespace() + ":" + rule.ruleCode() + "] " + msg);
                    }
                }
            }

            for (ExtensionDtos.DmnDecisionTableDto table : def.decisionTables()) {
                String target = table.targetEntityType() == null
                    ? "VOUCHER"
                    : table.targetEntityType().trim().toUpperCase(Locale.ROOT);
                if (!"VOUCHER".equals(target) && !"JOURNAL".equals(target)) {
                    continue;
                }
                BoundedFeelRuleEvaluator.DecisionTableOutcome dmnOut =
                    feelEvaluator.evaluateDecisionTable(table, evalCtx);
                if ("BLOCK".equals(dmnOut.outcome())) {
                    String detail = dmnOut.messages().isEmpty()
                        ? ("Decision table '" + table.tableCode() + "' blocked voucher via " + dmnOut.matchedRuleIds())
                        : String.join("; ", dmnOut.messages());
                    blockingMessages.add("[" + pkg.namespace() + ":" + table.tableCode() + "] " + detail);
                } else if ("WARN".equals(dmnOut.outcome()) && !dmnOut.messages().isEmpty()) {
                    warningMessages.add("[" + pkg.namespace() + ":" + table.tableCode() + "] "
                        + String.join("; ", dmnOut.messages()));
                }
            }
        }

        if (recordWarnings && !warningMessages.isEmpty() && blockingMessages.isEmpty() && context.actor() != null) {
            auditChainService.appendCompanyEvent(
                context.companyId(),
                context.actor().principalId(),
                context.actor().kind(),
                "EXTENSION_VOUCHER_RULE_WARNING",
                "VOUCHER",
                context.voucherId() == null ? "DRY_RUN" : context.voucherId().toString(),
                "SUCCESS",
                null,
                Map.of("warningCount", warningMessages.size())
            );
        }

        return List.copyOf(blockingMessages);
    }

    private Map<String, Object> buildVoucherEvaluationContext(VoucherExtensionContext context) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("voucherType", context.voucherTypeName() == null ? "" : context.voucherTypeName());
        map.put("voucherCategory", context.voucherTypeCategory() == null ? "" : context.voucherTypeCategory());
        map.put("status", context.status() == null ? "" : context.status());
        map.put("postingNature", context.postingNature() == null ? "" : context.postingNature());
        map.put("voucherDate", context.voucherDate() == null ? LocalDate.of(2025, 4, 1) : context.voucherDate());
        map.put("effectiveDate", context.effectiveDate() == null ? LocalDate.of(2025, 4, 1) : context.effectiveDate());
        map.put("voucherNumber", context.voucherNumber() == null ? "" : context.voucherNumber());
        map.put("supplierInvoiceNo", context.supplierInvoiceNo() == null ? "" : context.supplierInvoiceNo());
        map.put("narration", context.narration() == null ? "" : context.narration());
        map.put("currencyCode", context.currencyCode() == null ? "INR" : context.currencyCode());
        map.put("totalDebit", context.totalDebit() == null ? BigDecimal.ZERO : context.totalDebit());
        map.put("totalCredit", context.totalCredit() == null ? BigDecimal.ZERO : context.totalCredit());
        map.put("amount", context.totalDebit() == null ? BigDecimal.ZERO : context.totalDebit());
        map.put("lineCount", BigDecimal.valueOf(context.lineCount()));
        map.put("origin", context.origin() == null ? "LIVE" : context.origin());
        map.put("ledgerNames", context.ledgerNames() == null ? List.of() : context.ledgerNames());
        Map<String, Object> customStd = customFieldService.loadStandardValuesForRuleEvaluation(
            context.companyId(), "VOUCHER", context.voucherId()
        );
        map.put("custom", customStd);
        return map;
    }

    private List<ExtensionDtos.ValidationDiagnosticDto> runStaticPackageChecks(
        UUID companyId,
        ExtensionDtos.ExtensionPackageDefinitionDto def
    ) {
        List<ExtensionDtos.ValidationDiagnosticDto> diags = new ArrayList<>();

        if (!ExtensionDtos.JSON_SCHEMA_2020_12.equals(def.schemaDialect())) {
            diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                "ERROR",
                "MC-EXT-SCHEMA-DIALECT-UNSUPPORTED",
                "$.schemaDialect",
                "Extension schemaDialect must be '" + ExtensionDtos.JSON_SCHEMA_2020_12 + "'."
            ));
        }

        Set<String> privateFieldCodes = new HashSet<>();
        for (int i = 0; i < def.customFields().size(); i++) {
            ExtensionDtos.UpsertCustomFieldDefRequest cf = def.customFields().get(i);
            try {
                CustomFieldService.NormalizedFieldSpec norm = customFieldService.validateAndNormalizeSpec(
                    new ExtensionDtos.UpsertCustomFieldDefRequest(
                        cf.namespace() == null || cf.namespace().isBlank() ? def.namespace() : cf.namespace(),
                        cf.fieldUuid(),
                        cf.ownerEntityType(),
                        cf.fieldCode(),
                        cf.label(),
                        cf.dataType(),
                        cf.decimalScale(),
                        cf.enumOptions(),
                        cf.defaultValue(),
                        cf.protectionClass(),
                        cf.uiAnchor(),
                        cf.requiredFlag()
                    )
                );
                if ("SELECTED_PRIVATE".equals(norm.protectionClass())) {
                    privateFieldCodes.add(norm.fieldCode().toLowerCase(Locale.ROOT));
                }
            } catch (MoneyCraftException ex) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    ex.code(),
                    "$.customFields[" + i + "]",
                    ex.getMessage()
                ));
            }
        }

        Set<String> seenScreenLetters = new HashSet<>();
        for (int i = 0; i < def.uiAnchors().size(); i++) {
            ExtensionDtos.UiAnchorCustomizationDto anchor = def.uiAnchors().get(i);
            if (anchor.anchorId() == null || anchor.anchorId().isBlank()) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    "MC-EXT-ANCHOR-ID-REQUIRED",
                    "$.uiAnchors[" + i + "]",
                    "anchorId is required."
                ));
            } else if (anchor.anchorId().contains("<") || anchor.anchorId().contains(">")) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    "MC-EXT-ANCHOR-DOM-FORBIDDEN",
                    "$.uiAnchors[" + i + "].anchorId",
                    "Arbitrary HTML or DOM selector syntax is forbidden in UI anchors."
                ));
            }
            if (anchor.actionLetterPair() != null && !anchor.actionLetterPair().isBlank()) {
                var matcher = ACTION_PAIR_PATTERN.matcher(anchor.actionLetterPair().trim());
                if (!matcher.matches()
                    || !matcher.group(1).equalsIgnoreCase(matcher.group(2))) {
                    diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                        "ERROR",
                        "MC-EXT-KEY-PAIR-INVALID",
                        "$.uiAnchors[" + i + "].actionLetterPair",
                        "KEY-001 actionLetterPair must declare matching upper/lower ASCII letters (e.g. 'K/k')."
                    ));
                } else {
                    String keyScope = (anchor.targetScreen() == null ? "GLOBAL" : anchor.targetScreen())
                        + ":" + matcher.group(1);
                    if (!seenScreenLetters.add(keyScope)) {
                        diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                            "ERROR",
                            "MC-EXT-KEY-COLLISION",
                            "$.uiAnchors[" + i + "].actionLetterPair",
                            "Duplicate KEY-001 action letter '" + anchor.actionLetterPair() + "' in target screen."
                        ));
                    }
                }
            }
        }

        for (int i = 0; i < def.feelRules().size(); i++) {
            ExtensionDtos.FeelRuleSpecDto rule = def.feelRules().get(i);
            try {
                feelEvaluator.compileAndValidate(rule.conditionExpression());
                for (String privCode : privateFieldCodes) {
                    if (rule.conditionExpression().toLowerCase(Locale.ROOT).contains(privCode)) {
                        diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                            "ERROR",
                            "MC-EXT-PRIVATE-INFERENCE-FORBIDDEN",
                            "$.feelRules[" + i + "].conditionExpression",
                            "Ordinary designer FEEL expressions cannot depend on SELECTED_PRIVATE field '"
                                + privCode + "'."
                        ));
                    }
                }
            } catch (MoneyCraftException ex) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    ex.code(),
                    "$.feelRules[" + i + "]",
                    ex.getMessage()
                ));
            }
        }

        for (int i = 0; i < def.decisionTables().size(); i++) {
            try {
                feelEvaluator.validateDecisionTableStructure(def.decisionTables().get(i));
            } catch (MoneyCraftException ex) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    ex.code(),
                    "$.decisionTables[" + i + "]",
                    ex.getMessage()
                ));
            }
        }

        for (int i = 0; i < def.reports().size(); i++) {
            ExtensionDtos.ExtensionReportDefDto rep = def.reports().get(i);
            String proj = rep.baseProjection() == null ? "" : rep.baseProjection().trim().toUpperCase(Locale.ROOT);
            if (!ADMITTED_PROJECTIONS.contains(proj)) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    "MC-EXT-REPORT-PROJECTION-UNSUPPORTED",
                    "$.reports[" + i + "].baseProjection",
                    "Report baseProjection must be one of " + ADMITTED_PROJECTIONS + "."
                ));
            }
        }

        for (int i = 0; i < def.printLayouts().size(); i++) {
            ExtensionDtos.ExtensionPrintLayoutDto layout = def.printLayouts().get(i);
            String prof = layout.documentProfile() == null
                ? ""
                : layout.documentProfile().trim().toUpperCase(Locale.ROOT);
            if (!ADMITTED_PRINT_PROFILES.contains(prof)) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    "MC-EXT-PRINT-PROFILE-UNSUPPORTED",
                    "$.printLayouts[" + i + "].documentProfile",
                    "Print layout documentProfile must be one of " + ADMITTED_PRINT_PROFILES + "."
                ));
            }
            String combinedText = ((layout.headerTitle() == null ? "" : layout.headerTitle())
                + " " + (layout.termsText() == null ? "" : layout.termsText())).toLowerCase(Locale.ROOT);
            if (combinedText.contains("<script")
                || combinedText.contains("http://")
                || combinedText.contains("https://")
                || combinedText.contains("javascript:")) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    "MC-EXT-PRINT-UNSAFE-RESOURCE",
                    "$.printLayouts[" + i + "]",
                    "Executable markup and remote URLs are forbidden in trusted typed print layouts."
                ));
            }
        }

        for (int i = 0; i < def.connectorRefs().size(); i++) {
            ExtensionDtos.ExtensionConnectorRefDto conn = def.connectorRefs().get(i);
            String dir = conn.direction() == null ? "" : conn.direction().trim().toUpperCase(Locale.ROOT);
            if (!ADMITTED_CONNECTOR_DIRECTIONS.contains(dir)) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    "MC-EXT-CONNECTOR-DIRECTION-INVALID",
                    "$.connectorRefs[" + i + "].direction",
                    "Connector direction must be one of " + ADMITTED_CONNECTOR_DIRECTIONS + " (INBOUND_ODBC_MUTATION is not admitted)."
                ));
            }
            if (conn.secretRef() != null
                && (conn.secretRef().contains("password=")
                    || conn.secretRef().startsWith("Bearer ")
                    || conn.secretRef().contains("://"))) {
                diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                    "ERROR",
                    "MC-EXT-EMBEDDED-CREDENTIAL-FORBIDDEN",
                    "$.connectorRefs[" + i + "].secretRef",
                    "Extension packages must reference custodian secret aliases, never inline credentials or URLs."
                ));
            }
        }

        try {
            assertNoActiveAnchorConflicts(companyId, def.extensionUuid(), def.uiAnchors());
        } catch (MoneyCraftException ex) {
            diags.add(new ExtensionDtos.ValidationDiagnosticDto(
                "ERROR",
                ex.code(),
                "$.uiAnchors",
                ex.getMessage()
            ));
        }

        return List.copyOf(diags);
    }

    private void assertNoActiveAnchorConflicts(
        UUID companyId,
        UUID currentExtensionUuid,
        List<ExtensionDtos.UiAnchorCustomizationDto> candidateAnchors
    ) {
        if (candidateAnchors == null || candidateAnchors.isEmpty()) {
            return;
        }
        Set<String> candidateExclusiveIds = new HashSet<>();
        for (ExtensionDtos.UiAnchorCustomizationDto a : candidateAnchors) {
            if ("HIDE".equalsIgnoreCase(a.operation()) || "REORDER".equalsIgnoreCase(a.operation())) {
                candidateExclusiveIds.add(a.anchorId().trim().toLowerCase(Locale.ROOT));
            }
        }
        if (candidateExclusiveIds.isEmpty()) {
            return;
        }

        List<PackageRow> otherActive = businessJdbcTemplate.query(
            """
            SELECT p.id, p.company_id, p.namespace, p.extension_uuid, p.display_name,
                   p.revision_no, p.schema_dialect, p.definition_cipher_gcm, p.definition_sha256,
                   p.summary_json, p.validation_diagnostics_json, p.status,
                   p.version_no, p.created_by, p.created_at,
                   b.state AS binding_state, b.extension_package_id AS bound_pkg_id
              FROM mc_extension_company_binding b
              JOIN mc_extension_package p
                ON p.company_id = b.company_id AND p.id = b.extension_package_id
             WHERE b.company_id = ? AND b.state = 'ACTIVE' AND b.extension_uuid <> ?
            """,
            (rs, rowNum) -> mapPackageRow(rs),
            companyId,
            currentExtensionUuid
        );

        for (PackageRow other : otherActive) {
            ExtensionDtos.ExtensionPackageDefinitionDto otherDef = decryptDefinition(other);
            for (ExtensionDtos.UiAnchorCustomizationDto oa : otherDef.uiAnchors()) {
                if ("HIDE".equalsIgnoreCase(oa.operation()) || "REORDER".equalsIgnoreCase(oa.operation())) {
                    String idLower = oa.anchorId().trim().toLowerCase(Locale.ROOT);
                    if (candidateExclusiveIds.contains(idLower)) {
                        throw new MoneyCraftException(
                            HttpStatus.CONFLICT,
                            "MC-EXT-ANCHOR-CONFLICT",
                            "Conflicting UI Anchor Override",
                            "UI anchor '" + oa.anchorId() + "' is already overridden by active extension '"
                                + other.namespace() + "'."
                        );
                    }
                }
            }
        }
    }

    private ExtensionDtos.ExtensionPackageDefinitionDto normalizeIncomingDefinition(
        ExtensionDtos.CreateExtensionPackageRequest request
    ) {
        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXT-PACKAGE-REQUIRED",
                "Extension Package Payload Required",
                "Provide extension package attributes or rawDefinitionJson."
            );
        }
        if (request.rawDefinitionJson() != null && !request.rawDefinitionJson().isBlank()) {
            try {
                ExtensionDtos.ExtensionPackageDefinitionDto parsed = objectMapper.readValue(
                    request.rawDefinitionJson(),
                    ExtensionDtos.ExtensionPackageDefinitionDto.class
                );
                return sanitizeDefinitionRecord(parsed);
            } catch (JacksonException ex) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-JSON-INVALID",
                    "Invalid Extension Package JSON",
                    "Could not parse rawDefinitionJson: " + ex.getMessage()
                );
            }
        }
        ExtensionDtos.ExtensionPackageDefinitionDto built = new ExtensionDtos.ExtensionPackageDefinitionDto(
            request.schemaDialect() == null || request.schemaDialect().isBlank()
                ? ExtensionDtos.JSON_SCHEMA_2020_12
                : request.schemaDialect().trim(),
            "1.0",
            request.namespace(),
            request.extensionUuid(),
            request.displayName(),
            request.targetProfiles(),
            request.customFields(),
            request.uiAnchors(),
            request.feelRules(),
            request.decisionTables(),
            request.reports(),
            request.printLayouts(),
            request.connectorRefs()
        );
        return sanitizeDefinitionRecord(built);
    }

    private static ExtensionDtos.ExtensionPackageDefinitionDto sanitizeDefinitionRecord(
        ExtensionDtos.ExtensionPackageDefinitionDto raw
    ) {
        String ns = raw.namespace() == null ? "" : raw.namespace().trim().toLowerCase(Locale.ROOT);
        if (!NAMESPACE_PATTERN.matcher(ns).matches()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-NAMESPACE-INVALID",
                "Invalid Extension Namespace",
                "Namespace '" + ns + "' must match ^[a-z0-9][a-z0-9._-]{1,95}$."
            );
        }
        UUID extUuid = raw.extensionUuid() == null ? UUID.randomUUID() : raw.extensionUuid();
        String displayName = raw.displayName() == null || raw.displayName().isBlank()
            ? ns
            : raw.displayName().trim();
        String dialect = raw.schemaDialect() == null || raw.schemaDialect().isBlank()
            ? ExtensionDtos.JSON_SCHEMA_2020_12
            : raw.schemaDialect().trim();

        return new ExtensionDtos.ExtensionPackageDefinitionDto(
            dialect,
            raw.contractVersion() == null || raw.contractVersion().isBlank() ? "1.0" : raw.contractVersion().trim(),
            ns,
            extUuid,
            displayName,
            raw.targetProfiles() == null ? List.of("VOUCHER", "LEDGER") : List.copyOf(raw.targetProfiles()),
            raw.customFields() == null ? List.of() : List.copyOf(raw.customFields()),
            raw.uiAnchors() == null ? List.of() : List.copyOf(raw.uiAnchors()),
            raw.feelRules() == null ? List.of() : List.copyOf(raw.feelRules()),
            raw.decisionTables() == null ? List.of() : List.copyOf(raw.decisionTables()),
            raw.reports() == null ? List.of() : List.copyOf(raw.reports()),
            raw.printLayouts() == null ? List.of() : List.copyOf(raw.printLayouts()),
            raw.connectorRefs() == null ? List.of() : List.copyOf(raw.connectorRefs())
        );
    }

    private Map<String, Object> sanitizePreviewInputs(Map<String, Object> rawInputs) {
        if (rawInputs == null || rawInputs.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawInputs.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof BigDecimal || val instanceof Boolean || val instanceof LocalDate || val == null) {
                clean.put(entry.getKey(), val);
            } else if (val instanceof Integer i) {
                clean.put(entry.getKey(), BigDecimal.valueOf(i.longValue()));
            } else if (val instanceof Long l) {
                clean.put(entry.getKey(), BigDecimal.valueOf(l));
            } else if (val instanceof Number) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FEEL-FLOAT-FORBIDDEN",
                    "Floating-Point Preview Input Rejected",
                    "Supply monetary/quantity preview inputs as exact decimal strings, never JSON floats."
                );
            } else if (val instanceof String s) {
                clean.put(entry.getKey(), s);
            } else if (val instanceof List<?> list) {
                clean.put(entry.getKey(), list);
            } else {
                clean.put(entry.getKey(), val.toString());
            }
        }
        return clean;
    }

    private ExtensionDtos.ExtensionPackageDefinitionDto decryptDefinition(PackageRow row) {
        byte[] aad = buildPackageAad(row.companyId(), row.namespace(), row.extensionUuid(), row.revisionNo());
        byte[] plainBytes = tinkCryptoService.decryptGcm(aad, row.definitionCipherGcm());
        byte[] actualSha = jcsCanonicalizer.sha256(plainBytes);
        if (!java.security.MessageDigest.isEqual(actualSha, row.definitionSha256())) {
            throw new MoneyCraftException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MC-EXT-DIGEST-MISMATCH",
                "Extension Definition Digest Integrity Failure",
                "Decrypted extension definition digest does not match recorded SHA-256."
            );
        }
        try {
            return objectMapper.readValue(plainBytes, ExtensionDtos.ExtensionPackageDefinitionDto.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize extension definition", ex);
        }
    }

    private PackageRow requirePackageRow(UUID companyId, UUID packageId, boolean lockForUpdate) {
        String sql = """
            SELECT p.id, p.company_id, p.namespace, p.extension_uuid, p.display_name,
                   p.revision_no, p.schema_dialect, p.definition_cipher_gcm, p.definition_sha256,
                   p.summary_json, p.validation_diagnostics_json, p.status,
                   p.version_no, p.created_by, p.created_at,
                   b.state AS binding_state, b.extension_package_id AS bound_pkg_id
              FROM mc_extension_package p
              LEFT JOIN mc_extension_company_binding b
                ON b.company_id = p.company_id AND b.extension_uuid = p.extension_uuid
             WHERE p.company_id = ? AND p.id = ?
            """ + (lockForUpdate ? " FOR UPDATE OF p" : "");
        List<PackageRow> rows = businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> mapPackageRow(rs),
            companyId,
            packageId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-EXT-PACKAGE-NOT-FOUND",
                "Extension Package Not Found",
                "Extension package " + packageId + " was not found in company " + companyId + "."
            );
        }
        return rows.getFirst();
    }

    private List<ExtensionDtos.ExtensionBindingDto> queryBindingByExtensionUuid(
        UUID companyId,
        UUID extensionUuid,
        boolean lockForUpdate
    ) {
        String sql = """
            SELECT b.id, b.company_id, b.namespace, b.extension_uuid, b.extension_package_id,
                   p.revision_no, p.display_name, b.bound_definition_sha256, b.state,
                   b.activated_by, b.activated_at, b.suspended_by, b.suspended_at, b.version_no
              FROM mc_extension_company_binding b
              JOIN mc_extension_package p
                ON p.company_id = b.company_id AND p.id = b.extension_package_id
             WHERE b.company_id = ? AND b.extension_uuid = ?
            """ + (lockForUpdate ? " FOR UPDATE OF b" : "");
        return businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> mapBindingRow(rs),
            companyId,
            extensionUuid
        );
    }

    private ExtensionDtos.ExtensionPackageDto toPackageDto(PackageRow row, boolean includeDefinition) {
        String effectiveBinding = "INACTIVE";
        if (row.bindingState() != null && row.id().equals(row.boundPackageId())) {
            effectiveBinding = row.bindingState();
        }
        ExtensionDtos.ExtensionPackageDefinitionDto def = includeDefinition ? decryptDefinition(row) : null;
        return new ExtensionDtos.ExtensionPackageDto(
            row.id(),
            row.companyId(),
            row.namespace(),
            row.extensionUuid(),
            row.displayName(),
            row.revisionNo(),
            row.schemaDialect(),
            JcsCanonicalizer.toHex(row.definitionSha256()),
            row.status(),
            effectiveBinding,
            parseObjectMap(row.summaryJson()),
            parseDiagnostics(row.diagnosticsJson()),
            def,
            row.versionNo(),
            row.createdBy(),
            row.createdAt()
        );
    }

    private static PackageRow mapPackageRow(ResultSet rs) throws SQLException {
        return new PackageRow(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("namespace"),
            rs.getObject("extension_uuid", UUID.class),
            rs.getString("display_name"),
            rs.getInt("revision_no"),
            rs.getString("schema_dialect"),
            rs.getBytes("definition_cipher_gcm"),
            rs.getBytes("definition_sha256"),
            rs.getString("summary_json"),
            rs.getString("validation_diagnostics_json"),
            rs.getString("status"),
            rs.getLong("version_no"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("binding_state"),
            rs.getObject("bound_pkg_id", UUID.class)
        );
    }

    private static ExtensionDtos.ExtensionBindingDto mapBindingRow(ResultSet rs) throws SQLException {
        Timestamp actTs = rs.getTimestamp("activated_at");
        Timestamp suspTs = rs.getTimestamp("suspended_at");
        return new ExtensionDtos.ExtensionBindingDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("namespace"),
            rs.getObject("extension_uuid", UUID.class),
            rs.getObject("extension_package_id", UUID.class),
            rs.getInt("revision_no"),
            rs.getString("display_name"),
            JcsCanonicalizer.toHex(rs.getBytes("bound_definition_sha256")),
            rs.getString("state"),
            rs.getObject("activated_by", UUID.class),
            actTs == null ? null : actTs.toInstant(),
            rs.getObject("suspended_by", UUID.class),
            suspTs == null ? null : suspTs.toInstant(),
            rs.getLong("version_no")
        );
    }

    private static byte[] buildPackageAad(UUID companyId, String namespace, UUID extensionUuid, int revisionNo) {
        String aad = "mc_extension_package|" + companyId + "|" + namespace + "|" + extensionUuid + "|" + revisionNo;
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> parseObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JacksonException ignored) {
            return Map.of();
        }
    }

    private List<ExtensionDtos.ValidationDiagnosticDto> parseDiagnostics(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                json,
                new TypeReference<List<ExtensionDtos.ValidationDiagnosticDto>>() { }
            );
        } catch (JacksonException ignored) {
            return List.of();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize extension JSON", ex);
        }
    }

    private record PackageRow(
        UUID id,
        UUID companyId,
        String namespace,
        UUID extensionUuid,
        String displayName,
        int revisionNo,
        String schemaDialect,
        byte[] definitionCipherGcm,
        byte[] definitionSha256,
        String summaryJson,
        String diagnosticsJson,
        String status,
        long versionNo,
        UUID createdBy,
        java.time.Instant createdAt,
        String bindingState,
        UUID boundPackageId
    ) {
    }
}
