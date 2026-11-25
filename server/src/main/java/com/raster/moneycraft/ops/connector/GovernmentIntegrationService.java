package com.raster.moneycraft.ops.connector;

import com.raster.moneycraft.foundation.auth.StepUpAuthService;
import com.raster.moneycraft.foundation.authority.AdminReceiptService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.OidcClockSource;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Domain service for SUPER_ADMIN Government Statutory & Connected Banking Configuration
 * (Docs 51 & 52, UM-110-04). Enforces explicit installation Super Admin PERSON designation,
 * 5-minute recent authentication, target/action/revision-bound single-use step-up proofs,
 * write-only Tink AES256-GCM credential staging, safe probes outside DB locks, and secret-free audit.
 */
@Service
public class GovernmentIntegrationService {

    public static final String OP_CREATE = "government.profile.create";
    public static final String OP_UPDATE = "government.profile.update";
    public static final String OP_STAGE_CREDENTIAL = "government.profile.credential.stage";
    public static final String OP_START_VALIDATION = "government.profile.validation.start";
    public static final String OP_ACTIVATE = "government.profile.activate";
    public static final String OP_DISABLE = "government.profile.disable";

    private static final int MAX_BODY_BYTES = 16_384;
    private static final Duration RECENT_AUTH_WINDOW = Duration.ofMinutes(5);
    private static final Pattern GOV_ETAG_PATTERN =
        Pattern.compile("^\"gov:([0-9a-fA-F-]{36}):([1-9][0-9]*)\"$");
    private static final Pattern GSTIN_PATTERN =
        Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern TAN_PATTERN = Pattern.compile("^[A-Z]{4}[0-9]{5}[A-Z]$");
    private static final Pattern BANK_CORP_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{3,64}$");

    private static final Set<String> ALLOWED_UPSERT_KEYS = Set.of(
        "profileCode",
        "profileName",
        "displayName",
        "providerCode",
        "adapterCode",
        "endpointProfileId",
        "domainCategory",
        "environment",
        "companyId",
        "registrationKind",
        "registrationType",
        "registrationRef",
        "allowedOperationIds",
        "consentEvidenceRef",
        "certificateFingerprintSha256",
        "certificateExpiresAt",
        "stepUpIntentId"
    );
    private static final Set<String> ALLOWED_CREDENTIAL_STAGE_KEYS = Set.of(
        "slots",
        "secretSlots",
        "expectedRevision",
        "stepUpIntentId",
        "action",
        "retirePriorRevision"
    );
    private static final Set<String> ALLOWED_VALIDATION_START_KEYS = Set.of(
        "expectedRevision",
        "expectedProfileRevision",
        "credentialRevision",
        "stepUpIntentId"
    );
    private static final Set<String> ALLOWED_ACTIVATE_KEYS = Set.of(
        "validationId",
        "expectedRevision",
        "stepUpIntentId"
    );
    private static final Set<String> ALLOWED_DISABLE_KEYS = Set.of(
        "reasonCode",
        "reasonDetail",
        "reconciliationNote",
        "revokeActiveCredential",
        "expectedRevision",
        "stepUpIntentId"
    );
    private static final Set<String> ADMITTED_DISABLE_REASONS = Set.of(
        "ROTATION_MAINTENANCE",
        "CONSENT_REVOKED",
        "SUSPECTED_COMPROMISE",
        "PROVIDER_OUTAGE",
        "DECOMMISSIONED"
    );

    private final MoneyCraftProperties properties;
    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final StepUpAuthService stepUpAuthService;
    private final AdminReceiptService adminReceiptService;
    private final TinkCryptoService tinkCryptoService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final OidcClockSource clockSource;
    private final OutboundConnectorRegistry connectorRegistry;
    private final GovernmentInterfaceCatalogue catalogue;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate authorityJdbcTemplate;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate authorityTxTemplate;
    private final TransactionTemplate businessTxTemplate;

    public GovernmentIntegrationService(
        MoneyCraftProperties properties,
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        StepUpAuthService stepUpAuthService,
        AdminReceiptService adminReceiptService,
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer,
        OidcClockSource clockSource,
        OutboundConnectorRegistry connectorRegistry,
        GovernmentInterfaceCatalogue catalogue,
        ObjectMapper objectMapper,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("authorityTransactionManager") PlatformTransactionManager authorityTransactionManager,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.properties = properties;
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.stepUpAuthService = stepUpAuthService;
        this.adminReceiptService = adminReceiptService;
        this.tinkCryptoService = tinkCryptoService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.clockSource = clockSource;
        this.connectorRegistry = connectorRegistry;
        this.catalogue = catalogue;
        this.objectMapper = objectMapper;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.authorityTxTemplate = new TransactionTemplate(authorityTransactionManager);
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public GovernmentIntegrationDtos.GovernmentInterfaceInventoryResponse getInventory() {
        requireSuperAdminWithAudit("INVENTORY_READ", null);
        return new GovernmentIntegrationDtos.GovernmentInterfaceInventoryResponse(
            "1.1.0",
            catalogue.capabilities(),
            catalogue.providerSchemas()
        );
    }

    public List<GovernmentIntegrationDtos.GovernmentIntegrationProfileDto> listProfiles() {
        requireSuperAdminWithAudit("PROFILE_LIST", null);
        UUID installationId = loadActiveInstallationId();
        List<UUID> ids = authorityJdbcTemplate.query(
            """
            SELECT profile_id
              FROM ma_gov_integration_profile
             WHERE installation_id = ?
             ORDER BY updated_at DESC, profile_id ASC
             LIMIT 200
            """,
            (rs, rowNum) -> rs.getObject("profile_id", UUID.class),
            installationId
        );
        List<GovernmentIntegrationDtos.GovernmentIntegrationProfileDto> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            result.add(loadProfileView(installationId, id));
        }
        return List.copyOf(result);
    }

    public ProfileMutationResult getProfile(UUID profileId) {
        requireSuperAdminWithAudit("PROFILE_READ", profileId);
        UUID installationId = loadActiveInstallationId();
        GovernmentIntegrationDtos.GovernmentIntegrationProfileDto view = loadProfileView(installationId, profileId);
        return new ProfileMutationResult(view, view.etag(), false);
    }

