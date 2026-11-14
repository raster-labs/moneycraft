package com.raster.moneycraft.reporting.service;

import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.inventory.service.ValuationService;
import com.raster.moneycraft.reporting.dto.ReportingDtos;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
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
 * Penny-exact Financial & Ageing Statement Engine computing Trial Balance, Trading & P&L A/c,
 * Balance Sheet, MCA Schedule III (Division I & NCE), Cash Flow, Ratio Analysis, and Bills Ageing.
 */
@Service
public class FinancialStatementService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");
    private static final MathContext HIGH_PREC = MathContext.DECIMAL128;

    private static final Set<String> CASH_AND_BANK_GROUPS = Set.of(
        "cash-in-hand",
        "bank accounts",
        "bank od a/c",
        "bank occ a/c"
    );

    private final RbacAuthorizationService rbacGuard;
    private final ValuationService valuationService;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public FinancialStatementService(
        RbacAuthorizationService rbacGuard,
        ValuationService valuationService,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.rbacGuard = rbacGuard;
        this.valuationService = valuationService;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public ReportingDtos.TrialBalanceReportDto getTrialBalance(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawFromDate,
        LocalDate rawToDate,
        boolean includeOptional
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            CompanyMeta company = loadCompanyMeta(companyId);
            LocalDate fromDate = rawFromDate != null ? rawFromDate : company.financialYearFrom();
            LocalDate toDate = rawToDate != null ? rawToDate : defaultFyEnd(company.financialYearFrom());
            validateDateRange(fromDate, toDate);

            LedgerComputationSnapshot snap = computeLedgers(companyId, fromDate, toDate, includeOptional);
            int scale = company.currencyScale();

            List<ReportingDtos.TrialBalanceRowDto> ledgerRows = new ArrayList<>();
            BigDecimal totalOpenDr = BigDecimal.ZERO;
            BigDecimal totalOpenCr = BigDecimal.ZERO;
            BigDecimal totalPeriodDr = BigDecimal.ZERO;
            BigDecimal totalPeriodCr = BigDecimal.ZERO;
            BigDecimal totalCloseDr = BigDecimal.ZERO;
            BigDecimal totalCloseCr = BigDecimal.ZERO;

            for (ComputedLedger cl : snap.ledgers()) {
                boolean nonZero = cl.openDr().signum() != 0
                    || cl.openCr().signum() != 0
                    || cl.periodDr().signum() != 0
                    || cl.periodCr().signum() != 0
                    || cl.closeDr().signum() != 0
                    || cl.closeCr().signum() != 0;
                if (!nonZero) {
                    continue;
                }
                totalOpenDr = totalOpenDr.add(cl.openDr());
                totalOpenCr = totalOpenCr.add(cl.openCr());
                totalPeriodDr = totalPeriodDr.add(cl.periodDr());
                totalPeriodCr = totalPeriodCr.add(cl.periodCr());
                totalCloseDr = totalCloseDr.add(cl.closeDr());
                totalCloseCr = totalCloseCr.add(cl.closeCr());

                GroupRow grp = cl.groupId() != null ? snap.groupsById().get(cl.groupId()) : null;
                ledgerRows.add(new ReportingDtos.TrialBalanceRowDto(
                    cl.id(),
                    cl.groupId(),
                    grp != null ? grp.name() : "Primary",
                    cl.id(),
                    cl.name(),
                    cl.groupId(),
                    cl.aliasCode(),
                    cl.name(),
                    grp != null ? grp.nature() : "LIABILITIES",
                    "LEDGER",
                    1,
                    fmt(cl.openDr(), scale),
                    fmt(cl.openCr(), scale),
                    fmt(cl.periodDr(), scale),
                    fmt(cl.periodCr(), scale),
                    fmt(cl.closeDr(), scale),
                    fmt(cl.closeCr(), scale),
                    List.of()
                ));
            }

            List<ReportingDtos.TrialBalanceRowDto> groupRows = buildTrialBalanceGroupTree(
                snap,
                scale
            );

            BigDecimal unadjForex = queryUnadjustedForexDelta(companyId, toDate);
            BigDecimal unadjForexDr = unadjForex.signum() > 0 ? unadjForex : BigDecimal.ZERO;
            BigDecimal unadjForexCr = unadjForex.signum() < 0 ? unadjForex.negate() : BigDecimal.ZERO;

            BigDecimal diff = totalCloseDr.subtract(totalCloseCr).abs();
            boolean balanced = diff.compareTo(BigDecimal.ZERO) == 0;

            return new ReportingDtos.TrialBalanceReportDto(
                fromDate,
                toDate,
                includeOptional,
                groupRows,
                ledgerRows,
                fmt(unadjForexDr, scale),
                fmt(unadjForexCr, scale),
                fmt(totalOpenDr, scale),
                fmt(totalOpenCr, scale),
                fmt(totalPeriodDr, scale),
                fmt(totalPeriodCr, scale),
                fmt(totalCloseDr, scale),
                fmt(totalCloseCr, scale),
                fmt(diff, scale),
                balanced
            );
        });
    }

    public ReportingDtos.ProfitAndLossReportDto getProfitAndLoss(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawFromDate,
        LocalDate rawToDate,
        boolean includeOptional
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            CompanyMeta company = loadCompanyMeta(companyId);
            LocalDate fromDate = rawFromDate != null ? rawFromDate : company.financialYearFrom();
            LocalDate toDate = rawToDate != null ? rawToDate : defaultFyEnd(company.financialYearFrom());
            validateDateRange(fromDate, toDate);
            return computeProfitAndLossInTx(principal, companyId, company, fromDate, toDate, includeOptional);
        });
    }

    public ReportingDtos.BalanceSheetReportDto getBalanceSheet(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawAsOfDate,
        String rawFormat,
        boolean includeOptional
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            CompanyMeta company = loadCompanyMeta(companyId);
            LocalDate asOfDate = rawAsOfDate != null ? rawAsOfDate : defaultFyEnd(company.financialYearFrom());
            String format = "VERTICAL".equalsIgnoreCase(rawFormat) ? "VERTICAL" : "HORIZONTAL";
            return computeBalanceSheetInTx(principal, companyId, company, asOfDate, format, includeOptional);
        });
    }

    public ReportingDtos.ScheduleIiiReportDto getScheduleIii(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawAsOfDate,
        LocalDate comparativeAsOfDate,
        String rawEntityFormat,
        String rawRoundingScale
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            CompanyMeta company = loadCompanyMeta(companyId);
            LocalDate asOfDate = rawAsOfDate != null ? rawAsOfDate : defaultFyEnd(company.financialYearFrom());
            String entityFormat = "NCE".equalsIgnoreCase(rawEntityFormat) ? "NCE" : "DIVISION_I";
            String roundingScale = normalizeRoundingScale(rawRoundingScale);

            ScheduleIiiRawBucket cur = computeScheduleIiiBucket(principal, companyId, company, asOfDate);
            ScheduleIiiRawBucket comp = comparativeAsOfDate != null
                ? computeScheduleIiiBucket(principal, companyId, company, comparativeAsOfDate)
                : ScheduleIiiRawBucket.empty();

            return buildScheduleIiiReport(
                company,
                asOfDate,
                comparativeAsOfDate,
                entityFormat,
                roundingScale,
                cur,
                comp
            );
        });
    }

    public ReportingDtos.CashFlowReportDto getCashFlow(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawFromDate,
        LocalDate rawToDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            CompanyMeta company = loadCompanyMeta(companyId);
            LocalDate fromDate = rawFromDate != null ? rawFromDate : company.financialYearFrom();
            LocalDate toDate = rawToDate != null ? rawToDate : defaultFyEnd(company.financialYearFrom());
            validateDateRange(fromDate, toDate);
            return computeCashFlowInTx(companyId, company, fromDate, toDate);
        });
    }

    public ReportingDtos.RatioAnalysisReportDto getRatios(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate rawFromDate,
        LocalDate rawToDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            CompanyMeta company = loadCompanyMeta(companyId);
            LocalDate fromDate = rawFromDate != null ? rawFromDate : company.financialYearFrom();
            LocalDate toDate = rawToDate != null ? rawToDate : defaultFyEnd(company.financialYearFrom());
            validateDateRange(fromDate, toDate);

            ReportingDtos.ProfitAndLossReportDto pnl = computeProfitAndLossInTx(
                principal, companyId, company, fromDate, toDate, false
            );
            LedgerComputationSnapshot snap = computeLedgers(companyId, fromDate, toDate, false);
            int scale = company.currencyScale();

            BigDecimal closingStock = new BigDecimal(pnl.closingStockValue());
            BigDecimal openingStock = new BigDecimal(pnl.openingStockValue());
            BigDecimal cashInHand = sumGroupNetDrMinusCr(snap, "Cash-in-Hand", false);
            BigDecimal bankAccounts = sumGroupNetDrMinusCr(snap, "Bank Accounts", false);
            BigDecimal sundryDebtors = sumGroupNetDrMinusCr(snap, "Sundry Debtors", false);
            BigDecimal sundryCreditors = sumGroupNetDrMinusCr(snap, "Sundry Creditors", false).negate();

            BigDecimal currentAssets = sumGroupNetDrMinusCr(snap, "Current Assets", true).add(closingStock);
            BigDecimal currentLiabilities = sumGroupNetDrMinusCr(snap, "Current Liabilities", false).negate();
            BigDecimal loansLiability = sumGroupNetDrMinusCr(snap, "Loans (Liability)", false).negate();
            BigDecimal capitalAccount = sumGroupNetDrMinusCr(snap, "Capital Account", false).negate();
            BigDecimal netProfit = new BigDecimal(pnl.signedNetProfit());
            BigDecimal equity = capitalAccount.add(netProfit);

            BigDecimal workingCapital = currentAssets.subtract(currentLiabilities);
            BigDecimal quickAssets = currentAssets.subtract(closingStock);
            BigDecimal sales = new BigDecimal(pnl.salesTotal());
            BigDecimal purchases = new BigDecimal(pnl.purchaseTotal());
            BigDecimal grossProfit = new BigDecimal(pnl.grossProfit()).subtract(new BigDecimal(pnl.grossLoss()));
            BigDecimal cogs = openingStock.add(purchases)
                .add(new BigDecimal(pnl.directExpensesTotal()))
                .subtract(closingStock);
            BigDecimal avgInventory = openingStock.add(closingStock)
                .divide(new BigDecimal("2"), HIGH_PREC);

            BigDecimal currentRatio = safeDivide(currentAssets, currentLiabilities, 2);
            BigDecimal quickRatio = safeDivide(quickAssets, currentLiabilities, 2);
            BigDecimal debtEquityRatio = safeDivide(loansLiability, equity, 2);
            BigDecimal grossProfitPct = safeDivide(grossProfit.multiply(HUNDRED), sales, 2);
            BigDecimal netProfitPct = safeDivide(netProfit.multiply(HUNDRED), sales, 2);
            BigDecimal returnOnWcPct = safeDivide(netProfit.multiply(HUNDRED), workingCapital, 2);
            BigDecimal invTurnover = safeDivide(cogs.max(BigDecimal.ZERO), avgInventory, 2);
            BigDecimal recvDays = safeDivide(sundryDebtors.multiply(DAYS_IN_YEAR), sales, 2);

            return new ReportingDtos.RatioAnalysisReportDto(
                fromDate,
                toDate,
                fmt(workingCapital, scale),
                fmt(cashInHand, scale),
                fmt(bankAccounts, scale),
                fmt(sundryDebtors, scale),
                fmt(sundryCreditors, scale),
                pnl.salesTotal(),
                pnl.purchaseTotal(),
                fmt(closingStock, scale),
                fmt(netProfit, scale),
                fmt(currentRatio, 2),
                fmt(quickRatio, 2),
                fmt(debtEquityRatio, 2),
                fmt(grossProfitPct, 2),
                fmt(netProfitPct, 2),
                fmt(returnOnWcPct, 2),
                fmt(invTurnover, 2),
                fmt(recvDays, 2)
            );
        });
    }

    public ReportingDtos.AgeingReportDto getAgeing(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String rawType,
        LocalDate rawAsOfDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            CompanyMeta company = loadCompanyMeta(companyId);
            LocalDate asOfDate = rawAsOfDate != null ? rawAsOfDate : defaultFyEnd(company.financialYearFrom());
            String type = "PAYABLES".equalsIgnoreCase(rawType) ? "PAYABLES" : "RECEIVABLES";
            return computeAgeingInTx(companyId, company, type, asOfDate);
        });
    }

    private ReportingDtos.ProfitAndLossReportDto computeProfitAndLossInTx(
        MoneyCraftPrincipal principal,
        UUID companyId,
        CompanyMeta company,
        LocalDate fromDate,
        LocalDate toDate,
        boolean includeOptional
    ) {
        int scale = company.currencyScale();
        LedgerComputationSnapshot snap = computeLedgers(companyId, fromDate, toDate, includeOptional);
        int stockItemCount = countActiveStockItems(companyId);
        boolean includeRevenueOpening = !fromDate.isAfter(company.booksBeginningFrom());

        BigDecimal openingStock;
        BigDecimal closingStock;
        if (stockItemCount > 0) {
            LocalDate valStart = earliestValuationStart(companyId, company);
            if (toDate.isBefore(valStart)) {
                openingStock = BigDecimal.ZERO;
                closingStock = BigDecimal.ZERO;
            } else {
                if (!fromDate.isAfter(company.booksBeginningFrom()) || fromDate.minusDays(1).isBefore(valStart)) {
                    openingStock = queryTotalStockItemOpeningValue(companyId);
                } else {
                    openingStock = new BigDecimal(
                        valuationService.getStockSummary(
                            principal, companyId, valStart, fromDate.minusDays(1), null, null
                        ).totalClosingValue()
                    );
                }
                closingStock = new BigDecimal(
                    valuationService.getStockSummary(
                        principal, companyId, valStart, toDate, null, null
                    ).totalClosingValue()
                );
            }
        } else {
            openingStock = BigDecimal.ZERO;
            for (ComputedLedger cl : snap.ledgers()) {
                GroupRow top = resolveTopGroup(cl.groupId(), snap.groupsById());
                GroupRow direct = cl.groupId() != null ? snap.groupsById().get(cl.groupId()) : null;
                if (direct != null && "Stock-in-Hand".equalsIgnoreCase(direct.name()) && cl.openDr().signum() > 0) {
                    openingStock = openingStock.add(cl.openDr());
                } else if (top != null && "Stock-in-Hand".equalsIgnoreCase(top.name()) && cl.openDr().signum() > 0) {
                    openingStock = openingStock.add(cl.openDr());
                }
            }
            closingStock = BigDecimal.ZERO;
        }

        BigDecimal salesTotal = BigDecimal.ZERO;
        BigDecimal purchaseTotal = BigDecimal.ZERO;
        BigDecimal directExpensesTotal = BigDecimal.ZERO;
        BigDecimal directIncomesTotal = BigDecimal.ZERO;
        BigDecimal indirectExpensesTotal = BigDecimal.ZERO;
        BigDecimal indirectIncomesTotal = BigDecimal.ZERO;

        List<ReportingDtos.FinancialStatementLineDto> salesChildren = new ArrayList<>();
        List<ReportingDtos.FinancialStatementLineDto> purchaseChildren = new ArrayList<>();
        List<ReportingDtos.FinancialStatementLineDto> dirExpChildren = new ArrayList<>();
        List<ReportingDtos.FinancialStatementLineDto> dirIncChildren = new ArrayList<>();
        List<ReportingDtos.FinancialStatementLineDto> indExpChildren = new ArrayList<>();
        List<ReportingDtos.FinancialStatementLineDto> indIncChildren = new ArrayList<>();

        for (ComputedLedger cl : snap.ledgers()) {
            if (cl.groupId() == null) {
                continue;
            }
            GroupRow top = resolveTopGroup(cl.groupId(), snap.groupsById());
            if (top == null || (!"INCOME".equals(top.nature()) && !"EXPENSES".equals(top.nature()))) {
                continue;
            }
            BigDecimal dr = cl.periodDr().add(includeRevenueOpening ? cl.initOpenDr() : BigDecimal.ZERO);
            BigDecimal cr = cl.periodCr().add(includeRevenueOpening ? cl.initOpenCr() : BigDecimal.ZERO);
            if (dr.signum() == 0 && cr.signum() == 0) {
                continue;
            }

            if ("INCOME".equals(top.nature()) && top.affectsGrossProfit()) {
                BigDecimal netCr = cr.subtract(dr);
                ReportingDtos.FinancialStatementLineDto item = ledgerStmtLine(cl, netCr, scale);
                if ("Sales Accounts".equalsIgnoreCase(top.name())) {
                    salesTotal = salesTotal.add(netCr);
                    salesChildren.add(item);
                } else {
                    directIncomesTotal = directIncomesTotal.add(netCr);
                    dirIncChildren.add(item);
                }
            } else if ("EXPENSES".equals(top.nature()) && top.affectsGrossProfit()) {
                BigDecimal netDr = dr.subtract(cr);
                ReportingDtos.FinancialStatementLineDto item = ledgerStmtLine(cl, netDr, scale);
                if ("Purchase Accounts".equalsIgnoreCase(top.name())) {
                    purchaseTotal = purchaseTotal.add(netDr);
                    purchaseChildren.add(item);
                } else {
                    directExpensesTotal = directExpensesTotal.add(netDr);
                    dirExpChildren.add(item);
                }
            } else if ("INCOME".equals(top.nature())) {
                BigDecimal netCr = cr.subtract(dr);
                indirectIncomesTotal = indirectIncomesTotal.add(netCr);
                indIncChildren.add(ledgerStmtLine(cl, netCr, scale));
            } else {
                BigDecimal netDr = dr.subtract(cr);
                indirectExpensesTotal = indirectExpensesTotal.add(netDr);
                indExpChildren.add(ledgerStmtLine(cl, netDr, scale));
            }
        }

        BigDecimal tradingSigned = salesTotal
            .add(directIncomesTotal)
            .add(closingStock)
            .subtract(openingStock)
            .subtract(purchaseTotal)
            .subtract(directExpensesTotal);
        BigDecimal grossProfit = tradingSigned.signum() > 0 ? tradingSigned : BigDecimal.ZERO;
        BigDecimal grossLoss = tradingSigned.signum() < 0 ? tradingSigned.negate() : BigDecimal.ZERO;

        BigDecimal signedNetProfit = tradingSigned.add(indirectIncomesTotal).subtract(indirectExpensesTotal);
        BigDecimal netProfit = signedNetProfit.signum() > 0 ? signedNetProfit : BigDecimal.ZERO;
        BigDecimal netLoss = signedNetProfit.signum() < 0 ? signedNetProfit.negate() : BigDecimal.ZERO;

        List<ReportingDtos.FinancialStatementLineDto> tradingDrLines = new ArrayList<>();
        if (openingStock.signum() != 0) {
            tradingDrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "OPENING_STOCK", "STOCK", "OPENING_STOCK", "Opening Stock",
                fmt(openingStock, scale), null, null, null, List.of()
            ));
        }
        if (purchaseTotal.signum() != 0 || !purchaseChildren.isEmpty()) {
            tradingDrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "PURCHASE_ACCOUNTS", "GROUP", "PURCHASES", "Purchase Accounts",
                fmt(purchaseTotal, scale), null, null, null, purchaseChildren
            ));
        }
        if (directExpensesTotal.signum() != 0 || !dirExpChildren.isEmpty()) {
            tradingDrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "DIRECT_EXPENSES", "GROUP", "DIRECT_EXPENSES", "Direct Expenses",
                fmt(directExpensesTotal, scale), null, null, null, dirExpChildren
            ));
        }
        if (grossProfit.signum() > 0) {
            tradingDrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "GROSS_PROFIT_CO", "PROFIT_LOSS", "GROSS_PROFIT", "Gross Profit c/o",
                fmt(grossProfit, scale), null, null, null, List.of()
            ));
        }

        List<ReportingDtos.FinancialStatementLineDto> tradingCrLines = new ArrayList<>();
        if (salesTotal.signum() != 0 || !salesChildren.isEmpty()) {
            tradingCrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "SALES_ACCOUNTS", "GROUP", "SALES", "Sales Accounts",
                fmt(salesTotal, scale), null, null, null, salesChildren
            ));
        }
        if (directIncomesTotal.signum() != 0 || !dirIncChildren.isEmpty()) {
            tradingCrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "DIRECT_INCOMES", "GROUP", "DIRECT_INCOMES", "Direct Incomes",
                fmt(directIncomesTotal, scale), null, null, null, dirIncChildren
            ));
        }
        if (closingStock.signum() != 0) {
            tradingCrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "CLOSING_STOCK", "STOCK", "CLOSING_STOCK", "Closing Stock",
                fmt(closingStock, scale), null, null, null, List.of()
            ));
        }
        if (grossLoss.signum() > 0) {
            tradingCrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "GROSS_LOSS_CO", "PROFIT_LOSS", "GROSS_LOSS", "Gross Loss c/o",
                fmt(grossLoss, scale), null, null, null, List.of()
            ));
        }

        BigDecimal tradingTotal = openingStock
            .add(purchaseTotal)
            .add(directExpensesTotal)
            .add(grossProfit);

        List<ReportingDtos.FinancialStatementLineDto> pnlDrLines = new ArrayList<>();
        if (grossLoss.signum() > 0) {
            pnlDrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "GROSS_LOSS_BF", "PROFIT_LOSS", "GROSS_LOSS_BF", "Gross Loss b/f",
                fmt(grossLoss, scale), null, null, null, List.of()
            ));
        }
        if (indirectExpensesTotal.signum() != 0 || !indExpChildren.isEmpty()) {
            pnlDrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "INDIRECT_EXPENSES", "GROUP", "INDIRECT_EXPENSES", "Indirect Expenses",
                fmt(indirectExpensesTotal, scale), null, null, null, indExpChildren
            ));
        }
        if (netProfit.signum() > 0) {
            pnlDrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "NET_PROFIT", "PROFIT_LOSS", "NET_PROFIT", "Net Profit",
                fmt(netProfit, scale), null, null, null, List.of()
            ));
        }

        List<ReportingDtos.FinancialStatementLineDto> pnlCrLines = new ArrayList<>();
        if (grossProfit.signum() > 0) {
            pnlCrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "GROSS_PROFIT_BF", "PROFIT_LOSS", "GROSS_PROFIT_BF", "Gross Profit b/f",
                fmt(grossProfit, scale), null, null, null, List.of()
            ));
        }
        if (indirectIncomesTotal.signum() != 0 || !indIncChildren.isEmpty()) {
            pnlCrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "INDIRECT_INCOMES", "GROUP", "INDIRECT_INCOMES", "Indirect Incomes",
                fmt(indirectIncomesTotal, scale), null, null, null, indIncChildren
            ));
        }
        if (netLoss.signum() > 0) {
            pnlCrLines.add(new ReportingDtos.FinancialStatementLineDto(
                "NET_LOSS", "PROFIT_LOSS", "NET_LOSS", "Net Loss",
                fmt(netLoss, scale), null, null, null, List.of()
            ));
        }

        BigDecimal pnlTotal = grossLoss.add(indirectExpensesTotal).add(netProfit);

        return new ReportingDtos.ProfitAndLossReportDto(
            fromDate,
            toDate,
            fmt(openingStock, scale),
            fmt(closingStock, scale),
            fmt(salesTotal, scale),
            fmt(purchaseTotal, scale),
            fmt(directExpensesTotal, scale),
            fmt(directIncomesTotal, scale),
            fmt(indirectExpensesTotal, scale),
            fmt(indirectIncomesTotal, scale),
            tradingDrLines,
            tradingCrLines,
            fmt(tradingTotal, scale),
            fmt(grossProfit, scale),
            fmt(grossLoss, scale),
            pnlDrLines,
            pnlCrLines,
            fmt(pnlTotal, scale),
            fmt(netProfit, scale),
            fmt(netLoss, scale),
            fmt(signedNetProfit, scale)
        );
    }

    private ReportingDtos.BalanceSheetReportDto computeBalanceSheetInTx(
        MoneyCraftPrincipal principal,
        UUID companyId,
        CompanyMeta company,
        LocalDate asOfDate,
        String format,
        boolean includeOptional
    ) {
        int scale = company.currencyScale();
        LocalDate cumulativeStart = earliestValuationStart(companyId, company);
        if (asOfDate.isBefore(cumulativeStart)) {
            cumulativeStart = asOfDate;
        }
        ReportingDtos.ProfitAndLossReportDto cumPnl = computeProfitAndLossInTx(
            principal, companyId, company, cumulativeStart, asOfDate, includeOptional
        );
        LedgerComputationSnapshot snap = computeLedgers(companyId, cumulativeStart, asOfDate, includeOptional);
        BigDecimal closingStock = new BigDecimal(cumPnl.closingStockValue());

        List<ReportingDtos.FinancialStatementLineDto> liabilityLines = new ArrayList<>();
        List<ReportingDtos.FinancialStatementLineDto> assetLines = new ArrayList<>();
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalAssets = BigDecimal.ZERO;

        List<GroupRow> topGroups = snap.groupsById().values().stream()
            .filter(g -> g.parentGroupId() == null && ("LIABILITIES".equals(g.nature()) || "ASSETS".equals(g.nature())))
            .sorted(Comparator.comparingInt(GroupRow::sortOrder).thenComparing(GroupRow::name))
            .toList();

        for (GroupRow top : topGroups) {
            BsNodeResult node = buildBalanceSheetGroupNode(
                top,
                snap,
                "Current Assets".equalsIgnoreCase(top.name()) ? closingStock : BigDecimal.ZERO,
                scale
            );
            if (node.signedDrMinusCr().signum() == 0 && node.children().isEmpty()) {
                continue;
            }
            if (node.signedDrMinusCr().signum() < 0) {
                BigDecimal crAmt = node.signedDrMinusCr().negate();
                totalLiabilities = totalLiabilities.add(crAmt);
                liabilityLines.add(new ReportingDtos.FinancialStatementLineDto(
                    top.id().toString(),
                    "GROUP",
                    top.scheduleIiiLineCode(),
                    top.name(),
                    fmt(crAmt, scale),
                    null,
                    null,
                    top.id(),
                    node.children()
                ));
            } else if (node.signedDrMinusCr().signum() > 0) {
                BigDecimal drAmt = node.signedDrMinusCr();
                totalAssets = totalAssets.add(drAmt);
                assetLines.add(new ReportingDtos.FinancialStatementLineDto(
                    top.id().toString(),
                    "GROUP",
                    top.scheduleIiiLineCode(),
                    top.name(),
                    fmt(drAmt, scale),
                    null,
                    null,
                    top.id(),
                    node.children()
                ));
            }
        }

        // Profit & Loss A/c ledger + cumulative Net Profit / Loss
        BigDecimal pnlLedgerCrMinusDr = BigDecimal.ZERO;
        for (ComputedLedger cl : snap.ledgers()) {
            if (cl.groupId() == null) {
                pnlLedgerCrMinusDr = pnlLedgerCrMinusDr.add(cl.closeCr().subtract(cl.closeDr()));
            }
        }
        BigDecimal cumulativeProfitCr = pnlLedgerCrMinusDr.add(new BigDecimal(cumPnl.signedNetProfit()));
        if (cumulativeProfitCr.signum() > 0) {
            totalLiabilities = totalLiabilities.add(cumulativeProfitCr);
            liabilityLines.add(new ReportingDtos.FinancialStatementLineDto(
                "PROFIT_AND_LOSS_AC",
                "PROFIT_LOSS",
                "EQ_RESERVES_SURPLUS",
                "Profit & Loss A/c",
                fmt(cumulativeProfitCr, scale),
                null,
                null,
                null,
                List.of()
            ));
        } else if (cumulativeProfitCr.signum() < 0) {
            BigDecimal lossDr = cumulativeProfitCr.negate();
            totalAssets = totalAssets.add(lossDr);
            assetLines.add(new ReportingDtos.FinancialStatementLineDto(
                "PROFIT_AND_LOSS_AC",
                "PROFIT_LOSS",
                "EQ_RESERVES_SURPLUS",
                "Profit & Loss A/c",
                fmt(lossDr, scale),
                null,
                null,
                null,
                List.of()
            ));
        }

        // Difference in Opening Balances (if initial ledger openings were asymmetric)
        BigDecimal initDrSum = BigDecimal.ZERO;
        BigDecimal initCrSum = BigDecimal.ZERO;
        for (ComputedLedger cl : snap.ledgers()) {
            initDrSum = initDrSum.add(cl.initOpenDr());
            initCrSum = initCrSum.add(cl.initOpenCr());
        }
        BigDecimal openDiffDrMinusCr = initDrSum.subtract(initCrSum);
        if (openDiffDrMinusCr.signum() > 0) {
            totalLiabilities = totalLiabilities.add(openDiffDrMinusCr);
            liabilityLines.add(new ReportingDtos.FinancialStatementLineDto(
                "OPENING_BALANCE_DIFF",
                "DIFF",
                "DIFF_OPENING",
                "Difference in Opening Balances",
                fmt(openDiffDrMinusCr, scale),
                null,
                null,
                null,
                List.of()
            ));
        } else if (openDiffDrMinusCr.signum() < 0) {
            BigDecimal diffAsset = openDiffDrMinusCr.negate();
            totalAssets = totalAssets.add(diffAsset);
            assetLines.add(new ReportingDtos.FinancialStatementLineDto(
                "OPENING_BALANCE_DIFF",
                "DIFF",
                "DIFF_OPENING",
                "Difference in Opening Balances",
                fmt(diffAsset, scale),
                null,
                null,
                null,
                List.of()
            ));
        }

        BigDecimal diff = totalLiabilities.subtract(totalAssets).abs();
        boolean balanced = diff.compareTo(BigDecimal.ZERO) == 0;

        return new ReportingDtos.BalanceSheetReportDto(
            asOfDate,
            format,
            liabilityLines,
            assetLines,
            fmt(closingStock, scale),
            fmt(cumulativeProfitCr, scale),
            fmt(openDiffDrMinusCr.abs(), scale),
            fmt(totalLiabilities, scale),
            fmt(totalAssets, scale),
            fmt(diff, scale),
            balanced
        );
    }

    private BsNodeResult buildBalanceSheetGroupNode(
        GroupRow group,
        LedgerComputationSnapshot snap,
        BigDecimal injectedClosingStock,
        int scale
    ) {
        BigDecimal signedDrMinusCr = BigDecimal.ZERO;
        List<ReportingDtos.FinancialStatementLineDto> children = new ArrayList<>();

        if (injectedClosingStock.signum() > 0) {
            signedDrMinusCr = signedDrMinusCr.add(injectedClosingStock);
            children.add(new ReportingDtos.FinancialStatementLineDto(
                "CLOSING_STOCK",
                "STOCK",
                "CA_INVENTORIES",
                "Closing Stock",
                fmt(injectedClosingStock, scale),
                null,
                null,
                null,
                List.of()
            ));
        }

        List<GroupRow> subGroups = snap.childGroupsByParent()
            .getOrDefault(group.id(), List.of());
        for (GroupRow childGrp : subGroups) {
            if ("Stock-in-Hand".equalsIgnoreCase(childGrp.name())) {
                // Integrated inventory replaces Stock-in-Hand opening with Closing Stock
                continue;
            }
            BsNodeResult childNode = buildBalanceSheetGroupNode(childGrp, snap, BigDecimal.ZERO, scale);
            if (childNode.signedDrMinusCr().signum() != 0) {
                signedDrMinusCr = signedDrMinusCr.add(childNode.signedDrMinusCr());
                BigDecimal displayAmt = "LIABILITIES".equals(group.nature())
                    ? childNode.signedDrMinusCr().negate()
                    : childNode.signedDrMinusCr();
                children.add(new ReportingDtos.FinancialStatementLineDto(
                    childGrp.id().toString(),
                    "GROUP",
                    childGrp.scheduleIiiLineCode(),
                    childGrp.name(),
                    fmt(displayAmt, scale),
                    null,
                    null,
                    childGrp.id(),
                    childNode.children()
                ));
            }
        }

        if (!"Stock-in-Hand".equalsIgnoreCase(group.name())) {
            List<ComputedLedger> directLedgers = snap.ledgersByGroup().getOrDefault(group.id(), List.of());
            for (ComputedLedger cl : directLedgers) {
                BigDecimal ledgerDrMinusCr = cl.closeDr().subtract(cl.closeCr());
                if (ledgerDrMinusCr.signum() == 0) {
                    continue;
                }
                signedDrMinusCr = signedDrMinusCr.add(ledgerDrMinusCr);
                BigDecimal displayAmt = "LIABILITIES".equals(group.nature())
                    ? ledgerDrMinusCr.negate()
                    : ledgerDrMinusCr;
                children.add(new ReportingDtos.FinancialStatementLineDto(
                    cl.id().toString(),
                    "LEDGER",
                    group.scheduleIiiLineCode(),
                    cl.name(),
                    fmt(displayAmt, scale),
                    null,
                    cl.id(),
                    group.id(),
                    List.of()
                ));
            }
        }

        return new BsNodeResult(signedDrMinusCr, children);
    }

    private ScheduleIiiRawBucket computeScheduleIiiBucket(
        MoneyCraftPrincipal principal,
        UUID companyId,
        CompanyMeta company,
        LocalDate asOfDate
    ) {
        LocalDate start = earliestValuationStart(companyId, company);
        if (asOfDate.isBefore(start)) {
            start = asOfDate;
        }
        ReportingDtos.ProfitAndLossReportDto pnl = computeProfitAndLossInTx(
            principal, companyId, company, start, asOfDate, false
        );
        LedgerComputationSnapshot snap = computeLedgers(companyId, start, asOfDate, false);

        Map<String, BigDecimal> lineTotals = new LinkedHashMap<>();
        Map<String, List<NoteEntry>> lineItems = new LinkedHashMap<>();

        for (ComputedLedger cl : snap.ledgers()) {
            BigDecimal netDrMinusCr = cl.closeDr().subtract(cl.closeCr());
            if (netDrMinusCr.signum() == 0) {
                continue;
            }
            if (cl.groupId() == null) {
                BigDecimal crAmt = netDrMinusCr.negate();
                addScheduleBucket(lineTotals, lineItems, "EQ_RESERVES_SURPLUS", cl.id().toString(), cl.name(), crAmt);
                continue;
            }
            GroupRow grp = snap.groupsById().get(cl.groupId());
            GroupRow top = resolveTopGroup(cl.groupId(), snap.groupsById());
            if (grp == null || top == null) {
                continue;
            }
            if ("Stock-in-Hand".equalsIgnoreCase(grp.name()) || "Stock-in-Hand".equalsIgnoreCase(top.name())) {
                continue;
            }
            if ("INCOME".equals(top.nature()) || "EXPENSES".equals(top.nature())) {
                continue;
            }
            String rawCode = firstNonBlank(grp.scheduleIiiLineCode(), top.scheduleIiiLineCode());
            String code = normalizeScheduleIiiCode(rawCode, top.nature());
            if (code.startsWith("EQ_") || code.startsWith("NCL_") || code.startsWith("CL_")) {
                BigDecimal liabAmt = netDrMinusCr.negate();
                addScheduleBucket(lineTotals, lineItems, code, cl.id().toString(), cl.name(), liabAmt);
            } else {
                addScheduleBucket(lineTotals, lineItems, code, cl.id().toString(), cl.name(), netDrMinusCr);
            }
        }

        BigDecimal closingStock = new BigDecimal(pnl.closingStockValue());
        if (closingStock.signum() != 0) {
            addScheduleBucket(
                lineTotals, lineItems, "CA_INVENTORIES", "CLOSING_STOCK", "Closing Stock", closingStock
            );
        }

        BigDecimal signedNp = new BigDecimal(pnl.signedNetProfit());
        if (signedNp.signum() != 0) {
            addScheduleBucket(
                lineTotals, lineItems, "EQ_RESERVES_SURPLUS", "CURRENT_PERIOD_PROFIT",
                "Surplus / (Deficit) in Statement of Profit & Loss", signedNp
            );
        }

        // Opening balance diff if any
        BigDecimal initDr = BigDecimal.ZERO;
        BigDecimal initCr = BigDecimal.ZERO;
        for (ComputedLedger cl : snap.ledgers()) {
            initDr = initDr.add(cl.initOpenDr());
            initCr = initCr.add(cl.initOpenCr());
        }
        BigDecimal openDiff = initDr.subtract(initCr);
        if (openDiff.signum() != 0) {
            addScheduleBucket(
                lineTotals, lineItems, "EQ_RESERVES_SURPLUS", "OPENING_BALANCE_DIFF",
                "Opening Balance Adjustment", openDiff
            );
        }

        return new ScheduleIiiRawBucket(lineTotals, lineItems, pnl);
    }

    private static String normalizeScheduleIiiCode(String rawCode, String topNature) {
        if (rawCode == null || rawCode.isBlank()) {
            return "LIABILITIES".equals(topNature) ? "CL_OTHER_CURRENT_LIABILITIES" : "CA_OTHER_CURRENT_ASSETS";
        }
        String norm = rawCode.trim().toUpperCase(Locale.ROOT);
        return switch (norm) {
            case "SCH3_SHARE_CAPITAL", "EQ_SHARE_CAPITAL" -> "EQ_SHARE_CAPITAL";
            case "SCH3_RESERVES_AND_SURPLUS", "EQ_RESERVES_SURPLUS" -> "EQ_RESERVES_SURPLUS";
            case "SCH3_BORROWINGS", "SCH3_SECURED_LOANS", "SCH3_UNSECURED_LOANS", "NCL_LONG_TERM_BORROWINGS" ->
                "NCL_LONG_TERM_BORROWINGS";
            case "NCL_LONG_TERM_PROVISIONS" -> "NCL_LONG_TERM_PROVISIONS";
            case "SCH3_SHORT_TERM_BORROWINGS", "CL_SHORT_TERM_BORROWINGS" -> "CL_SHORT_TERM_BORROWINGS";
            case "SCH3_TRADE_PAYABLES", "CL_TRADE_PAYABLES" -> "CL_TRADE_PAYABLES";
            case "SCH3_CURRENT_LIABILITIES", "SCH3_STATUTORY_DUES", "SCH3_INTER_BRANCH", "SCH3_SUSPENSE",
                 "CL_OTHER_CURRENT_LIABILITIES" -> "CL_OTHER_CURRENT_LIABILITIES";
            case "SCH3_PROVISIONS", "CL_SHORT_TERM_PROVISIONS" -> "CL_SHORT_TERM_PROVISIONS";
            case "SCH3_PPE_FIXED_ASSETS", "NCA_PPE_TANGIBLE" -> "NCA_PPE_TANGIBLE";
            case "SCH3_INVESTMENTS", "NCA_NON_CURRENT_INVESTMENTS" -> "NCA_NON_CURRENT_INVESTMENTS";
            case "SCH3_SECURITY_DEPOSITS", "SCH3_OTHER_NON_CURRENT_ASSETS", "NCA_LONG_TERM_LOANS_ADVANCES" ->
                "NCA_LONG_TERM_LOANS_ADVANCES";
            case "SCH3_INVENTORIES", "CA_INVENTORIES" -> "CA_INVENTORIES";
            case "SCH3_TRADE_RECEIVABLES", "CA_TRADE_RECEIVABLES" -> "CA_TRADE_RECEIVABLES";
            case "SCH3_CASH_AND_CASH_EQUIVALENTS", "SCH3_BANK_BALANCES", "CA_CASH_AND_EQUIVALENTS" ->
                "CA_CASH_AND_EQUIVALENTS";
            case "SCH3_LOANS_AND_ADVANCES", "CA_SHORT_TERM_LOANS_ADVANCES" -> "CA_SHORT_TERM_LOANS_ADVANCES";
            case "SCH3_CURRENT_ASSETS", "CA_OTHER_CURRENT_ASSETS" -> "CA_OTHER_CURRENT_ASSETS";
            default -> "LIABILITIES".equals(topNature) ? "CL_OTHER_CURRENT_LIABILITIES" : "CA_OTHER_CURRENT_ASSETS";
        };
    }

    private ReportingDtos.ScheduleIiiReportDto buildScheduleIiiReport(
        CompanyMeta company,
        LocalDate asOfDate,
        LocalDate comparativeAsOfDate,
        String entityFormat,
        String roundingScale,
        ScheduleIiiRawBucket cur,
        ScheduleIiiRawBucket comp
    ) {
        boolean nce = "NCE".equals(entityFormat);
        int outScale = "ACTUAL".equals(roundingScale) ? company.currencyScale() : 2;
        BigDecimal divisor = roundingDivisor(roundingScale);

        List<ScheduleLineSpec> bsSpecs = List.of(
            new ScheduleLineSpec("EQ_SHARE_CAPITAL",
                nce ? "Owners' Capital Account" : "Share Capital",
                nce ? "OWNERS_FUNDS" : "EQUITY"),
            new ScheduleLineSpec("EQ_RESERVES_SURPLUS", "Reserves and Surplus",
                nce ? "OWNERS_FUNDS" : "EQUITY"),
            new ScheduleLineSpec("NCL_LONG_TERM_BORROWINGS", "Long-Term Borrowings", "NON_CURRENT_LIABILITIES"),
            new ScheduleLineSpec("NCL_LONG_TERM_PROVISIONS", "Long-Term Provisions", "NON_CURRENT_LIABILITIES"),
            new ScheduleLineSpec("CL_SHORT_TERM_BORROWINGS", "Short-Term Borrowings", "CURRENT_LIABILITIES"),
            new ScheduleLineSpec("CL_TRADE_PAYABLES", "Trade Payables", "CURRENT_LIABILITIES"),
            new ScheduleLineSpec("CL_OTHER_CURRENT_LIABILITIES", "Other Current Liabilities", "CURRENT_LIABILITIES"),
            new ScheduleLineSpec("CL_SHORT_TERM_PROVISIONS", "Short-Term Provisions", "CURRENT_LIABILITIES"),
            new ScheduleLineSpec("NCA_PPE_TANGIBLE",
                "Property, Plant and Equipment", "NON_CURRENT_ASSETS"),
            new ScheduleLineSpec("NCA_NON_CURRENT_INVESTMENTS", "Non-Current Investments", "NON_CURRENT_ASSETS"),
            new ScheduleLineSpec("NCA_LONG_TERM_LOANS_ADVANCES",
                "Long-Term Loans and Advances", "NON_CURRENT_ASSETS"),
            new ScheduleLineSpec("CA_INVENTORIES", "Inventories", "CURRENT_ASSETS"),
            new ScheduleLineSpec("CA_TRADE_RECEIVABLES", "Trade Receivables", "CURRENT_ASSETS"),
            new ScheduleLineSpec("CA_CASH_AND_EQUIVALENTS", "Cash and Cash Equivalents", "CURRENT_ASSETS"),
            new ScheduleLineSpec("CA_SHORT_TERM_LOANS_ADVANCES",
                "Short-Term Loans and Advances", "CURRENT_ASSETS"),
            new ScheduleLineSpec("CA_OTHER_CURRENT_ASSETS", "Other Current Assets", "CURRENT_ASSETS")
        );

        Map<String, BigDecimal> scaledCur = new LinkedHashMap<>();
        Map<String, BigDecimal> scaledComp = new LinkedHashMap<>();
        BigDecimal eqLiabCur = BigDecimal.ZERO;
        BigDecimal assetsCur = BigDecimal.ZERO;

        for (ScheduleLineSpec spec : bsSpecs) {
            BigDecimal cVal = scaleDiv(cur.lineTotals().getOrDefault(spec.code(), BigDecimal.ZERO), divisor, outScale);
            BigDecimal pVal = scaleDiv(comp.lineTotals().getOrDefault(spec.code(), BigDecimal.ZERO), divisor, outScale);
            scaledCur.put(spec.code(), cVal);
            scaledComp.put(spec.code(), pVal);
            if (isAssetLineCode(spec.code())) {
                assetsCur = assetsCur.add(cVal);
            } else {
                eqLiabCur = eqLiabCur.add(cVal);
            }
        }

        // Preserve exact Schedule III BS equality under LAKHS/CRORES display rounding
        BigDecimal roundingResidual = assetsCur.subtract(eqLiabCur);
        if (!"ACTUAL".equals(roundingScale) && roundingResidual.signum() != 0) {
            scaledCur.put(
                "EQ_RESERVES_SURPLUS",
                scaledCur.get("EQ_RESERVES_SURPLUS").add(roundingResidual)
            );
            eqLiabCur = eqLiabCur.add(roundingResidual);
        }

        List<ReportingDtos.ScheduleIiiNoteDto> notes = new ArrayList<>();
        List<ReportingDtos.ScheduleIiiLineDto> bsLines = new ArrayList<>();
        int noteCounter = 1;

        for (ScheduleLineSpec spec : bsSpecs) {
            BigDecimal cVal = scaledCur.get(spec.code());
            BigDecimal pVal = scaledComp.get(spec.code());
            List<NoteEntry> curItems = cur.lineItems().getOrDefault(spec.code(), List.of());
            List<NoteEntry> compItems = comp.lineItems().getOrDefault(spec.code(), List.of());
            String noteNo = null;

            if (cVal.signum() != 0 || pVal.signum() != 0 || !curItems.isEmpty() || !compItems.isEmpty()) {
                noteNo = Integer.toString(noteCounter++);
                Map<String, BigDecimal> compById = new HashMap<>();
                for (NoteEntry ne : compItems) {
                    compById.merge(ne.name(), ne.amount(), BigDecimal::add);
                }
                List<ReportingDtos.ScheduleIiiNoteItemDto> noteItems = new ArrayList<>();
                Set<String> seenNames = new HashSet<>();
                for (NoteEntry ne : curItems) {
                    seenNames.add(ne.name());
                    BigDecimal ic = scaleDiv(ne.amount(), divisor, outScale);
                    BigDecimal ip = scaleDiv(compById.getOrDefault(ne.name(), BigDecimal.ZERO), divisor, outScale);
                    noteItems.add(new ReportingDtos.ScheduleIiiNoteItemDto(
                        ne.id(), ne.name(), fmt(ic, outScale), fmt(ip, outScale)
                    ));
                }
                for (NoteEntry ne : compItems) {
                    if (seenNames.add(ne.name())) {
                        BigDecimal ip = scaleDiv(ne.amount(), divisor, outScale);
                        noteItems.add(new ReportingDtos.ScheduleIiiNoteItemDto(
                            ne.id(), ne.name(), fmt(BigDecimal.ZERO, outScale), fmt(ip, outScale)
                        ));
                    }
                }
                String cTotStr = fmt(cVal, outScale);
                String pTotStr = fmt(pVal, outScale);
                notes.add(new ReportingDtos.ScheduleIiiNoteDto(
                    noteNo, spec.code(), spec.label(), cTotStr, cTotStr, pTotStr, noteItems
                ));
            }

            bsLines.add(new ReportingDtos.ScheduleIiiLineDto(
                spec.code(),
                spec.label(),
                spec.label(),
                spec.section(),
                noteNo,
                1,
                false,
                fmt(cVal, outScale),
                fmt(pVal, outScale)
            ));
        }

        List<ReportingDtos.ScheduleIiiLineDto> pnlLines = buildScheduleIiiPnlLines(
            cur.pnl(),
            comp.pnl(),
            divisor,
            outScale
        );

        BigDecimal netProfitScaled = cur.pnl() != null
            ? scaleDiv(new BigDecimal(cur.pnl().signedNetProfit()), divisor, outScale)
            : BigDecimal.ZERO;

        boolean balanced = eqLiabCur.compareTo(assetsCur) == 0;

        return new ReportingDtos.ScheduleIiiReportDto(
            asOfDate,
            comparativeAsOfDate,
            entityFormat,
            roundingScale,
            bsLines,
            pnlLines,
            notes,
            fmt(eqLiabCur, outScale),
            fmt(assetsCur, outScale),
            fmt(netProfitScaled, outScale),
            balanced
        );
    }

    private List<ReportingDtos.ScheduleIiiLineDto> buildScheduleIiiPnlLines(
        ReportingDtos.ProfitAndLossReportDto curPnl,
        ReportingDtos.ProfitAndLossReportDto compPnl,
        BigDecimal divisor,
        int outScale
    ) {
        BigDecimal cRev = curPnl != null
            ? new BigDecimal(curPnl.salesTotal()).add(new BigDecimal(curPnl.directIncomesTotal()))
            : BigDecimal.ZERO;
        BigDecimal pRev = compPnl != null
            ? new BigDecimal(compPnl.salesTotal()).add(new BigDecimal(compPnl.directIncomesTotal()))
            : BigDecimal.ZERO;

        BigDecimal cOtherInc = curPnl != null ? new BigDecimal(curPnl.indirectIncomesTotal()) : BigDecimal.ZERO;
        BigDecimal pOtherInc = compPnl != null ? new BigDecimal(compPnl.indirectIncomesTotal()) : BigDecimal.ZERO;

        BigDecimal cPurch = curPnl != null ? new BigDecimal(curPnl.purchaseTotal()) : BigDecimal.ZERO;
        BigDecimal pPurch = compPnl != null ? new BigDecimal(compPnl.purchaseTotal()) : BigDecimal.ZERO;

        BigDecimal cInvChange = curPnl != null
            ? new BigDecimal(curPnl.openingStockValue()).subtract(new BigDecimal(curPnl.closingStockValue()))
            : BigDecimal.ZERO;
        BigDecimal pInvChange = compPnl != null
            ? new BigDecimal(compPnl.openingStockValue()).subtract(new BigDecimal(compPnl.closingStockValue()))
            : BigDecimal.ZERO;

        BigDecimal cOtherExp = curPnl != null
            ? new BigDecimal(curPnl.directExpensesTotal()).add(new BigDecimal(curPnl.indirectExpensesTotal()))
            : BigDecimal.ZERO;
        BigDecimal pOtherExp = compPnl != null
            ? new BigDecimal(compPnl.directExpensesTotal()).add(new BigDecimal(compPnl.indirectExpensesTotal()))
            : BigDecimal.ZERO;

        BigDecimal cNp = curPnl != null ? new BigDecimal(curPnl.signedNetProfit()) : BigDecimal.ZERO;
        BigDecimal pNp = compPnl != null ? new BigDecimal(compPnl.signedNetProfit()) : BigDecimal.ZERO;

        return List.of(
            pnlSchedLine("REV_FROM_OPERATIONS", "I. Revenue from Operations", "INCOME", false,
                scaleDiv(cRev, divisor, outScale), scaleDiv(pRev, divisor, outScale), outScale),
            pnlSchedLine("OTHER_INCOME", "II. Other Income", "INCOME", false,
                scaleDiv(cOtherInc, divisor, outScale), scaleDiv(pOtherInc, divisor, outScale), outScale),
            pnlSchedLine("PURCHASES_STOCK_IN_TRADE", "Purchases of Stock-in-Trade", "EXPENSES", false,
                scaleDiv(cPurch, divisor, outScale), scaleDiv(pPurch, divisor, outScale), outScale),
            pnlSchedLine("CHANGES_IN_INVENTORIES", "Changes in Inventories", "EXPENSES", false,
                scaleDiv(cInvChange, divisor, outScale), scaleDiv(pInvChange, divisor, outScale), outScale),
            pnlSchedLine("OTHER_EXPENSES", "Other Expenses", "EXPENSES", false,
                scaleDiv(cOtherExp, divisor, outScale), scaleDiv(pOtherExp, divisor, outScale), outScale),
            pnlSchedLine("PROFIT_FOR_THE_PERIOD", "Profit / (Loss) for the Period", "PROFIT", true,
                scaleDiv(cNp, divisor, outScale), scaleDiv(pNp, divisor, outScale), outScale)
        );
    }

    private ReportingDtos.CashFlowReportDto computeCashFlowInTx(
        UUID companyId,
        CompanyMeta company,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        int scale = company.currencyScale();
        LedgerComputationSnapshot snap = computeLedgers(companyId, fromDate, toDate, false);

        Set<UUID> cashLedgerIds = new HashSet<>();
        BigDecimal openingCashAndBank = BigDecimal.ZERO;
        BigDecimal closingCashAndBank = BigDecimal.ZERO;

        for (ComputedLedger cl : snap.ledgers()) {
            if (isCashOrBankLedger(cl.groupId(), snap.groupsById())) {
                cashLedgerIds.add(cl.id());
                openingCashAndBank = openingCashAndBank.add(cl.openDr().subtract(cl.openCr()));
                closingCashAndBank = closingCashAndBank.add(cl.closeDr().subtract(cl.closeCr()));
            }
        }

        List<ActiveJournalEntryRow> periodEntries = businessJdbcTemplate.query(
            """
            SELECT e.journal_id, j.effective_date, e.ledger_id, e.side, e.amount
              FROM mc_entry e
              JOIN mc_journal j
                ON j.company_id = e.company_id
               AND j.id = e.journal_id
             WHERE j.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND e.posting_batch_no = j.current_posting_batch_no
               AND e.is_reversal = false
               AND j.effective_date >= ?
               AND j.effective_date <= ?
             ORDER BY j.effective_date ASC, e.journal_id ASC, e.id ASC
            """,
            (rs, rowNum) -> new ActiveJournalEntryRow(
                rs.getObject("journal_id", UUID.class),
                rs.getObject("effective_date", LocalDate.class),
                rs.getObject("ledger_id", UUID.class),
                rs.getString("side"),
                rs.getBigDecimal("amount")
            ),
            companyId,
            fromDate,
            toDate
        );

        Map<UUID, List<ActiveJournalEntryRow>> byJournal = new LinkedHashMap<>();
        for (ActiveJournalEntryRow row : periodEntries) {
            byJournal.computeIfAbsent(row.journalId(), k -> new ArrayList<>()).add(row);
        }

        Map<String, CashFlowHeadAcc> activityMap = new LinkedHashMap<>();
        Map<YearMonth, CashFlowMonthAcc> monthMap = new LinkedHashMap<>();
        YearMonth ymCursor = YearMonth.from(fromDate);
        YearMonth ymEnd = YearMonth.from(toDate);
        while (!ymCursor.isAfter(ymEnd)) {
            monthMap.put(ymCursor, new CashFlowMonthAcc());
            ymCursor = ymCursor.plusMonths(1);
        }

        BigDecimal opIn = BigDecimal.ZERO;
        BigDecimal opOut = BigDecimal.ZERO;
        BigDecimal invIn = BigDecimal.ZERO;
        BigDecimal invOut = BigDecimal.ZERO;
        BigDecimal finIn = BigDecimal.ZERO;
        BigDecimal finOut = BigDecimal.ZERO;

        Map<UUID, ComputedLedger> ledgersById = new HashMap<>();
        for (ComputedLedger cl : snap.ledgers()) {
            ledgersById.put(cl.id(), cl);
        }

        for (List<ActiveJournalEntryRow> jEntries : byJournal.values()) {
            BigDecimal cashDr = BigDecimal.ZERO;
            BigDecimal cashCr = BigDecimal.ZERO;
            for (ActiveJournalEntryRow e : jEntries) {
                if (cashLedgerIds.contains(e.ledgerId())) {
                    if ("DR".equals(e.side())) {
                        cashDr = cashDr.add(e.amount());
                    } else {
                        cashCr = cashCr.add(e.amount());
                    }
                }
            }
            BigDecimal netCashDelta = cashDr.subtract(cashCr);
            if (netCashDelta.signum() == 0) {
                continue;
            }

            boolean isInflow = netCashDelta.signum() > 0;
            BigDecimal absCashMove = netCashDelta.abs();
            String counterSide = isInflow ? "CR" : "DR";

            List<ActiveJournalEntryRow> counterRows = jEntries.stream()
                .filter(e -> !cashLedgerIds.contains(e.ledgerId()) && counterSide.equals(e.side()))
                .toList();
            BigDecimal counterSum = counterRows.stream()
                .map(ActiveJournalEntryRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            LocalDate effDate = jEntries.getFirst().effectiveDate();
            CashFlowMonthAcc monthAcc = monthMap.computeIfAbsent(
                YearMonth.from(effDate), k -> new CashFlowMonthAcc()
            );
            if (isInflow) {
                monthAcc.inflow = monthAcc.inflow.add(absCashMove);
            } else {
                monthAcc.outflow = monthAcc.outflow.add(absCashMove);
            }

            if (counterRows.isEmpty() || counterSum.signum() == 0) {
                if (isInflow) {
                    opIn = opIn.add(absCashMove);
                } else {
                    opOut = opOut.add(absCashMove);
                }
                recordCashActivity(activityMap, "OPERATING", "Operating Cash Movements", isInflow, absCashMove);
                continue;
            }

            BigDecimal allocatedSoFar = BigDecimal.ZERO;
            for (int i = 0; i < counterRows.size(); i++) {
                ActiveJournalEntryRow cRow = counterRows.get(i);
                BigDecimal share = (i == counterRows.size() - 1)
                    ? absCashMove.subtract(allocatedSoFar)
                    : absCashMove.multiply(cRow.amount(), HIGH_PREC)
                        .divide(counterSum, scale, RoundingMode.HALF_UP);
                allocatedSoFar = allocatedSoFar.add(share);

                ComputedLedger cl = ledgersById.get(cRow.ledgerId());
                String category = classifyCashFlowCategory(
                    cl != null ? cl.groupId() : null,
                    snap.groupsById()
                );
                GroupRow top = cl != null ? resolveTopGroup(cl.groupId(), snap.groupsById()) : null;
                String headName = top != null ? top.name() : (cl != null ? cl.name() : "Operating Activities");

                if ("INVESTING".equals(category)) {
                    if (isInflow) {
                        invIn = invIn.add(share);
                    } else {
                        invOut = invOut.add(share);
                    }
                } else if ("FINANCING".equals(category)) {
                    if (isInflow) {
                        finIn = finIn.add(share);
                    } else {
                        finOut = finOut.add(share);
                    }
                } else {
                    if (isInflow) {
                        opIn = opIn.add(share);
                    } else {
                        opOut = opOut.add(share);
                    }
                }
                recordCashActivity(activityMap, category, headName, isInflow, share);
            }
        }

        BigDecimal netOp = opIn.subtract(opOut);
        BigDecimal netInv = invIn.subtract(invOut);
        BigDecimal netFin = finIn.subtract(finOut);
        BigDecimal netMove = netOp.add(netInv).add(netFin);
        boolean reconciled = openingCashAndBank.add(netMove).compareTo(closingCashAndBank) == 0;

        List<ReportingDtos.CashFlowActivityRowDto> opLines = new ArrayList<>();
        List<ReportingDtos.CashFlowActivityRowDto> invLines = new ArrayList<>();
        List<ReportingDtos.CashFlowActivityRowDto> finLines = new ArrayList<>();
        List<ReportingDtos.CashFlowActivityRowDto> allActivities = new ArrayList<>();

        for (CashFlowHeadAcc acc : activityMap.values()) {
            BigDecimal net = acc.inflow.subtract(acc.outflow);
            ReportingDtos.CashFlowActivityRowDto dto = new ReportingDtos.CashFlowActivityRowDto(
                acc.category,
                acc.particulars,
                fmt(acc.inflow, scale),
                fmt(acc.outflow, scale),
                fmt(net, scale)
            );
            allActivities.add(dto);
            if ("INVESTING".equals(acc.category)) {
                invLines.add(dto);
            } else if ("FINANCING".equals(acc.category)) {
                finLines.add(dto);
            } else {
                opLines.add(dto);
            }
        }

        List<ReportingDtos.CashFlowMonthRowDto> monthlyRows = new ArrayList<>();
        BigDecimal runningCash = openingCashAndBank;
        for (Map.Entry<YearMonth, CashFlowMonthAcc> entry : monthMap.entrySet()) {
            YearMonth ym = entry.getKey();
            CashFlowMonthAcc mAcc = entry.getValue();
            BigDecimal net = mAcc.inflow.subtract(mAcc.outflow);
            runningCash = runningCash.add(net);
            LocalDate mStart = ym.atDay(1).isBefore(fromDate) ? fromDate : ym.atDay(1);
            LocalDate mEnd = ym.atEndOfMonth().isAfter(toDate) ? toDate : ym.atEndOfMonth();
            String label = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + ym.getYear();
            monthlyRows.add(new ReportingDtos.CashFlowMonthRowDto(
                label,
                mStart,
                mEnd,
                fmt(mAcc.inflow, scale),
                fmt(mAcc.outflow, scale),
                fmt(net, scale),
                fmt(runningCash, scale)
            ));
        }

        return new ReportingDtos.CashFlowReportDto(
            fromDate,
            toDate,
            fmt(openingCashAndBank, scale),
            fmt(opIn, scale),
            fmt(opOut, scale),
            fmt(netOp, scale),
            fmt(invIn, scale),
            fmt(invOut, scale),
            fmt(netInv, scale),
            fmt(netInv, scale),
            fmt(finIn, scale),
            fmt(finOut, scale),
            fmt(netFin, scale),
            fmt(netFin, scale),
            fmt(netMove, scale),
            fmt(closingCashAndBank, scale),
            reconciled,
            opLines,
            invLines,
            finLines,
            allActivities,
            monthlyRows
        );
    }

    private ReportingDtos.AgeingReportDto computeAgeingInTx(
        UUID companyId,
        CompanyMeta company,
        String type,
        LocalDate asOfDate
    ) {
        int scale = company.currencyScale();
        boolean receivables = "RECEIVABLES".equals(type);

        Map<UUID, GroupRow> groupsById = loadCompanyGroups(companyId);

        List<RawBillAllocRow> rows = businessJdbcTemplate.query(
            """
            SELECT ba.id, ba.journal_id, ba.ledger_id AS party_ledger_id, l.name AS party_name,
                   l.gstin AS party_gstin, l.group_id, ba.allocation_type, ba.bill_name,
                   j.effective_date AS voucher_date, ba.due_date, ba.side, ba.amount
              FROM mc_bill_allocation ba
              JOIN mc_journal j
                ON j.company_id = ba.company_id
               AND j.id = ba.journal_id
              JOIN mc_ledger l
                ON l.company_id = ba.company_id
               AND l.id = ba.ledger_id
             WHERE ba.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.effective_date <= ?
               AND ba.allocation_type IN ('NEW_REF', 'ADVANCE', 'AGST_REF')
             ORDER BY j.effective_date ASC, ba.created_at ASC, ba.id ASC
            """,
            (rs, rowNum) -> new RawBillAllocRow(
                rs.getObject("id", UUID.class),
                rs.getObject("journal_id", UUID.class),
                rs.getObject("party_ledger_id", UUID.class),
                rs.getString("party_name"),
                rs.getString("party_gstin"),
                rs.getObject("group_id", UUID.class),
                rs.getString("allocation_type"),
                rs.getString("bill_name"),
                rs.getObject("voucher_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class),
                rs.getString("side"),
                rs.getBigDecimal("amount")
            ),
            companyId,
            asOfDate
        );

        Map<String, BillAcc> billMap = new LinkedHashMap<>();
        for (RawBillAllocRow r : rows) {
            if (r.billName() == null || r.billName().isBlank()) {
                continue;
            }
            boolean isDebtorSide = isUnderNamedBranch(r.groupId(), "Sundry Debtors", groupsById);
            boolean isCreditorSide = isUnderNamedBranch(r.groupId(), "Sundry Creditors", groupsById);
            if (receivables && isCreditorSide && !isDebtorSide) {
                continue;
            }
            if (!receivables && isDebtorSide && !isCreditorSide) {
                continue;
            }

            String key = r.partyLedgerId() + "::" + r.billName().trim().toLowerCase(Locale.ROOT);
            BillAcc acc = billMap.computeIfAbsent(key, k -> new BillAcc(
                r.id(),
                r.journalId(),
                r.partyLedgerId(),
                r.partyName(),
                r.partyGstin(),
                r.billName().trim(),
                r.voucherDate(),
                r.dueDate()
            ));

            if ("NEW_REF".equals(r.allocationType()) || "ADVANCE".equals(r.allocationType())) {
                if (acc.originVoucherId == null || r.voucherDate().isBefore(acc.billDate)) {
                    acc.originId = r.id();
                    acc.originVoucherId = r.journalId();
                    acc.billDate = r.voucherDate();
                }
                if (r.dueDate() != null && (acc.dueDate == null || r.dueDate().isBefore(acc.dueDate))) {
                    acc.dueDate = r.dueDate();
                }
            }
            if ("DR".equals(r.side())) {
                acc.drTotal = acc.drTotal.add(r.amount());
            } else {
                acc.crTotal = acc.crTotal.add(r.amount());
            }
        }

        List<ReportingDtos.AgeingBillRowDto> billDtos = new ArrayList<>();
        Map<UUID, PartyAgeingAcc> partyMap = new LinkedHashMap<>();
        BigDecimal total0To30 = BigDecimal.ZERO;
        BigDecimal total31To60 = BigDecimal.ZERO;
        BigDecimal total61To90 = BigDecimal.ZERO;
        BigDecimal totalOver90 = BigDecimal.ZERO;

        for (BillAcc acc : billMap.values()) {
            BigDecimal pending = receivables
                ? acc.drTotal.subtract(acc.crTotal)
                : acc.crTotal.subtract(acc.drTotal);
            if (pending.signum() <= 0) {
                continue;
            }
            BigDecimal origAmt = receivables ? acc.drTotal : acc.crTotal;
            LocalDate effDueDate = acc.dueDate != null ? acc.dueDate : acc.billDate;
            long ageDays = Math.max(0L, ChronoUnit.DAYS.between(acc.billDate, asOfDate));
            long overdueDays = Math.max(0L, ChronoUnit.DAYS.between(effDueDate, asOfDate));

            BigDecimal b0 = BigDecimal.ZERO;
            BigDecimal b31 = BigDecimal.ZERO;
            BigDecimal b61 = BigDecimal.ZERO;
            BigDecimal b90 = BigDecimal.ZERO;
            String bucketCode;
            if (overdueDays <= 30L) {
                bucketCode = "0_30";
                b0 = pending;
                total0To30 = total0To30.add(pending);
            } else if (overdueDays <= 60L) {
                bucketCode = "31_60";
                b31 = pending;
                total31To60 = total31To60.add(pending);
            } else if (overdueDays <= 90L) {
                bucketCode = "61_90";
                b61 = pending;
                total61To90 = total61To90.add(pending);
            } else {
                bucketCode = "GT_90";
                b90 = pending;
                totalOver90 = totalOver90.add(pending);
            }

            billDtos.add(new ReportingDtos.AgeingBillRowDto(
                acc.originId,
                acc.originVoucherId,
                acc.partyLedgerId,
                acc.partyName,
                acc.partyGstin,
                acc.billName,
                acc.billDate,
                effDueDate,
                ageDays,
                overdueDays,
                bucketCode,
                fmt(origAmt, scale),
                fmt(pending, scale),
                fmt(b0, scale),
                fmt(b31, scale),
                fmt(b61, scale),
                fmt(b90, scale)
            ));

            PartyAgeingAcc pAcc = partyMap.computeIfAbsent(
                acc.partyLedgerId,
                k -> new PartyAgeingAcc(acc.partyLedgerId, acc.partyName, acc.partyGstin)
            );
            pAcc.billCount++;
            pAcc.b0 = pAcc.b0.add(b0);
            pAcc.b31 = pAcc.b31.add(b31);
            pAcc.b61 = pAcc.b61.add(b61);
            pAcc.b90 = pAcc.b90.add(b90);
            pAcc.total = pAcc.total.add(pending);
        }

        billDtos.sort(Comparator.comparing(ReportingDtos.AgeingBillRowDto::billDate)
            .thenComparing(ReportingDtos.AgeingBillRowDto::billName));

        List<ReportingDtos.AgeingPartySummaryDto> partySummaries = partyMap.values().stream()
            .map(p -> new ReportingDtos.AgeingPartySummaryDto(
                p.ledgerId,
                p.partyName,
                p.partyGstin,
                p.billCount,
                fmt(p.b0, scale),
                fmt(p.b31, scale),
                fmt(p.b61, scale),
                fmt(p.b90, scale),
                fmt(p.total, scale)
            ))
            .toList();

        BigDecimal totalPending = total0To30.add(total31To60).add(total61To90).add(totalOver90);
        ReportingDtos.AgeingBucketTotalsDto bucketTotals = new ReportingDtos.AgeingBucketTotalsDto(
            fmt(total0To30, scale),
            fmt(total31To60, scale),
            fmt(total61To90, scale),
            fmt(totalOver90, scale),
            fmt(totalPending, scale)
        );

        return new ReportingDtos.AgeingReportDto(
            type,
            asOfDate,
            fmt(total0To30, scale),
            fmt(total31To60, scale),
            fmt(total61To90, scale),
            fmt(totalOver90, scale),
            fmt(totalPending, scale),
            bucketTotals,
            partySummaries,
            billDtos
        );
    }

    private LedgerComputationSnapshot computeLedgers(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        boolean includeOptional
    ) {
        Map<UUID, GroupRow> groupsById = loadCompanyGroups(companyId);
        Map<UUID, List<GroupRow>> childGroupsByParent = new LinkedHashMap<>();
        for (GroupRow g : groupsById.values()) {
            if (g.parentGroupId() != null) {
                childGroupsByParent.computeIfAbsent(g.parentGroupId(), k -> new ArrayList<>()).add(g);
            }
        }

        List<LedgerMasterRow> ledgerMasters = businessJdbcTemplate.query(
            """
            SELECT id, name, alias_code, group_id, opening_balance, opening_side
              FROM mc_ledger
             WHERE company_id = ?
               AND active = true
             ORDER BY name ASC
            """,
            (rs, rowNum) -> new LedgerMasterRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("alias_code"),
                rs.getObject("group_id", UUID.class),
                rs.getBigDecimal("opening_balance"),
                rs.getString("opening_side")
            ),
            companyId
        );

        Map<UUID, BigDecimal[]> movementMap = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT e.ledger_id,
                   COALESCE(SUM(CASE WHEN j.effective_date < ? AND e.side = 'DR' THEN e.amount ELSE 0 END), 0) AS pre_dr,
                   COALESCE(SUM(CASE WHEN j.effective_date < ? AND e.side = 'CR' THEN e.amount ELSE 0 END), 0) AS pre_cr,
                   COALESCE(SUM(CASE WHEN j.effective_date >= ? AND j.effective_date <= ? AND e.side = 'DR'
                                     THEN e.amount ELSE 0 END), 0) AS per_dr,
                   COALESCE(SUM(CASE WHEN j.effective_date >= ? AND j.effective_date <= ? AND e.side = 'CR'
                                     THEN e.amount ELSE 0 END), 0) AS per_cr
              FROM mc_entry e
              JOIN mc_journal j
                ON j.company_id = e.company_id
               AND j.id = e.journal_id
             WHERE j.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND e.posting_batch_no = j.current_posting_batch_no
               AND e.is_reversal = false
               AND j.effective_date <= ?
             GROUP BY e.ledger_id
            """,
            rs -> {
                movementMap.put(
                    rs.getObject("ledger_id", UUID.class),
                    new BigDecimal[]{
                        rs.getBigDecimal("pre_dr"),
                        rs.getBigDecimal("pre_cr"),
                        rs.getBigDecimal("per_dr"),
                        rs.getBigDecimal("per_cr")
                    }
                );
            },
            fromDate,
            fromDate,
            fromDate,
            toDate,
            fromDate,
            toDate,
            companyId,
            toDate
        );

        if (includeOptional) {
            businessJdbcTemplate.query(
                """
                SELECT jl.ledger_id,
                       COALESCE(SUM(CASE WHEN j.effective_date < ? AND jl.side = 'DR'
                                         THEN jl.amount ELSE 0 END), 0) AS pre_dr,
                       COALESCE(SUM(CASE WHEN j.effective_date < ? AND jl.side = 'CR'
                                         THEN jl.amount ELSE 0 END), 0) AS pre_cr,
                       COALESCE(SUM(CASE WHEN j.effective_date >= ? AND j.effective_date <= ? AND jl.side = 'DR'
                                         THEN jl.amount ELSE 0 END), 0) AS per_dr,
                       COALESCE(SUM(CASE WHEN j.effective_date >= ? AND j.effective_date <= ? AND jl.side = 'CR'
                                         THEN jl.amount ELSE 0 END), 0) AS per_cr
                  FROM mc_journal_line jl
                  JOIN mc_journal j
                    ON j.company_id = jl.company_id
                   AND j.id = jl.journal_id
                 WHERE j.company_id = ?
                   AND j.posting_nature = 'OPTIONAL'
                   AND j.status IN ('POSTED', 'DRAFT')
                   AND j.effective_date <= ?
                 GROUP BY jl.ledger_id
                """,
                rs -> {
                    UUID lid = rs.getObject("ledger_id", UUID.class);
                    BigDecimal[] cur = movementMap.computeIfAbsent(
                        lid,
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}
                    );
                    cur[0] = cur[0].add(rs.getBigDecimal("pre_dr"));
                    cur[1] = cur[1].add(rs.getBigDecimal("pre_cr"));
                    cur[2] = cur[2].add(rs.getBigDecimal("per_dr"));
                    cur[3] = cur[3].add(rs.getBigDecimal("per_cr"));
                },
                fromDate,
                fromDate,
                fromDate,
                toDate,
                fromDate,
                toDate,
                companyId,
                toDate
            );
        }

        List<ComputedLedger> computed = new ArrayList<>(ledgerMasters.size());
        Map<UUID, List<ComputedLedger>> ledgersByGroup = new LinkedHashMap<>();

        for (LedgerMasterRow lm : ledgerMasters) {
            BigDecimal initDr = "DR".equals(lm.openingSide()) ? lm.openingBalance() : BigDecimal.ZERO;
            BigDecimal initCr = "CR".equals(lm.openingSide()) ? lm.openingBalance() : BigDecimal.ZERO;
            BigDecimal[] mv = movementMap.get(lm.id());
            BigDecimal preDr = mv != null ? mv[0] : BigDecimal.ZERO;
            BigDecimal preCr = mv != null ? mv[1] : BigDecimal.ZERO;
            BigDecimal perDr = mv != null ? mv[2] : BigDecimal.ZERO;
            BigDecimal perCr = mv != null ? mv[3] : BigDecimal.ZERO;

            BigDecimal openNetDrMinusCr = initDr.subtract(initCr).add(preDr).subtract(preCr);
            BigDecimal openDr = openNetDrMinusCr.signum() > 0 ? openNetDrMinusCr : BigDecimal.ZERO;
            BigDecimal openCr = openNetDrMinusCr.signum() < 0 ? openNetDrMinusCr.negate() : BigDecimal.ZERO;

            BigDecimal closeNetDrMinusCr = openNetDrMinusCr.add(perDr).subtract(perCr);
            BigDecimal closeDr = closeNetDrMinusCr.signum() > 0 ? closeNetDrMinusCr : BigDecimal.ZERO;
            BigDecimal closeCr = closeNetDrMinusCr.signum() < 0 ? closeNetDrMinusCr.negate() : BigDecimal.ZERO;

            ComputedLedger cl = new ComputedLedger(
                lm.id(),
                lm.name(),
                lm.aliasCode(),
                lm.groupId(),
                initDr,
                initCr,
                openDr,
                openCr,
                perDr,
                perCr,
                closeDr,
                closeCr
            );
            computed.add(cl);
            if (lm.groupId() != null) {
                ledgersByGroup.computeIfAbsent(lm.groupId(), k -> new ArrayList<>()).add(cl);
            }
        }

        return new LedgerComputationSnapshot(groupsById, childGroupsByParent, computed, ledgersByGroup);
    }

    private List<ReportingDtos.TrialBalanceRowDto> buildTrialBalanceGroupTree(
        LedgerComputationSnapshot snap,
        int scale
    ) {
        List<GroupRow> roots = snap.groupsById().values().stream()
            .filter(g -> g.parentGroupId() == null)
            .sorted(Comparator.comparingInt(GroupRow::sortOrder).thenComparing(GroupRow::name))
            .toList();

        List<ReportingDtos.TrialBalanceRowDto> result = new ArrayList<>();
        for (GroupRow root : roots) {
            TbGroupBuild built = buildTbGroupNode(root, snap, 0, scale);
            if (built.nonZero()) {
                result.add(built.row());
            }
        }
        return result;
    }

    private TbGroupBuild buildTbGroupNode(
        GroupRow group,
        LedgerComputationSnapshot snap,
        int depth,
        int scale
    ) {
        BigDecimal openDr = BigDecimal.ZERO;
        BigDecimal openCr = BigDecimal.ZERO;
        BigDecimal perDr = BigDecimal.ZERO;
        BigDecimal perCr = BigDecimal.ZERO;
        BigDecimal closeDr = BigDecimal.ZERO;
        BigDecimal closeCr = BigDecimal.ZERO;
        List<ReportingDtos.TrialBalanceRowDto> children = new ArrayList<>();

        for (GroupRow sub : snap.childGroupsByParent().getOrDefault(group.id(), List.of())) {
            TbGroupBuild childBuilt = buildTbGroupNode(sub, snap, depth + 1, scale);
            if (childBuilt.nonZero()) {
                openDr = openDr.add(childBuilt.openDr());
                openCr = openCr.add(childBuilt.openCr());
                perDr = perDr.add(childBuilt.perDr());
                perCr = perCr.add(childBuilt.perCr());
                closeDr = closeDr.add(childBuilt.closeDr());
                closeCr = closeCr.add(childBuilt.closeCr());
                children.add(childBuilt.row());
            }
        }

        for (ComputedLedger cl : snap.ledgersByGroup().getOrDefault(group.id(), List.of())) {
            boolean nz = cl.openDr().signum() != 0
                || cl.openCr().signum() != 0
                || cl.periodDr().signum() != 0
                || cl.periodCr().signum() != 0
                || cl.closeDr().signum() != 0
                || cl.closeCr().signum() != 0;
            if (!nz) {
                continue;
            }
            openDr = openDr.add(cl.openDr());
            openCr = openCr.add(cl.openCr());
            perDr = perDr.add(cl.periodDr());
            perCr = perCr.add(cl.periodCr());
            closeDr = closeDr.add(cl.closeDr());
            closeCr = closeCr.add(cl.closeCr());

            children.add(new ReportingDtos.TrialBalanceRowDto(
                cl.id(),
                group.id(),
                group.name(),
                cl.id(),
                cl.name(),
                group.id(),
                cl.aliasCode(),
                cl.name(),
                group.nature(),
                "LEDGER",
                depth + 1,
                fmt(cl.openDr(), scale),
                fmt(cl.openCr(), scale),
                fmt(cl.periodDr(), scale),
                fmt(cl.periodCr(), scale),
                fmt(cl.closeDr(), scale),
                fmt(cl.closeCr(), scale),
                List.of()
            ));
        }

        boolean nonZero = openDr.signum() != 0
            || openCr.signum() != 0
            || perDr.signum() != 0
            || perCr.signum() != 0
            || closeDr.signum() != 0
            || closeCr.signum() != 0;

        ReportingDtos.TrialBalanceRowDto row = new ReportingDtos.TrialBalanceRowDto(
            group.id(),
            group.id(),
            group.name(),
            null,
            null,
            group.parentGroupId(),
            group.aliasCode(),
            group.name(),
            group.nature(),
            "GROUP",
            depth,
            fmt(openDr, scale),
            fmt(openCr, scale),
            fmt(perDr, scale),
            fmt(perCr, scale),
            fmt(closeDr, scale),
            fmt(closeCr, scale),
            children
        );

        return new TbGroupBuild(row, openDr, openCr, perDr, perCr, closeDr, closeCr, nonZero);
    }

    private Map<UUID, GroupRow> loadCompanyGroups(UUID companyId) {
        Map<UUID, GroupRow> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, name, alias_code, parent_group_id, nature, affects_gross_profit,
                   schedule_iii_line_code, sort_order
              FROM mc_account_group
             WHERE company_id = ?
             ORDER BY sort_order ASC, name ASC
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                map.put(id, new GroupRow(
                    id,
                    rs.getString("name"),
                    rs.getString("alias_code"),
                    rs.getObject("parent_group_id", UUID.class),
                    rs.getString("nature"),
                    rs.getBoolean("affects_gross_profit"),
                    rs.getString("schedule_iii_line_code"),
                    rs.getInt("sort_order")
                ));
            },
            companyId
        );
        return map;
    }

    private CompanyMeta loadCompanyMeta(UUID companyId) {
        List<CompanyMeta> list = businessJdbcTemplate.query(
            """
            SELECT financial_year_from, books_beginning_from, currency_scale
              FROM mc_company
             WHERE id = ?
            """,
            (rs, rowNum) -> new CompanyMeta(
                rs.getObject("financial_year_from", LocalDate.class),
                rs.getObject("books_beginning_from", LocalDate.class),
                rs.getInt("currency_scale")
            ),
            companyId
        );
        if (list.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-COMPANY-NOT-FOUND",
                "Company Not Found",
                "Company " + companyId + " does not exist."
            );
        }
        return list.getFirst();
    }

    private int countActiveStockItems(UUID companyId) {
        Integer cnt = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mc_stock_item WHERE company_id = ? AND active = true",
            Integer.class,
            companyId
        );
        return cnt != null ? cnt : 0;
    }

    private BigDecimal queryTotalStockItemOpeningValue(UUID companyId) {
        BigDecimal sum = businessJdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(opening_value), 0) FROM mc_stock_item WHERE company_id = ? AND active = true",
            BigDecimal.class,
            companyId
        );
        return sum != null ? sum : BigDecimal.ZERO;
    }

    private LocalDate earliestValuationStart(UUID companyId, CompanyMeta company) {
        LocalDate minVchDate = businessJdbcTemplate.queryForObject(
            """
            SELECT MIN(effective_date)
              FROM mc_journal
             WHERE company_id = ?
               AND status = 'POSTED'
            """,
            LocalDate.class,
            companyId
        );
        LocalDate base = company.booksBeginningFrom().isBefore(company.financialYearFrom())
            ? company.booksBeginningFrom()
            : company.financialYearFrom();
        if (minVchDate != null && minVchDate.isBefore(base)) {
            return minVchDate;
        }
        return base;
    }

    private BigDecimal queryUnadjustedForexDelta(UUID companyId, LocalDate asOfDate) {
        BigDecimal delta = businessJdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(ba.unadjusted_forex_delta), 0)
              FROM mc_bill_allocation ba
              JOIN mc_journal j
                ON j.company_id = ba.company_id
               AND j.id = ba.journal_id
             WHERE ba.company_id = ?
               AND ba.forex_swept_journal_id IS NULL
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.effective_date <= ?
            """,
            BigDecimal.class,
            companyId,
            asOfDate
        );
        return delta != null ? delta : BigDecimal.ZERO;
    }

    private BigDecimal sumGroupNetDrMinusCr(
        LedgerComputationSnapshot snap,
        String targetGroupName,
        boolean excludeStockInHand
    ) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ComputedLedger cl : snap.ledgers()) {
            if (cl.groupId() == null) {
                continue;
            }
            if (excludeStockInHand && isUnderNamedBranch(cl.groupId(), "Stock-in-Hand", snap.groupsById())) {
                continue;
            }
            if (isUnderNamedBranch(cl.groupId(), targetGroupName, snap.groupsById())) {
                sum = sum.add(cl.closeDr().subtract(cl.closeCr()));
            }
        }
        return sum;
    }

    private static GroupRow resolveTopGroup(UUID groupId, Map<UUID, GroupRow> groupsById) {
        if (groupId == null) {
            return null;
        }
        GroupRow cur = groupsById.get(groupId);
        int guard = 0;
        while (cur != null && cur.parentGroupId() != null && guard < 32) {
            GroupRow parent = groupsById.get(cur.parentGroupId());
            if (parent == null) {
                break;
            }
            cur = parent;
            guard++;
        }
        return cur;
    }

    private static boolean isUnderNamedBranch(
        UUID groupId,
        String targetName,
        Map<UUID, GroupRow> groupsById
    ) {
        GroupRow cur = groupId != null ? groupsById.get(groupId) : null;
        int guard = 0;
        while (cur != null && guard < 32) {
            if (targetName.equalsIgnoreCase(cur.name())) {
                return true;
            }
            cur = cur.parentGroupId() != null ? groupsById.get(cur.parentGroupId()) : null;
            guard++;
        }
        return false;
    }

    private static boolean isCashOrBankLedger(UUID groupId, Map<UUID, GroupRow> groupsById) {
        GroupRow cur = groupId != null ? groupsById.get(groupId) : null;
        int guard = 0;
        while (cur != null && guard < 32) {
            if (CASH_AND_BANK_GROUPS.contains(cur.name().toLowerCase(Locale.ROOT))) {
                return true;
            }
            cur = cur.parentGroupId() != null ? groupsById.get(cur.parentGroupId()) : null;
            guard++;
        }
        return false;
    }

    private static String classifyCashFlowCategory(UUID groupId, Map<UUID, GroupRow> groupsById) {
        GroupRow top = resolveTopGroup(groupId, groupsById);
        if (top == null) {
            return "OPERATING";
        }
        if ("Fixed Assets".equalsIgnoreCase(top.name()) || "Investments".equalsIgnoreCase(top.name())) {
            return "INVESTING";
        }
        if ("Capital Account".equalsIgnoreCase(top.name()) || "Loans (Liability)".equalsIgnoreCase(top.name())) {
            return "FINANCING";
        }
        return "OPERATING";
    }

    private static void recordCashActivity(
        Map<String, CashFlowHeadAcc> map,
        String category,
        String particulars,
        boolean isInflow,
        BigDecimal amount
    ) {
        String key = category + "::" + particulars;
        CashFlowHeadAcc acc = map.computeIfAbsent(key, k -> new CashFlowHeadAcc(category, particulars));
        if (isInflow) {
            acc.inflow = acc.inflow.add(amount);
        } else {
            acc.outflow = acc.outflow.add(amount);
        }
    }

    private static void addScheduleBucket(
        Map<String, BigDecimal> totals,
        Map<String, List<NoteEntry>> items,
        String code,
        String id,
        String name,
        BigDecimal amount
    ) {
        totals.merge(code, amount, BigDecimal::add);
        items.computeIfAbsent(code, k -> new ArrayList<>()).add(new NoteEntry(id, name, amount));
    }

    private static boolean isAssetLineCode(String code) {
        return code.startsWith("NCA_") || code.startsWith("CA_");
    }

    private static String normalizeRoundingScale(String raw) {
        if (raw == null || raw.isBlank()) {
            return "ACTUAL";
        }
        String up = raw.trim().toUpperCase(Locale.ROOT);
        if (Set.of("ACTUAL", "HUNDREDS", "THOUSANDS", "LAKHS", "CRORES").contains(up)) {
            return up;
        }
        return "ACTUAL";
    }

    private static BigDecimal roundingDivisor(String scale) {
        return switch (scale) {
            case "HUNDREDS" -> new BigDecimal("100");
            case "THOUSANDS" -> new BigDecimal("1000");
            case "LAKHS" -> new BigDecimal("100000");
            case "CRORES" -> new BigDecimal("10000000");
            default -> BigDecimal.ONE;
        };
    }

    private static BigDecimal scaleDiv(BigDecimal val, BigDecimal divisor, int outScale) {
        if (val == null || val.signum() == 0) {
            return BigDecimal.ZERO.setScale(outScale, RoundingMode.HALF_UP);
        }
        if (BigDecimal.ONE.compareTo(divisor) == 0) {
            return val.setScale(outScale, RoundingMode.HALF_UP);
        }
        return val.divide(divisor, outScale, RoundingMode.HALF_UP);
    }

    private static ReportingDtos.ScheduleIiiLineDto pnlSchedLine(
        String code,
        String label,
        String section,
        boolean subtotal,
        BigDecimal curAmt,
        BigDecimal compAmt,
        int outScale
    ) {
        return new ReportingDtos.ScheduleIiiLineDto(
            code,
            label,
            label,
            section,
            null,
            subtotal ? 0 : 1,
            subtotal,
            fmt(curAmt, outScale),
            fmt(compAmt, outScale)
        );
    }

    private static ReportingDtos.FinancialStatementLineDto ledgerStmtLine(
        ComputedLedger cl,
        BigDecimal amount,
        int scale
    ) {
        return new ReportingDtos.FinancialStatementLineDto(
            cl.id().toString(),
            "LEDGER",
            cl.aliasCode(),
            cl.name(),
            fmt(amount, scale),
            null,
            cl.id(),
            cl.groupId(),
            List.of()
        );
    }

    private static BigDecimal safeDivide(BigDecimal num, BigDecimal den, int scale) {
        if (num == null || den == null || den.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return num.divide(den, scale, RoundingMode.HALF_UP);
    }

    private static LocalDate defaultFyEnd(LocalDate fyStart) {
        return fyStart.plusYears(1).minusDays(1);
    }

    private static void validateDateRange(LocalDate fromDate, LocalDate toDate) {
        if (toDate.isBefore(fromDate)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-RPT-INVALID-DATE-RANGE",
                "Invalid Report Date Range",
                "toDate (" + toDate + ") must be on or after fromDate (" + fromDate + ")."
            );
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return "";
    }

    private static String fmt(BigDecimal val, int scale) {
        return ExactMoneyValidator.formatExactScale(val == null ? BigDecimal.ZERO : val, scale);
    }

    private record CompanyMeta(LocalDate financialYearFrom, LocalDate booksBeginningFrom, int currencyScale) {
    }

    private record GroupRow(
        UUID id,
        String name,
        String aliasCode,
        UUID parentGroupId,
        String nature,
        boolean affectsGrossProfit,
        String scheduleIiiLineCode,
        int sortOrder
    ) {
    }

    private record LedgerMasterRow(
        UUID id,
        String name,
        String aliasCode,
        UUID groupId,
        BigDecimal openingBalance,
        String openingSide
    ) {
    }

    private record ComputedLedger(
        UUID id,
        String name,
        String aliasCode,
        UUID groupId,
        BigDecimal initOpenDr,
        BigDecimal initOpenCr,
        BigDecimal openDr,
        BigDecimal openCr,
        BigDecimal periodDr,
        BigDecimal periodCr,
        BigDecimal closeDr,
        BigDecimal closeCr
    ) {
    }

    private record LedgerComputationSnapshot(
        Map<UUID, GroupRow> groupsById,
        Map<UUID, List<GroupRow>> childGroupsByParent,
        List<ComputedLedger> ledgers,
        Map<UUID, List<ComputedLedger>> ledgersByGroup
    ) {
    }

    private record TbGroupBuild(
        ReportingDtos.TrialBalanceRowDto row,
        BigDecimal openDr,
        BigDecimal openCr,
        BigDecimal perDr,
        BigDecimal perCr,
        BigDecimal closeDr,
        BigDecimal closeCr,
        boolean nonZero
    ) {
    }

    private record BsNodeResult(
        BigDecimal signedDrMinusCr,
        List<ReportingDtos.FinancialStatementLineDto> children
    ) {
    }

    private record NoteEntry(String id, String name, BigDecimal amount) {
    }

    private record ScheduleLineSpec(String code, String label, String section) {
    }

    private record ScheduleIiiRawBucket(
        Map<String, BigDecimal> lineTotals,
        Map<String, List<NoteEntry>> lineItems,
        ReportingDtos.ProfitAndLossReportDto pnl
    ) {
        static ScheduleIiiRawBucket empty() {
            return new ScheduleIiiRawBucket(Map.of(), Map.of(), null);
        }
    }

    private record ActiveJournalEntryRow(
        UUID journalId,
        LocalDate effectiveDate,
        UUID ledgerId,
        String side,
        BigDecimal amount
    ) {
    }

    private static final class CashFlowHeadAcc {
        private final String category;
        private final String particulars;
        private BigDecimal inflow = BigDecimal.ZERO;
        private BigDecimal outflow = BigDecimal.ZERO;

        CashFlowHeadAcc(String category, String particulars) {
            this.category = category;
            this.particulars = particulars;
        }
    }

    private static final class CashFlowMonthAcc {
        private BigDecimal inflow = BigDecimal.ZERO;
        private BigDecimal outflow = BigDecimal.ZERO;
    }

    private record RawBillAllocRow(
        UUID id,
        UUID journalId,
        UUID partyLedgerId,
        String partyName,
        String partyGstin,
        UUID groupId,
        String allocationType,
        String billName,
        LocalDate voucherDate,
        LocalDate dueDate,
        String side,
        BigDecimal amount
    ) {
    }

    private static final class BillAcc {
        private UUID originId;
        private UUID originVoucherId;
        private final UUID partyLedgerId;
        private final String partyName;
        private final String partyGstin;
        private final String billName;
        private LocalDate billDate;
        private LocalDate dueDate;
        private BigDecimal drTotal = BigDecimal.ZERO;
        private BigDecimal crTotal = BigDecimal.ZERO;

        BillAcc(
            UUID originId,
            UUID originVoucherId,
            UUID partyLedgerId,
            String partyName,
            String partyGstin,
            String billName,
            LocalDate billDate,
            LocalDate dueDate
        ) {
            this.originId = originId;
            this.originVoucherId = originVoucherId;
            this.partyLedgerId = partyLedgerId;
            this.partyName = partyName;
            this.partyGstin = partyGstin;
            this.billName = billName;
            this.billDate = billDate;
            this.dueDate = dueDate;
        }
    }

    private static final class PartyAgeingAcc {
        private final UUID ledgerId;
        private final String partyName;
        private final String partyGstin;
        private int billCount;
        private BigDecimal b0 = BigDecimal.ZERO;
        private BigDecimal b31 = BigDecimal.ZERO;
        private BigDecimal b61 = BigDecimal.ZERO;
        private BigDecimal b90 = BigDecimal.ZERO;
        private BigDecimal total = BigDecimal.ZERO;

        PartyAgeingAcc(UUID ledgerId, String partyName, String partyGstin) {
            this.ledgerId = ledgerId;
            this.partyName = partyName;
            this.partyGstin = partyGstin;
        }
    }
}
