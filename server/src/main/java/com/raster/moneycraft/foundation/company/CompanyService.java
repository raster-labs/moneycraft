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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Multi-company ERP master lifecycle, Tink AES256_SIV PAN protection, MCA Edit Log guard,
 * and optimistic concurrency control.
 */
@Service
public class CompanyService {

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
                        mca_edit_log_enforced, inventory_integrated, maintain_inventory,
                        bill_wise_enabled, cost_centres_enabled, gst_enabled,
                        tds_enabled, multi_gstin_enabled, visibility, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
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
                    spec.mcaEditLogEnforced(),
                    spec.inventoryIntegrated(),
                    spec.maintainInventory(),
                    spec.billWiseEnabled(),
                    spec.costCentresEnabled(),
                    spec.gstEnabled(),
                    spec.tdsEnabled(),
                    spec.multiGstinEnabled(),
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
                       mca_edit_log_enforced = ?,
                       inventory_integrated = ?,
                       maintain_inventory = ?,
                       bill_wise_enabled = ?,
                       cost_centres_enabled = ?,
                       gst_enabled = ?,
                       tds_enabled = ?,
                       multi_gstin_enabled = ?,
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
                spec.mcaEditLogEnforced(),
                spec.inventoryIntegrated(),
                spec.maintainInventory(),
                spec.billWiseEnabled(),
                spec.costCentresEnabled(),
                spec.gstEnabled(),
                spec.tdsEnabled(),
                spec.multiGstinEnabled(),
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
                   c.bill_wise_enabled, c.cost_centres_enabled, c.maintain_inventory,
                   c.inventory_integrated, c.gst_enabled, c.multi_gstin_enabled,
                   c.tds_enabled, c.mca_edit_log_enforced, c.version_no,
                   g.registration_type, g.gstin, g.periodicity, g.effective_from
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

        CompanyDtos.CompanyFeaturesDto features = new CompanyDtos.CompanyFeaturesDto(
            true,
            rs.getBoolean("bill_wise_enabled"),
            rs.getBoolean("cost_centres_enabled"),
            rs.getBoolean("maintain_inventory"),
            rs.getBoolean("inventory_integrated"),
            rs.getBoolean("gst_enabled"),
            rs.getBoolean("multi_gstin_enabled"),
            rs.getString("registration_type") == null ? "REGULAR" : rs.getString("registration_type"),
            rs.getString("gstin") == null ? "" : rs.getString("gstin"),
            uiPer,
            gstEff == null ? fyStart : gstEff,
            rs.getBoolean("tds_enabled"),
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
            rs.getShort("currency_scale"),
            fyStart,
            rs.getObject("books_beginning_from", LocalDate.class),
            null,
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
            true,
            spec.billWiseEnabled(),
            spec.costCentresEnabled(),
            spec.maintainInventory(),
            spec.inventoryIntegrated(),
            spec.gstEnabled(),
            spec.multiGstinEnabled(),
            spec.gstRegistrationType(),
            spec.gstin(),
            spec.gstr1Periodicity(),
            spec.gstEffectiveFrom(),
            spec.tdsEnabled(),
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
        boolean billWise = f == null || f.billWiseEnabled() == null || f.billWiseEnabled();
        boolean costCentres = f != null && Boolean.TRUE.equals(f.costCentresEnabled());
        boolean maintainInv = f == null || f.maintainInventory() == null || f.maintainInventory();
        boolean invIntegrated = f == null || f.inventoryIntegrated() == null || f.inventoryIntegrated();
        boolean gstEnabled = f == null || f.gstEnabled() == null || f.gstEnabled();
        boolean multiGstin = f != null && Boolean.TRUE.equals(f.multiGstinEnabled());
        boolean tdsEnabled = f != null && Boolean.TRUE.equals(f.tdsEnabled());
        boolean mcaEditLog = f != null && Boolean.TRUE.equals(f.mcaEditLogEnforced());
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
            billWise,
            costCentres,
            maintainInv,
            invIntegrated,
            gstEnabled,
            multiGstin,
            gstRegType,
            gstin,
            periodicity,
            gstEff,
            tdsEnabled,
            mcaEditLog
        );
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
        boolean billWiseEnabled,
        boolean costCentresEnabled,
        boolean maintainInventory,
        boolean inventoryIntegrated,
        boolean gstEnabled,
        boolean multiGstinEnabled,
        String gstRegistrationType,
        String gstin,
        String gstr1Periodicity,
        LocalDate gstEffectiveFrom,
        boolean tdsEnabled,
        boolean mcaEditLogEnforced
    ) {
    }
}
