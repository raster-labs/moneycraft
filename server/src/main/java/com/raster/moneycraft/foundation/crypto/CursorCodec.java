package com.raster.moneycraft.foundation.crypto;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class CursorCodec {

    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"MC-CURSOR\"}";
    private static final String HEADER_B64 = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));

    private final TinkCryptoService tinkCryptoService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate authorityJdbcTemplate;

    public CursorCodec(
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate
    ) {
        this.tinkCryptoService = tinkCryptoService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
    }

    public String encode(
        UUID principalId,
        UUID companyId,
        String endpointId,
        String filterHash,
        String lastSortValue,
        UUID lastEntityId,
        Instant expiresAt
    ) {
        LifecycleRow lifecycle = loadActiveCursorLifecycle();
        CursorPayload payload = new CursorPayload(
            lifecycle.keyId(),
            principalId,
            companyId,
            endpointId,
            filterHash,
            lifecycle.cursorEpoch(),
            lastSortValue,
            lastEntityId,
            expiresAt
        );
        String canonicalJson = jcsCanonicalizer.canonicalizeObject(payload);
        String payloadB64 = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(canonicalJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = HEADER_B64 + "." + payloadB64;
        TinkCryptoService.HmacDigestResult sig = tinkCryptoService.computeCursorSignature(
            signingInput.getBytes(StandardCharsets.UTF_8)
        );
        String sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.digest32());
        return signingInput + "." + sigB64;
    }

    public CursorPayload decodeAndVerify(
        String token,
        UUID expectedPrincipalId,
        UUID expectedCompanyId,
        String expectedEndpointId,
        String expectedFilterHash
    ) {
        if (token == null || token.isBlank()) {
            throw invalidCursor("Pagination cursor is empty.");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !HEADER_B64.equals(parts[0])) {
            throw invalidCursor("Malformed HS256 pagination cursor.");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] providedSig;
        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            providedSig = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw invalidCursor("Invalid Base64URL encoding in pagination cursor.");
        }

        TinkCryptoService.HmacDigestResult expectedSig = tinkCryptoService.computeCursorSignature(
            signingInput.getBytes(StandardCharsets.UTF_8)
        );
        if (!MessageDigest.isEqual(providedSig, expectedSig.digest32())) {
            throw invalidCursor("Pagination cursor HMAC signature verification failed.");
        }

        CursorPayload payload;
        try {
            payload = objectMapper.readValue(payloadBytes, CursorPayload.class);
        } catch (JacksonException ex) {
            throw invalidCursor("Pagination cursor payload deserialization failed.");
        }

        if (payload.expiresAt() == null || payload.expiresAt().isBefore(Instant.now())) {
            throw invalidCursor("Pagination cursor has expired.");
        }
        if (!Objects.equals(payload.principalId(), expectedPrincipalId)
            || !Objects.equals(payload.companyId(), expectedCompanyId)
            || !Objects.equals(payload.endpointId(), expectedEndpointId)
            || !Objects.equals(payload.filterHash(), expectedFilterHash)) {
            throw invalidCursor("Pagination cursor binding mismatch (principal, company, endpoint, or filter).");
        }

        LifecycleRow lifecycle = loadLifecycleByKeyId(payload.keyId());
        if (lifecycle.cursorEpoch() != payload.cursorEpoch()) {
            throw invalidCursor("Pagination cursor epoch has been rotated.");
        }
        if (lifecycle.verifyUntil() != null && lifecycle.verifyUntil().isBefore(Instant.now())) {
            throw invalidCursor("Pagination cursor signing key verification window has elapsed.");
        }
        return payload;
    }

    private LifecycleRow loadActiveCursorLifecycle() {
        List<LifecycleRow> rows = authorityJdbcTemplate.query(
            """
            SELECT cl.key_id, cl.cursor_epoch, cl.verify_until
              FROM ma_cursor_lifecycle cl
              JOIN ma_key k ON k.id = cl.key_id
             WHERE k.key_purpose = 'CURSOR_HMAC' AND k.state = 'ACTIVE'
             ORDER BY k.key_generation DESC
             LIMIT 1
            """,
            (rs, rowNum) -> new LifecycleRow(
                rs.getObject("key_id", UUID.class),
                rs.getLong("cursor_epoch"),
                rs.getObject("verify_until", java.time.OffsetDateTime.class) == null
                    ? null
                    : rs.getObject("verify_until", java.time.OffsetDateTime.class).toInstant()
            )
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-CURSOR-KEY-MISSING",
                "Cursor Key Lifecycle Missing",
                "No active CURSOR_HMAC lifecycle found in ma_cursor_lifecycle."
            );
        }
        return rows.getFirst();
    }

    private LifecycleRow loadLifecycleByKeyId(UUID keyId) {
        List<LifecycleRow> rows = authorityJdbcTemplate.query(
            """
            SELECT key_id, cursor_epoch, verify_until
              FROM ma_cursor_lifecycle
             WHERE key_id = ?
            """,
            (rs, rowNum) -> new LifecycleRow(
                rs.getObject("key_id", UUID.class),
                rs.getLong("cursor_epoch"),
                rs.getObject("verify_until", java.time.OffsetDateTime.class) == null
                    ? null
                    : rs.getObject("verify_until", java.time.OffsetDateTime.class).toInstant()
            ),
            keyId
        );
        if (rows.isEmpty()) {
            throw invalidCursor("Unknown cursor signing key identifier.");
        }
        return rows.getFirst();
    }

    private static MoneyCraftException invalidCursor(String detail) {
        return new MoneyCraftException(
            HttpStatus.BAD_REQUEST,
            "MC-CURSOR-INVALID",
            "Invalid Pagination Cursor",
            detail
        );
    }

    private record LifecycleRow(UUID keyId, long cursorEpoch, Instant verifyUntil) {
    }
}
