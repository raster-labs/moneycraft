package com.raster.moneycraft.inventory.service;

import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.EditLogSnapshotService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.inventory.dto.InventoryMasterDtos;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * Inventory Master CRUD, Optimistic Concurrency (ETag/If-Match), Opening Batch & Cost Layer
 * Seeding, Staggered Price Lookup, Multi-BOM Management, MCA Rule 3(1) Edit Log, and MC-AUDIT-2.
 */
@Service
public class InventoryMasterService {

    private static final Set<String> VALID_UOM_TYPES = Set.of("SIMPLE", "COMPOUND");
    private static final Set<String> VALID_MARKET_VAL_METHODS = Set.of(
        "AVG_PRICE", "LAST_SALE_PRICE", "STD_PRICE", "AT_ZERO_PRICE"
    );
    private static final Set<String> VALID_GST_APPLICABILITY = Set.of(
        "APPLICABLE", "NOT_APPLICABLE", "EXEMPT", "NIL_RATED", "NON_GST"
    );
    private static final Set<String> VALID_GST_SUPPLY_TYPES = Set.of(
        "GOODS", "SERVICES", "CAPITAL_GOODS"
    );
    private static final Set<String> VALID_BOM_COMPONENT_TYPES = Set.of(
        "RAW_MATERIAL", "CO_PRODUCT", "BY_PRODUCT", "SCRAP"
    );
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal FIVE_HUNDRED = new BigDecimal("500");

    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;
    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final EditLogSnapshotService editLogSnapshotService;
    private final AuditChainService auditChainService;
    private final ValuationService valuationService;
    private final ObjectMapper objectMapper;

    public InventoryMasterService(
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTxManager,
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        EditLogSnapshotService editLogSnapshotService,
        AuditChainService auditChainService,
        ValuationService valuationService,
        ObjectMapper objectMapper
    ) {
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTxManager);
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.editLogSnapshotService = editLogSnapshotService;
        this.auditChainService = auditChainService;
        this.valuationService = valuationService;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // 1. Units of Measure (mc_uom)
    // =========================================================================

    public List<InventoryMasterDtos.UomDto> listUoms(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessJdbcTemplate.query(
            """
            SELECT u.id, u.company_id, u.uom_type, u.symbol, u.formal_name, u.gst_uqc_code,
                   u.decimal_places, u.first_uom_id, fu.symbol AS first_symbol,
                   u.conversion_factor, u.second_uom_id, su.symbol AS second_symbol,
                   u.active, u.master_seq_id, u.alter_id, u.version_no, u.created_at, u.updated_at
              FROM mc_uom u
              LEFT JOIN mc_uom fu ON fu.company_id = u.company_id AND fu.id = u.first_uom_id
              LEFT JOIN mc_uom su ON su.company_id = u.company_id AND su.id = u.second_uom_id
             WHERE u.company_id = ? AND u.active = true
             ORDER BY lower(u.symbol) ASC, u.id ASC
            """,
            (rs, rowNum) -> mapUomRow(rs),
            companyId
        );
    }

    public InventoryMasterDtos.UomDto getUom(MoneyCraftPrincipal principal, UUID companyId, UUID uomId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return requireUomById(companyId, uomId);
    }

