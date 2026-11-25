package com.raster.moneycraft.foundation.preferences;

import com.raster.moneycraft.foundation.authority.AdminReceiptService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.OidcClockSource;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Implements authenticated-person UI presentation and keyboard preference persistence (Doc 50, Doc 52 §2).
 * Enforces closed schemaVersion 2, 16 KiB / depth 8 / 256 override bounds, strong ETag preconditions,
 * and 90-day preferences.update Authority receipts.
 */
@Service
public class UiPreferencesService {

    public static final int MAX_PAYLOAD_BYTES = 16_384;
    public static final int MAX_JSON_DEPTH = 8;
    public static final int MAX_KEYBOARD_OVERRIDES = 256;
    public static final String RECEIPT_OPERATION = "preferences.update";

    private static final Pattern STRONG_ETAG_PATTERN =
        Pattern.compile("^\"ui:([A-Za-z0-9_-]{16,128}):(0|[1-9][0-9]*)\"$");
    private static final Set<String> ALLOWED_APPEARANCES = Set.of("LIGHT", "DARK", "SYSTEM");
    private static final Set<String> ALLOWED_KEYBOARD_MODES = Set.of("CHARACTER", "OFF", "MODIFIER");
    private static final List<BigDecimal> ALLOWED_TEXT_SCALES = List.of(
        new BigDecimal("75"),
        new BigDecimal("87.5"),
        new BigDecimal("100"),
        new BigDecimal("112.5"),
        new BigDecimal("125"),
        new BigDecimal("150"),
        new BigDecimal("175"),
        new BigDecimal("200")
    );
    private static final Set<String> ALLOWED_PATCH_ROOT_KEYS = Set.of("schemaVersion", "changes");
    private static final Set<String> ALLOWED_CHANGES_KEYS = Set.of("appearance", "textScalePercent", "keyboard");
    private static final Set<String> ALLOWED_KEYBOARD_KEYS = Set.of(
        "version",
        "mode",
        "locale",
        "profileId",
        "overrides"
    );
    private static final Set<String> ALLOWED_OVERRIDE_KEYS = Set.of("contextId", "commandId", "chordId");

    private final AuthorityGuard authorityGuard;
    private final AdminReceiptService adminReceiptService;
    private final TinkCryptoService tinkCryptoService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final OidcClockSource clockSource;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate authorityJdbcTemplate;
    private final TransactionTemplate authorityTxTemplate;
    private final TransactionTemplate businessTxTemplate;

