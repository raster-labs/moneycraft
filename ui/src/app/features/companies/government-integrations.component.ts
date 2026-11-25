import { HttpClient, HttpHeaders } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth/auth.store';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import {
  ActiveModalContextHandle,
  ModalActionDefinition,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../core/keyboard/shortcut-command.registry';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { HelpPanelComponent } from '../../shared/components/contextual-help/help-panel.component';
import { HelpTooltipDirective } from '../../shared/components/contextual-help/help-tooltip.directive';
import { HelpTriggerComponent } from '../../shared/components/contextual-help/help-trigger.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { GstRegistrationMaster } from '../statutory/statutory.models';

/**
 * MoneyCraft Host Iteration `v1.1.0` — `SUPER_ADMIN` Government & Connected Banking
 * Protected Configuration Console (`Docs 51 & 52`, `UM-110-04`, `ITER110-012..017`).
 *
 * Security & Domain Guarantees:
 * - Visible and operable ONLY by an enabled `PERSON` carrying explicit installation `superAdmin: true`
 *   designation (distinct from `INSTALLATION_OWNER`, `security.admin`, and `COMPANY_ADMIN`).
 * - Every mutation requires a fresh single-use 5-minute `POST /api/v1/auth/step-up/password` intent
 *   bound to `(targetProfileId, action, expectedRevision)`, `If-Match`, and `Idempotency-Key`.
 * - Write-only credential staging: zero secret readback, reveal, copy, download, export, ciphertext,
 *   or browser storage persistence.
 * - Safe validation (`POST .../validations` -> `202`) performs only harmless auth/health probes and
 *   never creates/cancels an IRN, files a return, generates an EWB, or initiates a bank payout.
 */

export type IntegrationEnvironment = 'SANDBOX' | 'PRODUCTION';

export type IntegrationProfileStatus =
  | 'DRAFT'
  | 'CONFIGURED'
  | 'VALIDATION_PENDING'
  | 'VALIDATED'
  | 'ACTIVE'
  | 'DISABLED'
  | 'EXPIRED_OR_REVOKED'
  | 'FAILED';

export type CredentialSlotStatus = 'CONFIGURED' | 'MISSING' | 'EXPIRED' | 'VALIDATION_FAILED';

export type DisableReasonCode =
  | 'ROTATION_MAINTENANCE'
  | 'CONSENT_REVOKED'
  | 'SUSPECTED_COMPROMISE'
  | 'PROVIDER_OUTAGE'
  | 'DECOMMISSIONED';

export type StepUpAdminAction =
  | 'government.profile.create'
  | 'government.profile.update'
  | 'government.profile.credential.stage'
  | 'government.profile.validation.start'
  | 'government.profile.activate'
  | 'government.profile.disable';

export interface ProviderCredentialSlotSchema {
  readonly slotKey: string;
  readonly label: string;
  readonly secretKind: 'SECRET_VALUE' | 'SERVER_SECRET_REF';
  readonly maxLength: number;
  readonly required: boolean;
  readonly hint: string;
}

export interface AdmittedIntegrationProviderSpec {
  readonly providerCode: string;
  readonly adapterCode: string;
  readonly displayName: string;
  readonly domainCategory: 'GOVERNMENT_STATUTORY' | 'CONNECTED_BANKING';
  readonly jurisdictionOrService: string;
  readonly sandboxEndpointProfileId: string;
  readonly productionEndpointProfileId: string;
  readonly registrationKind: 'GSTIN' | 'PAN' | 'TAN' | 'CORPORATE_BANK_ID';
  readonly allowedOperations: readonly string[];
  readonly credentialSlots: readonly ProviderCredentialSlotSchema[];
  readonly safeValidationProbeSummary: string;
  readonly probeSideEffectNote: string;
}

export interface GovernmentCapabilityInventoryRow {
  readonly draftCapabilityId: string;
  readonly capabilityFamily: string;
  readonly operationScope: string;
  readonly channelClass: string;
  readonly officialEvidenceStatus: string;
  readonly authProfileRef: string;
  readonly knownSideEffects: string;
  readonly moneycraftImplementationStatus: string;
  readonly verificationStatus: string;
  readonly limitations: string;
}

export interface CredentialSlotMetadataView {
  readonly slotKey: string;
  readonly status: CredentialSlotStatus;
  readonly credentialVersionId: string | null;
  readonly versionNo: number | null;
  readonly updatedAt: string | null;
}

export interface ValidationOutcomeView {
  readonly validationId: string;
  readonly status: 'PENDING' | 'VALIDATED' | 'FAILED' | 'NOT_VALIDATED';
  readonly testedRevision: string;
  readonly testedCredentialRevision: string | null;
  readonly summaryMessage: string;
  readonly checkedAt: string | null;
}

export interface GovernmentIntegrationProfileView {
  readonly id: string;
  readonly profileName: string;
  readonly providerCode: string;
  readonly adapterCode: string;
  readonly endpointProfileId: string;
  readonly domainCategory: 'GOVERNMENT_STATUTORY' | 'CONNECTED_BANKING';
  readonly environment: IntegrationEnvironment;
  readonly companyId: string;
  readonly registrationRef: string;
  readonly allowedOperationIds: readonly string[];
  readonly consentEvidenceRef: string;
  readonly status: IntegrationProfileStatus;
  readonly credentialRevision: string;
  readonly credentialSlots: readonly CredentialSlotMetadataView[];
  readonly certificateFingerprintSha256: string | null;
  readonly certificateExpiresAt: string | null;
  readonly lastValidation: ValidationOutcomeView | null;
  readonly revision: string;
  readonly etag: string | null;
  readonly updatedAt: string | null;
}

export interface GovernmentProfileAuditEntry {
  readonly eventId: string;
  readonly timestamp: string;
  readonly actor: string;
  readonly action: string;
  readonly revision: string;
  readonly result: string;
  readonly reason: string | null;
  readonly correlationId: string | null;
}

export interface StepUpPasswordResponse {
  readonly stepUpIntentId: string;
  readonly expiresAt: string;
}

export function isPrincipalSuperAdmin(principal: unknown): boolean {
  if (!principal || typeof principal !== 'object') {
    return false;
  }
  const rec = principal as Record<string, unknown>;
  return rec['superAdmin'] === true;
}

export const ADMITTED_INTEGRATION_PROVIDERS: readonly AdmittedIntegrationProviderSpec[] =
  Object.freeze([
    {
      providerCode: 'GSP_UNIFIED',
      adapterCode: 'GSP_UNIFIED_V1',
      displayName: 'Unified GST Suvidha Provider (GSTR-1 / 2B / 3B / IMS)',
      domainCategory: 'GOVERNMENT_STATUTORY',
      jurisdictionOrService: 'IN-GSTN / GSP-ASP',
      sandboxEndpointProfileId: 'GSP_SANDBOX_V1',
      productionEndpointProfileId: 'GSP_PRODUCTION_V1',
      registrationKind: 'GSTIN',
      allowedOperations: ['GOV-D05', 'GOV-D06', 'GOV-D08', 'GOV-D09', 'GOV-D10'],
      credentialSlots: [
        {
          slotKey: 'aspClientId',
          label: 'ASP Client ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Contracted GSP partner ASP client identifier.',
        },
        {
          slotKey: 'aspClientSecret',
          label: 'ASP Client Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only GSP ASP secret; encrypted at rest via Tink.',
        },
        {
          slotKey: 'gspAppKey',
          label: 'GSP Application Key / Public Cert Ref',
          secretKind: 'SECRET_VALUE',
          maxLength: 512,
          required: true,
          hint: 'Session key / RSA public key reference required by GSP.',
        },
        {
          slotKey: 'taxpayerPortalUsername',
          label: 'Taxpayer GST API Username',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Taxpayer API username with active Manage API Access consent.',
        },
      ],
      safeValidationProbeSummary: 'GSP partner authentication & taxpayer consent token check',
      probeSideEffectNote:
        'Creates/verifies read-only GSP auth session; may request taxpayer OTP if consent expired. Performs zero return upload or filing.',
    },
    {
      providerCode: 'EINVOICE_IRP6',
      adapterCode: 'EINVOICE_IRP6_V1',
      displayName: 'GSTN IRP6 e-Invoice Partner Portal Adapter',
      domainCategory: 'GOVERNMENT_STATUTORY',
      jurisdictionOrService: 'IN-GST-IRP6',
      sandboxEndpointProfileId: 'IRP6_SANDBOX_V1',
      productionEndpointProfileId: 'IRP6_PRODUCTION_V1',
      registrationKind: 'GSTIN',
      allowedOperations: ['GOV-D03', 'GOV-D12', 'GOV-D13', 'GOV-D14', 'GOV-D15', 'GOV-D16', 'GOV-D17'],
      credentialSlots: [
        {
          slotKey: 'clientId',
          label: 'IRP6 Partner Client ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Issued during IRP6 sandbox/production onboarding.',
        },
        {
          slotKey: 'clientSecret',
          label: 'IRP6 Partner Client Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only partner secret; never readable after staging.',
        },
        {
          slotKey: 'taxpayerApiUsername',
          label: 'Taxpayer e-Invoice API Username',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Dedicated API user created on IRP portal (distinct from web login).',
        },
        {
          slotKey: 'taxpayerApiPassword',
          label: 'Taxpayer e-Invoice API Password',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only API password for encrypted IRP6 session.',
        },
      ],
      safeValidationProbeSummary: 'IRP6 Core Service Health & Partner Session Probe (GOV-D17)',
      probeSideEffectNote:
        'Executes harmless IRP health/session handshake only. Never generates or cancels an IRN.',
    },
    {
      providerCode: 'EINVOICE_NIC',
      adapterCode: 'EINVOICE_NIC_V1',
      displayName: 'NIC e-Invoice IRP1 Portal Adapter',
      domainCategory: 'GOVERNMENT_STATUTORY',
      jurisdictionOrService: 'IN-GST-NIC-IRP1',
      sandboxEndpointProfileId: 'NIC_IRP_SANDBOX_V1',
      productionEndpointProfileId: 'NIC_IRP_PRODUCTION_V1',
      registrationKind: 'GSTIN',
      allowedOperations: ['GOV-D12', 'GOV-D13', 'GOV-D14', 'GOV-D15', 'GOV-D17'],
      credentialSlots: [
        {
          slotKey: 'clientId',
          label: 'NIC IRP Client ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'NIC / GSP issued client identifier.',
        },
        {
          slotKey: 'clientSecret',
          label: 'NIC IRP Client Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only client secret.',
        },
        {
          slotKey: 'taxpayerApiUsername',
          label: 'e-Invoice API Username',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Taxpayer IRP API username.',
        },
        {
          slotKey: 'taxpayerApiPassword',
          label: 'e-Invoice API Password',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only taxpayer IRP API password.',
        },
      ],
      safeValidationProbeSummary: 'NIC IRP1 Authentication & Health Probe (GOV-D17)',
      probeSideEffectNote: 'Establishes short-lived auth context only; zero fiscal document mutation.',
    },
    {
      providerCode: 'EWAYBILL_NIC',
      adapterCode: 'EWAYBILL_NIC_V103',
      displayName: 'NIC e-Way Bill v1.03 Adapter',
      domainCategory: 'GOVERNMENT_STATUTORY',
      jurisdictionOrService: 'IN-NIC-EWB-V103',
      sandboxEndpointProfileId: 'NIC_EWB_SANDBOX_V103',
      productionEndpointProfileId: 'NIC_EWB_PRODUCTION_V103',
      registrationKind: 'GSTIN',
      allowedOperations: [
        'GOV-D02',
        'GOV-D18',
        'GOV-D19',
        'GOV-D20',
        'GOV-D21',
        'GOV-D22',
        'GOV-D23',
        'GOV-D24',
        'GOV-D25',
      ],
      credentialSlots: [
        {
          slotKey: 'gspClientId',
          label: 'EWB / GSP Client ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'NIC direct or GSP client ID for v1.03 auth.',
        },
        {
          slotKey: 'gspClientSecret',
          label: 'EWB / GSP Client Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only v1.03 client secret.',
        },
        {
          slotKey: 'ewbApiUsername',
          label: 'Taxpayer EWB API Username',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'API username registered under ewaybillgst.gov.in.',
        },
        {
          slotKey: 'ewbApiPassword',
          label: 'Taxpayer EWB API Password',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only encrypted v1.03 payload password.',
        },
      ],
      safeValidationProbeSummary: 'NIC EWB Authenticate v1.03 Session Check (GOV-D18)',
      probeSideEffectNote:
        'Validates v1.03 encrypted auth payload only. Never generates, updates, or cancels an e-Way Bill.',
    },
    {
      providerCode: 'GSTN_IMS',
      adapterCode: 'GSTN_IMS_V1',
      displayName: 'GSTN Invoice Management System (IMS) Adapter',
      domainCategory: 'GOVERNMENT_STATUTORY',
      jurisdictionOrService: 'IN-GSTN-IMS',
      sandboxEndpointProfileId: 'GSTN_IMS_SANDBOX_V1',
      productionEndpointProfileId: 'GSTN_IMS_PRODUCTION_V1',
      registrationKind: 'GSTIN',
      allowedOperations: ['GOV-D05', 'GOV-D08', 'GOV-D10'],
      credentialSlots: [
        {
          slotKey: 'aspClientId',
          label: 'ASP Client ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'GSP/ASP client ID.',
        },
        {
          slotKey: 'aspClientSecret',
          label: 'ASP Client Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only ASP secret.',
        },
        {
          slotKey: 'taxpayerPortalUsername',
          label: 'Taxpayer GST API Username',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Taxpayer API username.',
        },
      ],
      safeValidationProbeSummary: 'GSTN IMS Read-Only Session & Consent Check',
      probeSideEffectNote:
        'Checks read-only session status; never uploads or resets IMS invoice actions.',
    },
    {
      providerCode: 'GSTIN_LOOKUP',
      adapterCode: 'NIC_GSTIN_LOOKUP_V103',
      displayName: 'Authenticated GetGSTINDetails Lookup Adapter',
      domainCategory: 'GOVERNMENT_STATUTORY',
      jurisdictionOrService: 'IN-NIC-MASTER-LOOKUP',
      sandboxEndpointProfileId: 'GSTIN_LOOKUP_SANDBOX_V103',
      productionEndpointProfileId: 'GSTIN_LOOKUP_PRODUCTION_V103',
      registrationKind: 'GSTIN',
      allowedOperations: ['GOV-D02', 'GOV-D03'],
      credentialSlots: [
        {
          slotKey: 'clientId',
          label: 'Service Client ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Authorised EWB/IRP lookup client ID.',
        },
        {
          slotKey: 'clientSecret',
          label: 'Service Client Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only lookup client secret.',
        },
      ],
      safeValidationProbeSummary: 'Read-only GetGSTINDetails self-registration check (GOV-D02)',
      probeSideEffectNote: 'Read-only lookup; never alters accounting masters without user acceptance.',
    },
    {
      providerCode: 'ITD_ERI_TYPE2',
      adapterCode: 'ITD_ERI_TYPE2_V11',
      displayName: 'Income Tax Department Type-2 ERI Adapter',
      domainCategory: 'GOVERNMENT_STATUTORY',
      jurisdictionOrService: 'IN-ITD-ERI-TYPE2',
      sandboxEndpointProfileId: 'ITD_ERI_SANDBOX_V11',
      productionEndpointProfileId: 'ITD_ERI_PRODUCTION_V11',
      registrationKind: 'PAN',
      allowedOperations: ['GOV-D26', 'GOV-D27', 'GOV-D28', 'GOV-D29', 'GOV-D30', 'GOV-D31'],
      credentialSlots: [
        {
          slotKey: 'eriUserId',
          label: 'Registered Type-2 ERI User ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Approved ITD Type-2 ERI registration identifier.',
        },
        {
          slotKey: 'gatewayClientSecret',
          label: 'ITD Gateway Client Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only ERI gateway secret.',
        },
        {
          slotKey: 'dscSigningKeyRef',
          label: 'Server DSC Signing Key Reference',
          secretKind: 'SERVER_SECRET_REF',
          maxLength: 256,
          required: true,
          hint: 'Server-side /run/secrets or HSM reference for Class-3 Org DSC.',
        },
      ],
      safeValidationProbeSummary: 'Type-2 ERI Gateway Handshake Verification (GOV-D26)',
      probeSideEffectNote: 'Checks gateway auth/cert binding only; zero client addition or ITR submission.',
    },
    {
      providerCode: 'PROTEAN_PAN_VERIFY',
      adapterCode: 'PROTEAN_PAN_V1',
      displayName: 'Protean Authorised-Entity Online PAN Verification',
      domainCategory: 'GOVERNMENT_STATUTORY',
      jurisdictionOrService: 'IN-PROTEAN-PAN',
      sandboxEndpointProfileId: 'PROTEAN_PAN_SANDBOX_V1',
      productionEndpointProfileId: 'PROTEAN_PAN_PRODUCTION_V1',
      registrationKind: 'PAN',
      allowedOperations: ['GOV-D32', 'GOV-D33'],
      credentialSlots: [
        {
          slotKey: 'proteanUserId',
          label: 'Protean Enrolment User ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Authorised-entity Protean agency user ID.',
        },
        {
          slotKey: 'apiAccessToken',
          label: 'Protean API Access Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only Protean API secret.',
        },
        {
          slotKey: 'signingCertKeyRef',
          label: 'mTLS / Signing Certificate Reference',
          secretKind: 'SERVER_SECRET_REF',
          maxLength: 256,
          required: true,
          hint: 'Server-side X.509 certificate reference.',
        },
      ],
      safeValidationProbeSummary: 'Protean mTLS & Agency Credential Check',
      probeSideEffectNote:
        'Verifies certificate & credential envelope; live verification calls may incur per-query provider fee.',
    },
    {
      providerCode: 'ICICI_CIB',
      adapterCode: 'ICICI_CIB_CONNECTED_V1',
      displayName: 'ICICI Bank Corporate Connected Banking (CIB)',
      domainCategory: 'CONNECTED_BANKING',
      jurisdictionOrService: 'IN-BANK-ICICI-CIB',
      sandboxEndpointProfileId: 'ICICI_CIB_SANDBOX_V1',
      productionEndpointProfileId: 'ICICI_CIB_PRODUCTION_V1',
      registrationKind: 'CORPORATE_BANK_ID',
      allowedOperations: [
        'BANK-BAL-INQUIRY',
        'BANK-STMT-PULL',
        'BANK-PAYOUT-STATUS',
        'BANK-COMPOSITE-PAYOUT',
      ],
      credentialSlots: [
        {
          slotKey: 'apiKey',
          label: 'ICICI Developer API Key',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only ICICI gateway apikey.',
        },
        {
          slotKey: 'aggregatorId',
          label: 'Aggregator ID (AGGRID)',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Assigned ERP aggregator identifier.',
        },
        {
          slotKey: 'corpIdAndUrn',
          label: 'Corporate ID / URN Binding',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Approved CIB Corporate ID and URN.',
        },
        {
          slotKey: 'rsaPrivateKeyRef',
          label: 'Hybrid RSA-2048 Key Reference',
          secretKind: 'SERVER_SECRET_REF',
          maxLength: 256,
          required: true,
          hint: 'Server-side private key reference for payload envelope.',
        },
      ],
      safeValidationProbeSummary: 'ICICI CIB Read-Only Handshake / Balance Probe',
      probeSideEffectNote: 'Read-only inquiry only; never initiates NEFT/RTGS/IMPS/UPI payout.',
    },
    {
      providerCode: 'HDFC_CBX',
      adapterCode: 'HDFC_CBX_ENET_V1',
      displayName: 'HDFC Bank Corporate CBX / ENet Connected Banking',
      domainCategory: 'CONNECTED_BANKING',
      jurisdictionOrService: 'IN-BANK-HDFC-CBX',
      sandboxEndpointProfileId: 'HDFC_CBX_SANDBOX_V1',
      productionEndpointProfileId: 'HDFC_CBX_PRODUCTION_V1',
      registrationKind: 'CORPORATE_BANK_ID',
      allowedOperations: [
        'BANK-BAL-INQUIRY',
        'BANK-STMT-PULL',
        'BANK-PAYOUT-STATUS',
        'BANK-BULK-PAYOUT',
      ],
      credentialSlots: [
        {
          slotKey: 'clientId',
          label: 'HDFC API Client ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Subscribed corporate portal client ID.',
        },
        {
          slotKey: 'clientSecret',
          label: 'HDFC API Client Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only client secret.',
        },
        {
          slotKey: 'mtlsAndJweKeyRef',
          label: 'mTLS + JWS/JWE Key Reference',
          secretKind: 'SERVER_SECRET_REF',
          maxLength: 256,
          required: true,
          hint: 'Server-side X.509 mTLS + RS256/RSA-OAEP key reference.',
        },
      ],
      safeValidationProbeSummary: 'HDFC mTLS & OAuth Token Read-Only Probe',
      probeSideEffectNote: 'Verifies mutual TLS & scope token only; zero payment dispatch.',
    },
    {
      providerCode: 'AXIS_CORP',
      adapterCode: 'AXIS_CORP_NEO_V1',
      displayName: 'Axis Bank Corporate Neo / ARS Connected Banking',
      domainCategory: 'CONNECTED_BANKING',
      jurisdictionOrService: 'IN-BANK-AXIS-CORP',
      sandboxEndpointProfileId: 'AXIS_CORP_SANDBOX_V1',
      productionEndpointProfileId: 'AXIS_CORP_PRODUCTION_V1',
      registrationKind: 'CORPORATE_BANK_ID',
      allowedOperations: [
        'BANK-BAL-INQUIRY',
        'BANK-STMT-PULL',
        'BANK-PAYOUT-STATUS',
        'BANK-TRANSFER-PAYMENT',
      ],
      credentialSlots: [
        {
          slotKey: 'ibmClientId',
          label: 'X-IBM-Client-Id',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Axis API Connect client identifier.',
        },
        {
          slotKey: 'ibmClientSecret',
          label: 'X-IBM-Client-Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only Axis client secret.',
        },
        {
          slotKey: 'aesAndMtlsKeyRef',
          label: 'AES-256 & mTLS Certificate Reference',
          secretKind: 'SERVER_SECRET_REF',
          maxLength: 256,
          required: true,
          hint: 'Server-side mTLS and payload checksum key reference.',
        },
      ],
      safeValidationProbeSummary: 'Axis IBM Gateway mTLS & Account Status Probe',
      probeSideEffectNote: 'Read-only status check; never executes TransferPayment.',
    },
    {
      providerCode: 'SBI_CMP',
      adapterCode: 'SBI_YONO_CMP_V1',
      displayName: 'State Bank of India YONO Business / CMP Connected Banking',
      domainCategory: 'CONNECTED_BANKING',
      jurisdictionOrService: 'IN-BANK-SBI-CMP',
      sandboxEndpointProfileId: 'SBI_CMP_SANDBOX_V1',
      productionEndpointProfileId: 'SBI_CMP_PRODUCTION_V1',
      registrationKind: 'CORPORATE_BANK_ID',
      allowedOperations: [
        'BANK-BAL-INQUIRY',
        'BANK-STMT-PULL',
        'BANK-CMP-STATUS',
        'BANK-CMP-PAYMENT',
      ],
      credentialSlots: [
        {
          slotKey: 'partnerClientCode',
          label: 'SBI Partner Client Code / CIN',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Approved SBI YONO Business / CMP corporate code.',
        },
        {
          slotKey: 'aesGcmSecretKey',
          label: 'AES-256-GCM Session Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only SBI payload key.',
        },
        {
          slotKey: 'mtlsSigningCertRef',
          label: 'mTLS & RSA-2048 Signing Cert Ref',
          secretKind: 'SERVER_SECRET_REF',
          maxLength: 256,
          required: true,
          hint: 'Server-side X.509 mTLS and signing certificate reference.',
        },
      ],
      safeValidationProbeSummary: 'SBI CMP Partner mTLS & Balance Inquiry Probe',
      probeSideEffectNote: 'Read-only partner handshake; zero CMP payment instruction.',
    },
    {
      providerCode: 'AGGREGATOR_PAYOUT',
      adapterCode: 'AGGREGATOR_PAYOUT_V1',
      displayName: 'Corporate Payout & Verification Aggregator (RazorpayX / Cashfree / Setu)',
      domainCategory: 'CONNECTED_BANKING',
      jurisdictionOrService: 'IN-PAYOUT-AGGREGATOR',
      sandboxEndpointProfileId: 'AGGREGATOR_SANDBOX_V1',
      productionEndpointProfileId: 'AGGREGATOR_PRODUCTION_V1',
      registrationKind: 'CORPORATE_BANK_ID',
      allowedOperations: [
        'BANK-BAL-INQUIRY',
        'BANK-PENNY-DROP-VERIFY',
        'BANK-PAYOUT-STATUS',
        'BANK-COMPOSITE-PAYOUT',
      ],
      credentialSlots: [
        {
          slotKey: 'keyId',
          label: 'Aggregator Key ID',
          secretKind: 'SECRET_VALUE',
          maxLength: 128,
          required: true,
          hint: 'Issued Sandbox or Live Key ID.',
        },
        {
          slotKey: 'keySecret',
          label: 'Aggregator Key Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: true,
          hint: 'Write-only Key Secret.',
        },
        {
          slotKey: 'webhookVerifySecret',
          label: 'Webhook HMAC Verification Secret',
          secretKind: 'SECRET_VALUE',
          maxLength: 256,
          required: false,
          hint: 'Optional write-only webhook signature verification secret.',
        },
      ],
      safeValidationProbeSummary: 'Aggregator Key Auth & Source Account Read Probe',
      probeSideEffectNote: 'Read-only credential verification; zero payout or chargeable penny-drop.',
    },
  ]);