    public InventoryMasterDtos.UomDto createUom(
        MoneyCraftPrincipal actor,
        UUID companyId,
        InventoryMasterDtos.UpsertUomRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        NormalizedUomSpec spec = normalizeUom(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, false);
            assertUniqueUomSymbol(companyId, spec.symbol(), null);
            if ("COMPOUND".equals(spec.uomType())) {
                requireUomById(companyId, spec.firstUomId());
                requireUomById(companyId, spec.secondUomId());
            }

            UUID id = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_uom (
                    id, company_id, uom_type, symbol, formal_name, gst_uqc_code, decimal_places,
                    first_uom_id, conversion_factor, second_uom_id, active,
                    master_seq_id, alter_id, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                id,
                companyId,
                spec.uomType(),
                spec.symbol(),
                spec.formalName(),
                spec.gstUqcCode(),
                (short) spec.decimalPlaces(),
                spec.firstUomId(),
                spec.conversionFactor(),
                spec.secondUomId(),
                spec.active(),
                seq.masterSeqId(),
                seq.alterId()
            );

            InventoryMasterDtos.UomDto created = requireUomById(companyId, id);
            editLogSnapshotService.recordVersion(
                companyId,
                "UOM",
                id,
                created.masterSeqId(),
                created.alterId(),
                created.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                created.symbol(),
                null,
                null,
                created
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "UOM_CREATE",
                "UOM",
                id.toString(),
                "SUCCESS",
                null,
                Map.of("symbol", created.symbol(), "uomType", created.uomType())
            );
            return created;
        });
    }

    public InventoryMasterDtos.UomDto updateUom(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID uomId,
        String ifMatchHeader,
        InventoryMasterDtos.UpsertUomRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        NormalizedUomSpec spec = normalizeUom(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.UomDto before = lockUomById(companyId, uomId);
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, before.versionNo(), "uom");
            assertUniqueUomSymbol(companyId, spec.symbol(), uomId);
            if ("COMPOUND".equals(spec.uomType())) {
                if (uomId.equals(spec.firstUomId()) || uomId.equals(spec.secondUomId())) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-INV-UOM-SELF-REF",
                        "Invalid Compound UOM",
                        "Compound UOM cannot reference itself."
                    );
                }
                requireUomById(companyId, spec.firstUomId());
                requireUomById(companyId, spec.secondUomId());
            }

            long nextVersion = before.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_uom
                   SET uom_type = ?,
                       symbol = ?,
                       formal_name = ?,
                       gst_uqc_code = ?,
                       decimal_places = ?,
                       first_uom_id = ?,
                       conversion_factor = ?,
                       second_uom_id = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.uomType(),
                spec.symbol(),
                spec.formalName(),
                spec.gstUqcCode(),
                (short) spec.decimalPlaces(),
                spec.firstUomId(),
                spec.conversionFactor(),
                spec.secondUomId(),
                spec.active(),
                seq.alterId(),
                nextVersion,
                companyId,
                uomId
            );

            InventoryMasterDtos.UomDto after = requireUomById(companyId, uomId);
            editLogSnapshotService.recordVersion(
                companyId,
                "UOM",
                uomId,
                after.masterSeqId(),
                after.alterId(),
                after.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                after.symbol(),
                null,
                before,
                after
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "UOM_UPDATE",
                "UOM",
                uomId.toString(),
                "SUCCESS",
                null,
                Map.of("symbol", after.symbol(), "versionNo", after.versionNo())
            );
            return after;
        });
    }

    public void deleteUom(MoneyCraftPrincipal actor, UUID companyId, UUID uomId) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);

        businessTxTemplate.executeWithoutResult(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.UomDto before = lockUomById(companyId, uomId);

            Long refs = businessJdbcTemplate.queryForObject(
                """
                SELECT (
                    (SELECT COUNT(*) FROM mc_stock_item WHERE company_id = ? AND (base_uom_id = ? OR alternate_uom_id = ?))
                    + (SELECT COUNT(*) FROM mc_uom WHERE company_id = ? AND (first_uom_id = ? OR second_uom_id = ?))
                    + (SELECT COUNT(*) FROM mc_inventory_allocation WHERE company_id = ? AND uom_id = ?)
                )
                """,
                Long.class,
                companyId, uomId, uomId,
                companyId, uomId, uomId,
                companyId, uomId
            );
            if (refs != null && refs > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-INV-UOM-IN-USE",
                    "UOM Referenced by Masters or Vouchers",
                    "UOM '" + before.symbol() + "' is referenced and cannot be deleted."
                );
            }

            businessJdbcTemplate.update("DELETE FROM mc_uom WHERE company_id = ? AND id = ?", companyId, uomId);
            editLogSnapshotService.recordVersion(
                companyId,
                "UOM",
                uomId,
                before.masterSeqId(),
                seq.alterId(),
                before.versionNo() + 1L,
                "DELETE",
                actor.principalId(),
                actor.username(),
                before.symbol(),
                null,
                before,
                Map.of("deleted", true, "id", uomId.toString())
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "UOM_DELETE",
                "UOM",
                uomId.toString(),
                "SUCCESS",
                null,
                Map.of("symbol", before.symbol())
            );
        });
    }

    // =========================================================================
    // 2. Hierarchical Godowns (mc_godown)
    // =========================================================================

    public List<InventoryMasterDtos.GodownDto> listGodowns(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessJdbcTemplate.query(
            """
            SELECT g.id, g.company_id, g.name, g.alias_code, g.parent_godown_id, pg.name AS parent_name,
                   g.address, g.allow_storage_of_materials, g.our_stock_with_third_party,
                   g.third_party_stock_with_us, g.tree_path, g.depth_level, g.is_system_godown,
                   g.active, g.master_seq_id, g.alter_id, g.version_no, g.created_at, g.updated_at
              FROM mc_godown g
              LEFT JOIN mc_godown pg ON pg.company_id = g.company_id AND pg.id = g.parent_godown_id
             WHERE g.company_id = ? AND g.active = true
             ORDER BY g.is_system_godown DESC, g.depth_level ASC, lower(g.name) ASC
            """,
            (rs, rowNum) -> mapGodownRow(rs),
            companyId
        );
    }

    public InventoryMasterDtos.GodownDto getGodown(MoneyCraftPrincipal principal, UUID companyId, UUID godownId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return requireGodownById(companyId, godownId);
    }

    public InventoryMasterDtos.GodownDto createGodown(
        MoneyCraftPrincipal actor,
        UUID companyId,
        InventoryMasterDtos.UpsertGodownRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        NormalizedGodownSpec spec = normalizeGodown(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, false);
            assertUniqueGodownName(companyId, spec.name(), null);
            if (spec.parentGodownId() != null) {
                requireGodownById(companyId, spec.parentGodownId());
            }

            UUID id = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_godown (
                    id, company_id, name, alias_code, parent_godown_id, address,
                    allow_storage_of_materials, our_stock_with_third_party, third_party_stock_with_us,
                    is_system_godown, active, master_seq_id, alter_id, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?, 1)
                """,
                id,
                companyId,
                spec.name(),
                spec.aliasCode(),
                spec.parentGodownId(),
                spec.address(),
                spec.allowStorage(),
                spec.ourStockWithThirdParty(),
                spec.thirdPartyStockWithUs(),
                spec.active(),
                seq.masterSeqId(),
                seq.alterId()
            );

            InventoryMasterDtos.GodownDto created = requireGodownById(companyId, id);
            editLogSnapshotService.recordVersion(
                companyId,
                "GODOWN",
                id,
                created.masterSeqId(),
                created.alterId(),
                created.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                created.name(),
                null,
                null,
                created
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "GODOWN_CREATE",
                "GODOWN",
                id.toString(),
                "SUCCESS",
                null,
                Map.of("name", created.name())
            );
            return created;
        });
    }

    public InventoryMasterDtos.GodownDto updateGodown(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID godownId,
        String ifMatchHeader,
        InventoryMasterDtos.UpsertGodownRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        NormalizedGodownSpec spec = normalizeGodown(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.GodownDto before = lockGodownById(companyId, godownId);
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, before.versionNo(), "godown");

            if (before.isSystemGodown() && !before.name().equalsIgnoreCase(spec.name())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-SYSTEM-GODOWN-PROTECTED",
                    "System Godown Protected",
                    "Default godown '" + before.name() + "' cannot be renamed."
                );
            }
            assertUniqueGodownName(companyId, spec.name(), godownId);
            if (spec.parentGodownId() != null && !spec.parentGodownId().equals(godownId)) {
                requireGodownById(companyId, spec.parentGodownId());
            }

            long nextVersion = before.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_godown
                   SET name = ?,
                       alias_code = ?,
                       parent_godown_id = ?,
                       address = ?,
                       allow_storage_of_materials = ?,
                       our_stock_with_third_party = ?,
                       third_party_stock_with_us = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.name(),
                spec.aliasCode(),
                spec.parentGodownId(),
                spec.address(),
                spec.allowStorage(),
                spec.ourStockWithThirdParty(),
                spec.thirdPartyStockWithUs(),
                spec.active(),
                seq.alterId(),
                nextVersion,
                companyId,
                godownId
            );

            InventoryMasterDtos.GodownDto after = requireGodownById(companyId, godownId);
            editLogSnapshotService.recordVersion(
                companyId,
                "GODOWN",
                godownId,
                after.masterSeqId(),
                after.alterId(),
                after.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                after.name(),
                null,
                before,
                after
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "GODOWN_UPDATE",
                "GODOWN",
                godownId.toString(),
                "SUCCESS",
                null,
                Map.of("name", after.name(), "versionNo", after.versionNo())
            );
            return after;
        });
    }

    public void deleteGodown(MoneyCraftPrincipal actor, UUID companyId, UUID godownId) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);

        businessTxTemplate.executeWithoutResult(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.GodownDto before = lockGodownById(companyId, godownId);
            if (before.isSystemGodown()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-SYSTEM-GODOWN-PROTECTED",
                    "System Godown Protected",
                    "Default godown '" + before.name() + "' cannot be deleted."
                );
            }

            Long refs = businessJdbcTemplate.queryForObject(
                """
                SELECT (
                    (SELECT COUNT(*) FROM mc_godown WHERE company_id = ? AND parent_godown_id = ?)
                    + (SELECT COUNT(*) FROM mc_stock_batch WHERE company_id = ? AND godown_id = ?)
                    + (SELECT COUNT(*) FROM mc_batch_allocation WHERE company_id = ? AND godown_id = ?)
                    + (SELECT COUNT(*) FROM mc_bom_component WHERE company_id = ? AND godown_id = ?)
                )
                """,
                Long.class,
                companyId, godownId,
                companyId, godownId,
                companyId, godownId,
                companyId, godownId
            );
            if (refs != null && refs > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-INV-GODOWN-IN-USE",
                    "Godown Referenced by Children or Stock",
                    "Godown '" + before.name() + "' is referenced and cannot be deleted."
                );
            }

            businessJdbcTemplate.update("DELETE FROM mc_godown WHERE company_id = ? AND id = ?", companyId, godownId);
            editLogSnapshotService.recordVersion(
                companyId,
                "GODOWN",
                godownId,
                before.masterSeqId(),
                seq.alterId(),
                before.versionNo() + 1L,
                "DELETE",
                actor.principalId(),
                actor.username(),
                before.name(),
                null,
                before,
                Map.of("deleted", true, "id", godownId.toString())
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "GODOWN_DELETE",
                "GODOWN",
                godownId.toString(),
                "SUCCESS",
                null,
                Map.of("name", before.name())
            );
        });
    }

    // =========================================================================
    // 3. Stock Groups (mc_stock_group) & Stock Categories (mc_stock_category)
    // =========================================================================

    public List<InventoryMasterDtos.StockGroupDto> listStockGroups(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessJdbcTemplate.query(
            """
            SELECT sg.id, sg.company_id, sg.name, sg.alias_code,
                   sg.parent_stock_group_id, pg.name AS parent_name,
                   sg.should_quantities_of_items_be_added, sg.hsn_sac_code,
                   sg.gst_rate_pct, sg.cess_rate_pct, sg.tree_path, sg.depth_level,
                   sg.active, sg.master_seq_id, sg.alter_id, sg.version_no, sg.created_at, sg.updated_at
              FROM mc_stock_group sg
              LEFT JOIN mc_stock_group pg ON pg.company_id = sg.company_id AND pg.id = sg.parent_stock_group_id
             WHERE sg.company_id = ? AND sg.active = true
             ORDER BY sg.depth_level ASC, lower(sg.name) ASC
            """,
            (rs, rowNum) -> mapStockGroupRow(rs),
            companyId
        );
    }

    public InventoryMasterDtos.StockGroupDto getStockGroup(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID stockGroupId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return requireStockGroupById(companyId, stockGroupId);
    }

    public InventoryMasterDtos.StockGroupDto createStockGroup(
        MoneyCraftPrincipal actor,
        UUID companyId,
        InventoryMasterDtos.UpsertStockGroupRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        NormalizedStockGroupSpec spec = normalizeStockGroup(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, false);
            assertUniqueStockGroupName(companyId, spec.name(), null);
            if (spec.parentStockGroupId() != null) {
                requireStockGroupById(companyId, spec.parentStockGroupId());
            }

            UUID id = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_stock_group (
                    id, company_id, name, alias_code, parent_stock_group_id,
                    should_quantities_of_items_be_added, hsn_sac_code, gst_rate_pct, cess_rate_pct,
                    active, master_seq_id, alter_id, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                id,
                companyId,
                spec.name(),
                spec.aliasCode(),
                spec.parentStockGroupId(),
                spec.shouldAddQuantities(),
                spec.hsnSacCode(),
                spec.gstRatePct(),
                spec.cessRatePct(),
                spec.active(),
                seq.masterSeqId(),
                seq.alterId()
            );

            InventoryMasterDtos.StockGroupDto created = requireStockGroupById(companyId, id);
            editLogSnapshotService.recordVersion(
                companyId,
                "STOCK_GROUP",
                id,
                created.masterSeqId(),
                created.alterId(),
                created.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                created.name(),
                null,
                null,
                created
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "STOCK_GROUP_CREATE",
                "STOCK_GROUP",
                id.toString(),
                "SUCCESS",
                null,
                Map.of("name", created.name())
            );
            return created;
        });
    }

    public InventoryMasterDtos.StockGroupDto updateStockGroup(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID stockGroupId,
        String ifMatchHeader,
        InventoryMasterDtos.UpsertStockGroupRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        NormalizedStockGroupSpec spec = normalizeStockGroup(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.StockGroupDto before = lockStockGroupById(companyId, stockGroupId);
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, before.versionNo(), "stockGroup");
            assertUniqueStockGroupName(companyId, spec.name(), stockGroupId);
            if (spec.parentStockGroupId() != null && !spec.parentStockGroupId().equals(stockGroupId)) {
                requireStockGroupById(companyId, spec.parentStockGroupId());
            }

            long nextVersion = before.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_stock_group
                   SET name = ?,
                       alias_code = ?,
                       parent_stock_group_id = ?,
                       should_quantities_of_items_be_added = ?,
                       hsn_sac_code = ?,
                       gst_rate_pct = ?,
                       cess_rate_pct = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.name(),
                spec.aliasCode(),
                spec.parentStockGroupId(),
                spec.shouldAddQuantities(),
                spec.hsnSacCode(),
                spec.gstRatePct(),
                spec.cessRatePct(),
                spec.active(),
                seq.alterId(),
                nextVersion,
                companyId,
                stockGroupId
            );

            InventoryMasterDtos.StockGroupDto after = requireStockGroupById(companyId, stockGroupId);
            editLogSnapshotService.recordVersion(
                companyId,
                "STOCK_GROUP",
                stockGroupId,
                after.masterSeqId(),
                after.alterId(),
                after.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                after.name(),
                null,
                before,
                after
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "STOCK_GROUP_UPDATE",
                "STOCK_GROUP",
                stockGroupId.toString(),
                "SUCCESS",
                null,
                Map.of("name", after.name(), "versionNo", after.versionNo())
            );
            return after;
        });
    }

    public void deleteStockGroup(MoneyCraftPrincipal actor, UUID companyId, UUID stockGroupId) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);

        businessTxTemplate.executeWithoutResult(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.StockGroupDto before = lockStockGroupById(companyId, stockGroupId);

            Long refs = businessJdbcTemplate.queryForObject(
                """
                SELECT (
                    (SELECT COUNT(*) FROM mc_stock_group WHERE company_id = ? AND parent_stock_group_id = ?)
                    + (SELECT COUNT(*) FROM mc_stock_item WHERE company_id = ? AND stock_group_id = ?)
                )
                """,
                Long.class,
                companyId, stockGroupId,
                companyId, stockGroupId
            );
            if (refs != null && refs > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-INV-STOCKGROUP-IN-USE",
                    "Stock Group Referenced",
                    "Stock group '" + before.name() + "' has child groups or stock items and cannot be deleted."
                );
            }

            businessJdbcTemplate.update(
                "DELETE FROM mc_stock_group WHERE company_id = ? AND id = ?",
                companyId,
                stockGroupId
            );
            editLogSnapshotService.recordVersion(
                companyId,
                "STOCK_GROUP",
                stockGroupId,
                before.masterSeqId(),
                seq.alterId(),
                before.versionNo() + 1L,
                "DELETE",
                actor.principalId(),
                actor.username(),
                before.name(),
                null,
                before,
                Map.of("deleted", true, "id", stockGroupId.toString())
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "STOCK_GROUP_DELETE",
                "STOCK_GROUP",
                stockGroupId.toString(),
                "SUCCESS",
                null,
                Map.of("name", before.name())
            );
        });
    }

    public List<InventoryMasterDtos.StockCategoryDto> listStockCategories(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessJdbcTemplate.query(
            """
            SELECT sc.id, sc.company_id, sc.name, sc.alias_code,
                   sc.parent_stock_category_id, pc.name AS parent_name,
                   sc.tree_path, sc.depth_level, sc.active,
                   sc.master_seq_id, sc.alter_id, sc.version_no, sc.created_at, sc.updated_at
              FROM mc_stock_category sc
              LEFT JOIN mc_stock_category pc ON pc.company_id = sc.company_id AND pc.id = sc.parent_stock_category_id
             WHERE sc.company_id = ? AND sc.active = true
             ORDER BY sc.depth_level ASC, lower(sc.name) ASC
            """,
            (rs, rowNum) -> mapStockCategoryRow(rs),
            companyId
        );
    }

    public InventoryMasterDtos.StockCategoryDto getStockCategory(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID stockCategoryId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return requireStockCategoryById(companyId, stockCategoryId);
    }

    public InventoryMasterDtos.StockCategoryDto createStockCategory(
        MoneyCraftPrincipal actor,
        UUID companyId,
        InventoryMasterDtos.UpsertStockCategoryRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        String name = requireNonBlank(request == null ? null : request.name(), "name", 160);
        String alias = trimNullable(request.aliasCode(), 64);
        boolean active = request.active() == null || request.active();

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, false);
            assertUniqueStockCategoryName(companyId, name, null);
            UUID id = UUID.randomUUID();
            TreePosition pos = computeCategoryTreePosition(companyId, id, request.parentStockCategoryId());

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_stock_category (
                    id, company_id, name, alias_code, parent_stock_category_id,
                    tree_path, depth_level, active, master_seq_id, alter_id, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                id,
                companyId,
                name,
                alias,
                request.parentStockCategoryId(),
                pos.treePath(),
                (short) pos.depthLevel(),
                active,
                seq.masterSeqId(),
                seq.alterId()
            );

            InventoryMasterDtos.StockCategoryDto created = requireStockCategoryById(companyId, id);
            editLogSnapshotService.recordVersion(
                companyId,
                "STOCK_CATEGORY",
                id,
                created.masterSeqId(),
                created.alterId(),
                created.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                created.name(),
                null,
                null,
                created
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "STOCK_CATEGORY_CREATE",
                "STOCK_CATEGORY",
                id.toString(),
                "SUCCESS",
                null,
                Map.of("name", created.name())
            );
            return created;
        });
    }

    public InventoryMasterDtos.StockCategoryDto updateStockCategory(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID stockCategoryId,
        String ifMatchHeader,
        InventoryMasterDtos.UpsertStockCategoryRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        String name = requireNonBlank(request == null ? null : request.name(), "name", 160);
        String alias = trimNullable(request.aliasCode(), 64);
        boolean active = request.active() == null || request.active();

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.StockCategoryDto before = lockStockCategoryById(companyId, stockCategoryId);
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, before.versionNo(), "stockCategory");
            assertUniqueStockCategoryName(companyId, name, stockCategoryId);
            TreePosition pos = computeCategoryTreePosition(companyId, stockCategoryId, request.parentStockCategoryId());

            long nextVersion = before.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_stock_category
                   SET name = ?,
                       alias_code = ?,
                       parent_stock_category_id = ?,
                       tree_path = ?,
                       depth_level = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                name,
                alias,
                request.parentStockCategoryId(),
                pos.treePath(),
                (short) pos.depthLevel(),
                active,
                seq.alterId(),
                nextVersion,
                companyId,
                stockCategoryId
            );

            InventoryMasterDtos.StockCategoryDto after = requireStockCategoryById(companyId, stockCategoryId);
            editLogSnapshotService.recordVersion(
                companyId,
                "STOCK_CATEGORY",
                stockCategoryId,
                after.masterSeqId(),
                after.alterId(),
                after.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                after.name(),
                null,
                before,
                after
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "STOCK_CATEGORY_UPDATE",
                "STOCK_CATEGORY",
                stockCategoryId.toString(),
                "SUCCESS",
                null,
                Map.of("name", after.name(), "versionNo", after.versionNo())
            );
            return after;
        });
    }

    public void deleteStockCategory(MoneyCraftPrincipal actor, UUID companyId, UUID stockCategoryId) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);

        businessTxTemplate.executeWithoutResult(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.StockCategoryDto before = lockStockCategoryById(companyId, stockCategoryId);

            Long refs = businessJdbcTemplate.queryForObject(
                """
                SELECT (
                    (SELECT COUNT(*) FROM mc_stock_category WHERE company_id = ? AND parent_stock_category_id = ?)
                    + (SELECT COUNT(*) FROM mc_stock_item WHERE company_id = ? AND stock_category_id = ?)
                )
                """,
                Long.class,
                companyId, stockCategoryId,
                companyId, stockCategoryId
            );
            if (refs != null && refs > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-INV-STOCKCATEGORY-IN-USE",
                    "Stock Category Referenced",
                    "Stock category '" + before.name() + "' is referenced and cannot be deleted."
                );
            }

            businessJdbcTemplate.update(
                "DELETE FROM mc_stock_category WHERE company_id = ? AND id = ?",
                companyId,
                stockCategoryId
            );
            editLogSnapshotService.recordVersion(
                companyId,
                "STOCK_CATEGORY",
                stockCategoryId,
                before.masterSeqId(),
                seq.alterId(),
                before.versionNo() + 1L,
                "DELETE",
                actor.principalId(),
                actor.username(),
                before.name(),
                null,
                before,
                Map.of("deleted", true, "id", stockCategoryId.toString())
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "STOCK_CATEGORY_DELETE",
                "STOCK_CATEGORY",
                stockCategoryId.toString(),
                "SUCCESS",
                null,
                Map.of("name", before.name())
            );
        });
    }

    // =========================================================================
    // 4. Stock Items & Batches (mc_stock_item, mc_stock_batch, mc_stock_cost_layer)
    // =========================================================================

    public List<InventoryMasterDtos.StockItemDto> listStockItems(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID stockGroupId,
        String q
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        StringBuilder sql = new StringBuilder(stockItemSelectPrefix());
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        sql.append(" WHERE si.company_id = ? AND si.active = true");

        if (stockGroupId != null) {
            sql.append(" AND si.stock_group_id = ?");
            params.add(stockGroupId);
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(
                """
                 AND (
                     lower(si.name) LIKE ?
                     OR lower(COALESCE(si.alias_code, '')) LIKE ?
                     OR lower(COALESCE(si.part_no, '')) LIKE ?
                     OR lower(COALESCE(si.hsn_sac_code, '')) LIKE ?
                 )
                """
            );
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        sql.append(" ORDER BY lower(si.name) ASC, si.id ASC");

        return businessJdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> mapStockItemRow(rs, scale),
            params.toArray()
        );
    }

    public List<InventoryMasterDtos.StockItemDto> typeaheadStockItems(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String q,
        Integer rawLimit
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        int limit = CompanyMasterSequenceHelper.normalizePageLimit(rawLimit, 50, 100);
        StringBuilder sql = new StringBuilder(stockItemSelectPrefix());
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        sql.append(" WHERE si.company_id = ? AND si.active = true");

        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(
                """
                 AND (
                     lower(si.name) LIKE ?
                     OR lower(COALESCE(si.alias_code, '')) LIKE ?
                     OR lower(COALESCE(si.part_no, '')) LIKE ?
                     OR lower(COALESCE(si.hsn_sac_code, '')) LIKE ?
                 )
                """
            );
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        sql.append(" ORDER BY lower(si.name) ASC LIMIT ?");
        params.add(limit);

        return businessJdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> mapStockItemRow(rs, scale),
            params.toArray()
        );
    }

    public InventoryMasterDtos.StockItemDto getStockItem(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID stockItemId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        return requireStockItemById(companyId, stockItemId, scale);
    }

    public InventoryMasterDtos.StockItemDto createStockItem(
        MoneyCraftPrincipal actor,
        UUID companyId,
        InventoryMasterDtos.UpsertStockItemRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, false);
            NormalizedStockItemSpec spec = normalizeStockItem(request, seq.currencyScale());
            assertUniqueStockItemName(companyId, spec.name(), null);
            validateStockItemReferences(companyId, spec);

            UUID id = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_stock_item (
                    id, company_id, name, alias_code, part_no, description,
                    stock_group_id, stock_category_id, base_uom_id, alternate_uom_id,
                    conversion_numerator, conversion_denominator,
                    maintain_in_batches, track_date_of_mfg, use_expiry_dates,
                    costing_method, market_valuation_method, standard_cost, standard_selling_price,
                    opening_qty, opening_rate, opening_value,
                    gst_applicability, gst_supply_type, hsn_sac_code,
                    gst_rate_pct, cess_rate_pct, cess_per_unit,
                    is_tobacco_mrp_item, mrp_per_unit,
                    default_sales_ledger_id, default_purchase_ledger_id,
                    active, master_seq_id, alter_id, version_no, created_by
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?,
                    ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?, ?, 1, ?
                )
                """,
                id,
                companyId,
                spec.name(),
                spec.aliasCode(),
                spec.partNo(),
                spec.description(),
                spec.stockGroupId(),
                spec.stockCategoryId(),
                spec.baseUomId(),
                spec.alternateUomId(),
                spec.conversionNumerator(),
                spec.conversionDenominator(),
                spec.maintainInBatches(),
                spec.trackDateOfMfg(),
                spec.useExpiryDates(),
                spec.costingMethod(),
                spec.marketValuationMethod(),
                spec.standardCost(),
                spec.standardSellingPrice(),
                spec.openingQty(),
                spec.openingRate(),
                spec.openingValue(),
                spec.gstApplicability(),
                spec.gstSupplyType(),
                spec.hsnSacCode(),
                spec.gstRatePct(),
                spec.cessRatePct(),
                spec.cessPerUnit(),
                spec.isTobaccoMrpItem(),
                spec.mrpPerUnit(),
                spec.defaultSalesLedgerId(),
                spec.defaultPurchaseLedgerId(),
                spec.active(),
                seq.masterSeqId(),
                seq.alterId(),
                actor.principalId()
            );

            if (spec.openingQty().compareTo(BigDecimal.ZERO) > 0) {
                reconcileOpeningBatchAndLayers(companyId, id, null, spec);
            }

            InventoryMasterDtos.StockItemDto created = requireStockItemById(companyId, id, seq.currencyScale());
            editLogSnapshotService.recordVersion(
                companyId,
                "STOCK_ITEM",
                id,
                created.masterSeqId(),
                created.alterId(),
                created.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                created.name(),
                null,
                null,
                created
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "STOCK_ITEM_CREATE",
                "STOCK_ITEM",
                id.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "name", created.name(),
                    "costingMethod", created.costingMethod(),
                    "openingQty", created.openingQty()
                )
            );
            return created;
        });
    }

    public InventoryMasterDtos.StockItemDto updateStockItem(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID stockItemId,
        String ifMatchHeader,
        InventoryMasterDtos.UpsertStockItemRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.StockItemDto before = lockStockItemById(companyId, stockItemId, seq.currencyScale());
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, before.versionNo(), "stockItem");

            NormalizedStockItemSpec spec = normalizeStockItem(request, seq.currencyScale());
            assertUniqueStockItemName(companyId, spec.name(), stockItemId);
            validateStockItemReferences(companyId, spec);

            long nextVersion = before.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_stock_item
                   SET name = ?,
                       alias_code = ?,
                       part_no = ?,
                       description = ?,
                       stock_group_id = ?,
                       stock_category_id = ?,
                       base_uom_id = ?,
                       alternate_uom_id = ?,
                       conversion_numerator = ?,
                       conversion_denominator = ?,
                       maintain_in_batches = ?,
                       track_date_of_mfg = ?,
                       use_expiry_dates = ?,
                       costing_method = ?,
                       market_valuation_method = ?,
                       standard_cost = ?,
                       standard_selling_price = ?,
                       opening_qty = ?,
                       opening_rate = ?,
                       opening_value = ?,
                       gst_applicability = ?,
                       gst_supply_type = ?,
                       hsn_sac_code = ?,
                       gst_rate_pct = ?,
                       cess_rate_pct = ?,
                       cess_per_unit = ?,
                       is_tobacco_mrp_item = ?,
                       mrp_per_unit = ?,
                       default_sales_ledger_id = ?,
                       default_purchase_ledger_id = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.name(),
                spec.aliasCode(),
                spec.partNo(),
                spec.description(),
                spec.stockGroupId(),
                spec.stockCategoryId(),
                spec.baseUomId(),
                spec.alternateUomId(),
                spec.conversionNumerator(),
                spec.conversionDenominator(),
                spec.maintainInBatches(),
                spec.trackDateOfMfg(),
                spec.useExpiryDates(),
                spec.costingMethod(),
                spec.marketValuationMethod(),
                spec.standardCost(),
                spec.standardSellingPrice(),
                spec.openingQty(),
                spec.openingRate(),
                spec.openingValue(),
                spec.gstApplicability(),
                spec.gstSupplyType(),
                spec.hsnSacCode(),
                spec.gstRatePct(),
                spec.cessRatePct(),
                spec.cessPerUnit(),
                spec.isTobaccoMrpItem(),
                spec.mrpPerUnit(),
                spec.defaultSalesLedgerId(),
                spec.defaultPurchaseLedgerId(),
                spec.active(),
                seq.alterId(),
                nextVersion,
                companyId,
                stockItemId
            );

            reconcileOpeningBatchAndLayers(companyId, stockItemId, before, spec);

            InventoryMasterDtos.StockItemDto after = requireStockItemById(companyId, stockItemId, seq.currencyScale());
            editLogSnapshotService.recordVersion(
                companyId,
                "STOCK_ITEM",
                stockItemId,
                after.masterSeqId(),
                after.alterId(),
                after.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                after.name(),
                null,
                before,
                after
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "STOCK_ITEM_UPDATE",
                "STOCK_ITEM",
                stockItemId.toString(),
                "SUCCESS",
                null,
                Map.of("name", after.name(), "versionNo", after.versionNo())
            );
            return after;
        });
    }

    public void deleteStockItem(MoneyCraftPrincipal actor, UUID companyId, UUID stockItemId) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);

        businessTxTemplate.executeWithoutResult(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            InventoryMasterDtos.StockItemDto before = lockStockItemById(companyId, stockItemId, seq.currencyScale());

            Long refs = businessJdbcTemplate.queryForObject(
                """
                SELECT (
                    (SELECT COUNT(*) FROM mc_inventory_allocation WHERE company_id = ? AND stock_item_id = ?)
                    + (SELECT COUNT(*) FROM mc_bom WHERE company_id = ? AND stock_item_id = ?)
                    + (SELECT COUNT(*) FROM mc_bom_component WHERE company_id = ? AND component_item_id = ?)
                )
                """,
                Long.class,
                companyId, stockItemId,
                companyId, stockItemId,
                companyId, stockItemId
            );
            if (refs != null && refs > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-INV-ITEM-IN-USE",
                    "Stock Item Referenced",
                    "Stock item '" + before.name() + "' has voucher allocations or BOM references."
                );
            }

            businessJdbcTemplate.update(
                "DELETE FROM mc_stock_cost_layer WHERE company_id = ? AND stock_item_id = ?",
                companyId,
                stockItemId
            );
            businessJdbcTemplate.update(
                "DELETE FROM mc_stock_batch WHERE company_id = ? AND stock_item_id = ?",
                companyId,
                stockItemId
            );
            businessJdbcTemplate.update(
                "DELETE FROM mc_price_tier WHERE company_id = ? AND stock_item_id = ?",
                companyId,
                stockItemId
            );
            businessJdbcTemplate.update(
                "DELETE FROM mc_stock_item WHERE company_id = ? AND id = ?",
                companyId,
                stockItemId
            );

            editLogSnapshotService.recordVersion(
                companyId,
                "STOCK_ITEM",
                stockItemId,
                before.masterSeqId(),
                seq.alterId(),
                before.versionNo() + 1L,
                "DELETE",
                actor.principalId(),
                actor.username(),
                before.name(),
                null,
                before,
                Map.of("deleted", true, "id", stockItemId.toString())
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "STOCK_ITEM_DELETE",
                "STOCK_ITEM",
                stockItemId.toString(),
                "SUCCESS",
                null,
                Map.of("name", before.name())
            );
        });
    }

    public List<InventoryMasterDtos.StockBatchDto> listStockItemBatches(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID stockItemId,
        UUID godownId,
        LocalDate asOfDate,
        boolean includeZero
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        ValuationService.ItemValuationMeta item = valuationService.loadSingleItemMeta(companyId, stockItemId);
        if (item == null) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-ITEM-NOT-FOUND",
                "Stock Item Not Found",
                "Stock item " + stockItemId + " does not exist."
            );
        }

        LocalDate effectiveCheckDate = asOfDate != null ? asOfDate : LocalDate.now();
        Map<UUID, BigDecimal> historicalClosing = null;
        if (asOfDate != null) {
            historicalClosing = valuationService.computeBatchClosingQuantitiesAsOf(
                companyId,
                stockItemId,
                asOfDate,
                Map.of(stockItemId, item),
                valuationService.loadCompanyUoms(companyId)
            );
        }
        ValuationService.PointValuation pv = valuationService.evaluateItemAtDate(
            companyId,
            stockItemId,
            effectiveCheckDate
        );

        StringBuilder sql = new StringBuilder(
            """
            SELECT b.id, b.stock_item_id, b.godown_id, g.name AS godown_name,
                   b.batch_name, b.mfg_date, b.expiry_date, b.mrp_per_unit,
                   b.opening_qty, b.closing_qty
              FROM mc_stock_batch b
              JOIN mc_godown g ON g.company_id = b.company_id AND g.id = b.godown_id
             WHERE b.company_id = ? AND b.stock_item_id = ?
            """
        );
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(stockItemId);
        if (godownId != null) {
            sql.append(" AND b.godown_id = ?");
            params.add(godownId);
        }
        sql.append(" ORDER BY b.expiry_date ASC NULLS LAST, lower(b.batch_name) ASC");

        Map<UUID, BigDecimal> histRef = historicalClosing;
        List<InventoryMasterDtos.StockBatchDto> result = new ArrayList<>();
        businessJdbcTemplate.query(
            sql.toString(),
            rs -> {
                UUID bId = rs.getObject("id", UUID.class);
                BigDecimal closing = histRef != null
                    ? histRef.getOrDefault(bId, rs.getBigDecimal("opening_qty"))
                    : rs.getBigDecimal("closing_qty");
                if (!includeZero && closing.compareTo(BigDecimal.ZERO) == 0) {
                    return;
                }
                LocalDate exp = rs.getObject("expiry_date", LocalDate.class);
                boolean expired = exp != null && exp.isBefore(effectiveCheckDate);
                BigDecimal mrp = rs.getBigDecimal("mrp_per_unit");
                result.add(new InventoryMasterDtos.StockBatchDto(
                    bId,
                    bId,
                    stockItemId,
                    rs.getObject("godown_id", UUID.class),
                    rs.getString("godown_name"),
                    rs.getString("batch_name"),
                    rs.getObject("mfg_date", LocalDate.class),
                    exp,
                    mrp == null ? null : ExactMoneyValidator.formatExactScale(mrp.setScale(scale, RoundingMode.HALF_UP), scale),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("opening_qty")),
                    ExactMoneyValidator.formatPlainDecimal(closing),
                    ExactMoneyValidator.formatExactScale(pv.closingRate(), scale),
                    expired,
                    expired
                ));
            },
            params.toArray()
        );
        return result;
    }

    // =========================================================================
    // 5. Price Lists & Staggered Slabs (mc_price_list, mc_price_tier)
    // =========================================================================

    public List<InventoryMasterDtos.PriceListDto> listPriceLists(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        List<PriceListHeaderRow> headers = businessJdbcTemplate.query(
            """
            SELECT id, company_id, price_level_name, effective_from, trim(currency_code) AS ccy,
                   active, version_no, created_at
              FROM mc_price_list
             WHERE company_id = ? AND active = true
             ORDER BY lower(price_level_name) ASC, effective_from DESC
            """,
            (rs, rowNum) -> new PriceListHeaderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("price_level_name"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getString("ccy"),
                rs.getBoolean("active"),
                rs.getLong("version_no"),
                toInstant(rs, "created_at")
            ),
            companyId
        );
        if (headers.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<InventoryMasterDtos.PriceTierDto>> tiersByList = loadAllPriceTiers(companyId, scale);
        return headers.stream()
            .map(h -> new InventoryMasterDtos.PriceListDto(
                h.id(),
                h.companyId(),
                h.priceLevelName(),
                h.effectiveFrom(),
                h.effectiveFrom(),
                h.currencyCode(),
                tiersByList.getOrDefault(h.id(), List.of()),
                h.active(),
                h.versionNo(),
                h.createdAt()
            ))
            .toList();
    }

    public InventoryMasterDtos.PriceListDto getPriceList(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID priceListId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        return requirePriceListById(companyId, priceListId, scale);
    }

    public InventoryMasterDtos.PriceListDto createPriceList(
        MoneyCraftPrincipal actor,
        UUID companyId,
        InventoryMasterDtos.UpsertPriceListRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, false);
            NormalizedPriceListSpec spec = normalizePriceList(request, seq.baseCurrencyCode());

            Long dup = businessJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM mc_price_list
                 WHERE company_id = ? AND price_level_name = ? AND effective_from = ?
                """,
                Long.class,
                companyId,
                spec.priceLevelName(),
                spec.effectiveFrom()
            );
            if (dup != null && dup > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-INV-DUPLICATE-PRICE-LIST",
                    "Duplicate Price List",
                    "Price list '" + spec.priceLevelName() + "' effective " + spec.effectiveFrom() + " already exists."
                );
            }

            UUID id = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_price_list (
                    id, company_id, price_level_name, effective_from, currency_code, active, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, 1)
                """,
                id,
                companyId,
                spec.priceLevelName(),
                spec.effectiveFrom(),
                spec.currencyCode(),
                spec.active()
            );

            insertPriceTiers(companyId, id, spec.tiers());
            InventoryMasterDtos.PriceListDto created = requirePriceListById(companyId, id, seq.currencyScale());
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "PRICE_LIST_CREATE",
                "PRICE_LIST",
                id.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "priceLevelName", created.priceLevelName(),
                    "effectiveFrom", created.effectiveFrom().toString(),
                    "tierCount", created.tiers().size()
                )
            );
            return created;
        });
    }

    public InventoryMasterDtos.PriceListDto updatePriceList(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID priceListId,
        String ifMatchHeader,
        InventoryMasterDtos.UpsertPriceListRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);
            List<Long> verRows = businessJdbcTemplate.query(
                "SELECT version_no FROM mc_price_list WHERE company_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("version_no"),
                companyId,
                priceListId
            );
            if (verRows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-INV-PRICE-LIST-NOT-FOUND",
                    "Price List Not Found",
                    "Price list " + priceListId + " does not exist."
                );
            }
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, verRows.getFirst(), "priceList");

            NormalizedPriceListSpec spec = normalizePriceList(request, seq.baseCurrencyCode());
            long nextVersion = verRows.getFirst() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_price_list
                   SET price_level_name = ?,
                       effective_from = ?,
                       currency_code = ?,
                       active = ?,
                       version_no = ?
                 WHERE company_id = ? AND id = ?
                """,
                spec.priceLevelName(),
                spec.effectiveFrom(),
                spec.currencyCode(),
                spec.active(),
                nextVersion,
                companyId,
                priceListId
            );

            businessJdbcTemplate.update(
                "DELETE FROM mc_price_tier WHERE company_id = ? AND price_list_id = ?",
                companyId,
                priceListId
            );
            insertPriceTiers(companyId, priceListId, spec.tiers());

            InventoryMasterDtos.PriceListDto updated = requirePriceListById(
                companyId,
                priceListId,
                seq.currencyScale()
            );
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "PRICE_LIST_UPDATE",
                "PRICE_LIST",
                priceListId.toString(),
                "SUCCESS",
                null,
                Map.of("priceLevelName", updated.priceLevelName(), "versionNo", updated.versionNo())
            );
            return updated;
        });
    }

    public InventoryMasterDtos.PriceLookupResponse lookupPrice(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String priceLevelName,
        UUID stockItemId,
        String rawQty,
        LocalDate rawAsOfDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        if (priceLevelName == null || priceLevelName.isBlank() || stockItemId == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-PRICE-LOOKUP-INVALID",
                "Invalid Price Lookup Parameters",
                "priceLevelName and stockItemId are required."
            );
        }
        BigDecimal qty = rawQty == null || rawQty.isBlank()
            ? BigDecimal.ONE
            : ExactMoneyValidator.parseAndValidateBoundedDecimal(rawQty, 6, BigDecimal.ZERO, null, false, "qty");
        LocalDate asOfDate = rawAsOfDate != null ? rawAsOfDate : LocalDate.now();
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();

        List<InventoryMasterDtos.PriceLookupResponse> matches = businessJdbcTemplate.query(
            """
            SELECT pl.id AS list_id, pl.price_level_name, pl.effective_from,
                   trim(pl.currency_code) AS ccy, pt.rate, pt.discount_pct
              FROM mc_price_list pl
              JOIN mc_price_tier pt
                ON pt.company_id = pl.company_id AND pt.price_list_id = pl.id
             WHERE pl.company_id = ?
               AND lower(pl.price_level_name) = lower(?)
               AND pl.active = true
               AND pl.effective_from <= ?
               AND pt.stock_item_id = ?
               AND pt.from_qty <= ?
               AND (pt.less_than_qty IS NULL OR ? < pt.less_than_qty)
             ORDER BY pl.effective_from DESC, pt.from_qty DESC
             LIMIT 1
            """,
            (rs, rowNum) -> new InventoryMasterDtos.PriceLookupResponse(
                rs.getObject("list_id", UUID.class),
                rs.getString("price_level_name"),
                rs.getObject("effective_from", LocalDate.class),
                stockItemId,
                rs.getString("ccy"),
                ExactMoneyValidator.formatExactScale(
                    rs.getBigDecimal("rate").setScale(scale, RoundingMode.HALF_UP),
                    scale
                ),
                ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("discount_pct"))
            ),
            companyId,
            priceLevelName.trim(),
            asOfDate,
            stockItemId,
            qty,
            qty
        );

        if (matches.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-PRICE-NOT-FOUND",
                "Matching Price Slab Not Found",
                "No price slab found for level '" + priceLevelName + "' and quantity " + qty.toPlainString() + "."
            );
        }
        return matches.getFirst();
    }

    // =========================================================================
    // 6. Bills of Materials (mc_bom, mc_bom_component)
    // =========================================================================

    public List<InventoryMasterDtos.BomDto> listBoms(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID stockItemId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        StringBuilder sql = new StringBuilder(
            """
            SELECT b.id, b.company_id, b.stock_item_id, si.name AS item_name,
                   b.bom_name, b.unit_of_manufacture, b.additional_cost_rules::text AS rules_json,
                   b.active, b.version_no, b.created_at, b.updated_at
              FROM mc_bom b
              JOIN mc_stock_item si ON si.company_id = b.company_id AND si.id = b.stock_item_id
             WHERE b.company_id = ? AND b.active = true
            """
        );
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        if (stockItemId != null) {
            sql.append(" AND b.stock_item_id = ?");
            params.add(stockItemId);
        }
        sql.append(" ORDER BY lower(si.name) ASC, lower(b.bom_name) ASC");

        List<BomRow> headers = businessJdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> new BomRow(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getObject("stock_item_id", UUID.class),
                rs.getString("item_name"),
                rs.getString("bom_name"),
                rs.getBigDecimal("unit_of_manufacture"),
                rs.getString("rules_json"),
                rs.getBoolean("active"),
                rs.getLong("version_no"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at")
            ),
            params.toArray()
        );
        if (headers.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<InventoryMasterDtos.BomComponentDto>> compsByBom = loadAllBomComponents(companyId);
        return headers.stream()
            .map(h -> toBomDto(h, compsByBom.getOrDefault(h.id(), List.of())))
            .toList();
    }

    public InventoryMasterDtos.BomDto getBom(MoneyCraftPrincipal principal, UUID companyId, UUID bomId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return requireBomById(companyId, bomId);
    }

    public InventoryMasterDtos.BomDto createBom(
        MoneyCraftPrincipal actor,
        UUID companyId,
        InventoryMasterDtos.UpsertBomRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        NormalizedBomSpec spec = normalizeBom(request);

        return businessTxTemplate.execute(tx -> {
            sequenceHelper.allocateForCreate(companyId, false);
            assertBomItemExists(companyId, spec.stockItemId());

            Long dup = businessJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM mc_bom
                 WHERE company_id = ? AND stock_item_id = ? AND lower(bom_name) = lower(?)
                """,
                Long.class,
                companyId,
                spec.stockItemId(),
                spec.bomName()
            );
            if (dup != null && dup > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-INV-DUPLICATE-BOM",
                    "Duplicate BOM Name",
                    "BOM '" + spec.bomName() + "' already exists for this stock item."
                );
            }

            UUID id = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_bom (
                    id, company_id, stock_item_id, bom_name, unit_of_manufacture,
                    additional_cost_rules, active, version_no
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 1)
                """,
                id,
                companyId,
                spec.stockItemId(),
                spec.bomName(),
                spec.unitOfManufacture(),
                spec.rulesJson(),
                spec.active()
            );
            insertBomComponents(companyId, id, spec.stockItemId(), spec.components());

            InventoryMasterDtos.BomDto created = requireBomById(companyId, id);
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "BOM_CREATE",
                "BOM",
                id.toString(),
                "SUCCESS",
                null,
                Map.of("bomName", created.bomName(), "stockItemId", created.stockItemId().toString())
            );
            return created;
        });
    }

    public InventoryMasterDtos.BomDto updateBom(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID bomId,
        String ifMatchHeader,
        InventoryMasterDtos.UpsertBomRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        NormalizedBomSpec spec = normalizeBom(request);

        return businessTxTemplate.execute(tx -> {
            sequenceHelper.allocateForAlterOrDelete(companyId, false);
            List<Long> verRows = businessJdbcTemplate.query(
                "SELECT version_no FROM mc_bom WHERE company_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("version_no"),
                companyId,
                bomId
            );
            if (verRows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-INV-BOM-NOT-FOUND",
                    "BOM Not Found",
                    "BOM " + bomId + " does not exist."
                );
            }
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, verRows.getFirst(), "bom");
            assertBomItemExists(companyId, spec.stockItemId());

            long nextVersion = verRows.getFirst() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_bom
                   SET stock_item_id = ?,
                       bom_name = ?,
                       unit_of_manufacture = ?,
                       additional_cost_rules = ?::jsonb,
                       active = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.stockItemId(),
                spec.bomName(),
                spec.unitOfManufacture(),
                spec.rulesJson(),
                spec.active(),
                nextVersion,
                companyId,
                bomId
            );
            businessJdbcTemplate.update(
                "DELETE FROM mc_bom_component WHERE company_id = ? AND bom_id = ?",
                companyId,
                bomId
            );
            insertBomComponents(companyId, bomId, spec.stockItemId(), spec.components());

            InventoryMasterDtos.BomDto updated = requireBomById(companyId, bomId);
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "BOM_UPDATE",
                "BOM",
                bomId.toString(),
                "SUCCESS",
                null,
                Map.of("bomName", updated.bomName(), "versionNo", updated.versionNo())
            );
            return updated;
        });
    }

    // =========================================================================
    // Validation, Normalization & Row Mappers
    // =========================================================================

    private void reconcileOpeningBatchAndLayers(
        UUID companyId,
        UUID stockItemId,
        InventoryMasterDtos.StockItemDto before,
        NormalizedStockItemSpec spec
    ) {
        if (before != null
            && before.openingGodownId() != null
            && before.openingBatchName() != null) {
            BigDecimal priorOpen = new BigDecimal(before.openingQty());
            if (priorOpen.compareTo(BigDecimal.ZERO) > 0) {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_stock_batch
                       SET opening_qty = GREATEST(0, opening_qty - ?),
                           closing_qty = closing_qty - ?,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE company_id = ?
                       AND stock_item_id = ?
                       AND godown_id = ?
                       AND batch_name = ?
                    """,
                    priorOpen,
                    priorOpen,
                    companyId,
                    stockItemId,
                    before.openingGodownId(),
                    before.openingBatchName()
                );
            }
        }

        if (spec.openingQty().compareTo(BigDecimal.ZERO) > 0) {
            UUID godownId = spec.openingGodownId() != null
                ? spec.openingGodownId()
                : valuationService.findMainLocationGodownId(companyId);
            requireGodownById(companyId, godownId);
            String batchName = spec.openingBatchName() == null || spec.openingBatchName().isBlank()
                ? "Primary Batch"
                : spec.openingBatchName().trim();
            if (spec.openingMfgDate() != null
                && spec.openingExpiryDate() != null
                && spec.openingExpiryDate().isBefore(spec.openingMfgDate())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-BATCH-DATE-ORDER",
                    "Invalid Batch Dates",
                    "openingExpiryDate must be on or after openingMfgDate."
                );
            }

            List<UUID> existingBatch = businessJdbcTemplate.query(
                """
                SELECT id FROM mc_stock_batch
                 WHERE company_id = ? AND stock_item_id = ? AND godown_id = ? AND batch_name = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId,
                stockItemId,
                godownId,
                batchName
            );
            if (existingBatch.isEmpty()) {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_stock_batch (
                        id, company_id, stock_item_id, godown_id, batch_name,
                        mfg_date, expiry_date, mrp_per_unit, opening_qty, closing_qty, version_no
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """,
                    UUID.randomUUID(),
                    companyId,
                    stockItemId,
                    godownId,
                    batchName,
                    spec.openingMfgDate(),
                    spec.openingExpiryDate(),
                    spec.mrpPerUnit(),
                    spec.openingQty(),
                    spec.openingQty()
                );
            } else {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_stock_batch
                       SET mfg_date = COALESCE(?, mfg_date),
                           expiry_date = COALESCE(?, expiry_date),
                           mrp_per_unit = COALESCE(?, mrp_per_unit),
                           opening_qty = opening_qty + ?,
                           closing_qty = closing_qty + ?,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    spec.openingMfgDate(),
                    spec.openingExpiryDate(),
                    spec.mrpPerUnit(),
                    spec.openingQty(),
                    spec.openingQty(),
                    companyId,
                    existingBatch.getFirst()
                );
            }
        }
        valuationService.syncCostLayersForItems(companyId, Set.of(stockItemId));
    }

    private NormalizedUomSpec normalizeUom(InventoryMasterDtos.UpsertUomRequest req) {
        if (req == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-PAYLOAD-REQUIRED",
                "UOM Payload Required",
                "Request body is required."
            );
        }
        String uomType = req.uomType() == null || req.uomType().isBlank()
            ? "SIMPLE"
            : req.uomType().trim().toUpperCase(Locale.ROOT);
        if (!VALID_UOM_TYPES.contains(uomType)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-UOM-TYPE-INVALID",
                "Invalid UOM Type",
                "uomType must be SIMPLE or COMPOUND."
            );
        }
        String symbol = requireNonBlank(req.symbol(), "symbol", 32);
        String formalName = trimNullable(req.formalName(), 80);
        String uqc = trimNullable(req.gstUqcCode(), 16);
        if (uqc != null) {
            uqc = uqc.toUpperCase(Locale.ROOT);
        }
        int decimals = req.decimalPlaces() == null ? 0 : req.decimalPlaces();
        if (decimals < 0 || decimals > 6) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-UOM-DECIMALS-INVALID",
                "Invalid UOM Decimal Places",
                "decimalPlaces must be between 0 and 6."
            );
        }

        UUID firstId = null;
        UUID secondId = null;
        BigDecimal conv = null;
        if ("COMPOUND".equals(uomType)) {
            if (req.firstUomId() == null || req.secondUomId() == null || req.firstUomId().equals(req.secondUomId())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-COMPOUND-UOM-INVALID",
                    "Invalid Compound UOM Structure",
                    "Compound UOM requires distinct firstUomId, secondUomId, and positive conversionFactor."
                );
            }
            firstId = req.firstUomId();
            secondId = req.secondUomId();
            conv = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.conversionFactor(),
                6,
                BigDecimal.ZERO,
                null,
                true,
                "conversionFactor"
            );
        }
        boolean active = req.active() == null || req.active();
        return new NormalizedUomSpec(uomType, symbol, formalName, uqc, decimals, firstId, conv, secondId, active);
    }

    private NormalizedGodownSpec normalizeGodown(InventoryMasterDtos.UpsertGodownRequest req) {
        if (req == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-PAYLOAD-REQUIRED",
                "Godown Payload Required",
                "Request body is required."
            );
        }
        String name = requireNonBlank(req.name(), "name", 160);
        String alias = trimNullable(req.aliasCode(), 64);
        String addr = req.address() == null ? "" : req.address().trim();
        boolean allowStorage = req.allowStorageOfMaterials() == null || req.allowStorageOfMaterials();
        boolean ourWithThird = req.ourStockWithThirdParty() != null && req.ourStockWithThirdParty();
        boolean thirdWithUs = req.thirdPartyStockWithUs() != null && req.thirdPartyStockWithUs();
        boolean active = req.active() == null || req.active();
        return new NormalizedGodownSpec(
            name,
            alias,
            req.parentGodownId(),
            addr,
            allowStorage,
            ourWithThird,
            thirdWithUs,
            active
        );
    }

    private NormalizedStockGroupSpec normalizeStockGroup(InventoryMasterDtos.UpsertStockGroupRequest req) {
        if (req == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-PAYLOAD-REQUIRED",
                "Stock Group Payload Required",
                "Request body is required."
            );
        }
        String name = requireNonBlank(req.name(), "name", 160);
        String alias = trimNullable(req.aliasCode(), 64);
        boolean shouldAdd = req.shouldQuantitiesOfItemsBeAdded() != null && req.shouldQuantitiesOfItemsBeAdded();
        String hsn = trimNullable(req.hsnSacCode(), 16);
        BigDecimal gst = parseOptionalBounded(req.gstRatePct(), 6, BigDecimal.ZERO, HUNDRED, "gstRatePct");
        BigDecimal cess = parseOptionalBounded(req.cessRatePct(), 6, BigDecimal.ZERO, FIVE_HUNDRED, "cessRatePct");
        boolean active = req.active() == null || req.active();
        return new NormalizedStockGroupSpec(name, alias, req.parentStockGroupId(), shouldAdd, hsn, gst, cess, active);
    }

    private NormalizedStockItemSpec normalizeStockItem(InventoryMasterDtos.UpsertStockItemRequest req, int scale) {
        if (req == null || req.baseUomId() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-ITEM-UOM-REQUIRED",
                "Base UOM Required",
                "Stock item requires name and baseUomId."
            );
        }
        String name = requireNonBlank(req.name(), "name", 160);
        String alias = trimNullable(req.aliasCode(), 64);
        String partNo = trimNullable(req.partNo(), 64);
        String desc = req.description() == null ? "" : req.description().trim();

        UUID altUom = req.alternateUomId();
        BigDecimal convNum = null;
        BigDecimal convDen = null;
        if (altUom != null) {
            if (altUom.equals(req.baseUomId())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-ALT-UOM-INVALID",
                    "Invalid Alternate UOM",
                    "alternateUomId must differ from baseUomId."
                );
            }
            convNum = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.conversionNumerator(), 6, BigDecimal.ZERO, null, true, "conversionNumerator"
            );
            convDen = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.conversionDenominator(), 6, BigDecimal.ZERO, null, true, "conversionDenominator"
            );
        }

        String costing = req.costingMethod() == null || req.costingMethod().isBlank()
            ? "AVG_COST"
            : req.costingMethod().trim().toUpperCase(Locale.ROOT);
        if (!ValuationService.VALID_COSTING_METHODS.contains(costing)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-INVALID-COSTING-METHOD",
                "Invalid Costing Method",
                "costingMethod must be one of " + ValuationService.VALID_COSTING_METHODS + "."
            );
        }

        String marketVal = req.marketValuationMethod() == null || req.marketValuationMethod().isBlank()
            ? "AVG_PRICE"
            : req.marketValuationMethod().trim().toUpperCase(Locale.ROOT);
        if (!VALID_MARKET_VAL_METHODS.contains(marketVal)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-INVALID-MARKET-METHOD",
                "Invalid Market Valuation Method",
                "marketValuationMethod must be one of " + VALID_MARKET_VAL_METHODS + "."
            );
        }

        BigDecimal stdCost = parseOptionalBounded(req.standardCost(), 6, BigDecimal.ZERO, null, "standardCost");
        BigDecimal stdSell = parseOptionalBounded(
            req.standardSellingPrice(), 6, BigDecimal.ZERO, null, "standardSellingPrice"
        );

        BigDecimal openQty = req.openingQty() == null || req.openingQty().isBlank()
            ? BigDecimal.ZERO
            : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.openingQty(), 6, BigDecimal.ZERO, null, false, "openingQty"
            );
        BigDecimal openRate = req.openingRate() == null || req.openingRate().isBlank()
            ? null
            : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.openingRate(), 6, BigDecimal.ZERO, null, false, "openingRate"
            );
        BigDecimal openVal = req.openingValue() == null || req.openingValue().isBlank()
            ? null
            : ExactMoneyValidator.parseAndValidateExactScale(req.openingValue(), scale, "openingValue");

        if (openQty.compareTo(BigDecimal.ZERO) == 0) {
            openRate = BigDecimal.ZERO;
            openVal = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        } else {
            if (openVal == null && openRate != null) {
                openVal = openQty.multiply(openRate, MathContext.DECIMAL128).setScale(scale, RoundingMode.HALF_UP);
            } else if (openVal != null && openRate == null) {
                openRate = openVal.divide(openQty, 6, RoundingMode.HALF_UP);
            } else if (openVal == null) {
                openRate = BigDecimal.ZERO;
                openVal = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
            }
        }

        String gstApp = req.gstApplicability() == null || req.gstApplicability().isBlank()
            ? "APPLICABLE"
            : req.gstApplicability().trim().toUpperCase(Locale.ROOT);
        if (!VALID_GST_APPLICABILITY.contains(gstApp)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-GST-APPLICABILITY-INVALID",
                "Invalid GST Applicability",
                "gstApplicability must be one of " + VALID_GST_APPLICABILITY + "."
            );
        }
        String supplyType = req.gstSupplyType() == null || req.gstSupplyType().isBlank()
            ? "GOODS"
            : req.gstSupplyType().trim().toUpperCase(Locale.ROOT);
        if (!VALID_GST_SUPPLY_TYPES.contains(supplyType)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-GST-SUPPLY-INVALID",
                "Invalid GST Supply Type",
                "gstSupplyType must be GOODS, SERVICES, or CAPITAL_GOODS."
            );
        }

        String hsn = trimNullable(req.hsnSacCode(), 16);
        BigDecimal gstRate = parseOptionalBounded(req.gstRatePct(), 6, BigDecimal.ZERO, HUNDRED, "gstRatePct");
        BigDecimal cessRate = parseOptionalBounded(req.cessRatePct(), 6, BigDecimal.ZERO, FIVE_HUNDRED, "cessRatePct");
        BigDecimal cessUnit = parseOptionalBounded(req.cessPerUnit(), 6, BigDecimal.ZERO, null, "cessPerUnit");
        boolean tobacco = req.isTobaccoMrpItem() != null && req.isTobaccoMrpItem();
        BigDecimal mrp = parseOptionalBounded(req.mrpPerUnit(), 6, BigDecimal.ZERO, null, "mrpPerUnit");

        boolean batches = req.maintainInBatches() != null && req.maintainInBatches();
        boolean mfg = req.trackDateOfMfg() != null && req.trackDateOfMfg();
        boolean exp = req.useExpiryDates() != null && req.useExpiryDates();
        boolean active = req.active() == null || req.active();

        return new NormalizedStockItemSpec(
            name,
            alias,
            partNo,
            desc,
            req.stockGroupId(),
            req.stockCategoryId(),
            req.baseUomId(),
            altUom,
            convNum,
            convDen,
            batches,
            mfg,
            exp,
            costing,
            marketVal,
            stdCost,
            stdSell,
            openQty,
            openRate,
            openVal,
            req.openingGodownId(),
            req.openingBatchName(),
            req.openingMfgDate(),
            req.openingExpiryDate(),
            gstApp,
            supplyType,
            hsn,
            gstRate,
            cessRate,
            cessUnit,
            tobacco,
            mrp,
            req.defaultSalesLedgerId(),
            req.defaultPurchaseLedgerId(),
            active
        );
    }

    private NormalizedPriceListSpec normalizePriceList(
        InventoryMasterDtos.UpsertPriceListRequest req,
        String defaultCurrency
    ) {
        if (req == null || req.effectiveFrom() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-PRICE-LIST-INVALID",
                "Invalid Price List",
                "priceLevelName and effectiveFrom are required."
            );
        }
        String level = requireNonBlank(req.priceLevelName(), "priceLevelName", 80);
        String ccy = req.currencyCode() == null || req.currencyCode().isBlank()
            ? defaultCurrency
            : req.currencyCode().trim().toUpperCase(Locale.ROOT);
        boolean active = req.active() == null || req.active();
        List<NormalizedPriceTier> tiers = new ArrayList<>();
        if (req.tiers() != null) {
            for (InventoryMasterDtos.PriceTierDto t : req.tiers()) {
                if (t == null || t.stockItemId() == null) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-INV-PRICE-TIER-INVALID",
                        "Invalid Price Tier",
                        "Each price tier requires stockItemId and rate."
                    );
                }
                BigDecimal fromQty = t.fromQty() == null || t.fromQty().isBlank()
                    ? BigDecimal.ZERO
                    : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                        t.fromQty(), 6, BigDecimal.ZERO, null, false, "fromQty"
                    );
                BigDecimal lessThan = parseOptionalBounded(t.lessThanQty(), 6, BigDecimal.ZERO, null, "lessThanQty");
                if (lessThan != null && lessThan.compareTo(fromQty) <= 0) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-INV-PRICE-SLAB-RANGE",
                        "Invalid Price Slab Quantity Range",
                        "lessThanQty must be strictly greater than fromQty."
                    );
                }
                BigDecimal rate = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    t.rate(), 6, BigDecimal.ZERO, null, false, "rate"
                );
                BigDecimal disc = t.discountPct() == null || t.discountPct().isBlank()
                    ? BigDecimal.ZERO
                    : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                        t.discountPct(), 6, BigDecimal.ZERO, HUNDRED, false, "discountPct"
                    );
                tiers.add(new NormalizedPriceTier(t.stockItemId(), fromQty, lessThan, rate, disc));
            }
        }
        return new NormalizedPriceListSpec(level, req.effectiveFrom(), ccy, tiers, active);
    }

    private NormalizedBomSpec normalizeBom(InventoryMasterDtos.UpsertBomRequest req) {
        if (req == null || req.stockItemId() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-BOM-INVALID",
                "Invalid BOM Request",
                "stockItemId, bomName, and components are required."
            );
        }
        String bomName = requireNonBlank(req.bomName(), "bomName", 120);
        BigDecimal unitOfMfg = req.unitOfManufacture() == null || req.unitOfManufacture().isBlank()
            ? BigDecimal.ONE
            : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.unitOfManufacture(), 6, BigDecimal.ZERO, null, true, "unitOfManufacture"
            );
        if (req.components() == null || req.components().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-BOM-COMPONENTS-REQUIRED",
                "BOM Components Required",
                "At least one BOM component is required."
            );
        }

        List<NormalizedBomComp> comps = new ArrayList<>();
        int autoLine = 1;
        for (InventoryMasterDtos.BomComponentDto c : req.components()) {
            UUID itemId = c.componentItemId() != null ? c.componentItemId() : c.stockItemId();
            if (itemId == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-BOM-COMPONENT-ITEM-REQUIRED",
                    "BOM Component Item Required",
                    "componentItemId is required on each BOM component."
                );
            }
            if (itemId.equals(req.stockItemId())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-BOM-SELF-CYCLE",
                    "BOM Self-Cycle Prohibited",
                    "Finished stock item cannot be a component of its own BOM."
                );
            }
            String rawType = c.componentType() == null || c.componentType().isBlank()
                ? "RAW_MATERIAL"
                : c.componentType().trim().toUpperCase(Locale.ROOT);
            if ("COMPONENT".equals(rawType)) {
                rawType = "RAW_MATERIAL";
            }
            if (!VALID_BOM_COMPONENT_TYPES.contains(rawType)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-BOM-COMPONENT-TYPE-INVALID",
                    "Invalid BOM Component Type",
                    "componentType must be RAW_MATERIAL, CO_PRODUCT, BY_PRODUCT, or SCRAP."
                );
            }
            BigDecimal qty = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                c.quantity(), 6, BigDecimal.ZERO, null, true, "component.quantity"
            );
            BigDecimal pct = c.costAllocationPct() == null || c.costAllocationPct().isBlank()
                ? BigDecimal.ZERO
                : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    c.costAllocationPct(), 6, BigDecimal.ZERO, HUNDRED, false, "component.costAllocationPct"
                );
            int lineNo = c.lineNo() != null ? c.lineNo() : autoLine;
            autoLine = Math.max(autoLine + 1, lineNo + 1);
            comps.add(new NormalizedBomComp(lineNo, itemId, rawType, c.godownId(), qty, pct));
        }

        String rulesJson = "[]";
        if (req.additionalCostRules() != null && !req.additionalCostRules().isEmpty()) {
            try {
                rulesJson = objectMapper.writeValueAsString(req.additionalCostRules());
            } catch (JacksonException ex) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-BOM-RULES-INVALID",
                    "Invalid BOM Additional Cost Rules",
                    "Unable to serialize additionalCostRules."
                );
            }
        }
        boolean active = req.active() == null || req.active();
        return new NormalizedBomSpec(req.stockItemId(), bomName, unitOfMfg, rulesJson, comps, active);
    }

    private void insertPriceTiers(UUID companyId, UUID priceListId, List<NormalizedPriceTier> tiers) {
        for (NormalizedPriceTier t : tiers) {
            assertBomItemExists(companyId, t.stockItemId());
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_price_tier (
                    id, company_id, price_list_id, stock_item_id, from_qty, less_than_qty, rate, discount_pct
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                companyId,
                priceListId,
                t.stockItemId(),
                t.fromQty(),
                t.lessThanQty(),
                t.rate(),
                t.discountPct()
            );
        }
    }

    private void insertBomComponents(
        UUID companyId,
        UUID bomId,
        UUID parentItemId,
        List<NormalizedBomComp> comps
    ) {
        for (NormalizedBomComp c : comps) {
            assertBomItemExists(companyId, c.componentItemId());
            if (c.godownId() != null) {
                requireGodownById(companyId, c.godownId());
            }
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_bom_component (
                    id, company_id, bom_id, line_no, component_item_id,
                    component_type, godown_id, quantity, cost_allocation_pct
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                companyId,
                bomId,
                (short) c.lineNo(),
                c.componentItemId(),
                c.componentType(),
                c.godownId(),
                c.quantity(),
                c.costAllocationPct()
            );
        }
    }

    private void validateStockItemReferences(UUID companyId, NormalizedStockItemSpec spec) {
        requireUomById(companyId, spec.baseUomId());
        if (spec.alternateUomId() != null) {
            requireUomById(companyId, spec.alternateUomId());
        }
        if (spec.stockGroupId() != null) {
            requireStockGroupById(companyId, spec.stockGroupId());
        }
        if (spec.stockCategoryId() != null) {
            requireStockCategoryById(companyId, spec.stockCategoryId());
        }
        if (spec.defaultSalesLedgerId() != null) {
            assertLedgerExists(companyId, spec.defaultSalesLedgerId());
        }
        if (spec.defaultPurchaseLedgerId() != null) {
            assertLedgerExists(companyId, spec.defaultPurchaseLedgerId());
        }
    }

    private void assertLedgerExists(UUID companyId, UUID ledgerId) {
        Long cnt = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mc_ledger WHERE company_id = ? AND id = ? AND active = true",
            Long.class,
            companyId,
            ledgerId
        );
        if (cnt == null || cnt == 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-LEDGER-NOT-FOUND",
                "Ledger Not Found",
                "Ledger " + ledgerId + " does not exist in company."
            );
        }
    }

    private void assertBomItemExists(UUID companyId, UUID stockItemId) {
        Long cnt = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mc_stock_item WHERE company_id = ? AND id = ? AND active = true",
            Long.class,
            companyId,
            stockItemId
        );
        if (cnt == null || cnt == 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-ITEM-NOT-FOUND",
                "Stock Item Not Found",
                "Stock item " + stockItemId + " does not exist in company."
            );
        }
    }

    private TreePosition computeCategoryTreePosition(UUID companyId, UUID selfId, UUID parentId) {
        if (parentId == null) {
            return new TreePosition("/" + selfId + "/", 0);
        }
        if (parentId.equals(selfId)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-STOCKCATEGORY-CYCLE",
                "Stock Category Cycle Detected",
                "Stock category cannot be its own parent."
            );
        }
        UUID cursor = parentId;
        int hops = 0;
        String directParentPath = null;
        int directParentDepth = 0;

        while (cursor != null) {
            hops++;
            if (hops > 32 || cursor.equals(selfId)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-STOCKCATEGORY-CYCLE",
                    "Stock Category Cycle Detected",
                    "Cycle or depth > 32 detected in stock category hierarchy."
                );
            }
            record CatNode(UUID parent, String path, int depth) { }
            List<CatNode> rows = businessJdbcTemplate.query(
                """
                SELECT parent_stock_category_id, tree_path, depth_level
                  FROM mc_stock_category
                 WHERE company_id = ? AND id = ?
                """,
                (rs, rowNum) -> new CatNode(
                    rs.getObject("parent_stock_category_id", UUID.class),
                    rs.getString("tree_path"),
                    rs.getShort("depth_level")
                ),
                companyId,
                cursor
            );
            if (rows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-INV-STOCKCATEGORY-NOT-FOUND",
                    "Parent Stock Category Not Found",
                    "Parent stock category " + parentId + " does not exist."
                );
            }
            CatNode node = rows.getFirst();
            if (hops == 1) {
                directParentPath = node.path();
                directParentDepth = node.depth();
            }
            cursor = node.parent();
        }
        return new TreePosition(directParentPath + selfId + "/", directParentDepth + 1);
    }

    private void assertUniqueUomSymbol(UUID companyId, String symbol, UUID excludeId) {
        Long cnt = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM mc_uom
             WHERE company_id = ? AND lower(symbol) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid)
            """,
            Long.class,
            companyId, symbol, excludeId, excludeId
        );
        if (cnt != null && cnt > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-INV-DUPLICATE-UOM",
                "Duplicate UOM Symbol",
                "UOM symbol '" + symbol + "' already exists."
            );
        }
    }

    private void assertUniqueGodownName(UUID companyId, String name, UUID excludeId) {
        Long cnt = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM mc_godown
             WHERE company_id = ? AND lower(name) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid)
            """,
            Long.class,
            companyId, name, excludeId, excludeId
        );
        if (cnt != null && cnt > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-INV-DUPLICATE-GODOWN",
                "Duplicate Godown Name",
                "Godown '" + name + "' already exists."
            );
        }
    }

    private void assertUniqueStockGroupName(UUID companyId, String name, UUID excludeId) {
        Long cnt = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM mc_stock_group
             WHERE company_id = ? AND lower(name) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid)
            """,
            Long.class,
            companyId, name, excludeId, excludeId
        );
        if (cnt != null && cnt > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-INV-DUPLICATE-STOCKGROUP",
                "Duplicate Stock Group Name",
                "Stock group '" + name + "' already exists."
            );
        }
    }

    private void assertUniqueStockCategoryName(UUID companyId, String name, UUID excludeId) {
        Long cnt = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM mc_stock_category
             WHERE company_id = ? AND lower(name) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid)
            """,
            Long.class,
            companyId, name, excludeId, excludeId
        );
        if (cnt != null && cnt > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-INV-DUPLICATE-STOCKCATEGORY",
                "Duplicate Stock Category Name",
                "Stock category '" + name + "' already exists."
            );
        }
    }

    private void assertUniqueStockItemName(UUID companyId, String name, UUID excludeId) {
        Long cnt = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM mc_stock_item
             WHERE company_id = ? AND lower(name) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid)
            """,
            Long.class,
            companyId, name, excludeId, excludeId
        );
        if (cnt != null && cnt > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-INV-DUPLICATE-ITEM",
                "Duplicate Stock Item Name",
                "Stock item '" + name + "' already exists."
            );
        }
    }

    private InventoryMasterDtos.UomDto requireUomById(UUID companyId, UUID uomId) {
        List<InventoryMasterDtos.UomDto> rows = businessJdbcTemplate.query(
            """
            SELECT u.id, u.company_id, u.uom_type, u.symbol, u.formal_name, u.gst_uqc_code,
                   u.decimal_places, u.first_uom_id, fu.symbol AS first_symbol,
                   u.conversion_factor, u.second_uom_id, su.symbol AS second_symbol,
                   u.active, u.master_seq_id, u.alter_id, u.version_no, u.created_at, u.updated_at
              FROM mc_uom u
              LEFT JOIN mc_uom fu ON fu.company_id = u.company_id AND fu.id = u.first_uom_id
              LEFT JOIN mc_uom su ON su.company_id = u.company_id AND su.id = u.second_uom_id
             WHERE u.company_id = ? AND u.id = ?
            """,
            (rs, rowNum) -> mapUomRow(rs),
            companyId,
            uomId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-UOM-NOT-FOUND",
                "UOM Not Found",
                "UOM " + uomId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private InventoryMasterDtos.UomDto lockUomById(UUID companyId, UUID uomId) {
        businessJdbcTemplate.query(
            "SELECT id FROM mc_uom WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            uomId
        );
        return requireUomById(companyId, uomId);
    }

    private InventoryMasterDtos.GodownDto requireGodownById(UUID companyId, UUID godownId) {
        List<InventoryMasterDtos.GodownDto> rows = businessJdbcTemplate.query(
            """
            SELECT g.id, g.company_id, g.name, g.alias_code, g.parent_godown_id, pg.name AS parent_name,
                   g.address, g.allow_storage_of_materials, g.our_stock_with_third_party,
                   g.third_party_stock_with_us, g.tree_path, g.depth_level, g.is_system_godown,
                   g.active, g.master_seq_id, g.alter_id, g.version_no, g.created_at, g.updated_at
              FROM mc_godown g
              LEFT JOIN mc_godown pg ON pg.company_id = g.company_id AND pg.id = g.parent_godown_id
             WHERE g.company_id = ? AND g.id = ?
            """,
            (rs, rowNum) -> mapGodownRow(rs),
            companyId,
            godownId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-GODOWN-NOT-FOUND",
                "Godown Not Found",
                "Godown " + godownId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private InventoryMasterDtos.GodownDto lockGodownById(UUID companyId, UUID godownId) {
        businessJdbcTemplate.query(
            "SELECT id FROM mc_godown WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            godownId
        );
        return requireGodownById(companyId, godownId);
    }

    private InventoryMasterDtos.StockGroupDto requireStockGroupById(UUID companyId, UUID stockGroupId) {
        List<InventoryMasterDtos.StockGroupDto> rows = businessJdbcTemplate.query(
            """
            SELECT sg.id, sg.company_id, sg.name, sg.alias_code,
                   sg.parent_stock_group_id, pg.name AS parent_name,
                   sg.should_quantities_of_items_be_added, sg.hsn_sac_code,
                   sg.gst_rate_pct, sg.cess_rate_pct, sg.tree_path, sg.depth_level,
                   sg.active, sg.master_seq_id, sg.alter_id, sg.version_no, sg.created_at, sg.updated_at
              FROM mc_stock_group sg
              LEFT JOIN mc_stock_group pg ON pg.company_id = sg.company_id AND pg.id = sg.parent_stock_group_id
             WHERE sg.company_id = ? AND sg.id = ?
            """,
            (rs, rowNum) -> mapStockGroupRow(rs),
            companyId,
            stockGroupId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-STOCKGROUP-NOT-FOUND",
                "Stock Group Not Found",
                "Stock group " + stockGroupId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private InventoryMasterDtos.StockGroupDto lockStockGroupById(UUID companyId, UUID stockGroupId) {
        businessJdbcTemplate.query(
            "SELECT id FROM mc_stock_group WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            stockGroupId
        );
        return requireStockGroupById(companyId, stockGroupId);
    }

    private InventoryMasterDtos.StockCategoryDto requireStockCategoryById(UUID companyId, UUID stockCategoryId) {
        List<InventoryMasterDtos.StockCategoryDto> rows = businessJdbcTemplate.query(
            """
            SELECT sc.id, sc.company_id, sc.name, sc.alias_code,
                   sc.parent_stock_category_id, pc.name AS parent_name,
                   sc.tree_path, sc.depth_level, sc.active,
                   sc.master_seq_id, sc.alter_id, sc.version_no, sc.created_at, sc.updated_at
              FROM mc_stock_category sc
              LEFT JOIN mc_stock_category pc ON pc.company_id = sc.company_id AND pc.id = sc.parent_stock_category_id
             WHERE sc.company_id = ? AND sg_id_match(sc.id, ?)
            """.replace("sg_id_match(sc.id, ?)", "sc.id = ?"),
            (rs, rowNum) -> mapStockCategoryRow(rs),
            companyId,
            stockCategoryId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-STOCKCATEGORY-NOT-FOUND",
                "Stock Category Not Found",
                "Stock category " + stockCategoryId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private InventoryMasterDtos.StockCategoryDto lockStockCategoryById(UUID companyId, UUID stockCategoryId) {
        businessJdbcTemplate.query(
            "SELECT id FROM mc_stock_category WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            stockCategoryId
        );
        return requireStockCategoryById(companyId, stockCategoryId);
    }

    private String stockItemSelectPrefix() {
        return """
            SELECT si.id, si.company_id, si.name, si.alias_code, si.part_no, si.description,
                   si.stock_group_id, sg.name AS stock_group_name,
                   si.stock_category_id, sc.name AS stock_category_name,
                   si.base_uom_id, bu.symbol AS base_uom_symbol, bu.decimal_places AS base_uom_decimals,
                   si.alternate_uom_id, au.symbol AS alternate_uom_symbol,
                   si.conversion_numerator, si.conversion_denominator,
                   si.maintain_in_batches, si.track_date_of_mfg, si.use_expiry_dates,
                   si.costing_method, si.market_valuation_method,
                   si.standard_cost, si.standard_selling_price,
                   si.opening_qty, si.opening_rate, si.opening_value,
                   ob.godown_id AS opening_godown_id, ob.batch_name AS opening_batch_name,
                   ob.mfg_date AS opening_mfg_date, ob.expiry_date AS opening_expiry_date,
                   si.gst_applicability, si.gst_supply_type, si.hsn_sac_code,
                   si.gst_rate_pct, si.cess_rate_pct, si.cess_per_unit,
                   si.is_tobacco_mrp_item, si.mrp_per_unit,
                   si.default_sales_ledger_id, si.default_purchase_ledger_id,
                   si.active, si.master_seq_id, si.alter_id, si.version_no, si.created_at, si.updated_at
              FROM mc_stock_item si
              JOIN mc_uom bu ON bu.company_id = si.company_id AND bu.id = si.base_uom_id
              LEFT JOIN mc_uom au ON au.company_id = si.company_id AND au.id = si.alternate_uom_id
              LEFT JOIN mc_stock_group sg ON sg.company_id = si.company_id AND sg.id = si.stock_group_id
              LEFT JOIN mc_stock_category sc ON sc.company_id = si.company_id AND sc.id = si.stock_category_id
              LEFT JOIN LATERAL (
                  SELECT b.godown_id, b.batch_name, b.mfg_date, b.expiry_date
                    FROM mc_stock_batch b
                   WHERE b.company_id = si.company_id
                     AND b.stock_item_id = si.id
                     AND b.opening_qty > 0
                   ORDER BY b.created_at ASC
                   LIMIT 1
              ) ob ON true
            """;
    }

    private InventoryMasterDtos.StockItemDto requireStockItemById(UUID companyId, UUID stockItemId, int scale) {
        List<InventoryMasterDtos.StockItemDto> rows = businessJdbcTemplate.query(
            stockItemSelectPrefix() + " WHERE si.company_id = ? AND si.id = ?",
            (rs, rowNum) -> mapStockItemRow(rs, scale),
            companyId,
            stockItemId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-ITEM-NOT-FOUND",
                "Stock Item Not Found",
                "Stock item " + stockItemId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private InventoryMasterDtos.StockItemDto lockStockItemById(UUID companyId, UUID stockItemId, int scale) {
        businessJdbcTemplate.query(
            "SELECT id FROM mc_stock_item WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            stockItemId
        );
        return requireStockItemById(companyId, stockItemId, scale);
    }

    private InventoryMasterDtos.PriceListDto requirePriceListById(UUID companyId, UUID priceListId, int scale) {
        List<PriceListHeaderRow> headers = businessJdbcTemplate.query(
            """
            SELECT id, company_id, price_level_name, effective_from, trim(currency_code) AS ccy,
                   active, version_no, created_at
              FROM mc_price_list
             WHERE company_id = ? AND id = ?
            """,
            (rs, rowNum) -> new PriceListHeaderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("price_level_name"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getString("ccy"),
                rs.getBoolean("active"),
                rs.getLong("version_no"),
                toInstant(rs, "created_at")
            ),
            companyId,
            priceListId
        );
        if (headers.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-PRICE-LIST-NOT-FOUND",
                "Price List Not Found",
                "Price list " + priceListId + " does not exist."
            );
        }
        PriceListHeaderRow h = headers.getFirst();
        List<InventoryMasterDtos.PriceTierDto> tiers = businessJdbcTemplate.query(
            """
            SELECT pt.id, pt.stock_item_id, si.name AS item_name,
                   pt.from_qty, pt.less_than_qty, pt.rate, pt.discount_pct
              FROM mc_price_tier pt
              JOIN mc_stock_item si ON si.company_id = pt.company_id AND si.id = pt.stock_item_id
             WHERE pt.company_id = ? AND pt.price_list_id = ?
             ORDER BY lower(si.name) ASC, pt.from_qty ASC
            """,
            (rs, rowNum) -> mapPriceTierRow(rs, scale),
            companyId,
            priceListId
        );
        return new InventoryMasterDtos.PriceListDto(
            h.id(),
            h.companyId(),
            h.priceLevelName(),
            h.effectiveFrom(),
            h.effectiveFrom(),
            h.currencyCode(),
            tiers,
            h.active(),
            h.versionNo(),
            h.createdAt()
        );
    }

    private Map<UUID, List<InventoryMasterDtos.PriceTierDto>> loadAllPriceTiers(UUID companyId, int scale) {
        Map<UUID, List<InventoryMasterDtos.PriceTierDto>> map = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT pt.id, pt.price_list_id, pt.stock_item_id, si.name AS item_name,
                   pt.from_qty, pt.less_than_qty, pt.rate, pt.discount_pct
              FROM mc_price_tier pt
              JOIN mc_stock_item si ON si.company_id = pt.company_id AND si.id = pt.stock_item_id
             WHERE pt.company_id = ?
             ORDER BY lower(si.name) ASC, pt.from_qty ASC
            """,
            rs -> {
                UUID listId = rs.getObject("price_list_id", UUID.class);
                map.computeIfAbsent(listId, ignored -> new ArrayList<>()).add(mapPriceTierRow(rs, scale));
            },
            companyId
        );
        return map;
    }

    private InventoryMasterDtos.BomDto requireBomById(UUID companyId, UUID bomId) {
        List<BomRow> rows = businessJdbcTemplate.query(
            """
            SELECT b.id, b.company_id, b.stock_item_id, si.name AS item_name,
                   b.bom_name, b.unit_of_manufacture, b.additional_cost_rules::text AS rules_json,
                   b.active, b.version_no, b.created_at, b.updated_at
              FROM mc_bom b
              JOIN mc_stock_item si ON si.company_id = b.company_id AND si.id = b.stock_item_id
             WHERE b.company_id = ? AND b.id = ?
            """,
            (rs, rowNum) -> new BomRow(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getObject("stock_item_id", UUID.class),
                rs.getString("item_name"),
                rs.getString("bom_name"),
                rs.getBigDecimal("unit_of_manufacture"),
                rs.getString("rules_json"),
                rs.getBoolean("active"),
                rs.getLong("version_no"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at")
            ),
            companyId,
            bomId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-INV-BOM-NOT-FOUND",
                "BOM Not Found",
                "BOM " + bomId + " does not exist."
            );
        }
        List<InventoryMasterDtos.BomComponentDto> comps = businessJdbcTemplate.query(
            """
            SELECT bc.line_no, bc.component_item_id, si.name AS item_name, si.base_uom_id,
                   bc.component_type, bc.godown_id, bc.quantity, bc.cost_allocation_pct
              FROM mc_bom_component bc
              JOIN mc_stock_item si ON si.company_id = bc.company_id AND si.id = bc.component_item_id
             WHERE bc.company_id = ? AND bc.bom_id = ?
             ORDER BY bc.line_no ASC
            """,
            (rs, rowNum) -> mapBomCompRow(rs),
            companyId,
            bomId
        );
        return toBomDto(rows.getFirst(), comps);
    }

    private Map<UUID, List<InventoryMasterDtos.BomComponentDto>> loadAllBomComponents(UUID companyId) {
        Map<UUID, List<InventoryMasterDtos.BomComponentDto>> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT bc.bom_id, bc.line_no, bc.component_item_id, si.name AS item_name, si.base_uom_id,
                   bc.component_type, bc.godown_id, bc.quantity, bc.cost_allocation_pct
              FROM mc_bom_component bc
              JOIN mc_stock_item si ON si.company_id = bc.company_id AND si.id = bc.component_item_id
             WHERE bc.company_id = ?
             ORDER BY bc.line_no ASC
            """,
            rs -> {
                UUID bId = rs.getObject("bom_id", UUID.class);
                map.computeIfAbsent(bId, ignored -> new ArrayList<>()).add(mapBomCompRow(rs));
            },
            companyId
        );
        return map;
    }

    private InventoryMasterDtos.BomDto toBomDto(BomRow h, List<InventoryMasterDtos.BomComponentDto> comps) {
        List<InventoryMasterDtos.BomAdditionalCostRuleDto> rules = List.of();
        if (h.rulesJson() != null && !h.rulesJson().isBlank()) {
            try {
                rules = objectMapper.readValue(
                    h.rulesJson(),
                    new TypeReference<List<InventoryMasterDtos.BomAdditionalCostRuleDto>>() { }
                );
            } catch (JacksonException ignored) {
                rules = List.of();
            }
        }
        return new InventoryMasterDtos.BomDto(
            h.id(),
            h.companyId(),
            h.stockItemId(),
            h.stockItemName(),
            h.bomName(),
            ExactMoneyValidator.formatPlainDecimal(h.unitOfManufacture()),
            rules,
            comps,
            h.active(),
            h.versionNo(),
            h.createdAt(),
            h.updatedAt()
        );
    }

    private static InventoryMasterDtos.UomDto mapUomRow(ResultSet rs) throws SQLException {
        return new InventoryMasterDtos.UomDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("uom_type"),
            rs.getString("symbol"),
            rs.getString("formal_name"),
            rs.getString("gst_uqc_code"),
            rs.getShort("decimal_places"),
            rs.getObject("first_uom_id", UUID.class),
            rs.getString("first_symbol"),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("conversion_factor")),
            rs.getObject("second_uom_id", UUID.class),
            rs.getString("second_symbol"),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at")
        );
    }

    private static InventoryMasterDtos.GodownDto mapGodownRow(ResultSet rs) throws SQLException {
        return new InventoryMasterDtos.GodownDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            rs.getString("alias_code"),
            rs.getObject("parent_godown_id", UUID.class),
            rs.getString("parent_name"),
            rs.getString("address"),
            rs.getBoolean("allow_storage_of_materials"),
            rs.getBoolean("our_stock_with_third_party"),
            rs.getBoolean("third_party_stock_with_us"),
            rs.getString("tree_path"),
            rs.getShort("depth_level"),
            rs.getBoolean("is_system_godown"),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at")
        );
    }

    private static InventoryMasterDtos.StockGroupDto mapStockGroupRow(ResultSet rs) throws SQLException {
        return new InventoryMasterDtos.StockGroupDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            rs.getString("alias_code"),
            rs.getObject("parent_stock_group_id", UUID.class),
            rs.getString("parent_name"),
            rs.getBoolean("should_quantities_of_items_be_added"),
            rs.getString("hsn_sac_code"),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("gst_rate_pct")),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("cess_rate_pct")),
            rs.getString("tree_path"),
            rs.getShort("depth_level"),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at")
        );
    }

    private static InventoryMasterDtos.StockCategoryDto mapStockCategoryRow(ResultSet rs) throws SQLException {
        return new InventoryMasterDtos.StockCategoryDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            rs.getString("alias_code"),
            rs.getObject("parent_stock_category_id", UUID.class),
            rs.getString("parent_name"),
            rs.getString("tree_path"),
            rs.getShort("depth_level"),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at")
        );
    }

    private static InventoryMasterDtos.StockItemDto mapStockItemRow(ResultSet rs, int scale) throws SQLException {
        BigDecimal stdCost = rs.getBigDecimal("standard_cost");
        BigDecimal stdSell = rs.getBigDecimal("standard_selling_price");
        BigDecimal mrp = rs.getBigDecimal("mrp_per_unit");
        BigDecimal openRate = rs.getBigDecimal("opening_rate");
        BigDecimal openVal = rs.getBigDecimal("opening_value");

        return new InventoryMasterDtos.StockItemDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            rs.getString("alias_code"),
            rs.getString("part_no"),
            rs.getString("description"),
            rs.getObject("stock_group_id", UUID.class),
            rs.getString("stock_group_name"),
            rs.getObject("stock_category_id", UUID.class),
            rs.getString("stock_category_name"),
            rs.getObject("base_uom_id", UUID.class),
            rs.getString("base_uom_symbol"),
            rs.getShort("base_uom_decimals"),
            rs.getObject("alternate_uom_id", UUID.class),
            rs.getString("alternate_uom_symbol"),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("conversion_numerator")),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("conversion_denominator")),
            rs.getBoolean("maintain_in_batches"),
            rs.getBoolean("track_date_of_mfg"),
            rs.getBoolean("use_expiry_dates"),
            rs.getString("costing_method"),
            rs.getString("market_valuation_method"),
            stdCost == null ? null : ExactMoneyValidator.formatPlainDecimal(stdCost),
            stdSell == null ? null : ExactMoneyValidator.formatPlainDecimal(stdSell),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("opening_qty")),
            ExactMoneyValidator.formatPlainDecimal(openRate),
            ExactMoneyValidator.formatExactScale(openVal.setScale(scale, RoundingMode.HALF_UP), scale),
            rs.getObject("opening_godown_id", UUID.class),
            rs.getString("opening_batch_name"),
            rs.getObject("opening_mfg_date", LocalDate.class),
            rs.getObject("opening_expiry_date", LocalDate.class),
            rs.getString("gst_applicability"),
            rs.getString("gst_supply_type"),
            rs.getString("hsn_sac_code"),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("gst_rate_pct")),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("cess_rate_pct")),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("cess_per_unit")),
            rs.getBoolean("is_tobacco_mrp_item"),
            mrp == null ? null : ExactMoneyValidator.formatPlainDecimal(mrp),
            rs.getObject("default_sales_ledger_id", UUID.class),
            rs.getObject("default_purchase_ledger_id", UUID.class),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at")
        );
    }

    private static InventoryMasterDtos.PriceTierDto mapPriceTierRow(ResultSet rs, int scale) throws SQLException {
        return new InventoryMasterDtos.PriceTierDto(
            rs.getObject("id", UUID.class),
            rs.getObject("stock_item_id", UUID.class),
            rs.getString("item_name"),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("from_qty")),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("less_than_qty")),
            ExactMoneyValidator.formatExactScale(
                rs.getBigDecimal("rate").setScale(scale, RoundingMode.HALF_UP),
                scale
            ),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("discount_pct"))
        );
    }

    private static InventoryMasterDtos.BomComponentDto mapBomCompRow(ResultSet rs) throws SQLException {
        UUID itemId = rs.getObject("component_item_id", UUID.class);
        String name = rs.getString("item_name");
        return new InventoryMasterDtos.BomComponentDto(
            (int) rs.getShort("line_no"),
            itemId,
            itemId,
            name,
            name,
            rs.getString("component_type"),
            rs.getObject("godown_id", UUID.class),
            rs.getObject("base_uom_id", UUID.class),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("quantity")),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("cost_allocation_pct"))
        );
    }

    private static BigDecimal parseOptionalBounded(
        String raw,
        int maxScale,
        BigDecimal min,
        BigDecimal max,
        String fieldName
    ) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ExactMoneyValidator.parseAndValidateBoundedDecimal(raw, maxScale, min, max, false, fieldName);
    }

    private static String requireNonBlank(String raw, String field, int maxLen) {
        if (raw == null || raw.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-FIELD-REQUIRED",
                "Required Field Missing",
                "Field '" + field + "' is required."
            );
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLen) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-FIELD-TOO-LONG",
                "Field Exceeds Maximum Length",
                "Field '" + field + "' exceeds maximum length " + maxLen + "."
            );
        }
        return trimmed;
    }

    private static String trimNullable(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLen) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-INV-FIELD-TOO-LONG",
                "Field Exceeds Maximum Length",
                "Value exceeds maximum length " + maxLen + "."
            );
        }
        return trimmed;
    }

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    private record NormalizedUomSpec(
        String uomType,
        String symbol,
        String formalName,
        String gstUqcCode,
        int decimalPlaces,
        UUID firstUomId,
        BigDecimal conversionFactor,
        UUID secondUomId,
        boolean active
    ) {
    }

    private record NormalizedGodownSpec(
        String name,
        String aliasCode,
        UUID parentGodownId,
        String address,
        boolean allowStorage,
        boolean ourStockWithThirdParty,
        boolean thirdPartyStockWithUs,
        boolean active
    ) {
    }

    private record NormalizedStockGroupSpec(
        String name,
        String aliasCode,
        UUID parentStockGroupId,
        boolean shouldAddQuantities,
        String hsnSacCode,
        BigDecimal gstRatePct,
        BigDecimal cessRatePct,
        boolean active
    ) {
    }

    private record NormalizedStockItemSpec(
        String name,
        String aliasCode,
        String partNo,
        String description,
        UUID stockGroupId,
        UUID stockCategoryId,
        UUID baseUomId,
        UUID alternateUomId,
        BigDecimal conversionNumerator,
        BigDecimal conversionDenominator,
        boolean maintainInBatches,
        boolean trackDateOfMfg,
        boolean useExpiryDates,
        String costingMethod,
        String marketValuationMethod,
        BigDecimal standardCost,
        BigDecimal standardSellingPrice,
        BigDecimal openingQty,
        BigDecimal openingRate,
        BigDecimal openingValue,
        UUID openingGodownId,
        String openingBatchName,
        LocalDate openingMfgDate,
        LocalDate openingExpiryDate,
        String gstApplicability,
        String gstSupplyType,
        String hsnSacCode,
        BigDecimal gstRatePct,
        BigDecimal cessRatePct,
        BigDecimal cessPerUnit,
        boolean isTobaccoMrpItem,
        BigDecimal mrpPerUnit,
        UUID defaultSalesLedgerId,
        UUID defaultPurchaseLedgerId,
        boolean active
    ) {
    }

    private record NormalizedPriceTier(
        UUID stockItemId,
        BigDecimal fromQty,
        BigDecimal lessThanQty,
        BigDecimal rate,
        BigDecimal discountPct
    ) {
    }

    private record NormalizedPriceListSpec(
        String priceLevelName,
        LocalDate effectiveFrom,
        String currencyCode,
        List<NormalizedPriceTier> tiers,
        boolean active
    ) {
    }

    private record PriceListHeaderRow(
        UUID id,
        UUID companyId,
        String priceLevelName,
        LocalDate effectiveFrom,
        String currencyCode,
        boolean active,
        long versionNo,
        Instant createdAt
    ) {
    }

    private record NormalizedBomComp(
        int lineNo,
        UUID componentItemId,
        String componentType,
        UUID godownId,
        BigDecimal quantity,
        BigDecimal costAllocationPct
    ) {
    }

    private record NormalizedBomSpec(
        UUID stockItemId,
        String bomName,
        BigDecimal unitOfManufacture,
        String rulesJson,
        List<NormalizedBomComp> components,
        boolean active
    ) {
    }

    private record BomRow(
        UUID id,
        UUID companyId,
        UUID stockItemId,
        String stockItemName,
        String bomName,
        BigDecimal unitOfManufacture,
        String rulesJson,
        boolean active,
        long versionNo,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    private record TreePosition(String treePath, int depthLevel) {
    }
}
