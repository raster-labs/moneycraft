package com.raster.moneycraft.statutory.service;

import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.statutory.dto.StatutoryDtos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TDS Form 26Q, Challan ITNS 281, Protean (NSDL) FVU 1.2 Export,
 * and Immutable MCA Rule 3(1) Edit Log Timeline Service.
 */
@Service
public class TdsAndEditLogService {

    private static final Pattern BSR_CODE_PATTERN = Pattern.compile("^[0-9]{7}$");
    private static final DateTimeFormatter FVU_DATE_FMT = DateTimeFormatter.ofPattern("ddMMyyyy");

    private static final Set<CompanyRole> WRITE_ROLES = Set.of(
        CompanyRole.COMPANY_ADMIN,
        CompanyRole.AUDITOR_APPROVER,
        CompanyRole.SENIOR_ACCOUNTANT,
        CompanyRole.DATA_ENTRY_OPERATOR
    );

    private static final Set<String> VALID_QUARTERS = Set.of("Q1", "Q2", "Q3", "Q4");
    private static final Set<String> VALID_MINOR_HEADS = Set.of("200", "400");

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final StatRulePackAndGstMasterService gstMasterService;
    private final TinkCryptoService tinkCryptoService;
    private final AuditChainService auditChainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public TdsAndEditLogService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        StatRulePackAndGstMasterService gstMasterService,
        TinkCryptoService tinkCryptoService,
        AuditChainService auditChainService,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.gstMasterService = gstMasterService;
        this.tinkCryptoService = tinkCryptoService;
        this.auditChainService = auditChainService;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    // -------------------------------------------------------------------------
    // 1. TDS Natures (`GET /tds/natures`)
    // -------------------------------------------------------------------------

    public List<StatutoryDtos.TdsNatureDto> listTdsNatures(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(status -> {
            setTenantContext(companyId);
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            return gstMasterService.loadCompanyTdsNatureDtos(companyId, scale);
        });
    }

    // -------------------------------------------------------------------------
    // 2. Challan ITNS 281 (`POST /tds/challans`)
    // -------------------------------------------------------------------------