/**
 * Complete `GOV-D01..GOV-D39` (`government_interface_inventory.csv`) + 5 Connected Banking rows
 * bundled locally and reconciled with `GET /api/v1/admin/government-interface-inventory`.
 */
export const STATIC_GOVERNMENT_AND_BANKING_INVENTORY: readonly GovernmentCapabilityInventoryRow[] =
  Object.freeze([
    {
      draftCapabilityId: 'GOV-D01',
      capabilityFamily: 'GST taxpayer information',
      operationScope: 'Manual taxpayer lookup',
      channelClass: 'HUMAN_PORTAL',
      officialEvidenceStatus: 'OFFICIAL_UI_DOCUMENTED',
      authProfileRef: 'AUTH-PORTAL',
      knownSideEffects: 'Read-only human lookup',
      moneycraftImplementationStatus: 'EXCLUDED_HUMAN_CAPTCHA_PORTAL',
      verificationStatus: 'NOT RUN',
      limitations: 'Pre-login CAPTCHA route; not an unauthenticated JSON API',
    },
    {
      draftCapabilityId: 'GOV-D02',
      capabilityFamily: 'GST taxpayer information',
      operationScope: 'NIC taxpayer/transporter lookup',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_DOCUMENTED',
      authProfileRef: 'AUTH-EWB',
      knownSideEffects: 'Read external taxpayer details',
      moneycraftImplementationStatus: 'ADAPTER_REGISTERED_DISABLED_BY_DEFAULT',
      verificationStatus: 'NOT RUN',
      limitations: 'GET /Master/GetGSTINDetails; requires deliberate acceptance before master update',
    },
    {
      draftCapabilityId: 'GOV-D03',
      capabilityFamily: 'GST taxpayer information',
      operationScope: 'IRP taxpayer lookup and synchronisation',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_DOCUMENTED',
      authProfileRef: 'AUTH-IRP',
      knownSideEffects: 'Lookup; provider sync may update provider cache',
      moneycraftImplementationStatus: 'ADAPTER_REGISTERED_DISABLED_BY_DEFAULT',
      verificationStatus: 'NOT RUN',
      limitations: 'Selected IRP onboarding and taxpayer authorisation required',
    },
    {
      draftCapabilityId: 'GOV-D04',
      capabilityFamily: 'GST master reference data',
      operationScope: 'HSN/SAC lookup and master validation',
      channelClass: 'CANDIDATE_API_OR_MASTER_DATA',
      officialEvidenceStatus: 'SUPPLIER_BEHAVIOUR_DOCUMENTED_SCHEMA_PENDING',
      authProfileRef: 'AUTH-PROVIDER-PENDING',
      knownSideEffects: 'Accepting changes modifies MoneyCraft master data',
      moneycraftImplementationStatus: 'LOCAL_RULE_PACK_IMPLEMENTED',
      verificationStatus: 'NOT RUN',
      limitations: 'Current government endpoint schema pending admitted GSP catalogue',
    },
    {
      draftCapabilityId: 'GOV-D05',
      capabilityFamily: 'GST portal connection',
      operationScope: 'Login consent refresh logout revocation',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_ECOSYSTEM_AND_CONSENT_DOCUMENTED_SCHEMA_PENDING',
      authProfileRef: 'AUTH-GST',
      knownSideEffects: 'Changes consent/session; may send OTP',
      moneycraftImplementationStatus: 'ADAPTER_REGISTERED_DISABLED_BY_DEFAULT',
      verificationStatus: 'NOT RUN',
      limitations: 'Requires contracted GSP/ASP route plus Manage API Access taxpayer consent',
    },
    {
      draftCapabilityId: 'GOV-D06',
      capabilityFamily: 'GST return exchange',
      operationScope: 'GSTR-1 invoice and summary upload',
      channelClass: 'CANDIDATE_API',
      officialEvidenceStatus: 'SUPPLIER_BEHAVIOUR_DOCUMENTED_SCHEMA_PENDING',
      authProfileRef: 'AUTH-GST',
      knownSideEffects: 'Mutates remote return working data',
      moneycraftImplementationStatus: 'LOCAL_JSON_EXPORT_AND_GSP_SPI',
      verificationStatus: 'NOT RUN',
      limitations: 'Upload is not statutory filing',
    },
    {
      draftCapabilityId: 'GOV-D07',
      capabilityFamily: 'GST return filing',
      operationScope: 'GSTR-1 filing and acknowledgement',
      channelClass: 'CANDIDATE_API',
      officialEvidenceStatus: 'SUPPLIER_BEHAVIOUR_DOCUMENTED_SCHEMA_PENDING',
      authProfileRef: 'AUTH-GST',
      knownSideEffects: 'Statutory filing',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Exact EVC/DSC filing verification contract remains provider-specific',
    },
    {
      draftCapabilityId: 'GOV-D08',
      capabilityFamily: 'GST reconciliation',
      operationScope: 'GSTR-1 and GSTR-2B downloads',
      channelClass: 'CANDIDATE_API_AND_FILE',
      officialEvidenceStatus: 'SUPPLIER_BEHAVIOUR_DOCUMENTED_SCHEMA_PENDING',
      authProfileRef: 'AUTH-GST',
      knownSideEffects: 'External data read; local reconciliation version',
      moneycraftImplementationStatus: 'FILE_IMPORT_AND_GSP_SPI',
      verificationStatus: 'NOT RUN',
      limitations: 'Never overwrite prior downloaded evidence silently',
    },
    {
      draftCapabilityId: 'GOV-D09',
      capabilityFamily: 'GST summary return',
      operationScope: 'GSTR-3B download/upload and portal filing hand-off',
      channelClass: 'CANDIDATE_API_PLUS_PORTAL',
      officialEvidenceStatus: 'SUPPLIER_BEHAVIOUR_DOCUMENTED_SCHEMA_PENDING',
      authProfileRef: 'AUTH-GST',
      knownSideEffects: 'Remote draft update; separate portal filing/payment',
      moneycraftImplementationStatus: 'LOCAL_COMPUTE_JSON_AND_GSP_SPI',
      verificationStatus: 'NOT RUN',
      limitations: 'Uploading values is distinct from completing filing on GST portal',
    },
    {
      draftCapabilityId: 'GOV-D10',
      capabilityFamily: 'GST invoice management',
      operationScope: 'IMS download/action upload/reset/recompute',
      channelClass: 'CANDIDATE_API',
      officialEvidenceStatus: 'SUPPLIER_BEHAVIOUR_DOCUMENTED_SCHEMA_PENDING',
      authProfileRef: 'AUTH-GST',
      knownSideEffects: 'Changes remote invoice action and downstream GSTR-2B/3B',
      moneycraftImplementationStatus: 'IMS_WORKBENCH_AND_CONNECTOR_SPI',
      verificationStatus: 'NOT RUN',
      limitations: 'Requires current provider IMS catalogue and taxpayer consent',
    },
    {
      draftCapabilityId: 'GOV-D11',
      capabilityFamily: 'Additional GST discovery',
      operationScope: 'Other returns GST TDS/TCS ledgers payments notices',
      channelClass: 'DISCOVERY_TARGET_ONLY',
      officialEvidenceStatus: 'NO_OPERATION_LEVEL_API_VERIFICATION',
      authProfileRef: 'AUTH-PROVIDER-PENDING',
      knownSideEffects: 'Unknown until enumerated',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Inspection bucket only; not a claim of supported statutory scope',
    },
    {
      draftCapabilityId: 'GOV-D12',
      capabilityFamily: 'E-invoice authentication',
      operationScope: 'IRP core session',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_DOCUMENTED',
      authProfileRef: 'AUTH-IRP',
      knownSideEffects: 'Creates provider session',
      moneycraftImplementationStatus: 'ADAPTER_REGISTERED_DISABLED_BY_DEFAULT',
      verificationStatus: 'NOT RUN',
      limitations: 'IRP sandbox then production admission required',
    },
    {
      draftCapabilityId: 'GOV-D13',
      capabilityFamily: 'E-invoice registration',
      operationScope: 'Generate IRN',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_DOCUMENTED',
      authProfileRef: 'AUTH-IRP',
      knownSideEffects: 'Registers fiscal document',
      moneycraftImplementationStatus: 'OFFLINE_JSON_AND_CONNECTOR_SPI',
      verificationStatus: 'NOT RUN',
      limitations: 'Reconcile uncertain timeout response via retrieval before retry',
    },
    {
      draftCapabilityId: 'GOV-D14',
      capabilityFamily: 'E-invoice lifecycle',
      operationScope: 'Cancel IRN',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_DOCUMENTED',
      authProfileRef: 'AUTH-IRP',
      knownSideEffects: 'Remote statutory cancellation',
      moneycraftImplementationStatus: 'CONNECTOR_SPI_DEFINED',
      verificationStatus: 'NOT RUN',
      limitations: 'Local rollback cannot reverse accepted remote IRN cancellation',
    },
    {
      draftCapabilityId: 'GOV-D15',
      capabilityFamily: 'E-invoice retrieval',
      operationScope: 'Retrieve by IRN or document details',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_DOCUMENTED',
      authProfileRef: 'AUTH-IRP',
      knownSideEffects: 'Read external registration evidence',
      moneycraftImplementationStatus: 'CONNECTOR_SPI_DEFINED',
      verificationStatus: 'NOT RUN',
      limitations: 'Primary reconciliation path after uncertain IRN generation',
    },
    {
      draftCapabilityId: 'GOV-D16',
      capabilityFamily: 'E-invoice/EWB linkage',
      operationScope: 'Generate/cancel/retrieve EWB through IRN',
      channelClass: 'AUTHENTICATED_API_CONDITIONAL',
      officialEvidenceStatus: 'OFFICIAL_CATALOGUE_WITH_AVAILABILITY_QUALIFIER',
      authProfileRef: 'AUTH-IRP',
      knownSideEffects: 'Read or mutate EWB depending operation',
      moneycraftImplementationStatus: 'CONNECTOR_SPI_DEFINED',
      verificationStatus: 'NOT RUN',
      limitations: 'Confirm selected IRP EWB availability before enabling',
    },
    {
      draftCapabilityId: 'GOV-D17',
      capabilityFamily: 'IRP health',
      operationScope: 'Core service health',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_DOCUMENTED',
      authProfileRef: 'AUTH-IRP',
      knownSideEffects: 'No intended statutory mutation',
      moneycraftImplementationStatus: 'SAFE_VALIDATION_PROBE',
      verificationStatus: 'NOT RUN',
      limitations: 'Permitted safe connection validation target',
    },
    {
      draftCapabilityId: 'GOV-D18',
      capabilityFamily: 'EWB authentication',
      operationScope: 'Authenticate v1.03',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_DOCUMENTED',
      authProfileRef: 'AUTH-EWB',
      knownSideEffects: 'Creates token/session encryption context',
      moneycraftImplementationStatus: 'SAFE_VALIDATION_PROBE',
      verificationStatus: 'NOT RUN',
      limitations: 'POST /auth/ v1.03 cryptographic contract required',
    },
    {
      draftCapabilityId: 'GOV-D19',
      capabilityFamily: 'EWB generation',
      operationScope: 'Generate EWB or supported Part A workflow',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_CATALOGUE_DOCUMENTED',
      authProfileRef: 'AUTH-EWB',
      knownSideEffects: 'Creates remote transport document',
      moneycraftImplementationStatus: 'OFFLINE_JSON_AND_CONNECTOR_SPI',
      verificationStatus: 'NOT RUN',
      limitations: 'Apply active NIC schema and release-note profile',
    },
    {
      draftCapabilityId: 'GOV-D20',
      capabilityFamily: 'EWB transport updates',
      operationScope: 'Part B vehicle and transporter updates',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_CATALOGUE_DOCUMENTED',
      authProfileRef: 'AUTH-EWB',
      knownSideEffects: 'Updates remote transport details',
      moneycraftImplementationStatus: 'CONNECTOR_SPI_DEFINED',
      verificationStatus: 'NOT RUN',
      limitations: 'Separate permissions and action audit records',
    },
    {
      draftCapabilityId: 'GOV-D21',
      capabilityFamily: 'EWB consolidation',
      operationScope: 'Consolidate/regenerate consolidated EWB',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_CATALOGUE_DOCUMENTED',
      authProfileRef: 'AUTH-EWB',
      knownSideEffects: 'Creates/updates consolidation',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Catalogue capability only until provider schema bound',
    },
    {
      draftCapabilityId: 'GOV-D22',
      capabilityFamily: 'EWB lifecycle',
      operationScope: 'Cancel or reject',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_CATALOGUE_DOCUMENTED',
      authProfileRef: 'AUTH-EWB',
      knownSideEffects: 'Consequential remote mutation',
      moneycraftImplementationStatus: 'CONNECTOR_SPI_DEFINED',
      verificationStatus: 'NOT RUN',
      limitations: 'Cancellation and rejection are distinct time-bounded operations',
    },
    {
      draftCapabilityId: 'GOV-D23',
      capabilityFamily: 'EWB lifecycle',
      operationScope: 'Extend validity',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_CATALOGUE_DOCUMENTED',
      authProfileRef: 'AUTH-EWB',
      knownSideEffects: 'Changes remote validity',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Governed by current NIC eligibility window rules',
    },
    {
      draftCapabilityId: 'GOV-D24',
      capabilityFamily: 'EWB retrieval',
      operationScope: 'Details assigned/other-party/consolidated retrieval',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_CATALOGUE_DOCUMENTED',
      authProfileRef: 'AUTH-EWB',
      knownSideEffects: 'Reads transport evidence',
      moneycraftImplementationStatus: 'CONNECTOR_SPI_DEFINED',
      verificationStatus: 'NOT RUN',
      limitations: 'Taxpayer/transporter scope required',
    },
    {
      draftCapabilityId: 'GOV-D25',
      capabilityFamily: 'EWB multi-vehicle',
      operationScope: 'Initialise/add/update multi-vehicle movement',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_API_CATALOGUE_DOCUMENTED',
      authProfileRef: 'AUTH-EWB',
      knownSideEffects: 'Changes transport movement',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Capability catalogue evidence only',
    },
    {
      draftCapabilityId: 'GOV-D26',
      capabilityFamily: 'Income Tax ERI connection',
      operationScope: 'Type-2 ERI login/logout',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_PUBLISHED_SPEC_CURRENT_ADMISSION_PENDING',
      authProfileRef: 'AUTH-ERI2',
      knownSideEffects: 'Creates/ends ERI session',
      moneycraftImplementationStatus: 'ADAPTER_PROFILE_CANDIDATE',
      verificationStatus: 'NOT RUN',
      limitations: 'Public spec dated 2021; confirm admitted ITD gateway protocol',
    },
    {
      draftCapabilityId: 'GOV-D27',
      capabilityFamily: 'Income Tax client authority',
      operationScope: 'Add/register client with consent',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_PUBLISHED_API_DOCUMENTED',
      authProfileRef: 'AUTH-ERI2',
      knownSideEffects: 'Changes authorised client relationship',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Taxpayer OTP consent mandatory; Super Admin cannot substitute',
    },
    {
      draftCapabilityId: 'GOV-D28',
      capabilityFamily: 'Income Tax prefill',
      operationScope: 'Fetch client prefill',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_PUBLISHED_API_DOCUMENTED',
      authProfileRef: 'AUTH-ERI2',
      knownSideEffects: 'Reads sensitive taxpayer data',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Consented client relationship required',
    },
    {
      draftCapabilityId: 'GOV-D29',
      capabilityFamily: 'Income Tax returns',
      operationScope: 'Validate and submit ITR',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_PUBLISHED_API_DOCUMENTED',
      authProfileRef: 'AUTH-ERI2',
      knownSideEffects: 'Remote validation and statutory submission',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Preparation, validation, submission, verification are separate states',
    },
    {
      draftCapabilityId: 'GOV-D30',
      capabilityFamily: 'Income Tax verification',
      operationScope: 'e-Verify return',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_PUBLISHED_API_DOCUMENTED',
      authProfileRef: 'AUTH-ERI2',
      knownSideEffects: 'Consequential taxpayer verification',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Verification method depends on taxpayer/ERI class',
    },
    {
      draftCapabilityId: 'GOV-D31',
      capabilityFamily: 'Income Tax evidence',
      operationScope: 'Download acknowledgement',
      channelClass: 'AUTHENTICATED_API',
      officialEvidenceStatus: 'OFFICIAL_PUBLISHED_API_DOCUMENTED',
      authProfileRef: 'AUTH-ERI2',
      knownSideEffects: 'Reads filing acknowledgement',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Receipt must match taxpayer/form/year/submission',
    },
    {
      draftCapabilityId: 'GOV-D32',
      capabilityFamily: 'PAN/TAN validation',
      operationScope: 'Approved external-agency webservice',
      channelClass: 'RESTRICTED_API',
      officialEvidenceStatus: 'OFFICIAL_API_AND_APPROVAL_GATE_DOCUMENTED',
      authProfileRef: 'AUTH-EXTERNAL-AGENCY',
      knownSideEffects: 'Read identity verification result',
      moneycraftImplementationStatus: 'NOT ESTABLISHED',
      verificationStatus: 'NOT RUN',
      limitations: 'Separate ITD external-agency approval gate',
    },
    {
      draftCapabilityId: 'GOV-D33',
      capabilityFamily: 'PAN validation',
      operationScope: 'Authorised-entity Protean API service',
      channelClass: 'RESTRICTED_API',
      officialEvidenceStatus: 'OFFICIAL_SERVICE_DOCUMENTED_TECHNICAL_PROFILE_PENDING',
      authProfileRef: 'AUTH-PAN-PROVIDER',
      knownSideEffects: 'Read identity matching/status result',
      moneycraftImplementationStatus: 'ADAPTER_PROFILE_CANDIDATE',
      verificationStatus: 'NOT RUN',
      limitations: 'Eligible entity enrolment required; public search is not an API',
    },
    {
      draftCapabilityId: 'GOV-D34',
      capabilityFamily: 'Income Tax TDS/TCS preparation',
      operationScope: 'Regular/correction statement files',
      channelClass: 'FILE_FORMAT',
      officialEvidenceStatus: 'OFFICIAL_FILE_WORKFLOW_DOCUMENTED',
      authProfileRef: 'AUTH-NONE-LOCAL',
      knownSideEffects: 'Creates local filing artefact',
      moneycraftImplementationStatus: 'IMPLEMENTED_LOCAL_26Q_EXPORT',
      verificationStatus: 'VERIFIED_LOCAL_FILE',
      limitations: 'Local .txt file artefact, not a remote REST filing API',
    },
    {
      draftCapabilityId: 'GOV-D35',
      capabilityFamily: 'Income Tax TDS/TCS validation',
      operationScope: 'FVU validation and challan-input association',
      channelClass: 'LOCAL_UTILITY_AND_FILE',
      officialEvidenceStatus: 'OFFICIAL_UTILITY_WORKFLOW_DOCUMENTED',
      authProfileRef: 'AUTH-NONE-LOCAL',
      knownSideEffects: 'Local validation result',
      moneycraftImplementationStatus: 'IMPLEMENTED_PRE_FVU_CHECKS',
      verificationStatus: 'VERIFIED_LOCAL_FILE',
      limitations: 'Format validation via official Protean FVU is not portal filing acceptance',
    },
    {
      draftCapabilityId: 'GOV-D36',
      capabilityFamily: 'Income Tax TDS/TCS filing',
      operationScope: 'e-filing upload verification and processing status',
      channelClass: 'HUMAN_PORTAL_OR_ADMITTED_ROUTE_PENDING',
      officialEvidenceStatus: 'OFFICIAL_PORTAL_WORKFLOW_DOCUMENTED_NO_GENERIC_API_ESTABLISHED',
      authProfileRef: 'AUTH-TAN-PORTAL',
      knownSideEffects: 'Statutory upload/verification; remote processing',
      moneycraftImplementationStatus: 'HUMAN_PORTAL_HANDOFF',
      verificationStatus: 'NOT RUN',
      limitations: 'Portal TAN upload workflow does not establish generic public REST API',
    },
    {
      draftCapabilityId: 'GOV-D37',
      capabilityFamily: 'TRACES correction evidence',
      operationScope: 'Request/download consolidated file',
      channelClass: 'HUMAN_PORTAL_AND_FILE',
      officialEvidenceStatus: 'OFFICIAL_PORTAL_WORKFLOW_DOCUMENTED_NO_PUBLIC_API_ESTABLISHED',
      authProfileRef: 'AUTH-TRACES-PORTAL',
      knownSideEffects: 'Requests protected download',
      moneycraftImplementationStatus: 'HUMAN_PORTAL_HANDOFF',
      verificationStatus: 'NOT RUN',
      limitations: 'No public TRACES REST API established',
    },
    {
      draftCapabilityId: 'GOV-D38',
      capabilityFamily: 'TRACES diagnostics',
      operationScope: 'Request/download justification report',
      channelClass: 'HUMAN_PORTAL_AND_FILE',
      officialEvidenceStatus: 'OFFICIAL_PORTAL_WORKFLOW_DOCUMENTED_NO_PUBLIC_API_ESTABLISHED',
      authProfileRef: 'AUTH-TRACES-PORTAL',
      knownSideEffects: 'Requests protected report',
      moneycraftImplementationStatus: 'HUMAN_PORTAL_HANDOFF',
      verificationStatus: 'NOT RUN',
      limitations: 'Report utility distinct from remote API',
    },
    {
      draftCapabilityId: 'GOV-D39',
      capabilityFamily: 'TRACES account evidence',
      operationScope: 'Certificate downloads challan/credit/status screens',
      channelClass: 'HUMAN_PORTAL',
      officialEvidenceStatus: 'OFFICIAL_UI_CAPABILITIES_DOCUMENTED_NO_PUBLIC_API_ESTABLISHED',
      authProfileRef: 'AUTH-TRACES-PORTAL',
      knownSideEffects: 'Reads protected account evidence',
      moneycraftImplementationStatus: 'HUMAN_PORTAL_HANDOFF',
      verificationStatus: 'NOT RUN',
      limitations: 'Authorised TRACES portal login required',
    },
    {
      draftCapabilityId: 'BANK-CB01',
      capabilityFamily: 'Connected Banking (ICICI_CIB)',
      operationScope: 'Corporate Balance, MT940/JSON Statement Pull & Composite Payouts',
      channelClass: 'CONNECTED_BANKING_API',
      officialEvidenceStatus: 'OFFICIAL_BANK_PORTAL_DOCUMENTED',
      authProfileRef: 'AUTH-ICICI-CIB',
      knownSideEffects: 'Balance/statement read; payouts mutate bank funds when enabled',
      moneycraftImplementationStatus: 'ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (ICICI_CIB)',
      verificationStatus: 'NOT RUN',
      limitations: 'Requires ICICI AGGRID partnership, RSA-2048 cert exchange & static IP whitelist',
    },
    {
      draftCapabilityId: 'BANK-CB02',
      capabilityFamily: 'Connected Banking (HDFC_CBX)',
      operationScope: 'Corporate Balance, Statement Pull & CBX/ENet Payouts',
      channelClass: 'CONNECTED_BANKING_API',
      officialEvidenceStatus: 'OFFICIAL_BANK_PORTAL_DOCUMENTED',
      authProfileRef: 'AUTH-HDFC-CBX',
      knownSideEffects: 'Balance/statement read; payouts mutate bank funds when enabled',
      moneycraftImplementationStatus: 'ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (HDFC_CBX)',
      verificationStatus: 'NOT RUN',
      limitations: 'Requires HDFC CMS onboarding, mTLS X.509 cert, JWS/JWE keys & VAPT sign-off',
    },
    {
      draftCapabilityId: 'BANK-CB03',
      capabilityFamily: 'Connected Banking (AXIS_CORP)',
      operationScope: 'Corporate Neo TransferPayment, Status & Statement Inquiry',
      channelClass: 'CONNECTED_BANKING_API',
      officialEvidenceStatus: 'OFFICIAL_BANK_PORTAL_DOCUMENTED',
      authProfileRef: 'AUTH-AXIS-CORP',
      knownSideEffects: 'Balance/statement read; payouts mutate bank funds when enabled',
      moneycraftImplementationStatus: 'ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (AXIS_CORP)',
      verificationStatus: 'NOT RUN',
      limitations: 'Requires Axis IBM Client ID/Secret, mTLS cert & static IP whitelist',
    },
    {
      draftCapabilityId: 'BANK-CB04',
      capabilityFamily: 'Connected Banking (SBI_CMP)',
      operationScope: 'YONO Business / CMP Balance, Statement & Bulk Vendor Payments',
      channelClass: 'CONNECTED_BANKING_API',
      officialEvidenceStatus: 'OFFICIAL_BANK_PORTAL_DOCUMENTED',
      authProfileRef: 'AUTH-SBI-CMP',
      knownSideEffects: 'Balance/statement read; CMP payouts mutate bank funds when enabled',
      moneycraftImplementationStatus: 'ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (SBI_CMP)',
      verificationStatus: 'NOT RUN',
      limitations: 'Requires SBI Connected Banking / CMP partner onboarding & static IP',
    },
    {
      draftCapabilityId: 'BANK-CB05',
      capabilityFamily: 'Connected Banking (AGGREGATOR_PAYOUT)',
      operationScope: 'Current Account Payouts, Penny-Drop Verification & Webhook BRS',
      channelClass: 'CONNECTED_BANKING_API',
      officialEvidenceStatus: 'OFFICIAL_PROVIDER_API_DOCUMENTED',
      authProfileRef: 'AUTH-AGGREGATOR',
      knownSideEffects: 'Penny-drop/payouts incur provider charges or move funds when invoked',
      moneycraftImplementationStatus: 'ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (AGGREGATOR_PAYOUT)',
      verificationStatus: 'NOT RUN',
      limitations: 'Requires aggregator KYC, Key ID/Secret & IP allowlist',
    },
  ]);

