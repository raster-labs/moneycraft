package com.raster.moneycraft.statutory.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 Statutory Compliance (Multi-GSTIN, Rule Packs, 6-Tier GST, Rel 7.2 Tobacco MRP,
 * GSTR-1/3B/9, IMS Reconciliation, 30-Day e-Invoice/EWB, TDS 26Q/FVU 1.2) and MCA Edit Log
 * wire contracts. Every monetary value, rate, and quantity is serialized as an exact string.
 */
public final class StatutoryDtos {

    private StatutoryDtos() {
    }

    // -------------------------------------------------------------------------
    // 1. Multi-GSTIN & Signed Declarative Statutory Rule Packs
    // -------------------------------------------------------------------------

    public record GstRegistrationDto(
        UUID id,
        UUID companyId,
        String registrationName,
        String stateCode,
        String stateName,
        String registrationType,
        String gstin,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String periodicity,
        String gstr1Periodicity,
        boolean einvoiceApplicable,
        boolean eInvoiceApplicable,
        LocalDate einvoiceApplicableFrom,
        LocalDate eInvoiceEffectiveFrom,
        String einvoiceBillFromPlace,
        boolean ewayBillApplicable,
        boolean eWayBillApplicable,
        LocalDate ewayBillApplicableFrom,
        String ewayBillThresholdOverride,
        String eWayBillThreshold,
        String defaultGstRatePct,
        String defaultHsnSacCode,
        String lutBondNo,
        String lutBondNumber,
        LocalDate lutValidFrom,
        LocalDate lutValidTo,
        boolean isDefault,
        boolean isPrimary,
        boolean active,
        long masterSeqId,
        long alterId,
        long versionNo
    ) {
    }

    public record UpsertGstRegistrationRequest(
        String registrationName,
        String stateCode,
        String stateName,
        String registrationType,
        String gstin,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @JsonAlias("gstr1Periodicity")
        String periodicity,
        @JsonAlias("eInvoiceApplicable")
        Boolean einvoiceApplicable,
        @JsonAlias("eInvoiceEffectiveFrom")
        LocalDate einvoiceApplicableFrom,
        String einvoiceBillFromPlace,
        @JsonAlias("eWayBillApplicable")
        Boolean ewayBillApplicable,
        LocalDate ewayBillApplicableFrom,
        @JsonAlias("eWayBillThreshold")
        String ewayBillThresholdOverride,
        String defaultGstRatePct,
        String defaultHsnSacCode,
        @JsonAlias("lutBondNumber")
        String lutBondNo,
        LocalDate lutValidFrom,
        LocalDate lutValidTo,
        @JsonAlias("isPrimary")
        Boolean isDefault,
        Boolean active,
        Long versionNo
    ) {
    }

    public record StatutoryRulePackDto(
        UUID id,
        String packCode,
        String countryCode,
        String jurisdiction,
        String fiscalYearLabel,
        String schemaVersion,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String b2clThreshold,
        String eWayBillThreshold,
        List<String> gstSlabs,
        List<String> inheritanceHierarchy,
        List<TdsNatureDto> tdsSections,
        Map<String, Object> rulesJson,
        String packSha256,
        String signatureAlg,
        String signatureStatus,
        boolean active
    ) {
    }

    // -------------------------------------------------------------------------
    // 2. GST Compute Preview (`POST /gst/compute-preview`) & Uncertain Vouchers
    // -------------------------------------------------------------------------

    public record GstLineBreakupDto(
        int lineNo,
        String description,
        String hsnSacCode,
        String uqcCode,
        String billedQty,
        String rateSourceLevel,
        String gstRatePct,
        String cessRatePct,
        String commercialTaxableValue,
        String apportionedExpenseValue,
        String declaredMrp,
        String deemedMrpTaxableValue,
        boolean isDeemedMrpValuation,
        String assessableValue,
        String cgstAmount,
        String sgstAmount,
        String sgstUtgstAmount,
        String igstAmount,
        String cessAmount,
        String totalTaxAmount
    ) {
    }

    public record GstSlabTotalDto(
        String gstRatePct,
        String assessableValue,
        String cgstAmount,
        String sgstAmount,
        String sgstUtgstAmount,
        String igstAmount,
        String cessAmount,
        String totalTaxAmount
    ) {
    }

