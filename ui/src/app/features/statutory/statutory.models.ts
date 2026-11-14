import { BalanceSide } from '../masters/masters.models';

/**
 * MoneyCraft 1.0.0 — Phase 5 Statutory Compliance (Multi-GSTIN, Rule Packs,
 * GSTR-1/3B/9, Rel 7.2 IMS Reconciliation, e-Invoice/EWB, TDS 26Q) & MCA Edit Log Models.
 *
 * Every monetary value, tax rate, ITC reduction, and quantity is string-encoded
 * for exact `ExactDecimal` (`bigint`) arithmetic (zero IEEE-754 float math).
 */

export type GstRegistrationKind = 'REGULAR' | 'COMPOSITION' | 'ISD' | 'SEZ';
export type Gstr1Periodicity = 'MONTHLY' | 'QUARTERLY';
export type GstSupplyNature = 'INTRA_STATE' | 'INTER_STATE' | 'SEZ' | 'EXPORT' | 'EXEMPT';

// ---------------------------------------------------------------------------
// 1. Multi-GSTIN Registrations & Date-Effective Statutory Rule Packs
// ---------------------------------------------------------------------------

export interface GstRegistrationMaster {
  readonly id: string;
  readonly companyId?: string;
  readonly gstin: string;
  readonly stateCode: string;
  readonly stateName?: string | null;
  readonly registrationType: GstRegistrationKind;
  readonly effectiveFrom: string;
  readonly effectiveTo?: string | null;
  readonly gstr1Periodicity: Gstr1Periodicity;
  readonly lutBondNumber?: string | null;
  readonly lutValidFrom?: string | null;
  readonly lutValidTo?: string | null;
  readonly eInvoiceApplicable: boolean;
  readonly eInvoiceEffectiveFrom?: string | null;
  readonly eWayBillApplicable: boolean;
  readonly eWayBillThreshold?: string | null;
  readonly isPrimary: boolean;
  readonly active?: boolean;
  readonly versionNo: number;
}

export interface UpsertGstRegistrationRequest {
  readonly gstin: string;
  readonly stateCode: string;
  readonly stateName?: string | null;
  readonly registrationType: GstRegistrationKind;
  readonly effectiveFrom: string;
  readonly effectiveTo?: string | null;
  readonly gstr1Periodicity: Gstr1Periodicity;
  readonly lutBondNumber?: string | null;
  readonly lutValidFrom?: string | null;
  readonly lutValidTo?: string | null;
  readonly eInvoiceApplicable: boolean;
  readonly eInvoiceEffectiveFrom?: string | null;
  readonly eWayBillApplicable: boolean;
  readonly eWayBillThreshold?: string | null;
  readonly isPrimary: boolean;
  readonly active?: boolean;
  readonly versionNo?: number;
}

export interface StatutoryRulePackDto {
  readonly id: string;
  readonly packCode: string;
  readonly jurisdiction: string;
  readonly effectiveFrom: string;
  readonly effectiveTo?: string | null;
  readonly b2clThreshold?: string | null;
  readonly eWayBillThreshold?: string | null;
  readonly gstSlabs?: readonly string[] | null;
  readonly tdsSections?: readonly TdsNatureDto[];
  readonly signatureStatus?: string;
}

// ---------------------------------------------------------------------------
// 2. GST Compute Preview (`POST /gst/compute-preview`) & Uncertain Transactions
// ---------------------------------------------------------------------------

export interface GstLineBreakupDto {
  readonly lineNo?: number;
  readonly description: string;
  readonly hsnSacCode?: string | null;
  readonly rateSourceLevel?: string;
  readonly gstRatePct: string;
  readonly cessRatePct?: string | null;
  readonly assessableValue: string;
  readonly cgstAmount: string;
  readonly sgstAmount: string;
  readonly igstAmount: string;
  readonly cessAmount: string;
  readonly totalTaxAmount: string;
  readonly isDeemedMrpValuation?: boolean;
}

export interface GstSlabTotalDto {
  readonly gstRatePct: string;
  readonly assessableValue: string;
  readonly cgstAmount: string;
  readonly sgstAmount: string;
  readonly igstAmount: string;
  readonly cessAmount: string;
  readonly totalTaxAmount: string;
}

export interface GstSuggestedTaxLineDto {
  readonly dutyHead: 'CGST' | 'SGST_UTGST' | 'IGST' | 'CESS';
  readonly ledgerId?: string | null;
  readonly ledgerName: string;
  readonly side?: BalanceSide;
  readonly ratePct: string;
  readonly amount: string;
}

