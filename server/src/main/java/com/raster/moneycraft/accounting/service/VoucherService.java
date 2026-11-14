package com.raster.moneycraft.accounting.service;

import com.raster.moneycraft.accounting.dto.CursorPage;
import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.accounting.spi.VoucherInventoryBridge;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ACID Double-Entry Voucher Engine enforcing MCJ1-TXN-01 lock ordering, exact fixed-point
 * BigDecimal arithmetic, Single-Entry & Double-Entry views, Bill-Wise/Cost/Bank/Forex sub-ledgers,
 * FOUR_EYES governance, Tally Alt+X cancellation, MCA Rule 3(1) Edit Log, and MC-AUDIT-2.
 */
@Service
public class VoucherService {

    private static final Set<String> VALID_PERSISTED_VIEWS = Set.of(
        "SINGLE_ENTRY", "AS_VOUCHER", "ACCOUNTING_INVOICE", "ITEM_INVOICE"
    );

    private static final Set<String> VALID_POSTING_NATURES = Set.of(
        "NORMAL", "OPTIONAL", "MEMORANDUM", "REVERSING_JOURNAL"
    );

    private static final Set<String> VALID_ACTIONS = Set.of(
        "SAVE_DRAFT", "SUBMIT_FOR_APPROVAL", "POST"
    );

    private static final Set<String> VALID_LINE_ROLES = Set.of(
        "PARTY", "ACCOUNTING", "LEDGER", "ITEM_ACCOUNTING",
        "TAX", "DISCOUNT", "ADDITIONAL_CHARGE", "ROUND_OFF"
    );

    private static final Set<String> VALID_BILL_ALLOC_TYPES = Set.of(
        "NEW_REF", "AGST_REF", "ADVANCE", "ON_ACCOUNT"
    );

    private static final Set<String> VALID_BANK_TXN_TYPES = Set.of(
        "CHEQUE", "NEFT", "RTGS", "IMPS", "UPI",
        "CASH_DEPOSIT", "CARD", "ECS_NACH", "BANK_CHARGES", "OTHERS"
    );

    private static final Set<String> VALID_EXPORT_TYPES = Set.of(
        "WPAY", "WOPAY", "SEZ_WPAY", "SEZ_WOPAY", "DEEMED_EXPORT"
    );

    private static final Set<String> CONTRA_ALLOWED_GROUP_NAMES = Set.of(
        "cash-in-hand", "bank accounts", "bank od a/c", "bank occ a/c"
    );

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final VoucherNumberingService numberingService;
    private final EditLogSnapshotService editLogSnapshotService;
    private final AuditChainService auditChainService;
    private final CursorCodec cursorCodec;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;
    private final ObjectProvider<VoucherInventoryBridge> inventoryBridgeProvider;