    public record GstSuggestedTaxLineDto(
        String dutyHead,
        UUID ledgerId,
        String ledgerName,
        String side,
        String ratePct,
        String amount
    ) {
    }

    public record GstComputePreviewResponse(
        String supplyNature,
        String detailedSupplyNature,
        String companyStateCode,
        String placeOfSupplyState,
        List<GstLineBreakupDto> lineBreakups,
        List<GstSlabTotalDto> slabTotals,
        String totalAssessableValue,
        String totalCgst,
        String totalSgst,
        String totalIgst,
        String totalCess,
        String totalTax,
        List<GstSuggestedTaxLineDto> suggestedTaxLines
    ) {
    }

    public record UncertainTransactionDto(
        UUID voucherId,
        LocalDate voucherDate,
        String voucherTypeName,
        String voucherNumber,
        String partyName,
        String partyGstin,
        String exceptionCode,
        String uncertainReasonCode,
        String exceptionReason,
        String taxableAmount,
        String computedTaxAmount,
        String bookedTaxAmount,
        String differenceAmount,
        boolean conflictAccepted,
        UUID conflictAcceptedBy,
        String acceptedReason
    ) {
    }

    public record AcceptGstConflictRequest(
        String reason
    ) {
    }

    // -------------------------------------------------------------------------
    // 3. GSTR-1, GSTR-3B & Rel 7.2 One-Click GSTR-9 Annual Return
    // -------------------------------------------------------------------------

    public record Gstr1B2bRowDto(
        UUID voucherId,
        String partyGstin,
        String partyName,
        String invoiceNumber,
        LocalDate invoiceDate,
        String invoiceValue,
        String placeOfSupply,
        boolean reverseCharge,
        String gstRatePct,
        String taxableValue,
        String igstAmount,
        String cgstAmount,
        String sgstAmount,
        String cessAmount
    ) {
    }

    public record Gstr1B2clRowDto(
        UUID voucherId,
        String invoiceNumber,
        LocalDate invoiceDate,
        String invoiceValue,
        String placeOfSupply,
        String gstRatePct,
        String taxableValue,
        String igstAmount,
        String cessAmount
    ) {
    }

    public record Gstr1B2csRowDto(
        String supplyType,
        String placeOfSupply,
        String gstRatePct,
        String taxableValue,
        String igstAmount,
        String cgstAmount,
        String sgstAmount,
        String cessAmount
    ) {
    }

    public record Gstr1CdnrRowDto(
        UUID voucherId,
        String partyGstin,
        String partyName,
        String noteNumber,
        LocalDate noteDate,
        String noteType,
        String placeOfSupply,
        String noteValue,
        String taxableValue,
        String igstAmount,
        String cgstAmount,
        String sgstAmount,
        String cessAmount
    ) {
    }

    public record Gstr1ExportRowDto(
        UUID voucherId,
        String exportType,
        String invoiceNumber,
        LocalDate invoiceDate,
        String invoiceValue,
        String portCode,
        String shippingBillNo,
        LocalDate shippingBillDate,
        String taxableValue,
        String igstAmount,
        String cessAmount
    ) {
    }

    public record Gstr1HsnSummaryRowDto(
        String hsnSacCode,
        String description,
        String uqc,
        String uqcCode,
        String totalQty,
        String totalQuantity,
        String totalValue,
        String gstRatePct,
        String taxableValue,
        String igstAmount,
        String cgstAmount,
        String sgstAmount,
        String cessAmount
    ) {
    }

    public record Gstr1DocSummaryRowDto(
        String documentNature,
        String voucherTypeName,
        String baseCategory,
        String serialFrom,
        String serialTo,
        long totalCount,
        long cancelledCount,
        long netIssuedCount
    ) {
    }

    public record Gstr1ReportDto(
        LocalDate fromDate,
        LocalDate toDate,
        UUID gstRegistrationId,
        String gstin,
        List<Gstr1B2bRowDto> b2b,
        List<Gstr1B2clRowDto> b2cl,
        List<Gstr1B2csRowDto> b2cs,
        List<Gstr1CdnrRowDto> cdnr,
        List<Gstr1CdnrRowDto> cdnur,
        List<Gstr1ExportRowDto> exp,
        List<Gstr1HsnSummaryRowDto> hsnB2b,
        List<Gstr1HsnSummaryRowDto> hsnB2c,
        List<Gstr1DocSummaryRowDto> documentSummary,
        int uncertainCount,
        String totalTaxableValue,
        String totalIgst,
        String totalCgst,
        String totalSgst,
        String totalCess,
        String totalTax
    ) {
    }

