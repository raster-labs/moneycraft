package com.raster.moneycraft.exchange.service;

import com.raster.moneycraft.accounting.dto.LedgerDtos;
import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.accounting.service.LedgerService;
import com.raster.moneycraft.accounting.service.VoucherService;
import com.raster.moneycraft.accounting.spi.VoucherExtensionValidator;
import com.raster.moneycraft.exchange.dto.SpreadsheetImportDtos;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.security.SystemRole;
import com.raster.moneycraft.ops.connector.OutboundConnectorAdapter;
import com.raster.moneycraft.ops.connector.OutboundConnectorRegistry;
import com.raster.moneycraft.ops.connector.StatutoryConnectorHttpAdapter;
import com.raster.moneycraft.ops.connector.StatutoryHttpResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Increment X Guided Multi-Format Columnar Spreadsheet Importer (SHEET-001..008).
 * Enforces Tink-encrypted frozen snapshots, unlimited horizontal multi-ledger voucher mapping,
 * 1900/1904 epoch & fictitious 1900-02-29 rejection, explicit cached-formula consent,
 * per-unit LIVE atomic execution via {@link VoucherService} / {@link LedgerService},
 * checkpointed resume/cancel, and OWASP CSV-injection-safe error export.
 */
@Service
public class GuidedSpreadsheetImportService {

    public static final String PERM_IMPORT_PREPARE = "import.prepare";
    public static final String PERM_IMPORT_READ = "import.read";
    public static final String PERM_IMPORT_COMMIT = "import.commit";
    public static final String PERM_IMPORT_TEMPLATE_MANAGE = "import.template.manage";

    private static final Set<CompanyRole> PREPARE_AND_COMMIT_ROLES = Set.of(
        CompanyRole.COMPANY_ADMIN,
        CompanyRole.SENIOR_ACCOUNTANT,
        CompanyRole.DATA_ENTRY_OPERATOR
    );

    private static final Set<CompanyRole> TEMPLATE_MANAGE_ROLES = Set.of(
        CompanyRole.COMPANY_ADMIN,
        CompanyRole.SENIOR_ACCOUNTANT
    );

    private static final Set<String> VALID_VOUCHER_ACTIONS = Set.of(
        "SAVE_DRAFT", "SUBMIT_FOR_APPROVAL", "POST"
    );

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final SpreadsheetFormatParser formatParser;
    private final VoucherService voucherService;
    private final LedgerService ledgerService;
    private final OutboundConnectorRegistry connectorRegistry;
    private final TinkCryptoService tinkCryptoService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final AuditChainService auditChainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;
    private final TransactionTemplate requiresNewTxTemplate;
    private final ObjectProvider<VoucherExtensionValidator> extensionValidatorProvider;

    public GuidedSpreadsheetImportService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        SpreadsheetFormatParser formatParser,
        VoucherService voucherService,
        LedgerService ledgerService,
        OutboundConnectorRegistry connectorRegistry,
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer,
        AuditChainService auditChainService,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager,
        ObjectProvider<VoucherExtensionValidator> extensionValidatorProvider
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.formatParser = formatParser;
        this.voucherService = voucherService;
        this.ledgerService = ledgerService;
        this.connectorRegistry = connectorRegistry;
        this.tinkCryptoService = tinkCryptoService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.auditChainService = auditChainService;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
        this.requiresNewTxTemplate = new TransactionTemplate(businessTransactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.extensionValidatorProvider = extensionValidatorProvider;
    }

    // =========================================================================
    // 1. Versioned Columnar Mapping Templates
    // =========================================================================

    public List<SpreadsheetImportDtos.SpreadsheetTemplateDto> listSpreadsheetTemplates(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        requireImportRead(principal, companyId);
        return businessJdbcTemplate.query(
            """
            SELECT id, company_id, name, target_type, sheet_name, header_row_index,
                   default_values_json, duplicate_handling, version_no, created_at
              FROM mc_exchange_mapping_template
             WHERE company_id = ? AND active = true AND COALESCE(sheet_name, '') <> '__MC_SYNC_RULE__'
             ORDER BY lower(name) ASC, id ASC
            """,
            (rs, rowNum) -> mapTemplateRow(rs),
            companyId
        );
    }

