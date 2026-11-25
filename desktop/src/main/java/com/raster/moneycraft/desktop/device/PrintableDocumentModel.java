package com.raster.moneycraft.desktop.device;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, typed business document model consumed by {@link RawDevicePrintCompiler} (DEV-001).
 */
public record PrintableDocumentModel(
    UUID documentId,
    UUID companyId,
    String companyLegalName,
    DocumentKind documentKind,
    String voucherNumber,
    long documentVersion,
    LocalDate documentDate,
    String currencySymbol,
    String customerName,
    String narration,
    List<PrintableLineItem> lineItems,
    BigDecimal totalAmount,
    String barcodeValue,
    String barcodeSymbology,
    CopyBanner copyBanner,
    boolean containsSelectedPrivateFields,
    boolean requestPaperCut,
    boolean requestCashDrawerKick
) {

    public enum DocumentKind {
        THERMAL_RECEIPT,
        CONTINUOUS_FORM_VOUCHER,
        BARCODE_LABEL
    }

    public enum CopyBanner {
        ORIGINAL,
        DUPLICATE_REPRINT
    }

    public PrintableDocumentModel {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(companyLegalName, "companyLegalName must not be null");
        Objects.requireNonNull(documentKind, "documentKind must not be null");
        Objects.requireNonNull(voucherNumber, "voucherNumber must not be null");
        Objects.requireNonNull(documentDate, "documentDate must not be null");
        Objects.requireNonNull(currencySymbol, "currencySymbol must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(copyBanner, "copyBanner must not be null");

        if (documentVersion <= 0L) {
            throw new IllegalArgumentException("documentVersion must be positive.");
        }
        lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
    }

    public PrintableDocumentModel asDuplicateReprint() {
        return new PrintableDocumentModel(
            documentId,
            companyId,
            companyLegalName,
            documentKind,
            voucherNumber,
            documentVersion,
            documentDate,
            currencySymbol,
            customerName,
            narration,
            lineItems,
            totalAmount,
            barcodeValue,
            barcodeSymbology,
            CopyBanner.DUPLICATE_REPRINT,
            containsSelectedPrivateFields,
            requestPaperCut,
            false
        );
    }
}
