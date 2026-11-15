package com.raster.moneycraft.accounting.service;

import com.raster.moneycraft.accounting.dto.CursorPage;
import com.raster.moneycraft.accounting.dto.LedgerDtos;
import com.raster.moneycraft.accounting.spi.VoucherStatutoryBridge;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.EditLogSnapshotService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.CursorCodec;
import com.raster.moneycraft.foundation.crypto.CursorPayload;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Accounting Ledger master, Tink SIV/GCM PII protection, Mod-36 GSTIN validation,
 * HS256 keyset pagination, and sub-15ms indexed typeahead service.
 */
@Service
public class LedgerService {

    private static final Set<String> VALID_SIDES = Set.of("DR", "CR");
    private static final Set<String> VALID_TAX_TYPES = Set.of("NONE", "GST", "TDS", "TCS", "OTHERS");
    private static final Set<String> VALID_DUTY_HEADS = Set.of(
        "CGST", "SGST_UTGST", "IGST", "CESS", "TDS_SEC", "TCS_SEC", "OTHERS"
    );
    private static final Set<String> VALID_GST_APPLICABILITY = Set.of(
        "APPLICABLE", "NOT_APPLICABLE", "UNDEFINED"
    );
    private static final Set<String> VALID_GST_SUPPLY_TYPES = Set.of(
        "GOODS", "SERVICES", "CAPITAL_GOODS"
    );
    private static final Set<String> VALID_PARTY_REG_TYPES = Set.of(
        "UNKNOWN", "REGULAR", "COMPOSITION", "UNREGISTERED",
        "CONSUMER", "OVERSEAS", "SEZ", "GOVERNMENT_ENTITY"
    );
    private static final Set<String> VALID_TYPEAHEAD_FILTERS = Set.of(
        "ALL", "CASH_BANK", "PARTY", "SALES", "PURCHASE",
        "DUTIES_TAXES", "NON_CASH_BANK", "EXPENSE_INCOME"
    );
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final TinkCryptoService tinkCryptoService;
    private final CursorCodec cursorCodec;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final EditLogSnapshotService editLogSnapshotService;
    private final AuditChainService auditChainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;
    private final ObjectProvider<VoucherStatutoryBridge> statutoryBridgeProvider;

