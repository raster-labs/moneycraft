package com.raster.moneycraft.desktop.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client DTOs for Level 2/3 Account Books, Exception Reports, Statistics, Stock Query,
 * and Voucher Keyboard Navigation responses (Wave W2B).
 */
public record AccountBookReportDto(
    String bookType,
    String title,
    UUID groupId,
    String groupName,
    UUID ledgerId,
    String ledgerName,
    LocalDate fromDate,
    LocalDate toDate,
    int voucherCount,
    BigDecimal openingDebit,
    BigDecimal openingCredit,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    BigDecimal closingDebit,
    BigDecimal closingCredit,
    List<Map<String, Object>> summaryRows,
    List<Map<String, Object>> monthlySummary,
    List<Map<String, Object>> voucherRows
) {
    public AccountBookReportDto {
        summaryRows = summaryRows == null ? List.of() : List.copyOf(summaryRows);
        monthlySummary = monthlySummary == null ? List.of() : List.copyOf(monthlySummary);
        voucherRows = voucherRows == null ? List.of() : List.copyOf(voucherRows);
    }

    public record ExceptionReportDto(
        String type,
        String title,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOfDate,
        int rowCount,
        BigDecimal totalQuantity,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal totalAmount,
        List<Map<String, Object>> rows
    ) {
        public ExceptionReportDto {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    public record StatisticsReportDto(
        LocalDate fromDate,
        LocalDate toDate,
        List<Map<String, Object>> voucherTypeCounts,
        List<Map<String, Object>> masterCounts,
        long totalPostedVouchers,
        long totalOptionalVouchers,
        long totalCancelledVouchers,
        long totalDraftVouchers,
        long totalActiveVouchers,
        long totalVouchers,
        int totalMasterTypes
    ) {
        public StatisticsReportDto {
            voucherTypeCounts = voucherTypeCounts == null ? List.of() : List.copyOf(voucherTypeCounts);
            masterCounts = masterCounts == null ? List.of() : List.copyOf(masterCounts);
        }
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
        BigDecimal gstRatePct,
        BigDecimal standardCost,
        BigDecimal standardSellingPrice,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal openingQty,
        BigDecimal openingRate,
        BigDecimal openingValue,
        BigDecimal inwardQty,
        BigDecimal inwardValue,
        BigDecimal outwardQty,
        BigDecimal outwardValue,
        BigDecimal closingQty,
        BigDecimal closingRate,
        BigDecimal closingValue,
        Map<String, Object> lastPurchase,
        Map<String, Object> lastSale,
        List<Map<String, Object>> godownBatchBreakup,
        List<Map<String, Object>> movements
    ) {
        public StockQueryReportDto {
            godownBatchBreakup = godownBatchBreakup == null ? List.of() : List.copyOf(godownBatchBreakup);
            movements = movements == null ? List.of() : List.copyOf(movements);
        }
    }

    public record AdjacentVoucherDto(
        boolean found,
        String direction,
        UUID currentVoucherId,
        UUID adjacentVoucherId,
        boolean hasPrevious,
        boolean hasNext,
        VoucherDto voucher
    ) {
    }

    public record LastNarrationDto(
        boolean found,
        String narration,
        UUID sourceVoucherId,
        String sourceVoucherNumber,
        LocalDate sourceVoucherDate,
        UUID voucherTypeId,
        String voucherTypeName
    ) {
    }
}
