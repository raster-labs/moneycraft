package com.raster.moneycraft.accounting.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 3 ACID Double-Entry Voucher Engine wire contracts.
 * All monetary amounts, rates, and percentages are serialized as exact strings.
 */
public final class VoucherDtos {

    private VoucherDtos() {
    }

    public record UpsertVoucherRequest(
        UUID voucherTypeId,
        String voucherTypeName,
        UUID voucherClassId,
        String voucherNumber,
        LocalDate voucherDate,
        LocalDate effectiveDate,
        LocalDate reversingApplicableUpto,
        String persistedView,
        String postingNature,
        String action,
        String supplierInvoiceNo,
        LocalDate supplierInvoiceDate,
        UUID partyLedgerId,
        UUID accountingLedgerId,
        String placeOfSupplyState,
        UUID companyGstinId,
        String partyGstin,
        String partyMailingName,
        String partyStateCode,
        Boolean isReverseCharge,
        String exportType,
        String currencyCode,
        String exchangeRate,
        String narration,
        String sourceGuid,
        Long versionNo,
        List<VoucherLineRequest> lines,
        List<VoucherInventoryLineDto> inventoryLines
    ) {
        public UpsertVoucherRequest(
            UUID voucherTypeId,
            String voucherTypeName,
            UUID voucherClassId,
            String voucherNumber,
            LocalDate voucherDate,
            LocalDate effectiveDate,
            LocalDate reversingApplicableUpto,
            String persistedView,
            String postingNature,
            String action,
            String supplierInvoiceNo,
            LocalDate supplierInvoiceDate,
            UUID partyLedgerId,
            UUID accountingLedgerId,
            String placeOfSupplyState,
            UUID companyGstinId,
            String partyGstin,
            String partyMailingName,
            String partyStateCode,
            Boolean isReverseCharge,
            String exportType,
            String currencyCode,
            String exchangeRate,
            String narration,
            String sourceGuid,
            Long versionNo,
            List<VoucherLineRequest> lines
        ) {
            this(
                voucherTypeId,
                voucherTypeName,
                voucherClassId,
                voucherNumber,
                voucherDate,
                effectiveDate,
                reversingApplicableUpto,
                persistedView,
                postingNature,
                action,
                supplierInvoiceNo,
                supplierInvoiceDate,
                partyLedgerId,
                accountingLedgerId,
                placeOfSupplyState,
                companyGstinId,
                partyGstin,
                partyMailingName,
                partyStateCode,
                isReverseCharge,
                exportType,
                currencyCode,
                exchangeRate,
                narration,
                sourceGuid,
                versionNo,
                lines,
                null
            );
        }
    }

    public record BatchAllocationDto(
        UUID id,
        UUID godownId,
        String godownName,
        UUID stockBatchId,
        String batchName,
        String actualQty,
        String billedQty,
        String rate,
        String amount,
        LocalDate mfgDate,
        LocalDate expiryDate,
        Boolean allowExpiredBatch
    ) {
        public BatchAllocationDto(
            UUID id,
            UUID godownId,
            String godownName,
            UUID stockBatchId,
            String batchName,
            String actualQty,
            String billedQty,
            String rate,
            String amount,
            LocalDate mfgDate,
            LocalDate expiryDate
        ) {
            this(
                id,
                godownId,
                godownName,
                stockBatchId,
                batchName,
                actualQty,
                billedQty,
                rate,
                amount,
                mfgDate,
                expiryDate,
                null
            );
        }
    }

    public record VoucherInventoryLineDto(
        UUID id,
        Integer lineNo,
        UUID accountingLedgerId,
        UUID stockItemId,
        String stockItemName,
        String movementDirection,
        UUID uomId,
        String uomSymbol,
        String actualQty,
        String billedQty,
        String rate,
        String discountPct,
        String amount,
        String declaredMrp,
        String orderRefNo,
        String trackingNo,
        UUID linkedInwardAllocationId,
        Boolean allowExpiredBatch,
        List<BatchAllocationDto> batchAllocations
    ) {
    }