export interface GstComputePreviewResponse {
  readonly supplyNature: GstSupplyNature;
  readonly companyStateCode: string;
  readonly placeOfSupplyState: string;
  readonly lineBreakups: readonly GstLineBreakupDto[];
  readonly slabTotals: readonly GstSlabTotalDto[];
  readonly totalAssessableValue: string;
  readonly totalCgst: string;
  readonly totalSgst: string;
  readonly totalIgst: string;
  readonly totalCess: string;
  readonly totalTax: string;
  readonly suggestedTaxLines: readonly GstSuggestedTaxLineDto[];
}

export interface UncertainTransactionDto {
  readonly voucherId: string;
  readonly voucherDate: string;
  readonly voucherTypeName: string;
  readonly voucherNumber: string;
  readonly partyName: string;
  readonly partyGstin?: string | null;
  readonly exceptionCode: string;
  readonly exceptionReason: string;
  readonly taxableAmount: string;
  readonly computedTaxAmount: string;
  readonly bookedTaxAmount: string;
  readonly differenceAmount: string;
  readonly conflictAccepted?: boolean;
  readonly acceptedReason?: string | null;
}

export interface AcceptGstConflictRequest {
  readonly reason: string;
}

// ---------------------------------------------------------------------------
// 3. GSTR-1, GSTR-3B & Rel 7.2 One-Click GSTR-9 Annual Return
// ---------------------------------------------------------------------------

export interface Gstr1B2bRowDto {
  readonly voucherId?: string;
  readonly partyGstin: string;
  readonly partyName: string;
  readonly invoiceNumber: string;
  readonly invoiceDate: string;
  readonly invoiceValue: string;
  readonly placeOfSupply: string;
  readonly reverseCharge?: boolean;
  readonly gstRatePct?: string;
  readonly taxableValue: string;
  readonly igstAmount: string;
  readonly cgstAmount: string;
  readonly sgstAmount: string;
  readonly cessAmount: string;
}

export interface Gstr1B2clRowDto {
  readonly voucherId?: string;
  readonly invoiceNumber: string;
  readonly invoiceDate: string;
  readonly invoiceValue: string;
  readonly placeOfSupply: string;
  readonly gstRatePct: string;
  readonly taxableValue: string;
  readonly igstAmount: string;
  readonly cessAmount: string;
}

export interface Gstr1B2csRowDto {
  readonly supplyType: string;
  readonly placeOfSupply: string;
  readonly gstRatePct: string;
  readonly taxableValue: string;
  readonly igstAmount: string;
  readonly cgstAmount: string;
  readonly sgstAmount: string;
  readonly cessAmount: string;
}

export interface Gstr1CdnrRowDto {
  readonly voucherId?: string;
  readonly partyGstin: string;
  readonly partyName?: string;
  readonly noteNumber: string;
  readonly noteDate: string;
  readonly noteType: 'C' | 'D' | string;
  readonly noteValue: string;
  readonly taxableValue: string;
  readonly igstAmount: string;
  readonly cgstAmount: string;
  readonly sgstAmount: string;
  readonly cessAmount: string;
}

export interface Gstr1ExportRowDto {
  readonly voucherId?: string;
  readonly exportType: 'WPAY' | 'WOPAY' | string;
  readonly invoiceNumber: string;
  readonly invoiceDate: string;
  readonly invoiceValue: string;
  readonly portCode?: string | null;
  readonly shippingBillNo?: string | null;
  readonly shippingBillDate?: string | null;
  readonly taxableValue: string;
  readonly igstAmount: string;
  readonly cessAmount?: string;
}

export interface Gstr1HsnSummaryRowDto {
  readonly hsnSacCode: string;
  readonly description?: string | null;
  readonly uqcCode: string;
  readonly totalQuantity: string;
  readonly totalValue?: string;
  readonly gstRatePct?: string;
  readonly taxableValue: string;
  readonly igstAmount: string;
  readonly cgstAmount: string;
  readonly sgstAmount: string;
  readonly cessAmount: string;
}

export interface Gstr1DocSummaryRowDto {
  readonly documentNature: string;
  readonly serialFrom: string;
  readonly serialTo: string;
  readonly totalCount: number;
  readonly cancelledCount: number;
  readonly netIssuedCount: number;
}

export interface Gstr1ReportDto {
  readonly fromDate?: string;
  readonly toDate?: string;
  readonly gstRegistrationId?: string | null;
  readonly gstin?: string | null;
  readonly b2b: readonly Gstr1B2bRowDto[];
  readonly b2cl: readonly Gstr1B2clRowDto[];
  readonly b2cs: readonly Gstr1B2csRowDto[];
  readonly cdnr: readonly Gstr1CdnrRowDto[];
  readonly exp: readonly Gstr1ExportRowDto[];
  readonly hsnB2b: readonly Gstr1HsnSummaryRowDto[];
  readonly hsnB2c: readonly Gstr1HsnSummaryRowDto[];
  readonly documentSummary: readonly Gstr1DocSummaryRowDto[];
  readonly uncertainCount: number;
  readonly totalTaxableValue: string;
  readonly totalIgst: string;
  readonly totalCgst: string;
  readonly totalSgst: string;
  readonly totalCess: string;
  readonly totalTax: string;
}

