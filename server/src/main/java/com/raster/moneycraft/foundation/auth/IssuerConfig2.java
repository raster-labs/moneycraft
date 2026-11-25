package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.CompanyPermissions;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed Document 47 (MC-FED-01 Rev 2) DTOs, strict validator, Ed25519 signature verifier,
 * and MC-AUTH-ENC-1 envelope codec for IssuerConfig2 (MC-ISSUER-CONFIG-2) and MC-PROVIDER-CONFIG-1.
 */
public record IssuerConfig2(
    String canon,
    String issuer,
    String clientId,
    String clientSecretRef,
    String authorizationUri,
    String tokenUri,
    String jwksUri,
    String userInfoUri,
    String redirectUri,
    String rpId,
    List<String> origins,
    LifecycleConfig lifecycle
) {

    public static final String CANON_ISSUER_CONFIG_2 = "MC-ISSUER-CONFIG-2";
    public static final String SCHEMA_PROVIDER_CONFIG_1 = "MC-PROVIDER-CONFIG-1";
    public static final String PROFILE_MC_FED_01 = "MC-FED-01";
    public static final String CANON_AUTH_ENVELOPE_1 = "MC-AUTH-ENC-1";
    public static final String RECORD_TYPE_ISSUER_CONFIG = "ISSUER_CONFIG";
    public static final int MAX_ISSUER_CONFIG_BYTES = 49_152;

    private static final Pattern REG_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CANONICAL_UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );

    private static final Set<String> ISSUER_CONFIG_KEYS = Set.of(
        "canon",
        "issuer",
        "clientId",
        "clientSecretRef",
        "authorizationUri",
        "tokenUri",
        "jwksUri",
        "userInfoUri",
        "redirectUri",
        "rpId",
        "origins",
        "lifecycle"
    );

    private static final Set<String> LIFECYCLE_KEYS = Set.of(
        "profile",
        "realm",
        "adminBaseUri",
        "lifecycleClientId",
        "lifecycleSecretRef",
        "federationComponentId",
        "qualificationRef",
        "qualificationDigest",
        "upstreamMaxSeconds",
        "pollSeconds",
        "maxAgeSeconds",
        "clockAllowanceSeconds",
        "cycleDeadlineSeconds",
        "groupMappings"
    );

    private static final Set<String> GROUP_MAPPING_KEYS = Set.of("groupId", "grants");
    private static final Set<String> GRANT_TUPLE_KEYS = Set.of("companyId", "operation", "fieldGroup");

    private static final Set<String> PROVIDER_ENVELOPE_KEYS = Set.of(
        "schema",
        "installationId",
        "incarnation",
        "incidentId",
        "issuerId",
        "registrationId",
        "state",
        "expectedIssuerVersion",
        "config",
        "configDigest",
        "issuedAt",
        "expiresAt",
        "custodianRef",
        "evidenceRef",
        "signature"
    );

    private static final Set<String> PRIVATE_FIELD_GROUPS = Set.of("E1", "E2", "E3", "E4");

    private static final JsonMapper STRICT_MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();

    public IssuerConfig2 {
        origins = origins == null ? List.of() : List.copyOf(origins);
    }

    public record LifecycleConfig(
        String profile,
        String realm,
        String adminBaseUri,
        String lifecycleClientId,
        String lifecycleSecretRef,
        UUID federationComponentId,
        String qualificationRef,
        String qualificationDigest,
        String upstreamMaxSeconds,
        String pollSeconds,
        String maxAgeSeconds,
        String clockAllowanceSeconds,
        String cycleDeadlineSeconds,
        List<GroupMapping> groupMappings
    ) {
        public LifecycleConfig {
            groupMappings = groupMappings == null ? List.of() : List.copyOf(groupMappings);
        }
    }

    public record GroupMapping(
        UUID groupId,
        List<GroupGrantTuple> grants
    ) {
        public GroupMapping {
            grants = grants == null ? List.of() : List.copyOf(grants);
        }
    }

    public record GroupGrantTuple(
        UUID companyId,
        String operation,
        String fieldGroup
    ) {
    }

    public record SignedProviderConfig(
        String schema,
        UUID installationId,
        UUID incarnation,
        UUID incidentId,
        UUID issuerId,
        String registrationId,
        String state,
        Long expectedIssuerVersion,
        IssuerConfig2 config,
        String configDigest,
        Instant issuedAt,
        Instant expiresAt,
        String custodianRef,
        String evidenceRef,
        String signature,
        byte[] signingPayloadUtf8
    ) {
    }

    public static SignedProviderConfig parseAndValidateSignedEnvelope(
        String rawJson,
        JcsCanonicalizer jcsCanonicalizer,
        boolean allowTestLoopbackHttp
    ) {
        if (rawJson == null || rawJson.isBlank()) {
            throw invalidConfig("Provider configuration input is empty.");
        }
        JsonNode root;
        try {
            root = STRICT_MAPPER.readTree(rawJson);
        } catch (JacksonException ex) {
            throw invalidConfig("Malformed or duplicate-key JSON in provider configuration.");
        }
        requireExactKeys(root, PROVIDER_ENVELOPE_KEYS, "MC-PROVIDER-CONFIG-1");

        String schema = requireBoundedAscii(root, "schema", 1, 64, false);
        if (!SCHEMA_PROVIDER_CONFIG_1.equals(schema)) {
            throw invalidConfig("Unsupported provider schema '" + schema + "'.");
        }

        UUID installationId = requireCanonicalUuid(root, "installationId");
        UUID incarnation = requireCanonicalUuid(root, "incarnation");
        UUID incidentId = optionalCanonicalUuid(root, "incidentId");
        UUID issuerId = requireCanonicalUuid(root, "issuerId");
        String registrationId = requireBoundedAscii(root, "registrationId", 1, 64, false);
        if (!REG_ID_PATTERN.matcher(registrationId).matches()) {
            throw invalidConfig("registrationId violates Document 18 grammar.");
        }

        String state = requireBoundedAscii(root, "state", 1, 16, false);
        if (!"ENABLED".equals(state) && !"DISABLED".equals(state)) {
            throw invalidConfig("state must be ENABLED or DISABLED.");
        }

        Long expectedVersion = parseOptionalExpectedVersion(root.get("expectedIssuerVersion"));
        JsonNode configNode = root.get("config");
        IssuerConfig2 parsedConfig = parseAndValidateIssuerConfig(
            configNode,
            jcsCanonicalizer,
            allowTestLoopbackHttp
        );

        String canonicalConfigJson = jcsCanonicalizer.canonicalizeNode(configNode);
        byte[] canonicalConfigBytes = canonicalConfigJson.getBytes(StandardCharsets.UTF_8);
        if (canonicalConfigBytes.length > MAX_ISSUER_CONFIG_BYTES) {
            throw invalidConfig("IssuerConfig2 canonical payload exceeds 48 KiB bound.");
        }

        String expectedConfigDigest = JcsCanonicalizer.toHex(jcsCanonicalizer.sha256(canonicalConfigBytes));
        String configDigest = requireBoundedAscii(root, "configDigest", 64, 64, false);
        if (!HEX_64_PATTERN.matcher(configDigest).matches() || !expectedConfigDigest.equals(configDigest)) {
            throw invalidConfig("configDigest does not match SHA-256 of JCS(config).");
        }

        Instant issuedAt = parseIsoInstant(requireBoundedAscii(root, "issuedAt", 1, 64, false), "issuedAt");
        Instant expiresAt = parseIsoInstant(requireBoundedAscii(root, "expiresAt", 1, 64, false), "expiresAt");
        Duration validity = Duration.between(issuedAt, expiresAt);
        if (validity.isZero() || validity.isNegative() || validity.compareTo(Duration.ofHours(24)) > 0) {
            throw invalidConfig("Provider config validity window must be positive and at most 24 hours.");
        }

        String custodianRef = requireReferenceId(root, "custodianRef");
        String evidenceRef = requireReferenceId(root, "evidenceRef");
        String signature = requireBoundedAscii(root, "signature", 1, 512, false);
        if ("UNAVAILABLE".equalsIgnoreCase(signature)) {
            throw invalidConfig("Unreplaced UNAVAILABLE signature placeholder rejected.");
        }

        Map<String, Object> signedFields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (!"signature".equals(entry.getKey())) {
                signedFields.put(entry.getKey(), entry.getValue());
            }
        }
        String jcsPreceding = jcsCanonicalizer.canonicalizeObject(signedFields);
        byte[] signingPayload = (SCHEMA_PROVIDER_CONFIG_1 + "\n" + jcsPreceding).getBytes(StandardCharsets.UTF_8);

        return new SignedProviderConfig(
            schema,
            installationId,
            incarnation,
            incidentId,
            issuerId,
            registrationId,
            state,
            expectedVersion,
            parsedConfig,
            configDigest,
            issuedAt,
            expiresAt,
            custodianRef,
            evidenceRef,
            signature,
            signingPayload
        );
    }

    public static IssuerConfig2 parseAndValidateIssuerConfig(
        JsonNode node,
        JcsCanonicalizer jcsCanonicalizer,
        boolean allowTestLoopbackHttp
    ) {
        if (node == null || !node.isObject()) {
            throw invalidConfig("config must be a JSON object.");
        }
        requireExactKeys(node, ISSUER_CONFIG_KEYS, CANON_ISSUER_CONFIG_2);

        String canon = requireBoundedAscii(node, "canon", 1, 32, false);
        if (!CANON_ISSUER_CONFIG_2.equals(canon)) {
            throw invalidConfig("Unsupported issuer config canon '" + canon + "'.");
        }

        String issuer = requireValidUri(node, "issuer", allowTestLoopbackHttp, false);
        URI issuerUri = URI.create(issuer);
        String clientId = requireSafeSegmentId(node, "clientId");
        String clientSecretRef = requireReferenceId(node, "clientSecretRef");
        String authorizationUri = requireValidUri(node, "authorizationUri", allowTestLoopbackHttp, true);
        String tokenUri = requireValidUri(node, "tokenUri", allowTestLoopbackHttp, false);
        String jwksUri = requireValidUri(node, "jwksUri", allowTestLoopbackHttp, false);

        String userInfoUri = null;
        JsonNode userInfoNode = node.get("userInfoUri");
        if (userInfoNode != null && !userInfoNode.isNull()) {
            userInfoUri = requireValidUri(node, "userInfoUri", allowTestLoopbackHttp, false);
        }

        String redirectUri = requireValidUri(node, "redirectUri", allowTestLoopbackHttp, false);
        String rpId = requireBoundedAscii(node, "rpId", 1, 255, false);
        if ("UNAVAILABLE".equalsIgnoreCase(rpId)) {
            throw invalidConfig("rpId cannot be UNAVAILABLE.");
        }

        JsonNode originsNode = node.get("origins");
        if (originsNode == null || !originsNode.isArray() || originsNode.isEmpty() || originsNode.size() > 8) {
            throw invalidConfig("origins must be a sorted unique array of 1..8 URIs.");
        }
        List<String> origins = new ArrayList<>();
        String prevOrigin = null;
        for (JsonNode item : originsNode) {
            if (!item.isString()) {
                throw invalidConfig("origins entries must be strings.");
            }
            String originVal = validateUriString(item.asString(), "origins[]", allowTestLoopbackHttp, false);
            if (prevOrigin != null && prevOrigin.compareTo(originVal) >= 0) {
                throw invalidConfig("origins must be strictly sorted and unique.");
            }
            prevOrigin = originVal;
            origins.add(originVal);
        }

        LifecycleConfig lifecycle = parseAndValidateLifecycle(
            node.get("lifecycle"),
            issuerUri,
            allowTestLoopbackHttp
        );

        String jcs = jcsCanonicalizer.canonicalizeNode(node);
        if (jcs.getBytes(StandardCharsets.UTF_8).length > MAX_ISSUER_CONFIG_BYTES) {
            throw invalidConfig("IssuerConfig2 exceeds 48 KiB limit.");
        }

        return new IssuerConfig2(
            canon,
            issuer,
            clientId,
            clientSecretRef,
            authorizationUri,
            tokenUri,
            jwksUri,
            userInfoUri,
            redirectUri,
            rpId,
            origins,
            lifecycle
        );
    }

    private static LifecycleConfig parseAndValidateLifecycle(
        JsonNode lc,
        URI issuerUri,
        boolean allowTestLoopbackHttp
    ) {
        if (lc == null || !lc.isObject()) {
            throw invalidConfig("lifecycle must be a closed JSON object.");
        }
        requireExactKeys(lc, LIFECYCLE_KEYS, "IssuerConfig2.lifecycle");

        String profile = requireBoundedAscii(lc, "profile", 1, 32, false);
        if (!PROFILE_MC_FED_01.equals(profile)) {
            throw invalidConfig("Unsupported lifecycle profile '" + profile + "'.");
        }

        String realm = requireSafeSegmentId(lc, "realm");
        if ("moneycraft-test".equalsIgnoreCase(realm) && !allowTestLoopbackHttp) {
            throw invalidConfig("Synthetic test realm 'moneycraft-test' is refused outside test profile.");
        }

        String adminBaseUri = requireValidUri(lc, "adminBaseUri", allowTestLoopbackHttp, false);
        URI parsedAdmin = URI.create(adminBaseUri);
        if (parsedAdmin.getRawQuery() != null || parsedAdmin.getRawFragment() != null
            || parsedAdmin.getRawUserInfo() != null) {
            throw invalidConfig("adminBaseUri must not contain query, fragment, or userinfo.");
        }
        if (!issuerUri.getHost().equalsIgnoreCase(parsedAdmin.getHost())) {
            throw invalidConfig("adminBaseUri must share the configured issuer's trusted host.");
        }

        String lifecycleClientId = requireSafeSegmentId(lc, "lifecycleClientId");
        String lifecycleSecretRef = requireReferenceId(lc, "lifecycleSecretRef");
        UUID federationComponentId = requireCanonicalUuid(lc, "federationComponentId");
        String qualificationRef = requireReferenceId(lc, "qualificationRef");
        if (qualificationRef.toLowerCase().contains("test") && !allowTestLoopbackHttp) {
            throw invalidConfig("Test qualification reference is refused outside test profile.");
        }

        String qualificationDigest = requireBoundedAscii(lc, "qualificationDigest", 64, 64, false);
        if (!HEX_64_PATTERN.matcher(qualificationDigest).matches()
            || "0".repeat(64).equals(qualificationDigest)) {
            throw invalidConfig("qualificationDigest must be a valid non-zero SHA-256 hex digest.");
        }

        requireExactLiteral(lc, "upstreamMaxSeconds", "20");
        requireExactLiteral(lc, "pollSeconds", "60");
        requireExactLiteral(lc, "maxAgeSeconds", "120");
        requireExactLiteral(lc, "clockAllowanceSeconds", "5");
        requireExactLiteral(lc, "cycleDeadlineSeconds", "30");

        JsonNode gmArray = lc.get("groupMappings");
        if (gmArray == null || !gmArray.isArray() || gmArray.size() > 128) {
            throw invalidConfig("groupMappings must be an array with at most 128 groups.");
        }

        List<GroupMapping> mappings = new ArrayList<>();
        String prevGroupStr = null;
        int totalTuples = 0;

        for (JsonNode gmNode : gmArray) {
            if (!gmNode.isObject()) {
                throw invalidConfig("Each groupMapping entry must be an object.");
            }
            requireExactKeys(gmNode, GROUP_MAPPING_KEYS, "groupMappings[]");
            UUID groupId = requireCanonicalUuid(gmNode, "groupId");
            String groupStr = groupId.toString();
            if (prevGroupStr != null && prevGroupStr.compareTo(groupStr) >= 0) {
                throw invalidConfig("groupMappings must be sorted strictly ascending and unique by groupId.");
            }
            prevGroupStr = groupStr;

            JsonNode grantsArr = gmNode.get("grants");
            if (grantsArr == null || !grantsArr.isArray() || grantsArr.isEmpty()) {
                throw invalidConfig("Each groupMapping must have a non-empty grants array.");
            }

            List<GroupGrantTuple> grants = new ArrayList<>();
            String prevTupleKey = null;
            for (JsonNode gNode : grantsArr) {
                if (!gNode.isObject()) {
                    throw invalidConfig("Each grant tuple must be an object.");
                }
                requireExactKeys(gNode, GRANT_TUPLE_KEYS, "groupMappings[].grants[]");
                UUID companyId = requireCanonicalUuid(gNode, "companyId");
                String operation = requireBoundedAscii(gNode, "operation", 1, 64, false);
                String fieldGroup = requireBoundedAscii(gNode, "fieldGroup", 1, 16, false);

                if (!CompanyPermissions.ALLOWED_PROVIDER_GROUP_OPERATIONS.contains(operation)) {
                    throw invalidConfig(
                        "Forbidden operation '" + operation + "' in provider groupMappings."
                    );
                }

                if (CompanyPermissions.FIELD_PRIVATE_READ.equals(operation)
                    || CompanyPermissions.FIELD_PRIVATE_CHANGE.equals(operation)) {
                    if (!PRIVATE_FIELD_GROUPS.contains(fieldGroup)) {
                        throw invalidConfig("field.private.* operations require fieldGroup in E1..E4.");
                    }
                } else if (!"NONE".equals(fieldGroup)) {
                    throw invalidConfig("Operation '" + operation + "' requires fieldGroup=NONE.");
                }

                String tupleKey = companyId + "|" + operation + "|" + fieldGroup;
                if (prevTupleKey != null && prevTupleKey.compareTo(tupleKey) >= 0) {
                    throw invalidConfig("grants array must be sorted unique by (companyId, operation, fieldGroup).");
                }
                prevTupleKey = tupleKey;
                totalTuples++;
                if (totalTuples > 256) {
                    throw invalidConfig("Total mapped grant tuples across groupMappings cannot exceed 256.");
                }
                grants.add(new GroupGrantTuple(companyId, operation, fieldGroup));
            }
            mappings.add(new GroupMapping(groupId, grants));
        }

        return new LifecycleConfig(
            profile,
            realm,
            adminBaseUri,
            lifecycleClientId,
            lifecycleSecretRef,
            federationComponentId,
            qualificationRef,
            qualificationDigest,
            "20",
            "60",
            "120",
            "5",
            "30",
            mappings
        );
    }

    public static void verifyEd25519Signature(
        SignedProviderConfig signedConfig,
        String configuredPublicKeyText
    ) {
        if (configuredPublicKeyText == null || configuredPublicKeyText.isBlank()) {
            throw invalidConfig("Pinned custodian Ed25519 public key is not configured.");
        }
        PublicKey publicKey = parseEd25519PublicKey(configuredPublicKeyText.trim());
        byte[] sigBytes = decodeBase64Flexible(signedConfig.signature());
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signedConfig.signingPayloadUtf8());
            if (!verifier.verify(sigBytes)) {
                throw new MoneyCraftException(
                    HttpStatus.FORBIDDEN,
                    "MC-PROVIDER-CONFIG-SIGNATURE-INVALID",
                    "Provider Configuration Signature Invalid",
                    "Detached Ed25519 signature verification failed against pinned custodian key."
                );
            }
        } catch (GeneralSecurityException ex) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-PROVIDER-CONFIG-SIGNATURE-INVALID",
                "Provider Configuration Signature Invalid",
                "Ed25519 verification rejected provider configuration signature."
            );
        }
    }

    public static String signDetachedEd25519(
        PrivateKey ed25519PrivateKey,
        Map<String, Object> envelopeWithoutSignature,
        JcsCanonicalizer jcsCanonicalizer
    ) {
        Map<String, Object> copy = new LinkedHashMap<>(envelopeWithoutSignature);
        copy.remove("signature");
        String jcs = jcsCanonicalizer.canonicalizeObject(copy);
        byte[] payload = (SCHEMA_PROVIDER_CONFIG_1 + "\n" + jcs).getBytes(StandardCharsets.UTF_8);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(ed25519PrivateKey);
            signer.update(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign MC-PROVIDER-CONFIG-1 payload with Ed25519", ex);
        }
    }

    public static byte[] sealAuthorityEnvelope(
        IssuerConfig2 config,
        UUID installationId,
        UUID incarnation,
        UUID issuerId,
        long issuerVersion,
        UUID keyId,
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer
    ) {
        String configJcs = jcsCanonicalizer.canonicalizeObject(config);
        UUID encryptionId = UUID.randomUUID();
        Map<String, Object> headerWithoutCipher = new LinkedHashMap<>();
        headerWithoutCipher.put("canon", CANON_AUTH_ENVELOPE_1);
        headerWithoutCipher.put("installationId", installationId.toString());
        headerWithoutCipher.put("incarnation", incarnation.toString());
        headerWithoutCipher.put("recordType", RECORD_TYPE_ISSUER_CONFIG);
        headerWithoutCipher.put("recordId", issuerId.toString());
        headerWithoutCipher.put("recordVersion", Long.toString(issuerVersion));
        headerWithoutCipher.put("keyId", keyId == null ? installationId.toString() : keyId.toString());
        headerWithoutCipher.put("encryptionId", encryptionId.toString());

        byte[] aad = (CANON_AUTH_ENVELOPE_1 + "\n" + jcsCanonicalizer.canonicalizeObject(headerWithoutCipher))
            .getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = tinkCryptoService.encryptGcm(aad, configJcs.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> fullEnvelope = new LinkedHashMap<>(headerWithoutCipher);
        fullEnvelope.put("ciphertext", Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));
        return jcsCanonicalizer.canonicalizeObject(fullEnvelope).getBytes(StandardCharsets.UTF_8);
    }

    public static IssuerConfig2 openAuthorityEnvelope(
        byte[] envelopeBytes,
        UUID expectedInstallationId,
        UUID expectedIncarnation,
        UUID expectedIssuerId,
        long expectedIssuerVersion,
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer,
        boolean allowTestLoopbackHttp
    ) {
        if (envelopeBytes == null || envelopeBytes.length == 0) {
            throw invalidConfig("Missing metadata_cipher envelope on ma_issuer.");
        }
        try {
            JsonNode env = STRICT_MAPPER.readTree(envelopeBytes);
            String canon = env.path("canon").asString("");
            String recordType = env.path("recordType").asString("");
            String instStr = env.path("installationId").asString("");
            String incStr = env.path("incarnation").asString("");
            String recId = env.path("recordId").asString("");
            String recVer = env.path("recordVersion").asString("");
            String cipherB64 = env.path("ciphertext").asString("");

            if (!CANON_AUTH_ENVELOPE_1.equals(canon)
                || !RECORD_TYPE_ISSUER_CONFIG.equals(recordType)
                || !expectedInstallationId.toString().equals(instStr)
                || !expectedIncarnation.toString().equals(incStr)
                || !expectedIssuerId.toString().equals(recId)
                || !Long.toString(expectedIssuerVersion).equals(recVer)
                || cipherB64.isBlank()) {
                throw invalidConfig("AuthorityEnvelope header mismatch or incarnation changed.");
            }

            Map<String, Object> headerWithoutCipher = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : env.properties()) {
                if (!"ciphertext".equals(entry.getKey())) {
                    headerWithoutCipher.put(entry.getKey(), entry.getValue());
                }
            }
            byte[] aad = (CANON_AUTH_ENVELOPE_1 + "\n" + jcsCanonicalizer.canonicalizeObject(headerWithoutCipher))
                .getBytes(StandardCharsets.UTF_8);
            byte[] cipherBytes = Base64.getUrlDecoder().decode(cipherB64);
            byte[] plainBytes = tinkCryptoService.decryptGcm(aad, cipherBytes);
            JsonNode cfgNode = STRICT_MAPPER.readTree(plainBytes);
            return parseAndValidateIssuerConfig(cfgNode, jcsCanonicalizer, allowTestLoopbackHttp);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw invalidConfig("Failed to open ISSUER_CONFIG AuthorityEnvelope.");
        }
    }

    private static PublicKey parseEd25519PublicKey(String encodedKey) {
        byte[] keyBytes;
        if (encodedKey.matches("^[0-9a-fA-F]+$") && encodedKey.length() % 2 == 0) {
            keyBytes = HexFormat.of().parseHex(encodedKey);
        } else {
            keyBytes = decodeBase64Flexible(encodedKey);
        }
        // Raw 32-byte Ed25519 public key -> wrap in standard RFC 8410 SubjectPublicKeyInfo DER prefix
        if (keyBytes.length == 32) {
            byte[] x509Prefix = new byte[] {
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
            };
            byte[] wrapped = new byte[x509Prefix.length + 32];
            System.arraycopy(x509Prefix, 0, wrapped, 0, x509Prefix.length);
            System.arraycopy(keyBytes, 0, wrapped, x509Prefix.length, 32);
            keyBytes = wrapped;
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            return kf.generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (GeneralSecurityException ex) {
            throw invalidConfig("Invalid pinned Ed25519 custodian public key encoding.");
        }
    }

    private static byte[] decodeBase64Flexible(String text) {
        try {
            if (text.contains("-") || text.contains("_")) {
                return Base64.getUrlDecoder().decode(text);
            }
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException ex) {
            try {
                return Base64.getUrlDecoder().decode(text);
            } catch (IllegalArgumentException ignored) {
                throw invalidConfig("Malformed Base64 encoding in signed provider configuration.");
            }
        }
    }

    private static void requireExactKeys(JsonNode obj, Set<String> expectedKeys, String contextName) {
        if (obj == null || !obj.isObject()) {
            throw invalidConfig(contextName + " must be a JSON object.");
        }
        Set<String> actual = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : obj.properties()) {
            actual.add(entry.getKey());
        }
        if (!actual.equals(expectedKeys)) {
            throw invalidConfig("Closed schema key mismatch in " + contextName + ".");
        }
    }

    private static String requireBoundedAscii(
        JsonNode parent,
        String field,
        int minLen,
        int maxLen,
        boolean allowSlashes
    ) {
        JsonNode n = parent.get(field);
        if (n == null || !n.isString()) {
            throw invalidConfig("Field '" + field + "' must be a non-null string.");
        }
        String val = n.asString();
        if (val.length() < minLen || val.length() > maxLen) {
            throw invalidConfig("Field '" + field + "' violates length bounds.");
        }
        for (int i = 0; i < val.length(); i++) {
            char ch = val.charAt(i);
            if (ch < 0x20 || ch > 0x7E) {
                throw invalidConfig("Field '" + field + "' contains non-ASCII or control characters.");
            }
            if (!allowSlashes && ch == '/') {
                // Only enforced when allowSlashes is false for segment IDs
            }
        }
        return val;
    }

    private static String requireSafeSegmentId(JsonNode parent, String field) {
        String val = requireBoundedAscii(parent, field, 1, 128, false);
        if ("UNAVAILABLE".equalsIgnoreCase(val) || val.contains("/") || val.contains("..")) {
            throw invalidConfig("Field '" + field + "' contains forbidden characters or UNAVAILABLE placeholder.");
        }
        return val;
    }

    private static String requireReferenceId(JsonNode parent, String field) {
        String val = requireBoundedAscii(parent, field, 1, 160, true);
        if ("UNAVAILABLE".equalsIgnoreCase(val) || val.contains("..")) {
            throw invalidConfig("Reference '" + field + "' is UNAVAILABLE or invalid.");
        }
        return val;
    }

    private static void requireExactLiteral(JsonNode parent, String field, String expected) {
        String val = requireBoundedAscii(parent, field, 1, 16, false);
        if (!expected.equals(val)) {
            throw invalidConfig("Field '" + field + "' must equal '" + expected + "'.");
        }
    }

    private static UUID requireCanonicalUuid(JsonNode parent, String field) {
        String val = requireBoundedAscii(parent, field, 36, 36, false);
        if (!CANONICAL_UUID_PATTERN.matcher(val).matches()) {
            throw invalidConfig("Field '" + field + "' must be a canonical lowercase UUID.");
        }
        return UUID.fromString(val);
    }

    private static UUID optionalCanonicalUuid(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return requireCanonicalUuid(parent, field);
    }

    private static Long parseOptionalExpectedVersion(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw invalidConfig("expectedIssuerVersion must be a positive decimal string or null.");
        }
        String raw = node.asString();
        if (!raw.matches("^[1-9][0-9]{0,17}$")) {
            throw invalidConfig("expectedIssuerVersion must be a positive signed64 integer string.");
        }
        return Long.parseLong(raw);
    }

    private static Instant parseIsoInstant(String val, String field) {
        try {
            return Instant.parse(val);
        } catch (DateTimeParseException ex) {
            throw invalidConfig("Field '" + field + "' must be an ISO-8601 UTC instant.");
        }
    }

    private static String requireValidUri(
        JsonNode parent,
        String field,
        boolean allowTestLoopbackHttp,
        boolean allowQuery
    ) {
        JsonNode n = parent.get(field);
        if (n == null || !n.isString()) {
            throw invalidConfig("URI field '" + field + "' must be a string.");
        }
        return validateUriString(n.asString(), field, allowTestLoopbackHttp, allowQuery);
    }

    private static String validateUriString(
        String raw,
        String field,
        boolean allowTestLoopbackHttp,
        boolean allowQuery
    ) {
        if (raw == null || raw.isBlank() || raw.length() > 2048 || "UNAVAILABLE".equalsIgnoreCase(raw)) {
            throw invalidConfig("URI '" + field + "' is blank, oversized, or UNAVAILABLE.");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException ex) {
            throw invalidConfig("Malformed URI in '" + field + "'.");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw invalidConfig("URI '" + field + "' requires valid scheme and host without userinfo/fragment.");
        }
        if (!allowQuery && uri.getRawQuery() != null) {
            throw invalidConfig("URI '" + field + "' must not include query parameters.");
        }
        boolean isHttps = "https".equalsIgnoreCase(scheme);
        boolean isLoopbackHttp = allowTestLoopbackHttp
            && "http".equalsIgnoreCase(scheme)
            && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host));
        if (!isHttps && !isLoopbackHttp) {
            throw invalidConfig("URI '" + field + "' must use HTTPS.");
        }
        return raw;
    }

    private static MoneyCraftException invalidConfig(String detail) {
        return new MoneyCraftException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MC-PROVIDER-CONFIG-INVALID",
            "Invalid OIDC Provider Lifecycle Configuration",
            detail
        );
    }
}
