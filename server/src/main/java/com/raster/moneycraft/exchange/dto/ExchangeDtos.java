package com.raster.moneycraft.exchange.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical `<ENVELOPE>` XML, `JSONEx`, OpenXML `.xlsx`, `ASCII` (`.csv`), and `HTML` Data Exchange
 * wire contracts, including TallyPrime Gold duplicate handling (`COMBINE_OPENING_BALANCES`,
 * `IGNORE_DUPLICATES`, `MODIFY_WITH_NEW_DATA`), exception resolution, sample Excel generation,
 * and Client/Server Data Synchronization (`Alt+Z`).
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
        String duplicateHandling,
        String templateScope,
        String fromColumn,
        String toColumn,
        String excludeRowColumn,
        String excludeRowOperator,
        String excludeRowValue
    ) {
        public CreateExchangeTemplateRequest(
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
            this(
                name,
                templateName,
                targetType,
                entityScope,
                sheetName,
                headerRowIndex,
                columnMappings,
                defaultValues,
                duplicateHandling,
                "THIS_COMPANY",
                null,
                null,
                null,
                null,
                null
            );
        }
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
        String templateScope,
        String fromColumn,
        String toColumn,
        String excludeRowColumn,
        String excludeRowOperator,
        String excludeRowValue,
        boolean active,
        long versionNo,
        Instant createdAt
    ) {
        public ExchangeMappingTemplateDto(
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
            this(
                id,
                name,
                templateName,
                targetType,
                entityScope,
                sheetName,
                headerRowIndex,
                columnMappings,
                defaultValues,
                duplicateHandling,
                "THIS_COMPANY",
                null,
                null,
                null,
                null,
                null,
                active,
                versionNo,
                createdAt
            );
        }
    }

    public record ExportDataRequest(
        String entityScope,
        LocalDate fromDate,
        LocalDate toDate,
        Boolean includeDependentMasters,
        UUID templateId,
        Boolean includeNarration,
        Boolean includeBillWise,
        Boolean includeInventory,
        String digitalSignatureSignerName
    ) {
        public ExportDataRequest(
            String entityScope,
            LocalDate fromDate,
            LocalDate toDate,
            Boolean includeDependentMasters,
            UUID templateId
        ) {
            this(
                entityScope,
                fromDate,
                toDate,
                includeDependentMasters,
                templateId,
                true,
                true,
                true,
                null
            );
        }
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

    /**
     * NC-090-02 wire-protocol interoperability field (`tallyrequest`) preserved verbatim for
     * external `JSONEx` envelope compatibility.
     */
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
        JsonExDataDto data,
        String masterDuplicateRule,
        Boolean overwriteVoucherByGuid,
        String exceptionBehavior
    ) {
        public ImportDataRequest(
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
            this(
                fileName,
                content,
                rawXml,
                rawJson,
                base64Content,
                excelBase64,
                templateId,
                targetType,
                dryRun,
                header,
                data,
                "MODIFY_WITH_NEW_DATA",
                false,
                "STOP_AT_FIRST_EXCEPTION"
            );
        }
    }

    public record ImportExceptionItemDto(
        String exceptionId,
        UUID jobId,
        String entityType,
        String identifier,
        String errorCode,
        String message,
        Map<String, Object> rawPayload,
        boolean resolved,
        UUID resolvedEntityId,
        Instant createdAt
    ) {
    }

    public record ResolveImportExceptionRequest(
        String voucherTypeName,
        LocalDate voucherDate,
        String debitLedgerName,
        String creditLedgerName,
        String amount,
        String narration
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
        String message,
        int mastersSkipped,
        int vouchersUpdated,
        int vouchersSkipped,
        int exceptionsRecorded,
        List<ImportExceptionItemDto> exceptions
    ) {
        public ImportDataResultDto(
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
            this(
                jobId,
                format,
                status,
                groupsImported,
                ledgersImported,
                stockItemsImported,
                vouchersImported,
                totalDebit,
                totalCredit,
                isBalanced,
                message,
                0,
                0,
                0,
                0,
                List.of()
            );
        }
    }

    public record SyncRuleRequest(
        String ruleName,
        String ruleType,
        String direction,
        UUID targetCompanyId,
        String targetCompanyGuid,
        String targetCompanyName,
        String serverUrl,
        String masterDuplicateRule,
        Boolean overwriteVoucherByGuid
    ) {
    }

    public record SyncRuleDto(
        UUID id,
        UUID companyId,
        String ruleName,
        String ruleType,
        String direction,
        UUID targetCompanyId,
        String targetCompanyGuid,
        String targetCompanyName,
        String serverUrl,
        String masterDuplicateRule,
        boolean overwriteVoucherByGuid,
        long lastSyncAlterId,
        boolean active,
        Instant createdAt
    ) {
    }

    public record SyncSessionResultDto(
        UUID sessionId,
        UUID ruleId,
        String ruleName,
        UUID sourceCompanyId,
        UUID targetCompanyId,
        String status,
        long previousAlterIdWatermark,
        long newAlterIdWatermark,
        int mastersSynced,
        int vouchersSynced,
        int exceptionsRecorded,
        String message,
        Instant completedAt
    ) {
    }

    public record MultiAccountReportExportRequest(
        String reportType,
        LocalDate fromDate,
        LocalDate toDate,
        String format,
        Boolean includeZeroBalanceParties
    ) {
    }

    public record MultiAccountPartyStatementDto(
        UUID ledgerId,
        String partyName,
        String groupName,
        String gstin,
        String openingBalance,
        String openingSide,
        String periodDebit,
        String periodCredit,
        String closingBalance,
        String closingSide,
        String slab0To30,
        String slab31To60,
        String slab61To90,
        String slabOver90
    ) {
    }

    public record MultiAccountReportExportResponse(
        UUID companyId,
        String companyName,
        String reportType,
        LocalDate fromDate,
        LocalDate toDate,
        String format,
        int partyCount,
        List<MultiAccountPartyStatementDto> statements,
        String renderedContent
    ) {
    }

    public record EditLogSummaryItemDto(
        String entityType,
        UUID entityId,
        String displayName,
        String voucherNumber,
        String activityType,
        String alteredDueTo,
        int versionCount,
        long latestAlterId,
        String lastModifiedBy,
        Instant lastModifiedAt
    ) {
    }

    public record EditLogSummaryResponseDto(
        UUID companyId,
        String entityTypeFilter,
        boolean onlyAltered,
        int totalItems,
        int alteredItems,
        List<EditLogSummaryItemDto> items
    ) {
    }

    public record DashboardTilesExportRequest(
        LocalDate fromDate,
        LocalDate toDate,
        String format
    ) {
    }

    public record DashboardTileDto(
        String tileCode,
        String tileTitle,
        String primaryMetricLabel,
        String primaryMetricValue,
        Map<String, String> breakdown
    ) {
    }

    public record DashboardTilesExportResponse(
        UUID companyId,
        String companyName,
        LocalDate fromDate,
        LocalDate toDate,
        String format,
        int totalTiles,
        List<DashboardTileDto> tiles,
        String renderedContent
    ) {
    }

    public record DataVerificationCheckDto(
        String checkCode,
        String checkTitle,
        String status,
        int issueCount,
        String detail
    ) {
    }

    public record DataVerificationReportDto(
        UUID companyId,
        String companyName,
        String trialBalanceDifference,
        int unbalancedVoucherCount,
        int unitMismatchCount,
        int missingHsnOrGstinCount,
        int pendingImportExceptionCount,
        boolean readyForSplitOrSync,
        List<DataVerificationCheckDto> checks
    ) {
    }
}
