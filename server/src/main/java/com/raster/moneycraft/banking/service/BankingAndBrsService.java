package com.raster.moneycraft.banking.service;

import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.accounting.service.VoucherService;
import com.raster.moneycraft.banking.dto.BankingDtos;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.ObjectMapper;

/**
 * Banking Cheque Books, CSV / MT940 / CAMT.053 Statement Import, Auto-BRS Match Engine,
 * Optional Payroll Masters (Tink AES256_SIV PAN + AES256_GCM bank details), and
 * Docs-by-Ira OCR Document Staging Queue with mandatory human-review conversion.
 */
@Service
public class BankingAndBrsService {

    private static final Pattern MT940_TAG61 = Pattern.compile(
        "^:61:(\\d{6})(?:\\d{4})?(RC|RD|C|D)[A-Z]?([0-9]+(?:[.,][0-9]{1,6})?)([A-Z0-9]{4})?([^\\r\\n]*)$"
    );
    private static final Pattern MT940_BAL = Pattern.compile(
        "^:6[02][FM]:(C|D)(\\d{6})([A-Z]{3})([0-9]+(?:[.,][0-9]{1,6})?)$"
    );

    private static final List<DateTimeFormatter> FLEX_DATE_FORMATS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("yyyyMMdd"),
        new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d-MMM-yy")
            .toFormatter(Locale.ENGLISH),
        new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d-MMM-yyyy")
            .toFormatter(Locale.ENGLISH)
    );

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final TinkCryptoService tinkCryptoService;
    private final VoucherService voucherService;
    private final AuditChainService auditChainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public BankingAndBrsService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        TinkCryptoService tinkCryptoService,
        VoucherService voucherService,
        AuditChainService auditChainService,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.tinkCryptoService = tinkCryptoService;
        this.voucherService = voucherService;
        this.auditChainService = auditChainService;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public List<BankingDtos.ChequeBookDto> listChequeBooks(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> businessJdbcTemplate.query(
            """
            SELECT cb.id, cb.bank_ledger_id, l.name AS bank_name, cb.book_name,
                   cb.prefix, cb.from_number, cb.to_number, cb.number_of_leaves,
                   cb.active, cb.version_no,
                   (SELECT COUNT(*) FROM mc_cheque_leaf cl
                     WHERE cl.company_id = cb.company_id
                       AND cl.cheque_book_id = cb.id
                       AND cl.status = 'AVAILABLE') AS avail_cnt
              FROM mc_cheque_book cb
              JOIN mc_ledger l
                ON l.company_id = cb.company_id
               AND l.id = cb.bank_ledger_id
             WHERE cb.company_id = ?
             ORDER BY cb.created_at DESC
            """,
            (rs, rowNum) -> {
                String pfx = rs.getString("prefix");
                long fromNo = rs.getLong("from_number");
                long toNo = rs.getLong("to_number");
                int total = rs.getInt("number_of_leaves");
                return new BankingDtos.ChequeBookDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("bank_ledger_id", UUID.class),
                    rs.getString("bank_name"),
                    rs.getString("book_name"),
                    pfx,
                    fromNo,
                    toNo,
                    formatChequeNo(pfx, fromNo),
                    formatChequeNo(pfx, toNo),
                    total,
                    total,
                    rs.getInt("avail_cnt"),
                    rs.getBoolean("active"),
                    rs.getLong("version_no")
                );
            },
            companyId
        ));
    }

    public BankingDtos.ChequeBookDto createChequeBook(
        MoneyCraftPrincipal actor,
        UUID companyId,
        BankingDtos.CreateChequeBookRequest req
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (req == null || req.bankLedgerId() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-BNK-LEDGER-REQUIRED",
                "Bank Ledger Required",
                "Field 'bankLedgerId' is required."
            );
        }

        String prefix = req.prefix() != null ? req.prefix().trim() : extractAlphaPrefix(req.fromLeafNo());
        long fromNo = req.fromNumber() != null ? req.fromNumber() : parseLeafDigits(req.fromLeafNo(), "fromLeafNo");
        long toNo = req.toNumber() != null ? req.toNumber() : parseLeafDigits(req.toLeafNo(), "toLeafNo");
        if (fromNo < 1L || toNo < fromNo || (toNo - fromNo + 1L) > 10000L) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-BNK-INVALID-CHEQUE-RANGE",
                "Invalid Cheque Leaf Range",
                "Cheque range must satisfy fromNumber >= 1, toNumber >= fromNumber, and <= 10000 leaves."
            );
        }
        String bookName = (req.bookName() == null || req.bookName().isBlank())
            ? "Cheque Book " + formatChequeNo(prefix, fromNo) + "-" + formatChequeNo(prefix, toNo)
            : req.bookName().trim();
        int leafCount = (int) (toNo - fromNo + 1L);

        return businessTxTemplate.execute(tx -> {
            sequenceHelper.allocateForCreate(companyId, false);
            String bankName = requireLedgerName(companyId, req.bankLedgerId());
            UUID bookId = UUID.randomUUID();

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_cheque_book (
                    id, company_id, bank_ledger_id, book_name, prefix,
                    from_number, to_number, number_of_leaves, active, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 1)
                """,
                bookId, companyId, req.bankLedgerId(), bookName, prefix, fromNo, toNo, leafCount
            );

            for (long seq = fromNo; seq <= toNo; seq++) {
                String chqNo = formatChequeNo(prefix, seq);
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_cheque_leaf (
                        id, company_id, cheque_book_id, bank_ledger_id,
                        cheque_number, leaf_seq, status, version_no
                    ) VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE', 1)
                    """,
                    UUID.randomUUID(), companyId, bookId, req.bankLedgerId(), chqNo, seq
                );
            }

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "CHEQUE_BOOK_CREATE",
                "CHEQUE_BOOK",
                bookId.toString(),
                "SUCCESS",
                null,
                Map.of("bookName", bookName, "leaves", leafCount)
            );

            return new BankingDtos.ChequeBookDto(
                bookId,
                req.bankLedgerId(),
                bankName,
                bookName,
                prefix,
                fromNo,
                toNo,
                formatChequeNo(prefix, fromNo),
                formatChequeNo(prefix, toNo),
                leafCount,
                leafCount,
                leafCount,
                true,
                1L
            );
        });
    }

    public List<BankingDtos.ChequeLeafDto> listChequeLeaves(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID bookId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            int scale = loadCompanyScale(companyId);
            return businessJdbcTemplate.query(
                """
                SELECT cl.id, cl.cheque_book_id, cl.bank_ledger_id, cl.cheque_number,
                       cl.leaf_seq, cl.status, cl.journal_id, j.voucher_number,
                       cl.payee_name, cl.cheque_date, cl.amount, cl.remarks, cl.version_no
                  FROM mc_cheque_leaf cl
                  LEFT JOIN mc_journal j
                    ON j.company_id = cl.company_id
                   AND j.id = cl.journal_id
                 WHERE cl.company_id = ?
                   AND cl.cheque_book_id = ?
                 ORDER BY cl.leaf_seq ASC
                """,
                (rs, rowNum) -> {
                    String chqNo = rs.getString("cheque_number");
                    UUID jid = rs.getObject("journal_id", UUID.class);
                    LocalDate chqDate = rs.getObject("cheque_date", LocalDate.class);
                    BigDecimal amt = rs.getBigDecimal("amount");
                    return new BankingDtos.ChequeLeafDto(
                        rs.getObject("id", UUID.class),
                        rs.getObject("cheque_book_id", UUID.class),
                        rs.getObject("bank_ledger_id", UUID.class),
                        chqNo,
                        chqNo,
                        rs.getLong("leaf_seq"),
                        rs.getString("status"),
                        jid,
                        jid,
                        rs.getString("voucher_number"),
                        rs.getString("payee_name"),
                        chqDate,
                        chqDate,
                        amt != null ? ExactMoneyValidator.formatExactScale(amt, scale) : null,
                        rs.getString("remarks"),
                        rs.getLong("version_no")
                    );
                },
                companyId,
                bookId
            );
        });
    }

    public BankingDtos.BankStatementImportResponse importBankStatement(
        MoneyCraftPrincipal actor,
        UUID companyId,
        BankingDtos.ImportBankStatementRequest req
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (req == null || req.bankLedgerId() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-BNK-LEDGER-REQUIRED",
                "Bank Ledger Required",
                "Field 'bankLedgerId' is required."
            );
        }

        String format = normalizeStmtFormat(firstNonBlank(req.sourceFormat(), req.format()));
        String rawText = firstNonBlank(req.rawContent(), req.content());
        String fileName = firstNonBlank(req.fileName(), "statement." + format.toLowerCase(Locale.ROOT));

        ParsedStatement parsed = parseStatement(format, rawText, req);
        if (parsed.lines().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-BNK-EMPTY-STATEMENT",
                "No Statement Lines Found",
                "Bank statement contained zero non-zero debit/credit transaction lines."
            );
        }

        byte[] sha256 = sha256(
            (rawText != null && !rawText.isEmpty() ? rawText : fileName + ":" + parsed.lines().size())
                .getBytes(StandardCharsets.UTF_8)
        );

        return businessTxTemplate.execute(tx -> {
            sequenceHelper.allocateForCreate(companyId, false);
            ensurePrincipalProjected(actor);
            requireLedgerName(companyId, req.bankLedgerId());
            int scale = loadCompanyScale(companyId);

            UUID importId = UUID.randomUUID();
            Instant now = Instant.now();

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_bank_statement_import (
                    id, company_id, bank_ledger_id, file_name, source_format,
                    file_sha256, statement_from_date, statement_to_date,
                    opening_balance, closing_balance, total_lines, matched_lines, imported_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                importId,
                companyId,
                req.bankLedgerId(),
                fileName,
                format,
                sha256,
                parsed.fromDate(),
                parsed.toDate(),
                parsed.openingBalance(),
                parsed.closingBalance(),
                parsed.lines().size(),
                actor.principalId()
            );

            int seq = 1;
            for (ParsedStmtLine line : parsed.lines()) {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_bank_statement_line (
                        id, company_id, statement_import_id, bank_ledger_id, line_no,
                        txn_date, value_date, instrument_no, utr_reference, description,
                        withdrawal_amount, deposit_amount, running_balance, match_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNMATCHED')
                    """,
                    UUID.randomUUID(),
                    companyId,
                    importId,
                    req.bankLedgerId(),
                    seq++,
                    line.txnDate(),
                    line.valueDate() != null ? line.valueDate() : line.txnDate(),
                    trimMax(line.instrumentNo(), 64),
                    trimMax(line.utrReference(), 80),
                    line.description() == null ? "" : line.description(),
                    line.withdrawalAmount(),
                    line.depositAmount(),
                    line.runningBalance()
                );
            }

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "BANK_STATEMENT_IMPORT",
                "BANK_STATEMENT_IMPORT",
                importId.toString(),
                "SUCCESS",
                null,
                Map.of("format", format, "lines", parsed.lines().size())
            );

            return new BankingDtos.BankStatementImportResponse(
                importId,
                importId,
                req.bankLedgerId(),
                fileName,
                format,
                parsed.fromDate(),
                parsed.toDate(),
                ExactMoneyValidator.formatExactScale(parsed.openingBalance(), scale),
                ExactMoneyValidator.formatExactScale(parsed.closingBalance(), scale),
                parsed.lines().size(),
                parsed.lines().size(),
                0,
                now
            );
        });
    }

    public BankingDtos.BrsSummaryDto getBrsSummary(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID bankLedgerId,
        LocalDate rawAsOfDate
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            LocalDate asOfDate = rawAsOfDate != null ? rawAsOfDate : defaultAsOfDate(companyId);
            if (authorityGuard.currentSnapshot().writerLeaseHeld()) {
                ensureBankAllocationsForPostedLines(companyId, bankLedgerId);
            }
            return computeBrsSummaryInTx(companyId, bankLedgerId, asOfDate);
        });
    }

    public BankingDtos.AutoMatchBrsResponse autoMatchBrs(
        MoneyCraftPrincipal actor,
        UUID companyId,
        BankingDtos.AutoMatchBrsRequest req
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (req == null || req.bankLedgerId() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-BNK-LEDGER-REQUIRED",
                "Bank Ledger Required",
                "Field 'bankLedgerId' is required."
            );
        }
        Integer explicitWindow = req.dateWindowDays() != null ? req.dateWindowDays() : req.dateToleranceDays();
        int windowDays = explicitWindow != null ? Math.max(0, explicitWindow) : 5;

        return businessTxTemplate.execute(tx -> {
            sequenceHelper.allocateForCreate(companyId, false);
            ensurePrincipalProjected(actor);
            ensureBankAllocationsForPostedLines(companyId, req.bankLedgerId());

            List<CandidateAlloc> unrecAllocs = loadUnreconciledAllocations(companyId, req.bankLedgerId());
            List<CandidateStmtLine> unmatchedLines = loadUnmatchedStatementLines(
                companyId, req.bankLedgerId(), req.statementImportId()
            );

            Set<UUID> matchedAllocIds = new HashSet<>();
            Set<UUID> matchedLineIds = new HashSet<>();
            Set<UUID> touchedImportIds = new HashSet<>();
            int matchedCount = 0;

            // Pass 1: AUTO_EXACT (exact amount + matching instrument_no / transfer_mode_ref / utr_reference)
            for (CandidateAlloc alloc : unrecAllocs) {
                for (CandidateStmtLine sLine : unmatchedLines) {
                    if (matchedLineIds.contains(sLine.id())) {
                        continue;
                    }
                    if (!sameDirectionAndAmount(alloc, sLine)) {
                        continue;
                    }
                    if (referencesMatch(alloc, sLine)) {
                        persistMatch(
                            companyId,
                            actor.principalId(),
                            alloc,
                            sLine,
                            "AUTO_EXACT",
                            new BigDecimal("100.00")
                        );
                        matchedAllocIds.add(alloc.id());
                        matchedLineIds.add(sLine.id());
                        touchedImportIds.add(sLine.statementImportId());
                        matchedCount++;
                        break;
                    }
                }
            }

            // Pass 2: AUTO_INSTRUMENT_WINDOW (exact amount within +-dateWindowDays)
            for (CandidateAlloc alloc : unrecAllocs) {
                if (matchedAllocIds.contains(alloc.id())) {
                    continue;
                }
                CandidateStmtLine bestLine = null;
                long bestDayDiff = Long.MAX_VALUE;
                for (CandidateStmtLine sLine : unmatchedLines) {
                    if (matchedLineIds.contains(sLine.id())) {
                        continue;
                    }
                    if (!sameDirectionAndAmount(alloc, sLine)) {
                        continue;
                    }
                    LocalDate allocDate = alloc.instrumentDate() != null ? alloc.instrumentDate() : alloc.voucherDate();
                    long diff = Math.abs(ChronoUnit.DAYS.between(allocDate, sLine.txnDate()));
                    if (diff <= windowDays && diff < bestDayDiff) {
                        bestDayDiff = diff;
                        bestLine = sLine;
                    }
                }
                if (bestLine != null) {
                    persistMatch(
                        companyId,
                        actor.principalId(),
                        alloc,
                        bestLine,
                        "AUTO_INSTRUMENT_WINDOW",
                        new BigDecimal("90.00")
                    );
                    matchedAllocIds.add(alloc.id());
                    matchedLineIds.add(bestLine.id());
                    touchedImportIds.add(bestLine.statementImportId());
                    matchedCount++;
                }
            }

            for (UUID impId : touchedImportIds) {
                refreshImportMatchedCount(companyId, impId);
            }

            LocalDate asOfDate = defaultAsOfDate(companyId);
            BankingDtos.BrsSummaryDto summary = computeBrsSummaryInTx(companyId, req.bankLedgerId(), asOfDate);

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "BRS_AUTO_MATCH",
                "BANK_RECONCILIATION",
                req.bankLedgerId().toString(),
                "SUCCESS",
                null,
                Map.of("matchedCount", matchedCount)
            );

            return new BankingDtos.AutoMatchBrsResponse(
                matchedCount,
                summary.unreconciledAllocations().size(),
                summary
            );
        });
    }

    public BankingDtos.BrsSummaryDto manualReconcileBrs(
        MoneyCraftPrincipal actor,
        UUID companyId,
        BankingDtos.ManualReconcileBrsRequest req
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (req == null || req.bankAllocationId() == null || req.bankersDate() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-BNK-RECONCILE-ARGS-REQUIRED",
                "Reconciliation Fields Required",
                "Both 'bankAllocationId' and 'bankersDate' are required."
            );
        }

        return businessTxTemplate.execute(tx -> {
            sequenceHelper.allocateForCreate(companyId, false);
            ensurePrincipalProjected(actor);

            List<AllocMeta> rows = businessJdbcTemplate.query(
                """
                SELECT bank_ledger_id, amount
                  FROM mc_bank_allocation
                 WHERE company_id = ? AND id = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> new AllocMeta(
                    rs.getObject("bank_ledger_id", UUID.class),
                    rs.getBigDecimal("amount")
                ),
                companyId,
                req.bankAllocationId()
            );
            if (rows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-BNK-ALLOCATION-NOT-FOUND",
                    "Bank Allocation Not Found",
                    "Bank allocation " + req.bankAllocationId() + " does not exist."
                );
            }
            AllocMeta meta = rows.getFirst();

            businessJdbcTemplate.update(
                """
                UPDATE mc_bank_allocation
                   SET bankers_date = ?,
                       reconciliation_status = 'MANUALLY_RECONCILED',
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                req.bankersDate(),
                companyId,
                req.bankAllocationId()
            );

            if (req.statementLineId() != null) {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_brs_match (
                        id, company_id, statement_line_id, bank_allocation_id,
                        match_method, confidence_score, matched_amount, bankers_date, reconciled_by
                    ) VALUES (?, ?, ?, ?, 'MANUAL', 100, ?, ?, ?)
                    ON CONFLICT (company_id, statement_line_id, bank_allocation_id) DO NOTHING
                    """,
                    UUID.randomUUID(),
                    companyId,
                    req.statementLineId(),
                    req.bankAllocationId(),
                    meta.amount(),
                    req.bankersDate(),
                    actor.principalId()
                );
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_bank_statement_line
                       SET match_status = 'RECONCILED'
                     WHERE company_id = ? AND id = ?
                    """,
                    companyId,
                    req.statementLineId()
                );
                List<UUID> impIds = businessJdbcTemplate.query(
                    "SELECT statement_import_id FROM mc_bank_statement_line WHERE company_id = ? AND id = ?",
                    (rs, r) -> rs.getObject("statement_import_id", UUID.class),
                    companyId,
                    req.statementLineId()
                );
                for (UUID impId : impIds) {
                    refreshImportMatchedCount(companyId, impId);
                }
            }

            LocalDate asOfDate = defaultAsOfDate(companyId);
            return computeBrsSummaryInTx(
                companyId,
                meta.bankLedgerId(),
                req.bankersDate().isAfter(asOfDate) ? req.bankersDate() : asOfDate
            );
        });
    }

    public List<BankingDtos.PayrollHeadDto> listPayrollHeads(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> businessJdbcTemplate.query(
            """
            SELECT ph.id, ph.name, ph.pay_head_type, ph.calculation_type, ph.ledger_id,
                   l.name AS ledger_name, ph.affect_net_salary, ph.payslip_display_name,
                   ph.active, ph.version_no
              FROM mc_payroll_head ph
              JOIN mc_ledger l
                ON l.company_id = ph.company_id
               AND l.id = ph.ledger_id
             WHERE ph.company_id = ?
             ORDER BY ph.name ASC
            """,
            (rs, rowNum) -> new BankingDtos.PayrollHeadDto(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("pay_head_type"),
                rs.getString("calculation_type"),
                rs.getObject("ledger_id", UUID.class),
                rs.getString("ledger_name"),
                rs.getBoolean("affect_net_salary"),
                rs.getString("payslip_display_name"),
                rs.getBoolean("active"),
                rs.getLong("version_no")
            ),
            companyId
        ));
    }

    public BankingDtos.PayrollHeadDto createPayrollHead(
        MoneyCraftPrincipal actor,
        UUID companyId,
        BankingDtos.CreatePayrollHeadRequest req
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (req == null || req.name() == null || req.name().isBlank() || req.ledgerId() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-PAY-HEAD-INVALID",
                "Payroll Head Name and Ledger Required",
                "Fields 'name' and 'ledgerId' are required."
            );
        }
        String headType = firstNonBlank(req.payHeadType(), "EARNINGS_FOR_EMPLOYEES").toUpperCase(Locale.ROOT);
        String calcType = firstNonBlank(req.calculationType(), "FLAT_RATE").toUpperCase(Locale.ROOT);
        boolean affectNet = req.affectNetSalary() == null || req.affectNetSalary();
        String name = req.name().trim();
        String displayName = firstNonBlank(req.payslipDisplayName(), name);

        return businessTxTemplate.execute(tx -> {
            sequenceHelper.allocateForCreate(companyId, false);
            String ledgerName = requireLedgerName(companyId, req.ledgerId());
            UUID id = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_payroll_head (
                    id, company_id, name, pay_head_type, calculation_type,
                    ledger_id, affect_net_salary, payslip_display_name, active, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 1)
                """,
                id, companyId, name, headType, calcType, req.ledgerId(), affectNet, displayName
            );
            return new BankingDtos.PayrollHeadDto(
                id, name, headType, calcType, req.ledgerId(), ledgerName, affectNet, displayName, true, 1L
            );
        });
    }

    public List<BankingDtos.EmployeeDto> listEmployees(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> businessJdbcTemplate.query(
            """
            SELECT id, employee_code, name, department, designation, cost_centre_id,
                   date_of_joining, date_of_resignation, pan_plain_masked, uan_no, esi_no,
                   bank_details_gcm, active, version_no
              FROM mc_employee
             WHERE company_id = ?
             ORDER BY employee_code ASC
            """,
            (rs, rowNum) -> new BankingDtos.EmployeeDto(
                rs.getObject("id", UUID.class),
                rs.getString("employee_code"),
                rs.getString("name"),
                rs.getString("department"),
                rs.getString("designation"),
                rs.getObject("cost_centre_id", UUID.class),
                rs.getObject("date_of_joining", LocalDate.class),
                rs.getObject("date_of_resignation", LocalDate.class),
                rs.getString("pan_plain_masked"),
                rs.getString("uan_no"),
                rs.getString("esi_no"),
                rs.getBytes("bank_details_gcm") != null,
                rs.getBoolean("active"),
                rs.getLong("version_no")
            ),
            companyId
        ));
    }

    public BankingDtos.EmployeeDto createEmployee(
        MoneyCraftPrincipal actor,
        UUID companyId,
        BankingDtos.CreateEmployeeRequest req
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        String resolvedName = req == null ? null : firstNonBlank(req.name(), req.employeeName());
        if (req == null || req.employeeCode() == null || req.employeeCode().isBlank()
            || resolvedName == null || resolvedName.isBlank() || req.dateOfJoining() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-PAY-EMPLOYEE-INVALID",
                "Employee Code, Name, and Date of Joining Required",
                "Fields 'employeeCode', 'name', and 'dateOfJoining' are required."
            );
        }

        String code = req.employeeCode().trim();
        String name = resolvedName.trim();
        String resolvedUan = firstNonBlank(req.uanNo(), req.uan());
        String resolvedBankAcc = firstNonBlank(req.bankAccountNo(), req.bankAccountNumber());
        byte[] panSiv = null;
        String panMasked = null;
        if (req.pan() != null && !req.pan().isBlank()) {
            String cleanPan = req.pan().trim().toUpperCase(Locale.ROOT);
            panSiv = tinkCryptoService.encryptDeterministicSiv(companyId, "mc_employee", "pan_siv", cleanPan);
            panMasked = ExactMoneyValidator.maskPan(cleanPan);
        }

        UUID empId = UUID.randomUUID();
        byte[] bankGcm = null;
        if (resolvedBankAcc != null && !resolvedBankAcc.isBlank()) {
            String json = objectMapper.writeValueAsString(Map.of(
                "accountNo", resolvedBankAcc.trim(),
                "ifsc", firstNonBlank(req.bankIfsc(), ""),
                "bankName", firstNonBlank(req.bankName(), "")
            ));
            byte[] ad = ("mc_employee:" + companyId + ":" + empId).getBytes(StandardCharsets.UTF_8);
            bankGcm = tinkCryptoService.encryptGcm(ad, json.getBytes(StandardCharsets.UTF_8));
        }

        final byte[] finalPanSiv = panSiv;
        final String finalPanMasked = panMasked;
        final byte[] finalBankGcm = bankGcm;

        return businessTxTemplate.execute(tx -> {
            sequenceHelper.allocateForCreate(companyId, false);
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_employee (
                    id, company_id, employee_code, name, department, designation,
                    cost_centre_id, date_of_joining, date_of_resignation,
                    pan_siv, pan_plain_masked, uan_no, esi_no, bank_details_gcm,
                    active, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, 1)
                """,
                empId,
                companyId,
                code,
                name,
                trimMax(req.department(), 120),
                trimMax(req.designation(), 120),
                req.costCentreId(),
                req.dateOfJoining(),
                req.dateOfResignation(),
                finalPanSiv,
                finalPanMasked,
                trimMax(resolvedUan, 24),
                trimMax(req.esiNo(), 24),
                finalBankGcm
            );

            return new BankingDtos.EmployeeDto(
                empId,
                code,
                name,
                req.department(),
                req.designation(),
                req.costCentreId(),
                req.dateOfJoining(),
                req.dateOfResignation(),
                finalPanMasked,
                resolvedUan,
                req.esiNo(),
                finalBankGcm != null,
                true,
                1L
            );
        });
    }

    public List<BankingDtos.OcrStagedDocumentDto> listOcrDocuments(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            int scale = loadCompanyScale(companyId);
            return businessJdbcTemplate.query(
                """
                SELECT id, source_file_name, content_type, target_voucher_category,
                       extracted_supplier_name, extracted_supplier_gstin, extracted_invoice_no,
                       extracted_invoice_date, extracted_total_amount, extracted_tax_amount,
                       review_status, mapped_party_ledger_id, promoted_journal_id,
                       reviewed_by, reviewed_at, version_no
                  FROM mc_imported_doc_staging
                 WHERE company_id = ?
                 ORDER BY created_at DESC
                """,
                (rs, rowNum) -> mapOcrRow(rs, scale),
                companyId
            );
        });
    }

    public BankingDtos.OcrStagedDocumentDto stageOcrDocument(
        MoneyCraftPrincipal actor,
        UUID companyId,
        BankingDtos.CreateOcrStagedDocRequest req
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireInventoryWriteRole(rbacGuard, actor, companyId);
        if (req == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-OCR-BODY-REQUIRED",
                "OCR Document Payload Required",
                "Request body is required."
            );
        }

        String fileName = firstNonBlank(req.fileName(), "scanned-invoice.pdf");
        String contentType = firstNonBlank(req.contentType(), "application/pdf");
        String targetCat = resolveTargetCategory(req.targetVoucherCategory(), req.documentType());

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, false);
            ensurePrincipalProjected(actor);
            int scale = seq.currencyScale();

            BigDecimal totalAmt = req.totalAmount() != null && !req.totalAmount().isBlank()
                ? ExactMoneyValidator.parseAndValidateExactScale(req.totalAmount(), scale, "totalAmount")
                : BigDecimal.ZERO;
            BigDecimal taxAmt = req.taxAmount() != null && !req.taxAmount().isBlank()
                ? ExactMoneyValidator.parseAndValidateExactScale(req.taxAmount(), scale, "taxAmount")
                : BigDecimal.ZERO;

            byte[] rawBlob = req.base64Blob() != null && !req.base64Blob().isBlank()
                ? Base64.getDecoder().decode(req.base64Blob().trim())
                : (fileName + ":" + firstNonBlank(req.invoiceNumber(), "") + ":" + totalAmt)
                    .getBytes(StandardCharsets.UTF_8);
            byte[] fileSha256 = sha256(rawBlob);

            UUID docId = UUID.randomUUID();
            byte[] ad = ("mc_imported_doc_staging:" + companyId + ":" + docId).getBytes(StandardCharsets.UTF_8);
            byte[] encryptedBlob = tinkCryptoService.encryptGcm(ad, rawBlob);

            String payloadJson = req.rawExtractedJson() != null && !req.rawExtractedJson().isBlank()
                ? req.rawExtractedJson()
                : objectMapper.writeValueAsString(Map.of(
                    "invoiceNumber", firstNonBlank(req.invoiceNumber(), ""),
                    "supplierName", firstNonBlank(req.supplierName(), ""),
                    "totalAmount", ExactMoneyValidator.formatExactScale(totalAmt, scale)
                ));

            LocalDate invDate = req.invoiceDate() != null ? req.invoiceDate() : defaultAsOfDate(companyId);

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_imported_doc_staging (
                    id, company_id, source_file_name, content_type, file_sha256,
                    encrypted_blob_gcm, ocr_provider, review_status, target_voucher_category,
                    extracted_supplier_name, extracted_supplier_gstin, extracted_invoice_no,
                    extracted_invoice_date, extracted_total_amount, extracted_tax_amount,
                    extracted_text, extracted_payload_json, mapped_party_ledger_id,
                    version_no, created_by
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, 'LOCAL_MANUAL', 'EXTRACTED_PENDING_REVIEW', ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, CAST(? AS jsonb), ?,
                    1, ?
                )
                """,
                docId,
                companyId,
                fileName,
                contentType,
                fileSha256,
                encryptedBlob,
                targetCat,
                trimMax(req.supplierName(), 200),
                trimMax(req.supplierGstin(), 15),
                trimMax(req.invoiceNumber(), 64),
                invDate,
                totalAmt,
                taxAmt,
                firstNonBlank(req.extractedText(), fileName),
                payloadJson,
                req.mappedPartyLedgerId(),
                actor.principalId()
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "OCR_DOCUMENT_STAGE",
                "OCR_STAGED_DOC",
                docId.toString(),
                "SUCCESS",
                null,
                Map.of("fileName", fileName, "targetCategory", targetCat)
            );

            return businessJdbcTemplate.query(
                """
                SELECT id, source_file_name, content_type, target_voucher_category,
                       extracted_supplier_name, extracted_supplier_gstin, extracted_invoice_no,
                       extracted_invoice_date, extracted_total_amount, extracted_tax_amount,
                       review_status, mapped_party_ledger_id, promoted_journal_id,
                       reviewed_by, reviewed_at, version_no
                  FROM mc_imported_doc_staging
                 WHERE company_id = ? AND id = ?
                """,
                (rs, rowNum) -> mapOcrRow(rs, scale),
                companyId,
                docId
            ).getFirst();
        });
    }

    public BankingDtos.ApproveConvertOcrResponse approveAndConvertOcrDocument(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID docId,
        BankingDtos.ApproveConvertOcrRequest req
    ) {
        authorityGuard.assertActiveLease();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        return businessTxTemplate.execute(tx -> {
            ensurePrincipalProjected(actor);
            int scale = loadCompanyScale(companyId);

            List<StagedDocRow> docs = businessJdbcTemplate.query(
                """
                SELECT id, review_status, target_voucher_category, extracted_supplier_name,
                       extracted_supplier_gstin, extracted_invoice_no, extracted_invoice_date,
                       extracted_total_amount, mapped_party_ledger_id, promoted_journal_id
                  FROM mc_imported_doc_staging
                 WHERE company_id = ? AND id = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> new StagedDocRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("review_status"),
                    rs.getString("target_voucher_category"),
                    rs.getString("extracted_supplier_name"),
                    rs.getString("extracted_supplier_gstin"),
                    rs.getString("extracted_invoice_no"),
                    rs.getObject("extracted_invoice_date", LocalDate.class),
                    rs.getBigDecimal("extracted_total_amount"),
                    rs.getObject("mapped_party_ledger_id", UUID.class),
                    rs.getObject("promoted_journal_id", UUID.class)
                ),
                companyId,
                docId
            );
            if (docs.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-OCR-DOC-NOT-FOUND",
                    "Staged OCR Document Not Found",
                    "OCR document " + docId + " does not exist."
                );
            }
            StagedDocRow doc = docs.getFirst();
            if ("APPROVED_CONVERTED".equals(doc.reviewStatus()) && doc.promotedJournalId() != null) {
                return new BankingDtos.ApproveConvertOcrResponse(
                    docId, doc.promotedJournalId(), "", "APPROVED_CONVERTED"
                );
            }

            BigDecimal verifiedAmt = (req != null && req.verifiedTotalAmount() != null
                && !req.verifiedTotalAmount().isBlank())
                ? ExactMoneyValidator.parseAndValidateExactScale(req.verifiedTotalAmount(), scale, "verifiedTotalAmount")
                : (doc.extractedTotalAmount() != null ? doc.extractedTotalAmount() : BigDecimal.ZERO);
            if (verifiedAmt.signum() <= 0) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-OCR-POSITIVE-AMOUNT-REQUIRED",
                    "Verified Amount Must Be Positive",
                    "Approved OCR conversion requires a positive verifiedTotalAmount."
                );
            }

            boolean salesSide = "SALES".equals(doc.targetCategory());
            String defaultVchType = switch (doc.targetCategory()) {
                case "SALES" -> "Sales";
                case "EXPENSE_PAYMENT" -> "Payment";
                case "CREDIT_NOTE" -> "Credit Note";
                case "DEBIT_NOTE" -> "Debit Note";
                default -> "Purchase";
            };
            String vchTypeName = (req != null && req.voucherTypeName() != null && !req.voucherTypeName().isBlank())
                ? req.voucherTypeName().trim()
                : defaultVchType;

            UUID partyLedgerId = resolveOrCreatePartyLedger(
                companyId,
                req != null ? req.partyLedgerId() : null,
                doc.mappedPartyLedgerId(),
                doc.supplierName(),
                doc.supplierGstin(),
                salesSide
            );
            UUID counterLedgerId = resolveOrCreateCounterLedger(
                companyId,
                req != null ? req.expenseOrPurchaseLedgerId() : null,
                doc.targetCategory()
            );

            boolean partyBillWise = Boolean.TRUE.equals(businessJdbcTemplate.queryForObject(
                "SELECT maintain_balances_bill_by_bill FROM mc_ledger WHERE company_id = ? AND id = ?",
                Boolean.class,
                companyId,
                partyLedgerId
            ));

            String amtStr = ExactMoneyValidator.formatExactScale(verifiedAmt, scale);
            LocalDate vchDate = (req != null && req.voucherDate() != null)
                ? req.voucherDate()
                : (doc.invoiceDate() != null ? doc.invoiceDate() : defaultAsOfDate(companyId));

            String partySide = salesSide ? "DR" : "CR";
            String counterSide = salesSide ? "CR" : "DR";
            String billRef = firstNonBlank(doc.invoiceNo(), "OCR-" + docId.toString().substring(0, 8));

            List<VoucherDtos.BillAllocationDto> partyBills = partyBillWise
                ? List.of(new VoucherDtos.BillAllocationDto(
                    null, "NEW_REF", billRef, null, vchDate, partySide, amtStr, null, null, null, null, null
                ))
                : List.of();

            VoucherDtos.UpsertVoucherRequest vchReq = new VoucherDtos.UpsertVoucherRequest(
                null,
                vchTypeName,
                null,
                null,
                vchDate,
                vchDate,
                null,
                "AS_VOUCHER",
                "NORMAL",
                "POST",
                doc.invoiceNo(),
                doc.invoiceDate(),
                partyLedgerId,
                counterLedgerId,
                null,
                null,
                doc.supplierGstin(),
                doc.supplierName(),
                null,
                false,
                null,
                null,
                null,
                firstNonBlank(
                    req != null ? req.narration() : null,
                    "Human-verified OCR conversion: " + billRef
                ),
                null,
                null,
                List.of(
                    new VoucherDtos.VoucherLineRequest(
                        1, "PARTY", partyLedgerId, partySide, amtStr,
                        null, null, null, null, null, null, null, null,
                        partyBills, List.of(), List.of()
                    ),
                    new VoucherDtos.VoucherLineRequest(
                        2, "ACCOUNTING", counterLedgerId, counterSide, amtStr,
                        null, null, null, null, null, null, null, null,
                        List.of(), List.of(), List.of()
                    )
                ),
                List.of()
            );

            VoucherDtos.VoucherDetailDto created = voucherService.createImportedVoucher(
                actor, companyId, vchReq, "OCR_STAGED", "CREATE"
            );

            businessJdbcTemplate.update(
                """
                UPDATE mc_imported_doc_staging
                   SET review_status = 'APPROVED_CONVERTED',
                       mapped_party_ledger_id = ?,
                       promoted_journal_id = ?,
                       reviewed_by = ?,
                       reviewed_at = clock_timestamp(),
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                partyLedgerId,
                created.id(),
                actor.principalId(),
                companyId,
                docId
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "OCR_DOCUMENT_APPROVE_CONVERT",
                "OCR_STAGED_DOC",
                docId.toString(),
                "SUCCESS",
                null,
                Map.of("promotedJournalId", created.id().toString())
            );

            return new BankingDtos.ApproveConvertOcrResponse(
                docId,
                created.id(),
                created.voucherNumber(),
                "APPROVED_CONVERTED"
            );
        });
    }

    private BankingDtos.BrsSummaryDto computeBrsSummaryInTx(
        UUID companyId,
        UUID bankLedgerId,
        LocalDate asOfDate
    ) {
        int scale = loadCompanyScale(companyId);
        List<BankLedgerRow> ledgers = businessJdbcTemplate.query(
            """
            SELECT name, opening_balance, opening_side
              FROM mc_ledger
             WHERE company_id = ? AND id = ?
            """,
            (rs, rowNum) -> new BankLedgerRow(
                rs.getString("name"),
                rs.getBigDecimal("opening_balance"),
                rs.getString("opening_side")
            ),
            companyId,
            bankLedgerId
        );
        if (ledgers.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-BNK-LEDGER-NOT-FOUND",
                "Bank Ledger Not Found",
                "Bank ledger " + bankLedgerId + " does not exist."
            );
        }
        BankLedgerRow bank = ledgers.getFirst();

        BigDecimal openSigned = "DR".equals(bank.openingSide())
            ? bank.openingBalance()
            : bank.openingBalance().negate();

        BigDecimal glDelta = businessJdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(CASE WHEN e.side = 'DR' THEN e.amount ELSE -e.amount END), 0)
              FROM mc_entry e
              JOIN mc_journal j
                ON j.company_id = e.company_id
               AND j.id = e.journal_id
             WHERE j.company_id = ?
               AND e.ledger_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND e.posting_batch_no = j.current_posting_batch_no
               AND e.is_reversal = false
               AND j.effective_date <= ?
            """,
            BigDecimal.class,
            companyId,
            bankLedgerId,
            asOfDate
        );
        BigDecimal booksBalance = openSigned.add(glDelta != null ? glDelta : BigDecimal.ZERO);

        List<BankingDtos.BrsAllocationRowDto> unrec = new ArrayList<>();
        BigDecimal unrefDr = BigDecimal.ZERO;
        BigDecimal unrefCr = BigDecimal.ZERO;

        List<RawBrsAlloc> allocRows = businessJdbcTemplate.query(
            """
            SELECT ba.id, ba.journal_id, COALESCE(j.voucher_number, '1') AS vch_no,
                   vt.name AS vch_type, j.effective_date, ba.transaction_type,
                   ba.instrument_no, ba.instrument_date,
                   COALESCE(NULLIF(ba.favouring_name, ''), NULLIF(j.narration, ''), vt.name) AS party_narr,
                   trim(ba.side) AS side, ba.amount, ba.bankers_date, ba.reconciliation_status
              FROM mc_bank_allocation ba
              JOIN mc_journal j
                ON j.company_id = ba.company_id
               AND j.id = ba.journal_id
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id
               AND vt.id = j.voucher_type_id
             WHERE ba.company_id = ?
               AND ba.bank_ledger_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND j.effective_date <= ?
               AND (ba.bankers_date IS NULL OR ba.bankers_date > ?)
             ORDER BY j.effective_date ASC, ba.created_at ASC
            """,
            (rs, rowNum) -> new RawBrsAlloc(
                rs.getObject("id", UUID.class),
                rs.getObject("journal_id", UUID.class),
                rs.getString("vch_no"),
                rs.getString("vch_type"),
                rs.getObject("effective_date", LocalDate.class),
                rs.getString("transaction_type"),
                rs.getString("instrument_no"),
                rs.getObject("instrument_date", LocalDate.class),
                rs.getString("party_narr"),
                rs.getString("side"),
                rs.getBigDecimal("amount"),
                rs.getObject("bankers_date", LocalDate.class),
                rs.getString("reconciliation_status")
            ),
            companyId,
            bankLedgerId,
            asOfDate,
            asOfDate
        );

        for (RawBrsAlloc r : allocRows) {
            boolean dr = "DR".equals(r.side());
            if (dr) {
                unrefDr = unrefDr.add(r.amount());
            } else {
                unrefCr = unrefCr.add(r.amount());
            }
            unrec.add(new BankingDtos.BrsAllocationRowDto(
                r.id(),
                r.id(),
                r.journalId(),
                r.vchNo(),
                r.vchType(),
                r.vchDate(),
                r.txnType(),
                r.instrumentNo(),
                r.instrumentDate(),
                r.partyNarr(),
                r.side(),
                ExactMoneyValidator.formatExactScale(r.amount(), scale),
                ExactMoneyValidator.formatExactScale(dr ? r.amount() : BigDecimal.ZERO, scale),
                ExactMoneyValidator.formatExactScale(dr ? BigDecimal.ZERO : r.amount(), scale),
                r.bankersDate(),
                r.reconStatus()
            ));
        }

        BigDecimal computedBankBal = booksBalance.subtract(unrefDr).add(unrefCr);

        List<BigDecimal> latestClosings = businessJdbcTemplate.query(
            """
            SELECT closing_balance
              FROM mc_bank_statement_import
             WHERE company_id = ? AND bank_ledger_id = ?
             ORDER BY statement_to_date DESC, imported_at DESC
             LIMIT 1
            """,
            (rs, r) -> rs.getBigDecimal("closing_balance"),
            companyId,
            bankLedgerId
        );
        BigDecimal stmtClosing = latestClosings.isEmpty() ? computedBankBal : latestClosings.getFirst();

        List<BankingDtos.BrsStatementLineDto> unmatchedLines = businessJdbcTemplate.query(
            """
            SELECT id, line_no, txn_date, value_date, description, instrument_no, utr_reference,
                   withdrawal_amount, deposit_amount, running_balance, match_status
              FROM mc_bank_statement_line
             WHERE company_id = ?
               AND bank_ledger_id = ?
               AND match_status = 'UNMATCHED'
             ORDER BY txn_date ASC, line_no ASC
            """,
            (rs, rowNum) -> {
                BigDecimal w = rs.getBigDecimal("withdrawal_amount");
                BigDecimal d = rs.getBigDecimal("deposit_amount");
                BigDecimal rb = rs.getBigDecimal("running_balance");
                String ws = ExactMoneyValidator.formatExactScale(w, scale);
                String ds = ExactMoneyValidator.formatExactScale(d, scale);
                return new BankingDtos.BrsStatementLineDto(
                    rs.getObject("id", UUID.class),
                    rs.getInt("line_no"),
                    rs.getObject("txn_date", LocalDate.class),
                    rs.getObject("value_date", LocalDate.class),
                    rs.getString("description"),
                    rs.getString("instrument_no"),
                    rs.getString("utr_reference"),
                    ws,
                    ds,
                    ws,
                    ds,
                    rb != null ? ExactMoneyValidator.formatExactScale(rb, scale) : null,
                    rs.getString("match_status")
                );
            },
            companyId,
            bankLedgerId
        );

        List<BankingDtos.BrsMatchRowDto> matches = businessJdbcTemplate.query(
            """
            SELECT m.id, m.bank_allocation_id, m.statement_line_id,
                   COALESCE(j.voucher_number, '1') AS vch_no, ba.instrument_no,
                   m.matched_amount, m.bankers_date, m.match_method
              FROM mc_brs_match m
              JOIN mc_bank_allocation ba
                ON ba.company_id = m.company_id
               AND ba.id = m.bank_allocation_id
              JOIN mc_journal j
                ON j.company_id = ba.company_id
               AND j.id = ba.journal_id
             WHERE m.company_id = ?
               AND ba.bank_ledger_id = ?
             ORDER BY m.reconciled_at DESC
            """,
            (rs, rowNum) -> new BankingDtos.BrsMatchRowDto(
                rs.getObject("id", UUID.class),
                rs.getObject("bank_allocation_id", UUID.class),
                rs.getObject("statement_line_id", UUID.class),
                rs.getString("vch_no"),
                rs.getString("instrument_no"),
                ExactMoneyValidator.formatExactScale(rs.getBigDecimal("matched_amount"), scale),
                rs.getObject("bankers_date", LocalDate.class),
                rs.getString("match_method"),
                rs.getString("match_method")
            ),
            companyId,
            bankLedgerId
        );

        return new BankingDtos.BrsSummaryDto(
            bankLedgerId,
            bank.name(),
            asOfDate,
            ExactMoneyValidator.formatExactScale(booksBalance, scale),
            ExactMoneyValidator.formatExactScale(unrefDr, scale),
            ExactMoneyValidator.formatExactScale(unrefCr, scale),
            ExactMoneyValidator.formatExactScale(computedBankBal, scale),
            ExactMoneyValidator.formatExactScale(stmtClosing, scale),
            unrec,
            unmatchedLines,
            matches
        );
    }

    private void ensureBankAllocationsForPostedLines(UUID companyId, UUID bankLedgerId) {
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_bank_allocation (
                id, company_id, journal_id, journal_line_id, bank_ledger_id,
                transaction_type, instrument_no, instrument_date,
                favouring_name, side, amount, reconciliation_status
            )
            SELECT gen_random_uuid(), jl.company_id, jl.journal_id, jl.id, jl.ledger_id,
                   'OTHERS', j.supplier_invoice_no, j.effective_date,
                   LEFT(COALESCE(j.narration, ''), 200), jl.side, jl.amount, 'UNRECONCILED'
              FROM mc_journal_line jl
              JOIN mc_journal j
                ON j.company_id = jl.company_id
               AND j.id = jl.journal_id
             WHERE jl.company_id = ?
               AND jl.ledger_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND jl.amount > 0
               AND NOT EXISTS (
                   SELECT 1 FROM mc_bank_allocation ba
                    WHERE ba.company_id = jl.company_id
                      AND ba.journal_line_id = jl.id
               )
            """,
            companyId,
            bankLedgerId
        );
    }

    private List<CandidateAlloc> loadUnreconciledAllocations(UUID companyId, UUID bankLedgerId) {
        return businessJdbcTemplate.query(
            """
            SELECT ba.id, j.effective_date, ba.instrument_no, ba.instrument_date,
                   ba.transfer_mode_ref, trim(ba.side) AS side, ba.amount
              FROM mc_bank_allocation ba
              JOIN mc_journal j
                ON j.company_id = ba.company_id
               AND j.id = ba.journal_id
             WHERE ba.company_id = ?
               AND ba.bank_ledger_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND ba.reconciliation_status = 'UNRECONCILED'
               AND ba.bankers_date IS NULL
             ORDER BY j.effective_date ASC, ba.created_at ASC
               FOR UPDATE OF ba
            """,
            (rs, rowNum) -> new CandidateAlloc(
                rs.getObject("id", UUID.class),
                rs.getObject("effective_date", LocalDate.class),
                rs.getString("instrument_no"),
                rs.getObject("instrument_date", LocalDate.class),
                rs.getString("transfer_mode_ref"),
                rs.getString("side"),
                rs.getBigDecimal("amount")
            ),
            companyId,
            bankLedgerId
        );
    }

    private List<CandidateStmtLine> loadUnmatchedStatementLines(
        UUID companyId,
        UUID bankLedgerId,
        UUID statementImportId
    ) {
        return businessJdbcTemplate.query(
            """
            SELECT id, statement_import_id, txn_date, value_date, instrument_no,
                   utr_reference, description, withdrawal_amount, deposit_amount
              FROM mc_bank_statement_line
             WHERE company_id = ?
               AND bank_ledger_id = ?
               AND match_status = 'UNMATCHED'
               AND (?::uuid IS NULL OR statement_import_id = ?::uuid)
             ORDER BY txn_date ASC, line_no ASC
               FOR UPDATE
            """,
            (rs, rowNum) -> new CandidateStmtLine(
                rs.getObject("id", UUID.class),
                rs.getObject("statement_import_id", UUID.class),
                rs.getObject("txn_date", LocalDate.class),
                rs.getObject("value_date", LocalDate.class),
                rs.getString("instrument_no"),
                rs.getString("utr_reference"),
                rs.getString("description"),
                rs.getBigDecimal("withdrawal_amount"),
                rs.getBigDecimal("deposit_amount")
            ),
            companyId,
            bankLedgerId,
            statementImportId,
            statementImportId
        );
    }

    private void persistMatch(
        UUID companyId,
        UUID actorId,
        CandidateAlloc alloc,
        CandidateStmtLine sLine,
        String method,
        BigDecimal confidence
    ) {
        LocalDate bankersDate = sLine.valueDate() != null ? sLine.valueDate() : sLine.txnDate();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_brs_match (
                id, company_id, statement_line_id, bank_allocation_id,
                match_method, confidence_score, matched_amount, bankers_date, reconciled_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
            companyId,
            sLine.id(),
            alloc.id(),
            method,
            confidence,
            alloc.amount(),
            bankersDate,
            actorId
        );
        businessJdbcTemplate.update(
            """
            UPDATE mc_bank_allocation
               SET bankers_date = ?,
                   reconciliation_status = 'AUTO_MATCHED',
                   version_no = version_no + 1,
                   updated_at = clock_timestamp()
             WHERE company_id = ? AND id = ?
            """,
            bankersDate,
            companyId,
            alloc.id()
        );
        businessJdbcTemplate.update(
            """
            UPDATE mc_bank_statement_line
               SET match_status = 'RECONCILED'
             WHERE company_id = ? AND id = ?
            """,
            companyId,
            sLine.id()
        );
    }

    private void refreshImportMatchedCount(UUID companyId, UUID importId) {
        businessJdbcTemplate.update(
            """
            UPDATE mc_bank_statement_import
               SET matched_lines = (
                   SELECT COUNT(*) FROM mc_bank_statement_line
                    WHERE company_id = ? AND statement_import_id = ? AND match_status = 'RECONCILED'
               )
             WHERE company_id = ? AND id = ?
            """,
            companyId, importId, companyId, importId
        );
    }

    private static boolean sameDirectionAndAmount(CandidateAlloc alloc, CandidateStmtLine sLine) {
        if ("DR".equals(alloc.side())) {
            return sLine.depositAmount().compareTo(alloc.amount()) == 0;
        }
        return sLine.withdrawalAmount().compareTo(alloc.amount()) == 0;
    }

    private static boolean referencesMatch(CandidateAlloc alloc, CandidateStmtLine sLine) {
        String aRef = normalizeRefToken(firstNonBlank(alloc.instrumentNo(), alloc.transferModeRef()));
        if (aRef.isEmpty()) {
            return false;
        }
        String sInst = normalizeRefToken(sLine.instrumentNo());
        String sUtr = normalizeRefToken(sLine.utrReference());
        String sDesc = normalizeRefToken(sLine.description());
        return (!sInst.isEmpty() && (sInst.equals(aRef) || sInst.contains(aRef) || aRef.contains(sInst)))
            || (!sUtr.isEmpty() && (sUtr.equals(aRef) || sUtr.contains(aRef)))
            || (!sDesc.isEmpty() && sDesc.contains(aRef));
    }

    private static String normalizeRefToken(String s) {
        if (s == null) {
            return "";
        }
        String stripped = s.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return stripped.replaceFirst("^0+(?!$)", "");
    }

    private ParsedStatement parseStatement(
        String format,
        String rawText,
        BankingDtos.ImportBankStatementRequest req
    ) {
        if (req.lines() != null && !req.lines().isEmpty()) {
            return parseStructuredLines(req);
        }
        if (rawText == null || rawText.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-BNK-STATEMENT-CONTENT-REQUIRED",
                "Bank Statement Content Required",
                "Provide either 'rawContent'/'content' or structured 'lines'."
            );
        }
        return switch (format) {
            case "MT940" -> parseMt940(rawText, req);
            case "CAMT_053" -> parseCamt053(rawText, req);
            default -> parseCsv(rawText, req);
        };
    }

    private ParsedStatement parseStructuredLines(BankingDtos.ImportBankStatementRequest req) {
        List<ParsedStmtLine> list = new ArrayList<>();
        for (BankingDtos.StructuredStatementLineInput in : req.lines()) {
            BigDecimal w = parseOptionalDec(in.withdrawalAmount());
            BigDecimal d = parseOptionalDec(in.depositAmount());
            if (w.signum() <= 0 && d.signum() <= 0) {
                continue;
            }
            LocalDate dt = in.txnDate() != null ? in.txnDate() : LocalDate.now();
            BigDecimal rb = in.runningBalance() != null && !in.runningBalance().isBlank()
                ? parseSignedDec(in.runningBalance())
                : null;
            list.add(new ParsedStmtLine(
                dt, in.valueDate(), in.instrumentNo(), in.utrReference(), in.description(), w, d, rb
            ));
        }
        return finalizeParsedStatement(list, req, null, null);
    }

    private ParsedStatement parseCsv(String csv, BankingDtos.ImportBankStatementRequest req) {
        String[] rows = csv.split("\\r?\\n");
        List<ParsedStmtLine> lines = new ArrayList<>();
        int dateIdx = 0;
        int descIdx = 1;
        int instIdx = 2;
        int utrIdx = -1;
        int debitIdx = 3;
        int creditIdx = 4;
        int balIdx = 5;
        boolean headerChecked = false;

        for (String rawRow : rows) {
            String trimmed = rawRow.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> cols = splitCsvLine(trimmed);
            if (!headerChecked) {
                headerChecked = true;
                String joinedLower = trimmed.toLowerCase(Locale.ROOT);
                if (joinedLower.contains("date") && (joinedLower.contains("debit")
                    || joinedLower.contains("withdrawal") || joinedLower.contains("deposit")
                    || joinedLower.contains("credit") || joinedLower.contains("amount"))) {
                    instIdx = -1;
                    for (int i = 0; i < cols.size(); i++) {
                        String h = cols.get(i).trim().toLowerCase(Locale.ROOT);
                        if (h.equals("date") || (h.contains("date") && !h.contains("value") && dateIdx == 0)) {
                            dateIdx = i;
                        } else if (h.contains("desc") || h.contains("narr") || h.contains("particular")) {
                            descIdx = i;
                        } else if (h.contains("utr")) {
                            utrIdx = i;
                        } else if (h.contains("ref") || h.contains("chq") || h.contains("cheque")
                            || h.contains("inst")) {
                            instIdx = i;
                        } else if (h.contains("withdraw") || h.contains("debit") || h.equals("dr")) {
                            debitIdx = i;
                        } else if (h.contains("deposit") || h.contains("credit") || h.equals("cr")) {
                            creditIdx = i;
                        } else if (h.contains("bal")) {
                            balIdx = i;
                        }
                    }
                    continue;
                }
            }
            if (cols.size() < 3) {
                continue;
            }
            LocalDate txnDate = tryParseFlexDate(colAt(cols, dateIdx));
            if (txnDate == null) {
                continue;
            }
            String desc = colAt(cols, descIdx);
            String instVal = instIdx >= 0 ? colAt(cols, instIdx) : "";
            String utrVal = utrIdx >= 0 ? colAt(cols, utrIdx) : "";
            String instOut = firstNonBlank(instVal, utrVal);
            String utrOut = firstNonBlank(utrVal, instVal);
            BigDecimal debit = parseOptionalDec(colAt(cols, debitIdx));
            BigDecimal credit = parseOptionalDec(colAt(cols, creditIdx));
            if (debit.signum() <= 0 && credit.signum() <= 0) {
                continue;
            }
            String balRaw = colAt(cols, balIdx);
            BigDecimal runBal = !balRaw.isEmpty() ? parseSignedDec(balRaw) : null;
            lines.add(new ParsedStmtLine(txnDate, txnDate, instOut, utrOut, desc, debit, credit, runBal));
        }
        return finalizeParsedStatement(lines, req, null, null);
    }

    private ParsedStatement parseMt940(String mt940, BankingDtos.ImportBankStatementRequest req) {
        String[] rawLines = mt940.split("\\r?\\n");
        List<ParsedStmtLine> parsed = new ArrayList<>();
        BigDecimal openBal = null;
        BigDecimal closeBal = null;

        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(":60F:") || line.startsWith(":60M:")
                || line.startsWith(":62F:") || line.startsWith(":62M:")) {
                Matcher bm = MT940_BAL.matcher(line);
                if (bm.matches()) {
                    BigDecimal amt = new BigDecimal(bm.group(4).replace(',', '.'));
                    if ("D".equals(bm.group(1))) {
                        amt = amt.negate();
                    }
                    if (line.startsWith(":60")) {
                        openBal = amt;
                    } else {
                        closeBal = amt;
                    }
                }
            } else if (line.startsWith(":61:")) {
                Matcher m = MT940_TAG61.matcher(line);
                if (m.matches()) {
                    LocalDate dt = parseYyMmDd(m.group(1));
                    String dc = m.group(2);
                    BigDecimal amt = new BigDecimal(m.group(3).replace(',', '.'));
                    String tail = m.group(5) != null ? m.group(5).trim() : "";
                    String custRef = tail;
                    String bankRef = null;
                    int slashIdx = tail.indexOf("//");
                    if (slashIdx >= 0) {
                        custRef = tail.substring(0, slashIdx).trim();
                        bankRef = tail.substring(slashIdx + 2).trim();
                    }
                    if ("NONREF".equalsIgnoreCase(custRef)) {
                        custRef = null;
                    }
                    boolean credit = "C".equals(dc) || "RD".equals(dc);
                    parsed.add(new ParsedStmtLine(
                        dt,
                        dt,
                        custRef,
                        bankRef != null ? bankRef : custRef,
                        custRef != null ? custRef : "MT940 Entry",
                        credit ? BigDecimal.ZERO : amt,
                        credit ? amt : BigDecimal.ZERO,
                        null
                    ));
                }
            } else if (line.startsWith(":86:") && !parsed.isEmpty()) {
                ParsedStmtLine prev = parsed.removeLast();
                String info = line.substring(4).trim();
                parsed.add(new ParsedStmtLine(
                    prev.txnDate(),
                    prev.valueDate(),
                    prev.instrumentNo(),
                    prev.utrReference(),
                    info,
                    prev.withdrawalAmount(),
                    prev.depositAmount(),
                    prev.runningBalance()
                ));
            }
        }
        return finalizeParsedStatement(parsed, req, openBal, closeBal);
    }

    private ParsedStatement parseCamt053(String xmlText, BankingDtos.ImportBankStatementRequest req) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setNamespaceAware(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlText.getBytes(StandardCharsets.UTF_8)));

            NodeList entries = doc.getElementsByTagName("Ntry");
            List<ParsedStmtLine> list = new ArrayList<>();
            for (int i = 0; i < entries.getLength(); i++) {
                Element ntry = (Element) entries.item(i);
                BigDecimal amt = parseOptionalDec(firstChildText(ntry, "Amt"));
                if (amt.signum() <= 0) {
                    continue;
                }
                String ind = firstChildText(ntry, "CdtDbtInd").toUpperCase(Locale.ROOT);
                boolean credit = !"DBIT".equals(ind);
                String dtStr = firstNonBlank(
                    firstChildText(ntry, "Dt"),
                    firstChildText(ntry, "DtTm")
                );
                if (dtStr.length() >= 10) {
                    dtStr = dtStr.substring(0, 10);
                }
                LocalDate dt = tryParseFlexDate(dtStr);
                if (dt == null) {
                    dt = LocalDate.now();
                }
                String ref = firstNonBlank(
                    firstChildText(ntry, "ChqNb"),
                    firstChildText(ntry, "EndToEndId"),
                    firstChildText(ntry, "InstrId"),
                    firstChildText(ntry, "AcctSvcrRef")
                );
                String desc = firstNonBlank(
                    firstChildText(ntry, "AddtlNtryInf"),
                    firstChildText(ntry, "Ustrd"),
                    ref
                );
                list.add(new ParsedStmtLine(
                    dt, dt, ref, ref, desc, credit ? BigDecimal.ZERO : amt, credit ? amt : BigDecimal.ZERO, null
                ));
            }
            return finalizeParsedStatement(list, req, null, null);
        } catch (MoneyCraftException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-BNK-CAMT053-INVALID",
                "Malformed CAMT.053 XML",
                "Failed to parse ISO 20022 CAMT.053 bank statement XML."
            );
        }
    }

    private ParsedStatement finalizeParsedStatement(
        List<ParsedStmtLine> lines,
        BankingDtos.ImportBankStatementRequest req,
        BigDecimal parsedOpen,
        BigDecimal parsedClose
    ) {
        LocalDate minDate = lines.stream()
            .map(ParsedStmtLine::txnDate)
            .min(Comparator.naturalOrder())
            .orElse(LocalDate.now());
        LocalDate maxDate = lines.stream()
            .map(ParsedStmtLine::txnDate)
            .max(Comparator.naturalOrder())
            .orElse(minDate);

        LocalDate fromDate = req.statementFromDate() != null ? req.statementFromDate() : minDate;
        LocalDate toDate = req.statementToDate() != null ? req.statementToDate() : maxDate;
        if (toDate.isBefore(fromDate)) {
            toDate = fromDate;
        }

        BigDecimal openBal = req.openingBalance() != null && !req.openingBalance().isBlank()
            ? parseSignedDec(req.openingBalance())
            : (parsedOpen != null ? parsedOpen : BigDecimal.ZERO);

        BigDecimal closeBal;
        if (req.closingBalance() != null && !req.closingBalance().isBlank()) {
            closeBal = parseSignedDec(req.closingBalance());
        } else if (parsedClose != null) {
            closeBal = parsedClose;
        } else if (!lines.isEmpty() && lines.getLast().runningBalance() != null) {
            closeBal = lines.getLast().runningBalance();
        } else {
            BigDecimal net = BigDecimal.ZERO;
            for (ParsedStmtLine l : lines) {
                net = net.add(l.depositAmount()).subtract(l.withdrawalAmount());
            }
            closeBal = openBal.add(net);
        }

        return new ParsedStatement(fromDate, toDate, openBal, closeBal, lines);
    }

    private UUID resolveOrCreatePartyLedger(
        UUID companyId,
        UUID reqPartyId,
        UUID mappedPartyId,
        String supplierName,
        String supplierGstin,
        boolean salesSide
    ) {
        if (reqPartyId != null) {
            return reqPartyId;
        }
        if (mappedPartyId != null) {
            return mappedPartyId;
        }
        if (supplierGstin != null && !supplierGstin.isBlank()) {
            List<UUID> byGstin = businessJdbcTemplate.query(
                "SELECT id FROM mc_ledger WHERE company_id = ? AND upper(gstin) = ? LIMIT 1",
                (rs, r) -> rs.getObject("id", UUID.class),
                companyId,
                supplierGstin.trim().toUpperCase(Locale.ROOT)
            );
            if (!byGstin.isEmpty()) {
                return byGstin.getFirst();
            }
        }
        String partyName = firstNonBlank(supplierName, salesSide ? "OCR Customer A/c" : "OCR Supplier A/c");
        List<UUID> byName = businessJdbcTemplate.query(
            "SELECT id FROM mc_ledger WHERE company_id = ? AND lower(name) = lower(?) LIMIT 1",
            (rs, r) -> rs.getObject("id", UUID.class),
            companyId,
            partyName
        );
        if (!byName.isEmpty()) {
            return byName.getFirst();
        }

        String grpName = salesSide ? "Sundry Debtors" : "Sundry Creditors";
        UUID groupId = businessJdbcTemplate.queryForObject(
            "SELECT id FROM mc_account_group WHERE company_id = ? AND lower(name) = lower(?)",
            UUID.class,
            companyId,
            grpName
        );
        CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, false);
        UUID newLedgerId = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_ledger (
                id, company_id, group_id, name, maintain_balances_bill_by_bill,
                gstin, opening_balance, opening_side,
                master_seq_id, alter_id, active, version_no
            ) VALUES (?, ?, ?, ?, true, ?, 0, ?, ?, ?, true, 1)
            """,
            newLedgerId,
            companyId,
            groupId,
            partyName,
            trimMax(supplierGstin, 15),
            salesSide ? "DR" : "CR",
            seq.masterSeqId(),
            seq.alterId()
        );
        return newLedgerId;
    }

    private UUID resolveOrCreateCounterLedger(UUID companyId, UUID explicitId, String targetCategory) {
        if (explicitId != null) {
            return explicitId;
        }
        String targetGroup = switch (targetCategory) {
            case "SALES", "CREDIT_NOTE" -> "Sales Accounts";
            case "EXPENSE_PAYMENT" -> "Indirect Expenses";
            default -> "Purchase Accounts";
        };
        List<UUID> existing = businessJdbcTemplate.query(
            """
            SELECT l.id
              FROM mc_ledger l
              JOIN mc_account_group g
                ON g.company_id = l.company_id
               AND g.id = l.group_id
             WHERE l.company_id = ?
               AND lower(g.name) = lower(?)
               AND l.active = true
             ORDER BY l.created_at ASC
             LIMIT 1
            """,
            (rs, r) -> rs.getObject("id", UUID.class),
            companyId,
            targetGroup
        );
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID groupId = businessJdbcTemplate.queryForObject(
            "SELECT id FROM mc_account_group WHERE company_id = ? AND lower(name) = lower(?)",
            UUID.class,
            companyId,
            targetGroup
        );
        String defaultName = "Sales Accounts".equals(targetGroup)
            ? "OCR Sales A/c"
            : ("Indirect Expenses".equals(targetGroup) ? "OCR Expense A/c" : "OCR Purchases A/c");
        CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, false);
        UUID newId = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_ledger (
                id, company_id, group_id, name, opening_balance, opening_side,
                master_seq_id, alter_id, active, version_no
            ) VALUES (?, ?, ?, ?, 0, 'DR', ?, ?, true, 1)
            """,
            newId, companyId, groupId, defaultName, seq.masterSeqId(), seq.alterId()
        );
        return newId;
    }

    private BankingDtos.OcrStagedDocumentDto mapOcrRow(java.sql.ResultSet rs, int scale)
        throws java.sql.SQLException {
        String cat = rs.getString("target_voucher_category");
        String docType = switch (cat) {
            case "SALES" -> "SALES_INVOICE";
            case "EXPENSE_PAYMENT" -> "EXPENSE_RECEIPT";
            default -> "PURCHASE_INVOICE";
        };
        BigDecimal total = rs.getBigDecimal("extracted_total_amount");
        BigDecimal tax = rs.getBigDecimal("extracted_tax_amount");
        BigDecimal totSafe = total != null ? total : BigDecimal.ZERO;
        BigDecimal taxSafe = tax != null ? tax : BigDecimal.ZERO;
        BigDecimal taxable = totSafe.subtract(taxSafe).max(BigDecimal.ZERO);
        UUID promotedId = rs.getObject("promoted_journal_id", UUID.class);
        Timestamp revTs = rs.getTimestamp("reviewed_at");

        return new BankingDtos.OcrStagedDocumentDto(
            rs.getObject("id", UUID.class),
            rs.getString("source_file_name"),
            rs.getString("content_type"),
            docType,
            cat,
            firstNonBlank(rs.getString("extracted_supplier_name"), ""),
            rs.getString("extracted_supplier_gstin"),
            firstNonBlank(rs.getString("extracted_invoice_no"), ""),
            rs.getObject("extracted_invoice_date", LocalDate.class),
            ExactMoneyValidator.formatExactScale(taxable, scale),
            ExactMoneyValidator.formatExactScale(taxSafe, scale),
            ExactMoneyValidator.formatExactScale(totSafe, scale),
            "98.50",
            rs.getString("review_status"),
            rs.getObject("mapped_party_ledger_id", UUID.class),
            promotedId,
            promotedId,
            rs.getObject("reviewed_by", UUID.class),
            revTs != null ? revTs.toInstant() : null,
            rs.getLong("version_no")
        );
    }

    private void ensurePrincipalProjected(MoneyCraftPrincipal actor) {
        String kind = "SERVICE".equalsIgnoreCase(actor.kind()) ? "SERVICE" : "PERSON";
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_principal_projection (principal_id, kind, username, display_name, enabled, version_no)
            VALUES (?, ?, ?, ?, true, 1)
            ON CONFLICT (principal_id) DO NOTHING
            """,
            actor.principalId(),
            kind,
            actor.username(),
            actor.username()
        );
    }

    private String requireLedgerName(UUID companyId, UUID ledgerId) {
        List<String> names = businessJdbcTemplate.query(
            "SELECT name FROM mc_ledger WHERE company_id = ? AND id = ?",
            (rs, r) -> rs.getString("name"),
            companyId,
            ledgerId
        );
        if (names.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-LEDGER-NOT-FOUND",
                "Ledger Not Found",
                "Ledger " + ledgerId + " does not exist."
            );
        }
        return names.getFirst();
    }

    private int loadCompanyScale(UUID companyId) {
        Integer sc = businessJdbcTemplate.queryForObject(
            "SELECT currency_scale FROM mc_company WHERE id = ?",
            Integer.class,
            companyId
        );
        return sc != null ? sc : 2;
    }

    private LocalDate defaultAsOfDate(UUID companyId) {
        LocalDate maxVch = businessJdbcTemplate.queryForObject(
            "SELECT MAX(effective_date) FROM mc_journal WHERE company_id = ? AND status = 'POSTED'",
            LocalDate.class,
            companyId
        );
        if (maxVch != null) {
            return maxVch;
        }
        LocalDate fyFrom = businessJdbcTemplate.queryForObject(
            "SELECT financial_year_from FROM mc_company WHERE id = ?",
            LocalDate.class,
            companyId
        );
        return fyFrom != null ? fyFrom.plusYears(1).minusDays(1) : LocalDate.now();
    }

    private static String resolveTargetCategory(String rawCat, String rawDocType) {
        if (rawCat != null && !rawCat.isBlank()) {
            String up = rawCat.trim().toUpperCase(Locale.ROOT);
            if (Set.of("PURCHASE", "SALES", "EXPENSE_PAYMENT", "CREDIT_NOTE", "DEBIT_NOTE").contains(up)) {
                return up;
            }
        }
        if (rawDocType != null) {
            String up = rawDocType.trim().toUpperCase(Locale.ROOT);
            if ("SALES_INVOICE".equals(up)) {
                return "SALES";
            }
            if ("EXPENSE_RECEIPT".equals(up)) {
                return "EXPENSE_PAYMENT";
            }
        }
        return "PURCHASE";
    }

    private static String normalizeStmtFormat(String raw) {
        if (raw == null || raw.isBlank()) {
            return "CSV";
        }
        String up = raw.trim().toUpperCase(Locale.ROOT).replace('.', '_');
        if ("MT940".equals(up) || "CAMT_053".equals(up) || "CSV".equals(up) || "EXCEL".equals(up)) {
            return up;
        }
        return "CSV";
    }

    private static String extractAlphaPrefix(String rawLeaf) {
        if (rawLeaf == null) {
            return "";
        }
        String trimmed = rawLeaf.trim();
        int idx = 0;
        while (idx < trimmed.length() && !Character.isDigit(trimmed.charAt(idx))) {
            idx++;
        }
        return idx > 0 ? trimmed.substring(0, Math.min(idx, 16)) : "";
    }

    private static long parseLeafDigits(String rawLeaf, String fieldName) {
        if (rawLeaf == null || rawLeaf.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-BNK-LEAF-REQUIRED",
                "Cheque Leaf Number Required",
                "Field '" + fieldName + "' is required."
            );
        }
        String digits = rawLeaf.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-BNK-LEAF-INVALID",
                "Invalid Cheque Leaf Number",
                "Field '" + fieldName + "' must contain digits."
            );
        }
        return Long.parseLong(digits);
    }

    private static String formatChequeNo(String prefix, long num) {
        String p = prefix == null ? "" : prefix;
        return p + String.format("%06d", num);
    }

    private static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    private static String colAt(List<String> cols, int idx) {
        return idx >= 0 && idx < cols.size() ? cols.get(idx).trim() : "";
    }

    private static LocalDate tryParseFlexDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.trim();
        for (DateTimeFormatter fmt : FLEX_DATE_FORMATS) {
            try {
                return LocalDate.parse(clean, fmt);
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        return null;
    }

    private static LocalDate parseYyMmDd(String yymmdd) {
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int mm = Integer.parseInt(yymmdd.substring(2, 4));
        int dd = Integer.parseInt(yymmdd.substring(4, 6));
        return LocalDate.of(2000 + yy, mm, dd);
    }

    private static BigDecimal parseOptionalDec(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        String cleaned = raw.replace(",", "").replace("\u20B9", "").trim();
        if (cleaned.isEmpty() || "-".equals(cleaned)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(cleaned).abs();
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal parseSignedDec(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        String cleaned = raw.replace(",", "").replace("\u20B9", "").trim();
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static String firstChildText(Element parent, String tagName) {
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl.getLength() == 0 || nl.item(0) == null) {
            return "";
        }
        String t = nl.item(0).getTextContent();
        return t == null ? "" : t.trim();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String trimMax(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= maxLen ? t : t.substring(0, maxLen);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private record ParsedStmtLine(
        LocalDate txnDate,
        LocalDate valueDate,
        String instrumentNo,
        String utrReference,
        String description,
        BigDecimal withdrawalAmount,
        BigDecimal depositAmount,
        BigDecimal runningBalance
    ) {
    }

    private record ParsedStatement(
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        List<ParsedStmtLine> lines
    ) {
    }

    private record BankLedgerRow(String name, BigDecimal openingBalance, String openingSide) {
    }

    private record RawBrsAlloc(
        UUID id,
        UUID journalId,
        String vchNo,
        String vchType,
        LocalDate vchDate,
        String txnType,
        String instrumentNo,
        LocalDate instrumentDate,
        String partyNarr,
        String side,
        BigDecimal amount,
        LocalDate bankersDate,
        String reconStatus
    ) {
    }

    private record CandidateAlloc(
        UUID id,
        LocalDate voucherDate,
        String instrumentNo,
        LocalDate instrumentDate,
        String transferModeRef,
        String side,
        BigDecimal amount
    ) {
    }

    private record CandidateStmtLine(
        UUID id,
        UUID statementImportId,
        LocalDate txnDate,
        LocalDate valueDate,
        String instrumentNo,
        String utrReference,
        String description,
        BigDecimal withdrawalAmount,
        BigDecimal depositAmount
    ) {
    }

    private record AllocMeta(UUID bankLedgerId, BigDecimal amount) {
    }

    private record StagedDocRow(
        UUID id,
        String reviewStatus,
        String targetCategory,
        String supplierName,
        String supplierGstin,
        String invoiceNo,
        LocalDate invoiceDate,
        BigDecimal extractedTotalAmount,
        UUID mappedPartyLedgerId,
        UUID promotedJournalId
    ) {
    }
}
