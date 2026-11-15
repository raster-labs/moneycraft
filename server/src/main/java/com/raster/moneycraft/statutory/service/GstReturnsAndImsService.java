package com.raster.moneycraft.statutory.service;

import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.statutory.dto.StatutoryDtos;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates India GST Returns ({@code GSTR-1} with Phase III bifurcated B2B/B2C HSN and
 * Table 13 Document Summary, {@code GSTR-3B} with Rule 88A cross-utilization, and Rel 7.2
 * One-Click {@code GSTR-9} {@code .xlsx}/{@code .json} annual return) and manages the
 * Rel 7.2 Invoice Management System ({@code IMS}) reconciliation workbench.
 */
@Service
public class GstReturnsAndImsService {

    private static final BigDecimal ONE_PAISA = new BigDecimal("0.01");
    private static final DateTimeFormatter FILING_PERIOD_FMT = DateTimeFormatter.ofPattern("MMyyyy");

    private static final Set<String> VALID_IMS_ACTIONS = Set.of(
        "NO_ACTION", "ACCEPT", "REJECT", "PENDING"
    );

    private static final Set<String> VALID_IMS_DOC_TYPES = Set.of(
        "INVOICE", "DEBIT_NOTE", "CREDIT_NOTE", "AMENDMENT"
    );

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final StatRulePackAndGstMasterService rulePackAndGstMasterService;
    private final GstAndTdsComputationService computationService;
    private final AuditChainService auditChainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public GstReturnsAndImsService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        StatRulePackAndGstMasterService rulePackAndGstMasterService,
        GstAndTdsComputationService computationService,
        AuditChainService auditChainService,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.rulePackAndGstMasterService = rulePackAndGstMasterService;
        this.computationService = computationService;
        this.auditChainService = auditChainService;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    // -------------------------------------------------------------------------
    // 1. GSTR-1 Report & Portal JSON Export
    // -------------------------------------------------------------------------

