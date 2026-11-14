package com.raster.moneycraft.foundation.authority;

import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdminReceiptService {

    private final JdbcTemplate authorityJdbcTemplate;
    private final JdbcTemplate businessJdbcTemplate;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;

    public AdminReceiptService(
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper
    ) {
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
    }

    public MirroredReceipt recordAndMirrorAdminReceipt(
        UUID installationId,
        UUID actorPrincipalId,
        UUID companyScope,
        String operation,
        String targetType,
        String targetId,
        String idempotencyKey,
        byte[] requestJcsHmac,
        Map<String, Object> receiptPayload
    ) {
        List<ReceiptAnchor> prevRows = authorityJdbcTemplate.query(
            """
            SELECT receipt_seq, receipt_hash
              FROM ma_receipt
             ORDER BY receipt_seq DESC
             LIMIT 1
            """,
            (rs, rowNum) -> new ReceiptAnchor(rs.getLong("receipt_seq"), rs.getBytes("receipt_hash"))
        );

        byte[] prevHash = prevRows.isEmpty()
            ? jcsCanonicalizer.sha256(("MA-RECEIPT-GENESIS|" + installationId).getBytes(StandardCharsets.UTF_8))
            : prevRows.getFirst().receiptHash();

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(receiptPayload == null ? Map.of() : receiptPayload);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize admin receipt payload", ex);
        }

        UUID receiptId = UUID.randomUUID();
        String canonicalReceiptInput = jcsCanonicalizer.canonicalizeObject(
            Map.of(
                "receiptId", receiptId.toString(),
                "installationId", installationId.toString(),
                "actorPrincipalId", actorPrincipalId == null ? "" : actorPrincipalId.toString(),
                "companyScope", companyScope == null ? "" : companyScope.toString(),
                "operation", operation,
                "targetType", targetType,
                "targetId", targetId,
                "payload", jcsCanonicalizer.canonicalizeJson(payloadJson)
            )
        );
        byte[] receiptHash = jcsCanonicalizer.sha256Concat(
            prevHash,
            canonicalReceiptInput.getBytes(StandardCharsets.UTF_8)
        );

        InsertedReceipt inserted = authorityJdbcTemplate.queryForObject(
            """
            INSERT INTO ma_receipt (
                receipt_id, installation_id, actor_principal_id, company_scope,
                operation, target_type, target_id, idempotency_key,
                request_jcs_hmac, receipt_payload, receipt_hash, prev_receipt_hash,
                mirrored_to_business, mirrored_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, false, NULL)
            RETURNING receipt_seq, committed_at
            """,
            (rs, rowNum) -> new InsertedReceipt(
                rs.getLong("receipt_seq"),
                rs.getObject("committed_at", OffsetDateTime.class)
            ),
            receiptId,
            installationId,
            actorPrincipalId,
            companyScope,
            operation,
            targetType,
            targetId,
            idempotencyKey,
            requestJcsHmac,
            payloadJson,
            receiptHash,
            prevHash
        );
        if (inserted == null) {
            throw new IllegalStateException("ma_receipt insert returned null");
        }

        authorityJdbcTemplate.update(
            """
            UPDATE ma_state
               SET last_receipt_seq = GREATEST(last_receipt_seq, ?),
                   updated_at = clock_timestamp(),
                   version_no = version_no + 1
             WHERE installation_id = ?
            """,
            inserted.receiptSeq(),
            installationId
        );

        mirrorSingleReceiptToBusiness(
            inserted.receiptSeq(),
            receiptId,
            installationId,
            actorPrincipalId,
            companyScope,
            operation,
            targetType,
            targetId,
            receiptHash,
            prevHash,
            inserted.committedAt()
        );

        authorityJdbcTemplate.update(
            """
            UPDATE ma_receipt
               SET mirrored_to_business = true,
                   mirrored_at = clock_timestamp()
             WHERE receipt_seq = ?
            """,
            inserted.receiptSeq()
        );

        return new MirroredReceipt(inserted.receiptSeq(), receiptId, receiptHash);
    }

    public long resyncAllReceiptsDuringRecovery() {
        List<FullReceiptRow> authorityReceipts = authorityJdbcTemplate.query(
            """
            SELECT receipt_seq, receipt_id, installation_id, actor_principal_id, company_scope,
                   operation, target_type, target_id, receipt_hash, prev_receipt_hash, committed_at
              FROM ma_receipt
             ORDER BY receipt_seq ASC
            """,
            (rs, rowNum) -> new FullReceiptRow(
                rs.getLong("receipt_seq"),
                rs.getObject("receipt_id", UUID.class),
                rs.getObject("installation_id", UUID.class),
                rs.getObject("actor_principal_id", UUID.class),
                rs.getObject("company_scope", UUID.class),
                rs.getString("operation"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getBytes("receipt_hash"),
                rs.getBytes("prev_receipt_hash"),
                rs.getObject("committed_at", OffsetDateTime.class)
            )
        );

        long maxSeq = 0L;
        for (FullReceiptRow r : authorityReceipts) {
            mirrorSingleReceiptToBusiness(
                r.receiptSeq(),
                r.receiptId(),
                r.installationId(),
                r.actorPrincipalId(),
                r.companyScope(),
                r.operation(),
                r.targetType(),
                r.targetId(),
                r.receiptHash(),
                r.prevReceiptHash(),
                r.committedAt()
            );
            authorityJdbcTemplate.update(
                """
                UPDATE ma_receipt
                   SET mirrored_to_business = true,
                       mirrored_at = COALESCE(mirrored_at, clock_timestamp())
                 WHERE receipt_seq = ?
                """,
                r.receiptSeq()
            );
            maxSeq = r.receiptSeq();
        }

        // Remove any orphaned Business mirror rows beyond Authority max_seq after PITR restore reconciliation
        businessJdbcTemplate.update(
            "DELETE FROM mc_admin_receipt_mirror WHERE receipt_seq > ?",
            maxSeq
        );
        businessJdbcTemplate.update(
            """
            UPDATE mc_installation_fence
               SET last_mirrored_receipt_seq = ?,
                   updated_at = clock_timestamp()
             WHERE id = 1
            """,
            maxSeq
        );
        authorityJdbcTemplate.update(
            """
            UPDATE ma_state
               SET last_receipt_seq = ?,
                   updated_at = clock_timestamp()
             WHERE singleton = 1
            """,
            maxSeq
        );
        return maxSeq;
    }

    private void mirrorSingleReceiptToBusiness(
        long receiptSeq,
        UUID receiptId,
        UUID installationId,
        UUID actorPrincipalId,
        UUID companyScope,
        String operation,
        String targetType,
        String targetId,
        byte[] receiptHash,
        byte[] prevReceiptHash,
        OffsetDateTime committedAt
    ) {
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_admin_receipt_mirror (
                receipt_seq, receipt_id, installation_id, actor_principal_id,
                company_scope, operation, target_type, target_id,
                receipt_hash, prev_receipt_hash, authority_committed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (receipt_seq) DO UPDATE
               SET receipt_id = EXCLUDED.receipt_id,
                   receipt_hash = EXCLUDED.receipt_hash,
                   prev_receipt_hash = EXCLUDED.prev_receipt_hash,
                   mirrored_at = clock_timestamp()
            """,
            receiptSeq,
            receiptId,
            installationId,
            actorPrincipalId,
            companyScope,
            operation,
            targetType,
            targetId,
            receiptHash,
            prevReceiptHash,
            committedAt
        );
        businessJdbcTemplate.update(
            """
            UPDATE mc_installation_fence
               SET last_mirrored_receipt_seq = GREATEST(last_mirrored_receipt_seq, ?),
                   updated_at = clock_timestamp(),
                   version_no = version_no + 1
             WHERE id = 1
            """,
            receiptSeq
        );
    }

    private record ReceiptAnchor(long receiptSeq, byte[] receiptHash) {
    }

    private record InsertedReceipt(long receiptSeq, OffsetDateTime committedAt) {
    }

    private record FullReceiptRow(
        long receiptSeq,
        UUID receiptId,
        UUID installationId,
        UUID actorPrincipalId,
        UUID companyScope,
        String operation,
        String targetType,
        String targetId,
        byte[] receiptHash,
        byte[] prevReceiptHash,
        OffsetDateTime committedAt
    ) {
    }

    public record MirroredReceipt(long receiptSeq, UUID receiptId, byte[] receiptHash) {
    }
}