    public ProfileMutationResult createProfile(
        String rawIdempotencyKey,
        String headerStepUpIntent,
        byte[] rawBody
    ) {
        MoneyCraftPrincipal actor = requireSuperAdminWithAudit(OP_CREATE, null);
        authorityGuard.assertWritable();
        rbacGuard.requireRecentPersonAuthentication(actor.principalId(), RECENT_AUTH_WINDOW);

        JsonNode root = parseBoundedClosedObject(rawBody, ALLOWED_UPSERT_KEYS);
        String idempotencyKey = validateUuidHeader(rawIdempotencyKey, "Idempotency-Key");
        UUID stepUpIntentId = resolveStepUpIntentId(root, headerStepUpIntent);
        UUID installationId = loadActiveInstallationId();

        byte[] requestHmac = computeRequestHmac(
            OP_CREATE,
            "",
            stepUpIntentId,
            jcsCanonicalizer.canonicalizeJson(new String(rawBody, StandardCharsets.UTF_8))
        );

        ReceiptReplay replay = checkReceiptReplay(installationId, actor.principalId(), OP_CREATE, idempotencyKey, requestHmac);
        if (replay != null) {
            GovernmentIntegrationDtos.GovernmentIntegrationProfileDto dto =
                deserialize(replay.responseJson(), GovernmentIntegrationDtos.GovernmentIntegrationProfileDto.class);
            return new ProfileMutationResult(dto, replay.etag(), true);
        }

        ValidatedDraftInput draft = validateDraftInput(root, null);

        UUID createdId = authorityTxTemplate.execute(status -> {
            try {
                stepUpAuthService.verifyAndConsumeStepUpIntent(
                    stepUpIntentId,
                    actor.principalId(),
                    Set.of("CREATE"),
                    null,
                    null
                );
            } catch (MoneyCraftException ex) {
                appendAudit(
                    installationId,
                    null,
                    actor,
                    draft.companyId(),
                    draft.registrationRef(),
                    draft.providerCode(),
                    draft.environment(),
                    null,
                    OP_CREATE,
                    "DENIED",
                    ex.code(),
                    ex.getMessage()
                );
                throw ex;
            }

            UUID profileId = UUID.randomUUID();
            String profileCode = draft.profileCode() != null
                ? draft.profileCode()
                : buildDefaultProfileCode(draft.providerCode(), draft.environment(), profileId);
            OffsetDateTime nowTs = nowUtc();

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_gov_integration_profile (
                    profile_id, installation_id, profile_code, display_name, provider_family,
                    adapter_code, endpoint_profile_id, environment, company_id,
                    registration_type, registration_ref, allowed_operations, consent_evidence_ref,
                    certificate_fingerprint_sha256, certificate_expires_at, status,
                    last_validation_status, revision, created_by, updated_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'DRAFT', 'NOT_VALIDATED', 1, ?, ?, ?, ?)
                """,
                profileId,
                installationId,
                profileCode,
                draft.profileName(),
                draft.provider().schema().domainCategory(),
                draft.providerCode(),
                draft.endpointProfileId(),
                draft.environment(),
                draft.companyId(),
                draft.dbRegistrationType(),
                draft.registrationRef(),
                serialize(draft.allowedOperations()),
                draft.consentEvidenceRef(),
                draft.certificateFingerprintSha256(),
                draft.certificateExpiresAt(),
                actor.principalId(),
                actor.principalId(),
                nowTs,
                nowTs
            );

            appendAudit(
                installationId,
                profileId,
                actor,
                draft.companyId(),
                draft.registrationRef(),
                draft.providerCode(),
                draft.environment(),
                1L,
                OP_CREATE,
                "SUCCEEDED",
                null,
                "Created disabled " + draft.environment() + " profile draft '" + draft.profileName() + "'."
            );
            return profileId;
        });

        GovernmentIntegrationDtos.GovernmentIntegrationProfileDto view = loadProfileView(installationId, createdId);
        recordReceipt(
            installationId,
            actor.principalId(),
            OP_CREATE,
            "NONE",
            "",
            idempotencyKey,
            requestHmac,
            view.etag(),
            null,
            serialize(view)
        );
        return new ProfileMutationResult(view, view.etag(), false);
    }

    public ProfileMutationResult updateProfile(
        UUID profileId,
        String rawIfMatch,
        String rawIdempotencyKey,
        String headerStepUpIntent,
        byte[] rawBody
    ) {
        MoneyCraftPrincipal actor = requireSuperAdminWithAudit(OP_UPDATE, profileId);
        authorityGuard.assertWritable();
        rbacGuard.requireRecentPersonAuthentication(actor.principalId(), RECENT_AUTH_WINDOW);

        JsonNode root = parseBoundedClosedObject(rawBody, ALLOWED_UPSERT_KEYS);
        String idempotencyKey = validateUuidHeader(rawIdempotencyKey, "Idempotency-Key");
        UUID stepUpIntentId = resolveStepUpIntentId(root, headerStepUpIntent);
        UUID installationId = loadActiveInstallationId();

        byte[] requestHmac = computeRequestHmac(
            OP_UPDATE,
            rawIfMatch,
            stepUpIntentId,
            jcsCanonicalizer.canonicalizeJson(new String(rawBody, StandardCharsets.UTF_8))
        );
        ReceiptReplay replay = checkReceiptReplay(installationId, actor.principalId(), OP_UPDATE, idempotencyKey, requestHmac);
        if (replay != null) {
            GovernmentIntegrationDtos.GovernmentIntegrationProfileDto dto =
                deserialize(replay.responseJson(), GovernmentIntegrationDtos.GovernmentIntegrationProfileDto.class);
            return new ProfileMutationResult(dto, replay.etag(), true);
        }

        long expectedRev = parseAndVerifyProfileIfMatch(rawIfMatch, profileId);
        ValidatedDraftInput draft = validateDraftInput(root, profileId);

        authorityTxTemplate.executeWithoutResult(status -> {
            LockedProfileRow current = lockProfileForUpdate(installationId, profileId, expectedRev);
            if ("ACTIVE".equals(current.status())
                && (!current.adapterCode().equals(draft.providerCode())
                    || !current.environment().equals(draft.environment())
                    || !current.companyId().equals(draft.companyId())
                    || !current.registrationRef().equals(draft.registrationRef()))) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-GOV-ACTIVE-BINDING-IMMUTABLE",
                    "Active Profile Identity Binding Immutable",
                    "Disable the active profile before changing provider, environment, company, or taxpayer registration."
                );
            }

            consumeStepUpOrAuditDeny(
                installationId,
                profileId,
                actor,
                current,
                stepUpIntentId,
                Set.of("UPDATE"),
                OP_UPDATE
            );

            long nextRev = current.revision() + 1L;
            String nextStatus = "ACTIVE".equals(current.status())
                ? "CONFIGURED"
                : (current.stagedCredentialRevision() != null ? "CONFIGURED" : "DRAFT");
            OffsetDateTime nowTs = nowUtc();

            authorityJdbcTemplate.update(
                """
                UPDATE ma_gov_integration_profile
                   SET display_name = ?,
                       provider_family = ?,
                       adapter_code = ?,
                       endpoint_profile_id = ?,
                       environment = ?,
                       company_id = ?,
                       registration_type = ?,
                       registration_ref = ?,
                       allowed_operations = ?::jsonb,
                       consent_evidence_ref = ?,
                       certificate_fingerprint_sha256 = ?,
                       certificate_expires_at = ?,
                       status = ?,
                       last_validation_status = CASE
                           WHEN last_validation_status = 'NOT_VALIDATED' THEN 'NOT_VALIDATED'
                           ELSE 'INVALIDATED'
                       END,
                       revision = ?,
                       updated_by = ?,
                       updated_at = ?
                 WHERE profile_id = ? AND installation_id = ?
                """,
                draft.profileName(),
                draft.provider().schema().domainCategory(),
                draft.providerCode(),
                draft.endpointProfileId(),
                draft.environment(),
                draft.companyId(),
                draft.dbRegistrationType(),
                draft.registrationRef(),
                serialize(draft.allowedOperations()),
                draft.consentEvidenceRef(),
                draft.certificateFingerprintSha256(),
                draft.certificateExpiresAt(),
                nextStatus,
                nextRev,
                actor.principalId(),
                nowTs,
                profileId,
                installationId
            );

            appendAudit(
                installationId,
                profileId,
                actor,
                draft.companyId(),
                draft.registrationRef(),
                draft.providerCode(),
                draft.environment(),
                nextRev,
                OP_UPDATE,
                "SUCCEEDED",
                null,
                "Updated metadata and invalidated prior validation state."
            );
        });

        GovernmentIntegrationDtos.GovernmentIntegrationProfileDto view = loadProfileView(installationId, profileId);
        recordReceipt(
            installationId,
            actor.principalId(),
            OP_UPDATE,
            "UUID",
            profileId.toString(),
            idempotencyKey,
            requestHmac,
            view.etag(),
            null,
            serialize(view)
        );
        return new ProfileMutationResult(view, view.etag(), false);
    }

    public CredentialStageResult stageCredentialVersion(
        UUID profileId,
        String rawIfMatch,
        String rawIdempotencyKey,
        String headerStepUpIntent,
        byte[] rawBody
    ) {
        MoneyCraftPrincipal actor = requireSuperAdminWithAudit(OP_STAGE_CREDENTIAL, profileId);
        authorityGuard.assertWritable();
        rbacGuard.requireRecentPersonAuthentication(actor.principalId(), RECENT_AUTH_WINDOW);

        JsonNode root = parseBoundedClosedObject(rawBody, ALLOWED_CREDENTIAL_STAGE_KEYS);
        String idempotencyKey = validateUuidHeader(rawIdempotencyKey, "Idempotency-Key");
        UUID stepUpIntentId = resolveStepUpIntentId(root, headerStepUpIntent);
        UUID installationId = loadActiveInstallationId();

        Map<String, String> rawSlots = extractSlotSubmissions(root);
        Map<String, String> slotDigests = new LinkedHashMap<>();
        rawSlots.keySet().stream().sorted().forEach(k -> {
            byte[] d = jcsCanonicalizer.sha256(
                ("MC-GOV-SLOT-DIGEST|" + k + "|" + rawSlots.get(k)).getBytes(StandardCharsets.UTF_8)
            );
            slotDigests.put(k, JcsCanonicalizer.toHex(d));
        });

        byte[] requestHmac = computeRequestHmac(
            OP_STAGE_CREDENTIAL,
            rawIfMatch,
            stepUpIntentId,
            jcsCanonicalizer.canonicalizeObject(Map.of("slotDigests", slotDigests))
        );

        ReceiptReplay replay = checkReceiptReplay(
            installationId,
            actor.principalId(),
            OP_STAGE_CREDENTIAL,
            idempotencyKey,
            requestHmac
        );
        if (replay != null) {
            GovernmentIntegrationDtos.StagedCredentialVersionResponse dto =
                deserialize(replay.responseJson(), GovernmentIntegrationDtos.StagedCredentialVersionResponse.class);
            return new CredentialStageResult(dto, replay.etag(), true);
        }

        long expectedRev = parseAndVerifyProfileIfMatch(rawIfMatch, profileId);

        GovernmentIntegrationDtos.StagedCredentialVersionResponse stagedResp = authorityTxTemplate.execute(status -> {
            LockedProfileRow current = lockProfileForUpdate(installationId, profileId, expectedRev);
            consumeStepUpOrAuditDeny(
                installationId,
                profileId,
                actor,
                current,
                stepUpIntentId,
                Set.of("STAGE_CREDENTIAL", "ROTATE"),
                OP_STAGE_CREDENTIAL
            );

            GovernmentInterfaceCatalogue.AdmittedProviderDefinition provider =
                catalogue.findProvider(current.adapterCode());
            if (provider == null) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-GOV-PROVIDER-UNSUPPORTED",
                    "Unsupported Provider Schema",
                    "Provider '" + current.adapterCode() + "' is not admitted."
                );
            }

            Map<String, GovernmentIntegrationDtos.CredentialSlotSchemaDto> allowedSlots = new LinkedHashMap<>();
            for (GovernmentIntegrationDtos.CredentialSlotSchemaDto s : provider.schema().credentialSlots()) {
                allowedSlots.put(s.slotKey(), s);
            }

            for (Map.Entry<String, String> submitted : rawSlots.entrySet()) {
                GovernmentIntegrationDtos.CredentialSlotSchemaDto schema = allowedSlots.get(submitted.getKey());
                if (schema == null) {
                    throw new MoneyCraftException(
                        HttpStatus.BAD_REQUEST,
                        "MC-GOV-SLOT-UNKNOWN",
                        "Unknown Credential Slot",
                        "Credential slot '" + submitted.getKey() + "' is not part of provider '"
                            + current.adapterCode() + "' schema."
                    );
                }
                String value = submitted.getValue();
                if (value == null || value.isBlank() || value.length() > schema.maxLength()) {
                    throw new MoneyCraftException(
                        HttpStatus.BAD_REQUEST,
                        "MC-GOV-SLOT-LENGTH-INVALID",
                        "Invalid Credential Slot Length",
                        "Credential slot '" + submitted.getKey() + "' must be 1.." + schema.maxLength() + " chars."
                    );
                }
            }

            Long maxCredRev = authorityJdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(credential_revision), 0) FROM ma_gov_credential_version WHERE profile_id = ?",
                Long.class,
                profileId
            );
            long nextCredRev = (maxCredRev == null ? 0L : maxCredRev) + 1L;

            // Merge with any previously staged unrevoked slots so partial slot rotation preserves sibling slots
            Map<String, String> mergedSecretMap = new LinkedHashMap<>();
            if (current.stagedCredentialRevision() != null) {
                Map<String, String> priorSecrets = loadDecryptedSlotsIfUnrevoked(
                    installationId,
                    current,
                    current.stagedCredentialRevision()
                );
                mergedSecretMap.putAll(priorSecrets);
            }
            mergedSecretMap.putAll(rawSlots);

            for (GovernmentIntegrationDtos.CredentialSlotSchemaDto schema : provider.schema().credentialSlots()) {
                if (schema.required() && !mergedSecretMap.containsKey(schema.slotKey())) {
                    throw new MoneyCraftException(
                        HttpStatus.BAD_REQUEST,
                        "MC-GOV-SLOT-REQUIRED",
                        "Required Credential Slot Missing",
                        "Required credential slot '" + schema.slotKey() + "' must be configured."
                    );
                }
            }

            byte[] ad = buildCredentialEnvelopeAd(
                installationId,
                profileId,
                current.environment(),
                current.companyId(),
                current.registrationRef(),
                nextCredRev
            );
            String canonicalSecrets = jcsCanonicalizer.canonicalizeObject(new LinkedHashMap<>(mergedSecretMap));
            byte[] cipherBytes = tinkCryptoService.encryptGcm(ad, canonicalSecrets.getBytes(StandardCharsets.UTF_8));
            UUID activeGcmKeyId = authorityJdbcTemplate.queryForObject(
                """
                SELECT id FROM ma_key
                 WHERE installation_id = ? AND key_purpose = 'GCM_PAYLOAD' AND company_scope IS NULL AND state = 'ACTIVE'
                 ORDER BY key_generation DESC LIMIT 1
                """,
                UUID.class,
                installationId
            );

            OffsetDateTime nowTs = nowUtc();
            UUID credVersionId = UUID.randomUUID();
            List<Map<String, Object>> metadataEntries = new ArrayList<>();
            List<GovernmentIntegrationDtos.CredentialSlotMetadataDto> slotDtos = new ArrayList<>();
            for (GovernmentIntegrationDtos.CredentialSlotSchemaDto schema : provider.schema().credentialSlots()) {
                if (mergedSecretMap.containsKey(schema.slotKey())) {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("slotKey", schema.slotKey());
                    meta.put("status", "CONFIGURED");
                    meta.put("secretKind", schema.secretKind());
                    meta.put("updatedAt", nowTs.toString());
                    metadataEntries.add(meta);
                    slotDtos.add(new GovernmentIntegrationDtos.CredentialSlotMetadataDto(
                        schema.slotKey(),
                        "CONFIGURED",
                        credVersionId,
                        nextCredRev,
                        nowTs
                    ));
                }
            }

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_gov_credential_version (
                    credential_version_id, profile_id, credential_revision, environment_namespace,
                    key_id, secret_envelope_gcm, slot_metadata_json, state, staged_by, staged_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'STAGED', ?, ?)
                """,
                credVersionId,
                profileId,
                nextCredRev,
                current.environment(),
                activeGcmKeyId,
                cipherBytes,
                serialize(metadataEntries),
                actor.principalId(),
                nowTs
            );

            long nextProfileRev = current.revision() + 1L;
            String nextProfileStatus = "ACTIVE".equals(current.status()) ? "ACTIVE" : "CONFIGURED";
            authorityJdbcTemplate.update(
                """
                UPDATE ma_gov_integration_profile
                   SET staged_credential_revision = ?,
                       status = ?,
                       last_validation_status = CASE
                           WHEN status = 'ACTIVE' THEN last_validation_status
                           ELSE 'NOT_VALIDATED'
                       END,
                       revision = ?,
                       updated_by = ?,
                       updated_at = ?
                 WHERE profile_id = ? AND installation_id = ?
                """,
                nextCredRev,
                nextProfileStatus,
                nextProfileRev,
                actor.principalId(),
                nowTs,
                profileId,
                installationId
            );

            appendAudit(
                installationId,
                profileId,
                actor,
                current.companyId(),
                current.registrationRef(),
                current.adapterCode(),
                current.environment(),
                nextProfileRev,
                OP_STAGE_CREDENTIAL,
                "SUCCEEDED",
                null,
                "Staged Tink-encrypted credential revision " + nextCredRev
                    + " (" + slotDtos.size() + " configured slots; zero secret readback)."
            );

            String nextEtag = formatProfileEtag(profileId, nextProfileRev);
            return new GovernmentIntegrationDtos.StagedCredentialVersionResponse(
                credVersionId,
                profileId,
                String.valueOf(nextCredRev),
                String.valueOf(nextProfileRev),
                current.environment(),
                List.copyOf(slotDtos),
                nextEtag
            );
        });

