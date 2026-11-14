package com.raster.moneycraft.exchange.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tally `<ENVELOPE>` XML, Rel 7.2 `JSONEx`, and OpenXML `.xlsx` Data Exchange wire contracts.
 * All monetary amounts, tax rates, and stock quantities are serialized as exact fixed-point strings.
 */
public final class ExchangeDtos {

    private ExchangeDtos() {
    }

    public record CreateExchangeTemplateRequest(
        String name,
        String templateName,
        String targetType,
        String entityScope,
        String sheetName,
        Integer headerRowIndex,
        Map<String, String> columnMappings,
        Map<String, String> defaultValues,
        String duplicateHandling
    ) {
    }

    public record ExchangeMappingTemplateDto(
        UUID id,
        String name,
        String templateName,
        String targetType,
        String entityScope,
        String sheetName,
        int headerRowIndex,
        Map<String, String> columnMappings,
        Map<String, String> defaultValues,
        String duplicateHandling,
        boolean active,
        long versionNo,
        Instant createdAt
    ) {
    }

    public record ExportDataRequest(
        String entityScope,
        LocalDate fromDate,
        LocalDate toDate,
        Boolean includeDependentMasters,
        UUID templateId
    ) {
    }

    public record ExportDataResultDto(
        String format,
        String fileName,
        String mimeType,
        String content,
        String xml,
        int recordCount,
        String sha256Hex
    ) {
    }

    public record JsonExHeaderDto(
        int version,
        String tallyrequest,
        String type,
        String id
    ) {
    }

    public record JsonExGroupDto(
        String guid,
        String name,
        String parent,
        String nature,
        boolean affectsGrossProfit,
        boolean behavesLikeSubledger
    ) {
    }

    public record JsonExUomDto(
        String symbol,
        String formalName,
        int decimalPlaces
    ) {
    }

    public record JsonExGodownDto(
        String name,
        String parent
    ) {
    }

    public record JsonExStockGroupDto(
        String name,
        String parent
    ) {
    }

    public record JsonExStockItemDto(
        String guid,
        String name,
        String stockGroup,
        String baseUom,
        String costingMethod,
        String openingQty,
        String openingRate,
        String openingValue
    ) {
    }

    public record JsonExLedgerDto(
        String guid,
        String name,
        String groupName,
        String openingBalance,
        String openingSide,
        boolean maintainBalancesBillByBill,
        String gstin,
        String stateName,
        String taxType,
        String dutyHead
    ) {
    }

    public record JsonExBillAllocationDto(
        String allocationType,
        String billName,
        String side,
        String amount
    ) {
    }

    public record JsonExVoucherLineDto(
        int lineNo,
        String lineRole,
        String ledgerName,
        String side,
        String amount,
        List<JsonExBillAllocationDto> billAllocations
    ) {
    }

    public record JsonExInventoryLineDto(
        int lineNo,
        String stockItemName,
        String movementDirection,
        String uomSymbol,
        String actualQty,
        String billedQty,
        String rate,
        String amount,
        String godownName,
        String batchName
    ) {
    }

    public record JsonExVoucherDto(
        String guid,
        String voucherTypeName,
        String voucherNumber,
        LocalDate voucherDate,
        LocalDate effectiveDate,
        String persistedView,
        String postingNature,
        String partyLedgerName,
        String supplierInvoiceNo,
        String narration,
        List<JsonExVoucherLineDto> lines,
        List<JsonExInventoryLineDto> inventoryLines
    ) {
    }

    public record JsonExDataDto(
        List<JsonExGroupDto> groups,
        List<JsonExUomDto> uoms,
        List<JsonExGodownDto> godowns,
        List<JsonExStockGroupDto> stockGroups,
        List<JsonExStockItemDto> stockItems,
        List<JsonExLedgerDto> ledgers,
        List<JsonExVoucherDto> vouchers
    ) {
    }

    public record JsonExExportResponseDto(
        JsonExHeaderDto header,
        JsonExDataDto data,
        String format,
        String fileName,
        String mimeType,
        String content,
        int recordCount,
        String sha256Hex
    ) {
    }

    public record ImportDataRequest(
        String fileName,
        String content,
        String rawXml,
        String rawJson,
        String base64Content,
        String excelBase64,
        UUID templateId,
        String targetType,
        Boolean dryRun,
        JsonExHeaderDto header,
        JsonExDataDto data
    ) {
    }

    public record ImportDataResultDto(
        UUID jobId,
        String format,
        String status,
        int groupsImported,
        int ledgersImported,
        int stockItemsImported,
        int vouchersImported,
        String totalDebit,
        String totalCredit,
        boolean isBalanced,
        String message
    ) {
    }
}