    public StatutoryDtos.Gstr1ReportDto getGstr1(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawFromDate,
        LocalDate rawToDate,
        UUID gstRegistrationId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        DateWindow window = resolveDateWindow(companyId, rawFromDate, rawToDate, null);
        StatRulePackAndGstMasterService.RulePackConfig rulePack =
            rulePackAndGstMasterService.loadActiveRulePack(window.toDate());
        StatRulePackAndGstMasterService.GstRegContext gstReg =
            rulePackAndGstMasterService.resolveRegistrationOrCompanyFallback(
                companyId,
                gstRegistrationId,
                window.toDate()
            );

        List<OutwardAllocRow> allocRows = loadOutwardAllocRows(
            companyId,
            window.fromDate(),
            window.toDate(),
            gstRegistrationId
        );

        Map<UUID, List<OutwardAllocRow>> byJournal = new LinkedHashMap<>();
        for (OutwardAllocRow r : allocRows) {
            byJournal.computeIfAbsent(r.journalId(), k -> new ArrayList<>()).add(r);
        }

        List<StatutoryDtos.Gstr1B2bRowDto> b2b = new ArrayList<>();
        List<StatutoryDtos.Gstr1B2clRowDto> b2cl = new ArrayList<>();
        Map<B2csKey, BucketAcc> b2csMap = new LinkedHashMap<>();
        List<StatutoryDtos.Gstr1CdnrRowDto> cdnr = new ArrayList<>();
        List<StatutoryDtos.Gstr1CdnrRowDto> cdnur = new ArrayList<>();
        List<StatutoryDtos.Gstr1ExportRowDto> exp = new ArrayList<>();

        Map<HsnKey, HsnAcc> hsnB2bMap = new LinkedHashMap<>();
        Map<HsnKey, HsnAcc> hsnB2cMap = new LinkedHashMap<>();

        BigDecimal totalTaxable = BigDecimal.ZERO;
        BigDecimal totalIgst = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        BigDecimal totalCess = BigDecimal.ZERO;

        for (Map.Entry<UUID, List<OutwardAllocRow>> entry : byJournal.entrySet()) {
            UUID jId = entry.getKey();
            List<OutwardAllocRow> jRows = entry.getValue();
            OutwardAllocRow first = jRows.getFirst();
            boolean isNote = "CREDIT_NOTE".equals(first.baseCategory()) || "DEBIT_NOTE".equals(first.baseCategory());
            BigDecimal sign = "CREDIT_NOTE".equals(first.baseCategory()) ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            boolean hasPartyGstin = first.partyGstin() != null && !first.partyGstin().isBlank();

            BigDecimal invTaxable = BigDecimal.ZERO;
            BigDecimal invTax = BigDecimal.ZERO;
            for (OutwardAllocRow r : jRows) {
                invTaxable = invTaxable.add(r.assessableValue());
                invTax = invTax.add(r.igstAmount()).add(r.cgstAmount()).add(r.sgstAmount()).add(r.cessAmount());

                totalTaxable = totalTaxable.add(r.assessableValue().multiply(sign));
                totalIgst = totalIgst.add(r.igstAmount().multiply(sign));
                totalCgst = totalCgst.add(r.cgstAmount().multiply(sign));
                totalSgst = totalSgst.add(r.sgstAmount().multiply(sign));
                totalCess = totalCess.add(r.cessAmount().multiply(sign));

                // Bifurcated Phase III HSN accumulation
                String hsn = first.hsnOrDefault(r.hsnSacCode());
                boolean isSac = rulePack.omitUqcForPureSac() && hsn.startsWith(rulePack.sacPrefix());
                String uqc = isSac ? "NA" : (r.uqcCode() == null || r.uqcCode().isBlank() ? "NOS" : r.uqcCode());
                BigDecimal qtyForHsn = isSac ? BigDecimal.ZERO : r.billedQty().multiply(sign);
                HsnKey hk = new HsnKey(hsn, uqc, r.gstRatePct().stripTrailingZeros());
                Map<HsnKey, HsnAcc> targetHsnMap = hasPartyGstin ? hsnB2bMap : hsnB2cMap;
                targetHsnMap.computeIfAbsent(hk, k -> new HsnAcc()).add(
                    qtyForHsn,
                    r.assessableValue().multiply(sign),
                    r.igstAmount().multiply(sign),
                    r.cgstAmount().multiply(sign),
                    r.sgstAmount().multiply(sign),
                    r.cessAmount().multiply(sign)
                );
            }
            BigDecimal invoiceTotalValue = invTaxable.add(invTax);

            // Group journal's allocations by slab rate for B2B / B2CL / B2CS / CDNR / EXP rows
            Map<BigDecimal, BucketAcc> slabMap = new LinkedHashMap<>();
            for (OutwardAllocRow r : jRows) {
                slabMap.computeIfAbsent(r.gstRatePct().stripTrailingZeros(), k -> new BucketAcc()).add(
                    r.assessableValue(),
                    r.igstAmount(),
                    r.cgstAmount(),
                    r.sgstAmount(),
                    r.cessAmount()
                );
            }

            String primarySupply = first.supplyNature();
            if (isNote) {
                String noteType = "CREDIT_NOTE".equals(first.baseCategory()) ? "C" : "D";
                for (Map.Entry<BigDecimal, BucketAcc> se : slabMap.entrySet()) {
                    BucketAcc b = se.getValue();
                    StatutoryDtos.Gstr1CdnrRowDto dto = new StatutoryDtos.Gstr1CdnrRowDto(
                        jId,
                        first.partyGstin(),
                        first.partyName(),
                        first.voucherNumber(),
                        first.voucherDate(),
                        noteType,
                        first.posState(),
                        ExactMoneyValidator.formatExactScale(invoiceTotalValue, scale),
                        ExactMoneyValidator.formatExactScale(b.taxable, scale),
                        ExactMoneyValidator.formatExactScale(b.igst, scale),
                        ExactMoneyValidator.formatExactScale(b.cgst, scale),
                        ExactMoneyValidator.formatExactScale(b.sgst, scale),
                        ExactMoneyValidator.formatExactScale(b.cess, scale)
                    );
                    if (hasPartyGstin) {
                        cdnr.add(dto);
                    } else {
                        cdnur.add(dto);
                    }
                }
            } else if ("EXPORT_WPAY".equals(primarySupply) || "EXPORT_WOPAY".equals(primarySupply)) {
                String expType = "EXPORT_WPAY".equals(primarySupply) ? "WPAY" : "WOPAY";
                BucketAcc sumAll = new BucketAcc();
                for (BucketAcc b : slabMap.values()) {
                    sumAll.add(b.taxable, b.igst, b.cgst, b.sgst, b.cess);
                }
                exp.add(
                    new StatutoryDtos.Gstr1ExportRowDto(
                        jId,
                        expType,
                        first.voucherNumber(),
                        first.voucherDate(),
                        ExactMoneyValidator.formatExactScale(invoiceTotalValue, scale),
                        null,
                        null,
                        null,
                        ExactMoneyValidator.formatExactScale(sumAll.taxable, scale),
                        ExactMoneyValidator.formatExactScale(sumAll.igst, scale),
                        ExactMoneyValidator.formatExactScale(sumAll.cess, scale)
                    )
                );
            } else if (hasPartyGstin
                || "SEZ_WPAY".equals(primarySupply)
                || "SEZ_WOPAY".equals(primarySupply)
                || "DEEMED_EXPORT".equals(primarySupply)) {
                for (Map.Entry<BigDecimal, BucketAcc> se : slabMap.entrySet()) {
                    BucketAcc b = se.getValue();
                    b2b.add(
                        new StatutoryDtos.Gstr1B2bRowDto(
                            jId,
                            first.partyGstin() == null ? "" : first.partyGstin(),
                            first.partyName(),
                            first.voucherNumber(),
                            first.voucherDate(),
                            ExactMoneyValidator.formatExactScale(invoiceTotalValue, scale),
                            first.posState(),
                            first.isRcm(),
                            StatRulePackAndGstMasterService.formatRate2(se.getKey()),
                            ExactMoneyValidator.formatExactScale(b.taxable, scale),
                            ExactMoneyValidator.formatExactScale(b.igst, scale),
                            ExactMoneyValidator.formatExactScale(b.cgst, scale),
                            ExactMoneyValidator.formatExactScale(b.sgst, scale),
                            ExactMoneyValidator.formatExactScale(b.cess, scale)
                        )
                    );
                }
            } else if ("INTER_STATE_TAXABLE".equals(primarySupply)
                && invoiceTotalValue.compareTo(rulePack.b2clInvoiceThresholdInr()) > 0) {
                for (Map.Entry<BigDecimal, BucketAcc> se : slabMap.entrySet()) {
                    BucketAcc b = se.getValue();
                    b2cl.add(
                        new StatutoryDtos.Gstr1B2clRowDto(
                            jId,
                            first.voucherNumber(),
                            first.voucherDate(),
                            ExactMoneyValidator.formatExactScale(invoiceTotalValue, scale),
                            first.posState(),
                            StatRulePackAndGstMasterService.formatRate2(se.getKey()),
                            ExactMoneyValidator.formatExactScale(b.taxable, scale),
                            ExactMoneyValidator.formatExactScale(b.igst, scale),
                            ExactMoneyValidator.formatExactScale(b.cess, scale)
                        )
                    );
                }
            } else {
                String splyTy = "INTER_STATE_TAXABLE".equals(primarySupply) ? "INTER" : "INTRA";
                for (Map.Entry<BigDecimal, BucketAcc> se : slabMap.entrySet()) {
                    BucketAcc b = se.getValue();
                    B2csKey bk = new B2csKey(splyTy, first.posState(), se.getKey());
                    b2csMap.computeIfAbsent(bk, k -> new BucketAcc()).add(
                        b.taxable,
                        b.igst,
                        b.cgst,
                        b.sgst,
                        b.cess
                    );
                }
            }
        }

        List<StatutoryDtos.Gstr1B2csRowDto> b2cs = new ArrayList<>();
        for (Map.Entry<B2csKey, BucketAcc> e : b2csMap.entrySet()) {
            B2csKey k = e.getKey();
            BucketAcc b = e.getValue();
            b2cs.add(
                new StatutoryDtos.Gstr1B2csRowDto(
                    k.supplyType(),
                    k.pos(),
                    StatRulePackAndGstMasterService.formatRate2(k.ratePct()),
                    ExactMoneyValidator.formatExactScale(b.taxable, scale),
                    ExactMoneyValidator.formatExactScale(b.igst, scale),
                    ExactMoneyValidator.formatExactScale(b.cgst, scale),
                    ExactMoneyValidator.formatExactScale(b.sgst, scale),
                    ExactMoneyValidator.formatExactScale(b.cess, scale)
                )
            );
        }

        List<StatutoryDtos.Gstr1HsnSummaryRowDto> hsnB2b = toHsnDtoList(hsnB2bMap, scale);
        List<StatutoryDtos.Gstr1HsnSummaryRowDto> hsnB2c = toHsnDtoList(hsnB2cMap, scale);
        List<StatutoryDtos.Gstr1DocSummaryRowDto> docSummary = loadGstr1DocumentSummary(
            companyId,
            window.fromDate(),
            window.toDate(),
            gstRegistrationId
        );
        int uncertainCount = (int) computationService.listUncertainTransactions(
            principal,
            companyId,
            window.fromDate(),
            window.toDate()
        ).stream()
            .filter(u -> u.voucherTypeName() == null
                || !"purchase".equalsIgnoreCase(u.voucherTypeName().trim()))
            .count();

        BigDecimal totalTax = totalIgst.add(totalCgst).add(totalSgst).add(totalCess);
        return new StatutoryDtos.Gstr1ReportDto(
            window.fromDate(),
            window.toDate(),
            gstReg.id(),
            gstReg.gstin(),
            List.copyOf(b2b),
            List.copyOf(b2cl),
            List.copyOf(b2cs),
            List.copyOf(cdnr),
            List.copyOf(cdnur),
            List.copyOf(exp),
            hsnB2b,
            hsnB2c,
            docSummary,
            uncertainCount,
            ExactMoneyValidator.formatExactScale(totalTaxable.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(totalIgst.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(totalCgst.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(totalSgst.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(totalCess.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(totalTax.setScale(scale, RoundingMode.HALF_UP), scale)
        );
    }

    public byte[] exportGstr1Json(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        UUID gstRegistrationId
    ) {
        StatutoryDtos.Gstr1ReportDto report = getGstr1(principal, companyId, fromDate, toDate, gstRegistrationId);
        Map<String, Object> portalPayload = new LinkedHashMap<>();
        portalPayload.put("gstin", report.gstin() != null ? report.gstin() : "");
        portalPayload.put("fp", FILING_PERIOD_FMT.format(report.toDate()));
        portalPayload.put("version", "GST3.1.6");
        portalPayload.put("hash", "hash");
        portalPayload.put("b2b", report.b2b());
        portalPayload.put("b2cl", report.b2cl());
        portalPayload.put("b2cs", report.b2cs());
        portalPayload.put("cdnr", report.cdnr());
        portalPayload.put("cdnur", report.cdnur());
        portalPayload.put("exp", report.exp());
        portalPayload.put("hsn", Map.of("hsn_b2b", report.hsnB2b(), "hsn_b2c", report.hsnB2c()));
        portalPayload.put("doc_issue", report.documentSummary());
        portalPayload.put(
            "summaryTotals",
            Map.of(
                "totalTaxableValue", report.totalTaxableValue(),
                "totalIgst", report.totalIgst(),
                "totalCgst", report.totalCgst(),
                "totalSgst", report.totalSgst(),
                "totalCess", report.totalCess(),
                "totalTax", report.totalTax()
            )
        );
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(portalPayload)
                .getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize GSTR-1 portal JSON", ex);
        }
    }

    // -------------------------------------------------------------------------
    // 2. Rel 7.2 IMS Reconciliation Workbench (`import-json`, `invoices`, `actions`)
    // -------------------------------------------------------------------------

    public List<StatutoryDtos.ImsInvoiceDto> importImsJson(
        MoneyCraftPrincipal actor,
        UUID companyId,
        StatutoryDtos.ImsImportRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (request == null || request.invoices() == null || request.invoices().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-IMS-PAYLOAD-EMPTY",
                "IMS Import Payload Empty",
                "Supply a non-empty list of supplier inward invoices to import."
            );
        }

        return businessTxTemplate.execute(tx -> {
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            String defaultPeriod = normalizeFilingPeriod(request.returnPeriod(), LocalDate.now());
            UUID regId = request.gstRegistrationId() != null
                ? request.gstRegistrationId()
                : rulePackAndGstMasterService.resolveDefaultCompanyGstinId(companyId, LocalDate.now());
            if (regId == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-IMS-GST-REG-REQUIRED",
                    "Company GST Registration Required",
                    "Configure at least one company GSTIN registration before importing IMS invoices."
                );
            }
            StatRulePackAndGstMasterService.GstRegContext regCtx =
                rulePackAndGstMasterService.resolveRegistrationOrCompanyFallback(companyId, regId, LocalDate.now());

            List<UUID> touchedIds = new ArrayList<>();
            for (Map<String, Object> raw : request.invoices()) {
                String supplierGstin = ExactMoneyValidator.validatePartyGstin(
                    asStr(raw, "supplierGstin", "ctin"),
                    null
                );
                String supplierName = firstNonBlank(asStr(raw, "supplierName", "supplierLegalName", "trdnm"), supplierGstin);
                String docType = normalizeImsDocType(asStr(raw, "docType", "doctyp"));
                String invNo = asStr(raw, "invoiceNumber", "inum", "docNo");
                if (invNo == null || invNo.isBlank()) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-IMS-INVOICE-NO-REQUIRED",
                        "Supplier Invoice Number Required",
                        "Each IMS inward invoice requires 'invoiceNumber'."
                    );
                }
                LocalDate invDate = parseIsoDate(asStr(raw, "invoiceDate", "idt", "docDate"), LocalDate.now());
                String pos = firstNonBlank(asStr(raw, "placeOfSupplyState", "pos"), regCtx.stateCode(), "33");
                boolean isRcm = "Y".equalsIgnoreCase(asStr(raw, "rchrg"))
                    || Boolean.parseBoolean(String.valueOf(raw.getOrDefault("isRcm", "false")));
                String retPeriod = normalizeFilingPeriod(asStr(raw, "returnPeriod", "fp"), invDate, defaultPeriod);

                BigDecimal taxable = parseMoneyOpt(asStr(raw, "taxableValue", "txval"), scale);
                BigDecimal igst = parseMoneyOpt(asStr(raw, "igstAmount", "iamt"), scale);
                BigDecimal cgst = parseMoneyOpt(asStr(raw, "cgstAmount", "camt"), scale);
                BigDecimal sgst = parseMoneyOpt(asStr(raw, "sgstAmount", "samt"), scale);
                BigDecimal cess = parseMoneyOpt(asStr(raw, "cessAmount", "csamt"), scale);
                String explicitPortalItc = asStr(raw, "portalItcAmount");
                BigDecimal portalItc = explicitPortalItc != null && !explicitPortalItc.isBlank()
                    ? parseMoneyOpt(explicitPortalItc, scale)
                    : igst.add(cgst).add(sgst).add(cess);

                BookMatchResult match = matchBookPurchase(
                    companyId,
                    supplierGstin,
                    invNo.trim(),
                    taxable,
                    portalItc
                );

                List<UUID> existingIds = businessJdbcTemplate.query(
                    """
                    SELECT id FROM mc_ims_inward_invoice
                     WHERE company_id = ? AND gst_registration_id = ?
                       AND supplier_gstin = ? AND doc_type = ?
                       AND invoice_number = ? AND invoice_date = ?
                    """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    companyId,
                    regId,
                    supplierGstin,
                    docType,
                    invNo.trim(),
                    invDate
                );

                if (existingIds.isEmpty()) {
                    UUID imsId = UUID.randomUUID();
                    businessJdbcTemplate.update(
                        """
                        INSERT INTO mc_ims_inward_invoice (
                            id, company_id, gst_registration_id, supplier_gstin, supplier_legal_name,
                            doc_type, invoice_number, invoice_date, place_of_supply_state, is_rcm,
                            portal_return_period, effective_filing_period,
                            portal_taxable_value, portal_igst, portal_cgst, portal_sgst, portal_cess,
                            portal_itc_amount, itc_reduction_amount, ims_action,
                            match_status, matched_journal_id, version_no
                        ) VALUES (
                            ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?,
                            ?, ?,
                            ?, ?, ?, ?, ?,
                            ?, 0, 'NO_ACTION',
                            ?, ?, 1
                        )
                        """,
                        imsId,
                        companyId,
                        regId,
                        supplierGstin,
                        supplierName,
                        docType,
                        invNo.trim(),
                        invDate,
                        pos,
                        isRcm,
                        retPeriod,
                        retPeriod,
                        taxable,
                        igst,
                        cgst,
                        sgst,
                        cess,
                        portalItc,
                        match.matchStatus(),
                        match.matchedJournalId()
                    );
                    touchedIds.add(imsId);
                } else {
                    UUID imsId = existingIds.getFirst();
                    businessJdbcTemplate.update(
                        """
                        UPDATE mc_ims_inward_invoice
                           SET supplier_legal_name = ?,
                               portal_taxable_value = ?,
                               portal_igst = ?,
                               portal_cgst = ?,
                               portal_sgst = ?,
                               portal_cess = ?,
                               portal_itc_amount = ?,
                               match_status = ?,
                               matched_journal_id = ?,
                               version_no = version_no + 1,
                               updated_at = clock_timestamp()
                         WHERE company_id = ? AND id = ?
                        """,
                        supplierName,
                        taxable,
                        igst,
                        cgst,
                        sgst,
                        cess,
                        portalItc,
                        match.matchStatus(),
                        match.matchedJournalId(),
                        companyId,
                        imsId
                    );
                    touchedIds.add(imsId);
                }
            }

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "IMS_IMPORT_JSON",
                "IMS_BATCH",
                defaultPeriod,
                "SUCCESS",
                null,
                Map.of("importedCount", touchedIds.size(), "returnPeriod", defaultPeriod)
            );

            return loadImsDtosByIds(companyId, touchedIds, scale);
        });
    }

    public List<StatutoryDtos.ImsInvoiceDto> listImsInvoices(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String returnPeriod,
        UUID gstRegistrationId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();

        List<Object> params = new ArrayList<>();
        params.add(companyId);
        StringBuilder where = new StringBuilder("ims.company_id = ?");
        if (returnPeriod != null && !returnPeriod.isBlank()) {
            String normPeriod = returnPeriod.trim();
            where.append(" AND (ims.portal_return_period = ? OR ims.effective_filing_period = ?)");
            params.add(normPeriod);
            params.add(normPeriod);
        }
        if (gstRegistrationId != null) {
            where.append(" AND ims.gst_registration_id = ?");
            params.add(gstRegistrationId);
        }

        String sql = imsSelectSql(where.toString()) + " ORDER BY ims.invoice_date ASC, ims.invoice_number ASC";
        return businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> mapImsDto(rs, scale),
            params.toArray()
        );
    }

    public StatutoryDtos.ImsInvoiceDto applyImsAction(
        MoneyCraftPrincipal actor,
        UUID companyId,
        StatutoryDtos.ImsActionRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (request == null || request.imsInvoiceId() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-IMS-ID-REQUIRED",
                "IMS Invoice ID Required",
                "Field 'imsInvoiceId' is required."
            );
        }

        String action = request.action() == null ? "" : request.action().trim().toUpperCase(Locale.ROOT);
        if (!VALID_IMS_ACTIONS.contains(action)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-IMS-ACTION-INVALID",
                "Invalid IMS Action",
                "action must be one of " + VALID_IMS_ACTIONS + "."
            );
        }

        return businessTxTemplate.execute(tx -> {
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            List<Map<String, Object>> rows = businessJdbcTemplate.queryForList(
                """
                SELECT ims_action, portal_itc_amount, effective_filing_period, invoice_date
                  FROM mc_ims_inward_invoice
                 WHERE company_id = ? AND id = ?
                 FOR UPDATE
                """,
                companyId,
                request.imsInvoiceId()
            );
            if (rows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-IMS-NOT-FOUND",
                    "IMS Invoice Not Found",
                    "IMS inward invoice " + request.imsInvoiceId() + " does not exist."
                );
            }
            Map<String, Object> existing = rows.getFirst();
            String priorAction = (String) existing.get("ims_action");
            BigDecimal portalItc = (BigDecimal) existing.get("portal_itc_amount");
            String existingEffPeriod = (String) existing.get("effective_filing_period");
            LocalDate invDate = LocalDate.parse(existing.get("invoice_date").toString());

            StatRulePackAndGstMasterService.RulePackConfig rulePack =
                rulePackAndGstMasterService.loadActiveRulePack(invDate);

            BigDecimal reduction = (request.itcReductionAmount() == null || request.itcReductionAmount().isBlank())
                ? BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY)
                : ExactMoneyValidator.parseAndValidateExactScale(
                    request.itcReductionAmount(),
                    scale,
                    "itcReductionAmount"
                );
            if (reduction.compareTo(portalItc) > 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-IMS-REDUCTION-EXCEEDS-ITC",
                    "ITC Reduction Exceeds Portal ITC",
                    "itcReductionAmount cannot exceed portalItcAmount (" + portalItc.toPlainString() + ")."
                );
            }

            String remarks = request.remarks() == null ? null : request.remarks().trim();
            boolean remarksMissing = remarks == null || remarks.isEmpty();
            boolean rejectNeedsRemarks = "REJECT".equals(action) && rulePack.imsRemarksMandatoryOnReject();
            boolean reductionNeedsRemarks = reduction.signum() > 0 && rulePack.imsRemarksMandatoryOnItcReduction();
            if ((rejectNeedsRemarks || reductionNeedsRemarks) && remarksMissing) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-IMS-REMARKS-REQUIRED",
                    "IMS Remarks Mandatory",
                    "TallyPrime Rel 7.2 / GSTN IMS requires non-empty remarks when action is REJECT "
                        + "or when partial itcReductionAmount is specified.",
                    Map.of("remarks", "Remarks are mandatory for REJECT or ITC reduction")
                );
            }
            if (remarks != null && remarks.length() > 255) {
                remarks = remarks.substring(0, 255);
            }

            String targetFilingPeriod = (request.effectiveFilingPeriod() != null
                && !request.effectiveFilingPeriod().isBlank())
                ? normalizeFilingPeriod(request.effectiveFilingPeriod(), invDate)
                : existingEffPeriod;

            businessJdbcTemplate.update(
                """
                UPDATE mc_ims_inward_invoice
                   SET ims_action = ?,
                       itc_reduction_amount = ?,
                       remarks = ?,
                       effective_filing_period = ?,
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                action,
                reduction,
                remarks,
                targetFilingPeriod,
                companyId,
                request.imsInvoiceId()
            );

            ensureActorProjected(actor);
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_ims_action_log (
                    id, company_id, ims_invoice_id, prior_action, new_action,
                    itc_reduction_amount, remarks, effective_filing_period,
                    pushed_to_gstn, actor_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, false, ?)
                """,
                UUID.randomUUID(),
                companyId,
                request.imsInvoiceId(),
                priorAction,
                action,
                reduction,
                remarks,
                targetFilingPeriod,
                actor.principalId()
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "IMS_ACTION_APPLY",
                "IMS_INVOICE",
                request.imsInvoiceId().toString(),
                "SUCCESS",
                null,
                Map.of(
                    "priorAction", priorAction,
                    "newAction", action,
                    "itcReductionAmount", ExactMoneyValidator.formatExactScale(reduction, scale),
                    "effectiveFilingPeriod", targetFilingPeriod
                )
            );

            return loadImsDtosByIds(companyId, List.of(request.imsInvoiceId()), scale).getFirst();
        });
    }

    // -------------------------------------------------------------------------
    // 3. GSTR-3B Monthly Summary & Rule 88A Cross-Utilization
    // -------------------------------------------------------------------------

    public StatutoryDtos.Gstr3bReportDto getGstr3b(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawFromDate,
        LocalDate rawToDate,
        String rawReturnPeriod,
        UUID gstRegistrationId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        DateWindow window = resolveDateWindow(companyId, rawFromDate, rawToDate, rawReturnPeriod);
        StatRulePackAndGstMasterService.GstRegContext gstReg =
            rulePackAndGstMasterService.resolveRegistrationOrCompanyFallback(
                companyId,
                gstRegistrationId,
                window.toDate()
            );

        // 1. Outward Table 3.1(a), 3.1(b), 3.1(c)
        List<OutwardAllocRow> outwardRows = loadOutwardAllocRows(
            companyId,
            window.fromDate(),
            window.toDate(),
            gstRegistrationId
        );
        BucketAcc tbl31a = new BucketAcc();
        BucketAcc tbl31b = new BucketAcc();
        BucketAcc tbl31c = new BucketAcc();
        for (OutwardAllocRow r : outwardRows) {
            BigDecimal sign = "CREDIT_NOTE".equals(r.baseCategory()) ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            String sn = r.supplyNature();
            if ("EXPORT_WPAY".equals(sn) || "EXPORT_WOPAY".equals(sn)
                || "SEZ_WPAY".equals(sn) || "SEZ_WOPAY".equals(sn)) {
                tbl31b.addSigned(r, sign);
            } else if ("EXEMPT".equals(sn) || "NIL_RATED".equals(sn) || "NON_GST".equals(sn)) {
                tbl31c.addSigned(r, sign);
            } else {
                tbl31a.addSigned(r, sign);
            }
        }

        // 2. Inward RCM Table 3.1(d) & 4(A)(3)
        BucketAcc rcmBucket = loadRcmBucket(companyId, window.fromDate(), window.toDate(), gstRegistrationId);

        // 3. Table 4(A)(5) All Other ITC (Books + Rel 7.2 IMS Reconciliation & Carryover)
        ItcSynthesisResult itcSynth = synthesizeEligibleAndReversedItc(
            companyId,
            window,
            gstRegistrationId,
            rcmBucket,
            scale
        );

        // 4. Rule 88A Cross-Utilization for Net Tax Payable (Outward Regular vs Net Eligible ITC + Cash RCM)
        StatutoryDtos.Gstr3bTaxBucketDto netPayable = computeRule88aNetPayable(
            tbl31a,
            tbl31b,
            rcmBucket,
            itcSynth.netEligible(),
            scale
        );

        int uncertainCount = computationService.listUncertainTransactions(
            principal,
            companyId,
            window.fromDate(),
            window.toDate()
        ).size();

        Map<String, StatutoryDtos.Gstr3bTaxBucketDto> breakdown = new LinkedHashMap<>();
        breakdown.put("4A3_INWARD_RCM", toBucketDto(rcmBucket, scale));
        breakdown.put("4A5_ALL_OTHER_ITC", toBucketDto(itcSynth.allOtherItc(), scale));

        return new StatutoryDtos.Gstr3bReportDto(
            window.fromDate(),
            window.toDate(),
            window.returnPeriodLabel(),
            gstReg.id(),
            gstReg.gstin(),
            toBucketDto(tbl31a, scale),
            toBucketDto(tbl31b, scale),
            toBucketDto(tbl31c, scale),
            toBucketDto(rcmBucket, scale),
            toBucketDto(itcSynth.netEligible(), scale),
            breakdown,
            toBucketDto(itcSynth.reversedTotal(), scale),
            netPayable,
            itcSynth.imsAcceptedCount(),
            ExactMoneyValidator.formatExactScale(itcSynth.imsPendingDeferred().setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(itcSynth.imsReductionTotal().setScale(scale, RoundingMode.HALF_UP), scale),
            uncertainCount
        );
    }

    // -------------------------------------------------------------------------
    // 4. Rel 7.2 One-Click GSTR-9 Annual Return (`.xlsx` & `.json`)
    // -------------------------------------------------------------------------

    public StatutoryDtos.Gstr9ReportDto getGstr9(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID rawFiscalYearId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        FiscalYearWindow fy = resolveFiscalYearWindow(companyId, rawFiscalYearId);
        StatutoryDtos.Gstr1ReportDto gstr1Year = getGstr1(
            principal,
            companyId,
            fy.startDate(),
            fy.endDate(),
            null
        );
        StatutoryDtos.Gstr3bReportDto gstr3bYear = getGstr3b(
            principal,
            companyId,
            fy.startDate(),
            fy.endDate(),
            null,
            null
        );

        // Table 4 Outward
        BucketAcc b2cAcc = new BucketAcc();
        for (StatutoryDtos.Gstr1B2csRowDto r : gstr1Year.b2cs()) {
            b2cAcc.addStrings(r.taxableValue(), r.igstAmount(), r.cgstAmount(), r.sgstAmount(), r.cessAmount());
        }
        for (StatutoryDtos.Gstr1B2clRowDto r : gstr1Year.b2cl()) {
            b2cAcc.addStrings(r.taxableValue(), r.igstAmount(), "0", "0", r.cessAmount());
        }
        BucketAcc b2bAcc = new BucketAcc();
        for (StatutoryDtos.Gstr1B2bRowDto r : gstr1Year.b2b()) {
            b2bAcc.addStrings(r.taxableValue(), r.igstAmount(), r.cgstAmount(), r.sgstAmount(), r.cessAmount());
        }
        BucketAcc expWpay = new BucketAcc();
        BucketAcc expWopay = new BucketAcc();
        for (StatutoryDtos.Gstr1ExportRowDto r : gstr1Year.exp()) {
            if ("WPAY".equals(r.exportType())) {
                expWpay.addStrings(r.taxableValue(), r.igstAmount(), "0", "0", r.cessAmount());
            } else {
                expWopay.addStrings(r.taxableValue(), "0", "0", "0", "0");
            }
        }

        List<StatutoryDtos.Gstr9TableRowDto> table4 = List.of(
            toGstr9Row("4A", "Supplies made to un-registered persons (B2C)", b2cAcc, scale),
            toGstr9Row("4B", "Supplies made to registered persons (B2B)", b2bAcc, scale),
            toGstr9Row("4C", "Zero rated supply (Export) on payment of tax", expWpay, scale),
            bucketToGstr9Row("4G", "Inward supplies on which tax is to be paid on reverse charge", gstr3bYear.inwardRcm()),
            new StatutoryDtos.Gstr9TableRowDto(
                "4N",
                "Supplies and advances on which tax is to be paid (Total Outward)",
                gstr1Year.totalTaxableValue(),
                gstr1Year.totalIgst(),
                gstr1Year.totalCgst(),
                gstr1Year.totalSgst(),
                gstr1Year.totalCess()
            )
        );

        List<StatutoryDtos.Gstr9TableRowDto> table5 = List.of(
            toGstr9Row("5A", "Zero rated supply (Export) without payment of tax", expWopay, scale),
            bucketToGstr9Row("5D_5F", "Exempted, Nil Rated and Non-GST supplies", gstr3bYear.outwardNilExempt())
        );

        List<StatutoryDtos.Gstr9TableRowDto> table6 = List.of(
            bucketToGstr9Row(
                "6B",
                "Inward supplies (other than imports and RCM) — Inputs, Capital Goods & Services",
                gstr3bYear.itcTable4aBreakdown().getOrDefault("4A5_ALL_OTHER_ITC", gstr3bYear.eligibleItc())
            ),
            bucketToGstr9Row("6C_6D", "Inward supplies liable to reverse charge (RCM ITC)", gstr3bYear.inwardRcm()),
            bucketToGstr9Row("6O", "Total ITC availed", gstr3bYear.eligibleItc())
        );

        List<StatutoryDtos.Gstr9TableRowDto> table7 = List.of(
            bucketToGstr9Row("7A_7H", "ITC Reversed (Purchase Returns & Rel 7.2 IMS Reductions)", gstr3bYear.itcReversed())
        );

        BigDecimal portal2bItc = sumPortal2bItcForWindow(companyId, fy.startDate(), fy.endDate());
        BigDecimal booksItc = new BigDecimal(gstr3bYear.eligibleItc().totalTax());
        BigDecimal reconDiff = portal2bItc.signum() == 0
            ? BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY)
            : portal2bItc.subtract(booksItc).setScale(scale, RoundingMode.HALF_UP);

        String zero = ExactMoneyValidator.formatExactScale(BigDecimal.ZERO, scale);
        List<StatutoryDtos.Gstr9TableRowDto> table8 = List.of(
            new StatutoryDtos.Gstr9TableRowDto(
                "8A",
                "ITC as per GSTR-2B / IMS Portal",
                zero,
                ExactMoneyValidator.formatExactScale(portal2bItc.setScale(scale, RoundingMode.HALF_UP), scale),
                zero,
                zero,
                zero
            ),
            bucketToGstr9Row("8B", "ITC as per Books / Table 6B", gstr3bYear.eligibleItc()),
            new StatutoryDtos.Gstr9TableRowDto(
                "8D",
                "ITC Difference (8A minus 8B)",
                zero,
                ExactMoneyValidator.formatExactScale(reconDiff, scale),
                zero,
                zero,
                zero
            )
        );

        List<StatutoryDtos.Gstr9TableRowDto> table9 = List.of(
            new StatutoryDtos.Gstr9TableRowDto(
                "9_PAYABLE",
                "Total Tax Payable",
                gstr1Year.totalTaxableValue(),
                gstr1Year.totalIgst(),
                gstr1Year.totalCgst(),
                gstr1Year.totalSgst(),
                gstr1Year.totalCess()
            ),
            bucketToGstr9Row("9_NET_CASH", "Net Tax Payable in Cash after ITC", gstr3bYear.netTaxPayable())
        );

        List<StatutoryDtos.Gstr1HsnSummaryRowDto> table17OutwardHsn = new ArrayList<>();
        table17OutwardHsn.addAll(gstr1Year.hsnB2b());
        table17OutwardHsn.addAll(gstr1Year.hsnB2c());

        List<StatutoryDtos.Gstr1HsnSummaryRowDto> table18InwardHsn = loadInwardHsnSummary(
            companyId,
            fy.startDate(),
            fy.endDate(),
            scale
        );

        return new StatutoryDtos.Gstr9ReportDto(
            fy.id(),
            fy.label(),
            gstr1Year.gstin(),
            table4,
            table5,
            table6,
            table7,
            table8,
            table9,
            List.copyOf(table17OutwardHsn),
            table18InwardHsn,
            gstr1Year.totalTaxableValue(),
            gstr1Year.totalTax(),
            gstr3bYear.eligibleItc().totalTax(),
            gstr3bYear.itcReversed().totalTax(),
            gstr3bYear.netTaxPayable().totalTax(),
            ExactMoneyValidator.formatExactScale(reconDiff, scale)
        );
    }

    public byte[] exportGstr9(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID fiscalYearId,
        String rawFormat
    ) {
        StatutoryDtos.Gstr9ReportDto report = getGstr9(principal, companyId, fiscalYearId);
        String format = rawFormat == null ? "XLSX" : rawFormat.trim().toUpperCase(Locale.ROOT);
        if ("JSON".equals(format)) {
            try {
                return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report)
                    .getBytes(StandardCharsets.UTF_8);
            } catch (JacksonException ex) {
                throw new IllegalStateException("Failed to serialize GSTR-9 JSON", ex);
            }
        }
        if (!"XLSX".equals(format)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GSTR9-FORMAT-INVALID",
                "Unsupported GSTR-9 Export Format",
                "format must be XLSX or JSON."
            );
        }
        return renderGstr9Workbook(report);
    }

    // -------------------------------------------------------------------------
    // Internal Synthesis, Rule 88A & Apache POI Workbook Helpers
    // -------------------------------------------------------------------------

    private record DateWindow(
        LocalDate fromDate,
        LocalDate toDate,
        String returnPeriodLabel,
        Set<String> coveredFilingPeriods
    ) {
    }

    private record FiscalYearWindow(
        UUID id,
        String label,
        LocalDate startDate,
        LocalDate endDate
    ) {
    }

    private record OutwardAllocRow(
        UUID journalId,
        LocalDate voucherDate,
        String voucherNumber,
        String baseCategory,
        String partyName,
        String partyGstin,
        String posState,
        boolean isRcm,
        String supplyNature,
        String hsnSacCode,
        String uqcCode,
        BigDecimal billedQty,
        BigDecimal assessableValue,
        BigDecimal gstRatePct,
        BigDecimal igstAmount,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal cessAmount
    ) {
        String hsnOrDefault(String raw) {
            return raw == null || raw.isBlank() ? "0000" : raw.trim();
        }
    }

    private record B2csKey(String supplyType, String pos, BigDecimal ratePct) {
    }

    private record HsnKey(String hsn, String uqc, BigDecimal ratePct) {
    }

    private static final class BucketAcc {
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal cess = BigDecimal.ZERO;

        void add(BigDecimal tx, BigDecimal i, BigDecimal c, BigDecimal s, BigDecimal cs) {
            taxable = taxable.add(tx);
            igst = igst.add(i);
            cgst = cgst.add(c);
            sgst = sgst.add(s);
            cess = cess.add(cs);
        }

        void addSigned(OutwardAllocRow r, BigDecimal sign) {
            add(
                r.assessableValue().multiply(sign),
                r.igstAmount().multiply(sign),
                r.cgstAmount().multiply(sign),
                r.sgstAmount().multiply(sign),
                r.cessAmount().multiply(sign)
            );
        }

        void addStrings(String tx, String i, String c, String s, String cs) {
            add(
                new BigDecimal(tx),
                new BigDecimal(i),
                new BigDecimal(c),
                new BigDecimal(s),
                cs == null ? BigDecimal.ZERO : new BigDecimal(cs)
            );
        }

        BigDecimal totalTax() {
            return igst.add(cgst).add(sgst).add(cess);
        }
    }

    private static final class HsnAcc {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal cess = BigDecimal.ZERO;

        void add(BigDecimal q, BigDecimal tx, BigDecimal i, BigDecimal c, BigDecimal s, BigDecimal cs) {
            qty = qty.add(q);
            taxable = taxable.add(tx);
            igst = igst.add(i);
            cgst = cgst.add(c);
            sgst = sgst.add(s);
            cess = cess.add(cs);
        }
    }

    private record BookMatchResult(UUID matchedJournalId, String matchStatus) {
    }

    private record ItcSynthesisResult(
        BucketAcc allOtherItc,
        BucketAcc netEligible,
        BucketAcc reversedTotal,
        int imsAcceptedCount,
        BigDecimal imsPendingDeferred,
        BigDecimal imsReductionTotal
    ) {
    }

    private DateWindow resolveDateWindow(
        UUID companyId,
        LocalDate rawFrom,
        LocalDate rawTo,
        String rawReturnPeriod
    ) {
        if (rawFrom == null && rawTo == null && rawReturnPeriod != null && rawReturnPeriod.trim().length() == 6) {
            String rp = rawReturnPeriod.trim();
            int month = Integer.parseInt(rp.substring(0, 2));
            int year = Integer.parseInt(rp.substring(2, 6));
            YearMonth ym = YearMonth.of(year, month);
            return new DateWindow(ym.atDay(1), ym.atEndOfMonth(), rp, Set.of(rp));
        }

        LocalDate from = rawFrom;
        LocalDate to = rawTo;
        if (from == null || to == null) {
            FiscalYearWindow fy = resolveFiscalYearWindow(companyId, null);
            if (from == null) {
                from = fy.startDate();
            }
            if (to == null) {
                to = fy.endDate();
            }
        }
        Set<String> periods = new LinkedHashSet<>();
        YearMonth cur = YearMonth.from(from);
        YearMonth end = YearMonth.from(to);
        while (!cur.isAfter(end)) {
            periods.add(String.format(Locale.ROOT, "%02d%04d", cur.getMonthValue(), cur.getYear()));
            cur = cur.plusMonths(1);
        }
        String label = (rawReturnPeriod != null && !rawReturnPeriod.isBlank())
            ? rawReturnPeriod.trim()
            : FILING_PERIOD_FMT.format(to);
        if (rawReturnPeriod != null && !rawReturnPeriod.isBlank()) {
            periods = Set.of(rawReturnPeriod.trim());
        }
        return new DateWindow(from, to, label, Set.copyOf(periods));
    }

    private FiscalYearWindow resolveFiscalYearWindow(UUID companyId, UUID fiscalYearId) {
        if (fiscalYearId != null) {
            List<FiscalYearWindow> rows = businessJdbcTemplate.query(
                "SELECT id, display_label, start_date, end_date FROM mc_fiscal_year WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> new FiscalYearWindow(
                    rs.getObject("id", UUID.class),
                    rs.getString("display_label"),
                    rs.getObject("start_date", LocalDate.class),
                    rs.getObject("end_date", LocalDate.class)
                ),
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

        List<FiscalYearWindow> all = businessJdbcTemplate.query(
            """
            SELECT id, display_label, start_date, end_date
              FROM mc_fiscal_year
             WHERE company_id = ?
             ORDER BY is_closed ASC, start_date DESC
             LIMIT 1
            """,
            (rs, rowNum) -> new FiscalYearWindow(
                rs.getObject("id", UUID.class),
                rs.getString("display_label"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class)
            ),
            companyId
        );
        if (all.isEmpty()) {
            return new FiscalYearWindow(
                null,
                "FY 2025-26",
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2026, 3, 31)
            );
        }
        return all.getFirst();
    }

    private List<OutwardAllocRow> loadOutwardAllocRows(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        UUID gstRegistrationId
    ) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(fromDate);
        params.add(toDate);

        StringBuilder regClause = new StringBuilder();
        if (gstRegistrationId != null) {
            regClause.append(" AND j.company_gstin_id = ?");
            params.add(gstRegistrationId);
        }

        String sql =
            """
            SELECT j.id AS journal_id, j.voucher_date, COALESCE(j.voucher_number, '') AS voucher_number,
                   vt.base_category, COALESCE(pl.name, COALESCE(j.party_mailing_name, 'Cash')) AS party_name,
                   j.party_gstin, COALESCE(j.place_of_supply_state, '33') AS pos_state,
                   ta.is_rcm, ta.supply_nature, ta.hsn_sac_code, ta.gst_uqc_code,
                   ta.billed_qty, ta.assessable_value,
                   (ta.cgst_rate_pct + ta.sgst_utgst_rate_pct + ta.igst_rate_pct) AS gst_rate_pct,
                   ta.igst_amount, ta.cgst_amount, ta.sgst_utgst_amount, ta.cess_amount
              FROM mc_tax_allocation ta
              JOIN mc_journal j
                ON j.company_id = ta.company_id AND j.id = ta.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
             WHERE ta.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_date BETWEEN ? AND ?
               AND vt.base_category IN ('SALES', 'CREDIT_NOTE', 'DEBIT_NOTE')
               AND ta.itc_eligibility = 'NOT_APPLICABLE'
               AND ta.is_rcm = false
               AND ta.is_uncertain_transaction = false
            """ + regClause + " ORDER BY j.voucher_date ASC, j.voucher_number ASC, ta.created_at ASC";

        return businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> new OutwardAllocRow(
                rs.getObject("journal_id", UUID.class),
                rs.getObject("voucher_date", LocalDate.class),
                rs.getString("voucher_number"),
                rs.getString("base_category"),
                rs.getString("party_name"),
                rs.getString("party_gstin"),
                rs.getString("pos_state"),
                rs.getBoolean("is_rcm"),
                rs.getString("supply_nature"),
                rs.getString("hsn_sac_code"),
                rs.getString("gst_uqc_code"),
                rs.getBigDecimal("billed_qty"),
                rs.getBigDecimal("assessable_value"),
                rs.getBigDecimal("gst_rate_pct"),
                rs.getBigDecimal("igst_amount"),
                rs.getBigDecimal("cgst_amount"),
                rs.getBigDecimal("sgst_utgst_amount"),
                rs.getBigDecimal("cess_amount")
            ),
            params.toArray()
        );
    }

    private List<StatutoryDtos.Gstr1DocSummaryRowDto> loadGstr1DocumentSummary(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        UUID gstRegistrationId
    ) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(fromDate);
        params.add(toDate);
        StringBuilder regClause = new StringBuilder();
        if (gstRegistrationId != null) {
            regClause.append(" AND j.company_gstin_id = ?");
            params.add(gstRegistrationId);
        }

        String sql =
            """
            SELECT vt.name AS voucher_type_name,
                   vt.base_category,
                   COALESCE(MIN(j.voucher_number), '') AS serial_from,
                   COALESCE(MAX(j.voucher_number), '') AS serial_to,
                   COUNT(*) FILTER (WHERE j.status IN ('POSTED', 'REVERSED', 'CANCELLED')) AS total_count,
                   COUNT(*) FILTER (WHERE j.status = 'CANCELLED') AS cancelled_count,
                   COUNT(*) FILTER (WHERE j.status IN ('POSTED', 'REVERSED')) AS net_issued_count
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE j.company_id = ?
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_number IS NOT NULL
               AND j.voucher_date BETWEEN ? AND ?
               AND vt.base_category IN ('SALES', 'CREDIT_NOTE', 'DEBIT_NOTE', 'DELIVERY_NOTE')
            """ + regClause + " GROUP BY vt.name, vt.base_category ORDER BY vt.base_category ASC, vt.name ASC";

        return businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> {
                String baseCat = rs.getString("base_category");
                String nature = switch (baseCat) {
                    case "SALES" -> "Invoices for outward supply";
                    case "CREDIT_NOTE" -> "Credit Note";
                    case "DEBIT_NOTE" -> "Debit Note";
                    default -> "Delivery Challan";
                };
                return new StatutoryDtos.Gstr1DocSummaryRowDto(
                    nature,
                    rs.getString("voucher_type_name"),
                    baseCat,
                    rs.getString("serial_from"),
                    rs.getString("serial_to"),
                    rs.getLong("total_count"),
                    rs.getLong("cancelled_count"),
                    rs.getLong("net_issued_count")
                );
            },
            params.toArray()
        );
    }

    private BucketAcc loadRcmBucket(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        UUID gstRegistrationId
    ) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(fromDate);
        params.add(toDate);
        StringBuilder regClause = new StringBuilder();
        if (gstRegistrationId != null) {
            regClause.append(" AND j.company_gstin_id = ?");
            params.add(gstRegistrationId);
        }
        BucketAcc acc = new BucketAcc();
        String sql =
            """
            SELECT COALESCE(SUM(ta.assessable_value), 0::numeric) AS tx,
                   COALESCE(SUM(ta.igst_amount), 0::numeric) AS igst,
                   COALESCE(SUM(ta.cgst_amount), 0::numeric) AS cgst,
                   COALESCE(SUM(ta.sgst_utgst_amount), 0::numeric) AS sgst,
                   COALESCE(SUM(ta.cess_amount), 0::numeric) AS cess
              FROM mc_tax_allocation ta
              JOIN mc_journal j
                ON j.company_id = ta.company_id AND j.id = ta.journal_id
             WHERE ta.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_date BETWEEN ? AND ?
               AND ta.is_rcm = true
               AND ta.is_uncertain_transaction = false
            """ + regClause;
        businessJdbcTemplate.query(
            sql,
            rs -> {
                acc.add(
                    rs.getBigDecimal("tx"),
                    rs.getBigDecimal("igst"),
                    rs.getBigDecimal("cgst"),
                    rs.getBigDecimal("sgst"),
                    rs.getBigDecimal("cess")
                );
            },
            params.toArray()
        );
        return acc;
    }

    private ItcSynthesisResult synthesizeEligibleAndReversedItc(
        UUID companyId,
        DateWindow window,
        UUID gstRegistrationId,
        BucketAcc rcmBucket,
        int scale
    ) {
        // 1. Unmatched book purchase ITC in [fromDate..toDate]
        List<Object> bookParams = new ArrayList<>();
        bookParams.add(companyId);
        bookParams.add(window.fromDate());
        bookParams.add(window.toDate());
        StringBuilder regClause = new StringBuilder();
        if (gstRegistrationId != null) {
            regClause.append(" AND j.company_gstin_id = ?");
            bookParams.add(gstRegistrationId);
        }

        BucketAcc unmatchedBooksItc = new BucketAcc();
        BucketAcc purchaseReturnsReversed = new BucketAcc();

        String bookSql =
            """
            SELECT vt.base_category,
                   ta.assessable_value, ta.igst_amount, ta.cgst_amount, ta.sgst_utgst_amount, ta.cess_amount,
                   EXISTS (
                       SELECT 1 FROM mc_ims_inward_invoice ims
                        WHERE ims.company_id = ta.company_id
                          AND ims.matched_journal_id = ta.journal_id
                   ) AS has_ims_link
              FROM mc_tax_allocation ta
              JOIN mc_journal j
                ON j.company_id = ta.company_id AND j.id = ta.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE ta.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_date BETWEEN ? AND ?
               AND ta.is_rcm = false
               AND ta.is_uncertain_transaction = false
               AND ta.itc_eligibility IN ('ELIGIBLE_INPUTS', 'ELIGIBLE_CAPITAL_GOODS', 'ELIGIBLE_INPUT_SERVICES')
            """ + regClause;

        businessJdbcTemplate.query(
            bookSql,
            rs -> {
                String cat = rs.getString("base_category");
                boolean hasIms = rs.getBoolean("has_ims_link");
                if ("DEBIT_NOTE".equals(cat)) {
                    purchaseReturnsReversed.add(
                        rs.getBigDecimal("assessable_value"),
                        rs.getBigDecimal("igst_amount"),
                        rs.getBigDecimal("cgst_amount"),
                        rs.getBigDecimal("sgst_utgst_amount"),
                        rs.getBigDecimal("cess_amount")
                    );
                } else if (!hasIms) {
                    unmatchedBooksItc.add(
                        rs.getBigDecimal("assessable_value"),
                        rs.getBigDecimal("igst_amount"),
                        rs.getBigDecimal("cgst_amount"),
                        rs.getBigDecimal("sgst_utgst_amount"),
                        rs.getBigDecimal("cess_amount")
                    );
                }
            },
            bookParams.toArray()
        );

        // 2. IMS invoices whose effective_filing_period or portal_return_period falls in window
        BucketAcc imsNetAccepted = new BucketAcc();
        BucketAcc imsReductionBucket = new BucketAcc();
        final int[] acceptedCount = {0};
        final BigDecimal[] pendingDeferred = {BigDecimal.ZERO};
        final BigDecimal[] reductionTotal = {BigDecimal.ZERO};

        List<Object> imsParams = new ArrayList<>();
        imsParams.add(companyId);
        StringBuilder imsWhere = new StringBuilder("company_id = ?");
        if (gstRegistrationId != null) {
            imsWhere.append(" AND gst_registration_id = ?");
            imsParams.add(gstRegistrationId);
        }

        businessJdbcTemplate.query(
            """
            SELECT portal_return_period, effective_filing_period, ims_action,
                   portal_taxable_value, portal_igst, portal_cgst, portal_sgst, portal_cess,
                   portal_itc_amount, itc_reduction_amount, is_rcm
              FROM mc_ims_inward_invoice
            """ + " WHERE " + imsWhere,
            rs -> {
                if (rs.getBoolean("is_rcm")) {
                    return;
                }
                String portalFp = rs.getString("portal_return_period");
                String effFp = rs.getString("effective_filing_period");
                String action = rs.getString("ims_action");
                BigDecimal portalItc = rs.getBigDecimal("portal_itc_amount");
                BigDecimal red = rs.getBigDecimal("itc_reduction_amount");

                if ("PENDING".equals(action) && window.coveredFilingPeriods().contains(portalFp)) {
                    pendingDeferred[0] = pendingDeferred[0].add(portalItc);
                    return;
                }
                if ("REJECT".equals(action)) {
                    return;
                }
                if (("ACCEPT".equals(action) || "NO_ACTION".equals(action))
                    && window.coveredFilingPeriods().contains(effFp)) {
                    if ("ACCEPT".equals(action)) {
                        acceptedCount[0]++;
                    }
                    BigDecimal tx = rs.getBigDecimal("portal_taxable_value");
                    BigDecimal igst = rs.getBigDecimal("portal_igst");
                    BigDecimal cgst = rs.getBigDecimal("portal_cgst");
                    BigDecimal sgst = rs.getBigDecimal("portal_sgst");
                    BigDecimal cess = rs.getBigDecimal("portal_cess");

                    if (red.signum() > 0 && portalItc.signum() > 0) {
                        reductionTotal[0] = reductionTotal[0].add(red);
                        BigDecimal ratioKeep = portalItc.subtract(red).divide(portalItc, 8, RoundingMode.HALF_UP);
                        BigDecimal netI = igst.multiply(ratioKeep).setScale(scale, RoundingMode.HALF_UP);
                        BigDecimal netC = cgst.multiply(ratioKeep).setScale(scale, RoundingMode.HALF_UP);
                        BigDecimal netS = sgst.multiply(ratioKeep).setScale(scale, RoundingMode.HALF_UP);
                        BigDecimal netCs = portalItc.subtract(red).subtract(netI).subtract(netC).subtract(netS);
                        if (netCs.signum() < 0) {
                            netS = netS.add(netCs);
                            netCs = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
                        }
                        imsNetAccepted.add(tx, netI, netC, netS, netCs);
                        imsReductionBucket.add(
                            BigDecimal.ZERO,
                            igst.subtract(netI),
                            cgst.subtract(netC),
                            sgst.subtract(netS),
                            cess.subtract(netCs)
                        );
                    } else {
                        imsNetAccepted.add(tx, igst, cgst, sgst, cess);
                    }
                }
            },
            imsParams.toArray()
        );

        BucketAcc allOther = new BucketAcc();
        allOther.add(
            unmatchedBooksItc.taxable.add(imsNetAccepted.taxable),
            unmatchedBooksItc.igst.add(imsNetAccepted.igst),
            unmatchedBooksItc.cgst.add(imsNetAccepted.cgst),
            unmatchedBooksItc.sgst.add(imsNetAccepted.sgst),
            unmatchedBooksItc.cess.add(imsNetAccepted.cess)
        );

        BucketAcc reversed = new BucketAcc();
        reversed.add(
            purchaseReturnsReversed.taxable,
            purchaseReturnsReversed.igst.add(imsReductionBucket.igst),
            purchaseReturnsReversed.cgst.add(imsReductionBucket.cgst),
            purchaseReturnsReversed.sgst.add(imsReductionBucket.sgst),
            purchaseReturnsReversed.cess.add(imsReductionBucket.cess)
        );

        BucketAcc netEligible = new BucketAcc();
        netEligible.add(
            allOther.taxable.add(rcmBucket.taxable).subtract(purchaseReturnsReversed.taxable).max(BigDecimal.ZERO),
            allOther.igst.add(rcmBucket.igst).subtract(purchaseReturnsReversed.igst).max(BigDecimal.ZERO),
            allOther.cgst.add(rcmBucket.cgst).subtract(purchaseReturnsReversed.cgst).max(BigDecimal.ZERO),
            allOther.sgst.add(rcmBucket.sgst).subtract(purchaseReturnsReversed.sgst).max(BigDecimal.ZERO),
            allOther.cess.add(rcmBucket.cess).subtract(purchaseReturnsReversed.cess).max(BigDecimal.ZERO)
        );

        return new ItcSynthesisResult(
            allOther,
            netEligible,
            reversed,
            acceptedCount[0],
            pendingDeferred[0],
            reductionTotal[0]
        );
    }

    private static StatutoryDtos.Gstr3bTaxBucketDto computeRule88aNetPayable(
        BucketAcc tbl31a,
        BucketAcc tbl31b,
        BucketAcc rcmCash,
        BucketAcc eligibleItc,
        int scale
    ) {
        BigDecimal remOutIgst = tbl31a.igst.add(tbl31b.igst).max(BigDecimal.ZERO);
        BigDecimal remOutCgst = tbl31a.cgst.add(tbl31b.cgst).max(BigDecimal.ZERO);
        BigDecimal remOutSgst = tbl31a.sgst.add(tbl31b.sgst).max(BigDecimal.ZERO);
        BigDecimal remOutCess = tbl31a.cess.add(tbl31b.cess).max(BigDecimal.ZERO);

        BigDecimal remItcIgst = eligibleItc.igst;
        BigDecimal remItcCgst = eligibleItc.cgst;
        BigDecimal remItcSgst = eligibleItc.sgst;
        BigDecimal remItcCess = eligibleItc.cess;

        // 1. IGST ITC -> IGST, then CGST, then SGST
        BigDecimal use = remItcIgst.min(remOutIgst);
        remItcIgst = remItcIgst.subtract(use);
        remOutIgst = remOutIgst.subtract(use);

        use = remItcIgst.min(remOutCgst);
        remItcIgst = remItcIgst.subtract(use);
        remOutCgst = remOutCgst.subtract(use);

        use = remItcIgst.min(remOutSgst);
        remItcIgst = remItcIgst.subtract(use);
        remOutSgst = remOutSgst.subtract(use);

        // 2. CGST ITC -> CGST, then IGST
        use = remItcCgst.min(remOutCgst);
        remItcCgst = remItcCgst.subtract(use);
        remOutCgst = remOutCgst.subtract(use);

        use = remItcCgst.min(remOutIgst);
        remItcCgst = remItcCgst.subtract(use);
        remOutIgst = remOutIgst.subtract(use);

        // 3. SGST ITC -> SGST, then IGST
        use = remItcSgst.min(remOutSgst);
        remItcSgst = remItcSgst.subtract(use);
        remOutSgst = remOutSgst.subtract(use);

        use = remItcSgst.min(remOutIgst);
        remItcSgst = remItcSgst.subtract(use);
        remOutIgst = remOutIgst.subtract(use);

        // 4. Cess ITC -> Cess
        use = remItcCess.min(remOutCess);
        remOutCess = remOutCess.subtract(use);

        // 5. Add mandatory cash RCM liability (Table 3.1d)
        BigDecimal cashIgst = remOutIgst.add(rcmCash.igst).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal cashCgst = remOutCgst.add(rcmCash.cgst).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal cashSgst = remOutSgst.add(rcmCash.sgst).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal cashCess = remOutCess.add(rcmCash.cess).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal cashTotal = cashIgst.add(cashCgst).add(cashSgst).add(cashCess);

        return new StatutoryDtos.Gstr3bTaxBucketDto(
            ExactMoneyValidator.formatExactScale(BigDecimal.ZERO, scale),
            ExactMoneyValidator.formatExactScale(cashIgst, scale),
            ExactMoneyValidator.formatExactScale(cashCgst, scale),
            ExactMoneyValidator.formatExactScale(cashSgst, scale),
            ExactMoneyValidator.formatExactScale(cashCess, scale),
            ExactMoneyValidator.formatExactScale(cashTotal, scale)
        );
    }

    private BookMatchResult matchBookPurchase(
        UUID companyId,
        String supplierGstin,
        String portalInvNo,
        BigDecimal portalTaxable,
        BigDecimal portalTax
    ) {
        String normPortalNo = normalizeDocNumber(portalInvNo);
        List<Map<String, Object>> candidates = businessJdbcTemplate.queryForList(
            """
            SELECT j.id,
                   COALESCE(j.supplier_invoice_no, '') AS supp_no,
                   COALESCE(j.voucher_number, '') AS vch_no,
                   COALESCE((
                       SELECT SUM(ta.assessable_value)
                         FROM mc_tax_allocation ta
                        WHERE ta.company_id = j.company_id AND ta.journal_id = j.id
                   ), 0::numeric) AS book_taxable,
                   COALESCE((
                       SELECT SUM(ta.igst_amount + ta.cgst_amount + ta.sgst_utgst_amount + ta.cess_amount)
                         FROM mc_tax_allocation ta
                        WHERE ta.company_id = j.company_id AND ta.journal_id = j.id
                   ), 0::numeric) AS book_tax
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE j.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.base_category IN ('PURCHASE', 'DEBIT_NOTE', 'JOURNAL')
               AND upper(COALESCE(j.party_gstin, '')) = ?
            """,
            companyId,
            supplierGstin.toUpperCase(Locale.ROOT)
        );

        for (Map<String, Object> row : candidates) {
            String suppNo = normalizeDocNumber((String) row.get("supp_no"));
            String vchNo = normalizeDocNumber((String) row.get("vch_no"));
            if (normPortalNo.equals(suppNo) || normPortalNo.equals(vchNo)) {
                UUID jId = (UUID) row.get("id");
                BigDecimal bTaxable = (BigDecimal) row.get("book_taxable");
                BigDecimal bTax = (BigDecimal) row.get("book_tax");
                boolean exact = bTaxable.subtract(portalTaxable).abs().compareTo(ONE_PAISA) <= 0
                    && bTax.subtract(portalTax).abs().compareTo(ONE_PAISA) <= 0;
                return new BookMatchResult(jId, exact ? "EXACT_MATCH" : "PARTIAL_MISMATCH");
            }
        }
        return new BookMatchResult(null, "MISSING_IN_BOOKS");
    }

    private List<StatutoryDtos.ImsInvoiceDto> loadImsDtosByIds(UUID companyId, List<UUID> ids, int scale) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        StringBuilder inSql = new StringBuilder();
        for (UUID id : ids) {
            if (!inSql.isEmpty()) {
                inSql.append(", ");
            }
            inSql.append("?");
            params.add(id);
        }
        String sql = imsSelectSql("ims.company_id = ? AND ims.id IN (" + inSql + ")")
            + " ORDER BY ims.invoice_date ASC, ims.invoice_number ASC";
        return businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> mapImsDto(rs, scale),
            params.toArray()
        );
    }

    private static String imsSelectSql(String whereClause) {
        return """
            SELECT ims.id, ims.gst_registration_id, ims.supplier_gstin,
                   COALESCE(ims.supplier_legal_name, ims.supplier_gstin) AS supplier_name,
                   ims.doc_type, ims.invoice_number, ims.invoice_date, ims.place_of_supply_state,
                   ims.is_rcm, ims.portal_return_period, ims.effective_filing_period,
                   ims.portal_taxable_value, ims.portal_igst, ims.portal_cgst,
                   ims.portal_sgst, ims.portal_cess, ims.portal_itc_amount,
                   ims.itc_reduction_amount, ims.ims_action, ims.remarks,
                   ims.match_status, ims.matched_journal_id, ims.version_no,
                   j.total_debit AS books_invoice_value,
                   (
                       SELECT SUM(ta.igst_amount + ta.cgst_amount + ta.sgst_utgst_amount + ta.cess_amount)
                         FROM mc_tax_allocation ta
                        WHERE ta.company_id = ims.company_id
                          AND ta.journal_id = ims.matched_journal_id
                   ) AS books_tax_amount
              FROM mc_ims_inward_invoice ims
              LEFT JOIN mc_journal j
                ON j.company_id = ims.company_id AND j.id = ims.matched_journal_id
            """ + " WHERE " + whereClause;
    }

    private static StatutoryDtos.ImsInvoiceDto mapImsDto(ResultSet rs, int scale) throws SQLException {
        BigDecimal taxable = rs.getBigDecimal("portal_taxable_value").setScale(scale, RoundingMode.HALF_UP);
        BigDecimal igst = rs.getBigDecimal("portal_igst").setScale(scale, RoundingMode.HALF_UP);
        BigDecimal cgst = rs.getBigDecimal("portal_cgst").setScale(scale, RoundingMode.HALF_UP);
        BigDecimal sgst = rs.getBigDecimal("portal_sgst").setScale(scale, RoundingMode.HALF_UP);
        BigDecimal cess = rs.getBigDecimal("portal_cess").setScale(scale, RoundingMode.HALF_UP);
        BigDecimal portalItc = rs.getBigDecimal("portal_itc_amount").setScale(scale, RoundingMode.HALF_UP);
        BigDecimal invVal = taxable.add(igst).add(cgst).add(sgst).add(cess);
        BigDecimal red = rs.getBigDecimal("itc_reduction_amount").setScale(scale, RoundingMode.HALF_UP);
        BigDecimal bVal = rs.getBigDecimal("books_invoice_value");
        BigDecimal bTax = rs.getBigDecimal("books_tax_amount");
        String action = rs.getString("ims_action");

        return new StatutoryDtos.ImsInvoiceDto(
            rs.getObject("id", UUID.class),
            rs.getObject("gst_registration_id", UUID.class),
            rs.getString("supplier_gstin"),
            rs.getString("supplier_name"),
            rs.getString("doc_type"),
            rs.getString("invoice_number"),
            rs.getObject("invoice_date", LocalDate.class),
            rs.getString("place_of_supply_state"),
            rs.getBoolean("is_rcm"),
            rs.getString("portal_return_period"),
            rs.getString("effective_filing_period"),
            ExactMoneyValidator.formatExactScale(invVal, scale),
            ExactMoneyValidator.formatExactScale(taxable, scale),
            ExactMoneyValidator.formatExactScale(igst, scale),
            ExactMoneyValidator.formatExactScale(cgst, scale),
            ExactMoneyValidator.formatExactScale(sgst, scale),
            ExactMoneyValidator.formatExactScale(cess, scale),
            ExactMoneyValidator.formatExactScale(portalItc, scale),
            ExactMoneyValidator.formatExactScale(red, scale),
            action,
            action,
            rs.getString("remarks"),
            rs.getString("match_status"),
            rs.getObject("matched_journal_id", UUID.class),
            bVal == null ? null : ExactMoneyValidator.formatExactScale(bVal.setScale(scale, RoundingMode.HALF_UP), scale),
            bTax == null ? null : ExactMoneyValidator.formatExactScale(bTax.setScale(scale, RoundingMode.HALF_UP), scale),
            rs.getLong("version_no")
        );
    }

    private BigDecimal sumPortal2bItcForWindow(UUID companyId, LocalDate fromDate, LocalDate toDate) {
        BigDecimal sum = businessJdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(portal_itc_amount), 0::numeric)
              FROM mc_ims_inward_invoice
             WHERE company_id = ?
               AND invoice_date BETWEEN ? AND ?
            """,
            BigDecimal.class,
            companyId,
            fromDate,
            toDate
        );
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private List<StatutoryDtos.Gstr1HsnSummaryRowDto> loadInwardHsnSummary(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        int scale
    ) {
        Map<HsnKey, HsnAcc> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT COALESCE(ta.hsn_sac_code, '0000') AS hsn,
                   COALESCE(ta.gst_uqc_code, 'NOS') AS uqc,
                   (ta.cgst_rate_pct + ta.sgst_utgst_rate_pct + ta.igst_rate_pct) AS rate_pct,
                   ta.billed_qty, ta.assessable_value,
                   ta.igst_amount, ta.cgst_amount, ta.sgst_utgst_amount, ta.cess_amount
              FROM mc_tax_allocation ta
              JOIN mc_journal j
                ON j.company_id = ta.company_id AND j.id = ta.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE ta.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_date BETWEEN ? AND ?
               AND vt.base_category = 'PURCHASE'
               AND ta.is_uncertain_transaction = false
            """,
            rs -> {
                String hsn = rs.getString("hsn");
                String uqc = hsn.startsWith("99") ? "NA" : rs.getString("uqc");
                BigDecimal qty = hsn.startsWith("99") ? BigDecimal.ZERO : rs.getBigDecimal("billed_qty");
                HsnKey k = new HsnKey(hsn, uqc, rs.getBigDecimal("rate_pct").stripTrailingZeros());
                map.computeIfAbsent(k, x -> new HsnAcc()).add(
                    qty,
                    rs.getBigDecimal("assessable_value"),
                    rs.getBigDecimal("igst_amount"),
                    rs.getBigDecimal("cgst_amount"),
                    rs.getBigDecimal("sgst_utgst_amount"),
                    rs.getBigDecimal("cess_amount")
                );
            },
            companyId,
            fromDate,
            toDate
        );
        return toHsnDtoList(map, scale);
    }

    private static List<StatutoryDtos.Gstr1HsnSummaryRowDto> toHsnDtoList(Map<HsnKey, HsnAcc> map, int scale) {
        List<StatutoryDtos.Gstr1HsnSummaryRowDto> list = new ArrayList<>(map.size());
        List<Map.Entry<HsnKey, HsnAcc>> sorted = new ArrayList<>(map.entrySet());
        sorted.sort(Comparator.comparing(e -> e.getKey().hsn()));
        for (Map.Entry<HsnKey, HsnAcc> e : sorted) {
            HsnKey k = e.getKey();
            HsnAcc a = e.getValue();
            BigDecimal totalVal = a.taxable.add(a.igst).add(a.cgst).add(a.sgst).add(a.cess);
            String qtyStr = ExactMoneyValidator.formatPlainDecimal(a.qty.stripTrailingZeros());
            list.add(
                new StatutoryDtos.Gstr1HsnSummaryRowDto(
                    k.hsn(),
                    "HSN " + k.hsn(),
                    k.uqc(),
                    k.uqc(),
                    qtyStr,
                    qtyStr,
                    ExactMoneyValidator.formatExactScale(totalVal.setScale(scale, RoundingMode.HALF_UP), scale),
                    StatRulePackAndGstMasterService.formatRate2(k.ratePct()),
                    ExactMoneyValidator.formatExactScale(a.taxable.setScale(scale, RoundingMode.HALF_UP), scale),
                    ExactMoneyValidator.formatExactScale(a.igst.setScale(scale, RoundingMode.HALF_UP), scale),
                    ExactMoneyValidator.formatExactScale(a.cgst.setScale(scale, RoundingMode.HALF_UP), scale),
                    ExactMoneyValidator.formatExactScale(a.sgst.setScale(scale, RoundingMode.HALF_UP), scale),
                    ExactMoneyValidator.formatExactScale(a.cess.setScale(scale, RoundingMode.HALF_UP), scale)
                )
            );
        }
        return List.copyOf(list);
    }

    private static StatutoryDtos.Gstr3bTaxBucketDto toBucketDto(BucketAcc b, int scale) {
        return new StatutoryDtos.Gstr3bTaxBucketDto(
            ExactMoneyValidator.formatExactScale(b.taxable.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(b.igst.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(b.cgst.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(b.sgst.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(b.cess.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(b.totalTax().setScale(scale, RoundingMode.HALF_UP), scale)
        );
    }

    private static StatutoryDtos.Gstr9TableRowDto toGstr9Row(
        String code,
        String desc,
        BucketAcc b,
        int scale
    ) {
        return new StatutoryDtos.Gstr9TableRowDto(
            code,
            desc,
            ExactMoneyValidator.formatExactScale(b.taxable.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(b.igst.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(b.cgst.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(b.sgst.setScale(scale, RoundingMode.HALF_UP), scale),
            ExactMoneyValidator.formatExactScale(b.cess.setScale(scale, RoundingMode.HALF_UP), scale)
        );
    }

    private static StatutoryDtos.Gstr9TableRowDto bucketToGstr9Row(
        String code,
        String desc,
        StatutoryDtos.Gstr3bTaxBucketDto b
    ) {
        return new StatutoryDtos.Gstr9TableRowDto(
            code,
            desc,
            b.taxableValue() == null ? "0.00" : b.taxableValue(),
            b.igstAmount(),
            b.cgstAmount(),
            b.sgstAmount(),
            b.cessAmount()
        );
    }

    private static byte[] renderGstr9Workbook(StatutoryDtos.Gstr9ReportDto report) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            headerStyle.setFont(boldFont);

            // Sheet 1: Overview
            Sheet overview = wb.createSheet("GSTR9_Overview");
            writeStringRow(overview, 0, headerStyle, "Field", "Value");
            writeStringRow(overview, 1, null, "Financial Year", report.financialYearLabel());
            writeStringRow(overview, 2, null, "GSTIN", report.gstin() != null ? report.gstin() : "");
            writeStringRow(overview, 3, null, "Total Outward Taxable", report.totalOutwardTaxable());
            writeStringRow(overview, 4, null, "Total Outward Tax", report.totalOutwardTax());
            writeStringRow(overview, 5, null, "Total ITC Availed", report.totalItcAvailed());
            writeStringRow(overview, 6, null, "Total ITC Reversed", report.totalItcReversed());
            writeStringRow(overview, 7, null, "Net Tax Payable", report.netTaxPayable());
            writeStringRow(overview, 8, null, "GSTR-2B / IMS Difference", report.reconciliationDifference());

            // Sheet 2..5: Tables 4, 5, 6 & 7, 8 & 9
            writeTableRowsSheet(wb, "Table_4_Outward", headerStyle, report.table4Outward());
            writeTableRowsSheet(wb, "Table_5_Exempt", headerStyle, report.table5Exempt());

            List<StatutoryDtos.Gstr9TableRowDto> tbl67 = new ArrayList<>(report.table6ItcAvailed());
            tbl67.addAll(report.table7ItcReversed());
            writeTableRowsSheet(wb, "Table_6_7_ITC", headerStyle, tbl67);

            List<StatutoryDtos.Gstr9TableRowDto> tbl89 = new ArrayList<>(report.table8Reconciliation());
            tbl89.addAll(report.table9TaxPaid());
            writeTableRowsSheet(wb, "Table_8_9_Recon_Tax", headerStyle, tbl89);

            // Sheet 6 & 7: Table 17 Outward HSN & Table 18 Inward HSN
            writeHsnSheet(wb, "Table_17_HSN_Outward", headerStyle, report.table17HsnOutward());
            writeHsnSheet(wb, "Table_18_HSN_Inward", headerStyle, report.table18HsnInward());

            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build GSTR-9 XLSX workbook", ex);
        }
    }

    private static void writeTableRowsSheet(
        XSSFWorkbook wb,
        String sheetName,
        CellStyle headerStyle,
        List<StatutoryDtos.Gstr9TableRowDto> rows
    ) {
        Sheet sheet = wb.createSheet(sheetName);
        writeStringRow(sheet, 0, headerStyle, "Table Code", "Description", "Taxable Value", "IGST", "CGST", "SGST", "Cess");
        int rowIdx = 1;
        for (StatutoryDtos.Gstr9TableRowDto r : rows) {
            writeStringRow(
                sheet,
                rowIdx++,
                null,
                r.tableCode(),
                r.description(),
                r.taxableValue(),
                r.igstAmount(),
                r.cgstAmount(),
                r.sgstAmount(),
                r.cessAmount()
            );
        }
    }

    private static void writeHsnSheet(
        XSSFWorkbook wb,
        String sheetName,
        CellStyle headerStyle,
        List<StatutoryDtos.Gstr1HsnSummaryRowDto> rows
    ) {
        Sheet sheet = wb.createSheet(sheetName);
        writeStringRow(
            sheet,
            0,
            headerStyle,
            "HSN/SAC",
            "UQC",
            "Total Quantity",
            "GST Rate %",
            "Taxable Value",
            "IGST",
            "CGST",
            "SGST",
            "Cess"
        );
        int rowIdx = 1;
        for (StatutoryDtos.Gstr1HsnSummaryRowDto r : rows) {
            writeStringRow(
                sheet,
                rowIdx++,
                null,
                r.hsnSacCode(),
                r.uqcCode(),
                r.totalQuantity(),
                r.gstRatePct(),
                r.taxableValue(),
                r.igstAmount(),
                r.cgstAmount(),
                r.sgstAmount(),
                r.cessAmount()
            );
        }
    }

    private static void writeStringRow(Sheet sheet, int rowIdx, CellStyle style, String... values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i] == null ? "" : values[i]);
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    private void ensureActorProjected(MoneyCraftPrincipal actor) {
        if (actor == null || actor.principalId() == null) {
            return;
        }
        String kind = "SERVICE".equalsIgnoreCase(actor.kind()) ? "SERVICE" : "PERSON";
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_principal_projection (
                principal_id, kind, username, display_name, enabled, version_no
            ) VALUES (?, ?, ?, ?, true, 1)
            ON CONFLICT (principal_id) DO NOTHING
            """,
            actor.principalId(),
            kind,
            actor.username() == null ? "system" : actor.username(),
            actor.username() == null ? "System" : actor.username()
        );
    }

    private static String normalizeFilingPeriod(String raw, LocalDate fallbackDate) {
        return normalizeFilingPeriod(raw, fallbackDate, null);
    }

    private static String normalizeFilingPeriod(String raw, LocalDate fallbackDate, String explicitFallback) {
        if (raw != null && !raw.isBlank()) {
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.length() == 6) {
                int firstTwo = Integer.parseInt(digits.substring(0, 2));
                if (firstTwo >= 1 && firstTwo <= 12) {
                    return digits;
                }
                // Convert YYYYMM -> MMYYYY if supplied in ISO order
                return digits.substring(4, 6) + digits.substring(0, 4);
            }
        }
        if (explicitFallback != null && !explicitFallback.isBlank()) {
            return explicitFallback;
        }
        return FILING_PERIOD_FMT.format(fallbackDate);
    }

    private static String normalizeImsDocType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "INVOICE";
        }
        String up = raw.trim().toUpperCase(Locale.ROOT);
        if ("INV".equals(up) || "R".equals(up)) {
            return "INVOICE";
        }
        if ("DN".equals(up) || "D".equals(up)) {
            return "DEBIT_NOTE";
        }
        if ("CN".equals(up) || "C".equals(up)) {
            return "CREDIT_NOTE";
        }
        return VALID_IMS_DOC_TYPES.contains(up) ? up : "INVOICE";
    }

    private static String normalizeDocNumber(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-/]", "");
    }

    private static BigDecimal parseMoneyOpt(String raw, int scale) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        }
        return new BigDecimal(raw.trim()).setScale(scale, RoundingMode.HALF_UP);
    }

    private static LocalDate parseIsoDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String t = raw.trim();
        if (t.matches("^[0-9]{2}-[0-9]{2}-[0-9]{4}$")) {
            return LocalDate.parse(t, DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        }
        return LocalDate.parse(t);
    }

    private static String asStr(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }
}
