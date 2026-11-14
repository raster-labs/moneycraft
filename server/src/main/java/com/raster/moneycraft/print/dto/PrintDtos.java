package com.raster.moneycraft.print.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request contracts for Brand Identity v0.2.0 deterministic server-side PDF rendering
 * (Voucher Tax Invoices & Financial / Ageing / Inventory Statements).
 */
public final class PrintDtos {

    private PrintDtos() {
    }

    public record VoucherPrintRequest(
        String title,
        Boolean showNarration,
        Boolean showBillAllocations,
        Boolean showInventoryDetails,
        String copiesLabel
    ) {
    }

    public record ReportPrintRequest(
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOfDate,
        LocalDate comparativeAsOfDate,
        String format,
        String type,
        String entityFormat,
        String roundingScale,
        Boolean includeOptional,
        UUID stockGroupId,
        String valuationMethodOverride
    ) {
    }
}
