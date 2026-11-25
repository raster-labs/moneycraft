package com.raster.moneycraft.reporting.service;

import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.inventory.dto.SupplyChainReportDtos;
import com.raster.moneycraft.inventory.service.ValuationService;
import com.raster.moneycraft.reporting.dto.ReportingDtos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

/**
 * Real PostgreSQL 18 server-backed Level 2 & Level 3 Tally report engine:
 * Statistics (24 voucher types + 11 master types), Exception Reports, Account Books
 * (Cash/Bank Summary, Group Summary, Group/Ledger Vouchers, and all 8 Voucher Registers),
 * and Single-Item 360-degree Stock Query.
 * Uses pure BigDecimal arithmetic with zero binary floating-point types.
 */
@Service
public class TallyLevel3ReportingService {

    private static final Set<String> CASH_BANK_GROUP_NAMES = Set.of(
        "cash-in-hand",
        "bank accounts",
        "bank od a/c",
        "bank occ a/c"
    );

    private static final Map<String, String> REGISTER_BOOK_TO_CATEGORY = Map.of(
        "SALES_REGISTER", "SALES",
        "PURCHASE_REGISTER", "PURCHASE",
        "JOURNAL_REGISTER", "JOURNAL",
        "CONTRA_REGISTER", "CONTRA",
        "PAYMENT_REGISTER", "PAYMENT",
        "RECEIPT_REGISTER", "RECEIPT",
        "DEBIT_NOTE_REGISTER", "DEBIT_NOTE",
        "CREDIT_NOTE_REGISTER", "CREDIT_NOTE"
    );

    private static final Map<String, String> BOOK_TITLES = Map.ofEntries(
        Map.entry("CASH_BANK_SUMMARY", "Cash/Bank Summary"),
        Map.entry("GROUP_SUMMARY", "Group Summary"),
        Map.entry("GROUP_VOUCHERS", "Group Vouchers"),
        Map.entry("LEDGER_VOUCHERS", "Ledger Vouchers"),
        Map.entry("SALES_REGISTER", "Sales Register"),
        Map.entry("PURCHASE_REGISTER", "Purchase Register"),
        Map.entry("JOURNAL_REGISTER", "Journal Register"),
        Map.entry("CONTRA_REGISTER", "Contra Register"),
        Map.entry("PAYMENT_REGISTER", "Payment Register"),
        Map.entry("RECEIPT_REGISTER", "Receipt Register"),
        Map.entry("DEBIT_NOTE_REGISTER", "Debit Note Register"),
        Map.entry("CREDIT_NOTE_REGISTER", "Credit Note Register")
    );

