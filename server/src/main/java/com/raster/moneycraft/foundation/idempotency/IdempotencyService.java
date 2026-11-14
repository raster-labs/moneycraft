package com.raster.moneycraft.foundation.idempotency;

import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {

    public static final UUID INSTALLATION_RETRY_SCOPE =
        UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final JdbcTemplate businessJdbcTemplate;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final TinkCryptoService tinkCryptoService;

    public IdempotencyService(
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        JcsCanonicalizer jcsCanonicalizer,
        TinkCryptoService tinkCryptoService
    ) {
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.tinkCryptoService = tinkCryptoService;
    }

    public TinkCryptoService.HmacDigestResult computeRequestJcsHmac(byte[] rawRequestBody) {
        String jsonText = (rawRequestBody == null || rawRequestBody.length == 0)
            ? "{}"
            : new String(rawRequestBody, StandardCharsets.UTF_8).trim();
        if (jsonText.isEmpty()) {
            jsonText = "{}";
        }
        final String canonicalJson;
        try {
            canonicalJson = jcsCanonicalizer.canonicalizeJson(jsonText);
        } catch (IllegalArgumentException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-INVALID-JSON",
                "Malformed JSON Request Body",
                "Request body must be valid UTF-8 JSON for RFC 8785 JCS canonicalization."
            );
        }
        return tinkCryptoService.computeIdempotencyHmac(canonicalJson.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<StoredIdempotencyReceipt> checkExistingReceipt(
        UUID companyScope,
        UUID principalId,
        String endpointId,
        String idempotencyKey,
        byte[] expectedRequestHmac
    ) {
        UUID effectiveScope = companyScope == null ? INSTALLATION_RETRY_SCOPE : companyScope;
        List<StoredIdempotencyReceipt> rows = businessJdbcTemplate.query(
            """
            SELECT request_jcs_hmac, http_status, response_etag, response_location,
                   response_body::text AS body_json
              FROM mc_retry
             WHERE company_id = ?
               AND principal_id = ?
               AND endpoint_id = ?
               AND idempotency_key = ?
               AND state = 'COMPLETE'
               AND expires_at > clock_timestamp()
            """,
            (rs, rowNum) -> new StoredIdempotencyReceipt(
                rs.getBytes("request_jcs_hmac"),
                rs.getInt("http_status"),
                rs.getString("response_etag"),
                rs.getString("response_location"),
                rs.getString("body_json")
            ),
            effectiveScope,
            principalId,
            endpointId,
            idempotencyKey
        );

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        StoredIdempotencyReceipt stored = rows.getFirst();
        if (!MessageDigest.isEqual(stored.requestJcsHmac(), expectedRequestHmac)) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-IDEMPOTENCY-PAYLOAD-MISMATCH",
                "Idempotency Key Reused With Different Payload",
                "Idempotency-Key '" + idempotencyKey + "' was already committed with a different RFC 8785 JCS payload."
            );
        }
        return Optional.of(stored);
    }

    public void saveReceipt(
        UUID companyScope,
        UUID principalId,
        String endpointId,
        String idempotencyKey,
        UUID hmacKeyId,
        byte[] requestJcsHmac,
        int httpStatus,
        String responseEtag,
        String responseLocation,
        String responseBodyJson
    ) {
        UUID effectiveScope = companyScope == null ? INSTALLATION_RETRY_SCOPE : companyScope;
        String cleanBody = (responseBodyJson == null || responseBodyJson.isBlank()) ? null : responseBodyJson;

        businessJdbcTemplate.update(
            """
            INSERT INTO mc_retry (
                company_id, principal_id, endpoint_id, idempotency_key,
                canon_version, hmac_key_id, request_jcs_hmac, state,
                http_status, response_etag, response_location, response_body
            ) VALUES (?, ?, ?, ?, 1, ?, ?, 'COMPLETE', ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (company_id, principal_id, endpoint_id, idempotency_key) DO NOTHING
            """,
            effectiveScope,
            principalId,
            endpointId,
            idempotencyKey,
            hmacKeyId,
            requestJcsHmac,
            (short) httpStatus,
            responseEtag,
            responseLocation,
            cleanBody
        );
    }

    public record StoredIdempotencyReceipt(
        byte[] requestJcsHmac,
        int httpStatus,
        String responseEtag,
        String responseLocation,
        String responseBodyJson
    ) {
    }
}