    public VoucherService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        VoucherNumberingService numberingService,
        EditLogSnapshotService editLogSnapshotService,
        AuditChainService auditChainService,
        CursorCodec cursorCodec,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager,
        ObjectProvider<VoucherInventoryBridge> inventoryBridgeProvider
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.numberingService = numberingService;
        this.editLogSnapshotService = editLogSnapshotService;
        this.auditChainService = auditChainService;
        this.cursorCodec = cursorCodec;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
        this.inventoryBridgeProvider = inventoryBridgeProvider;
    }

    public VoucherDtos.VoucherDetailDto createVoucher(
        MoneyCraftPrincipal actor,
        UUID companyId,
        VoucherDtos.UpsertVoucherRequest request
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        if (request == null || request.voucherDate() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-DATE-REQUIRED",
                "Voucher Date Required",
                "Field 'voucherDate' is required."
            );
        }

        return businessTxTemplate.execute(tx -> {
            // 1. MCJ1-TXN-01 step: lock mc_company FOR UPDATE and allocate master_seq_id / alter_id
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForCreate(companyId, false);

            LocalDate voucherDate = request.voucherDate();
            LocalDate effectiveDate = request.effectiveDate() == null ? voucherDate : request.effectiveDate();

            // 2. MCJ1-TXN-01 step: lock mc_period FOR SHARE & assert OPEN
            LockedPeriodRow period = lockAndRequireOpenPeriod(companyId, effectiveDate);

            // 3. Resolve Voucher Type & normalize header modes
            VoucherTypeMeta vt = resolveVoucherType(companyId, request.voucherTypeId(), request.voucherTypeName());
            if (vt.postsAccountingEntries()) {
                CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
            }
            HeaderSpec header = normalizeHeaderSpec(request, vt, seq.baseCurrencyCode());

            VoucherInventoryBridge invBridge = inventoryBridgeProvider.getIfAvailable();
            VoucherInventoryBridge.InventoryPreLockPlan invPreLock = invBridge != null
                ? invBridge.resolvePreLockPlan(companyId, null, request.inventoryLines())
                : new VoucherInventoryBridge.InventoryPreLockPlan(Set.of(), Set.of());

            // 4. Collect & lock all distinct referenced mc_ledger rows sorted ascending by UUID::toString
            Set<UUID> referencedLedgerIds = collectReferencedLedgerIds(request, null);
            referencedLedgerIds.addAll(invPreLock.additionalLedgerIdsToLock());
            Map<UUID, LockedLedgerMeta> lockedLedgers = lockLedgersInCanonicalOrder(companyId, referencedLedgerIds);

            // 5. MCJ1-TXN-01 step: lock mc_stock_item & mc_stock_batch sorted ascending & synthesize ITEM_INVOICE lines
            LockedLedgerMeta partyLedgerMeta = request.partyLedgerId() == null
                ? null
                : lockedLedgers.get(request.partyLedgerId());
            VoucherInventoryBridge.PreparedInventoryContext invCtx = invBridge != null
                ? invBridge.lockAndPrepareInventory(
                    new VoucherInventoryBridge.InventoryPrepareCommand(
                        companyId,
                        null,
                        voucherDate,
                        effectiveDate,
                        seq.currencyScale(),
                        vt.baseCategory(),
                        vt.postsAccountingEntries(),
                        vt.movesInventory(),
                        vt.allowZeroValuedTransactions(),
                        header.persistedView(),
                        header.postingNature(),
                        header.status(),
                        request.partyLedgerId(),
                        partyLedgerMeta != null && partyLedgerMeta.maintainBalancesBillByBill(),
                        request.accountingLedgerId(),
                        header.supplierInvoiceNo(),
                        request.voucherNumber(),
                        invPreLock.stockItemIdsToLock(),
                        request.lines(),
                        request.inventoryLines()
                    )
                )
                : new VoucherInventoryBridge.PreparedInventoryContext(request.lines(), null, Set.of());

            // 6. Normalize & balance accounting lines + sub-ledger allocations
            boolean hasInventoryLines = request.inventoryLines() != null && !request.inventoryLines().isEmpty();
            List<PreparedLine> preparedLines = prepareAndValidateLines(
                companyId,
                null,
                effectiveDate,
                seq.currencyScale(),
                seq.baseCurrencyCode(),
                vt,
                header,
                request.accountingLedgerId(),
                invCtx.effectiveAccountingLines(),
                hasInventoryLines,
                lockedLedgers
            );

            BigDecimal totalDr = sumSide(preparedLines, "DR");
            BigDecimal totalCr = sumSide(preparedLines, "CR");

            UUID journalId = UUID.randomUUID();

            // 7. MCJ1-TXN-01 step: lock mc_number_series FOR UPDATE & allocate gap-free voucher number
            VoucherNumberingService.AllocatedVoucherNumber allocatedNumber = numberingService.allocateNumber(
                companyId,
                journalId,
                vt.id(),
                vt.numberingMethod(),
                vt.preventDuplicateNumbers(),
                period.fiscalYearId(),
                header.companyGstinId(),
                request.voucherNumber()
            );

            boolean postActiveGl = shouldHoldActiveGl(header.status(), header.postingNature(), vt.postsAccountingEntries());
            int postingBatchNo = postActiveGl ? 1 : 0;
            Long postSeq = "POSTED".equals(header.status()) ? seq.alterId() : null;
            UUID postedBy = "POSTED".equals(header.status()) ? actor.principalId() : null;

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_journal (
                    id, company_id, period_id, fiscal_year_id, voucher_type_id,
                    voucher_class_id, number_series_id, status, posting_nature,
                    persisted_view, approval_policy, voucher_number, voucher_seq_no, post_seq,
                    supplier_invoice_no, supplier_invoice_date, voucher_date, effective_date,
                    reversing_applicable_upto, party_ledger_id, accounting_ledger_id,
                    place_of_supply_state, company_gstin_id, party_gstin, party_mailing_name,
                    party_state_code, is_reverse_charge, export_type, currency_code,
                    exchange_rate, total_debit, total_credit, narration, origin, source_guid,
                    master_seq_id, alter_id, current_posting_batch_no, version_no,
                    created_by, updated_by, posted_by, posted_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, 'LIVE', ?,
                    ?, ?, ?, 1,
                    ?, ?, ?, CASE WHEN ?::uuid IS NOT NULL THEN clock_timestamp() ELSE NULL END
                )
                """,
                journalId,
                companyId,
                period.id(),
                period.fiscalYearId(),
                vt.id(),
                request.voucherClassId(),
                allocatedNumber.numberSeriesId(),
                header.status(),
                header.postingNature(),
                header.persistedView(),
                header.approvalPolicy(),
                allocatedNumber.voucherNumber(),
                allocatedNumber.voucherSeqNo(),
                postSeq,
                header.supplierInvoiceNo(),
                request.supplierInvoiceDate(),
                voucherDate,
                effectiveDate,
                request.reversingApplicableUpto(),
                request.partyLedgerId(),
                request.accountingLedgerId(),
                header.placeOfSupplyState(),
                header.companyGstinId(),
                header.partyGstin(),
                header.partyMailingName(),
                header.partyStateCode(),
                header.isReverseCharge(),
                header.exportType(),
                header.currencyCode(),
                header.exchangeRate(),
                totalDr,
                totalCr,
                header.narration(),
                header.sourceGuid(),
                seq.masterSeqId(),
                seq.alterId(),
                postingBatchNo,
                actor.principalId(),
                actor.principalId(),
                postedBy,
                postedBy
            );

            insertLinesAndSubLedgers(companyId, journalId, preparedLines);

            if (postActiveGl) {
                insertActiveGlEntries(companyId, journalId, period.id(), effectiveDate, postingBatchNo, preparedLines);
            }

            if (invBridge != null) {
                invBridge.persistAndApplyInventory(
                    new VoucherInventoryBridge.InventoryPersistCommand(
                        companyId,
                        journalId,
                        allocatedNumber.voucherNumber(),
                        effectiveDate,
                        header.status(),
                        header.postingNature(),
                        vt.baseCategory(),
                        vt.movesInventory(),
                        request.partyLedgerId(),
                        header.supplierInvoiceNo(),
                        invCtx
                    )
                );
            }

            VoucherDtos.VoucherDetailDto created = loadVoucherDetail(companyId, journalId, seq.currencyScale());

            editLogSnapshotService.recordVersion(
                companyId,
                "VOUCHER",
                journalId,
                created.masterSeqId(),
                created.alterId(),
                created.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                created.voucherNumber(),
                created.voucherDate(),
                null,
                created
            );

            // 7. MCJ1-TXN-01 final step: lock mc_audit_tail & append MC-AUDIT-2 event
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "VOUCHER_CREATE",
                "VOUCHER",
                journalId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "voucherType", created.voucherTypeName(),
                    "voucherNumber", created.voucherNumber() == null ? "" : created.voucherNumber(),
                    "status", created.status(),
                    "postingNature", created.postingNature(),
                    "totalDebit", created.totalDebit(),
                    "versionNo", created.versionNo()
                )
            );

            return created;
        });
    }

    public VoucherDtos.VoucherDetailDto getVoucher(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID voucherId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        CompanyMasterSequenceHelper.CompanyCurrencyMeta companyMeta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        return loadVoucherDetail(companyId, voucherId, companyMeta.currencyScale());
    }

    public VoucherDtos.VoucherDetailDto updateVoucher(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId,
        String ifMatchHeader,
        VoucherDtos.UpsertVoucherRequest request
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        if (request == null || request.voucherDate() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-DATE-REQUIRED",
                "Voucher Date Required",
                "Field 'voucherDate' is required."
            );
        }

        return businessTxTemplate.execute(tx -> {
            // 1. Lock mc_company FOR UPDATE & allocate alter_id
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            LocalDate voucherDate = request.voucherDate();
            LocalDate effectiveDate = request.effectiveDate() == null ? voucherDate : request.effectiveDate();

            // Read existing journal period first so we can lock both old & new periods in canonical order
            UUID priorPeriodId = findExistingJournalPeriodId(companyId, voucherId);
            LockedPeriodRow newPeriod = findPeriodForDate(companyId, effectiveDate);
            lockAndRequireOpenPeriodsInOrder(companyId, priorPeriodId, newPeriod.id());

            // 2. Lock mc_journal FOR UPDATE
            LockedJournalRow existing = lockJournalById(companyId, voucherId);
            CompanyMasterSequenceHelper.assertExpectedVersion(
                expectedVersion,
                existing.versionNo(),
                "voucher"
            );
            if ("CANCELLED".equals(existing.status()) || "REVERSED".equals(existing.status())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-VOUCHER-IMMUTABLE-STATE",
                    "Voucher Cannot Be Altered",
                    "Vouchers in state " + existing.status() + " cannot be altered."
                );
            }

            VoucherDtos.VoucherDetailDto beforeSnapshot = loadVoucherDetail(companyId, voucherId, seq.currencyScale());
            VoucherTypeMeta priorVt = resolveVoucherType(companyId, existing.voucherTypeId(), null);

            UUID reqVtId = request.voucherTypeId() != null
                ? request.voucherTypeId()
                : ((request.voucherTypeName() == null || request.voucherTypeName().isBlank())
                    ? existing.voucherTypeId()
                    : null);
            VoucherTypeMeta vt = resolveVoucherType(companyId, reqVtId, request.voucherTypeName());
            if (vt.postsAccountingEntries() || priorVt.postsAccountingEntries()) {
                CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
            }
            HeaderSpec header = normalizeHeaderSpecForUpdate(request, vt, existing, seq.baseCurrencyCode());

            VoucherInventoryBridge invBridge = inventoryBridgeProvider.getIfAvailable();
            VoucherInventoryBridge.InventoryPreLockPlan invPreLock = invBridge != null
                ? invBridge.resolvePreLockPlan(companyId, voucherId, request.inventoryLines())
                : new VoucherInventoryBridge.InventoryPreLockPlan(Set.of(), Set.of());

            // Collect & lock all distinct ledgers across BOTH prior state and updated request
            Set<UUID> allLedgerIds = collectReferencedLedgerIds(request, beforeSnapshot);
            allLedgerIds.addAll(findPriorEntryLedgerIds(companyId, voucherId));
            allLedgerIds.addAll(invPreLock.additionalLedgerIdsToLock());
            Map<UUID, LockedLedgerMeta> lockedLedgers = lockLedgersInCanonicalOrder(companyId, allLedgerIds);

            // Check if prior entries exist — cannot demote a voucher that already has mc_entry rows to DRAFT/MEMORANDUM
            List<NetLedgerEntrySlice> activeNetSlices = findUncompensatedEntrySlices(companyId, voucherId);
            long totalHistoricalEntries = countHistoricalEntries(companyId, voucherId);
            if (totalHistoricalEntries > 0
                && ("DRAFT".equals(header.status())
                    || "APPROVED".equals(header.status())
                    || "MEMORANDUM".equals(header.postingNature())
                    || "REVERSING_JOURNAL".equals(header.postingNature())
                    || !vt.postsAccountingEntries())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-POSTED-CANNOT-DEMOTE-TO-DRAFT",
                    "Posted Voucher Cannot Be Demoted to Draft",
                    "Once GL entries exist in mc_entry, convert to OPTIONAL (Ctrl+L) or CANCEL (Alt+X) instead of DRAFT."
                );
            }

            Set<UUID> priorInventoryItems = Set.of();
            if (invBridge != null) {
                invBridge.lockJournalStockItemsAndBatches(companyId, voucherId);
                priorInventoryItems = invBridge.clearAndReverseJournalInventory(
                    companyId,
                    voucherId,
                    existing.status(),
                    existing.postingNature(),
                    priorVt.baseCategory(),
                    priorVt.movesInventory()
                );
            }

            LockedLedgerMeta partyLedgerMeta = request.partyLedgerId() == null
                ? null
                : lockedLedgers.get(request.partyLedgerId());
            VoucherInventoryBridge.PreparedInventoryContext invCtx = invBridge != null
                ? invBridge.lockAndPrepareInventory(
                    new VoucherInventoryBridge.InventoryPrepareCommand(
                        companyId,
                        voucherId,
                        voucherDate,
                        effectiveDate,
                        seq.currencyScale(),
                        vt.baseCategory(),
                        vt.postsAccountingEntries(),
                        vt.movesInventory(),
                        vt.allowZeroValuedTransactions(),
                        header.persistedView(),
                        header.postingNature(),
                        header.status(),
                        request.partyLedgerId(),
                        partyLedgerMeta != null && partyLedgerMeta.maintainBalancesBillByBill(),
                        request.accountingLedgerId(),
                        header.supplierInvoiceNo(),
                        existing.voucherNumber() != null ? existing.voucherNumber() : request.voucherNumber(),
                        invPreLock.stockItemIdsToLock(),
                        request.lines(),
                        request.inventoryLines()
                    )
                )
                : new VoucherInventoryBridge.PreparedInventoryContext(request.lines(), null, Set.of());

            // Delete existing sub-ledger allocations & presentation lines before validating new allocations
            deleteSubLedgersAndLines(companyId, voucherId);

            boolean hasInventoryLines = request.inventoryLines() != null && !request.inventoryLines().isEmpty();
            List<PreparedLine> preparedLines = prepareAndValidateLines(
                companyId,
                voucherId,
                effectiveDate,
                seq.currencyScale(),
                seq.baseCurrencyCode(),
                vt,
                header,
                request.accountingLedgerId(),
                invCtx.effectiveAccountingLines(),
                hasInventoryLines,
                lockedLedgers
            );

            BigDecimal totalDr = sumSide(preparedLines, "DR");
            BigDecimal totalCr = sumSide(preparedLines, "CR");

            // Retain existing voucher_number unless MANUAL updated or previously unnumbered
            String resolvedNumber = existing.voucherNumber();
            Long resolvedSeqNo = existing.voucherSeqNo();
            UUID resolvedSeriesId = existing.numberSeriesId();
            if ("MANUAL".equals(vt.numberingMethod())
                && request.voucherNumber() != null
                && !request.voucherNumber().isBlank()) {
                resolvedNumber = request.voucherNumber().trim();
                if (vt.preventDuplicateNumbers()) {
                    numberingService.assertUniqueManualNumberOnAlter(
                        companyId,
                        vt.id(),
                        newPeriod.fiscalYearId(),
                        resolvedNumber,
                        voucherId
                    );
                }
            } else if (resolvedNumber == null) {
                VoucherNumberingService.AllocatedVoucherNumber alloc = numberingService.allocateNumber(
                    companyId,
                    voucherId,
                    vt.id(),
                    vt.numberingMethod(),
                    vt.preventDuplicateNumbers(),
                    newPeriod.fiscalYearId(),
                    header.companyGstinId(),
                    request.voucherNumber()
                );
                resolvedSeriesId = alloc.numberSeriesId();
                resolvedNumber = alloc.voucherNumber();
                resolvedSeqNo = alloc.voucherSeqNo();
            }

            int nextBatch = existing.currentPostingBatchNo();
            if (!activeNetSlices.isEmpty()) {
                nextBatch = nextBatch + 1;
                insertCompensatingReversalEntries(companyId, voucherId, nextBatch, activeNetSlices);
            }

            boolean postActiveGl = shouldHoldActiveGl(header.status(), header.postingNature(), vt.postsAccountingEntries());
            if (postActiveGl) {
                nextBatch = nextBatch + 1;
                insertActiveGlEntries(companyId, voucherId, newPeriod.id(), effectiveDate, nextBatch, preparedLines);
            }

            long nextVersion = existing.versionNo() + 1L;
            UUID postedBy = "POSTED".equals(header.status())
                ? (existing.postedBy() != null ? existing.postedBy() : actor.principalId())
                : existing.postedBy();

            businessJdbcTemplate.update(
                """
                UPDATE mc_journal
                   SET period_id = ?,
                       fiscal_year_id = ?,
                       voucher_type_id = ?,
                       voucher_class_id = ?,
                       number_series_id = ?,
                       status = ?,
                       posting_nature = ?,
                       persisted_view = ?,
                       approval_policy = ?,
                       voucher_number = ?,
                       voucher_seq_no = ?,
                       supplier_invoice_no = ?,
                       supplier_invoice_date = ?,
                       voucher_date = ?,
                       effective_date = ?,
                       reversing_applicable_upto = ?,
                       party_ledger_id = ?,
                       accounting_ledger_id = ?,
                       place_of_supply_state = ?,
                       company_gstin_id = ?,
                       party_gstin = ?,
                       party_mailing_name = ?,
                       party_state_code = ?,
                       is_reverse_charge = ?,
                       export_type = ?,
                       currency_code = ?,
                       exchange_rate = ?,
                       total_debit = ?,
                       total_credit = ?,
                       narration = ?,
                       source_guid = ?,
                       alter_id = ?,
                       current_posting_batch_no = ?,
                       version_no = ?,
                       updated_by = ?,
                       posted_by = ?,
                       posted_at = CASE
                           WHEN ? = 'POSTED' AND posted_at IS NULL THEN clock_timestamp()
                           ELSE posted_at
                       END,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                newPeriod.id(),
                newPeriod.fiscalYearId(),
                vt.id(),
                request.voucherClassId(),
                resolvedSeriesId,
                header.status(),
                header.postingNature(),
                header.persistedView(),
                header.approvalPolicy(),
                resolvedNumber,
                resolvedSeqNo,
                header.supplierInvoiceNo(),
                request.supplierInvoiceDate(),
                voucherDate,
                effectiveDate,
                request.reversingApplicableUpto(),
                request.partyLedgerId(),
                request.accountingLedgerId(),
                header.placeOfSupplyState(),
                header.companyGstinId(),
                header.partyGstin(),
                header.partyMailingName(),
                header.partyStateCode(),
                header.isReverseCharge(),
                header.exportType(),
                header.currencyCode(),
                header.exchangeRate(),
                totalDr,
                totalCr,
                header.narration(),
                header.sourceGuid(),
                seq.alterId(),
                nextBatch,
                nextVersion,
                actor.principalId(),
                postedBy,
                header.status(),
                companyId,
                voucherId
            );

            insertLinesAndSubLedgers(companyId, voucherId, preparedLines);

            if (invBridge != null) {
                Set<UUID> mergedTouched = new TreeSet<>(Comparator.comparing(UUID::toString));
                mergedTouched.addAll(priorInventoryItems);
                mergedTouched.addAll(invCtx.touchedStockItemIds());
                VoucherInventoryBridge.PreparedInventoryContext mergedCtx =
                    new VoucherInventoryBridge.PreparedInventoryContext(
                        invCtx.effectiveAccountingLines(),
                        invCtx.preparedPayload(),
                        Set.copyOf(mergedTouched)
                    );
                invBridge.persistAndApplyInventory(
                    new VoucherInventoryBridge.InventoryPersistCommand(
                        companyId,
                        voucherId,
                        resolvedNumber,
                        effectiveDate,
                        header.status(),
                        header.postingNature(),
                        vt.baseCategory(),
                        vt.movesInventory(),
                        request.partyLedgerId(),
                        header.supplierInvoiceNo(),
                        mergedCtx
                    )
                );
            }

            VoucherDtos.VoucherDetailDto afterSnapshot = loadVoucherDetail(companyId, voucherId, seq.currencyScale());

            editLogSnapshotService.recordVersion(
                companyId,
                "VOUCHER",
                voucherId,
                afterSnapshot.masterSeqId(),
                afterSnapshot.alterId(),
                afterSnapshot.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                afterSnapshot.voucherNumber(),
                afterSnapshot.voucherDate(),
                beforeSnapshot,
                afterSnapshot
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "VOUCHER_ALTER",
                "VOUCHER",
                voucherId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "voucherNumber", afterSnapshot.voucherNumber() == null ? "" : afterSnapshot.voucherNumber(),
                    "status", afterSnapshot.status(),
                    "postingNature", afterSnapshot.postingNature(),
                    "totalDebit", afterSnapshot.totalDebit(),
                    "versionNo", afterSnapshot.versionNo()
                )
            );

            return afterSnapshot;
        });
    }

    public VoucherDtos.VoucherDetailDto approveVoucher(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId,
        String ifMatchHeader,
        VoucherDtos.ApproveVoucherRequest request
    ) {
        authorityGuard.assertActiveLease();
        rbacGuard.requireCompanyRole(actor, companyId, CompanyMasterSequenceHelper.MASTER_ADMIN_ROLES);
        Long expectedVersion = parseOptionalIfMatch(ifMatchHeader);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            UUID periodId = findExistingJournalPeriodId(companyId, voucherId);
            lockAndRequireOpenPeriodsInOrder(companyId, periodId, periodId);

            LockedJournalRow existing = lockJournalById(companyId, voucherId);
            if (expectedVersion != null) {
                CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, existing.versionNo(), "voucher");
            }
            if (!"DRAFT".equals(existing.status())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-VOUCHER-NOT-IN-DRAFT",
                    "Voucher Not Awaiting Approval",
                    "Only DRAFT vouchers can be approved (current status: " + existing.status() + ")."
                );
            }
            if (actor.principalId().equals(existing.createdBy())) {
                throw new MoneyCraftException(
                    HttpStatus.FORBIDDEN,
                    "MC-ACC-FOUR-EYES-VIOLATION",
                    "Four-Eyes Maker-Checker Violation",
                    "Creator principal cannot self-approve a voucher; a distinct approver is required."
                );
            }

            VoucherDtos.VoucherDetailDto beforeSnapshot = loadVoucherDetail(companyId, voucherId, seq.currencyScale());
            VoucherTypeMeta vt = resolveVoucherType(companyId, existing.voucherTypeId(), null);

            Set<UUID> ledgerIds = collectSnapshotLedgerIds(beforeSnapshot);
            lockLedgersInCanonicalOrder(companyId, ledgerIds);

            VoucherInventoryBridge invBridge = inventoryBridgeProvider.getIfAvailable();
            if (invBridge != null) {
                invBridge.lockJournalStockItemsAndBatches(companyId, voucherId);
            }

            boolean postImmediately = request != null && Boolean.TRUE.equals(request.postImmediately());
            String targetStatus = postImmediately ? "POSTED" : "APPROVED";
            int nextBatch = existing.currentPostingBatchNo();

            if (postImmediately && shouldHoldActiveGl(targetStatus, existing.postingNature(), vt.postsAccountingEntries())) {
                nextBatch = nextBatch + 1;
                insertActiveGlEntriesFromStoredLines(companyId, voucherId, existing.periodId(), existing.effectiveDate(), nextBatch);
            }

            long nextVersion = existing.versionNo() + 1L;
            UUID postedBy = postImmediately ? actor.principalId() : null;

            businessJdbcTemplate.update(
                """
                UPDATE mc_journal
                   SET status = ?,
                       approved_by = ?,
                       approved_at = clock_timestamp(),
                       posted_by = COALESCE(?, posted_by),
                       posted_at = CASE WHEN ?::uuid IS NOT NULL THEN clock_timestamp() ELSE posted_at END,
                       post_seq = CASE WHEN ?::uuid IS NOT NULL THEN ? ELSE post_seq END,
                       alter_id = ?,
                       current_posting_batch_no = ?,
                       version_no = ?,
                       updated_by = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                targetStatus,
                actor.principalId(),
                postedBy,
                postedBy,
                postedBy,
                seq.alterId(),
                seq.alterId(),
                nextBatch,
                nextVersion,
                actor.principalId(),
                companyId,
                voucherId
            );

            if (postImmediately && "NORMAL".equals(existing.postingNature()) && invBridge != null) {
                invBridge.activateStoredJournalInventoryOnPost(
                    companyId,
                    voucherId,
                    existing.effectiveDate(),
                    vt.baseCategory(),
                    vt.movesInventory(),
                    beforeSnapshot.partyLedgerId(),
                    existing.voucherNumber()
                );
            }

            VoucherDtos.VoucherDetailDto afterSnapshot = loadVoucherDetail(companyId, voucherId, seq.currencyScale());
            editLogSnapshotService.recordVersion(
                companyId,
                "VOUCHER",
                voucherId,
                afterSnapshot.masterSeqId(),
                afterSnapshot.alterId(),
                afterSnapshot.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                afterSnapshot.voucherNumber(),
                afterSnapshot.voucherDate(),
                beforeSnapshot,
                afterSnapshot
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "VOUCHER_APPROVE",
                "VOUCHER",
                voucherId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "voucherNumber", afterSnapshot.voucherNumber() == null ? "" : afterSnapshot.voucherNumber(),
                    "status", afterSnapshot.status(),
                    "versionNo", afterSnapshot.versionNo()
                )
            );
            return afterSnapshot;
        });
    }

    public VoucherDtos.VoucherDetailDto postVoucher(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId,
        String ifMatchHeader
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        Long expectedVersion = parseOptionalIfMatch(ifMatchHeader);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            UUID periodId = findExistingJournalPeriodId(companyId, voucherId);
            lockAndRequireOpenPeriodsInOrder(companyId, periodId, periodId);

            LockedJournalRow existing = lockJournalById(companyId, voucherId);
            if (expectedVersion != null) {
                CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, existing.versionNo(), "voucher");
            }
            if ("CANCELLED".equals(existing.status()) || "REVERSED".equals(existing.status())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-VOUCHER-IMMUTABLE-STATE",
                    "Voucher Cannot Be Posted",
                    "Voucher in state " + existing.status() + " cannot be posted."
                );
            }
            if ("POSTED".equals(existing.status()) && "NORMAL".equals(existing.postingNature())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-VOUCHER-ALREADY-POSTED",
                    "Voucher Already Posted",
                    "Voucher is already POSTED with NORMAL posting nature."
                );
            }

            if ("FOUR_EYES".equals(existing.approvalPolicy())
                && (existing.approvedBy() == null || existing.approvedBy().equals(existing.createdBy()))) {
                throw new MoneyCraftException(
                    HttpStatus.FORBIDDEN,
                    "MC-ACC-FOUR-EYES-VIOLATION",
                    "Four-Eyes Approval Required Before Posting",
                    "A distinct approver must approve this FOUR_EYES voucher before it can be posted."
                );
            }

            VoucherDtos.VoucherDetailDto beforeSnapshot = loadVoucherDetail(companyId, voucherId, seq.currencyScale());
            VoucherTypeMeta vt = resolveVoucherType(companyId, existing.voucherTypeId(), null);

            Set<UUID> ledgerIds = collectSnapshotLedgerIds(beforeSnapshot);
            Map<UUID, LockedLedgerMeta> lockedLedgers = lockLedgersInCanonicalOrder(companyId, ledgerIds);

            VoucherInventoryBridge invBridge = inventoryBridgeProvider.getIfAvailable();
            if (invBridge != null) {
                invBridge.lockJournalStockItemsAndBatches(companyId, voucherId);
            }

            String targetNature = ("MEMORANDUM".equals(vt.baseCategory()) || "REVERSING_JOURNAL".equals(vt.baseCategory()))
                ? existing.postingNature()
                : "NORMAL";

            // Verify bill-wise discipline when promoting OPTIONAL/DRAFT -> NORMAL
            if ("NORMAL".equals(targetNature)) {
                for (VoucherDtos.VoucherLineDetailDto line : beforeSnapshot.lines()) {
                    LockedLedgerMeta lm = lockedLedgers.get(line.ledgerId());
                    if (lm != null && lm.maintainBalancesBillByBill() && line.billAllocations().isEmpty()) {
                        throw new MoneyCraftException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "MC-ACC-BILL-ALLOCATION-REQUIRED",
                            "Bill-Wise Allocations Required",
                            "Ledger '" + lm.name() + "' requires bill-wise allocations before posting."
                        );
                    }
                }
            }

            int nextBatch = existing.currentPostingBatchNo();
            if (shouldHoldActiveGl("POSTED", targetNature, vt.postsAccountingEntries())) {
                nextBatch = nextBatch + 1;
                insertActiveGlEntriesFromStoredLines(
                    companyId,
                    voucherId,
                    existing.periodId(),
                    existing.effectiveDate(),
                    nextBatch
                );
            }

            long nextVersion = existing.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_journal
                   SET status = 'POSTED',
                       posting_nature = ?,
                       posted_by = ?,
                       posted_at = clock_timestamp(),
                       post_seq = ?,
                       alter_id = ?,
                       current_posting_batch_no = ?,
                       version_no = ?,
                       updated_by = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                targetNature,
                actor.principalId(),
                seq.alterId(),
                seq.alterId(),
                nextBatch,
                nextVersion,
                actor.principalId(),
                companyId,
                voucherId
            );

            if ("NORMAL".equals(targetNature) && invBridge != null) {
                invBridge.activateStoredJournalInventoryOnPost(
                    companyId,
                    voucherId,
                    existing.effectiveDate(),
                    vt.baseCategory(),
                    vt.movesInventory(),
                    beforeSnapshot.partyLedgerId(),
                    existing.voucherNumber()
                );
            }

            VoucherDtos.VoucherDetailDto afterSnapshot = loadVoucherDetail(companyId, voucherId, seq.currencyScale());
            editLogSnapshotService.recordVersion(
                companyId,
                "VOUCHER",
                voucherId,
                afterSnapshot.masterSeqId(),
                afterSnapshot.alterId(),
                afterSnapshot.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                afterSnapshot.voucherNumber(),
                afterSnapshot.voucherDate(),
                beforeSnapshot,
                afterSnapshot
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "VOUCHER_POST",
                "VOUCHER",
                voucherId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "voucherNumber", afterSnapshot.voucherNumber() == null ? "" : afterSnapshot.voucherNumber(),
                    "status", afterSnapshot.status(),
                    "versionNo", afterSnapshot.versionNo()
                )
            );
            return afterSnapshot;
        });
    }

    public VoucherDtos.VoucherDetailDto reverseVoucher(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId,
        String ifMatchHeader,
        VoucherDtos.ReverseVoucherRequest request
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        Long expectedVersion = parseOptionalIfMatch(ifMatchHeader);

        return businessTxTemplate.execute(tx -> {
            // Allocate master/alter sequences for both altering original & creating reversal
            CompanyMasterSequenceHelper.AllocatedMasterSeq createSeq =
                sequenceHelper.allocateForCreate(companyId, false);
            CompanyMasterSequenceHelper.AllocatedMasterSeq alterSeq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            UUID origPeriodId = findExistingJournalPeriodId(companyId, voucherId);
            LockedJournalRow origpeek = readJournalRowUnlocked(companyId, voucherId);
            LocalDate reversalDate = (request != null && request.reversalDate() != null)
                ? request.reversalDate()
                : origpeek.effectiveDate();
            LockedPeriodRow reversalPeriod = findPeriodForDate(companyId, reversalDate);

            lockAndRequireOpenPeriodsInOrder(companyId, origPeriodId, reversalPeriod.id());

            LockedJournalRow existing = lockJournalById(companyId, voucherId);
            if (expectedVersion != null) {
                CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, existing.versionNo(), "voucher");
            }
            if (!"POSTED".equals(existing.status()) || !"NORMAL".equals(existing.postingNature())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-REVERSE-REQUIRES-POSTED",
                    "Reversal Requires Posted Normal Voucher",
                    "Only POSTED vouchers with NORMAL posting nature can be reversed."
                );
            }

            VoucherDtos.VoucherDetailDto origBefore = loadVoucherDetail(companyId, voucherId, createSeq.currencyScale());
            VoucherTypeMeta vt = resolveVoucherType(companyId, existing.voucherTypeId(), null);

            Set<UUID> ledgerIds = collectSnapshotLedgerIds(origBefore);
            lockLedgersInCanonicalOrder(companyId, ledgerIds);

            VoucherInventoryBridge invBridge = inventoryBridgeProvider.getIfAvailable();
            if (invBridge != null) {
                invBridge.lockJournalStockItemsAndBatches(companyId, voucherId);
            }

            UUID reversalJournalId = UUID.randomUUID();
            VoucherNumberingService.AllocatedVoucherNumber revNum = numberingService.allocateNumber(
                companyId,
                reversalJournalId,
                vt.id(),
                "MANUAL".equals(vt.numberingMethod()) ? "NONE" : vt.numberingMethod(),
                vt.preventDuplicateNumbers(),
                reversalPeriod.fiscalYearId(),
                origBefore.companyGstinId(),
                "MANUAL".equals(vt.numberingMethod())
                    ? truncate32("REV-" + (existing.voucherNumber() == null ? "1" : existing.voucherNumber()))
                    : null
            );

            // 1. Mark original journal status = REVERSED
            businessJdbcTemplate.update(
                """
                UPDATE mc_journal
                   SET status = 'REVERSED',
                       alter_id = ?,
                       version_no = version_no + 1,
                       updated_by = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                alterSeq.alterId(),
                actor.principalId(),
                companyId,
                voucherId
            );
            VoucherDtos.VoucherDetailDto origAfter = loadVoucherDetail(companyId, voucherId, createSeq.currencyScale());
            editLogSnapshotService.recordVersion(
                companyId,
                "VOUCHER",
                voucherId,
                origAfter.masterSeqId(),
                origAfter.alterId(),
                origAfter.versionNo(),
                "ALTER",
                actor.principalId(),
                actor.username(),
                origAfter.voucherNumber(),
                origAfter.voucherDate(),
                origBefore,
                origAfter
            );

            // 2. Build inverted lines for compensating reversal journal
            List<PreparedLine> revLines = invertLinesForReversal(origBefore, createSeq.currencyScale());
            BigDecimal totalDr = sumSide(revLines, "DR");
            BigDecimal totalCr = sumSide(revLines, "CR");
            String revNarration = (request != null && request.narration() != null && !request.narration().isBlank())
                ? request.narration().trim()
                : "Reversal of " + vt.name() + " #" + (existing.voucherNumber() == null ? existing.id() : existing.voucherNumber());

            int revBatchNo = vt.postsAccountingEntries() ? 1 : 0;
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_journal (
                    id, company_id, period_id, fiscal_year_id, voucher_type_id,
                    voucher_class_id, number_series_id, status, posting_nature,
                    persisted_view, approval_policy, voucher_number, voucher_seq_no, post_seq,
                    voucher_date, effective_date, party_ledger_id, accounting_ledger_id,
                    company_gstin_id, currency_code, exchange_rate,
                    total_debit, total_credit, narration, reversal_of_journal_id, origin,
                    master_seq_id, alter_id, current_posting_batch_no, version_no,
                    created_by, updated_by, posted_by, posted_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, 'POSTED', 'NORMAL',
                    ?, 'SINGLE_STEP', ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?, ?, 'LIVE',
                    ?, ?, ?, 1,
                    ?, ?, ?, clock_timestamp()
                )
                """,
                reversalJournalId,
                companyId,
                reversalPeriod.id(),
                reversalPeriod.fiscalYearId(),
                vt.id(),
                origBefore.voucherClassId(),
                revNum.numberSeriesId(),
                origBefore.persistedView(),
                revNum.voucherNumber(),
                revNum.voucherSeqNo(),
                createSeq.alterId(),
                reversalDate,
                reversalDate,
                origBefore.partyLedgerId(),
                origBefore.accountingLedgerId(),
                origBefore.companyGstinId(),
                origBefore.currencyCode(),
                new BigDecimal(origBefore.exchangeRate()),
                totalDr,
                totalCr,
                revNarration,
                voucherId,
                createSeq.masterSeqId(),
                createSeq.alterId(),
                revBatchNo,
                actor.principalId(),
                actor.principalId(),
                actor.principalId()
            );

            insertLinesAndSubLedgers(companyId, reversalJournalId, revLines);
            if (vt.postsAccountingEntries()) {
                insertActiveGlEntries(companyId, reversalJournalId, reversalPeriod.id(), reversalDate, 1, revLines);
            }
            if (invBridge != null) {
                invBridge.cloneInvertedInventoryOnReverse(
                    companyId,
                    voucherId,
                    reversalJournalId,
                    reversalDate,
                    vt.baseCategory(),
                    vt.movesInventory(),
                    origBefore.partyLedgerId()
                );
            }

            VoucherDtos.VoucherDetailDto revCreated = loadVoucherDetail(
                companyId,
                reversalJournalId,
                createSeq.currencyScale()
            );
            editLogSnapshotService.recordVersion(
                companyId,
                "VOUCHER",
                reversalJournalId,
                revCreated.masterSeqId(),
                revCreated.alterId(),
                revCreated.versionNo(),
                "CREATE",
                actor.principalId(),
                actor.username(),
                revCreated.voucherNumber(),
                revCreated.voucherDate(),
                null,
                revCreated
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "VOUCHER_REVERSE",
                "VOUCHER",
                voucherId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "originalVoucherId", voucherId.toString(),
                    "reversalVoucherId", reversalJournalId.toString(),
                    "reversalVoucherNumber", revCreated.voucherNumber() == null ? "" : revCreated.voucherNumber()
                )
            );
            return revCreated;
        });
    }

    public VoucherDtos.VoucherDetailDto cancelVoucher(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId,
        String ifMatchHeader,
        VoucherDtos.CancelVoucherRequest request
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        Long expectedVersion = parseOptionalIfMatch(ifMatchHeader);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            UUID periodId = findExistingJournalPeriodId(companyId, voucherId);
            lockAndRequireOpenPeriodsInOrder(companyId, periodId, periodId);

            LockedJournalRow existing = lockJournalById(companyId, voucherId);
            if (expectedVersion != null) {
                CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, existing.versionNo(), "voucher");
            }
            if ("CANCELLED".equals(existing.status())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-VOUCHER-ALREADY-CANCELLED",
                    "Voucher Already Cancelled",
                    "Voucher " + voucherId + " is already CANCELLED."
                );
            }
            if ("REVERSED".equals(existing.status())) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-VOUCHER-IMMUTABLE-STATE",
                    "Reversed Voucher Cannot Be Cancelled",
                    "Voucher has already been REVERSED via a compensating reversal journal."
                );
            }

            VoucherDtos.VoucherDetailDto beforeSnapshot = loadVoucherDetail(companyId, voucherId, seq.currencyScale());
            VoucherTypeMeta vt = resolveVoucherType(companyId, existing.voucherTypeId(), null);

            Set<UUID> ledgerIds = collectSnapshotLedgerIds(beforeSnapshot);
            ledgerIds.addAll(findPriorEntryLedgerIds(companyId, voucherId));
            lockLedgersInCanonicalOrder(companyId, ledgerIds);

            VoucherInventoryBridge invBridge = inventoryBridgeProvider.getIfAvailable();
            Set<UUID> touchedInventoryItems = Set.of();
            if (invBridge != null) {
                invBridge.lockJournalStockItemsAndBatches(companyId, voucherId);
                touchedInventoryItems = invBridge.clearAndReverseJournalInventory(
                    companyId,
                    voucherId,
                    existing.status(),
                    existing.postingNature(),
                    vt.baseCategory(),
                    vt.movesInventory()
                );
            }

            // Release all open bill/cost/bank allocations and clear commercial lines
            deleteSubLedgersAndLines(companyId, voucherId);

            // Insert compensating reversal mc_entry rows so every ledger nets to 0.00
            List<NetLedgerEntrySlice> activeNetSlices = findUncompensatedEntrySlices(companyId, voucherId);
            int nextBatch = existing.currentPostingBatchNo();
            if (!activeNetSlices.isEmpty()) {
                nextBatch = nextBatch + 1;
                insertCompensatingReversalEntries(companyId, voucherId, nextBatch, activeNetSlices);
            }

            String reason = request == null ? null : request.reason();
            numberingService.recordCancelledForGstr1(
                companyId,
                existing.numberSeriesId(),
                existing.voucherTypeId(),
                voucherId,
                existing.voucherNumber(),
                existing.voucherSeqNo(),
                reason
            );

            String preservedNarration = beforeSnapshot.narration();
            if ((preservedNarration == null || preservedNarration.isBlank())
                && reason != null && !reason.isBlank()) {
                preservedNarration = reason.trim();
            }

            long nextVersion = existing.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_journal
                   SET status = 'CANCELLED',
                       total_debit = 0,
                       total_credit = 0,
                       narration = ?,
                       cancelled_by = ?,
                       cancelled_at = clock_timestamp(),
                       alter_id = ?,
                       current_posting_batch_no = ?,
                       version_no = ?,
                       updated_by = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                preservedNarration == null ? "" : preservedNarration,
                actor.principalId(),
                seq.alterId(),
                nextBatch,
                nextVersion,
                actor.principalId(),
                companyId,
                voucherId
            );

            if (invBridge != null && !touchedInventoryItems.isEmpty()) {
                invBridge.syncCostLayers(companyId, touchedInventoryItems);
            }

            VoucherDtos.VoucherDetailDto afterSnapshot = loadVoucherDetail(companyId, voucherId, seq.currencyScale());
            editLogSnapshotService.recordVersion(
                companyId,
                "VOUCHER",
                voucherId,
                afterSnapshot.masterSeqId(),
                afterSnapshot.alterId(),
                afterSnapshot.versionNo(),
                "CANCEL",
                actor.principalId(),
                actor.username(),
                afterSnapshot.voucherNumber(),
                afterSnapshot.voucherDate(),
                beforeSnapshot,
                afterSnapshot
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "VOUCHER_CANCEL",
                "VOUCHER",
                voucherId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "voucherNumber", afterSnapshot.voucherNumber() == null ? "" : afterSnapshot.voucherNumber(),
                    "status", afterSnapshot.status(),
                    "versionNo", afterSnapshot.versionNo()
                )
            );
            return afterSnapshot;
        });
    }

    public CursorPage<VoucherDtos.VoucherSummaryDto> listVouchers(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        UUID voucherTypeId,
        String voucherCategory,
        UUID ledgerId,
        String status,
        Boolean includeOptional,
        Boolean includeCancelled,
        Integer rawLimit,
        String cursor
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int pageSize = CompanyMasterSequenceHelper.normalizePageLimit(rawLimit, 50, 200);
        CompanyMasterSequenceHelper.CompanyCurrencyMeta companyMeta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        int scale = companyMeta.currencyScale();

        boolean showOptional = includeOptional == null || includeOptional;
        boolean showCancelled = includeCancelled == null || includeCancelled;
        String normCat = voucherCategory == null ? "" : voucherCategory.trim().toUpperCase(Locale.ROOT);
        String normStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);

        String filterFingerprint = (fromDate == null ? "" : fromDate)
            + "|" + (toDate == null ? "" : toDate)
            + "|" + (voucherTypeId == null ? "" : voucherTypeId)
            + "|" + normCat
            + "|" + (ledgerId == null ? "" : ledgerId)
            + "|" + normStatus
            + "|" + showOptional
            + "|" + showCancelled;
        String filterHash = JcsCanonicalizer.toHex(
            jcsCanonicalizer.sha256(filterFingerprint.getBytes(StandardCharsets.UTF_8))
        ).substring(0, 16);
        String endpointId = "GET /companies/vouchers";

        StringBuilder where = new StringBuilder("j.company_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(companyId);

        if (fromDate != null) {
            where.append(" AND j.voucher_date >= ?");
            params.add(fromDate);
        }
        if (toDate != null) {
            where.append(" AND j.voucher_date <= ?");
            params.add(toDate);
        }
        if (voucherTypeId != null) {
            where.append(" AND j.voucher_type_id = ?");
            params.add(voucherTypeId);
        }
        if (!normCat.isEmpty()) {
            where.append(" AND vt.base_category = ?");
            params.add(normCat);
        }
        if (ledgerId != null) {
            where.append(
                """
                 AND (
                     j.party_ledger_id = ?
                     OR j.accounting_ledger_id = ?
                     OR EXISTS (
                         SELECT 1
                           FROM mc_journal_line fl
                          WHERE fl.company_id = j.company_id
                            AND fl.journal_id = j.id
                            AND fl.ledger_id = ?
                     )
                 )
                """
            );
            params.add(ledgerId);
            params.add(ledgerId);
            params.add(ledgerId);
        }
        if (!normStatus.isEmpty()) {
            where.append(" AND j.status = ?");
            params.add(normStatus);
        } else if (!showCancelled) {
            where.append(" AND j.status <> 'CANCELLED'");
        }
        if (!showOptional) {
            where.append(" AND j.posting_nature <> 'OPTIONAL'");
        }

        if (cursor != null && !cursor.isBlank()) {
            CursorPayload decoded = cursorCodec.decodeAndVerify(
                cursor,
                principal.principalId(),
                companyId,
                endpointId,
                filterHash
            );
            DayBookSortTuple sortTuple = decodeDayBookSortTuple(decoded.lastSortValue());
            where.append(
                """
                 AND (
                     j.voucher_date,
                     vt.name,
                     COALESCE(j.voucher_seq_no, 9223372036854775807),
                     COALESCE(j.voucher_number, ''),
                     j.id
                 ) > (?::date, ?, ?, ?, ?::uuid)
                """
            );
            params.add(sortTuple.voucherDate());
            params.add(sortTuple.voucherTypeName());
            params.add(sortTuple.seqOrMax());
            params.add(sortTuple.voucherNumber());
            params.add(decoded.lastEntityId());
        }

        params.add(pageSize + 1);
        String sql = """
            SELECT j.id, j.voucher_date, j.effective_date,
                   j.voucher_type_id, vt.name AS voucher_type_name, vt.base_category,
                   j.voucher_number, j.voucher_seq_no, j.status, j.posting_nature, j.persisted_view,
                   COALESCE(pl.name, fl.first_ledger_name, fil.first_item_name, '') AS primary_particulars,
                   j.total_debit, j.total_credit, j.narration, j.version_no, j.alter_id
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
              LEFT JOIN LATERAL (
                  SELECT l.name AS first_ledger_name
                    FROM mc_journal_line jl
                    JOIN mc_ledger l
                      ON l.company_id = jl.company_id AND l.id = jl.ledger_id
                   WHERE jl.company_id = j.company_id
                     AND jl.journal_id = j.id
                   ORDER BY jl.line_no ASC
                   LIMIT 1
              ) fl ON true
              LEFT JOIN LATERAL (
                  SELECT si.name AS first_item_name
                    FROM mc_inventory_allocation ia
                    JOIN mc_stock_item si
                      ON si.company_id = ia.company_id AND si.id = ia.stock_item_id
                   WHERE ia.company_id = j.company_id
                     AND ia.journal_id = j.id
                   ORDER BY ia.line_no ASC
                   LIMIT 1
              ) fil ON true
             WHERE %s
             ORDER BY j.voucher_date ASC,
                      vt.name ASC,
                      COALESCE(j.voucher_seq_no, 9223372036854775807) ASC,
                      COALESCE(j.voucher_number, '') ASC,
                      j.id ASC
             LIMIT ?
            """.formatted(where.toString());

        List<SummaryWithSeq> fetched = businessJdbcTemplate.query(
            sql,
            (rs, rowNum) -> {
                String st = rs.getString("status");
                String particulars = "CANCELLED".equals(st)
                    ? "Cancelled"
                    : rs.getString("primary_particulars");
                VoucherDtos.VoucherSummaryDto dto = new VoucherDtos.VoucherSummaryDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("voucher_date", LocalDate.class),
                    rs.getObject("effective_date", LocalDate.class),
                    rs.getObject("voucher_type_id", UUID.class),
                    rs.getString("voucher_type_name"),
                    rs.getString("base_category"),
                    rs.getString("voucher_number"),
                    st,
                    rs.getString("posting_nature"),
                    rs.getString("persisted_view"),
                    particulars,
                    ExactMoneyValidator.formatExactScale(rs.getBigDecimal("total_debit"), scale),
                    ExactMoneyValidator.formatExactScale(rs.getBigDecimal("total_credit"), scale),
                    rs.getString("narration"),
                    rs.getLong("version_no"),
                    rs.getLong("alter_id")
                );
                Long rawSeq = rs.getObject("voucher_seq_no", Long.class);
                return new SummaryWithSeq(dto, rawSeq == null ? Long.MAX_VALUE : rawSeq);
            },
            params.toArray()
        );

        boolean hasMore = fetched.size() > pageSize;
        List<SummaryWithSeq> pageSlice = hasMore ? fetched.subList(0, pageSize) : fetched;
        List<VoucherDtos.VoucherSummaryDto> items = pageSlice.stream()
            .map(SummaryWithSeq::summary)
            .toList();

        String nextCursor = null;
        if (hasMore && !pageSlice.isEmpty()) {
            SummaryWithSeq last = pageSlice.getLast();
            VoucherDtos.VoucherSummaryDto lastDto = last.summary();
            String encodedSortTuple = encodeDayBookSortTuple(
                new DayBookSortTuple(
                    lastDto.voucherDate(),
                    lastDto.voucherTypeName(),
                    last.seqOrMax(),
                    lastDto.voucherNumber() == null ? "" : lastDto.voucherNumber()
                )
            );
            nextCursor = cursorCodec.encode(
                principal.principalId(),
                companyId,
                endpointId,
                filterHash,
                encodedSortTuple,
                lastDto.id(),
                Instant.now().plus(Duration.ofHours(4))
            );
        }

        return new CursorPage<>(items, nextCursor, hasMore);
    }

    public List<VoucherDtos.OpenBillDto> listOpenBills(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID ledgerId,
        UUID excludeVoucherId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        CompanyMasterSequenceHelper.CompanyCurrencyMeta companyMeta = sequenceHelper.readCompanyCurrencyMeta(companyId);
        int scale = companyMeta.currencyScale();

        if (excludeVoucherId == null) {
            return businessJdbcTemplate.query(
                """
                SELECT bill_name, bill_date, due_date, total_dr, total_cr,
                       pending_amount, pending_side, unadjusted_forex_delta
                  FROM vw_mc_open_bill_balances
                 WHERE company_id = ?
                   AND ledger_id = ?
                 ORDER BY bill_date ASC NULLS LAST, bill_name ASC
                """,
                (rs, rowNum) -> mapOpenBillRow(rs, scale),
                companyId,
                ledgerId
            );
        }

        return businessJdbcTemplate.query(
            """
            SELECT
                ba.bill_name,
                MIN(j.voucher_date) FILTER (WHERE ba.allocation_type IN ('NEW_REF', 'ADVANCE')) AS bill_date,
                MAX(ba.due_date) FILTER (WHERE ba.allocation_type IN ('NEW_REF', 'ADVANCE')) AS due_date,
                COALESCE(SUM(CASE WHEN ba.side = 'DR' THEN ba.amount ELSE 0::numeric END), 0::numeric) AS total_dr,
                COALESCE(SUM(CASE WHEN ba.side = 'CR' THEN ba.amount ELSE 0::numeric END), 0::numeric) AS total_cr,
                ABS(
                    COALESCE(SUM(CASE WHEN ba.side = 'DR' THEN ba.amount ELSE -ba.amount END), 0::numeric)
                ) AS pending_amount,
                CASE
                    WHEN SUM(CASE WHEN ba.side = 'DR' THEN ba.amount ELSE -ba.amount END) >= 0 THEN 'DR'
                    ELSE 'CR'
                END AS pending_side,
                COALESCE(SUM(ba.unadjusted_forex_delta) FILTER (WHERE ba.forex_swept_journal_id IS NULL), 0::numeric)
                    AS unadjusted_forex_delta
              FROM mc_bill_allocation ba
              JOIN mc_journal j
                ON j.company_id = ba.company_id AND j.id = ba.journal_id
             WHERE ba.company_id = ?
               AND ba.ledger_id = ?
               AND j.id <> ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND ba.allocation_type IN ('NEW_REF', 'AGST_REF', 'ADVANCE')
             GROUP BY ba.company_id, ba.ledger_id, ba.bill_name
            HAVING SUM(CASE WHEN ba.side = 'DR' THEN ba.amount ELSE -ba.amount END) <> 0
             ORDER BY bill_date ASC NULLS LAST, ba.bill_name ASC
            """,
            (rs, rowNum) -> mapOpenBillRow(rs, scale),
            companyId,
            ledgerId,
            excludeVoucherId
        );
    }

    public VoucherDtos.NextVoucherNumberResponse previewNextVoucherNumber(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID voucherTypeId,
        String voucherTypeName,
        LocalDate voucherDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        VoucherTypeMeta vt = resolveVoucherType(companyId, voucherTypeId, voucherTypeName);
        return numberingService.previewNextNumber(
            companyId,
            vt.id(),
            vt.name(),
            vt.numberingMethod(),
            voucherDate
        );
    }

    // -------------------------------------------------------------------------
    // Internal Validation, Sub-Ledger, Lock Ordering & Persistence Helpers
    // -------------------------------------------------------------------------

    private VoucherTypeMeta resolveVoucherType(
        UUID companyId,
        UUID voucherTypeId,
        String voucherTypeName
    ) {
        if (voucherTypeId == null && (voucherTypeName == null || voucherTypeName.isBlank())) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-TYPE-REQUIRED",
                "Voucher Type Required",
                "Supply either 'voucherTypeId' or 'voucherTypeName'."
            );
        }

        List<VoucherTypeMeta> rows = voucherTypeId != null
            ? businessJdbcTemplate.query(
                """
                SELECT id, name, base_category, posts_accounting_entries, moves_inventory,
                       default_entry_mode, numbering_method, approval_policy,
                       prevent_duplicate_numbers, allow_zero_valued_transactions,
                       make_optional_by_default, active
                  FROM mc_voucher_type
                 WHERE company_id = ? AND id = ?
                """,
                this::mapVoucherTypeMeta,
                companyId,
                voucherTypeId
            )
            : businessJdbcTemplate.query(
                """
                SELECT id, name, base_category, posts_accounting_entries, moves_inventory,
                       default_entry_mode, numbering_method, approval_policy,
                       prevent_duplicate_numbers, allow_zero_valued_transactions,
                       make_optional_by_default, active
                  FROM mc_voucher_type
                 WHERE company_id = ? AND lower(name) = lower(?)
                """,
                this::mapVoucherTypeMeta,
                companyId,
                voucherTypeName.trim()
            );

        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-VOUCHER-TYPE-NOT-FOUND",
                "Voucher Type Not Found",
                "Requested voucher type does not exist in company."
            );
        }
        VoucherTypeMeta vt = rows.getFirst();
        if (!vt.active()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-TYPE-INACTIVE",
                "Voucher Type Inactive",
                "Voucher type '" + vt.name() + "' is inactive."
            );
        }
        return vt;
    }

    private VoucherTypeMeta mapVoucherTypeMeta(ResultSet rs, int rowNum) throws SQLException {
        return new VoucherTypeMeta(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("base_category"),
            rs.getBoolean("posts_accounting_entries"),
            rs.getBoolean("moves_inventory"),
            rs.getString("default_entry_mode"),
            rs.getString("numbering_method"),
            rs.getString("approval_policy"),
            rs.getBoolean("prevent_duplicate_numbers"),
            rs.getBoolean("allow_zero_valued_transactions"),
            rs.getBoolean("make_optional_by_default"),
            rs.getBoolean("active")
        );
    }

    private HeaderSpec normalizeHeaderSpec(
        VoucherDtos.UpsertVoucherRequest req,
        VoucherTypeMeta vt,
        String baseCurrencyCode
    ) {
        String persistedView = normalizePersistedView(req.persistedView(), vt.defaultEntryMode());
        String postingNature = normalizePostingNature(req.postingNature(), vt);

        String action = req.action() == null || req.action().isBlank()
            ? "POST"
            : req.action().trim().toUpperCase(Locale.ROOT);
        if (!VALID_ACTIONS.contains(action)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-ACTION-INVALID",
                "Invalid Voucher Action",
                "action must be SAVE_DRAFT, SUBMIT_FOR_APPROVAL, or POST."
            );
        }

        String status;
        if ("SAVE_DRAFT".equals(action) || "SUBMIT_FOR_APPROVAL".equals(action)) {
            status = "DRAFT";
        } else if ("FOUR_EYES".equals(vt.approvalPolicy()) && "NORMAL".equals(postingNature)) {
            status = "DRAFT";
        } else {
            status = "POSTED";
        }

        return buildCommonHeaderFields(req, vt.approvalPolicy(), persistedView, postingNature, status, baseCurrencyCode);
    }

    private HeaderSpec normalizeHeaderSpecForUpdate(
        VoucherDtos.UpsertVoucherRequest req,
        VoucherTypeMeta vt,
        LockedJournalRow existing,
        String baseCurrencyCode
    ) {
        String persistedView = normalizePersistedView(
            req.persistedView() != null ? req.persistedView() : existing.persistedView(),
            vt.defaultEntryMode()
        );
        String postingNature = req.postingNature() != null && !req.postingNature().isBlank()
            ? normalizePostingNature(req.postingNature(), vt)
            : existing.postingNature();

        String status;
        if (req.action() != null && !req.action().isBlank()) {
            String action = req.action().trim().toUpperCase(Locale.ROOT);
            if (!VALID_ACTIONS.contains(action)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-VOUCHER-ACTION-INVALID",
                    "Invalid Voucher Action",
                    "action must be SAVE_DRAFT, SUBMIT_FOR_APPROVAL, or POST."
                );
            }
            if ("SAVE_DRAFT".equals(action) || "SUBMIT_FOR_APPROVAL".equals(action)) {
                status = "DRAFT";
            } else if ("FOUR_EYES".equals(vt.approvalPolicy())
                && "NORMAL".equals(postingNature)
                && existing.approvedBy() == null) {
                status = "DRAFT";
            } else {
                status = "POSTED";
            }
        } else {
            status = existing.status();
        }

        return buildCommonHeaderFields(req, vt.approvalPolicy(), persistedView, postingNature, status, baseCurrencyCode);
    }

    private HeaderSpec buildCommonHeaderFields(
        VoucherDtos.UpsertVoucherRequest req,
        String approvalPolicy,
        String persistedView,
        String postingNature,
        String status,
        String baseCurrencyCode
    ) {
        String ccy = req.currencyCode() == null || req.currencyCode().isBlank()
            ? baseCurrencyCode
            : req.currencyCode().trim().toUpperCase(Locale.ROOT);
        if (!ccy.matches("^[A-Z]{3}$")) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-CURRENCY-INVALID",
                "Invalid Currency ISO Code",
                "currencyCode must be a 3-letter uppercase ISO 4217 code."
            );
        }

        BigDecimal exchangeRate = (req.exchangeRate() == null || req.exchangeRate().isBlank())
            ? BigDecimal.ONE
            : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                req.exchangeRate(),
                6,
                BigDecimal.ZERO,
                null,
                true,
                "exchangeRate"
            );

        String partyGstin = null;
        if (req.partyGstin() != null && !req.partyGstin().isBlank()) {
            partyGstin = ExactMoneyValidator.validatePartyGstin(req.partyGstin(), req.partyStateCode());
        }

        String exportType = null;
        if (req.exportType() != null && !req.exportType().isBlank()) {
            exportType = req.exportType().trim().toUpperCase(Locale.ROOT);
            if (!VALID_EXPORT_TYPES.contains(exportType)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-EXPORT-TYPE-INVALID",
                    "Invalid Export Type",
                    "exportType must be one of " + VALID_EXPORT_TYPES + "."
                );
            }
        }

        return new HeaderSpec(
            status,
            postingNature,
            persistedView,
            approvalPolicy,
            trimOrNull(req.supplierInvoiceNo(), 64),
            trimOrNull(req.placeOfSupplyState(), 4),
            req.companyGstinId(),
            partyGstin,
            trimOrNull(req.partyMailingName(), 200),
            trimOrNull(req.partyStateCode(), 4),
            Boolean.TRUE.equals(req.isReverseCharge()),
            exportType,
            ccy,
            exchangeRate,
            req.narration() == null ? "" : req.narration().trim(),
            trimOrNull(req.sourceGuid(), 128)
        );
    }

    private static String normalizePersistedView(String rawView, String defaultEntryMode) {
        String candidate = (rawView == null || rawView.isBlank())
            ? defaultEntryMode
            : rawView.trim().toUpperCase(Locale.ROOT);
        if ("DOUBLE_ENTRY".equals(candidate)) {
            candidate = "AS_VOUCHER";
        }
        if (!VALID_PERSISTED_VIEWS.contains(candidate)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-PERSISTED-VIEW-INVALID",
                "Invalid Voucher Persisted View",
                "persistedView must be SINGLE_ENTRY, AS_VOUCHER, ACCOUNTING_INVOICE, or ITEM_INVOICE."
            );
        }
        return candidate;
    }

    private static String normalizePostingNature(String rawNature, VoucherTypeMeta vt) {
        if ("MEMORANDUM".equals(vt.baseCategory())) {
            return "MEMORANDUM";
        }
        if ("REVERSING_JOURNAL".equals(vt.baseCategory())) {
            return "REVERSING_JOURNAL";
        }
        if (rawNature == null || rawNature.isBlank()) {
            return vt.makeOptionalByDefault() ? "OPTIONAL" : "NORMAL";
        }
        String norm = rawNature.trim().toUpperCase(Locale.ROOT);
        if (!VALID_POSTING_NATURES.contains(norm)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-POSTING-NATURE-INVALID",
                "Invalid Posting Nature",
                "postingNature must be NORMAL, OPTIONAL, MEMORANDUM, or REVERSING_JOURNAL."
            );
        }
        return norm;
    }

    private List<PreparedLine> prepareAndValidateLines(
        UUID companyId,
        UUID excludeJournalId,
        LocalDate effectiveDate,
        int currencyScale,
        String baseCurrencyCode,
        VoucherTypeMeta vt,
        HeaderSpec header,
        UUID headerAccountingLedgerId,
        List<VoucherDtos.VoucherLineRequest> rawLines,
        boolean hasInventoryLines,
        Map<UUID, LockedLedgerMeta> lockedLedgers
    ) {
        if ((rawLines == null || rawLines.isEmpty()) && !vt.postsAccountingEntries() && hasInventoryLines) {
            return List.of();
        }
        if (rawLines == null || rawLines.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-MIN-TWO-ENTRIES",
                "Voucher Lines Required",
                "At least one detail line (or two double-entry lines) is required."
            );
        }

        UUID primaryCostCategoryId = null;
        Map<LedgerBillKey, BigDecimal> inFlightBillAdjustments = new HashMap<>();
        List<PreparedLine> prepared = new ArrayList<>(rawLines.size() + 1);

        int autoLineNo = 1;
        for (int idx = 0; idx < rawLines.size(); idx++) {
            VoucherDtos.VoucherLineRequest lr = rawLines.get(idx);
            if (lr == null || lr.ledgerId() == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-LEDGER-REQUIRED",
                    "Line Ledger Required",
                    "lines[" + idx + "].ledgerId is required."
                );
            }
            LockedLedgerMeta ledger = lockedLedgers.get(lr.ledgerId());
            if (ledger == null || !ledger.active()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-LEDGER-NOT-FOUND",
                    "Ledger Not Found or Inactive",
                    "Ledger " + lr.ledgerId() + " does not exist or is inactive in company."
                );
            }

            if ("CONTRA".equals(vt.baseCategory()) && !isCashOrBankLedger(companyId, ledger)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-CONTRA-CASH-BANK-ONLY",
                    "Contra Allows Only Cash/Bank Ledgers",
                    "Contra voucher line ledger '" + ledger.name() + "' must belong to Cash-in-Hand or Bank Accounts."
                );
            }

            String role = lr.lineRole() == null || lr.lineRole().isBlank()
                ? "LEDGER"
                : lr.lineRole().trim().toUpperCase(Locale.ROOT);
            if (!VALID_LINE_ROLES.contains(role)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-LINE-ROLE-INVALID",
                    "Invalid Line Role",
                    "lines[" + idx + "].lineRole must be one of " + VALID_LINE_ROLES + "."
                );
            }

            String side = resolveLineSide(lr.side(), vt.baseCategory(), header.persistedView(), idx);
            ForexAndBaseAmount lineMoney = parseLineMoney(
                lr.amount(),
                lr.forexCurrencyCode(),
                lr.forexAmount(),
                lr.forexRate(),
                currencyScale,
                baseCurrencyCode,
                "lines[" + idx + "]"
            );

            if (!vt.allowZeroValuedTransactions()
                && !"ROUND_OFF".equals(role)
                && lineMoney.baseAmount().signum() <= 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-ZERO-AMOUNT-FORBIDDEN",
                    "Line Amount Must Be Positive",
                    "lines[" + idx + "].amount must be strictly positive."
                );
            }

            BigDecimal ratePct = (lr.ratePct() == null || lr.ratePct().isBlank())
                ? null
                : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    lr.ratePct(),
                    6,
                    BigDecimal.ZERO,
                    new BigDecimal("1000"),
                    false,
                    "lines[" + idx + "].ratePct"
                );
            BigDecimal computedTax = (lr.computedTaxAmount() == null || lr.computedTaxAmount().isBlank())
                ? null
                : ExactMoneyValidator.parseAndValidateExactScale(
                    lr.computedTaxAmount(),
                    currencyScale,
                    "lines[" + idx + "].computedTaxAmount"
                );

            List<PreparedBillAlloc> billAllocs = prepareBillAllocations(
                companyId,
                excludeJournalId,
                effectiveDate,
                currencyScale,
                baseCurrencyCode,
                header.postingNature(),
                ledger,
                side,
                lineMoney,
                lr.billAllocations(),
                inFlightBillAdjustments,
                idx
            );

            if (lr.costAllocations() != null && !lr.costAllocations().isEmpty() && primaryCostCategoryId == null) {
                primaryCostCategoryId = findPrimaryCostCategoryId(companyId);
            }
            List<PreparedCostAlloc> costAllocs = prepareCostAllocations(
                companyId,
                currencyScale,
                primaryCostCategoryId,
                side,
                lineMoney.baseAmount(),
                lr.costAllocations(),
                idx
            );

            List<PreparedBankAlloc> bankAllocs = prepareBankAllocations(
                companyId,
                currencyScale,
                ledger,
                headerAccountingLedgerId,
                side,
                lineMoney.baseAmount(),
                lr.bankAllocations(),
                lockedLedgers,
                idx
            );

            int lineNo = autoLineNo++;
            prepared.add(
                new PreparedLine(
                    UUID.randomUUID(),
                    lineNo,
                    role,
                    ledger.id(),
                    side,
                    lineMoney.baseAmount(),
                    lineMoney.forexCurrencyCode(),
                    lineMoney.forexAmount(),
                    lineMoney.forexRate(),
                    ratePct,
                    computedTax,
                    Boolean.TRUE.equals(lr.isGstOverridden()),
                    trimOrNull(lr.gstOverrideReason(), 255),
                    lr.lineNarration() == null ? "" : lr.lineNarration().trim(),
                    billAllocs,
                    costAllocs,
                    bankAllocs
                )
            );
        }

        // Auto-synthesize header accountingLedgerId counterparty line in SINGLE_ENTRY when needed
        BigDecimal sumDr = sumSide(prepared, "DR");
        BigDecimal sumCr = sumSide(prepared, "CR");
        if ("SINGLE_ENTRY".equals(header.persistedView()) && sumDr.compareTo(sumCr) != 0) {
            if (headerAccountingLedgerId == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-UNBALANCED-JOURNAL",
                    "Single-Entry Header Ledger Required",
                    "Single-entry voucher lines are unbalanced and no accountingLedgerId header was provided."
                );
            }
            LockedLedgerMeta headerLedger = lockedLedgers.get(headerAccountingLedgerId);
            if (headerLedger == null || !headerLedger.active()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-LEDGER-NOT-FOUND",
                    "Header Accounting Ledger Not Found",
                    "Header accountingLedgerId " + headerAccountingLedgerId + " does not exist or is inactive."
                );
            }
            if ("CONTRA".equals(vt.baseCategory()) && !isCashOrBankLedger(companyId, headerLedger)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-CONTRA-CASH-BANK-ONLY",
                    "Contra Allows Only Cash/Bank Ledgers",
                    "Contra header ledger '" + headerLedger.name() + "' must belong to Cash-in-Hand or Bank Accounts."
                );
            }
            BigDecimal diff = sumDr.subtract(sumCr);
            String counterpartySide = diff.signum() > 0 ? "CR" : "DR";
            BigDecimal counterpartyAmt = diff.abs();
            prepared.add(
                new PreparedLine(
                    UUID.randomUUID(),
                    autoLineNo,
                    "ACCOUNTING",
                    headerLedger.id(),
                    counterpartySide,
                    counterpartyAmt,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    "",
                    List.of(),
                    List.of(),
                    List.of()
                )
            );
            sumDr = sumSide(prepared, "DR");
            sumCr = sumSide(prepared, "CR");
        }

        long nonZeroLineCount = prepared.stream().filter(l -> l.amount().signum() > 0).count();
        if (prepared.size() < 2 || (!vt.allowZeroValuedTransactions() && nonZeroLineCount < 2)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-MIN-TWO-ENTRIES",
                "Minimum Two Accounting Lines Required",
                "Double-entry accounting requires at least two balanced DR/CR lines."
            );
        }

        if (sumDr.compareTo(sumCr) != 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-UNBALANCED-JOURNAL",
                "Unbalanced Double-Entry Voucher",
                "Total DR (" + ExactMoneyValidator.formatExactScale(sumDr, currencyScale)
                    + ") does not equal total CR (" + ExactMoneyValidator.formatExactScale(sumCr, currencyScale) + ")."
            );
        }

        if (!vt.allowZeroValuedTransactions() && sumDr.signum() <= 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-ZERO-SUM-JOURNAL",
                "Zero-Valued Voucher Forbidden",
                "Voucher total debit/credit must be strictly greater than zero."
            );
        }

        return List.copyOf(prepared);
    }

    private Set<UUID> collectReferencedLedgerIds(
        VoucherDtos.UpsertVoucherRequest request,
        VoucherDtos.VoucherDetailDto priorSnapshot
    ) {
        Set<UUID> sortedIds = new TreeSet<>(Comparator.comparing(UUID::toString));
        if (request.partyLedgerId() != null) {
            sortedIds.add(request.partyLedgerId());
        }
        if (request.accountingLedgerId() != null) {
            sortedIds.add(request.accountingLedgerId());
        }
        if (request.lines() != null) {
            for (VoucherDtos.VoucherLineRequest lr : request.lines()) {
                if (lr != null) {
                    if (lr.ledgerId() != null) {
                        sortedIds.add(lr.ledgerId());
                    }
                    if (lr.bankAllocations() != null) {
                        for (VoucherDtos.BankAllocationDto ba : lr.bankAllocations()) {
                            if (ba != null && ba.bankLedgerId() != null) {
                                sortedIds.add(ba.bankLedgerId());
                            }
                        }
                    }
                }
            }
        }
        if (priorSnapshot != null) {
            sortedIds.addAll(collectSnapshotLedgerIds(priorSnapshot));
        }
        return sortedIds;
    }

    private static Set<UUID> collectSnapshotLedgerIds(VoucherDtos.VoucherDetailDto snapshot) {
        Set<UUID> sorted = new TreeSet<>(Comparator.comparing(UUID::toString));
        if (snapshot.partyLedgerId() != null) {
            sorted.add(snapshot.partyLedgerId());
        }
        if (snapshot.accountingLedgerId() != null) {
            sorted.add(snapshot.accountingLedgerId());
        }
        if (snapshot.lines() != null) {
            for (VoucherDtos.VoucherLineDetailDto line : snapshot.lines()) {
                if (line.ledgerId() != null) {
                    sorted.add(line.ledgerId());
                }
                for (VoucherDtos.BankAllocationDto ba : line.bankAllocations()) {
                    if (ba.bankLedgerId() != null) {
                        sorted.add(ba.bankLedgerId());
                    }
                }
            }
        }
        return sorted;
    }

    private Map<UUID, LockedLedgerMeta> lockLedgersInCanonicalOrder(UUID companyId, Set<UUID> ledgerIds) {
        List<UUID> ordered = new ArrayList<>(ledgerIds);
        ordered.sort(Comparator.comparing(UUID::toString));

        Map<UUID, LockedLedgerMeta> map = new LinkedHashMap<>();
        for (UUID ledgerId : ordered) {
            List<LockedLedgerMeta> rows = businessJdbcTemplate.query(
                """
                SELECT l.id, l.name, l.group_id, l.maintain_balances_bill_by_bill,
                       l.default_credit_period_days, l.cost_centres_applicable, l.active
                  FROM mc_ledger l
                 WHERE l.company_id = ? AND l.id = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> new LockedLedgerMeta(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getObject("group_id", UUID.class),
                    rs.getBoolean("maintain_balances_bill_by_bill"),
                    rs.getObject("default_credit_period_days", Integer.class),
                    rs.getBoolean("cost_centres_applicable"),
                    rs.getBoolean("active")
                ),
                companyId,
                ledgerId
            );
            if (!rows.isEmpty()) {
                map.put(ledgerId, rows.getFirst());
            }
        }
        return map;
    }

    private boolean isCashOrBankLedger(UUID companyId, LockedLedgerMeta ledger) {
        Boolean allowed = businessJdbcTemplate.queryForObject(
            """
            WITH RECURSIVE grp_chain AS (
                SELECT id, parent_group_id, lower(name) AS lname
                  FROM mc_account_group
                 WHERE company_id = ? AND id = ?
                UNION ALL
                SELECT p.id, p.parent_group_id, lower(p.name) AS lname
                  FROM mc_account_group p
                  JOIN grp_chain c ON p.id = c.parent_group_id
                 WHERE p.company_id = ?
            )
            SELECT EXISTS (
                SELECT 1 FROM grp_chain
                 WHERE lname IN ('cash-in-hand', 'bank accounts', 'bank od a/c', 'bank occ a/c')
            )
            """,
            Boolean.class,
            companyId,
            ledger.groupId(),
            companyId
        );
        return Boolean.TRUE.equals(allowed) || CONTRA_ALLOWED_GROUP_NAMES.contains(ledger.name().toLowerCase(Locale.ROOT));
    }

    private static String resolveLineSide(
        String rawSide,
        String baseCategory,
        String persistedView,
        int lineIdx
    ) {
        if (rawSide != null && !rawSide.isBlank()) {
            String norm = rawSide.trim().toUpperCase(Locale.ROOT);
            if (!"DR".equals(norm) && !"CR".equals(norm)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-SIDE-INVALID",
                    "Invalid Accounting Side",
                    "lines[" + lineIdx + "].side must be DR or CR."
                );
            }
            return norm;
        }
        if ("SINGLE_ENTRY".equals(persistedView)) {
            if ("PAYMENT".equals(baseCategory)) {
                return "DR";
            }
            if ("RECEIPT".equals(baseCategory)) {
                return "CR";
            }
            if ("CONTRA".equals(baseCategory)) {
                return "CR";
            }
        }
        throw new MoneyCraftException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MC-ACC-SIDE-REQUIRED",
            "Accounting Side Required",
            "lines[" + lineIdx + "].side ('DR' or 'CR') is required."
        );
    }

    private static ForexAndBaseAmount parseLineMoney(
        String rawBaseAmount,
        String rawForexCcy,
        String rawForexAmount,
        String rawForexRate,
        int currencyScale,
        String baseCurrencyCode,
        String fieldPrefix
    ) {
        String forexCcy = null;
        BigDecimal forexAmt = null;
        BigDecimal forexRate = null;

        boolean hasForex = (rawForexAmount != null && !rawForexAmount.isBlank())
            || (rawForexRate != null && !rawForexRate.isBlank())
            || (rawForexCcy != null && !rawForexCcy.isBlank()
                && !rawForexCcy.trim().equalsIgnoreCase(baseCurrencyCode));

        if (hasForex) {
            forexCcy = (rawForexCcy == null || rawForexCcy.isBlank())
                ? baseCurrencyCode
                : rawForexCcy.trim().toUpperCase(Locale.ROOT);
            if (!forexCcy.matches("^[A-Z]{3}$")) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-CURRENCY-INVALID",
                    "Invalid Forex Currency Code",
                    fieldPrefix + ".forexCurrencyCode must be a 3-letter uppercase ISO code."
                );
            }
            if (rawForexAmount != null && !rawForexAmount.isBlank()) {
                forexAmt = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    rawForexAmount,
                    6,
                    BigDecimal.ZERO,
                    null,
                    false,
                    fieldPrefix + ".forexAmount"
                );
            }
            if (rawForexRate != null && !rawForexRate.isBlank()) {
                forexRate = ExactMoneyValidator.parseAndValidateBoundedDecimal(
                    rawForexRate,
                    6,
                    BigDecimal.ZERO,
                    null,
                    true,
                    fieldPrefix + ".forexRate"
                );
            }
        }

        BigDecimal computedFromForex = (forexAmt != null && forexRate != null)
            ? forexAmt.multiply(forexRate).setScale(currencyScale, RoundingMode.HALF_UP)
            : null;

        BigDecimal baseAmount;
        if (rawBaseAmount == null || rawBaseAmount.isBlank()) {
            if (computedFromForex == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-AMOUNT-REQUIRED",
                    "Line Amount Required",
                    fieldPrefix + ".amount is required."
                );
            }
            baseAmount = computedFromForex;
        } else {
            baseAmount = ExactMoneyValidator.parseAndValidateExactScale(
                rawBaseAmount,
                currencyScale,
                fieldPrefix + ".amount"
            );
            if (computedFromForex != null && baseAmount.compareTo(computedFromForex) != 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-FOREX-CONVERSION-MISMATCH",
                    "Forex Conversion Mismatch",
                    fieldPrefix + ".amount (" + baseAmount + ") does not match round(forexAmount * forexRate) ("
                        + computedFromForex + ")."
                );
            }
        }

        return new ForexAndBaseAmount(baseAmount, forexCcy, forexAmt, forexRate);
    }

    private List<PreparedBillAlloc> prepareBillAllocations(
        UUID companyId,
        UUID excludeJournalId,
        LocalDate effectiveDate,
        int currencyScale,
        String baseCurrencyCode,
        String postingNature,
        LockedLedgerMeta ledger,
        String lineSide,
        ForexAndBaseAmount lineMoney,
        List<VoucherDtos.BillAllocationDto> rawAllocs,
        Map<LedgerBillKey, BigDecimal> inFlightBillAdjustments,
        int lineIdx
    ) {
        if (rawAllocs == null || rawAllocs.isEmpty()) {
            if (ledger.maintainBalancesBillByBill() && "NORMAL".equals(postingNature)
                && lineMoney.baseAmount().signum() > 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-BILL-ALLOCATION-REQUIRED",
                    "Bill-Wise Allocations Required",
                    "Ledger '" + ledger.name() + "' has bill-by-bill tracking enabled; supply billAllocations."
                );
            }
            return List.of();
        }

        List<PreparedBillAlloc> result = new ArrayList<>(rawAllocs.size());
        BigDecimal signedBaseSum = BigDecimal.ZERO;

        for (int bIdx = 0; bIdx < rawAllocs.size(); bIdx++) {
            VoucherDtos.BillAllocationDto ba = rawAllocs.get(bIdx);
            String prefix = "lines[" + lineIdx + "].billAllocations[" + bIdx + "]";
            if (ba == null || ba.allocationType() == null || ba.allocationType().isBlank()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-BILL-ALLOCATION-TYPE-INVALID",
                    "Bill Allocation Type Required",
                    prefix + ".allocationType must be NEW_REF, AGST_REF, ADVANCE, or ON_ACCOUNT."
                );
            }
            String allocType = ba.allocationType().trim().toUpperCase(Locale.ROOT);
            if (!VALID_BILL_ALLOC_TYPES.contains(allocType)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-BILL-ALLOCATION-TYPE-INVALID",
                    "Invalid Bill Allocation Type",
                    prefix + ".allocationType must be one of " + VALID_BILL_ALLOC_TYPES + "."
                );
            }

            String billName = (ba.billName() == null || ba.billName().isBlank())
                ? ("ON_ACCOUNT".equals(allocType) ? "On Account" : null)
                : ba.billName().trim();
            if (billName == null || billName.length() > 120) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-BILL-NAME-REQUIRED",
                    "Bill Reference Name Required",
                    prefix + ".billName must be between 1 and 120 characters."
                );
            }

            String allocSide = (ba.side() == null || ba.side().isBlank())
                ? lineSide
                : ba.side().trim().toUpperCase(Locale.ROOT);
            if (!"DR".equals(allocSide) && !"CR".equals(allocSide)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-SIDE-INVALID",
                    "Invalid Bill Allocation Side",
                    prefix + ".side must be DR or CR."
                );
            }

            String effFxCcy = ba.forexCurrencyCode() != null && !ba.forexCurrencyCode().isBlank()
                ? ba.forexCurrencyCode()
                : (rawAllocs.size() == 1 ? lineMoney.forexCurrencyCode() : null);
            String effFxAmt = ba.forexAmount() != null && !ba.forexAmount().isBlank()
                ? ba.forexAmount()
                : (rawAllocs.size() == 1 && lineMoney.forexAmount() != null
                    ? lineMoney.forexAmount().toPlainString()
                    : null);
            String effFxRate = ba.forexRate() != null && !ba.forexRate().isBlank()
                ? ba.forexRate()
                : (rawAllocs.size() == 1 && lineMoney.forexRate() != null
                    ? lineMoney.forexRate().toPlainString()
                    : null);

            ForexAndBaseAmount allocMoney = parseLineMoney(
                ba.amount(),
                effFxCcy,
                effFxAmt,
                effFxRate,
                currencyScale,
                baseCurrencyCode,
                prefix
            );
            if (allocMoney.baseAmount().signum() <= 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-ZERO-AMOUNT-FORBIDDEN",
                    "Bill Allocation Amount Must Be Positive",
                    prefix + ".amount must be strictly positive."
                );
            }

            Integer creditDays = ba.creditPeriodDays();
            LocalDate dueDate = ba.dueDate();
            if ("NEW_REF".equals(allocType) && dueDate == null) {
                if (creditDays == null && ledger.defaultCreditPeriodDays() != null) {
                    creditDays = ledger.defaultCreditPeriodDays();
                }
                if (creditDays != null && creditDays >= 0) {
                    dueDate = effectiveDate.plusDays(creditDays);
                }
            }

            boolean allowOver = Boolean.TRUE.equals(ba.allowOverSettlement());
            BigDecimal unadjustedForexDelta = BigDecimal.ZERO.setScale(currencyScale, RoundingMode.UNNECESSARY);

            if ("AGST_REF".equals(allocType) && "NORMAL".equals(postingNature)) {
                unadjustedForexDelta = validateAgstRefAndComputeForexDelta(
                    companyId,
                    excludeJournalId,
                    currencyScale,
                    baseCurrencyCode,
                    ledger.id(),
                    billName,
                    allocSide,
                    allocMoney,
                    allowOver,
                    inFlightBillAdjustments
                );
            }

            if (allocSide.equals(lineSide)) {
                signedBaseSum = signedBaseSum.add(allocMoney.baseAmount());
            } else {
                signedBaseSum = signedBaseSum.subtract(allocMoney.baseAmount());
            }

            result.add(
                new PreparedBillAlloc(
                    UUID.randomUUID(),
                    allocType,
                    billName,
                    creditDays,
                    dueDate,
                    allocSide,
                    allocMoney.baseAmount(),
                    allocMoney.forexCurrencyCode(),
                    allocMoney.forexAmount(),
                    allocMoney.forexRate(),
                    unadjustedForexDelta,
                    allowOver
                )
            );
        }

        if (signedBaseSum.compareTo(lineMoney.baseAmount()) != 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-BILL-ALLOCATION-MISMATCH",
                "Bill-Wise Allocations Sum Mismatch",
                "Signed sum of billAllocations (" + ExactMoneyValidator.formatExactScale(signedBaseSum, currencyScale)
                    + ") does not equal line amount ("
                    + ExactMoneyValidator.formatExactScale(lineMoney.baseAmount(), currencyScale) + ")."
            );
        }

        return List.copyOf(result);
    }

    private BigDecimal validateAgstRefAndComputeForexDelta(
        UUID companyId,
        UUID excludeJournalId,
        int currencyScale,
        String baseCurrencyCode,
        UUID ledgerId,
        String billName,
        String settleSide,
        ForexAndBaseAmount allocMoney,
        boolean allowOverSettlement,
        Map<LedgerBillKey, BigDecimal> inFlightBillAdjustments
    ) {
        OpenBillForexSnapshot openSnap = queryOpenBillForexSnapshot(companyId, ledgerId, billName, excludeJournalId);
        LedgerBillKey billKey = new LedgerBillKey(ledgerId, billName);

        boolean foreignBill = openSnap.originForexSum().signum() > 0
            && allocMoney.forexAmount() != null
            && allocMoney.forexCurrencyCode() != null
            && !allocMoney.forexCurrencyCode().equalsIgnoreCase(baseCurrencyCode);

        if (foreignBill) {
            BigDecimal priorInFlightFx = inFlightBillAdjustments.getOrDefault(billKey, BigDecimal.ZERO);
            BigDecimal netOpenForex = "CR".equals(settleSide)
                ? openSnap.signedForexDrMinusCr().subtract(priorInFlightFx)
                : openSnap.signedForexDrMinusCr().negate().subtract(priorInFlightFx);

            if (!allowOverSettlement && allocMoney.forexAmount().compareTo(netOpenForex) > 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-BILL-OVERSETTLED",
                    "Foreign Bill Over-Settlement Rejected",
                    "Settlement of " + allocMoney.forexAmount() + " " + allocMoney.forexCurrencyCode()
                        + " exceeds open bill '" + billName + "' pending balance of " + netOpenForex + "."
                );
            }
            inFlightBillAdjustments.put(billKey, priorInFlightFx.add(allocMoney.forexAmount()));

            BigDecimal historicalProportionalBase = openSnap.originBaseSum()
                .multiply(allocMoney.forexAmount())
                .divide(openSnap.originForexSum(), currencyScale, RoundingMode.HALF_UP);

            return allocMoney.baseAmount().subtract(historicalProportionalBase);
        }

        BigDecimal priorInFlightBase = inFlightBillAdjustments.getOrDefault(billKey, BigDecimal.ZERO);
        BigDecimal netOpenBase = "CR".equals(settleSide)
            ? openSnap.signedBaseDrMinusCr().subtract(priorInFlightBase)
            : openSnap.signedBaseDrMinusCr().negate().subtract(priorInFlightBase);

        if (!allowOverSettlement && allocMoney.baseAmount().compareTo(netOpenBase) > 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-BILL-OVERSETTLED",
                "Bill Over-Settlement Rejected",
                "Settlement amount " + ExactMoneyValidator.formatExactScale(allocMoney.baseAmount(), currencyScale)
                    + " exceeds open bill '" + billName + "' pending balance of "
                    + ExactMoneyValidator.formatExactScale(netOpenBase.max(BigDecimal.ZERO), currencyScale) + "."
            );
        }
        inFlightBillAdjustments.put(billKey, priorInFlightBase.add(allocMoney.baseAmount()));
        return BigDecimal.ZERO.setScale(currencyScale, RoundingMode.UNNECESSARY);
    }

    private OpenBillForexSnapshot queryOpenBillForexSnapshot(
        UUID companyId,
        UUID ledgerId,
        String billName,
        UUID excludeJournalId
    ) {
        OpenBillForexSnapshot snap = businessJdbcTemplate.queryForObject(
            """
            SELECT
                COALESCE(SUM(CASE WHEN ba.side = 'DR' THEN ba.amount ELSE -ba.amount END), 0::numeric)
                    AS signed_base_dr_minus_cr,
                COALESCE(SUM(CASE
                    WHEN ba.forex_amount IS NOT NULL AND ba.side = 'DR' THEN ba.forex_amount
                    WHEN ba.forex_amount IS NOT NULL AND ba.side = 'CR' THEN -ba.forex_amount
                    ELSE 0::numeric
                END), 0::numeric) AS signed_forex_dr_minus_cr,
                COALESCE(SUM(ba.amount) FILTER (WHERE ba.allocation_type IN ('NEW_REF', 'ADVANCE')), 0::numeric)
                    AS origin_base_sum,
                COALESCE(SUM(ba.forex_amount) FILTER (
                    WHERE ba.allocation_type IN ('NEW_REF', 'ADVANCE') AND ba.forex_amount IS NOT NULL
                ), 0::numeric) AS origin_forex_sum
              FROM mc_bill_allocation ba
              JOIN mc_journal j
                ON j.company_id = ba.company_id AND j.id = ba.journal_id
             WHERE ba.company_id = ?
               AND ba.ledger_id = ?
               AND ba.bill_name = ?
               AND (?::uuid IS NULL OR j.id <> ?::uuid)
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND ba.allocation_type IN ('NEW_REF', 'AGST_REF', 'ADVANCE')
            """,
            (rs, rowNum) -> new OpenBillForexSnapshot(
                rs.getBigDecimal("signed_base_dr_minus_cr"),
                rs.getBigDecimal("signed_forex_dr_minus_cr"),
                rs.getBigDecimal("origin_base_sum"),
                rs.getBigDecimal("origin_forex_sum")
            ),
            companyId,
            ledgerId,
            billName,
            excludeJournalId,
            excludeJournalId
        );
        return snap == null
            ? new OpenBillForexSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
            : snap;
    }

    private List<PreparedCostAlloc> prepareCostAllocations(
        UUID companyId,
        int currencyScale,
        UUID primaryCostCategoryId,
        String lineSide,
        BigDecimal lineAmount,
        List<VoucherDtos.CostAllocationDto> rawAllocs,
        int lineIdx
    ) {
        if (rawAllocs == null || rawAllocs.isEmpty()) {
            return List.of();
        }
        List<PreparedCostAlloc> result = new ArrayList<>(rawAllocs.size());
        Map<UUID, BigDecimal> categorySignedSums = new LinkedHashMap<>();

        for (int cIdx = 0; cIdx < rawAllocs.size(); cIdx++) {
            VoucherDtos.CostAllocationDto ca = rawAllocs.get(cIdx);
            String prefix = "lines[" + lineIdx + "].costAllocations[" + cIdx + "]";
            if (ca == null || ca.costCentreId() == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-COST-CENTRE-REQUIRED",
                    "Cost Centre Required",
                    prefix + ".costCentreId is required."
                );
            }
            UUID centreCatId = findCostCentreCategoryId(companyId, ca.costCentreId());
            UUID effectiveCatId = ca.costCategoryId() != null
                ? ca.costCategoryId()
                : (centreCatId != null ? centreCatId : primaryCostCategoryId);
            if (!Objects.equals(effectiveCatId, centreCatId)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-COST-CENTRE-CAT-MISMATCH",
                    "Cost Centre Category Mismatch",
                    prefix + ".costCentreId does not belong to specified costCategoryId."
                );
            }

            String side = (ca.side() == null || ca.side().isBlank())
                ? lineSide
                : ca.side().trim().toUpperCase(Locale.ROOT);
            if (!"DR".equals(side) && !"CR".equals(side)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-SIDE-INVALID",
                    "Invalid Cost Allocation Side",
                    prefix + ".side must be DR or CR."
                );
            }
            BigDecimal amt = ExactMoneyValidator.parseAndValidateExactScale(
                ca.amount(),
                currencyScale,
                prefix + ".amount"
            );
            if (amt.signum() <= 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-ZERO-AMOUNT-FORBIDDEN",
                    "Cost Allocation Amount Must Be Positive",
                    prefix + ".amount must be strictly positive."
                );
            }

            BigDecimal prev = categorySignedSums.getOrDefault(effectiveCatId, BigDecimal.ZERO);
            categorySignedSums.put(effectiveCatId, side.equals(lineSide) ? prev.add(amt) : prev.subtract(amt));

            result.add(new PreparedCostAlloc(UUID.randomUUID(), effectiveCatId, ca.costCentreId(), side, amt));
        }

        for (Map.Entry<UUID, BigDecimal> entry : categorySignedSums.entrySet()) {
            if (entry.getValue().compareTo(lineAmount) != 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-COST-ALLOCATION-MISMATCH",
                    "Cost Centre Allocations Sum Mismatch",
                    "Cost allocations for category " + entry.getKey() + " sum to "
                        + ExactMoneyValidator.formatExactScale(entry.getValue(), currencyScale)
                        + " instead of line amount "
                        + ExactMoneyValidator.formatExactScale(lineAmount, currencyScale) + "."
                );
            }
        }
        return List.copyOf(result);
    }

    private List<PreparedBankAlloc> prepareBankAllocations(
        UUID companyId,
        int currencyScale,
        LockedLedgerMeta lineLedger,
        UUID headerAccountingLedgerId,
        String lineSide,
        BigDecimal lineAmount,
        List<VoucherDtos.BankAllocationDto> rawAllocs,
        Map<UUID, LockedLedgerMeta> lockedLedgers,
        int lineIdx
    ) {
        if (rawAllocs == null || rawAllocs.isEmpty()) {
            return List.of();
        }
        List<PreparedBankAlloc> result = new ArrayList<>(rawAllocs.size());
        UUID defaultBankLedgerId = lineLedger.id();
        String defaultBankSide = lineSide;
        if (headerAccountingLedgerId != null
            && !lineLedger.id().equals(headerAccountingLedgerId)
            && !isCashOrBankLedger(companyId, lineLedger)) {
            defaultBankLedgerId = headerAccountingLedgerId;
            defaultBankSide = "DR".equals(lineSide) ? "CR" : "DR";
        }

        BigDecimal signedAgainstDefaultSide = BigDecimal.ZERO;
        for (int kIdx = 0; kIdx < rawAllocs.size(); kIdx++) {
            VoucherDtos.BankAllocationDto ba = rawAllocs.get(kIdx);
            String prefix = "lines[" + lineIdx + "].bankAllocations[" + kIdx + "]";
            if (ba == null) {
                continue;
            }
            UUID bankLedgerId = ba.bankLedgerId() == null ? defaultBankLedgerId : ba.bankLedgerId();
            if (!lockedLedgers.containsKey(bankLedgerId)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-LEDGER-NOT-FOUND",
                    "Bank Ledger Not Found",
                    prefix + ".bankLedgerId does not exist in company."
                );
            }
            String txnType = (ba.transactionType() == null || ba.transactionType().isBlank())
                ? "CHEQUE"
                : ba.transactionType().trim().toUpperCase(Locale.ROOT);
            if (!VALID_BANK_TXN_TYPES.contains(txnType)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-BANK-TXN-TYPE-INVALID",
                    "Invalid Bank Transaction Type",
                    prefix + ".transactionType must be one of " + VALID_BANK_TXN_TYPES + "."
                );
            }
            String expectedSideForBank = bankLedgerId.equals(lineLedger.id())
                ? lineSide
                : ("DR".equals(lineSide) ? "CR" : "DR");
            String side = (ba.side() == null || ba.side().isBlank())
                ? (ba.bankLedgerId() == null ? defaultBankSide : expectedSideForBank)
                : ba.side().trim().toUpperCase(Locale.ROOT);
            if (!"DR".equals(side) && !"CR".equals(side)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-SIDE-INVALID",
                    "Invalid Bank Allocation Side",
                    prefix + ".side must be DR or CR."
                );
            }
            BigDecimal amt = (ba.amount() == null || ba.amount().isBlank()) && rawAllocs.size() == 1
                ? lineAmount
                : ExactMoneyValidator.parseAndValidateExactScale(ba.amount(), currencyScale, prefix + ".amount");
            if (amt.signum() <= 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-ZERO-AMOUNT-FORBIDDEN",
                    "Bank Allocation Amount Must Be Positive",
                    prefix + ".amount must be strictly positive."
                );
            }

            String refSide = result.isEmpty() ? side : result.getFirst().side();
            signedAgainstDefaultSide = side.equals(refSide)
                ? signedAgainstDefaultSide.add(amt)
                : signedAgainstDefaultSide.subtract(amt);
            result.add(
                new PreparedBankAlloc(
                    UUID.randomUUID(),
                    bankLedgerId,
                    txnType,
                    trimOrNull(ba.instrumentNo(), 64),
                    ba.instrumentDate(),
                    ba.bankersDate(),
                    trimOrNull(ba.favouringName(), 200),
                    trimOrNull(ba.transferModeRef(), 80),
                    side,
                    amt
                )
            );
        }

        if (!result.isEmpty() && signedAgainstDefaultSide.abs().compareTo(lineAmount) != 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-BANK-ALLOCATION-MISMATCH",
                "Bank Allocations Sum Mismatch",
                "Bank allocations sum to "
                    + ExactMoneyValidator.formatExactScale(signedAgainstDefaultSide.abs(), currencyScale)
                    + " instead of line amount " + ExactMoneyValidator.formatExactScale(lineAmount, currencyScale) + "."
            );
        }
        return List.copyOf(result);
    }

    private void insertLinesAndSubLedgers(UUID companyId, UUID journalId, List<PreparedLine> lines) {
        for (PreparedLine line : lines) {
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_journal_line (
                    id, company_id, journal_id, line_no, line_role, ledger_id,
                    side, amount, forex_currency_code, forex_amount, forex_rate,
                    rate_pct, computed_tax_amount, is_gst_overridden, gst_override_reason, line_narration
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                line.id(),
                companyId,
                journalId,
                line.lineNo(),
                line.lineRole(),
                line.ledgerId(),
                line.side(),
                line.amount(),
                line.forexCurrencyCode(),
                line.forexAmount(),
                line.forexRate(),
                line.ratePct(),
                line.computedTaxAmount(),
                line.isGstOverridden(),
                line.gstOverrideReason(),
                line.lineNarration()
            );

            for (PreparedBillAlloc ba : line.billAllocations()) {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_bill_allocation (
                        id, company_id, journal_id, journal_line_id, ledger_id,
                        allocation_type, bill_name, credit_period_days, due_date,
                        side, amount, forex_currency_code, forex_amount, forex_rate,
                        unadjusted_forex_delta, allow_over_settlement
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    ba.id(),
                    companyId,
                    journalId,
                    line.id(),
                    line.ledgerId(),
                    ba.allocationType(),
                    ba.billName(),
                    ba.creditPeriodDays(),
                    ba.dueDate(),
                    ba.side(),
                    ba.amount(),
                    ba.forexCurrencyCode(),
                    ba.forexAmount(),
                    ba.forexRate(),
                    ba.unadjustedForexDelta(),
                    ba.allowOverSettlement()
                );
            }

            for (PreparedCostAlloc ca : line.costAllocations()) {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_cost_allocation (
                        id, company_id, journal_id, journal_line_id, ledger_id,
                        cost_category_id, cost_centre_id, side, amount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    ca.id(),
                    companyId,
                    journalId,
                    line.id(),
                    line.ledgerId(),
                    ca.costCategoryId(),
                    ca.costCentreId(),
                    ca.side(),
                    ca.amount()
                );
            }

            for (PreparedBankAlloc bka : line.bankAllocations()) {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_bank_allocation (
                        id, company_id, journal_id, journal_line_id, bank_ledger_id,
                        transaction_type, instrument_no, instrument_date, bankers_date,
                        favouring_name, transfer_mode_ref, side, amount, reconciliation_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNRECONCILED')
                    """,
                    bka.id(),
                    companyId,
                    journalId,
                    line.id(),
                    bka.bankLedgerId(),
                    bka.transactionType(),
                    bka.instrumentNo(),
                    bka.instrumentDate(),
                    bka.bankersDate(),
                    bka.favouringName(),
                    bka.transferModeRef(),
                    bka.side(),
                    bka.amount()
                );
            }
        }
    }

    /**
     * Inserts immutable mc_entry rows with journal_line_id = NULL so future voucher alterations
     * can cleanly replace mc_journal_line while preserving strict append-only GL history.
     */
    private void insertActiveGlEntries(
        UUID companyId,
        UUID journalId,
        UUID periodId,
        LocalDate entryDate,
        int postingBatchNo,
        List<PreparedLine> lines
    ) {
        for (PreparedLine line : lines) {
            if (line.amount().signum() <= 0) {
                continue;
            }
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_entry (
                    id, company_id, journal_id, journal_line_id, period_id, entry_date,
                    ledger_id, side, amount, base_currency_amount,
                    forex_currency_code, forex_amount, forex_rate,
                    posting_batch_no, is_reversal
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)
                """,
                UUID.randomUUID(),
                companyId,
                journalId,
                periodId,
                entryDate,
                line.ledgerId(),
                line.side(),
                line.amount(),
                line.amount(),
                line.forexCurrencyCode(),
                line.forexAmount(),
                line.forexRate(),
                postingBatchNo
            );
        }
    }

    private void insertActiveGlEntriesFromStoredLines(
        UUID companyId,
        UUID journalId,
        UUID periodId,
        LocalDate entryDate,
        int postingBatchNo
    ) {
        List<StoredLineSlice> stored = businessJdbcTemplate.query(
            """
            SELECT ledger_id, side, amount, forex_currency_code, forex_amount, forex_rate
              FROM mc_journal_line
             WHERE company_id = ? AND journal_id = ? AND amount > 0
             ORDER BY line_no ASC
            """,
            (rs, rowNum) -> new StoredLineSlice(
                rs.getObject("ledger_id", UUID.class),
                rs.getString("side"),
                rs.getBigDecimal("amount"),
                rs.getString("forex_currency_code"),
                rs.getBigDecimal("forex_amount"),
                rs.getBigDecimal("forex_rate")
            ),
            companyId,
            journalId
        );
        for (StoredLineSlice s : stored) {
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_entry (
                    id, company_id, journal_id, journal_line_id, period_id, entry_date,
                    ledger_id, side, amount, base_currency_amount,
                    forex_currency_code, forex_amount, forex_rate,
                    posting_batch_no, is_reversal
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)
                """,
                UUID.randomUUID(),
                companyId,
                journalId,
                periodId,
                entryDate,
                s.ledgerId(),
                s.side(),
                s.amount(),
                s.amount(),
                s.forexCurrencyCode(),
                s.forexAmount(),
                s.forexRate(),
                postingBatchNo
            );
        }
    }

    private List<NetLedgerEntrySlice> findUncompensatedEntrySlices(UUID companyId, UUID journalId) {
        return businessJdbcTemplate.query(
            """
            SELECT period_id, entry_date, ledger_id,
                   SUM(CASE WHEN side = 'DR' THEN amount ELSE -amount END) AS signed_net
              FROM mc_entry
             WHERE company_id = ? AND journal_id = ?
             GROUP BY period_id, entry_date, ledger_id
            HAVING SUM(CASE WHEN side = 'DR' THEN amount ELSE -amount END) <> 0
             ORDER BY period_id::text ASC, entry_date ASC, ledger_id::text ASC
            """,
            (rs, rowNum) -> new NetLedgerEntrySlice(
                rs.getObject("period_id", UUID.class),
                rs.getObject("entry_date", LocalDate.class),
                rs.getObject("ledger_id", UUID.class),
                rs.getBigDecimal("signed_net")
            ),
            companyId,
            journalId
        );
    }

    private void insertCompensatingReversalEntries(
        UUID companyId,
        UUID journalId,
        int reversalBatchNo,
        List<NetLedgerEntrySlice> slices
    ) {
        for (NetLedgerEntrySlice slice : slices) {
            String oppositeSide = slice.signedNet().signum() > 0 ? "CR" : "DR";
            BigDecimal absAmt = slice.signedNet().abs();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_entry (
                    id, company_id, journal_id, journal_line_id, period_id, entry_date,
                    ledger_id, side, amount, base_currency_amount,
                    posting_batch_no, is_reversal
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, true)
                """,
                UUID.randomUUID(),
                companyId,
                journalId,
                slice.periodId(),
                slice.entryDate(),
                slice.ledgerId(),
                oppositeSide,
                absAmt,
                absAmt,
                reversalBatchNo
            );
        }
    }

    private void deleteSubLedgersAndLines(UUID companyId, UUID journalId) {
        businessJdbcTemplate.update(
            "DELETE FROM mc_bill_allocation WHERE company_id = ? AND journal_id = ?",
            companyId,
            journalId
        );
        businessJdbcTemplate.update(
            "DELETE FROM mc_cost_allocation WHERE company_id = ? AND journal_id = ?",
            companyId,
            journalId
        );
        businessJdbcTemplate.update(
            "DELETE FROM mc_bank_allocation WHERE company_id = ? AND journal_id = ?",
            companyId,
            journalId
        );
        businessJdbcTemplate.update(
            "DELETE FROM mc_journal_line WHERE company_id = ? AND journal_id = ?",
            companyId,
            journalId
        );
    }

    private List<PreparedLine> invertLinesForReversal(VoucherDtos.VoucherDetailDto orig, int scale) {
        List<PreparedLine> rev = new ArrayList<>(orig.lines().size());
        int lineNo = 1;
        for (VoucherDtos.VoucherLineDetailDto l : orig.lines()) {
            String invertedSide = "DR".equals(l.side()) ? "CR" : "DR";
            BigDecimal amt = new BigDecimal(l.amount()).setScale(scale, RoundingMode.UNNECESSARY);
            BigDecimal fxAmt = l.forexAmount() == null ? null : new BigDecimal(l.forexAmount());
            BigDecimal fxRate = l.forexRate() == null ? null : new BigDecimal(l.forexRate());
            BigDecimal ratePct = l.ratePct() == null ? null : new BigDecimal(l.ratePct());
            BigDecimal compTax = l.computedTaxAmount() == null ? null : new BigDecimal(l.computedTaxAmount());

            List<PreparedBillAlloc> revBills = new ArrayList<>();
            for (VoucherDtos.BillAllocationDto ba : l.billAllocations()) {
                String invBillSide = "DR".equals(ba.side()) ? "CR" : "DR";
                String revType = ("NEW_REF".equals(ba.allocationType()) || "ADVANCE".equals(ba.allocationType()))
                    ? "AGST_REF"
                    : ba.allocationType();
                revBills.add(
                    new PreparedBillAlloc(
                        UUID.randomUUID(),
                        revType,
                        ba.billName(),
                        null,
                        null,
                        invBillSide,
                        new BigDecimal(ba.amount()).setScale(scale, RoundingMode.UNNECESSARY),
                        ba.forexCurrencyCode(),
                        ba.forexAmount() == null ? null : new BigDecimal(ba.forexAmount()),
                        ba.forexRate() == null ? null : new BigDecimal(ba.forexRate()),
                        BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY),
                        true
                    )
                );
            }

            List<PreparedCostAlloc> revCosts = new ArrayList<>();
            for (VoucherDtos.CostAllocationDto ca : l.costAllocations()) {
                String invCostSide = "DR".equals(ca.side()) ? "CR" : "DR";
                revCosts.add(
                    new PreparedCostAlloc(
                        UUID.randomUUID(),
                        ca.costCategoryId(),
                        ca.costCentreId(),
                        invCostSide,
                        new BigDecimal(ca.amount()).setScale(scale, RoundingMode.UNNECESSARY)
                    )
                );
            }

            rev.add(
                new PreparedLine(
                    UUID.randomUUID(),
                    lineNo++,
                    l.lineRole(),
                    l.ledgerId(),
                    invertedSide,
                    amt,
                    l.forexCurrencyCode(),
                    fxAmt,
                    fxRate,
                    ratePct,
                    compTax,
                    false,
                    null,
                    l.lineNarration() == null ? "" : l.lineNarration(),
                    revBills,
                    revCosts,
                    List.of()
                )
            );
        }
        return rev;
    }

    private VoucherDtos.VoucherDetailDto loadVoucherDetail(UUID companyId, UUID journalId, int scale) {
        List<VoucherDtos.VoucherDetailDto> headers = businessJdbcTemplate.query(
            """
            SELECT j.id, j.company_id, j.period_id, j.fiscal_year_id,
                   j.voucher_type_id, vt.name AS voucher_type_name, vt.base_category,
                   j.voucher_class_id, j.status, j.posting_nature, j.persisted_view, j.approval_policy,
                   j.voucher_number, j.voucher_seq_no, j.voucher_date, j.effective_date,
                   j.reversing_applicable_upto, j.supplier_invoice_no, j.supplier_invoice_date,
                   j.party_ledger_id, pl.name AS party_ledger_name,
                   j.accounting_ledger_id, al.name AS accounting_ledger_name,
                   j.place_of_supply_state, j.company_gstin_id, j.party_gstin,
                   j.party_mailing_name, j.party_state_code, j.is_reverse_charge, j.export_type,
                   trim(j.currency_code) AS currency_code, j.exchange_rate,
                   j.total_debit, j.total_credit, j.narration, j.reversal_of_journal_id,
                   j.master_seq_id, j.alter_id, j.current_posting_batch_no, j.version_no,
                   j.created_by, j.approved_by, j.posted_by, j.cancelled_by,
                   j.created_at, j.updated_at, j.approved_at, j.posted_at, j.cancelled_at
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
              LEFT JOIN mc_ledger al
                ON al.company_id = j.company_id AND al.id = j.accounting_ledger_id
             WHERE j.company_id = ? AND j.id = ?
            """,
            (rs, rowNum) -> new VoucherDtos.VoucherDetailDto(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getObject("period_id", UUID.class),
                rs.getObject("fiscal_year_id", UUID.class),
                rs.getObject("voucher_type_id", UUID.class),
                rs.getString("voucher_type_name"),
                rs.getString("base_category"),
                rs.getObject("voucher_class_id", UUID.class),
                rs.getString("status"),
                rs.getString("posting_nature"),
                rs.getString("persisted_view"),
                rs.getString("approval_policy"),
                rs.getString("voucher_number"),
                rs.getObject("voucher_seq_no", Long.class),
                rs.getObject("voucher_date", LocalDate.class),
                rs.getObject("effective_date", LocalDate.class),
                rs.getObject("reversing_applicable_upto", LocalDate.class),
                rs.getString("supplier_invoice_no"),
                rs.getObject("supplier_invoice_date", LocalDate.class),
                rs.getObject("party_ledger_id", UUID.class),
                rs.getString("party_ledger_name"),
                rs.getObject("accounting_ledger_id", UUID.class),
                rs.getString("accounting_ledger_name"),
                rs.getString("place_of_supply_state"),
                rs.getObject("company_gstin_id", UUID.class),
                rs.getString("party_gstin"),
                rs.getString("party_mailing_name"),
                rs.getString("party_state_code"),
                rs.getBoolean("is_reverse_charge"),
                rs.getString("export_type"),
                rs.getString("currency_code"),
                ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("exchange_rate")),
                ExactMoneyValidator.formatExactScale(rs.getBigDecimal("total_debit"), scale),
                ExactMoneyValidator.formatExactScale(rs.getBigDecimal("total_credit"), scale),
                rs.getString("narration"),
                rs.getObject("reversal_of_journal_id", UUID.class),
                rs.getLong("master_seq_id"),
                rs.getLong("alter_id"),
                rs.getInt("current_posting_batch_no"),
                rs.getLong("version_no"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class),
                rs.getObject("posted_by", UUID.class),
                rs.getObject("cancelled_by", UUID.class),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"),
                toInstant(rs, "approved_at"),
                toInstant(rs, "posted_at"),
                toInstant(rs, "cancelled_at"),
                List.of()
            ),
            companyId,
            journalId
        );

        if (headers.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-VOUCHER-NOT-FOUND",
                "Voucher Not Found",
                "Voucher " + journalId + " does not exist."
            );
        }
        VoucherDtos.VoucherDetailDto hdr = headers.getFirst();
        List<VoucherDtos.VoucherLineDetailDto> lines = loadVoucherLines(companyId, journalId, scale);
        VoucherInventoryBridge invBridge = inventoryBridgeProvider.getIfAvailable();
        List<VoucherDtos.VoucherInventoryLineDto> inventoryLines = invBridge != null
            ? invBridge.loadInventoryLines(companyId, journalId, scale)
            : List.of();
        return new VoucherDtos.VoucherDetailDto(
            hdr.id(),
            hdr.companyId(),
            hdr.periodId(),
            hdr.fiscalYearId(),
            hdr.voucherTypeId(),
            hdr.voucherTypeName(),
            hdr.voucherTypeCategory(),
            hdr.voucherClassId(),
            hdr.status(),
            hdr.postingNature(),
            hdr.persistedView(),
            hdr.approvalPolicy(),
            hdr.voucherNumber(),
            hdr.voucherSeqNo(),
            hdr.voucherDate(),
            hdr.effectiveDate(),
            hdr.reversingApplicableUpto(),
            hdr.supplierInvoiceNo(),
            hdr.supplierInvoiceDate(),
            hdr.partyLedgerId(),
            hdr.partyLedgerName(),
            hdr.accountingLedgerId(),
            hdr.accountingLedgerName(),
            hdr.placeOfSupplyState(),
            hdr.companyGstinId(),
            hdr.partyGstin(),
            hdr.partyMailingName(),
            hdr.partyStateCode(),
            hdr.isReverseCharge(),
            hdr.exportType(),
            hdr.currencyCode(),
            hdr.exchangeRate(),
            hdr.totalDebit(),
            hdr.totalCredit(),
            hdr.narration(),
            hdr.reversalOfJournalId(),
            hdr.masterSeqId(),
            hdr.alterId(),
            hdr.currentPostingBatchNo(),
            hdr.versionNo(),
            hdr.createdBy(),
            hdr.approvedBy(),
            hdr.postedBy(),
            hdr.cancelledBy(),
            hdr.createdAt(),
            hdr.updatedAt(),
            hdr.approvedAt(),
            hdr.postedAt(),
            hdr.cancelledAt(),
            lines,
            inventoryLines
        );
    }

    private List<VoucherDtos.VoucherLineDetailDto> loadVoucherLines(UUID companyId, UUID journalId, int scale) {
        Map<UUID, List<VoucherDtos.BillAllocationDto>> billsByLine = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, journal_line_id, allocation_type, bill_name, credit_period_days, due_date,
                   trim(side) AS side, amount, trim(forex_currency_code) AS forex_ccy,
                   forex_amount, forex_rate, unadjusted_forex_delta, allow_over_settlement
              FROM mc_bill_allocation
             WHERE company_id = ? AND journal_id = ?
             ORDER BY created_at ASC, id ASC
            """,
            rs -> {
                UUID lineId = rs.getObject("journal_line_id", UUID.class);
                billsByLine.computeIfAbsent(lineId, k -> new ArrayList<>()).add(
                    new VoucherDtos.BillAllocationDto(
                        rs.getObject("id", UUID.class),
                        rs.getString("allocation_type"),
                        rs.getString("bill_name"),
                        rs.getObject("credit_period_days", Integer.class),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getString("side"),
                        ExactMoneyValidator.formatExactScale(rs.getBigDecimal("amount"), scale),
                        rs.getString("forex_ccy"),
                        ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("forex_amount")),
                        ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("forex_rate")),
                        ExactMoneyValidator.formatExactScale(rs.getBigDecimal("unadjusted_forex_delta"), scale),
                        rs.getBoolean("allow_over_settlement")
                    )
                );
            },
            companyId,
            journalId
        );

        Map<UUID, List<VoucherDtos.CostAllocationDto>> costsByLine = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT ca.id, ca.journal_line_id, ca.cost_category_id, cat.name AS category_name,
                   ca.cost_centre_id, cc.name AS centre_name, trim(ca.side) AS side, ca.amount
              FROM mc_cost_allocation ca
              JOIN mc_cost_category cat
                ON cat.company_id = ca.company_id AND cat.id = ca.cost_category_id
              JOIN mc_cost_centre cc
                ON cc.company_id = ca.company_id AND cc.id = ca.cost_centre_id
             WHERE ca.company_id = ? AND ca.journal_id = ?
             ORDER BY ca.created_at ASC, ca.id ASC
            """,
            rs -> {
                UUID lineId = rs.getObject("journal_line_id", UUID.class);
                costsByLine.computeIfAbsent(lineId, k -> new ArrayList<>()).add(
                    new VoucherDtos.CostAllocationDto(
                        rs.getObject("id", UUID.class),
                        rs.getObject("cost_category_id", UUID.class),
                        rs.getString("category_name"),
                        rs.getObject("cost_centre_id", UUID.class),
                        rs.getString("centre_name"),
                        rs.getString("side"),
                        ExactMoneyValidator.formatExactScale(rs.getBigDecimal("amount"), scale)
                    )
                );
            },
            companyId,
            journalId
        );

        Map<UUID, List<VoucherDtos.BankAllocationDto>> banksByLine = new HashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, journal_line_id, bank_ledger_id, transaction_type, instrument_no,
                   instrument_date, bankers_date, favouring_name, transfer_mode_ref,
                   trim(side) AS side, amount, reconciliation_status
              FROM mc_bank_allocation
             WHERE company_id = ? AND journal_id = ?
             ORDER BY created_at ASC, id ASC
            """,
            rs -> {
                UUID lineId = rs.getObject("journal_line_id", UUID.class);
                banksByLine.computeIfAbsent(lineId, k -> new ArrayList<>()).add(
                    new VoucherDtos.BankAllocationDto(
                        rs.getObject("id", UUID.class),
                        rs.getObject("bank_ledger_id", UUID.class),
                        rs.getString("transaction_type"),
                        rs.getString("instrument_no"),
                        rs.getObject("instrument_date", LocalDate.class),
                        rs.getObject("bankers_date", LocalDate.class),
                        rs.getString("favouring_name"),
                        rs.getString("transfer_mode_ref"),
                        rs.getString("side"),
                        ExactMoneyValidator.formatExactScale(rs.getBigDecimal("amount"), scale),
                        rs.getString("reconciliation_status")
                    )
                );
            },
            companyId,
            journalId
        );

        return businessJdbcTemplate.query(
            """
            SELECT jl.id, jl.line_no, jl.line_role, jl.ledger_id, l.name AS ledger_name,
                   trim(jl.side) AS side, jl.amount, trim(jl.forex_currency_code) AS forex_ccy,
                   jl.forex_amount, jl.forex_rate, jl.rate_pct, jl.computed_tax_amount,
                   jl.is_gst_overridden, jl.gst_override_reason, jl.line_narration
              FROM mc_journal_line jl
              LEFT JOIN mc_ledger l
                ON l.company_id = jl.company_id AND l.id = jl.ledger_id
             WHERE jl.company_id = ? AND jl.journal_id = ?
             ORDER BY jl.line_no ASC
            """,
            (rs, rowNum) -> {
                UUID lineId = rs.getObject("id", UUID.class);
                BigDecimal compTax = rs.getBigDecimal("computed_tax_amount");
                return new VoucherDtos.VoucherLineDetailDto(
                    lineId,
                    rs.getInt("line_no"),
                    rs.getString("line_role"),
                    rs.getObject("ledger_id", UUID.class),
                    rs.getString("ledger_name"),
                    rs.getString("side"),
                    ExactMoneyValidator.formatExactScale(rs.getBigDecimal("amount"), scale),
                    rs.getString("forex_ccy"),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("forex_amount")),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("forex_rate")),
                    ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("rate_pct")),
                    compTax == null ? null : ExactMoneyValidator.formatExactScale(compTax, scale),
                    rs.getBoolean("is_gst_overridden"),
                    rs.getString("gst_override_reason"),
                    rs.getString("line_narration"),
                    List.copyOf(billsByLine.getOrDefault(lineId, List.of())),
                    List.copyOf(costsByLine.getOrDefault(lineId, List.of())),
                    List.copyOf(banksByLine.getOrDefault(lineId, List.of()))
                );
            },
            companyId,
            journalId
        );
    }

    private LockedPeriodRow lockAndRequireOpenPeriod(UUID companyId, LocalDate effectiveDate) {
        List<LockedPeriodRow> rows = businessJdbcTemplate.query(
            """
            SELECT id, fiscal_year_id, code, start_date, end_date, state
              FROM mc_period
             WHERE company_id = ?
               AND start_date <= ?
               AND end_date >= ?
               FOR SHARE
            """,
            this::mapLockedPeriod,
            companyId,
            effectiveDate,
            effectiveDate
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-PERIOD-NOT-FOUND",
                "Accounting Period Not Found",
                "No accounting period covers date " + effectiveDate + "."
            );
        }
        LockedPeriodRow p = rows.getFirst();
        if (!"OPEN".equals(p.state())) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-PERIOD-NOT-OPEN",
                "Accounting Period Not Open",
                "Accounting period '" + p.code() + "' is in state " + p.state() + " (expected OPEN)."
            );
        }
        return p;
    }

    private LockedPeriodRow findPeriodForDate(UUID companyId, LocalDate date) {
        List<LockedPeriodRow> rows = businessJdbcTemplate.query(
            """
            SELECT id, fiscal_year_id, code, start_date, end_date, state
              FROM mc_period
             WHERE company_id = ?
               AND start_date <= ?
               AND end_date >= ?
            """,
            this::mapLockedPeriod,
            companyId,
            date,
            date
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-PERIOD-NOT-FOUND",
                "Accounting Period Not Found",
                "No accounting period covers date " + date + "."
            );
        }
        return rows.getFirst();
    }

    private void lockAndRequireOpenPeriodsInOrder(UUID companyId, UUID firstPeriodId, UUID secondPeriodId) {
        Set<UUID> ordered = new TreeSet<>(Comparator.comparing(UUID::toString));
        ordered.add(firstPeriodId);
        ordered.add(secondPeriodId);
        for (UUID pid : ordered) {
            List<LockedPeriodRow> rows = businessJdbcTemplate.query(
                """
                SELECT id, fiscal_year_id, code, start_date, end_date, state
                  FROM mc_period
                 WHERE company_id = ? AND id = ?
                   FOR SHARE
                """,
                this::mapLockedPeriod,
                companyId,
                pid
            );
            if (rows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-PERIOD-NOT-FOUND",
                    "Accounting Period Not Found",
                    "Accounting period " + pid + " does not exist."
                );
            }
            LockedPeriodRow p = rows.getFirst();
            if (!"OPEN".equals(p.state())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-PERIOD-NOT-OPEN",
                    "Accounting Period Not Open",
                    "Accounting period '" + p.code() + "' is " + p.state() + " (expected OPEN)."
                );
            }
        }
    }

    private LockedPeriodRow mapLockedPeriod(ResultSet rs, int rowNum) throws SQLException {
        return new LockedPeriodRow(
            rs.getObject("id", UUID.class),
            rs.getObject("fiscal_year_id", UUID.class),
            rs.getString("code"),
            rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class),
            rs.getString("state")
        );
    }

    private UUID findExistingJournalPeriodId(UUID companyId, UUID journalId) {
        List<UUID> rows = businessJdbcTemplate.query(
            "SELECT period_id FROM mc_journal WHERE company_id = ? AND id = ?",
            (rs, rowNum) -> rs.getObject("period_id", UUID.class),
            companyId,
            journalId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-VOUCHER-NOT-FOUND",
                "Voucher Not Found",
                "Voucher " + journalId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private LockedJournalRow readJournalRowUnlocked(UUID companyId, UUID journalId) {
        List<LockedJournalRow> rows = businessJdbcTemplate.query(
            journalLockSelectSql(false),
            this::mapLockedJournal,
            companyId,
            journalId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-VOUCHER-NOT-FOUND",
                "Voucher Not Found",
                "Voucher " + journalId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private LockedJournalRow lockJournalById(UUID companyId, UUID journalId) {
        List<LockedJournalRow> rows = businessJdbcTemplate.query(
            journalLockSelectSql(true),
            this::mapLockedJournal,
            companyId,
            journalId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-VOUCHER-NOT-FOUND",
                "Voucher Not Found",
                "Voucher " + journalId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private static String journalLockSelectSql(boolean forUpdate) {
        return """
            SELECT id, period_id, fiscal_year_id, voucher_type_id, number_series_id,
                   status, posting_nature, persisted_view, approval_policy,
                   voucher_number, voucher_seq_no, effective_date,
                   current_posting_batch_no, version_no, created_by, approved_by, posted_by
              FROM mc_journal
             WHERE company_id = ? AND id = ?
            """ + (forUpdate ? " FOR UPDATE" : "");
    }

    private LockedJournalRow mapLockedJournal(ResultSet rs, int rowNum) throws SQLException {
        return new LockedJournalRow(
            rs.getObject("id", UUID.class),
            rs.getObject("period_id", UUID.class),
            rs.getObject("fiscal_year_id", UUID.class),
            rs.getObject("voucher_type_id", UUID.class),
            rs.getObject("number_series_id", UUID.class),
            rs.getString("status"),
            rs.getString("posting_nature"),
            rs.getString("persisted_view"),
            rs.getString("approval_policy"),
            rs.getString("voucher_number"),
            rs.getObject("voucher_seq_no", Long.class),
            rs.getObject("effective_date", LocalDate.class),
            rs.getInt("current_posting_batch_no"),
            rs.getLong("version_no"),
            rs.getObject("created_by", UUID.class),
            rs.getObject("approved_by", UUID.class),
            rs.getObject("posted_by", UUID.class)
        );
    }

    private List<UUID> findPriorEntryLedgerIds(UUID companyId, UUID journalId) {
        return businessJdbcTemplate.query(
            "SELECT DISTINCT ledger_id FROM mc_entry WHERE company_id = ? AND journal_id = ?",
            (rs, rowNum) -> rs.getObject("ledger_id", UUID.class),
            companyId,
            journalId
        );
    }

    private long countHistoricalEntries(UUID companyId, UUID journalId) {
        Long cnt = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mc_entry WHERE company_id = ? AND journal_id = ?",
            Long.class,
            companyId,
            journalId
        );
        return cnt == null ? 0L : cnt;
    }

    private UUID findPrimaryCostCategoryId(UUID companyId) {
        List<UUID> rows = businessJdbcTemplate.query(
            "SELECT id FROM mc_cost_category WHERE company_id = ? AND is_primary_category = true LIMIT 1",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private UUID findCostCentreCategoryId(UUID companyId, UUID costCentreId) {
        List<UUID> rows = businessJdbcTemplate.query(
            "SELECT cost_category_id FROM mc_cost_centre WHERE company_id = ? AND id = ? AND active = true",
            (rs, rowNum) -> rs.getObject("cost_category_id", UUID.class),
            companyId,
            costCentreId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-COST-CENTRE-NOT-FOUND",
                "Cost Centre Not Found",
                "Cost centre " + costCentreId + " does not exist or is inactive."
            );
        }
        return rows.getFirst();
    }

    private static VoucherDtos.OpenBillDto mapOpenBillRow(ResultSet rs, int scale) throws SQLException {
        return new VoucherDtos.OpenBillDto(
            rs.getString("bill_name"),
            rs.getObject("bill_date", LocalDate.class),
            rs.getObject("due_date", LocalDate.class),
            ExactMoneyValidator.formatExactScale(rs.getBigDecimal("total_dr"), scale),
            ExactMoneyValidator.formatExactScale(rs.getBigDecimal("total_cr"), scale),
            ExactMoneyValidator.formatExactScale(rs.getBigDecimal("pending_amount"), scale),
            rs.getString("pending_side"),
            ExactMoneyValidator.formatExactScale(rs.getBigDecimal("unadjusted_forex_delta"), scale)
        );
    }

    private String encodeDayBookSortTuple(DayBookSortTuple tuple) {
        try {
            return objectMapper.writeValueAsString(
                List.of(
                    tuple.voucherDate().toString(),
                    tuple.voucherTypeName(),
                    Long.toString(tuple.seqOrMax()),
                    tuple.voucherNumber()
                )
            );
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize DayBook cursor sort tuple", ex);
        }
    }

    private DayBookSortTuple decodeDayBookSortTuple(String raw) {
        try {
            JsonNode arr = objectMapper.readTree(raw);
            if (!arr.isArray() || arr.size() != 4) {
                throw new IllegalArgumentException("Expected 4-element array");
            }
            return new DayBookSortTuple(
                LocalDate.parse(arr.get(0).asString()),
                arr.get(1).asString(),
                Long.parseLong(arr.get(2).asString()),
                arr.get(3).asString()
            );
        } catch (RuntimeException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-CURSOR-INVALID",
                "Invalid Day Book Cursor",
                "Malformed Day Book pagination sort tuple."
            );
        }
    }

    private static boolean shouldHoldActiveGl(
        String status,
        String postingNature,
        boolean voucherTypePostsEntries
    ) {
        return "POSTED".equals(status) && "NORMAL".equals(postingNature) && voucherTypePostsEntries;
    }

    private static BigDecimal sumSide(List<PreparedLine> lines, String side) {
        BigDecimal total = BigDecimal.ZERO;
        for (PreparedLine line : lines) {
            if (side.equals(line.side())) {
                total = total.add(line.amount());
            }
        }
        return total;
    }

    private static Long parseOptionalIfMatch(String ifMatchHeader) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            return null;
        }
        return CompanyMasterSequenceHelper.requireIfMatchVersion(ifMatchHeader, null);
    }

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    private static String trimOrNull(String val, int maxLen) {
        if (val == null || val.isBlank()) {
            return null;
        }
        String t = val.trim();
        return t.length() <= maxLen ? t : t.substring(0, maxLen);
    }

    private static String truncate32(String val) {
        return val.length() <= 32 ? val : val.substring(0, 32);
    }

    private record VoucherTypeMeta(
        UUID id,
        String name,
        String baseCategory,
        boolean postsAccountingEntries,
        boolean movesInventory,
        String defaultEntryMode,
        String numberingMethod,
        String approvalPolicy,
        boolean preventDuplicateNumbers,
        boolean allowZeroValuedTransactions,
        boolean makeOptionalByDefault,
        boolean active
    ) {
    }

    private record HeaderSpec(
        String status,
        String postingNature,
        String persistedView,
        String approvalPolicy,
        String supplierInvoiceNo,
        String placeOfSupplyState,
        UUID companyGstinId,
        String partyGstin,
        String partyMailingName,
        String partyStateCode,
        boolean isReverseCharge,
        String exportType,
        String currencyCode,
        BigDecimal exchangeRate,
        String narration,
        String sourceGuid
    ) {
    }

    private record LockedPeriodRow(
        UUID id,
        UUID fiscalYearId,
        String code,
        LocalDate startDate,
        LocalDate endDate,
        String state
    ) {
    }

    private record LockedJournalRow(
        UUID id,
        UUID periodId,
        UUID fiscalYearId,
        UUID voucherTypeId,
        UUID numberSeriesId,
        String status,
        String postingNature,
        String persistedView,
        String approvalPolicy,
        String voucherNumber,
        Long voucherSeqNo,
        LocalDate effectiveDate,
        int currentPostingBatchNo,
        long versionNo,
        UUID createdBy,
        UUID approvedBy,
        UUID postedBy
    ) {
    }

    private record LockedLedgerMeta(
        UUID id,
        String name,
        UUID groupId,
        boolean maintainBalancesBillByBill,
        Integer defaultCreditPeriodDays,
        boolean costCentresApplicable,
        boolean active
    ) {
    }

    private record ForexAndBaseAmount(
        BigDecimal baseAmount,
        String forexCurrencyCode,
        BigDecimal forexAmount,
        BigDecimal forexRate
    ) {
    }

    private record PreparedLine(
        UUID id,
        int lineNo,
        String lineRole,
        UUID ledgerId,
        String side,
        BigDecimal amount,
        String forexCurrencyCode,
        BigDecimal forexAmount,
        BigDecimal forexRate,
        BigDecimal ratePct,
        BigDecimal computedTaxAmount,
        boolean isGstOverridden,
        String gstOverrideReason,
        String lineNarration,
        List<PreparedBillAlloc> billAllocations,
        List<PreparedCostAlloc> costAllocations,
        List<PreparedBankAlloc> bankAllocations
    ) {
    }

    private record PreparedBillAlloc(
        UUID id,
        String allocationType,
        String billName,
        Integer creditPeriodDays,
        LocalDate dueDate,
        String side,
        BigDecimal amount,
        String forexCurrencyCode,
        BigDecimal forexAmount,
        BigDecimal forexRate,
        BigDecimal unadjustedForexDelta,
        boolean allowOverSettlement
    ) {
    }

    private record PreparedCostAlloc(
        UUID id,
        UUID costCategoryId,
        UUID costCentreId,
        String side,
        BigDecimal amount
    ) {
    }

    private record PreparedBankAlloc(
        UUID id,
        UUID bankLedgerId,
        String transactionType,
        String instrumentNo,
        LocalDate instrumentDate,
        LocalDate bankersDate,
        String favouringName,
        String transferModeRef,
        String side,
        BigDecimal amount
    ) {
    }

    private record LedgerBillKey(UUID ledgerId, String billName) {
    }

    private record OpenBillForexSnapshot(
        BigDecimal signedBaseDrMinusCr,
        BigDecimal signedForexDrMinusCr,
        BigDecimal originBaseSum,
        BigDecimal originForexSum
    ) {
    }

    private record NetLedgerEntrySlice(
        UUID periodId,
        LocalDate entryDate,
        UUID ledgerId,
        BigDecimal signedNet
    ) {
    }

    private record StoredLineSlice(
        UUID ledgerId,
        String side,
        BigDecimal amount,
        String forexCurrencyCode,
        BigDecimal forexAmount,
        BigDecimal forexRate
    ) {
    }

    private record DayBookSortTuple(
        LocalDate voucherDate,
        String voucherTypeName,
        long seqOrMax,
        String voucherNumber
    ) {
    }

    private record SummaryWithSeq(
        VoucherDtos.VoucherSummaryDto summary,
        long seqOrMax
    ) {
    }
}
