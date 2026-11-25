package com.raster.moneycraft.accounting.spi;

import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Hexagonal SPI allowing the Extension Workbench bounded context (EXT-004) to enforce
 * active company DMN 1.6 / FEEL validation and warning rules identically across browser UI,
 * direct REST API, and Guided Spreadsheet Import without coupling accounting to extension.
 */
public interface VoucherExtensionValidator {

    void validateVoucher(VoucherExtensionContext context);

    record VoucherExtensionContext(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId,
        String voucherTypeName,
        String voucherTypeCategory,
        String status,
        String postingNature,
        LocalDate voucherDate,
        LocalDate effectiveDate,
        String voucherNumber,
        String supplierInvoiceNo,
        String narration,
        String currencyCode,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        int lineCount,
        String origin,
        List<String> ledgerNames,
        VoucherDtos.UpsertVoucherRequest request
    ) {
    }
}