        recordReceipt(
            installationId,
            actor.principalId(),
            OP_STAGE_CREDENTIAL,
            "UUID",
            profileId.toString(),
            idempotencyKey,
            requestHmac,
            stagedResp.etag(),
            null,
            serialize(stagedResp)
        );
        return new CredentialStageResult(stagedResp, stagedResp.etag(), false);
    }

    public ValidationStartResult startSafeValidation(
        UUID profileId,
        String rawIfMatch,
        String rawIdempotencyKey,
        String headerStepUpIntent,
        byte[] rawBody
    ) {
        MoneyCraftPrincipal actor = requireSuperAdminWithAudit(OP_START_VALIDATION, profileId);
        authorityGuard.assertWritable();
        rbacGuard.requireRecentPersonAuthentication(actor.principalId(), RECENT_AUTH_WINDOW);

        JsonNode root = parseBoundedClosedObject(rawBody, ALLOWED_VALIDATION_START_KEYS);
        String idempotencyKey = validateUuidHeader(rawIdempotencyKey, "Idempotency-Key");
        UUID stepUpIntentId = resolveStepUpIntentId(root, headerStepUpIntent);
        UUID installationId = loadActiveInstallationId();

        byte[] requestHmac = computeRequestHmac(
            OP_START_VALIDATION,
            rawIfMatch,
            stepUpIntentId,
            jcsCanonicalizer.canonicalizeJson(new String(rawBody, StandardCharsets.UTF_8))
        );
        ReceiptReplay replay = checkReceiptReplay(
            installationId,
            actor.principalId(),
            OP_START_VALIDATION,
            idempotencyKey,
            requestHmac
        );
        if (replay != null) {
            GovernmentIntegrationDtos.ValidationOutcomeDto dto =
                deserialize(replay.responseJson(), GovernmentIntegrationDtos.ValidationOutcomeDto.class);
            return new ValidationStartResult(dto, replay.etag(), replay.location(), true);
        }

        long expectedRev = parseAndVerifyProfileIfMatch(rawIfMatch, profileId);
        Long requestedCredRev = parseOptionalPositiveLong(root.get("credentialRevision"));

        // Phase 1: Durable PENDING validation-job commit inside short Authority TX
        PreparedProbeContext prepared = authorityTxTemplate.execute(status -> {
            LockedProfileRow current = lockProfileForUpdate(installationId, profileId, expectedRev);
            consumeStepUpOrAuditDeny(
                installationId,
                profileId,
                actor,
                current,
                stepUpIntentId,
                Set.of("VALIDATE"),
                OP_START_VALIDATION
            );

            long credRev = requestedCredRev != null
                ? requestedCredRev
                : (current.stagedCredentialRevision() != null ? current.stagedCredentialRevision() : 0L);
            if (credRev <= 0L) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-GOV-CREDENTIALS-MISSING",
                    "Credential Revision Not Staged",
                    "Stage write-only credentials before running connection validation."
                );
            }

            Map<String, String> decryptedSlots = loadDecryptedSlotsForValidation(installationId, current, credRev);
            GovernmentInterfaceCatalogue.AdmittedProviderDefinition provider =
                Objects.requireNonNull(catalogue.findProvider(current.adapterCode()));

            UUID validationId = UUID.randomUUID();
            OffsetDateTime startedAt = nowUtc();
            String probeOp = provider.schema().safeValidationProbeSummary();
            String disclosure = provider.schema().probeSideEffectNote();

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_gov_validation_job (
                    validation_id, profile_id, profile_revision, credential_revision,
                    probe_operation, probe_mode, side_effect_disclosure, state,
                    sanitised_summary, initiated_by, started_at
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, 'PENDING', 'Validation in progress', ?, ?)
                """,
                validationId,
                profileId,
                current.revision(),
                credRev,
                probeOp,
                disclosure,
                actor.principalId(),
                startedAt
            );

            if (!"ACTIVE".equals(current.status())) {
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_gov_integration_profile
                       SET status = 'VALIDATION_PENDING',
                           last_validation_id = ?,
                           last_validation_status = 'PENDING',
                           updated_at = ?
                     WHERE profile_id = ?
                    """,
                    validationId,
                    startedAt,
                    profileId
                );
            }

            return new PreparedProbeContext(
                validationId,
                current,
                credRev,
                provider,
                decryptedSlots
            );
        });

        if (prepared == null) {
            throw new IllegalStateException("Validation preparation returned null");
        }

        // Phase 2: Execute safe non-mutating probe strictly OUTSIDE DB locks (Doc 52 §3)
        ProbeExecutionOutcome probeOutcome = executeSafeProbeOutsideLocks(prepared);

        // Phase 3: Re-verify authority & revision before committing validation job result
        FinalizedValidation finalized = authorityTxTemplate.execute(status -> {
            authorityGuard.assertWritable();
            OffsetDateTime completedAt = nowUtc();

            authorityJdbcTemplate.update(
                """
                UPDATE ma_gov_validation_job
                   SET probe_mode = ?,
                       state = ?,
                       sanitised_summary = ?,
                       sanitised_error_code = ?,
                       http_status = ?,
                       completed_at = ?
                 WHERE validation_id = ?
                """,
                probeOutcome.probeMode(),
                probeOutcome.dbState(),
                probeOutcome.sanitisedSummary(),
                probeOutcome.errorCode(),
                probeOutcome.httpStatus(),
                completedAt,
                prepared.validationId()
            );

            List<LockedProfileRow> currentRows = authorityJdbcTemplate.query(
                """
                SELECT profile_id, profile_code, display_name, adapter_code, endpoint_profile_id,
                       environment, company_id, registration_type, registration_ref,
                       status, active_credential_revision, staged_credential_revision, revision
                  FROM ma_gov_integration_profile
                 WHERE profile_id = ? AND installation_id = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> mapLockedRow(rs),
                profileId,
                installationId
            );
            LockedProfileRow latest = currentRows.getFirst();

            boolean stillSameRevision = latest.revision() == prepared.profile().revision()
                && Objects.equals(latest.stagedCredentialRevision(), prepared.credentialRevision());

            if (stillSameRevision) {
                String nextProfileStatus = latest.status();
                if (!"ACTIVE".equals(latest.status())) {
                    nextProfileStatus = switch (probeOutcome.dbState()) {
                        case "SUCCEEDED" -> "VALIDATED";
                        case "FAILED" -> "FAILED";
                        default -> "CONFIGURED";
                    };
                }
                String lastValStatus = switch (probeOutcome.dbState()) {
                    case "SUCCEEDED" -> "SUCCEEDED";
                    case "FAILED" -> "FAILED";
                    case "UNCERTAIN" -> "UNCERTAIN";
                    default -> "NOT_AVAILABLE";
                };
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_gov_integration_profile
                       SET status = ?,
                           last_validation_id = ?,
                           last_validation_status = ?,
                           last_validated_revision = ?,
                           last_validated_credential_revision = ?,
                           last_validated_at = ?,
                           updated_at = ?
                     WHERE profile_id = ?
                    """,
                    nextProfileStatus,
                    prepared.validationId(),
                    lastValStatus,
                    latest.revision(),
                    prepared.credentialRevision(),
                    completedAt,
                    completedAt,
                    profileId
                );
            }

            appendAudit(
                installationId,
                profileId,
                actor,
                latest.companyId(),
                latest.registrationRef(),
                latest.adapterCode(),
                latest.environment(),
                latest.revision(),
                OP_START_VALIDATION,
                probeOutcome.auditOutcome(),
                probeOutcome.errorCode(),
                probeOutcome.sanitisedSummary()
            );

            String wireStatus = mapValidationDbStateToWire(probeOutcome.dbState());
            GovernmentIntegrationDtos.ValidationOutcomeDto outcomeDto =
                new GovernmentIntegrationDtos.ValidationOutcomeDto(
                    prepared.validationId(),
                    wireStatus,
                    String.valueOf(prepared.profile().revision()),
                    String.valueOf(prepared.credentialRevision()),
                    String.valueOf(prepared.credentialRevision()),
                    prepared.provider().schema().safeValidationProbeSummary(),
                    probeOutcome.probeMode(),
                    prepared.provider().schema().probeSideEffectNote(),
                    probeOutcome.sanitisedSummary(),
                    completedAt
                );
            return new FinalizedValidation(outcomeDto, formatProfileEtag(profileId, latest.revision()));
        });

        String location = "/api/v1/admin/government-integration-profiles/" + profileId
            + "/validations/" + prepared.validationId();
        recordReceipt(
            installationId,
            actor.principalId(),
            OP_START_VALIDATION,
            "UUID",
            profileId.toString(),
            idempotencyKey,
            requestHmac,
            finalized.etag(),
            location,
            serialize(finalized.outcome())
        );
        return new ValidationStartResult(finalized.outcome(), finalized.etag(), location, false);
    }

    public GovernmentIntegrationDtos.ValidationOutcomeDto getValidationJob(UUID profileId, UUID validationId) {
        requireSuperAdminWithAudit("VALIDATION_READ", profileId);
        List<GovernmentIntegrationDtos.ValidationOutcomeDto> rows = authorityJdbcTemplate.query(
            """
            SELECT validation_id, profile_revision, credential_revision, probe_operation,
                   probe_mode, side_effect_disclosure, state, sanitised_summary,
                   COALESCE(completed_at, started_at) AS checked_at
              FROM ma_gov_validation_job
             WHERE profile_id = ? AND validation_id = ?
            """,
            (rs, rowNum) -> {
                String rev = String.valueOf(rs.getLong("profile_revision"));
                String credRev = String.valueOf(rs.getLong("credential_revision"));
                return new GovernmentIntegrationDtos.ValidationOutcomeDto(
                    rs.getObject("validation_id", UUID.class),
                    mapValidationDbStateToWire(rs.getString("state")),
                    rev,
                    credRev,
                    credRev,
                    rs.getString("probe_operation"),
                    rs.getString("probe_mode"),
                    rs.getString("side_effect_disclosure"),
                    rs.getString("sanitised_summary"),
                    rs.getObject("checked_at", OffsetDateTime.class)
                );
            },
            profileId,
            validationId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-GOV-VALIDATION-NOT-FOUND",
                "Validation Record Not Found",
                "Validation job " + validationId + " was not found for profile " + profileId + "."
            );
        }
        return rows.getFirst();
    }

    public ProfileMutationResult activateProfile(
        UUID profileId,
        String rawIfMatch,
        String rawIdempotencyKey,
        String headerStepUpIntent,
        byte[] rawBody
    ) {
        MoneyCraftPrincipal actor = requireSuperAdminWithAudit(OP_ACTIVATE, profileId);
        authorityGuard.assertWritable();
        rbacGuard.requireRecentPersonAuthentication(actor.principalId(), RECENT_AUTH_WINDOW);

        JsonNode root = parseBoundedClosedObject(rawBody, ALLOWED_ACTIVATE_KEYS);
        String idempotencyKey = validateUuidHeader(rawIdempotencyKey, "Idempotency-Key");
        UUID stepUpIntentId = resolveStepUpIntentId(root, headerStepUpIntent);
        UUID validationId = parseRequiredUuidField(root, "validationId");
        UUID installationId = loadActiveInstallationId();

        byte[] requestHmac = computeRequestHmac(
            OP_ACTIVATE,
            rawIfMatch,
            stepUpIntentId,
            jcsCanonicalizer.canonicalizeJson(new String(rawBody, StandardCharsets.UTF_8))
        );
        ReceiptReplay replay = checkReceiptReplay(installationId, actor.principalId(), OP_ACTIVATE, idempotencyKey, requestHmac);
        if (replay != null) {
            GovernmentIntegrationDtos.GovernmentIntegrationProfileDto dto =
                deserialize(replay.responseJson(), GovernmentIntegrationDtos.GovernmentIntegrationProfileDto.class);
            return new ProfileMutationResult(dto, replay.etag(), true);
        }

        long expectedRev = parseAndVerifyProfileIfMatch(rawIfMatch, profileId);

        authorityTxTemplate.executeWithoutResult(status -> {
            LockedProfileRow current = lockProfileForUpdate(installationId, profileId, expectedRev);
            consumeStepUpOrAuditDeny(
                installationId,
                profileId,
                actor,
                current,
                stepUpIntentId,
                Set.of("ACTIVATE"),
                OP_ACTIVATE
            );

            List<ValidationCheckRow> valRows = authorityJdbcTemplate.query(
                """
                SELECT profile_revision, credential_revision, state
                  FROM ma_gov_validation_job
                 WHERE validation_id = ? AND profile_id = ?
                """,
                (rs, rowNum) -> new ValidationCheckRow(
                    rs.getLong("profile_revision"),
                    rs.getLong("credential_revision"),
                    rs.getString("state")
                ),
                validationId,
                profileId
            );
            if (valRows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-GOV-VALIDATION-NOT-FOUND",
                    "Validation Proof Not Found",
                    "Referenced validationId does not exist for this profile."
                );
            }
            ValidationCheckRow val = valRows.getFirst();
            if (!"SUCCEEDED".equals(val.state())
                || val.profileRevision() != current.revision()
                || current.stagedCredentialRevision() == null
                || val.credentialRevision() != current.stagedCredentialRevision()) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-GOV-VALIDATION-STALE",
                    "Current Successful Validation Required",
                    "Activation requires a SUCCEEDED validation matching the exact current profile and staged credential revision."
                );
            }

            List<String> credStates = authorityJdbcTemplate.query(
                """
                SELECT state FROM ma_gov_credential_version
                 WHERE profile_id = ? AND credential_revision = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> rs.getString("state"),
                profileId,
                val.credentialRevision()
            );
            if (credStates.isEmpty()
                || "REVOKED".equals(credStates.getFirst())
                || "COMPROMISED".equals(credStates.getFirst())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-GOV-CREDENTIAL-REVOKED",
                    "Revoked Credential Cannot Be Activated",
                    "Revoked or compromised credential versions cannot be activated or restored."
                );
            }

            OffsetDateTime nowTs = nowUtc();
            authorityJdbcTemplate.update(
                """
                UPDATE ma_gov_credential_version
                   SET state = 'RETIRED',
                       retired_at = ?
                 WHERE profile_id = ?
                   AND state = 'ACTIVE'
                   AND credential_revision <> ?
                """,
                nowTs,
                profileId,
                val.credentialRevision()
            );
            authorityJdbcTemplate.update(
                """
                UPDATE ma_gov_credential_version
                   SET state = 'ACTIVE',
                       activated_at = ?
                 WHERE profile_id = ?
                   AND credential_revision = ?
                """,
                nowTs,
                profileId,
                val.credentialRevision()
            );

            long nextRev = current.revision() + 1L;
            authorityJdbcTemplate.update(
                """
                UPDATE ma_gov_integration_profile
                   SET status = 'ACTIVE',
                       active_credential_revision = ?,
                       disabled_reason = NULL,
                       revision = ?,
                       updated_by = ?,
                       updated_at = ?
                 WHERE profile_id = ? AND installation_id = ?
                """,
                val.credentialRevision(),
                nextRev,
                actor.principalId(),
                nowTs,
                profileId,
                installationId
            );

            appendAudit(
                installationId,
                profileId,
                actor,
                current.companyId(),
                current.registrationRef(),
                current.adapterCode(),
                current.environment(),
                nextRev,
                OP_ACTIVATE,
                "SUCCEEDED",
                null,
                "Atomically activated " + current.environment() + " profile with credential revision "
                    + val.credentialRevision() + "."
            );
        });

        GovernmentIntegrationDtos.GovernmentIntegrationProfileDto view = loadProfileView(installationId, profileId);
        recordReceipt(
            installationId,
            actor.principalId(),
            OP_ACTIVATE,
            "UUID",
            profileId.toString(),
            idempotencyKey,
            requestHmac,
            view.etag(),
            null,
            serialize(view)
        );
        return new ProfileMutationResult(view, view.etag(), false);
    }

    public ProfileMutationResult disableProfile(
        UUID profileId,
        String rawIfMatch,
        String rawIdempotencyKey,
        String headerStepUpIntent,
        byte[] rawBody
    ) {
        MoneyCraftPrincipal actor = requireSuperAdminWithAudit(OP_DISABLE, profileId);
        authorityGuard.assertWritable();
        rbacGuard.requireRecentPersonAuthentication(actor.principalId(), RECENT_AUTH_WINDOW);

        JsonNode root = parseBoundedClosedObject(rawBody, ALLOWED_DISABLE_KEYS);
        String idempotencyKey = validateUuidHeader(rawIdempotencyKey, "Idempotency-Key");
        UUID stepUpIntentId = resolveStepUpIntentId(root, headerStepUpIntent);
        String reasonCode = requireBoundedText(root, "reasonCode", 64).toUpperCase(Locale.ROOT);
        if (!ADMITTED_DISABLE_REASONS.contains(reasonCode)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-DISABLE-REASON-INVALID",
                "Invalid Disable Reason Code",
                "reasonCode must be one of " + ADMITTED_DISABLE_REASONS + "."
            );
        }
        String note = optionalBoundedText(root, "reasonDetail", 512);
        if (note == null) {
            note = optionalBoundedText(root, "reconciliationNote", 512);
        }
        if (note == null) {
            note = "Blocks new dispatch; already submitted statutory actions retain submitted revision for reconciliation.";
        }
        boolean explicitRevoke = root.has("revokeActiveCredential") && root.get("revokeActiveCredential").asBoolean(false);
        boolean revokeCredentials = explicitRevoke
            || "CONSENT_REVOKED".equals(reasonCode)
            || "SUSPECTED_COMPROMISE".equals(reasonCode);

        UUID installationId = loadActiveInstallationId();
        byte[] requestHmac = computeRequestHmac(
            OP_DISABLE,
            rawIfMatch,
            stepUpIntentId,
            jcsCanonicalizer.canonicalizeJson(new String(rawBody, StandardCharsets.UTF_8))
        );
        ReceiptReplay replay = checkReceiptReplay(installationId, actor.principalId(), OP_DISABLE, idempotencyKey, requestHmac);
        if (replay != null) {
            GovernmentIntegrationDtos.GovernmentIntegrationProfileDto dto =
                deserialize(replay.responseJson(), GovernmentIntegrationDtos.GovernmentIntegrationProfileDto.class);
            return new ProfileMutationResult(dto, replay.etag(), true);
        }

        long expectedRev = parseAndVerifyProfileIfMatch(rawIfMatch, profileId);
        String finalNote = note;

        authorityTxTemplate.executeWithoutResult(status -> {
            LockedProfileRow current = lockProfileForUpdate(installationId, profileId, expectedRev);
            consumeStepUpOrAuditDeny(
                installationId,
                profileId,
                actor,
                current,
                stepUpIntentId,
                Set.of("DISABLE"),
                OP_DISABLE
            );

            OffsetDateTime nowTs = nowUtc();
            String nextDbStatus = revokeCredentials ? "EXPIRED_REVOKED" : "DISABLED";
            if (revokeCredentials) {
                String credState = "SUSPECTED_COMPROMISE".equals(reasonCode) ? "COMPROMISED" : "REVOKED";
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_gov_credential_version
                       SET state = ?,
                           retired_at = COALESCE(retired_at, ?)
                     WHERE profile_id = ?
                       AND state IN ('ACTIVE', 'STAGED')
                    """,
                    credState,
                    nowTs,
                    profileId
                );
            }

            long nextRev = current.revision() + 1L;
            authorityJdbcTemplate.update(
                """
                UPDATE ma_gov_integration_profile
                   SET status = ?,
                       disabled_reason = ?,
                       reconciliation_note = ?,
                       revision = ?,
                       updated_by = ?,
                       updated_at = ?
                 WHERE profile_id = ? AND installation_id = ?
                """,
                nextDbStatus,
                reasonCode,
                finalNote,
                nextRev,
                actor.principalId(),
                nowTs,
                profileId,
                installationId
            );

            appendAudit(
                installationId,
                profileId,
                actor,
                current.companyId(),
                current.registrationRef(),
                current.adapterCode(),
                current.environment(),
                nextRev,
                OP_DISABLE,
                "SUCCEEDED",
                reasonCode,
                finalNote
            );
        });

        GovernmentIntegrationDtos.GovernmentIntegrationProfileDto view = loadProfileView(installationId, profileId);
        recordReceipt(
            installationId,
            actor.principalId(),
            OP_DISABLE,
            "UUID",
            profileId.toString(),
            idempotencyKey,
            requestHmac,
            view.etag(),
            null,
            serialize(view)
        );
        return new ProfileMutationResult(view, view.etag(), false);
    }

    public List<GovernmentIntegrationDtos.ProfileAuditEntryDto> getProfileAudit(UUID profileId, int limit) {
        requireSuperAdminWithAudit("AUDIT_READ", profileId);
        int boundedLimit = Math.max(1, Math.min(200, limit));
        UUID installationId = loadActiveInstallationId();

        Integer count = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ma_gov_integration_profile WHERE profile_id = ? AND installation_id = ?",
            Integer.class,
            profileId,
            installationId
        );
        if (count == null || count == 0) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-GOV-PROFILE-NOT-FOUND",
                "Integration Profile Not Found",
                "Profile " + profileId + " does not exist."
            );
        }

        return authorityJdbcTemplate.query(
            """
            SELECT a.event_id, a.recorded_at, COALESCE(p.username::text, a.actor_principal_id::text, 'SYSTEM') AS actor_label,
                   a.actor_kind, a.action, COALESCE(a.profile_revision, 0) AS rev,
                   a.outcome, a.reason_code, a.sanitised_detail, a.correlation_id
              FROM ma_gov_audit_event a
              LEFT JOIN ma_principal p ON p.principal_id = a.actor_principal_id
             WHERE a.installation_id = ?
               AND (a.profile_id = ? OR a.profile_id IS NULL)
             ORDER BY a.audit_seq DESC
             LIMIT ?
            """,
            (rs, rowNum) -> new GovernmentIntegrationDtos.ProfileAuditEntryDto(
                rs.getObject("event_id", UUID.class),
                rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getString("actor_label"),
                rs.getString("actor_kind"),
                rs.getString("action"),
                String.valueOf(rs.getLong("rev")),
                rs.getString("outcome"),
                rs.getString("reason_code"),
                rs.getString("sanitised_detail"),
                rs.getString("correlation_id")
            ),
            installationId,
            profileId,
            boundedLimit
        );
    }

    private ProbeExecutionOutcome executeSafeProbeOutsideLocks(PreparedProbeContext prepared) {
        Collection<String> secretsToScrub = prepared.decryptedSlots().values();
        if (!prepared.provider().schema().safeProbeSupported()) {
            return new ProbeExecutionOutcome(
                "NO_SAFE_PROBE_ADMITTED",
                "NOT_VALIDATED",
                "ALLOWED",
                "MC-GOV-PROBE-UNAVAILABLE",
                null,
                "No safe automated probe is admitted for " + prepared.profile().adapterCode()
                    + "; profile remains NOT_VALIDATED."
            );
        }

        if (!connectorRegistry.isConnectorSpiEnabled()) {
            if ("PRODUCTION".equals(prepared.profile().environment())) {
                return new ProbeExecutionOutcome(
                    "AIRGAPPED_SPI_DISABLED",
                    "NOT_VALIDATED",
                    "FAILED",
                    "MC-CONNECTOR-DISABLED",
                    null,
                    "Outbound Connector SPI is disabled; PRODUCTION profile cannot be validated without live safe probe."
                );
            }
            return new ProbeExecutionOutcome(
                "SANDBOX_ENVELOPE_AND_SCHEMA_SELF_CHECK",
                "SUCCEEDED",
                "SUCCEEDED",
                null,
                200,
                "Verified SANDBOX Tink credential envelope, registration binding, and closed provider schema (air-gapped safe check; zero statutory/payment mutation)."
            );
        }

        String endpointProfileId = prepared.profile().endpointProfileId();
        String overrideUri = properties.getConnector().getEndpointProfileOverrides().get(endpointProfileId);
        URI targetUri = overrideUri != null && !overrideUri.isBlank()
            ? URI.create(overrideUri.trim())
            : prepared.provider().defaultProbeUri(prepared.profile().environment());

        try {
            OutboundConnectorAdapter adapter = connectorRegistry.requireEnabledConnector(
                prepared.provider().connectorCode(),
                targetUri
            );
            if (!(adapter instanceof StatutoryConnectorHttpAdapter httpAdapter)) {
                return new ProbeExecutionOutcome(
                    "ADAPTER_NON_HTTP",
                    "NOT_VALIDATED",
                    "FAILED",
                    "MC-CONNECTOR-UNSUPPORTED",
                    null,
                    "Registered connector adapter does not expose HTTP safe probe."
                );
            }

            Map<String, String> headers = Map.of(
                "X-MoneyCraft-Probe-Mode", "SAFE_HEALTH_AUTH_ONLY",
                "X-MoneyCraft-Environment", prepared.profile().environment()
            );
            StatutoryHttpResponse resp = httpAdapter.getSafeProbe(targetUri, headers, Duration.ofSeconds(5));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return new ProbeExecutionOutcome(
                    "LIVE_HTTP_SAFE_GET_PROBE",
                    "SUCCEEDED",
                    "SUCCEEDED",
                    null,
                    resp.statusCode(),
                    "Safe read-only probe succeeded against approved endpoint profile "
                        + endpointProfileId + " (HTTP " + resp.statusCode() + "; zero remote mutation)."
                );
            }
            String scrubbedDetail = redactSecrets(
                "Upstream probe rejected connection with HTTP " + resp.statusCode() + ": " + safeTruncate(resp.body(), 180),
                secretsToScrub
            );
            return new ProbeExecutionOutcome(
                "LIVE_HTTP_SAFE_GET_PROBE",
                "FAILED",
                "FAILED",
                "MC-GOV-PROBE-HTTP-" + resp.statusCode(),
                resp.statusCode(),
                scrubbedDetail
            );
        } catch (MoneyCraftException ex) {
            String scrubbedMsg = redactSecrets(ex.getMessage(), secretsToScrub);
            if ("MC-CONNECTOR-TIMEOUT".equals(ex.code())) {
                return new ProbeExecutionOutcome(
                    "LIVE_HTTP_SAFE_GET_PROBE",
                    "UNCERTAIN",
                    "UNCERTAIN",
                    ex.code(),
                    504,
                    "Probe timed out with uncertain remote outcome; reconcile before retrying (" + scrubbedMsg + ")."
                );
            }
            return new ProbeExecutionOutcome(
                "LIVE_HTTP_SAFE_GET_PROBE",
                "FAILED",
                "FAILED",
                ex.code(),
                ex.status().value(),
                scrubbedMsg
            );
        }
    }

    public static String redactSecrets(String rawMessage, Collection<String> secrets) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "Provider error (details redacted).";
        }
        String sanitised = rawMessage;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && secret.length() >= 3) {
                    sanitised = sanitised.replace(secret, "[REDACTED]");
                }
            }
        }
        return safeTruncate(sanitised, 480);
    }

    private MoneyCraftPrincipal requireSuperAdminWithAudit(String action, UUID profileId) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        if (!"PERSON".equals(principal.kind())
            || principal.isServiceKeyAuthenticated()
            || !principal.superAdmin()) {
            List<UUID> stateIds = authorityJdbcTemplate.query(
                "SELECT installation_id FROM ma_state WHERE singleton = 1",
                (rs, rowNum) -> rs.getObject("installation_id", UUID.class)
            );
            if (!stateIds.isEmpty()) {
                authorityJdbcTemplate.update(
                    """
                    INSERT INTO ma_gov_audit_event (
                        event_id, installation_id, profile_id, actor_principal_id, actor_kind,
                        action, outcome, reason_code, sanitised_detail, correlation_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'DENIED', 'MC-SUPER-ADMIN-REQUIRED', ?, ?)
                    """,
                    UUID.randomUUID(),
                    stateIds.getFirst(),
                    profileId,
                    principal.principalId(),
                    principal.kind(),
                    action,
                    "Denied non-Super-Admin access attempt on protected government integration surface.",
                    UUID.randomUUID().toString()
                );
            }
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-SUPER-ADMIN-REQUIRED",
                "Super Admin Designation Required",
                "Only an enabled PERSON holding an active installation Super Admin designation may access this surface."
            );
        }
        return principal;
    }

    private void consumeStepUpOrAuditDeny(
        UUID installationId,
        UUID profileId,
        MoneyCraftPrincipal actor,
        LockedProfileRow current,
        UUID stepUpIntentId,
        Set<String> acceptedActions,
        String auditAction
    ) {
        try {
            stepUpAuthService.verifyAndConsumeStepUpIntent(
                stepUpIntentId,
                actor.principalId(),
                acceptedActions,
                profileId,
                current.revision()
            );
        } catch (MoneyCraftException ex) {
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_gov_audit_event (
                    event_id, installation_id, profile_id, actor_principal_id, actor_kind,
                    company_id, registration_ref, adapter_code, environment, profile_revision,
                    action, outcome, reason_code, sanitised_detail, correlation_id
                ) VALUES (?, ?, ?, ?, 'PERSON', ?, ?, ?, ?, ?, ?, 'DENIED', ?, ?, ?)
                """,
                UUID.randomUUID(),
                installationId,
                profileId,
                actor.principalId(),
                current.companyId(),
                current.registrationRef(),
                current.adapterCode(),
                current.environment(),
                current.revision(),
                auditAction,
                ex.code(),
                safeTruncate(ex.getMessage(), 480),
                UUID.randomUUID().toString()
            );
            throw ex;
        }
    }

    private ValidatedDraftInput validateDraftInput(JsonNode root, UUID existingProfileId) {
        String profileName = optionalBoundedText(root, "profileName", 160);
        if (profileName == null) {
            profileName = optionalBoundedText(root, "displayName", 160);
        }
        if (profileName == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-NAME-REQUIRED",
                "Profile Name Required",
                "profileName is required (1..160 characters)."
            );
        }

        String profileCode = optionalBoundedText(root, "profileCode", 64);
        if (profileCode != null && !profileCode.matches("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-CODE-INVALID",
                "Invalid Profile Code",
                "profileCode must match ^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$."
            );
        }

        String providerCode = requireBoundedText(root, "providerCode", 64);
        GovernmentInterfaceCatalogue.AdmittedProviderDefinition provider = catalogue.findProvider(providerCode);
        if (provider == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-PROVIDER-UNSUPPORTED",
                "Unsupported Integration Provider",
                "providerCode '" + providerCode + "' is not in the admitted registry."
            );
        }

        String adapterCode = optionalBoundedText(root, "adapterCode", 64);
        if (adapterCode != null
            && !adapterCode.equals(provider.schema().adapterCode())
            && !adapterCode.equals(provider.schema().providerCode())) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-ADAPTER-MISMATCH",
                "Provider Adapter Mismatch",
                "adapterCode '" + adapterCode + "' does not match provider '" + providerCode + "'."
            );
        }

        String environment = requireBoundedText(root, "environment", 16).toUpperCase(Locale.ROOT);
        if (!"SANDBOX".equals(environment) && !"PRODUCTION".equals(environment)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-ENVIRONMENT-INVALID",
                "Invalid Environment",
                "environment must be SANDBOX or PRODUCTION."
            );
        }

        String expectedEndpointProfile = provider.expectedEndpointProfileId(environment);
        String endpointProfileId = optionalBoundedText(root, "endpointProfileId", 96);
        if (endpointProfileId == null) {
            endpointProfileId = expectedEndpointProfile;
        } else {
            if (endpointProfileId.contains("://") || endpointProfileId.contains("?") || endpointProfileId.contains("/")) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-GOV-ARBITRARY-URL-FORBIDDEN",
                    "Arbitrary Endpoint URL Forbidden",
                    "endpointProfileId must reference an admitted profile ID, never an arbitrary URL or query string."
                );
            }
            if (!expectedEndpointProfile.equals(endpointProfileId)) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-GOV-ENDPOINT-ENV-MISMATCH",
                    "Cross-Environment Endpoint Substitution Rejected",
                    "Environment " + environment + " requires endpointProfileId '" + expectedEndpointProfile + "'."
                );
            }
        }

        UUID companyId = parseRequiredUuidField(root, "companyId");
        verifyCompanyAndRegistrationBinding(companyId, provider.schema().registrationKind(), root);

        String registrationRef = requireBoundedText(root, "registrationRef", 64).toUpperCase(Locale.ROOT);
        String dbRegType = mapRegistrationKindToDb(provider.schema().registrationKind());
        validateRegistrationGrammar(dbRegType, registrationRef);

        JsonNode opsNode = root.get("allowedOperationIds");
        if (opsNode == null || !opsNode.isArray() || opsNode.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-OPERATIONS-REQUIRED",
                "Allowed Operations Required",
                "allowedOperationIds must be a non-empty array of admitted operation IDs."
            );
        }
        Set<String> sortedOps = new LinkedHashSet<>();
        List<String> rawOps = new ArrayList<>();
        for (JsonNode op : opsNode) {
            if (!op.isString() || !provider.schema().allowedOperations().contains(op.asString())) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-GOV-OPERATION-UNSUPPORTED",
                    "Unpermitted Operation ID",
                    "Operation '" + op.asString() + "' is not admitted for provider '" + providerCode + "'."
                );
            }
            rawOps.add(op.asString());
        }
        Collections.sort(rawOps);
        sortedOps.addAll(rawOps);

        String consentRef = optionalBoundedText(root, "consentEvidenceRef", 160);
        if (consentRef == null) {
            consentRef = "PENDING-CONSENT-EVIDENCE";
        }
        if ("PRODUCTION".equals(environment) && "PENDING-CONSENT-EVIDENCE".equals(consentRef)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-CONSENT-REQUIRED",
                "Production Onboarding/Consent Evidence Required",
                "PRODUCTION profiles require an explicit consentEvidenceRef."
            );
        }

        String certFp = optionalBoundedText(root, "certificateFingerprintSha256", 64);
        if (certFp != null && !certFp.matches("^[0-9a-fA-F]{64}$")) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-CERT-FP-INVALID",
                "Invalid Certificate SHA-256 Fingerprint",
                "certificateFingerprintSha256 must be 64 hexadecimal characters when provided."
            );
        }
        OffsetDateTime certExpiresAt = null;
        if (root.has("certificateExpiresAt") && !root.get("certificateExpiresAt").isNull()) {
            try {
                certExpiresAt = OffsetDateTime.parse(root.get("certificateExpiresAt").asString());
            } catch (RuntimeException ex) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-GOV-CERT-EXPIRY-INVALID",
                    "Invalid Certificate Expiry Timestamp",
                    "certificateExpiresAt must be ISO-8601 OffsetDateTime."
                );
            }
        }

        return new ValidatedDraftInput(
            profileCode,
            profileName,
            providerCode,
            provider,
            endpointProfileId,
            environment,
            companyId,
            dbRegType,
            registrationRef,
            List.copyOf(sortedOps),
            consentRef,
            certFp == null ? null : certFp.toLowerCase(Locale.ROOT),
            certExpiresAt
        );
    }

    private void verifyCompanyAndRegistrationBinding(UUID companyId, String registrationKind, JsonNode root) {
        String registrationRef = requireBoundedText(root, "registrationRef", 64).toUpperCase(Locale.ROOT);
        Boolean companyExists = TenantContextHolder.callWithSystemBypass("gov-company-check", () -> {
            Integer cnt = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_company WHERE id = ?",
                Integer.class,
                companyId
            );
            return cnt != null && cnt > 0;
        });
        if (!Boolean.TRUE.equals(companyExists)) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-COMPANY-NOT-FOUND",
                "Company Not Found",
                "Target company " + companyId + " does not exist."
            );
        }

        if ("GSTIN".equals(registrationKind)) {
            List<String> companyGstins = TenantContextHolder.callWithSystemBypass("gov-gstin-check", () ->
                businessJdbcTemplate.query(
                    "SELECT gstin::text FROM mc_gst_registration WHERE company_id = ? AND active = true",
                    (rs, rowNum) -> rs.getString(1).toUpperCase(Locale.ROOT),
                    companyId
                )
            );
            if (!companyGstins.isEmpty() && !companyGstins.contains(registrationRef)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-GOV-REGISTRATION-MISMATCH",
                    "Cross-Company or Unregistered GSTIN Rejected",
                    "GSTIN '" + registrationRef + "' does not belong to company " + companyId + "."
                );
            }
        }
    }

    private static void validateRegistrationGrammar(String dbRegType, String ref) {
        boolean valid = switch (dbRegType) {
            case "GSTIN" -> GSTIN_PATTERN.matcher(ref).matches();
            case "PAN" -> PAN_PATTERN.matcher(ref).matches();
            case "TAN" -> TAN_PATTERN.matcher(ref).matches();
            default -> BANK_CORP_ID_PATTERN.matcher(ref).matches();
        };
        if (!valid) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-REGISTRATION-FORMAT-INVALID",
                "Invalid Registration Binding Format",
                "registrationRef '" + ref + "' does not match required " + dbRegType + " format."
            );
        }
    }

    private GovernmentIntegrationDtos.GovernmentIntegrationProfileDto loadProfileView(
        UUID installationId,
        UUID profileId
    ) {
        List<ProfileViewRow> rows = authorityJdbcTemplate.query(
            """
            SELECT profile_id, profile_code, display_name, provider_family, adapter_code,
                   endpoint_profile_id, environment, company_id, registration_type, registration_ref,
                   allowed_operations::text AS ops_json, consent_evidence_ref,
                   certificate_fingerprint_sha256, certificate_expires_at, status,
                   active_credential_revision, staged_credential_revision, last_validation_id,
                   disabled_reason, reconciliation_note, revision, updated_at
              FROM ma_gov_integration_profile
             WHERE profile_id = ? AND installation_id = ?
            """,
            (rs, rowNum) -> new ProfileViewRow(
                rs.getObject("profile_id", UUID.class),
                rs.getString("profile_code"),
                rs.getString("display_name"),
                rs.getString("provider_family"),
                rs.getString("adapter_code"),
                rs.getString("endpoint_profile_id"),
                rs.getString("environment"),
                rs.getObject("company_id", UUID.class),
                rs.getString("registration_type"),
                rs.getString("registration_ref"),
                rs.getString("ops_json"),
                rs.getString("consent_evidence_ref"),
                rs.getString("certificate_fingerprint_sha256"),
                rs.getObject("certificate_expires_at", OffsetDateTime.class),
                rs.getString("status"),
                rs.getObject("active_credential_revision", Long.class),
                rs.getObject("staged_credential_revision", Long.class),
                rs.getObject("last_validation_id", UUID.class),
                rs.getString("disabled_reason"),
                rs.getString("reconciliation_note"),
                rs.getLong("revision"),
                rs.getObject("updated_at", OffsetDateTime.class)
            ),
            profileId,
            installationId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-GOV-PROFILE-NOT-FOUND",
                "Integration Profile Not Found",
                "Government integration profile " + profileId + " was not found."
            );
        }
        ProfileViewRow r = rows.getFirst();
        GovernmentInterfaceCatalogue.AdmittedProviderDefinition provider = catalogue.findProvider(r.adapterCode());
        Long effectiveCredRev = r.stagedCredentialRevision() != null
            ? r.stagedCredentialRevision()
            : r.activeCredentialRevision();

        List<GovernmentIntegrationDtos.CredentialSlotMetadataDto> slotViews =
            loadSlotMetadataViews(profileId, effectiveCredRev, r.status());
        GovernmentIntegrationDtos.ValidationOutcomeDto lastVal = r.lastValidationId() == null
            ? null
            : getValidationJob(profileId, r.lastValidationId());

        String wireStatus = "EXPIRED_REVOKED".equals(r.status()) ? "EXPIRED_OR_REVOKED" : r.status();
        String wireRegKind = "BANK_CORP_ID".equals(r.registrationType()) ? "CORPORATE_BANK_ID" : r.registrationType();
        String etag = formatProfileEtag(profileId, r.revision());

        return new GovernmentIntegrationDtos.GovernmentIntegrationProfileDto(
            r.profileId(),
            r.profileCode(),
            r.displayName(),
            r.adapterCode(),
            provider != null ? provider.schema().adapterCode() : r.adapterCode(),
            r.endpointProfileId(),
            r.providerFamily(),
            r.environment(),
            r.companyId(),
            wireRegKind,
            r.registrationRef(),
            rbacGuard.parseStringArray(r.opsJson()),
            r.consentEvidenceRef(),
            wireStatus,
            effectiveCredRev == null ? "0" : String.valueOf(effectiveCredRev),
            r.activeCredentialRevision() == null ? "0" : String.valueOf(r.activeCredentialRevision()),
            slotViews,
            r.certFingerprint(),
            r.certExpiresAt(),
            lastVal,
            r.disabledReason(),
            r.reconciliationNote(),
            String.valueOf(r.revision()),
            etag,
            r.updatedAt()
        );
    }

    private List<GovernmentIntegrationDtos.CredentialSlotMetadataDto> loadSlotMetadataViews(
        UUID profileId,
        Long credRevision,
        String profileStatus
    ) {
        if (credRevision == null || credRevision <= 0L) {
            return List.of();
        }
        List<CredMetaRow> rows = authorityJdbcTemplate.query(
            """
            SELECT credential_version_id, credential_revision, slot_metadata_json::text AS meta_json,
                   state, staged_at
              FROM ma_gov_credential_version
             WHERE profile_id = ? AND credential_revision = ?
            """,
            (rs, rowNum) -> new CredMetaRow(
                rs.getObject("credential_version_id", UUID.class),
                rs.getLong("credential_revision"),
                rs.getString("meta_json"),
                rs.getString("state"),
                rs.getObject("staged_at", OffsetDateTime.class)
            ),
            profileId,
            credRevision
        );
        if (rows.isEmpty()) {
            return List.of();
        }
        CredMetaRow row = rows.getFirst();
        String slotStatus = ("REVOKED".equals(row.state()) || "COMPROMISED".equals(row.state()))
            ? "EXPIRED"
            : ("FAILED".equals(profileStatus) ? "VALIDATION_FAILED" : "CONFIGURED");

        List<GovernmentIntegrationDtos.CredentialSlotMetadataDto> out = new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(row.metaJson());
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    String slotKey = item.path("slotKey").asString("");
                    if (!slotKey.isBlank()) {
                        out.add(new GovernmentIntegrationDtos.CredentialSlotMetadataDto(
                            slotKey,
                            slotStatus,
                            row.credentialVersionId(),
                            row.credentialRevision(),
                            row.stagedAt()
                        ));
                    }
                }
            }
        } catch (JacksonException ignored) {
            // Non-secret metadata fallback
        }
        return List.copyOf(out);
    }

    private Map<String, String> loadDecryptedSlotsIfUnrevoked(
        UUID installationId,
        LockedProfileRow profile,
        long credRevision
    ) {
        List<CredCipherRow> rows = authorityJdbcTemplate.query(
            """
            SELECT environment_namespace, secret_envelope_gcm, state
              FROM ma_gov_credential_version
             WHERE profile_id = ? AND credential_revision = ?
            """,
            (rs, rowNum) -> new CredCipherRow(
                rs.getString("environment_namespace"),
                rs.getBytes("secret_envelope_gcm"),
                rs.getString("state")
            ),
            profile.profileId(),
            credRevision
        );
        if (rows.isEmpty()) {
            return Map.of();
        }
        CredCipherRow row = rows.getFirst();
        if ("REVOKED".equals(row.state()) || "COMPROMISED".equals(row.state())) {
            return Map.of();
        }
        return decryptSlotCipher(installationId, profile, credRevision, row);
    }

    private Map<String, String> loadDecryptedSlotsForValidation(
        UUID installationId,
        LockedProfileRow profile,
        long credRevision
    ) {
        List<CredCipherRow> rows = authorityJdbcTemplate.query(
            """
            SELECT environment_namespace, secret_envelope_gcm, state
              FROM ma_gov_credential_version
             WHERE profile_id = ? AND credential_revision = ?
            """,
            (rs, rowNum) -> new CredCipherRow(
                rs.getString("environment_namespace"),
                rs.getBytes("secret_envelope_gcm"),
                rs.getString("state")
            ),
            profile.profileId(),
            credRevision
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-GOV-CREDENTIALS-NOT-FOUND",
                "Credential Revision Not Found",
                "Credential revision " + credRevision + " does not exist on this profile."
            );
        }
        CredCipherRow row = rows.getFirst();
        if ("REVOKED".equals(row.state()) || "COMPROMISED".equals(row.state())) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-GOV-CREDENTIAL-REVOKED",
                "Credential Revision Revoked",
                "Revoked or compromised credential revision " + credRevision + " cannot be validated or activated."
            );
        }
        if (!profile.environment().equals(row.environmentNamespace())) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-GOV-CREDENTIAL-ENV-MISMATCH",
                "Cross-Environment Credential Substitution Rejected",
                "Credential namespace does not match profile environment."
            );
        }
        return decryptSlotCipher(installationId, profile, credRevision, row);
    }

    private Map<String, String> decryptSlotCipher(
        UUID installationId,
        LockedProfileRow profile,
        long credRevision,
        CredCipherRow row
    ) {
        byte[] ad = buildCredentialEnvelopeAd(
            installationId,
            profile.profileId(),
            profile.environment(),
            profile.companyId(),
            profile.registrationRef(),
            credRevision
        );
        byte[] plainBytes = tinkCryptoService.decryptGcm(ad, row.cipherBytes());
        try {
            JsonNode obj = objectMapper.readTree(plainBytes);
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : obj.properties()) {
                out.put(entry.getKey(), entry.getValue().asString(""));
            }
            return Collections.unmodifiableMap(out);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Corrupted credential slot JSON inside authenticated GCM payload", ex);
        }
    }

    private LockedProfileRow lockProfileForUpdate(UUID installationId, UUID profileId, long expectedRev) {
        List<LockedProfileRow> rows = authorityJdbcTemplate.query(
            """
            SELECT profile_id, profile_code, display_name, adapter_code, endpoint_profile_id,
                   environment, company_id, registration_type, registration_ref,
                   status, active_credential_revision, staged_credential_revision, revision
              FROM ma_gov_integration_profile
             WHERE profile_id = ? AND installation_id = ?
               FOR UPDATE
            """,
            (rs, rowNum) -> mapLockedRow(rs),
            profileId,
            installationId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-GOV-PROFILE-NOT-FOUND",
                "Integration Profile Not Found",
                "Government integration profile " + profileId + " was not found."
            );
        }
        LockedProfileRow row = rows.getFirst();
        if (row.revision() != expectedRev) {
            String serverEtag = formatProfileEtag(profileId, row.revision());
            throw new MoneyCraftException(
                HttpStatus.PRECONDITION_FAILED,
                "MC-GOV-REVISION-CONFLICT",
                "Profile Revision Conflict",
                "Profile revision is now " + row.revision() + " (If-Match expected " + expectedRev + ").",
                Map.of(),
                Map.of("currentRevision", String.valueOf(row.revision()), "serverEtag", serverEtag),
                serverEtag
            );
        }
        return row;
    }

    private static LockedProfileRow mapLockedRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LockedProfileRow(
            rs.getObject("profile_id", UUID.class),
            rs.getString("profile_code"),
            rs.getString("display_name"),
            rs.getString("adapter_code"),
            rs.getString("endpoint_profile_id"),
            rs.getString("environment"),
            rs.getObject("company_id", UUID.class),
            rs.getString("registration_type"),
            rs.getString("registration_ref"),
            rs.getString("status"),
            rs.getObject("active_credential_revision", Long.class),
            rs.getObject("staged_credential_revision", Long.class),
            rs.getLong("revision")
        );
    }

    private ReceiptReplay checkReceiptReplay(
        UUID installationId,
        UUID actorPrincipalId,
        String operation,
        String idempotencyKey,
        byte[] requestHmac
    ) {
        List<ReceiptRow> rows = authorityJdbcTemplate.query(
            """
            SELECT request_jcs_hmac, receipt_payload::text AS payload_json
              FROM ma_receipt
             WHERE installation_id = ?
               AND actor_principal_id = ?
               AND operation = ?
               AND idempotency_key = ?
               AND expires_at > ?
             ORDER BY receipt_seq DESC
             LIMIT 1
            """,
            (rs, rowNum) -> new ReceiptRow(
                rs.getBytes("request_jcs_hmac"),
                rs.getString("payload_json")
            ),
            installationId,
            actorPrincipalId,
            operation,
            idempotencyKey,
            nowUtc()
        );
        if (rows.isEmpty()) {
            return null;
        }
        ReceiptRow row = rows.getFirst();
        if (row.hmac() == null || !MessageDigest.isEqual(row.hmac(), requestHmac)) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-IDEMPOTENCY-PAYLOAD-MISMATCH",
                "Idempotency Key Reused With Different Proof or Payload",
                "Idempotency-Key '" + idempotencyKey + "' cannot be reused with a modified payload or fresh step-up intent."
            );
        }
        try {
            JsonNode payload = objectMapper.readTree(row.payloadJson());
            String etag = payload.hasNonNull("etag") ? payload.get("etag").asString() : null;
            String location = payload.hasNonNull("location") ? payload.get("location").asString() : null;
            String respJson = payload.get("responseJson").asString();
            return new ReceiptReplay(respJson, etag, location);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed deserializing government receipt payload", ex);
        }
    }

    private void recordReceipt(
        UUID installationId,
        UUID actorPrincipalId,
        String operation,
        String targetType,
        String targetId,
        String idempotencyKey,
        byte[] requestHmac,
        String etag,
        String location,
        String responseJson
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (etag != null) {
            payload.put("etag", etag);
        }
        if (location != null) {
            payload.put("location", location);
        }
        payload.put("responseJson", responseJson);

        TenantContextHolder.callWithSystemBypass("gov-admin-receipt", () -> {
            businessTxTemplate.executeWithoutResult(status -> adminReceiptService.recordAndMirrorAdminReceipt(
                installationId,
                actorPrincipalId,
                null,
                operation,
                targetType,
                targetId,
                idempotencyKey,
                requestHmac,
                payload
            ));
            return null;
        });
    }

    private void appendAudit(
        UUID installationId,
        UUID profileId,
        MoneyCraftPrincipal actor,
        UUID companyId,
        String registrationRef,
        String adapterCode,
        String environment,
        Long profileRevision,
        String action,
        String outcome,
        String reasonCode,
        String sanitisedDetail
    ) {
        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_gov_audit_event (
                event_id, installation_id, profile_id, actor_principal_id, actor_kind,
                company_id, registration_ref, adapter_code, environment, profile_revision,
                action, outcome, reason_code, sanitised_detail, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
            installationId,
            profileId,
            actor.principalId(),
            actor.kind(),
            companyId,
            registrationRef,
            adapterCode,
            environment,
            profileRevision,
            action,
            outcome,
            reasonCode,
            safeTruncate(sanitisedDetail, 480),
            UUID.randomUUID().toString()
        );
    }

    private byte[] computeRequestHmac(
        String operation,
        String rawIfMatch,
        UUID stepUpIntentId,
        String canonicalNonSecretBody
    ) {
        String canonical = jcsCanonicalizer.canonicalizeObject(
            Map.of(
                "operation", operation,
                "ifMatch", rawIfMatch == null ? "" : rawIfMatch.trim(),
                "stepUpIntentId", stepUpIntentId == null ? "" : stepUpIntentId.toString(),
                "body", canonicalNonSecretBody
            )
        );
        return tinkCryptoService.computeIdempotencyHmac(canonical.getBytes(StandardCharsets.UTF_8)).digest32();
    }

    private static byte[] buildCredentialEnvelopeAd(
        UUID installationId,
        UUID profileId,
        String environment,
        UUID companyId,
        String registrationRef,
        long credentialRevision
    ) {
        String ad = "ma_gov_credential_version|" + installationId + "|" + profileId + "|"
            + environment + "|" + companyId + "|" + registrationRef + "|" + credentialRevision;
        return ad.getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode parseBoundedClosedObject(byte[] rawBody, Set<String> allowedKeys) {
        if (rawBody != null && rawBody.length > MAX_BODY_BYTES) {
            throw new MoneyCraftException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "MC-GOV-PAYLOAD-TOO-LARGE",
                "Payload Too Large",
                "Government configuration request body cannot exceed 16 KiB."
            );
        }
        if (rawBody == null || rawBody.length == 0) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-BODY-REQUIRED",
                "Request Body Required",
                "A closed JSON object body is required."
            );
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root == null || !root.isObject()) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-GOV-SCHEMA-INVALID",
                    "Invalid JSON Object",
                    "Expected a closed JSON object."
                );
            }
            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                if (!allowedKeys.contains(entry.getKey())) {
                    throw new MoneyCraftException(
                        HttpStatus.BAD_REQUEST,
                        "MC-GOV-UNKNOWN-FIELD",
                        "Unknown Field in Closed Schema",
                        "Unexpected field '" + entry.getKey() + "'."
                    );
                }
            }
            return root;
        } catch (JacksonException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-REQ-MALFORMED-JSON",
                "Malformed JSON Body",
                "Request body could not be parsed as valid JSON."
            );
        }
    }

    private static Map<String, String> extractSlotSubmissions(JsonNode root) {
        JsonNode slotsNode = root.has("slots") ? root.get("slots") : root.get("secretSlots");
        if (slotsNode == null || slotsNode.isNull()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-SLOTS-REQUIRED",
                "Credential Slots Required",
                "Provide non-empty write-only credential slots."
            );
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (slotsNode.isObject()) {
            for (Map.Entry<String, JsonNode> entry : slotsNode.properties()) {
                if (!entry.getValue().isString()) {
                    throw new MoneyCraftException(
                        HttpStatus.BAD_REQUEST,
                        "MC-GOV-SLOT-INVALID",
                        "Invalid Credential Slot Value",
                        "Credential slot '" + entry.getKey() + "' value must be a string."
                    );
                }
                String trimmed = entry.getValue().asString().trim();
                if (!trimmed.isEmpty()) {
                    out.put(entry.getKey(), trimmed);
                }
            }
        } else if (slotsNode.isArray()) {
            for (JsonNode item : slotsNode) {
                if (!item.isObject()) {
                    throw new MoneyCraftException(
                        HttpStatus.BAD_REQUEST,
                        "MC-GOV-SLOT-INVALID",
                        "Invalid Credential Slot Entry",
                        "Each credential slot entry must be an object."
                    );
                }
                String key = item.has("slotKey") ? item.get("slotKey").asString("") : item.path("slotName").asString("");
                String val = item.has("secretValue")
                    ? item.get("secretValue").asString("")
                    : item.path("secretRef").asString("");
                if (!key.isBlank() && !val.trim().isEmpty()) {
                    out.put(key.trim(), val.trim());
                }
            }
        }
        if (out.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-SLOTS-REQUIRED",
                "Empty Credential Slots",
                "At least one non-empty credential slot must be supplied."
            );
        }
        return out;
    }

    private static long parseAndVerifyProfileIfMatch(String rawIfMatch, UUID expectedProfileId) {
        if (rawIfMatch == null || rawIfMatch.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.PRECONDITION_REQUIRED,
                "MC-PRECONDITION-REQUIRED",
                "If-Match Precondition Required",
                "Profile mutation requires If-Match header \"gov:<profileId>:<revision>\"."
            );
        }
        Matcher matcher = GOV_ETAG_PATTERN.matcher(rawIfMatch.trim());
        if (!matcher.matches()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-ETAG-MALFORMED",
                "Malformed Profile ETag",
                "If-Match must follow grammar \"gov:<profileId>:<revision>\"."
            );
        }
        UUID etagProfileId = UUID.fromString(matcher.group(1));
        if (!expectedProfileId.equals(etagProfileId)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-ETAG-WRONG-SCOPE",
                "ETag Profile Mismatch",
                "If-Match profileId does not match target profile."
            );
        }
        return Long.parseLong(matcher.group(2));
    }

    private static UUID resolveStepUpIntentId(JsonNode root, String headerStepUp) {
        String raw = null;
        if (root.hasNonNull("stepUpIntentId") && root.get("stepUpIntentId").isString()) {
            raw = root.get("stepUpIntentId").asString();
        } else if (headerStepUp != null && !headerStepUp.isBlank()) {
            raw = headerStepUp;
        }
        if (raw == null || raw.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-STEP-UP-REQUIRED",
                "Step-Up Proof Required",
                "stepUpIntentId is required for every government integration mutation."
            );
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-STEP-UP-INVALID",
                "Malformed Step-Up Intent ID",
                "stepUpIntentId must be a canonical UUID."
            );
        }
    }

    private static String validateUuidHeader(String rawHeader, String headerName) {
        if (rawHeader == null || rawHeader.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-IDEMPOTENCY-KEY-REQUIRED",
                headerName + " Header Required",
                headerName + " UUID header is required."
            );
        }
        try {
            return UUID.fromString(rawHeader.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-IDEMPOTENCY-KEY-INVALID",
                "Invalid " + headerName,
                headerName + " must be a canonical UUID."
            );
        }
    }

    private static UUID parseRequiredUuidField(JsonNode root, String field) {
        String raw = requireBoundedText(root, field, 64);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-UUID-INVALID",
                "Invalid UUID Field",
                "Field '" + field + "' must be a valid UUID."
            );
        }
    }

    private static Long parseOptionalPositiveLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            long val = node.isNumber() ? node.longValue() : Long.parseLong(node.asString().trim());
            return val > 0 ? val : null;
        } catch (NumberFormatException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-REVISION-INVALID",
                "Invalid Revision Number",
                "Revision field must be a positive integer."
            );
        }
    }

    private static String requireBoundedText(JsonNode root, String field, int maxLen) {
        String val = optionalBoundedText(root, field, maxLen);
        if (val == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-FIELD-REQUIRED",
                "Required Field Missing",
                "Field '" + field + "' is required."
            );
        }
        return val;
    }

    private static String optionalBoundedText(JsonNode root, String field, int maxLen) {
        if (!root.hasNonNull(field)) {
            return null;
        }
        JsonNode node = root.get(field);
        if (!node.isString()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-FIELD-INVALID",
                "Invalid String Field",
                "Field '" + field + "' must be a string."
            );
        }
        String trimmed = node.asString().trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLen) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GOV-FIELD-TOO-LONG",
                "Field Exceeds Maximum Length",
                "Field '" + field + "' cannot exceed " + maxLen + " characters."
            );
        }
        return trimmed;
    }

    private static String mapRegistrationKindToDb(String kind) {
        if ("CORPORATE_BANK_ID".equalsIgnoreCase(kind)) {
            return "BANK_CORP_ID";
        }
        return kind.toUpperCase(Locale.ROOT);
    }

    private static String mapValidationDbStateToWire(String dbState) {
        return switch (dbState) {
            case "SUCCEEDED" -> "VALIDATED";
            case "FAILED" -> "FAILED";
            case "PENDING" -> "PENDING";
            default -> "NOT_VALIDATED";
        };
    }

    private static String buildDefaultProfileCode(String providerCode, String environment, UUID profileId) {
        String cleanProvider = providerCode.replaceAll("[^A-Za-z0-9_-]", "");
        return cleanProvider + "-" + environment + "-" + profileId.toString().substring(0, 8);
    }

    public static String formatProfileEtag(UUID profileId, long revision) {
        return "\"gov:" + profileId + ":" + revision + "\"";
    }

    private static String safeTruncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private UUID loadActiveInstallationId() {
        List<UUID> ids = authorityJdbcTemplate.query(
            "SELECT installation_id FROM ma_state WHERE singleton = 1 AND security_state = 'ACTIVE'",
            (rs, rowNum) -> rs.getObject("installation_id", UUID.class)
        );
        if (ids.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-SYSTEM-NOT-ACTIVE",
                "Installation Authority Not Active",
                "Protected Authority installation state is not ACTIVE."
            );
        }
        return ids.getFirst();
    }

    private OffsetDateTime nowUtc() {
        return OffsetDateTime.ofInstant(clockSource.instant(), ZoneOffset.UTC);
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException ex) {
            throw new IllegalStateException("JSON serialization failed", ex);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("JSON deserialization failed", ex);
        }
    }

    public record ProfileMutationResult(
        GovernmentIntegrationDtos.GovernmentIntegrationProfileDto profile,
        String etag,
        boolean replayed
    ) {
    }

    public record CredentialStageResult(
        GovernmentIntegrationDtos.StagedCredentialVersionResponse response,
        String etag,
        boolean replayed
    ) {
    }

    public record ValidationStartResult(
        GovernmentIntegrationDtos.ValidationOutcomeDto outcome,
        String etag,
        String location,
        boolean replayed
    ) {
    }

    private record ValidatedDraftInput(
        String profileCode,
        String profileName,
        String providerCode,
        GovernmentInterfaceCatalogue.AdmittedProviderDefinition provider,
        String endpointProfileId,
        String environment,
        UUID companyId,
        String dbRegistrationType,
        String registrationRef,
        List<String> allowedOperations,
        String consentEvidenceRef,
        String certificateFingerprintSha256,
        OffsetDateTime certificateExpiresAt
    ) {
    }

    private record LockedProfileRow(
        UUID profileId,
        String profileCode,
        String displayName,
        String adapterCode,
        String endpointProfileId,
        String environment,
        UUID companyId,
        String registrationType,
        String registrationRef,
        String status,
        Long activeCredentialRevision,
        Long stagedCredentialRevision,
        long revision
    ) {
    }

    private record ProfileViewRow(
        UUID profileId,
        String profileCode,
        String displayName,
        String providerFamily,
        String adapterCode,
        String endpointProfileId,
        String environment,
        UUID companyId,
        String registrationType,
        String registrationRef,
        String opsJson,
        String consentEvidenceRef,
        String certFingerprint,
        OffsetDateTime certExpiresAt,
        String status,
        Long activeCredentialRevision,
        Long stagedCredentialRevision,
        UUID lastValidationId,
        String disabledReason,
        String reconciliationNote,
        long revision,
        OffsetDateTime updatedAt
    ) {
    }

    private record CredMetaRow(
        UUID credentialVersionId,
        long credentialRevision,
        String metaJson,
        String state,
        OffsetDateTime stagedAt
    ) {
    }

    private record CredCipherRow(String environmentNamespace, byte[] cipherBytes, String state) {
    }

    private record PreparedProbeContext(
        UUID validationId,
        LockedProfileRow profile,
        long credentialRevision,
        GovernmentInterfaceCatalogue.AdmittedProviderDefinition provider,
        Map<String, String> decryptedSlots
    ) {
    }

    private record ProbeExecutionOutcome(
        String probeMode,
        String dbState,
        String auditOutcome,
        String errorCode,
        Integer httpStatus,
        String sanitisedSummary
    ) {
    }

    private record FinalizedValidation(
        GovernmentIntegrationDtos.ValidationOutcomeDto outcome,
        String etag
    ) {
    }

    private record ValidationCheckRow(long profileRevision, long credentialRevision, String state) {
    }

    private record ReceiptRow(byte[] hmac, String payloadJson) {
    }

    private record ReceiptReplay(String responseJson, String etag, String location) {
    }
}
