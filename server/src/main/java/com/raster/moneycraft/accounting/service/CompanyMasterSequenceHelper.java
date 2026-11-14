package com.raster.moneycraft.accounting.service;

import com.raster.moneycraft.foundation.company.CompanyService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acquires mc_company row lock first per MCJ1-TXN-01 and allocates monotonic
 * master_seq_id / alter_id sequences for MCA Rule 3(1) Edit Log attribution.
 */
@Component
public class CompanyMasterSequenceHelper {

    public static final Set<CompanyRole> MASTER_WRITE_ROLES = Set.of(
        CompanyRole.COMPANY_ADMIN,
        CompanyRole.SENIOR_ACCOUNTANT,
        CompanyRole.DATA_ENTRY_OPERATOR
    );

    public static final Set<CompanyRole> MASTER_ADMIN_ROLES = Set.of(
        CompanyRole.COMPANY_ADMIN,
        CompanyRole.SENIOR_ACCOUNTANT
    );

    private final JdbcTemplate businessJdbcTemplate;

    public CompanyMasterSequenceHelper(
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate
    ) {
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    public CompanyCurrencyMeta readCompanyCurrencyMeta(UUID companyId) {
        List<CompanyCurrencyMeta> rows = businessJdbcTemplate.query(
            """
            SELECT currency_scale, trim(base_currency_code) AS base_ccy, default_approval_policy
              FROM mc_company
             WHERE id = ?
            """,
            (rs, rowNum) -> new CompanyCurrencyMeta(
                rs.getShort("currency_scale"),
                rs.getString("base_ccy"),
                rs.getString("default_approval_policy")
            ),
            companyId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-COMPANY-NOT-FOUND",
                "Company Not Found",
                "Company " + companyId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public AllocatedMasterSeq allocateForCreate(UUID companyId, boolean bumpLedgerCatalogEpoch) {
        LockedCompanyRow locked = lockCompanyRow(companyId);
        long epochDelta = bumpLedgerCatalogEpoch ? 1L : 0L;
        businessJdbcTemplate.update(
            """
            UPDATE mc_company
               SET next_master_seq_id = next_master_seq_id + 1,
                   next_alter_id = next_alter_id + 1,
                   ledger_catalog_epoch = ledger_catalog_epoch + ?
             WHERE id = ?
            """,
            epochDelta,
            companyId
        );
        return new AllocatedMasterSeq(
            locked.currencyScale(),
            locked.baseCurrencyCode(),
            locked.defaultApprovalPolicy(),
            locked.nextMasterSeqId(),
            locked.nextAlterId()
        );
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public AllocatedMasterSeq allocateForAlterOrDelete(UUID companyId, boolean bumpLedgerCatalogEpoch) {
        LockedCompanyRow locked = lockCompanyRow(companyId);
        long epochDelta = bumpLedgerCatalogEpoch ? 1L : 0L;
        businessJdbcTemplate.update(
            """
            UPDATE mc_company
               SET next_alter_id = next_alter_id + 1,
                   ledger_catalog_epoch = ledger_catalog_epoch + ?
             WHERE id = ?
            """,
            epochDelta,
            companyId
        );
        return new AllocatedMasterSeq(
            locked.currencyScale(),
            locked.baseCurrencyCode(),
            locked.defaultApprovalPolicy(),
            locked.nextMasterSeqId(),
            locked.nextAlterId()
        );
    }

    public static void requireMasterWriteRole(
        RbacAuthorizationService rbacGuard,
        MoneyCraftPrincipal actor,
        UUID companyId
    ) {
        rbacGuard.requireCompanyRole(actor, companyId, MASTER_WRITE_ROLES);
    }

    public static long requireIfMatchVersion(String ifMatchHeader, Long bodyVersionNo) {
        return CompanyService.parseExpectedVersion(ifMatchHeader, bodyVersionNo);
    }

    public static void assertExpectedVersion(long expectedVersion, long actualVersion, String resourceName) {
        if (expectedVersion != actualVersion) {
            throw new MoneyCraftException(
                HttpStatus.PRECONDITION_FAILED,
                "MC-CONCURRENCY-STALE-VERSION",
                "Stale Resource Version (ETag Mismatch)",
                "Expected " + resourceName + " version v" + expectedVersion
                    + " but current version is v" + actualVersion + "."
            );
        }
    }

    public static int normalizePageLimit(Integer rawLimit, int defaultLimit, int maxLimit) {
        if (rawLimit == null) {
            return defaultLimit;
        }
        if (rawLimit < 1 || rawLimit > maxLimit) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PAGE-LIMIT-INVALID",
                "Invalid Page Limit",
                "Query parameter 'limit' must be between 1 and " + maxLimit + "."
            );
        }
        return rawLimit;
    }

    private LockedCompanyRow lockCompanyRow(UUID companyId) {
        List<LockedCompanyRow> rows = businessJdbcTemplate.query(
            """
            SELECT currency_scale, trim(base_currency_code) AS base_ccy,
                   default_approval_policy, next_master_seq_id, next_alter_id
              FROM mc_company
             WHERE id = ?
               FOR UPDATE
            """,
            (rs, rowNum) -> new LockedCompanyRow(
                rs.getShort("currency_scale"),
                rs.getString("base_ccy"),
                rs.getString("default_approval_policy"),
                rs.getLong("next_master_seq_id"),
                rs.getLong("next_alter_id")
            ),
            companyId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-COMPANY-NOT-FOUND",
                "Company Not Found",
                "Company " + companyId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private record LockedCompanyRow(
        int currencyScale,
        String baseCurrencyCode,
        String defaultApprovalPolicy,
        long nextMasterSeqId,
        long nextAlterId
    ) {
    }

    public record CompanyCurrencyMeta(
        int currencyScale,
        String baseCurrencyCode,
        String defaultApprovalPolicy
    ) {
    }

    public record AllocatedMasterSeq(
        int currencyScale,
        String baseCurrencyCode,
        String defaultApprovalPolicy,
        long masterSeqId,
        long alterId
    ) {
    }
}
