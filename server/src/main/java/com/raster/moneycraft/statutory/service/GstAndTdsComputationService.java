package com.raster.moneycraft.statutory.service;

import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.accounting.spi.VoucherStatutoryBridge;
import com.raster.moneycraft.foundation.audit.AuditChainService;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 6-Tier GST Rate & HSN Inheritance Engine, {@code appropriate_for = 'GST'} Freight
 * Apportionment, Rel 7.2 Tobacco Deemed MRP Valuation, Reverse Charge, Uncertain Transaction
 * Detection & Conflict Acceptance, and Automatic TDS/TCS Deduction Persistence.
 */
@Service
public class GstAndTdsComputationService implements VoucherStatutoryBridge {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal ONE_PAISA = new BigDecimal("0.01");
    private static final DateTimeFormatter FILING_PERIOD_FMT = DateTimeFormatter.ofPattern("MMyyyy");

    private static final Set<String> GST_VOUCHER_CATEGORIES = Set.of(
        "SALES", "PURCHASE", "CREDIT_NOTE", "DEBIT_NOTE"
    );

    private static final Set<String> OUTWARD_VOUCHER_CATEGORIES = Set.of(
        "SALES", "CREDIT_NOTE"
    );

    private static final Set<String> REGISTERED_PARTY_TYPES = Set.of(
        "REGULAR", "SEZ", "COMPOSITION"
    );

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final StatRulePackAndGstMasterService rulePackAndGstMasterService;
    private final AuditChainService auditChainService;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public GstAndTdsComputationService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        StatRulePackAndGstMasterService rulePackAndGstMasterService,
        AuditChainService auditChainService,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.rulePackAndGstMasterService = rulePackAndGstMasterService;
        this.auditChainService = auditChainService;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    @Override
    public UUID resolveDefaultCompanyGstinId(UUID companyId, LocalDate effectiveDate) {
        return rulePackAndGstMasterService.resolveDefaultCompanyGstinId(companyId, effectiveDate);
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void clearJournalStatutoryAllocations(UUID companyId, UUID journalId) {
        businessJdbcTemplate.update(
            "DELETE FROM mc_tax_allocation WHERE company_id = ? AND journal_id = ?",
            companyId,
            journalId
        );
        businessJdbcTemplate.update(
            "DELETE FROM mc_tds_deduction WHERE company_id = ? AND journal_id = ?",
            companyId,
            journalId
        );
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void computeAndPersistJournalStatutory(UUID companyId, UUID journalId) {
        List<StoredJournalHeader> headers = businessJdbcTemplate.query(
            """
            SELECT j.id, j.voucher_date, j.effective_date, j.status, j.posting_nature,
                   j.party_ledger_id, j.accounting_ledger_id, j.place_of_supply_state,
                   j.company_gstin_id, j.party_gstin, j.party_state_code,
                   j.is_reverse_charge, j.export_type, j.reversal_of_journal_id,
                   vt.base_category, c.currency_scale
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              JOIN mc_company c
                ON c.id = j.company_id
             WHERE j.company_id = ? AND j.id = ?
            """,
            this::mapStoredHeader,
            companyId,
            journalId
        );
        if (headers.isEmpty()) {
            return;
        }
        StoredJournalHeader hdr = headers.getFirst();
        if (!"POSTED".equals(hdr.status())
            || !"NORMAL".equals(hdr.postingNature())
            || hdr.reversalOfJournalId() != null) {
            return;
        }

        StatRulePackAndGstMasterService.RulePackConfig rulePack =
            rulePackAndGstMasterService.loadActiveRulePack(hdr.effectiveDate());
        StatRulePackAndGstMasterService.GstRegContext gstReg =
            rulePackAndGstMasterService.resolveRegistrationOrCompanyFallback(
                companyId,
                hdr.companyGstinId(),
                hdr.effectiveDate()
            );

        List<StoredJournalLine> lines = loadStoredJournalLines(companyId, journalId);
        List<StoredInvLine> invLines = loadStoredInvLines(companyId, journalId);

        Set<UUID> allLedgerIds = new LinkedHashSet<>();
        if (hdr.partyLedgerId() != null) {
            allLedgerIds.add(hdr.partyLedgerId());
        }
        if (hdr.accountingLedgerId() != null) {
            allLedgerIds.add(hdr.accountingLedgerId());
        }
        for (StoredJournalLine jl : lines) {
            allLedgerIds.add(jl.ledgerId());
        }
        Map<UUID, LedgerTaxMeta> ledgerMap = loadLedgerTaxMeta(companyId, allLedgerIds);

        UUID effectivePartyLedgerId = resolveEffectivePartyLedgerId(hdr.partyLedgerId(), lines, ledgerMap);
        LedgerTaxMeta partyMeta = effectivePartyLedgerId == null ? null : ledgerMap.get(effectivePartyLedgerId);

        String effectivePartyGstin = firstNonBlank(
            hdr.partyGstin(),
            partyMeta != null ? partyMeta.gstin() : null
        );
        String effectivePartyState = firstNonBlank(
            hdr.partyStateCode(),
            effectivePartyGstin != null && effectivePartyGstin.length() >= 2
                ? effectivePartyGstin.substring(0, 2)
                : null,
            partyMeta != null ? partyMeta.stateCode() : null
        );
        String companyState = firstNonBlank(gstReg.stateCode(), effectivePartyState, "33");
        boolean inwardSupply = isInwardGstVoucher(hdr.baseCategory(), hdr.isReverseCharge());
        String effectivePosState = inwardSupply
            ? firstNonBlank(hdr.placeOfSupplyState(), companyState)
            : firstNonBlank(hdr.placeOfSupplyState(), effectivePartyState, companyState);
        String supplierOriginState = inwardSupply
            ? firstNonBlank(effectivePartyState, companyState)
            : companyState;
        UUID resolvedCompanyGstinId = hdr.companyGstinId() != null ? hdr.companyGstinId() : gstReg.id();

        // Backfill missing party GSTIN / POS / default registration on mc_journal before snapshot
        if ((hdr.partyGstin() == null && effectivePartyGstin != null)
            || (hdr.partyStateCode() == null && effectivePartyState != null)
            || (hdr.placeOfSupplyState() == null && effectivePosState != null)
            || (hdr.companyGstinId() == null && resolvedCompanyGstinId != null)) {
            businessJdbcTemplate.update(
                """
                UPDATE mc_journal
                   SET party_gstin = COALESCE(party_gstin, ?),
                       party_state_code = COALESCE(party_state_code, ?),
                       place_of_supply_state = COALESCE(place_of_supply_state, ?),
                       company_gstin_id = COALESCE(company_gstin_id, ?)
                 WHERE company_id = ? AND id = ?
                """,
                effectivePartyGstin,
                effectivePartyState,
                effectivePosState,
                resolvedCompanyGstinId,
                companyId,
                journalId
            );
        }

        // 1. Persist TDS / TCS deductions if any TDS/TCS duty ledger is credited
        persistTdsDeductionsIfApplicable(
            companyId,
            journalId,
            hdr,
            lines,
            ledgerMap,
            effectivePartyLedgerId,
            rulePack
        );

        // 2. Persist 6-tier GST tax allocations if voucher is GST-relevant
        persistGstAllocationsIfApplicable(
            companyId,
            journalId,
            hdr,
            lines,
            invLines,
            ledgerMap,
            partyMeta,
            effectivePartyGstin,
            supplierOriginState,
            effectivePosState,
            resolvedCompanyGstinId,
            gstReg,
            rulePack
        );
    }

    // -------------------------------------------------------------------------
    // Live GST Compute Preview (`POST /gst/compute-preview`)
    // -------------------------------------------------------------------------

    public StatutoryDtos.GstComputePreviewResponse computePreview(
        MoneyCraftPrincipal principal,
        UUID companyId,
        VoucherDtos.UpsertVoucherRequest request
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TAX-REQ-REQUIRED",
                "Voucher Preview Payload Required",
                "UpsertVoucherRequest body is required."
            );
        }
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        LocalDate effectiveDate = request.effectiveDate() != null
            ? request.effectiveDate()
            : (request.voucherDate() != null ? request.voucherDate() : LocalDate.now());

        StatRulePackAndGstMasterService.RulePackConfig rulePack =
            rulePackAndGstMasterService.loadActiveRulePack(effectiveDate);
        StatRulePackAndGstMasterService.GstRegContext gstReg =
            rulePackAndGstMasterService.resolveRegistrationOrCompanyFallback(
                companyId,
                request.companyGstinId(),
                effectiveDate
            );

        String baseCategory = resolvePreviewBaseCategory(
            companyId,
            request.voucherTypeId(),
            request.voucherTypeName()
        );

        Set<UUID> ledgerIds = new LinkedHashSet<>();
        if (request.partyLedgerId() != null) {
            ledgerIds.add(request.partyLedgerId());
        }
        if (request.accountingLedgerId() != null) {
            ledgerIds.add(request.accountingLedgerId());
        }
        if (request.lines() != null) {
            for (VoucherDtos.VoucherLineRequest lr : request.lines()) {
                if (lr != null && lr.ledgerId() != null) {
                    ledgerIds.add(lr.ledgerId());
                }
            }
        }
        if (request.inventoryLines() != null) {
            for (VoucherDtos.VoucherInventoryLineDto il : request.inventoryLines()) {
                if (il != null && il.accountingLedgerId() != null) {
                    ledgerIds.add(il.accountingLedgerId());
                }
            }
        }
        Map<UUID, LedgerTaxMeta> ledgerMap = loadLedgerTaxMeta(companyId, ledgerIds);

        Set<UUID> itemIds = new LinkedHashSet<>();
        if (request.inventoryLines() != null) {
            for (VoucherDtos.VoucherInventoryLineDto il : request.inventoryLines()) {
                if (il != null && il.stockItemId() != null) {
                    itemIds.add(il.stockItemId());
                }
            }
        }
        Map<UUID, StockItemTaxMeta> itemMap = loadStockItemTaxMeta(companyId, itemIds);
        Map<UUID, GroupTaxMeta> accountGroupMap = loadAllAccountGroupTaxMeta(companyId);
        Map<UUID, StockGroupTaxMeta> stockGroupMap = loadAllStockGroupTaxMeta(companyId);

        LedgerTaxMeta partyMeta = request.partyLedgerId() == null ? null : ledgerMap.get(request.partyLedgerId());
        String partyGstin = firstNonBlank(
            request.partyGstin(),
            partyMeta != null ? partyMeta.gstin() : null
        );
        String partyState = firstNonBlank(
            request.partyStateCode(),
            partyGstin != null && partyGstin.length() >= 2 ? partyGstin.substring(0, 2) : null,
            partyMeta != null ? partyMeta.stateCode() : null
        );
        String companyState = firstNonBlank(gstReg.stateCode(), partyState, "33");
        boolean isRcm = Boolean.TRUE.equals(request.isReverseCharge());
        boolean inwardSupply = isInwardGstVoucher(baseCategory, isRcm);
        String posState = inwardSupply
            ? firstNonBlank(request.placeOfSupplyState(), companyState)
            : firstNonBlank(request.placeOfSupplyState(), partyState, companyState);
        String supplierOriginState = inwardSupply
            ? firstNonBlank(partyState, companyState)
            : companyState;
        String exportType = resolveEffectiveExportType(request.exportType(), partyMeta);

        List<CandidateTaxableUnit> rawUnits = buildPreviewTaxableUnits(
            request,
            scale,
            ledgerMap,
            itemMap,
            accountGroupMap,
            stockGroupMap,
            gstReg,
            rulePack,
            supplierOriginState,
            posState,
            isRcm,
            exportType
        );

        ComputedVoucherGstResult computed = apportionAndRoundSlabs(rawUnits, scale, rulePack);
        String detailedNature = computed.primaryDetailedSupplyNature(supplierOriginState, posState, isRcm, exportType);
        String uiSupplyNature = toUiSupplyNature(detailedNature);

        List<StatutoryDtos.GstLineBreakupDto> breakups = new ArrayList<>();
        for (ComputedTaxLine cl : computed.lines()) {
            BigDecimal lineTax = cl.cgstAmount()
                .add(cl.sgstAmount())
                .add(cl.igstAmount())
                .add(cl.cessAmount());
            String sgstWire = ExactMoneyValidator.formatExactScale(cl.sgstAmount(), scale);
            breakups.add(
                new StatutoryDtos.GstLineBreakupDto(
                    cl.lineNo(),
                    cl.description(),
                    cl.hsnSacCode(),
                    cl.uqcCode(),
                    ExactMoneyValidator.formatPlainDecimal(cl.billedQty()),
                    cl.rateSourceLevel(),
                    StatRulePackAndGstMasterService.formatRate2(cl.gstRatePct()),
                    StatRulePackAndGstMasterService.formatRate2(cl.cessRatePct()),
                    ExactMoneyValidator.formatExactScale(cl.commercialTaxableValue(), scale),
                    ExactMoneyValidator.formatExactScale(cl.apportionedExpenseValue(), scale),
                    cl.declaredMrp() == null
                        ? null
                        : ExactMoneyValidator.formatExactScale(cl.declaredMrp().setScale(scale, RoundingMode.HALF_UP), scale),
                    cl.deemedMrpTaxableValue() == null
                        ? null
                        : ExactMoneyValidator.formatExactScale(cl.deemedMrpTaxableValue(), scale),
                    cl.isDeemedMrpValuation(),
                    ExactMoneyValidator.formatExactScale(cl.assessableValue(), scale),
                    ExactMoneyValidator.formatExactScale(cl.cgstAmount(), scale),
                    sgstWire,
                    sgstWire,
                    ExactMoneyValidator.formatExactScale(cl.igstAmount(), scale),
                    ExactMoneyValidator.formatExactScale(cl.cessAmount(), scale),
                    ExactMoneyValidator.formatExactScale(lineTax, scale)
                )
            );
        }

        List<StatutoryDtos.GstSlabTotalDto> slabDtos = new ArrayList<>();
        for (ComputedSlabSummary slab : computed.slabs()) {
            BigDecimal slabTax = slab.cgstAmount()
                .add(slab.sgstAmount())
                .add(slab.igstAmount())
                .add(slab.cessAmount());
            String sgstWire = ExactMoneyValidator.formatExactScale(slab.sgstAmount(), scale);
            slabDtos.add(
                new StatutoryDtos.GstSlabTotalDto(
                    StatRulePackAndGstMasterService.formatRate2(slab.gstRatePct()),
                    ExactMoneyValidator.formatExactScale(slab.assessableValue(), scale),
                    ExactMoneyValidator.formatExactScale(slab.cgstAmount(), scale),
                    sgstWire,
                    sgstWire,
                    ExactMoneyValidator.formatExactScale(slab.igstAmount(), scale),
                    ExactMoneyValidator.formatExactScale(slab.cessAmount(), scale),
                    ExactMoneyValidator.formatExactScale(slabTax, scale)
                )
            );
        }

        String taxLineSide = determineTaxSide(baseCategory, partyMeta);
        List<StatutoryDtos.GstSuggestedTaxLineDto> suggestedLines = buildSuggestedTaxLines(
            companyId,
            computed,
            taxLineSide,
            scale
        );

        return new StatutoryDtos.GstComputePreviewResponse(
            uiSupplyNature,
            detailedNature,
            companyState,
            posState,
            List.copyOf(breakups),
            List.copyOf(slabDtos),
            ExactMoneyValidator.formatExactScale(computed.totalAssessable(), scale),
            ExactMoneyValidator.formatExactScale(computed.totalCgst(), scale),
            ExactMoneyValidator.formatExactScale(computed.totalSgst(), scale),
            ExactMoneyValidator.formatExactScale(computed.totalIgst(), scale),
            ExactMoneyValidator.formatExactScale(computed.totalCess(), scale),
            ExactMoneyValidator.formatExactScale(computed.totalTax(), scale),
            suggestedLines
        );
    }

