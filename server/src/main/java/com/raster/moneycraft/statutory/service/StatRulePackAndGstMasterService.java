package com.raster.moneycraft.statutory.service;

import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.EditLogSnapshotService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.statutory.dto.StatutoryDtos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Manages Multi-GSTIN registrations ({@code mc_gst_registration}) with Mod-36 validation
 * and MCA Edit Log tracking, and resolves signed declarative statutory rule packs
 * ({@code mc_stat_rule_pack}) with zero hardcoded tax slabs or statutory rates.
 */
@Service
public class StatRulePackAndGstMasterService {

    private static final Set<String> VALID_REG_TYPES = Set.of(
        "REGULAR", "COMPOSITION", "ISD", "SEZ", "TDS_DEDUCTOR", "TCS_COLLECTOR"
    );

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final EditLogSnapshotService editLogSnapshotService;
    private final AuditChainService auditChainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public StatRulePackAndGstMasterService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        EditLogSnapshotService editLogSnapshotService,
        AuditChainService auditChainService,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.editLogSnapshotService = editLogSnapshotService;
        this.auditChainService = auditChainService;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public record RulePackConfig(
        UUID id,
        String packCode,
        String countryCode,
        String fiscalYearLabel,
        String schemaVersion,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        List<BigDecimal> gstSlabs,
        List<String> gstSlabStrings,
        BigDecimal b2clInvoiceThresholdInr,
        BigDecimal ewayBillDefaultThresholdInr,
        RoundingMode slabRoundingMode,
        List<String> inheritanceHierarchy,
        Set<Integer> validHsnDigitLengths,
        String sacPrefix,
        boolean omitUqcForPureSac,
        boolean tobaccoDeemedMrpEnabled,
        String missingMrpErrorCode,
        boolean imsRemarksMandatoryOnReject,
        boolean imsRemarksMandatoryOnItcReduction,
        int maxSessionWindowHours,
        String fvuSchemaVersion,
        BigDecimal defaultNoPanRatePct,
        Map<String, Object> rawRulesMap,
        String packSha256,
        String signatureAlg,
        boolean active
    ) {
    }

    public record GstRegContext(
        UUID id,
        String gstin,
        String stateCode,
        String stateName,
        String registrationType,
        BigDecimal defaultGstRatePct,
        String defaultHsnSacCode,
        BigDecimal ewayBillThresholdOverride
    ) {
    }

    // -------------------------------------------------------------------------
    // Signed Declarative Statutory Rule Packs (`GET /statutory/rule-packs`)
    // -------------------------------------------------------------------------

    public List<StatutoryDtos.StatutoryRulePackDto> listRulePacks(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate asOfDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        List<StatutoryDtos.TdsNatureDto> companyTdsNatures = loadCompanyTdsNatureDtos(companyId, scale);

        if (asOfDate != null) {
            RulePackConfig activePack = loadActiveRulePack(asOfDate);
            return List.of(toRulePackDto(activePack, scale, companyTdsNatures));
        }

        List<RulePackConfig> rows = businessJdbcTemplate.query(
            """
            SELECT id, pack_code, country_code, fiscal_year_label, effective_from, effective_to,
                   schema_version, rules_json::text AS rules_json_text, pack_sha256, signature_alg, active
              FROM mc_stat_rule_pack
             WHERE active = true
             ORDER BY effective_from DESC, pack_code ASC
            """,
            this::mapRulePackRow
        );
        return rows.stream()
            .map(cfg -> toRulePackDto(cfg, scale, companyTdsNatures))
            .toList();
    }