    public LedgerService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        TinkCryptoService tinkCryptoService,
        CursorCodec cursorCodec,
        JcsCanonicalizer jcsCanonicalizer,
        EditLogSnapshotService editLogSnapshotService,
        AuditChainService auditChainService,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager,
        ObjectProvider<VoucherStatutoryBridge> statutoryBridgeProvider
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.tinkCryptoService = tinkCryptoService;
        this.cursorCodec = cursorCodec;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.editLogSnapshotService = editLogSnapshotService;
        this.auditChainService = auditChainService;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
        this.statutoryBridgeProvider = statutoryBridgeProvider;
    }

    public CursorPage<LedgerDtos.LedgerResponse> listLedgers(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String cursor,
        Integer limit,
        String queryText,
        UUID groupId,
        String taxType
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        int pageSize = CompanyMasterSequenceHelper.normalizePageLimit(limit, 50, 200);

        String normQ = queryText == null ? "" : queryText.trim();
        String normTax = taxType == null || taxType.isBlank() ? "" : taxType.trim().toUpperCase(Locale.ROOT);
        if (!normTax.isEmpty() && !VALID_TAX_TYPES.contains(normTax)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-ACC-TAX-TYPE-INVALID",
                "Invalid Tax Type Filter",
                "taxType filter must be NONE, GST, TDS, TCS, or OTHERS."
            );
        }

        String filterKey = "q=" + normQ.toLowerCase(Locale.ROOT)
            + "|groupId=" + (groupId == null ? "" : groupId)
            + "|taxType=" + normTax;
        String filterHash = JcsCanonicalizer.toHex(
            jcsCanonicalizer.sha256(filterKey.getBytes(StandardCharsets.UTF_8))
        );
        String endpointId = "GET:/ledgers";

        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("l.company_id = ?");
        params.add(companyId);

        if (!normQ.isEmpty()) {
            where.append(" AND (l.name ILIKE ? OR COALESCE(l.alias_code, '') ILIKE ? OR COALESCE(l.gstin, '') ILIKE ?)");
            String pattern = "%" + normQ + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        if (groupId != null) {
            where.append(" AND l.group_id = ?");
            params.add(groupId);
        }
        if (!normTax.isEmpty()) {
            where.append(" AND l.tax_type = ?");
            params.add(normTax);
        }

        if (cursor != null && !cursor.isBlank()) {
            CursorPayload decoded = cursorCodec.decodeAndVerify(
                cursor,
                principal.principalId(),
                companyId,
                endpointId,
                filterHash
            );
            where.append(" AND (lower(l.name), l.id) > (?, ?)");
            params.add(decoded.lastSortValue());
            params.add(decoded.lastEntityId());
        }

        params.add(pageSize + 1);
        String sql = ledgerSelectSql(where.toString()) + " ORDER BY lower(l.name) ASC, l.id ASC LIMIT ?";
        List<LedgerDtos.LedgerResponse> rows = businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> mapLedgerRow(rs, scale),
            params.toArray()
        );

        boolean hasMore = rows.size() > pageSize;
        List<LedgerDtos.LedgerResponse> pageItems = hasMore
            ? List.copyOf(rows.subList(0, pageSize))
            : List.copyOf(rows);

        String nextCursor = null;
        if (hasMore && !pageItems.isEmpty()) {
            LedgerDtos.LedgerResponse last = pageItems.getLast();
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

    public List<LedgerDtos.LedgerTypeaheadResponse> typeaheadLedgers(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String queryText,
        String rawFilter,
        Integer rawLimit
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int maxRows = CompanyMasterSequenceHelper.normalizePageLimit(rawLimit, 50, 100);
        String filter = rawFilter == null || rawFilter.isBlank()
            ? "ALL"
            : rawFilter.trim().toUpperCase(Locale.ROOT);
        if (!VALID_TYPEAHEAD_FILTERS.contains(filter)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-ACC-FILTER-INVALID",
                "Invalid Ledger Typeahead Filter",
                "Filter must be one of " + VALID_TYPEAHEAD_FILTERS + "."
            );
        }

        String normQ = queryText == null ? "" : queryText.trim();
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(companyId);

        StringBuilder sql = new StringBuilder(
            """
            SELECT l.id, l.name, l.alias_code, l.group_id, g.name AS group_name,
                   COALESCE(g.nature, 'LIABILITIES') AS nature,
                   l.maintain_balances_bill_by_bill, l.cost_centres_applicable,
                   l.tax_type, l.duty_head, l.gstin, l.state_name,
                   l.party_registration_type, l.appropriate_for,
                   l.tds_applicable, l.tds_nature_code,
                   l.opening_balance, l.opening_side,
                   c.currency_scale,
                   COALESCE(pb.dr_sum, 0::numeric) AS dr_movements,
                   COALESCE(pb.cr_sum, 0::numeric) AS cr_movements
              FROM mc_ledger l
              JOIN mc_company c
                ON c.id = l.company_id
              LEFT JOIN mc_account_group g
                ON g.company_id = l.company_id AND g.id = l.group_id
              LEFT JOIN (
                  SELECT ledger_id,
                         SUM(dr_total) AS dr_sum,
                         SUM(cr_total) AS cr_sum
                    FROM mc_ledger_period_bal
                   WHERE company_id = ?
                   GROUP BY ledger_id
              ) pb ON pb.ledger_id = l.id
             WHERE l.company_id = ?
               AND l.active = true
            """
        );

        appendTypeaheadCategoryFilter(sql, filter);

        if (!normQ.isEmpty()) {
            sql.append(
                " AND (l.name ILIKE ? OR COALESCE(l.alias_code, '') ILIKE ? OR COALESCE(l.gstin, '') ILIKE ?)"
            );
            String pattern = "%" + normQ + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            sql.append(" ORDER BY (lower(l.name) LIKE ?) DESC, lower(l.name) ASC, l.id ASC LIMIT ?");
            params.add(normQ.toLowerCase(Locale.ROOT) + "%");
            params.add(maxRows);
        } else {
            sql.append(" ORDER BY lower(l.name) ASC, l.id ASC LIMIT ?");
            params.add(maxRows);
        }

        return businessJdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> mapTypeaheadRow(rs, rs.getShort("currency_scale")),
            params.toArray()
        );
    }

    public LedgerDtos.LedgerResponse getLedger(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID ledgerId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        return requireLedgerById(companyId, ledgerId, scale);
    }

    public LedgerDtos.LedgerResponse createLedger(
        MoneyCraftPrincipal actor,
        UUID companyId,
        LedgerDtos.CreateOrUpdateLedgerRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForCreate(companyId, true);

            NormalizedLedgerSpec spec = validateAndNormalize(
                request,
                seq.currencyScale(),
                seq.baseCurrencyCode(),
                false
            );
            assertGroupExists(companyId, spec.groupId());
            assertUniqueLedgerName(companyId, spec.name(), null);

            UUID ledgerId = UUID.randomUUID();
            EncryptedLedgerSecrets secrets = encryptSecrets(companyId, ledgerId, spec.panPlain(), spec.bankDetails());

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_ledger (
                    id, company_id, name, alias_code, group_id,
                    opening_balance, opening_side, currency_code,
                    maintain_balances_bill_by_bill, default_credit_period_days,
                    check_credit_days_during_voucher, credit_limit,
                    inventory_values_affected, cost_centres_applicable,
                    tax_type, duty_head, gst_applicability, gst_supply_type, hsn_sac_code, gst_rate_pct,
                    appropriate_for, appropriation_basis,
                    party_registration_type, gstin, country_code, state_code, state_name, pincode,
                    pan_siv, pan_plain_masked, msme_udyam_no,
                    tds_applicable, tds_nature_code, tds_deductee_type,
                    bank_account_holder, bank_ifsc_code, bank_name, bank_branch,
                    bank_account_siv, bank_details_gcm, upi_id,
                    is_system_ledger, active, master_seq_id, alter_id, version_no, created_by
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    false, ?, ?, ?, 1, ?
                )
                """,
                ledgerId,
                companyId,
                spec.name(),
                spec.aliasCode(),
                spec.groupId(),
                spec.openingBalance(),
                spec.openingSide(),
                spec.currencyCode(),
                spec.maintainBalancesBillByBill(),
                spec.defaultCreditPeriodDays(),
                spec.checkCreditDaysDuringVoucher(),
                spec.creditLimit(),
                spec.inventoryValuesAffected(),
                spec.costCentresApplicable(),
                spec.taxType(),
                spec.dutyHead(),
                spec.gstApplicability(),
                spec.gstSupplyType(),
                spec.hsnSacCode(),
                spec.gstRatePct(),
                spec.dbAppropriateFor(),
                spec.appropriationBasis(),
                spec.partyRegistrationType(),
                spec.gstin(),
                spec.countryCode(),
                spec.stateCode(),
                spec.stateName(),
                spec.pincode(),
                secrets.panSiv(),
                secrets.panMasked(),
                spec.msmeUdyamNo(),
                spec.tdsApplicable(),
                spec.tdsNatureCode(),
                spec.tdsDeducteeType(),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().accountHolder()),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().ifscCode()),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().bankName()),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().branch()),
                secrets.bankAccountSiv(),
                secrets.bankDetailsGcm(),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().upiId()),
                spec.active(),
                seq.masterSeqId(),
                seq.alterId(),
                actor.principalId()
            );

            LedgerDtos.LedgerResponse created = requireLedgerById(companyId, ledgerId, seq.currencyScale());
            editLogSnapshotService.recordVersion(
                companyId,
                "LEDGER",
                ledgerId,
                created.masterSeqId(),
                created.alterId(),
                created.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                null,
                null,
                null,
                redactBankNumberForSnapshot(created)
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "LEDGER_CREATE",
                "LEDGER",
                ledgerId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "name", created.name(),
                    "taxType", created.taxType(),
                    "openingBalance", created.openingBalance(),
                    "versionNo", created.versionNo()
                )
            );
            return created;
        });
    }

    public LedgerDtos.LedgerResponse updateLedger(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID ledgerId,
        String ifMatchHeader,
        LedgerDtos.CreateOrUpdateLedgerRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, true);

            LedgerDtos.LedgerResponse before = lockLedgerById(companyId, ledgerId, seq.currencyScale());
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, before.versionNo(), "ledger");

            boolean isProfitAndLoss = "Profit & Loss A/c".equalsIgnoreCase(before.name());
            NormalizedLedgerSpec spec = validateAndNormalize(
                request,
                seq.currencyScale(),
                seq.baseCurrencyCode(),
                isProfitAndLoss
            );

            if (before.isSystemLedger() && !before.name().equalsIgnoreCase(spec.name())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-SYSTEM-LEDGER-PROTECTED",
                    "Predefined System Ledger Protected",
                    "Predefined system ledger '" + before.name() + "' cannot be renamed."
                );
            }
            if (!isProfitAndLoss) {
                assertGroupExists(companyId, spec.groupId());
            }
            assertUniqueLedgerName(companyId, spec.name(), ledgerId);

            EncryptedLedgerSecrets secrets = encryptSecrets(companyId, ledgerId, spec.panPlain(), spec.bankDetails());
            long nextVersion = before.versionNo() + 1L;

            businessJdbcTemplate.update(
                """
                UPDATE mc_ledger
                   SET name = ?,
                       alias_code = ?,
                       group_id = ?,
                       opening_balance = ?,
                       opening_side = ?,
                       currency_code = ?,
                       maintain_balances_bill_by_bill = ?,
                       default_credit_period_days = ?,
                       check_credit_days_during_voucher = ?,
                       credit_limit = ?,
                       inventory_values_affected = ?,
                       cost_centres_applicable = ?,
                       tax_type = ?,
                       duty_head = ?,
                       gst_applicability = ?,
                       gst_supply_type = ?,
                       hsn_sac_code = ?,
                       gst_rate_pct = ?,
                       appropriate_for = ?,
                       appropriation_basis = ?,
                       party_registration_type = ?,
                       gstin = ?,
                       country_code = ?,
                       state_code = ?,
                       state_name = ?,
                       pincode = ?,
                       pan_siv = COALESCE(?, pan_siv),
                       pan_plain_masked = COALESCE(?, pan_plain_masked),
                       msme_udyam_no = ?,
                       tds_applicable = ?,
                       tds_nature_code = ?,
                       tds_deductee_type = ?,
                       bank_account_holder = ?,
                       bank_ifsc_code = ?,
                       bank_name = ?,
                       bank_branch = ?,
                       bank_account_siv = ?,
                       bank_details_gcm = ?,
                       upi_id = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.name(),
                spec.aliasCode(),
                isProfitAndLoss ? null : spec.groupId(),
                spec.openingBalance(),
                spec.openingSide(),
                spec.currencyCode(),
                spec.maintainBalancesBillByBill(),
                spec.defaultCreditPeriodDays(),
                spec.checkCreditDaysDuringVoucher(),
                spec.creditLimit(),
                spec.inventoryValuesAffected(),
                spec.costCentresApplicable(),
                spec.taxType(),
                spec.dutyHead(),
                spec.gstApplicability(),
                spec.gstSupplyType(),
                spec.hsnSacCode(),
                spec.gstRatePct(),
                spec.dbAppropriateFor(),
                spec.appropriationBasis(),
                spec.partyRegistrationType(),
                spec.gstin(),
                spec.countryCode(),
                spec.stateCode(),
                spec.stateName(),
                spec.pincode(),
                secrets.panSiv(),
                secrets.panMasked(),
                spec.msmeUdyamNo(),
                spec.tdsApplicable(),
                spec.tdsNatureCode(),
                spec.tdsDeducteeType(),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().accountHolder()),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().ifscCode()),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().bankName()),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().branch()),
                secrets.bankAccountSiv(),
                secrets.bankDetailsGcm(),
                spec.bankDetails() == null ? null : trimToNull(spec.bankDetails().upiId()),
                before.isSystemLedger() || spec.active(),
                seq.alterId(),
                nextVersion,
                companyId,
                ledgerId
            );

            VoucherStatutoryBridge statBridge = statutoryBridgeProvider.getIfAvailable();
            if (statBridge != null) {
                statBridge.recomputeOpenPeriodStatutoryForLedger(companyId, ledgerId);
            }

            LedgerDtos.LedgerResponse after = requireLedgerById(companyId, ledgerId, seq.currencyScale());
            editLogSnapshotService.recordVersion(
                companyId,
                "LEDGER",
                ledgerId,
                after.masterSeqId(),
                after.alterId(),
                after.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                null,
                null,
                redactBankNumberForSnapshot(before),
                redactBankNumberForSnapshot(after)
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "LEDGER_ALTER",
                "LEDGER",
                ledgerId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "name", after.name(),
                    "openingBalance", after.openingBalance(),
                    "versionNo", after.versionNo()
                )
            );
            return after;
        });
    }

    public void deleteLedger(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID ledgerId
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        businessTxTemplate.executeWithoutResult(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, true);

            LedgerDtos.LedgerResponse before = lockLedgerById(companyId, ledgerId, seq.currencyScale());
            if (before.isSystemLedger()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-SYSTEM-LEDGER-PROTECTED",
                    "Predefined System Ledger Protected",
                    "Predefined system ledger '" + before.name() + "' cannot be deleted."
                );
            }

            Integer entryRefs = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_journal_line WHERE company_id = ? AND ledger_id = ?",
                Integer.class,
                companyId,
                ledgerId
            );
            if (entryRefs != null && entryRefs > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-MASTER-IN-USE",
                    "Ledger Has Voucher Transactions",
                    "Ledger '" + before.name() + "' cannot be deleted because vouchers reference it."
                );
            }

            businessJdbcTemplate.update(
                "DELETE FROM mc_ledger WHERE company_id = ? AND id = ?",
                companyId,
                ledgerId
            );

            long deleteVersion = before.versionNo() + 1L;
            LedgerDtos.LedgerResponse redactedBefore = redactBankNumberForSnapshot(before);
            editLogSnapshotService.recordVersion(
                companyId,
                "LEDGER",
                ledgerId,
                before.masterSeqId(),
                seq.alterId(),
                deleteVersion,
                "DELETE",
                actor.principalId(),
                actor.username(),
                null,
                null,
                redactedBefore,
                redactedBefore
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "LEDGER_DELETE",
                "LEDGER",
                ledgerId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "name", before.name(),
                    "deletedVersionNo", deleteVersion
                )
            );
        });
    }

    private static void appendTypeaheadCategoryFilter(StringBuilder sql, String filter) {
        switch (filter) {
            case "ALL" -> {
                // All active ledgers
            }
            case "CASH_BANK" -> sql.append(
                """
                 AND g.id IS NOT NULL
                 AND EXISTS (
                     SELECT 1 FROM mc_account_group anc
                      WHERE anc.company_id = l.company_id
                        AND lower(anc.name) IN ('cash-in-hand', 'bank accounts', 'bank od a/c')
                        AND position('/' || anc.id::text || '/' IN g.tree_path) > 0
                 )
                """
            );
            case "NON_CASH_BANK" -> sql.append(
                """
                 AND NOT (
                     g.id IS NOT NULL
                     AND EXISTS (
                         SELECT 1 FROM mc_account_group anc
                          WHERE anc.company_id = l.company_id
                            AND lower(anc.name) IN ('cash-in-hand', 'bank accounts', 'bank od a/c')
                            AND position('/' || anc.id::text || '/' IN g.tree_path) > 0
                     )
                 )
                """
            );
            case "PARTY" -> sql.append(
                """
                 AND (
                     COALESCE(g.behaves_like_subledger, false) = true
                     OR (
                         g.id IS NOT NULL
                         AND EXISTS (
                             SELECT 1 FROM mc_account_group anc
                              WHERE anc.company_id = l.company_id
                                AND lower(anc.name) IN ('sundry debtors', 'sundry creditors', 'branch / divisions')
                                AND position('/' || anc.id::text || '/' IN g.tree_path) > 0
                         )
                     )
                 )
                """
            );
            case "SALES" -> sql.append(
                """
                 AND g.id IS NOT NULL
                 AND EXISTS (
                     SELECT 1 FROM mc_account_group anc
                      WHERE anc.company_id = l.company_id
                        AND lower(anc.name) = 'sales accounts'
                        AND position('/' || anc.id::text || '/' IN g.tree_path) > 0
                 )
                """
            );
            case "PURCHASE" -> sql.append(
                """
                 AND g.id IS NOT NULL
                 AND EXISTS (
                     SELECT 1 FROM mc_account_group anc
                      WHERE anc.company_id = l.company_id
                        AND lower(anc.name) = 'purchase accounts'
                        AND position('/' || anc.id::text || '/' IN g.tree_path) > 0
                 )
                """
            );
            case "DUTIES_TAXES" -> sql.append(
                """
                 AND (
                     l.tax_type <> 'NONE'
                     OR (
                         g.id IS NOT NULL
                         AND EXISTS (
                             SELECT 1 FROM mc_account_group anc
                              WHERE anc.company_id = l.company_id
                                AND lower(anc.name) = 'duties & taxes'
                                AND position('/' || anc.id::text || '/' IN g.tree_path) > 0
                         )
                     )
                 )
                """
            );
            case "EXPENSE_INCOME" -> sql.append(" AND g.nature IN ('INCOME', 'EXPENSES')");
            default -> throw new IllegalArgumentException("Unsupported filter: " + filter);
        }
    }

    private EncryptedLedgerSecrets encryptSecrets(
        UUID companyId,
        UUID ledgerId,
        String panPlain,
        LedgerDtos.BankDetailsDto bankDetails
    ) {
        byte[] panSiv = null;
        String panMasked = null;
        if (panPlain != null && !panPlain.isBlank()) {
            panSiv = tinkCryptoService.encryptDeterministicSiv(companyId, "mc_ledger", "pan_siv", panPlain);
            panMasked = ExactMoneyValidator.maskPan(panPlain);
        }

        byte[] bankAccountSiv = null;
        byte[] bankDetailsGcm = null;
        if (bankDetails != null) {
            if (bankDetails.accountNumber() != null && !bankDetails.accountNumber().isBlank()) {
                bankAccountSiv = tinkCryptoService.encryptDeterministicSiv(
                    companyId,
                    "mc_ledger",
                    "bank_account_siv",
                    bankDetails.accountNumber().trim()
                );
            }
            try {
                byte[] plainJson = objectMapper.writeValueAsBytes(bankDetails);
                bankDetailsGcm = tinkCryptoService.encryptGcm(buildBankGcmAd(companyId, ledgerId), plainJson);
            } catch (JacksonException ex) {
                throw new IllegalStateException("Failed to serialize bankDetails payload", ex);
            }
        }
        return new EncryptedLedgerSecrets(panSiv, panMasked, bankAccountSiv, bankDetailsGcm);
    }

    private LedgerDtos.BankDetailsDto decryptBankDetails(UUID companyId, UUID ledgerId, byte[] gcmCiphertext) {
        if (gcmCiphertext == null || gcmCiphertext.length == 0) {
            return null;
        }
        byte[] decrypted = tinkCryptoService.decryptGcm(buildBankGcmAd(companyId, ledgerId), gcmCiphertext);
        try {
            return objectMapper.readValue(decrypted, LedgerDtos.BankDetailsDto.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize decrypted bank_details_gcm", ex);
        }
    }

    private static byte[] buildBankGcmAd(UUID companyId, UUID ledgerId) {
        return (companyId + "|mc_ledger|bank_details_gcm|" + ledgerId).getBytes(StandardCharsets.UTF_8);
    }

    private static LedgerDtos.LedgerResponse redactBankNumberForSnapshot(LedgerDtos.LedgerResponse dto) {
        if (dto.bankDetails() == null || dto.bankDetails().accountNumber() == null) {
            return dto;
        }
        String rawAcct = dto.bankDetails().accountNumber().trim();
        String maskedAcct = rawAcct.length() <= 4
            ? "****"
            : "****" + rawAcct.substring(rawAcct.length() - 4);
        LedgerDtos.BankDetailsDto maskedBank = new LedgerDtos.BankDetailsDto(
            dto.bankDetails().accountHolder(),
            maskedAcct,
            dto.bankDetails().ifscCode(),
            dto.bankDetails().bankName(),
            dto.bankDetails().branch(),
            dto.bankDetails().upiId()
        );
        return new LedgerDtos.LedgerResponse(
            dto.id(),
            dto.companyId(),
            dto.name(),
            dto.aliasCode(),
            dto.groupId(),
            dto.groupName(),
            dto.nature(),
            dto.openingBalance(),
            dto.openingSide(),
            dto.currencyCode(),
            dto.maintainBalancesBillByBill(),
            dto.defaultCreditPeriodDays(),
            dto.checkCreditDaysDuringVoucher(),
            dto.creditLimit(),
            dto.inventoryValuesAffected(),
            dto.costCentresApplicable(),
            dto.taxType(),
            dto.dutyHead(),
            dto.gstApplicability(),
            dto.gstSupplyType(),
            dto.gstRatePct(),
            dto.hsnSacCode(),
            dto.appropriateFor(),
            dto.partyRegistrationType(),
            dto.countryCode(),
            dto.stateCode(),
            dto.stateName(),
            dto.pincode(),
            dto.gstin(),
            dto.panMasked(),
            dto.tdsApplicable(),
            dto.tdsNatureCode(),
            dto.tdsDeducteeType(),
            dto.msmeUdyamNo(),
            maskedBank,
            dto.isSystemLedger(),
            dto.active(),
            dto.masterSeqId(),
            dto.alterId(),
            dto.versionNo()
        );
    }

    private void assertGroupExists(UUID companyId, UUID groupId) {
        Integer count = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mc_account_group WHERE company_id = ? AND id = ?",
            Integer.class,
            companyId,
            groupId
        );
        if (count == null || count == 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-GROUP-NOT-FOUND",
                "Account Group Not Found",
                "Account group " + groupId + " does not exist in company.",
                Map.of("groupId", "Valid account group UUID is required")
            );
        }
    }

    private void assertUniqueLedgerName(UUID companyId, String name, UUID excludeId) {
        Integer dup = excludeId == null
            ? businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_ledger WHERE company_id = ? AND lower(name) = lower(?)",
                Integer.class,
                companyId,
                name
            )
            : businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_ledger WHERE company_id = ? AND lower(name) = lower(?) AND id <> ?",
                Integer.class,
                companyId,
                name,
                excludeId
            );
        if (dup != null && dup > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-ACC-LEDGER-DUPLICATE",
                "Duplicate Ledger Name",
                "Ledger '" + name + "' already exists in this company."
            );
        }
    }

    private LedgerDtos.LedgerResponse requireLedgerById(UUID companyId, UUID ledgerId, int scale) {
        List<LedgerDtos.LedgerResponse> rows = businessJdbcTemplate.query(
            ledgerSelectSql("l.company_id = ? AND l.id = ?"),
            (rs, rowNum) -> mapLedgerRow(rs, scale),
            companyId,
            ledgerId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-LEDGER-NOT-FOUND",
                "Ledger Not Found",
                "Ledger " + ledgerId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private LedgerDtos.LedgerResponse lockLedgerById(UUID companyId, UUID ledgerId, int scale) {
        List<UUID> locked = businessJdbcTemplate.query(
            "SELECT id FROM mc_ledger WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            ledgerId
        );
        if (locked.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-LEDGER-NOT-FOUND",
                "Ledger Not Found",
                "Ledger " + ledgerId + " does not exist."
            );
        }
        return requireLedgerById(companyId, ledgerId, scale);
    }

    private static String ledgerSelectSql(String whereClause) {
        return """
            SELECT l.id, l.company_id, l.name, l.alias_code, l.group_id,
                   g.name AS group_name, COALESCE(g.nature, 'LIABILITIES') AS nature,
                   l.opening_balance, l.opening_side, trim(l.currency_code) AS ccy_code,
                   l.maintain_balances_bill_by_bill, l.default_credit_period_days,
                   l.check_credit_days_during_voucher, l.credit_limit,
                   l.inventory_values_affected, l.cost_centres_applicable,
                   l.tax_type, l.duty_head, l.gst_applicability, l.gst_supply_type,
                   l.gst_rate_pct, l.hsn_sac_code,
                   l.appropriate_for, l.party_registration_type,
                   l.country_code, l.state_code, l.state_name, l.pincode,
                   l.gstin, l.pan_plain_masked,
                   l.tds_applicable, l.tds_nature_code, l.tds_deductee_type,
                   l.msme_udyam_no, l.bank_details_gcm,
                   l.is_system_ledger, l.active,
                   l.master_seq_id, l.alter_id, l.version_no
              FROM mc_ledger l
              LEFT JOIN mc_account_group g
                ON g.company_id = l.company_id AND g.id = l.group_id
             WHERE %s
            """.formatted(whereClause);
    }

    private LedgerDtos.LedgerResponse mapLedgerRow(ResultSet rs, int scale) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID companyId = rs.getObject("company_id", UUID.class);
        BigDecimal opening = rs.getBigDecimal("opening_balance");
        BigDecimal creditLimit = rs.getBigDecimal("credit_limit");
        BigDecimal gstRate = rs.getBigDecimal("gst_rate_pct");
        Integer creditDays = (Integer) rs.getObject("default_credit_period_days");

        return new LedgerDtos.LedgerResponse(
            id,
            companyId,
            rs.getString("name"),
            rs.getString("alias_code"),
            rs.getObject("group_id", UUID.class),
            rs.getString("group_name"),
            rs.getString("nature"),
            ExactMoneyValidator.formatExactScale(opening, scale),
            rs.getString("opening_side"),
            rs.getString("ccy_code"),
            rs.getBoolean("maintain_balances_bill_by_bill"),
            creditDays,
            rs.getBoolean("check_credit_days_during_voucher"),
            creditLimit == null ? null : ExactMoneyValidator.formatExactScale(creditLimit, scale),
            rs.getBoolean("inventory_values_affected"),
            rs.getBoolean("cost_centres_applicable"),
            rs.getString("tax_type"),
            rs.getString("duty_head"),
            rs.getString("gst_applicability"),
            rs.getString("gst_supply_type"),
            ExactMoneyValidator.formatPlainDecimal(gstRate),
            rs.getString("hsn_sac_code"),
            toWireAppropriateFor(rs.getString("appropriate_for")),
            rs.getString("party_registration_type"),
            rs.getString("country_code"),
            rs.getString("state_code"),
            rs.getString("state_name"),
            rs.getString("pincode"),
            rs.getString("gstin"),
            rs.getString("pan_plain_masked"),
            rs.getBoolean("tds_applicable"),
            rs.getString("tds_nature_code"),
            rs.getString("tds_deductee_type"),
            rs.getString("msme_udyam_no"),
            decryptBankDetails(companyId, id, rs.getBytes("bank_details_gcm")),
            rs.getBoolean("is_system_ledger"),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no")
        );
    }

    private LedgerDtos.LedgerTypeaheadResponse mapTypeaheadRow(ResultSet rs, int scale) throws SQLException {
        BigDecimal opening = rs.getBigDecimal("opening_balance");
        String openingSide = rs.getString("opening_side");
        BigDecimal drMov = rs.getBigDecimal("dr_movements");
        BigDecimal crMov = rs.getBigDecimal("cr_movements");

        BigDecimal signedOpening = "DR".equals(openingSide) ? opening : opening.negate();
        BigDecimal signedClosing = signedOpening.add(drMov).subtract(crMov);

        String balanceSide;
        BigDecimal absClosing;
        if (signedClosing.signum() > 0) {
            balanceSide = "DR";
            absClosing = signedClosing;
        } else if (signedClosing.signum() < 0) {
            balanceSide = "CR";
            absClosing = signedClosing.negate();
        } else {
            balanceSide = openingSide;
            absClosing = BigDecimal.ZERO;
        }

        return new LedgerDtos.LedgerTypeaheadResponse(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("alias_code"),
            rs.getObject("group_id", UUID.class),
            rs.getString("group_name"),
            rs.getString("nature"),
            rs.getBoolean("maintain_balances_bill_by_bill"),
            rs.getBoolean("cost_centres_applicable"),
            rs.getString("tax_type"),
            rs.getString("duty_head"),
            rs.getString("gstin"),
            rs.getString("state_name"),
            rs.getString("party_registration_type"),
            toWireAppropriateFor(rs.getString("appropriate_for")),
            rs.getBoolean("tds_applicable"),
            rs.getString("tds_nature_code"),
            ExactMoneyValidator.formatExactScale(absClosing, scale),
            balanceSide
        );
    }

    private NormalizedLedgerSpec validateAndNormalize(
        LedgerDtos.CreateOrUpdateLedgerRequest req,
        int currencyScale,
        String baseCurrencyCode,
        boolean allowNullGroupForProfitAndLoss
    ) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-LEDGER-NAME-REQUIRED",
                "Ledger Name Required",
                "Ledger 'name' must be non-empty.",
                Map.of("name", "Ledger name is required")
            );
        }
        if (!allowNullGroupForProfitAndLoss && req.groupId() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-LEDGER-GROUP-REQUIRED",
                "Account Group Required",
                "Ledger 'groupId' is required.",
                Map.of("groupId", "Account group is required")
            );
        }

        String name = req.name().trim();
        BigDecimal openingBalance = req.openingBalance() == null || req.openingBalance().isBlank()
            ? BigDecimal.ZERO.setScale(currencyScale)
            : ExactMoneyValidator.parseAndValidateExactScale(req.openingBalance(), currencyScale, "openingBalance");

        String openingSide = req.openingSide() == null || req.openingSide().isBlank()
            ? "DR"
            : req.openingSide().trim().toUpperCase(Locale.ROOT);
        if (!VALID_SIDES.contains(openingSide)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-SIDE-INVALID",
                "Invalid Opening Balance Side",
                "openingSide must be DR or CR."
            );
        }

        String currencyCode = req.currencyCode() == null || req.currencyCode().isBlank()
            ? baseCurrencyCode
            : req.currencyCode().trim().toUpperCase(Locale.ROOT);
        if (!currencyCode.matches("^[A-Z]{3}$")) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-CURRENCY-INVALID",
                "Invalid Currency ISO Code",
                "currencyCode must be a 3-letter uppercase ISO currency code."
            );
        }

        if (req.defaultCreditPeriodDays() != null && req.defaultCreditPeriodDays() < 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-CREDIT-DAYS-INVALID",
                "Invalid Credit Period",
                "defaultCreditPeriodDays must be >= 0."
            );
        }

        BigDecimal creditLimit = null;
        if (req.creditLimit() != null && !req.creditLimit().isBlank()) {
            creditLimit = ExactMoneyValidator.parseAndValidateExactScale(
                req.creditLimit(),
                currencyScale,
                "creditLimit"
            );
        }

        String taxType = req.taxType() == null || req.taxType().isBlank()
            ? "NONE"
            : req.taxType().trim().toUpperCase(Locale.ROOT);
        if (!VALID_TAX_TYPES.contains(taxType)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-TAX-TYPE-INVALID",
                "Invalid Tax Type",
                "taxType must be NONE, GST, TDS, TCS, or OTHERS."
            );
        }

        String dutyHead = trimToNull(req.dutyHead());
        if (dutyHead != null) {
            dutyHead = dutyHead.toUpperCase(Locale.ROOT);
            if (!VALID_DUTY_HEADS.contains(dutyHead)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-DUTY-HEAD-INVALID",
                    "Invalid Duty Head",
                    "dutyHead must be one of " + VALID_DUTY_HEADS + "."
                );
            }
        }

        BigDecimal gstRatePct = null;
        if (req.gstRatePct() != null && !req.gstRatePct().isBlank()) {
            gstRatePct = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.gstRatePct(),
                6,
                BigDecimal.ZERO,
                HUNDRED,
                false,
                "gstRatePct"
            );
        }

        String gstApplicability;
        if (req.gstApplicability() != null && !req.gstApplicability().isBlank()) {
            gstApplicability = req.gstApplicability().trim().toUpperCase(Locale.ROOT);
            if (!VALID_GST_APPLICABILITY.contains(gstApplicability)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-GST-APP-INVALID",
                    "Invalid GST Applicability",
                    "gstApplicability must be APPLICABLE, NOT_APPLICABLE, or UNDEFINED."
                );
            }
        } else {
            gstApplicability = (gstRatePct != null || "GST".equals(taxType) || req.hsnSacCode() != null)
                ? "APPLICABLE"
                : "NOT_APPLICABLE";
        }

        String gstSupplyType = req.gstSupplyType() == null || req.gstSupplyType().isBlank()
            ? "GOODS"
            : req.gstSupplyType().trim().toUpperCase(Locale.ROOT);
        if (!VALID_GST_SUPPLY_TYPES.contains(gstSupplyType)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-GST-SUPPLY-INVALID",
                "Invalid GST Supply Type",
                "gstSupplyType must be GOODS, SERVICES, or CAPITAL_GOODS."
            );
        }

        String appropBasis = req.appropriationBasis() == null || req.appropriationBasis().isBlank()
            ? "BY_VALUE"
            : req.appropriationBasis().trim().toUpperCase(Locale.ROOT);
        if (!"BY_VALUE".equals(appropBasis) && !"BY_QTY".equals(appropBasis)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-APPROP-BASIS-INVALID",
                "Invalid Appropriation Basis",
                "appropriationBasis must be BY_VALUE or BY_QTY."
            );
        }

        String rawApprop = req.appropriateFor() == null || req.appropriateFor().isBlank()
            ? "NONE"
            : req.appropriateFor().trim().toUpperCase(Locale.ROOT);
        String dbApprop = switch (rawApprop) {
            case "NONE" -> "NONE";
            case "GST" -> "GST";
            case "BOTH" -> "BY_QTY".equals(appropBasis) ? "BOTH_QTY" : "BOTH_VALUE";
            case "BOTH_QTY" -> "BOTH_QTY";
            case "BOTH_VALUE" -> "BOTH_VALUE";
            default -> throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-APPROPRIATE-FOR-INVALID",
                "Invalid Appropriate-For Mode",
                "appropriateFor must be NONE, GST, or BOTH."
            );
        };

        String partyReg = req.partyRegistrationType() == null || req.partyRegistrationType().isBlank()
            ? "UNKNOWN"
            : req.partyRegistrationType().trim().toUpperCase(Locale.ROOT);
        if (!VALID_PARTY_REG_TYPES.contains(partyReg)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-PARTY-REG-INVALID",
                "Invalid Party Registration Type",
                "partyRegistrationType must be one of " + VALID_PARTY_REG_TYPES + "."
            );
        }

        String countryCode = req.countryCode() == null || req.countryCode().isBlank()
            ? "IN"
            : req.countryCode().trim().toUpperCase(Locale.ROOT);
        if (!countryCode.matches("^[A-Z]{2}$")) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-COUNTRY-INVALID",
                "Invalid Country Code",
                "countryCode must be a 2-letter uppercase ISO country code."
            );
        }

        String stateCode = trimToNull(req.stateCode());
        String gstin = trimToNull(req.gstin());
        String panPlain = trimToNull(req.pan());
        if (gstin != null) {
            gstin = ExactMoneyValidator.validatePartyGstin(gstin, stateCode);
            if (stateCode == null) {
                stateCode = gstin.substring(0, 2);
            }
            if (panPlain == null) {
                panPlain = gstin.substring(2, 12);
            }
            if ("UNKNOWN".equals(partyReg)) {
                partyReg = "REGULAR";
            }
        }
        if (panPlain != null) {
            panPlain = ExactMoneyValidator.validatePan(panPlain);
        }

        String stateName = trimToNull(req.stateName());
        if (stateName == null && stateCode != null) {
            stateName = "State-" + stateCode;
        }

        return new NormalizedLedgerSpec(
            name,
            trimToNull(req.aliasCode()),
            req.groupId(),
            openingBalance,
            openingSide,
            currencyCode,
            Boolean.TRUE.equals(req.maintainBalancesBillByBill()),
            req.defaultCreditPeriodDays(),
            Boolean.TRUE.equals(req.checkCreditDaysDuringVoucher()),
            creditLimit,
            Boolean.TRUE.equals(req.inventoryValuesAffected()),
            Boolean.TRUE.equals(req.costCentresApplicable()),
            taxType,
            dutyHead,
            gstApplicability,
            gstSupplyType,
            trimToNull(req.hsnSacCode()),
            gstRatePct,
            dbApprop,
            appropBasis,
            partyReg,
            gstin,
            countryCode,
            stateCode,
            stateName,
            trimToNull(req.pincode()),
            panPlain,
            Boolean.TRUE.equals(req.tdsApplicable()),
            trimToNull(req.tdsNatureCode()),
            trimToNull(req.tdsDeducteeType()),
            trimToNull(req.msmeUdyamNo()),
            req.bankDetails(),
            req.active() == null || req.active()
        );
    }

    private static String toWireAppropriateFor(String dbValue) {
        if ("BOTH_QTY".equals(dbValue) || "BOTH_VALUE".equals(dbValue)) {
            return "BOTH";
        }
        return dbValue == null ? "NONE" : dbValue;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record EncryptedLedgerSecrets(
        byte[] panSiv,
        String panMasked,
        byte[] bankAccountSiv,
        byte[] bankDetailsGcm
    ) {
    }

    private record NormalizedLedgerSpec(
        String name,
        String aliasCode,
        UUID groupId,
        BigDecimal openingBalance,
        String openingSide,
        String currencyCode,
        boolean maintainBalancesBillByBill,
        Integer defaultCreditPeriodDays,
        boolean checkCreditDaysDuringVoucher,
        BigDecimal creditLimit,
        boolean inventoryValuesAffected,
        boolean costCentresApplicable,
        String taxType,
        String dutyHead,
        String gstApplicability,
        String gstSupplyType,
        String hsnSacCode,
        BigDecimal gstRatePct,
        String dbAppropriateFor,
        String appropriationBasis,
        String partyRegistrationType,
        String gstin,
        String countryCode,
        String stateCode,
        String stateName,
        String pincode,
        String panPlain,
        boolean tdsApplicable,
        String tdsNatureCode,
        String tdsDeducteeType,
        String msmeUdyamNo,
        LedgerDtos.BankDetailsDto bankDetails,
        boolean active
    ) {
    }
}