    public UiPreferencesService(
        AuthorityGuard authorityGuard,
        AdminReceiptService adminReceiptService,
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer,
        OidcClockSource clockSource,
        ObjectMapper objectMapper,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("authorityTransactionManager") PlatformTransactionManager authorityTransactionManager,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.adminReceiptService = adminReceiptService;
        this.tinkCryptoService = tinkCryptoService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.clockSource = clockSource;
        this.objectMapper = objectMapper;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.authorityTxTemplate = new TransactionTemplate(authorityTransactionManager);
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public PreferenceResult readPreferences(MoneyCraftPrincipal person) {
        UUID installationId = loadActiveInstallationId();
        String subjectScope = deriveSubjectScope(installationId, person.principalId());

        List<StoredPreferenceRow> rows = authorityJdbcTemplate.query(
            """
            SELECT preferences_json::text AS prefs_json, revision, subject_scope
              FROM ma_person_ui_preference
             WHERE principal_id = ? AND installation_id = ?
            """,
            (rs, rowNum) -> new StoredPreferenceRow(
                rs.getString("prefs_json"),
                rs.getLong("revision"),
                rs.getString("subject_scope")
            ),
            person.principalId(),
            installationId
        );

        if (rows.isEmpty()) {
            String rev = "0";
            UiPreferencesDtos.MeUiPreferencesResponse body = new UiPreferencesDtos.MeUiPreferencesResponse(
                UiPreferencesDtos.UiPreferencesV2Dto.defaults(),
                rev,
                subjectScope
            );
            return new PreferenceResult(body, formatEtag(subjectScope, rev), false);
        }

        StoredPreferenceRow row = rows.getFirst();
        UiPreferencesDtos.UiPreferencesV2Dto prefs = deserializePreferencesOrDefault(row.prefsJson());
        String rev = String.valueOf(row.revision());
        UiPreferencesDtos.MeUiPreferencesResponse body = new UiPreferencesDtos.MeUiPreferencesResponse(
            prefs,
            rev,
            row.subjectScope()
        );
        return new PreferenceResult(body, formatEtag(row.subjectScope(), rev), false);
    }

    public PreferenceResult patchPreferences(
        MoneyCraftPrincipal person,
        String rawIfMatchHeader,
        String rawIdempotencyKey,
        byte[] rawBodyBytes
    ) {
        authorityGuard.assertWritable();
        if (rawBodyBytes != null && rawBodyBytes.length > MAX_PAYLOAD_BYTES) {
            throw new MoneyCraftException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "MC-PREFERENCES-PAYLOAD-TOO-LARGE",
                "UI Preferences Payload Exceeds 16 KiB",
                "PATCH /api/v1/me/ui-preferences body cannot exceed 16384 UTF-8 bytes."
            );
        }
        if (rawBodyBytes == null || rawBodyBytes.length == 0) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-BODY-REQUIRED",
                "Patch Body Required",
                "PATCH /api/v1/me/ui-preferences requires a closed JSON object body."
            );
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBodyBytes);
        } catch (JacksonException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-REQ-MALFORMED-JSON",
                "Malformed JSON Body",
                "Request body could not be parsed as valid JSON."
            );
        }

        if (computeJsonDepth(root, 1) > MAX_JSON_DEPTH) {
            throw new MoneyCraftException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "MC-PREFERENCES-DEPTH-EXCEEDED",
                "JSON Nesting Depth Exceeded",
                "PATCH /api/v1/me/ui-preferences JSON nesting depth cannot exceed " + MAX_JSON_DEPTH + "."
            );
        }

        ValidatedPatch validatedPatch = validateClosedPatchNode(root);
        String idempotencyKey = validateUuidIdempotencyKey(rawIdempotencyKey);
        UUID installationId = loadActiveInstallationId();
        String subjectScope = deriveSubjectScope(installationId, person.principalId());

        String canonicalFingerprintInput = jcsCanonicalizer.canonicalizeObject(
            Map.of(
                "ifMatch", rawIfMatchHeader == null ? "" : rawIfMatchHeader.trim(),
                "body", jcsCanonicalizer.canonicalizeJson(new String(rawBodyBytes, StandardCharsets.UTF_8))
            )
        );
        byte[] requestHmac = tinkCryptoService
            .computeIdempotencyHmac(canonicalFingerprintInput.getBytes(StandardCharsets.UTF_8))
            .digest32();

        PreferenceResult replayed = checkExistingPreferenceReceipt(
            installationId,
            person.principalId(),
            idempotencyKey,
            requestHmac
        );
        if (replayed != null) {
            return replayed;
        }

        long expectedRevision = validateAndParseIfMatch(rawIfMatchHeader, subjectScope);

        CommittedPatch committed = authorityTxTemplate.execute(status -> {
            authorityJdbcTemplate.queryForObject(
                "SELECT principal_id FROM ma_principal WHERE principal_id = ? FOR UPDATE",
                UUID.class,
                person.principalId()
            );

            List<StoredPreferenceRow> existingRows = authorityJdbcTemplate.query(
                """
                SELECT preferences_json::text AS prefs_json, revision, subject_scope
                  FROM ma_person_ui_preference
                 WHERE principal_id = ? AND installation_id = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> new StoredPreferenceRow(
                    rs.getString("prefs_json"),
                    rs.getLong("revision"),
                    rs.getString("subject_scope")
                ),
                person.principalId(),
                installationId
            );

            long currentRev = existingRows.isEmpty() ? 0L : existingRows.getFirst().revision();
            UiPreferencesDtos.UiPreferencesV2Dto currentPrefs = existingRows.isEmpty()
                ? UiPreferencesDtos.UiPreferencesV2Dto.defaults()
                : deserializePreferencesOrDefault(existingRows.getFirst().prefsJson());
            String activeScope = existingRows.isEmpty() ? subjectScope : existingRows.getFirst().subjectScope();

            if (expectedRevision != currentRev) {
                String currentRevStr = String.valueOf(currentRev);
                String serverEtag = formatEtag(activeScope, currentRevStr);
                List<String> conflicting = computeConflictingFields(validatedPatch, currentPrefs);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("serverCurrent", currentPrefs);
                extra.put("serverRevision", currentRevStr);
                extra.put("serverEtag", serverEtag);
                extra.put("subjectScope", activeScope);
                extra.put("conflictingFields", conflicting);
                throw new MoneyCraftException(
                    HttpStatus.PRECONDITION_FAILED,
                    "MC-PREFERENCES-REVISION-CONFLICT",
                    "UI Preferences Revision Conflict",
                    "Preferences changed in another session (current revision " + currentRevStr + ").",
                    Map.of(),
                    extra,
                    serverEtag
                );
            }

            UiPreferencesDtos.UiPreferencesV2Dto merged = new UiPreferencesDtos.UiPreferencesV2Dto(
                UiPreferencesDtos.SCHEMA_VERSION_2,
                validatedPatch.hasAppearance() ? validatedPatch.appearance() : currentPrefs.appearance(),
                validatedPatch.hasTextScale() ? validatedPatch.textScalePercent() : currentPrefs.textScalePercent(),
                validatedPatch.hasKeyboard() ? validatedPatch.keyboard() : currentPrefs.keyboard()
            );

            long nextRevision = currentRev + 1L;
            String mergedJson = serializePreferences(merged);
            OffsetDateTime nowTs = OffsetDateTime.ofInstant(clockSource.instant(), ZoneOffset.UTC);

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_person_ui_preference (
                    principal_id, installation_id, subject_scope, preferences_json, revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (principal_id) DO UPDATE
                   SET preferences_json = EXCLUDED.preferences_json,
                       revision = EXCLUDED.revision,
                       updated_at = EXCLUDED.updated_at
                """,
                person.principalId(),
                installationId,
                activeScope,
                mergedJson,
                nextRevision,
                nowTs,
                nowTs
            );

            String nextRevStr = String.valueOf(nextRevision);
            String nextEtag = formatEtag(activeScope, nextRevStr);
            UiPreferencesDtos.MeUiPreferencesResponse responseBody =
                new UiPreferencesDtos.MeUiPreferencesResponse(merged, nextRevStr, activeScope);
            return new CommittedPatch(responseBody, nextEtag);
        });

        if (committed == null) {
            throw new IllegalStateException("Preference patch transaction returned null");
        }

        Map<String, Object> receiptPayload = new LinkedHashMap<>();
        receiptPayload.put("revision", committed.response().revision());
        receiptPayload.put("subjectScope", committed.response().subjectScope());
        receiptPayload.put("etag", committed.etag());
        receiptPayload.put("preferencesJson", serializePreferences(committed.response().preferences()));

        TenantContextHolder.callWithSystemBypass("ui-preferences-receipt", () -> {
            businessTxTemplate.executeWithoutResult(status -> adminReceiptService.recordAndMirrorAdminReceipt(
                installationId,
                person.principalId(),
                null,
                RECEIPT_OPERATION,
                "PERSON",
                person.principalId().toString(),
                idempotencyKey,
                requestHmac,
                receiptPayload
            ));
            return null;
        });

        return new PreferenceResult(committed.response(), committed.etag(), false);
    }

    public String deriveSubjectScope(UUID installationId, UUID principalId) {
        byte[] digest = jcsCanonicalizer.sha256(
            ("MC-UI-SUBJECT-SCOPE-V2|" + installationId + "|" + principalId).getBytes(StandardCharsets.UTF_8)
        );
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    public static String formatEtag(String subjectScope, String revision) {
        return "\"ui:" + subjectScope + ":" + revision + "\"";
    }

    private PreferenceResult checkExistingPreferenceReceipt(
        UUID installationId,
        UUID principalId,
        String idempotencyKey,
        byte[] requestHmac
    ) {
        OffsetDateTime nowTs = OffsetDateTime.ofInstant(clockSource.instant(), ZoneOffset.UTC);
        List<ReceiptReplayRow> rows = authorityJdbcTemplate.query(
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
            (rs, rowNum) -> new ReceiptReplayRow(
                rs.getBytes("request_jcs_hmac"),
                rs.getString("payload_json")
            ),
            installationId,
            principalId,
            RECEIPT_OPERATION,
            idempotencyKey,
            nowTs
        );
        if (rows.isEmpty()) {
            return null;
        }
        ReceiptReplayRow replay = rows.getFirst();
        if (replay.requestHmac() == null || !MessageDigest.isEqual(replay.requestHmac(), requestHmac)) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-IDEMPOTENCY-PAYLOAD-MISMATCH",
                "Idempotency Key Reused With Different Payload",
                "Idempotency-Key '" + idempotencyKey + "' was already used with a different preference patch or If-Match."
            );
        }
        try {
            JsonNode payloadNode = objectMapper.readTree(replay.payloadJson());
            String rev = payloadNode.get("revision").asString();
            String scope = payloadNode.get("subjectScope").asString();
            String etag = payloadNode.get("etag").asString();
            UiPreferencesDtos.UiPreferencesV2Dto prefs =
                deserializePreferencesOrDefault(payloadNode.get("preferencesJson").asString());
            return new PreferenceResult(
                new UiPreferencesDtos.MeUiPreferencesResponse(prefs, rev, scope),
                etag,
                true
            );
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed reading stored preferences.update receipt", ex);
        }
    }

    private static long validateAndParseIfMatch(String rawIfMatch, String expectedSubjectScope) {
        if (rawIfMatch == null || rawIfMatch.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.PRECONDITION_REQUIRED,
                "MC-PRECONDITION-REQUIRED",
                "If-Match Precondition Required",
                "PATCH /api/v1/me/ui-preferences requires If-Match from the latest GET."
            );
        }
        Matcher matcher = STRONG_ETAG_PATTERN.matcher(rawIfMatch.trim());
        if (!matcher.matches()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-ETAG-MALFORMED",
                "Malformed UI Preferences ETag",
                "If-Match must follow grammar \"ui:<subjectScope>:<revision>\"."
            );
        }
        String suppliedScope = matcher.group(1);
        if (!expectedSubjectScope.equals(suppliedScope)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-ETAG-WRONG-SCOPE",
                "ETag Scope Mismatch",
                "If-Match subjectScope does not belong to the authenticated person."
            );
        }
        try {
            return Long.parseLong(matcher.group(2));
        } catch (NumberFormatException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-ETAG-MALFORMED",
                "Invalid ETag Revision",
                "ETag revision exceeds 64-bit integer range."
            );
        }
    }

    private static String validateUuidIdempotencyKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-IDEMPOTENCY-KEY-REQUIRED",
                "Idempotency-Key Required",
                "PATCH /api/v1/me/ui-preferences requires a UUID Idempotency-Key header."
            );
        }
        try {
            return UUID.fromString(rawKey.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-IDEMPOTENCY-KEY-INVALID",
                "Invalid UUID Idempotency-Key",
                "Idempotency-Key must be a canonical UUID."
            );
        }
    }

    private static ValidatedPatch validateClosedPatchNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-SCHEMA-INVALID",
                "Invalid UI Preferences Patch",
                "Expected a closed JSON object."
            );
        }
        assertOnlyAllowedKeys(root, ALLOWED_PATCH_ROOT_KEYS, "root");

        JsonNode schemaNode = root.get("schemaVersion");
        if (schemaNode == null || !schemaNode.isIntegralNumber() || schemaNode.intValue() != 2) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-SCHEMA-INVALID",
                "Unsupported UI Preferences Schema Version",
                "schemaVersion must equal integer 2."
            );
        }

        JsonNode changesNode = root.get("changes");
        if (changesNode == null || !changesNode.isObject() || changesNode.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-EMPTY-PATCH",
                "Empty Preference Changes",
                "changes must be a non-empty closed object with at least one supported property."
            );
        }
        assertOnlyAllowedKeys(changesNode, ALLOWED_CHANGES_KEYS, "changes");

        boolean hasAppearance = changesNode.has("appearance");
        String appearance = null;
        if (hasAppearance) {
            JsonNode appNode = changesNode.get("appearance");
            if (!appNode.isString() || !ALLOWED_APPEARANCES.contains(appNode.asString())) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-APPEARANCE-INVALID",
                    "Invalid Appearance Mode",
                    "appearance must be LIGHT, DARK, or SYSTEM."
                );
            }
            appearance = appNode.asString();
        }

        boolean hasTextScale = changesNode.has("textScalePercent");
        BigDecimal textScale = null;
        if (hasTextScale) {
            JsonNode scaleNode = changesNode.get("textScalePercent");
            if (scaleNode == null || !scaleNode.isNumber() || scaleNode.isBoolean()) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-SCALE-INVALID",
                    "Invalid Text Scale Preset",
                    "textScalePercent must be one of 75, 87.5, 100, 112.5, 125, 150, 175, or 200."
                );
            }
            BigDecimal candidate = scaleNode.decimalValue();
            for (BigDecimal preset : ALLOWED_TEXT_SCALES) {
                if (preset.compareTo(candidate) == 0) {
                    textScale = preset;
                    break;
                }
            }
            if (textScale == null) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-SCALE-INVALID",
                    "Unsupported Text Scale Preset",
                    "textScalePercent must be one of 75, 87.5, 100, 112.5, 125, 150, 175, or 200."
                );
            }
        }

        boolean hasKeyboard = changesNode.has("keyboard");
        UiPreferencesDtos.KeyboardPreferenceV1Dto keyboard = null;
        if (hasKeyboard) {
            keyboard = validateClosedKeyboardNode(changesNode.get("keyboard"));
        }

        return new ValidatedPatch(hasAppearance, appearance, hasTextScale, textScale, hasKeyboard, keyboard);
    }

    private static UiPreferencesDtos.KeyboardPreferenceV1Dto validateClosedKeyboardNode(JsonNode kbdNode) {
        if (kbdNode == null || !kbdNode.isObject()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-KEYBOARD-INVALID",
                "Invalid Keyboard Preference Object",
                "keyboard must be a complete Document 45 version 1 closed object."
            );
        }
        assertOnlyAllowedKeys(kbdNode, ALLOWED_KEYBOARD_KEYS, "keyboard");
        for (String reqKey : ALLOWED_KEYBOARD_KEYS) {
            if (!kbdNode.has(reqKey)) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-KEYBOARD-INVALID",
                    "Incomplete Keyboard Preference Object",
                    "keyboard is missing required field '" + reqKey + "'."
                );
            }
        }

        JsonNode versionNode = kbdNode.get("version");
        if (!versionNode.isIntegralNumber() || versionNode.intValue() != 1) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-KEYBOARD-INVALID",
                "Unsupported Keyboard Schema Version",
                "keyboard.version must equal integer 1."
            );
        }

        JsonNode modeNode = kbdNode.get("mode");
        if (!modeNode.isString() || !ALLOWED_KEYBOARD_MODES.contains(modeNode.asString())) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-KEYBOARD-INVALID",
                "Invalid Keyboard Mode",
                "keyboard.mode must be CHARACTER, OFF, or MODIFIER."
            );
        }

        JsonNode localeNode = kbdNode.get("locale");
        if (!localeNode.isString() || !UiPreferencesDtos.LOCALE_EN_GB.equals(localeNode.asString())) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-KEYBOARD-INVALID",
                "Unsupported Keyboard Locale",
                "keyboard.locale must equal en-GB."
            );
        }

        JsonNode profileNode = kbdNode.get("profileId");
        String profileId = null;
        if (!profileNode.isNull()) {
            if (!profileNode.isString()) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-KEYBOARD-INVALID",
                    "Invalid Keyboard Profile Identifier",
                    "keyboard.profileId must be null or a 1..128 character string."
                );
            }
            String rawProfile = profileNode.asString();
            if (rawProfile.trim().isEmpty() || rawProfile.length() > 128) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-KEYBOARD-INVALID",
                    "Invalid Keyboard Profile Identifier",
                    "keyboard.profileId must be between 1 and 128 non-blank characters."
                );
            }
            profileId = rawProfile;
        }

        JsonNode overridesNode = kbdNode.get("overrides");
        if (!overridesNode.isArray()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-KEYBOARD-INVALID",
                "Invalid Keyboard Overrides",
                "keyboard.overrides must be a JSON array."
            );
        }
        if (overridesNode.size() > MAX_KEYBOARD_OVERRIDES) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-OVERRIDES-LIMIT",
                "Keyboard Overrides Limit Exceeded",
                "keyboard.overrides cannot exceed " + MAX_KEYBOARD_OVERRIDES + " items."
            );
        }

        List<UiPreferencesDtos.KeyboardOverrideDto> overrides = new ArrayList<>(overridesNode.size());
        Set<String> seenCommandBindings = new HashSet<>();
        Set<String> seenChordBindings = new HashSet<>();

        for (JsonNode item : overridesNode) {
            if (item == null || !item.isObject()) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-KEYBOARD-INVALID",
                    "Invalid Keyboard Override Item",
                    "Each keyboard override item must be a closed object."
                );
            }
            assertOnlyAllowedKeys(item, ALLOWED_OVERRIDE_KEYS, "keyboard.overrides[]");
            String contextId = requireBoundedIdentifier(item, "contextId");
            String commandId = requireBoundedIdentifier(item, "commandId");
            String chordId = requireBoundedIdentifier(item, "chordId");

            String cmdPair = contextId + "::" + commandId;
            String chordPair = contextId + "::" + chordId.toLowerCase();
            if (!seenCommandBindings.add(cmdPair) || !seenChordBindings.add(chordPair)) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-KEYBOARD-COLLISION",
                    "Colliding Keyboard Override",
                    "Duplicate command or chord binding detected inside context '" + contextId + "'."
                );
            }
            overrides.add(new UiPreferencesDtos.KeyboardOverrideDto(contextId, commandId, chordId));
        }

        return new UiPreferencesDtos.KeyboardPreferenceV1Dto(
            UiPreferencesDtos.KEYBOARD_VERSION_1,
            modeNode.asString(),
            UiPreferencesDtos.LOCALE_EN_GB,
            profileId,
            List.copyOf(overrides)
        );
    }

    private static String requireBoundedIdentifier(JsonNode obj, String field) {
        JsonNode child = obj.get(field);
        if (child == null || !child.isString()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-KEYBOARD-INVALID",
                "Missing Keyboard Override Field",
                "Keyboard override field '" + field + "' must be a 1..128 character string."
            );
        }
        String val = child.asString();
        if (val.isEmpty() || val.length() > 128 || val.trim().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PREFERENCES-KEYBOARD-INVALID",
                "Invalid Keyboard Override Field",
                "Keyboard override field '" + field + "' must be 1..128 non-blank characters."
            );
        }
        for (int i = 0; i < val.length(); i++) {
            char ch = val.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-KEYBOARD-INVALID",
                    "Control Character Forbidden",
                    "Keyboard override field '" + field + "' must not contain control characters."
                );
            }
        }
        return val;
    }

    private static void assertOnlyAllowedKeys(JsonNode objectNode, Set<String> allowedKeys, String pathLabel) {
        for (Map.Entry<String, JsonNode> entry : objectNode.properties()) {
            if (!allowedKeys.contains(entry.getKey())) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-PREFERENCES-UNKNOWN-FIELD",
                    "Unknown Field in Closed Schema",
                    "Unexpected property '" + entry.getKey() + "' at " + pathLabel + "."
                );
            }
        }
    }

    public static int computeJsonDepth(JsonNode node, int currentDepth) {
        if (currentDepth > MAX_JSON_DEPTH + 1) {
            return currentDepth;
        }
        if (node == null || node.isValueNode() || node.isNull()) {
            return currentDepth;
        }
        if (node.isArray()) {
            int max = currentDepth + 1;
            for (JsonNode child : node) {
                max = Math.max(max, computeJsonDepth(child, currentDepth + 1));
            }
            return max;
        }
        if (node.isObject()) {
            int max = currentDepth + 1;
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                max = Math.max(max, computeJsonDepth(entry.getValue(), currentDepth + 1));
            }
            return max;
        }
        return currentDepth;
    }

    private static List<String> computeConflictingFields(
        ValidatedPatch patch,
        UiPreferencesDtos.UiPreferencesV2Dto serverCurrent
    ) {
        List<String> conflicts = new ArrayList<>();
        if (patch.hasAppearance() && !Objects.equals(patch.appearance(), serverCurrent.appearance())) {
            conflicts.add("appearance");
        }
        if (patch.hasTextScale()
            && patch.textScalePercent().compareTo(serverCurrent.textScalePercent()) != 0) {
            conflicts.add("textScalePercent");
        }
        if (patch.hasKeyboard() && !Objects.equals(patch.keyboard(), serverCurrent.keyboard())) {
            conflicts.add("keyboard");
        }
        return List.copyOf(conflicts);
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

    private UiPreferencesDtos.UiPreferencesV2Dto deserializePreferencesOrDefault(String json) {
        try {
            return objectMapper.readValue(json, UiPreferencesDtos.UiPreferencesV2Dto.class);
        } catch (JacksonException ex) {
            return UiPreferencesDtos.UiPreferencesV2Dto.defaults();
        }
    }

    private String serializePreferences(UiPreferencesDtos.UiPreferencesV2Dto prefs) {
        try {
            return objectMapper.writeValueAsString(prefs);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed serializing UI preferences", ex);
        }
    }

    public record PreferenceResult(
        UiPreferencesDtos.MeUiPreferencesResponse response,
        String etag,
        boolean replayed
    ) {
    }

    private record StoredPreferenceRow(String prefsJson, long revision, String subjectScope) {
    }

    private record ReceiptReplayRow(byte[] requestHmac, String payloadJson) {
    }

    private record ValidatedPatch(
        boolean hasAppearance,
        String appearance,
        boolean hasTextScale,
        BigDecimal textScalePercent,
        boolean hasKeyboard,
        UiPreferencesDtos.KeyboardPreferenceV1Dto keyboard
    ) {
    }

    private record CommittedPatch(
        UiPreferencesDtos.MeUiPreferencesResponse response,
        String etag
    ) {
    }
}
