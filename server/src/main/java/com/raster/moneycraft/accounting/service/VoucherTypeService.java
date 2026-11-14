package com.raster.moneycraft.accounting.service;

import com.raster.moneycraft.accounting.dto.VoucherTypeDtos;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.EditLogSnapshotService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * TallyPrime 7.2 24 predefined + custom Voucher Type master service.
 */
@Service
public class VoucherTypeService {

    private static final Set<String> VALID_CATEGORIES = Set.of(
        "CONTRA", "PAYMENT", "RECEIPT", "JOURNAL", "SALES", "PURCHASE",
        "CREDIT_NOTE", "DEBIT_NOTE", "MEMORANDUM", "REVERSING_JOURNAL",
        "SALES_ORDER", "PURCHASE_ORDER", "JOB_WORK_OUT_ORDER", "JOB_WORK_IN_ORDER",
        "DELIVERY_NOTE", "RECEIPT_NOTE", "REJECTIONS_IN", "REJECTIONS_OUT",
        "STOCK_JOURNAL", "PHYSICAL_STOCK", "MATERIAL_IN", "MATERIAL_OUT",
        "ATTENDANCE", "PAYROLL"
    );

    private static final Set<String> VALID_ENTRY_MODES = Set.of(
        "SINGLE_ENTRY", "DOUBLE_ENTRY", "ITEM_INVOICE", "ACCOUNTING_INVOICE", "AS_VOUCHER"
    );

    private static final Set<String> VALID_NUMBERING_METHODS = Set.of(
        "AUTOMATIC_RETAIN_NUMBERS", "AUTOMATIC_RENUMBER", "MULTI_USER_AUTO", "MANUAL", "NONE"
    );

