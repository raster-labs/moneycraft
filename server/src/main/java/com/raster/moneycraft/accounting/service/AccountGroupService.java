package com.raster.moneycraft.accounting.service;

import com.raster.moneycraft.accounting.dto.AccountGroupDtos;
import com.raster.moneycraft.accounting.dto.CursorPage;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.EditLogSnapshotService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.CursorCodec;
import com.raster.moneycraft.foundation.crypto.CursorPayload;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

/**
 * TallyPrime 7.2 28 predefined + custom hierarchical Account Groups and CoA tree service.
 */
@Service
public class AccountGroupService {

    private static final Set<String> VALID_NATURES = Set.of("ASSETS", "LIABILITIES", "INCOME", "EXPENSES");
    private static final Set<String> VALID_ALLOC_METHODS = Set.of(
        "NOT_APPLICABLE",
        "APPROPRIATE_BY_QTY",
        "APPROPRIATE_BY_VALUE"
    );
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final CursorCodec cursorCodec;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final EditLogSnapshotService editLogSnapshotService;
    private final AuditChainService auditChainService;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public AccountGroupService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        CursorCodec cursorCodec,
        JcsCanonicalizer jcsCanonicalizer,
        EditLogSnapshotService editLogSnapshotService,
        AuditChainService auditChainService,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.cursorCodec = cursorCodec;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.editLogSnapshotService = editLogSnapshotService;
        this.auditChainService = auditChainService;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public AccountGroupDtos.ChartOfAccountsTreeResponse getChartOfAccountsTree(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();

        List<AccountGroupDtos.AccountGroupResponse> groups = businessJdbcTemplate.query(
            groupSelectSql("g.company_id = ?") + " ORDER BY g.sort_order ASC, lower(g.name) ASC, g.id ASC",
            this::mapGroupRow,
            companyId
        );

        List<LedgerTreeLeaf> ledgers = businessJdbcTemplate.query(
            """
            SELECT l.id, l.name, l.alias_code, l.group_id,
                   COALESCE(g.nature, 'LIABILITIES') AS nature,
                   COALESCE(g.affects_gross_profit, false) AS affects_gp,
                   g.schedule_iii_line_code,
                   l.is_system_ledger, l.opening_balance, l.opening_side, l.version_no
              FROM mc_ledger l
              LEFT JOIN mc_account_group g
                ON g.company_id = l.company_id AND g.id = l.group_id
             WHERE l.company_id = ? AND l.active = true
             ORDER BY lower(l.name) ASC, l.id ASC
            """,
            (rs, rowNum) -> new LedgerTreeLeaf(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("alias_code"),
                rs.getObject("group_id", UUID.class),
                rs.getString("nature"),
                rs.getBoolean("affects_gp"),
                rs.getString("schedule_iii_line_code"),
                rs.getBoolean("is_system_ledger"),
                rs.getBigDecimal("opening_balance"),
                rs.getString("opening_side"),
                rs.getLong("version_no")
            ),
            companyId
        );

        Map<UUID, List<AccountGroupDtos.AccountGroupResponse>> childGroupsByParent = new LinkedHashMap<>();
        List<AccountGroupDtos.AccountGroupResponse> rootGroups = new ArrayList<>();
        for (AccountGroupDtos.AccountGroupResponse group : groups) {
            if (group.parentGroupId() == null) {
                rootGroups.add(group);
            } else {
                childGroupsByParent
                    .computeIfAbsent(group.parentGroupId(), ignored -> new ArrayList<>())
                    .add(group);
            }
        }

        Map<UUID, List<LedgerTreeLeaf>> ledgersByGroup = new LinkedHashMap<>();
        List<LedgerTreeLeaf> rootLedgers = new ArrayList<>();
        BigDecimal totalOpeningDr = BigDecimal.ZERO;
        BigDecimal totalOpeningCr = BigDecimal.ZERO;

        for (LedgerTreeLeaf leaf : ledgers) {
            if ("DR".equals(leaf.openingSide())) {
                totalOpeningDr = totalOpeningDr.add(leaf.openingBalance());
            } else {
                totalOpeningCr = totalOpeningCr.add(leaf.openingBalance());
            }
            if (leaf.groupId() == null) {
                rootLedgers.add(leaf);
            } else {
                ledgersByGroup
                    .computeIfAbsent(leaf.groupId(), ignored -> new ArrayList<>())
                    .add(leaf);
            }
        }

        List<AccountGroupDtos.CoaTreeNodeDto> rootNodes = new ArrayList<>();
        for (AccountGroupDtos.AccountGroupResponse rootGroup : rootGroups) {
            rootNodes.add(buildGroupSubtree(rootGroup, childGroupsByParent, ledgersByGroup, scale).node());
        }
        for (LedgerTreeLeaf ungrouped : rootLedgers) {
            rootNodes.add(toLedgerNode(ungrouped, scale));
        }

        BigDecimal diff = totalOpeningDr.subtract(totalOpeningCr);
        return new AccountGroupDtos.ChartOfAccountsTreeResponse(
            List.copyOf(rootNodes),
            groups.size(),
            ledgers.size(),
            ExactMoneyValidator.formatExactScale(totalOpeningDr, scale),
            ExactMoneyValidator.formatExactScale(totalOpeningCr, scale),
            ExactMoneyValidator.formatExactScale(diff, scale)
        );
    }

