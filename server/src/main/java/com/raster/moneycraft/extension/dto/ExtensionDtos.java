package com.raster.moneycraft.extension.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Increment X Extension Workbench (EXT-001..008) & Static TDL Importer (TDL-001..004) wire contracts.
 * All decimal values are represented as exact strings (zero binary floating-point).
 */
public final class ExtensionDtos {

    public static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private ExtensionDtos() {
    }

    public record UpsertCustomFieldDefRequest(
        String namespace,
        UUID fieldUuid,
        String ownerEntityType,
        String fieldCode,
        String label,
        String dataType,
        Integer decimalScale,
        List<String> enumOptions,
        String defaultValue,
        String protectionClass,
        String uiAnchor,
        Boolean requiredFlag
    ) {
    }

    public record CustomFieldDefDto(
        UUID id,
        UUID companyId,
        UUID extensionPackageId,
        String namespace,
        UUID fieldUuid,
        String ownerEntityType,
        String fieldCode,
        String label,
        String dataType,
        Integer decimalScale,
        List<String> enumOptions,
        String defaultValue,
        String protectionClass,
        String uiAnchor,
        boolean requiredFlag,
        boolean active,
        long versionNo,
        Instant createdAt
    ) {
    }

    public record FieldValueWriteItemDto(
        UUID fieldDefId,
        String namespace,
        String fieldCode,
        String value
    ) {
    }

    public record UpsertCustomFieldValuesRequest(
        List<FieldValueWriteItemDto> items,
        Map<String, String> values
    ) {
    }

    public record CustomFieldValueDto(
        UUID fieldDefId,
        String namespace,
        UUID fieldUuid,
        String ownerEntityType,
        UUID entityId,
        String fieldCode,
        String label,
        String dataType,
        String protectionClass,
        String uiAnchor,
        String value,
        boolean redacted,
        long valueVersion,
        Instant updatedAt
    ) {
    }

    public record FeelRuleSpecDto(
        String ruleCode,
        String label,
        String targetEntityType,
        String severity,
        String conditionExpression,
        String errorMessage,
        Integer orderNo
    ) {
    }

    public record DmnDecisionRuleRowDto(
        String ruleId,
        List<String> inputEntries,
        String outcome,
        String message
    ) {
    }

    public record DmnDecisionTableDto(
        String tableCode,
        String label,
        String targetEntityType,
        String hitPolicy,
        List<String> inputExpressions,
        List<DmnDecisionRuleRowDto> rules
    ) {
    }

    public record UiAnchorCustomizationDto(
        String anchorId,
        String targetScreen,
        String operation,
        List<String> fieldCodes,
        String actionCommandId,
        String actionLetterPair
    ) {
    }

    public record ExtensionReportDefDto(
        String reportCode,
        String label,
        String baseProjection,
        List<String> selectedColumns,
        Map<String, String> declaredFilters
    ) {
    }

    public record ExtensionPrintLayoutDto(
        String layoutCode,
        String label,
        String documentProfile,
        String headerTitle,
        List<String> visibleFieldCodes,
        boolean showCompanyLogo,
        String termsText
    ) {
    }

    public record ExtensionConnectorRefDto(
        String profileCode,
        String direction,
        String connectorCode,
        String secretRef,
        String idempotencyStrategy
    ) {
    }

    public record ExtensionPackageDefinitionDto(
        String schemaDialect,
        String contractVersion,
        String namespace,
        UUID extensionUuid,
        String displayName,
        List<String> targetProfiles,
        List<UpsertCustomFieldDefRequest> customFields,
        List<UiAnchorCustomizationDto> uiAnchors,
        List<FeelRuleSpecDto> feelRules,
        List<DmnDecisionTableDto> decisionTables,
        List<ExtensionReportDefDto> reports,
        List<ExtensionPrintLayoutDto> printLayouts,
        List<ExtensionConnectorRefDto> connectorRefs
    ) {
    }

