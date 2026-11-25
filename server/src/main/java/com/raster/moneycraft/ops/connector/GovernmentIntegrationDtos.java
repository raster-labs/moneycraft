package com.raster.moneycraft.ops.connector;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Closed DTOs for SUPER_ADMIN Government Statutory & Connected Banking Configuration
 * (Docs 51 & 52, UM-110-04). Contains zero secret values, tokens, or ciphertexts.
 */
public final class GovernmentIntegrationDtos {

    private GovernmentIntegrationDtos() {
    }

    public record CredentialSlotSchemaDto(
        String slotKey,
        String label,
        String secretKind,
        int maxLength,
        boolean required,
        String hint
    ) {
    }

    public record AdmittedProviderSchemaDto(
        String providerCode,
        String adapterCode,
        String displayName,
        String domainCategory,
        String jurisdictionOrService,
        String sandboxEndpointProfileId,
        String productionEndpointProfileId,
        String registrationKind,
        List<String> allowedOperations,
        List<CredentialSlotSchemaDto> credentialSlots,
        boolean safeProbeSupported,
        String safeValidationProbeSummary,
        String probeSideEffectNote
    ) {
    }

    public record CapabilityInventoryRowDto(
        String draftCapabilityId,
        String capabilityFamily,
        String operationScope,
        String channelClass,
        String officialEvidenceStatus,
        String officialSourceKeys,
        String onboardingAccess,
        String authProfileRef,
        String knownSideEffects,
        String referenceHelpEvidenceIds,
        String sourceCodeEvidenceStatus,
        String moneycraftImplementationStatus,
        String verificationStatus,
        String limitations
    ) {
    }

    public record GovernmentInterfaceInventoryResponse(
        String hostDocumentationVersion,
        List<CapabilityInventoryRowDto> capabilities,
        List<AdmittedProviderSchemaDto> providers
    ) {
    }

    public record CredentialSlotMetadataDto(
        String slotKey,
        String status,
        UUID credentialVersionId,
        long versionNo,
        OffsetDateTime updatedAt
    ) {
    }

    public record ValidationOutcomeDto(
        UUID validationId,
        String status,
        String testedRevision,
        String testedCredentialRevision,
        String credentialRevision,
        String probeOperation,
        String probeMode,
        String sideEffectDisclosure,
        String summaryMessage,
        OffsetDateTime checkedAt
    ) {
    }

    public record GovernmentIntegrationProfileDto(
        UUID id,
        String profileCode,
        String profileName,
        String providerCode,
        String adapterCode,
        String endpointProfileId,
        String domainCategory,
        String environment,
        UUID companyId,
        String registrationKind,
        String registrationRef,
        List<String> allowedOperationIds,
        String consentEvidenceRef,
        String status,
        String credentialRevision,
        String activeCredentialRevision,
        List<CredentialSlotMetadataDto> credentialSlots,
        String certificateFingerprintSha256,
        OffsetDateTime certificateExpiresAt,
        ValidationOutcomeDto lastValidation,
        String disabledReason,
        String reconciliationNote,
        String revision,
        String etag,
        OffsetDateTime updatedAt
    ) {
    }

    public record StagedCredentialVersionResponse(
        UUID credentialVersionId,
        UUID profileId,
        String credentialRevision,
        String profileRevision,
        String environmentNamespace,
        List<CredentialSlotMetadataDto> credentialSlots,
        String etag
    ) {
    }

    public record ProfileAuditEntryDto(
        UUID eventId,
        OffsetDateTime timestamp,
        String actor,
        String actorKind,
        String action,
        String revision,
        String result,
        String reason,
        String detail,
        String correlationId
    ) {
    }
}
