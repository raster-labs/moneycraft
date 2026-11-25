package com.raster.moneycraft.exchange.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Increment X Guided Multi-Format Columnar Spreadsheet Importer (SHEET-001..008) wire contracts.
 * Supports unlimited multi-ledger horizontal columnar voucher mapping with exact decimal strings.
 */
public final class SpreadsheetImportDtos {

    private SpreadsheetImportDtos() {
    }

    public record LedgerColumnMappingDto(
        String sourceColumn,
        String ledgerName,
        UUID ledgerId,
        String side,
        String lineRole
    ) {
    }

    public record SpreadsheetMappingSpecDto(
        String targetType,
        String voucherAction,
        String defaultVoucherType,
        String voucherDateColumn,
        String voucherTypeColumn,
        String voucherNumberColumn,
        String referenceNumberColumn,
        String narrationColumn,
        String groupingKeyColumn,
        String partyLedgerNameColumn,
        String partyAmountColumn,
        String partySide,
        String debitLedgerColumn,
        String creditLedgerColumn,
        String amountColumn,
        List<LedgerColumnMappingDto> ledgerColumns,
        String ledgerNameColumn,
        String aliasCodeColumn,
        String groupNameColumn,
        String openingBalanceColumn,
        String openingSideColumn,
        String gstinColumn,
        String stateCodeColumn,
        String stateNameColumn,
        String billWiseColumn,
        String datePattern,
        String duplicateHandling
    ) {
    }

    public record GoogleSheetCaptureSpecDto(
        String spreadsheetId,
        String sheetName,
        String rangeA1,
        String scopeUsed,
        String endpointUri,
        List<List<String>> frozenRows
    ) {
    }

    public record PrepareSpreadsheetBatchRequest(
        String fileName,
        String formatHint,
        String base64Content,
        String csvText,
        String sheetName,
        Integer headerRowIndex,
        String delimiter,
        Boolean cachedFormulaConsent,
        Boolean acknowledgeDuplicateRun,
        UUID templateId,
        SpreadsheetMappingSpecDto mapping,
        GoogleSheetCaptureSpecDto googleSheetCapture
    ) {
    }

    public record RemapSpreadsheetBatchRequest(
        String sheetName,
        Integer headerRowIndex,
        String delimiter,
        Boolean cachedFormulaConsent,
        UUID templateId,
        SpreadsheetMappingSpecDto mapping
    ) {
    }

    public record ExecuteSpreadsheetBatchRequest(
        String action,
        Boolean allowValidUnitsOnly,
        Integer maxUnitsThisRun
    ) {
    }

    public record SaveSpreadsheetTemplateRequest(
        UUID templateId,
        String name,
        String targetType,
        String sheetName,
        Integer headerRowIndex,
        SpreadsheetMappingSpecDto mapping,
        String duplicateHandling
    ) {
    }

    public record SpreadsheetTemplateDto(
        UUID id,
        UUID companyId,
        String name,
        String targetType,
        String sheetName,
        int headerRowIndex,
        SpreadsheetMappingSpecDto mapping,
        String duplicateHandling,
        long versionNo,
        Instant createdAt
    ) {
    }

    public record CellValidationErrorDto(
        String code,
        String field,
        String sheetName,
        int rowNumber,
        int columnIndex,
        String cellRef,
        String columnHeader,
        String rawCellSnippet,
        String message
    ) {
    }

    public record SourceCoordinatesDto(
        String sheetName,
        List<Integer> rowNumbers,
        Map<String, String> cellRefs
    ) {
    }

    public record SpreadsheetUnitDto(
        UUID id,
        int unitSeq,
        String unitKey,
        String targetType,
        String validationStatus,
        String executionStatus,
        String unitDebit,
        String unitCredit,
        String unitIdempotencyKey,
        SourceCoordinatesDto sourceCoordinates,
        Map<String, Object> intendedSummary,
        List<CellValidationErrorDto> validationErrors,
        String executionErrorCode,
        String executionErrorMessage,
        UUID resultingEntityId,
        String resultingReference,
        Instant committedAt
    ) {
    }

    public record SpreadsheetBatchDto(
        UUID id,
        UUID companyId,
        String sourceFileName,
        String sourceSha256Hex,
        String formatProfile,
        String targetType,
        String sheetName,
        List<String> availableSheets,
        List<String> detectedHeaders,
        int headerRowIndex,
        String dateSystem,
        boolean macroDetected,
        boolean formulaCellsDetected,
        boolean cachedFormulaPolicyAccepted,
        UUID templateId,
        Long templateVersion,
        int mappingRevision,
        SpreadsheetMappingSpecDto mapping,
        String status,
        UUID previousMatchingBatchId,
        int totalUnits,
        int validUnits,
        int invalidUnits,
        int committedUnits,
        int failedUnits,
        int skippedUnits,
        int uncertainUnits,
        String totalDebit,
        String totalCredit,
        List<String> diagnostics,
        long versionNo,
        Instant createdAt,
        Instant completedAt,
        List<SpreadsheetUnitDto> units
    ) {
    }
}