    private static final Set<String> VALID_APPROVAL_POLICIES = Set.of(
        "SINGLE_STEP", "FOUR_EYES"
    );

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final EditLogSnapshotService editLogSnapshotService;
    private final AuditChainService auditChainService;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public VoucherTypeService(
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

    public List<VoucherTypeDtos.VoucherTypeResponse> listVoucherTypes(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessJdbcTemplate.query(
            voucherTypeSelectSql("vt.company_id = ?") + " ORDER BY vt.master_seq_id ASC, lower(vt.name) ASC",
            this::mapVoucherTypeRow,
            companyId
        );
    }

    public VoucherTypeDtos.VoucherTypeResponse getVoucherType(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID voucherTypeId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return requireVoucherTypeById(companyId, voucherTypeId);
    }

    public VoucherTypeDtos.VoucherTypeResponse createVoucherType(
        MoneyCraftPrincipal actor,
        UUID companyId,
        VoucherTypeDtos.CreateOrUpdateVoucherTypeRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForCreate(companyId, false);

            VoucherTypeDtos.VoucherTypeResponse parent = resolveParentVoucherType(companyId, request);
            NormalizedVoucherTypeSpec spec = validateAndNormalize(
                request,
                parent,
                seq.defaultApprovalPolicy()
            );
            assertUniqueVoucherTypeName(companyId, spec.name(), null);

            UUID voucherTypeId = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_voucher_type (
                    id, company_id, name, abbreviation, base_category, parent_voucher_type_id,
                    posts_accounting_entries, moves_inventory, default_entry_mode,
                    numbering_method, prevent_duplicate_numbers, allow_zero_valued_transactions,
                    make_optional_by_default, print_after_save, approval_policy, shortcut_key,
                    is_system_voucher_type, active, master_seq_id, alter_id, version_no
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?, ?,
                    false, ?, ?, ?, 1
                )
                """,
                voucherTypeId,
                companyId,
                spec.name(),
                spec.abbreviation(),
                spec.category(),
                parent == null ? null : parent.id(),
                spec.postsAccountingEntries(),
                spec.movesInventory(),
                spec.defaultEntryMode(),
                spec.numberingMethod(),
                spec.preventDuplicates(),
                spec.allowZeroValued(),
                spec.isOptionalByDefault(),
                spec.printAfterSave(),
                spec.approvalPolicy(),
                spec.shortcutKey(),
                spec.active(),
                seq.masterSeqId(),
                seq.alterId()
            );

            VoucherTypeDtos.VoucherTypeResponse created = requireVoucherTypeById(companyId, voucherTypeId);
            editLogSnapshotService.recordVersion(
                companyId,
                "VOUCHER_TYPE",
                voucherTypeId,
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
                "VOUCHER_TYPE_CREATE",
                "VOUCHER_TYPE",
                voucherTypeId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "name", created.name(),
                    "category", created.category(),
                    "versionNo", created.versionNo()
                )
            );
            return created;
        });
    }

    public VoucherTypeDtos.VoucherTypeResponse updateVoucherType(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherTypeId,
        String ifMatchHeader,
        VoucherTypeDtos.CreateOrUpdateVoucherTypeRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            VoucherTypeDtos.VoucherTypeResponse before = lockVoucherTypeById(companyId, voucherTypeId);
            CompanyMasterSequenceHelper.assertExpectedVersion(
                expectedVersion,
                before.versionNo(),
                "voucher type"
            );

            VoucherTypeDtos.VoucherTypeResponse parent = resolveParentVoucherType(companyId, request);
            NormalizedVoucherTypeSpec spec = validateAndNormalize(
                request,
                parent != null ? parent : before,
                seq.defaultApprovalPolicy()
            );

            if (before.isSystemType() && !before.name().equalsIgnoreCase(spec.name())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-SYSTEM-VCHTYPE-PROTECTED",
                    "Predefined Voucher Type Protected",
                    "Predefined voucher type '" + before.name() + "' cannot be renamed."
                );
            }
            assertUniqueVoucherTypeName(companyId, spec.name(), voucherTypeId);

            UUID parentId = before.isSystemType()
                ? null
                : (parent != null ? parent.id() : before.parentVoucherTypeId());
            long nextVersion = before.versionNo() + 1L;

            businessJdbcTemplate.update(
                """
                UPDATE mc_voucher_type
                   SET name = ?,
                       abbreviation = ?,
                       base_category = ?,
                       parent_voucher_type_id = ?,
                       posts_accounting_entries = ?,
                       moves_inventory = ?,
                       default_entry_mode = ?,
                       numbering_method = ?,
                       prevent_duplicate_numbers = ?,
                       allow_zero_valued_transactions = ?,
                       make_optional_by_default = ?,
                       print_after_save = ?,
                       approval_policy = ?,
                       shortcut_key = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.name(),
                spec.abbreviation(),
                before.isSystemType() ? before.category() : spec.category(),
                parentId,
                spec.postsAccountingEntries(),
                spec.movesInventory(),
                spec.defaultEntryMode(),
                spec.numberingMethod(),
                spec.preventDuplicates(),
                spec.allowZeroValued(),
                spec.isOptionalByDefault(),
                spec.printAfterSave(),
                spec.approvalPolicy(),
                spec.shortcutKey(),
                before.isSystemType() || spec.active(),
                seq.alterId(),
                nextVersion,
                companyId,
                voucherTypeId
            );

            VoucherTypeDtos.VoucherTypeResponse after = requireVoucherTypeById(companyId, voucherTypeId);
            editLogSnapshotService.recordVersion(
                companyId,
                "VOUCHER_TYPE",
                voucherTypeId,
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
                "VOUCHER_TYPE_ALTER",
                "VOUCHER_TYPE",
                voucherTypeId.toString(),
                "SUCCESS",
                null,
                Map.of("name", after.name(), "versionNo", after.versionNo())
            );
            return after;
        });
    }

    private VoucherTypeDtos.VoucherTypeResponse resolveParentVoucherType(
        UUID companyId,
        VoucherTypeDtos.CreateOrUpdateVoucherTypeRequest req
    ) {
        if (req == null) {
            return null;
        }
        if (req.parentVoucherTypeId() != null) {
            return requireVoucherTypeById(companyId, req.parentVoucherTypeId());
        }
        if (req.parentVoucherType() != null && !req.parentVoucherType().isBlank()) {
            String rawParent = req.parentVoucherType().trim();
            List<VoucherTypeDtos.VoucherTypeResponse> byName = businessJdbcTemplate.query(
                voucherTypeSelectSql("vt.company_id = ? AND lower(vt.name) = lower(?)"),
                this::mapVoucherTypeRow,
                companyId,
                rawParent
            );
            if (byName.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-VOUCHER-TYPE-PARENT-MISSING",
                    "Parent Voucher Type Not Found",
                    "Parent voucher type '" + rawParent + "' does not exist in company."
                );
            }
            return byName.getFirst();
        }
        return null;
    }

    private void assertUniqueVoucherTypeName(UUID companyId, String name, UUID excludeId) {
        Integer dup = excludeId == null
            ? businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_voucher_type WHERE company_id = ? AND lower(name) = lower(?)",
                Integer.class,
                companyId,
                name
            )
            : businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_voucher_type WHERE company_id = ? AND lower(name) = lower(?) AND id <> ?",
                Integer.class,
                companyId,
                name,
                excludeId
            );
        if (dup != null && dup > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-ACC-VOUCHER-TYPE-DUPLICATE",
                "Duplicate Voucher Type Name",
                "Voucher type '" + name + "' already exists in this company."
            );
        }
    }

    private VoucherTypeDtos.VoucherTypeResponse requireVoucherTypeById(UUID companyId, UUID voucherTypeId) {
        List<VoucherTypeDtos.VoucherTypeResponse> rows = businessJdbcTemplate.query(
            voucherTypeSelectSql("vt.company_id = ? AND vt.id = ?"),
            this::mapVoucherTypeRow,
            companyId,
            voucherTypeId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-VOUCHER-TYPE-NOT-FOUND",
                "Voucher Type Not Found",
                "Voucher type " + voucherTypeId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private VoucherTypeDtos.VoucherTypeResponse lockVoucherTypeById(UUID companyId, UUID voucherTypeId) {
        List<UUID> locked = businessJdbcTemplate.query(
            "SELECT id FROM mc_voucher_type WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            voucherTypeId
        );
        if (locked.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-VOUCHER-TYPE-NOT-FOUND",
                "Voucher Type Not Found",
                "Voucher type " + voucherTypeId + " does not exist."
            );
        }
        return requireVoucherTypeById(companyId, voucherTypeId);
    }

    private static String voucherTypeSelectSql(String whereClause) {
        return """
            SELECT vt.id, vt.company_id, vt.name, vt.abbreviation,
                   vt.parent_voucher_type_id, pvt.name AS parent_voucher_type_name,
                   vt.base_category, vt.posts_accounting_entries, vt.moves_inventory,
                   vt.default_entry_mode, vt.numbering_method, vt.approval_policy,
                   vt.prevent_duplicate_numbers, vt.allow_zero_valued_transactions,
                   vt.make_optional_by_default, vt.print_after_save,
                   vt.shortcut_key, vt.is_system_voucher_type, vt.active,
                   vt.master_seq_id, vt.alter_id, vt.version_no
              FROM mc_voucher_type vt
              LEFT JOIN mc_voucher_type pvt
                ON pvt.company_id = vt.company_id AND pvt.id = vt.parent_voucher_type_id
             WHERE %s
            """.formatted(whereClause);
    }

    private VoucherTypeDtos.VoucherTypeResponse mapVoucherTypeRow(ResultSet rs, int rowNum) throws SQLException {
        return new VoucherTypeDtos.VoucherTypeResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            rs.getString("abbreviation"),
            rs.getObject("parent_voucher_type_id", UUID.class),
            rs.getString("parent_voucher_type_name"),
            rs.getString("base_category"),
            rs.getBoolean("posts_accounting_entries"),
            rs.getBoolean("moves_inventory"),
            rs.getString("default_entry_mode"),
            rs.getString("numbering_method"),
            rs.getString("approval_policy"),
            rs.getBoolean("prevent_duplicate_numbers"),
            rs.getBoolean("allow_zero_valued_transactions"),
            rs.getBoolean("make_optional_by_default"),
            rs.getBoolean("print_after_save"),
            rs.getString("shortcut_key"),
            rs.getBoolean("is_system_voucher_type"),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no")
        );
    }

    private static NormalizedVoucherTypeSpec validateAndNormalize(
        VoucherTypeDtos.CreateOrUpdateVoucherTypeRequest req,
        VoucherTypeDtos.VoucherTypeResponse fallbackTemplate,
        String companyDefaultApprovalPolicy
    ) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-TYPE-NAME-REQUIRED",
                "Voucher Type Name Required",
                "Voucher type 'name' must be non-empty."
            );
        }
        String name = req.name().trim();
        String abbrev = req.abbreviation() == null || req.abbreviation().isBlank()
            ? (fallbackTemplate != null
                ? fallbackTemplate.abbreviation()
                : name.substring(0, Math.min(8, name.length())))
            : req.abbreviation().trim();

        String category = req.category() == null || req.category().isBlank()
            ? (fallbackTemplate == null ? null : fallbackTemplate.category())
            : req.category().trim().toUpperCase(Locale.ROOT);
        if (category == null || !VALID_CATEGORIES.contains(category)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-TYPE-CATEGORY-INVALID",
                "Invalid Voucher Base Category",
                "category must be one of the 24 canonical MoneyCraft voucher categories."
            );
        }

        String entryMode = req.defaultEntryMode() == null || req.defaultEntryMode().isBlank()
            ? (fallbackTemplate == null ? "AS_VOUCHER" : fallbackTemplate.defaultEntryMode())
            : req.defaultEntryMode().trim().toUpperCase(Locale.ROOT);
        if (!VALID_ENTRY_MODES.contains(entryMode)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-TYPE-MODE-INVALID",
                "Invalid Default Entry Mode",
                "defaultEntryMode must be one of " + VALID_ENTRY_MODES + "."
            );
        }

        String numbering = req.numberingMethod() == null || req.numberingMethod().isBlank()
            ? (fallbackTemplate == null ? "AUTOMATIC_RETAIN_NUMBERS" : fallbackTemplate.numberingMethod())
            : req.numberingMethod().trim().toUpperCase(Locale.ROOT);
        if (!VALID_NUMBERING_METHODS.contains(numbering)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-TYPE-NUMBERING-INVALID",
                "Invalid Numbering Method",
                "numberingMethod must be one of " + VALID_NUMBERING_METHODS + "."
            );
        }

        String approval = req.approvalPolicy() == null || req.approvalPolicy().isBlank()
            ? (fallbackTemplate == null ? companyDefaultApprovalPolicy : fallbackTemplate.approvalPolicy())
            : req.approvalPolicy().trim().toUpperCase(Locale.ROOT);
        if (!VALID_APPROVAL_POLICIES.contains(approval)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-TYPE-APPROVAL-INVALID",
                "Invalid Approval Policy",
                "approvalPolicy must be SINGLE_STEP or FOUR_EYES."
            );
        }

        boolean postsGl = req.postsAccountingEntries() != null
            ? req.postsAccountingEntries()
            : (fallbackTemplate == null || fallbackTemplate.postsAccountingEntries());
        boolean movesInv = req.movesInventory() != null
            ? req.movesInventory()
            : (fallbackTemplate != null && fallbackTemplate.movesInventory());
        boolean preventDup = req.preventDuplicates() != null
            ? req.preventDuplicates()
            : (fallbackTemplate == null || fallbackTemplate.preventDuplicates());
        boolean allowZero = req.allowZeroValued() != null
            ? req.allowZeroValued()
            : (fallbackTemplate != null && fallbackTemplate.allowZeroValued());
        boolean optDefault = req.isOptionalByDefault() != null
            ? req.isOptionalByDefault()
            : (fallbackTemplate != null && fallbackTemplate.isOptionalByDefault());
        boolean printSave = req.printAfterSave() != null
            ? req.printAfterSave()
            : (fallbackTemplate != null && fallbackTemplate.printAfterSave());

        String shortcut = req.shortcutKey() == null || req.shortcutKey().isBlank()
            ? null
            : req.shortcutKey().trim();

        return new NormalizedVoucherTypeSpec(
            name,
            abbrev,
            category,
            postsGl,
            movesInv,
            entryMode,
            numbering,
            approval,
            preventDup,
            allowZero,
            optDefault,
            printSave,
            shortcut,
            req.active() == null || req.active()
        );
    }

    private record NormalizedVoucherTypeSpec(
        String name,
        String abbreviation,
        String category,
        boolean postsAccountingEntries,
        boolean movesInventory,
        String defaultEntryMode,
        String numberingMethod,
        String approvalPolicy,
        boolean preventDuplicates,
        boolean allowZeroValued,
        boolean isOptionalByDefault,
        boolean printAfterSave,
        String shortcutKey,
        boolean active
    ) {
    }
}
