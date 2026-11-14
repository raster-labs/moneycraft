package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AdminReceiptService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.authenticator.AuthenticatorImpl;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasskeyWebAuthnService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final List<AuthDtos.PasskeyCredParamDto> SUPPORTED_ALGS = List.of(
        new AuthDtos.PasskeyCredParamDto("public-key", -7),
        new AuthDtos.PasskeyCredParamDto("public-key", -8),
        new AuthDtos.PasskeyCredParamDto("public-key", -257)
    );

    private final MoneyCraftProperties properties;
    private final AuthorityGuard authorityGuard;
    private final AuthService authService;
    private final AdminReceiptService adminReceiptService;
    private final AuditChainService auditChainService;
    private final RbacAuthorizationService rbacAuthorizationService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final JdbcTemplate authorityJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;
    private final WebAuthnManager webAuthnManager;
    private final AttestedCredentialDataConverter credentialDataConverter;

    public PasskeyWebAuthnService(
        MoneyCraftProperties properties,
        AuthorityGuard authorityGuard,
        AuthService authService,
        AdminReceiptService adminReceiptService,
        AuditChainService auditChainService,
        RbacAuthorizationService rbacAuthorizationService,
        JcsCanonicalizer jcsCanonicalizer,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.properties = properties;
        this.authorityGuard = authorityGuard;
        this.authService = authService;
        this.adminReceiptService = adminReceiptService;
        this.auditChainService = auditChainService;
        this.rbacAuthorizationService = rbacAuthorizationService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);

        ObjectConverter objectConverter = new ObjectConverter();
        this.webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter);
        this.credentialDataConverter = new AttestedCredentialDataConverter(objectConverter);
    }

    public AuthDtos.PasskeyCreationOptionsResponse createRegistrationOptions(
        MoneyCraftPrincipal principal,
        String sessionKey,
        AuthDtos.PasskeyRegisterOptionsRequest request
    ) {
        authorityGuard.assertWritable();
        byte[] challengeBytes = new byte[32];
        SECURE_RANDOM.nextBytes(challengeBytes);

        String label = (request != null && request.label() != null && !request.label().isBlank())
            ? request.label().trim()
            : principal.username() + "-passkey";

        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_auth_challenge (
                challenge_id, principal_id, session_primary_id, purpose,
                challenge_bytes, metadata_json, consumed, expires_at
            ) VALUES (?, ?, NULL, 'WEBAUTHN_REGISTER', ?, ?::jsonb, false, clock_timestamp() + interval '5 minutes')
            """,
            UUID.randomUUID(),
            principal.principalId(),
            challengeBytes,
            jcsCanonicalizer.canonicalizeObject(Map.of("sessionKey", sessionKey == null ? "" : sessionKey, "label", label))
        );

        List<AuthDtos.PasskeyCredentialDescriptorDto> existingCreds = authorityJdbcTemplate.query(
            "SELECT credential_id FROM ma_passkey WHERE principal_id = ? AND state = 'ACTIVE'",
            (rs, rowNum) -> new AuthDtos.PasskeyCredentialDescriptorDto(
                base64UrlEncode(rs.getBytes("credential_id")),
                "public-key"
            ),
            principal.principalId()
        );

        byte[] userHandle = uuidToBytes(principal.principalId());
        return new AuthDtos.PasskeyCreationOptionsResponse(
            base64UrlEncode(challengeBytes),
            new AuthDtos.PasskeyRpDto(properties.getWebauthn().getRpName(), properties.getWebauthn().getRpId()),
            new AuthDtos.PasskeyUserDto(
                base64UrlEncode(userHandle),
                principal.username(),
                principal.displayName()
            ),
            SUPPORTED_ALGS,
            60_000L,
            "none",
            existingCreds
        );
    }

    public void verifyRegistration(
        MoneyCraftPrincipal principal,
        AuthDtos.PasskeyRegisterVerifyRequest request
    ) {
        authorityGuard.assertWritable();
        if (request == null || request.clientDataJSON() == null || request.attestationObject() == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PASSKEY-INVALID-PAYLOAD",
                "Invalid Passkey Registration Payload",
                "clientDataJSON and attestationObject are required."
            );
        }

        List<ChallengeRow> challenges = authorityJdbcTemplate.query(
            """
            SELECT challenge_id, challenge_bytes
              FROM ma_auth_challenge
             WHERE principal_id = ?
               AND purpose = 'WEBAUTHN_REGISTER'
               AND consumed = false
               AND expires_at > clock_timestamp()
             ORDER BY created_at DESC
             LIMIT 1
            """,
            (rs, rowNum) -> new ChallengeRow(
                rs.getObject("challenge_id", UUID.class),
                rs.getBytes("challenge_bytes")
            ),
            principal.principalId()
        );

        if (challenges.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PASSKEY-CHALLENGE-EXPIRED",
                "Passkey Challenge Missing or Expired",
                "Initiate /auth/passkeys/register/options before verifying enrollment."
            );
        }

        ChallengeRow activeChallenge = challenges.getFirst();
        authorityJdbcTemplate.update(
            "UPDATE ma_auth_challenge SET consumed = true, consumed_at = clock_timestamp() WHERE challenge_id = ?",
            activeChallenge.challengeId()
        );

        byte[] clientDataBytes = base64UrlDecode(request.clientDataJSON());
        byte[] attestationBytes = base64UrlDecode(request.attestationObject());

        ServerProperty serverProperty = buildServerProperty(activeChallenge.challengeBytes());
        RegistrationParameters params = new RegistrationParameters(serverProperty, null, false, true);
        RegistrationData registrationData;
        try {
            registrationData = webAuthnManager.verify(
                new RegistrationRequest(attestationBytes, clientDataBytes),
                params
            );
        } catch (DataConversionException | VerificationException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PASSKEY-VERIFY-FAILED",
                "WebAuthn Attestation Verification Failed",
                "Passkey registration verification failed: " + ex.getMessage()
            );
        }

        if (registrationData.getAttestationObject() == null
            || registrationData.getAttestationObject().getAuthenticatorData() == null
            || registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData() == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PASSKEY-ATTESTATION-MISSING",
                "Missing Attested Credential Data",
                "Authenticator response did not contain attested public key credential data."
            );
        }

        var authData = registrationData.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData attestedData = authData.getAttestedCredentialData();
        byte[] credentialId = attestedData.getCredentialId();
        byte[] serializedAttestedData = credentialDataConverter.convert(attestedData);
        UUID aaguid = attestedData.getAaguid() == null ? null : attestedData.getAaguid().getValue();
        long signCount = authData.getSignCount();
        String label = (request.label() == null || request.label().isBlank())
            ? principal.username() + "-passkey"
            : request.label().trim();

        UUID passkeyId = UUID.randomUUID();
        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_passkey (
                id, principal_id, credential_id, user_handle, public_key_cose,
                sign_count, label, aaguid, backup_eligible, backup_state, state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
            """,
            passkeyId,
            principal.principalId(),
            credentialId,
            uuidToBytes(principal.principalId()),
            serializedAttestedData,
            signCount,
            label,
            aaguid,
            authData.isFlagBE(),
            authData.isFlagBS()
        );

        UUID installationId = authService.requireActiveInstallationId();
        TenantContextHolder.callWithSystemBypass("passkey-register-audit", () -> {
            businessTxTemplate.executeWithoutResult(status -> {
                adminReceiptService.recordAndMirrorAdminReceipt(
                    installationId,
                    principal.principalId(),
                    null,
                    "PASSKEY_REGISTER",
                    "PASSKEY",
                    passkeyId.toString(),
                    null,
                    null,
                    Map.of("label", label)
                );
                auditChainService.appendInstallationEvent(
                    installationId,
                    principal.principalId(),
                    principal.kind(),
                    "PASSKEY_REGISTER",
                    "PASSKEY",
                    passkeyId.toString(),
                    "SUCCESS",
                    null,
                    Map.of("label", label, "passkeyId", passkeyId.toString())
                );
            });
            return null;
        });
    }

    public AuthDtos.PasskeyRequestOptionsResponse createAuthenticationOptions(
        String sessionKey,
        AuthDtos.PasskeyAuthOptionsRequest request
    ) {
        byte[] challengeBytes = new byte[32];
        SECURE_RANDOM.nextBytes(challengeBytes);

        UUID targetPrincipalId = null;
        List<AuthDtos.PasskeyCredentialDescriptorDto> allowCredentials = List.of();

        if (request != null && request.username() != null && !request.username().isBlank()) {
            List<UUID> pIds = authorityJdbcTemplate.query(
                "SELECT principal_id FROM ma_principal WHERE username = ? AND status = 'ENABLED'",
                (rs, rowNum) -> rs.getObject("principal_id", UUID.class),
                request.username().trim()
            );
            if (!pIds.isEmpty()) {
                targetPrincipalId = pIds.getFirst();
                allowCredentials = authorityJdbcTemplate.query(
                    "SELECT credential_id FROM ma_passkey WHERE principal_id = ? AND state = 'ACTIVE'",
                    (rs, rowNum) -> new AuthDtos.PasskeyCredentialDescriptorDto(
                        base64UrlEncode(rs.getBytes("credential_id")),
                        "public-key"
                    ),
                    targetPrincipalId
                );
            }
        }

        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_auth_challenge (
                challenge_id, principal_id, session_primary_id, purpose,
                challenge_bytes, state_nonce, metadata_json, consumed, expires_at
            ) VALUES (?, ?, NULL, 'WEBAUTHN_AUTHENTICATE', ?, ?, ?::jsonb, false, clock_timestamp() + interval '5 minutes')
            """,
            UUID.randomUUID(),
            targetPrincipalId,
            challengeBytes,
            sessionKey,
            jcsCanonicalizer.canonicalizeObject(Map.of("sessionKey", sessionKey == null ? "" : sessionKey))
        );

        return new AuthDtos.PasskeyRequestOptionsResponse(
            base64UrlEncode(challengeBytes),
            properties.getWebauthn().getRpId(),
            60_000L,
            "preferred",
            allowCredentials
        );
    }

    public MoneyCraftPrincipal verifyAuthentication(
        String sessionKey,
        AuthDtos.PasskeyAuthVerifyRequest request
    ) {
        if (request == null || request.rawId() == null || request.clientDataJSON() == null
            || request.authenticatorData() == null || request.signature() == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PASSKEY-AUTH-INVALID",
                "Invalid Passkey Assertion Payload",
                "rawId, clientDataJSON, authenticatorData, and signature are required."
            );
        }

        byte[] credentialId = base64UrlDecode(request.rawId());
        List<StoredPasskeyRow> passkeys = authorityJdbcTemplate.query(
            """
            SELECT id, principal_id, user_handle, public_key_cose, sign_count
              FROM ma_passkey
             WHERE credential_id = ? AND state = 'ACTIVE'
            """,
            (rs, rowNum) -> new StoredPasskeyRow(
                rs.getObject("id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getBytes("user_handle"),
                rs.getBytes("public_key_cose"),
                rs.getLong("sign_count")
            ),
            credentialId
        );

        if (passkeys.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-PASSKEY-UNKNOWN",
                "Passkey Credential Not Recognized",
                "No active passkey matched the supplied credentialId."
            );
        }
        StoredPasskeyRow passkey = passkeys.getFirst();

        List<ChallengeRow> challenges = authorityJdbcTemplate.query(
            """
            SELECT challenge_id, challenge_bytes
              FROM ma_auth_challenge
             WHERE purpose = 'WEBAUTHN_AUTHENTICATE'
               AND consumed = false
               AND expires_at > clock_timestamp()
               AND (principal_id IS NULL OR principal_id = ?)
             ORDER BY CASE WHEN state_nonce = ? THEN 0 ELSE 1 END, created_at DESC
             LIMIT 1
            """,
            (rs, rowNum) -> new ChallengeRow(
                rs.getObject("challenge_id", UUID.class),
                rs.getBytes("challenge_bytes")
            ),
            passkey.principalId(),
            sessionKey
        );

        if (challenges.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PASSKEY-CHALLENGE-EXPIRED",
                "Passkey Authentication Challenge Expired",
                "Initiate /auth/passkeys/authenticate/options before submitting assertion."
            );
        }

        ChallengeRow activeChallenge = challenges.getFirst();
        authorityJdbcTemplate.update(
            "UPDATE ma_auth_challenge SET consumed = true, consumed_at = clock_timestamp() WHERE challenge_id = ?",
            activeChallenge.challengeId()
        );

        AttestedCredentialData attestedCredentialData = credentialDataConverter.convert(passkey.publicKeyCose());
        AuthenticatorImpl authenticator = new AuthenticatorImpl(
            attestedCredentialData,
            null,
            passkey.signCount()
        );

        byte[] userHandleBytes = request.userHandle() == null || request.userHandle().isBlank()
            ? passkey.userHandle()
            : base64UrlDecode(request.userHandle());
        byte[] authenticatorDataBytes = base64UrlDecode(request.authenticatorData());
        byte[] clientDataJsonBytes = base64UrlDecode(request.clientDataJSON());
        byte[] signatureBytes = base64UrlDecode(request.signature());

        ServerProperty serverProperty = buildServerProperty(activeChallenge.challengeBytes());
        AuthenticationParameters authParams = new AuthenticationParameters(
            serverProperty,
            authenticator,
            List.of(credentialId),
            false,
            true
        );

        AuthenticationData verifiedData;
        try {
            verifiedData = webAuthnManager.verify(
                new AuthenticationRequest(
                    credentialId,
                    userHandleBytes,
                    authenticatorDataBytes,
                    clientDataJsonBytes,
                    signatureBytes
                ),
                authParams
            );
        } catch (DataConversionException | VerificationException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-PASSKEY-ASSERTION-INVALID",
                "Passkey Signature Verification Failed",
                "WebAuthn assertion could not be verified: " + ex.getMessage()
            );
        }

        long newSignCount = verifiedData.getAuthenticatorData() == null
            ? passkey.signCount()
            : verifiedData.getAuthenticatorData().getSignCount();

        authorityJdbcTemplate.update(
            """
            UPDATE ma_passkey
               SET sign_count = ?,
                   last_used_at = clock_timestamp(),
                   version_no = version_no + 1
             WHERE id = ?
            """,
            newSignCount,
            passkey.id()
        );
        authorityJdbcTemplate.update(
            "UPDATE ma_principal SET last_authenticated_at = clock_timestamp() WHERE principal_id = ?",
            passkey.principalId()
        );

        return rbacAuthorizationService.loadPrincipalProfile(passkey.principalId());
    }

    private ServerProperty buildServerProperty(byte[] challengeBytes) {
        Set<Origin> origins = new LinkedHashSet<>();
        for (String configured : properties.getWebauthn().getAllowedOrigins()) {
            if (configured != null && !configured.isBlank()) {
                origins.add(new Origin(configured.trim()));
            }
        }
        if (origins.isEmpty()) {
            origins.add(new Origin(properties.getBaseUrl()));
        }
        return new ServerProperty(
            origins,
            properties.getWebauthn().getRpId(),
            new DefaultChallenge(challengeBytes)
        );
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] base64UrlDecode(String raw) {
        try {
            return Base64.getUrlDecoder().decode(raw);
        } catch (IllegalArgumentException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PASSKEY-BASE64-INVALID",
                "Invalid Base64URL Encoding",
                "WebAuthn binary parameter was not valid Base64URL."
            );
        }
    }

    private record ChallengeRow(UUID challengeId, byte[] challengeBytes) {
    }

    private record StoredPasskeyRow(
        UUID id,
        UUID principalId,
        byte[] userHandle,
        byte[] publicKeyCose,
        long signCount
    ) {
    }
}