    public record VoucherLineRequest(
        Integer lineNo,
        String lineRole,
        UUID ledgerId,
        String side,
        String amount,
        String forexCurrencyCode,
        String forexAmount,
        String forexRate,
        String ratePct,
        String computedTaxAmount,
        Boolean isGstOverridden,
        String gstOverrideReason,
        String lineNarration,
        List<BillAllocationDto> billAllocations,
        List<CostAllocationDto> costAllocations,
        List<BankAllocationDto> bankAllocations
    ) {
    }

    public record BillAllocationDto(
        UUID id,
        String allocationType,
        String billName,
        Integer creditPeriodDays,
        LocalDate dueDate,
        String side,
        String amount,
        String forexCurrencyCode,
        String forexAmount,
        String forexRate,
        String unadjustedForexDelta,
        Boolean allowOverSettlement
    ) {
    }

    public record CostAllocationDto(
        UUID id,
        UUID costCategoryId,
        String costCategoryName,
        UUID costCentreId,
        String costCentreName,
        String side,
        String amount
    ) {
    }

    public record BankAllocationDto(
        UUID id,
        UUID bankLedgerId,
        String transactionType,
        String instrumentNo,
        LocalDate instrumentDate,
        LocalDate bankersDate,
        String favouringName,
        String transferModeRef,
        String side,
        String amount,
        String reconciliationStatus
    ) {
    }

    public record VoucherLineDetailDto(
        UUID id,
        int lineNo,
        String lineRole,
        UUID ledgerId,
        String ledgerName,
        String side,
        String amount,
        String forexCurrencyCode,
        String forexAmount,
        String forexRate,
        String ratePct,
        String computedTaxAmount,
        boolean isGstOverridden,
        String gstOverrideReason,
        String lineNarration,
        List<BillAllocationDto> billAllocations,
        List<CostAllocationDto> costAllocations,
        List<BankAllocationDto> bankAllocations
    ) {
    }

    public record VoucherDetailDto(
        UUID id,
        UUID companyId,
        UUID periodId,
        UUID fiscalYearId,
        UUID voucherTypeId,
        String voucherTypeName,
        String voucherTypeCategory,
        UUID voucherClassId,
        String status,
        String postingNature,
        String persistedView,
        String approvalPolicy,
        String voucherNumber,
        Long voucherSeqNo,
        LocalDate voucherDate,
        LocalDate effectiveDate,
        LocalDate reversingApplicableUpto,
        String supplierInvoiceNo,
        LocalDate supplierInvoiceDate,
        UUID partyLedgerId,
        String partyLedgerName,
        UUID accountingLedgerId,
        String accountingLedgerName,
        String placeOfSupplyState,
        UUID companyGstinId,
        String partyGstin,
        String partyMailingName,
        String partyStateCode,
        boolean isReverseCharge,
        String exportType,
        String currencyCode,
        String exchangeRate,
        String totalDebit,
        String totalCredit,
        String narration,
        UUID reversalOfJournalId,
        long masterSeqId,
        long alterId,
        int currentPostingBatchNo,
        long versionNo,
        UUID createdBy,
        UUID approvedBy,
        UUID postedBy,
        UUID cancelledBy,
        Instant createdAt,
        Instant updatedAt,
        Instant approvedAt,
        Instant postedAt,
        Instant cancelledAt,
        List<VoucherLineDetailDto> lines,
        List<VoucherInventoryLineDto> inventoryLines
    ) {
        public VoucherDetailDto(
            UUID id,
            UUID companyId,
            UUID periodId,
            UUID fiscalYearId,
            UUID voucherTypeId,
            String voucherTypeName,
            String voucherTypeCategory,
            UUID voucherClassId,
            String status,
            String postingNature,
            String persistedView,
            String approvalPolicy,
            String voucherNumber,
            Long voucherSeqNo,
            LocalDate voucherDate,
            LocalDate effectiveDate,
            LocalDate reversingApplicableUpto,
            String supplierInvoiceNo,
            LocalDate supplierInvoiceDate,
            UUID partyLedgerId,
            String partyLedgerName,
            UUID accountingLedgerId,
            String accountingLedgerName,
            String placeOfSupplyState,
            UUID companyGstinId,
            String partyGstin,
            String partyMailingName,
            String partyStateCode,
            boolean isReverseCharge,
            String exportType,
            String currencyCode,
            String exchangeRate,
            String totalDebit,
            String totalCredit,
            String narration,
            UUID reversalOfJournalId,
            long masterSeqId,
            long alterId,
            int currentPostingBatchNo,
            long versionNo,
            UUID createdBy,
            UUID approvedBy,
            UUID postedBy,
            UUID cancelledBy,
            Instant createdAt,
            Instant updatedAt,
            Instant approvedAt,
            Instant postedAt,
            Instant cancelledAt,
            List<VoucherLineDetailDto> lines
        ) {
            this(
                id,
                companyId,
                periodId,
                fiscalYearId,
                voucherTypeId,
                voucherTypeName,
                voucherTypeCategory,
                voucherClassId,
                status,
                postingNature,
                persistedView,
                approvalPolicy,
                voucherNumber,
                voucherSeqNo,
                voucherDate,
                effectiveDate,
                reversingApplicableUpto,
                supplierInvoiceNo,
                supplierInvoiceDate,
                partyLedgerId,
                partyLedgerName,
                accountingLedgerId,
                accountingLedgerName,
                placeOfSupplyState,
                companyGstinId,
                partyGstin,
                partyMailingName,
                partyStateCode,
                isReverseCharge,
                exportType,
                currencyCode,
                exchangeRate,
                totalDebit,
                totalCredit,
                narration,
                reversalOfJournalId,
                masterSeqId,
                alterId,
                currentPostingBatchNo,
                versionNo,
                createdBy,
                approvedBy,
                postedBy,
                cancelledBy,
                createdAt,
                updatedAt,
                approvedAt,
                postedAt,
                cancelledAt,
                lines,
                List.of()
            );
        }
    }