    public CursorPage<AccountGroupDtos.AccountGroupResponse> listAccountGroups(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String cursor,
        Integer limit,
        String queryText
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int pageSize = CompanyMasterSequenceHelper.normalizePageLimit(limit, 50, 200);
        String normalizedQ = queryText == null ? "" : queryText.trim();
        String filterHash = JcsCanonicalizer.toHex(
            jcsCanonicalizer.sha256(("q=" + normalizedQ.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8))
        );
        String endpointId = "GET:/account-groups";

        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("g.company_id = ?");
        params.add(companyId);

        if (!normalizedQ.isEmpty()) {
            where.append(" AND (g.name ILIKE ? OR COALESCE(g.alias_code, '') ILIKE ?)");
            String pattern = "%" + normalizedQ + "%";
            params.add(pattern);
            params.add(pattern);
        }

        if (cursor != null && !cursor.isBlank()) {
            CursorPayload decoded = cursorCodec.decodeAndVerify(
                cursor,
                principal.principalId(),
                companyId,
                endpointId,
                filterHash
            );
            where.append(" AND (lower(g.name), g.id) > (?, ?)");
            params.add(decoded.lastSortValue());
            params.add(decoded.lastEntityId());
        }

        params.add(pageSize + 1);
        String sql = groupSelectSql(where.toString()) + " ORDER BY lower(g.name) ASC, g.id ASC LIMIT ?";
        List<AccountGroupDtos.AccountGroupResponse> rows = businessJdbcTemplate.query(
            sql,
            this::mapGroupRow,
            params.toArray()
        );

        boolean hasMore = rows.size() > pageSize;
        List<AccountGroupDtos.AccountGroupResponse> pageItems = hasMore
            ? List.copyOf(rows.subList(0, pageSize))
            : List.copyOf(rows);

        String nextCursor = null;
        if (hasMore && !pageItems.isEmpty()) {
            AccountGroupDtos.AccountGroupResponse last = pageItems.getLast();
            nextCursor = cursorCodec.encode(
                principal.principalId(),
                companyId,
                endpointId,
                filterHash,
                last.name().toLowerCase(Locale.ROOT),
                last.id(),
                Instant.now().plus(Duration.ofHours(4))
            );
        }
        return new CursorPage<>(pageItems, nextCursor, hasMore);
    }

    public AccountGroupDtos.AccountGroupResponse getAccountGroup(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID groupId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return requireGroupById(companyId, groupId);
    }