    public record Gstr1ExportRequest(
        LocalDate fromDate,
        LocalDate toDate,
        UUID gstRegistrationId
    ) {
    }

    public record Gstr3bTaxBucketDto(
        String taxableValue,
        String igstAmount,
        String cgstAmount,
        String sgstAmount,
        String cessAmount,
        String totalTax
    ) {
    }

    public record Gstr3bReportDto(
        LocalDate fromDate,
        LocalDate toDate,
        String returnPeriod,
        UUID gstRegistrationId,
        String gstin,
        Gstr3bTaxBucketDto outwardTaxable,
        Gstr3bTaxBucketDto outwardZeroRated,
        Gstr3bTaxBucketDto outwardNilExempt,
        Gstr3bTaxBucketDto inwardRcm,
        Gstr3bTaxBucketDto eligibleItc,
        Map<String, Gstr3bTaxBucketDto> itcTable4aBreakdown,
        Gstr3bTaxBucketDto itcReversed,
        Gstr3bTaxBucketDto netTaxPayable,
        int imsAcceptedCount,
        String imsPendingItcDeferred,
        String imsItcReductionTotal,
        int uncertainCount
    ) {
    }

    public record Gstr9TableRowDto(
        String tableCode,
        String description,
        String taxableValue,
        String igstAmount,
        String cgstAmount,
        String sgstAmount,
        String cessAmount
    ) {
    }

    public record Gstr9ReportDto(
        UUID fiscalYearId,
        String financialYearLabel,
        String gstin,
        List<Gstr9TableRowDto> table4Outward,
        List<Gstr9TableRowDto> table5Exempt,
        List<Gstr9TableRowDto> table6ItcAvailed,
        List<Gstr9TableRowDto> table7ItcReversed,
        List<Gstr9TableRowDto> table8Reconciliation,
        List<Gstr9TableRowDto> table9TaxPaid,
        List<Gstr1HsnSummaryRowDto> table17HsnOutward,
        List<Gstr1HsnSummaryRowDto> table18HsnInward,
        String totalOutwardTaxable,
        String totalOutwardTax,
        String totalItcAvailed,
        String totalItcReversed,
        String netTaxPayable,
        String reconciliationDifference
    ) {
    }

    public record Gstr9ExportRequest(
        UUID fiscalYearId,
        String format
    ) {
    }

    // -------------------------------------------------------------------------
    // 4. Rel 7.2 IMS (Invoice Management System) Reconciliation
    // -------------------------------------------------------------------------

    public record ImsInvoiceDto(
        UUID id,
        UUID gstRegistrationId,
        String supplierGstin,
        String supplierName,
        String docType,
        String invoiceNumber,
        LocalDate invoiceDate,
        String placeOfSupplyState,
        boolean isRcm,
        String returnPeriod,
        String effectiveFilingPeriod,
        String invoiceValue,
        String taxableValue,
        String igstAmount,
        String cgstAmount,
        String sgstAmount,
        String cessAmount,
        String portalItcAmount,
        String itcReductionAmount,
        String imsStatus,
        String imsAction,
        String remarks,
        String matchStatus,
        UUID booksVoucherId,
        String booksInvoiceValue,
        String booksTaxAmount,
        long versionNo
    ) {
    }

    public record ImsImportRequest(
        String returnPeriod,
        UUID gstRegistrationId,
        List<Map<String, Object>> invoices
    ) {
    }

    public record ImsActionRequest(
        @JsonAlias("id")
        UUID imsInvoiceId,
        String action,
        String itcReductionAmount,
        String remarks,
        String effectiveFilingPeriod
    ) {
    }

    // -------------------------------------------------------------------------
    // 5. 30-Day e-Invoice & e-Way Bill Connector Contracts
    // -------------------------------------------------------------------------

    public record EinvoiceGenerateResponse(
        UUID id,
        UUID voucherId,
        String voucherNumber,
        String status,
        String irn,
        String ackNo,
        Instant ackDate,
        String signedQrCode,
        String ewbNo,
        Instant sessionValidUntil
    ) {
    }

    public record EinvoiceCancelRequest(
        String cancelReasonCode,
        String cancelRemarks
    ) {
    }

