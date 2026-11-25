package com.raster.moneycraft.desktop.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Cryptographic RFC 7636 S256 PKCE + state + OIDC nonce transaction record bound to a single
 * ephemeral loopback redirect URI (SWG-002).
 */
public record PkceTransaction(
    String codeVerifier,
    String codeChallengeS256,
    String state,
    String nonce,
    URI redirectUri,
    Instant expiresAt
) {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public PkceTransaction {
        Objects.requireNonNull(codeVerifier, "codeVerifier must not be null");
        Objects.requireNonNull(codeChallengeS256, "codeChallengeS256 must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(nonce, "nonce must not be null");
        Objects.requireNonNull(redirectUri, "redirectUri must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        if (codeVerifier.length() < 43 || codeVerifier.length() > 128) {
            throw new NativeAuthSecurityException(
                "MC-PKCE-VERIFIER-LENGTH",
                "RFC 7636 PKCE code_verifier must be 43..128 characters."
            );
        }
    }

    public static PkceTransaction generate(URI redirectUri, Instant expiresAt) {
        byte[] verifierBytes = new byte[32];
        byte[] stateBytes = new byte[32];
        byte[] nonceBytes = new byte[32];
        SECURE_RANDOM.nextBytes(verifierBytes);
        SECURE_RANDOM.nextBytes(stateBytes);
        SECURE_RANDOM.nextBytes(nonceBytes);

        String verifier = URL_ENCODER.encodeToString(verifierBytes);
        String challenge = computeS256Challenge(verifier);
        String state = URL_ENCODER.encodeToString(stateBytes);
        String nonce = URL_ENCODER.encodeToString(nonceBytes);
        return new PkceTransaction(verifier, challenge, state, nonce, redirectUri, expiresAt);
    }

    public static String computeS256Challenge(String codeVerifier) {
        Objects.requireNonNull(codeVerifier, "codeVerifier must not be null");
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in Java runtime.", ex);
        }
    }

    public boolean matchesStateConstantTime(String candidateState) {
        if (candidateState == null) {
            return false;
        }
        return MessageDigest.isEqual(
            state.getBytes(StandardCharsets.UTF_8),
            candidateState.getBytes(StandardCharsets.UTF_8)
        );
    }
}
