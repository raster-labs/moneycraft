export const JSON_SCHEMA_2020_12 = 'https://json-schema.org/draft/2020-12/schema';

export type ExtensionWorkbenchTab =
  | 'CUSTOM_FIELDS'
  | 'RULES_PACKAGES'
  | 'TDL_IMPORTER'
  | 'LIFECYCLE_ODBC';

export type CustomFieldEntityType = 'LEDGER' | 'STOCK_ITEM' | 'VOUCHER';
export type CustomFieldDataType = 'TEXT' | 'EXACT_DECIMAL' | 'DATE' | 'BOOLEAN' | 'ENUM';
export type CustomFieldProtectionClass = 'STANDARD' | 'SELECTED_PRIVATE';
export type DmnHitPolicy = 'UNIQUE' | 'FIRST' | 'COLLECT';
export type TdlDisposition =
  | 'TRANSLATED_VERIFICATION_PENDING'
  | 'REQUIRES_MAPPING'
  | 'UNSUPPORTED'
  | 'BLOCKED_DEPENDENCY'
  | 'NOT_APPLICABLE_WITH_EXPLANATION';

export type OdbcProjectionName =
  | 'TRIAL_BALANCE'
  | 'LEDGERS'
  | 'VOUCHER_REGISTER'
  | 'STOCK_SUMMARY';

export interface UpsertCustomFieldDefRequest {
  readonly namespace: string;
  readonly fieldUuid?: string | null;
  readonly ownerEntityType: CustomFieldEntityType | string;
  readonly fieldCode: string;
  readonly label: string;
  readonly dataType: CustomFieldDataType | string;
  readonly decimalScale?: number | null;
  readonly enumOptions?: readonly string[] | null;
  readonly defaultValue?: string | null;
  readonly protectionClass: CustomFieldProtectionClass | string;
  readonly uiAnchor: string;
  readonly requiredFlag?: boolean;
}

export interface CustomFieldDefDto {
  readonly id: string;
  readonly companyId: string;
  readonly extensionPackageId?: string | null;
  readonly namespace: string;
  readonly fieldUuid: string;
  readonly ownerEntityType: string;
  readonly fieldCode: string;
  readonly label: string;
  readonly dataType: string;
  readonly decimalScale?: number | null;
  readonly enumOptions?: readonly string[] | null;
  readonly defaultValue?: string | null;
  readonly protectionClass: string;
  readonly uiAnchor: string;
  readonly requiredFlag: boolean;
  readonly active: boolean;
  readonly versionNo: number;
  readonly createdAt?: string;
}

export interface FieldValueWriteItemDto {
  readonly fieldDefId?: string | null;
  readonly namespace: string;
  readonly fieldCode: string;
  readonly value: string;
}

export interface UpsertCustomFieldValuesRequest {
  readonly items?: readonly FieldValueWriteItemDto[];
  readonly values?: Readonly<Record<string, string>>;
}

export interface CustomFieldValueDto {
  readonly fieldDefId: string;
  readonly namespace: string;
  readonly fieldUuid: string;
  readonly ownerEntityType: string;
  readonly entityId: string;
  readonly fieldCode: string;
  readonly label: string;
  readonly dataType: string;
  readonly protectionClass: string;
  readonly uiAnchor: string;
  readonly value: string;
  readonly redacted: boolean;
  readonly valueVersion: number;
  readonly updatedAt?: string;
}

export interface FeelRuleSpecDto {
  readonly ruleCode: string;
  readonly label: string;
  readonly targetEntityType: string;
  readonly severity: 'ERROR' | 'WARNING' | string;
  readonly conditionExpression: string;
  readonly errorMessage: string;
  readonly orderNo?: number;
}

export interface DmnDecisionRuleRowDto {
  readonly ruleId: string;
  readonly inputEntries: readonly string[];
  readonly outcome: string;
  readonly message: string;
}

export interface DmnDecisionTableDto {
  readonly tableCode: string;
  readonly label: string;
  readonly targetEntityType: string;
  readonly hitPolicy: DmnHitPolicy | string;
  readonly inputExpressions: readonly string[];
  readonly rules: readonly DmnDecisionRuleRowDto[];
}

export interface UiAnchorCustomizationDto {
  readonly anchorId: string;
  readonly targetScreen: string;
  readonly operation: string;
  readonly fieldCodes: readonly string[];
  readonly actionCommandId?: string | null;
  readonly actionLetterPair?: string | null;
}

export interface ExtensionReportDefDto {
  readonly reportCode: string;
  readonly label: string;
  readonly baseProjection: string;
  readonly selectedColumns: readonly string[];
  readonly declaredFilters: Readonly<Record<string, string>>;
}

export interface ExtensionPrintLayoutDto {
  readonly layoutCode: string;
  readonly label: string;
  readonly documentProfile: string;
  readonly headerTitle: string;
  readonly visibleFieldCodes: readonly string[];
  readonly showCompanyLogo: boolean;
  readonly termsText?: string | null;
}

export interface ExtensionConnectorRefDto {
  readonly profileCode: string;
  readonly direction: string;
  readonly connectorCode: string;
  readonly secretRef: string;
  readonly idempotencyStrategy: string;
}