function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

@Component({
  selector: 'mc-government-integrations',
  standalone: true,
  imports: [
    FormsModule,
    CodeBadgeComponent,
    SemanticIconComponent,
    HelpTriggerComponent,
    HelpTooltipDirective,
    HelpPanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-gov-layout" data-testid="government-integrations-screen">
      <div class="mc-gov-header">
        <div>
          <div class="mc-gov-title-row">
            <h1>Government Statutory &amp; Connected Banking Integrations</h1>
            <span class="mc-badge mc-badge-slate">SUPER_ADMIN ONLY</span>
            <mc-help-trigger
              helpId="HELP-110-018"
              controlName="government integration profiles"
            />
          </div>
          <p class="mc-gov-sub">
            Manage disabled-by-default statutory portal profiles (<strong>GOV-D01..GOV-D39</strong>)
            and Corporate Connected Banking adapters (<strong>ICICI_CIB</strong>,
            <strong>HDFC_CBX</strong>, <strong>AXIS_CORP</strong>, <strong>SBI_CMP</strong>,
            <strong>AGGREGATOR_PAYOUT</strong>) with Tink envelope-encrypted write-only secrets and
            5-minute target-bound step-up authentication.
          </p>
        </div>

        @if (isSuperAdmin()) {
          <div class="mc-gov-header-actions">
            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              data-testid="gov-refresh-btn"
              (click)="reloadAll()"
            >
              <mc-icon name="refresh" [size]="16" />
              <span>Refresh</span>
            </button>
            <button
              type="button"
              class="mc-btn mc-btn-primary"
              data-testid="gov-new-profile-btn"
              (click)="openCreateProfileModal()"
            >
              <mc-icon name="save-document" [size]="16" />
              <span>New Integration Profile</span>
            </button>
          </div>
        }
      </div>

      @if (!isSuperAdmin()) {
        <section
          class="mc-card mc-gov-denied-card"
          role="alert"
          data-testid="gov-super-admin-denied"
        >
          <div class="mc-status-banner mc-status-error">
            <mc-icon name="error" [size]="20" />
            <span>
              Restricted to Explicitly Designated Installation Super Admin (ITER110-013)
            </span>
          </div>
          <p class="mc-gov-denied-copy">
            Your current principal does not hold the explicit installation
            <code>SUPER_ADMIN</code> designation. Neither <code>INSTALLATION_OWNER</code>,
            <code>security.admin</code>, nor <code>COMPANY_ADMIN</code> automatically grants access
            to protected government or connected-banking credentials. All server endpoints enforce
            <code>requireSuperAdminPerson()</code> independently.
          </p>
        </section>
      } @else {
        @if (statusBanner(); as okMsg) {
          <div class="mc-status-banner mc-status-confirmed" role="status" data-testid="gov-status-banner">
            <mc-icon name="confirmed" [size]="20" />
            <span>{{ okMsg }}</span>
          </div>
        }

        @if (errorBanner(); as errMsg) {
          <div class="mc-status-banner mc-status-error" role="alert" data-testid="gov-error-banner">
            <mc-icon name="error" [size]="20" />
            <span>{{ errMsg }}</span>
          </div>
        }

        <div class="mc-status-banner mc-status-pending" role="note">
          <mc-icon name="warning" [size]="20" />
          <span>
            <strong>Statutory Evidence Boundary:</strong> Uploading a draft payload or validating a
            connection is <strong>not</strong> a filed statutory return. Stored secrets are write-only
            and can never be revealed, copied, or exported.
          </span>
          <mc-help-trigger helpId="HELP-110-022" controlName="statutory filing status" />
        </div>

        <!-- Tabs: 1. Configured Profiles & Vault | 2. Capability & Adapter Inventory (GOV-D01..39 + Banking) -->
        <div class="mc-gov-tabs" role="tablist" aria-label="Integration Administration Views">
          <button
            type="button"
            role="tab"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'PROFILES'"
            [class.mc-btn-secondary]="activeTab() !== 'PROFILES'"
            [attr.aria-selected]="activeTab() === 'PROFILES'"
            data-testid="gov-tab-profiles"
            (click)="activeTab.set('PROFILES')"
          >
            <span>1. Protected Integration Profiles ({{ profiles().length }})</span>
          </button>
          <button
            type="button"
            role="tab"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'INVENTORY'"
            [class.mc-btn-secondary]="activeTab() !== 'INVENTORY'"
            [attr.aria-selected]="activeTab() === 'INVENTORY'"
            data-testid="gov-tab-inventory"
            (click)="activeTab.set('INVENTORY')"
          >
            <span>
              2. Capability &amp; Connected Banking Inventory ({{ inventoryRows().length }})
            </span>
          </button>
        </div>

        @if (activeTab() === 'PROFILES') {
          <div class="mc-gov-split">
            <!-- Left: Bounded Metadata Profile List -->
            <section class="mc-card mc-gov-card" aria-label="Integration Profiles List">
              <h2>Configured Profiles (Sandbox &amp; Production Isolated)</h2>

              @if (profiles().length === 0) {
                <div class="mc-empty-records" data-testid="gov-profiles-empty">
                  No integration profiles configured. Select "New Integration Profile" to create a
                  disabled draft shell.
                </div>
              } @else {
                <div class="mc-table-scroll-region">
                  <table class="mc-table" aria-label="Government and Banking Integration Profiles">
                    <thead>
                      <tr>
                        <th>Profile / Provider</th>
                        <th>Environment</th>
                        <th>Company / Registration</th>
                        <th>Status</th>
                        <th>Rev</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (prof of profiles(); track prof.id) {
                        <tr
                          class="mc-gov-clickable-row"
                          [class.mc-row-selected]="selectedProfile()?.id === prof.id"
                          [attr.data-testid]="'gov-profile-row-' + prof.id"
                          (click)="selectProfile(prof)"
                        >
                          <td>
                            <strong>{{ prof.profileName }}</strong>
                            <div class="mc-gov-cell-sub mc-code">{{ prof.providerCode }}</div>
                          </td>
                          <td>
                            @if (prof.environment === 'PRODUCTION') {
                              <span class="mc-badge mc-badge-warning">PRODUCTION</span>
                            } @else {
                              <span class="mc-badge mc-badge-slate">SANDBOX</span>
                            }
                          </td>
                          <td>
                            <span class="mc-code">{{ prof.registrationRef }}</span>
                          </td>
                          <td>
                            <span class="mc-badge" [class]="statusBadgeClass(prof.status)">
                              {{ prof.status }}
                            </span>
                          </td>
                          <td class="mc-code">r{{ prof.revision }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </section>

            <!-- Right: Selected Profile Detail, Write-Only Credential Staging, Validation, Activate/Disable & Audit -->
            <section class="mc-card mc-gov-card" aria-label="Selected Profile Operations">
              @if (selectedProfile(); as sel) {
                <div class="mc-gov-detail-head">
                  <div>
                    <h2>{{ sel.profileName }}</h2>
                    <div class="mc-gov-meta-tags">
                      <span class="mc-badge mc-badge-slate mc-code">{{ sel.providerCode }}</span>
                      <span class="mc-badge mc-badge-slate mc-code">{{ sel.endpointProfileId }}</span>
                      @if (sel.environment === 'PRODUCTION') {
                        <span class="mc-badge mc-badge-warning">PRODUCTION NAMESPACE</span>
                      } @else {
                        <span class="mc-badge mc-badge-slate">SANDBOX NAMESPACE</span>
                      }
                      <span class="mc-badge" [class]="statusBadgeClass(sel.status)">
                        {{ sel.status }}
                      </span>
                    </div>
                  </div>

                  <div class="mc-gov-action-cluster">
                    <button
                      type="button"
                      class="mc-btn mc-btn-secondary"
                      data-testid="gov-edit-draft-btn"
                      (click)="openEditProfileModal(sel)"
                    >
                      <span>Edit Draft</span>
                    </button>
                  </div>
                </div>

                <dl class="mc-gov-kv-grid">
                  <div>
                    <dt>Company ID</dt>
                    <dd class="mc-code">{{ sel.companyId }}</dd>
                  </div>
                  <div>
                    <dt>Bound Registration</dt>
                    <dd class="mc-code">{{ sel.registrationRef }}</dd>
                  </div>
                  <div>
                    <dt>Profile Revision / Credential Rev</dt>
                    <dd class="mc-code">r{{ sel.revision }} / cred:{{ sel.credentialRevision }}</dd>
                  </div>
                  <div>
                    <dt>Consent / Onboarding Ref</dt>
                    <dd>{{ sel.consentEvidenceRef || 'Not recorded' }}</dd>
                  </div>
                  <div>
                    <dt>Permitted Operations</dt>
                    <dd class="mc-code">{{ sel.allowedOperationIds.join(', ') || 'None' }}</dd>
                  </div>
                  <div>
                    <dt>Certificate SHA-256</dt>
                    <dd class="mc-code">{{ sel.certificateFingerprintSha256 || 'None' }}</dd>
                  </div>
                </dl>

                <hr class="mc-gov-divider" />

                <!-- Write-Only Credential Staging -->
                <div class="mc-gov-section-title-row">
                  <h3>Write-Only Protected Credential Slots</h3>
                  <mc-help-trigger
                    helpId="HELP-110-019"
                    controlName="replacing protected credentials"
                  />
                </div>
                <p class="mc-gov-note">
                  Existing secrets are envelope-encrypted server-side and can never be viewed, copied,
                  or exported. Enter replacement values below to stage a new credential version
                  (requires fresh 5-minute step-up).
                </p>

                <form
                  class="mc-gov-slot-form"
                  autocomplete="off"
                  (ngSubmit)="stageCredentialVersion(sel)"
                >
                  @for (slotSchema of providerSlotsFor(sel.providerCode); track slotSchema.slotKey) {
                    <div class="mc-gov-slot-row">
                      <div class="mc-gov-slot-label-line">
                        <label class="mc-field-label" [attr.for]="'slot-' + slotSchema.slotKey">
                          {{ slotSchema.label }}
                          @if (slotSchema.required) {
                            <span>(Required)</span>
                          }
                        </label>
                        <span
                          class="mc-badge"
                          [class]="slotStatusBadgeClass(slotStatusFor(sel, slotSchema.slotKey))"
                          [attr.data-testid]="'slot-status-' + slotSchema.slotKey"
                        >
                          {{ slotStatusLabel(slotStatusFor(sel, slotSchema.slotKey)) }}
                        </span>
                      </div>
                      <input
                        [attr.id]="'slot-' + slotSchema.slotKey"
                        [name]="'slot_' + slotSchema.slotKey"
                        [ngModelOptions]="{ standalone: true }"
                        [attr.type]="slotSchema.secretKind === 'SECRET_VALUE' ? 'password' : 'text'"
                        autocomplete="new-password"
                        data-lpignore="true"
                        class="mc-input"
                        [attr.maxlength]="slotSchema.maxLength"
                        [attr.data-testid]="'gov-slot-input-' + slotSchema.slotKey"
                        [placeholder]="
                          slotSchema.secretKind === 'SERVER_SECRET_REF'
                            ? '/run/secrets/provider_key_ref (write-only reference)'
                            : 'Enter new write-only secret to replace…'
                        "
                        [ngModel]="stagedSlotInputs()[slotSchema.slotKey] ?? ''"
                        (ngModelChange)="updateStagedSlotInput(slotSchema.slotKey, $event)"
                      />
                      <div class="mc-gov-slot-hint">{{ slotSchema.hint }}</div>
                    </div>
                  }

                  <div class="mc-gov-btn-row">
                    <button
                      type="submit"
                      class="mc-btn mc-btn-secondary"
                      data-testid="gov-stage-credentials-btn"
                      [mcHelpTooltip]="'HELP-110-019'"
                    >
                      <mc-icon name="save-document" [size]="16" />
                      <span>Stage Replacement Credentials</span>
                    </button>
                    <button
                      type="button"
                      class="mc-btn mc-btn-secondary"
                      (click)="clearTransientCredentialInputs()"
                    >
                      <span>Clear Unsubmitted Inputs</span>
                    </button>
                  </div>
                </form>

                <hr class="mc-gov-divider" />

                <!-- Safe Validation, Activate/Apply, Disable -->
                <div class="mc-gov-section-title-row">
                  <h3>Safe Connection Validation, Activation &amp; Disable</h3>
                  <div class="mc-gov-inline-helps">
                    <mc-help-trigger helpId="HELP-110-020" controlName="validate connection" />
                    <mc-help-trigger helpId="HELP-110-021" controlName="apply integration" />
                  </div>
                </div>

                @if (providerSpecFor(sel.providerCode); as pSpec) {
                  <div class="mc-gov-probe-box" data-testid="gov-safe-probe-disclosure">
                    <div><strong>Declared Safe Probe:</strong> {{ pSpec.safeValidationProbeSummary }}</div>
                    <div class="mc-gov-slot-hint">{{ pSpec.probeSideEffectNote }}</div>
                  </div>
                }

                @if (sel.lastValidation; as val) {
                  <div class="mc-gov-validation-card" data-testid="gov-validation-status-card">
                    <div>
                      <strong>Validation ({{ val.validationId }}):</strong>
                      <span class="mc-badge mc-badge-slate">{{ val.status }}</span>
                      <span>(Revision r{{ val.testedRevision }})</span>
                    </div>
                    <div>{{ val.summaryMessage }}</div>
                  </div>
                }

                <div class="mc-gov-lifecycle-btns">
                  <button
                    type="button"
                    class="mc-btn mc-btn-secondary"
                    data-testid="gov-validate-btn"
                    [mcHelpTooltip]="'HELP-110-020'"
                    (click)="startSafeValidation(sel)"
                  >
                    <mc-icon name="refresh" [size]="16" />
                    <span>Validate connection</span>
                  </button>

                  @if (sel.lastValidation; as lastVal) {
                    <button
                      type="button"
                      class="mc-btn mc-btn-secondary"
                      data-testid="gov-poll-validation-btn"
                      (click)="refreshValidationOutcome(sel.id, lastVal.validationId)"
                    >
                      <span>Check probe status</span>
                    </button>
                  }

                  <button
                    type="button"
                    class="mc-btn mc-btn-primary"
                    data-testid="gov-activate-btn"
                    [disabled]="!canActivateProfile(sel)"
                    [mcHelpTooltip]="'HELP-110-021'"
                    (click)="activateValidatedProfile(sel)"
                  >
                    <mc-icon name="approve-shield" [size]="16" />
                    <span>Apply / Activate</span>
                  </button>

                  <button
                    type="button"
                    class="mc-btn mc-btn-danger"
                    data-testid="gov-open-disable-btn"
                    [disabled]="sel.status === 'DISABLED'"
                    (click)="openDisableModal(sel)"
                  >
                    <span>Disable Profile</span>
                  </button>
                </div>

                <hr class="mc-gov-divider" />

                <!-- Secret-Free Audit Trail -->
                <div class="mc-gov-section-title-row">
                  <h3>Secret-Free Administration Audit History</h3>
                  <button
                    type="button"
                    class="mc-btn mc-btn-secondary"
                    data-testid="gov-refresh-audit-btn"
                    (click)="loadProfileAudit(sel.id)"
                  >
                    <mc-icon name="refresh" [size]="16" />
                    <span>Reload Audit</span>
                  </button>
                </div>

                @if (auditEvents().length === 0) {
                  <div class="mc-empty-records">No audit events recorded for this profile yet.</div>
                } @else {
                  <div class="mc-table-scroll-region">
                    <table class="mc-table" aria-label="Profile Administration Audit History">
                      <thead>
                        <tr>
                          <th>Timestamp</th>
                          <th>Actor</th>
                          <th>Action</th>
                          <th>Rev</th>
                          <th>Outcome</th>
                          <th>Correlation ID</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (ev of auditEvents(); track ev.eventId) {
                          <tr data-testid="gov-audit-row">
                            <td class="mc-code">{{ ev.timestamp }}</td>
                            <td>{{ ev.actor }}</td>
                            <td class="mc-code">{{ ev.action }}</td>
                            <td class="mc-code">r{{ ev.revision }}</td>
                            <td>{{ ev.result }}</td>
                            <td>
                              @if (ev.correlationId) {
                                <mc-code-badge [code]="ev.correlationId" />
                              } @else {
                                <span>—</span>
                              }
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }
              } @else {
                <div class="mc-empty-records">
                  Select an integration profile on the left or create a new draft profile to stage
                  write-only credentials, run a safe connection probe, or review audit history.
                </div>
              }
            </section>
          </div>
        } @else {
          <!-- Tab 2: Reconciled GOV-D01..39 + Connected Banking Capability Inventory -->
          <section
            class="mc-card mc-gov-card"
            aria-label="Government and Connected Banking Capability Inventory"
            data-testid="gov-inventory-section"
          >
            <div class="mc-gov-inventory-toolbar">
              <div class="mc-gov-filter-field">
                <label class="mc-field-label" for="gov-inv-search">
                  Filter GOV-D01..39 &amp; Connected Banking Catalogue
                </label>
                <input
                  id="gov-inv-search"
                  type="search"
                  class="mc-input"
                  data-testid="gov-inventory-search"
                  placeholder="Search GOV-D13, IRP6, EWB, 26Q, ICICI_CIB, HDFC_CBX…"
                  [ngModel]="inventoryFilterQuery()"
                  (ngModelChange)="inventoryFilterQuery.set($event)"
                />
              </div>

              <div class="mc-gov-filter-field">
                <label class="mc-field-label" for="gov-inv-channel">Channel Class</label>
                <select
                  id="gov-inv-channel"
                  class="mc-select"
                  data-testid="gov-inventory-channel-filter"
                  [ngModel]="inventoryChannelFilter()"
                  (ngModelChange)="inventoryChannelFilter.set($event)"
                >
                  <option value="ALL">All Channels ({{ inventoryRows().length }})</option>
                  <option value="AUTHENTICATED_API">Authenticated Government API</option>
                  <option value="CONNECTED_BANKING_API">Corporate Connected Banking API</option>
                  <option value="CANDIDATE_API">Candidate / GSP Schema Pending</option>
                  <option value="FILE_OR_UTILITY">Local File / Protean FVU Utility</option>
                  <option value="HUMAN_PORTAL">Human Portal / No Public REST API</option>
                </select>
              </div>
            </div>

            <div class="mc-table-scroll-region">
              <table class="mc-table" aria-label="Capability Inventory Worksheet">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Capability Family &amp; Scope</th>
                    <th>Channel</th>
                    <th>Auth Profile</th>
                    <th>MoneyCraft Status</th>
                    <th>Verification</th>
                    <th>Documented Limits &amp; Side Effects</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of filteredInventoryRows(); track row.draftCapabilityId) {
                    <tr [attr.data-testid]="'gov-inv-row-' + row.draftCapabilityId">
                      <td class="mc-code">{{ row.draftCapabilityId }}</td>
                      <td>
                        <strong>{{ row.capabilityFamily }}</strong>
                        <div class="mc-gov-cell-sub">{{ row.operationScope }}</div>
                      </td>
                      <td class="mc-code">{{ row.channelClass }}</td>
                      <td class="mc-code">{{ row.authProfileRef }}</td>
                      <td>
                        <span class="mc-badge mc-badge-slate">{{ row.moneycraftImplementationStatus }}</span>
                      </td>
                      <td class="mc-code">{{ row.verificationStatus }}</td>
                      <td class="mc-gov-cell-sub">
                        {{ row.limitations }} ({{ row.knownSideEffects }})
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }

        <!-- Create / Edit Draft Profile Modal (KEY-001 Badges [S] Save Draft / [C] Cancel) -->
        @if (profileModalOpen()) {
          <div
            class="mc-modal-backdrop"
            role="dialog"
            aria-modal="true"
            aria-labelledby="gov-profile-modal-title"
            data-testid="gov-profile-modal"
          >
            <div class="mc-modal-dialog">
              <div class="mc-modal-header">
                <span id="gov-profile-modal-title">
                  {{ editingProfileId() ? 'Edit Integration Draft Metadata' : 'Create Integration Draft Profile' }}
                </span>
                <span class="mc-badge mc-badge-slate">Draft Only — Never Auto-Activates</span>
              </div>

              <div class="mc-modal-body mc-gov-modal-stack">
                <div class="mc-two-col">
                  <div>
                    <label class="mc-field-label" for="gov-prof-name">Profile Display Label</label>
                    <input
                      id="gov-prof-name"
                      type="text"
                      class="mc-input"
                      data-testid="gov-draft-name-input"
                      [(ngModel)]="draftProfileName"
                      maxlength="120"
                      required
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="gov-prof-env">Isolated Environment</label>
                    <select
                      id="gov-prof-env"
                      class="mc-select"
                      data-testid="gov-draft-env-select"
                      [(ngModel)]="draftEnvironment"
                    >
                      <option value="SANDBOX">SANDBOX (Test / UAT Namespace)</option>
                      <option value="PRODUCTION">PRODUCTION (Live Statutory / Bank Namespace)</option>
                    </select>
                  </div>
                </div>

                <div class="mc-two-col">
                  <div>
                    <label class="mc-field-label" for="gov-prof-provider">
                      Admitted Provider &amp; Adapter
                    </label>
                    <select
                      id="gov-prof-provider"
                      class="mc-select"
                      data-testid="gov-draft-provider-select"
                      [ngModel]="draftProviderCode"
                      (ngModelChange)="onDraftProviderChange($event)"
                    >
                      @for (p of admittedProviders; track p.providerCode) {
                        <option [value]="p.providerCode">
                          {{ p.providerCode }} — {{ p.displayName }}
                        </option>
                      }
                    </select>
                  </div>
                  <div>
                    <label class="mc-field-label" for="gov-prof-endpoint">
                      Closed Endpoint Profile ID
                    </label>
                    <input
                      id="gov-prof-endpoint"
                      type="text"
                      class="mc-input mc-code"
                      [value]="resolvedDraftEndpointProfileId()"
                      readonly
                    />
                  </div>
                </div>

                <div class="mc-two-col">
                  <div>
                    <label class="mc-field-label" for="gov-prof-company">Permitted Company</label>
                    <select
                      id="gov-prof-company"
                      class="mc-select"
                      data-testid="gov-draft-company-select"
                      [ngModel]="draftCompanyId"
                      (ngModelChange)="onDraftCompanyChange($event)"
                    >
                      @for (comp of companyContext.openCompanies(); track comp.id) {
                        <option [value]="comp.id">{{ comp.legalName }} ({{ comp.code }})</option>
                      }
                    </select>
                  </div>
                  <div>
                    <label class="mc-field-label" for="gov-prof-reg">
                      Protected Registration Binding (GSTIN / PAN / TAN / Corp ID)
                    </label>
                    @if (companyGstRegistrations().length > 0 && currentDraftProviderSpec().registrationKind === 'GSTIN') {
                      <select
                        id="gov-prof-reg"
                        class="mc-select mc-code"
                        data-testid="gov-draft-reg-select"
                        [(ngModel)]="draftRegistrationRef"
                      >
                        @for (reg of companyGstRegistrations(); track reg.id) {
                          <option [value]="reg.gstin">{{ reg.gstin }} ({{ reg.stateCode }})</option>
                        }
                      </select>
                    } @else {
                      <input
                        id="gov-prof-reg"
                        type="text"
                        class="mc-input mc-code"
                        data-testid="gov-draft-reg-input"
                        [(ngModel)]="draftRegistrationRef"
                        placeholder="e.g. 33AABCM1234F1Z4 or AABCM1234F or CIB-CORP-01"
                        maxlength="64"
                      />
                    }
                  </div>
                </div>

                <div class="mc-two-col">
                  <div>
                    <label class="mc-field-label" for="gov-prof-consent">
                      Onboarding / Taxpayer Consent Evidence Ref
                    </label>
                    <input
                      id="gov-prof-consent"
                      type="text"
                      class="mc-input"
                      data-testid="gov-draft-consent-input"
                      [(ngModel)]="draftConsentEvidenceRef"
                      placeholder="e.g. GSP-AGR-2026-09 / BOARD-RES-14"
                      maxlength="160"
                    />
                  </div>
                  <div>
                    <label class="mc-field-label" for="gov-prof-cert-fp">
                      mTLS / Signing Certificate SHA-256 (Optional)
                    </label>
                    <input
                      id="gov-prof-cert-fp"
                      type="text"
                      class="mc-input mc-code"
                      [(ngModel)]="draftCertFingerprint"
                      placeholder="64-hex public certificate fingerprint"
                      maxlength="64"
                    />
                  </div>
                </div>

                <fieldset class="mc-gov-ops-fieldset">
                  <legend class="mc-field-label">Permitted Closed Operations</legend>
                  <div class="mc-gov-ops-checks">
                    @for (opId of currentDraftProviderSpec().allowedOperations; track opId) {
                      <label class="mc-gov-op-check">
                        <input
                          type="checkbox"
                          [checked]="draftAllowedOps().includes(opId)"
                          (change)="toggleDraftOperation(opId)"
                        />
                        <span class="mc-code">{{ opId }}</span>
                      </label>
                    }
                  </div>
                </fieldset>
              </div>

              <div class="mc-modal-footer">
                <span class="mc-gov-slot-hint">
                  Requires 5-minute Super Admin step-up verification before saving.
                </span>
                <div class="mc-gov-btn-row">
                  <button
                    type="button"
                    class="mc-btn mc-btn-primary"
                    data-testid="gov-save-draft-btn"
                    (click)="submitProfileDraft()"
                  >
                    <mc-icon name="save-document" [size]="16" />
                    <span>Save draft</span>
                    @if (modalActionHint('government.profileEditor', 'government.saveDraft', 'S/s'); as h) {
                      <span class="mc-action-key-badge" aria-hidden="true">[{{ h }}]</span>
                    }
                  </button>
                  <button
                    type="button"
                    class="mc-btn mc-btn-secondary"
                    data-testid="gov-cancel-draft-btn"
                    (click)="closeProfileModal()"
                  >
                    <mc-icon name="close" [size]="16" />
                    <span>Cancel</span>
                    @if (modalActionHint('government.profileEditor', 'editor.close', 'C/c'); as h) {
                      <span class="mc-action-key-badge" aria-hidden="true">[{{ h }}]</span>
                    }
                  </button>
                </div>
              </div>
            </div>
          </div>
        }

        <!-- Disable Profile Confirmation Modal (KEY-001 Badges [D] Disable / [C] Cancel) -->
        @if (disableModalOpen() && selectedProfile(); as disProf) {
          <div
            class="mc-modal-backdrop"
            role="dialog"
            aria-modal="true"
            aria-labelledby="gov-disable-modal-title"
            data-testid="gov-disable-modal"
          >
            <div class="mc-modal-dialog">
              <div class="mc-modal-header">
                <span id="gov-disable-modal-title">
                  Disable Integration Profile — {{ disProf.profileName }}
                </span>
              </div>
              <div class="mc-modal-body mc-gov-modal-stack">
                <p class="mc-gov-note">
                  Disabling immediately blocks new outbound dispatches on this profile. It never cancels
                  an already accepted IRN, EWB, or statutory action; any in-flight uncertain operations
                  retain revision <code>r{{ disProf.revision }}</code> for reconciliation.
                </p>

                <div>
                  <label class="mc-field-label" for="gov-disable-reason-code">Audited Reason Code</label>
                  <select
                    id="gov-disable-reason-code"
                    class="mc-select"
                    data-testid="gov-disable-reason-select"
                    [(ngModel)]="disableReasonCode"
                  >
                    <option value="ROTATION_MAINTENANCE">ROTATION_MAINTENANCE</option>
                    <option value="CONSENT_REVOKED">CONSENT_REVOKED</option>
                    <option value="SUSPECTED_COMPROMISE">SUSPECTED_COMPROMISE</option>
                    <option value="PROVIDER_OUTAGE">PROVIDER_OUTAGE</option>
                    <option value="DECOMMISSIONED">DECOMMISSIONED</option>
                  </select>
                </div>

                <div>
                  <label class="mc-field-label" for="gov-disable-note">
                    In-Flight Reconciliation &amp; Operator Note
                  </label>
                  <input
                    id="gov-disable-note"
                    type="text"
                    class="mc-input"
                    data-testid="gov-disable-note-input"
                    [(ngModel)]="disableReconciliationNote"
                    placeholder="Document how pending uncertain dispatches (if any) will be reconciled"
                    maxlength="240"
                  />
                </div>
              </div>

              <div class="mc-modal-footer">
                <span>Step-up authentication required.</span>
                <div class="mc-gov-btn-row">
                  <button
                    type="button"
                    class="mc-btn mc-btn-danger"
                    data-testid="gov-confirm-disable-btn"
                    (click)="confirmDisableProfile(disProf)"
                  >
                    <span>Disable</span>
                    @if (modalActionHint('government.disableConfirm', 'government.disable', 'D/d'); as h) {
                      <span class="mc-action-key-badge" aria-hidden="true">[{{ h }}]</span>
                    }
                  </button>
                  <button
                    type="button"
                    class="mc-btn mc-btn-secondary"
                    data-testid="gov-cancel-disable-btn"
                    (click)="closeDisableModal()"
                  >
                    <mc-icon name="close" [size]="16" />
                    <span>Cancel</span>
                    @if (modalActionHint('government.disableConfirm', 'editor.close', 'C/c'); as h) {
                      <span class="mc-action-key-badge" aria-hidden="true">[{{ h }}]</span>
                    }
                  </button>
                </div>
              </div>
            </div>
          </div>
        }

        <!-- 5-Minute Target/Action/Revision-Bound Step-Up Password Modal -->
        @if (stepUpModalOpen()) {
          <div
            class="mc-modal-backdrop"
            role="dialog"
            aria-modal="true"
            aria-labelledby="gov-stepup-modal-title"
            data-testid="gov-stepup-modal"
          >
            <div class="mc-modal-dialog">
              <div class="mc-modal-header">
                <span id="gov-stepup-modal-title">
                  Super Admin Fresh Step-Up Authentication (5-Minute Single-Use Intent)
                </span>
              </div>

              <form class="mc-modal-body mc-gov-modal-stack" (ngSubmit)="submitStepUpPassword()">
                <div class="mc-gov-probe-box">
                  <div>
                    <strong>Bound Action:</strong>
                    <code class="mc-code">{{ pendingStepUpAction() }}</code>
                  </div>
                  <div>
                    <strong>Target Profile / Revision:</strong>
                    <code class="mc-code">
                      {{ pendingStepUpTargetId() ?? 'NEW_PROFILE' }} @ r{{ pendingStepUpRevision() ?? '0' }}
                    </code>
                  </div>
                </div>

                @if (stepUpError(); as sErr) {
                  <div class="mc-status-banner mc-status-error" role="alert">
                    <mc-icon name="error" [size]="20" />
                    <span>{{ sErr }}</span>
                  </div>
                }

                <div>
                  <label class="mc-field-label" for="gov-stepup-password">
                    Confirm Current Super Admin Password
                  </label>
                  <input
                    id="gov-stepup-password"
                    type="password"
                    name="stepUpPassword"
                    autocomplete="current-password"
                    class="mc-input"
                    data-testid="gov-stepup-password-input"
                    [(ngModel)]="stepUpPasswordInput"
                    required
                  />
                </div>

                <div class="mc-modal-footer">
                  <span class="mc-gov-slot-hint">
                    Single-use server proof; never stored in browser storage.
                  </span>
                  <div class="mc-gov-btn-row">
                    <button
                      type="submit"
                      class="mc-btn mc-btn-primary"
                      data-testid="gov-stepup-submit-btn"
                    >
                      <mc-icon name="approve-shield" [size]="16" />
                      <span>Verify &amp; Continue</span>
                      @if (modalActionHint('government.stepUp', 'stepUp.verify', 'V/v'); as h) {
                        <span class="mc-action-key-badge" aria-hidden="true">[{{ h }}]</span>
                      }
                    </button>
                    <button
                      type="button"
                      class="mc-btn mc-btn-secondary"
                      data-testid="gov-stepup-cancel-btn"
                      (click)="cancelStepUpModal()"
                    >
                      <mc-icon name="close" [size]="16" />
                      <span>Cancel</span>
                      @if (modalActionHint('government.stepUp', 'editor.close', 'C/c'); as h) {
                        <span class="mc-action-key-badge" aria-hidden="true">[{{ h }}]</span>
                      }
                    </button>
                  </div>
                </div>
              </form>
            </div>
          </div>
        }
      }

      <mc-help-panel />
    </div>
  `,
  styles: `
    .mc-gov-layout {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-4);
    }

    .mc-gov-header {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: flex-start;
      gap: var(--mc-space-3);
    }

    .mc-gov-title-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-gov-sub {
      margin: 0.25rem 0 0;
      font-size: var(--mc-font-size-sm);
      color: var(--mc-color-slate-600);
      max-width: 82ch;
    }

    .mc-gov-header-actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-gov-denied-card {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-gov-denied-copy {
      margin: 0;
      font-size: var(--mc-font-size-sm);
      line-height: 1.5;
    }

    .mc-gov-tabs {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-gov-split {
      display: grid;
      grid-template-columns: minmax(280px, 1fr) minmax(340px, 1.35fr);
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 1024px) {
      .mc-gov-split {
        grid-template-columns: minmax(0, 1fr);
      }
    }

    .mc-gov-card {
      padding: var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);

      h2 {
        font-size: var(--mc-font-size-md);
        margin: 0;
      }

      h3 {
        font-size: var(--mc-font-size-sm);
        margin: 0;
      }
    }

    .mc-gov-clickable-row {
      cursor: pointer;
    }

    .mc-gov-cell-sub {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-gov-detail-head {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: flex-start;
      gap: var(--mc-space-2);
    }

    .mc-gov-meta-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem;
      margin-top: 0.35rem;
    }

    .mc-gov-action-cluster {
      display: flex;
      gap: var(--mc-space-2);
    }

    .mc-gov-kv-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-2) var(--mc-space-4);
      margin: 0;
      font-size: var(--mc-font-size-xs);

      dt {
        font-weight: 600;
        color: var(--mc-color-slate-600);
      }

      dd {
        margin: 0.1rem 0 0;
        font-weight: 600;
        overflow-wrap: anywhere;
      }
    }

    @media (max-width: 640px) {
      .mc-gov-kv-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }

    .mc-gov-divider {
      border: 0;
      border-top: var(--mc-border-subtle);
      margin: var(--mc-space-1) 0;
    }

    .mc-gov-section-title-row {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-gov-inline-helps {
      display: inline-flex;
      gap: 0.35rem;
    }

    .mc-gov-note {
      margin: 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      line-height: 1.45;
    }

    .mc-gov-slot-form {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-gov-slot-row {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }

    .mc-gov-slot-label-line {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-gov-slot-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-gov-btn-row,
    .mc-gov-lifecycle-btns {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-gov-probe-box,
    .mc-gov-validation-card {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      font-size: var(--mc-font-size-xs);
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .mc-gov-inventory-toolbar {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
      gap: var(--mc-space-3);
    }

    @media (max-width: 640px) {
      .mc-gov-inventory-toolbar {
        grid-template-columns: minmax(0, 1fr);
      }
    }

    .mc-gov-filter-field {
      display: flex;
      flex-direction: column;
    }

    .mc-gov-modal-stack {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-gov-ops-fieldset {
      border: var(--mc-border-strong);
      border-radius: var(--mc-radius-xs);
      padding: var(--mc-space-2) var(--mc-space-3);
      margin: 0;
    }

    .mc-gov-ops-checks {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-2) var(--mc-space-3);
      margin-top: 0.25rem;
    }

    .mc-gov-op-check {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      font-size: var(--mc-font-size-xs);
      cursor: pointer;
    }
  `,
})
export class GovernmentIntegrationsComponent implements OnInit {
  readonly companyContext = inject(CompanyContextStore);
  private readonly authStore = inject(AuthStore);
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly destroyRef = inject(DestroyRef);

  readonly admittedProviders = ADMITTED_INTEGRATION_PROVIDERS;

  readonly isSuperAdmin = computed<boolean>(() =>
    isPrincipalSuperAdmin(this.authStore.principal()),
  );

  readonly activeTab = signal<'PROFILES' | 'INVENTORY'>('PROFILES');
  readonly profiles = signal<readonly GovernmentIntegrationProfileView[]>([]);
  readonly selectedProfileId = signal<string | null>(null);
  readonly auditEvents = signal<readonly GovernmentProfileAuditEntry[]>([]);
  readonly inventoryRows = signal<readonly GovernmentCapabilityInventoryRow[]>(
    STATIC_GOVERNMENT_AND_BANKING_INVENTORY,
  );
  readonly companyGstRegistrations = signal<readonly GstRegistrationMaster[]>([]);

  readonly inventoryFilterQuery = signal<string>('');
  readonly inventoryChannelFilter = signal<string>('ALL');

  readonly statusBanner = signal<string | null>(null);
  readonly errorBanner = signal<string | null>(null);

  // Transient write-only credential slot inputs (never persisted, wiped immediately after staging)
  readonly stagedSlotInputs = signal<Readonly<Record<string, string>>>({});

  // Create / Edit Draft Modal state
  readonly profileModalOpen = signal<boolean>(false);
  readonly editingProfileId = signal<string | null>(null);
  draftProfileName = '';
  draftEnvironment: IntegrationEnvironment = 'SANDBOX';
  draftProviderCode = 'EINVOICE_IRP6';
  draftCompanyId = '';
  draftRegistrationRef = '';
  draftConsentEvidenceRef = '';
  draftCertFingerprint = '';
  readonly draftAllowedOps = signal<readonly string[]>(['GOV-D12', 'GOV-D13', 'GOV-D15', 'GOV-D17']);

  // Disable Modal state
  readonly disableModalOpen = signal<boolean>(false);
  disableReasonCode: DisableReasonCode = 'ROTATION_MAINTENANCE';
  disableReconciliationNote = '';

  // 5-Minute Single-Use Step-Up Modal state
  readonly stepUpModalOpen = signal<boolean>(false);
  readonly pendingStepUpAction = signal<StepUpAdminAction | null>(null);
  readonly pendingStepUpTargetId = signal<string | null>(null);
  readonly pendingStepUpRevision = signal<string | null>(null);
  readonly stepUpError = signal<string | null>(null);
  stepUpPasswordInput = '';
  private stepUpContinuation: ((stepUpIntentId: string) => Promise<void>) | null = null;
  private modalContextHandle: ActiveModalContextHandle | null = null;

  readonly selectedProfile = computed<GovernmentIntegrationProfileView | null>(() => {
    const id = this.selectedProfileId();
    const all = this.profiles();
    if (!id) {
      return all[0] ?? null;
    }
    return all.find((p) => p.id === id) ?? all[0] ?? null;
  });

  readonly currentDraftProviderSpec = computed<AdmittedIntegrationProviderSpec>(() => {
    return (
      this.admittedProviders.find((p) => p.providerCode === this.draftProviderCode) ??
      this.admittedProviders[0]
    );
  });

  readonly resolvedDraftEndpointProfileId = computed<string>(() => {
    const spec = this.currentDraftProviderSpec();
    return this.draftEnvironment === 'PRODUCTION'
      ? spec.productionEndpointProfileId
      : spec.sandboxEndpointProfileId;
  });

  readonly filteredInventoryRows = computed<readonly GovernmentCapabilityInventoryRow[]>(() => {
    const q = this.inventoryFilterQuery().trim().toLowerCase();
    const channel = this.inventoryChannelFilter();
    return this.inventoryRows().filter((row) => {
      if (channel !== 'ALL') {
        if (channel === 'AUTHENTICATED_API' && !row.channelClass.startsWith('AUTHENTICATED_API')) {
          return false;
        }
        if (
          channel === 'CONNECTED_BANKING_API' &&
          row.channelClass !== 'CONNECTED_BANKING_API' &&
          !row.draftCapabilityId.startsWith('BANK-')
        ) {
          return false;
        }
        if (channel === 'CANDIDATE_API' && !row.channelClass.includes('CANDIDATE')) {
          return false;
        }
        if (
          channel === 'FILE_OR_UTILITY' &&
          !row.channelClass.includes('FILE') &&
          !row.channelClass.includes('UTILITY')
        ) {
          return false;
        }
        if (channel === 'HUMAN_PORTAL' && !row.channelClass.includes('HUMAN_PORTAL')) {
          return false;
        }
      }
      if (!q) {
        return true;
      }
      return (
        row.draftCapabilityId.toLowerCase().includes(q) ||
        row.capabilityFamily.toLowerCase().includes(q) ||
        row.operationScope.toLowerCase().includes(q) ||
        row.authProfileRef.toLowerCase().includes(q) ||
        row.moneycraftImplementationStatus.toLowerCase().includes(q)
      );
    });
  });

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.clearTransientCredentialInputs();
      this.stepUpPasswordInput = '';
      this.releaseModalContext();
    });
  }

  ngOnInit(): void {
    const activeCompany = this.companyContext.activeCompany();
    if (activeCompany) {
      this.draftCompanyId = activeCompany.id;
      this.draftRegistrationRef = activeCompany.features.gstin || '';
    }
    if (this.isSuperAdmin()) {
      void this.reloadAll();
    }
  }

  async reloadAll(): Promise<void> {
    if (!this.isSuperAdmin()) {
      return;
    }
    this.errorBanner.set(null);
    if (this.companyContext.openCompanies().length === 0) {
      await this.companyContext.loadCompanies();
      const first = this.companyContext.activeCompany();
      if (first && !this.draftCompanyId) {
        this.draftCompanyId = first.id;
      }
    }
    await Promise.all([this.loadProfiles(), this.loadInventory()]);
    const sel = this.selectedProfile();
    if (sel) {
      await this.loadProfileAudit(sel.id);
    }
  }

  async loadProfiles(): Promise<void> {
    try {
      const raw = await firstValueFrom(
        this.http.get<unknown>(this.runtimeConfig.url('/admin/government-integration-profiles'), {
          withCredentials: true,
        }),
      );
      const rows = Array.isArray(raw)
        ? raw
        : raw && typeof raw === 'object' && Array.isArray((raw as Record<string, unknown>)['items'])
          ? ((raw as Record<string, unknown>)['items'] as unknown[])
          : [];
      const mapped = rows
        .map((item) => this.normalizeProfile(item, null))
        .filter((p): p is GovernmentIntegrationProfileView => p !== null);
      this.profiles.set(mapped);
      if (mapped.length > 0 && !this.selectedProfileId()) {
        this.selectedProfileId.set(mapped[0].id);
      }
    } catch (err: unknown) {
      this.errorBanner.set(
        err instanceof Error
          ? err.message
          : 'Unable to load government integration profiles. Verify Super Admin session.',
      );
    }
  }

  async loadInventory(): Promise<void> {
    try {
      const raw = await firstValueFrom(
        this.http.get<unknown>(this.runtimeConfig.url('/admin/government-interface-inventory'), {
          withCredentials: true,
        }),
      );
      const list = Array.isArray(raw)
        ? raw
        : raw && typeof raw === 'object' && Array.isArray((raw as Record<string, unknown>)['items'])
          ? ((raw as Record<string, unknown>)['items'] as unknown[])
          : raw &&
              typeof raw === 'object' &&
              Array.isArray((raw as Record<string, unknown>)['capabilities'])
            ? ((raw as Record<string, unknown>)['capabilities'] as unknown[])
            : [];

      if (list.length > 0) {
        const reconciled = list.map((r) => this.normalizeInventoryRow(r));
        // Ensure any Connected Banking rows not returned by server remain visible alongside server rows
        const seenIds = new Set(reconciled.map((r) => r.draftCapabilityId));
        const merged = [
          ...reconciled,
          ...STATIC_GOVERNMENT_AND_BANKING_INVENTORY.filter((s) => !seenIds.has(s.draftCapabilityId)),
        ];
        this.inventoryRows.set(merged);
      }
    } catch {
      // Retain complete bundled 44-row inventory offline/fallback
    }
  }

  selectProfile(profile: GovernmentIntegrationProfileView): void {
    this.clearTransientCredentialInputs();
    this.selectedProfileId.set(profile.id);
    void this.refreshSingleProfile(profile.id);
    void this.loadProfileAudit(profile.id);
  }

  async refreshSingleProfile(profileId: string): Promise<void> {
    try {
      const resp = await firstValueFrom(
        this.http.get<unknown>(
          this.runtimeConfig.url(
            `/admin/government-integration-profiles/${encodeURIComponent(profileId)}`,
          ),
          { observe: 'response', withCredentials: true },
        ),
      );
      const normalized = this.normalizeProfile(resp.body, resp.headers.get('ETag'));
      if (normalized) {
        this.upsertProfileInList(normalized);
      }
    } catch {
      // Keep existing list item if detail fetch fails
    }
  }

  async loadProfileAudit(profileId: string): Promise<void> {
    try {
      const raw = await firstValueFrom(
        this.http.get<unknown>(
          this.runtimeConfig.url(
            `/admin/government-integration-profiles/${encodeURIComponent(profileId)}/audit`,
          ),
          { withCredentials: true },
        ),
      );
      const list = Array.isArray(raw)
        ? raw
        : raw && typeof raw === 'object' && Array.isArray((raw as Record<string, unknown>)['items'])
          ? ((raw as Record<string, unknown>)['items'] as unknown[])
          : [];
      this.auditEvents.set(
        list.map((ev, index) => {
          const r = (ev ?? {}) as Record<string, unknown>;
          return {
            eventId: String(r['id'] ?? r['eventId'] ?? `aud-${index}`),
            timestamp: String(r['timestamp'] ?? r['createdAt'] ?? r['occurredAt'] ?? ''),
            actor: String(r['actor'] ?? r['principalUsername'] ?? r['principalId'] ?? 'SUPER_ADMIN'),
            action: String(r['action'] ?? r['operation'] ?? ''),
            revision: String(r['revision'] ?? '0'),
            result: String(r['result'] ?? r['outcome'] ?? 'COMMITTED'),
            reason: r['reason'] ? String(r['reason']) : null,
            correlationId: r['correlationId'] ? String(r['correlationId']) : null,
          };
        }),
      );
    } catch {
      this.auditEvents.set([]);
    }
  }

  providerSpecFor(providerCode: string): AdmittedIntegrationProviderSpec | null {
    return this.admittedProviders.find((p) => p.providerCode === providerCode) ?? null;
  }

  providerSlotsFor(providerCode: string): readonly ProviderCredentialSlotSchema[] {
    return this.providerSpecFor(providerCode)?.credentialSlots ?? [];
  }

  slotStatusFor(profile: GovernmentIntegrationProfileView, slotKey: string): CredentialSlotStatus {
    const found = profile.credentialSlots.find((s) => s.slotKey === slotKey);
    return found?.status ?? 'MISSING';
  }

  slotStatusLabel(status: CredentialSlotStatus): string {
    switch (status) {
      case 'CONFIGURED':
        return 'Configured';
      case 'EXPIRED':
        return 'Expired';
      case 'VALIDATION_FAILED':
        return 'Validation failed';
      case 'MISSING':
        return 'Missing';
    }
  }

  slotStatusBadgeClass(status: CredentialSlotStatus): string {
    switch (status) {
      case 'CONFIGURED':
        return 'mc-badge mc-badge-jade';
      case 'EXPIRED':
        return 'mc-badge mc-badge-warning';
      case 'VALIDATION_FAILED':
        return 'mc-badge mc-badge-danger';
      case 'MISSING':
        return 'mc-badge mc-badge-slate';
    }
  }

  statusBadgeClass(status: IntegrationProfileStatus): string {
    switch (status) {
      case 'ACTIVE':
      case 'VALIDATED':
        return 'mc-badge mc-badge-jade';
      case 'VALIDATION_PENDING':
      case 'CONFIGURED':
        return 'mc-badge mc-badge-warning';
      case 'FAILED':
      case 'EXPIRED_OR_REVOKED':
        return 'mc-badge mc-badge-danger';
      case 'DRAFT':
      case 'DISABLED':
        return 'mc-badge mc-badge-slate';
    }
  }

  canActivateProfile(profile: GovernmentIntegrationProfileView): boolean {
    if (profile.status === 'VALIDATED') {
      return true;
    }
    if (
      profile.lastValidation &&
      profile.lastValidation.status === 'VALIDATED' &&
      profile.lastValidation.testedRevision === profile.revision
    ) {
      return true;
    }
    return false;
  }

  updateStagedSlotInput(slotKey: string, value: string): void {
    this.stagedSlotInputs.set({
      ...this.stagedSlotInputs(),
      [slotKey]: value,
    });
  }

  clearTransientCredentialInputs(): void {
    this.stagedSlotInputs.set({});
  }

  modalActionHint(
    contextId: string,
    commandId: string,
    pairSpec: 'S/s' | 'C/c' | 'D/d' | 'V/v',
  ): string | null {
    return this.shortcutRegistry.effectiveActionHint(contextId, commandId, asciiPair(pairSpec));
  }

  openCreateProfileModal(): void {
    this.editingProfileId.set(null);
    this.draftProfileName = '';
    this.draftEnvironment = 'SANDBOX';
    this.draftProviderCode = 'EINVOICE_IRP6';
    const activeComp = this.companyContext.activeCompany();
    this.draftCompanyId = activeComp?.id ?? '';
    this.draftRegistrationRef = activeComp?.features.gstin ?? '';
    this.draftConsentEvidenceRef = '';
    this.draftCertFingerprint = '';
    this.draftAllowedOps.set([...this.currentDraftProviderSpec().allowedOperations]);
    if (this.draftCompanyId) {
      void this.loadCompanyGstRegistrations(this.draftCompanyId);
    }
    this.profileModalOpen.set(true);
    this.bindProfileEditorModalShortcuts();
  }

  openEditProfileModal(profile: GovernmentIntegrationProfileView): void {
    this.editingProfileId.set(profile.id);
    this.draftProfileName = profile.profileName;
    this.draftEnvironment = profile.environment;
    this.draftProviderCode = profile.providerCode;
    this.draftCompanyId = profile.companyId;
    this.draftRegistrationRef = profile.registrationRef;
    this.draftConsentEvidenceRef = profile.consentEvidenceRef;
    this.draftCertFingerprint = profile.certificateFingerprintSha256 ?? '';
    this.draftAllowedOps.set([...profile.allowedOperationIds]);
    if (profile.companyId) {
      void this.loadCompanyGstRegistrations(profile.companyId);
    }
    this.profileModalOpen.set(true);
    this.bindProfileEditorModalShortcuts();
  }

  closeProfileModal(): void {
    this.profileModalOpen.set(false);
    this.releaseModalContext();
  }

  onDraftProviderChange(providerCode: string): void {
    this.draftProviderCode = providerCode;
    const spec = this.providerSpecFor(providerCode);
    if (spec) {
      this.draftAllowedOps.set([...spec.allowedOperations]);
    }
  }

  onDraftCompanyChange(companyId: string): void {
    this.draftCompanyId = companyId;
    const comp = this.companyContext.openCompanies().find((c) => c.id === companyId);
    if (comp?.features.gstin) {
      this.draftRegistrationRef = comp.features.gstin;
    }
    void this.loadCompanyGstRegistrations(companyId);
  }

  toggleDraftOperation(opId: string): void {
    const current = this.draftAllowedOps();
    const next = current.includes(opId)
      ? current.filter((x) => x !== opId)
      : [...current, opId].sort();
    this.draftAllowedOps.set(next);
  }

  submitProfileDraft(): void {
    const name = this.draftProfileName.trim();
    const companyId = this.draftCompanyId.trim();
    const registrationRef = this.draftRegistrationRef.trim();
    if (!name || !companyId || !registrationRef) {
      this.errorBanner.set('Profile name, permitted company, and registration binding are required.');
      return;
    }

    const spec = this.currentDraftProviderSpec();
    const sortedOps = [...this.draftAllowedOps()].sort();
    if (sortedOps.length === 0) {
      this.errorBanner.set('Select at least one permitted closed operation.');
      return;
    }

    const editId = this.editingProfileId();
    const existing = editId ? this.profiles().find((p) => p.id === editId) ?? null : null;
    const action: StepUpAdminAction = editId
      ? 'government.profile.update'
      : 'government.profile.create';

    this.closeProfileModal();
    this.requestStepUpAndRun(action, editId, existing?.revision ?? null, async (stepUpIntentId) => {
      const payload = {
        profileName: name,
        providerCode: spec.providerCode,
        adapterCode: spec.adapterCode,
        endpointProfileId: this.resolvedDraftEndpointProfileId(),
        domainCategory: spec.domainCategory,
        environment: this.draftEnvironment,
        companyId,
        registrationRef,
        allowedOperationIds: sortedOps,
        consentEvidenceRef: this.draftConsentEvidenceRef.trim(),
        certificateFingerprintSha256: this.draftCertFingerprint.trim() || null,
        stepUpIntentId,
      };

      if (!editId) {
        const resp = await firstValueFrom(
          this.http.post<unknown>(
            this.runtimeConfig.url('/admin/government-integration-profiles'),
            payload,
            {
              headers: this.buildMutationHeaders(stepUpIntentId, null),
              observe: 'response',
              withCredentials: true,
            },
          ),
        );
        const created = this.normalizeProfile(resp.body, resp.headers.get('ETag'));
        if (created) {
          this.upsertProfileInList(created);
          this.selectedProfileId.set(created.id);
          await this.loadProfileAudit(created.id);
        }
        this.statusBanner.set(
          `Created disabled ${this.draftEnvironment} integration draft "${name}".`,
        );
      } else {
        const ifMatch = existing ? this.resolveProfileIfMatch(existing) : null;
        const resp = await firstValueFrom(
          this.http.put<unknown>(
            this.runtimeConfig.url(
              `/admin/government-integration-profiles/${encodeURIComponent(editId)}`,
            ),
            payload,
            {
              headers: this.buildMutationHeaders(stepUpIntentId, ifMatch),
              observe: 'response',
              withCredentials: true,
            },
          ),
        );
        const updated = this.normalizeProfile(resp.body, resp.headers.get('ETag'));
        if (updated) {
          this.upsertProfileInList(updated);
          await this.loadProfileAudit(updated.id);
        }
        this.statusBanner.set(
          `Updated draft metadata for "${name}" (prior validation invalidated until re-probed).`,
        );
      }
    });
  }

  stageCredentialVersion(profile: GovernmentIntegrationProfileView): void {
    const rawInputs = this.stagedSlotInputs();
    const nonEmptySlots: Record<string, string> = {};
    for (const [key, val] of Object.entries(rawInputs)) {
      const trimmed = val.trim();
      if (trimmed.length > 0) {
        nonEmptySlots[key] = trimmed;
      }
    }

    if (Object.keys(nonEmptySlots).length === 0) {
      this.errorBanner.set('Enter at least one replacement credential slot before staging.');
      return;
    }

    // Snapshot transiently for the single TLS POST and wipe component input signals immediately
    // if user cancels or once submitted!
    this.requestStepUpAndRun(
      'government.profile.credential.stage',
      profile.id,
      profile.revision,
      async (stepUpIntentId) => {
        try {
          await firstValueFrom(
            this.http.post<unknown>(
              this.runtimeConfig.url(
                `/admin/government-integration-profiles/${encodeURIComponent(profile.id)}/credential-versions`,
              ),
              {
                slots: nonEmptySlots,
                secretSlots: nonEmptySlots,
                expectedRevision: profile.revision,
                stepUpIntentId,
              },
              {
                headers: this.buildMutationHeaders(
                  stepUpIntentId,
                  this.resolveProfileIfMatch(profile),
                ),
                withCredentials: true,
              },
            ),
          );
          await this.refreshSingleProfile(profile.id);
          await this.loadProfileAudit(profile.id);
          this.statusBanner.set(
            'Staged encrypted credential version. Stored secrets remain server-side only; run Validate connection next.',
          );
        } finally {
          this.clearTransientCredentialInputs();
        }
      },
    );
  }

  startSafeValidation(profile: GovernmentIntegrationProfileView): void {
    this.requestStepUpAndRun(
      'government.profile.validation.start',
      profile.id,
      profile.revision,
      async (stepUpIntentId) => {
        const resp = await firstValueFrom(
          this.http.post<unknown>(
            this.runtimeConfig.url(
              `/admin/government-integration-profiles/${encodeURIComponent(profile.id)}/validations`,
            ),
            {
              expectedRevision: profile.revision,
              credentialRevision: profile.credentialRevision,
              stepUpIntentId,
            },
            {
              headers: this.buildMutationHeaders(
                stepUpIntentId,
                this.resolveProfileIfMatch(profile),
              ),
              observe: 'response',
              withCredentials: true,
            },
          ),
        );

        const body = (resp.body ?? {}) as Record<string, unknown>;
        const validationId = String(
          body['validationId'] ?? body['id'] ?? `val-${Date.now()}`,
        );
        await this.refreshValidationOutcome(profile.id, validationId);
        await this.refreshSingleProfile(profile.id);
        await this.loadProfileAudit(profile.id);
        this.statusBanner.set(
          `Safe connection validation (${validationId}) recorded for revision r${profile.revision} (zero statutory/payment mutation).`,
        );
      },
    );
  }

  async refreshValidationOutcome(profileId: string, validationId: string): Promise<void> {
    try {
      const raw = await firstValueFrom(
        this.http.get<unknown>(
          this.runtimeConfig.url(
            `/admin/government-integration-profiles/${encodeURIComponent(profileId)}/validations/${encodeURIComponent(validationId)}`,
          ),
          { withCredentials: true },
        ),
      );
      const r = (raw ?? {}) as Record<string, unknown>;
      const statusRaw = String(r['status'] ?? 'VALIDATED').toUpperCase();
      const mappedStatus: ValidationOutcomeView['status'] =
        statusRaw === 'PENDING' ||
        statusRaw === 'VALIDATED' ||
        statusRaw === 'FAILED' ||
        statusRaw === 'NOT_VALIDATED'
          ? statusRaw
          : 'VALIDATED';

      const current = this.profiles().find((p) => p.id === profileId);
      if (current) {
        const nextValidation: ValidationOutcomeView = {
          validationId,
          status: mappedStatus,
          testedRevision: String(r['testedRevision'] ?? r['revision'] ?? current.revision),
          testedCredentialRevision: r['credentialRevision']
            ? String(r['credentialRevision'])
            : current.credentialRevision,
          summaryMessage: String(
            r['summaryMessage'] ?? r['message'] ?? r['detail'] ?? `Validation ${mappedStatus}`,
          ),
          checkedAt: r['checkedAt'] ? String(r['checkedAt']) : new Date().toISOString(),
        };
        const updatedProfile: GovernmentIntegrationProfileView = {
          ...current,
          status: mappedStatus === 'VALIDATED' && current.status !== 'ACTIVE' ? 'VALIDATED' : current.status,
          lastValidation: nextValidation,
        };
        this.upsertProfileInList(updatedProfile);
      }
    } catch {
      // Non-fatal if polling before job commits
    }
  }

  activateValidatedProfile(profile: GovernmentIntegrationProfileView): void {
    const validationId = profile.lastValidation?.validationId ?? '';
    this.requestStepUpAndRun(
      'government.profile.activate',
      profile.id,
      profile.revision,
      async (stepUpIntentId) => {
        const resp = await firstValueFrom(
          this.http.post<unknown>(
            this.runtimeConfig.url(
              `/admin/government-integration-profiles/${encodeURIComponent(profile.id)}/activate`,
            ),
            {
              validationId,
              expectedRevision: profile.revision,
              stepUpIntentId,
            },
            {
              headers: this.buildMutationHeaders(
                stepUpIntentId,
                this.resolveProfileIfMatch(profile),
              ),
              observe: 'response',
              withCredentials: true,
            },
          ),
        );
        const activated = this.normalizeProfile(resp.body, resp.headers.get('ETag'));
        if (activated) {
          this.upsertProfileInList(activated);
        } else {
          await this.refreshSingleProfile(profile.id);
        }
        await this.loadProfileAudit(profile.id);
        this.statusBanner.set(
          `Activated ${profile.environment} integration profile "${profile.profileName}" for ${profile.registrationRef}.`,
        );
      },
    );
  }

  openDisableModal(profile: GovernmentIntegrationProfileView): void {
    this.selectedProfileId.set(profile.id);
    this.disableReasonCode = 'ROTATION_MAINTENANCE';
    this.disableReconciliationNote = '';
    this.disableModalOpen.set(true);
    this.releaseModalContext();
    const actions: readonly ModalActionDefinition[] = [
      {
        contextId: 'government.disableConfirm',
        locale: 'en-GB',
        commandId: 'government.disable',
        label: 'Disable',
        letterPair: asciiPair('D/d'),
        permissionRef: 'superAdmin',
        effect: 'Proceed to Super Admin step-up verification to disable profile.',
        normalActivation: () => this.confirmDisableProfile(profile),
        iconSemanticId: null,
        stateTokenRole: 'action.danger',
        isMutation: true,
      },
      {
        contextId: 'government.disableConfirm',
        locale: 'en-GB',
        commandId: 'editor.close',
        label: 'Cancel',
        letterPair: asciiPair('C/c'),
        permissionRef: 'superAdmin',
        effect: 'Cancel disable dialog without altering active profile.',
        normalActivation: () => this.closeDisableModal(),
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ];
    this.modalContextHandle = this.shortcutRegistry.openModalContext(actions, 'en-GB');
  }

  closeDisableModal(): void {
    this.disableModalOpen.set(false);
    this.releaseModalContext();
  }

  confirmDisableProfile(profile: GovernmentIntegrationProfileView): void {
    const reasonCode = this.disableReasonCode;
    const reconciliationNote =
      this.disableReconciliationNote.trim() ||
      'In-flight operations retain submitted profile revision for retrieval reconciliation.';
    this.closeDisableModal();

    this.requestStepUpAndRun(
      'government.profile.disable',
      profile.id,
      profile.revision,
      async (stepUpIntentId) => {
        const resp = await firstValueFrom(
          this.http.post<unknown>(
            this.runtimeConfig.url(
              `/admin/government-integration-profiles/${encodeURIComponent(profile.id)}/disable`,
            ),
            {
              reasonCode,
              reasonDetail: reconciliationNote,
              expectedRevision: profile.revision,
              stepUpIntentId,
            },
            {
              headers: this.buildMutationHeaders(
                stepUpIntentId,
                this.resolveProfileIfMatch(profile),
              ),
              observe: 'response',
              withCredentials: true,
            },
          ),
        );
        const disabled = this.normalizeProfile(resp.body, resp.headers.get('ETag'));
        if (disabled) {
          this.upsertProfileInList(disabled);
        } else {
          await this.refreshSingleProfile(profile.id);
        }
        await this.loadProfileAudit(profile.id);
        this.statusBanner.set(
          `Disabled profile "${profile.profileName}" (${reasonCode}). New dispatches blocked.`,
        );
      },
    );
  }

  async submitStepUpPassword(): Promise<void> {
    const password = this.stepUpPasswordInput;
    const action = this.pendingStepUpAction();
    if (!password || !action) {
      this.stepUpError.set('Enter your Super Admin password to verify step-up intent.');
      return;
    }

    this.stepUpError.set(null);
    const targetProfileId = this.pendingStepUpTargetId();
    const expectedRevision = this.pendingStepUpRevision();
    const numericExpectedRevision =
      expectedRevision !== null && /^\d+$/.test(expectedRevision)
        ? Number.parseInt(expectedRevision, 10)
        : null;
    const continuation = this.stepUpContinuation;

    try {
      const stepUpResp = await firstValueFrom(
        this.http.post<StepUpPasswordResponse>(
          this.runtimeConfig.url('/auth/step-up/password'),
          {
            password,
            targetProfileId,
            action,
            expectedRevision: numericExpectedRevision,
          },
          {
            headers: new HttpHeaders({ 'Idempotency-Key': generateIdempotencyKey() }),
            withCredentials: true,
          },
        ),
      );

      if (!stepUpResp?.stepUpIntentId) {
        this.stepUpError.set('Server did not issue a valid step-up intent.');
        return;
      }

      // Close step-up modal and wipe transient password before running target mutation
      this.stepUpPasswordInput = '';
      this.stepUpModalOpen.set(false);
      this.stepUpContinuation = null;
      this.releaseModalContext();

      if (continuation) {
        this.errorBanner.set(null);
        await continuation(stepUpResp.stepUpIntentId);
      }
    } catch (err: unknown) {
      this.stepUpError.set(
        err instanceof Error ? err.message : 'Step-up verification or administrative action failed.',
      );
    } finally {
      this.stepUpPasswordInput = '';
    }
  }

  cancelStepUpModal(): void {
    this.stepUpPasswordInput = '';
    this.stepUpError.set(null);
    this.stepUpContinuation = null;
    this.stepUpModalOpen.set(false);
    this.releaseModalContext();
  }

  private requestStepUpAndRun(
    action: StepUpAdminAction,
    targetProfileId: string | null,
    expectedRevision: string | null,
    continuation: (stepUpIntentId: string) => Promise<void>,
  ): void {
    this.stepUpPasswordInput = '';
    this.stepUpError.set(null);
    this.pendingStepUpAction.set(action);
    this.pendingStepUpTargetId.set(targetProfileId);
    this.pendingStepUpRevision.set(expectedRevision);
    this.stepUpContinuation = continuation;
    this.stepUpModalOpen.set(true);

    this.releaseModalContext();
    const actions: readonly ModalActionDefinition[] = [
      {
        contextId: 'government.stepUp',
        locale: 'en-GB',
        commandId: 'stepUp.verify',
        label: 'Verify & Continue',
        letterPair: asciiPair('V/v'),
        permissionRef: 'superAdmin',
        effect: 'Submit entered Super Admin password to obtain single-use step-up proof.',
        normalActivation: () => this.submitStepUpPassword(),
        iconSemanticId: 'mc.icon.approve-shield',
        stateTokenRole: 'action.primary',
        isMutation: true,
      },
      {
        contextId: 'government.stepUp',
        locale: 'en-GB',
        commandId: 'editor.close',
        label: 'Cancel',
        letterPair: asciiPair('C/c'),
        permissionRef: 'superAdmin',
        effect: 'Abort step-up prompt without executing mutation.',
        normalActivation: () => this.cancelStepUpModal(),
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ];
    this.modalContextHandle = this.shortcutRegistry.openModalContext(actions, 'en-GB');
  }

  private bindProfileEditorModalShortcuts(): void {
    this.releaseModalContext();
    const actions: readonly ModalActionDefinition[] = [
      {
        contextId: 'government.profileEditor',
        locale: 'en-GB',
        commandId: 'government.saveDraft',
        label: 'Save draft',
        letterPair: asciiPair('S/s'),
        permissionRef: 'superAdmin',
        effect: 'Proceed to Super Admin step-up verification to save integration draft.',
        normalActivation: () => this.submitProfileDraft(),
        iconSemanticId: 'mc.icon.save-document',
        stateTokenRole: 'action.primary',
        isMutation: true,
      },
      {
        contextId: 'government.profileEditor',
        locale: 'en-GB',
        commandId: 'editor.close',
        label: 'Cancel',
        letterPair: asciiPair('C/c'),
        permissionRef: 'superAdmin',
        effect: 'Close integration draft form without saving.',
        normalActivation: () => this.closeProfileModal(),
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ];
    this.modalContextHandle = this.shortcutRegistry.openModalContext(actions, 'en-GB');
  }

  private releaseModalContext(): void {
    if (this.modalContextHandle) {
      this.modalContextHandle.close();
      this.modalContextHandle = null;
    }
  }

  private async loadCompanyGstRegistrations(companyId: string): Promise<void> {
    try {
      const list = await firstValueFrom(
        this.http.get<readonly GstRegistrationMaster[]>(
          this.runtimeConfig.url(`/companies/${encodeURIComponent(companyId)}/gst/registrations`),
          { withCredentials: true },
        ),
      );
      this.companyGstRegistrations.set(Array.isArray(list) ? list : []);
      if (!this.draftRegistrationRef && list.length > 0) {
        this.draftRegistrationRef = list[0].gstin;
      }
    } catch {
      this.companyGstRegistrations.set([]);
    }
  }

  private buildMutationHeaders(stepUpIntentId: string, ifMatch: string | null): HttpHeaders {
    const map: Record<string, string> = {
      'Idempotency-Key': generateIdempotencyKey(),
      'X-Step-Up-Intent': stepUpIntentId,
    };
    if (ifMatch) {
      map['If-Match'] = ifMatch;
    }
    return new HttpHeaders(map);
  }

  private resolveProfileIfMatch(profile: GovernmentIntegrationProfileView): string {
    if (profile.etag && profile.etag.trim().length > 0) {
      return profile.etag.trim();
    }
    return `"gov:${profile.id}:${profile.revision}"`;
  }

  private upsertProfileInList(profile: GovernmentIntegrationProfileView): void {
    const existing = this.profiles();
    const idx = existing.findIndex((p) => p.id === profile.id);
    if (idx >= 0) {
      const next = [...existing];
      next[idx] = profile;
      this.profiles.set(next);
    } else {
      this.profiles.set([profile, ...existing]);
    }
  }

  private normalizeProfile(
    raw: unknown,
    headerEtag: string | null,
  ): GovernmentIntegrationProfileView | null {
    if (!raw || typeof raw !== 'object') {
      return null;
    }
    const r = raw as Record<string, unknown>;
    const id = String(r['id'] ?? r['profileId'] ?? '');
    if (!id) {
      return null;
    }
    const providerCode = String(r['providerCode'] ?? r['provider'] ?? 'EINVOICE_IRP6');
    const spec = this.providerSpecFor(providerCode);
    const environment: IntegrationEnvironment =
      String(r['environment'] ?? 'SANDBOX').toUpperCase() === 'PRODUCTION'
        ? 'PRODUCTION'
        : 'SANDBOX';
    const revision = String(r['revision'] ?? '0');

    const rawSlots = Array.isArray(r['credentialSlots'])
      ? (r['credentialSlots'] as unknown[])
      : Array.isArray(r['credentialReferences'])
        ? (r['credentialReferences'] as unknown[])
        : [];

    const credentialSlots: CredentialSlotMetadataView[] = rawSlots.map((s) => {
      const sr = (s ?? {}) as Record<string, unknown>;
      const stRaw = String(sr['status'] ?? 'CONFIGURED').toUpperCase();
      const status: CredentialSlotStatus =
        stRaw === 'CONFIGURED' ||
        stRaw === 'MISSING' ||
        stRaw === 'EXPIRED' ||
        stRaw === 'VALIDATION_FAILED'
          ? stRaw
          : 'CONFIGURED';
      return {
        slotKey: String(sr['slotKey'] ?? sr['slotName'] ?? ''),
        status,
        credentialVersionId: sr['credentialVersionId'] ? String(sr['credentialVersionId']) : null,
        versionNo: typeof sr['versionNo'] === 'number' ? sr['versionNo'] : null,
        updatedAt: sr['updatedAt'] ? String(sr['updatedAt']) : null,
      };
    });

    let lastValidation: ValidationOutcomeView | null = null;
    if (r['lastValidation'] && typeof r['lastValidation'] === 'object') {
      const lv = r['lastValidation'] as Record<string, unknown>;
      const vst = String(lv['status'] ?? 'NOT_VALIDATED').toUpperCase();
      lastValidation = {
        validationId: String(lv['validationId'] ?? lv['id'] ?? ''),
        status:
          vst === 'PENDING' || vst === 'VALIDATED' || vst === 'FAILED' || vst === 'NOT_VALIDATED'
            ? vst
            : 'NOT_VALIDATED',
        testedRevision: String(lv['testedRevision'] ?? revision),
        testedCredentialRevision: lv['testedCredentialRevision']
          ? String(lv['testedCredentialRevision'])
          : null,
        summaryMessage: String(lv['summaryMessage'] ?? lv['message'] ?? ''),
        checkedAt: lv['checkedAt'] ? String(lv['checkedAt']) : null,
      };
    }

    const statusUpper = String(r['status'] ?? 'DRAFT').toUpperCase();
    const status: IntegrationProfileStatus =
      statusUpper === 'DRAFT' ||
      statusUpper === 'CONFIGURED' ||
      statusUpper === 'VALIDATION_PENDING' ||
      statusUpper === 'VALIDATED' ||
      statusUpper === 'ACTIVE' ||
      statusUpper === 'DISABLED' ||
      statusUpper === 'EXPIRED_OR_REVOKED' ||
      statusUpper === 'FAILED'
        ? statusUpper
        : 'DRAFT';

    const opsRaw = Array.isArray(r['allowedOperationIds'])
      ? (r['allowedOperationIds'] as unknown[])
      : Array.isArray(r['permittedOperations'])
        ? (r['permittedOperations'] as unknown[])
        : [];

    return {
      id,
      profileName: String(r['profileName'] ?? r['displayName'] ?? r['name'] ?? providerCode),
      providerCode,
      adapterCode: String(r['adapterCode'] ?? spec?.adapterCode ?? providerCode),
      endpointProfileId: String(
        r['endpointProfileId'] ??
          (environment === 'PRODUCTION'
            ? spec?.productionEndpointProfileId
            : spec?.sandboxEndpointProfileId) ??
          '',
      ),
      domainCategory:
        String(r['domainCategory'] ?? spec?.domainCategory ?? 'GOVERNMENT_STATUTORY') ===
        'CONNECTED_BANKING'
          ? 'CONNECTED_BANKING'
          : 'GOVERNMENT_STATUTORY',
      environment,
      companyId: String(r['companyId'] ?? ''),
      registrationRef: String(r['registrationRef'] ?? r['gstin'] ?? r['registrationBinding'] ?? ''),
      allowedOperationIds: opsRaw.map((o) => String(o)).sort(),
      consentEvidenceRef: String(r['consentEvidenceRef'] ?? ''),
      status,
      credentialRevision: String(r['credentialRevision'] ?? '0'),
      credentialSlots,
      certificateFingerprintSha256: r['certificateFingerprintSha256']
        ? String(r['certificateFingerprintSha256'])
        : null,
      certificateExpiresAt: r['certificateExpiresAt'] ? String(r['certificateExpiresAt']) : null,
      lastValidation,
      revision,
      etag: headerEtag ?? (r['etag'] ? String(r['etag']) : null),
      updatedAt: r['updatedAt'] ? String(r['updatedAt']) : null,
    };
  }

  private normalizeInventoryRow(raw: unknown): GovernmentCapabilityInventoryRow {
    const r = (raw ?? {}) as Record<string, unknown>;
    return {
      draftCapabilityId: String(
        r['draftCapabilityId'] ?? r['draft_capability_id'] ?? r['capabilityId'] ?? r['id'] ?? '',
      ),
      capabilityFamily: String(r['capabilityFamily'] ?? r['capability_family'] ?? ''),
      operationScope: String(r['operationScope'] ?? r['operation_scope'] ?? ''),
      channelClass: String(r['channelClass'] ?? r['channel_class'] ?? ''),
      officialEvidenceStatus: String(
        r['officialEvidenceStatus'] ?? r['official_evidence_status'] ?? '',
      ),
      authProfileRef: String(r['authProfileRef'] ?? r['auth_profile_ref'] ?? ''),
      knownSideEffects: String(r['knownSideEffects'] ?? r['known_side_effects'] ?? ''),
      moneycraftImplementationStatus: String(
        r['moneycraftImplementationStatus'] ??
          r['moneycraft_implementation_status'] ??
          'NOT ESTABLISHED',
      ),
      verificationStatus: String(r['verificationStatus'] ?? r['verification_status'] ?? 'NOT RUN'),
      limitations: String(r['limitations'] ?? ''),
    };
  }
}