    // -------------------------------------------------------------------------
    // Uncertain Transactions Workbench (`GET` & `POST accept-conflict`)
    // -------------------------------------------------------------------------

    public List<StatutoryDtos.UncertainTransactionDto> listUncertainTransactions(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();

        List<Object> params = new ArrayList<>();
        params.add(companyId);
        StringBuilder dateFilter = new StringBuilder();
        if (fromDate != null) {
            dateFilter.append(" AND uv.voucher_date >= ?");
            params.add(fromDate);
        }
        if (toDate != null) {
            dateFilter.append(" AND uv.voucher_date <= ?");
            params.add(toDate);
        }

        String sql =
            """
            SELECT uv.journal_id, uv.voucher_date, vt.name AS voucher_type_name,
                   COALESCE(uv.voucher_number, '') AS voucher_number,
                   COALESCE(pl.name, '') AS party_name,
                   uv.party_gstin,
                   uv.uncertain_category,
                   (
                       SELECT ta.uncertain_reason_code
                         FROM mc_tax_allocation ta
                        WHERE ta.company_id = uv.company_id
                          AND ta.journal_id = uv.journal_id
                          AND ta.is_uncertain_transaction = true
                        ORDER BY ta.created_at ASC
                        LIMIT 1
                   ) AS alloc_reason_code,
                   COALESCE((
                       SELECT SUM(ta.assessable_value)
                         FROM mc_tax_allocation ta
                        WHERE ta.company_id = uv.company_id
                          AND ta.journal_id = uv.journal_id
                   ), 0::numeric) AS computed_taxable,
                   COALESCE((
                       SELECT SUM(ta.cgst_amount + ta.sgst_utgst_amount + ta.igst_amount + ta.cess_amount)
                         FROM mc_tax_allocation ta
                        WHERE ta.company_id = uv.company_id
                          AND ta.journal_id = uv.journal_id
                   ), 0::numeric) AS computed_tax,
                   COALESCE((
                       SELECT SUM(jl.amount)
                         FROM mc_journal_line jl
                         JOIN mc_ledger l
                           ON l.company_id = jl.company_id AND l.id = jl.ledger_id
                         LEFT JOIN mc_account_group g
                           ON g.company_id = l.company_id AND g.id = l.group_id
                        WHERE jl.company_id = uv.company_id
                          AND jl.journal_id = uv.journal_id
                          AND (l.tax_type = 'GST' OR lower(COALESCE(g.name, '')) = 'duties & taxes')
                   ), 0::numeric) AS booked_tax
              FROM vw_mc_gstr_uncertain_vouchers uv
              JOIN mc_voucher_type vt
                ON vt.company_id = uv.company_id AND vt.id = uv.voucher_type_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = uv.company_id AND pl.id = uv.party_ledger_id
             WHERE uv.company_id = ?
               AND NOT (
                   uv.uncertain_category = 'UNCLASSIFIED_DUTY_LEDGER_POSTING'
                   AND EXISTS (
                       SELECT 1 FROM mc_tax_allocation acc
                        WHERE acc.company_id = uv.company_id
                          AND acc.journal_id = uv.journal_id
                          AND acc.conflict_accepted_by IS NOT NULL
                   )
               )
            """ + dateFilter + " ORDER BY uv.voucher_date ASC, uv.voucher_number ASC, uv.journal_id ASC";

        return businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> {
                String allocCode = rs.getString("alloc_reason_code");
                String viewCat = rs.getString("uncertain_category");
                String code = (allocCode != null && !allocCode.isBlank()) ? allocCode : viewCat;
                String humanReason = describeUncertainCode(code);
                BigDecimal taxable = rs.getBigDecimal("computed_taxable").setScale(scale, RoundingMode.HALF_UP);
                BigDecimal compTax = rs.getBigDecimal("computed_tax").setScale(scale, RoundingMode.HALF_UP);
                BigDecimal bookTax = rs.getBigDecimal("booked_tax").setScale(scale, RoundingMode.HALF_UP);
                BigDecimal diff = bookTax.subtract(compTax).abs();

                return new StatutoryDtos.UncertainTransactionDto(
                    rs.getObject("journal_id", UUID.class),
                    rs.getObject("voucher_date", LocalDate.class),
                    rs.getString("voucher_type_name"),
                    rs.getString("voucher_number"),
                    rs.getString("party_name"),
                    rs.getString("party_gstin"),
                    code,
                    code,
                    humanReason,
                    ExactMoneyValidator.formatExactScale(taxable, scale),
                    ExactMoneyValidator.formatExactScale(compTax, scale),
                    ExactMoneyValidator.formatExactScale(bookTax, scale),
                    ExactMoneyValidator.formatExactScale(diff, scale),
                    false,
                    null,
                    null
                );
            },
            params.toArray()
        );
    }

    public StatutoryDtos.UncertainTransactionDto acceptUncertainConflict(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId,
        StatutoryDtos.AcceptGstConflictRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TAX-CONFLICT-REASON-REQUIRED",
                "Conflict Acceptance Reason Required",
                "A non-empty reason is required to accept a GST conflict."
            );
        }
        String trimmedReason = reason.length() <= 255 ? reason : reason.substring(0, 255);

        return businessTxTemplate.execute(tx -> {
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            List<Map<String, Object>> jRows = businessJdbcTemplate.queryForList(
                """
                SELECT j.voucher_date, COALESCE(j.voucher_number, '') AS voucher_number,
                       j.party_gstin, j.company_gstin_id, vt.name AS voucher_type_name,
                       COALESCE(pl.name, '') AS party_name
                  FROM mc_journal j
                  JOIN mc_voucher_type vt
                    ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
                  LEFT JOIN mc_ledger pl
                    ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
                 WHERE j.company_id = ? AND j.id = ?
                 FOR UPDATE OF j
                """,
                companyId,
                voucherId
            );
            if (jRows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-ACC-VOUCHER-NOT-FOUND",
                    "Voucher Not Found",
                    "Voucher " + voucherId + " does not exist."
                );
            }
            Map<String, Object> jRow = jRows.getFirst();
            ensurePrincipalProjected(actor);

            int updatedAllocs = businessJdbcTemplate.update(
                """
                UPDATE mc_tax_allocation
                   SET is_uncertain_transaction = false,
                       conflict_accepted_by = ?,
                       conflict_accepted_reason = ?
                 WHERE company_id = ? AND journal_id = ?
                """,
                actor.principalId(),
                trimmedReason,
                companyId,
                voucherId
            );

            businessJdbcTemplate.update(
                """
                UPDATE mc_journal_line
                   SET is_gst_overridden = false,
                       gst_override_reason = ?
                 WHERE company_id = ? AND journal_id = ? AND is_gst_overridden = true
                """,
                trimmedReason,
                companyId,
                voucherId
            );

            LocalDate vDate = LocalDate.parse(jRow.get("voucher_date").toString());
            if (updatedAllocs == 0) {
                StatRulePackAndGstMasterService.RulePackConfig rp =
                    rulePackAndGstMasterService.loadActiveRulePack(vDate);
                UUID gstRegId = (UUID) jRow.get("company_gstin_id");
                if (gstRegId == null) {
                    gstRegId = rulePackAndGstMasterService.resolveDefaultCompanyGstinId(companyId, vDate);
                }
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_tax_allocation (
                        id, company_id, journal_id, gst_registration_id, stat_rule_pack_id,
                        rate_source_level, supply_nature, billed_qty,
                        commercial_taxable_value, apportioned_expense_value, assessable_value,
                        is_uncertain_transaction, conflict_accepted_by, conflict_accepted_reason,
                        effective_filing_period
                    ) VALUES (
                        ?, ?, ?, ?, ?,
                        'ACCOUNTING_LEDGER', 'INTRA_STATE_TAXABLE', 0,
                        0, 0, 0,
                        false, ?, ?, ?
                    )
                    """,
                    UUID.randomUUID(),
                    companyId,
                    voucherId,
                    gstRegId,
                    rp.id(),
                    actor.principalId(),
                    trimmedReason,
                    FILING_PERIOD_FMT.format(vDate)
                );
            }

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "GST_CONFLICT_ACCEPT",
                "VOUCHER",
                voucherId.toString(),
                "SUCCESS",
                null,
                Map.of("reason", trimmedReason)
            );

            String zeroStr = ExactMoneyValidator.formatExactScale(BigDecimal.ZERO, scale);
            return new StatutoryDtos.UncertainTransactionDto(
                voucherId,
                vDate,
                (String) jRow.get("voucher_type_name"),
                (String) jRow.get("voucher_number"),
                (String) jRow.get("party_name"),
                (String) jRow.get("party_gstin"),
                "ACCEPTED",
                "ACCEPTED",
                trimmedReason,
                zeroStr,
                zeroStr,
                zeroStr,
                zeroStr,
                true,
                actor.principalId(),
                trimmedReason
            );
        });
    }

    // -------------------------------------------------------------------------
    // Core 6-Tier GST Inheritance, Tobacco MRP & Slab Rounding Implementation
    // -------------------------------------------------------------------------

    private void persistGstAllocationsIfApplicable(
        UUID companyId,
        UUID journalId,
        StoredJournalHeader hdr,
        List<StoredJournalLine> lines,
        List<StoredInvLine> invLines,
        Map<UUID, LedgerTaxMeta> ledgerMap,
        LedgerTaxMeta partyMeta,
        String effectivePartyGstin,
        String companyState,
        String posState,
        UUID resolvedCompanyGstinId,
        StatRulePackAndGstMasterService.GstRegContext gstReg,
        StatRulePackAndGstMasterService.RulePackConfig rulePack
    ) {
        boolean isPrimaryGstVoucher = GST_VOUCHER_CATEGORIES.contains(hdr.baseCategory());
        boolean hasBookedGstDutyLine = lines.stream().anyMatch(jl -> {
            LedgerTaxMeta lm = ledgerMap.get(jl.ledgerId());
            return lm != null && "GST".equals(lm.taxType());
        });
        if (!isPrimaryGstVoucher && !hdr.isReverseCharge() && !hasBookedGstDutyLine) {
            return;
        }

        Set<UUID> itemIds = new LinkedHashSet<>();
        for (StoredInvLine il : invLines) {
            itemIds.add(il.stockItemId());
        }
        Map<UUID, StockItemTaxMeta> itemMap = loadStockItemTaxMeta(companyId, itemIds);
        Map<UUID, GroupTaxMeta> accountGroupMap = loadAllAccountGroupTaxMeta(companyId);
        Map<UUID, StockGroupTaxMeta> stockGroupMap = loadAllStockGroupTaxMeta(companyId);

        String exportType = resolveEffectiveExportType(hdr.exportType(), partyMeta);

        List<CandidateTaxableUnit> units = buildStoredTaxableUnits(
            hdr,
            lines,
            invLines,
            ledgerMap,
            itemMap,
            accountGroupMap,
            stockGroupMap,
            gstReg,
            rulePack,
            companyState,
            posState,
            exportType
        );
        if (units.isEmpty()) {
            return;
        }

        ComputedVoucherGstResult computed = apportionAndRoundSlabs(units, hdr.currencyScale(), rulePack);

        // Sum booked GST duty lines on voucher
        BigDecimal bookedCgst = BigDecimal.ZERO;
        BigDecimal bookedSgst = BigDecimal.ZERO;
        BigDecimal bookedIgst = BigDecimal.ZERO;
        BigDecimal bookedCess = BigDecimal.ZERO;
        boolean explicitLineOverride = false;
        for (StoredJournalLine jl : lines) {
            if (jl.isGstOverridden()) {
                explicitLineOverride = true;
            }
            LedgerTaxMeta lm = ledgerMap.get(jl.ledgerId());
            if (lm != null && "GST".equals(lm.taxType())) {
                String dh = lm.dutyHead() == null ? "" : lm.dutyHead();
                switch (dh) {
                    case "CGST" -> bookedCgst = bookedCgst.add(jl.amount());
                    case "SGST_UTGST" -> bookedSgst = bookedSgst.add(jl.amount());
                    case "IGST" -> bookedIgst = bookedIgst.add(jl.amount());
                    case "CESS" -> bookedCess = bookedCess.add(jl.amount());
                    default -> {
                    }
                }
            }
        }
        BigDecimal bookedTotalTax = bookedCgst.add(bookedSgst).add(bookedIgst).add(bookedCess);

        boolean hasAnyGstActivity = computed.totalTax().signum() > 0
            || bookedTotalTax.signum() > 0
            || explicitLineOverride
            || hdr.isReverseCharge()
            || (gstReg.gstin() != null && !gstReg.gstin().isBlank() && effectivePartyGstin != null);

        if (!hasAnyGstActivity) {
            return;
        }

        // Evaluate uncertain transaction flags
        String voucherLevelUncertainCode = null;
        if (!hdr.isReverseCharge()) {
            boolean headMismatch = bookedCgst.subtract(computed.totalCgst()).abs().compareTo(ONE_PAISA) >= 0
                || bookedSgst.subtract(computed.totalSgst()).abs().compareTo(ONE_PAISA) >= 0
                || bookedIgst.subtract(computed.totalIgst()).abs().compareTo(ONE_PAISA) >= 0
                || bookedCess.subtract(computed.totalCess()).abs().compareTo(ONE_PAISA) >= 0;
            if (explicitLineOverride || headMismatch) {
                voucherLevelUncertainCode = "TAX_AMOUNT_MISMATCH";
            }
        }
        if (voucherLevelUncertainCode == null
            && partyMeta != null
            && REGISTERED_PARTY_TYPES.contains(partyMeta.partyRegistrationType())
            && (effectivePartyGstin == null || effectivePartyGstin.isBlank())) {
            voucherLevelUncertainCode = "MISSING_PARTY_GSTIN";
        }

        boolean isInwardForItc = isVoucherInwardForItc(hdr.baseCategory(), hdr.isReverseCharge(), lines, ledgerMap);
        String filingPeriod = FILING_PERIOD_FMT.format(hdr.effectiveDate());

        for (ComputedTaxLine cl : computed.lines()) {
            String lineReason = voucherLevelUncertainCode;
            if (lineReason == null && cl.gstRatePct().signum() > 0) {
                if (!isValidHsnCode(cl.hsnSacCode(), rulePack.validHsnDigitLengths())) {
                    lineReason = "INVALID_HSN_LENGTH";
                }
            }
            boolean isUncertain = lineReason != null;
            String itcEligibility = resolveItcEligibility(isInwardForItc, cl, ledgerMap, accountGroupMap);

            BigDecimal halfRate = cl.gstRatePct().divide(TWO, 6, RoundingMode.HALF_UP);
            BigDecimal cgstRate = cl.cgstAmount().signum() > 0 || isIntraSupply(cl.supplyNature(), companyState, posState)
                ? (isZeroTaxSupply(cl.supplyNature()) ? BigDecimal.ZERO : halfRate)
                : BigDecimal.ZERO;
            BigDecimal sgstRate = cgstRate;
            BigDecimal igstRate = (cgstRate.signum() == 0 && !isZeroTaxSupply(cl.supplyNature()))
                ? cl.gstRatePct()
                : BigDecimal.ZERO;

            String uqcToPersist = resolveUqcForPersistence(cl.hsnSacCode(), cl.uqcCode(), rulePack);

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_tax_allocation (
                    id, company_id, journal_id, journal_line_id, inventory_allocation_id,
                    gst_registration_id, stat_rule_pack_id, rate_source_level, supply_nature,
                    hsn_sac_code, gst_uqc_code, billed_qty,
                    commercial_taxable_value, apportioned_expense_value, deemed_mrp_taxable_value,
                    assessable_value,
                    cgst_rate_pct, cgst_amount, sgst_utgst_rate_pct, sgst_utgst_amount,
                    igst_rate_pct, igst_amount, cess_rate_pct, cess_per_unit, cess_amount,
                    is_rcm, itc_eligibility, is_uncertain_transaction, uncertain_reason_code,
                    effective_filing_period
                ) VALUES (
                    ?, ?, ?, NULL, NULL,
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, 0, ?,
                    ?, ?, ?, ?,
                    ?
                )
                """,
                UUID.randomUUID(),
                companyId,
                journalId,
                resolvedCompanyGstinId,
                rulePack.id(),
                cl.rateSourceLevel(),
                normalizeDbSupplyNature(cl.supplyNature()),
                cl.hsnSacCode(),
                uqcToPersist,
                cl.billedQty(),
                cl.commercialTaxableValue(),
                cl.apportionedExpenseValue(),
                cl.deemedMrpTaxableValue(),
                cl.assessableValue(),
                cgstRate,
                cl.cgstAmount(),
                sgstRate,
                cl.sgstAmount(),
                igstRate,
                cl.igstAmount(),
                cl.cessRatePct(),
                cl.cessAmount(),
                hdr.isReverseCharge(),
                itcEligibility,
                isUncertain,
                lineReason,
                filingPeriod
            );
        }
    }

    private void persistTdsDeductionsIfApplicable(
        UUID companyId,
        UUID journalId,
        StoredJournalHeader hdr,
        List<StoredJournalLine> lines,
        Map<UUID, LedgerTaxMeta> ledgerMap,
        UUID effectivePartyLedgerId,
        StatRulePackAndGstMasterService.RulePackConfig rulePack
    ) {
        List<StoredJournalLine> tdsLines = lines.stream()
            .filter(jl -> "CR".equals(jl.side()))
            .filter(jl -> {
                LedgerTaxMeta lm = ledgerMap.get(jl.ledgerId());
                return lm != null && ("TDS".equals(lm.taxType()) || "TCS".equals(lm.taxType())
                    || "TDS_SEC".equals(lm.dutyHead()) || "TCS_SEC".equals(lm.dutyHead()));
            })
            .toList();
        if (tdsLines.isEmpty() || effectivePartyLedgerId == null) {
            return;
        }

        LedgerTaxMeta partyMeta = ledgerMap.get(effectivePartyLedgerId);
        List<TdsNatureRow> companyNatures = loadTdsNatureRows(companyId);
        if (companyNatures.isEmpty()) {
            return;
        }

        BigDecimal grossDrExpense = BigDecimal.ZERO;
        String expenseTdsCode = null;
        for (StoredJournalLine jl : lines) {
            LedgerTaxMeta lm = ledgerMap.get(jl.ledgerId());
            if ("DR".equals(jl.side())
                && lm != null
                && !"GST".equals(lm.taxType())
                && !"TDS".equals(lm.taxType())
                && !"TCS".equals(lm.taxType())) {
                grossDrExpense = grossDrExpense.add(jl.amount());
                if (expenseTdsCode == null && lm.tdsNatureCode() != null && !lm.tdsNatureCode().isBlank()) {
                    expenseTdsCode = lm.tdsNatureCode();
                }
            }
        }
        if (grossDrExpense.signum() <= 0) {
            return;
        }

        for (StoredJournalLine tdsLine : tdsLines) {
            LedgerTaxMeta tdsLedgerMeta = ledgerMap.get(tdsLine.ledgerId());
            String desiredSection = firstNonBlank(
                tdsLedgerMeta != null ? tdsLedgerMeta.tdsNatureCode() : null,
                partyMeta != null ? partyMeta.tdsNatureCode() : null,
                expenseTdsCode,
                inferSectionFromName(tdsLedgerMeta != null ? tdsLedgerMeta.name() : "")
            );
            TdsNatureRow nature = matchTdsNature(companyNatures, desiredSection);

            boolean hasPan = partyMeta != null && partyMeta.hasPanSiv();
            String activeLowerCert = resolveActiveLowerCert(partyMeta, hdr.effectiveDate());
            boolean noPanHigherRate = !hasPan && activeLowerCert == null;

            BigDecimal tdsAmt = tdsLine.amount();
            BigDecimal computedRate = tdsLine.ratePct() != null
                ? tdsLine.ratePct()
                : tdsAmt.multiply(HUNDRED).divide(grossDrExpense, 4, RoundingMode.HALF_UP);
            if (noPanHigherRate && computedRate.compareTo(BigDecimal.ZERO) == 0) {
                computedRate = nature.noPanRatePct() != null ? nature.noPanRatePct() : rulePack.defaultNoPanRatePct();
            }

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_tds_deduction (
                    id, company_id, journal_id, journal_line_id,
                    party_ledger_id, tds_ledger_id, tds_nature_id, challan_id,
                    deduction_date, gross_credited_amount, applied_rate_pct,
                    is_no_pan_higher_rate, lower_deduction_cert_no,
                    tds_amount, surcharge_amount, cess_amount, total_tax_deducted
                ) VALUES (
                    ?, ?, ?, NULL,
                    ?, ?, ?, NULL,
                    ?, ?, ?,
                    ?, ?,
                    ?, 0, 0, ?
                )
                """,
                UUID.randomUUID(),
                companyId,
                journalId,
                effectivePartyLedgerId,
                tdsLine.ledgerId(),
                nature.id(),
                hdr.effectiveDate(),
                grossDrExpense,
                computedRate,
                noPanHigherRate,
                activeLowerCert,
                tdsAmt,
                tdsAmt
            );
        }
    }

    private List<CandidateTaxableUnit> buildPreviewTaxableUnits(
        VoucherDtos.UpsertVoucherRequest req,
        int scale,
        Map<UUID, LedgerTaxMeta> ledgerMap,
        Map<UUID, StockItemTaxMeta> itemMap,
        Map<UUID, GroupTaxMeta> accountGroupMap,
        Map<UUID, StockGroupTaxMeta> stockGroupMap,
        StatRulePackAndGstMasterService.GstRegContext gstReg,
        StatRulePackAndGstMasterService.RulePackConfig rulePack,
        String companyState,
        String posState,
        boolean isRcm,
        String exportType
    ) {
        List<ApportionExpenseEntry> gstExpenses = new ArrayList<>();
        Map<Integer, BigDecimal> lineRateOverrides = new HashMap<>();
        if (req.lines() != null) {
            int idx = 1;
            for (VoucherDtos.VoucherLineRequest lr : req.lines()) {
                int ln = lr.lineNo() != null ? lr.lineNo() : idx;
                if (lr.ratePct() != null && !lr.ratePct().isBlank()
                    && !"TAX".equalsIgnoreCase(lr.lineRole() == null ? "" : lr.lineRole())) {
                    lineRateOverrides.put(ln, new BigDecimal(lr.ratePct().trim()));
                }
                LedgerTaxMeta lm = lr.ledgerId() == null ? null : ledgerMap.get(lr.ledgerId());
                if (lm != null && isGstAppropriateLedger(lm) && lr.amount() != null && !lr.amount().isBlank()) {
                    BigDecimal expAmt = ExactMoneyValidator.parseAndValidateExactScale(lr.amount(), scale, "amount");
                    gstExpenses.add(new ApportionExpenseEntry(expAmt, lm.appropriationBasis()));
                }
                idx++;
            }
        }

        List<CandidateTaxableUnit> units = new ArrayList<>();
        boolean hasInv = req.inventoryLines() != null && !req.inventoryLines().isEmpty();
        if (hasInv) {
            int idx = 1;
            for (VoucherDtos.VoucherInventoryLineDto il : req.inventoryLines()) {
                int ln = il.lineNo() != null ? il.lineNo() : idx;
                StockItemTaxMeta item = itemMap.get(il.stockItemId());
                UUID acctLedgerId = il.accountingLedgerId() != null ? il.accountingLedgerId() : req.accountingLedgerId();
                LedgerTaxMeta acctLedger = acctLedgerId == null ? null : ledgerMap.get(acctLedgerId);
                BigDecimal qty = (il.billedQty() != null && !il.billedQty().isBlank())
                    ? new BigDecimal(il.billedQty().trim())
                    : ((il.actualQty() != null && !il.actualQty().isBlank())
                        ? new BigDecimal(il.actualQty().trim())
                        : BigDecimal.ONE);
                BigDecimal commAmt = ExactMoneyValidator.parseAndValidateExactScale(
                    il.amount(),
                    scale,
                    "inventoryLines.amount"
                );
                BigDecimal reqMrp = (il.declaredMrp() == null || il.declaredMrp().isBlank())
                    ? null
                    : new BigDecimal(il.declaredMrp().trim());

                units.add(
                    resolveSixTierUnit(
                        ln,
                        item != null ? item.name() : (il.stockItemName() != null ? il.stockItemName() : "Item"),
                        qty,
                        commAmt,
                        reqMrp,
                        lineRateOverrides.get(ln),
                        acctLedger,
                        item,
                        accountGroupMap,
                        stockGroupMap,
                        gstReg,
                        rulePack,
                        companyState,
                        posState,
                        isRcm,
                        exportType,
                        scale
                    )
                );
                idx++;
            }
            attachExpensesToUnits(units, gstExpenses, scale);
            return units;
        }

        // Pure Accounting Invoice / Expense GST lines
        if (req.lines() != null) {
            int idx = 1;
            for (VoucherDtos.VoucherLineRequest lr : req.lines()) {
                int ln = lr.lineNo() != null ? lr.lineNo() : idx;
                LedgerTaxMeta lm = lr.ledgerId() == null ? null : ledgerMap.get(lr.ledgerId());
                if (lm != null && isCommercialAccountLine(lr.ledgerId(), req.partyLedgerId(), lr.lineRole(), lm)) {
                    BigDecimal amt = ExactMoneyValidator.parseAndValidateExactScale(lr.amount(), scale, "lines.amount");
                    BigDecimal overridePct = (lr.ratePct() != null && !lr.ratePct().isBlank())
                        ? new BigDecimal(lr.ratePct().trim())
                        : null;
                    units.add(
                        resolveSixTierUnit(
                            ln,
                            lm.name(),
                            BigDecimal.ZERO,
                            amt,
                            null,
                            overridePct,
                            lm,
                            null,
                            accountGroupMap,
                            stockGroupMap,
                            gstReg,
                            rulePack,
                            companyState,
                            posState,
                            isRcm,
                            exportType,
                            scale
                        )
                    );
                }
                idx++;
            }
        }
        return units;
    }

    private List<CandidateTaxableUnit> buildStoredTaxableUnits(
        StoredJournalHeader hdr,
        List<StoredJournalLine> lines,
        List<StoredInvLine> invLines,
        Map<UUID, LedgerTaxMeta> ledgerMap,
        Map<UUID, StockItemTaxMeta> itemMap,
        Map<UUID, GroupTaxMeta> accountGroupMap,
        Map<UUID, StockGroupTaxMeta> stockGroupMap,
        StatRulePackAndGstMasterService.GstRegContext gstReg,
        StatRulePackAndGstMasterService.RulePackConfig rulePack,
        String companyState,
        String posState,
        String exportType
    ) {
        int scale = hdr.currencyScale();
        if (!invLines.isEmpty()) {
            List<ApportionExpenseEntry> gstExpenses = new ArrayList<>();
            LedgerTaxMeta fallbackItemAcctLedger = hdr.accountingLedgerId() == null
                ? null
                : ledgerMap.get(hdr.accountingLedgerId());
            for (StoredJournalLine jl : lines) {
                LedgerTaxMeta lm = ledgerMap.get(jl.ledgerId());
                if (lm != null && "ITEM_ACCOUNTING".equals(jl.lineRole()) && fallbackItemAcctLedger == null) {
                    fallbackItemAcctLedger = lm;
                }
                if (lm != null && isGstAppropriateLedger(lm) && !"ITEM_ACCOUNTING".equals(jl.lineRole())) {
                    gstExpenses.add(new ApportionExpenseEntry(jl.amount(), lm.appropriationBasis()));
                }
            }

            List<CandidateTaxableUnit> units = new ArrayList<>();
            for (StoredInvLine il : invLines) {
                StockItemTaxMeta item = itemMap.get(il.stockItemId());
                UUID itemAcctId = findItemDefaultLedgerId(hdr.baseCategory(), item);
                LedgerTaxMeta effectiveLedger = itemAcctId != null && ledgerMap.containsKey(itemAcctId)
                    ? ledgerMap.get(itemAcctId)
                    : fallbackItemAcctLedger;

                units.add(
                    resolveSixTierUnit(
                        il.lineNo(),
                        item != null ? item.name() : "Item",
                        il.billedQty(),
                        il.amount(),
                        il.declaredMrp(),
                        null,
                        effectiveLedger,
                        item,
                        accountGroupMap,
                        stockGroupMap,
                        gstReg,
                        rulePack,
                        companyState,
                        posState,
                        hdr.isReverseCharge(),
                        exportType,
                        scale
                    )
                );
            }
            attachExpensesToUnits(units, gstExpenses, scale);
            return units;
        }

        List<CandidateTaxableUnit> units = new ArrayList<>();
        UUID effectivePartyId = resolveEffectivePartyLedgerId(hdr.partyLedgerId(), lines, ledgerMap);
        for (StoredJournalLine jl : lines) {
            LedgerTaxMeta lm = ledgerMap.get(jl.ledgerId());
            if (lm != null && isCommercialAccountLine(jl.ledgerId(), effectivePartyId, jl.lineRole(), lm)) {
                units.add(
                    resolveSixTierUnit(
                        jl.lineNo(),
                        lm.name(),
                        BigDecimal.ZERO,
                        jl.amount(),
                        null,
                        jl.ratePct(),
                        lm,
                        null,
                        accountGroupMap,
                        stockGroupMap,
                        gstReg,
                        rulePack,
                        companyState,
                        posState,
                        hdr.isReverseCharge(),
                        exportType,
                        scale
                    )
                );
            }
        }
        return units;
    }

    private CandidateTaxableUnit resolveSixTierUnit(
        int lineNo,
        String description,
        BigDecimal billedQty,
        BigDecimal commercialAmount,
        BigDecimal explicitMrp,
        BigDecimal lineRateOverride,
        LedgerTaxMeta accountingLedger,
        StockItemTaxMeta stockItem,
        Map<UUID, GroupTaxMeta> accountGroupMap,
        Map<UUID, StockGroupTaxMeta> stockGroupMap,
        StatRulePackAndGstMasterService.GstRegContext gstReg,
        StatRulePackAndGstMasterService.RulePackConfig rulePack,
        String companyState,
        String posState,
        boolean isRcm,
        String exportType,
        int scale
    ) {
        BigDecimal resolvedRate = null;
        String resolvedSource = "COMPANY_GST_REGISTRATION";
        BigDecimal resolvedCess = BigDecimal.ZERO;
        String resolvedHsn = null;

        for (String tier : rulePack.inheritanceHierarchy()) {
            switch (tier) {
                case "VOUCHER_LINE_OVERRIDE" -> {
                    if (resolvedRate == null && lineRateOverride != null) {
                        resolvedRate = lineRateOverride;
                        resolvedSource = "VOUCHER_LINE_OVERRIDE";
                    }
                }
                case "ACCOUNTING_LEDGER" -> {
                    if (accountingLedger != null) {
                        if (resolvedRate == null && accountingLedger.gstRatePct() != null) {
                            resolvedRate = accountingLedger.gstRatePct();
                            resolvedSource = "ACCOUNTING_LEDGER";
                            if (accountingLedger.cessRatePct() != null) {
                                resolvedCess = accountingLedger.cessRatePct();
                            }
                        }
                        if (resolvedHsn == null && notBlank(accountingLedger.hsnSacCode())) {
                            resolvedHsn = accountingLedger.hsnSacCode();
                        }
                    }
                }
                case "STOCK_ITEM" -> {
                    if (stockItem != null) {
                        if (resolvedRate == null && stockItem.gstRatePct() != null) {
                            resolvedRate = stockItem.gstRatePct();
                            resolvedSource = "STOCK_ITEM";
                            if (stockItem.cessRatePct() != null) {
                                resolvedCess = stockItem.cessRatePct();
                            }
                        }
                        if (resolvedHsn == null && notBlank(stockItem.hsnSacCode())) {
                            resolvedHsn = stockItem.hsnSacCode();
                        }
                    }
                }
                case "STOCK_GROUP" -> {
                    if (stockItem != null && stockItem.stockGroupId() != null) {
                        StockGroupTaxMeta sg = findNearestStockGroupWithGst(stockItem.stockGroupId(), stockGroupMap);
                        if (sg != null) {
                            if (resolvedRate == null && sg.gstRatePct() != null) {
                                resolvedRate = sg.gstRatePct();
                                resolvedSource = "STOCK_GROUP";
                                if (sg.cessRatePct() != null) {
                                    resolvedCess = sg.cessRatePct();
                                }
                            }
                            if (resolvedHsn == null) {
                                String sgHsn = findNearestStockGroupHsn(stockItem.stockGroupId(), stockGroupMap);
                                if (notBlank(sgHsn)) {
                                    resolvedHsn = sgHsn;
                                }
                            }
                        } else if (resolvedHsn == null) {
                            String sgHsn = findNearestStockGroupHsn(stockItem.stockGroupId(), stockGroupMap);
                            if (notBlank(sgHsn)) {
                                resolvedHsn = sgHsn;
                            }
                        }
                    }
                }
                case "ACCOUNT_GROUP" -> {
                    if (accountingLedger != null && accountingLedger.groupId() != null) {
                        GroupTaxMeta ag = findNearestAccountGroupWithGst(accountingLedger.groupId(), accountGroupMap);
                        if (ag != null && resolvedRate == null && ag.gstRatePct() != null) {
                            resolvedRate = ag.gstRatePct();
                            resolvedSource = "ACCOUNT_GROUP";
                        }
                        if (resolvedHsn == null) {
                            String agHsn = findNearestAccountGroupHsn(accountingLedger.groupId(), accountGroupMap);
                            if (notBlank(agHsn)) {
                                resolvedHsn = agHsn;
                            }
                        }
                    }
                }
                case "COMPANY_GST_REGISTRATION" -> {
                    if (gstReg != null) {
                        if (resolvedRate == null && gstReg.defaultGstRatePct() != null) {
                            resolvedRate = gstReg.defaultGstRatePct();
                            resolvedSource = "COMPANY_GST_REGISTRATION";
                        }
                        if (resolvedHsn == null && notBlank(gstReg.defaultHsnSacCode())) {
                            resolvedHsn = gstReg.defaultHsnSacCode();
                        }
                    }
                }
                default -> {
                }
            }
        }

        if (resolvedRate == null) {
            resolvedRate = BigDecimal.ZERO;
            if (stockItem != null) {
                resolvedSource = "STOCK_ITEM";
            } else if (accountingLedger != null) {
                resolvedSource = "ACCOUNTING_LEDGER";
            }
        }

        String applicability = firstNonBlank(
            stockItem != null ? stockItem.gstApplicability() : null,
            accountingLedger != null ? accountingLedger.gstApplicability() : null,
            "APPLICABLE"
        );
        String supplyNature = resolveDetailedSupplyNature(
            applicability,
            companyState,
            posState,
            isRcm,
            exportType
        );
        if (isZeroTaxSupply(supplyNature) && !"EXPORT_WPAY".equals(supplyNature) && !"SEZ_WPAY".equals(supplyNature)) {
            resolvedRate = BigDecimal.ZERO;
            resolvedCess = BigDecimal.ZERO;
        }

        boolean isDeemedMrp = false;
        BigDecimal effectiveMrp = null;
        BigDecimal deemedMrpTaxable = null;
        if (stockItem != null && stockItem.isTobaccoMrpItem() && rulePack.tobaccoDeemedMrpEnabled()) {
            effectiveMrp = explicitMrp != null && explicitMrp.signum() > 0
                ? explicitMrp
                : stockItem.mrpPerUnit();
            if (effectiveMrp == null || effectiveMrp.signum() <= 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    rulePack.missingMrpErrorCode(),
                    "Declared Retail Sale Price (MRP) Mandatory",
                    "Rel 7.2 Tobacco / Pan Masala item '" + stockItem.name()
                        + "' requires a positive declaredMrp or stock item mrpPerUnit."
                );
            }
            isDeemedMrp = true;
            BigDecimal divisor = HUNDRED.add(resolvedRate);
            deemedMrpTaxable = effectiveMrp
                .multiply(billedQty)
                .multiply(HUNDRED)
                .divide(divisor, scale, rulePack.slabRoundingMode());
        }

        String uqc = stockItem != null && notBlank(stockItem.uqcCode()) ? stockItem.uqcCode() : "NOS";
        String typeOfSupply = stockItem != null ? stockItem.gstTypeOfSupply() : "SERVICES";
        UUID ledgerId = accountingLedger != null ? accountingLedger.id() : null;

        return new CandidateTaxableUnit(
            lineNo,
            description,
            ledgerId,
            resolvedHsn,
            uqc,
            typeOfSupply,
            billedQty,
            commercialAmount,
            BigDecimal.ZERO,
            effectiveMrp,
            deemedMrpTaxable,
            isDeemedMrp,
            resolvedSource,
            supplyNature,
            resolvedRate,
            resolvedCess
        );
    }

    private static void attachExpensesToUnits(
        List<CandidateTaxableUnit> units,
        List<ApportionExpenseEntry> expenses,
        int scale
    ) {
        if (units.isEmpty() || expenses.isEmpty()) {
            return;
        }
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (CandidateTaxableUnit u : units) {
            totalValue = totalValue.add(u.commercialTaxableValue());
            totalQty = totalQty.add(u.billedQty());
        }

        BigDecimal[] apportioned = new BigDecimal[units.size()];
        for (int i = 0; i < units.size(); i++) {
            apportioned[i] = BigDecimal.ZERO;
        }

        for (ApportionExpenseEntry exp : expenses) {
            boolean byQty = "BY_QTY".equalsIgnoreCase(exp.basis()) && totalQty.signum() > 0;
            BigDecimal denom = byQty ? totalQty : totalValue;
            if (denom.signum() <= 0) {
                apportioned[0] = apportioned[0].add(exp.amount());
                continue;
            }
            BigDecimal allocatedSoFar = BigDecimal.ZERO;
            for (int i = 0; i < units.size(); i++) {
                if (i == units.size() - 1) {
                    apportioned[i] = apportioned[i].add(exp.amount().subtract(allocatedSoFar));
                } else {
                    BigDecimal weight = byQty ? units.get(i).billedQty() : units.get(i).commercialTaxableValue();
                    BigDecimal share = exp.amount().multiply(weight).divide(denom, scale, RoundingMode.HALF_UP);
                    apportioned[i] = apportioned[i].add(share);
                    allocatedSoFar = allocatedSoFar.add(share);
                }
            }
        }

        for (int i = 0; i < units.size(); i++) {
            CandidateTaxableUnit u = units.get(i);
            units.set(
                i,
                new CandidateTaxableUnit(
                    u.lineNo(),
                    u.description(),
                    u.ledgerId(),
                    u.hsnSacCode(),
                    u.uqcCode(),
                    u.typeOfSupply(),
                    u.billedQty(),
                    u.commercialTaxableValue(),
                    apportioned[i],
                    u.declaredMrp(),
                    u.deemedMrpTaxableValue(),
                    u.isDeemedMrpValuation(),
                    u.rateSourceLevel(),
                    u.supplyNature(),
                    u.gstRatePct(),
                    u.cessRatePct()
                )
            );
        }
    }

    private ComputedVoucherGstResult apportionAndRoundSlabs(
        List<CandidateTaxableUnit> units,
        int scale,
        StatRulePackAndGstMasterService.RulePackConfig rulePack
    ) {
        RoundingMode rm = rulePack.slabRoundingMode();
        Map<SlabKey, List<Integer>> slabUnitIndices = new LinkedHashMap<>();
        BigDecimal[] assessables = new BigDecimal[units.size()];

        for (int i = 0; i < units.size(); i++) {
            CandidateTaxableUnit u = units.get(i);
            BigDecimal baseTaxable = u.isDeemedMrpValuation() && u.deemedMrpTaxableValue() != null
                ? u.deemedMrpTaxableValue()
                : u.commercialTaxableValue();
            assessables[i] = baseTaxable.add(u.apportionedExpenseValue());
            SlabKey key = new SlabKey(
                u.gstRatePct().stripTrailingZeros(),
                u.cessRatePct().stripTrailingZeros(),
                u.supplyNature()
            );
            slabUnitIndices.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        BigDecimal[] lineCgst = new BigDecimal[units.size()];
        BigDecimal[] lineSgst = new BigDecimal[units.size()];
        BigDecimal[] lineIgst = new BigDecimal[units.size()];
        BigDecimal[] lineCess = new BigDecimal[units.size()];

        List<ComputedSlabSummary> slabSummaries = new ArrayList<>();

        for (Map.Entry<SlabKey, List<Integer>> entry : slabUnitIndices.entrySet()) {
            SlabKey key = entry.getKey();
            List<Integer> idxs = entry.getValue();
            BigDecimal slabAssessable = BigDecimal.ZERO;
            for (int idx : idxs) {
                slabAssessable = slabAssessable.add(assessables[idx]);
            }

            BigDecimal slabCgst = BigDecimal.ZERO;
            BigDecimal slabSgst = BigDecimal.ZERO;
            BigDecimal slabIgst = BigDecimal.ZERO;
            BigDecimal slabCess = BigDecimal.ZERO;

            if (!isZeroTaxSupply(key.supplyNature()) && key.gstRatePct().signum() > 0) {
                if ("INTRA_STATE_TAXABLE".equals(key.supplyNature())
                    || ("REVERSE_CHARGE_INWARD".equals(key.supplyNature()) && isCurrentIntraFlag(units.get(idxs.getFirst())))) {
                    BigDecimal halfRate = key.gstRatePct().divide(TWO, 6, RoundingMode.HALF_UP);
                    slabCgst = slabAssessable.multiply(halfRate).divide(HUNDRED, scale, rm);
                    slabSgst = slabAssessable.multiply(halfRate).divide(HUNDRED, scale, rm);
                } else {
                    slabIgst = slabAssessable.multiply(key.gstRatePct()).divide(HUNDRED, scale, rm);
                }
            }
            if (!isZeroTaxSupply(key.supplyNature()) && key.cessRatePct().signum() > 0) {
                slabCess = slabAssessable.multiply(key.cessRatePct()).divide(HUNDRED, scale, rm);
            }

            distributeSlabTaxAcrossLines(idxs, assessables, slabAssessable, slabCgst, lineCgst, scale, rm);
            distributeSlabTaxAcrossLines(idxs, assessables, slabAssessable, slabSgst, lineSgst, scale, rm);
            distributeSlabTaxAcrossLines(idxs, assessables, slabAssessable, slabIgst, lineIgst, scale, rm);
            distributeSlabTaxAcrossLines(idxs, assessables, slabAssessable, slabCess, lineCess, scale, rm);

            slabSummaries.add(
                new ComputedSlabSummary(
                    key.gstRatePct(),
                    slabAssessable,
                    slabCgst,
                    slabSgst,
                    slabIgst,
                    slabCess
                )
            );
        }

        List<ComputedTaxLine> outLines = new ArrayList<>(units.size());
        BigDecimal totalAssessable = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        BigDecimal totalIgst = BigDecimal.ZERO;
        BigDecimal totalCess = BigDecimal.ZERO;

        for (int i = 0; i < units.size(); i++) {
            CandidateTaxableUnit u = units.get(i);
            totalAssessable = totalAssessable.add(assessables[i]);
            totalCgst = totalCgst.add(lineCgst[i]);
            totalSgst = totalSgst.add(lineSgst[i]);
            totalIgst = totalIgst.add(lineIgst[i]);
            totalCess = totalCess.add(lineCess[i]);

            outLines.add(
                new ComputedTaxLine(
                    u.lineNo(),
                    u.description(),
                    u.ledgerId(),
                    u.hsnSacCode(),
                    u.uqcCode(),
                    u.typeOfSupply(),
                    u.billedQty(),
                    u.commercialTaxableValue(),
                    u.apportionedExpenseValue(),
                    u.declaredMrp(),
                    u.deemedMrpTaxableValue(),
                    u.isDeemedMrpValuation(),
                    assessables[i],
                    u.rateSourceLevel(),
                    u.supplyNature(),
                    u.gstRatePct(),
                    u.cessRatePct(),
                    lineCgst[i],
                    lineSgst[i],
                    lineIgst[i],
                    lineCess[i]
                )
            );
        }

        slabSummaries.sort(Comparator.comparing(ComputedSlabSummary::gstRatePct));
        BigDecimal totalTax = totalCgst.add(totalSgst).add(totalIgst).add(totalCess);
        return new ComputedVoucherGstResult(
            outLines,
            slabSummaries,
            totalAssessable,
            totalCgst,
            totalSgst,
            totalIgst,
            totalCess,
            totalTax
        );
    }

    private static void distributeSlabTaxAcrossLines(
        List<Integer> idxs,
        BigDecimal[] assessables,
        BigDecimal slabAssessable,
        BigDecimal slabTaxTotal,
        BigDecimal[] target,
        int scale,
        RoundingMode rm
    ) {
        if (slabTaxTotal.signum() == 0 || slabAssessable.signum() == 0) {
            for (int idx : idxs) {
                target[idx] = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
            }
            return;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (int pos = 0; pos < idxs.size(); pos++) {
            int idx = idxs.get(pos);
            if (pos == idxs.size() - 1) {
                target[idx] = slabTaxTotal.subtract(allocated).setScale(scale, RoundingMode.UNNECESSARY);
            } else {
                BigDecimal share = slabTaxTotal.multiply(assessables[idx]).divide(slabAssessable, scale, rm);
                target[idx] = share;
                allocated = allocated.add(share);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Database Metadata Lookup Helpers
    // -------------------------------------------------------------------------

    private record StoredJournalHeader(
        UUID id,
        LocalDate voucherDate,
        LocalDate effectiveDate,
        String status,
        String postingNature,
        UUID partyLedgerId,
        UUID accountingLedgerId,
        String placeOfSupplyState,
        UUID companyGstinId,
        String partyGstin,
        String partyStateCode,
        boolean isReverseCharge,
        String exportType,
        UUID reversalOfJournalId,
        String baseCategory,
        int currencyScale
    ) {
    }

    private record StoredJournalLine(
        UUID id,
        int lineNo,
        String lineRole,
        UUID ledgerId,
        String side,
        BigDecimal amount,
        BigDecimal ratePct,
        boolean isGstOverridden
    ) {
    }

    private record StoredInvLine(
        UUID id,
        int lineNo,
        UUID stockItemId,
        BigDecimal billedQty,
        BigDecimal amount,
        BigDecimal declaredMrp
    ) {
    }

    private record LedgerTaxMeta(
        UUID id,
        String name,
        UUID groupId,
        String groupName,
        String groupNature,
        String taxType,
        String dutyHead,
        String gstApplicability,
        String hsnSacCode,
        BigDecimal gstRatePct,
        BigDecimal cessRatePct,
        String appropriateFor,
        String appropriationBasis,
        String partyRegistrationType,
        String gstin,
        String stateCode,
        boolean hasPanSiv,
        boolean tdsApplicable,
        String tdsNatureCode,
        String tdsDeducteeType,
        String lowerTdsCertNo,
        BigDecimal lowerTdsRatePct,
        LocalDate lowerTdsValidFrom,
        LocalDate lowerTdsValidTo
    ) {
    }

    private record StockItemTaxMeta(
        UUID id,
        String name,
        UUID stockGroupId,
        String uqcCode,
        String gstApplicability,
        String hsnSacCode,
        BigDecimal gstRatePct,
        BigDecimal cessRatePct,
        String gstTypeOfSupply,
        boolean isTobaccoMrpItem,
        BigDecimal mrpPerUnit,
        UUID defaultSalesLedgerId,
        UUID defaultPurchaseLedgerId
    ) {
    }

    private record GroupTaxMeta(
        UUID id,
        String name,
        UUID parentGroupId,
        String hsnSacCode,
        BigDecimal gstRatePct
    ) {
    }

    private record StockGroupTaxMeta(
        UUID id,
        UUID parentStockGroupId,
        String hsnSacCode,
        BigDecimal gstRatePct,
        BigDecimal cessRatePct
    ) {
    }

    private record TdsNatureRow(
        UUID id,
        String sectionCode,
        BigDecimal rateIndividualHufPct,
        BigDecimal rateOtherPct,
        BigDecimal noPanRatePct
    ) {
    }

    private record ApportionExpenseEntry(
        BigDecimal amount,
        String basis
    ) {
    }

    private record CandidateTaxableUnit(
        int lineNo,
        String description,
        UUID ledgerId,
        String hsnSacCode,
        String uqcCode,
        String typeOfSupply,
        BigDecimal billedQty,
        BigDecimal commercialTaxableValue,
        BigDecimal apportionedExpenseValue,
        BigDecimal declaredMrp,
        BigDecimal deemedMrpTaxableValue,
        boolean isDeemedMrpValuation,
        String rateSourceLevel,
        String supplyNature,
        BigDecimal gstRatePct,
        BigDecimal cessRatePct
    ) {
    }

    private record SlabKey(
        BigDecimal gstRatePct,
        BigDecimal cessRatePct,
        String supplyNature
    ) {
    }

    private record ComputedTaxLine(
        int lineNo,
        String description,
        UUID ledgerId,
        String hsnSacCode,
        String uqcCode,
        String typeOfSupply,
        BigDecimal billedQty,
        BigDecimal commercialTaxableValue,
        BigDecimal apportionedExpenseValue,
        BigDecimal declaredMrp,
        BigDecimal deemedMrpTaxableValue,
        boolean isDeemedMrpValuation,
        BigDecimal assessableValue,
        String rateSourceLevel,
        String supplyNature,
        BigDecimal gstRatePct,
        BigDecimal cessRatePct,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal igstAmount,
        BigDecimal cessAmount
    ) {
    }

    private record ComputedSlabSummary(
        BigDecimal gstRatePct,
        BigDecimal assessableValue,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal igstAmount,
        BigDecimal cessAmount
    ) {
    }

    private record ComputedVoucherGstResult(
        List<ComputedTaxLine> lines,
        List<ComputedSlabSummary> slabs,
        BigDecimal totalAssessable,
        BigDecimal totalCgst,
        BigDecimal totalSgst,
        BigDecimal totalIgst,
        BigDecimal totalCess,
        BigDecimal totalTax
    ) {
        String primaryDetailedSupplyNature(
            String companyState,
            String posState,
            boolean isRcm,
            String exportType
        ) {
            if (!lines.isEmpty()) {
                String sn = lines.getFirst().supplyNature();
                return sn.startsWith("REVERSE_CHARGE_INWARD") ? "REVERSE_CHARGE_INWARD" : sn;
            }
            return resolveDetailedSupplyNature("APPLICABLE", companyState, posState, isRcm, exportType);
        }
    }

    private StoredJournalHeader mapStoredHeader(ResultSet rs, int rowNum) throws SQLException {
        return new StoredJournalHeader(
            rs.getObject("id", UUID.class),
            rs.getObject("voucher_date", LocalDate.class),
            rs.getObject("effective_date", LocalDate.class),
            rs.getString("status"),
            rs.getString("posting_nature"),
            rs.getObject("party_ledger_id", UUID.class),
            rs.getObject("accounting_ledger_id", UUID.class),
            rs.getString("place_of_supply_state"),
            rs.getObject("company_gstin_id", UUID.class),
            rs.getString("party_gstin"),
            rs.getString("party_state_code"),
            rs.getBoolean("is_reverse_charge"),
            rs.getString("export_type"),
            rs.getObject("reversal_of_journal_id", UUID.class),
            rs.getString("base_category"),
            rs.getShort("currency_scale")
        );
    }

    private List<StoredJournalLine> loadStoredJournalLines(UUID companyId, UUID journalId) {
        return businessJdbcTemplate.query(
            """
            SELECT id, line_no, line_role, ledger_id, side, amount, rate_pct, is_gst_overridden
              FROM mc_journal_line
             WHERE company_id = ? AND journal_id = ?
             ORDER BY line_no ASC
            """,
            (rs, rowNum) -> new StoredJournalLine(
                rs.getObject("id", UUID.class),
                rs.getInt("line_no"),
                rs.getString("line_role"),
                rs.getObject("ledger_id", UUID.class),
                rs.getString("side"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("rate_pct"),
                rs.getBoolean("is_gst_overridden")
            ),
            companyId,
            journalId
        );
    }

    private List<StoredInvLine> loadStoredInvLines(UUID companyId, UUID journalId) {
        return businessJdbcTemplate.query(
            """
            SELECT id, line_no, stock_item_id, billed_qty, amount, declared_mrp
              FROM mc_inventory_allocation
             WHERE company_id = ? AND journal_id = ?
             ORDER BY line_no ASC
            """,
            (rs, rowNum) -> new StoredInvLine(
                rs.getObject("id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("stock_item_id", UUID.class),
                rs.getBigDecimal("billed_qty"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("declared_mrp")
            ),
            companyId,
            journalId
        );
    }

    private Map<UUID, LedgerTaxMeta> loadLedgerTaxMeta(UUID companyId, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, LedgerTaxMeta> map = new HashMap<>();
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
        String sql =
            """
            SELECT l.id, l.name, l.group_id, COALESCE(g.name, '') AS group_name,
                   COALESCE(g.nature, '') AS group_nature, l.tax_type, l.duty_head,
                   l.gst_applicability, l.hsn_sac_code, l.gst_rate_pct, l.cess_rate_pct,
                   l.appropriate_for, l.appropriation_basis, l.party_registration_type,
                   l.gstin, l.state_code, (l.pan_siv IS NOT NULL) AS has_pan_siv,
                   l.tds_applicable, l.tds_nature_code, l.tds_deductee_type,
                   l.lower_tds_cert_no, l.lower_tds_rate_pct, l.lower_tds_valid_from, l.lower_tds_valid_to
              FROM mc_ledger l
              LEFT JOIN mc_account_group g
                ON g.company_id = l.company_id AND g.id = l.group_id
             WHERE l.company_id = ? AND l.id IN (""" + inSql + ")";

        businessJdbcTemplate.query(
            sql,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                map.put(
                    id,
                    new LedgerTaxMeta(
                        id,
                        rs.getString("name"),
                        rs.getObject("group_id", UUID.class),
                        rs.getString("group_name"),
                        rs.getString("group_nature"),
                        rs.getString("tax_type"),
                        rs.getString("duty_head"),
                        rs.getString("gst_applicability"),
                        rs.getString("hsn_sac_code"),
                        rs.getBigDecimal("gst_rate_pct"),
                        rs.getBigDecimal("cess_rate_pct"),
                        rs.getString("appropriate_for"),
                        rs.getString("appropriation_basis"),
                        rs.getString("party_registration_type"),
                        rs.getString("gstin"),
                        rs.getString("state_code"),
                        rs.getBoolean("has_pan_siv"),
                        rs.getBoolean("tds_applicable"),
                        rs.getString("tds_nature_code"),
                        rs.getString("tds_deductee_type"),
                        rs.getString("lower_tds_cert_no"),
                        rs.getBigDecimal("lower_tds_rate_pct"),
                        rs.getObject("lower_tds_valid_from", LocalDate.class),
                        rs.getObject("lower_tds_valid_to", LocalDate.class)
                    )
                );
            },
            params.toArray()
        );
        return map;
    }

    private Map<UUID, StockItemTaxMeta> loadStockItemTaxMeta(UUID companyId, Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, StockItemTaxMeta> map = new HashMap<>();
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        StringBuilder inSql = new StringBuilder();
        for (UUID id : itemIds) {
            if (!inSql.isEmpty()) {
                inSql.append(", ");
            }
            inSql.append("?");
            params.add(id);
        }
        String sql =
            """
            SELECT si.id, si.name, si.stock_group_id, u.gst_uqc_code, u.symbol,
                   si.gst_applicability, si.hsn_sac_code, si.gst_rate_pct, si.cess_rate_pct,
                   si.gst_supply_type, si.is_tobacco_mrp_item, si.mrp_per_unit,
                   si.default_sales_ledger_id, si.default_purchase_ledger_id
              FROM mc_stock_item si
              JOIN mc_uom u
                ON u.company_id = si.company_id AND u.id = si.base_uom_id
             WHERE si.company_id = ? AND si.id IN (""" + inSql + ")";

        businessJdbcTemplate.query(
            sql,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                String uqc = firstNonBlank(rs.getString("gst_uqc_code"), rs.getString("symbol"), "NOS");
                map.put(
                    id,
                    new StockItemTaxMeta(
                        id,
                        rs.getString("name"),
                        rs.getObject("stock_group_id", UUID.class),
                        uqc,
                        rs.getString("gst_applicability"),
                        rs.getString("hsn_sac_code"),
                        rs.getBigDecimal("gst_rate_pct"),
                        rs.getBigDecimal("cess_rate_pct"),
                        rs.getString("gst_supply_type"),
                        rs.getBoolean("is_tobacco_mrp_item"),
                        rs.getBigDecimal("mrp_per_unit"),
                        rs.getObject("default_sales_ledger_id", UUID.class),
                        rs.getObject("default_purchase_ledger_id", UUID.class)
                    )
                );
            },
            params.toArray()
        );
        return map;
    }

    private Map<UUID, GroupTaxMeta> loadAllAccountGroupTaxMeta(UUID companyId) {
        Map<UUID, GroupTaxMeta> map = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, name, parent_group_id, default_hsn_sac_code, default_gst_rate_pct
              FROM mc_account_group
             WHERE company_id = ?
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                map.put(
                    id,
                    new GroupTaxMeta(
                        id,
                        rs.getString("name"),
                        rs.getObject("parent_group_id", UUID.class),
                        rs.getString("default_hsn_sac_code"),
                        rs.getBigDecimal("default_gst_rate_pct")
                    )
                );
            },
            companyId
        );
        return map;
    }

    private Map<UUID, StockGroupTaxMeta> loadAllStockGroupTaxMeta(UUID companyId) {
        Map<UUID, StockGroupTaxMeta> map = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, parent_stock_group_id, hsn_sac_code, gst_rate_pct, cess_rate_pct
              FROM mc_stock_group
             WHERE company_id = ?
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                map.put(
                    id,
                    new StockGroupTaxMeta(
                        id,
                        rs.getObject("parent_stock_group_id", UUID.class),
                        rs.getString("hsn_sac_code"),
                        rs.getBigDecimal("gst_rate_pct"),
                        rs.getBigDecimal("cess_rate_pct")
                    )
                );
            },
            companyId
        );
        return map;
    }

    private List<TdsNatureRow> loadTdsNatureRows(UUID companyId) {
        return businessJdbcTemplate.query(
            """
            SELECT id, section_code, rate_individual_huf_pct, rate_other_pct, no_pan_rate_pct
              FROM mc_tds_nature
             WHERE company_id = ? AND active = true
             ORDER BY section_code ASC
            """,
            (rs, rowNum) -> new TdsNatureRow(
                rs.getObject("id", UUID.class),
                rs.getString("section_code"),
                rs.getBigDecimal("rate_individual_huf_pct"),
                rs.getBigDecimal("rate_other_pct"),
                rs.getBigDecimal("no_pan_rate_pct")
            ),
            companyId
        );
    }

    private String resolvePreviewBaseCategory(UUID companyId, UUID voucherTypeId, String voucherTypeName) {
        if (voucherTypeId != null) {
            List<String> cats = businessJdbcTemplate.query(
                "SELECT base_category FROM mc_voucher_type WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> rs.getString("base_category"),
                companyId,
                voucherTypeId
            );
            if (!cats.isEmpty()) {
                return cats.getFirst();
            }
        }
        if (voucherTypeName != null && !voucherTypeName.isBlank()) {
            List<String> cats = businessJdbcTemplate.query(
                "SELECT base_category FROM mc_voucher_type WHERE company_id = ? AND lower(name) = lower(?)",
                (rs, rowNum) -> rs.getString("base_category"),
                companyId,
                voucherTypeName.trim()
            );
            if (!cats.isEmpty()) {
                return cats.getFirst();
            }
        }
        return "SALES";
    }

    private List<StatutoryDtos.GstSuggestedTaxLineDto> buildSuggestedTaxLines(
        UUID companyId,
        ComputedVoucherGstResult computed,
        String side,
        int scale
    ) {
        Map<String, Map.Entry<UUID, String>> dutyLedgers = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, name, duty_head
              FROM mc_ledger
             WHERE company_id = ? AND tax_type = 'GST' AND active = true
             ORDER BY name ASC
            """,
            rs -> {
                String dh = rs.getString("duty_head");
                if (dh != null && !dutyLedgers.containsKey(dh)) {
                    dutyLedgers.put(dh, Map.entry(rs.getObject("id", UUID.class), rs.getString("name")));
                }
            },
            companyId
        );

        List<StatutoryDtos.GstSuggestedTaxLineDto> list = new ArrayList<>();
        BigDecimal primaryRate = computed.slabs().size() == 1
            ? computed.slabs().getFirst().gstRatePct()
            : BigDecimal.ZERO;

        if (computed.totalCgst().signum() > 0) {
            Map.Entry<UUID, String> l = dutyLedgers.get("CGST");
            BigDecimal half = primaryRate.divide(TWO, 6, RoundingMode.HALF_UP);
            list.add(
                new StatutoryDtos.GstSuggestedTaxLineDto(
                    "CGST",
                    l != null ? l.getKey() : null,
                    l != null ? l.getValue() : "CGST",
                    side,
                    StatRulePackAndGstMasterService.formatRate2(half),
                    ExactMoneyValidator.formatExactScale(computed.totalCgst(), scale)
                )
            );
        }
        if (computed.totalSgst().signum() > 0) {
            Map.Entry<UUID, String> l = dutyLedgers.get("SGST_UTGST");
            BigDecimal half = primaryRate.divide(TWO, 6, RoundingMode.HALF_UP);
            list.add(
                new StatutoryDtos.GstSuggestedTaxLineDto(
                    "SGST_UTGST",
                    l != null ? l.getKey() : null,
                    l != null ? l.getValue() : "SGST",
                    side,
                    StatRulePackAndGstMasterService.formatRate2(half),
                    ExactMoneyValidator.formatExactScale(computed.totalSgst(), scale)
                )
            );
        }
        if (computed.totalIgst().signum() > 0) {
            Map.Entry<UUID, String> l = dutyLedgers.get("IGST");
            list.add(
                new StatutoryDtos.GstSuggestedTaxLineDto(
                    "IGST",
                    l != null ? l.getKey() : null,
                    l != null ? l.getValue() : "IGST",
                    side,
                    StatRulePackAndGstMasterService.formatRate2(primaryRate),
                    ExactMoneyValidator.formatExactScale(computed.totalIgst(), scale)
                )
            );
        }
        if (computed.totalCess().signum() > 0) {
            Map.Entry<UUID, String> l = dutyLedgers.get("CESS");
            list.add(
                new StatutoryDtos.GstSuggestedTaxLineDto(
                    "CESS",
                    l != null ? l.getKey() : null,
                    l != null ? l.getValue() : "GST Cess",
                    side,
                    "0.00",
                    ExactMoneyValidator.formatExactScale(computed.totalCess(), scale)
                )
            );
        }
        return List.copyOf(list);
    }

    private void ensurePrincipalProjected(MoneyCraftPrincipal actor) {
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

    // -------------------------------------------------------------------------
    // Pure Statutory Classification & Hierarchy Walk Utilities
    // -------------------------------------------------------------------------

    private static StockGroupTaxMeta findNearestStockGroupWithGst(
        UUID startId,
        Map<UUID, StockGroupTaxMeta> groups
    ) {
        UUID cur = startId;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            StockGroupTaxMeta sg = groups.get(cur);
            if (sg == null) {
                return null;
            }
            if (sg.gstRatePct() != null) {
                return sg;
            }
            cur = sg.parentStockGroupId();
        }
        return null;
    }

    private static String findNearestStockGroupHsn(UUID startId, Map<UUID, StockGroupTaxMeta> groups) {
        UUID cur = startId;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            StockGroupTaxMeta sg = groups.get(cur);
            if (sg == null) {
                return null;
            }
            if (notBlank(sg.hsnSacCode())) {
                return sg.hsnSacCode();
            }
            cur = sg.parentStockGroupId();
        }
        return null;
    }

    private static GroupTaxMeta findNearestAccountGroupWithGst(UUID startId, Map<UUID, GroupTaxMeta> groups) {
        UUID cur = startId;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            GroupTaxMeta ag = groups.get(cur);
            if (ag == null) {
                return null;
            }
            if (ag.gstRatePct() != null) {
                return ag;
            }
            cur = ag.parentGroupId();
        }
        return null;
    }

    private static String findNearestAccountGroupHsn(UUID startId, Map<UUID, GroupTaxMeta> groups) {
        UUID cur = startId;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            GroupTaxMeta ag = groups.get(cur);
            if (ag == null) {
                return null;
            }
            if (notBlank(ag.hsnSacCode())) {
                return ag.hsnSacCode();
            }
            cur = ag.parentGroupId();
        }
        return null;
    }

    private static String resolveDetailedSupplyNature(
        String applicability,
        String companyState,
        String posState,
        boolean isRcm,
        String exportType
    ) {
        if (isRcm) {
            boolean intra = companyState != null && companyState.equals(posState);
            return intra ? "REVERSE_CHARGE_INWARD" : "REVERSE_CHARGE_INWARD_INTER";
        }
        if ("WPAY".equals(exportType)) {
            return "EXPORT_WPAY";
        }
        if ("WOPAY".equals(exportType)) {
            return "EXPORT_WOPAY";
        }
        if ("SEZ_WPAY".equals(exportType)) {
            return "SEZ_WPAY";
        }
        if ("SEZ_WOPAY".equals(exportType)) {
            return "SEZ_WOPAY";
        }
        if ("DEEMED_EXPORT".equals(exportType)) {
            return "DEEMED_EXPORT";
        }
        if ("EXEMPT".equals(applicability)) {
            return "EXEMPT";
        }
        if ("NIL_RATED".equals(applicability)) {
            return "NIL_RATED";
        }
        if ("NON_GST".equals(applicability)) {
            return "NON_GST";
        }
        if (companyState != null && companyState.equals(posState)) {
            return "INTRA_STATE_TAXABLE";
        }
        return "INTER_STATE_TAXABLE";
    }

    private static boolean isCurrentIntraFlag(CandidateTaxableUnit unit) {
        return "REVERSE_CHARGE_INWARD".equals(unit.supplyNature());
    }

    private static String normalizeDbSupplyNature(String internalNature) {
        if ("REVERSE_CHARGE_INWARD_INTER".equals(internalNature)) {
            return "REVERSE_CHARGE_INWARD";
        }
        return internalNature;
    }

    private static boolean isInwardGstVoucher(String baseCategory, boolean isRcm) {
        if (isRcm) {
            return true;
        }
        return "PURCHASE".equals(baseCategory)
            || "DEBIT_NOTE".equals(baseCategory)
            || "RECEIPT_NOTE".equals(baseCategory)
            || "REJECTION_OUT".equals(baseCategory)
            || "PURCHASE_ORDER".equals(baseCategory);
    }

    private static boolean isIntraSupply(String supplyNature, String companyState, String posState) {
        if ("INTRA_STATE_TAXABLE".equals(supplyNature) || "REVERSE_CHARGE_INWARD".equals(supplyNature)) {
            return true;
        }
        if ("REVERSE_CHARGE_INWARD_INTER".equals(supplyNature)) {
            return false;
        }
        if (supplyNature.startsWith("REVERSE_CHARGE_INWARD")) {
            return companyState != null && companyState.equals(posState);
        }
        return false;
    }

    private static boolean isZeroTaxSupply(String supplyNature) {
        return "EXPORT_WOPAY".equals(supplyNature)
            || "SEZ_WOPAY".equals(supplyNature)
            || "EXEMPT".equals(supplyNature)
            || "NIL_RATED".equals(supplyNature)
            || "NON_GST".equals(supplyNature);
    }

    private static String toUiSupplyNature(String detailedNature) {
        return switch (detailedNature) {
            case "INTRA_STATE_TAXABLE", "REVERSE_CHARGE_INWARD" -> "INTRA_STATE";
            case "INTER_STATE_TAXABLE", "REVERSE_CHARGE_INWARD_INTER", "DEEMED_EXPORT" -> "INTER_STATE";
            case "SEZ_WPAY", "SEZ_WOPAY" -> "SEZ";
            case "EXPORT_WPAY", "EXPORT_WOPAY" -> "EXPORT";
            default -> "EXEMPT";
        };
    }

    private static String resolveEffectiveExportType(String rawExportType, LedgerTaxMeta partyMeta) {
        if (notBlank(rawExportType)) {
            return rawExportType.trim().toUpperCase(Locale.ROOT);
        }
        if (partyMeta != null) {
            if ("OVERSEAS".equals(partyMeta.partyRegistrationType())) {
                return "WOPAY";
            }
            if ("SEZ".equals(partyMeta.partyRegistrationType())) {
                return "SEZ_WOPAY";
            }
        }
        return null;
    }

    private static boolean isGstAppropriateLedger(LedgerTaxMeta lm) {
        String af = lm.appropriateFor() == null ? "NONE" : lm.appropriateFor();
        return "GST".equals(af) || "BOTH_QTY".equals(af) || "BOTH_VALUE".equals(af);
    }

    private static boolean isCommercialAccountLine(
        UUID lineLedgerId,
        UUID partyLedgerId,
        String lineRole,
        LedgerTaxMeta lm
    ) {
        if (lineLedgerId == null || lineLedgerId.equals(partyLedgerId)) {
            return false;
        }
        String role = lineRole == null ? "" : lineRole.toUpperCase(Locale.ROOT);
        if ("PARTY".equals(role) || "TAX".equals(role) || "ROUND_OFF".equals(role)) {
            return false;
        }
        String tt = lm.taxType() == null ? "NONE" : lm.taxType();
        if (!"NONE".equals(tt)) {
            return false;
        }
        String gn = lm.groupName() == null ? "" : lm.groupName().toLowerCase(Locale.ROOT);
        return !"duties & taxes".equals(gn)
            && !"sundry debtors".equals(gn)
            && !"sundry creditors".equals(gn)
            && !"bank accounts".equals(gn)
            && !"cash-in-hand".equals(gn);
    }

    private static UUID resolveEffectivePartyLedgerId(
        UUID headerPartyId,
        List<StoredJournalLine> lines,
        Map<UUID, LedgerTaxMeta> ledgerMap
    ) {
        if (headerPartyId != null) {
            return headerPartyId;
        }
        for (StoredJournalLine jl : lines) {
            if ("PARTY".equals(jl.lineRole())) {
                return jl.ledgerId();
            }
        }
        for (StoredJournalLine jl : lines) {
            LedgerTaxMeta lm = ledgerMap.get(jl.ledgerId());
            if (lm != null) {
                String gn = lm.groupName() == null ? "" : lm.groupName().toLowerCase(Locale.ROOT);
                if ("sundry creditors".equals(gn) || "sundry debtors".equals(gn) || lm.tdsApplicable()) {
                    return jl.ledgerId();
                }
            }
        }
        return null;
    }

    private static UUID findItemDefaultLedgerId(String baseCategory, StockItemTaxMeta item) {
        if (item == null) {
            return null;
        }
        if ("PURCHASE".equals(baseCategory) || "DEBIT_NOTE".equals(baseCategory)) {
            return item.defaultPurchaseLedgerId();
        }
        return item.defaultSalesLedgerId();
    }

    private static boolean isVoucherInwardForItc(
        String baseCategory,
        boolean isRcm,
        List<StoredJournalLine> lines,
        Map<UUID, LedgerTaxMeta> ledgerMap
    ) {
        if ("PURCHASE".equals(baseCategory) || isRcm) {
            return true;
        }
        if ("SALES".equals(baseCategory) || "CREDIT_NOTE".equals(baseCategory)) {
            return false;
        }
        if ("DEBIT_NOTE".equals(baseCategory)) {
            for (StoredJournalLine jl : lines) {
                LedgerTaxMeta lm = ledgerMap.get(jl.ledgerId());
                if (lm != null && "purchase accounts".equalsIgnoreCase(lm.groupName())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static String resolveItcEligibility(
        boolean isInward,
        ComputedTaxLine cl,
        Map<UUID, LedgerTaxMeta> ledgerMap,
        Map<UUID, GroupTaxMeta> accountGroupMap
    ) {
        if (!isInward) {
            return "NOT_APPLICABLE";
        }
        if ("SERVICES".equals(cl.typeOfSupply())
            || (cl.hsnSacCode() != null && cl.hsnSacCode().startsWith("99"))) {
            return "ELIGIBLE_INPUT_SERVICES";
        }
        if ("CAPITAL_GOODS".equals(cl.typeOfSupply())) {
            return "ELIGIBLE_CAPITAL_GOODS";
        }
        if (cl.ledgerId() != null) {
            LedgerTaxMeta lm = ledgerMap.get(cl.ledgerId());
            if (lm != null && isUnderFixedAssets(lm.groupId(), accountGroupMap)) {
                return "ELIGIBLE_CAPITAL_GOODS";
            }
        }
        return "ELIGIBLE_INPUTS";
    }

    private static boolean isUnderFixedAssets(UUID groupId, Map<UUID, GroupTaxMeta> groups) {
        UUID cur = groupId;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            GroupTaxMeta g = groups.get(cur);
            if (g == null) {
                return false;
            }
            if ("fixed assets".equalsIgnoreCase(g.name())) {
                return true;
            }
            cur = g.parentGroupId();
        }
        return false;
    }

    private static String resolveUqcForPersistence(
        String hsnSacCode,
        String rawUqc,
        StatRulePackAndGstMasterService.RulePackConfig rulePack
    ) {
        if (rulePack.omitUqcForPureSac()
            && hsnSacCode != null
            && hsnSacCode.startsWith(rulePack.sacPrefix())) {
            return "NA";
        }
        return notBlank(rawUqc) ? rawUqc : "NOS";
    }

    private static boolean isValidHsnCode(String hsn, Set<Integer> validLengths) {
        if (hsn == null || hsn.isBlank()) {
            return false;
        }
        String trimmed = hsn.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return validLengths.contains(trimmed.length());
    }

    private static String determineTaxSide(String baseCategory, LedgerTaxMeta partyMeta) {
        if (OUTWARD_VOUCHER_CATEGORIES.contains(baseCategory)) {
            return "SALES".equals(baseCategory) ? "CR" : "DR";
        }
        if ("DEBIT_NOTE".equals(baseCategory) && partyMeta != null
            && "sundry debtors".equalsIgnoreCase(partyMeta.groupName())) {
            return "CR";
        }
        return "DR";
    }

    private static TdsNatureRow matchTdsNature(List<TdsNatureRow> rows, String desiredSection) {
        if (notBlank(desiredSection)) {
            for (TdsNatureRow r : rows) {
                if (r.sectionCode().equalsIgnoreCase(desiredSection.trim())) {
                    return r;
                }
            }
        }
        return rows.getFirst();
    }

    private static String inferSectionFromName(String ledgerName) {
        if (ledgerName == null) {
            return null;
        }
        String up = ledgerName.toUpperCase(Locale.ROOT);
        for (String sec : List.of("194C", "194J", "194H", "194I", "194Q", "206C")) {
            if (up.contains(sec)) {
                return sec;
            }
        }
        return null;
    }

    private static String resolveActiveLowerCert(LedgerTaxMeta party, LocalDate effectiveDate) {
        if (party == null || !notBlank(party.lowerTdsCertNo())) {
            return null;
        }
        if (party.lowerTdsValidFrom() != null && effectiveDate.isBefore(party.lowerTdsValidFrom())) {
            return null;
        }
        if (party.lowerTdsValidTo() != null && effectiveDate.isAfter(party.lowerTdsValidTo())) {
            return null;
        }
        return party.lowerTdsCertNo().trim();
    }

    private static String describeUncertainCode(String code) {
        if (code == null) {
            return "GST statutory validation conflict";
        }
        return switch (code) {
            case "TAX_AMOUNT_MISMATCH", "MANUAL_TAX_OVERRIDE_MISMATCH" ->
                "Booked GST duty amount differs from slab-computed tax (manual override or rounding conflict).";
            case "MISSING_PARTY_GSTIN" ->
                "Registered party ledger is missing a valid 15-character GSTIN.";
            case "INVALID_HSN_LENGTH" ->
                "HSN/SAC code is missing or does not satisfy Phase III 4/6/8-digit length requirements.";
            case "UNCLASSIFIED_DUTY_LEDGER_POSTING" ->
                "Voucher posts to a Duties & Taxes ledger with unclassified tax_type (NONE/OTHERS).";
            default -> "Statutory GST conflict (" + code + ").";
        };
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
