package com.raster.moneycraft.foundation.audit;

import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditChainService {

    private static final Set<String> REDACTED_KEYS = Set.of(
        "password",
        "confirmPassword",
        "secret",
        "rawSecretOnce",
        "clientSecret",
        "argon2idHash",
        "recoveryReceipt",
        "recoveryReceiptCode",
        "pan",
        "bankAccountNumber",
        "attestationObject",
        "signature"
    );

    private final JdbcTemplate businessJdbcTemplate;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;

    public AuditChainService(
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper
    ) {
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public byte[] appendInstallationEvent(
        UUID installationId,
        UUID actorPrincipalId,
        String actorKind,
        String operation,
        String entityType,
        String entityId,
        String outcome,
        String errorCode,
        Map<String, Object> payload
    ) {
        return appendScopedEvent(
            "INSTALLATION",
            installationId,
            null,
            actorPrincipalId,
            actorKind,
            operation,
            entityType,
            entityId,
            outcome,
            errorCode,
            payload
        );
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public byte[] appendCompanyEvent(
        UUID companyId,
        UUID actorPrincipalId,
        String actorKind,
        String operation,
        String entityType,
        String entityId,
        String outcome,
        String errorCode,
        Map<String, Object> payload
    ) {
        return appendScopedEvent(
            "COMPANY",
            companyId,
            companyId,
            actorPrincipalId,
            actorKind,
            operation,
            entityType,
            entityId,
            outcome,
            errorCode,
            payload
        );
    }

    private byte[] appendScopedEvent(
        String scopeType,
        UUID scopeId,
        UUID companyId,
        UUID actorPrincipalId,
        String actorKind,
        String operation,
        String entityType,
        String entityId,
        String outcome,
        String errorCode,
        Map<String, Object> payload
    ) {
        byte[] genesisAnchor = computeGenesisSeedHash(scopeType, scopeId);
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_audit_tail (scope_id, scope_type, next_seq, last_event_hash)
            VALUES (?, ?, 1, ?)
            ON CONFLICT (scope_id) DO NOTHING
            """,
            scopeId,
            scopeType,
            genesisAnchor
        );

        TailRow lockedTail = businessJdbcTemplate.queryForObject(
            """
            SELECT next_seq, last_event_hash
              FROM mc_audit_tail
             WHERE scope_id = ?
               FOR UPDATE
            """,
            (rs, rowNum) -> new TailRow(rs.getLong("next_seq"), rs.getBytes("last_event_hash")),
            scopeId
        );
        if (lockedTail == null) {
            throw new IllegalStateException("mc_audit_tail lock acquisition returned null for " + scopeId);
        }

        Map<String, Object> redactedMap = redactMap(payload);
        String redactedJson;
        JsonNode payloadNode;
        try {
            redactedJson = objectMapper.writeValueAsString(redactedMap);
            payloadNode = objectMapper.readTree(redactedJson);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize redacted audit payload", ex);
        }

        long seqNo = lockedTail.nextSeq();
        byte[] prevHash = lockedTail.lastEventHash();
        byte[] eventHash = computeEventHash(
            prevHash,
            scopeType,
            scopeId,
            companyId,
            seqNo,
            actorPrincipalId,
            actorKind,
            operation,
            entityType,
            entityId,
            outcome,
            errorCode,
            payloadNode
        );

        UUID eventId = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_audit_event (
                id, scope_type, scope_id, company_id, seq_no,
                actor_principal_id, actor_kind, operation, entity_type, entity_id,
                outcome, error_code, correlation_id, redacted_payload, prev_hash, event_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?::jsonb, ?, ?)
            """,
            eventId,
            scopeType,
            scopeId,
            companyId,
            seqNo,
            actorPrincipalId,
            actorKind,
            operation,
            entityType,
            entityId,
            outcome,
            errorCode,
            redactedJson,
            prevHash,
            eventHash
        );

        businessJdbcTemplate.update(
            """
            UPDATE mc_audit_tail
               SET next_seq = ?,
                   last_event_hash = ?,
                   version_no = version_no + 1,
                   updated_at = clock_timestamp()
             WHERE scope_id = ?
            """,
            seqNo + 1L,
            eventHash,
            scopeId
        );

        return eventHash;
    }

    public AuditVerifyResult verifyAllStreams() {
        List<StreamTailRow> tails = businessJdbcTemplate.query(
            """
            SELECT scope_id, scope_type, next_seq, last_event_hash
              FROM mc_audit_tail
             ORDER BY scope_type DESC, scope_id ASC
            """,
            (rs, rowNum) -> new StreamTailRow(
                rs.getObject("scope_id", UUID.class),
                rs.getString("scope_type"),
                rs.getLong("next_seq"),
                rs.getBytes("last_event_hash")
            )
        );

        if (tails.isEmpty()) {
            byte[] emptyDigest = jcsCanonicalizer.sha256("MC-AUDIT-2:EMPTY".getBytes(StandardCharsets.UTF_8));
            return new AuditVerifyResult(true, 0L, JcsCanonicalizer.toHex(emptyDigest), Instant.now(), null);
        }

        long totalChecked = 0L;
        MessageDigest aggregateDigest;
        try {
            aggregateDigest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }

        for (StreamTailRow tail : tails) {
            List<StoredEventRow> events = businessJdbcTemplate.query(
                """
                SELECT scope_type, scope_id, company_id, seq_no, actor_principal_id,
                       actor_kind, operation, entity_type, entity_id, outcome, error_code,
                       redacted_payload::text AS payload_text, prev_hash, event_hash
                  FROM mc_audit_event
                 WHERE scope_id = ?
                 ORDER BY seq_no ASC
                """,
                (rs, rowNum) -> new StoredEventRow(
                    rs.getString("scope_type"),
                    rs.getObject("scope_id", UUID.class),
                    rs.getObject("company_id", UUID.class),
                    rs.getLong("seq_no"),
                    rs.getObject("actor_principal_id", UUID.class),
                    rs.getString("actor_kind"),
                    rs.getString("operation"),
                    rs.getString("entity_type"),
                    rs.getString("entity_id"),
                    rs.getString("outcome"),
                    rs.getString("error_code"),
                    rs.getString("payload_text"),
                    rs.getBytes("prev_hash"),
                    rs.getBytes("event_hash")
                ),
                tail.scopeId()
            );

            long expectedCount = tail.nextSeq() - 1L;
            if (events.size() != expectedCount) {
                return new AuditVerifyResult(
                    false,
                    totalChecked,
                    JcsCanonicalizer.toHex(tail.lastEventHash()),
                    Instant.now(),
                    "Audit event count mismatch for scope " + tail.scopeId()
                        + ": expected " + expectedCount + " but found " + events.size()
                );
            }

            byte[] expectedPrev = computeGenesisSeedHash(tail.scopeType(), tail.scopeId());
            long expectedSeq = 1L;
            for (StoredEventRow ev : events) {
                if (ev.seqNo() != expectedSeq) {
                    return new AuditVerifyResult(
                        false,
                        totalChecked,
                        JcsCanonicalizer.toHex(tail.lastEventHash()),
                        Instant.now(),
                        "Sequence gap in scope " + tail.scopeId() + " at seq " + ev.seqNo()
                    );
                }
                if (!MessageDigest.isEqual(expectedPrev, ev.prevHash())) {
                    return new AuditVerifyResult(
                        false,
                        totalChecked,
                        JcsCanonicalizer.toHex(tail.lastEventHash()),
                        Instant.now(),
                        "Broken prev_hash link in scope " + tail.scopeId() + " at seq " + ev.seqNo()
                    );
                }

                JsonNode payloadNode;
                try {
                    payloadNode = objectMapper.readTree(ev.payloadText());
                } catch (JacksonException ex) {
                    return new AuditVerifyResult(
                        false,
                        totalChecked,
                        JcsCanonicalizer.toHex(tail.lastEventHash()),
                        Instant.now(),
                        "Corrupted JSON payload in mc_audit_event seq " + ev.seqNo()
                    );
                }

                byte[] recomputed = computeEventHash(
                    ev.prevHash(),
                    ev.scopeType(),
                    ev.scopeId(),
                    ev.companyId(),
                    ev.seqNo(),
                    ev.actorPrincipalId(),
                    ev.actorKind(),
                    ev.operation(),
                    ev.entityType(),
                    ev.entityId(),
                    ev.outcome(),
                    ev.errorCode(),
                    payloadNode
                );
                if (!MessageDigest.isEqual(recomputed, ev.eventHash())) {
                    return new AuditVerifyResult(
                        false,
                        totalChecked,
                        JcsCanonicalizer.toHex(tail.lastEventHash()),
                        Instant.now(),
                        "Event SHA-256 mismatch in scope " + tail.scopeId() + " at seq " + ev.seqNo()
                    );
                }
                expectedPrev = ev.eventHash();
                expectedSeq++;
                totalChecked++;
            }

            if (!MessageDigest.isEqual(expectedPrev, tail.lastEventHash())) {
                return new AuditVerifyResult(
                    false,
                    totalChecked,
                    JcsCanonicalizer.toHex(tail.lastEventHash()),
                    Instant.now(),
                    "mc_audit_tail.last_event_hash does not match head of mc_audit_event in scope " + tail.scopeId()
                );
            }
            aggregateDigest.update(tail.lastEventHash());
        }

        byte[] finalTail = tails.size() == 1
            ? Arrays.copyOf(tails.getFirst().lastEventHash(), tails.getFirst().lastEventHash().length)
            : aggregateDigest.digest();
        return new AuditVerifyResult(true, totalChecked, JcsCanonicalizer.toHex(finalTail), Instant.now(), null);
    }

    private byte[] computeGenesisSeedHash(String scopeType, UUID scopeId) {
        String seed = "MC-AUDIT-2-GENESIS|" + scopeType + "|" + scopeId;
        return jcsCanonicalizer.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] computeEventHash(
        byte[] prevHash,
        String scopeType,
        UUID scopeId,
        UUID companyId,
        long seqNo,
        UUID actorPrincipalId,
        String actorKind,
        String operation,
        String entityType,
        String entityId,
        String outcome,
        String errorCode,
        JsonNode payloadNode
    ) {
        CanonicalAuditEnvelope envelope = new CanonicalAuditEnvelope(
            scopeType,
            scopeId == null ? null : scopeId.toString(),
            companyId == null ? null : companyId.toString(),
            seqNo,
            actorPrincipalId == null ? null : actorPrincipalId.toString(),
            actorKind,
            operation,
            entityType,
            entityId,
            outcome,
            errorCode,
            payloadNode
        );
        String jcs = jcsCanonicalizer.canonicalizeObject(envelope);
        return jcsCanonicalizer.sha256Concat(prevHash, jcs.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> redactMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            if (REDACTED_KEYS.contains(key)) {
                clean.put(key, "[REDACTED]");
            } else {
                clean.put(key, entry.getValue());
            }
        }
        return clean;
    }

    public record CanonicalAuditEnvelope(
        String scopeType,
        String scopeId,
        String companyId,
        long seqNo,
        String actorPrincipalId,
        String actorKind,
        String operation,
        String entityType,
        String entityId,
        String outcome,
        String errorCode,
        JsonNode payload
    ) {
    }

    private record TailRow(long nextSeq, byte[] lastEventHash) {
    }

    private record StreamTailRow(UUID scopeId, String scopeType, long nextSeq, byte[] lastEventHash) {
    }

    private record StoredEventRow(
        String scopeType,
        UUID scopeId,
        UUID companyId,
        long seqNo,
        UUID actorPrincipalId,
        String actorKind,
        String operation,
        String entityType,
        String entityId,
        String outcome,
        String errorCode,
        String payloadText,
        byte[] prevHash,
        byte[] eventHash
    ) {
    }
}