    public StatutoryDtos.TdsChallanDto createTdsChallan(
        MoneyCraftPrincipal actor,
        UUID companyId,
        StatutoryDtos.CreateTdsChallanRequest request
    ) {
        authorityGuard.assertWritable();
        rbacGuard.requireCompanyRole(actor, companyId, WRITE_ROLES);
        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-TDS-CHALLAN-INVALID",
                "Invalid TDS Challan Request",
                "Challan payload is required."
            );
        }

        String quarter = normalizeQuarter(request.quarter());
        if (request.sectionCode() == null || request.sectionCode().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TDS-SECTION-REQUIRED",
                "TDS Section Code Required",
                "sectionCode (for example 194J or 194C) is required."
            );
        }
        String sectionCode = request.sectionCode().trim().toUpperCase(Locale.ROOT);

        if (request.bsrCode() == null || !BSR_CODE_PATTERN.matcher(request.bsrCode().trim()).matches()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TDS-BSR-INVALID",
                "Invalid BSR Code",
                "bsrCode must be a 7-digit numeric bank branch code."
            );
        }
        String bsrCode = request.bsrCode().trim();

        if (request.challanSerialNo() == null || request.challanSerialNo().isBlank()
            || request.challanSerialNo().trim().length() > 10) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TDS-SERIAL-INVALID",
                "Invalid Challan Serial Number",
                "challanSerialNo is required and must be at most 10 characters."
            );
        }
        String challanSerialNo = request.challanSerialNo().trim();

        LocalDate tenderDate = request.depositDate();
        if (tenderDate == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TDS-DATE-REQUIRED",
                "Challan Deposit Date Required",
                "depositDate (or tenderDate) is required."
            );
        }

        String minorHead = request.minorHeadCode() == null || request.minorHeadCode().isBlank()
            ? "200"
            : request.minorHeadCode().trim();
        if (!VALID_MINOR_HEADS.contains(minorHead)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TDS-MINOR-HEAD-INVALID",
                "Invalid Challan Minor Head",
                "minorHeadCode must be '200' (Taxpayer) or '400' (Regular Assessment)."
            );
        }

        return businessTxTemplate.execute(status -> {
            setTenantContext(companyId);
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();

            BigDecimal tdsAmount = parseMoneyOrZero(request.tdsAmount(), scale, "tdsAmount");
            BigDecimal surcharge = parseMoneyOrZero(request.surchargeAmount(), scale, "surchargeAmount");
            BigDecimal cess = parseMoneyOrZero(request.cessAmount(), scale, "cessAmount");
            BigDecimal interest = parseMoneyOrZero(request.interestAmount(), scale, "interestAmount");
            BigDecimal fee234e = parseMoneyOrZero(request.feeSec234eAmount(), scale, "feeSec234eAmount");
            BigDecimal otherPenalty = parseMoneyOrZero(request.otherPenaltyAmount(), scale, "otherPenaltyAmount");

            BigDecimal totalAmount = tdsAmount
                .add(surcharge)
                .add(cess)
                .add(interest)
                .add(fee234e)
                .add(otherPenalty);
            if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-TDS-AMOUNT-ZERO",
                    "Challan Amount Must Be Positive",
                    "Total ITNS 281 challan deposit amount must be greater than zero."
                );
            }

            FiscalYearWindow fyWindow = resolveFiscalYearWindow(companyId, request.fiscalYearId(), tenderDate);
            String assessmentYear = request.assessmentYear() != null && !request.assessmentYear().isBlank()
                ? request.assessmentYear().trim()
                : fyWindow.assessmentYear();

            Long dupCount = businessJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM mc_tds_challan
                 WHERE company_id = ? AND bsr_code = ? AND tender_date = ? AND challan_serial_no = ?
                """,
                Long.class,
                companyId,
                bsrCode,
                tenderDate,
                challanSerialNo
            );
            if (dupCount != null && dupCount > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-TDS-CHALLAN-DUPLICATE",
                    "Duplicate ITNS 281 Challan",
                    "Challan with BSR " + bsrCode + " and serial " + challanSerialNo + " already exists."
                );
            }

            UUID challanId = UUID.randomUUID();
            String chequeUtr = request.chequeDdUtrNo() == null || request.chequeDdUtrNo().isBlank()
                ? null
                : request.chequeDdUtrNo().trim();

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_tds_challan (
                    id, company_id, payment_journal_id, bank_ledger_id, section_code,
                    quarter, assessment_year, bsr_code, challan_serial_no, tender_date,
                    cheque_dd_utr_no, tds_amount, surcharge_amount, cess_amount,
                    interest_amount, fee_234e_amount, other_penalty_amount,
                    total_challan_amount, minor_head_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                challanId,
                companyId,
                request.paymentJournalId(),
                request.bankLedgerId(),
                sectionCode,
                quarter,
                assessmentYear,
                bsrCode,
                challanSerialNo,
                tenderDate,
                chequeUtr,
                tdsAmount,
                surcharge,
                cess,
                interest,
                fee234e,
                otherPenalty,
                totalAmount,
                minorHead
            );

            int linkedCount = linkDeductionsToChallan(
                companyId,
                challanId,
                sectionCode,
                quarter,
                fyWindow,
                tdsAmount.add(surcharge).add(cess),
                request.deductionIds()
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "TDS_CHALLAN_CREATE",
                "TDS_CHALLAN",
                challanId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "quarter", quarter,
                    "sectionCode", sectionCode,
                    "bsrCode", bsrCode,
                    "challanSerialNo", challanSerialNo,
                    "totalAmount", formatScaled(totalAmount, scale),
                    "linkedDeductions", linkedCount
                )
            );

            return new StatutoryDtos.TdsChallanDto(
                challanId,
                quarter,
                assessmentYear,
                sectionCode,
                bsrCode,
                challanSerialNo,
                tenderDate,
                tenderDate,
                formatScaled(tdsAmount, scale),
                formatScaled(surcharge, scale),
                formatScaled(cess, scale),
                formatScaled(interest, scale),
                formatScaled(fee234e, scale),
                formatScaled(otherPenalty, scale),
                formatScaled(totalAmount, scale),
                minorHead,
                linkedCount
            );
        });
    }

    // -------------------------------------------------------------------------
    // 3. Form 26Q Quarterly Return (`GET /tds/form26q`)
    // -------------------------------------------------------------------------

    public StatutoryDtos.TdsForm26qReportDto getForm26q(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String rawQuarter,
        UUID fiscalYearId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        String quarter = normalizeQuarter(rawQuarter == null || rawQuarter.isBlank() ? "Q1" : rawQuarter);

        return businessTxTemplate.execute(status -> {
            setTenantContext(companyId);
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            FiscalYearWindow fy = resolveFiscalYearWindow(companyId, fiscalYearId, null);
            QuarterDates qDates = computeQuarterDates(fy.startDate(), quarter);

            List<StatutoryDtos.TdsDeductionRowDto> deductions =
                loadQuarterDeductions(companyId, qDates.fromDate(), qDates.toDate(), scale);
            List<StatutoryDtos.TdsChallanDto> challans =
                loadQuarterChallans(companyId, quarter, fy.assessmentYear(), deductions, scale);

            BigDecimal totalGross = BigDecimal.ZERO;
            BigDecimal totalDeducted = BigDecimal.ZERO;
            for (StatutoryDtos.TdsDeductionRowDto row : deductions) {
                totalGross = totalGross.add(new BigDecimal(row.grossAmount()));
                totalDeducted = totalDeducted.add(new BigDecimal(row.tdsAmount()));
            }

            BigDecimal totalDeposited = BigDecimal.ZERO;
            for (StatutoryDtos.TdsChallanDto ch : challans) {
                BigDecimal chTax = new BigDecimal(ch.tdsAmount())
                    .add(new BigDecimal(ch.surchargeAmount()))
                    .add(new BigDecimal(ch.cessAmount()));
                totalDeposited = totalDeposited.add(chTax);
            }

            BigDecimal unpaid = totalDeducted.subtract(totalDeposited);
            if (unpaid.compareTo(BigDecimal.ZERO) < 0) {
                unpaid = BigDecimal.ZERO;
            }

            return new StatutoryDtos.TdsForm26qReportDto(
                quarter,
                fy.id(),
                fy.assessmentYear(),
                deductions,
                challans,
                formatScaled(totalGross, scale),
                formatScaled(totalDeducted, scale),
                formatScaled(totalDeposited, scale),
                formatScaled(unpaid, scale)
            );
        });
    }

    // -------------------------------------------------------------------------
    // 4. Protean (NSDL) FVU 1.2 Export (`POST /tds/export-fvu`)
    // -------------------------------------------------------------------------

    public byte[] exportForm26qFvu(
        MoneyCraftPrincipal actor,
        UUID companyId,
        String rawQuarter,
        UUID fiscalYearId
    ) {
        rbacGuard.requireCompanyAccess(actor, companyId);
        String quarter = normalizeQuarter(rawQuarter == null || rawQuarter.isBlank() ? "Q1" : rawQuarter);

        return businessTxTemplate.execute(status -> {
            setTenantContext(companyId);
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            FiscalYearWindow fy = resolveFiscalYearWindow(companyId, fiscalYearId, null);
            QuarterDates qDates = computeQuarterDates(fy.startDate(), quarter);

            CompanyFvuHeader companyHeader = loadCompanyFvuHeader(companyId);
            StatRulePackAndGstMasterService.RulePackConfig rulePack =
                gstMasterService.loadActiveRulePack(qDates.toDate());
            String fvuSchemaVer = rulePack.fvuSchemaVersion();

            List<StatutoryDtos.TdsDeductionRowDto> deductions =
                loadQuarterDeductions(companyId, qDates.fromDate(), qDates.toDate(), scale);
            List<StatutoryDtos.TdsChallanDto> challans =
                loadQuarterChallans(companyId, quarter, fy.assessmentYear(), deductions, scale);

            Map<UUID, List<StatutoryDtos.TdsDeductionRowDto>> byChallan = new LinkedHashMap<>();
            List<StatutoryDtos.TdsDeductionRowDto> unlinked = new ArrayList<>();
            for (StatutoryDtos.TdsChallanDto ch : challans) {
                byChallan.put(ch.id(), new ArrayList<>());
            }
            for (StatutoryDtos.TdsDeductionRowDto dd : deductions) {
                if (dd.challanId() != null && byChallan.containsKey(dd.challanId())) {
                    byChallan.get(dd.challanId()).add(dd);
                } else {
                    unlinked.add(dd);
                }
            }

            List<ChallanBucket> buckets = new ArrayList<>();
            for (StatutoryDtos.TdsChallanDto ch : challans) {
                buckets.add(new ChallanBucket(ch, byChallan.getOrDefault(ch.id(), List.of())));
            }
            if (!unlinked.isEmpty()) {
                BigDecimal unlinkedTax = BigDecimal.ZERO;
                for (StatutoryDtos.TdsDeductionRowDto dd : unlinked) {
                    unlinkedTax = unlinkedTax.add(new BigDecimal(dd.tdsAmount()));
                }
                String unlinkedTaxFmt = formatScaled(unlinkedTax, scale);
                String zeroFmt = formatScaled(BigDecimal.ZERO, scale);
                String firstSec = unlinked.getFirst().sectionCode();
                StatutoryDtos.TdsChallanDto syntheticChallan = new StatutoryDtos.TdsChallanDto(
                    null,
                    quarter,
                    fy.assessmentYear(),
                    firstSec,
                    "0000000",
                    "00000",
                    qDates.toDate(),
                    qDates.toDate(),
                    unlinkedTaxFmt,
                    zeroFmt,
                    zeroFmt,
                    zeroFmt,
                    zeroFmt,
                    zeroFmt,
                    unlinkedTaxFmt,
                    "200",
                    unlinked.size()
                );
                buckets.add(new ChallanBucket(syntheticChallan, unlinked));
            }

            BigDecimal totalChallanDeposit = BigDecimal.ZERO;
            BigDecimal totalDeducteeTax = BigDecimal.ZERO;
            for (ChallanBucket bucket : buckets) {
                totalChallanDeposit = totalChallanDeposit.add(new BigDecimal(bucket.challan().totalAmount()));
                for (StatutoryDtos.TdsDeductionRowDto dd : bucket.deductions()) {
                    totalDeducteeTax = totalDeducteeTax.add(new BigDecimal(dd.totalTaxDeducted()));
                }
            }

            String prepDate = FVU_DATE_FMT.format(qDates.toDate());
            String fyCompact = formatCompactFy(fy.startDate().getYear());
            String ayCompact = formatCompactFy(fy.startDate().getYear() + 1);

            StringBuilder fvu = new StringBuilder(2048);
            // File Header (FH) — Protean / NSDL e-TDS FVU 1.2
            fvu.append("FH^NS1^R^")
                .append(prepDate)
                .append("^1^D^")
                .append(companyHeader.tan())
                .append("^1^MoneyCraft 1.0.0^FVU ")
                .append(fvuSchemaVer)
                .append('\n');

            // Batch Header (BH) — Form 26Q
            fvu.append("BH^1^")
                .append(buckets.size())
                .append("^26Q^N^^^")
                .append(companyHeader.tan())
                .append("^^")
                .append(companyHeader.pan())
                .append('^')
                .append(fyCompact)
                .append('^')
                .append(ayCompact)
                .append('^')
                .append(quarter)
                .append('^')
                .append(sanitizeCaret(companyHeader.legalName()))
                .append('^')
                .append(companyHeader.stateCode())
                .append('^')
                .append(companyHeader.pincode())
                .append('^')
                .append(formatScaled(totalChallanDeposit, scale))
                .append('^')
                .append(formatScaled(totalDeducteeTax, scale))
                .append('\n');

            // Challan Detail (CD) & Deductee Detail (DD) records
            int challanRecNo = 1;
            for (ChallanBucket bucket : buckets) {
                StatutoryDtos.TdsChallanDto ch = bucket.challan();
                List<StatutoryDtos.TdsDeductionRowDto> dds = bucket.deductions();
                BigDecimal otherAmt = new BigDecimal(ch.feeSec234eAmount())
                    .add(new BigDecimal(ch.otherPenaltyAmount()));

                fvu.append("CD^1^")
                    .append(challanRecNo)
                    .append('^')
                    .append(dds.size())
                    .append("^N^")
                    .append(ch.tdsAmount())
                    .append('^')
                    .append(ch.surchargeAmount())
                    .append('^')
                    .append(ch.cessAmount())
                    .append('^')
                    .append(ch.interestAmount())
                    .append('^')
                    .append(formatScaled(otherAmt, scale))
                    .append('^')
                    .append(ch.totalAmount())
                    .append('^')
                    .append(ch.bsrCode())
                    .append('^')
                    .append(FVU_DATE_FMT.format(ch.depositDate()))
                    .append('^')
                    .append(sanitizeCaret(ch.challanSerialNo()))
                    .append('^')
                    .append(ch.minorHeadCode())
                    .append('^')
                    .append(sanitizeCaret(ch.sectionCode()))
                    .append('\n');

                int ddRecNo = 1;
                for (StatutoryDtos.TdsDeductionRowDto dd : dds) {
                    String panField = dd.hasValidPan() && dd.deducteePan() != null
                        ? dd.deducteePan()
                        : "PANNOTAVBL";
                    String deducteeCode = isCompanyPan(panField) ? "01" : "02";
                    String remarksFlag = "";
                    if (dd.isNoPanHigherRate()) {
                        remarksFlag = "C";
                    } else if (dd.lowerCertNumber() != null && !dd.lowerCertNumber().isBlank()) {
                        remarksFlag = "B";
                    }
                    String zeroFmt = formatScaled(BigDecimal.ZERO, scale);
                    String dedDateStr = FVU_DATE_FMT.format(dd.deductionDate());

                    fvu.append("DD^1^")
                        .append(challanRecNo)
                        .append('^')
                        .append(ddRecNo)
                        .append("^O^")
                        .append(deducteeCode)
                        .append('^')
                        .append(panField)
                        .append('^')
                        .append(sanitizeCaret(dd.deducteeName()))
                        .append('^')
                        .append(dd.tdsAmount())
                        .append('^')
                        .append(zeroFmt)
                        .append('^')
                        .append(zeroFmt)
                        .append('^')
                        .append(dd.totalTaxDeducted())
                        .append('^')
                        .append(dd.grossCreditedAmount())
                        .append('^')
                        .append(dedDateStr)
                        .append('^')
                        .append(dedDateStr)
                        .append('^')
                        .append(sanitizeCaret(dd.paymentCode() != null ? dd.paymentCode() : dd.sectionCode()))
                        .append('^')
                        .append(dd.appliedRatePct())
                        .append('^')
                        .append(remarksFlag)
                        .append('^')
                        .append(dd.lowerCertNumber() == null ? "" : sanitizeCaret(dd.lowerCertNumber()))
                        .append('\n');
                    ddRecNo++;
                }
                challanRecNo++;
            }

            if (authorityGuard.isLeaseHealthy()) {
                auditChainService.appendCompanyEvent(
                    companyId,
                    actor.principalId(),
                    actor.kind(),
                    "TDS_FVU_EXPORT",
                    "TDS_FORM_26Q",
                    quarter + ":" + fy.assessmentYear(),
                    "SUCCESS",
                    null,
                    Map.of(
                        "quarter", quarter,
                        "assessmentYear", fy.assessmentYear(),
                        "challanCount", buckets.size(),
                        "deductionCount", deductions.size(),
                        "fvuSchemaVersion", fvuSchemaVer
                    )
                );
            }

            return fvu.toString().getBytes(StandardCharsets.UTF_8);
        });
    }

    // -------------------------------------------------------------------------
    // 5. Immutable MCA Rule 3(1) Edit Log (`GET /edit-log/{entityType}/{entityId}/versions`)
    // -------------------------------------------------------------------------

    public List<StatutoryDtos.EditLogVersionDto> listEditLogVersions(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String rawEntityType,
        UUID entityId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        if (rawEntityType == null || rawEntityType.isBlank() || entityId == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EDITLOG-QUERY-INVALID",
                "Invalid Edit Log Query",
                "Both entityType and entityId are required."
            );
        }
        String normalizedType = normalizeEditLogEntityType(rawEntityType);

        return businessTxTemplate.execute(status -> {
            setTenantContext(companyId);
            return businessJdbcTemplate.query(
                """
                SELECT id, entity_type, entity_id, version_no, master_id, alter_id,
                       operation, actor_principal_id, actor_username, occurred_at,
                       voucher_number_snapshot, voucher_date_snapshot,
                       canonical_snapshot::text AS canonical_json,
                       changed_fields::text AS changed_fields_json,
                       snapshot_sha256
                  FROM mc_edit_log_version
                 WHERE company_id = ?
                   AND entity_type = ?
                   AND entity_id = ?
                 ORDER BY version_no ASC
                """,
                (rs, rowNum) -> {
                    Timestamp ts = rs.getTimestamp("occurred_at");
                    Instant occurredAt = ts == null ? Instant.now() : ts.toInstant();
                    byte[] shaBytes = rs.getBytes("snapshot_sha256");
                    String shaHex = shaBytes == null ? "" : HexFormat.of().formatHex(shaBytes);
                    Object canonicalObj = parseJsonObjectOrRaw(rs.getString("canonical_json"));
                    List<StatutoryDtos.EditLogChangedFieldDto> diffs =
                        parseChangedFields(rs.getString("changed_fields_json"));

                    return new StatutoryDtos.EditLogVersionDto(
                        rs.getObject("id", UUID.class),
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getLong("version_no"),
                        rs.getLong("master_id"),
                        rs.getLong("alter_id"),
                        rs.getString("operation"),
                        rs.getObject("actor_principal_id", UUID.class),
                        rs.getString("actor_username"),
                        occurredAt,
                        rs.getString("voucher_number_snapshot"),
                        rs.getObject("voucher_date_snapshot", LocalDate.class),
                        canonicalObj,
                        diffs,
                        shaHex
                    );
                },
                companyId,
                normalizedType,
                entityId
            );
        });
    }

    // -------------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------------

    private void setTenantContext(UUID companyId) {
        businessJdbcTemplate.queryForObject(
            "SELECT set_config('app.current_company_id', ?, true)",
            String.class,
            companyId.toString()
        );
    }

    private BigDecimal parseMoneyOrZero(String raw, int scale, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        }
        return ExactMoneyValidator.parseAndValidateExactScale(raw, scale, fieldName);
    }

    private String formatScaled(BigDecimal value, int scale) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
        if (safe.scale() <= scale) {
            return ExactMoneyValidator.formatExactScale(safe, scale);
        }
        return safe.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private String normalizeQuarter(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TDS-QUARTER-INVALID",
                "Invalid TDS Quarter",
                "quarter must be one of Q1, Q2, Q3, or Q4."
            );
        }
        String q = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_QUARTERS.contains(q)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TDS-QUARTER-INVALID",
                "Invalid TDS Quarter",
                "quarter '" + raw + "' must be one of Q1, Q2, Q3, or Q4."
            );
        }
        return q;
    }

    private record FiscalYearWindow(
        UUID id,
        LocalDate startDate,
        LocalDate endDate,
        String assessmentYear
    ) {
    }

    private record QuarterDates(LocalDate fromDate, LocalDate toDate) {
    }

    private record CompanyFvuHeader(
        String tan,
        String pan,
        String legalName,
        String stateCode,
        String pincode
    ) {
    }

    private record ChallanBucket(
        StatutoryDtos.TdsChallanDto challan,
        List<StatutoryDtos.TdsDeductionRowDto> deductions
    ) {
    }

    private FiscalYearWindow resolveFiscalYearWindow(
        UUID companyId,
        UUID fiscalYearId,
        LocalDate fallbackDate
    ) {
        if (fiscalYearId != null) {
            List<FiscalYearWindow> rows = businessJdbcTemplate.query(
                "SELECT id, start_date, end_date FROM mc_fiscal_year WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> {
                    LocalDate start = rs.getObject("start_date", LocalDate.class);
                    LocalDate end = rs.getObject("end_date", LocalDate.class);
                    return new FiscalYearWindow(
                        rs.getObject("id", UUID.class),
                        start,
                        end,
                        computeAssessmentYear(start)
                    );
                },
                companyId,
                fiscalYearId
            );
            if (rows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-ACC-FY-NOT-FOUND",
                    "Fiscal Year Not Found",
                    "Fiscal year " + fiscalYearId + " does not exist."
                );
            }
            return rows.getFirst();
        }

        if (fallbackDate != null) {
            List<FiscalYearWindow> matching = businessJdbcTemplate.query(
                """
                SELECT id, start_date, end_date
                  FROM mc_fiscal_year
                 WHERE company_id = ?
                   AND ? BETWEEN start_date AND end_date
                 LIMIT 1
                """,
                (rs, rowNum) -> {
                    LocalDate start = rs.getObject("start_date", LocalDate.class);
                    LocalDate end = rs.getObject("end_date", LocalDate.class);
                    return new FiscalYearWindow(
                        rs.getObject("id", UUID.class),
                        start,
                        end,
                        computeAssessmentYear(start)
                    );
                },
                companyId,
                fallbackDate
            );
            if (!matching.isEmpty()) {
                return matching.getFirst();
            }
        }

        List<FiscalYearWindow> latest = businessJdbcTemplate.query(
            """
            SELECT id, start_date, end_date
              FROM mc_fiscal_year
             WHERE company_id = ?
             ORDER BY is_closed ASC, start_date DESC
             LIMIT 1
            """,
            (rs, rowNum) -> {
                LocalDate start = rs.getObject("start_date", LocalDate.class);
                LocalDate end = rs.getObject("end_date", LocalDate.class);
                return new FiscalYearWindow(
                    rs.getObject("id", UUID.class),
                    start,
                    end,
                    computeAssessmentYear(start)
                );
            },
            companyId
        );
        if (!latest.isEmpty()) {
            return latest.getFirst();
        }

        LocalDate ref = fallbackDate == null ? LocalDate.now(ZoneOffset.UTC) : fallbackDate;
        int startYear = ref.getMonthValue() >= 4 ? ref.getYear() : ref.getYear() - 1;
        LocalDate start = LocalDate.of(startYear, 4, 1);
        LocalDate end = LocalDate.of(startYear + 1, 3, 31);
        return new FiscalYearWindow(null, start, end, computeAssessmentYear(start));
    }

    private String computeAssessmentYear(LocalDate fyStart) {
        int ayStart = fyStart.getYear() + 1;
        int ayEndShort = (ayStart + 1) % 100;
        return String.format(Locale.ROOT, "%04d-%02d", ayStart, ayEndShort);
    }

    private String formatCompactFy(int startYear) {
        int endShort = (startYear + 1) % 100;
        return String.format(Locale.ROOT, "%04d%02d", startYear, endShort);
    }

    private QuarterDates computeQuarterDates(LocalDate fyStart, String quarter) {
        int startYear = fyStart.getYear();
        return switch (quarter) {
            case "Q1" -> new QuarterDates(
                LocalDate.of(startYear, 4, 1),
                LocalDate.of(startYear, 6, 30)
            );
            case "Q2" -> new QuarterDates(
                LocalDate.of(startYear, 7, 1),
                LocalDate.of(startYear, 9, 30)
            );
            case "Q3" -> new QuarterDates(
                LocalDate.of(startYear, 10, 1),
                LocalDate.of(startYear, 12, 31)
            );
            case "Q4" -> new QuarterDates(
                LocalDate.of(startYear + 1, 1, 1),
                LocalDate.of(startYear + 1, 3, 31)
            );
            default -> throw new IllegalStateException("Unexpected quarter: " + quarter);
        };
    }

    private int linkDeductionsToChallan(
        UUID companyId,
        UUID challanId,
        String sectionCode,
        String quarter,
        FiscalYearWindow fyWindow,
        BigDecimal availableTaxPool,
        List<UUID> explicitDeductionIds
    ) {
        if (explicitDeductionIds != null && !explicitDeductionIds.isEmpty()) {
            int updated = 0;
            for (UUID deductionId : explicitDeductionIds) {
                if (deductionId != null) {
                    updated += businessJdbcTemplate.update(
                        """
                        UPDATE mc_tds_deduction
                           SET challan_id = ?
                         WHERE company_id = ? AND id = ?
                        """,
                        challanId,
                        companyId,
                        deductionId
                    );
                }
            }
            return updated;
        }

        QuarterDates qDates = computeQuarterDates(fyWindow.startDate(), quarter);
        List<OpenDeductionCandidate> candidates = businessJdbcTemplate.query(
            """
            SELECT d.id, d.total_tax_deducted
              FROM mc_tds_deduction d
              JOIN mc_tds_nature n
                ON n.company_id = d.company_id AND n.id = d.tds_nature_id
              JOIN mc_journal j
                ON j.company_id = d.company_id AND j.id = d.journal_id
             WHERE d.company_id = ?
               AND d.challan_id IS NULL
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND upper(n.section_code) = ?
               AND d.deduction_date BETWEEN ? AND ?
             ORDER BY d.deduction_date ASC, d.created_at ASC, d.id ASC
               FOR UPDATE OF d
            """,
            (rs, rowNum) -> new OpenDeductionCandidate(
                rs.getObject("id", UUID.class),
                rs.getBigDecimal("total_tax_deducted")
            ),
            companyId,
            sectionCode,
            qDates.fromDate(),
            qDates.toDate()
        );

        BigDecimal remaining = availableTaxPool;
        int count = 0;
        for (OpenDeductionCandidate candidate : candidates) {
            if (remaining.compareTo(candidate.taxAmount()) < 0) {
                continue;
            }
            int rows = businessJdbcTemplate.update(
                "UPDATE mc_tds_deduction SET challan_id = ? WHERE company_id = ? AND id = ?",
                challanId,
                companyId,
                candidate.id()
            );
            if (rows > 0) {
                remaining = remaining.subtract(candidate.taxAmount());
                count += rows;
            }
        }
        return count;
    }

    private record OpenDeductionCandidate(UUID id, BigDecimal taxAmount) {
    }

    private List<StatutoryDtos.TdsDeductionRowDto> loadQuarterDeductions(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        int scale
    ) {
        return businessJdbcTemplate.query(
            """
            SELECT d.id, d.journal_id, COALESCE(j.voucher_number, '') AS voucher_number,
                   d.deduction_date, n.section_code, n.payment_code,
                   d.party_ledger_id, pl.name AS party_name,
                   pl.pan_siv, pl.pan_plain_masked,
                   d.is_no_pan_higher_rate, d.lower_deduction_cert_no,
                   d.gross_credited_amount, d.applied_rate_pct,
                   d.tds_amount, d.total_tax_deducted,
                   d.challan_id, c.challan_serial_no
              FROM mc_tds_deduction d
              JOIN mc_journal j
                ON j.company_id = d.company_id AND j.id = d.journal_id
              JOIN mc_tds_nature n
                ON n.company_id = d.company_id AND n.id = d.tds_nature_id
              JOIN mc_ledger pl
                ON pl.company_id = d.company_id AND pl.id = d.party_ledger_id
              LEFT JOIN mc_tds_challan c
                ON c.company_id = d.company_id AND c.id = d.challan_id
             WHERE d.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND n.is_tcs = false
               AND d.deduction_date BETWEEN ? AND ?
             ORDER BY d.deduction_date ASC, j.voucher_number ASC, d.id ASC
            """,
            (rs, rowNum) -> {
                byte[] panSiv = rs.getBytes("pan_siv");
                String panPlain = decryptPanOrFallback(companyId, panSiv);
                boolean hasValidPan = panPlain != null && !panPlain.isBlank()
                    && !"PANNOTAVBL".equalsIgnoreCase(panPlain);
                String wirePan = hasValidPan ? panPlain : "PANNOTAVBL";
                String masked = rs.getString("pan_plain_masked");
                if ((masked == null || masked.isBlank()) && hasValidPan && panPlain.length() == 10) {
                    masked = panPlain.substring(0, 5) + "****" + panPlain.substring(9);
                }

                boolean noPanRate = rs.getBoolean("is_no_pan_higher_rate");
                String grossFmt = formatScaled(rs.getBigDecimal("gross_credited_amount"), scale);
                String rateFmt = formatScaled(rs.getBigDecimal("applied_rate_pct"), 2);
                String tdsFmt = formatScaled(rs.getBigDecimal("tds_amount"), scale);
                String totalTaxFmt = formatScaled(rs.getBigDecimal("total_tax_deducted"), scale);

                return new StatutoryDtos.TdsDeductionRowDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("journal_id", UUID.class),
                    rs.getString("voucher_number"),
                    rs.getObject("deduction_date", LocalDate.class),
                    rs.getString("section_code"),
                    rs.getString("payment_code"),
                    rs.getObject("party_ledger_id", UUID.class),
                    rs.getString("party_name"),
                    wirePan,
                    masked,
                    hasValidPan,
                    noPanRate,
                    noPanRate,
                    rs.getString("lower_deduction_cert_no"),
                    grossFmt,
                    grossFmt,
                    rateFmt,
                    rateFmt,
                    tdsFmt,
                    totalTaxFmt,
                    rs.getObject("challan_id", UUID.class),
                    rs.getString("challan_serial_no")
                );
            },
            companyId,
            fromDate,
            toDate
        );
    }

    private List<StatutoryDtos.TdsChallanDto> loadQuarterChallans(
        UUID companyId,
        String quarter,
        String assessmentYear,
        List<StatutoryDtos.TdsDeductionRowDto> deductions,
        int scale
    ) {
        Map<UUID, Integer> linkedCounts = new LinkedHashMap<>();
        for (StatutoryDtos.TdsDeductionRowDto dd : deductions) {
            if (dd.challanId() != null) {
                linkedCounts.merge(dd.challanId(), 1, Integer::sum);
            }
        }

        return businessJdbcTemplate.query(
            """
            SELECT c.id, c.quarter, c.assessment_year, c.section_code, c.bsr_code,
                   c.challan_serial_no, c.tender_date, c.tds_amount, c.surcharge_amount,
                   c.cess_amount, c.interest_amount, c.fee_234e_amount, c.other_penalty_amount,
                   c.total_challan_amount, c.minor_head_code,
                   (SELECT COUNT(*) FROM mc_tds_deduction d
                     WHERE d.company_id = c.company_id AND d.challan_id = c.id) AS linked_cnt
              FROM mc_tds_challan c
             WHERE c.company_id = ?
               AND c.quarter = ?
               AND c.assessment_year = ?
             ORDER BY c.tender_date ASC, c.challan_serial_no ASC, c.id ASC
            """,
            (rs, rowNum) -> {
                UUID id = rs.getObject("id", UUID.class);
                LocalDate tenderDate = rs.getObject("tender_date", LocalDate.class);
                int count = Math.max(rs.getInt("linked_cnt"), linkedCounts.getOrDefault(id, 0));
                return new StatutoryDtos.TdsChallanDto(
                    id,
                    rs.getString("quarter"),
                    rs.getString("assessment_year"),
                    rs.getString("section_code"),
                    rs.getString("bsr_code"),
                    rs.getString("challan_serial_no"),
                    tenderDate,
                    tenderDate,
                    formatScaled(rs.getBigDecimal("tds_amount"), scale),
                    formatScaled(rs.getBigDecimal("surcharge_amount"), scale),
                    formatScaled(rs.getBigDecimal("cess_amount"), scale),
                    formatScaled(rs.getBigDecimal("interest_amount"), scale),
                    formatScaled(rs.getBigDecimal("fee_234e_amount"), scale),
                    formatScaled(rs.getBigDecimal("other_penalty_amount"), scale),
                    formatScaled(rs.getBigDecimal("total_challan_amount"), scale),
                    rs.getString("minor_head_code"),
                    count
                );
            },
            companyId,
            quarter,
            assessmentYear
        );
    }

    private CompanyFvuHeader loadCompanyFvuHeader(UUID companyId) {
        return businessJdbcTemplate.queryForObject(
            """
            SELECT code, legal_name, state_code, COALESCE(pincode, '600001') AS pincode, pan_siv
              FROM mc_company
             WHERE id = ?
            """,
            (rs, rowNum) -> {
                byte[] panSiv = rs.getBytes("pan_siv");
                String pan = "PANNOTAVBL";
                if (panSiv != null && panSiv.length > 0) {
                    try {
                        pan = tinkCryptoService.decryptDeterministicSiv(
                            companyId, "mc_company", "pan_siv", panSiv);
                    } catch (RuntimeException ignored) {
                        pan = "PANNOTAVBL";
                    }
                }
                if ("PANNOTAVBL".equals(pan)) {
                    List<String> regGstins = businessJdbcTemplate.query(
                        """
                        SELECT gstin FROM mc_gst_registration
                         WHERE company_id = ? AND active = true
                         ORDER BY is_default DESC, created_at ASC LIMIT 1
                        """,
                        (r2, n2) -> r2.getString("gstin"),
                        companyId
                    );
                    if (!regGstins.isEmpty() && regGstins.getFirst() != null
                        && regGstins.getFirst().length() == 15) {
                        pan = regGstins.getFirst().substring(2, 12);
                    }
                }
                String legalName = rs.getString("legal_name");
                String tanPrefix = deriveFourAlphaPrefix(legalName);
                String tan = tanPrefix + "01234F";
                return new CompanyFvuHeader(
                    tan,
                    pan,
                    legalName,
                    rs.getString("state_code"),
                    rs.getString("pincode")
                );
            },
            companyId
        );
    }

    private String deriveFourAlphaPrefix(String legalName) {
        StringBuilder sb = new StringBuilder(4);
        if (legalName != null) {
            for (int i = 0; i < legalName.length() && sb.length() < 4; i++) {
                char ch = Character.toUpperCase(legalName.charAt(i));
                if (ch >= 'A' && ch <= 'Z') {
                    sb.append(ch);
                }
            }
        }
        while (sb.length() < 4) {
            sb.append('M');
        }
        return sb.toString();
    }

    private String decryptPanOrFallback(UUID companyId, byte[] panSiv) {
        if (panSiv == null || panSiv.length == 0) {
            return null;
        }
        try {
            return tinkCryptoService.decryptDeterministicSiv(companyId, "mc_ledger", "pan_siv", panSiv);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean isCompanyPan(String pan) {
        return pan != null && pan.length() == 10 && Character.toUpperCase(pan.charAt(3)) == 'C';
    }

    private String sanitizeCaret(String input) {
        if (input == null) {
            return "";
        }
        return input.replace('^', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String normalizeEditLogEntityType(String raw) {
        String candidate = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (candidate) {
            case "VOUCHER", "VOUCHERS", "JOURNAL" -> "VOUCHER";
            case "LEDGER", "LEDGERS" -> "LEDGER";
            case "ACCOUNT_GROUP", "ACCOUNT_GROUPS", "GROUP" -> "ACCOUNT_GROUP";
            case "STOCK_ITEM", "STOCK_ITEMS" -> "STOCK_ITEM";
            case "STOCK_GROUP", "STOCK_GROUPS" -> "STOCK_GROUP";
            case "STOCK_CATEGORY", "STOCK_CATEGORIES" -> "STOCK_CATEGORY";
            case "GODOWN", "GODOWNS" -> "GODOWN";
            case "UOM", "UOMS" -> "UOM";
            case "VOUCHER_TYPE", "VOUCHER_TYPES" -> "VOUCHER_TYPE";
            case "COST_CENTRE", "COST_CENTRES" -> "COST_CENTRE";
            case "GST_REGISTRATION", "GST_REGISTRATIONS" -> "GST_REGISTRATION";
            case "COMPANY", "COMPANIES" -> "COMPANY";
            default -> candidate;
        };
    }

    private Object parseJsonObjectOrRaw(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JacksonException ex) {
            return json;
        }
    }

    private List<StatutoryDtos.EditLogChangedFieldDto> parseChangedFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<StatutoryDtos.EditLogChangedFieldDto> list = new ArrayList<>();
            for (JsonNode item : root) {
                if (item.isObject()) {
                    String path = textOrNull(item, "fieldPath");
                    if (path == null) {
                        path = textOrNull(item, "field");
                    }
                    Object beforeVal = extractJsonValue(
                        item.has("beforeValue") ? item.get("beforeValue") : item.get("oldValue")
                    );
                    Object afterVal = extractJsonValue(
                        item.has("afterValue") ? item.get("afterValue") : item.get("newValue")
                    );
                    if (path != null) {
                        list.add(new StatutoryDtos.EditLogChangedFieldDto(path, beforeVal, afterVal));
                    }
                }
            }
            return List.copyOf(list);
        } catch (JacksonException ex) {
            return List.of();
        }
    }

    private String textOrNull(JsonNode node, String prop) {
        JsonNode child = node.get(prop);
        return child == null || child.isNull() ? null : child.asString();
    }

    private Object extractJsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            String raw = node.asString();
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                try {
                    return objectMapper.readValue(raw, String.class);
                } catch (JacksonException ignored) {
                    return raw;
                }
            }
            return raw;
        }
        try {
            return objectMapper.treeToValue(node, Object.class);
        } catch (JacksonException ex) {
            return node.toString();
        }
    }
}
