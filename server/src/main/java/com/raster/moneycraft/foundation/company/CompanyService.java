package com.raster.moneycraft.foundation.company;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.EditLogSnapshotService;
import com.raster.moneycraft.foundation.auth.AuthService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.events.SseBroadcasterService;
import com.raster.moneycraft.foundation.security.CompanyGrantInfo;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.security.SystemRole;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Multi-company ERP master lifecycle, Tink AES256_SIV PAN protection, MCA Edit Log guard,
 * F11/F12 dynamic configuration engine, and optimistic concurrency control.
 */
@Service
public class CompanyService {

    private static final BigDecimal DEFAULT_EWAY_THRESHOLD = new BigDecimal("50000.00");

    private final AuthorityGuard authorityGuard;
    private final AuthService moneyCraftAuthService;
    private final RbacAuthorizationService rbacGuard;
    private final FiscalPeriodService fiscalPeriodService;
    private final TinkCryptoService tinkCryptoService;
    private final AuditChainService mcAuditChainService;
    private final EditLogSnapshotService editLogSnapshotService;
    private final SseBroadcasterService sseBroadcaster;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;
    private final ObjectMapper objectMapper;

    public CompanyService(
        AuthorityGuard authorityGuard,
        AuthService moneyCraftAuthService,
        RbacAuthorizationService rbacGuard,
        FiscalPeriodService fiscalPeriodService,
        TinkCryptoService tinkCryptoService,
        AuditChainService mcAuditChainService,
        EditLogSnapshotService editLogSnapshotService,
        SseBroadcasterService sseBroadcaster,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this(
            authorityGuard,
            moneyCraftAuthService,
            rbacGuard,
            fiscalPeriodService,
            tinkCryptoService,
            mcAuditChainService,
            editLogSnapshotService,
            sseBroadcaster,
            businessJdbcTemplate,
            businessTransactionManager,
            new ObjectMapper()
        );
    }

    @Autowired
    public CompanyService(
        AuthorityGuard authorityGuard,
        AuthService moneyCraftAuthService,
        RbacAuthorizationService rbacGuard,
        FiscalPeriodService fiscalPeriodService,
        TinkCryptoService tinkCryptoService,
        AuditChainService mcAuditChainService,
        EditLogSnapshotService editLogSnapshotService,
        SseBroadcasterService sseBroadcaster,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager,
        ObjectMapper objectMapper
    ) {
        this.authorityGuard = authorityGuard;
        this.moneyCraftAuthService = moneyCraftAuthService;
        this.rbacGuard = rbacGuard;
        this.fiscalPeriodService = fiscalPeriodService;
        this.tinkCryptoService = tinkCryptoService;
        this.mcAuditChainService = mcAuditChainService;
        this.editLogSnapshotService = editLogSnapshotService;
        this.sseBroadcaster = sseBroadcaster;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
        this.objectMapper = objectMapper;
    }

