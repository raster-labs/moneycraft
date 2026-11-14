package com.raster.moneycraft.foundation.audit;

import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
public class EditLogSnapshotService {

    private final JdbcTemplate businessJdbcTemplate;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;

    public EditLogSnapshotService(
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper
    ) {
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public UUID recordVersion(
        UUID companyId,
        String entityType,
        UUID entityId,
        long masterId,
        long alterId,
        long versionNo,
        String operation,
        UUID actorPrincipalId,
        String actorUsername,
        String voucherNumberSnapshot,
        LocalDate voucherDateSnapshot,
        Object previousSnapshot,
        Object currentSnapshot
    ) {
        String canonicalJson = jcsCanonicalizer.canonicalizeObject(currentSnapshot);
        byte[] sha256 = jcsCanonicalizer.sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
        String changedFieldsJson = computeChangedFieldsJson(previousSnapshot, currentSnapshot);
        UUID editLogId = UUID.randomUUID();

        businessJdbcTemplate.update(
            """
            INSERT INTO mc_edit_log_version (
                id, company_id, entity_type, entity_id, master_id, alter_id,
                version_no, operation, actor_principal_id, actor_username,
                voucher_number_snapshot, voucher_date_snapshot,
                canonical_snapshot, changed_fields, snapshot_sha256
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """,
            editLogId,
            companyId,
            entityType,
            entityId,
            masterId,
            alterId,
            versionNo,
            operation,
            actorPrincipalId,
            actorUsername == null || actorUsername.isBlank() ? "system" : actorUsername,
            voucherNumberSnapshot,
            voucherDateSnapshot,
            canonicalJson,
            changedFieldsJson,
            sha256
        );
        return editLogId;
    }

    private String computeChangedFieldsJson(Object previous, Object current) {
        if (previous == null || current == null) {
            return "[]";
        }
        JsonNode prevTree = objectMapper.valueToTree(previous);
        JsonNode currTree = objectMapper.valueToTree(current);
        List<Map<String, Object>> diffs = new ArrayList<>();
        collectDiffs("", prevTree, currTree, diffs);
        try {
            return objectMapper.writeValueAsString(diffs);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize MCA Edit Log diff array", ex);
        }
    }

    private void collectDiffs(
        String prefix,
        JsonNode prev,
        JsonNode curr,
        List<Map<String, Object>> diffs
    ) {
        if (Objects.equals(prev, curr)) {
            return;
        }
        if (prev != null && curr != null && prev.isObject() && curr.isObject()) {
            Set<String> keys = new TreeSet<>();
            keys.addAll(prev.propertyNames());
            keys.addAll(curr.propertyNames());
            for (String key : keys) {
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                collectDiffs(path, prev.get(key), curr.get(key), diffs);
            }
            return;
        }
        if (prev != null && curr != null && prev.isArray() && curr.isArray()) {
            int maxLen = Math.max(prev.size(), curr.size());
            for (int i = 0; i < maxLen; i++) {
                JsonNode prevItem = i < prev.size() ? prev.get(i) : null;
                JsonNode currItem = i < curr.size() ? curr.get(i) : null;
                collectDiffs(prefix + "[" + i + "]", prevItem, currItem, diffs);
            }
            return;
        }
        String oldVal = prev == null || prev.isNull() ? null : jcsCanonicalizer.canonicalizeNode(prev);
        String newVal = curr == null || curr.isNull() ? null : jcsCanonicalizer.canonicalizeNode(curr);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("fieldPath", prefix);
        diff.put("beforeValue", oldVal);
        diff.put("afterValue", newVal);
        diff.put("field", prefix);
        diff.put("oldValue", oldVal);
        diff.put("newValue", newVal);
        diffs.add(diff);
    }
}