    public record CreateExtensionPackageRequest(
        String namespace,
        UUID extensionUuid,
        String displayName,
        String schemaDialect,
        List<String> targetProfiles,
        List<UpsertCustomFieldDefRequest> customFields,
        List<UiAnchorCustomizationDto> uiAnchors,
        List<FeelRuleSpecDto> feelRules,
        List<DmnDecisionTableDto> decisionTables,
        List<ExtensionReportDefDto> reports,
        List<ExtensionPrintLayoutDto> printLayouts,
        List<ExtensionConnectorRefDto> connectorRefs,
        String rawDefinitionJson
    ) {
    }

    public record ValidationDiagnosticDto(
        String severity,
        String code,
        String location,
        String message
    ) {
    }

    public record ExtensionPackageDto(
        UUID id,
        UUID companyId,
        String namespace,
        UUID extensionUuid,
        String displayName,
        int revisionNo,
        String schemaDialect,
        String definitionSha256Hex,
        String status,
        String bindingState,
        Map<String, Object> summary,
        List<ValidationDiagnosticDto> validationDiagnostics,
        ExtensionPackageDefinitionDto definition,
        long versionNo,
        UUID createdBy,
        Instant createdAt
    ) {
    }

    public record ExtensionBindingDto(
        UUID id,
        UUID companyId,
        String namespace,
        UUID extensionUuid,
        UUID extensionPackageId,
        int revisionNo,
        String displayName,
        String boundDefinitionSha256Hex,
        String state,
        UUID activatedBy,
        Instant activatedAt,
        UUID suspendedBy,
        Instant suspendedAt,
        long versionNo
    ) {
    }

    public record EvaluatePreviewRequest(
        String expression,
        DmnDecisionTableDto decisionTable,
        Map<String, Object> inputs
    ) {
    }

    public record EvaluatePreviewResponse(
        boolean valid,
        String resultType,
        String resultValue,
        List<String> matchedRuleIds,
        String outcome,
        List<String> messages,
        int astDepth,
        int stepsConsumed
    ) {
    }

    public record ImportTdlProjectRequest(
        String projectName,
        String fileName,
        String sourceVersionClaim,
        String tdlText,
        String base64Content,
        Map<String, String> includedFiles
    ) {
    }

    public record TdlConstructDto(
        int constructSeq,
        String constructType,
        String identifier,
        String sourceFile,
        int startLine,
        int endLine,
        String rawHeader,
        String disposition,
        String targetComponent,
        String mappedTargetCode,
        String permissionImplication,
        String explanation
    ) {
    }

    public record TdlParityMatrixRowDto(
        int rowNo,
        String sourceRef,
        String constructIdentifier,
        String requiredBehaviour,
        String targetComponent,
        String disposition,
        String plannedFixture,
        String expectedResult,
        String executionStatus
    ) {
    }

    public record TdlImportProjectDto(
        UUID id,
        UUID companyId,
        String projectName,
        String fileName,
        String sourceVersionClaim,
        String sourceSha256Hex,
        boolean hasCompiledTcpBlock,
        boolean hasNativeDllBlock,
        boolean hasUnresolvedIncludes,
        String status,
        UUID draftExtensionId,
        Map<String, Integer> summaryCounts,
        List<TdlConstructDto> constructs,
        List<TdlParityMatrixRowDto> parityMatrix,
        Instant createdAt
    ) {
    }

    public record OdbcProjectionQueryRequest(
        String projection,
        LocalDate fromDate,
        LocalDate toDate,
        Boolean includeOptional,
        Integer limit,
        String rawSql
    ) {
    }

    public record OdbcProjectionColumnDto(
        String name,
        String dataType
    ) {
    }

    public record OdbcProjectionQueryResponse(
        String projection,
        Instant asOf,
        int rowCount,
        List<OdbcProjectionColumnDto> columns,
        List<Map<String, String>> rows
    ) {
    }
}
