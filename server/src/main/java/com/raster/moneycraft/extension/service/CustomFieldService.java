package com.raster.moneycraft.extension.service;

import com.raster.moneycraft.extension.dto.ExtensionDtos;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Namespaced + UUID Custom Field definition and Tink SIV/GCM encrypted value service (EXT-003).
 * Enforces strict type validation, compatibility guards, and SELECTED_PRIVATE redaction/AEAD custody.
 */
@Service
public class CustomFieldService {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,95}$");
    private static final Pattern FIELD_CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,63}$");
    private static final Set<String> VALID_OWNERS = Set.of("LEDGER", "STOCK_ITEM", "VOUCHER");
    private static final Set<String> VALID_TYPES = Set.of("TEXT", "EXACT_DECIMAL", "DATE", "BOOLEAN", "ENUM");
    private static final Set<String> VALID_PROTECTIONS = Set.of("STANDARD", "SELECTED_PRIVATE");

    private final AuthorityGuard authorityGuard;
    private final ExtensionAccessHelper accessHelper;
    private final TinkCryptoService tinkCryptoService;
    private final AuditChainService auditChainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public CustomFieldService(
        AuthorityGuard authorityGuard,
        ExtensionAccessHelper accessHelper,
        TinkCryptoService tinkCryptoService,
        AuditChainService auditChainService,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.accessHelper = accessHelper;
        this.tinkCryptoService = tinkCryptoService;
        this.auditChainService = auditChainService;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public List<ExtensionDtos.CustomFieldDefDto> listFieldDefinitions(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String ownerEntityType
    ) {
        accessHelper.requireCompanyRead(principal, companyId);
        if (ownerEntityType != null && !ownerEntityType.isBlank()) {
            String normOwner = normalizeOwnerEntityType(ownerEntityType);
            return businessJdbcTemplate.query(
                """
                SELECT id, company_id, extension_package_id, namespace, field_uuid,
                       owner_entity_type, field_code, label, data_type, decimal_scale,
                       enum_options_json, default_value_text, protection_class, ui_anchor,
                       required_flag, active, version_no, created_at
                  FROM mc_custom_field_def
                 WHERE company_id = ? AND owner_entity_type = ? AND active = true
                 ORDER BY namespace ASC, field_code ASC
                """,
                (rs, rowNum) -> mapFieldDefRow(rs),
                companyId,
                normOwner
            );
        }
        return businessJdbcTemplate.query(
            """
            SELECT id, company_id, extension_package_id, namespace, field_uuid,
                   owner_entity_type, field_code, label, data_type, decimal_scale,
                   enum_options_json, default_value_text, protection_class, ui_anchor,
                   required_flag, active, version_no, created_at
              FROM mc_custom_field_def
             WHERE company_id = ? AND active = true
             ORDER BY owner_entity_type ASC, namespace ASC, field_code ASC
            """,
            (rs, rowNum) -> mapFieldDefRow(rs),
            companyId
        );
    }

    public ExtensionDtos.CustomFieldDefDto upsertFieldDefinition(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID extensionPackageId,
        ExtensionDtos.UpsertCustomFieldDefRequest request
    ) {
        authorityGuard.assertWritable();
        accessHelper.requireExtensionAuthor(actor, companyId);
        NormalizedFieldSpec spec = validateAndNormalizeSpec(request);

        return businessTxTemplate.execute(tx -> {
            accessHelper.ensurePrincipalProjected(actor);
            return upsertNormalizedInCurrentTx(actor, companyId, extensionPackageId, spec);
        });
    }

    public ExtensionDtos.CustomFieldDefDto upsertNormalizedInCurrentTx(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID extensionPackageId,
        ExtensionDtos.UpsertCustomFieldDefRequest request
    ) {
        NormalizedFieldSpec spec = validateAndNormalizeSpec(request);
        return upsertNormalizedInCurrentTx(actor, companyId, extensionPackageId, spec);
    }

    private ExtensionDtos.CustomFieldDefDto upsertNormalizedInCurrentTx(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID extensionPackageId,
        NormalizedFieldSpec spec
    ) {
        List<ExtensionDtos.CustomFieldDefDto> existingByUuid = businessJdbcTemplate.query(
            """
            SELECT id, company_id, extension_package_id, namespace, field_uuid,
                   owner_entity_type, field_code, label, data_type, decimal_scale,
                   enum_options_json, default_value_text, protection_class, ui_anchor,
                   required_flag, active, version_no, created_at
              FROM mc_custom_field_def
             WHERE company_id = ? AND namespace = ? AND field_uuid = ?
               FOR UPDATE
            """,
            (rs, rowNum) -> mapFieldDefRow(rs),
            companyId,
            spec.namespace(),
            spec.fieldUuid()
        );

        if (!existingByUuid.isEmpty()) {
            ExtensionDtos.CustomFieldDefDto cur = existingByUuid.getFirst();
            if (!cur.ownerEntityType().equals(spec.ownerEntityType())
                || !cur.dataType().equals(spec.dataType())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FIELD-INCOMPATIBLE",
                    "Incompatible Custom Field Redefinition",
                    "Custom field '" + spec.namespace() + ":" + spec.fieldUuid()
                        + "' cannot change ownerEntityType (" + cur.ownerEntityType() + " -> " + spec.ownerEntityType()
                        + ") or dataType (" + cur.dataType() + " -> " + spec.dataType() + ")."
                );
            }
            if ("SELECTED_PRIVATE".equals(cur.protectionClass())
                && "STANDARD".equals(spec.protectionClass())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FIELD-PROTECTION-DOWNGRADE",
                    "Custom Field Protection Downgrade Forbidden",
                    "Cannot weaken custom field '" + spec.fieldCode() + "' from SELECTED_PRIVATE to STANDARD."
                );
            }
            if ("EXACT_DECIMAL".equals(cur.dataType())
                && cur.decimalScale() != null
                && spec.decimalScale() != null
                && spec.decimalScale() < cur.decimalScale()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FIELD-INCOMPATIBLE",
                    "Decimal Scale Narrowing Forbidden",
                    "Cannot narrow decimalScale of '" + spec.fieldCode() + "' from "
                        + cur.decimalScale() + " to " + spec.decimalScale() + "."
                );
            }

            long nextVer = cur.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_custom_field_def
                   SET extension_package_id = COALESCE(?, extension_package_id),
                       field_code = ?,
                       label = ?,
                       decimal_scale = ?,
                       enum_options_json = ?::jsonb,
                       default_value_text = ?,
                       protection_class = ?,
                       ui_anchor = ?,
                       required_flag = ?,
                       active = true,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                extensionPackageId,
                spec.fieldCode(),
                spec.label(),
                spec.decimalScale() == null ? null : spec.decimalScale().shortValue(),
                toJson(spec.enumOptions()),
                spec.defaultValue(),
                spec.protectionClass(),
                spec.uiAnchor(),
                spec.requiredFlag(),
                nextVer,
                companyId,
                cur.id()
            );
            return requireDefById(companyId, cur.id());
        }

        List<UUID> codeCollision = businessJdbcTemplate.query(
            """
            SELECT field_uuid
              FROM mc_custom_field_def
             WHERE company_id = ? AND owner_entity_type = ? AND namespace = ? AND lower(field_code) = lower(?)
            """,
            (rs, rowNum) -> rs.getObject("field_uuid", UUID.class),
            companyId,
            spec.ownerEntityType(),
            spec.namespace(),
            spec.fieldCode()
        );
        if (!codeCollision.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-EXT-FIELD-CODE-COLLISION",
                "Custom Field Code Collision",
                "Field code '" + spec.namespace() + "." + spec.fieldCode()
                    + "' is already bound to a different fieldUuid (" + codeCollision.getFirst() + ")."
            );
        }

        UUID id = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_custom_field_def (
                id, company_id, extension_package_id, namespace, field_uuid,
                owner_entity_type, field_code, label, data_type, decimal_scale,
                enum_options_json, default_value_text, protection_class, ui_anchor,
                required_flag, active, version_no, created_by
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?::jsonb, ?, ?, ?,
                ?, true, 1, ?
            )
            """,
            id,
            companyId,
            extensionPackageId,
            spec.namespace(),
            spec.fieldUuid(),
            spec.ownerEntityType(),
            spec.fieldCode(),
            spec.label(),
            spec.dataType(),
            spec.decimalScale() == null ? null : spec.decimalScale().shortValue(),
            toJson(spec.enumOptions()),
            spec.defaultValue(),
            spec.protectionClass(),
            spec.uiAnchor(),
            spec.requiredFlag(),
            actor.principalId()
        );

        auditChainService.appendCompanyEvent(
            companyId,
            actor.principalId(),
            actor.kind(),
            "CUSTOM_FIELD_DEF_CREATE",
            "CUSTOM_FIELD_DEF",
            id.toString(),
            "SUCCESS",
            null,
            Map.of(
                "namespace", spec.namespace(),
                "fieldUuid", spec.fieldUuid().toString(),
                "ownerEntityType", spec.ownerEntityType(),
                "fieldCode", spec.fieldCode(),
                "protectionClass", spec.protectionClass()
            )
        );
        return requireDefById(companyId, id);
    }

    public List<ExtensionDtos.CustomFieldValueDto> writeEntityValues(
        MoneyCraftPrincipal actor,
        UUID companyId,
        String rawOwnerEntityType,
        UUID entityId,
        ExtensionDtos.UpsertCustomFieldValuesRequest request
    ) {
        authorityGuard.assertWritable();
        accessHelper.requireCustomFieldWrite(actor, companyId);
        String ownerEntityType = normalizeOwnerEntityType(rawOwnerEntityType);
        if (entityId == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXT-ENTITY-ID-REQUIRED",
                "Entity Identifier Required",
                "Specify a valid target entityId."
            );
        }
        if (request == null
            || ((request.items() == null || request.items().isEmpty())
                && (request.values() == null || request.values().isEmpty()))) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXT-VALUES-REQUIRED",
                "Custom Field Values Required",
                "Provide at least one custom field value in 'items' or 'values'."
            );
        }

        return businessTxTemplate.execute(tx -> {
            accessHelper.ensurePrincipalProjected(actor);
            assertTargetEntityExists(companyId, ownerEntityType, entityId);

            List<ExtensionDtos.FieldValueWriteItemDto> writeItems = normalizeWriteItems(request);
            for (ExtensionDtos.FieldValueWriteItemDto item : writeItems) {
                ExtensionDtos.CustomFieldDefDto def = resolveFieldDefForWrite(companyId, ownerEntityType, item);
                boolean isPrivate = "SELECTED_PRIVATE".equals(def.protectionClass());
                if (isPrivate) {
                    accessHelper.requireSelectedPrivateFieldAccess(actor, companyId);
                }

                if (item.value() == null || item.value().isBlank()) {
                    if (def.requiredFlag()) {
                        throw new MoneyCraftException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "MC-EXT-FIELD-REQUIRED",
                            "Required Custom Field Empty",
                            "Custom field '" + def.label() + "' is required."
                        );
                    }
                    businessJdbcTemplate.update(
                        "DELETE FROM mc_custom_field_value WHERE company_id = ? AND field_def_id = ? AND entity_id = ?",
                        companyId,
                        def.id(),
                        entityId
                    );
                    continue;
                }

                String canonical = validateAndCanonicalizeValue(def, item.value());
                String plainCol = isPrivate ? null : canonical;
                byte[] sivBytes = null;
                byte[] gcmBytes = null;
                if (isPrivate) {
                    sivBytes = tinkCryptoService.encryptDeterministicSiv(
                        companyId,
                        "mc_custom_field_value",
                        def.namespace() + ":" + def.fieldUuid(),
                        canonical
                    );
                    byte[] aad = buildGcmAssociatedData(companyId, def, entityId);
                    gcmBytes = tinkCryptoService.encryptGcm(aad, canonical.getBytes(StandardCharsets.UTF_8));
                }

                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_custom_field_value (
                        id, company_id, field_def_id, owner_entity_type, entity_id,
                        plain_value_text, siv_ciphertext, gcm_ciphertext, value_version, updated_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    ON CONFLICT (company_id, field_def_id, entity_id)
                    DO UPDATE SET
                        plain_value_text = EXCLUDED.plain_value_text,
                        siv_ciphertext = EXCLUDED.siv_ciphertext,
                        gcm_ciphertext = EXCLUDED.gcm_ciphertext,
                        value_version = mc_custom_field_value.value_version + 1,
                        updated_by = EXCLUDED.updated_by,
                        updated_at = clock_timestamp()
                    """,
                    UUID.randomUUID(),
                    companyId,
                    def.id(),
                    ownerEntityType,
                    entityId,
                    plainCol,
                    sivBytes,
                    gcmBytes,
                    actor.principalId()
                );

                auditChainService.appendCompanyEvent(
                    companyId,
                    actor.principalId(),
                    actor.kind(),
                    "CUSTOM_FIELD_VALUE_UPSERT",
                    ownerEntityType,
                    entityId.toString(),
                    "SUCCESS",
                    null,
                    Map.of(
                        "namespace", def.namespace(),
                        "fieldUuid", def.fieldUuid().toString(),
                        "fieldCode", def.fieldCode(),
                        "protectionClass", def.protectionClass()
                    )
                );
            }

            return readEntityValues(actor, companyId, ownerEntityType, entityId, false);
        });
    }

    public List<ExtensionDtos.CustomFieldValueDto> readEntityValues(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String rawOwnerEntityType,
        UUID entityId,
        boolean strictPrivateAccess
    ) {
        accessHelper.requireCompanyRead(principal, companyId);
        String ownerEntityType = normalizeOwnerEntityType(rawOwnerEntityType);
        boolean canDecryptPrivate = accessHelper.canAccessSelectedPrivateFields(principal, companyId);

        List<StoredValueJoinRow> rows = businessJdbcTemplate.query(
            """
            SELECT v.field_def_id, d.namespace, d.field_uuid, d.owner_entity_type,
                   v.entity_id, d.field_code, d.label, d.data_type, d.protection_class,
                   d.ui_anchor, d.version_no AS def_version_no,
                   v.plain_value_text, v.gcm_ciphertext, v.value_version, v.updated_at
              FROM mc_custom_field_value v
              JOIN mc_custom_field_def d
                ON d.company_id = v.company_id AND d.id = v.field_def_id
             WHERE v.company_id = ?
               AND v.owner_entity_type = ?
               AND v.entity_id = ?
             ORDER BY d.namespace ASC, d.field_code ASC
            """,
            (rs, rowNum) -> new StoredValueJoinRow(
                rs.getObject("field_def_id", UUID.class),
                rs.getString("namespace"),
                rs.getObject("field_uuid", UUID.class),
                rs.getString("owner_entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getString("field_code"),
                rs.getString("label"),
                rs.getString("data_type"),
                rs.getString("protection_class"),
                rs.getString("ui_anchor"),
                rs.getLong("def_version_no"),
                rs.getString("plain_value_text"),
                rs.getBytes("gcm_ciphertext"),
                rs.getLong("value_version"),
                rs.getTimestamp("updated_at").toInstant()
            ),
            companyId,
            ownerEntityType,
            entityId
        );

        List<ExtensionDtos.CustomFieldValueDto> result = new ArrayList<>(rows.size());
        for (StoredValueJoinRow row : rows) {
            if ("SELECTED_PRIVATE".equals(row.protectionClass())) {
                if (!canDecryptPrivate) {
                    if (strictPrivateAccess) {
                        throw new MoneyCraftException(
                            HttpStatus.FORBIDDEN,
                            "MC-EXT-PRIVATE-FIELD-FORBIDDEN",
                            "Selected Private Custom Field Access Denied",
                            "Principal is not permitted to decrypt SELECTED_PRIVATE custom field '"
                                + row.fieldCode() + "'."
                        );
                    }
                    result.add(new ExtensionDtos.CustomFieldValueDto(
                        row.fieldDefId(),
                        row.namespace(),
                        row.fieldUuid(),
                        row.ownerEntityType(),
                        row.entityId(),
                        row.fieldCode(),
                        row.label(),
                        row.dataType(),
                        row.protectionClass(),
                        row.uiAnchor(),
                        null,
                        true,
                        row.valueVersion(),
                        row.updatedAt()
                    ));
                    continue;
                }
                byte[] aad = buildGcmAssociatedDataRaw(
                    companyId,
                    row.ownerEntityType(),
                    row.entityId(),
                    row.namespace(),
                    row.fieldUuid()
                );
                byte[] plainBytes = tinkCryptoService.decryptGcm(aad, row.gcmCiphertext());
                String decrypted = new String(plainBytes, StandardCharsets.UTF_8);
                result.add(new ExtensionDtos.CustomFieldValueDto(
                    row.fieldDefId(),
                    row.namespace(),
                    row.fieldUuid(),
                    row.ownerEntityType(),
                    row.entityId(),
                    row.fieldCode(),
                    row.label(),
                    row.dataType(),
                    row.protectionClass(),
                    row.uiAnchor(),
                    decrypted,
                    false,
                    row.valueVersion(),
                    row.updatedAt()
                ));
            } else {
                result.add(new ExtensionDtos.CustomFieldValueDto(
                    row.fieldDefId(),
                    row.namespace(),
                    row.fieldUuid(),
                    row.ownerEntityType(),
                    row.entityId(),
                    row.fieldCode(),
                    row.label(),
                    row.dataType(),
                    row.protectionClass(),
                    row.uiAnchor(),
                    row.plainValueText(),
                    false,
                    row.valueVersion(),
                    row.updatedAt()
                ));
            }
        }
        return List.copyOf(result);
    }

    public Map<String, Object> loadStandardValuesForRuleEvaluation(
        UUID companyId,
        String ownerEntityType,
        UUID entityId
    ) {
        if (entityId == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT d.field_code, d.data_type, v.plain_value_text
              FROM mc_custom_field_value v
              JOIN mc_custom_field_def d
                ON d.company_id = v.company_id AND d.id = v.field_def_id
             WHERE v.company_id = ?
               AND v.owner_entity_type = ?
               AND v.entity_id = ?
               AND d.protection_class = 'STANDARD'
               AND v.plain_value_text IS NOT NULL
            """,
            (rs, rowNum) -> {
                String code = rs.getString("field_code");
                String dtype = rs.getString("data_type");
                String txt = rs.getString("plain_value_text");
                Object typed = switch (dtype) {
                    case "EXACT_DECIMAL" -> new BigDecimal(txt);
                    case "BOOLEAN" -> Boolean.parseBoolean(txt);
                    case "DATE" -> LocalDate.parse(txt);
                    default -> txt;
                };
                map.put(code, typed);
                return null;
            },
            companyId,
            ownerEntityType,
            entityId
        );
        return Map.copyOf(map);
    }

    public NormalizedFieldSpec validateAndNormalizeSpec(ExtensionDtos.UpsertCustomFieldDefRequest req) {
        if (req == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FIELD-SPEC-REQUIRED",
                "Custom Field Specification Required",
                "Provide custom field definition attributes."
            );
        }
        String ns = req.namespace() == null ? "" : req.namespace().trim().toLowerCase(Locale.ROOT);
        if (!NAMESPACE_PATTERN.matcher(ns).matches()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-NAMESPACE-INVALID",
                "Invalid Extension Namespace",
                "Namespace '" + ns + "' must match ^[a-z0-9][a-z0-9._-]{1,95}$."
            );
        }
        UUID fieldUuid = req.fieldUuid() == null ? UUID.randomUUID() : req.fieldUuid();
        String owner = normalizeOwnerEntityType(req.ownerEntityType());
        String code = req.fieldCode() == null ? "" : req.fieldCode().trim();
        if (!FIELD_CODE_PATTERN.matcher(code).matches()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FIELD-CODE-INVALID",
                "Invalid Custom Field Code",
                "fieldCode '" + code + "' must match ^[A-Za-z][A-Za-z0-9_]{1,63}$."
            );
        }
        String label = req.label() == null || req.label().isBlank() ? code : req.label().trim();
        String dtype = req.dataType() == null ? "" : req.dataType().trim().toUpperCase(Locale.ROOT);
        if (!VALID_TYPES.contains(dtype)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FIELD-TYPE-INVALID",
                "Invalid Custom Field Data Type",
                "dataType must be one of " + VALID_TYPES + "."
            );
        }
        Integer scale = null;
        if ("EXACT_DECIMAL".equals(dtype)) {
            int s = req.decimalScale() == null ? 2 : req.decimalScale();
            if (s < 0 || s > 6) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FIELD-SCALE-INVALID",
                    "Invalid Custom Field Decimal Scale",
                    "decimalScale must be between 0 and 6."
                );
            }
            scale = s;
        }
        List<String> enumOpts = new ArrayList<>();
        if ("ENUM".equals(dtype)) {
            if (req.enumOptions() == null || req.enumOptions().isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-ENUM-OPTIONS-REQUIRED",
                    "Enum Options Required",
                    "ENUM custom fields must declare at least one enumOption."
                );
            }
            for (String opt : req.enumOptions()) {
                if (opt != null && !opt.isBlank()) {
                    enumOpts.add(opt.trim());
                }
            }
        }
        String prot = req.protectionClass() == null || req.protectionClass().isBlank()
            ? "STANDARD"
            : req.protectionClass().trim().toUpperCase(Locale.ROOT);
        if (!VALID_PROTECTIONS.contains(prot)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-PROTECTION-INVALID",
                "Invalid Protection Class",
                "protectionClass must be STANDARD or SELECTED_PRIVATE."
            );
        }
        String anchor = req.uiAnchor() == null || req.uiAnchor().isBlank()
            ? owner + "_HEADER_EXTRA"
            : req.uiAnchor().trim();
        String defaultVal = req.defaultValue() == null || req.defaultValue().isBlank()
            ? null
            : req.defaultValue().trim();
        if ("SELECTED_PRIVATE".equals(prot) && defaultVal != null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-PRIVATE-DEFAULT-FORBIDDEN",
                "Plaintext Default Forbidden on SELECTED_PRIVATE Field",
                "SELECTED_PRIVATE custom fields must not embed plaintext default literals in schema metadata."
            );
        }
        boolean required = Boolean.TRUE.equals(req.requiredFlag());

        return new NormalizedFieldSpec(
            ns, fieldUuid, owner, code, label, dtype, scale, List.copyOf(enumOpts), defaultVal, prot, anchor, required
        );
    }

    private static String normalizeOwnerEntityType(String raw) {
        String u = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("LEDGER".equals(u) || "LEDGERS".equals(u)) {
            return "LEDGER";
        }
        if ("STOCK_ITEM".equals(u) || "STOCK_ITEMS".equals(u) || "STOCKITEM".equals(u)) {
            return "STOCK_ITEM";
        }
        if ("VOUCHER".equals(u) || "VOUCHERS".equals(u) || "JOURNAL".equals(u)) {
            return "VOUCHER";
        }
        if (!VALID_OWNERS.contains(u)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXT-OWNER-TYPE-INVALID",
                "Invalid Custom Field Owner Entity Type",
                "ownerEntityType must be LEDGER, STOCK_ITEM, or VOUCHER."
            );
        }
        return u;
    }

    private void assertTargetEntityExists(UUID companyId, String ownerEntityType, UUID entityId) {
        String table = switch (ownerEntityType) {
            case "LEDGER" -> "mc_ledger";
            case "STOCK_ITEM" -> "mc_stock_item";
            case "VOUCHER" -> "mc_journal";
            default -> throw new IllegalArgumentException(ownerEntityType);
        };
        Integer count = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE company_id = ? AND id = ?",
            Integer.class,
            companyId,
            entityId
        );
        if (count == null || count == 0) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-EXT-TARGET-ENTITY-NOT-FOUND",
                "Target Entity Not Found",
                ownerEntityType + " " + entityId + " was not found in company " + companyId + "."
            );
        }
    }

    private static List<ExtensionDtos.FieldValueWriteItemDto> normalizeWriteItems(
        ExtensionDtos.UpsertCustomFieldValuesRequest req
    ) {
        List<ExtensionDtos.FieldValueWriteItemDto> list = new ArrayList<>();
        if (req.items() != null) {
            list.addAll(req.items());
        }
        if (req.values() != null) {
            for (Map.Entry<String, String> e : req.values().entrySet()) {
                String key = e.getKey().trim();
                int colon = key.indexOf(':');
                if (colon > 0) {
                    list.add(new ExtensionDtos.FieldValueWriteItemDto(
                        null,
                        key.substring(0, colon),
                        key.substring(colon + 1),
                        e.getValue()
                    ));
                } else {
                    list.add(new ExtensionDtos.FieldValueWriteItemDto(null, null, key, e.getValue()));
                }
            }
        }
        return list;
    }

    private ExtensionDtos.CustomFieldDefDto resolveFieldDefForWrite(
        UUID companyId,
        String ownerEntityType,
        ExtensionDtos.FieldValueWriteItemDto item
    ) {
        List<ExtensionDtos.CustomFieldDefDto> matches;
        if (item.fieldDefId() != null) {
            matches = businessJdbcTemplate.query(
                """
                SELECT id, company_id, extension_package_id, namespace, field_uuid,
                       owner_entity_type, field_code, label, data_type, decimal_scale,
                       enum_options_json, default_value_text, protection_class, ui_anchor,
                       required_flag, active, version_no, created_at
                  FROM mc_custom_field_def
                 WHERE company_id = ? AND id = ? AND owner_entity_type = ? AND active = true
                """,
                (rs, rowNum) -> mapFieldDefRow(rs),
                companyId,
                item.fieldDefId(),
                ownerEntityType
            );
        } else if (item.namespace() != null && !item.namespace().isBlank()) {
            matches = businessJdbcTemplate.query(
                """
                SELECT id, company_id, extension_package_id, namespace, field_uuid,
                       owner_entity_type, field_code, label, data_type, decimal_scale,
                       enum_options_json, default_value_text, protection_class, ui_anchor,
                       required_flag, active, version_no, created_at
                  FROM mc_custom_field_def
                 WHERE company_id = ? AND owner_entity_type = ? AND namespace = ?
                   AND lower(field_code) = lower(?) AND active = true
                """,
                (rs, rowNum) -> mapFieldDefRow(rs),
                companyId,
                ownerEntityType,
                item.namespace().trim().toLowerCase(Locale.ROOT),
                item.fieldCode().trim()
            );
        } else {
            matches = businessJdbcTemplate.query(
                """
                SELECT id, company_id, extension_package_id, namespace, field_uuid,
                       owner_entity_type, field_code, label, data_type, decimal_scale,
                       enum_options_json, default_value_text, protection_class, ui_anchor,
                       required_flag, active, version_no, created_at
                  FROM mc_custom_field_def
                 WHERE company_id = ? AND owner_entity_type = ?
                   AND lower(field_code) = lower(?) AND active = true
                """,
                (rs, rowNum) -> mapFieldDefRow(rs),
                companyId,
                ownerEntityType,
                item.fieldCode() == null ? "" : item.fieldCode().trim()
            );
            if (matches.size() > 1) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-EXT-FIELD-AMBIGUOUS",
                    "Ambiguous Custom Field Code Across Namespaces",
                    "Specify 'namespace' or 'fieldDefId' to disambiguate '" + item.fieldCode() + "'."
                );
            }
        }
        if (matches.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-EXT-FIELD-DEF-NOT-FOUND",
                "Custom Field Definition Not Found",
                "Active custom field definition was not found for " + ownerEntityType + "."
            );
        }
        return matches.getFirst();
    }

    private static String validateAndCanonicalizeValue(ExtensionDtos.CustomFieldDefDto def, String rawValue) {
        String trimmed = rawValue.trim();
        return switch (def.dataType()) {
            case "EXACT_DECIMAL" -> {
                if (trimmed.contains("e") || trimmed.contains("E")) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-ACC-DECIMAL-INVALID",
                        "Scientific Notation Rejected",
                        "Custom field '" + def.fieldCode() + "' must be an exact fixed-point decimal string."
                    );
                }
                int maxScale = def.decimalScale() == null ? 2 : def.decimalScale();
                BigDecimal parsed = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    trimmed,
                    maxScale,
                    new BigDecimal("-999999999999999999"),
                    new BigDecimal("999999999999999999"),
                    false,
                    def.fieldCode()
                );
                yield ExactMoneyValidator.formatExactScale(parsed, maxScale);
            }
            case "DATE" -> {
                if ("1900-02-29".equals(trimmed)) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-EXT-INVALID-DATE",
                        "Invalid Calendar Date",
                        "Fictitious leap date 1900-02-29 is rejected."
                    );
                }
                try {
                    yield LocalDate.parse(trimmed).toString();
                } catch (DateTimeParseException ex) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-EXT-INVALID-DATE",
                        "Invalid Custom Date Value",
                        "Custom field '" + def.fieldCode() + "' requires ISO-8601 YYYY-MM-DD date."
                    );
                }
            }
            case "BOOLEAN" -> {
                if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-EXT-INVALID-BOOLEAN",
                        "Invalid Custom Boolean Value",
                        "Custom field '" + def.fieldCode() + "' requires 'true' or 'false'."
                    );
                }
                yield Boolean.toString(Boolean.parseBoolean(trimmed));
            }
            case "ENUM" -> {
                if (!def.enumOptions().contains(trimmed)) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-EXT-INVALID-ENUM",
                        "Invalid Enum Selection",
                        "Value '" + trimmed + "' is not in allowed options " + def.enumOptions() + "."
                    );
                }
                yield trimmed;
            }
            default -> {
                if (trimmed.length() > 4096) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-EXT-TEXT-TOO-LONG",
                        "Custom Text Value Too Long",
                        "Custom field '" + def.fieldCode() + "' exceeds 4096 characters."
                    );
                }
                yield trimmed;
            }
        };
    }

    private static byte[] buildGcmAssociatedData(
        UUID companyId,
        ExtensionDtos.CustomFieldDefDto def,
        UUID entityId
    ) {
        return buildGcmAssociatedDataRaw(
            companyId,
            def.ownerEntityType(),
            entityId,
            def.namespace(),
            def.fieldUuid()
        );
    }

    private static byte[] buildGcmAssociatedDataRaw(
        UUID companyId,
        String ownerEntityType,
        UUID entityId,
        String namespace,
        UUID fieldUuid
    ) {
        String aad = "MC-EXT-CF-1|" + companyId + "|" + ownerEntityType
            + "|" + entityId + "|" + namespace + "|" + fieldUuid;
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    private ExtensionDtos.CustomFieldDefDto requireDefById(UUID companyId, UUID id) {
        List<ExtensionDtos.CustomFieldDefDto> rows = businessJdbcTemplate.query(
            """
            SELECT id, company_id, extension_package_id, namespace, field_uuid,
                   owner_entity_type, field_code, label, data_type, decimal_scale,
                   enum_options_json, default_value_text, protection_class, ui_anchor,
                   required_flag, active, version_no, created_at
              FROM mc_custom_field_def
             WHERE company_id = ? AND id = ?
            """,
            (rs, rowNum) -> mapFieldDefRow(rs),
            companyId,
            id
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-EXT-FIELD-DEF-NOT-FOUND",
                "Custom Field Definition Not Found",
                "Custom field definition " + id + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private ExtensionDtos.CustomFieldDefDto mapFieldDefRow(ResultSet rs) throws SQLException {
        Short decScale = rs.getObject("decimal_scale", Short.class);
        return new ExtensionDtos.CustomFieldDefDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getObject("extension_package_id", UUID.class),
            rs.getString("namespace"),
            rs.getObject("field_uuid", UUID.class),
            rs.getString("owner_entity_type"),
            rs.getString("field_code"),
            rs.getString("label"),
            rs.getString("data_type"),
            decScale == null ? null : decScale.intValue(),
            parseStringList(rs.getString("enum_options_json")),
            rs.getString("default_value_text"),
            rs.getString("protection_class"),
            rs.getString("ui_anchor"),
            rs.getBoolean("required_flag"),
            rs.getBoolean("active"),
            rs.getLong("version_no"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JacksonException ignored) {
            return List.of();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize custom field JSON", ex);
        }
    }

    public record NormalizedFieldSpec(
        String namespace,
        UUID fieldUuid,
        String ownerEntityType,
        String fieldCode,
        String label,
        String dataType,
        Integer decimalScale,
        List<String> enumOptions,
        String defaultValue,
        String protectionClass,
        String uiAnchor,
        boolean requiredFlag
    ) {
    }

    private record StoredValueJoinRow(
        UUID fieldDefId,
        String namespace,
        UUID fieldUuid,
        String ownerEntityType,
        UUID entityId,
        String fieldCode,
        String label,
        String dataType,
        String protectionClass,
        String uiAnchor,
        long defVersionNo,
        String plainValueText,
        byte[] gcmCiphertext,
        long valueVersion,
        java.time.Instant updatedAt
    ) {
    }
}