export interface ExtensionPackageDefinitionDto {
  readonly schemaDialect: string;
  readonly contractVersion: string;
  readonly namespace: string;
  readonly extensionUuid: string;
  readonly displayName: string;
  readonly targetProfiles: readonly string[];
  readonly customFields: readonly UpsertCustomFieldDefRequest[];
  readonly uiAnchors: readonly UiAnchorCustomizationDto[];
  readonly feelRules: readonly FeelRuleSpecDto[];
  readonly decisionTables: readonly DmnDecisionTableDto[];
  readonly reports: readonly ExtensionReportDefDto[];
  readonly printLayouts: readonly ExtensionPrintLayoutDto[];
  readonly connectorRefs: readonly ExtensionConnectorRefDto[];
}

export interface CreateExtensionPackageRequest {
  readonly namespace: string;
  readonly extensionUuid?: string | null;
  readonly displayName: string;
  readonly schemaDialect: string;
  readonly targetProfiles: readonly string[];
  readonly customFields?: readonly UpsertCustomFieldDefRequest[];
  readonly uiAnchors?: readonly UiAnchorCustomizationDto[];
  readonly feelRules?: readonly FeelRuleSpecDto[];
  readonly decisionTables?: readonly DmnDecisionTableDto[];
  readonly reports?: readonly ExtensionReportDefDto[];
  readonly printLayouts?: readonly ExtensionPrintLayoutDto[];
  readonly connectorRefs?: readonly ExtensionConnectorRefDto[];
  readonly rawDefinitionJson?: string | null;
}

export interface ValidationDiagnosticDto {
  readonly severity: string;
  readonly code: string;
  readonly location: string;
  readonly message: string;
}

export interface ExtensionPackageDto {
  readonly id: string;
  readonly companyId: string;
  readonly namespace: string;
  readonly extensionUuid: string;
  readonly displayName: string;
  readonly revisionNo: number;
  readonly schemaDialect: string;
  readonly definitionSha256Hex: string;
  readonly status: string;
  readonly bindingState?: string | null;
  readonly summary?: Readonly<Record<string, unknown>>;
  readonly validationDiagnostics: readonly ValidationDiagnosticDto[];
  readonly definition?: ExtensionPackageDefinitionDto | null;
  readonly versionNo: number;
  readonly createdBy?: string | null;
  readonly createdAt?: string;
}

export interface ExtensionBindingDto {
  readonly id: string;
  readonly companyId: string;
  readonly namespace: string;
  readonly extensionUuid: string;
  readonly extensionPackageId: string;
  readonly revisionNo: number;
  readonly displayName: string;
  readonly boundDefinitionSha256Hex: string;
  readonly state: string;
  readonly activatedBy?: string | null;
  readonly activatedAt?: string | null;
  readonly suspendedBy?: string | null;
  readonly suspendedAt?: string | null;
  readonly versionNo: number;
}

export interface EvaluatePreviewRequest {
  readonly expression?: string | null;
  readonly decisionTable?: DmnDecisionTableDto | null;
  readonly inputs: Readonly<Record<string, unknown>>;
}

export interface EvaluatePreviewResponse {
  readonly valid: boolean;
  readonly resultType: string;
  readonly resultValue: string;
  readonly matchedRuleIds: readonly string[];
  readonly outcome?: string | null;
  readonly messages: readonly string[];
  readonly astDepth: number;
  readonly stepsConsumed: number;
}

export interface ImportTdlProjectRequest {
  readonly projectName: string;
  readonly fileName: string;
  readonly sourceVersionClaim?: string | null;
  readonly tdlText?: string | null;
  readonly base64Content?: string | null;
  readonly includedFiles?: Readonly<Record<string, string>>;
}

export interface TdlConstructDto {
  readonly constructSeq: number;
  readonly constructType: string;
  readonly identifier: string;
  readonly sourceFile: string;
  readonly startLine: number;
  readonly endLine: number;
  readonly rawHeader: string;
  readonly disposition: TdlDisposition | string;
  readonly targetComponent: string;
  readonly mappedTargetCode?: string | null;
  readonly permissionImplication?: string | null;
  readonly explanation: string;
}

export interface TdlParityMatrixRowDto {
  readonly rowNo: number;
  readonly sourceRef: string;
  readonly constructIdentifier: string;
  readonly requiredBehaviour: string;
  readonly targetComponent: string;
  readonly disposition: TdlDisposition | string;
  readonly plannedFixture: string;
  readonly expectedResult: string;
  readonly executionStatus: string;
}

export interface TdlImportProjectDto {
  readonly id: string;
  readonly companyId: string;
  readonly projectName: string;
  readonly fileName: string;
  readonly sourceVersionClaim?: string | null;
  readonly sourceSha256Hex: string;
  readonly hasCompiledTcpBlock: boolean;
  readonly hasNativeDllBlock: boolean;
  readonly hasUnresolvedIncludes: boolean;
  readonly status: string;
  readonly draftExtensionId?: string | null;
  readonly summaryCounts: Readonly<Record<string, number>>;
  readonly constructs: readonly TdlConstructDto[];
  readonly parityMatrix: readonly TdlParityMatrixRowDto[];
  readonly createdAt?: string;
}

export interface OdbcProjectionQueryRequest {
  readonly projection: OdbcProjectionName | string;
  readonly fromDate?: string | null;
  readonly toDate?: string | null;
  readonly includeOptional?: boolean;
  readonly limit?: number;
  readonly rawSql?: string | null;
}

export interface OdbcProjectionColumnDto {
  readonly name: string;
  readonly dataType: string;
}

export interface OdbcProjectionQueryResponse {
  readonly projection: string;
  readonly asOf: string;
  readonly rowCount: number;
  readonly columns: readonly OdbcProjectionColumnDto[];
  readonly rows: readonly Readonly<Record<string, string>>[];
}