    public RulePackConfig loadActiveRulePack(LocalDate asOfDate) {
        LocalDate targetDate = asOfDate != null ? asOfDate : LocalDate.now();
        List<RulePackConfig> exactMatches = businessJdbcTemplate.query(
            """
            SELECT id, pack_code, country_code, fiscal_year_label, effective_from, effective_to,
                   schema_version, rules_json::text AS rules_json_text, pack_sha256, signature_alg, active
              FROM mc_stat_rule_pack
             WHERE country_code = 'IN'
               AND active = true
               AND effective_from <= ?
               AND (effective_to IS NULL OR effective_to >= ?)
             ORDER BY effective_from DESC
             LIMIT 1
            """,
            this::mapRulePackRow,
            targetDate,
            targetDate
        );
        if (!exactMatches.isEmpty()) {
            return exactMatches.getFirst();
        }

        List<RulePackConfig> fallbackMatches = businessJdbcTemplate.query(
            """
            SELECT id, pack_code, country_code, fiscal_year_label, effective_from, effective_to,
                   schema_version, rules_json::text AS rules_json_text, pack_sha256, signature_alg, active
              FROM mc_stat_rule_pack
             WHERE country_code = 'IN'
               AND active = true
             ORDER BY effective_from DESC
             LIMIT 1
            """,
            this::mapRulePackRow
        );
        if (fallbackMatches.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-STAT-RULE-PACK-MISSING",
                "Statutory Rule Pack Not Found",
                "No active signed mc_stat_rule_pack exists for jurisdiction IN."
            );
        }
        return fallbackMatches.getFirst();
    }

    // -------------------------------------------------------------------------
    // Multi-GSTIN Registrations (`GET|POST|PUT /gst/registrations`)
    // -------------------------------------------------------------------------

    public List<StatutoryDtos.GstRegistrationDto> listGstRegistrations(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        return businessJdbcTemplate.query(
            """
            SELECT id, company_id, registration_name, state_code, state_name, registration_type,
                   gstin, effective_from, periodicity, einvoice_applicable, einvoice_applicable_from,
                   einvoice_bill_from_place, eway_bill_applicable, eway_bill_applicable_from,
                   eway_bill_threshold_override, default_gst_rate_pct, default_hsn_sac_code,
                   lut_bond_no, lut_valid_from, lut_valid_to, is_default, active,
                   master_seq_id, alter_id, version_no
              FROM mc_gst_registration
             WHERE company_id = ?
             ORDER BY is_default DESC, state_code ASC, gstin ASC
            """,
            (rs, rowNum) -> mapGstRegistrationDto(rs, scale),
            companyId
        );
    }

    public StatutoryDtos.GstRegistrationDto getGstRegistration(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID registrationId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        return requireGstRegistrationById(companyId, registrationId, scale);
    }

    public StatutoryDtos.GstRegistrationDto createGstRegistration(
        MoneyCraftPrincipal actor,
        UUID companyId,
        StatutoryDtos.UpsertGstRegistrationRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForCreate(companyId, true);
            NormalizedGstRegSpec spec = validateAndNormalizeGstReg(request, seq.currencyScale());

            Long dupCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_gst_registration WHERE company_id = ? AND gstin = ?",
                Long.class,
                companyId,
                spec.gstin()
            );
            if (dupCount != null && dupCount > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-STAT-GSTIN-DUPLICATE",
                    "Duplicate GSTIN Registration",
                    "GSTIN '" + spec.gstin() + "' is already registered for this company."
                );
            }

            Long existingCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_gst_registration WHERE company_id = ?",
                Long.class,
                companyId
            );
            boolean makeDefault = spec.isDefault() || existingCount == null || existingCount == 0L;
            if (makeDefault) {
                businessJdbcTemplate.update(
                    "UPDATE mc_gst_registration SET is_default = false WHERE company_id = ? AND is_default = true",
                    companyId
                );
            }

            UUID registrationId = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_gst_registration (
                    id, company_id, registration_name, state_code, state_name, registration_type,
                    gstin, effective_from, periodicity, einvoice_applicable, einvoice_applicable_from,
                    einvoice_bill_from_place, eway_bill_applicable, eway_bill_applicable_from,
                    eway_bill_threshold_override, default_gst_rate_pct, default_hsn_sac_code,
                    lut_bond_no, lut_valid_from, lut_valid_to, is_default, active,
                    master_seq_id, alter_id, version_no
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, 1
                )
                """,
                registrationId,
                companyId,
                spec.registrationName(),
                spec.stateCode(),
                spec.stateName(),
                spec.registrationType(),
                spec.gstin(),
                spec.effectiveFrom(),
                spec.periodicity(),
                spec.einvoiceApplicable(),
                spec.einvoiceApplicableFrom(),
                spec.einvoiceBillFromPlace(),
                spec.ewayBillApplicable(),
                spec.ewayBillApplicableFrom(),
                spec.ewayBillThresholdOverride(),
                spec.defaultGstRatePct(),
                spec.defaultHsnSacCode(),
                spec.lutBondNo(),
                spec.lutValidFrom(),
                spec.lutValidTo(),
                makeDefault,
                spec.active(),
                seq.masterSeqId(),
                seq.alterId()
            );

            StatutoryDtos.GstRegistrationDto created =
                requireGstRegistrationById(companyId, registrationId, seq.currencyScale());

            editLogSnapshotService.recordVersion(
                companyId,
                "GST_REGISTRATION",
                registrationId,
                created.masterSeqId(),
                created.alterId(),
                created.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                null,
                null,
                null,
                created
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "GST_REGISTRATION_CREATE",
                "GST_REGISTRATION",
                registrationId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "gstin", created.gstin(),
                    "stateCode", created.stateCode(),
                    "isDefault", created.isDefault()
                )
            );
            return created;
        });
    }

    public StatutoryDtos.GstRegistrationDto updateGstRegistration(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID registrationId,
        String ifMatchHeader,
        StatutoryDtos.UpsertGstRegistrationRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, true);

            List<Long> verRows = businessJdbcTemplate.query(
                "SELECT version_no FROM mc_gst_registration WHERE company_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("version_no"),
                companyId,
                registrationId
            );
            if (verRows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-STAT-GST-REG-NOT-FOUND",
                    "GST Registration Not Found",
                    "GST registration " + registrationId + " does not exist."
                );
            }
            CompanyMasterSequenceHelper.assertExpectedVersion(
                expectedVersion,
                verRows.getFirst(),
                "gst_registration"
            );

            StatutoryDtos.GstRegistrationDto beforeSnapshot =
                requireGstRegistrationById(companyId, registrationId, seq.currencyScale());
            NormalizedGstRegSpec spec = validateAndNormalizeGstReg(request, seq.currencyScale());

            Long dupCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_gst_registration WHERE company_id = ? AND gstin = ? AND id <> ?",
                Long.class,
                companyId,
                spec.gstin(),
                registrationId
            );
            if (dupCount != null && dupCount > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-STAT-GSTIN-DUPLICATE",
                    "Duplicate GSTIN Registration",
                    "GSTIN '" + spec.gstin() + "' is already assigned to another registration."
                );
            }

            if (spec.isDefault()) {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_gst_registration
                       SET is_default = false
                     WHERE company_id = ? AND id <> ? AND is_default = true
                    """,
                    companyId,
                    registrationId
                );
            }

            businessJdbcTemplate.update(
                """
                UPDATE mc_gst_registration
                   SET registration_name = ?,
                       state_code = ?,
                       state_name = ?,
                       registration_type = ?,
                       gstin = ?,
                       effective_from = ?,
                       periodicity = ?,
                       einvoice_applicable = ?,
                       einvoice_applicable_from = ?,
                       einvoice_bill_from_place = ?,
                       eway_bill_applicable = ?,
                       eway_bill_applicable_from = ?,
                       eway_bill_threshold_override = ?,
                       default_gst_rate_pct = ?,
                       default_hsn_sac_code = ?,
                       lut_bond_no = ?,
                       lut_valid_from = ?,
                       lut_valid_to = ?,
                       is_default = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.registrationName(),
                spec.stateCode(),
                spec.stateName(),
                spec.registrationType(),
                spec.gstin(),
                spec.effectiveFrom(),
                spec.periodicity(),
                spec.einvoiceApplicable(),
                spec.einvoiceApplicableFrom(),
                spec.einvoiceBillFromPlace(),
                spec.ewayBillApplicable(),
                spec.ewayBillApplicableFrom(),
                spec.ewayBillThresholdOverride(),
                spec.defaultGstRatePct(),
                spec.defaultHsnSacCode(),
                spec.lutBondNo(),
                spec.lutValidFrom(),
                spec.lutValidTo(),
                spec.isDefault(),
                spec.active(),
                seq.alterId(),
                companyId,
                registrationId
            );

            StatutoryDtos.GstRegistrationDto afterSnapshot =
                requireGstRegistrationById(companyId, registrationId, seq.currencyScale());

            editLogSnapshotService.recordVersion(
                companyId,
                "GST_REGISTRATION",
                registrationId,
                afterSnapshot.masterSeqId(),
                afterSnapshot.alterId(),
                afterSnapshot.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                null,
                null,
                beforeSnapshot,
                afterSnapshot
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "GST_REGISTRATION_ALTER",
                "GST_REGISTRATION",
                registrationId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "gstin", afterSnapshot.gstin(),
                    "versionNo", afterSnapshot.versionNo()
                )
            );
            return afterSnapshot;
        });
    }

    // -------------------------------------------------------------------------
    // Internal Resolution Helpers for Sibling Statutory Services
    // -------------------------------------------------------------------------

    public UUID resolveDefaultCompanyGstinId(UUID companyId, LocalDate effectiveDate) {
        LocalDate targetDate = effectiveDate != null ? effectiveDate : LocalDate.now();
        List<UUID> defaultIds = businessJdbcTemplate.query(
            """
            SELECT id
              FROM mc_gst_registration
             WHERE company_id = ?
               AND active = true
             ORDER BY is_default DESC,
                      (effective_from <= ?) DESC,
                      effective_from DESC,
                      created_at ASC
             LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            targetDate
        );
        return defaultIds.isEmpty() ? null : defaultIds.getFirst();
    }

    public GstRegContext resolveRegistrationOrCompanyFallback(
        UUID companyId,
        UUID gstRegistrationId,
        LocalDate effectiveDate
    ) {
        UUID candidateId = gstRegistrationId != null
            ? gstRegistrationId
            : resolveDefaultCompanyGstinId(companyId, effectiveDate);
        if (candidateId != null) {
            List<GstRegContext> regs = businessJdbcTemplate.query(
                """
                SELECT id, gstin, state_code, state_name, registration_type,
                       default_gst_rate_pct, default_hsn_sac_code, eway_bill_threshold_override
                  FROM mc_gst_registration
                 WHERE company_id = ? AND id = ?
                """,
                (rs, rowNum) -> new GstRegContext(
                    rs.getObject("id", UUID.class),
                    rs.getString("gstin"),
                    rs.getString("state_code"),
                    rs.getString("state_name"),
                    rs.getString("registration_type"),
                    rs.getBigDecimal("default_gst_rate_pct"),
                    rs.getString("default_hsn_sac_code"),
                    rs.getBigDecimal("eway_bill_threshold_override")
                ),
                companyId,
                candidateId
            );
            if (!regs.isEmpty()) {
                return regs.getFirst();
            }
        }

        List<GstRegContext> companyFallback = businessJdbcTemplate.query(
            """
            SELECT NULL AS gstin, state_code, state_name
              FROM mc_company
             WHERE id = ?
            """,
            (rs, rowNum) -> new GstRegContext(
                null,
                rs.getString("gstin"),
                rs.getString("state_code"),
                rs.getString("state_name"),
                "REGULAR",
                null,
                null,
                null
            ),
            companyId
        );
        if (companyFallback.isEmpty()) {
            return new GstRegContext(null, null, null, null, "REGULAR", null, null, null);
        }
        return companyFallback.getFirst();
    }

    public List<StatutoryDtos.TdsNatureDto> loadCompanyTdsNatureDtos(UUID companyId, int scale) {
        return businessJdbcTemplate.query(
            """
            SELECT id, section_code, payment_code, name, rate_individual_huf_pct,
                   rate_other_pct, no_pan_rate_pct, single_bill_threshold,
                   annual_aggregate_threshold, is_tcs, effective_from, active
              FROM mc_tds_nature
             WHERE company_id = ?
             ORDER BY section_code ASC, payment_code ASC
            """,
            (rs, rowNum) -> new StatutoryDtos.TdsNatureDto(
                rs.getObject("id", UUID.class),
                rs.getString("section_code"),
                rs.getString("payment_code"),
                rs.getString("name"),
                rs.getString("name"),
                ExactMoneyValidator.formatExactScale(
                    rs.getBigDecimal("single_bill_threshold").setScale(scale, RoundingMode.HALF_UP),
                    scale
                ),
                ExactMoneyValidator.formatExactScale(
                    rs.getBigDecimal("annual_aggregate_threshold").setScale(scale, RoundingMode.HALF_UP),
                    scale
                ),
                formatRate2(rs.getBigDecimal("rate_individual_huf_pct")),
                formatRate2(rs.getBigDecimal("rate_other_pct")),
                formatRate2(rs.getBigDecimal("no_pan_rate_pct")),
                rs.getBoolean("is_tcs"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getBoolean("active")
            ),
            companyId
        );
    }

    // -------------------------------------------------------------------------
    // Private Normalization & Mapping Helpers
    // -------------------------------------------------------------------------

    private StatutoryDtos.GstRegistrationDto requireGstRegistrationById(
        UUID companyId,
        UUID registrationId,
        int scale
    ) {
        List<StatutoryDtos.GstRegistrationDto> rows = businessJdbcTemplate.query(
            """
            SELECT id, company_id, registration_name, state_code, state_name, registration_type,
                   gstin, effective_from, periodicity, einvoice_applicable, einvoice_applicable_from,
                   einvoice_bill_from_place, eway_bill_applicable, eway_bill_applicable_from,
                   eway_bill_threshold_override, default_gst_rate_pct, default_hsn_sac_code,
                   lut_bond_no, lut_valid_from, lut_valid_to, is_default, active,
                   master_seq_id, alter_id, version_no
              FROM mc_gst_registration
             WHERE company_id = ? AND id = ?
            """,
            (rs, rowNum) -> mapGstRegistrationDto(rs, scale),
            companyId,
            registrationId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-STAT-GST-REG-NOT-FOUND",
                "GST Registration Not Found",
                "GST registration " + registrationId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private StatutoryDtos.GstRegistrationDto mapGstRegistrationDto(ResultSet rs, int scale) throws SQLException {
        String dbPeriodicity = rs.getString("periodicity");
        String uiPeriodicity = "QUARTERLY_QRMP".equals(dbPeriodicity) ? "QUARTERLY" : dbPeriodicity;
        BigDecimal ewbOverride = rs.getBigDecimal("eway_bill_threshold_override");
        String ewbStr = ewbOverride == null
            ? null
            : ExactMoneyValidator.formatExactScale(ewbOverride.setScale(scale, RoundingMode.HALF_UP), scale);
        BigDecimal defaultRate = rs.getBigDecimal("default_gst_rate_pct");
        String defaultRateStr = defaultRate == null ? null : formatRate2(defaultRate);
        boolean einvApp = rs.getBoolean("einvoice_applicable");
        LocalDate einvFrom = rs.getObject("einvoice_applicable_from", LocalDate.class);
        boolean ewbApp = rs.getBoolean("eway_bill_applicable");
        String lut = rs.getString("lut_bond_no");
        boolean isDef = rs.getBoolean("is_default");

        return new StatutoryDtos.GstRegistrationDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("registration_name"),
            rs.getString("state_code"),
            rs.getString("state_name"),
            rs.getString("registration_type"),
            rs.getString("gstin"),
            rs.getObject("effective_from", LocalDate.class),
            null,
            dbPeriodicity,
            uiPeriodicity,
            einvApp,
            einvApp,
            einvFrom,
            einvFrom,
            rs.getString("einvoice_bill_from_place"),
            ewbApp,
            ewbApp,
            rs.getObject("eway_bill_applicable_from", LocalDate.class),
            ewbStr,
            ewbStr,
            defaultRateStr,
            rs.getString("default_hsn_sac_code"),
            lut,
            lut,
            rs.getObject("lut_valid_from", LocalDate.class),
            rs.getObject("lut_valid_to", LocalDate.class),
            isDef,
            isDef,
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no")
        );
    }

    private record NormalizedGstRegSpec(
        String registrationName,
        String stateCode,
        String stateName,
        String registrationType,
        String gstin,
        LocalDate effectiveFrom,
        String periodicity,
        boolean einvoiceApplicable,
        LocalDate einvoiceApplicableFrom,
        String einvoiceBillFromPlace,
        boolean ewayBillApplicable,
        LocalDate ewayBillApplicableFrom,
        BigDecimal ewayBillThresholdOverride,
        BigDecimal defaultGstRatePct,
        String defaultHsnSacCode,
        String lutBondNo,
        LocalDate lutValidFrom,
        LocalDate lutValidTo,
        boolean isDefault,
        boolean active
    ) {
    }

    private NormalizedGstRegSpec validateAndNormalizeGstReg(
        StatutoryDtos.UpsertGstRegistrationRequest req,
        int scale
    ) {
        if (req == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-STAT-REQ-REQUIRED",
                "GST Registration Payload Required",
                "Request body is required."
            );
        }
        String rawStateCode = req.stateCode() == null ? "" : req.stateCode().trim();
        String gstin = ExactMoneyValidator.validateGstin(
            req.gstin(),
            rawStateCode.isEmpty() ? null : rawStateCode
        );
        String stateCode = rawStateCode.isEmpty()
            ? gstin.substring(0, 2)
            : (rawStateCode.length() == 1 ? "0" + rawStateCode : rawStateCode);
        String stateName = (req.stateName() == null || req.stateName().isBlank())
            ? "State " + stateCode
            : req.stateName().trim();
        String regName = (req.registrationName() == null || req.registrationName().isBlank())
            ? stateName + " (" + gstin + ")"
            : req.registrationName().trim();

        String regType = (req.registrationType() == null || req.registrationType().isBlank())
            ? "REGULAR"
            : req.registrationType().trim().toUpperCase(Locale.ROOT);
        if ("SEZ_UNIT".equals(regType) || "SEZ_DEVELOPER".equals(regType)) {
            regType = "SEZ";
        }
        if (!VALID_REG_TYPES.contains(regType)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-STAT-REG-TYPE-INVALID",
                "Invalid GST Registration Type",
                "registrationType must be one of " + VALID_REG_TYPES + "."
            );
        }

        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.of(2025, 4, 1);

        String rawPeriodicity = (req.periodicity() == null || req.periodicity().isBlank())
            ? "MONTHLY"
            : req.periodicity().trim().toUpperCase(Locale.ROOT);
        if ("QUARTERLY".equals(rawPeriodicity)) {
            rawPeriodicity = "QUARTERLY_QRMP";
        }
        if (!"MONTHLY".equals(rawPeriodicity) && !"QUARTERLY_QRMP".equals(rawPeriodicity)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-STAT-PERIODICITY-INVALID",
                "Invalid GST Periodicity",
                "periodicity must be MONTHLY or QUARTERLY_QRMP."
            );
        }

        BigDecimal ewbOverride = (req.ewayBillThresholdOverride() == null || req.ewayBillThresholdOverride().isBlank())
            ? null
            : ExactMoneyValidator.parseAndValidateExactScale(
                req.ewayBillThresholdOverride(),
                scale,
                "ewayBillThresholdOverride"
            );

        BigDecimal defaultRate = (req.defaultGstRatePct() == null || req.defaultGstRatePct().isBlank())
            ? null
            : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.defaultGstRatePct(),
                6,
                BigDecimal.ZERO,
                HUNDRED,
                false,
                "defaultGstRatePct"
            );

        if (req.lutValidFrom() != null && req.lutValidTo() != null && req.lutValidTo().isBefore(req.lutValidFrom())) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-STAT-LUT-DATES-INVALID",
                "Invalid LUT Validity Range",
                "lutValidTo cannot be earlier than lutValidFrom."
            );
        }

        return new NormalizedGstRegSpec(
            regName,
            stateCode,
            stateName,
            regType,
            gstin,
            effectiveFrom,
            rawPeriodicity,
            Boolean.TRUE.equals(req.einvoiceApplicable()),
            req.einvoiceApplicableFrom(),
            trimOrNull(req.einvoiceBillFromPlace(), 100),
            req.ewayBillApplicable() == null || req.ewayBillApplicable(),
            req.ewayBillApplicableFrom(),
            ewbOverride,
            defaultRate,
            trimOrNull(req.defaultHsnSacCode(), 16),
            trimOrNull(req.lutBondNo(), 64),
            req.lutValidFrom(),
            req.lutValidTo(),
            Boolean.TRUE.equals(req.isDefault()),
            req.active() == null || req.active()
        );
    }

    private RulePackConfig mapRulePackRow(ResultSet rs, int rowNum) throws SQLException {
        String rawJson = rs.getString("rules_json_text");
        JsonNode root;
        Map<String, Object> rawMap;
        try {
            root = objectMapper.readTree(rawJson);
            rawMap = objectMapper.readValue(rawJson, new TypeReference<Map<String, Object>>() { });
        } catch (JacksonException ex) {
            throw new IllegalStateException("Corrupt mc_stat_rule_pack JSON", ex);
        }

        JsonNode gst = root.path("gst");
        List<BigDecimal> slabs = new ArrayList<>();
        List<String> slabStrings = new ArrayList<>();
        for (JsonNode s : gst.path("slabsPct")) {
            BigDecimal bd = new BigDecimal(s.asText());
            slabs.add(bd);
            slabStrings.add(s.asText());
        }

        BigDecimal b2clThresh = new BigDecimal(gst.path("b2clInvoiceThresholdInr").asText("100000.00"));
        BigDecimal ewbThresh = new BigDecimal(gst.path("ewayBillDefaultThresholdInr").asText("50000.00"));
        RoundingMode roundingMode = RoundingMode.valueOf(gst.path("slabLevelRoundingMode").asText("HALF_UP"));

        List<String> hierarchy = new ArrayList<>();
        for (JsonNode h : gst.path("inheritanceHierarchy")) {
            hierarchy.add(h.asText());
        }

        JsonNode hsn = gst.path("hsnPhaseIii");
        Set<Integer> validHsnLengths = new LinkedHashSet<>();
        for (JsonNode lenNode : hsn.path("validDigitLengths")) {
            validHsnLengths.add(lenNode.asInt());
        }
        String sacPrefix = hsn.path("sacPrefix").asText("99");
        boolean omitUqcForSac = hsn.path("omitUqcForPureSac").asBoolean(true);

        JsonNode tobacco = gst.path("tobaccoDeemedMrpRel72");
        boolean tobaccoEnabled = tobacco.path("enabled").asBoolean(true);
        String missingMrpCode = tobacco.path("missingMrpErrorCode").asText("MC-TAX-MRP-REQUIRED");

        JsonNode ims = gst.path("imsRel72");
        boolean remarksReject = ims.path("remarksMandatoryOnReject").asBoolean(true);
        boolean remarksReduction = ims.path("remarksMandatoryOnItcReduction").asBoolean(true);

        JsonNode conn = gst.path("connectorSessionRel72");
        int maxSessionHours = conn.path("maxSessionWindowHours").asInt(720);

        JsonNode tds = root.path("tdsTcs");
        String fvuVer = tds.path("fvuSchemaVersion").asText("1.2");
        BigDecimal defaultNoPan = new BigDecimal(tds.path("defaultNoPanRatePct").asText("20.00"));

        return new RulePackConfig(
            rs.getObject("id", UUID.class),
            rs.getString("pack_code"),
            rs.getString("country_code").trim(),
            rs.getString("fiscal_year_label"),
            rs.getString("schema_version"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            List.copyOf(slabs),
            List.copyOf(slabStrings),
            b2clThresh,
            ewbThresh,
            roundingMode,
            List.copyOf(hierarchy),
            Set.copyOf(validHsnLengths),
            sacPrefix,
            omitUqcForSac,
            tobaccoEnabled,
            missingMrpCode,
            remarksReject,
            remarksReduction,
            maxSessionHours,
            fvuVer,
            defaultNoPan,
            rawMap,
            rs.getString("pack_sha256"),
            rs.getString("signature_alg"),
            rs.getBoolean("active")
        );
    }

    private static StatutoryDtos.StatutoryRulePackDto toRulePackDto(
        RulePackConfig cfg,
        int scale,
        List<StatutoryDtos.TdsNatureDto> tdsSections
    ) {
        return new StatutoryDtos.StatutoryRulePackDto(
            cfg.id(),
            cfg.packCode(),
            cfg.countryCode(),
            cfg.countryCode(),
            cfg.fiscalYearLabel(),
            cfg.schemaVersion(),
            cfg.effectiveFrom(),
            cfg.effectiveTo(),
            ExactMoneyValidator.formatExactScale(cfg.b2clInvoiceThresholdInr().setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(cfg.ewayBillDefaultThresholdInr().setScale(scale, RoundingMode.HALF_UP), scale),
            cfg.gstSlabStrings(),
            cfg.inheritanceHierarchy(),
            tdsSections,
            cfg.rawRulesMap(),
            cfg.packSha256(),
            cfg.signatureAlg(),
            "VERIFIED",
            cfg.active()
        );
    }

    static String formatRate2(BigDecimal rate) {
        if (rate == null) {
            return "0.00";
        }
        BigDecimal stripped = rate.stripTrailingZeros();
        if (stripped.scale() < 2) {
            return stripped.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        }
        return stripped.toPlainString();
    }

    private static String trimOrNull(String s, int maxLen) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= maxLen ? t : t.substring(0, maxLen);
    }
}