    public record DayBookLineBreakupDto(
        int lineNo,
        String ledgerName,
        String side,
        String amount
    ) {
    }

    public record VoucherSummaryDto(
        UUID id,
        LocalDate voucherDate,
        LocalDate effectiveDate,
        UUID voucherTypeId,
        String voucherTypeName,
        String voucherTypeCategory,
        String voucherNumber,
        String status,
        String postingNature,
        String persistedView,
        String primaryParticulars,
        String totalDebit,
        String totalCredit,
        String narration,
        long versionNo,
        long alterId,
        List<DayBookLineBreakupDto> lineBreakups
    ) {
        public VoucherSummaryDto(
            UUID id,
            LocalDate voucherDate,
            LocalDate effectiveDate,
            UUID voucherTypeId,
            String voucherTypeName,
            String voucherTypeCategory,
            String voucherNumber,
            String status,
            String postingNature,
            String persistedView,
            String primaryParticulars,
            String totalDebit,
            String totalCredit,
            String narration,
            long versionNo,
            long alterId
        ) {
            this(
                id,
                voucherDate,
                effectiveDate,
                voucherTypeId,
                voucherTypeName,
                voucherTypeCategory,
                voucherNumber,
                status,
                postingNature,
                persistedView,
                primaryParticulars,
                totalDebit,
                totalCredit,
                narration,
                versionNo,
                alterId,
                List.of()
            );
        }
    }

    public record OpenBillDto(
        String billName,
        LocalDate billDate,
        LocalDate dueDate,
        String totalDr,
        String totalCr,
        String pendingAmount,
        String pendingSide,
        String unadjustedForexDelta
    ) {
    }

    public record NextVoucherNumberResponse(
        UUID voucherTypeId,
        String voucherTypeName,
        String numberingMethod,
        String nextVoucherNumber,
        String expectedVoucherNumber,
        Long nextSeqNo,
        boolean manualRequired,
        boolean automatic
    ) {
    }

    public record UnusedVoucherNumberDto(
        UUID id,
        UUID voucherTypeId,
        String voucherNumber,
        Long numericSeq,
        String status,
        String releasedReason
    ) {
    }

    public record ApproveVoucherRequest(
        Boolean postImmediately,
        String remarks
    ) {
    }

    public record ReverseVoucherRequest(
        LocalDate reversalDate,
        String narration
    ) {
    }

    public record CancelVoucherRequest(
        String reason
    ) {
    }

    public record AdjacentVoucherResponse(
        boolean found,
        String direction,
        UUID currentVoucherId,
        UUID adjacentVoucherId,
        boolean hasPrevious,
        boolean hasNext,
        VoucherDetailDto voucher
    ) {
    }

    public record LastNarrationResponse(
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
