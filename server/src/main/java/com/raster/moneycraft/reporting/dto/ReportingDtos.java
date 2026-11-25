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

    // =========================================================================
    // Level 2 / Level 3 Tally Reports: Statistics, Exceptions, Account Books & Stock Query
    // =========================================================================

    public record StatisticsVoucherTypeRowDto(
        UUID voucherTypeId,
        String name,
        String abbreviation,
        String baseCategory,
        String shortcutKey,
        long postedCount,
        long optionalCount,
        long cancelledCount,
        long draftCount,
        long activeCount,
        long totalCount
    ) {
    }

    public record StatisticsMasterCountRowDto(
        String code,
        String name,
        long count,
        String route
    ) {
    }

    public record StatisticsReportDto(
        LocalDate fromDate,
        LocalDate toDate,
        List<StatisticsVoucherTypeRowDto> voucherTypeCounts,
        List<StatisticsMasterCountRowDto> masterCounts,
        long totalPostedVouchers,
        long totalOptionalVouchers,
        long totalCancelledVouchers,
        long totalDraftVouchers,
        long totalActiveVouchers,
        long totalVouchers,
        long totalMasterTypes
    ) {
    }

    public record ExceptionReportRowDto(
        UUID entityId,
        String entityType,
        LocalDate date,
        LocalDate dueDate,
        String referenceNumber,
        String voucherTypeName,
        String groupOrCategory,
        String particulars,
        String uomSymbol,
        String quantity,
        String rate,
        String debitAmount,
        String creditAmount,
        String balanceOrAmount,
        String normalSide,
        String actualSide,
        Long overdueDays,
        String status,
        String narration
    ) {
    }

    public record ExceptionReportDto(
        String type,
        String title,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOfDate,
        int rowCount,
        String totalQuantity,
        String totalDebit,
        String totalCredit,
        String totalAmount,
        List<ExceptionReportRowDto> rows
    ) {
    }

    public record AccountBookSummaryRowDto(
        UUID entityId,
        String rowType,
        String name,
        String parentGroupName,
        String openingDebit,
        String openingCredit,
        String periodDebit,
        String periodCredit,
        String closingDebit,
        String closingCredit
    ) {
    }

    public record AccountBookMonthRowDto(
        String monthKey,
        String monthLabel,
        LocalDate fromDate,
        LocalDate toDate,
        long voucherCount,
        String debitTotal,
        String creditTotal,
        String netAmount,
        String closingBalance,
        String closingSide
    ) {
    }

    public record AccountBookVoucherRowDto(
        UUID voucherId,
        LocalDate voucherDate,
        String voucherTypeName,
        String voucherTypeCategory,
        String voucherNumber,
        String particulars,
        String partyLedgerName,
        String partyGstin,
        String supplierInvoiceNo,
        String debitAmount,
        String creditAmount,
        String runningBalance,
        String runningSide,
        String status,
        String narration
    ) {
    }

    public record AccountBookReportDto(
        String bookType,
        String title,
        UUID groupId,
        String groupName,
        UUID ledgerId,
        String ledgerName,
        LocalDate fromDate,
        LocalDate toDate,
        long voucherCount,
        String openingDebit,
        String openingCredit,
        String totalDebit,
        String totalCredit,
        String closingDebit,
        String closingCredit,
        List<AccountBookSummaryRowDto> summaryRows,
        List<AccountBookMonthRowDto> monthlySummary,
        List<AccountBookVoucherRowDto> voucherRows
    ) {
    }

    public record StockQueryLastPartyTxDto(
        UUID voucherId,
        LocalDate voucherDate,
        String voucherTypeName,
        String voucherNumber,
        UUID partyLedgerId,
        String partyName,
        String quantity,
        String uomSymbol,
        String rate,
        String discountPct,
        String amount
    ) {
    }

    public record StockQueryGodownBatchRowDto(
        UUID godownId,
        String godownName,
        UUID batchId,
        String batchName,
        LocalDate mfgDate,
        LocalDate expiryDate,
        boolean expired,
        String closingQty,
        String closingRate,
        String closingValue
    ) {
    }

    public record StockQueryMovementRowDto(
        UUID voucherId,
        LocalDate voucherDate,
        String voucherTypeName,
        String voucherNumber,
        String particulars,
        String movementDirection,
        String quantity,
        String rate,
        String value
    ) {
    }

    public record StockQueryReportDto(
        UUID stockItemId,
        String stockItemName,
        String aliasCode,
        String partNo,
        UUID stockGroupId,
        String stockGroupName,
        UUID stockCategoryId,
        String stockCategoryName,
        String uomSymbol,
        String costingMethod,
        String hsnSacCode,
        String gstRatePct,
        String standardCost,
        String standardSellingPrice,
        LocalDate fromDate,
        LocalDate toDate,
        String openingQty,
        String openingRate,
        String openingValue,
        String inwardQty,
        String inwardValue,
        String outwardQty,
        String outwardValue,
        String closingQty,
        String closingRate,
        String closingValue,
        LocalDate lastPurchaseDate,
        String lastPurchaseParty,
        String lastPurchaseQty,
        String lastPurchaseRate,
        LocalDate lastSalesDate,
        String lastSalesParty,
        String lastSalesQty,
        String lastSalesRate,
        StockQueryLastPartyTxDto lastPurchase,
        StockQueryLastPartyTxDto lastSale,
        List<StockQueryGodownBatchRowDto> godownBatchBreakup,
        List<StockQueryMovementRowDto> movements
    ) {
    }
}