export interface Gstr3bTaxBucketDto {
  readonly taxableValue?: string;
  readonly igstAmount: string;
  readonly cgstAmount: string;
  readonly sgstAmount: string;
  readonly cessAmount: string;
  readonly totalTax: string;
}

export interface Gstr3bReportDto {
  readonly fromDate?: string;
  readonly toDate?: string;
  readonly returnPeriod?: string;
  readonly gstRegistrationId?: string | null;
  readonly gstin?: string | null;
  readonly outwardTaxable: Gstr3bTaxBucketDto;
  readonly inwardRcm: Gstr3bTaxBucketDto;
  readonly eligibleItc: Gstr3bTaxBucketDto;
  readonly itcReversed: Gstr3bTaxBucketDto;
  readonly netTaxPayable: Gstr3bTaxBucketDto;
  readonly imsAcceptedCount?: number;
  readonly imsPendingItcDeferred?: string;
  readonly imsItcReductionTotal?: string;
  readonly uncertainCount?: number;
}

export type Gstr9ExportFormat = 'XLSX' | 'JSON';

export interface Gstr9TableRowDto {
  readonly tableCode: string;
  readonly description: string;
  readonly taxableValue: string;
  readonly igstAmount: string;
  readonly cgstAmount: string;
  readonly sgstAmount: string;
  readonly cessAmount: string;
}

export interface Gstr9ReportDto {
  readonly fiscalYearId: string;
  readonly financialYearLabel?: string;
  readonly gstin?: string | null;
  readonly table4Outward: readonly Gstr9TableRowDto[];
  readonly table5Exempt?: readonly Gstr9TableRowDto[];
  readonly table6ItcAvailed: readonly Gstr9TableRowDto[];
  readonly table7ItcReversed?: readonly Gstr9TableRowDto[];
  readonly table8Reconciliation?: readonly Gstr9TableRowDto[];
  readonly table9TaxPaid?: readonly Gstr9TableRowDto[];
  readonly table17HsnOutward: readonly Gstr1HsnSummaryRowDto[];
  readonly table18HsnInward?: readonly Gstr1HsnSummaryRowDto[];
  readonly totalOutwardTaxable: string;
  readonly totalOutwardTax: string;
  readonly totalItcAvailed: string;
  readonly totalItcReversed: string;
  readonly netTaxPayable: string;
  readonly reconciliationDifference: string;
}

// ---------------------------------------------------------------------------
// 4. TallyPrime Rel 7.2 IMS (Invoice Management System) Reconciliation
// ---------------------------------------------------------------------------

export type ImsActionType = 'ACCEPT' | 'REJECT' | 'PENDING' | 'NO_ACTION';
export type ImsMatchStatus = 'EXACT_MATCH' | 'PARTIAL_MISMATCH' | 'MISSING_IN_BOOKS';

export interface ImsInvoiceDto {
  readonly id: string;
  readonly supplierGstin: string;
  readonly supplierName: string;
  readonly invoiceNumber: string;
  readonly invoiceDate: string;
  readonly returnPeriod: string;
  readonly invoiceValue: string;
  readonly taxableValue: string;
  readonly igstAmount: string;
  readonly cgstAmount: string;
  readonly sgstAmount: string;
  readonly cessAmount: string;
  readonly portalItcAmount: string;
  readonly booksVoucherId?: string | null;
  readonly booksInvoiceValue?: string | null;
  readonly booksTaxAmount?: string | null;
  readonly matchStatus: ImsMatchStatus;
  readonly imsStatus: ImsActionType;
  readonly itcReductionAmount: string;
  readonly remarks?: string | null;
  readonly effectiveFilingPeriod?: string | null;
}

export interface ImsActionRequest {
  readonly imsInvoiceId: string;
  readonly action: ImsActionType;
  readonly itcReductionAmount?: string;
  readonly remarks?: string | null;
  readonly effectiveFilingPeriod?: string | null;
}

export interface ImsImportPayload {
  readonly returnPeriod: string;
  readonly gstRegistrationId?: string | null;
  readonly invoices: readonly Record<string, unknown>[];
}

// ---------------------------------------------------------------------------
// 5. e-Invoice, e-Way Bill & TDS Form 26Q (Protean FVU 1.2)
// ---------------------------------------------------------------------------