    public AccountGroupDtos.AccountGroupResponse createAccountGroup(
        MoneyCraftPrincipal actor,
        UUID companyId,
        AccountGroupDtos.CreateOrUpdateAccountGroupRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        NormalizedGroupSpec spec = validateAndNormalize(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForCreate(companyId, false);

            assertUniqueGroupName(companyId, spec.name(), null);
            String resolvedNature = resolveNatureForInsertOrUpdate(companyId, spec.parentGroupId(), spec.nature());
            UUID groupId = UUID.randomUUID();

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_account_group (
                    id, company_id, name, alias_code, parent_group_id, nature,
                    affects_gross_profit, behaves_like_subledger, net_debit_credit_for_reporting,
                    used_for_calculation, method_to_allocate_purchase, schedule_iii_line_code,
                    default_gst_rate_pct, default_hsn_sac_code, is_system_group, sort_order,
                    master_seq_id, alter_id, version_no, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?, 1, ?)
                """,
                groupId,
                companyId,
                spec.name(),
                spec.aliasCode(),
                spec.parentGroupId(),
                resolvedNature,
                spec.affectsGrossProfit(),
                spec.behavesLikeSubledger(),
                spec.netDebitCreditForReporting(),
                spec.usedForCalculation(),
                spec.methodToAllocatePurchase(),
                spec.scheduleIiiLineCode(),
                spec.defaultGstRatePct(),
                spec.defaultHsnSacCode(),
                spec.sortOrder(),
                seq.masterSeqId(),
                seq.alterId(),
                actor.principalId()
            );

            AccountGroupDtos.AccountGroupResponse created = requireGroupById(companyId, groupId);
            editLogSnapshotService.recordVersion(
                companyId,
                "ACCOUNT_GROUP",
                groupId,
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
                "ACCOUNT_GROUP_CREATE",
                "ACCOUNT_GROUP",
                groupId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "name", created.name(),
                    "nature", created.nature(),
                    "versionNo", created.versionNo()
                )
            );
            return created;
        });
    }

    public AccountGroupDtos.AccountGroupResponse updateAccountGroup(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID groupId,
        String ifMatchHeader,
        AccountGroupDtos.CreateOrUpdateAccountGroupRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        NormalizedGroupSpec spec = validateAndNormalize(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            AccountGroupDtos.AccountGroupResponse before = lockGroupById(companyId, groupId);
            CompanyMasterSequenceHelper.assertExpectedVersion(
                expectedVersion,
                before.versionNo(),
                "account group"
            );

            if (before.isSystemGroup() && !before.name().equalsIgnoreCase(spec.name())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-SYSTEM-GROUP-PROTECTED",
                    "Predefined System Group Protected",
                    "Predefined system group '" + before.name() + "' cannot be renamed."
                );
            }

            assertUniqueGroupName(companyId, spec.name(), groupId);
            assertNoGroupCycle(companyId, groupId, spec.parentGroupId());
            String resolvedNature = resolveNatureForInsertOrUpdate(companyId, spec.parentGroupId(), spec.nature());
            long nextVersion = before.versionNo() + 1L;

            businessJdbcTemplate.update(
                """
                UPDATE mc_account_group
                   SET name = ?,
                       alias_code = ?,
                       parent_group_id = ?,
                       nature = ?,
                       affects_gross_profit = ?,
                       behaves_like_subledger = ?,
                       net_debit_credit_for_reporting = ?,
                       used_for_calculation = ?,
                       method_to_allocate_purchase = ?,
                       schedule_iii_line_code = ?,
                       default_gst_rate_pct = ?,
                       default_hsn_sac_code = ?,
                       sort_order = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.name(),
                spec.aliasCode(),
                spec.parentGroupId(),
                resolvedNature,
                spec.affectsGrossProfit(),
                spec.behavesLikeSubledger(),
                spec.netDebitCreditForReporting(),
                spec.usedForCalculation(),
                spec.methodToAllocatePurchase(),
                spec.scheduleIiiLineCode(),
                spec.defaultGstRatePct(),
                spec.defaultHsnSacCode(),
                spec.sortOrder(),
                seq.alterId(),
                nextVersion,
                companyId,
                groupId
            );

            AccountGroupDtos.AccountGroupResponse after = requireGroupById(companyId, groupId);
            editLogSnapshotService.recordVersion(
                companyId,
                "ACCOUNT_GROUP",
                groupId,
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
                "ACCOUNT_GROUP_ALTER",
                "ACCOUNT_GROUP",
                groupId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "name", after.name(),
                    "versionNo", after.versionNo()
                )
            );
            return after;
        });
    }

    public void deleteAccountGroup(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID groupId
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        businessTxTemplate.executeWithoutResult(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            AccountGroupDtos.AccountGroupResponse before = lockGroupById(companyId, groupId);
            if (before.isSystemGroup()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-SYSTEM-GROUP-PROTECTED",
                    "Predefined System Group Protected",
                    "Predefined system group '" + before.name() + "' cannot be deleted."
                );
            }

            Integer childCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_account_group WHERE company_id = ? AND parent_group_id = ?",
                Integer.class,
                companyId,
                groupId
            );
            Integer ledgerCount = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_ledger WHERE company_id = ? AND group_id = ?",
                Integer.class,
                companyId,
                groupId
            );
            if ((childCount != null && childCount > 0) || (ledgerCount != null && ledgerCount > 0)) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-MASTER-IN-USE",
                    "Account Group Has Child Masters",
                    "Account group '" + before.name() + "' cannot be deleted while sub-groups or ledgers exist under it."
                );
            }

            businessJdbcTemplate.update(
                "DELETE FROM mc_account_group WHERE company_id = ? AND id = ?",
                companyId,
                groupId
            );

            long deleteVersion = before.versionNo() + 1L;
            editLogSnapshotService.recordVersion(
                companyId,
                "ACCOUNT_GROUP",
                groupId,
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
                "ACCOUNT_GROUP_DELETE",
                "ACCOUNT_GROUP",
                groupId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "name", before.name(),
                    "deletedVersionNo", deleteVersion
                )
            );
        });
    }

    private SubtreeResult buildGroupSubtree(
        AccountGroupDtos.AccountGroupResponse group,
        Map<UUID, List<AccountGroupDtos.AccountGroupResponse>> childGroupsByParent,
        Map<UUID, List<LedgerTreeLeaf>> ledgersByGroup,
        int scale
    ) {
        List<AccountGroupDtos.CoaTreeNodeDto> children = new ArrayList<>();
        BigDecimal sumDr = BigDecimal.ZERO;
        BigDecimal sumCr = BigDecimal.ZERO;

        for (AccountGroupDtos.AccountGroupResponse childGroup : childGroupsByParent.getOrDefault(group.id(), List.of())) {
            SubtreeResult sub = buildGroupSubtree(childGroup, childGroupsByParent, ledgersByGroup, scale);
            children.add(sub.node());
            sumDr = sumDr.add(sub.openingDr());
            sumCr = sumCr.add(sub.openingCr());
        }

        for (LedgerTreeLeaf leaf : ledgersByGroup.getOrDefault(group.id(), List.of())) {
            children.add(toLedgerNode(leaf, scale));
            if ("DR".equals(leaf.openingSide())) {
                sumDr = sumDr.add(leaf.openingBalance());
            } else {
                sumCr = sumCr.add(leaf.openingBalance());
            }
        }

        BigDecimal net = sumDr.subtract(sumCr);
        String netSide = net.signum() >= 0 ? "DR" : "CR";
        AccountGroupDtos.CoaTreeNodeDto groupNode = new AccountGroupDtos.CoaTreeNodeDto(
            group.id(),
            "GROUP",
            group.name(),
            group.aliasCode(),
            group.parentGroupId(),
            group.nature(),
            group.affectsGrossProfit(),
            group.behavesLikeSubledger(),
            group.scheduleIiiLineCode(),
            group.isSystemGroup(),
            ExactMoneyValidator.formatExactScale(net.abs(), scale),
            netSide,
            ExactMoneyValidator.formatExactScale(sumDr, scale),
            ExactMoneyValidator.formatExactScale(sumCr, scale),
            group.versionNo(),
            List.copyOf(children)
        );
        return new SubtreeResult(groupNode, sumDr, sumCr);
    }

    private static AccountGroupDtos.CoaTreeNodeDto toLedgerNode(LedgerTreeLeaf leaf, int scale) {
        BigDecimal dr = "DR".equals(leaf.openingSide()) ? leaf.openingBalance() : BigDecimal.ZERO;
        BigDecimal cr = "CR".equals(leaf.openingSide()) ? leaf.openingBalance() : BigDecimal.ZERO;
        return new AccountGroupDtos.CoaTreeNodeDto(
            leaf.id(),
            "LEDGER",
            leaf.name(),
            leaf.aliasCode(),
            leaf.groupId(),
            leaf.nature(),
            leaf.affectsGrossProfit(),
            false,
            leaf.scheduleIiiLineCode(),
            leaf.isSystemLedger(),
            ExactMoneyValidator.formatExactScale(leaf.openingBalance(), scale),
            leaf.openingSide(),
            ExactMoneyValidator.formatExactScale(dr, scale),
            ExactMoneyValidator.formatExactScale(cr, scale),
            leaf.versionNo(),
            List.of()
        );
    }

    private String resolveNatureForInsertOrUpdate(UUID companyId, UUID parentGroupId, String requestedNature) {
        if (parentGroupId != null) {
            List<String> parentNature = businessJdbcTemplate.query(
                "SELECT nature FROM mc_account_group WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> rs.getString("nature"),
                companyId,
                parentGroupId
            );
            if (parentNature.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-GROUP-PARENT-MISSING",
                    "Parent Account Group Not Found",
                    "Parent account group " + parentGroupId + " does not exist in company."
                );
            }
            return parentNature.getFirst();
        }
        String norm = requestedNature == null || requestedNature.isBlank()
            ? "ASSETS"
            : requestedNature.trim().toUpperCase(Locale.ROOT);
        if (!VALID_NATURES.contains(norm)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-GROUP-NATURE-INVALID",
                "Invalid Account Group Nature",
                "Nature must be one of ASSETS, LIABILITIES, INCOME, or EXPENSES."
            );
        }
        return norm;
    }

    private void assertNoGroupCycle(UUID companyId, UUID groupId, UUID newParentId) {
        if (newParentId == null) {
            return;
        }
        if (groupId.equals(newParentId)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-GROUP-CYCLE",
                "Account Group Hierarchy Cycle",
                "Account group cannot be its own parent."
            );
        }
        UUID cursor = newParentId;
        int hops = 0;
        while (cursor != null) {
            hops++;
            if (hops > 32) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-GROUP-DEPTH",
                    "Account Group Maximum Depth Exceeded",
                    "Account group hierarchy cannot exceed depth 32."
                );
            }
            if (groupId.equals(cursor)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-GROUP-CYCLE",
                    "Account Group Hierarchy Cycle",
                    "Assigning parent " + newParentId + " introduces a cycle in mc_account_group."
                );
            }
            List<UUID> parents = businessJdbcTemplate.query(
                "SELECT parent_group_id FROM mc_account_group WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> rs.getObject("parent_group_id", UUID.class),
                companyId,
                cursor
            );
            cursor = parents.isEmpty() ? null : parents.getFirst();
        }
    }

    private void assertUniqueGroupName(UUID companyId, String name, UUID excludeId) {
        Integer dupCount = excludeId == null
            ? businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_account_group WHERE company_id = ? AND lower(name) = lower(?)",
                Integer.class,
                companyId,
                name
            )
            : businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_account_group WHERE company_id = ? AND lower(name) = lower(?) AND id <> ?",
                Integer.class,
                companyId,
                name,
                excludeId
            );
        if (dupCount != null && dupCount > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-ACC-GROUP-DUPLICATE",
                "Duplicate Account Group Name",
                "Account group '" + name + "' already exists in this company."
            );
        }
    }

    private AccountGroupDtos.AccountGroupResponse requireGroupById(UUID companyId, UUID groupId) {
        List<AccountGroupDtos.AccountGroupResponse> rows = businessJdbcTemplate.query(
            groupSelectSql("g.company_id = ? AND g.id = ?"),
            this::mapGroupRow,
            companyId,
            groupId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-GROUP-NOT-FOUND",
                "Account Group Not Found",
                "Account group " + groupId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private AccountGroupDtos.AccountGroupResponse lockGroupById(UUID companyId, UUID groupId) {
        List<UUID> locked = businessJdbcTemplate.query(
            "SELECT id FROM mc_account_group WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            groupId
        );
        if (locked.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-GROUP-NOT-FOUND",
                "Account Group Not Found",
                "Account group " + groupId + " does not exist."
            );
        }
        return requireGroupById(companyId, groupId);
    }

    private static String groupSelectSql(String whereClause) {
        return """
            SELECT g.id, g.company_id, g.name, g.alias_code, g.parent_group_id,
                   pg.name AS parent_group_name, g.nature, g.affects_gross_profit,
                   g.behaves_like_subledger, g.net_debit_credit_for_reporting,
                   g.used_for_calculation, g.method_to_allocate_purchase,
                   g.schedule_iii_line_code, g.default_gst_rate_pct, g.default_hsn_sac_code,
                   g.depth_level, g.is_system_group, g.sort_order,
                   g.master_seq_id, g.alter_id, g.version_no
              FROM mc_account_group g
              LEFT JOIN mc_account_group pg
                ON pg.company_id = g.company_id AND pg.id = g.parent_group_id
             WHERE %s
            """.formatted(whereClause);
    }

    private AccountGroupDtos.AccountGroupResponse mapGroupRow(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal defaultGst = rs.getBigDecimal("default_gst_rate_pct");
        return new AccountGroupDtos.AccountGroupResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            rs.getString("alias_code"),
            rs.getObject("parent_group_id", UUID.class),
            rs.getString("parent_group_name"),
            rs.getString("nature"),
            rs.getBoolean("affects_gross_profit"),
            rs.getBoolean("behaves_like_subledger"),
            rs.getBoolean("net_debit_credit_for_reporting"),
            rs.getBoolean("used_for_calculation"),
            rs.getString("method_to_allocate_purchase"),
            rs.getString("schedule_iii_line_code"),
            ExactMoneyValidator.formatPlainDecimal(defaultGst),
            rs.getString("default_hsn_sac_code"),
            rs.getShort("depth_level"),
            rs.getBoolean("is_system_group"),
            rs.getInt("sort_order"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no")
        );
    }

    private NormalizedGroupSpec validateAndNormalize(AccountGroupDtos.CreateOrUpdateAccountGroupRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-GROUP-NAME-REQUIRED",
                "Account Group Name Required",
                "Account group 'name' must be non-empty.",
                Map.of("name", "Account group name is required")
            );
        }
        String name = req.name().trim();
        String alias = trimToNull(req.aliasCode());
        String allocMethod = req.methodToAllocatePurchase() == null || req.methodToAllocatePurchase().isBlank()
            ? "NOT_APPLICABLE"
            : req.methodToAllocatePurchase().trim().toUpperCase(Locale.ROOT);
        if (!VALID_ALLOC_METHODS.contains(allocMethod)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-GROUP-ALLOC-INVALID",
                "Invalid Purchase Allocation Method",
                "methodToAllocatePurchase must be NOT_APPLICABLE, APPROPRIATE_BY_QTY, or APPROPRIATE_BY_VALUE."
            );
        }

        BigDecimal gstRate = null;
        if (req.defaultGstRatePct() != null && !req.defaultGstRatePct().isBlank()) {
            gstRate = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.defaultGstRatePct(),
                6,
                BigDecimal.ZERO,
                HUNDRED,
                false,
                "defaultGstRatePct"
            );
        }

        int sortOrder = req.sortOrder() == null ? 100 : req.sortOrder();
        return new NormalizedGroupSpec(
            name,
            alias,
            req.parentGroupId(),
            req.nature(),
            Boolean.TRUE.equals(req.affectsGrossProfit()),
            Boolean.TRUE.equals(req.behavesLikeSubledger()),
            Boolean.TRUE.equals(req.netDebitCreditForReporting()),
            Boolean.TRUE.equals(req.usedForCalculation()),
            allocMethod,
            trimToNull(req.scheduleIiiLineCode()),
            gstRate,
            trimToNull(req.defaultHsnSacCode()),
            sortOrder
        );
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record LedgerTreeLeaf(
        UUID id,
        String name,
        String aliasCode,
        UUID groupId,
        String nature,
        boolean affectsGrossProfit,
        String scheduleIiiLineCode,
        boolean isSystemLedger,
        BigDecimal openingBalance,
        String openingSide,
        long versionNo
    ) {
    }

    private record SubtreeResult(
        AccountGroupDtos.CoaTreeNodeDto node,
        BigDecimal openingDr,
        BigDecimal openingCr
    ) {
    }

    private record NormalizedGroupSpec(
        String name,
        String aliasCode,
        UUID parentGroupId,
        String nature,
        boolean affectsGrossProfit,
        boolean behavesLikeSubledger,
        boolean netDebitCreditForReporting,
        boolean usedForCalculation,
        String methodToAllocatePurchase,
        String scheduleIiiLineCode,
        BigDecimal defaultGstRatePct,
        String defaultHsnSacCode,
        int sortOrder
    ) {
    }
}
