package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Client DTO for Profit &amp; Loss A/c responses (/api/v1/companies/{id}/reports/profit-and-loss).
 */
public record ProfitAndLossReportDto(
    LocalDate fromDate,
    LocalDate toDate,
    BigDecimal openingStockValue,
    BigDecimal closingStockValue,
    BigDecimal salesTotal,
    BigDecimal purchaseTotal,
    BigDecimal directExpensesTotal,
    BigDecimal directIncomesTotal,
    BigDecimal indirectExpensesTotal,
    BigDecimal indirectIncomesTotal,
    BigDecimal tradingTotal,
    BigDecimal grossProfit,
    BigDecimal grossLoss,
    BigDecimal pnlTotal,
    BigDecimal netProfit,
    BigDecimal netLoss,
    BigDecimal signedNetProfit,
    List<Map<String, Object>> tradingDebitLines,
    List<Map<String, Object>> tradingCreditLines,
    List<Map<String, Object>> pnlDebitLines,
    List<Map<String, Object>> pnlCreditLines
) {
    public ProfitAndLossReportDto {
        tradingDebitLines = tradingDebitLines == null ? List.of() : List.copyOf(tradingDebitLines);
        tradingCreditLines = tradingCreditLines == null ? List.of() : List.copyOf(tradingCreditLines);
        pnlDebitLines = pnlDebitLines == null ? List.of() : List.copyOf(pnlDebitLines);
        pnlCreditLines = pnlCreditLines == null ? List.of() : List.copyOf(pnlCreditLines);
    }
}