export interface EinvoiceGenerateResponse {
  readonly voucherId: string;
  readonly voucherNumber?: string;
  readonly status: 'GENERATED' | 'CANCELLED' | 'OFFLINE_EXPORTED' | string;
  readonly irn: string;
  readonly ackNo: string;
  readonly ackDate: string;
  readonly signedQrCode?: string | null;
  readonly ewbNo?: string | null;
  readonly sessionValidUntil?: string | null;
}

export interface EinvoiceCancelRequest {
  readonly cancelReasonCode: string;
  readonly cancelRemarks: string;
}

export interface EwayBillGenerateRequest {
  readonly transporterId?: string | null;
  readonly transporterName?: string | null;
  readonly transportMode: 'ROAD' | 'RAIL' | 'AIR' | 'SHIP';
  readonly distanceKm: number;
  readonly vehicleNo?: string | null;
  readonly transportDocNo?: string | null;
  readonly transportDocDate?: string | null;
}

export interface EwayBillGenerateResponse {
  readonly voucherId: string;
  readonly ewbNo: string;
  readonly ewbDate: string;
  readonly ewbValidUpto: string;
  readonly status: string;
}

export type TdsQuarter = 'Q1' | 'Q2' | 'Q3' | 'Q4';

export interface TdsNatureDto {
  readonly id?: string;
  readonly sectionCode: string;
  readonly paymentCode?: string;
  readonly description: string;
  readonly singleBillThreshold: string;
  readonly annualAggregateThreshold: string;
  readonly rateIndividualHufPct: string;
  readonly rateOtherDeducteePct: string;
  readonly noPanHigherRatePct: string;
}

export interface CreateTdsChallanRequest {
  readonly quarter: TdsQuarter;
  readonly fiscalYearId?: string;
  readonly sectionCode: string;
  readonly bsrCode: string;
  readonly challanSerialNo: string;
  readonly depositDate: string;
  readonly bankLedgerId?: string | null;
  readonly tdsAmount: string;
  readonly surchargeAmount?: string;
  readonly cessAmount?: string;
  readonly interestAmount?: string;
  readonly feeSec234eAmount?: string;
}

export interface TdsChallanDto {
  readonly id: string;
  readonly quarter: TdsQuarter;
  readonly sectionCode: string;
  readonly bsrCode: string;
  readonly challanSerialNo: string;
  readonly depositDate: string;
  readonly tdsAmount: string;
  readonly totalAmount: string;
}

export interface TdsDeductionRowDto {
  readonly voucherId?: string;
  readonly voucherNumber?: string;
  readonly deductionDate: string;
  readonly sectionCode: string;
  readonly deducteeName: string;
  readonly deducteePan?: string | null;
  readonly hasValidPan: boolean;
  readonly higherRate206aaApplied: boolean;
  readonly lowerCertNumber?: string | null;
  readonly grossAmount: string;
  readonly tdsRatePct: string;
  readonly tdsAmount: string;
  readonly challanId?: string | null;
}

export interface TdsForm26qReportDto {
  readonly quarter: TdsQuarter;
  readonly fiscalYearId: string;
  readonly deductions: readonly TdsDeductionRowDto[];
  readonly challans: readonly TdsChallanDto[];
  readonly totalGrossAmount: string;
  readonly totalTdsDeducted: string;
  readonly totalTdsDeposited: string;
  readonly unpaidTdsAmount: string;
}

// ---------------------------------------------------------------------------
// 6. Immutable MCA Edit Log (`Alt+Q`)
// ---------------------------------------------------------------------------

export type EditLogEntityType =
  | 'VOUCHER'
  | 'LEDGER'
  | 'ACCOUNT_GROUP'
  | 'STOCK_ITEM'
  | 'STOCK_GROUP'
  | 'STOCK_CATEGORY'
  | 'GODOWN'
  | 'UOM'
  | 'COST_CENTRE';

export type EditLogOperation = 'CREATE' | 'ALTER' | 'CANCEL' | 'DELETE' | 'MIGRATE_IN';

export interface EditLogChangedFieldDto {
  readonly fieldPath: string;
  readonly beforeValue: unknown;
  readonly afterValue: unknown;
}

export interface EditLogVersionDto {
  readonly id?: string;
  readonly versionNo: number;
  readonly masterId: number;
  readonly alterId: number;
  readonly operation: EditLogOperation | string;
  readonly actorPrincipalId?: string | null;
  readonly actorUsername: string;
  readonly occurredAt: string;
  readonly voucherNumberSnapshot?: string | null;
  readonly voucherDateSnapshot?: string | null;
  readonly canonicalSnapshot: Record<string, unknown> | string;
  readonly changedFields: readonly EditLogChangedFieldDto[];
  readonly snapshotSha256Hex: string;
}