    public List<CompanyDtos.CompanySummaryDto> listAccessibleCompanies(MoneyCraftPrincipal principal) {
        if (principal.isServiceKeyAuthenticated() && !principal.serviceKeyCompanyAllowlist().isEmpty()) {
            List<UUID> scopedIds = principal.serviceKeyCompanyAllowlist();
            return TenantContextHolder.callWithSystemBypass("service-key-company-list", () ->
                fetchCompaniesByIdList(scopedIds)
            );
        }

        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)
            || principal.hasSystemRole(SystemRole.SECURITY_AUDITOR)) {
            return TenantContextHolder.callWithSystemBypass("owner-company-list", () ->
                businessJdbcTemplate.query(companySelectSql(null), this::mapCompanyRow)
            );
        }

        List<UUID> grantedIds = principal.companyGrants().stream()
            .map(CompanyGrantInfo::companyId)
            .distinct()
            .toList();
        if (grantedIds.isEmpty()) {
            return List.of();
        }
        return TenantContextHolder.callWithSystemBypass("principal-company-list", () ->
            fetchCompaniesByIdList(grantedIds)
        );
    }

    public CompanyDtos.CompanySummaryDto getCompany(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        List<CompanyDtos.CompanySummaryDto> rows = businessJdbcTemplate.query(
            companySelectSql("c.id = ?"),
            this::mapCompanyRow,
            companyId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-COMPANY-NOT-FOUND",
                "Company Not Found",
                "Company " + companyId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    public CompanyDtos.CompanySummaryDto createCompany(
        MoneyCraftPrincipal actor,
        CompanyDtos.CreateOrUpdateCompanyRequest request
    ) {
        authorityGuard.assertWritable();
        NormalizedCompanySpec spec = validateAndNormalize(request);

        UUID installationId = moneyCraftAuthService.requireActiveInstallationId();
        UUID companyId = UUID.randomUUID();

        byte[] sivPanBytes = null;
        String maskedPan = null;
        if (spec.panPlain() != null && !spec.panPlain().isBlank()) {
            sivPanBytes = tinkCryptoService.encryptDeterministicSiv(
                companyId,
                "mc_company",
                "pan_siv",
                spec.panPlain()
            );
            maskedPan = maskPanValue(spec.panPlain());
        }
        final byte[] storedPanSiv = sivPanBytes;
        final String storedPanMask = maskedPan;

        CompanyDtos.CompanySummaryDto created = TenantContextHolder.callWithSystemBypass("company-create", () ->
            businessTxTemplate.execute(tx -> {
                Integer codeTaken = businessJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM mc_company WHERE code = ?",
                    Integer.class,
                    spec.code()
                );
                if (codeTaken != null && codeTaken > 0) {
                    throw new MoneyCraftException(
                        HttpStatus.CONFLICT,
                        "MC-COMPANY-DUPLICATE-CODE",
                        "Duplicate Company Code",
                        "Company code '" + spec.code() + "' is already registered."
                    );
                }

                // PostgreSQL 18 triggers on mc_company seed 28 groups, Cash + Profit & Loss A/c,
                // 24 voucher types, base currency, Primary Cost Category, Main Location, and 6 TDS natures.
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_company (
                        id, code, legal_name, mailing_name, address_lines,
                        country_code, state_code, state_name, pincode,
                        pan_siv, pan_plain_masked,
                        base_currency_code, base_currency_symbol, currency_scale,
                        books_beginning_from, financial_year_from,
                        maintain_accounts, bill_wise_enabled, cost_centres_enabled, interest_calculation_enabled,
                        maintain_inventory, inventory_integrated, multiple_price_levels_enabled,
                        batches_enabled, expiry_date_for_batches_enabled, job_order_processing_enabled,
                        cost_tracking_enabled, job_costing_enabled, use_discount_column_in_invoices,
                        use_separate_actual_and_billed_qty, gst_enabled, multi_gstin_enabled,
                        einvoice_applicable, eway_bill_applicable, eway_bill_threshold_limit,
                        tds_enabled, tcs_enabled, vat_enabled, excise_enabled, service_tax_enabled,
                        payroll_enabled, payroll_statutory_enabled, multiple_addresses_enabled,
                        mark_modified_vouchers, mca_edit_log_enforced,
                        visibility, created_by
                    ) VALUES (
                        ?, ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?,
                        ?, ?, ?,
                        ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        ?, ?, ?,
                        ?, ?,
                        'ACTIVE', ?
                    )
                    """,
                    companyId,
                    spec.code(),
                    spec.legalName(),
                    spec.mailingName(),
                    spec.addressLines(),
                    spec.countryCode(),
                    spec.stateCode(),
                    spec.stateName(),
                    spec.pinCode(),
                    storedPanSiv,
                    storedPanMask,
                    spec.baseCurrencyCode(),
                    spec.currencySymbol(),
                    (short) spec.currencyScale(),
                    spec.booksBeginningFrom(),
                    spec.financialYearFrom(),
                    spec.maintainAccounts(),
                    spec.billWiseEnabled(),
                    spec.costCentresEnabled(),
                    spec.interestCalculationEnabled(),
                    spec.maintainInventory(),
                    spec.inventoryIntegrated(),
                    spec.multiplePriceLevelsEnabled(),
                    spec.batchesEnabled(),
                    spec.expiryDateForBatchesEnabled(),
                    spec.jobOrderProcessingEnabled(),
                    spec.costTrackingEnabled(),
                    spec.jobCostingEnabled(),
                    spec.useDiscountColumnInInvoices(),
                    spec.useSeparateActualAndBilledQty(),
                    spec.gstEnabled(),
                    spec.multiGstinEnabled(),
                    spec.eInvoiceApplicable(),
                    spec.eWayBillApplicable(),
                    spec.eWayBillThresholdLimit(),
                    spec.tdsEnabled(),
                    spec.tcsEnabled(),
                    spec.vatEnabled(),
                    spec.exciseEnabled(),
                    spec.serviceTaxEnabled(),
                    spec.payrollEnabled(),
                    spec.payrollStatutoryEnabled(),
                    spec.multipleAddressesEnabled(),
                    spec.markModifiedVouchers(),
                    spec.mcaEditLogEnforced(),
                    actor.principalId()
                );

                LocalDate fyEnd = spec.financialYearFrom().plusYears(1).minusDays(1);
                String fyCode = "FY" + spec.financialYearFrom().getYear() + "-"
                    + String.format("%02d", fyEnd.getYear() % 100);
                fiscalPeriodService.seedFiscalYearWithMonthlyPeriodsInCurrentTx(
                    companyId,
                    actor.principalId(),
                    fyCode,
                    "FY " + spec.financialYearFrom().getYear() + "-" + String.format("%02d", fyEnd.getYear() % 100),
                    spec.financialYearFrom(),
                    fyEnd
                );

                if (spec.gstEnabled() && !spec.gstin().isEmpty()) {
                    syncPrimaryGstRegistration(companyId, spec);
                }

                moneyCraftAuthService.grantCompanyCreatorAdminWithinCurrentTx(
                    installationId,
                    companyId,
                    actor
                );

                CompanyDtos.CompanySummaryDto initialSummary = toSummaryDto(
                    companyId,
                    spec,
                    storedPanMask,
                    1L
                );

                editLogSnapshotService.recordVersion(
                    companyId,
                    "COMPANY",
                    companyId,
                    1L,
                    1L,
                    1L,
                    "CREATE",
                    actor.principalId(),
                    actor.username(),
                    null,
                    null,
                    null,
                    initialSummary
                );

                mcAuditChainService.appendCompanyEvent(
                    companyId,
                    actor.principalId(),
                    actor.kind(),
                    "COMPANY_CREATE",
                    "COMPANY",
                    companyId.toString(),
                    "SUCCESS",
                    null,
                    Map.of(
                        "code", spec.code(),
                        "legalName", spec.legalName(),
                        "stateCode", spec.stateCode(),
                        "financialYearFrom", spec.financialYearFrom().toString(),
                        "mcaEditLogEnforced", spec.mcaEditLogEnforced()
                    )
                );

                return initialSummary;
            })
        );

        sseBroadcaster.broadcast(
            "COMPANY_CREATED",
            companyId,
            Map.of("companyId", companyId.toString(), "code", created.code())
        );
        return created;
    }

    public CompanyDtos.CompanySummaryDto updateCompany(
        MoneyCraftPrincipal actor,
        UUID companyId,
        String ifMatchHeader,
        CompanyDtos.CreateOrUpdateCompanyRequest request
    ) {
        authorityGuard.assertWritable();
        rbacGuard.requireCompanyRole(actor, companyId, Set.of(CompanyRole.COMPANY_ADMIN));

        long expectedVersion = parseExpectedVersion(ifMatchHeader, request == null ? null : request.versionNo());
        NormalizedCompanySpec spec = validateAndNormalize(request);

        CompanyDtos.CompanySummaryDto altered = businessTxTemplate.execute(tx -> {
            List<LockedCompanyState> lockedRows = businessJdbcTemplate.query(
                """
                SELECT version_no, next_alter_id, mca_edit_log_enforced, pan_plain_masked
                  FROM mc_company
                 WHERE id = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> new LockedCompanyState(
                    rs.getLong("version_no"),
                    rs.getLong("next_alter_id"),
                    rs.getBoolean("mca_edit_log_enforced"),
                    rs.getString("pan_plain_masked")
                ),
                companyId
            );

            if (lockedRows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-COMPANY-NOT-FOUND",
                    "Company Not Found",
                    "Company " + companyId + " does not exist."
                );
            }

            LockedCompanyState locked = lockedRows.getFirst();
            if (locked.versionNo() != expectedVersion) {
                throw new MoneyCraftException(
                    HttpStatus.PRECONDITION_FAILED,
                    "MC-CONCURRENCY-STALE-VERSION",
                    "Stale Resource Version (ETag Mismatch)",
                    "Expected company version v" + expectedVersion + " but current version is v" + locked.versionNo() + "."
                );
            }

            if (locked.mcaEditLogEnforced() && !spec.mcaEditLogEnforced()) {
                throw new MoneyCraftException(
                    HttpStatus.FORBIDDEN,
                    "MC-EDITLOG-MANDATORY",
                    "MCA Edit Log Cannot Be Disabled",
                    "Once MCA Audit Trail (mcaEditLogEnforced) is enabled, disabling it is prohibited."
                );
            }

            CompanyDtos.CompanySummaryDto beforeDto = getCompany(actor, companyId);

            byte[] updatedPanSiv = null;
            String updatedPanMask = locked.panMasked();
            if (spec.panPlain() != null && !spec.panPlain().isBlank()) {
                updatedPanSiv = tinkCryptoService.encryptDeterministicSiv(
                    companyId,
                    "mc_company",
                    "pan_siv",
                    spec.panPlain()
                );
                updatedPanMask = maskPanValue(spec.panPlain());
            }

            long nextVersion = locked.versionNo() + 1L;
            long alterSeq = locked.nextAlterId();

            businessJdbcTemplate.update(
                """
                UPDATE mc_company
                   SET code = ?,
                       legal_name = ?,
                       mailing_name = ?,
                       address_lines = ?,
                       country_code = ?,
                       state_code = ?,
                       state_name = ?,
                       pincode = ?,
                       pan_siv = COALESCE(?, pan_siv),
                       pan_plain_masked = COALESCE(?, pan_plain_masked),
                       base_currency_code = ?,
                       base_currency_symbol = ?,
                       currency_scale = ?,
                       books_beginning_from = ?,
                       financial_year_from = ?,
                       maintain_accounts = ?,
                       bill_wise_enabled = ?,
                       cost_centres_enabled = ?,
                       interest_calculation_enabled = ?,
                       maintain_inventory = ?,
                       inventory_integrated = ?,
                       multiple_price_levels_enabled = ?,
                       batches_enabled = ?,
                       expiry_date_for_batches_enabled = ?,
                       job_order_processing_enabled = ?,
                       cost_tracking_enabled = ?,
                       job_costing_enabled = ?,
                       use_discount_column_in_invoices = ?,
                       use_separate_actual_and_billed_qty = ?,
                       gst_enabled = ?,
                       multi_gstin_enabled = ?,
                       einvoice_applicable = ?,
                       eway_bill_applicable = ?,
                       eway_bill_threshold_limit = ?,
                       tds_enabled = ?,
                       tcs_enabled = ?,
                       vat_enabled = ?,
                       excise_enabled = ?,
                       service_tax_enabled = ?,
                       payroll_enabled = ?,
                       payroll_statutory_enabled = ?,
                       multiple_addresses_enabled = ?,
                       mark_modified_vouchers = ?,
                       mca_edit_log_enforced = ?,
                       next_alter_id = next_alter_id + 1,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE id = ?
                """,
                spec.code(),
                spec.legalName(),
                spec.mailingName(),
                spec.addressLines(),
                spec.countryCode(),
                spec.stateCode(),
                spec.stateName(),
                spec.pinCode(),
                updatedPanSiv,
                updatedPanMask,
                spec.baseCurrencyCode(),
                spec.currencySymbol(),
                (short) spec.currencyScale(),
                spec.booksBeginningFrom(),
                spec.financialYearFrom(),
                spec.maintainAccounts(),
                spec.billWiseEnabled(),
                spec.costCentresEnabled(),
                spec.interestCalculationEnabled(),
                spec.maintainInventory(),
                spec.inventoryIntegrated(),
                spec.multiplePriceLevelsEnabled(),
                spec.batchesEnabled(),
                spec.expiryDateForBatchesEnabled(),
                spec.jobOrderProcessingEnabled(),
                spec.costTrackingEnabled(),
                spec.jobCostingEnabled(),
                spec.useDiscountColumnInInvoices(),
                spec.useSeparateActualAndBilledQty(),
                spec.gstEnabled(),
                spec.multiGstinEnabled(),
                spec.eInvoiceApplicable(),
                spec.eWayBillApplicable(),
                spec.eWayBillThresholdLimit(),
                spec.tdsEnabled(),
                spec.tcsEnabled(),
                spec.vatEnabled(),
                spec.exciseEnabled(),
                spec.serviceTaxEnabled(),
                spec.payrollEnabled(),
                spec.payrollStatutoryEnabled(),
                spec.multipleAddressesEnabled(),
                spec.markModifiedVouchers(),
                spec.mcaEditLogEnforced(),
                nextVersion,
                companyId
            );

            if (spec.gstEnabled() && !spec.gstin().isEmpty()) {
                syncPrimaryGstRegistration(companyId, spec);
            }

            CompanyDtos.CompanySummaryDto afterDto = toSummaryDto(
                companyId,
                spec,
                updatedPanMask,
                nextVersion
            );

            editLogSnapshotService.recordVersion(
                companyId,
                "COMPANY",
                companyId,
                1L,
                alterSeq,
                nextVersion,
                "ALTER",
                actor.principalId(),
                actor.username(),
                null,
                null,
                beforeDto,
                afterDto
            );

            mcAuditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "COMPANY_ALTER",
                "COMPANY",
                companyId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "code", spec.code(),
                    "legalName", spec.legalName(),
                    "versionNo", nextVersion,
                    "mcaEditLogEnforced", spec.mcaEditLogEnforced()
                )
            );

            return afterDto;
        });

        sseBroadcaster.broadcast(
            "COMPANY_UPDATED",
            companyId,
            Map.of("companyId", companyId.toString(), "versionNo", altered.versionNo())
        );
        return altered;
    }

    public CompanyDtos.CompanyF12ConfigDto getF12Config(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        List<String> rows = businessJdbcTemplate.query(
            "SELECT f12_config::text FROM mc_company WHERE id = ?",
            (rs, rowNum) -> rs.getString(1),
            companyId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-COMPANY-NOT-FOUND",
                "Company Not Found",
                "Company " + companyId + " does not exist."
            );
        }
        return parseF12ConfigJson(rows.getFirst());
    }

    public CompanyDtos.CompanyF12ConfigDto updateF12Config(
        MoneyCraftPrincipal actor,
        UUID companyId,
        CompanyDtos.CompanyF12ConfigDto request
    ) {
        authorityGuard.assertWritable();
        rbacGuard.requireCompanyAccess(actor, companyId);
        CompanyDtos.CompanyF12ConfigDto normalized = normalizeF12Config(request);
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(normalized);
        } catch (JacksonException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-COMPANY-F12-INVALID",
                "Invalid F12 Configuration Payload",
                "Unable to serialize F12 configuration payload."
            );
        }

        CompanyDtos.CompanyF12ConfigDto saved = businessTxTemplate.execute(tx -> {
            List<UUID> locked = businessJdbcTemplate.query(
                "SELECT id FROM mc_company WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId
            );
            if (locked.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-COMPANY-NOT-FOUND",
                    "Company Not Found",
                    "Company " + companyId + " does not exist."
                );
            }

            businessJdbcTemplate.update(
                """
                UPDATE mc_company
                   SET f12_config = ?::jsonb,
                       updated_at = clock_timestamp()
                 WHERE id = ?
                """,
                jsonPayload,
                companyId
            );

            mcAuditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "COMPANY_F12_CONFIG_UPDATE",
                "COMPANY",
                companyId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "reportFormat", normalized.reportFormat(),
                    "scaleFactor", normalized.scaleFactor(),
                    "sortingMethod", normalized.sortingMethod(),
                    "useCrDrInsteadOfToBy", normalized.useCrDrInsteadOfToBy()
                )
            );
            return normalized;
        });

        sseBroadcaster.broadcast(
            "COMPANY_F12_UPDATED",
            companyId,
            Map.of("companyId", companyId.toString(), "reportFormat", saved.reportFormat())
        );
        return saved;
    }

    public String decryptCompanyPan(MoneyCraftPrincipal actor, UUID companyId) {
        rbacGuard.requireCompanyRole(actor, companyId, Set.of(CompanyRole.COMPANY_ADMIN));
        List<byte[]> rows = businessJdbcTemplate.query(
            "SELECT pan_siv FROM mc_company WHERE id = ?",
            (rs, rowNum) -> rs.getBytes("pan_siv"),
            companyId
        );
        if (rows.isEmpty() || rows.getFirst() == null) {
            return null;
        }
        return tinkCryptoService.decryptDeterministicSiv(
            companyId,
            "mc_company",
            "pan_siv",
            rows.getFirst()
        );
    }

    public static long parseExpectedVersion(String ifMatchHeader, Long bodyVersionNo) {
        if (ifMatchHeader != null && !ifMatchHeader.isBlank()) {
            String raw = ifMatchHeader.trim();
            if (raw.startsWith("W/")) {
                raw = raw.substring(2).trim();
            }
            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
                raw = raw.substring(1, raw.length() - 1).trim();
            }
            if (raw.startsWith("v") || raw.startsWith("V")) {
                raw = raw.substring(1).trim();
            }
            try {
                long parsed = Long.parseLong(raw);
                if (parsed >= 1L) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Handled below
            }
            throw new MoneyCraftException(
                HttpStatus.PRECONDITION_FAILED,
                "MC-CONCURRENCY-STALE-VERSION",
                "Invalid If-Match ETag",
                "Supplied If-Match header '" + ifMatchHeader + "' is not a valid resource version."
            );
        }
        if (bodyVersionNo != null && bodyVersionNo >= 1L) {
            return bodyVersionNo;
        }
        throw new MoneyCraftException(
            HttpStatus.PRECONDITION_REQUIRED,
            "MC-IF-MATCH-REQUIRED",
            "If-Match Header Required",
            "Mutating PUT requests must specify If-Match matching the current ETag (for example \"v1\")."
        );
    }

    private void syncPrimaryGstRegistration(UUID companyId, NormalizedCompanySpec spec) {
        String dbPeriodicity = "QUARTERLY".equalsIgnoreCase(spec.gstr1Periodicity())
            || "QUARTERLY_QRMP".equalsIgnoreCase(spec.gstr1Periodicity())
            ? "QUARTERLY_QRMP"
            : "MONTHLY";

        List<UUID> currentDefault = businessJdbcTemplate.query(
            "SELECT id FROM mc_gst_registration WHERE company_id = ? AND is_default = true",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId
        );

        if (currentDefault.isEmpty()) {
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_gst_registration (
                    id, company_id, registration_name, state_code, state_name,
                    registration_type, gstin, effective_from, periodicity,
                    is_default, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true, true)
                """,
                UUID.randomUUID(),
                companyId,
                spec.stateName() + " Primary GST",
                spec.stateCode(),
                spec.stateName(),
                spec.gstRegistrationType(),
                spec.gstin(),
                spec.gstEffectiveFrom(),
                dbPeriodicity
            );
        } else {
            businessJdbcTemplate.update(
                """
                UPDATE mc_gst_registration
                   SET state_code = ?,
                       state_name = ?,
                       registration_type = ?,
                       gstin = ?,
                       effective_from = ?,
                       periodicity = ?,
                       alter_id = alter_id + 1,
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.stateCode(),
                spec.stateName(),
                spec.gstRegistrationType(),
                spec.gstin(),
                spec.gstEffectiveFrom(),
                dbPeriodicity,
                companyId,
                currentDefault.getFirst()
            );
        }
    }

    private List<CompanyDtos.CompanySummaryDto> fetchCompaniesByIdList(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String inPlaceholders = String.join(",", ids.stream().map(u -> "?").toList());
        return businessJdbcTemplate.query(
            companySelectSql("c.visibility = 'ACTIVE' AND c.id IN (" + inPlaceholders + ")"),
            this::mapCompanyRow,
            ids.toArray()
        );
    }

    private static String companySelectSql(String customWhereClause) {
        String predicate = customWhereClause == null ? "c.visibility = 'ACTIVE'" : customWhereClause;
        return """
            SELECT c.id, c.code, c.legal_name, c.mailing_name, c.country_code, c.state_code,
                   c.pincode, c.pan_plain_masked, c.base_currency_code, c.base_currency_symbol,
                   c.currency_scale, c.financial_year_from, c.books_beginning_from,
                   c.maintain_accounts, c.bill_wise_enabled, c.cost_centres_enabled,
                   c.interest_calculation_enabled, c.maintain_inventory, c.inventory_integrated,
                   c.multiple_price_levels_enabled, c.batches_enabled, c.expiry_date_for_batches_enabled,
                   c.job_order_processing_enabled, c.cost_tracking_enabled, c.job_costing_enabled,
                   c.use_discount_column_in_invoices, c.use_separate_actual_and_billed_qty,
                   c.gst_enabled, c.multi_gstin_enabled,
                   c.einvoice_applicable, c.eway_bill_applicable, c.eway_bill_threshold_limit,
                   c.tds_enabled, c.tcs_enabled, c.vat_enabled, c.excise_enabled, c.service_tax_enabled,
                   c.payroll_enabled, c.payroll_statutory_enabled,
                   c.multiple_addresses_enabled, c.mark_modified_vouchers,
                   c.mca_edit_log_enforced, c.version_no,
                   g.registration_type, g.gstin, g.periodicity, g.effective_from,
                   (SELECT MAX(j.voucher_date)
                      FROM mc_journal j
                     WHERE j.company_id = c.id
                       AND j.status = 'POSTED'
                       AND j.posting_nature = 'NORMAL') AS last_voucher_date
              FROM mc_company c
              LEFT JOIN mc_gst_registration g
                ON g.company_id = c.id AND g.is_default = true AND g.active = true
             WHERE %s
             ORDER BY c.code ASC
            """.formatted(predicate);
    }

    private CompanyDtos.CompanySummaryDto mapCompanyRow(java.sql.ResultSet rs, int rowNum)
        throws java.sql.SQLException {
        String dbPer = rs.getString("periodicity");
        String uiPer = "QUARTERLY_QRMP".equals(dbPer) ? "QUARTERLY" : "MONTHLY";
        LocalDate fyStart = rs.getObject("financial_year_from", LocalDate.class);
        LocalDate gstEff = rs.getObject("effective_from", LocalDate.class);
        int ccyScale = rs.getShort("currency_scale");
        BigDecimal ewayThreshold = rs.getBigDecimal("eway_bill_threshold_limit");

        CompanyDtos.CompanyFeaturesDto features = new CompanyDtos.CompanyFeaturesDto(
            rs.getBoolean("maintain_accounts"),
            rs.getBoolean("bill_wise_enabled"),
            rs.getBoolean("cost_centres_enabled"),
            rs.getBoolean("interest_calculation_enabled"),
            rs.getBoolean("maintain_inventory"),
            rs.getBoolean("inventory_integrated"),
            rs.getBoolean("multiple_price_levels_enabled"),
            rs.getBoolean("batches_enabled"),
            rs.getBoolean("expiry_date_for_batches_enabled"),
            rs.getBoolean("job_order_processing_enabled"),
            rs.getBoolean("cost_tracking_enabled"),
            rs.getBoolean("job_costing_enabled"),
            rs.getBoolean("use_discount_column_in_invoices"),
            rs.getBoolean("use_separate_actual_and_billed_qty"),
            rs.getBoolean("gst_enabled"),
            rs.getBoolean("multi_gstin_enabled"),
            rs.getString("registration_type") == null ? "REGULAR" : rs.getString("registration_type"),
            rs.getString("gstin") == null ? "" : rs.getString("gstin"),
            uiPer,
            gstEff == null ? fyStart : gstEff,
            rs.getBoolean("einvoice_applicable"),
            rs.getBoolean("eway_bill_applicable"),
            formatThresholdLimit(ewayThreshold, ccyScale),
            rs.getBoolean("tds_enabled"),
            rs.getBoolean("tcs_enabled"),
            rs.getBoolean("vat_enabled"),
            rs.getBoolean("excise_enabled"),
            rs.getBoolean("service_tax_enabled"),
            rs.getBoolean("payroll_enabled"),
            rs.getBoolean("payroll_statutory_enabled"),
            rs.getBoolean("multiple_addresses_enabled"),
            rs.getBoolean("mark_modified_vouchers"),
            rs.getBoolean("mca_edit_log_enforced")
        );

        return new CompanyDtos.CompanySummaryDto(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("legal_name"),
            rs.getString("mailing_name"),
            rs.getString("country_code"),
            rs.getString("state_code"),
            rs.getString("pincode") == null ? "" : rs.getString("pincode"),
            rs.getString("pan_plain_masked"),
            rs.getString("base_currency_code"),
            rs.getString("base_currency_symbol"),
            ccyScale,
            fyStart,
            rs.getObject("books_beginning_from", LocalDate.class),
            rs.getObject("last_voucher_date", LocalDate.class),
            rs.getLong("version_no"),
            features
        );
    }

    private static CompanyDtos.CompanySummaryDto toSummaryDto(
        UUID companyId,
        NormalizedCompanySpec spec,
        String panMasked,
        long versionNo
    ) {
        CompanyDtos.CompanyFeaturesDto features = new CompanyDtos.CompanyFeaturesDto(
            spec.maintainAccounts(),
            spec.billWiseEnabled(),
            spec.costCentresEnabled(),
            spec.interestCalculationEnabled(),
            spec.maintainInventory(),
            spec.inventoryIntegrated(),
            spec.multiplePriceLevelsEnabled(),
            spec.batchesEnabled(),
            spec.expiryDateForBatchesEnabled(),
            spec.jobOrderProcessingEnabled(),
            spec.costTrackingEnabled(),
            spec.jobCostingEnabled(),
            spec.useDiscountColumnInInvoices(),
            spec.useSeparateActualAndBilledQty(),
            spec.gstEnabled(),
            spec.multiGstinEnabled(),
            spec.gstRegistrationType(),
            spec.gstin(),
            spec.gstr1Periodicity(),
            spec.gstEffectiveFrom(),
            spec.eInvoiceApplicable(),
            spec.eWayBillApplicable(),
            formatThresholdLimit(spec.eWayBillThresholdLimit(), spec.currencyScale()),
            spec.tdsEnabled(),
            spec.tcsEnabled(),
            spec.vatEnabled(),
            spec.exciseEnabled(),
            spec.serviceTaxEnabled(),
            spec.payrollEnabled(),
            spec.payrollStatutoryEnabled(),
            spec.multipleAddressesEnabled(),
            spec.markModifiedVouchers(),
            spec.mcaEditLogEnforced()
        );
        return new CompanyDtos.CompanySummaryDto(
            companyId,
            spec.code(),
            spec.legalName(),
            spec.mailingName(),
            spec.countryCode(),
            spec.stateCode(),
            spec.pinCode(),
            panMasked,
            spec.baseCurrencyCode(),
            spec.currencySymbol(),
            spec.currencyScale(),
            spec.financialYearFrom(),
            spec.booksBeginningFrom(),
            null,
            versionNo,
            features
        );
    }

    private NormalizedCompanySpec validateAndNormalize(CompanyDtos.CreateOrUpdateCompanyRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()
            || req.legalName() == null || req.legalName().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-COMPANY-INVALID",
                "Missing Required Company Fields",
                "code and legalName are required."
            );
        }

        String code = req.code().trim().toUpperCase();
        if (!code.matches("^[A-Za-z0-9_-]{1,24}$")) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-COMPANY-CODE-INVALID",
                "Invalid Company Code",
                "Company code must contain 1 to 24 alphanumeric, underscore, or hyphen characters."
            );
        }

        String legalName = req.legalName().trim();
        String mailingName = (req.mailingName() == null || req.mailingName().isBlank())
            ? legalName
            : req.mailingName().trim();
        String addressLines = req.addressLines() == null ? "" : req.addressLines().trim();
        String countryCode = (req.countryCode() == null || req.countryCode().isBlank())
            ? "IN"
            : req.countryCode().trim().toUpperCase();
        String stateCode = (req.stateCode() == null || req.stateCode().isBlank())
            ? "33"
            : req.stateCode().trim();
        String stateName = (req.stateName() == null || req.stateName().isBlank())
            ? defaultStateLabel(stateCode)
            : req.stateName().trim();
        String pinCode = req.pinCode() == null ? "" : req.pinCode().trim();
        String baseCurrency = (req.baseCurrencyCode() == null || req.baseCurrencyCode().isBlank())
            ? "INR"
            : req.baseCurrencyCode().trim().toUpperCase();
        String currencySymbol = (req.currencySymbol() == null || req.currencySymbol().isBlank())
            ? ("INR".equals(baseCurrency) ? "\u20B9" : baseCurrency)
            : req.currencySymbol().trim();
        int scale = req.currencyScale() == null ? 2 : req.currencyScale();
        if (scale < 0 || scale > 6) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-COMPANY-SCALE-INVALID",
                "Invalid Currency Scale",
                "currencyScale must be between 0 and 6."
            );
        }

        LocalDate fyFrom = req.financialYearFrom() == null ? LocalDate.of(2025, 4, 1) : req.financialYearFrom();
        LocalDate booksFrom = req.booksBeginningFrom() == null ? fyFrom : req.booksBeginningFrom();
        if (booksFrom.isBefore(fyFrom)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-COMPANY-BOOKS-DATE-INVALID",
                "Invalid Books Beginning Date",
                "booksBeginningFrom must be on or after financialYearFrom."
            );
        }

        CompanyDtos.CompanyFeaturesDto f = req.features();
        boolean maintainAccounts = f == null || f.maintainAccounts() == null || f.maintainAccounts();
        boolean billWise = f == null || f.billWiseEnabled() == null || f.billWiseEnabled();
        boolean costCentres = f != null && Boolean.TRUE.equals(f.costCentresEnabled());
        boolean interestCalc = f != null && Boolean.TRUE.equals(f.interestCalculationEnabled());
        boolean maintainInv = f == null || f.maintainInventory() == null || f.maintainInventory();
        boolean invIntegrated = f == null || f.inventoryIntegrated() == null || f.inventoryIntegrated();
        boolean multiplePriceLevels = f != null && Boolean.TRUE.equals(f.multiplePriceLevelsEnabled());
        boolean batches = f == null || f.batchesEnabled() == null || f.batchesEnabled();
        boolean expiryBatches = f == null || f.expiryDateForBatchesEnabled() == null || f.expiryDateForBatchesEnabled();
        boolean jobOrderProcessing = f != null && Boolean.TRUE.equals(f.jobOrderProcessingEnabled());
        boolean costTracking = f != null && Boolean.TRUE.equals(f.costTrackingEnabled());
        boolean jobCosting = f != null && Boolean.TRUE.equals(f.jobCostingEnabled());
        boolean useDiscountCol = f == null || f.useDiscountColumnInInvoices() == null || f.useDiscountColumnInInvoices();
        boolean useSeparateActualBilled =
            f == null || f.useSeparateActualAndBilledQty() == null || f.useSeparateActualAndBilledQty();
        boolean gstEnabled = f == null || f.gstEnabled() == null || f.gstEnabled();
        boolean multiGstin = f != null && Boolean.TRUE.equals(f.multiGstinEnabled());
        boolean eInvoiceApp = f != null && Boolean.TRUE.equals(f.eInvoiceApplicable());
        boolean eWayBillApp = f == null || f.eWayBillApplicable() == null || f.eWayBillApplicable();
        boolean tdsEnabled = f != null && Boolean.TRUE.equals(f.tdsEnabled());
        boolean tcsEnabled = f != null && Boolean.TRUE.equals(f.tcsEnabled());
        boolean vatEnabled = f != null && Boolean.TRUE.equals(f.vatEnabled());
        boolean exciseEnabled = f != null && Boolean.TRUE.equals(f.exciseEnabled());
        boolean serviceTaxEnabled = f != null && Boolean.TRUE.equals(f.serviceTaxEnabled());
        boolean payrollEnabled = f != null && Boolean.TRUE.equals(f.payrollEnabled());
        boolean payrollStatEnabled = f != null && Boolean.TRUE.equals(f.payrollStatutoryEnabled());
        boolean multipleAddresses = f != null && Boolean.TRUE.equals(f.multipleAddressesEnabled());
        boolean markModifiedVouchers = f != null && Boolean.TRUE.equals(f.markModifiedVouchers());
        boolean mcaEditLog = f != null && Boolean.TRUE.equals(f.mcaEditLogEnforced());

        // Enforce TallyPrime 7.2 dependent feature invariants
        if (!maintainAccounts) {
            billWise = false;
            invIntegrated = false;
        }
        if (!maintainInv) {
            invIntegrated = false;
            multiplePriceLevels = false;
            batches = false;
            expiryBatches = false;
            jobOrderProcessing = false;
            costTracking = false;
        }
        if (!batches) {
            expiryBatches = false;
        }
        if (!costCentres) {
            jobCosting = false;
        }
        if (!payrollEnabled) {
            payrollStatEnabled = false;
        }

        BigDecimal eWayBillThreshold = DEFAULT_EWAY_THRESHOLD;
        if (f != null && f.eWayBillThresholdLimit() != null && !f.eWayBillThresholdLimit().isBlank()) {
            eWayBillThreshold = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                f.eWayBillThresholdLimit(),
                6,
                BigDecimal.ZERO,
                null,
                false,
                "eWayBillThresholdLimit"
            );
        }

        String gstRegType = (f == null || f.gstRegistrationType() == null || f.gstRegistrationType().isBlank())
            ? "REGULAR"
            : f.gstRegistrationType().trim().toUpperCase();
        String periodicity = (f == null || f.gstr1Periodicity() == null || f.gstr1Periodicity().isBlank())
            ? "MONTHLY"
            : f.gstr1Periodicity().trim().toUpperCase();
        LocalDate gstEff = (f == null || f.gstEffectiveFrom() == null) ? fyFrom : f.gstEffectiveFrom();
        String gstin = (f == null || f.gstin() == null) ? "" : f.gstin().trim().toUpperCase();

        String panPlain = req.pan() == null ? null : req.pan().trim().toUpperCase();
        if (gstEnabled && !gstin.isEmpty()) {
            gstin = ExactMoneyValidator.validateGstin(
                gstin,
                "IN".equals(countryCode) ? stateCode : null
            );
            if (panPlain == null || panPlain.isEmpty()) {
                panPlain = gstin.substring(2, 12);
            }
        }

        return new NormalizedCompanySpec(
            code,
            legalName,
            mailingName,
            addressLines,
            countryCode,
            stateCode,
            stateName,
            pinCode,
            panPlain,
            baseCurrency,
            currencySymbol,
            scale,
            fyFrom,
            booksFrom,
            maintainAccounts,
            billWise,
            costCentres,
            interestCalc,
            maintainInv,
            invIntegrated,
            multiplePriceLevels,
            batches,
            expiryBatches,
            jobOrderProcessing,
            costTracking,
            jobCosting,
            useDiscountCol,
            useSeparateActualBilled,
            gstEnabled,
            multiGstin,
            gstRegType,
            gstin,
            periodicity,
            gstEff,
            eInvoiceApp,
            eWayBillApp,
            eWayBillThreshold,
            tdsEnabled,
            tcsEnabled,
            vatEnabled,
            exciseEnabled,
            serviceTaxEnabled,
            payrollEnabled,
            payrollStatEnabled,
            multipleAddresses,
            markModifiedVouchers,
            mcaEditLog
        );
    }

    private CompanyDtos.CompanyF12ConfigDto parseF12ConfigJson(String rawJson) {
        CompanyDtos.CompanyF12ConfigDto d = CompanyDtos.CompanyF12ConfigDto.defaults();
        if (rawJson == null || rawJson.isBlank() || "{}".equals(rawJson.trim())) {
            return d;
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            if (node == null || !node.isObject() || node.isEmpty()) {
                return d;
            }
            return new CompanyDtos.CompanyF12ConfigDto(
                boolOr(node, "useCrDrInsteadOfToBy", d.useCrDrInsteadOfToBy()),
                boolOr(node, "provideReferenceNoAndDate", d.provideReferenceNoAndDate()),
                boolOr(node, "provideDispatchOrderExportDetails", d.provideDispatchOrderExportDetails()),
                boolOr(node, "warnOnNegativeCash", d.warnOnNegativeCash()),
                boolOr(node, "warnOnNegativeStock", d.warnOnNegativeStock()),
                boolOr(node, "preallocateBills", d.preallocateBills()),
                boolOr(node, "showBillWiseList", d.showBillWiseList()),
                boolOr(node, "showBillFinalBalances", d.showBillFinalBalances()),
                boolOr(node, "useSingleEntryForPaymentReceiptContra", d.useSingleEntryForPaymentReceiptContra()),
                boolOr(node, "showLedgerCurrentBalance", d.showLedgerCurrentBalance()),
                boolOr(node, "showBalancesAsOnVoucherDate", d.showBalancesAsOnVoucherDate()),
                boolOr(node, "provideLineNarration", d.provideLineNarration()),
                boolOr(node, "provideCommonNarration", d.provideCommonNarration()),
                boolOr(node, "consolidateStockItemsWithSameRate", d.consolidateStockItemsWithSameRate()),
                boolOr(node, "allowGstRateOverride", d.allowGstRateOverride()),
                boolOr(node, "provideAliases", d.provideAliases()),
                boolOr(node, "billWiseForNonTradingAccounts", d.billWiseForNonTradingAccounts()),
                boolOr(node, "provideCreditLimits", d.provideCreditLimits()),
                boolOr(node, "provideLedgerAddresses", d.provideLedgerAddresses()),
                boolOr(node, "provideLedgerBankDetails", d.provideLedgerBankDetails()),
                boolOr(node, "providePartNumbers", d.providePartNumbers()),
                boolOr(node, "provideAlternateUnits", d.provideAlternateUnits()),
                boolOr(node, "provideStandardRates", d.provideStandardRates()),
                boolOr(node, "showOpeningBalances", d.showOpeningBalances()),
                boolOr(node, "showTransactions", d.showTransactions()),
                boolOr(node, "showNettTransactionsOnly", d.showNettTransactionsOnly()),
                boolOr(node, "showClosingBalances", d.showClosingBalances()),
                boolOr(node, "showPercentages", d.showPercentages()),
                strOr(node, "reportFormat", d.reportFormat()),
                strOr(node, "scaleFactor", d.scaleFactor()),
                strOr(node, "sortingMethod", d.sortingMethod())
            );
        } catch (JacksonException ex) {
            return d;
        }
    }

    private static CompanyDtos.CompanyF12ConfigDto normalizeF12Config(CompanyDtos.CompanyF12ConfigDto req) {
        CompanyDtos.CompanyF12ConfigDto d = CompanyDtos.CompanyF12ConfigDto.defaults();
        if (req == null) {
            return d;
        }
        String fmt = req.reportFormat() == null || req.reportFormat().isBlank()
            ? d.reportFormat()
            : req.reportFormat().trim().toUpperCase(Locale.ROOT);
        String scale = req.scaleFactor() == null || req.scaleFactor().isBlank()
            ? d.scaleFactor()
            : req.scaleFactor().trim().toUpperCase(Locale.ROOT);
        String sort = req.sortingMethod() == null || req.sortingMethod().isBlank()
            ? d.sortingMethod()
            : req.sortingMethod().trim().toUpperCase(Locale.ROOT);
        return new CompanyDtos.CompanyF12ConfigDto(
            req.useCrDrInsteadOfToBy(),
            req.provideReferenceNoAndDate(),
            req.provideDispatchOrderExportDetails(),
            req.warnOnNegativeCash(),
            req.warnOnNegativeStock(),
            req.preallocateBills(),
            req.showBillWiseList(),
            req.showBillFinalBalances(),
            req.useSingleEntryForPaymentReceiptContra(),
            req.showLedgerCurrentBalance(),
            req.showBalancesAsOnVoucherDate(),
            req.provideLineNarration(),
            req.provideCommonNarration(),
            req.consolidateStockItemsWithSameRate(),
            req.allowGstRateOverride(),
            req.provideAliases(),
            req.billWiseForNonTradingAccounts(),
            req.provideCreditLimits(),
            req.provideLedgerAddresses(),
            req.provideLedgerBankDetails(),
            req.providePartNumbers(),
            req.provideAlternateUnits(),
            req.provideStandardRates(),
            req.showOpeningBalances(),
            req.showTransactions(),
            req.showNettTransactionsOnly(),
            req.showClosingBalances(),
            req.showPercentages(),
            fmt,
            scale,
            sort
        );
    }

    private static boolean boolOr(JsonNode node, String field, boolean fallback) {
        JsonNode child = node.get(field);
        return child != null && !child.isNull() ? child.asBoolean(fallback) : fallback;
    }

    private static String strOr(JsonNode node, String field, String fallback) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return fallback;
        }
        String text = child.asString();
        return text == null || text.isBlank() ? fallback : text.trim().toUpperCase(Locale.ROOT);
    }

    private static String formatThresholdLimit(BigDecimal raw, int companyScale) {
        BigDecimal safe = raw == null ? DEFAULT_EWAY_THRESHOLD : raw;
        int minScale = Math.max(2, companyScale);
        int targetScale = Math.max(minScale, Math.max(0, safe.stripTrailingZeros().scale()));
        return safe.setScale(targetScale, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String defaultStateLabel(String stateCode) {
        return switch (stateCode) {
            case "27" -> "Maharashtra";
            case "29" -> "Karnataka";
            case "33" -> "Tamil Nadu";
            default -> "State-" + stateCode;
        };
    }

    private static String maskPanValue(String pan) {
        if (pan == null || pan.length() < 10) {
            return pan;
        }
        return pan.substring(0, 5) + "****" + pan.substring(9);
    }

    private record LockedCompanyState(
        long versionNo,
        long nextAlterId,
        boolean mcaEditLogEnforced,
        String panMasked
    ) {
    }

    private record NormalizedCompanySpec(
        String code,
        String legalName,
        String mailingName,
        String addressLines,
        String countryCode,
        String stateCode,
        String stateName,
        String pinCode,
        String panPlain,
        String baseCurrencyCode,
        String currencySymbol,
        int currencyScale,
        LocalDate financialYearFrom,
        LocalDate booksBeginningFrom,
        boolean maintainAccounts,
        boolean billWiseEnabled,
        boolean costCentresEnabled,
        boolean interestCalculationEnabled,
        boolean maintainInventory,
        boolean inventoryIntegrated,
        boolean multiplePriceLevelsEnabled,
        boolean batchesEnabled,
        boolean expiryDateForBatchesEnabled,
        boolean jobOrderProcessingEnabled,
        boolean costTrackingEnabled,
        boolean jobCostingEnabled,
        boolean useDiscountColumnInInvoices,
        boolean useSeparateActualAndBilledQty,
        boolean gstEnabled,
        boolean multiGstinEnabled,
        String gstRegistrationType,
        String gstin,
        String gstr1Periodicity,
        LocalDate gstEffectiveFrom,
        boolean eInvoiceApplicable,
        boolean eWayBillApplicable,
        BigDecimal eWayBillThresholdLimit,
        boolean tdsEnabled,
        boolean tcsEnabled,
        boolean vatEnabled,
        boolean exciseEnabled,
        boolean serviceTaxEnabled,
        boolean payrollEnabled,
        boolean payrollStatutoryEnabled,
        boolean multipleAddressesEnabled,
        boolean markModifiedVouchers,
        boolean mcaEditLogEnforced
    ) {
    }
}
