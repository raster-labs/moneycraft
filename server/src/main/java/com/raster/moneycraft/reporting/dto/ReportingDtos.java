package com.raster.moneycraft.reporting.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6 Financial Statements, MCA Schedule III, Cash Flow, Ratio Analysis,
 * Bills Receivable/Payable Ageing, and Global SmartFind wire contracts.
 * Every monetary balance, ratio, and percentage is serialized as an exact decimal String.
 */
public final class ReportingDtos {

    private ReportingDtos() {
    }

    public record TrialBalanceRowDto(
        UUID id,
        UUID groupId,
        String groupName,
        UUID ledgerId,
        String ledgerName,
        UUID parentGroupId,
        String code,
        String name,
        String nature,
        String rowType,
        int depthLevel,
        String openingDebit,
        String openingCredit,
        String periodDebit,
        String periodCredit,
        String closingDebit,
        String closingCredit,
        List<TrialBalanceRowDto> children
    ) {
    }

    public record TrialBalanceReportDto(
        LocalDate fromDate,
        LocalDate toDate,
        boolean includeOptional,
        List<TrialBalanceRowDto> groupRows,
        List<TrialBalanceRowDto> ledgerRows,
        String unadjustedForexDebit,
        String unadjustedForexCredit,
        String totalOpeningDebit,
        String totalOpeningCredit,
        String totalPeriodDebit,
        String totalPeriodCredit,
        String totalClosingDebit,
        String totalClosingCredit,
        String differenceAmount,
        boolean isBalanced
    ) {
    }

    public record FinancialStatementLineDto(
        String id,
        String lineType,
        String code,
        String name,
        String amount,
        String comparativeAmount,
        UUID ledgerId,
        UUID groupId,
        List<FinancialStatementLineDto> children
    ) {
    }

    public record ProfitAndLossReportDto(
        LocalDate fromDate,
        LocalDate toDate,
        String openingStockValue,
        String closingStockValue,
        String salesTotal,
        String purchaseTotal,
        String directExpensesTotal,
        String directIncomesTotal,
        String indirectExpensesTotal,
        String indirectIncomesTotal,
        List<FinancialStatementLineDto> tradingDebitLines,
        List<FinancialStatementLineDto> tradingCreditLines,
        String tradingTotal,
        String grossProfit,
        String grossLoss,
        List<FinancialStatementLineDto> pnlDebitLines,
        List<FinancialStatementLineDto> pnlCreditLines,
        String pnlTotal,
        String netProfit,
        String netLoss,
        String signedNetProfit
    ) {
    }

    public record BalanceSheetReportDto(
        LocalDate asOfDate,
        String format,
        List<FinancialStatementLineDto> liabilityLines,
        List<FinancialStatementLineDto> assetLines,
        String closingStockValue,
        String profitAndLossBalance,
        String openingBalanceDifference,
        String totalLiabilities,
        String totalAssets,
        String differenceAmount,
        boolean isBalanced
    ) {
    }

    public record ScheduleIiiLineDto(
        String lineCode,
        String label,
        String particulars,
        String section,
        String noteNo,
        int indentLevel,
        boolean isSubtotal,
        String currentAmount,
        String comparativeAmount
    ) {
    }

    public record ScheduleIiiNoteItemDto(
        String id,
        String name,
        String currentAmount,
        String comparativeAmount
    ) {
    }

    public record ScheduleIiiNoteDto(
        String noteNo,
        String lineCode,
        String title,
        String totalAmount,
        String currentTotal,
        String comparativeTotal,
        List<ScheduleIiiNoteItemDto> items
    ) {
    }

    public record ScheduleIiiReportDto(
        LocalDate asOfDate,
        LocalDate comparativeAsOfDate,
        String entityFormat,
        String roundingScale,
        List<ScheduleIiiLineDto> balanceSheetLines,
        List<ScheduleIiiLineDto> statementOfPnlLines,
        List<ScheduleIiiNoteDto> notes,
        String totalEquityAndLiabilities,
        String totalAssets,
        String netProfitForPeriod,
        boolean isBalanced
    ) {
    }

    public record CashFlowMonthRowDto(
        String monthLabel,
        LocalDate fromDate,
        LocalDate toDate,
        String inflow,
        String outflow,
        String netFlow,
        String closingBalance
    ) {
    }

    public record CashFlowActivityRowDto(
        String category,
        String particulars,
        String inflow,
        String outflow,
        String netFlow
    ) {
    }

    public record CashFlowReportDto(
        LocalDate fromDate,
        LocalDate toDate,
        String openingCashAndBank,
        String operatingInflow,
        String operatingOutflow,
        String netOperatingCashFlow,
        String investingInflow,
        String investingOutflow,
        String netInvestingCashFlow,
        String investingCashFlow,
        String financingInflow,
        String financingOutflow,
        String netFinancingCashFlow,
        String financingCashFlow,
        String netCashMovement,
        String closingCashAndBank,
        boolean isReconciled,
        List<CashFlowActivityRowDto> operatingLines,
        List<CashFlowActivityRowDto> investingLines,
        List<CashFlowActivityRowDto> financingLines,
        List<CashFlowActivityRowDto> activities,
        List<CashFlowMonthRowDto> monthlySummary
    ) {
    }

    public record RatioAnalysisReportDto(
        LocalDate fromDate,
        LocalDate toDate,
        String workingCapital,
        String cashInHand,
        String bankAccounts,
        String sundryDebtors,
        String sundryCreditors,
        String salesAccounts,
        String purchaseAccounts,
        String stockInHand,
        String netProfit,
        String currentRatio,
        String quickRatio,
        String debtEquityRatio,
        String grossProfitPct,
        String netProfitPct,
        String returnOnWorkingCapitalPct,
        String inventoryTurnover,
        String receivableTurnoverDays
    ) {
    }

    public record AgeingPartySummaryDto(
        UUID ledgerId,
        String partyName,
        String partyGstin,
        int billCount,
        String bucket0To30,
        String bucket31To60,
        String bucket61To90,
        String bucketOver90,
        String totalPending
    ) {
    }

    public record AgeingBillRowDto(
        UUID id,
        UUID voucherId,
        UUID ledgerId,
        String partyName,
        String partyGstin,
        String billName,
        LocalDate billDate,
        LocalDate dueDate,
        long ageDays,
        long overdueDays,
        String bucket,
        String originalAmount,
        String pendingAmount,
        String bucket0To30,
        String bucket31To60,
        String bucket61To90,
        String bucketOver90
    ) {
    }

    public record AgeingBucketTotalsDto(
        String bucket0To30,
        String bucket31To60,
        String bucket61To90,
        String bucketOver90,
        String totalPending
    ) {
    }

    public record AgeingReportDto(
        String type,
        LocalDate asOfDate,
        String bucket0To30,
        String bucket31To60,
        String bucket61To90,
        String bucketOver90,
        String totalPendingAmount,
        AgeingBucketTotalsDto bucketTotals,
        List<AgeingPartySummaryDto> partySummaries,
        List<AgeingBillRowDto> bills
    ) {
    }

    public record SmartFindHitDto(
        String id,
        UUID companyId,
        String companyName,
        String category,
        String entityType,
        String title,
        String subtitle,
        String matchedField,
        String matchedSnippet,
        String amount,
        String route
    ) {
    }

    public record SmartFindResponseDto(
        String query,
        String scope,
        long tookMs,
        int totalMatches,
        List<SmartFindHitDto> results
    ) {
    }
}