    private final RbacAuthorizationService rbacGuard;
    private final FinancialStatementService financialStatementService;
    private final ValuationService valuationService;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public TallyLevel3ReportingService(
        RbacAuthorizationService rbacGuard,
        FinancialStatementService financialStatementService,
        ValuationService valuationService,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.rbacGuard = rbacGuard;
        this.financialStatementService = financialStatementService;
        this.valuationService = valuationService;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    // =========================================================================
    // 1. Statements of Accounts -> Statistics
    // =========================================================================

    public ReportingDtos.StatisticsReportDto getStatistics(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawFromDate,
        LocalDate rawToDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            CompanyInfo company = loadCompanyInfo(companyId);
            LocalDate fromDate = rawFromDate != null ? rawFromDate : company.financialYearFrom();
            LocalDate toDate = rawToDate != null ? rawToDate : defaultFyEnd(company.financialYearFrom());
            validateDateRange(fromDate, toDate);

            List<ReportingDtos.StatisticsVoucherTypeRowDto> vchRows = businessJdbcTemplate.query(
                """
                SELECT vt.id,
                       vt.name,
                       vt.abbreviation,
                       vt.base_category,
                       vt.shortcut_key,
                       COUNT(j.id) FILTER (
                           WHERE j.status = 'POSTED' AND j.posting_nature <> 'OPTIONAL'
                       ) AS posted_cnt,
                       COUNT(j.id) FILTER (
                           WHERE j.status <> 'CANCELLED' AND j.posting_nature = 'OPTIONAL'
                       ) AS optional_cnt,
                       COUNT(j.id) FILTER (
                           WHERE j.status = 'CANCELLED'
                       ) AS cancelled_cnt,
                       COUNT(j.id) FILTER (
                           WHERE j.status IN ('DRAFT', 'APPROVED') AND j.posting_nature <> 'OPTIONAL'
                       ) AS draft_cnt
                  FROM mc_voucher_type vt
                  LEFT JOIN mc_journal j
                    ON j.company_id = vt.company_id
                   AND j.voucher_type_id = vt.id
                   AND j.voucher_date >= ?
                   AND j.voucher_date <= ?
                 WHERE vt.company_id = ?
                 GROUP BY vt.id, vt.name, vt.abbreviation, vt.base_category, vt.shortcut_key
                 ORDER BY lower(vt.name) ASC, vt.id ASC
                """,
                (rs, rowNum) -> {
                    long posted = rs.getLong("posted_cnt");
                    long optional = rs.getLong("optional_cnt");
                    long cancelled = rs.getLong("cancelled_cnt");
                    long draft = rs.getLong("draft_cnt");
                    long active = posted + optional;
                    long total = active + cancelled + draft;
                    return new ReportingDtos.StatisticsVoucherTypeRowDto(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("abbreviation"),
                        rs.getString("base_category"),
                        rs.getString("shortcut_key"),
                        posted,
                        optional,
                        cancelled,
                        draft,
                        active,
                        total
                    );
                },
                fromDate,
                toDate,
                companyId
            );

            long totalPosted = 0L;
            long totalOptional = 0L;
            long totalCancelled = 0L;
            long totalDraft = 0L;
            for (ReportingDtos.StatisticsVoucherTypeRowDto r : vchRows) {
                totalPosted += r.postedCount();
                totalOptional += r.optionalCount();
                totalCancelled += r.cancelledCount();
                totalDraft += r.draftCount();
            }

            long groupCnt = countTable(companyId, "mc_account_group");
            long ledgerCnt = countTable(companyId, "mc_ledger");
            long stockGroupCnt = countTable(companyId, "mc_stock_group");
            long stockCategoryCnt = countTable(companyId, "mc_stock_category");
            long stockItemCnt = countTable(companyId, "mc_stock_item");
            long uomCnt = countTable(companyId, "mc_uom");
            long godownCnt = countTable(companyId, "mc_godown");
            long costCategoryCnt = countTable(companyId, "mc_cost_category");
            long costCentreCnt = countTable(companyId, "mc_cost_centre");
            long extraCurrencyCnt = countForeignCurrencies(companyId, company.baseCurrencyCode());
            long currencyCnt = 1L + extraCurrencyCnt;
            long voucherTypeCnt = vchRows.size();

            List<ReportingDtos.StatisticsMasterCountRowDto> masterRows = List.of(
                new ReportingDtos.StatisticsMasterCountRowDto("GROUPS", "Groups", groupCnt, "/app/masters/groups"),
                new ReportingDtos.StatisticsMasterCountRowDto("LEDGERS", "Ledgers", ledgerCnt, "/app/masters/ledgers"),
                new ReportingDtos.StatisticsMasterCountRowDto(
                    "STOCK_GROUPS", "Stock Groups", stockGroupCnt, "/app/inventory/stock-groups"
                ),
                new ReportingDtos.StatisticsMasterCountRowDto(
                    "STOCK_CATEGORIES", "Stock Categories", stockCategoryCnt, "/app/inventory/stock-categories"
                ),
                new ReportingDtos.StatisticsMasterCountRowDto(
                    "STOCK_ITEMS", "Stock Items", stockItemCnt, "/app/inventory/stock-items"
                ),
                new ReportingDtos.StatisticsMasterCountRowDto("UNITS", "Units", uomCnt, "/app/inventory/uoms"),
                new ReportingDtos.StatisticsMasterCountRowDto("GODOWNS", "Godowns", godownCnt, "/app/inventory/godowns"),
                new ReportingDtos.StatisticsMasterCountRowDto(
                    "COST_CATEGORIES", "Cost Categories", costCategoryCnt, "/app/masters/cost-categories"
                ),
                new ReportingDtos.StatisticsMasterCountRowDto(
                    "COST_CENTRES", "Cost Centres", costCentreCnt, "/app/masters/cost-centres"
                ),
                new ReportingDtos.StatisticsMasterCountRowDto(
                    "CURRENCIES", "Currencies", currencyCnt, "/app/masters/currencies"
                ),
                new ReportingDtos.StatisticsMasterCountRowDto(
                    "VOUCHER_TYPES", "Voucher Types", voucherTypeCnt, "/app/masters/voucher-types"
                )
            );

            long totalActive = totalPosted + totalOptional;
            long totalAll = totalActive + totalCancelled + totalDraft;

            return new ReportingDtos.StatisticsReportDto(
                fromDate,
                toDate,
                vchRows,
                masterRows,
                totalPosted,
                totalOptional,
                totalCancelled,
                totalDraft,
                totalActive,
                totalAll,
                masterRows.size()
            );
        });
    }

    // =========================================================================
    // 2. Display More Reports -> Exception Reports
    // =========================================================================

    public ReportingDtos.ExceptionReportDto getExceptions(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String rawType,
        LocalDate rawFromDate,
        LocalDate rawToDate,
        LocalDate rawAsOfDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        String type = rawType == null || rawType.isBlank()
            ? "NEGATIVE_STOCK"
            : rawType.trim().toUpperCase(Locale.ROOT);

        return businessTxTemplate.execute(tx -> {
            CompanyInfo company = loadCompanyInfo(companyId);
            int scale = company.currencyScale();
            LocalDate fromDate = rawFromDate != null ? rawFromDate : company.financialYearFrom();
            LocalDate toDate = rawToDate != null
                ? rawToDate
                : (rawAsOfDate != null ? rawAsOfDate : defaultFyEnd(company.financialYearFrom()));
            LocalDate asOfDate = rawAsOfDate != null ? rawAsOfDate : toDate;
            if (fromDate.isAfter(toDate)) {
                fromDate = company.booksBeginningFrom().isBefore(toDate) ? company.booksBeginningFrom() : toDate;
            }

            return switch (type) {
                case "NEGATIVE_STOCK" -> buildNegativeStockExceptions(principal, companyId, fromDate, toDate, asOfDate, scale);
                case "NEGATIVE_LEDGERS" -> buildNegativeLedgerExceptions(principal, companyId, fromDate, toDate, asOfDate, scale);
                case "OVERDUE_RECEIVABLES" -> buildOverdueBillExceptions(
                    principal, companyId, "OVERDUE_RECEIVABLES", "Overdue Receivables", "RECEIVABLES",
                    fromDate, toDate, asOfDate, scale
                );
                case "OVERDUE_PAYABLES" -> buildOverdueBillExceptions(
                    principal, companyId, "OVERDUE_PAYABLES", "Overdue Payables", "PAYABLES",
                    fromDate, toDate, asOfDate, scale
                );
                case "OPTIONAL_VOUCHERS" -> buildVoucherExceptions(
                    companyId, "OPTIONAL_VOUCHERS", "Optional Vouchers", fromDate, toDate, asOfDate, scale,
                    "j.status <> 'CANCELLED' AND j.posting_nature = 'OPTIONAL'"
                );
                case "CANCELLED_VOUCHERS" -> buildVoucherExceptions(
                    companyId, "CANCELLED_VOUCHERS", "Cancelled Vouchers", fromDate, toDate, asOfDate, scale,
                    "j.status = 'CANCELLED'"
                );
                case "MEMORANDUM_VOUCHERS" -> buildVoucherExceptions(
                    companyId, "MEMORANDUM_VOUCHERS", "Memorandum Vouchers", fromDate, toDate, asOfDate, scale,
                    "j.status <> 'CANCELLED' AND (vt.base_category = 'MEMORANDUM' OR j.posting_nature = 'MEMORANDUM')"
                );
                case "REVERSING_JOURNALS" -> buildVoucherExceptions(
                    companyId, "REVERSING_JOURNALS", "Reversing Journals", fromDate, toDate, asOfDate, scale,
                    "j.status <> 'CANCELLED' AND (vt.base_category = 'REVERSING_JOURNAL' OR j.posting_nature = 'REVERSING_JOURNAL')"
                );
                case "APPROVAL_QUEUE" -> buildVoucherExceptions(
                    companyId, "APPROVAL_QUEUE", "Voucher Approval Queue", fromDate, toDate, asOfDate, scale,
                    "j.status IN ('DRAFT', 'APPROVED') AND j.posting_nature = 'NORMAL'"
                );
                default -> throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-REP-INVALID-EXCEPTION-TYPE",
                    "Unsupported Exception Report Type",
                    "Unsupported exception report type: " + rawType
                );
            };
        });
    }

    private ReportingDtos.ExceptionReportDto buildNegativeStockExceptions(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOfDate,
        int scale
    ) {
        SupplyChainReportDtos.StockSummaryReportDto stock =
            valuationService.getStockSummary(principal, companyId, fromDate, asOfDate, null, null);
        List<ReportingDtos.ExceptionReportRowDto> rows = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalVal = BigDecimal.ZERO;

        for (SupplyChainReportDtos.StockSummaryItemDto item : stock.items()) {
            BigDecimal closeQty = new BigDecimal(item.closingQty());
            if (closeQty.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal closeVal = new BigDecimal(item.closingValue());
                totalQty = totalQty.add(closeQty);
                totalVal = totalVal.add(closeVal.abs());
                rows.add(new ReportingDtos.ExceptionReportRowDto(
                    item.stockItemId(),
                    "STOCK_ITEM",
                    asOfDate,
                    null,
                    item.hsnSacCode(),
                    null,
                    item.stockGroupName(),
                    item.stockItemName(),
                    item.uomSymbol(),
                    item.closingQty(),
                    item.closingRate(),
                    fmt(BigDecimal.ZERO, scale),
                    fmt(closeVal.abs(), scale),
                    item.closingValue(),
                    "INWARD",
                    "NEGATIVE",
                    null,
                    "NEGATIVE",
                    null
                ));
            }
        }

        return new ReportingDtos.ExceptionReportDto(
            "NEGATIVE_STOCK",
            "Negative Stock",
            fromDate,
            toDate,
            asOfDate,
            rows.size(),
            ExactMoneyValidator.formatPlainDecimal(totalQty),
            fmt(BigDecimal.ZERO, scale),
            fmt(totalVal, scale),
            fmt(totalVal, scale),
            rows
        );
    }

    private ReportingDtos.ExceptionReportDto buildNegativeLedgerExceptions(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOfDate,
        int scale
    ) {
        ReportingDtos.TrialBalanceReportDto tb =
            financialStatementService.getTrialBalance(principal, companyId, fromDate, asOfDate, false);
        List<ReportingDtos.ExceptionReportRowDto> rows = new ArrayList<>();
        BigDecimal totalDr = BigDecimal.ZERO;
        BigDecimal totalCr = BigDecimal.ZERO;

        for (ReportingDtos.TrialBalanceRowDto lr : tb.ledgerRows()) {
            BigDecimal clDr = new BigDecimal(lr.closingDebit());
            BigDecimal clCr = new BigDecimal(lr.closingCredit());
            boolean normalDebit = "ASSETS".equals(lr.nature()) || "EXPENSES".equals(lr.nature());
            boolean abnormal = (normalDebit && clCr.signum() > 0) || (!normalDebit && clDr.signum() > 0);
            if (!abnormal) {
                continue;
            }
            BigDecimal abnAmt = normalDebit ? clCr : clDr;
            totalDr = totalDr.add(clDr);
            totalCr = totalCr.add(clCr);
            rows.add(new ReportingDtos.ExceptionReportRowDto(
                lr.ledgerId(),
                "LEDGER",
                asOfDate,
                null,
                lr.code(),
                null,
                lr.groupName(),
                lr.ledgerName(),
                null,
                null,
                null,
                lr.closingDebit(),
                lr.closingCredit(),
                fmt(abnAmt, scale),
                normalDebit ? "DR" : "CR",
                normalDebit ? "CR" : "DR",
                null,
                "NEGATIVE",
                null
            ));
        }

        return new ReportingDtos.ExceptionReportDto(
            "NEGATIVE_LEDGERS",
            "Negative Ledgers",
            fromDate,
            toDate,
            asOfDate,
            rows.size(),
            "0",
            fmt(totalDr, scale),
            fmt(totalCr, scale),
            fmt(totalDr.add(totalCr), scale),
            rows
        );
    }

    private ReportingDtos.ExceptionReportDto buildOverdueBillExceptions(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String reportType,
        String title,
        String ageingSide,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOfDate,
        int scale
    ) {
        ReportingDtos.AgeingReportDto ageing =
            financialStatementService.getAgeing(principal, companyId, ageingSide, asOfDate);
        List<ReportingDtos.ExceptionReportRowDto> rows = new ArrayList<>();
        BigDecimal totalPending = BigDecimal.ZERO;
        boolean isReceivable = "RECEIVABLES".equals(ageingSide);

        for (ReportingDtos.AgeingBillRowDto bill : ageing.bills()) {
            if (bill.overdueDays() <= 0) {
                continue;
            }
            BigDecimal pending = new BigDecimal(bill.pendingAmount());
            totalPending = totalPending.add(pending);
            rows.add(new ReportingDtos.ExceptionReportRowDto(
                bill.voucherId() != null ? bill.voucherId() : bill.id(),
                "BILL",
                bill.billDate(),
                bill.dueDate(),
                bill.billName(),
                null,
                ageingSide,
                bill.partyName(),
                null,
                null,
                null,
                isReceivable ? bill.pendingAmount() : fmt(BigDecimal.ZERO, scale),
                isReceivable ? fmt(BigDecimal.ZERO, scale) : bill.pendingAmount(),
                bill.pendingAmount(),
                isReceivable ? "DR" : "CR",
                isReceivable ? "DR" : "CR",
                bill.overdueDays(),
                "OVERDUE",
                bill.partyGstin()
            ));
        }

        return new ReportingDtos.ExceptionReportDto(
            reportType,
            title,
            fromDate,
            toDate,
            asOfDate,
            rows.size(),
            "0",
            isReceivable ? fmt(totalPending, scale) : fmt(BigDecimal.ZERO, scale),
            isReceivable ? fmt(BigDecimal.ZERO, scale) : fmt(totalPending, scale),
            fmt(totalPending, scale),
            rows
        );
    }

    private ReportingDtos.ExceptionReportDto buildVoucherExceptions(
        UUID companyId,
        String reportType,
        String title,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOfDate,
        int scale,
        String predicateSql
    ) {
        String sql = """
            SELECT j.id,
                   j.voucher_date,
                   j.reversing_applicable_upto,
                   j.voucher_number,
                   j.status,
                   j.posting_nature,
                   j.total_debit,
                   j.total_credit,
                   j.narration,
                   vt.name AS vt_name,
                   vt.base_category,
                   COALESCE(pl.name, (
                       SELECT l2.name
                         FROM mc_journal_line jl2
                         JOIN mc_ledger l2
                           ON l2.company_id = jl2.company_id AND l2.id = jl2.ledger_id
                        WHERE jl2.company_id = j.company_id AND jl2.journal_id = j.id
                        ORDER BY jl2.line_no ASC
                        LIMIT 1
                   ), '') AS particulars
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
             WHERE j.company_id = ?
               AND j.voucher_date >= ?
               AND j.voucher_date <= ?
               AND (%s)
             ORDER BY j.voucher_date ASC, j.master_seq_id ASC, j.id ASC
            """.formatted(predicateSql);

        List<ReportingDtos.ExceptionReportRowDto> rows = new ArrayList<>();
        BigDecimal[] totals = new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};

        businessJdbcTemplate.query(
            sql,
            rs -> {
                BigDecimal dr = rs.getBigDecimal("total_debit");
                BigDecimal cr = rs.getBigDecimal("total_credit");
                totals[0] = totals[0].add(dr);
                totals[1] = totals[1].add(cr);
                String status = rs.getString("status");
                String nature = rs.getString("posting_nature");
                String badge = "OPTIONAL".equals(nature) && !"CANCELLED".equals(status) ? "OPTIONAL" : status;
                rows.add(new ReportingDtos.ExceptionReportRowDto(
                    rs.getObject("id", UUID.class),
                    "VOUCHER",
                    rs.getObject("voucher_date", LocalDate.class),
                    rs.getObject("reversing_applicable_upto", LocalDate.class),
                    rs.getString("voucher_number"),
                    rs.getString("vt_name"),
                    rs.getString("base_category"),
                    rs.getString("particulars"),
                    null,
                    null,
                    null,
                    fmt(dr, scale),
                    fmt(cr, scale),
                    fmt(dr, scale),
                    "DR",
                    "DR",
                    null,
                    badge,
                    rs.getString("narration")
                ));
            },
            companyId,
            fromDate,
            toDate
        );

        return new ReportingDtos.ExceptionReportDto(
            reportType,
            title,
            fromDate,
            toDate,
            asOfDate,
            rows.size(),
            "0",
            fmt(totals[0], scale),
            fmt(totals[1], scale),
            fmt(totals[0], scale),
            rows
        );
    }

    // =========================================================================
    // 3. Display More Reports -> Account Books (Level 3 Summaries & Registers)
    // =========================================================================

    public ReportingDtos.AccountBookReportDto getAccountBooks(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String rawBookType,
        UUID groupId,
        UUID ledgerId,
        LocalDate rawFromDate,
        LocalDate rawToDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        String bookType = rawBookType == null || rawBookType.isBlank()
            ? "CASH_BANK_SUMMARY"
            : rawBookType.trim().toUpperCase(Locale.ROOT);

        if (!BOOK_TITLES.containsKey(bookType)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-REP-INVALID-BOOK-TYPE",
                "Unsupported Account Book Type",
                "Unsupported bookType: " + rawBookType
            );
        }

        return businessTxTemplate.execute(tx -> {
            CompanyInfo company = loadCompanyInfo(companyId);
            int scale = company.currencyScale();
            LocalDate fromDate = rawFromDate != null ? rawFromDate : company.financialYearFrom();
            LocalDate toDate = rawToDate != null ? rawToDate : defaultFyEnd(company.financialYearFrom());
            validateDateRange(fromDate, toDate);

            String registerCategory = REGISTER_BOOK_TO_CATEGORY.get(bookType);
            if (registerCategory != null) {
                return buildCategoryRegisterBook(
                    companyId,
                    bookType,
                    BOOK_TITLES.get(bookType),
                    registerCategory,
                    ledgerId,
                    fromDate,
                    toDate,
                    scale
                );
            }
            return buildGroupOrLedgerAccountBook(
                principal,
                companyId,
                bookType,
                groupId,
                ledgerId,
                fromDate,
                toDate,
                scale
            );
        });
    }

    private ReportingDtos.AccountBookReportDto buildCategoryRegisterBook(
        UUID companyId,
        String bookType,
        String title,
        String baseCategory,
        UUID optionalLedgerId,
        LocalDate fromDate,
        LocalDate toDate,
        int scale
    ) {
        StringBuilder sql = new StringBuilder(
            """
            SELECT j.id,
                   j.voucher_date,
                   j.voucher_number,
                   j.supplier_invoice_no,
                   j.party_gstin,
                   j.total_debit,
                   j.total_credit,
                   j.status,
                   j.narration,
                   vt.name AS vt_name,
                   vt.base_category,
                   pl.name AS party_name,
                   COALESCE(pl.name, (
                       SELECT l2.name
                         FROM mc_journal_line jl2
                         JOIN mc_ledger l2
                           ON l2.company_id = jl2.company_id AND l2.id = jl2.ledger_id
                        WHERE jl2.company_id = j.company_id AND jl2.journal_id = j.id
                        ORDER BY jl2.line_no ASC
                        LIMIT 1
                   ), '') AS particulars
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
             WHERE j.company_id = ?
               AND vt.base_category = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_date >= ?
               AND j.voucher_date <= ?
            """
        );
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(baseCategory);
        params.add(fromDate);
        params.add(toDate);

        if (optionalLedgerId != null) {
            sql.append(
                """
                 AND (
                     j.party_ledger_id = ?
                     OR EXISTS (
                         SELECT 1
                           FROM mc_journal_line jl
                          WHERE jl.company_id = j.company_id
                            AND jl.journal_id = j.id
                            AND jl.ledger_id = ?
                     )
                 )
                """
            );
            params.add(optionalLedgerId);
            params.add(optionalLedgerId);
        }
        sql.append(" ORDER BY j.voucher_date ASC, j.master_seq_id ASC, j.id ASC");

        Map<YearMonth, MonthBucket> monthBuckets = initMonthBuckets(fromDate, toDate);
        List<ReportingDtos.AccountBookVoucherRowDto> voucherRows = new ArrayList<>();
        BigDecimal[] grand = new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        boolean naturalCreditRegister = "SALES".equals(baseCategory)
            || "CREDIT_NOTE".equals(baseCategory)
            || "RECEIPT".equals(baseCategory);

        businessJdbcTemplate.query(
            sql.toString(),
            rs -> {
                LocalDate vDate = rs.getObject("voucher_date", LocalDate.class);
                BigDecimal vDr = rs.getBigDecimal("total_debit");
                BigDecimal vCr = rs.getBigDecimal("total_credit");
                grand[0] = grand[0].add(vDr);
                grand[1] = grand[1].add(vCr);
                grand[2] = grand[2].add(naturalCreditRegister ? vCr : vDr);

                YearMonth ym = YearMonth.from(vDate);
                MonthBucket mb = monthBuckets.computeIfAbsent(ym, k -> new MonthBucket(k, fromDate, toDate));
                mb.count++;
                mb.dr = mb.dr.add(vDr);
                mb.cr = mb.cr.add(vCr);

                voucherRows.add(new ReportingDtos.AccountBookVoucherRowDto(
                    rs.getObject("id", UUID.class),
                    vDate,
                    rs.getString("vt_name"),
                    rs.getString("base_category"),
                    rs.getString("voucher_number"),
                    rs.getString("particulars"),
                    rs.getString("party_name"),
                    rs.getString("party_gstin"),
                    rs.getString("supplier_invoice_no"),
                    fmt(vDr, scale),
                    fmt(vCr, scale),
                    fmt(grand[2], scale),
                    naturalCreditRegister ? "CR" : "DR",
                    rs.getString("status"),
                    rs.getString("narration")
                ));
            },
            params.toArray()
        );

        List<ReportingDtos.AccountBookMonthRowDto> monthly = new ArrayList<>();
        BigDecimal runningMonth = BigDecimal.ZERO;
        for (MonthBucket mb : monthBuckets.values()) {
            BigDecimal mPrimary = naturalCreditRegister ? mb.cr : mb.dr;
            runningMonth = runningMonth.add(mPrimary);
            monthly.add(new ReportingDtos.AccountBookMonthRowDto(
                mb.ym.toString(),
                formatMonthLabel(mb.ym),
                mb.sliceFrom,
                mb.sliceTo,
                mb.count,
                fmt(mb.dr, scale),
                fmt(mb.cr, scale),
                fmt(mPrimary, scale),
                fmt(runningMonth, scale),
                naturalCreditRegister ? "CR" : "DR"
            ));
        }

        return new ReportingDtos.AccountBookReportDto(
            bookType,
            title,
            null,
            null,
            optionalLedgerId,
            optionalLedgerId != null ? findLedgerNameOrNull(companyId, optionalLedgerId) : null,
            fromDate,
            toDate,
            voucherRows.size(),
            fmt(BigDecimal.ZERO, scale),
            fmt(BigDecimal.ZERO, scale),
            fmt(grand[0], scale),
            fmt(grand[1], scale),
            naturalCreditRegister ? fmt(BigDecimal.ZERO, scale) : fmt(grand[0], scale),
            naturalCreditRegister ? fmt(grand[1], scale) : fmt(BigDecimal.ZERO, scale),
            List.of(),
            monthly,
            voucherRows
        );
    }

    private ReportingDtos.AccountBookReportDto buildGroupOrLedgerAccountBook(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String bookType,
        UUID groupId,
        UUID ledgerId,
        LocalDate fromDate,
        LocalDate toDate,
        int scale
    ) {
        Map<UUID, GroupNode> groups = loadAccountGroups(companyId);
        Map<UUID, List<UUID>> childGroupsByParent = new HashMap<>();
        for (GroupNode g : groups.values()) {
            if (g.parentGroupId() != null) {
                childGroupsByParent.computeIfAbsent(g.parentGroupId(), k -> new ArrayList<>()).add(g.id());
            }
        }

        ReportingDtos.TrialBalanceReportDto tb =
            financialStatementService.getTrialBalance(principal, companyId, fromDate, toDate, false);
        Map<UUID, ReportingDtos.TrialBalanceRowDto> tbLedgersById = new HashMap<>();
        for (ReportingDtos.TrialBalanceRowDto lr : tb.ledgerRows()) {
            if (lr.ledgerId() != null) {
                tbLedgersById.put(lr.ledgerId(), lr);
            }
        }

        List<LedgerMeta> allLedgers = loadAllLedgers(companyId);
        Set<UUID> targetGroupIds = new HashSet<>();
        Set<UUID> targetLedgerIds = new HashSet<>();
        UUID resolvedGroupId = groupId;
        String resolvedGroupName = null;
        UUID resolvedLedgerId = ledgerId;
        String resolvedLedgerName = null;

        if ("LEDGER_VOUCHERS".equals(bookType)) {
            LedgerMeta target = resolveTargetLedger(allLedgers, ledgerId);
            resolvedLedgerId = target.id();
            resolvedLedgerName = target.name();
            targetLedgerIds.add(target.id());
        } else if ("CASH_BANK_SUMMARY".equals(bookType)) {
            for (GroupNode g : groups.values()) {
                if (CASH_BANK_GROUP_NAMES.contains(g.name().toLowerCase(Locale.ROOT))) {
                    collectSubtreeGroupIds(g.id(), childGroupsByParent, targetGroupIds);
                }
            }
            for (LedgerMeta l : allLedgers) {
                if (l.groupId() != null && targetGroupIds.contains(l.groupId())) {
                    targetLedgerIds.add(l.id());
                }
            }
            resolvedGroupName = "Cash-in-Hand & Bank Accounts";
        } else {
            if (resolvedGroupId != null) {
                GroupNode root = groups.get(resolvedGroupId);
                if (root == null) {
                    throw new MoneyCraftException(
                        HttpStatus.NOT_FOUND,
                        "MC-ACC-GROUP-NOT-FOUND",
                        "Account Group Not Found",
                        "Account group not found: " + resolvedGroupId
                    );
                }
                resolvedGroupName = root.name();
                collectSubtreeGroupIds(root.id(), childGroupsByParent, targetGroupIds);
                for (LedgerMeta l : allLedgers) {
                    if (l.groupId() != null && targetGroupIds.contains(l.groupId())) {
                        targetLedgerIds.add(l.id());
                    }
                }
            } else {
                targetGroupIds.addAll(groups.keySet());
                for (LedgerMeta l : allLedgers) {
                    targetLedgerIds.add(l.id());
                }
                resolvedGroupName = "Primary";
            }
        }

        List<ReportingDtos.AccountBookSummaryRowDto> summaryRows = buildGroupAndLedgerSummaryRows(
            bookType,
            resolvedGroupId,
            groups,
            childGroupsByParent,
            allLedgers,
            targetGroupIds,
            targetLedgerIds,
            tbLedgersById,
            scale
        );

        // Compute aggregate opening signed balance (+ = DR, - = CR) across targetLedgerIds
        BigDecimal openingSigned = computeSignedOpeningAsOf(companyId, targetLedgerIds, allLedgers, fromDate);
        BigDecimal openDr = openingSigned.signum() > 0 ? openingSigned : BigDecimal.ZERO;
        BigDecimal openCr = openingSigned.signum() < 0 ? openingSigned.negate() : BigDecimal.ZERO;

        List<VoucherTargetMovement> movements = loadMovementsForTargetLedgers(
            companyId,
            targetLedgerIds,
            fromDate,
            toDate
        );

        Map<YearMonth, MonthBucket> monthBuckets = initMonthBuckets(fromDate, toDate);
        List<ReportingDtos.AccountBookVoucherRowDto> voucherRows = new ArrayList<>();
        BigDecimal runningSigned = openingSigned;
        BigDecimal periodDr = BigDecimal.ZERO;
        BigDecimal periodCr = BigDecimal.ZERO;

        for (VoucherTargetMovement mv : movements) {
            periodDr = periodDr.add(mv.drAmount());
            periodCr = periodCr.add(mv.crAmount());
            runningSigned = runningSigned.add(mv.drAmount()).subtract(mv.crAmount());

            YearMonth ym = YearMonth.from(mv.voucherDate());
            MonthBucket mb = monthBuckets.computeIfAbsent(ym, k -> new MonthBucket(k, fromDate, toDate));
            mb.count++;
            mb.dr = mb.dr.add(mv.drAmount());
            mb.cr = mb.cr.add(mv.crAmount());

            voucherRows.add(new ReportingDtos.AccountBookVoucherRowDto(
                mv.voucherId(),
                mv.voucherDate(),
                mv.voucherTypeName(),
                mv.voucherTypeCategory(),
                mv.voucherNumber(),
                mv.counterpartParticulars(),
                mv.partyName(),
                mv.partyGstin(),
                mv.supplierInvoiceNo(),
                fmt(mv.drAmount(), scale),
                fmt(mv.crAmount(), scale),
                fmt(runningSigned.abs(), scale),
                runningSigned.signum() >= 0 ? "DR" : "CR",
                mv.status(),
                mv.narration()
            ));
        }

        List<ReportingDtos.AccountBookMonthRowDto> monthly = new ArrayList<>();
        BigDecimal runningMonthSigned = openingSigned;
        for (MonthBucket mb : monthBuckets.values()) {
            BigDecimal net = mb.dr.subtract(mb.cr);
            runningMonthSigned = runningMonthSigned.add(net);
            monthly.add(new ReportingDtos.AccountBookMonthRowDto(
                mb.ym.toString(),
                formatMonthLabel(mb.ym),
                mb.sliceFrom,
                mb.sliceTo,
                mb.count,
                fmt(mb.dr, scale),
                fmt(mb.cr, scale),
                fmt(net.abs(), scale),
                fmt(runningMonthSigned.abs(), scale),
                runningMonthSigned.signum() >= 0 ? "DR" : "CR"
            ));
        }

        BigDecimal closeDr = runningSigned.signum() > 0 ? runningSigned : BigDecimal.ZERO;
        BigDecimal closeCr = runningSigned.signum() < 0 ? runningSigned.negate() : BigDecimal.ZERO;

        String effectiveTitle = BOOK_TITLES.get(bookType)
            + (resolvedLedgerName != null ? " — " + resolvedLedgerName
                : (resolvedGroupName != null ? " — " + resolvedGroupName : ""));

        return new ReportingDtos.AccountBookReportDto(
            bookType,
            effectiveTitle,
            resolvedGroupId,
            resolvedGroupName,
            resolvedLedgerId,
            resolvedLedgerName,
            fromDate,
            toDate,
            voucherRows.size(),
            fmt(openDr, scale),
            fmt(openCr, scale),
            fmt(periodDr, scale),
            fmt(periodCr, scale),
            fmt(closeDr, scale),
            fmt(closeCr, scale),
            summaryRows,
            monthly,
            voucherRows
        );
    }

    private List<ReportingDtos.AccountBookSummaryRowDto> buildGroupAndLedgerSummaryRows(
        String bookType,
        UUID rootGroupId,
        Map<UUID, GroupNode> groups,
        Map<UUID, List<UUID>> childGroupsByParent,
        List<LedgerMeta> allLedgers,
        Set<UUID> targetGroupIds,
        Set<UUID> targetLedgerIds,
        Map<UUID, ReportingDtos.TrialBalanceRowDto> tbLedgersById,
        int scale
    ) {
        if ("LEDGER_VOUCHERS".equals(bookType)) {
            return List.of();
        }
        List<ReportingDtos.AccountBookSummaryRowDto> out = new ArrayList<>();

        List<GroupNode> summaryGroups = new ArrayList<>();
        if ("CASH_BANK_SUMMARY".equals(bookType)) {
            for (GroupNode g : groups.values()) {
                if (CASH_BANK_GROUP_NAMES.contains(g.name().toLowerCase(Locale.ROOT))) {
                    summaryGroups.add(g);
                }
            }
        } else if (rootGroupId != null) {
            for (UUID childId : childGroupsByParent.getOrDefault(rootGroupId, List.of())) {
                GroupNode cg = groups.get(childId);
                if (cg != null) {
                    summaryGroups.add(cg);
                }
            }
        } else {
            for (GroupNode g : groups.values()) {
                if (g.parentGroupId() == null) {
                    summaryGroups.add(g);
                }
            }
        }
        summaryGroups.sort(Comparator.comparing(GroupNode::name, String.CASE_INSENSITIVE_ORDER));

        for (GroupNode sg : summaryGroups) {
            Set<UUID> subGroupIds = new HashSet<>();
            collectSubtreeGroupIds(sg.id(), childGroupsByParent, subGroupIds);
            BigDecimal oDr = BigDecimal.ZERO;
            BigDecimal oCr = BigDecimal.ZERO;
            BigDecimal pDr = BigDecimal.ZERO;
            BigDecimal pCr = BigDecimal.ZERO;
            BigDecimal cDr = BigDecimal.ZERO;
            BigDecimal cCr = BigDecimal.ZERO;
            for (LedgerMeta l : allLedgers) {
                if (l.groupId() != null && subGroupIds.contains(l.groupId())) {
                    ReportingDtos.TrialBalanceRowDto tbr = tbLedgersById.get(l.id());
                    if (tbr != null) {
                        oDr = oDr.add(new BigDecimal(tbr.openingDebit()));
                        oCr = oCr.add(new BigDecimal(tbr.openingCredit()));
                        pDr = pDr.add(new BigDecimal(tbr.periodDebit()));
                        pCr = pCr.add(new BigDecimal(tbr.periodCredit()));
                        cDr = cDr.add(new BigDecimal(tbr.closingDebit()));
                        cCr = cCr.add(new BigDecimal(tbr.closingCredit()));
                    }
                }
            }
            GroupNode parent = sg.parentGroupId() != null ? groups.get(sg.parentGroupId()) : null;
            out.add(new ReportingDtos.AccountBookSummaryRowDto(
                sg.id(),
                "GROUP",
                sg.name(),
                parent != null ? parent.name() : "Primary",
                fmt(oDr, scale),
                fmt(oCr, scale),
                fmt(pDr, scale),
                fmt(pCr, scale),
                fmt(cDr, scale),
                fmt(cCr, scale)
            ));
        }

        for (LedgerMeta l : allLedgers) {
            boolean includeDirect = "CASH_BANK_SUMMARY".equals(bookType)
                ? targetLedgerIds.contains(l.id())
                : (rootGroupId != null ? rootGroupId.equals(l.groupId()) : l.groupId() == null);
            if (!includeDirect) {
                continue;
            }
            ReportingDtos.TrialBalanceRowDto tbr = tbLedgersById.get(l.id());
            BigDecimal oDr = tbr != null ? new BigDecimal(tbr.openingDebit())
                : ("DR".equals(l.openingSide()) ? l.openingBalance() : BigDecimal.ZERO);
            BigDecimal oCr = tbr != null ? new BigDecimal(tbr.openingCredit())
                : ("CR".equals(l.openingSide()) ? l.openingBalance() : BigDecimal.ZERO);
            BigDecimal pDr = tbr != null ? new BigDecimal(tbr.periodDebit()) : BigDecimal.ZERO;
            BigDecimal pCr = tbr != null ? new BigDecimal(tbr.periodCredit()) : BigDecimal.ZERO;
            BigDecimal cDr = tbr != null ? new BigDecimal(tbr.closingDebit()) : oDr;
            BigDecimal cCr = tbr != null ? new BigDecimal(tbr.closingCredit()) : oCr;

            GroupNode grp = l.groupId() != null ? groups.get(l.groupId()) : null;
            out.add(new ReportingDtos.AccountBookSummaryRowDto(
                l.id(),
                "LEDGER",
                l.name(),
                grp != null ? grp.name() : "Primary",
                fmt(oDr, scale),
                fmt(oCr, scale),
                fmt(pDr, scale),
                fmt(pCr, scale),
                fmt(cDr, scale),
                fmt(cCr, scale)
            ));
        }

        return out;
    }

    // =========================================================================
    // 4. Statements of Inventory -> Stock Query (360-Degree Single-Item View)
    // =========================================================================

    public ReportingDtos.StockQueryReportDto getStockQuery(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID rawStockItemId,
        LocalDate rawFromDate,
        LocalDate rawToDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            CompanyInfo company = loadCompanyInfo(companyId);
            int scale = company.currencyScale();
            LocalDate fromDate = rawFromDate != null ? rawFromDate : company.financialYearFrom();
            LocalDate toDate = rawToDate != null ? rawToDate : defaultFyEnd(company.financialYearFrom());
            validateDateRange(fromDate, toDate);

            StockItemHeader item = resolveStockItemHeader(companyId, rawStockItemId);

            SupplyChainReportDtos.StockSummaryReportDto summary =
                valuationService.getStockSummary(principal, companyId, fromDate, toDate, null, null);
            SupplyChainReportDtos.StockSummaryItemDto itemVal = null;
            for (SupplyChainReportDtos.StockSummaryItemDto candidate : summary.items()) {
                if (item.id().equals(candidate.stockItemId())) {
                    itemVal = candidate;
                    break;
                }
            }

            String openQty = itemVal != null ? itemVal.openingQty()
                : ExactMoneyValidator.formatPlainDecimal(item.openingQty());
            String openRate = itemVal != null ? itemVal.openingRate() : fmt(item.openingRate(), scale);
            String openVal = itemVal != null ? itemVal.openingValue() : fmt(item.openingValue(), scale);
            String inQty = itemVal != null ? itemVal.inwardQty() : "0";
            String inVal = itemVal != null ? itemVal.inwardValue() : fmt(BigDecimal.ZERO, scale);
            String outQty = itemVal != null ? itemVal.outwardQty() : "0";
            String outVal = itemVal != null ? itemVal.outwardValue() : fmt(BigDecimal.ZERO, scale);
            String closeQty = itemVal != null ? itemVal.closingQty() : openQty;
            String closeRate = itemVal != null ? itemVal.closingRate() : openRate;
            String closeVal = itemVal != null ? itemVal.closingValue() : openVal;

            ReportingDtos.StockQueryLastPartyTxDto lastPurchase = queryLastPartyItemTx(
                companyId,
                item.id(),
                toDate,
                List.of("PURCHASE", "RECEIPT_NOTE"),
                "INWARD",
                scale
            );
            ReportingDtos.StockQueryLastPartyTxDto lastSale = queryLastPartyItemTx(
                companyId,
                item.id(),
                toDate,
                List.of("SALES", "DELIVERY_NOTE"),
                "OUTWARD",
                scale
            );

            List<ReportingDtos.StockQueryGodownBatchRowDto> godownBatchRows = new ArrayList<>();
            SupplyChainReportDtos.BatchAgeingReportDto batchAgeing =
                valuationService.getBatchAgeing(principal, companyId, item.id(), toDate);
            for (SupplyChainReportDtos.BatchAgeingRowDto b : batchAgeing.batches()) {
                BigDecimal bQty = new BigDecimal(b.closingQty());
                BigDecimal bVal = new BigDecimal(b.closingValue());
                BigDecimal bRate = bQty.signum() != 0
                    ? bVal.divide(bQty.abs(), scale, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
                godownBatchRows.add(new ReportingDtos.StockQueryGodownBatchRowDto(
                    b.godownId(),
                    b.godownName(),
                    b.batchId(),
                    b.batchName(),
                    b.mfgDate(),
                    b.expiryDate(),
                    b.isExpired(),
                    b.closingQty(),
                    fmt(bRate, scale),
                    b.closingValue()
                ));
            }
            if (godownBatchRows.isEmpty()) {
                SupplyChainReportDtos.GodownSummaryReportDto godowns =
                    valuationService.getGodownSummary(principal, companyId, null, toDate);
                for (SupplyChainReportDtos.GodownSummaryRowDto gr : godowns.rows()) {
                    if (item.id().equals(gr.stockItemId())) {
                        godownBatchRows.add(new ReportingDtos.StockQueryGodownBatchRowDto(
                            gr.godownId(),
                            gr.godownName(),
                            null,
                            "Primary Batch",
                            null,
                            null,
                            false,
                            gr.closingQty(),
                            gr.closingRate(),
                            gr.closingValue()
                        ));
                    }
                }
            }

            SupplyChainReportDtos.MovementAnalysisReportDto mvAnalysis =
                valuationService.getMovementAnalysis(principal, companyId, item.id(), fromDate, toDate);
            List<ReportingDtos.StockQueryMovementRowDto> movements = mvAnalysis.movements().stream()
                .map(m -> new ReportingDtos.StockQueryMovementRowDto(
                    m.voucherId(),
                    m.voucherDate(),
                    m.voucherTypeName(),
                    m.voucherNumber(),
                    m.particulars(),
                    m.movementDirection(),
                    m.quantity(),
                    m.rate(),
                    m.value()
                ))
                .toList();

            return new ReportingDtos.StockQueryReportDto(
                item.id(),
                item.name(),
                item.aliasCode(),
                item.partNo(),
                item.stockGroupId(),
                item.stockGroupName(),
                item.stockCategoryId(),
                item.stockCategoryName(),
                item.uomSymbol(),
                item.costingMethod(),
                item.hsnSacCode(),
                item.gstRatePct() != null ? ExactMoneyValidator.formatPlainDecimal(item.gstRatePct()) : null,
                item.standardCost() != null ? fmt(item.standardCost(), scale) : null,
                item.standardSellingPrice() != null ? fmt(item.standardSellingPrice(), scale) : null,
                fromDate,
                toDate,
                openQty,
                openRate,
                openVal,
                inQty,
                inVal,
                outQty,
                outVal,
                closeQty,
                closeRate,
                closeVal,
                lastPurchase != null ? lastPurchase.voucherDate() : null,
                lastPurchase != null ? lastPurchase.partyName() : null,
                lastPurchase != null ? lastPurchase.quantity() : null,
                lastPurchase != null ? lastPurchase.rate() : null,
                lastSale != null ? lastSale.voucherDate() : null,
                lastSale != null ? lastSale.partyName() : null,
                lastSale != null ? lastSale.quantity() : null,
                lastSale != null ? lastSale.rate() : null,
                lastPurchase,
                lastSale,
                godownBatchRows,
                movements
            );
        });
    }

    // =========================================================================
    // Internal SQL & Calculation Helpers
    // =========================================================================

    private ReportingDtos.StockQueryLastPartyTxDto queryLastPartyItemTx(
        UUID companyId,
        UUID stockItemId,
        LocalDate upToDate,
        List<String> preferredCategories,
        String fallbackDirection,
        int scale
    ) {
        List<ReportingDtos.StockQueryLastPartyTxDto> preferred = businessJdbcTemplate.query(
            """
            SELECT j.id AS voucher_id,
                   j.voucher_date,
                   vt.name AS vt_name,
                   j.voucher_number,
                   j.party_ledger_id,
                   COALESCE(pl.name, (
                       SELECT l2.name
                         FROM mc_journal_line jl2
                         JOIN mc_ledger l2
                           ON l2.company_id = jl2.company_id AND l2.id = jl2.ledger_id
                        WHERE jl2.company_id = j.company_id
                          AND jl2.journal_id = j.id
                          AND jl2.line_role = 'PARTY'
                        ORDER BY jl2.line_no ASC
                        LIMIT 1
                   ), 'Direct / Cash') AS party_name,
                   ia.billed_qty,
                   ia.actual_qty,
                   u.symbol AS uom_symbol,
                   ia.rate,
                   ia.discount_pct,
                   ia.amount
              FROM mc_inventory_allocation ia
              JOIN mc_journal j
                ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              JOIN mc_uom u
                ON u.company_id = ia.company_id AND u.id = ia.uom_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
             WHERE ia.company_id = ?
               AND ia.stock_item_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_date <= ?
               AND (vt.base_category = ? OR vt.base_category = ?)
             ORDER BY j.voucher_date DESC, j.master_seq_id DESC, ia.line_no DESC
             LIMIT 1
            """,
            (rs, rowNum) -> mapLastPartyItemTx(rs, scale),
            companyId,
            stockItemId,
            upToDate,
            preferredCategories.get(0),
            preferredCategories.get(1)
        );
        if (!preferred.isEmpty()) {
            return preferred.getFirst();
        }

        List<ReportingDtos.StockQueryLastPartyTxDto> fallback = businessJdbcTemplate.query(
            """
            SELECT j.id AS voucher_id,
                   j.voucher_date,
                   vt.name AS vt_name,
                   j.voucher_number,
                   j.party_ledger_id,
                   COALESCE(pl.name, 'Stock Movement') AS party_name,
                   ia.billed_qty,
                   ia.actual_qty,
                   u.symbol AS uom_symbol,
                   ia.rate,
                   ia.discount_pct,
                   ia.amount
              FROM mc_inventory_allocation ia
              JOIN mc_journal j
                ON j.company_id = ia.company_id AND j.id = ia.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              JOIN mc_uom u
                ON u.company_id = ia.company_id AND u.id = ia.uom_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
             WHERE ia.company_id = ?
               AND ia.stock_item_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_date <= ?
               AND ia.movement_direction = ?
             ORDER BY j.voucher_date DESC, j.master_seq_id DESC, ia.line_no DESC
             LIMIT 1
            """,
            (rs, rowNum) -> mapLastPartyItemTx(rs, scale),
            companyId,
            stockItemId,
            upToDate,
            fallbackDirection
        );
        return fallback.isEmpty() ? null : fallback.getFirst();
    }

    private ReportingDtos.StockQueryLastPartyTxDto mapLastPartyItemTx(
        java.sql.ResultSet rs,
        int scale
    ) throws java.sql.SQLException {
        BigDecimal billed = rs.getBigDecimal("billed_qty");
        BigDecimal actual = rs.getBigDecimal("actual_qty");
        BigDecimal qty = billed != null && billed.signum() > 0 ? billed : actual;
        return new ReportingDtos.StockQueryLastPartyTxDto(
            rs.getObject("voucher_id", UUID.class),
            rs.getObject("voucher_date", LocalDate.class),
            rs.getString("vt_name"),
            rs.getString("voucher_number"),
            rs.getObject("party_ledger_id", UUID.class),
            rs.getString("party_name"),
            ExactMoneyValidator.formatPlainDecimal(qty),
            rs.getString("uom_symbol"),
            fmt(rs.getBigDecimal("rate"), scale),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("discount_pct")),
            fmt(rs.getBigDecimal("amount"), scale)
        );
    }

    private StockItemHeader resolveStockItemHeader(UUID companyId, UUID requestedId) {
        String sql = """
            SELECT si.id,
                   si.name,
                   si.alias_code,
                   si.part_no,
                   si.stock_group_id,
                   sg.name AS stock_group_name,
                   si.stock_category_id,
                   sc.name AS stock_category_name,
                   u.symbol AS uom_symbol,
                   si.costing_method,
                   si.hsn_sac_code,
                   si.gst_rate_pct,
                   si.standard_cost,
                   si.standard_selling_price,
                   si.opening_qty,
                   si.opening_rate,
                   si.opening_value
              FROM mc_stock_item si
              JOIN mc_uom u
                ON u.company_id = si.company_id AND u.id = si.base_uom_id
              LEFT JOIN mc_stock_group sg
                ON sg.company_id = si.company_id AND sg.id = si.stock_group_id
              LEFT JOIN mc_stock_category sc
                ON sc.company_id = si.company_id AND sc.id = si.stock_category_id
             WHERE si.company_id = ?
            """
            + (requestedId != null ? " AND si.id = ?" : " ORDER BY lower(si.name) ASC, si.id ASC LIMIT 1");

        List<StockItemHeader> rows = requestedId != null
            ? businessJdbcTemplate.query(sql, (rs, rowNum) -> mapStockItemHeader(rs), companyId, requestedId)
            : businessJdbcTemplate.query(sql, (rs, rowNum) -> mapStockItemHeader(rs), companyId);

        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-STOCK-ITEM-NOT-FOUND",
                "Stock Item Not Found",
                requestedId != null
                    ? "Stock item not found: " + requestedId
                    : "No stock items exist in this company yet."
            );
        }
        return rows.getFirst();
    }

    private StockItemHeader mapStockItemHeader(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StockItemHeader(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("alias_code"),
            rs.getString("part_no"),
            rs.getObject("stock_group_id", UUID.class),
            rs.getString("stock_group_name"),
            rs.getObject("stock_category_id", UUID.class),
            rs.getString("stock_category_name"),
            rs.getString("uom_symbol"),
            rs.getString("costing_method"),
            rs.getString("hsn_sac_code"),
            rs.getBigDecimal("gst_rate_pct"),
            rs.getBigDecimal("standard_cost"),
            rs.getBigDecimal("standard_selling_price"),
            rs.getBigDecimal("opening_qty"),
            rs.getBigDecimal("opening_rate"),
            rs.getBigDecimal("opening_value")
        );
    }

    private BigDecimal computeSignedOpeningAsOf(
        UUID companyId,
        Set<UUID> targetLedgerIds,
        List<LedgerMeta> allLedgers,
        LocalDate fromDate
    ) {
        if (targetLedgerIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal signed = BigDecimal.ZERO;
        for (LedgerMeta l : allLedgers) {
            if (targetLedgerIds.contains(l.id())) {
                BigDecimal ob = l.openingBalance() != null ? l.openingBalance() : BigDecimal.ZERO;
                signed = "CR".equals(l.openingSide()) ? signed.subtract(ob) : signed.add(ob);
            }
        }

        List<PrePeriodLineRow> priorLines = businessJdbcTemplate.query(
            """
            SELECT jl.ledger_id,
                   jl.side,
                   COALESCE(SUM(jl.amount), 0) AS sum_amt
              FROM mc_journal_line jl
              JOIN mc_journal j
                ON j.company_id = jl.company_id AND j.id = jl.journal_id
             WHERE jl.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_date < ?
             GROUP BY jl.ledger_id, jl.side
            """,
            (rs, rowNum) -> new PrePeriodLineRow(
                rs.getObject("ledger_id", UUID.class),
                rs.getString("side"),
                rs.getBigDecimal("sum_amt")
            ),
            companyId,
            fromDate
        );

        for (PrePeriodLineRow r : priorLines) {
            if (r.ledgerId() != null && targetLedgerIds.contains(r.ledgerId())) {
                signed = "CR".equals(r.side()) ? signed.subtract(r.amount()) : signed.add(r.amount());
            }
        }
        return signed;
    }

    private List<VoucherTargetMovement> loadMovementsForTargetLedgers(
        UUID companyId,
        Set<UUID> targetLedgerIds,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        if (targetLedgerIds.isEmpty()) {
            return List.of();
        }
        List<RawJournalLineRow> lines = businessJdbcTemplate.query(
            """
            SELECT j.id AS journal_id,
                   j.voucher_date,
                   j.master_seq_id,
                   j.voucher_number,
                   j.supplier_invoice_no,
                   j.party_gstin,
                   j.status,
                   j.narration,
                   vt.name AS vt_name,
                   vt.base_category,
                   pl.name AS party_name,
                   jl.ledger_id,
                   l.name AS ledger_name,
                   jl.side,
                   jl.amount
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              JOIN mc_journal_line jl
                ON jl.company_id = j.company_id AND jl.journal_id = j.id
              LEFT JOIN mc_ledger l
                ON l.company_id = jl.company_id AND l.id = jl.ledger_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
             WHERE j.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.voucher_date >= ?
               AND j.voucher_date <= ?
             ORDER BY j.voucher_date ASC, j.master_seq_id ASC, j.id ASC, jl.line_no ASC
            """,
            (rs, rowNum) -> new RawJournalLineRow(
                rs.getObject("journal_id", UUID.class),
                rs.getObject("voucher_date", LocalDate.class),
                rs.getString("voucher_number"),
                rs.getString("supplier_invoice_no"),
                rs.getString("party_gstin"),
                rs.getString("status"),
                rs.getString("narration"),
                rs.getString("vt_name"),
                rs.getString("base_category"),
                rs.getString("party_name"),
                rs.getObject("ledger_id", UUID.class),
                rs.getString("ledger_name"),
                rs.getString("side"),
                rs.getBigDecimal("amount")
            ),
            companyId,
            fromDate,
            toDate
        );

        Map<UUID, MutableVoucherMove> byVoucher = new LinkedHashMap<>();
        for (RawJournalLineRow r : lines) {
            MutableVoucherMove mv = byVoucher.computeIfAbsent(r.journalId(), id -> new MutableVoucherMove(r));
            if (r.ledgerId() != null && targetLedgerIds.contains(r.ledgerId())) {
                mv.touchesTarget = true;
                if ("DR".equals(r.side())) {
                    mv.dr = mv.dr.add(r.amount());
                } else {
                    mv.cr = mv.cr.add(r.amount());
                }
                if (mv.firstTargetLedgerName == null && r.ledgerName() != null) {
                    mv.firstTargetLedgerName = r.ledgerName();
                }
            } else if (mv.firstCounterpartName == null && r.ledgerName() != null) {
                mv.firstCounterpartName = r.ledgerName();
            }
        }

        List<VoucherTargetMovement> result = new ArrayList<>();
        for (MutableVoucherMove mv : byVoucher.values()) {
            if (!mv.touchesTarget) {
                continue;
            }
            String particulars = mv.firstCounterpartName != null
                ? mv.firstCounterpartName
                : (mv.first.partyName() != null ? mv.first.partyName()
                    : (mv.firstTargetLedgerName != null ? mv.firstTargetLedgerName : ""));
            result.add(new VoucherTargetMovement(
                mv.first.journalId(),
                mv.first.voucherDate(),
                mv.first.voucherTypeName(),
                mv.first.baseCategory(),
                mv.first.voucherNumber(),
                particulars,
                mv.first.partyName(),
                mv.first.partyGstin(),
                mv.first.supplierInvoiceNo(),
                mv.dr,
                mv.cr,
                mv.first.status(),
                mv.first.narration()
            ));
        }
        return result;
    }

    private void collectSubtreeGroupIds(
        UUID rootId,
        Map<UUID, List<UUID>> childrenMap,
        Set<UUID> visited
    ) {
        if (rootId == null || !visited.add(rootId)) {
            return;
        }
        for (UUID childId : childrenMap.getOrDefault(rootId, List.of())) {
            collectSubtreeGroupIds(childId, childrenMap, visited);
        }
    }

    private Map<UUID, GroupNode> loadAccountGroups(UUID companyId) {
        Map<UUID, GroupNode> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, name, parent_group_id, nature
              FROM mc_account_group
             WHERE company_id = ?
             ORDER BY sort_order ASC, lower(name) ASC
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                map.put(id, new GroupNode(
                    id,
                    rs.getString("name"),
                    rs.getObject("parent_group_id", UUID.class),
                    rs.getString("nature")
                ));
            },
            companyId
        );
        return map;
    }

    private List<LedgerMeta> loadAllLedgers(UUID companyId) {
        return businessJdbcTemplate.query(
            """
            SELECT id, name, group_id, opening_balance, opening_side
              FROM mc_ledger
             WHERE company_id = ?
             ORDER BY lower(name) ASC, id ASC
            """,
            (rs, rowNum) -> new LedgerMeta(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("group_id", UUID.class),
                rs.getBigDecimal("opening_balance"),
                rs.getString("opening_side")
            ),
            companyId
        );
    }

    private LedgerMeta resolveTargetLedger(List<LedgerMeta> allLedgers, UUID requestedLedgerId) {
        if (requestedLedgerId != null) {
            for (LedgerMeta l : allLedgers) {
                if (requestedLedgerId.equals(l.id())) {
                    return l;
                }
            }
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-LEDGER-NOT-FOUND",
                "Ledger Not Found",
                "Ledger not found: " + requestedLedgerId
            );
        }
        for (LedgerMeta l : allLedgers) {
            if ("Cash".equalsIgnoreCase(l.name())) {
                return l;
            }
        }
        if (allLedgers.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-LEDGER-NOT-FOUND",
                "Ledger Not Found",
                "No ledgers exist in this company."
            );
        }
        return allLedgers.getFirst();
    }

    private String findLedgerNameOrNull(UUID companyId, UUID ledgerId) {
        List<String> names = businessJdbcTemplate.query(
            "SELECT name FROM mc_ledger WHERE company_id = ? AND id = ?",
            (rs, rowNum) -> rs.getString("name"),
            companyId,
            ledgerId
        );
        return names.isEmpty() ? null : names.getFirst();
    }

    private long countTable(UUID companyId, String tableName) {
        Long cnt = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + tableName + " WHERE company_id = ?",
            Long.class,
            companyId
        );
        return cnt != null ? cnt : 0L;
    }

    private long countForeignCurrencies(UUID companyId, String baseCurrencyCode) {
        Long cnt = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mc_currency WHERE company_id = ? AND upper(iso_code) <> upper(?)",
            Long.class,
            companyId,
            baseCurrencyCode != null ? baseCurrencyCode.trim() : "INR"
        );
        return cnt != null ? cnt : 0L;
    }

    private CompanyInfo loadCompanyInfo(UUID companyId) {
        List<CompanyInfo> rows = businessJdbcTemplate.query(
            """
            SELECT base_currency_code, currency_scale, books_beginning_from, financial_year_from
              FROM mc_company
             WHERE id = ?
            """,
            (rs, rowNum) -> new CompanyInfo(
                rs.getString("base_currency_code"),
                rs.getInt("currency_scale"),
                rs.getObject("books_beginning_from", LocalDate.class),
                rs.getObject("financial_year_from", LocalDate.class)
            ),
            companyId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-CMP-NOT-FOUND",
                "Company Not Found",
                "Company does not exist: " + companyId
            );
        }
        return rows.getFirst();
    }

    private static LocalDate defaultFyEnd(LocalDate fyStart) {
        return fyStart.plusYears(1).minusDays(1);
    }

    private static void validateDateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-REP-INVALID-DATE-RANGE",
                "Invalid Report Date Range",
                "fromDate (" + fromDate + ") must not be after toDate (" + toDate + ")."
            );
        }
    }

    private static Map<YearMonth, MonthBucket> initMonthBuckets(LocalDate fromDate, LocalDate toDate) {
        Map<YearMonth, MonthBucket> map = new LinkedHashMap<>();
        YearMonth cur = YearMonth.from(fromDate);
        YearMonth end = YearMonth.from(toDate);
        int guard = 0;
        while (!cur.isAfter(end) && guard++ < 120) {
            map.put(cur, new MonthBucket(cur, fromDate, toDate));
            cur = cur.plusMonths(1);
        }
        return map;
    }

    private static String formatMonthLabel(YearMonth ym) {
        return ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + ym.getYear();
    }

    private static String fmt(BigDecimal val, int scale) {
        return ExactMoneyValidator.formatExactScale(val == null ? BigDecimal.ZERO : val, scale);
    }

    private record CompanyInfo(
        String baseCurrencyCode,
        int currencyScale,
        LocalDate booksBeginningFrom,
        LocalDate financialYearFrom
    ) {
    }

    private record GroupNode(
        UUID id,
        String name,
        UUID parentGroupId,
        String nature
    ) {
    }

    private record LedgerMeta(
        UUID id,
        String name,
        UUID groupId,
        BigDecimal openingBalance,
        String openingSide
    ) {
    }

    private record PrePeriodLineRow(
        UUID ledgerId,
        String side,
        BigDecimal amount
    ) {
    }

    private record RawJournalLineRow(
        UUID journalId,
        LocalDate voucherDate,
        String voucherNumber,
        String supplierInvoiceNo,
        String partyGstin,
        String status,
        String narration,
        String voucherTypeName,
        String baseCategory,
        String partyName,
        UUID ledgerId,
        String ledgerName,
        String side,
        BigDecimal amount
    ) {
    }

    private static final class MutableVoucherMove {
        private final RawJournalLineRow first;
        private boolean touchesTarget;
        private BigDecimal dr = BigDecimal.ZERO;
        private BigDecimal cr = BigDecimal.ZERO;
        private String firstCounterpartName;
        private String firstTargetLedgerName;

        MutableVoucherMove(RawJournalLineRow first) {
            this.first = first;
        }
    }

    private record VoucherTargetMovement(
        UUID voucherId,
        LocalDate voucherDate,
        String voucherTypeName,
        String voucherTypeCategory,
        String voucherNumber,
        String counterpartParticulars,
        String partyName,
        String partyGstin,
        String supplierInvoiceNo,
        BigDecimal drAmount,
        BigDecimal crAmount,
        String status,
        String narration
    ) {
    }

    private static final class MonthBucket {
        private final YearMonth ym;
        private final LocalDate sliceFrom;
        private final LocalDate sliceTo;
        private long count;
        private BigDecimal dr = BigDecimal.ZERO;
        private BigDecimal cr = BigDecimal.ZERO;

        MonthBucket(YearMonth ym, LocalDate reportFrom, LocalDate reportTo) {
            this.ym = ym;
            LocalDate mStart = ym.atDay(1);
            LocalDate mEnd = ym.atEndOfMonth();
            this.sliceFrom = mStart.isBefore(reportFrom) ? reportFrom : mStart;
            this.sliceTo = mEnd.isAfter(reportTo) ? reportTo : mEnd;
        }
    }

    private record StockItemHeader(
        UUID id,
        String name,
        String aliasCode,
        String partNo,
        UUID stockGroupId,
        String stockGroupName,
        UUID stockCategoryId,
        String stockCategoryName,
        String uomSymbol,
        String costingMethod,
        String hsnSacCode,
        BigDecimal gstRatePct,
        BigDecimal standardCost,
        BigDecimal standardSellingPrice,
        BigDecimal openingQty,
        BigDecimal openingRate,
        BigDecimal openingValue
    ) {
    }
}