    public record EwayBillGenerateRequest(
        String transporterId,
        String transporterName,
        String transportMode,
        Integer distanceKm,
        String vehicleNo,
        String vehicleType,
        String transportDocNo,
        LocalDate transportDocDate
    ) {
    }

    public record EwayBillGenerateResponse(
        UUID id,
        UUID voucherId,
        String ewbNo,
        Instant ewbDate,
        Instant ewbValidUpto,
        String transportMode,
        int distanceKm,
        String vehicleNo,
        String status,
        Instant sessionValidUntil
    ) {
    }

    public record OfflineBulkExportRequest(
        List<UUID> voucherIds
    ) {
    }

    // -------------------------------------------------------------------------
    // 6. TDS Form 26Q, Challan ITNS 281 & Protean FVU 1.2
    // -------------------------------------------------------------------------

    public record TdsNatureDto(
        UUID id,
        String sectionCode,
        String paymentCode,
        String name,
        String description,
        String singleBillThreshold,
        String annualAggregateThreshold,
        String rateIndividualHufPct,
        String rateOtherDeducteePct,
        String noPanHigherRatePct,
        boolean isTcs,
        LocalDate effectiveFrom,
        boolean active
    ) {
    }

    public record CreateTdsChallanRequest(
        String quarter,
        UUID fiscalYearId,
        String assessmentYear,
        String sectionCode,
        String bsrCode,
        String challanSerialNo,
        @JsonAlias("tenderDate")
        LocalDate depositDate,
        UUID paymentJournalId,
        UUID bankLedgerId,
        String chequeDdUtrNo,
        String tdsAmount,
        String surchargeAmount,
        String cessAmount,
        String interestAmount,
        @JsonAlias("fee234eAmount")
        String feeSec234eAmount,
        String otherPenaltyAmount,
        String minorHeadCode,
        List<UUID> deductionIds
    ) {
    }

    public record TdsChallanDto(
        UUID id,
        String quarter,
        String assessmentYear,
        String sectionCode,
        String bsrCode,
        String challanSerialNo,
        LocalDate depositDate,
        LocalDate tenderDate,
        String tdsAmount,
        String surchargeAmount,
        String cessAmount,
        String interestAmount,
        String feeSec234eAmount,
        String otherPenaltyAmount,
        String totalAmount,
        String minorHeadCode,
        int linkedDeductionCount
    ) {
    }

    public record TdsDeductionRowDto(
        UUID id,
        UUID voucherId,
        String voucherNumber,
        LocalDate deductionDate,
        String sectionCode,
        String paymentCode,
        UUID partyLedgerId,
        String deducteeName,
        String deducteePan,
        String deducteePanMasked,
        boolean hasValidPan,
        boolean higherRate206aaApplied,
        boolean isNoPanHigherRate,
        String lowerCertNumber,
        String grossAmount,
        String grossCreditedAmount,
        String tdsRatePct,
        String appliedRatePct,
        String tdsAmount,
        String totalTaxDeducted,
        UUID challanId,
        String challanSerialNo
    ) {
    }

    public record TdsForm26qReportDto(
        String quarter,
        UUID fiscalYearId,
        String assessmentYear,
        List<TdsDeductionRowDto> deductions,
        List<TdsChallanDto> challans,
        String totalGrossAmount,
        String totalTdsDeducted,
        String totalTdsDeposited,
        String unpaidTdsAmount
    ) {
    }

    public record TdsExportFvuRequest(
        String quarter,
        UUID fiscalYearId
    ) {
    }

    // -------------------------------------------------------------------------
    // 7. Immutable MCA Rule 3(1) Edit Log (`Alt+Q`)
    // -------------------------------------------------------------------------

    public record EditLogChangedFieldDto(
        String fieldPath,
        Object beforeValue,
        Object afterValue
    ) {
    }

    public record EditLogVersionDto(
        UUID id,
        String entityType,
        UUID entityId,
        long versionNo,
        long masterId,
        long alterId,
        String operation,
        UUID actorPrincipalId,
        String actorUsername,
        Instant occurredAt,
        String voucherNumberSnapshot,
        LocalDate voucherDateSnapshot,
        Object canonicalSnapshot,
        List<EditLogChangedFieldDto> changedFields,
        String snapshotSha256Hex
    ) {
    }
}
