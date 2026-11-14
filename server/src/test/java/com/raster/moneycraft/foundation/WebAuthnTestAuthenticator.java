package com.raster.moneycraft.foundation;

import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * Real cryptographic FIDO2 / WebAuthn ES256 (P-256) software authenticator for integration tests.
 */
public final class WebAuthnTestAuthenticator {

    private final KeyPair keyPair;
    private final byte[] credentialId;
    private final ObjectConverter objectConverter = new ObjectConverter();
    private long signCounter = 1L;

    public WebAuthnTestAuthenticator() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            this.keyPair = kpg.generateKeyPair();
            this.credentialId = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String credentialIdBase64Url() {
        return b64Url(credentialId);
    }

    public EnrolledAttestationPayload createRegistrationAttestation(
        String challengeBase64Url,
        String origin,
        String rpId
    ) {
        try {
            String clientDataJson = "{\"type\":\"webauthn.create\",\"challenge\":\""
                + challengeBase64Url + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}";
            byte[] rpIdHash = MessageDigest.getInstance("SHA-256")
                .digest(rpId.getBytes(StandardCharsets.UTF_8));

            ECPublicKey pub = (ECPublicKey) keyPair.getPublic();
            byte[] xCoord = toFixed32Bytes(pub.getW().getAffineX());
            byte[] yCoord = toFixed32Bytes(pub.getW().getAffineY());

            EC2COSEKey coseKey = EC2COSEKey.create(
                pub,
                COSEAlgorithmIdentifier.ES256
            );
            // Ensure 32-byte coordinates on EC2COSEKey
            Assertions.checkCoordinates(xCoord, yCoord);

            AttestedCredentialData attestedCredentialData = new AttestedCredentialData(
                new AAGUID(UUID.randomUUID()),
                credentialId,
                coseKey
            );

            byte flags = (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV | AuthenticatorData.BIT_AT);
            AuthenticatorData<RegistrationExtensionAuthenticatorOutput> authData =
                new AuthenticatorData<>(rpIdHash, flags, signCounter, attestedCredentialData);
            AttestationObject attestationObject = new AttestationObject(authData, new NoneAttestationStatement());
            byte[] attestationBytes = new AttestationObjectConverter(objectConverter)
                .convertToBytes(attestationObject);

            return new EnrolledAttestationPayload(
                credentialIdBase64Url(),
                b64Url(clientDataJson.getBytes(StandardCharsets.UTF_8)),
                b64Url(attestationBytes)
            );
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public SignedAssertionPayload createAuthenticationAssertion(
        String challengeBase64Url,
        String origin,
        String rpId,
        String userHandleBase64Url
    ) {
        try {
            signCounter++;
            String clientDataJson = "{\"type\":\"webauthn.get\",\"challenge\":\""
                + challengeBase64Url + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}";
            byte[] clientDataBytes = clientDataJson.getBytes(StandardCharsets.UTF_8);
            byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataBytes);

            byte[] rpIdHash = MessageDigest.getInstance("SHA-256")
                .digest(rpId.getBytes(StandardCharsets.UTF_8));
            byte flags = (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV);

            AuthenticatorData<AuthenticationExtensionAuthenticatorOutput> authData =
                new AuthenticatorData<>(rpIdHash, flags, signCounter);
            byte[] authenticatorDataBytes = new AuthenticatorDataConverter(objectConverter).convert(authData);

            byte[] signedPayload = new byte[authenticatorDataBytes.length + clientDataHash.length];
            System.arraycopy(authenticatorDataBytes, 0, signedPayload, 0, authenticatorDataBytes.length);
            System.arraycopy(
                clientDataHash,
                0,
                signedPayload,
                authenticatorDataBytes.length,
                clientDataHash.length
            );

            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initSign(keyPair.getPrivate());
            ecdsa.update(signedPayload);
            byte[] derSig = ecdsa.sign();

            return new SignedAssertionPayload(
                credentialIdBase64Url(),
                b64Url(clientDataBytes),
                b64Url(authenticatorDataBytes),
                b64Url(derSig),
                userHandleBase64Url
            );
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] toFixed32Bytes(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        if (raw.length > 32) {
            return Arrays.copyOfRange(raw, raw.length - 32, raw.length);
        }
        byte[] padded = new byte[32];
        System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        return padded;
    }

    private static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class Assertions {
        private Assertions() {
        }

        static void checkCoordinates(byte[] x, byte[] y) {
            if (x.length != 32 || y.length != 32) {
                throw new IllegalStateException("Expected 32-byte P-256 coordinates");
            }
        }
    }

    public record EnrolledAttestationPayload(
        String credentialId,
        String clientDataJson,
        String attestationObject
    ) {
    }

    public record SignedAssertionPayload(
        String credentialId,
        String clientDataJson,
        String authenticatorData,
        String signature,
        String userHandle
    ) {
    }
}
