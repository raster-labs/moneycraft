package com.raster.moneycraft.banking.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6 Banking Auto-BRS (CSV, MT940, CAMT.053), Cheque Books, Optional Payroll,
 * and Docs-by-Ira OCR Document Human-Review Queue DTOs.
 */
public final class BankingDtos {

    private BankingDtos() {
    }

    public record CreateChequeBookRequest(
        UUID bankLedgerId,
        String bookName,
        String prefix,
        Long fromNumber,
        Long toNumber,
        String fromLeafNo,
        String toLeafNo
    ) {
    }

    public record ChequeBookDto(
        UUID id,
        UUID bankLedgerId,
        String bankLedgerName,
        String bookName,
        String prefix,
        long fromNumber,
        long toNumber,
        String fromLeafNo,
        String toLeafNo,
        int numberOfLeaves,
        int totalLeaves,
        int availableLeaves,
        boolean active,
        long versionNo
    ) {
    }

    public record ChequeLeafDto(
        UUID id,
        UUID chequeBookId,
        UUID bankLedgerId,
        String chequeNumber,
        String leafNumber,
        long leafSeq,
        String status,
        UUID journalId,
        UUID voucherId,
        String voucherNumber,
        String payeeName,
        LocalDate chequeDate,
        LocalDate instrumentDate,
        String amount,
        String remarks,
        long versionNo
    ) {
    }

    public record StructuredStatementLineInput(
        LocalDate txnDate,
        LocalDate valueDate,
        String instrumentNo,
        String utrReference,
        String description,
        String withdrawalAmount,
        String depositAmount,
        String runningBalance
    ) {
    }

    public record ImportBankStatementRequest(
        UUID bankLedgerId,
        String fileName,
        String sourceFormat,
        String format,
        String rawContent,
        String content,
        LocalDate statementFromDate,
        LocalDate statementToDate,
        String openingBalance,
        String closingBalance,
        List<StructuredStatementLineInput> lines
    ) {
    }

    public record BankStatementImportResponse(
        UUID id,
        UUID statementImportId,
        UUID bankLedgerId,
        String fileName,
        String sourceFormat,
        LocalDate statementFromDate,
        LocalDate statementToDate,
        String openingBalance,
        String closingBalance,
        int totalLines,
        int importedLinesCount,
        int matchedLines,
        Instant importedAt
    ) {
    }

    public record BrsAllocationRowDto(
        UUID id,
        UUID bankAllocationId,
        UUID voucherId,
        String voucherNumber,
        String voucherTypeName,
        LocalDate voucherDate,
        String transactionType,
        String instrumentNo,
        LocalDate instrumentDate,
        String partyOrNarration,
        String side,
        String amount,
        String debitAmount,
        String creditAmount,
        LocalDate bankersDate,
        String reconciliationStatus
    ) {
    }

    public record BrsStatementLineDto(
        UUID id,
        int lineNo,
        LocalDate txnDate,
        LocalDate valueDate,
        String description,
        String instrumentNo,
        String utrReference,
        String debitAmount,
        String creditAmount,
        String withdrawalAmount,
        String depositAmount,
        String runningBalance,
        String matchStatus
    ) {
    }

    public record BrsMatchRowDto(
        UUID id,
        UUID bankAllocationId,
        UUID statementLineId,
        String voucherNumber,
        String instrumentNo,
        String amount,
        LocalDate bankersDate,
        String matchMethod,
        String matchConfidence
    ) {
    }

    public record BrsSummaryDto(
        UUID bankLedgerId,
        String bankLedgerName,
        LocalDate asOfDate,
        String balanceAsPerCompanyBooks,
        String amountsNotReflectedInBankDr,
        String amountsNotReflectedInBankCr,
        String balanceAsPerBankComputed,
        String statementClosingBalance,
        List<BrsAllocationRowDto> unreconciledAllocations,
        List<BrsStatementLineDto> unmatchedStatementLines,
        List<BrsMatchRowDto> matches
    ) {
    }

    public record AutoMatchBrsRequest(
        UUID bankLedgerId,
        UUID statementImportId,
        Integer dateWindowDays,
        Integer dateToleranceDays
    ) {
    }

    public record AutoMatchBrsResponse(
        int matchedCount,
        int remainingUnreconciledCount,
        BrsSummaryDto summary
    ) {
    }

    public record ManualReconcileBrsRequest(
        UUID bankAllocationId,
        LocalDate bankersDate,
        UUID statementLineId
    ) {
    }

    public record CreatePayrollHeadRequest(
        String name,
        String payHeadType,
        String calculationType,
        UUID ledgerId,
        Boolean affectNetSalary,
        String payslipDisplayName
    ) {
    }

    public record PayrollHeadDto(
        UUID id,
        String name,
        String payHeadType,
        String calculationType,
        UUID ledgerId,
        String ledgerName,
        boolean affectNetSalary,
        String payslipDisplayName,
        boolean active,
        long versionNo
    ) {
    }

    public record CreateEmployeeRequest(
        String employeeCode,
        String name,
        String employeeName,
        String department,
        String designation,
        UUID costCentreId,
        LocalDate dateOfJoining,
        LocalDate dateOfResignation,
        String pan,
        String uanNo,
        String uan,
        String esiNo,
        String bankAccountNo,
        String bankAccountNumber,
        String bankIfsc,
        String bankName
    ) {
    }

    public record EmployeeDto(
        UUID id,
        String employeeCode,
        String name,
        String department,
        String designation,
        UUID costCentreId,
        LocalDate dateOfJoining,
        LocalDate dateOfResignation,
        String panMasked,
        String uanNo,
        String esiNo,
        boolean hasEncryptedBankDetails,
        boolean active,
        long versionNo
    ) {
    }

    public record CreateOcrStagedDocRequest(
        String fileName,
        String contentType,
        String documentType,
        String targetVoucherCategory,
        String supplierName,
        String supplierGstin,
        String invoiceNumber,
        LocalDate invoiceDate,
        String taxableAmount,
        String taxAmount,
        String totalAmount,
        String extractedText,
        String rawExtractedJson,
        String base64Blob,
        UUID mappedPartyLedgerId
    ) {
    }

    public record OcrStagedDocumentDto(
        UUID id,
        String fileName,
        String contentType,
        String documentType,
        String targetVoucherCategory,
        String supplierName,
        String supplierGstin,
        String invoiceNumber,
        LocalDate invoiceDate,
        String taxableAmount,
        String taxAmount,
        String totalAmount,
        String confidencePct,
        String reviewStatus,
        UUID mappedPartyLedgerId,
        UUID convertedVoucherId,
        UUID promotedJournalId,
        UUID reviewedBy,
        Instant reviewedAt,
        long versionNo
    ) {
    }

    public record ApproveConvertOcrRequest(
        String voucherTypeName,
        UUID partyLedgerId,
        UUID expenseOrPurchaseLedgerId,
        String verifiedTotalAmount,
        LocalDate voucherDate,
        String narration
    ) {
    }

    public record ApproveConvertOcrResponse(
        UUID documentId,
        UUID convertedVoucherId,
        String voucherNumber,
        String status
    ) {
    }
}