    public SpreadsheetImportDtos.SpreadsheetTemplateDto saveSpreadsheetTemplate(
        MoneyCraftPrincipal actor,
        UUID companyId,
        SpreadsheetImportDtos.SaveSpreadsheetTemplateRequest request
    ) {
        authorityGuard.assertWritable();
        requireTemplateManage(actor, companyId);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-SHEET-TEMPLATE-NAME-REQUIRED",
                "Template Name Required",
                "Specify a non-blank mapping template name."
            );
        }
        String name = request.name().trim();
        String targetType = normalizeTargetType(
            request.targetType() != null ? request.targetType()
                : (request.mapping() == null ? null : request.mapping().targetType())
        );
        String sheetName = request.sheetName() == null || request.sheetName().isBlank()
            ? "Sheet1"
            : request.sheetName().trim();
        int headerRow = request.headerRowIndex() == null || request.headerRowIndex() < 1
            ? 1
            : request.headerRowIndex();
        String dup = normalizeDupHandling(request.duplicateHandling());
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto spec = normalizeMappingSpec(request.mapping(), targetType);

        Map<String, Object> defaultJsonWrapper = new LinkedHashMap<>();
        defaultJsonWrapper.put("guidedMappingSpec", spec);

        return businessTxTemplate.execute(tx -> {
            ensurePrincipalProjected(actor);
            UUID id;
            if (request.templateId() != null) {
                id = request.templateId();
                int updated = businessJdbcTemplate.update(
                    """
                    UPDATE mc_exchange_mapping_template
                       SET name = ?,
                           target_type = ?,
                           sheet_name = ?,
                           header_row_index = ?,
                           default_values_json = ?::jsonb,
                           duplicate_handling = ?,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    name,
                    targetType,
                    sheetName,
                    headerRow,
                    toJson(defaultJsonWrapper),
                    dup,
                    companyId,
                    id
                );
                if (updated == 0) {
                    throw new MoneyCraftException(
                        HttpStatus.NOT_FOUND,
                        "MC-SHEET-TEMPLATE-NOT-FOUND",
                        "Spreadsheet Mapping Template Not Found",
                        "Template " + id + " does not exist in company " + companyId + "."
                    );
                }
            } else {
                id = UUID.randomUUID();
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_exchange_mapping_template (
                        id, company_id, name, target_type, sheet_name, header_row_index,
                        column_mappings_json, default_values_json, duplicate_handling,
                        active, version_no, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?::jsonb, ?, true, 1, ?)
                    """,
                    id,
                    companyId,
                    name,
                    targetType,
                    sheetName,
                    headerRow,
                    toJson(defaultJsonWrapper),
                    dup,
                    actor.principalId()
                );
            }

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "SHEET_TEMPLATE_SAVE",
                "EXCHANGE_TEMPLATE",
                id.toString(),
                "SUCCESS",
                null,
                Map.of("name", name, "targetType", targetType)
            );
            return requireTemplateDto(companyId, id);
        });
    }

    // =========================================================================
    // 2. Prepare / Freeze Snapshot / Dry-Run Validate (Zero Accounting Writes)
    // =========================================================================

    public List<SpreadsheetImportDtos.SpreadsheetBatchDto> listBatches(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        requireImportRead(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        List<BatchRow> rows = businessJdbcTemplate.query(
            """
            SELECT id, company_id, source_file_name, source_cipher_gcm, source_sha256,
                   format_profile, target_type, sheet_name, available_sheets_json,
                   detected_headers_json, header_row_index, date_system, macro_detected,
                   formula_cells_detected, template_id, template_version, mapping_revision,
                   mapping_snapshot_json, cached_formula_policy_accepted, status,
                   total_units, valid_units, invalid_units, committed_units,
                   failed_units, skipped_units, uncertain_units,
                   total_debit, total_credit, diagnostics_json,
                   actor_principal_id, version_no, created_at, completed_at
              FROM mc_sheet_import_batch
             WHERE company_id = ?
             ORDER BY created_at DESC
            """,
            (rs, rowNum) -> mapBatchRow(rs),
            companyId
        );
        List<SpreadsheetImportDtos.SpreadsheetBatchDto> out = new ArrayList<>(rows.size());
        for (BatchRow r : rows) {
            out.add(toBatchDto(r, scale, null, List.of()));
        }
        return List.copyOf(out);
    }

    public SpreadsheetImportDtos.SpreadsheetBatchDto getBatch(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID batchId
    ) {
        requireImportRead(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        BatchRow batch = requireBatchRow(companyId, batchId, false);
        List<SpreadsheetImportDtos.SpreadsheetUnitDto> units = loadUnits(companyId, batchId, batch.mappingRevision(), scale);
        UUID priorId = findPreviousMatchingBatchId(companyId, batch.sourceSha256(), batch.targetType(), batchId);
        return toBatchDto(batch, scale, priorId, units);
    }

    public SpreadsheetImportDtos.SpreadsheetBatchDto prepareBatch(
        MoneyCraftPrincipal actor,
        UUID companyId,
        SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest request
    ) {
        authorityGuard.assertWritable();
        requireImportPrepare(actor, companyId);
        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-SHEET-PREPARE-REQUIRED",
                "Spreadsheet Prepare Payload Required",
                "Provide spreadsheet file bytes, CSV text, or Google Sheets capture specification."
            );
        }

        CapturedSourceSnapshot snapshot = captureAndFreezeSource(request);
        char delimiter = resolveDelimiter(request.delimiter());
        SpreadsheetFormatParser.ParsedWorkbook workbook = snapshot.preParsedWorkbook() != null
            ? snapshot.preParsedWorkbook()
            : formatParser.parse(snapshot.fileName(), request.formatHint(), snapshot.rawBytes(), delimiter);

        TemplateBinding tplBinding = resolveOptionalTemplate(companyId, request.templateId());
        String targetType = normalizeTargetType(
            request.mapping() != null && request.mapping().targetType() != null
                ? request.mapping().targetType()
                : (tplBinding.mapping() != null ? tplBinding.mapping().targetType() : null)
        );
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto effectiveMapping = normalizeMappingSpec(
            request.mapping() != null ? request.mapping() : tplBinding.mapping(),
            targetType
        );

        String requestedSheet = firstNonBlank(request.sheetName(), tplBinding.sheetName());
        SpreadsheetFormatParser.ParsedSheet selectedSheet = selectSheet(workbook, requestedSheet);
        int headerRowIdx = request.headerRowIndex() != null && request.headerRowIndex() >= 1
            ? request.headerRowIndex()
            : (tplBinding.headerRowIndex() != null ? tplBinding.headerRowIndex() : 1);
        boolean formulaConsent = Boolean.TRUE.equals(request.cachedFormulaConsent());

        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        byte[] sha256 = jcsCanonicalizer.sha256(snapshot.rawBytes());
        UUID priorBatchId = findPreviousMatchingBatchId(companyId, sha256, targetType, null);

        DryRunPlan plan = buildAndValidateUnits(
            actor,
            companyId,
            scale,
            workbook,
            selectedSheet,
            headerRowIdx,
            effectiveMapping,
            formulaConsent
        );

        UUID batchId = UUID.randomUUID();
        byte[] aad = buildBatchAad(companyId, batchId, sha256);
        byte[] cipherGcm = tinkCryptoService.encryptGcm(aad, snapshot.rawBytes());

        List<String> diagnostics = new ArrayList<>(workbook.diagnostics());
        if (workbook.formulaCellsDetected()) {
            diagnostics.add(
                formulaConsent
                    ? "Cached formula values accepted explicitly without recalculation."
                    : "Formula cells detected; set cachedFormulaConsent=true to admit cached results."
            );
        }
        if (priorBatchId != null && !Boolean.TRUE.equals(request.acknowledgeDuplicateRun())) {
            diagnostics.add(
                "Previous matching source digest detected in batch " + priorBatchId
                    + "; acknowledgeDuplicateRun=true recommended before deliberate repeat commit."
            );
        }

        String batchStatus = (plan.invalidUnits() == 0 && plan.validUnits() > 0)
            ? "READY"
            : (plan.invalidUnits() > 0 ? "FAILED_BEFORE_WRITE" : "PREPARED");

        return businessTxTemplate.execute(tx -> {
            ensurePrincipalProjected(actor);
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_sheet_import_batch (
                    id, company_id, source_file_name, source_cipher_gcm, source_sha256,
                    format_profile, target_type, sheet_name, available_sheets_json,
                    detected_headers_json, header_row_index, date_system, macro_detected,
                    formula_cells_detected, template_id, template_version, mapping_revision,
                    mapping_snapshot_json, cached_formula_policy_accepted, status,
                    total_units, valid_units, invalid_units, committed_units,
                    failed_units, skipped_units, uncertain_units,
                    total_debit, total_credit, diagnostics_json,
                    actor_principal_id, version_no
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?::jsonb,
                    ?::jsonb, ?, ?, ?,
                    ?, ?, ?, 1,
                    ?::jsonb, ?, ?,
                    ?, ?, ?, 0,
                    0, 0, 0,
                    ?, ?, ?::jsonb,
                    ?, 1
                )
                """,
                batchId,
                companyId,
                snapshot.fileName(),
                cipherGcm,
                sha256,
                workbook.formatProfile(),
                targetType,
                selectedSheet.name(),
                toJson(workbook.sheets().stream().map(SpreadsheetFormatParser.ParsedSheet::name).toList()),
                toJson(plan.detectedHeaders()),
                headerRowIdx,
                workbook.dateSystem(),
                workbook.macroDetected(),
                workbook.formulaCellsDetected(),
                tplBinding.templateId(),
                tplBinding.templateVersion(),
                toJson(effectiveMapping),
                formulaConsent,
                batchStatus,
                plan.units().size(),
                plan.validUnits(),
                plan.invalidUnits(),
                plan.totalDebit(),
                plan.totalCredit(),
                toJson(diagnostics),
                actor.principalId()
            );

            persistUnits(companyId, batchId, 1, plan.units());

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "SHEET_BATCH_PREPARE",
                "SHEET_IMPORT_BATCH",
                batchId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "formatProfile", workbook.formatProfile(),
                    "targetType", targetType,
                    "totalUnits", plan.units().size(),
                    "validUnits", plan.validUnits(),
                    "invalidUnits", plan.invalidUnits(),
                    "status", batchStatus
                )
            );

            return getBatch(actor, companyId, batchId);
        });
    }

    public SpreadsheetImportDtos.SpreadsheetBatchDto remapBatch(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID batchId,
        SpreadsheetImportDtos.RemapSpreadsheetBatchRequest request
    ) {
        authorityGuard.assertWritable();
        requireImportPrepare(actor, companyId);
        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-SHEET-REMAP-REQUIRED",
                "Remap Request Required",
                "Provide updated mapping or cachedFormulaConsent."
            );
        }

        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        return businessTxTemplate.execute(tx -> {
            ensurePrincipalProjected(actor);
            BatchRow batch = requireBatchRow(companyId, batchId, true);
            if (!Set.of("PREPARED", "READY", "FAILED_BEFORE_WRITE").contains(batch.status())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-SHEET-BATCH-LOCKED",
                    "Batch Cannot Be Remapped After Execution Starts",
                    "Batch in state '" + batch.status() + "' cannot be remapped; prepare a new batch instead."
                );
            }

            byte[] aad = buildBatchAad(companyId, batchId, batch.sourceSha256());
            byte[] frozenBytes = tinkCryptoService.decryptGcm(aad, batch.sourceCipherGcm());
            char delimiter = resolveDelimiter(request.delimiter());

            SpreadsheetFormatParser.ParsedWorkbook workbook;
            if ("GOOGLE_SHEETS".equals(batch.formatProfile())) {
                List<List<String>> rows = parseNestedStringRows(new String(frozenBytes, StandardCharsets.UTF_8));
                workbook = formatParser.buildFromGoogleSheetsRows(batch.sheetName(), rows);
            } else {
                workbook = formatParser.parse(batch.sourceFileName(), batch.formatProfile(), frozenBytes, delimiter);
            }

            TemplateBinding tplBinding = resolveOptionalTemplate(
                companyId,
                request.templateId() != null ? request.templateId() : batch.templateId()
            );
            SpreadsheetImportDtos.SpreadsheetMappingSpecDto priorMapping = parseMappingSpec(batch.mappingSnapshotJson());
            String targetType = normalizeTargetType(
                request.mapping() != null && request.mapping().targetType() != null
                    ? request.mapping().targetType()
                    : batch.targetType()
            );
            SpreadsheetImportDtos.SpreadsheetMappingSpecDto effectiveMapping = normalizeMappingSpec(
                request.mapping() != null ? request.mapping()
                    : (tplBinding.mapping() != null ? tplBinding.mapping() : priorMapping),
                targetType
            );

            String sheetName = firstNonBlank(request.sheetName(), batch.sheetName());
            SpreadsheetFormatParser.ParsedSheet selectedSheet = selectSheet(workbook, sheetName);
            int headerRowIdx = request.headerRowIndex() != null && request.headerRowIndex() >= 1
                ? request.headerRowIndex()
                : batch.headerRowIndex();
            boolean formulaConsent = request.cachedFormulaConsent() != null
                ? request.cachedFormulaConsent()
                : batch.cachedFormulaPolicyAccepted();

            DryRunPlan plan = buildAndValidateUnits(
                actor,
                companyId,
                scale,
                workbook,
                selectedSheet,
                headerRowIdx,
                effectiveMapping,
                formulaConsent
            );

            int nextMapRev = batch.mappingRevision() + 1;
            String nextStatus = (plan.invalidUnits() == 0 && plan.validUnits() > 0)
                ? "READY"
                : (plan.invalidUnits() > 0 ? "FAILED_BEFORE_WRITE" : "PREPARED");

            businessJdbcTemplate.update(
                """
                UPDATE mc_sheet_import_unit
                   SET execution_status = 'SKIPPED',
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND batch_id = ? AND mapping_revision = ? AND execution_status = 'PENDING'
                """,
                companyId,
                batchId,
                batch.mappingRevision()
            );

            businessJdbcTemplate.update(
                """
                UPDATE mc_sheet_import_batch
                   SET target_type = ?,
                       sheet_name = ?,
                       detected_headers_json = ?::jsonb,
                       header_row_index = ?,
                       template_id = ?,
                       template_version = ?,
                       mapping_revision = ?,
                       mapping_snapshot_json = ?::jsonb,
                       cached_formula_policy_accepted = ?,
                       status = ?,
                       total_units = ?,
                       valid_units = ?,
                       invalid_units = ?,
                       total_debit = ?,
                       total_credit = ?,
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                targetType,
                selectedSheet.name(),
                toJson(plan.detectedHeaders()),
                headerRowIdx,
                tplBinding.templateId() != null ? tplBinding.templateId() : batch.templateId(),
                tplBinding.templateVersion() != null ? tplBinding.templateVersion() : batch.templateVersion(),
                nextMapRev,
                toJson(effectiveMapping),
                formulaConsent,
                nextStatus,
                plan.units().size(),
                plan.validUnits(),
                plan.invalidUnits(),
                plan.totalDebit(),
                plan.totalCredit(),
                companyId,
                batchId
            );

            persistUnits(companyId, batchId, nextMapRev, plan.units());
            return getBatch(actor, companyId, batchId);
        });
    }

    // =========================================================================
    // 3. Per-Unit LIVE Atomic Commit, Interruption/Resume & Cancellation
    // =========================================================================

    public SpreadsheetImportDtos.SpreadsheetBatchDto executeBatch(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID batchId,
        SpreadsheetImportDtos.ExecuteSpreadsheetBatchRequest request
    ) {
        authorityGuard.assertWritable();
        requireImportCommit(actor, companyId);

        boolean allowValidOnly = request != null && Boolean.TRUE.equals(request.allowValidUnitsOnly());
        Integer maxUnits = request == null ? null : request.maxUnitsThisRun();
        String requestedAction = request == null ? null : request.action();

        BatchRow batch = requireBatchRow(companyId, batchId, false);
        if ("COMPLETED".equals(batch.status())) {
            return getBatch(actor, companyId, batchId);
        }
        if ("CANCELLED".equals(batch.status())) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-SHEET-BATCH-CANCELLED",
                "Spreadsheet Batch Cancelled",
                "Cancelled batch " + batchId + " cannot be resumed."
            );
        }
        if (!isPrincipalProjectedAndEnabled(batch.actorPrincipalId())) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-SHEET-PREPARER-REVOKED",
                "Initiating Preparer Authority Revoked",
                "Initiating preparer principal is disabled; batch execution is halted."
            );
        }

        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        List<StoredUnitRow> allUnits = loadStoredUnitRows(companyId, batchId, batch.mappingRevision());

        if (batch.invalidUnits() > 0 && !allowValidOnly) {
            SpreadsheetImportDtos.CellValidationErrorDto firstErr = findFirstValidationError(allUnits);
            String errCode = firstErr != null ? firstErr.code() : "MC-SHEET-BATCH-INVALID";
            String errMsg = firstErr != null
                ? (firstErr.sheetName() + "!" + firstErr.cellRef() + ": " + firstErr.message())
                : ("Batch contains " + batch.invalidUnits() + " invalid unit(s); fix errors or confirm allowValidUnitsOnly=true.");
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                errCode,
                "Spreadsheet Batch Contains Validation Errors",
                errMsg
            );
        }
        if (batch.validUnits() == 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-NO-VALID-UNITS",
                "Zero Valid Units in Batch",
                "Batch contains zero valid business units to execute."
            );
        }

        SpreadsheetImportDtos.SpreadsheetMappingSpecDto frozenMap = parseMappingSpec(batch.mappingSnapshotJson());
        String effectiveVoucherAction = normalizeVoucherAction(
            requestedAction != null && !requestedAction.isBlank()
                ? requestedAction
                : frozenMap.voucherAction()
        );

        businessTxTemplate.executeWithoutResult(tx -> {
            ensurePrincipalProjected(actor);
            businessJdbcTemplate.update(
                """
                UPDATE mc_sheet_import_batch
                   SET status = 'EXECUTING',
                       executed_by = ?,
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                actor.principalId(),
                companyId,
                batchId
            );
            if (allowValidOnly) {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_sheet_import_unit
                       SET execution_status = 'SKIPPED',
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND batch_id = ? AND mapping_revision = ?
                       AND validation_status = 'INVALID' AND execution_status = 'PENDING'
                    """,
                    companyId,
                    batchId,
                    batch.mappingRevision()
                );
            }
        });

        int executedInThisCall = 0;
        boolean interruptedByLimit = false;

        for (StoredUnitRow unit : allUnits) {
            if (!"PENDING".equals(unit.executionStatus()) || "INVALID".equals(unit.validationStatus())) {
                continue;
            }
            if (maxUnits != null && maxUnits > 0 && executedInThisCall >= maxUnits) {
                interruptedByLimit = true;
                break;
            }

            // Re-verify AuthorityGuard & current batch cancellation state at each unit boundary
            authorityGuard.assertWritable();
            String latestStatus = queryBatchStatus(companyId, batchId);
            if ("CANCELLED".equals(latestStatus)) {
                break;
            }

            executeSingleUnitAtomically(actor, companyId, batch.targetType(), unit, effectiveVoucherAction);
            executedInThisCall++;
        }

        boolean finalInterrupted = interruptedByLimit;
        businessTxTemplate.executeWithoutResult(tx -> reconcileBatchCountersAndStatus(
            actor, companyId, batchId, batch.mappingRevision(), finalInterrupted
        ));

        return getBatch(actor, companyId, batchId);
    }

    public SpreadsheetImportDtos.SpreadsheetBatchDto cancelBatch(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID batchId
    ) {
        authorityGuard.assertWritable();
        requireImportCommit(actor, companyId);

        return businessTxTemplate.execute(tx -> {
            ensurePrincipalProjected(actor);
            BatchRow batch = requireBatchRow(companyId, batchId, true);
            if ("COMPLETED".equals(batch.status())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-SHEET-ALREADY-COMPLETED",
                    "Completed Batch Cannot Be Cancelled",
                    "All units in batch " + batchId + " are already committed; use ordinary voucher reversal/amendment."
                );
            }

            businessJdbcTemplate.update(
                """
                UPDATE mc_sheet_import_unit
                   SET execution_status = 'SKIPPED',
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND batch_id = ? AND mapping_revision = ?
                   AND execution_status = 'PENDING'
                """,
                companyId,
                batchId,
                batch.mappingRevision()
            );

            UnitStatusCounts counts = queryUnitCounts(companyId, batchId, batch.mappingRevision());
            businessJdbcTemplate.update(
                """
                UPDATE mc_sheet_import_batch
                   SET status = 'CANCELLED',
                       committed_units = ?,
                       failed_units = ?,
                       skipped_units = ?,
                       uncertain_units = ?,
                       version_no = version_no + 1,
                       updated_at = clock_timestamp(),
                       completed_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                counts.committed(),
                counts.failed(),
                counts.skipped(),
                counts.uncertain(),
                companyId,
                batchId
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "SHEET_BATCH_CANCEL",
                "SHEET_IMPORT_BATCH",
                batchId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "committedUnitsPreserved", counts.committed(),
                    "skippedPendingUnits", counts.skipped()
                )
            );
            return getBatch(actor, companyId, batchId);
        });
    }

    // =========================================================================
    // 4. Formula-Injection-Safe Error CSV Export (OWASP CSV Injection Defense)
    // =========================================================================

    public String exportErrorsCsv(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID batchId
    ) {
        requireImportRead(principal, companyId);
        BatchRow batch = requireBatchRow(companyId, batchId, false);
        List<StoredUnitRow> units = loadStoredUnitRows(companyId, batchId, batch.mappingRevision());

        StringBuilder csv = new StringBuilder(2048);
        csv.append("UnitSeq,UnitKey,SheetName,RowNumber,ColumnIndex,CellRef,ColumnHeader,ErrorCode,Field,SafeCellValue,Message\r\n");

        for (StoredUnitRow u : units) {
            List<SpreadsheetImportDtos.CellValidationErrorDto> valErrors = parseValidationErrors(u.validationErrorsJson());
            for (SpreadsheetImportDtos.CellValidationErrorDto err : valErrors) {
                appendCsvRow(
                    csv,
                    Integer.toString(u.unitSeq()),
                    u.unitKey(),
                    err.sheetName(),
                    Integer.toString(err.rowNumber()),
                    Integer.toString(err.columnIndex()),
                    err.cellRef(),
                    err.columnHeader(),
                    err.code(),
                    err.field(),
                    err.rawCellSnippet(),
                    err.message()
                );
            }
            if ("FAILED".equals(u.executionStatus()) && u.executionErrorCode() != null) {
                SpreadsheetImportDtos.SourceCoordinatesDto coords = parseCoordinates(u.sourceCoordinatesJson());
                int firstRow = coords.rowNumbers().isEmpty() ? 0 : coords.rowNumbers().getFirst();
                appendCsvRow(
                    csv,
                    Integer.toString(u.unitSeq()),
                    u.unitKey(),
                    coords.sheetName(),
                    Integer.toString(firstRow),
                    "0",
                    "ROW_" + firstRow,
                    "",
                    u.executionErrorCode(),
                    "execution",
                    "",
                    u.executionErrorMessage()
                );
            }
        }
        return csv.toString();
    }

    public static String sanitizeFormulaInjectionLiteral(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int idx = 0;
        while (idx < raw.length() && raw.charAt(idx) == ' ') {
            idx++;
        }
        char first = raw.charAt(0);
        char firstNonSpace = idx < raw.length() ? raw.charAt(idx) : first;
        if (isFormulaTrigger(first) || isFormulaTrigger(firstNonSpace)) {
            return "'" + raw;
        }
        return raw;
    }

    private static boolean isFormulaTrigger(char ch) {
        return ch == '=' || ch == '+' || ch == '-' || ch == '@' || ch == '\t' || ch == '\r';
    }

    private static void appendCsvRow(StringBuilder sb, String... rawCols) {
        for (int i = 0; i < rawCols.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String safe = sanitizeFormulaInjectionLiteral(rawCols[i] == null ? "" : rawCols[i]);
            boolean needsQuotes = safe.indexOf(',') >= 0
                || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0
                || safe.indexOf('\r') >= 0;
            if (needsQuotes) {
                sb.append('"').append(safe.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(safe);
            }
        }
        sb.append("\r\n");
    }

    // =========================================================================
    // Internal Unit Builder & Validator (Multi-Ledger Horizontal + Grouped)
    // =========================================================================

    private DryRunPlan buildAndValidateUnits(
        MoneyCraftPrincipal actor,
        UUID companyId,
        int scale,
        SpreadsheetFormatParser.ParsedWorkbook workbook,
        SpreadsheetFormatParser.ParsedSheet sheet,
        int headerRowIndex,
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto mapping,
        boolean cachedFormulaConsent
    ) {
        List<SpreadsheetFormatParser.ParsedRow> allRows = sheet.rows();
        SpreadsheetFormatParser.ParsedRow headerRow = allRows.stream()
            .filter(r -> r.rowNumber() == headerRowIndex)
            .findFirst()
            .orElse(allRows.isEmpty() ? new SpreadsheetFormatParser.ParsedRow(headerRowIndex, List.of()) : allRows.getFirst());

        List<String> detectedHeaders = new ArrayList<>();
        Map<String, Integer> colByHeaderLower = new LinkedHashMap<>();
        for (SpreadsheetFormatParser.ParsedCell cell : headerRow.cells()) {
            String h = cell.rawText() == null ? "" : cell.rawText().trim();
            detectedHeaders.add(h);
            if (!h.isEmpty()) {
                colByHeaderLower.putIfAbsent(h.toLowerCase(Locale.ROOT), cell.columnIndex());
            }
        }

        List<SpreadsheetFormatParser.ParsedRow> dataRows = allRows.stream()
            .filter(r -> r.rowNumber() > headerRow.rowNumber())
            .toList();

        if ("LEDGERS".equals(mapping.targetType())) {
            return buildLedgerUnits(
                companyId,
                scale,
                sheet.name(),
                detectedHeaders,
                colByHeaderLower,
                dataRows,
                mapping,
                cachedFormulaConsent
            );
        }

        return buildVoucherUnits(
            actor,
            companyId,
            scale,
            workbook.dateSystem(),
            sheet.name(),
            detectedHeaders,
            colByHeaderLower,
            dataRows,
            mapping,
            cachedFormulaConsent
        );
    }

    private DryRunPlan buildVoucherUnits(
        MoneyCraftPrincipal actor,
        UUID companyId,
        int scale,
        String dateSystem,
        String sheetName,
        List<String> detectedHeaders,
        Map<String, Integer> colByHeaderLower,
        List<SpreadsheetFormatParser.ParsedRow> dataRows,
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto mapping,
        boolean cachedFormulaConsent
    ) {
        Map<String, CompanyLedgerMeta> ledgersByLower = loadCompanyLedgers(companyId);
        Map<String, CompanyVoucherTypeMeta> voucherTypesByLower = loadCompanyVoucherTypes(companyId);

        Integer groupCol = resolveColumnOneBased(mapping.groupingKeyColumn(), colByHeaderLower);
        Map<String, List<SpreadsheetFormatParser.ParsedRow>> unitRowGroups = new LinkedHashMap<>();
        int autoSeq = 1;
        for (SpreadsheetFormatParser.ParsedRow row : dataRows) {
            String key;
            if (groupCol != null) {
                String gVal = row.cellAtOneBased(groupCol).rawText().trim();
                key = gVal.isEmpty() ? ("ROW-" + row.rowNumber()) : ("GRP-" + gVal);
            } else {
                key = "ROW-" + row.rowNumber() + "-" + (autoSeq++);
            }
            unitRowGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        Integer dateCol = resolveColumnWithFallbacks(
            mapping.voucherDateColumn(), colByHeaderLower, "date", "voucher date", "voucherdate", "invoice date"
        );
        Integer vchTypeCol = resolveColumnWithFallbacks(
            mapping.voucherTypeColumn(), colByHeaderLower, "voucher type", "vouchertype", "type"
        );
        Integer vchNoCol = resolveColumnWithFallbacks(
            mapping.voucherNumberColumn(), colByHeaderLower, "voucher no", "voucher number", "vouchernumber", "invoice no"
        );
        Integer refNoCol = resolveColumnWithFallbacks(
            mapping.referenceNumberColumn(), colByHeaderLower, "ref no", "reference", "supplier invoice no"
        );
        Integer narrCol = resolveColumnWithFallbacks(
            mapping.narrationColumn(), colByHeaderLower, "narration", "particulars", "remarks"
        );
        Integer partyNameCol = resolveColumnWithFallbacks(
            mapping.partyLedgerNameColumn(), colByHeaderLower, "party ledger", "party", "customer", "supplier", "party name"
        );
        Integer partyAmtCol = resolveColumnWithFallbacks(
            mapping.partyAmountColumn(), colByHeaderLower, "party amount", "invoice total", "total amount", "gross total"
        );
        Integer drLedgerCol = resolveColumnWithFallbacks(
            mapping.debitLedgerColumn(), colByHeaderLower, "debit ledger", "dr ledger", "debitledger"
        );
        Integer crLedgerCol = resolveColumnWithFallbacks(
            mapping.creditLedgerColumn(), colByHeaderLower, "credit ledger", "cr ledger", "creditledger"
        );
        Integer singleAmtCol = resolveColumnWithFallbacks(
            mapping.amountColumn(), colByHeaderLower, "amount", "value"
        );

        List<PreparedUnitPlan> units = new ArrayList<>();
        BigDecimal grandDr = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        BigDecimal grandCr = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        int validCount = 0;
        int invalidCount = 0;
        int unitSeq = 1;

        for (Map.Entry<String, List<SpreadsheetFormatParser.ParsedRow>> groupEntry : unitRowGroups.entrySet()) {
            String unitKey = groupEntry.getKey();
            List<SpreadsheetFormatParser.ParsedRow> rows = groupEntry.getValue();
            SpreadsheetFormatParser.ParsedRow firstRow = rows.getFirst();

            List<SpreadsheetImportDtos.CellValidationErrorDto> errors = new ArrayList<>();
            Map<String, String> coordMap = new LinkedHashMap<>();
            List<Integer> rowNums = rows.stream().map(SpreadsheetFormatParser.ParsedRow::rowNumber).toList();

            // 1. Resolve Accounting Date (fictitious 1900-02-29 throws 422 MC-SHEET-INVALID-DATE immediately)
            LocalDate voucherDate = null;
            if (dateCol == null) {
                errors.add(cellError(
                    "MC-SHEET-DATE-COLUMN-MISSING", "voucherDate", sheetName,
                    firstRow.rowNumber(), 1, "A" + firstRow.rowNumber(), "", "",
                    "Voucher date column is not mapped or missing in sheet header."
                ));
            } else {
                SpreadsheetFormatParser.ParsedCell dateCell = firstRow.cellAtOneBased(dateCol);
                coordMap.put("voucherDate", sheetName + "!" + dateCell.cellRef());
                if (checkFormulaPolicy(dateCell, cachedFormulaConsent, sheetName, firstRow.rowNumber(),
                    headerAt(detectedHeaders, dateCol), "voucherDate", errors)) {
                    voucherDate = SpreadsheetFormatParser.resolveAccountingDate(
                        dateCell, dateSystem, mapping.datePattern(), sheetName
                    );
                }
            }

            // 2. Resolve Voucher Type
            String rawVchType = vchTypeCol != null
                ? readCellTextChecked(
                    firstRow.cellAtOneBased(vchTypeCol), cachedFormulaConsent, sheetName,
                    firstRow.rowNumber(), headerAt(detectedHeaders, vchTypeCol), "voucherType", errors
                )
                : "";
            if (rawVchType.isBlank()) {
                rawVchType = (mapping.defaultVoucherType() == null || mapping.defaultVoucherType().isBlank())
                    ? "Journal"
                    : mapping.defaultVoucherType().trim();
            }
            CompanyVoucherTypeMeta vchTypeMeta = voucherTypesByLower.get(rawVchType.toLowerCase(Locale.ROOT));
            if (vchTypeMeta == null) {
                int cIdx = vchTypeCol == null ? 1 : vchTypeCol;
                errors.add(cellError(
                    "MC-SHEET-VOUCHER-TYPE-NOT-FOUND", "voucherType", sheetName,
                    firstRow.rowNumber(), cIdx, SpreadsheetFormatParser.toColumnLetters(cIdx - 1) + firstRow.rowNumber(),
                    headerAt(detectedHeaders, cIdx), rawVchType,
                    "Voucher type '" + rawVchType + "' is not defined in company."
                ));
            }

            String vchNumber = vchNoCol == null ? null : emptyToNull(readCellTextChecked(
                firstRow.cellAtOneBased(vchNoCol), cachedFormulaConsent, sheetName,
                firstRow.rowNumber(), headerAt(detectedHeaders, vchNoCol), "voucherNumber", errors
            ));
            String refNumber = refNoCol == null ? null : emptyToNull(readCellTextChecked(
                firstRow.cellAtOneBased(refNoCol), cachedFormulaConsent, sheetName,
                firstRow.rowNumber(), headerAt(detectedHeaders, refNoCol), "referenceNumber", errors
            ));
            String narration = narrCol == null ? "" : readCellTextChecked(
                firstRow.cellAtOneBased(narrCol), cachedFormulaConsent, sheetName,
                firstRow.rowNumber(), headerAt(detectedHeaders, narrCol), "narration", errors
            );

            // 3. Collect Voucher Lines Across Unlimited Mapped Ledger Columns & Grouped Rows
            List<VoucherDtos.VoucherLineRequest> lines = new ArrayList<>();
            List<String> resolvedLedgerNames = new ArrayList<>();
            BigDecimal unitDr = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
            BigDecimal unitCr = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
            UUID headerPartyLedgerId = null;
            int lineNo = 1;

            for (SpreadsheetFormatParser.ParsedRow row : rows) {
                // 3A. Dynamic Party Ledger + Party Amount column (if mapped)
                if (partyNameCol != null && partyAmtCol != null && (row == firstRow || groupCol == null)) {
                    SpreadsheetFormatParser.ParsedCell pNameCell = row.cellAtOneBased(partyNameCol);
                    SpreadsheetFormatParser.ParsedCell pAmtCell = row.cellAtOneBased(partyAmtCol);
                    String partyName = readCellTextChecked(
                        pNameCell, cachedFormulaConsent, sheetName, row.rowNumber(),
                        headerAt(detectedHeaders, partyNameCol), "partyLedger", errors
                    );
                    if (!partyName.isBlank() && pAmtCell.kind() != SpreadsheetFormatParser.CellKind.BLANK) {
                        ParsedSignedAmount pAmt = parseCellAmount(
                            pAmtCell,
                            mapping.partySide() == null || mapping.partySide().isBlank() ? "DR" : mapping.partySide(),
                            scale,
                            cachedFormulaConsent,
                            sheetName,
                            row.rowNumber(),
                            headerAt(detectedHeaders, partyAmtCol),
                            "partyAmount",
                            errors
                        );
                        CompanyLedgerMeta partyMeta = resolveCompanyLedger(
                            partyName, ledgersByLower, sheetName, row.rowNumber(), pNameCell,
                            headerAt(detectedHeaders, partyNameCol), "partyLedger", errors
                        );
                        if (pAmt != null && pAmt.amount().signum() > 0 && partyMeta != null) {
                            headerPartyLedgerId = partyMeta.id();
                            coordMap.put("line:" + lineNo, sheetName + "!" + pAmtCell.cellRef());
                            List<VoucherDtos.BillAllocationDto> bills = buildAutoBillAllocIfNeeded(
                                partyMeta, pAmt.side(), pAmt.amount(), scale, refNumber, vchNumber, unitKey
                            );
                            lines.add(new VoucherDtos.VoucherLineRequest(
                                lineNo++, "PARTY", partyMeta.id(), pAmt.side(),
                                ExactMoneyValidator.formatExactScale(pAmt.amount(), scale),
                                null, null, null, null, null, false, null, null,
                                bills, List.of(), List.of()
                            ));
                            resolvedLedgerNames.add(partyMeta.name());
                            if ("DR".equals(pAmt.side())) {
                                unitDr = unitDr.add(pAmt.amount());
                            } else {
                                unitCr = unitCr.add(pAmt.amount());
                            }
                        }
                    }
                }

                // 3B. Unlimited Horizontal Multi-Ledger Columns (0..N columns, zero 81-column cap!)
                for (SpreadsheetImportDtos.LedgerColumnMappingDto colMap : mapping.ledgerColumns()) {
                    Integer cIdx = resolveColumnOneBased(colMap.sourceColumn(), colByHeaderLower);
                    if (cIdx == null) {
                        errors.add(cellError(
                            "MC-SHEET-COLUMN-NOT-FOUND", "ledgerColumn", sheetName,
                            row.rowNumber(), 1, "A" + row.rowNumber(),
                            colMap.sourceColumn(), "",
                            "Mapped ledger column '" + colMap.sourceColumn() + "' was not found in sheet header."
                        ));
                        continue;
                    }
                    SpreadsheetFormatParser.ParsedCell cell = row.cellAtOneBased(cIdx);
                    if (cell.kind() == SpreadsheetFormatParser.CellKind.BLANK) {
                        continue;
                    }
                    String targetLedgerName = (colMap.ledgerName() != null && !colMap.ledgerName().isBlank())
                        ? colMap.ledgerName().trim()
                        : headerAt(detectedHeaders, cIdx);
                    String declaredSide = (colMap.side() == null || colMap.side().isBlank())
                        ? "CR"
                        : colMap.side().trim().toUpperCase(Locale.ROOT);
                    ParsedSignedAmount parsedAmt = parseCellAmount(
                        cell, declaredSide, scale, cachedFormulaConsent, sheetName,
                        row.rowNumber(), headerAt(detectedHeaders, cIdx), targetLedgerName, errors
                    );
                    if (parsedAmt == null || parsedAmt.amount().signum() == 0) {
                        continue;
                    }
                    CompanyLedgerMeta ledgerMeta = colMap.ledgerId() != null
                        ? findLedgerById(colMap.ledgerId(), ledgersByLower)
                        : resolveCompanyLedger(
                            targetLedgerName, ledgersByLower, sheetName, row.rowNumber(), cell,
                            headerAt(detectedHeaders, cIdx), targetLedgerName, errors
                        );
                    if (ledgerMeta == null) {
                        continue;
                    }
                    String role = normalizeLineRole(colMap.lineRole());
                    if ("PARTY".equals(role) && headerPartyLedgerId == null) {
                        headerPartyLedgerId = ledgerMeta.id();
                    }
                    coordMap.put("line:" + lineNo, sheetName + "!" + cell.cellRef());
                    List<VoucherDtos.BillAllocationDto> bills = buildAutoBillAllocIfNeeded(
                        ledgerMeta, parsedAmt.side(), parsedAmt.amount(), scale, refNumber, vchNumber, unitKey
                    );
                    lines.add(new VoucherDtos.VoucherLineRequest(
                        lineNo++, role, ledgerMeta.id(), parsedAmt.side(),
                        ExactMoneyValidator.formatExactScale(parsedAmt.amount(), scale),
                        null, null, null, null, null, false, null, null,
                        bills, List.of(), List.of()
                    ));
                    resolvedLedgerNames.add(ledgerMeta.name());
                    if ("DR".equals(parsedAmt.side())) {
                        unitDr = unitDr.add(parsedAmt.amount());
                    } else {
                        unitCr = unitCr.add(parsedAmt.amount());
                    }
                }

                // 3C. Classic 2-Column Debit Ledger / Credit Ledger + Amount (when no ledgerColumns mapped)
                if (mapping.ledgerColumns().isEmpty() && drLedgerCol != null && crLedgerCol != null && singleAmtCol != null) {
                    SpreadsheetFormatParser.ParsedCell drCell = row.cellAtOneBased(drLedgerCol);
                    SpreadsheetFormatParser.ParsedCell crCell = row.cellAtOneBased(crLedgerCol);
                    SpreadsheetFormatParser.ParsedCell amtCell = row.cellAtOneBased(singleAmtCol);
                    String drName = readCellTextChecked(
                        drCell, cachedFormulaConsent, sheetName, row.rowNumber(),
                        headerAt(detectedHeaders, drLedgerCol), "debitLedger", errors
                    );
                    String crName = readCellTextChecked(
                        crCell, cachedFormulaConsent, sheetName, row.rowNumber(),
                        headerAt(detectedHeaders, crLedgerCol), "creditLedger", errors
                    );
                    ParsedSignedAmount amt = parseCellAmount(
                        amtCell, "DR", scale, cachedFormulaConsent, sheetName,
                        row.rowNumber(), headerAt(detectedHeaders, singleAmtCol), "amount", errors
                    );
                    CompanyLedgerMeta drMeta = drName.isBlank() ? null : resolveCompanyLedger(
                        drName, ledgersByLower, sheetName, row.rowNumber(), drCell,
                        headerAt(detectedHeaders, drLedgerCol), "debitLedger", errors
                    );
                    CompanyLedgerMeta crMeta = crName.isBlank() ? null : resolveCompanyLedger(
                        crName, ledgersByLower, sheetName, row.rowNumber(), crCell,
                        headerAt(detectedHeaders, crLedgerCol), "creditLedger", errors
                    );
                    if (amt != null && amt.amount().signum() > 0 && drMeta != null && crMeta != null) {
                        String fmtAmt = ExactMoneyValidator.formatExactScale(amt.amount(), scale);
                        lines.add(new VoucherDtos.VoucherLineRequest(
                            lineNo++, "LEDGER", drMeta.id(), "DR", fmtAmt,
                            null, null, null, null, null, false, null, null,
                            buildAutoBillAllocIfNeeded(drMeta, "DR", amt.amount(), scale, refNumber, vchNumber, unitKey),
                            List.of(), List.of()
                        ));
                        lines.add(new VoucherDtos.VoucherLineRequest(
                            lineNo++, "LEDGER", crMeta.id(), "CR", fmtAmt,
                            null, null, null, null, null, false, null, null,
                            buildAutoBillAllocIfNeeded(crMeta, "CR", amt.amount(), scale, refNumber, vchNumber, unitKey),
                            List.of(), List.of()
                        ));
                        resolvedLedgerNames.add(drMeta.name());
                        resolvedLedgerNames.add(crMeta.name());
                        unitDr = unitDr.add(amt.amount());
                        unitCr = unitCr.add(amt.amount());
                    }
                }
            }

            if (errors.isEmpty()) {
                if (lines.size() < 2) {
                    errors.add(cellError(
                        "MC-SHEET-INSUFFICIENT-LINES", "lines", sheetName,
                        firstRow.rowNumber(), 1, "A" + firstRow.rowNumber(), "", "",
                        "Voucher unit requires at least 2 non-zero accounting ledger lines."
                    ));
                } else if (unitDr.compareTo(unitCr) != 0) {
                    errors.add(cellError(
                        "MC-SHEET-UNIT-UNBALANCED", "lines", sheetName,
                        firstRow.rowNumber(), 1, "A" + firstRow.rowNumber(), "", "",
                        "Voucher unit is unbalanced: total DR "
                            + ExactMoneyValidator.formatExactScale(unitDr, scale)
                            + " != total CR "
                            + ExactMoneyValidator.formatExactScale(unitCr, scale) + "."
                    ));
                }
            }

            String requestedAction = normalizeVoucherAction(mapping.voucherAction());
            VoucherDtos.UpsertVoucherRequest voucherPayload = null;
            if (errors.isEmpty() && vchTypeMeta != null && voucherDate != null) {
                voucherPayload = new VoucherDtos.UpsertVoucherRequest(
                    vchTypeMeta.id(),
                    vchTypeMeta.name(),
                    null,
                    vchNumber,
                    voucherDate,
                    voucherDate,
                    null,
                    "AS_VOUCHER",
                    "NORMAL",
                    requestedAction,
                    refNumber,
                    refNumber != null ? voucherDate : null,
                    headerPartyLedgerId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    narration,
                    null,
                    null,
                    List.copyOf(lines),
                    List.of()
                );

                // Dry-run active company extension rules (EXT-004) so preview & commit behave identically!
                VoucherExtensionValidator extValidator = extensionValidatorProvider == null
                    ? null
                    : extensionValidatorProvider.getIfAvailable();
                if (extValidator != null) {
                    try {
                        extValidator.validateVoucher(
                            new VoucherExtensionValidator.VoucherExtensionContext(
                                actor,
                                companyId,
                                null,
                                vchTypeMeta.name(),
                                vchTypeMeta.baseCategory(),
                                "POST".equals(requestedAction) ? "POSTED" : "DRAFT",
                                "NORMAL",
                                voucherDate,
                                voucherDate,
                                vchNumber,
                                refNumber,
                                narration,
                                "INR",
                                unitDr,
                                unitCr,
                                lines.size(),
                                "LIVE",
                                List.copyOf(resolvedLedgerNames),
                                voucherPayload
                            )
                        );
                    } catch (MoneyCraftException ex) {
                        errors.add(cellError(
                            ex.code(),
                            "extensionRule",
                            sheetName,
                            firstRow.rowNumber(),
                            1,
                            "A" + firstRow.rowNumber(),
                            "",
                            "",
                            ex.getMessage()
                        ));
                    }
                }
            }

            boolean valid = errors.isEmpty() && voucherPayload != null;
            if (valid) {
                validCount++;
                grandDr = grandDr.add(unitDr);
                grandCr = grandCr.add(unitCr);
            } else {
                invalidCount++;
            }

            units.add(new PreparedUnitPlan(
                unitSeq++,
                unitKey,
                "ACCOUNTING_VOUCHERS",
                new SpreadsheetImportDtos.SourceCoordinatesDto(sheetName, rowNums, coordMap),
                voucherPayload != null ? toJson(voucherPayload) : "{}",
                unitDr,
                unitCr,
                valid ? "VALID" : "INVALID",
                List.copyOf(errors)
            ));
        }

        return new DryRunPlan(detectedHeaders, List.copyOf(units), validCount, invalidCount, grandDr, grandCr);
    }

    private DryRunPlan buildLedgerUnits(
        UUID companyId,
        int scale,
        String sheetName,
        List<String> detectedHeaders,
        Map<String, Integer> colByHeaderLower,
        List<SpreadsheetFormatParser.ParsedRow> dataRows,
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto mapping,
        boolean cachedFormulaConsent
    ) {
        Map<String, UUID> groupsByLower = loadCompanyGroups(companyId);

        Integer nameCol = resolveColumnWithFallbacks(
            mapping.ledgerNameColumn(), colByHeaderLower, "name", "ledger name", "ledgername"
        );
        Integer aliasCol = resolveColumnWithFallbacks(
            mapping.aliasCodeColumn(), colByHeaderLower, "alias", "alias code", "code"
        );
        Integer grpCol = resolveColumnWithFallbacks(
            mapping.groupNameColumn(), colByHeaderLower, "group", "group name", "under", "parent"
        );
        Integer obCol = resolveColumnWithFallbacks(
            mapping.openingBalanceColumn(), colByHeaderLower, "opening balance", "openingbalance", "opening"
        );
        Integer sideCol = resolveColumnWithFallbacks(
            mapping.openingSideColumn(), colByHeaderLower, "opening side", "side", "dr/cr"
        );
        Integer gstinCol = resolveColumnWithFallbacks(
            mapping.gstinColumn(), colByHeaderLower, "gstin", "party gstin"
        );
        Integer stateCodeCol = resolveColumnWithFallbacks(
            mapping.stateCodeColumn(), colByHeaderLower, "state code", "statecode"
        );
        Integer stateNameCol = resolveColumnWithFallbacks(
            mapping.stateNameColumn(), colByHeaderLower, "state", "state name"
        );
        Integer billWiseCol = resolveColumnWithFallbacks(
            mapping.billWiseColumn(), colByHeaderLower, "bill wise", "billwise"
        );

        List<PreparedUnitPlan> units = new ArrayList<>();
        BigDecimal grandDr = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        BigDecimal grandCr = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        int validCount = 0;
        int invalidCount = 0;
        int seq = 1;

        for (SpreadsheetFormatParser.ParsedRow row : dataRows) {
            List<SpreadsheetImportDtos.CellValidationErrorDto> errors = new ArrayList<>();
            Map<String, String> coords = new LinkedHashMap<>();

            String ledgerName = nameCol == null ? "" : readCellTextChecked(
                row.cellAtOneBased(nameCol), cachedFormulaConsent, sheetName,
                row.rowNumber(), headerAt(detectedHeaders, nameCol), "name", errors
            );
            if (ledgerName.isBlank()) {
                errors.add(cellError(
                    "MC-SHEET-LEDGER-NAME-REQUIRED", "name", sheetName,
                    row.rowNumber(), nameCol == null ? 1 : nameCol,
                    "A" + row.rowNumber(), "", "", "Ledger name is required."
                ));
            } else if (nameCol != null) {
                coords.put("name", sheetName + "!" + row.cellAtOneBased(nameCol).cellRef());
            }

            String alias = aliasCol == null ? null : emptyToNull(readCellTextChecked(
                row.cellAtOneBased(aliasCol), cachedFormulaConsent, sheetName,
                row.rowNumber(), headerAt(detectedHeaders, aliasCol), "aliasCode", errors
            ));
            String grpName = grpCol == null ? "Sundry Debtors" : readCellTextChecked(
                row.cellAtOneBased(grpCol), cachedFormulaConsent, sheetName,
                row.rowNumber(), headerAt(detectedHeaders, grpCol), "groupName", errors
            );
            UUID grpId = groupsByLower.get(grpName.trim().toLowerCase(Locale.ROOT));
            if (grpId == null) {
                int c = grpCol == null ? 1 : grpCol;
                errors.add(cellError(
                    "MC-SHEET-GROUP-NOT-FOUND", "groupName", sheetName,
                    row.rowNumber(), c, SpreadsheetFormatParser.toColumnLetters(c - 1) + row.rowNumber(),
                    headerAt(detectedHeaders, c), grpName,
                    "Account group '" + grpName + "' does not exist in company."
                ));
            }

            BigDecimal ob = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
            if (obCol != null && row.cellAtOneBased(obCol).kind() != SpreadsheetFormatParser.CellKind.BLANK) {
                ParsedSignedAmount parsedOb = parseCellAmount(
                    row.cellAtOneBased(obCol), "DR", scale, cachedFormulaConsent,
                    sheetName, row.rowNumber(), headerAt(detectedHeaders, obCol), "openingBalance", errors
                );
                if (parsedOb != null) {
                    ob = parsedOb.amount();
                }
            }
            String side = sideCol == null ? "DR" : readCellTextChecked(
                row.cellAtOneBased(sideCol), cachedFormulaConsent, sheetName,
                row.rowNumber(), headerAt(detectedHeaders, sideCol), "openingSide", errors
            ).toUpperCase(Locale.ROOT);
            if (side.isBlank()) {
                side = "DR";
            } else if (!"DR".equals(side) && !"CR".equals(side)) {
                errors.add(cellError(
                    "MC-SHEET-SIDE-INVALID", "openingSide", sheetName,
                    row.rowNumber(), sideCol, row.cellAtOneBased(sideCol).cellRef(),
                    headerAt(detectedHeaders, sideCol), side, "openingSide must be DR or CR."
                ));
            }

            String gstin = gstinCol == null ? null : emptyToNull(readCellTextChecked(
                row.cellAtOneBased(gstinCol), cachedFormulaConsent, sheetName,
                row.rowNumber(), headerAt(detectedHeaders, gstinCol), "gstin", errors
            ));
            String stateCode = stateCodeCol == null ? null : emptyToNull(readCellTextChecked(
                row.cellAtOneBased(stateCodeCol), cachedFormulaConsent, sheetName,
                row.rowNumber(), headerAt(detectedHeaders, stateCodeCol), "stateCode", errors
            ));
            String stateName = stateNameCol == null ? null : emptyToNull(readCellTextChecked(
                row.cellAtOneBased(stateNameCol), cachedFormulaConsent, sheetName,
                row.rowNumber(), headerAt(detectedHeaders, stateNameCol), "stateName", errors
            ));
            if (gstin != null) {
                try {
                    gstin = ExactMoneyValidator.validatePartyGstin(gstin, stateCode);
                } catch (MoneyCraftException ex) {
                    errors.add(cellError(
                        ex.code(), "gstin", sheetName,
                        row.rowNumber(), gstinCol, row.cellAtOneBased(gstinCol).cellRef(),
                        headerAt(detectedHeaders, gstinCol), gstin, ex.getMessage()
                    ));
                }
            }
            boolean billWise = billWiseCol != null && isTruthy(readCellTextChecked(
                row.cellAtOneBased(billWiseCol), cachedFormulaConsent, sheetName,
                row.rowNumber(), headerAt(detectedHeaders, billWiseCol), "billWise", errors
            ));

            LedgerDtos.CreateOrUpdateLedgerRequest payload = null;
            if (errors.isEmpty() && grpId != null) {
                payload = new LedgerDtos.CreateOrUpdateLedgerRequest(
                    ledgerName.trim(),
                    alias,
                    grpId,
                    ExactMoneyValidator.formatExactScale(ob, scale),
                    side,
                    null,
                    billWise,
                    null,
                    false,
                    null,
                    false,
                    false,
                    "NONE",
                    null,
                    "NOT_APPLICABLE",
                    "GOODS",
                    null,
                    null,
                    "NONE",
                    "BY_VALUE",
                    gstin != null ? "REGULAR" : "UNKNOWN",
                    "IN",
                    stateCode,
                    stateName,
                    null,
                    gstin,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    true,
                    null
                );
            }

            BigDecimal uDr = "DR".equals(side) ? ob : BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
            BigDecimal uCr = "CR".equals(side) ? ob : BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
            boolean valid = errors.isEmpty() && payload != null;
            if (valid) {
                validCount++;
                grandDr = grandDr.add(uDr);
                grandCr = grandCr.add(uCr);
            } else {
                invalidCount++;
            }

            units.add(new PreparedUnitPlan(
                seq++,
                "LEDGER-ROW-" + row.rowNumber(),
                "LEDGERS",
                new SpreadsheetImportDtos.SourceCoordinatesDto(sheetName, List.of(row.rowNumber()), coords),
                payload != null ? toJson(payload) : "{}",
                uDr,
                uCr,
                valid ? "VALID" : "INVALID",
                List.copyOf(errors)
            ));
        }

        return new DryRunPlan(detectedHeaders, List.copyOf(units), validCount, invalidCount, grandDr, grandCr);
    }

    // =========================================================================
    // Atomic Single-Unit Execution
    // =========================================================================

    private void executeSingleUnitAtomically(
        MoneyCraftPrincipal actor,
        UUID companyId,
        String targetType,
        StoredUnitRow unit,
        String effectiveVoucherAction
    ) {
        try {
            requiresNewTxTemplate.executeWithoutResult(tx -> {
                List<String> curState = businessJdbcTemplate.query(
                    """
                    SELECT execution_status
                      FROM mc_sheet_import_unit
                     WHERE company_id = ? AND id = ?
                       FOR UPDATE
                    """,
                    (rs, rowNum) -> rs.getString("execution_status"),
                    companyId,
                    unit.id()
                );
                if (curState.isEmpty() || !"PENDING".equals(curState.getFirst())) {
                    return;
                }

                UUID resultingId;
                String resultingRef;
                if ("LEDGERS".equals(targetType)) {
                    LedgerDtos.CreateOrUpdateLedgerRequest req = readJson(
                        unit.intendedPayloadJson(),
                        LedgerDtos.CreateOrUpdateLedgerRequest.class
                    );
                    LedgerDtos.LedgerResponse created = ledgerService.createLedger(actor, companyId, req);
                    resultingId = created.id();
                    resultingRef = created.name();
                } else {
                    VoucherDtos.UpsertVoucherRequest storedReq = readJson(
                        unit.intendedPayloadJson(),
                        VoucherDtos.UpsertVoucherRequest.class
                    );
                    VoucherDtos.UpsertVoucherRequest effectiveReq = new VoucherDtos.UpsertVoucherRequest(
                        storedReq.voucherTypeId(),
                        storedReq.voucherTypeName(),
                        storedReq.voucherClassId(),
                        storedReq.voucherNumber(),
                        storedReq.voucherDate(),
                        storedReq.effectiveDate(),
                        storedReq.reversingApplicableUpto(),
                        storedReq.persistedView(),
                        storedReq.postingNature(),
                        effectiveVoucherAction,
                        storedReq.supplierInvoiceNo(),
                        storedReq.supplierInvoiceDate(),
                        storedReq.partyLedgerId(),
                        storedReq.accountingLedgerId(),
                        storedReq.placeOfSupplyState(),
                        storedReq.companyGstinId(),
                        storedReq.partyGstin(),
                        storedReq.partyMailingName(),
                        storedReq.partyStateCode(),
                        storedReq.isReverseCharge(),
                        storedReq.exportType(),
                        storedReq.currencyCode(),
                        storedReq.exchangeRate(),
                        storedReq.narration(),
                        storedReq.sourceGuid(),
                        storedReq.versionNo(),
                        storedReq.lines(),
                        storedReq.inventoryLines()
                    );
                    VoucherDtos.VoucherDetailDto created = voucherService.createVoucher(actor, companyId, effectiveReq);
                    resultingId = created.id();
                    resultingRef = created.voucherNumber();
                }

                businessJdbcTemplate.update(
                    """
                    UPDATE mc_sheet_import_unit
                       SET execution_status = 'COMMITTED',
                           resulting_entity_id = ?,
                           resulting_reference = ?,
                           execution_error_code = NULL,
                           execution_error_message = NULL,
                           committed_at = clock_timestamp(),
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    resultingId,
                    resultingRef,
                    companyId,
                    unit.id()
                );
            });
        } catch (MoneyCraftException ex) {
            recordUnitFailureInNewTx(companyId, unit.id(), ex.code(), ex.getMessage());
        } catch (RuntimeException ex) {
            recordUnitFailureInNewTx(
                companyId,
                unit.id(),
                "MC-SHEET-UNIT-EXEC-FAILED",
                ex.getMessage() == null ? "Unit execution failed" : ex.getMessage()
            );
        }
    }

    private void recordUnitFailureInNewTx(UUID companyId, UUID unitId, String code, String message) {
        String safeCode = code == null ? "MC-SHEET-UNIT-FAILED" : (code.length() > 64 ? code.substring(0, 64) : code);
        String safeMsg = message == null ? "" : (message.length() > 500 ? message.substring(0, 500) : message);
        requiresNewTxTemplate.executeWithoutResult(tx ->
            businessJdbcTemplate.update(
                """
                UPDATE mc_sheet_import_unit
                   SET execution_status = 'FAILED',
                       execution_error_code = ?,
                       execution_error_message = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ? AND execution_status = 'PENDING'
                """,
                safeCode,
                safeMsg,
                companyId,
                unitId
            )
        );
    }

    private void reconcileBatchCountersAndStatus(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID batchId,
        int mappingRevision,
        boolean interruptedByLimit
    ) {
        BatchRow lockedBatch = requireBatchRow(companyId, batchId, true);
        if ("CANCELLED".equals(lockedBatch.status())) {
            return;
        }
        UnitStatusCounts counts = queryUnitCounts(companyId, batchId, mappingRevision);
        String finalStatus = (interruptedByLimit || counts.pending() > 0 || counts.failed() > 0 || counts.uncertain() > 0)
            ? "PARTIAL_INTERRUPTED"
            : "COMPLETED";

        businessJdbcTemplate.update(
            """
            UPDATE mc_sheet_import_batch
               SET status = ?,
                   committed_units = ?,
                   failed_units = ?,
                   skipped_units = ?,
                   uncertain_units = ?,
                   version_no = version_no + 1,
                   updated_at = clock_timestamp(),
                   completed_at = CASE WHEN ? = 'COMPLETED' THEN clock_timestamp() ELSE completed_at END
             WHERE company_id = ? AND id = ?
            """,
            finalStatus,
            counts.committed(),
            counts.failed(),
            counts.skipped(),
            counts.uncertain(),
            finalStatus,
            companyId,
            batchId
        );

        if ("COMPLETED".equals(finalStatus)) {
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_import_job (
                    id, company_id, job_type, status, source_snapshot_sha256,
                    total_records_read, valid_records_count, error_records_count,
                    diagnostics_json, initiated_by, committed_by, completed_at
                ) VALUES (?, ?, 'SHEET_GUIDED', 'COMMITTED', ?, ?, ?, ?, ?::jsonb, ?, ?, clock_timestamp())
                """,
                UUID.randomUUID(),
                companyId,
                lockedBatch.sourceSha256(),
                lockedBatch.totalUnits(),
                counts.committed(),
                counts.failed(),
                toJson(List.of("Guided Spreadsheet Batch " + batchId)),
                lockedBatch.actorPrincipalId(),
                actor.principalId()
            );
        }

        auditChainService.appendCompanyEvent(
            companyId,
            actor.principalId(),
            actor.kind(),
            "SHEET_BATCH_EXECUTE",
            "SHEET_IMPORT_BATCH",
            batchId.toString(),
            "SUCCESS",
            null,
            Map.of(
                "status", finalStatus,
                "committedUnits", counts.committed(),
                "failedUnits", counts.failed(),
                "pendingUnits", counts.pending()
            )
        );
    }

    // =========================================================================
    // Source Capture & Cell Conversion Helpers
    // =========================================================================

    private CapturedSourceSnapshot captureAndFreezeSource(
        SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest req
    ) {
        if (req.googleSheetCapture() != null) {
            SpreadsheetImportDtos.GoogleSheetCaptureSpecDto gs = req.googleSheetCapture();
            if (gs.frozenRows() != null && !gs.frozenRows().isEmpty()) {
                byte[] frozenBytes = toJson(gs.frozenRows()).getBytes(StandardCharsets.UTF_8);
                String name = "google-sheet-" + (gs.spreadsheetId() == null ? "snapshot" : gs.spreadsheetId()) + ".json";
                SpreadsheetFormatParser.ParsedWorkbook wb =
                    formatParser.buildFromGoogleSheetsRows(gs.sheetName(), gs.frozenRows());
                return new CapturedSourceSnapshot(name, frozenBytes, wb);
            }
            if (!connectorRegistry.isConnectorSpiEnabled()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-SHEET-CONNECTOR-DISABLED",
                    "Google Sheets Outbound Connector Disabled",
                    "Optional Google Sheets outbound capture is disabled in air-gapped private-LAN mode; upload .xlsx/.ods/.csv directly."
                );
            }
            URI uri = URI.create(
                gs.endpointUri() == null || gs.endpointUri().isBlank()
                    ? "https://sheets.googleapis.com/v4/spreadsheets/" + gs.spreadsheetId()
                    : gs.endpointUri().trim()
            );
            OutboundConnectorAdapter adapter = connectorRegistry.requireEnabledConnector("GOOGLE_SHEETS", uri);
            if (!(adapter instanceof StatutoryConnectorHttpAdapter httpAdapter)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-SHEET-CONNECTOR-UNQUALIFIED",
                    "Google Sheets Adapter Not Qualified",
                    "Registered GOOGLE_SHEETS connector does not implement qualified HTTP transport."
                );
            }
            String maskPayload = toJson(Map.of(
                "spreadsheetId", gs.spreadsheetId() == null ? "" : gs.spreadsheetId(),
                "range", gs.rangeA1() == null ? "A1:ZZ5000" : gs.rangeA1()
            ));
            StatutoryHttpResponse resp = httpAdapter.postJson(uri, Map.of("Accept", "application/json"), maskPayload);
            byte[] respBytes = resp.body().getBytes(StandardCharsets.UTF_8);
            List<List<String>> parsedRows = parseGoogleSheetsApiJson(resp.body());
            SpreadsheetFormatParser.ParsedWorkbook wb =
                formatParser.buildFromGoogleSheetsRows(gs.sheetName(), parsedRows);
            return new CapturedSourceSnapshot("google-sheet-" + gs.spreadsheetId() + ".json", respBytes, wb);
        }

        if (req.base64Content() != null && !req.base64Content().isBlank()) {
            String b64 = req.base64Content().trim();
            int comma = b64.indexOf(',');
            if (b64.startsWith("data:") && comma > 0) {
                b64 = b64.substring(comma + 1);
            }
            try {
                byte[] decoded = Base64.getMimeDecoder().decode(b64);
                String fName = req.fileName() == null || req.fileName().isBlank() ? "upload.xlsx" : req.fileName().trim();
                return new CapturedSourceSnapshot(fName, decoded, null);
            } catch (IllegalArgumentException ex) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-SHEET-BASE64-INVALID",
                    "Invalid Base64 Spreadsheet Content",
                    "Failed to decode base64 spreadsheet payload."
                );
            }
        }

        if (req.csvText() != null && !req.csvText().isBlank()) {
            byte[] csvBytes = req.csvText().getBytes(StandardCharsets.UTF_8);
            String fName = req.fileName() == null || req.fileName().isBlank() ? "upload.csv" : req.fileName().trim();
            return new CapturedSourceSnapshot(fName, csvBytes, null);
        }

        throw new MoneyCraftException(
            HttpStatus.BAD_REQUEST,
            "MC-SHEET-SOURCE-MISSING",
            "Spreadsheet Source Missing",
            "Provide base64Content, csvText, multipart file, or googleSheetCapture."
        );
    }

    private List<List<String>> parseGoogleSheetsApiJson(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode values = root.has("values") ? root.get("values") : root;
            if (!values.isArray()) {
                return List.of();
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode rNode : values) {
                if (rNode.isArray()) {
                    List<String> cols = new ArrayList<>();
                    for (JsonNode cNode : rNode) {
                        cols.add(cNode.isNull() ? "" : cNode.asString());
                    }
                    rows.add(cols);
                }
            }
            return rows;
        } catch (JacksonException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-GOOGLE-JSON-INVALID",
                "Invalid Google Sheets Response",
                "Failed to parse Google Sheets values JSON."
            );
        }
    }

    private static boolean checkFormulaPolicy(
        SpreadsheetFormatParser.ParsedCell cell,
        boolean cachedFormulaConsent,
        String sheetName,
        int rowNumber,
        String colHeader,
        String fieldName,
        List<SpreadsheetImportDtos.CellValidationErrorDto> errors
    ) {
        if (cell.kind() == SpreadsheetFormatParser.CellKind.FORMULA_ERROR) {
            errors.add(cellError(
                "MC-SHEET-FORMULA-ERROR",
                fieldName,
                sheetName,
                rowNumber,
                cell.columnIndex(),
                cell.cellRef(),
                colHeader,
                cell.rawText(),
                "Spreadsheet cell carries formula evaluation error '" + cell.rawText()
                    + "'; formulas are never recalculated and error cells are rejected."
            ));
            return false;
        }
        if (cell.fromFormula() && !cachedFormulaConsent) {
            errors.add(cellError(
                "MC-SHEET-FORMULA-CONSENT-REQUIRED",
                fieldName,
                sheetName,
                rowNumber,
                cell.columnIndex(),
                cell.cellRef(),
                colHeader,
                cell.rawText(),
                "Cell contains formula '=" + (cell.formulaText() == null ? "" : cell.formulaText())
                    + "'; confirm cachedFormulaConsent=true to admit cached formula values."
            ));
            return false;
        }
        return true;
    }

    private static String readCellTextChecked(
        SpreadsheetFormatParser.ParsedCell cell,
        boolean cachedFormulaConsent,
        String sheetName,
        int rowNumber,
        String colHeader,
        String fieldName,
        List<SpreadsheetImportDtos.CellValidationErrorDto> errors
    ) {
        if (!checkFormulaPolicy(cell, cachedFormulaConsent, sheetName, rowNumber, colHeader, fieldName, errors)) {
            return "";
        }
        return cell.rawText() == null ? "" : cell.rawText().trim();
    }

    private static ParsedSignedAmount parseCellAmount(
        SpreadsheetFormatParser.ParsedCell cell,
        String declaredSide,
        int scale,
        boolean cachedFormulaConsent,
        String sheetName,
        int rowNumber,
        String colHeader,
        String fieldName,
        List<SpreadsheetImportDtos.CellValidationErrorDto> errors
    ) {
        if (!checkFormulaPolicy(cell, cachedFormulaConsent, sheetName, rowNumber, colHeader, fieldName, errors)) {
            return null;
        }
        if (cell.kind() == SpreadsheetFormatParser.CellKind.BLANK) {
            return null;
        }

        BigDecimal rawBd;
        if (cell.numericValue() != null) {
            rawBd = cell.numericValue();
        } else {
            String cleaned = cell.rawText() == null ? "" : cell.rawText().trim();
            cleaned = cleaned.replace("₹", "").replace(",", "").trim();
            if (cleaned.isEmpty() || "-".equals(cleaned)) {
                return null;
            }
            if (cleaned.contains("e") || cleaned.contains("E")) {
                errors.add(cellError(
                    "MC-ACC-DECIMAL-INVALID", fieldName, sheetName,
                    rowNumber, cell.columnIndex(), cell.cellRef(), colHeader, cell.rawText(),
                    "Exponent notation '" + cell.rawText() + "' is forbidden; store exact fixed-point decimals."
                ));
                return null;
            }
            try {
                rawBd = new BigDecimal(cleaned);
            } catch (NumberFormatException ex) {
                errors.add(cellError(
                    "MC-ACC-DECIMAL-INVALID", fieldName, sheetName,
                    rowNumber, cell.columnIndex(), cell.cellRef(), colHeader, cell.rawText(),
                    "Invalid decimal amount '" + cell.rawText() + "'."
                ));
                return null;
            }
        }

        BigDecimal scaled;
        try {
            scaled = rawBd.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            errors.add(cellError(
                "MC-ACC-SCALE-OVERFLOW", fieldName, sheetName,
                rowNumber, cell.columnIndex(), cell.cellRef(), colHeader, cell.rawText(),
                "Amount '" + rawBd.toPlainString() + "' exceeds company minor-unit scale " + scale + "."
            ));
            return null;
        }

        if (scaled.signum() == 0) {
            return new ParsedSignedAmount("DR", scaled.abs());
        }

        String sideSpec = declaredSide == null ? "DR" : declaredSide.trim().toUpperCase(Locale.ROOT);
        if ("SIGNED".equals(sideSpec) || "SIGNED_DR".equals(sideSpec)) {
            return scaled.signum() >= 0
                ? new ParsedSignedAmount("DR", scaled)
                : new ParsedSignedAmount("CR", scaled.abs());
        }
        if ("SIGNED_CR".equals(sideSpec)) {
            return scaled.signum() >= 0
                ? new ParsedSignedAmount("CR", scaled)
                : new ParsedSignedAmount("DR", scaled.abs());
        }
        if (scaled.signum() < 0) {
            errors.add(cellError(
                "MC-SHEET-NEGATIVE-AMOUNT", fieldName, sheetName,
                rowNumber, cell.columnIndex(), cell.cellRef(), colHeader, cell.rawText(),
                "Negative amount requires SIGNED or SIGNED_CR column side policy."
            ));
            return null;
        }
        String finalSide = "CR".equals(sideSpec) ? "CR" : "DR";
        return new ParsedSignedAmount(finalSide, scaled);
    }

    private static CompanyLedgerMeta resolveCompanyLedger(
        String rawName,
        Map<String, CompanyLedgerMeta> ledgersByLower,
        String sheetName,
        int rowNumber,
        SpreadsheetFormatParser.ParsedCell cell,
        String colHeader,
        String fieldName,
        List<SpreadsheetImportDtos.CellValidationErrorDto> errors
    ) {
        CompanyLedgerMeta found = ledgersByLower.get(rawName.trim().toLowerCase(Locale.ROOT));
        if (found == null) {
            errors.add(cellError(
                "MC-SHEET-LEDGER-NOT-FOUND",
                fieldName,
                sheetName,
                rowNumber,
                cell.columnIndex(),
                cell.cellRef(),
                colHeader,
                rawName,
                "Ledger '" + rawName + "' does not exist in selected company (silent master creation is prohibited)."
            ));
        }
        return found;
    }

    private static CompanyLedgerMeta findLedgerById(UUID id, Map<String, CompanyLedgerMeta> ledgersByLower) {
        for (CompanyLedgerMeta meta : ledgersByLower.values()) {
            if (id.equals(meta.id())) {
                return meta;
            }
        }
        return null;
    }

    private static List<VoucherDtos.BillAllocationDto> buildAutoBillAllocIfNeeded(
        CompanyLedgerMeta meta,
        String side,
        BigDecimal amount,
        int scale,
        String refNo,
        String vchNo,
        String unitKey
    ) {
        if (!meta.billWise()) {
            return List.of();
        }
        String billName = firstNonBlank(refNo, vchNo, unitKey);
        return List.of(new VoucherDtos.BillAllocationDto(
            null,
            "NEW_REF",
            billName,
            null,
            null,
            side,
            ExactMoneyValidator.formatExactScale(amount, scale),
            null,
            null,
            null,
            null,
            false
        ));
    }

    private static Integer resolveColumnWithFallbacks(
        String explicitSpec,
        Map<String, Integer> colByHeaderLower,
        String... fallbacks
    ) {
        Integer explicit = resolveColumnOneBased(explicitSpec, colByHeaderLower);
        if (explicit != null) {
            return explicit;
        }
        for (String fb : fallbacks) {
            Integer idx = colByHeaderLower.get(fb.toLowerCase(Locale.ROOT));
            if (idx != null) {
                return idx;
            }
        }
        return null;
    }

    private static Integer resolveColumnOneBased(String spec, Map<String, Integer> colByHeaderLower) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String trimmed = spec.trim();
        Integer byHeader = colByHeaderLower.get(trimmed.toLowerCase(Locale.ROOT));
        if (byHeader != null) {
            return byHeader;
        }
        if (trimmed.chars().allMatch(Character::isDigit)) {
            int parsed = Integer.parseInt(trimmed);
            return parsed >= 1 ? parsed : null;
        }
        if (trimmed.length() <= 3 && trimmed.chars().allMatch(ch -> (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'))) {
            String upper = trimmed.toUpperCase(Locale.ROOT);
            int val = 0;
            for (int i = 0; i < upper.length(); i++) {
                val = (val * 26) + (upper.charAt(i) - 'A' + 1);
            }
            return val >= 1 ? val : null;
        }
        return null;
    }

    private static SpreadsheetFormatParser.ParsedSheet selectSheet(
        SpreadsheetFormatParser.ParsedWorkbook wb,
        String requestedSheet
    ) {
        if (wb.sheets().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-NO-SHEETS",
                "Workbook Contains Zero Sheets",
                "Uploaded spreadsheet has no readable tables/sheets."
            );
        }
        if (requestedSheet == null || requestedSheet.isBlank()) {
            return wb.sheets().getFirst();
        }
        for (SpreadsheetFormatParser.ParsedSheet s : wb.sheets()) {
            if (s.name().equalsIgnoreCase(requestedSheet.trim())) {
                return s;
            }
        }
        return wb.sheets().getFirst();
    }

    // =========================================================================
    // Database Queries, RBAC & JSON Persistence Helpers
    // =========================================================================

    private void persistUnits(UUID companyId, UUID batchId, int mappingRevision, List<PreparedUnitPlan> units) {
        for (PreparedUnitPlan u : units) {
            String unitIdemKey = batchId + ":r" + mappingRevision + ":u" + u.unitSeq();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_sheet_import_unit (
                    id, company_id, batch_id, mapping_revision, unit_seq, unit_key,
                    target_type, source_coordinates_json, intended_payload_json,
                    unit_debit, unit_credit, validation_status, validation_errors_json,
                    unit_idempotency_key, execution_status
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?::jsonb, ?::jsonb,
                    ?, ?, ?, ?::jsonb,
                    ?, 'PENDING'
                )
                """,
                UUID.randomUUID(),
                companyId,
                batchId,
                mappingRevision,
                u.unitSeq(),
                u.unitKey(),
                u.targetType(),
                toJson(u.coordinates()),
                u.intendedPayloadJson(),
                u.unitDebit(),
                u.unitCredit(),
                u.validationStatus(),
                toJson(u.validationErrors()),
                unitIdemKey
            );
        }
    }

    private List<SpreadsheetImportDtos.SpreadsheetUnitDto> loadUnits(
        UUID companyId,
        UUID batchId,
        int mappingRevision,
        int scale
    ) {
        List<StoredUnitRow> rawRows = loadStoredUnitRows(companyId, batchId, mappingRevision);
        List<SpreadsheetImportDtos.SpreadsheetUnitDto> out = new ArrayList<>(rawRows.size());
        for (StoredUnitRow r : rawRows) {
            out.add(new SpreadsheetImportDtos.SpreadsheetUnitDto(
                r.id(),
                r.unitSeq(),
                r.unitKey(),
                r.targetType(),
                r.validationStatus(),
                r.executionStatus(),
                ExactMoneyValidator.formatExactScale(r.unitDebit(), scale),
                ExactMoneyValidator.formatExactScale(r.unitCredit(), scale),
                r.unitIdempotencyKey(),
                parseCoordinates(r.sourceCoordinatesJson()),
                summarizePayload(r.intendedPayloadJson()),
                parseValidationErrors(r.validationErrorsJson()),
                r.executionErrorCode(),
                r.executionErrorMessage(),
                r.resultingEntityId(),
                r.resultingReference(),
                r.committedAt()
            ));
        }
        return List.copyOf(out);
    }

    private List<StoredUnitRow> loadStoredUnitRows(UUID companyId, UUID batchId, int mappingRevision) {
        return businessJdbcTemplate.query(
            """
            SELECT id, unit_seq, unit_key, target_type, source_coordinates_json,
                   intended_payload_json, unit_debit, unit_credit, validation_status,
                   validation_errors_json, unit_idempotency_key, execution_status,
                   execution_error_code, execution_error_message, resulting_entity_id,
                   resulting_reference, committed_at
              FROM mc_sheet_import_unit
             WHERE company_id = ? AND batch_id = ? AND mapping_revision = ?
             ORDER BY unit_seq ASC
            """,
            (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("committed_at");
                return new StoredUnitRow(
                    rs.getObject("id", UUID.class),
                    rs.getInt("unit_seq"),
                    rs.getString("unit_key"),
                    rs.getString("target_type"),
                    rs.getString("source_coordinates_json"),
                    rs.getString("intended_payload_json"),
                    rs.getBigDecimal("unit_debit"),
                    rs.getBigDecimal("unit_credit"),
                    rs.getString("validation_status"),
                    rs.getString("validation_errors_json"),
                    rs.getString("unit_idempotency_key"),
                    rs.getString("execution_status"),
                    rs.getString("execution_error_code"),
                    rs.getString("execution_error_message"),
                    rs.getObject("resulting_entity_id", UUID.class),
                    rs.getString("resulting_reference"),
                    ts == null ? null : ts.toInstant()
                );
            },
            companyId,
            batchId,
            mappingRevision
        );
    }

    private BatchRow requireBatchRow(UUID companyId, UUID batchId, boolean lockForUpdate) {
        String sql = """
            SELECT id, company_id, source_file_name, source_cipher_gcm, source_sha256,
                   format_profile, target_type, sheet_name, available_sheets_json,
                   detected_headers_json, header_row_index, date_system, macro_detected,
                   formula_cells_detected, template_id, template_version, mapping_revision,
                   mapping_snapshot_json, cached_formula_policy_accepted, status,
                   total_units, valid_units, invalid_units, committed_units,
                   failed_units, skipped_units, uncertain_units,
                   total_debit, total_credit, diagnostics_json,
                   actor_principal_id, version_no, created_at, completed_at
              FROM mc_sheet_import_batch
             WHERE company_id = ? AND id = ?
            """ + (lockForUpdate ? " FOR UPDATE" : "");
        List<BatchRow> rows = businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> mapBatchRow(rs),
            companyId,
            batchId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-SHEET-BATCH-NOT-FOUND",
                "Spreadsheet Import Batch Not Found",
                "Batch " + batchId + " was not found in company " + companyId + "."
            );
        }
        return rows.getFirst();
    }

    private String queryBatchStatus(UUID companyId, UUID batchId) {
        List<String> rows = businessJdbcTemplate.query(
            "SELECT status FROM mc_sheet_import_batch WHERE company_id = ? AND id = ?",
            (rs, rowNum) -> rs.getString("status"),
            companyId,
            batchId
        );
        return rows.isEmpty() ? "CANCELLED" : rows.getFirst();
    }

    private UnitStatusCounts queryUnitCounts(UUID companyId, UUID batchId, int mappingRevision) {
        return businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FILTER (WHERE execution_status = 'PENDING') AS pending_cnt,
                   COUNT(*) FILTER (WHERE execution_status = 'COMMITTED') AS committed_cnt,
                   COUNT(*) FILTER (WHERE execution_status = 'FAILED') AS failed_cnt,
                   COUNT(*) FILTER (WHERE execution_status = 'SKIPPED') AS skipped_cnt,
                   COUNT(*) FILTER (WHERE execution_status = 'UNCERTAIN') AS uncertain_cnt
              FROM mc_sheet_import_unit
             WHERE company_id = ? AND batch_id = ? AND mapping_revision = ?
            """,
            (rs, rowNum) -> new UnitStatusCounts(
                rs.getInt("pending_cnt"),
                rs.getInt("committed_cnt"),
                rs.getInt("failed_cnt"),
                rs.getInt("skipped_cnt"),
                rs.getInt("uncertain_cnt")
            ),
            companyId,
            batchId,
            mappingRevision
        );
    }

    private UUID findPreviousMatchingBatchId(
        UUID companyId,
        byte[] sha256,
        String targetType,
        UUID excludeBatchId
    ) {
        List<UUID> rows = businessJdbcTemplate.query(
            """
            SELECT id
              FROM mc_sheet_import_batch
             WHERE company_id = ?
               AND source_sha256 = ?
               AND target_type = ?
               AND status <> 'CANCELLED'
               AND (?::uuid IS NULL OR id <> ?::uuid)
             ORDER BY created_at DESC
             LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            sha256,
            targetType,
            excludeBatchId,
            excludeBatchId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, CompanyLedgerMeta> loadCompanyLedgers(UUID companyId) {
        Map<String, CompanyLedgerMeta> map = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, name, alias_code, maintain_balances_bill_by_bill
              FROM mc_ledger
             WHERE company_id = ? AND active = true
            """,
            (rs, rowNum) -> {
                CompanyLedgerMeta meta = new CompanyLedgerMeta(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getBoolean("maintain_balances_bill_by_bill")
                );
                map.put(meta.name().trim().toLowerCase(Locale.ROOT), meta);
                String alias = rs.getString("alias_code");
                if (alias != null && !alias.isBlank()) {
                    map.putIfAbsent(alias.trim().toLowerCase(Locale.ROOT), meta);
                }
                return null;
            },
            companyId
        );
        return map;
    }

    private Map<String, CompanyVoucherTypeMeta> loadCompanyVoucherTypes(UUID companyId) {
        Map<String, CompanyVoucherTypeMeta> map = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, name, abbreviation, base_category
              FROM mc_voucher_type
             WHERE company_id = ? AND active = true
            """,
            (rs, rowNum) -> {
                CompanyVoucherTypeMeta vt = new CompanyVoucherTypeMeta(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("base_category")
                );
                map.put(vt.name().trim().toLowerCase(Locale.ROOT), vt);
                String abbr = rs.getString("abbreviation");
                if (abbr != null && !abbr.isBlank()) {
                    map.putIfAbsent(abbr.trim().toLowerCase(Locale.ROOT), vt);
                }
                return null;
            },
            companyId
        );
        return map;
    }

    private Map<String, UUID> loadCompanyGroups(UUID companyId) {
        Map<String, UUID> map = new HashMap<>();
        businessJdbcTemplate.query(
            "SELECT id, name FROM mc_account_group WHERE company_id = ?",
            (rs, rowNum) -> {
                map.put(rs.getString("name").trim().toLowerCase(Locale.ROOT), rs.getObject("id", UUID.class));
                return null;
            },
            companyId
        );
        return map;
    }

    private TemplateBinding resolveOptionalTemplate(UUID companyId, UUID templateId) {
        if (templateId == null) {
            return new TemplateBinding(null, null, null, null, null);
        }
        SpreadsheetImportDtos.SpreadsheetTemplateDto dto = requireTemplateDto(companyId, templateId);
        return new TemplateBinding(dto.id(), dto.versionNo(), dto.sheetName(), dto.headerRowIndex(), dto.mapping());
    }

    private SpreadsheetImportDtos.SpreadsheetTemplateDto requireTemplateDto(UUID companyId, UUID id) {
        List<SpreadsheetImportDtos.SpreadsheetTemplateDto> rows = businessJdbcTemplate.query(
            """
            SELECT id, company_id, name, target_type, sheet_name, header_row_index,
                   default_values_json, duplicate_handling, version_no, created_at
              FROM mc_exchange_mapping_template
             WHERE company_id = ? AND id = ?
            """,
            (rs, rowNum) -> mapTemplateRow(rs),
            companyId,
            id
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-SHEET-TEMPLATE-NOT-FOUND",
                "Spreadsheet Mapping Template Not Found",
                "Mapping template " + id + " does not exist in company " + companyId + "."
            );
        }
        return rows.getFirst();
    }

    private SpreadsheetImportDtos.SpreadsheetTemplateDto mapTemplateRow(ResultSet rs) throws SQLException {
        String targetType = rs.getString("target_type");
        String defaultJson = rs.getString("default_values_json");
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto spec = null;
        if (defaultJson != null && !defaultJson.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(defaultJson);
                if (root.has("guidedMappingSpec")) {
                    spec = objectMapper.treeToValue(
                        root.get("guidedMappingSpec"),
                        SpreadsheetImportDtos.SpreadsheetMappingSpecDto.class
                    );
                }
            } catch (JacksonException ignored) {
                spec = null;
            }
        }
        spec = normalizeMappingSpec(spec, normalizeTargetType(targetType));
        return new SpreadsheetImportDtos.SpreadsheetTemplateDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            targetType,
            rs.getString("sheet_name"),
            rs.getInt("header_row_index"),
            spec,
            rs.getString("duplicate_handling"),
            rs.getLong("version_no"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private SpreadsheetImportDtos.SpreadsheetBatchDto toBatchDto(
        BatchRow r,
        int scale,
        UUID priorBatchId,
        List<SpreadsheetImportDtos.SpreadsheetUnitDto> units
    ) {
        return new SpreadsheetImportDtos.SpreadsheetBatchDto(
            r.id(),
            r.companyId(),
            r.sourceFileName(),
            JcsCanonicalizer.toHex(r.sourceSha256()),
            r.formatProfile(),
            r.targetType(),
            r.sheetName(),
            parseStringList(r.availableSheetsJson()),
            parseStringList(r.detectedHeadersJson()),
            r.headerRowIndex(),
            r.dateSystem(),
            r.macroDetected(),
            r.formulaCellsDetected(),
            r.cachedFormulaPolicyAccepted(),
            r.templateId(),
            r.templateVersion(),
            r.mappingRevision(),
            parseMappingSpec(r.mappingSnapshotJson()),
            r.status(),
            priorBatchId,
            r.totalUnits(),
            r.validUnits(),
            r.invalidUnits(),
            r.committedUnits(),
            r.failedUnits(),
            r.skippedUnits(),
            r.uncertainUnits(),
            ExactMoneyValidator.formatExactScale(r.totalDebit(), scale),
            ExactMoneyValidator.formatExactScale(r.totalCredit(), scale),
            parseStringList(r.diagnosticsJson()),
            r.versionNo(),
            r.createdAt(),
            r.completedAt(),
            units
        );
    }

    private static BatchRow mapBatchRow(ResultSet rs) throws SQLException {
        Timestamp comp = rs.getTimestamp("completed_at");
        return new BatchRow(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("source_file_name"),
            rs.getBytes("source_cipher_gcm"),
            rs.getBytes("source_sha256"),
            rs.getString("format_profile"),
            rs.getString("target_type"),
            rs.getString("sheet_name"),
            rs.getString("available_sheets_json"),
            rs.getString("detected_headers_json"),
            rs.getInt("header_row_index"),
            rs.getString("date_system"),
            rs.getBoolean("macro_detected"),
            rs.getBoolean("formula_cells_detected"),
            rs.getObject("template_id", UUID.class),
            rs.getObject("template_version", Long.class),
            rs.getInt("mapping_revision"),
            rs.getString("mapping_snapshot_json"),
            rs.getBoolean("cached_formula_policy_accepted"),
            rs.getString("status"),
            rs.getInt("total_units"),
            rs.getInt("valid_units"),
            rs.getInt("invalid_units"),
            rs.getInt("committed_units"),
            rs.getInt("failed_units"),
            rs.getInt("skipped_units"),
            rs.getInt("uncertain_units"),
            rs.getBigDecimal("total_debit"),
            rs.getBigDecimal("total_credit"),
            rs.getString("diagnostics_json"),
            rs.getObject("actor_principal_id", UUID.class),
            rs.getLong("version_no"),
            rs.getTimestamp("created_at").toInstant(),
            comp == null ? null : comp.toInstant()
        );
    }

    private void requireImportRead(MoneyCraftPrincipal principal, UUID companyId) {
        if (principal == null) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-AUTH-REQUIRED",
                "Authentication Required",
                "Authenticated principal is required."
            );
        }
        rbacGuard.requireCompanyAccess(principal, companyId);
    }

    private void requireImportPrepare(MoneyCraftPrincipal principal, UUID companyId) {
        requireImportRead(principal, companyId);
        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)
            || hasExplicitPermission(principal.principalId(), companyId, PERM_IMPORT_PREPARE)) {
            return;
        }
        rbacGuard.requireCompanyRole(principal, companyId, PREPARE_AND_COMMIT_ROLES);
    }

    private void requireImportCommit(MoneyCraftPrincipal principal, UUID companyId) {
        requireImportRead(principal, companyId);
        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)
            || hasExplicitPermission(principal.principalId(), companyId, PERM_IMPORT_COMMIT)) {
            return;
        }
        rbacGuard.requireCompanyRole(principal, companyId, PREPARE_AND_COMMIT_ROLES);
    }

    private void requireTemplateManage(MoneyCraftPrincipal principal, UUID companyId) {
        requireImportRead(principal, companyId);
        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)
            || hasExplicitPermission(principal.principalId(), companyId, PERM_IMPORT_TEMPLATE_MANAGE)) {
            return;
        }
        rbacGuard.requireCompanyRole(principal, companyId, TEMPLATE_MANAGE_ROLES);
    }

    private boolean hasExplicitPermission(UUID principalId, UUID companyId, String perm) {
        List<String> rows = businessJdbcTemplate.query(
            """
            SELECT permissions::text AS perms_json
              FROM mc_principal_grant
             WHERE principal_id = ? AND company_id = ? AND active = true
            """,
            (rs, rowNum) -> rs.getString("perms_json"),
            principalId,
            companyId
        );
        Set<String> all = new HashSet<>();
        for (String json : rows) {
            for (String p : rbacGuard.parseStringArray(json)) {
                all.add(p.trim().toLowerCase(Locale.ROOT));
            }
        }
        return all.contains(perm.toLowerCase(Locale.ROOT));
    }

    private void ensurePrincipalProjected(MoneyCraftPrincipal actor) {
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_principal_projection (principal_id, kind, username, display_name, enabled, version_no)
            VALUES (?, ?, ?, ?, true, 1)
            ON CONFLICT (principal_id) DO NOTHING
            """,
            actor.principalId(),
            actor.kind(),
            actor.username(),
            actor.displayName() == null || actor.displayName().isBlank() ? actor.username() : actor.displayName()
        );
    }

    private boolean isPrincipalProjectedAndEnabled(UUID principalId) {
        List<Boolean> rows = businessJdbcTemplate.query(
            "SELECT enabled FROM mc_principal_projection WHERE principal_id = ?",
            (rs, rowNum) -> rs.getBoolean("enabled"),
            principalId
        );
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
    }

    private SpreadsheetImportDtos.CellValidationErrorDto findFirstValidationError(List<StoredUnitRow> units) {
        for (StoredUnitRow u : units) {
            List<SpreadsheetImportDtos.CellValidationErrorDto> list = parseValidationErrors(u.validationErrorsJson());
            if (!list.isEmpty()) {
                return list.getFirst();
            }
        }
        return null;
    }

    private static SpreadsheetImportDtos.CellValidationErrorDto cellError(
        String code,
        String field,
        String sheetName,
        int rowNo,
        int colIdx,
        String cellRef,
        String colHeader,
        String snippet,
        String message
    ) {
        String safeSnippet = snippet == null ? "" : (snippet.length() > 120 ? snippet.substring(0, 120) : snippet);
        return new SpreadsheetImportDtos.CellValidationErrorDto(
            code,
            field,
            sheetName,
            rowNo,
            colIdx,
            cellRef,
            colHeader == null ? "" : colHeader,
            safeSnippet,
            message
        );
    }

    private static String normalizeTargetType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "ACCOUNTING_VOUCHERS";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if ("LEDGERS".equals(u) || "LEDGER".equals(u)) {
            return "LEDGERS";
        }
        if ("ACCOUNTING_VOUCHERS".equals(u) || "VOUCHERS".equals(u) || "JOURNAL".equals(u)) {
            return "ACCOUNTING_VOUCHERS";
        }
        throw new MoneyCraftException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MC-SHEET-TARGET-UNSUPPORTED",
            "Unsupported Spreadsheet Import Target",
            "Target '" + raw + "' is not admitted; supported targets are ACCOUNTING_VOUCHERS and LEDGERS."
        );
    }

    private static String normalizeVoucherAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return "POST";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_VOUCHER_ACTIONS.contains(u)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-ACTION-INVALID",
                "Invalid Voucher Execution Action",
                "Action must be SAVE_DRAFT, SUBMIT_FOR_APPROVAL, or POST (migration bypasses are prohibited)."
            );
        }
        return u;
    }

    private static String normalizeLineRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return "LEDGER";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "PARTY", "ACCOUNTING", "LEDGER", "TAX", "DISCOUNT", "ADDITIONAL_CHARGE", "ROUND_OFF" -> u;
            default -> "LEDGER";
        };
    }

    private static String normalizeDupHandling(String raw) {
        if (raw == null || raw.isBlank()) {
            return "REJECT";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return Set.of("REJECT", "SKIP", "UPDATE_IF_DRAFT").contains(u) ? u : "REJECT";
    }

    private static SpreadsheetImportDtos.SpreadsheetMappingSpecDto normalizeMappingSpec(
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto raw,
        String targetType
    ) {
        if (raw == null) {
            return new SpreadsheetImportDtos.SpreadsheetMappingSpecDto(
                targetType, "POST", "Journal",
                null, null, null, null, null, null,
                null, null, "DR",
                null, null, null,
                List.of(),
                null, null, null, null, null, null, null, null, null,
                "AUTO", "REJECT"
            );
        }
        return new SpreadsheetImportDtos.SpreadsheetMappingSpecDto(
            targetType,
            raw.voucherAction() == null || raw.voucherAction().isBlank() ? "POST" : raw.voucherAction().trim(),
            raw.defaultVoucherType() == null || raw.defaultVoucherType().isBlank()
                ? "Journal" : raw.defaultVoucherType().trim(),
            raw.voucherDateColumn(),
            raw.voucherTypeColumn(),
            raw.voucherNumberColumn(),
            raw.referenceNumberColumn(),
            raw.narrationColumn(),
            raw.groupingKeyColumn(),
            raw.partyLedgerNameColumn(),
            raw.partyAmountColumn(),
            raw.partySide() == null || raw.partySide().isBlank() ? "DR" : raw.partySide().trim(),
            raw.debitLedgerColumn(),
            raw.creditLedgerColumn(),
            raw.amountColumn(),
            raw.ledgerColumns() == null ? List.of() : List.copyOf(raw.ledgerColumns()),
            raw.ledgerNameColumn(),
            raw.aliasCodeColumn(),
            raw.groupNameColumn(),
            raw.openingBalanceColumn(),
            raw.openingSideColumn(),
            raw.gstinColumn(),
            raw.stateCodeColumn(),
            raw.stateNameColumn(),
            raw.billWiseColumn(),
            raw.datePattern() == null || raw.datePattern().isBlank() ? "AUTO" : raw.datePattern().trim(),
            normalizeDupHandling(raw.duplicateHandling())
        );
    }

    private static char resolveDelimiter(String raw) {
        if (raw == null || raw.isEmpty()) {
            return ',';
        }
        if ("\\t".equals(raw) || "TAB".equalsIgnoreCase(raw)) {
            return '\t';
        }
        return raw.charAt(0);
    }

    private static String headerAt(List<String> headers, int oneBasedCol) {
        return (oneBasedCol >= 1 && oneBasedCol <= headers.size()) ? headers.get(oneBasedCol - 1) : "";
    }

    private static boolean isTruthy(String val) {
        if (val == null) {
            return false;
        }
        String t = val.trim().toLowerCase(Locale.ROOT);
        return "true".equals(t) || "yes".equals(t) || "1".equals(t) || "y".equals(t);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }

    private static byte[] buildBatchAad(UUID companyId, UUID batchId, byte[] sha256) {
        String aad = "mc_sheet_import_batch|" + companyId + "|" + batchId + "|" + JcsCanonicalizer.toHex(sha256);
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JacksonException ignored) {
            return List.of();
        }
    }

    private List<List<String>> parseNestedStringRows(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<List<String>>>() { });
        } catch (JacksonException ignored) {
            return List.of();
        }
    }

    private SpreadsheetImportDtos.SpreadsheetMappingSpecDto parseMappingSpec(String json) {
        if (json == null || json.isBlank()) {
            return normalizeMappingSpec(null, "ACCOUNTING_VOUCHERS");
        }
        try {
            SpreadsheetImportDtos.SpreadsheetMappingSpecDto parsed =
                objectMapper.readValue(json, SpreadsheetImportDtos.SpreadsheetMappingSpecDto.class);
            return normalizeMappingSpec(parsed, normalizeTargetType(parsed.targetType()));
        } catch (JacksonException ignored) {
            return normalizeMappingSpec(null, "ACCOUNTING_VOUCHERS");
        }
    }

    private SpreadsheetImportDtos.SourceCoordinatesDto parseCoordinates(String json) {
        if (json == null || json.isBlank()) {
            return new SpreadsheetImportDtos.SourceCoordinatesDto("Sheet1", List.of(), Map.of());
        }
        try {
            return objectMapper.readValue(json, SpreadsheetImportDtos.SourceCoordinatesDto.class);
        } catch (JacksonException ignored) {
            return new SpreadsheetImportDtos.SourceCoordinatesDto("Sheet1", List.of(), Map.of());
        }
    }

    private List<SpreadsheetImportDtos.CellValidationErrorDto> parseValidationErrors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                json,
                new TypeReference<List<SpreadsheetImportDtos.CellValidationErrorDto>>() { }
            );
        } catch (JacksonException ignored) {
            return List.of();
        }
    }

    private Map<String, Object> summarizePayload(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, Object> summary = new LinkedHashMap<>();
            if (node.has("voucherTypeName")) {
                summary.put("voucherTypeName", node.get("voucherTypeName").asString());
            }
            if (node.has("voucherDate") && !node.get("voucherDate").isNull()) {
                summary.put("voucherDate", node.get("voucherDate").asString());
            }
            if (node.has("voucherNumber") && !node.get("voucherNumber").isNull()) {
                summary.put("voucherNumber", node.get("voucherNumber").asString());
            }
            if (node.has("lines") && node.get("lines").isArray()) {
                summary.put("lineCount", node.get("lines").size());
            }
            if (node.has("name")) {
                summary.put("name", node.get("name").asString());
            }
            if (node.has("gstin") && !node.get("gstin").isNull()) {
                summary.put("gstin", node.get("gstin").asString());
            }
            return summary;
        } catch (JacksonException ignored) {
            return Map.of();
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize unit payload", ex);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize spreadsheet batch JSON", ex);
        }
    }

    private record CapturedSourceSnapshot(
        String fileName,
        byte[] rawBytes,
        SpreadsheetFormatParser.ParsedWorkbook preParsedWorkbook
    ) {
    }

    private record TemplateBinding(
        UUID templateId,
        Long templateVersion,
        String sheetName,
        Integer headerRowIndex,
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto mapping
    ) {
    }

    private record ParsedSignedAmount(String side, BigDecimal amount) {
    }

    private record CompanyLedgerMeta(UUID id, String name, boolean billWise) {
    }

    private record CompanyVoucherTypeMeta(UUID id, String name, String baseCategory) {
    }

    private record PreparedUnitPlan(
        int unitSeq,
        String unitKey,
        String targetType,
        SpreadsheetImportDtos.SourceCoordinatesDto coordinates,
        String intendedPayloadJson,
        BigDecimal unitDebit,
        BigDecimal unitCredit,
        String validationStatus,
        List<SpreadsheetImportDtos.CellValidationErrorDto> validationErrors
    ) {
    }

    private record DryRunPlan(
        List<String> detectedHeaders,
        List<PreparedUnitPlan> units,
        int validUnits,
        int invalidUnits,
        BigDecimal totalDebit,
        BigDecimal totalCredit
    ) {
    }

    private record StoredUnitRow(
        UUID id,
        int unitSeq,
        String unitKey,
        String targetType,
        String sourceCoordinatesJson,
        String intendedPayloadJson,
        BigDecimal unitDebit,
        BigDecimal unitCredit,
        String validationStatus,
        String validationErrorsJson,
        String unitIdempotencyKey,
        String executionStatus,
        String executionErrorCode,
        String executionErrorMessage,
        UUID resultingEntityId,
        String resultingReference,
        java.time.Instant committedAt
    ) {
    }

    private record UnitStatusCounts(
        int pending,
        int committed,
        int failed,
        int skipped,
        int uncertain
    ) {
    }

    private record BatchRow(
        UUID id,
        UUID companyId,
        String sourceFileName,
        byte[] sourceCipherGcm,
        byte[] sourceSha256,
        String formatProfile,
        String targetType,
        String sheetName,
        String availableSheetsJson,
        String detectedHeadersJson,
        int headerRowIndex,
        String dateSystem,
        boolean macroDetected,
        boolean formulaCellsDetected,
        UUID templateId,
        Long templateVersion,
        int mappingRevision,
        String mappingSnapshotJson,
        boolean cachedFormulaPolicyAccepted,
        String status,
        int totalUnits,
        int validUnits,
        int invalidUnits,
        int committedUnits,
        int failedUnits,
        int skippedUnits,
        int uncertainUnits,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        String diagnosticsJson,
        UUID actorPrincipalId,
        long versionNo,
        java.time.Instant createdAt,
        java.time.Instant completedAt
    ) {
    }
}
