package com.raster.moneycraft.accounting.service;

import com.raster.moneycraft.accounting.dto.CostMasterDtos;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.EditLogSnapshotService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Parallel Cost Category and hierarchical Cost Centre master service.
 */
@Service
public class CostCentreService {

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final EditLogSnapshotService editLogSnapshotService;
    private final AuditChainService auditChainService;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public CostCentreService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        EditLogSnapshotService editLogSnapshotService,
        AuditChainService auditChainService,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.editLogSnapshotService = editLogSnapshotService;
        this.auditChainService = auditChainService;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public List<CostMasterDtos.CostCategoryResponse> listCostCategories(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessJdbcTemplate.query(
            categorySelectSql("cat.company_id = ?") + " ORDER BY cat.is_primary_category DESC, lower(cat.name) ASC",
            this::mapCategoryRow,
            companyId
        );
    }

    public CostMasterDtos.CostCategoryResponse createCostCategory(
        MoneyCraftPrincipal actor,
        UUID companyId,
        CostMasterDtos.CreateOrUpdateCostCategoryRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-COST-CATEGORY-NAME-REQUIRED",
                "Cost Category Name Required",
                "Cost category 'name' must be non-empty."
            );
        }
        String name = request.name().trim();
        boolean allocRev = request.allocateRevenueItems() == null || request.allocateRevenueItems();
        boolean allocNonRev = Boolean.TRUE.equals(request.allocateNonRevenueItems());
        boolean active = request.active() == null || request.active();

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForCreate(companyId, false);

            Integer dup = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_cost_category WHERE company_id = ? AND lower(name) = lower(?)",
                Integer.class,
                companyId,
                name
            );
            if (dup != null && dup > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-COST-CATEGORY-DUPLICATE",
                    "Duplicate Cost Category Name",
                    "Cost category '" + name + "' already exists in this company."
                );
            }

            UUID categoryId = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_cost_category (
                    id, company_id, name, allocate_revenue_items, allocate_non_revenue_items,
                    is_primary_category, active, master_seq_id, alter_id, version_no
                ) VALUES (?, ?, ?, ?, ?, false, ?, ?, ?, 1)
                """,
                categoryId,
                companyId,
                name,
                allocRev,
                allocNonRev,
                active,
                seq.masterSeqId(),
                seq.alterId()
            );

            CostMasterDtos.CostCategoryResponse created = requireCategoryById(companyId, categoryId);
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "COST_CATEGORY_CREATE",
                "COST_CATEGORY",
                categoryId.toString(),
                "SUCCESS",
                null,
                Map.of("name", created.name(), "versionNo", created.versionNo())
            );
            return created;
        });
    }

    public CostMasterDtos.CostCategoryResponse updateCostCategory(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID categoryId,
        String ifMatchHeader,
        CostMasterDtos.CreateOrUpdateCostCategoryRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-COST-CATEGORY-NAME-REQUIRED",
                "Cost Category Name Required",
                "Cost category 'name' must be non-empty."
            );
        }
        String name = request.name().trim();
        boolean allocRev = request.allocateRevenueItems() == null || request.allocateRevenueItems();
        boolean allocNonRev = Boolean.TRUE.equals(request.allocateNonRevenueItems());
        boolean active = request.active() == null || request.active();

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            CostMasterDtos.CostCategoryResponse before = lockCategoryById(companyId, categoryId);
            CompanyMasterSequenceHelper.assertExpectedVersion(
                expectedVersion,
                before.versionNo(),
                "cost category"
            );

            Integer dup = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_cost_category WHERE company_id = ? AND lower(name) = lower(?) AND id <> ?",
                Integer.class,
                companyId,
                name,
                categoryId
            );
            if (dup != null && dup > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-COST-CATEGORY-DUPLICATE",
                    "Duplicate Cost Category Name",
                    "Cost category '" + name + "' already exists in this company."
                );
            }

            long nextVersion = before.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_cost_category
                   SET name = ?,
                       allocate_revenue_items = ?,
                       allocate_non_revenue_items = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                name,
                allocRev,
                allocNonRev,
                before.isPrimaryCategory() || active,
                seq.alterId(),
                nextVersion,
                companyId,
                categoryId
            );

            CostMasterDtos.CostCategoryResponse after = requireCategoryById(companyId, categoryId);
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "COST_CATEGORY_ALTER",
                "COST_CATEGORY",
                categoryId.toString(),
                "SUCCESS",
                null,
                Map.of("name", after.name(), "versionNo", after.versionNo())
            );
            return after;
        });
    }

    public List<CostMasterDtos.CostCentreResponse> listCostCentres(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID categoryId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("cc.company_id = ?");
        params.add(companyId);
        if (categoryId != null) {
            where.append(" AND cc.cost_category_id = ?");
            params.add(categoryId);
        }
        return businessJdbcTemplate.query(
            centreSelectSql(where.toString()) + " ORDER BY cc.depth_level ASC, lower(cc.name) ASC, cc.id ASC",
            this::mapCentreRow,
            params.toArray()
        );
    }

    public CostMasterDtos.CostCentreResponse getCostCentre(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID centreId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return requireCentreById(companyId, centreId);
    }

    public CostMasterDtos.CostCentreResponse createCostCentre(
        MoneyCraftPrincipal actor,
        UUID companyId,
        CostMasterDtos.CreateOrUpdateCostCentreRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-COST-CENTRE-NAME-REQUIRED",
                "Cost Centre Name Required",
                "Cost centre 'name' must be non-empty."
            );
        }
        String name = request.name().trim();
        String alias = request.aliasCode() == null || request.aliasCode().isBlank()
            ? null
            : request.aliasCode().trim();
        boolean active = request.active() == null || request.active();

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForCreate(companyId, false);

            UUID parentId = request.resolvedParentId();
            UUID categoryId = resolveCategoryId(companyId, request.resolvedCategoryId(), parentId);
            assertUniqueCentreName(companyId, categoryId, name, null);

            UUID centreId = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_cost_centre (
                    id, company_id, cost_category_id, parent_cost_centre_id,
                    name, alias_code, active, master_seq_id, alter_id, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                centreId,
                companyId,
                categoryId,
                parentId,
                name,
                alias,
                active,
                seq.masterSeqId(),
                seq.alterId()
            );

            CostMasterDtos.CostCentreResponse created = requireCentreById(companyId, centreId);
            editLogSnapshotService.recordVersion(
                companyId,
                "COST_CENTRE",
                centreId,
                created.masterSeqId(),
                created.alterId(),
                created.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                null,
                null,
                null,
                created
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "COST_CENTRE_CREATE",
                "COST_CENTRE",
                centreId.toString(),
                "SUCCESS",
                null,
                Map.of("name", created.name(), "categoryId", created.categoryId().toString())
            );
            return created;
        });
    }

    public CostMasterDtos.CostCentreResponse updateCostCentre(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID centreId,
        String ifMatchHeader,
        CostMasterDtos.CreateOrUpdateCostCentreRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-COST-CENTRE-NAME-REQUIRED",
                "Cost Centre Name Required",
                "Cost centre 'name' must be non-empty."
            );
        }
        String name = request.name().trim();
        String alias = request.aliasCode() == null || request.aliasCode().isBlank()
            ? null
            : request.aliasCode().trim();
        boolean active = request.active() == null || request.active();

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            CostMasterDtos.CostCentreResponse before = lockCentreById(companyId, centreId);
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, before.versionNo(), "cost centre");

            UUID parentId = request.resolvedParentId();
            UUID requestedCatId = request.resolvedCategoryId();
            UUID effectiveCatId = requestedCatId == null ? before.categoryId() : requestedCatId;

            assertNoCentreCycle(companyId, centreId, parentId);
            assertUniqueCentreName(companyId, effectiveCatId, name, centreId);

            long nextVersion = before.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_cost_centre
                   SET cost_category_id = ?,
                       parent_cost_centre_id = ?,
                       name = ?,
                       alias_code = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                effectiveCatId,
                parentId,
                name,
                alias,
                active,
                seq.alterId(),
                nextVersion,
                companyId,
                centreId
            );

            CostMasterDtos.CostCentreResponse after = requireCentreById(companyId, centreId);
            editLogSnapshotService.recordVersion(
                companyId,
                "COST_CENTRE",
                centreId,
                after.masterSeqId(),
                after.alterId(),
                after.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                null,
                null,
                before,
                after
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "COST_CENTRE_ALTER",
                "COST_CENTRE",
                centreId.toString(),
                "SUCCESS",
                null,
                Map.of("name", after.name(), "versionNo", after.versionNo())
            );
            return after;
        });
    }

    public void deleteCostCentre(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID centreId
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        businessTxTemplate.executeWithoutResult(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            CostMasterDtos.CostCentreResponse before = lockCentreById(companyId, centreId);
            Integer childCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_cost_centre WHERE company_id = ? AND parent_cost_centre_id = ?",
                Integer.class,
                companyId,
                centreId
            );
            Integer allocCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_cost_allocation WHERE company_id = ? AND cost_centre_id = ?",
                Integer.class,
                companyId,
                centreId
            );
            if ((childCount != null && childCount > 0) || (allocCount != null && allocCount > 0)) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-MASTER-IN-USE",
                    "Cost Centre Referenced by Dependent Records",
                    "Cost centre '" + before.name() + "' cannot be deleted while child centres or allocations reference it."
                );
            }

            businessJdbcTemplate.update(
                "DELETE FROM mc_cost_centre WHERE company_id = ? AND id = ?",
                companyId,
                centreId
            );

            long deleteVersion = before.versionNo() + 1L;
            editLogSnapshotService.recordVersion(
                companyId,
                "COST_CENTRE",
                centreId,
                before.masterSeqId(),
                seq.alterId(),
                deleteVersion,
                "DELETE",
                actor.principalId(),
                actor.username(),
                null,
                null,
                before,
                before
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "COST_CENTRE_DELETE",
                "COST_CENTRE",
                centreId.toString(),
                "SUCCESS",
                null,
                Map.of("name", before.name(), "deletedVersionNo", deleteVersion)
            );
        });
    }

    private UUID resolveCategoryId(UUID companyId, UUID requestedCategoryId, UUID parentCentreId) {
        if (requestedCategoryId != null) {
            requireCategoryById(companyId, requestedCategoryId);
            return requestedCategoryId;
        }
        if (parentCentreId != null) {
            CostMasterDtos.CostCentreResponse parent = requireCentreById(companyId, parentCentreId);
            return parent.categoryId();
        }
        List<UUID> primaryIds = businessJdbcTemplate.query(
            """
            SELECT id FROM mc_cost_category
             WHERE company_id = ?
             ORDER BY is_primary_category DESC, master_seq_id ASC
             LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId
        );
        if (primaryIds.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-COST-CATEGORY-MISSING",
                "Cost Category Required",
                "No cost category exists in company " + companyId + "."
            );
        }
        return primaryIds.getFirst();
    }

    private void assertNoCentreCycle(UUID companyId, UUID centreId, UUID newParentId) {
        if (newParentId == null) {
            return;
        }
        if (centreId.equals(newParentId)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-COST-CENTRE-CYCLE",
                "Cost Centre Hierarchy Cycle",
                "Cost centre cannot be its own parent."
            );
        }
        UUID cursor = newParentId;
        int hops = 0;
        while (cursor != null) {
            hops++;
            if (hops > 32) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-COSTCENTRE-DEPTH",
                    "Cost Centre Maximum Depth Exceeded",
                    "Cost centre hierarchy cannot exceed depth 32."
                );
            }
            if (centreId.equals(cursor)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-COST-CENTRE-CYCLE",
                    "Cost Centre Hierarchy Cycle",
                    "Assigning parent " + newParentId + " introduces a cycle in mc_cost_centre."
                );
            }
            List<UUID> parents = businessJdbcTemplate.query(
                "SELECT parent_cost_centre_id FROM mc_cost_centre WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> rs.getObject("parent_cost_centre_id", UUID.class),
                companyId,
                cursor
            );
            cursor = parents.isEmpty() ? null : parents.getFirst();
        }
    }

    private void assertUniqueCentreName(UUID companyId, UUID categoryId, String name, UUID excludeId) {
        Integer dup = excludeId == null
            ? businessJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM mc_cost_centre
                 WHERE company_id = ? AND cost_category_id = ? AND lower(name) = lower(?)
                """,
                Integer.class,
                companyId,
                categoryId,
                name
            )
            : businessJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM mc_cost_centre
                 WHERE company_id = ? AND cost_category_id = ? AND lower(name) = lower(?) AND id <> ?
                """,
                Integer.class,
                companyId,
                categoryId,
                name,
                excludeId
            );
        if (dup != null && dup > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-ACC-COST-CENTRE-DUPLICATE",
                "Duplicate Cost Centre Name",
                "Cost centre '" + name + "' already exists in this cost category."
            );
        }
    }

    private CostMasterDtos.CostCategoryResponse requireCategoryById(UUID companyId, UUID categoryId) {
        List<CostMasterDtos.CostCategoryResponse> rows = businessJdbcTemplate.query(
            categorySelectSql("cat.company_id = ? AND cat.id = ?"),
            this::mapCategoryRow,
            companyId,
            categoryId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-COST-CATEGORY-NOT-FOUND",
                "Cost Category Not Found",
                "Cost category " + categoryId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private CostMasterDtos.CostCategoryResponse lockCategoryById(UUID companyId, UUID categoryId) {
        List<UUID> locked = businessJdbcTemplate.query(
            "SELECT id FROM mc_cost_category WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            categoryId
        );
        if (locked.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-COST-CATEGORY-NOT-FOUND",
                "Cost Category Not Found",
                "Cost category " + categoryId + " does not exist."
            );
        }
        return requireCategoryById(companyId, categoryId);
    }

    private CostMasterDtos.CostCentreResponse requireCentreById(UUID companyId, UUID centreId) {
        List<CostMasterDtos.CostCentreResponse> rows = businessJdbcTemplate.query(
            centreSelectSql("cc.company_id = ? AND cc.id = ?"),
            this::mapCentreRow,
            companyId,
            centreId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-COST-CENTRE-NOT-FOUND",
                "Cost Centre Not Found",
                "Cost centre " + centreId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private CostMasterDtos.CostCentreResponse lockCentreById(UUID companyId, UUID centreId) {
        List<UUID> locked = businessJdbcTemplate.query(
            "SELECT id FROM mc_cost_centre WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            centreId
        );
        if (locked.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-COST-CENTRE-NOT-FOUND",
                "Cost Centre Not Found",
                "Cost centre " + centreId + " does not exist."
            );
        }
        return requireCentreById(companyId, centreId);
    }

    private static String categorySelectSql(String whereClause) {
        return """
            SELECT cat.id, cat.company_id, cat.name,
                   cat.allocate_revenue_items, cat.allocate_non_revenue_items,
                   cat.is_primary_category, cat.active,
                   cat.master_seq_id, cat.alter_id, cat.version_no
              FROM mc_cost_category cat
             WHERE %s
            """.formatted(whereClause);
    }

    private static String centreSelectSql(String whereClause) {
        return """
            SELECT cc.id, cc.company_id, cc.cost_category_id, cat.name AS category_name,
                   cc.parent_cost_centre_id, pcc.name AS parent_centre_name,
                   cc.name, cc.alias_code, cc.depth_level, cc.active,
                   cc.master_seq_id, cc.alter_id, cc.version_no
              FROM mc_cost_centre cc
              JOIN mc_cost_category cat
                ON cat.company_id = cc.company_id AND cat.id = cc.cost_category_id
              LEFT JOIN mc_cost_centre pcc
                ON pcc.company_id = cc.company_id AND pcc.id = cc.parent_cost_centre_id
             WHERE %s
            """.formatted(whereClause);
    }

    private CostMasterDtos.CostCategoryResponse mapCategoryRow(ResultSet rs, int rowNum) throws SQLException {
        return new CostMasterDtos.CostCategoryResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            rs.getBoolean("allocate_revenue_items"),
            rs.getBoolean("allocate_non_revenue_items"),
            rs.getBoolean("is_primary_category"),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no")
        );
    }

    private CostMasterDtos.CostCentreResponse mapCentreRow(ResultSet rs, int rowNum) throws SQLException {
        return new CostMasterDtos.CostCentreResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getObject("cost_category_id", UUID.class),
            rs.getString("category_name"),
            rs.getObject("parent_cost_centre_id", UUID.class),
            rs.getString("parent_centre_name"),
            rs.getString("name"),
            rs.getString("alias_code"),
            rs.getShort("depth_level"),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no")
        );
    }
}
