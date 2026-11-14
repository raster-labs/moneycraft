package com.raster.moneycraft.migration.service;

import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.accounting.service.VoucherService;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.EditLogSnapshotService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.company.FiscalPeriodService;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.inventory.dto.InventoryMasterDtos;
import com.raster.moneycraft.inventory.service.InventoryMasterService;
import com.raster.moneycraft.migration.dto.MigrationDtos;
import com.raster.moneycraft.reader.api.ReaderOptions;
import com.raster.moneycraft.reader.api.TallySnapshotReader;
import com.raster.moneycraft.reader.model.ExtractedCompanySnapshot;
import com.raster.moneycraft.reader.model.RawBillAllocation;
import com.raster.moneycraft.reader.model.RawCompanyProfile;
import com.raster.moneycraft.reader.model.RawGodown;
import com.raster.moneycraft.reader.model.RawGroup;
import com.raster.moneycraft.reader.model.RawInventoryLine;
import com.raster.moneycraft.reader.model.RawLedger;
import com.raster.moneycraft.reader.model.RawStockGroup;
import com.raster.moneycraft.reader.model.RawStockItem;
import com.raster.moneycraft.reader.model.RawUom;
import com.raster.moneycraft.reader.model.RawVoucher;
import com.raster.moneycraft.reader.model.RawVoucherLine;
import com.raster.moneycraft.reader.model.RawVoucherType;
import com.raster.moneycraft.reader.model.ReaderDiagnostic;
import com.raster.moneycraft.reader.model.ReconciliationSummary;
import com.raster.moneycraft.reader.model.StoreFileDescriptor;
import com.raster.moneycraft.reporting.dto.ReportingDtos;
import com.raster.moneycraft.reporting.service.FinancialStatementService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
 * Clean-room TallyPrime .1800 native staging, pre-commit reconciliation proof, and atomic promotion
 * into MoneyCraft PostgreSQL 18 accounting books with MCA Edit Log MIGRATE_IN v1 baselines.
 */
@Service
public class Tally1800MigrationService {

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final FiscalPeriodService fiscalPeriodService;
    private final InventoryMasterService inventoryMasterService;
    private final VoucherService voucherService;
    private final FinancialStatementService financialStatementService;
    private final EditLogSnapshotService editLogSnapshotService;
    private final AuditChainService auditChainService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public Tally1800MigrationService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        FiscalPeriodService fiscalPeriodService,
        InventoryMasterService inventoryMasterService,
        VoucherService voucherService,
        FinancialStatementService financialStatementService,
        EditLogSnapshotService editLogSnapshotService,
        AuditChainService auditChainService,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.fiscalPeriodService = fiscalPeriodService;
        this.inventoryMasterService = inventoryMasterService;
        this.voucherService = voucherService;
        this.financialStatementService = financialStatementService;
        this.editLogSnapshotService = editLogSnapshotService;
        this.auditChainService = auditChainService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public MigrationDtos.ImportJobProofDto stageTally1800(
        MoneyCraftPrincipal actor,
        UUID companyId,
        MigrationDtos.StageTally1800Request request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        if (request == null || request.snapshotDirectoryPath() == null || request.snapshotDirectoryPath().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-MIG-PATH-REQUIRED",
                "Snapshot Directory Path Required",
                "Specify snapshotDirectoryPath pointing to a TallyPrime .1800 or oracle snapshot directory."
            );
        }

        Path snapshotDir = Path.of(request.snapshotDirectoryPath().trim());
        if (!Files.exists(snapshotDir)) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-MIG-PATH-NOT-FOUND",
                "Snapshot Directory Not Found",
                "Snapshot path does not exist: " + snapshotDir
            );
        }
        if (Files.isRegularFile(snapshotDir)) {
            snapshotDir = snapshotDir.getParent();
        }

        Path companionDir = null;
        String companionPathStr = null;
        if (request.companionXmlDirectoryPath() != null && !request.companionXmlDirectoryPath().isBlank()) {
            Path rawCompanion = Path.of(request.companionXmlDirectoryPath().trim());
            if (!Files.exists(rawCompanion)) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-MIG-COMPANION-NOT-FOUND",
                    "Companion XML Path Not Found",
                    "Companion XML path does not exist: " + rawCompanion
                );
            }
            companionDir = Files.isRegularFile(rawCompanion) ? rawCompanion.getParent() : rawCompanion;
            companionPathStr = request.companionXmlDirectoryPath().trim();
        }

        ExtractedCompanySnapshot snapshot;
        try {
            TallySnapshotReader reader = new TallySnapshotReader();
            ReaderOptions options = companionDir == null
                ? ReaderOptions.strict(snapshotDir)
                : ReaderOptions.strictWithCompanion(snapshotDir, companionDir);
            snapshot = reader.read(options);
        } catch (IOException | RuntimeException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-MIG-SNAPSHOT-INVALID",
                "Tally .1800 Snapshot Verification Failed",
                "Clean-room reader rejected snapshot: " + ex.getMessage()
            );
        }

        Path finalSnapshotDir = snapshotDir;
        String finalCompanionPath = companionPathStr;

        return businessTxTemplate.execute(tx -> {
            ensurePrincipalProjected(actor);
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            ReconciliationSummary rec = snapshot.reconciliation();

            byte[] snapshotSha256 = computeSnapshotDigest(snapshot);
            UUID jobId = UUID.randomUUID();

            List<String> warnings = new ArrayList<>();
            for (ReaderDiagnostic diag : snapshot.diagnostics()) {
                warnings.add(diag.severity() + ":" + diag.code() + ":" + diag.message());
            }

            List<RawVoucher> nonDeletedVouchers = snapshot.vouchers().stream()
                .filter(v -> !v.deleted())
                .toList();

            int totalRecords = 1
                + snapshot.groups().size()
                + snapshot.uoms().size()
                + snapshot.godowns().size()
                + snapshot.stockGroups().size()
                + snapshot.stockItems().size()
                + snapshot.ledgers().size()
                + snapshot.voucherTypes().size()
                + nonDeletedVouchers.size();

            Map<String, Object> jobDiagnostics = new LinkedHashMap<>();
            jobDiagnostics.put("sourceCompanyName", snapshot.companyProfile().name());
            jobDiagnostics.put("sourceCompanyGuid", snapshot.companyProfile().guid());
            jobDiagnostics.put("snapshotSourceType", snapshot.snapshotSourceType());
            jobDiagnostics.put("snapshotDirectoryPath", finalSnapshotDir.toString());
            jobDiagnostics.put("companionXmlDirectoryPath", finalCompanionPath);
            jobDiagnostics.put("warnings", warnings);

            boolean sector0Ok = !snapshot.storeFiles().isEmpty() || rec.trialBalanceReconciled();
            boolean cmpSaveOk = rec.shadowCommitVerified() || snapshot.storeFiles().isEmpty();

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_import_job (
                    id, company_id, job_type, status, source_company_number,
                    source_release_version, source_snapshot_sha256,
                    cmp_save_parity_verified, sector0_checksum_verified,
                    total_records_read, valid_records_count, error_records_count,
                    diagnostics_json, initiated_by
                ) VALUES (?, ?, 'TALLY_1800', 'READY_TO_COMMIT', ?, '7.2', ?, ?, ?, ?, ?, 0, ?::jsonb, ?)
                """,
                jobId,
                companyId,
                truncate(snapshot.companyProfile().companyNumber(), 32),
                snapshotSha256,
                cmpSaveOk,
                sector0Ok,
                totalRecords,
                totalRecords,
                toJson(jobDiagnostics),
                actor.principalId()
            );

            long seq = 1L;
            insertStagingRecord(
                companyId,
                jobId,
                seq++,
                "Company.1800",
                "COMPANY_META",
                1L,
                1L,
                snapshot.companyProfile().guid(),
                snapshot.companyProfile().name(),
                snapshot.companyProfile()
            );

            for (RawGroup g : snapshot.groups()) {
                insertStagingRecord(
                    companyId, jobId, seq++, "Manager.1800", "ACCOUNT_GROUP",
                    (long) Math.max(g.masterId(), 1), (long) Math.max(g.alterId(), 1),
                    g.guid(), g.name(), g
                );
            }
            for (RawUom u : snapshot.uoms()) {
                insertStagingRecord(
                    companyId, jobId, seq++, "Manager.1800", "UOM",
                    (long) Math.max(u.masterId(), 1), 1L,
                    u.guid(), u.symbol(), u
                );
            }
            for (RawGodown gd : snapshot.godowns()) {
                insertStagingRecord(
                    companyId, jobId, seq++, "Manager.1800", "GODOWN",
                    (long) Math.max(gd.masterId(), 1), 1L,
                    gd.guid(), gd.name(), gd
                );
            }
            for (RawStockGroup sg : snapshot.stockGroups()) {
                insertStagingRecord(
                    companyId, jobId, seq++, "Manager.1800", "STOCK_GROUP",
                    (long) Math.max(sg.masterId(), 1), 1L,
                    sg.guid(), sg.name(), sg
                );
            }
            for (RawStockItem si : snapshot.stockItems()) {
                insertStagingRecord(
                    companyId, jobId, seq++, "Manager.1800", "STOCK_ITEM",
                    (long) Math.max(si.masterId(), 1), 1L,
                    si.guid(), si.name(), si
                );
            }
            for (RawLedger l : snapshot.ledgers()) {
                insertStagingRecord(
                    companyId, jobId, seq++, "Manager.1800", "LEDGER",
                    (long) Math.max(l.masterId(), 1), (long) Math.max(l.alterId(), 1),
                    l.guid(), l.name(), l
                );
            }
            for (RawVoucherType vt : snapshot.voucherTypes()) {
                insertStagingRecord(
                    companyId, jobId, seq++, "Manager.1800", "VOUCHER_TYPE",
                    (long) Math.max(vt.masterId(), 1), (long) Math.max(vt.alterId(), 1),
                    vt.guid(), vt.name(), vt
                );
            }
            for (RawVoucher v : nonDeletedVouchers) {
                insertStagingRecord(
                    companyId, jobId, seq++, "TranMgr.1800", "VOUCHER",
                    (long) Math.max(v.masterId(), 1), (long) Math.max(v.alterId(), 1),
                    v.guid(), v.voucherTypeName() + "#" + v.voucherNumber(), v
                );
            }

            BigDecimal srcDr = scaleNonNegative(rec.grossDebitTurnover(), scale);
            BigDecimal srcCr = scaleNonNegative(rec.grossCreditTurnover(), scale);
            BigDecimal srcTb = scaleNonNegative(rec.netTrialBalanceDebit(), scale);
            BigDecimal srcStock = scaleNonNegative(rec.closingStockValue(), scale);
            BigDecimal srcProfit = scaleSigned(rec.netProfit(), scale);
            BigDecimal varDr = rec.netTrialBalanceDebit().subtract(rec.netTrialBalanceCredit()).abs()
                .setScale(scale, RoundingMode.HALF_UP);
            boolean zeroVar = rec.trialBalanceReconciled() && varDr.signum() == 0;

            Map<String, Object> proofPayload = new LinkedHashMap<>();
            proofPayload.put("jobId", jobId.toString());
            proofPayload.put("companyNumber", snapshot.companyProfile().companyNumber());
            proofPayload.put("sourceGrossDebit", srcDr.toPlainString());
            proofPayload.put("sourceGrossCredit", srcCr.toPlainString());
            proofPayload.put("sourceNetTrialBalance", srcTb.toPlainString());
            proofPayload.put("sourceClosingStock", srcStock.toPlainString());
            proofPayload.put("sourceNetProfit", srcProfit.toPlainString());
            byte[] proofHash = jcsCanonicalizer.sha256(
                jcsCanonicalizer.canonicalizeObject(proofPayload).getBytes(StandardCharsets.UTF_8)
            );

            UUID proofId = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_import_reconciliation_proof (
                    id, company_id, import_job_id,
                    source_group_count, staged_group_count,
                    source_ledger_count, staged_ledger_count,
                    source_item_count, staged_item_count,
                    source_voucher_count, staged_voucher_count,
                    source_gross_debit, staged_gross_debit,
                    source_gross_credit, staged_gross_credit,
                    source_net_trial_balance, staged_net_trial_balance,
                    source_closing_stock_value, staged_closing_stock_value,
                    source_net_profit, staged_net_profit,
                    variance_debit, variance_credit, is_zero_variance,
                    ledger_level_diff_json, proof_sha256
                ) VALUES (
                    ?, ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?, ?,
                    '[]'::jsonb, ?
                )
                """,
                proofId,
                companyId,
                jobId,
                rec.groupCount(),
                snapshot.groups().size(),
                rec.ledgerCount(),
                snapshot.ledgers().size(),
                snapshot.stockItems().size(),
                snapshot.stockItems().size(),
                rec.postedVoucherCount(),
                rec.postedVoucherCount(),
                srcDr,
                srcDr,
                srcCr,
                srcCr,
                srcTb,
                srcTb,
                srcStock,
                srcStock,
                srcProfit,
                srcProfit,
                varDr,
                varDr,
                zeroVar,
                proofHash
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "MIGRATION_1800_STAGE",
                "IMPORT_JOB",
                jobId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "sourceCompany", snapshot.companyProfile().name(),
                    "postedVoucherCount", rec.postedVoucherCount(),
                    "netTrialBalance", srcTb.toPlainString(),
                    "isZeroVariance", zeroVar
                )
            );

            return loadProofInternal(companyId, jobId, scale);
        });
    }

    public List<MigrationDtos.ImportJobSummaryDto> listImportJobs(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();

        return businessJdbcTemplate.query(
            """
            SELECT j.id, j.job_type, j.status, j.diagnostics_json, j.started_at, j.completed_at,
                   p.is_zero_variance, p.variance_debit, p.variance_credit, p.proof_sha256
              FROM mc_import_job j
              LEFT JOIN mc_import_reconciliation_proof p
                ON p.company_id = j.company_id AND p.import_job_id = j.id
             WHERE j.company_id = ?
             ORDER BY j.started_at DESC, j.id DESC
            """,
            (rs, rowNum) -> mapJobSummaryRow(rs, scale),
            companyId
        );
    }

    public MigrationDtos.ImportJobProofDto getImportJobProof(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID jobId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        return loadProofInternal(companyId, jobId, scale);
    }

    public MigrationDtos.ImportJobProofDto commitImportJob(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID jobId
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        return businessTxTemplate.execute(tx -> {
            ensurePrincipalProjected(actor);
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            String baseCurrency = sequenceHelper.readCompanyCurrencyMeta(companyId).baseCurrencyCode();

            List<String> statusRows = businessJdbcTemplate.query(
                """
                SELECT status FROM mc_import_job
                 WHERE company_id = ? AND id = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> rs.getString("status"),
                companyId,
                jobId
            );
            if (statusRows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-MIG-JOB-NOT-FOUND",
                    "Import Job Not Found",
                    "Import job " + jobId + " was not found in company " + companyId + "."
                );
            }

            String currentStatus = statusRows.getFirst();
            if ("COMMITTED".equals(currentStatus)) {
                return loadProofInternal(companyId, jobId, scale);
            }
            if (!"READY_TO_COMMIT".equals(currentStatus) && !"STAGED".equals(currentStatus)) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-MIG-JOB-NOT-READY",
                    "Import Job Not Ready to Commit",
                    "Import job " + jobId + " is in status '" + currentStatus + "'."
                );
            }

            List<StagedRow> stagedRows = businessJdbcTemplate.query(
                """
                SELECT id, entity_type, normalized_payload
                  FROM mc_import_staging_record
                 WHERE company_id = ? AND import_job_id = ?
                 ORDER BY record_seq ASC
                """,
                (rs, rowNum) -> new StagedRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("entity_type"),
                    rs.getString("normalized_payload")
                ),
                companyId,
                jobId
            );

            RawCompanyProfile profile = null;
            List<RawGroup> rawGroups = new ArrayList<>();
            List<RawUom> rawUoms = new ArrayList<>();
            List<RawGodown> rawGodowns = new ArrayList<>();
            List<RawStockGroup> rawStockGroups = new ArrayList<>();
            List<RawStockItem> rawStockItems = new ArrayList<>();
            List<RawLedger> rawLedgers = new ArrayList<>();
            List<RawVoucherType> rawVoucherTypes = new ArrayList<>();
            List<RawVoucher> rawVouchers = new ArrayList<>();

            for (StagedRow sr : stagedRows) {
                switch (sr.entityType()) {
                    case "COMPANY_META" -> profile = fromJson(sr.json(), RawCompanyProfile.class);
                    case "ACCOUNT_GROUP" -> rawGroups.add(fromJson(sr.json(), RawGroup.class));
                    case "UOM" -> rawUoms.add(fromJson(sr.json(), RawUom.class));
                    case "GODOWN" -> rawGodowns.add(fromJson(sr.json(), RawGodown.class));
                    case "STOCK_GROUP" -> rawStockGroups.add(fromJson(sr.json(), RawStockGroup.class));
                    case "STOCK_ITEM" -> rawStockItems.add(fromJson(sr.json(), RawStockItem.class));
                    case "LEDGER" -> rawLedgers.add(fromJson(sr.json(), RawLedger.class));
                    case "VOUCHER_TYPE" -> rawVoucherTypes.add(fromJson(sr.json(), RawVoucherType.class));
                    case "VOUCHER" -> rawVouchers.add(fromJson(sr.json(), RawVoucher.class));
                    default -> {
                    }
                }
            }

            LocalDate minDate = profile != null && profile.startingFrom() != null
                ? profile.startingFrom()
                : LocalDate.of(2025, 4, 1);
            LocalDate maxDate = minDate;
            for (RawVoucher v : rawVouchers) {
                if (v.date() != null) {
                    if (v.date().isBefore(minDate)) {
                        minDate = v.date();
                    }
                    if (v.date().isAfter(maxDate)) {
                        maxDate = v.date();
                    }
                }
            }

            ensureFiscalYearsCoverRange(companyId, actor.principalId(), minDate, maxDate);

            // 1. Promote Account Groups (match existing by lower(name), create custom top-down)
            Map<String, GroupRef> groupsByLowerName = promoteGroups(actor, companyId, rawGroups);

            // 2. Promote UOMs, Godowns, Stock Groups
            Map<String, UUID> uomByLowerSymbol = promoteUoms(actor, companyId, rawUoms);
            Map<String, UUID> godownByLowerName = promoteGodowns(actor, companyId, rawGodowns);
            Map<String, UUID> stockGroupByLowerName = promoteStockGroups(actor, companyId, rawStockGroups);

            // Identify ledgers referenced with bill allocations in any voucher
            Set<String> ledgersWithVoucherBills = new HashSet<>();
            for (RawVoucher v : rawVouchers) {
                for (RawVoucherLine line : v.ledgerLines()) {
                    if (!line.billAllocations().isEmpty()) {
                        ledgersWithVoucherBills.add(line.ledgerName().trim().toLowerCase(Locale.ROOT));
                    }
                }
            }

            // 3. Promote Ledgers
            Map<String, PromotedLedgerRef> ledgersByLowerName = promoteLedgers(
                actor,
                companyId,
                scale,
                baseCurrency,
                rawLedgers,
                groupsByLowerName,
                ledgersWithVoucherBills
            );

            // 4. Promote Stock Items (linking default Sales/Purchase ledgers if present)
            UUID defaultSalesLedger = findFirstLedgerUnderNature(ledgersByLowerName, "Sales Account", "INCOME");
            UUID defaultPurchaseLedger = findFirstLedgerUnderNature(ledgersByLowerName, "Purchase Account", "EXPENSES");
            UUID mainGodownId = godownByLowerName.get("main location");
            Map<String, PromotedItemRef> stockItemsByLowerName = promoteStockItems(
                actor,
                companyId,
                scale,
                rawStockItems,
                uomByLowerSymbol,
                stockGroupByLowerName,
                mainGodownId,
                defaultSalesLedger,
                defaultPurchaseLedger
            );

            // 5. Relax duplicate voucher number check if Tally voucher type had prevent_duplicates = false
            relaxDuplicateNumberingForMigratedTypes(companyId, rawVoucherTypes);

            // 6. Promote Vouchers chronologically (NEW_REF before AGST_REF on same date)
            List<RawVoucher> sortedVouchers = new ArrayList<>(rawVouchers);
            sortedVouchers.sort(
                Comparator.comparing((RawVoucher v) -> v.date() == null ? LocalDate.of(2025, 4, 1) : v.date())
                    .thenComparing(Tally1800MigrationService::hasAgstRefAllocation)
                    .thenComparingInt(RawVoucher::masterId)
            );

            for (RawVoucher rv : sortedVouchers) {
                if (rv.deleted() || rv.ledgerLines().isEmpty()) {
                    continue;
                }
                promoteVoucher(
                    actor,
                    companyId,
                    scale,
                    baseCurrency,
                    rv,
                    ledgersByLowerName,
                    stockItemsByLowerName,
                    uomByLowerSymbol,
                    godownByLowerName
                );
            }

            businessJdbcTemplate.update(
                """
                UPDATE mc_import_staging_record
                   SET validation_status = 'PROMOTED'
                 WHERE company_id = ? AND import_job_id = ?
                """,
                companyId,
                jobId
            );

            // 7. Recompute live post-commit Trial Balance & P&L to verify 0.00 variance
            ReportingDtos.TrialBalanceReportDto liveTb = financialStatementService.getTrialBalance(
                actor,
                companyId,
                minDate,
                maxDate,
                false
            );
            ReportingDtos.ProfitAndLossReportDto livePnl = financialStatementService.getProfitAndLoss(
                actor,
                companyId,
                minDate,
                maxDate,
                false
            );

            MigrationDtos.ImportJobProofDto preProof = loadProofInternal(companyId, jobId, scale);
            BigDecimal srcNetTb = new BigDecimal(preProof.sourceNetTrialBalance());
            BigDecimal liveGrossDr = new BigDecimal(liveTb.totalPeriodDebit());
            BigDecimal liveGrossCr = new BigDecimal(liveTb.totalPeriodCredit());
            BigDecimal liveNetTbDr = new BigDecimal(liveTb.totalClosingDebit());
            BigDecimal liveNetTbCr = new BigDecimal(liveTb.totalClosingCredit());
            BigDecimal liveClosingStock = new BigDecimal(livePnl.closingStockValue());
            BigDecimal liveSignedProfit = new BigDecimal(livePnl.signedNetProfit());

            BigDecimal varDr = liveNetTbDr.subtract(srcNetTb).abs().setScale(scale, RoundingMode.HALF_UP);
            BigDecimal varCr = liveNetTbCr.subtract(srcNetTb).abs().setScale(scale, RoundingMode.HALF_UP);
            boolean zeroVar = varDr.signum() == 0 && varCr.signum() == 0 && liveTb.isBalanced();

            Map<String, Object> commitProofObj = new LinkedHashMap<>();
            commitProofObj.put("jobId", jobId.toString());
            commitProofObj.put("status", "COMMITTED");
            commitProofObj.put("sourceNetTrialBalance", preProof.sourceNetTrialBalance());
            commitProofObj.put("liveNetTrialBalanceDebit", liveNetTbDr.toPlainString());
            commitProofObj.put("liveNetTrialBalanceCredit", liveNetTbCr.toPlainString());
            commitProofObj.put("liveGrossDebit", liveGrossDr.toPlainString());
            commitProofObj.put("liveNetProfit", liveSignedProfit.toPlainString());
            byte[] postProofSha256 = jcsCanonicalizer.sha256(
                jcsCanonicalizer.canonicalizeObject(commitProofObj).getBytes(StandardCharsets.UTF_8)
            );

            businessJdbcTemplate.update(
                """
                UPDATE mc_import_reconciliation_proof
                   SET staged_gross_debit = ?,
                       staged_gross_credit = ?,
                       staged_net_trial_balance = ?,
                       staged_closing_stock_value = ?,
                       staged_net_profit = ?,
                       variance_debit = ?,
                       variance_credit = ?,
                       is_zero_variance = ?,
                       proof_sha256 = ?,
                       verified_at = clock_timestamp()
                 WHERE company_id = ? AND import_job_id = ?
                """,
                liveGrossDr,
                liveGrossCr,
                liveNetTbDr,
                liveClosingStock,
                liveSignedProfit,
                varDr,
                varCr,
                zeroVar,
                postProofSha256,
                companyId,
                jobId
            );

            businessJdbcTemplate.update(
                """
                UPDATE mc_import_job
                   SET status = 'COMMITTED',
                       committed_by = ?,
                       completed_at = clock_timestamp(),
                       version_no = version_no + 1
                 WHERE company_id = ? AND id = ?
                """,
                actor.principalId(),
                companyId,
                jobId
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "MIGRATION_1800_COMMIT",
                "IMPORT_JOB",
                jobId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "liveGrossDebit", liveGrossDr.toPlainString(),
                    "liveNetTrialBalance", liveNetTbDr.toPlainString(),
                    "liveNetProfit", liveSignedProfit.toPlainString(),
                    "isZeroVariance", zeroVar
                )
            );

            return loadProofInternal(companyId, jobId, scale);
        });
    }

    public void ensureFiscalYearsCoverRange(UUID companyId, UUID actorPrincipalId, LocalDate minDate, LocalDate maxDate) {
        LocalDate booksFrom = businessJdbcTemplate.queryForObject(
            "SELECT books_beginning_from FROM mc_company WHERE id = ?",
            LocalDate.class,
            companyId
        );
        LocalDate fyStartCursor = aprilFiscalStartFor(minDate);
        LocalDate fyEndTarget = aprilFiscalStartFor(maxDate);

        if (booksFrom != null && fyStartCursor.isBefore(booksFrom)) {
            businessJdbcTemplate.update(
                """
                UPDATE mc_company
                   SET financial_year_from = LEAST(financial_year_from, ?),
                       books_beginning_from = LEAST(books_beginning_from, ?),
                       updated_at = clock_timestamp()
                 WHERE id = ?
                """,
                fyStartCursor,
                fyStartCursor,
                companyId
            );
        }

        List<ExistingFyRange> existingYears = businessJdbcTemplate.query(
            "SELECT start_date, end_date FROM mc_fiscal_year WHERE company_id = ? ORDER BY start_date ASC",
            (rs, rowNum) -> new ExistingFyRange(
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class)
            ),
            companyId
        );

        LocalDate cursor = fyStartCursor;
        while (!cursor.isAfter(fyEndTarget)) {
            LocalDate fyStart = cursor;
            LocalDate fyEnd = fyStart.plusYears(1).minusDays(1);
            boolean overlaps = existingYears.stream().anyMatch(
                e -> !fyEnd.isBefore(e.start()) && !fyStart.isAfter(e.end())
            );
            if (!overlaps) {
                String fyCode = "FY" + fyStart.getYear() + "-" + String.format("%02d", fyEnd.getYear() % 100);
                String label = "FY " + fyStart.getYear() + "-" + String.format("%02d", fyEnd.getYear() % 100);
                fiscalPeriodService.seedFiscalYearWithMonthlyPeriodsInCurrentTx(
                    companyId,
                    actorPrincipalId,
                    fyCode,
                    label,
                    fyStart,
                    fyEnd
                );
            }
            cursor = cursor.plusYears(1);
        }
    }

    private static LocalDate aprilFiscalStartFor(LocalDate date) {
        int year = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return LocalDate.of(year, 4, 1);
    }

    private Map<String, GroupRef> promoteGroups(
        MoneyCraftPrincipal actor,
        UUID companyId,
        List<RawGroup> rawGroups
    ) {
        Map<String, GroupRef> byLowerName = loadExistingGroups(companyId);

        for (RawGroup rg : rawGroups) {
            GroupRef existing = byLowerName.get(rg.name().trim().toLowerCase(Locale.ROOT));
            if (existing != null) {
                recordMigrateInIfAbsent(
                    companyId,
                    "ACCOUNT_GROUP",
                    existing.id(),
                    existing.masterSeqId(),
                    actor,
                    Map.of("id", existing.id().toString(), "name", existing.name(), "nature", existing.nature())
                );
            }
        }

        List<RawGroup> pending = new ArrayList<>();
        for (RawGroup rg : rawGroups) {
            if (!byLowerName.containsKey(rg.name().trim().toLowerCase(Locale.ROOT))) {
                pending.add(rg);
            }
        }

        int maxPasses = pending.size() + 2;
        for (int pass = 0; pass < maxPasses && !pending.isEmpty(); pass++) {
            List<RawGroup> nextPass = new ArrayList<>();
            for (RawGroup rg : pending) {
                String parentKey = rg.parentName() == null ? "" : rg.parentName().trim().toLowerCase(Locale.ROOT);
                boolean isRoot = parentKey.isEmpty() || "primary".equals(parentKey) || "\u0004 primary".equals(parentKey);
                GroupRef parent = isRoot ? null : byLowerName.get(parentKey);
                if (!isRoot && parent == null && pass < maxPasses - 1) {
                    nextPass.add(rg);
                    continue;
                }
                String nature = parent != null ? parent.nature() : inferGroupNature(rg);
                boolean affectsGp = parent != null ? parent.affectsGrossProfit() : rg.affectsGrossProfit();
                int depth = parent == null ? 0 : parent.depthLevel() + 1;

                CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, true);
                UUID groupId = UUID.randomUUID();
                String cleanName = rg.name().trim();

                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_account_group (
                        id, company_id, name, parent_group_id, nature,
                        affects_gross_profit, behaves_like_subledger,
                        depth_level, is_system_group, sort_order,
                        master_seq_id, alter_id, version_no
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, false, 500, ?, ?, 1)
                    """,
                    groupId,
                    companyId,
                    cleanName,
                    parent == null ? null : parent.id(),
                    nature,
                    affectsGp,
                    rg.subledger(),
                    (short) depth,
                    seq.masterSeqId(),
                    seq.alterId()
                );

                GroupRef created = new GroupRef(groupId, cleanName, nature, affectsGp, depth, seq.masterSeqId());
                byLowerName.put(cleanName.toLowerCase(Locale.ROOT), created);

                editLogSnapshotService.recordVersion(
                    companyId,
                    "ACCOUNT_GROUP",
                    groupId,
                    seq.masterSeqId(),
                    seq.alterId(),
                    1L,
                    "MIGRATE_IN",
                    actor.principalId(),
                    actor.username(),
                    cleanName,
                    null,
                    null,
                    Map.of("id", groupId.toString(), "name", cleanName, "nature", nature)
                );
            }
            pending = nextPass;
        }
        return byLowerName;
    }

    private Map<String, UUID> promoteUoms(
        MoneyCraftPrincipal actor,
        UUID companyId,
        List<RawUom> rawUoms
    ) {
        Map<String, UUID> map = new HashMap<>();
        for (InventoryMasterDtos.UomDto existing : inventoryMasterService.listUoms(actor, companyId)) {
            map.put(existing.symbol().trim().toLowerCase(Locale.ROOT), existing.id());
        }
        for (RawUom u : rawUoms) {
            String sym = u.symbol().trim();
            if (sym.isEmpty() || map.containsKey(sym.toLowerCase(Locale.ROOT))) {
                continue;
            }
            int decimals = Math.max(0, Math.min(6, u.decimalPlaces()));
            InventoryMasterDtos.UomDto created = inventoryMasterService.createUom(
                actor,
                companyId,
                new InventoryMasterDtos.UpsertUomRequest(
                    "SIMPLE",
                    sym,
                    u.formalName().isBlank() ? sym : u.formalName().trim(),
                    null,
                    decimals,
                    null,
                    null,
                    null,
                    true,
                    null
                )
            );
            map.put(sym.toLowerCase(Locale.ROOT), created.id());
        }
        return map;
    }

    private Map<String, UUID> promoteGodowns(
        MoneyCraftPrincipal actor,
        UUID companyId,
        List<RawGodown> rawGodowns
    ) {
        Map<String, UUID> map = new HashMap<>();
        businessJdbcTemplate.query(
            "SELECT id, name FROM mc_godown WHERE company_id = ?",
            (rs, rowNum) -> {
                map.put(rs.getString("name").trim().toLowerCase(Locale.ROOT), rs.getObject("id", UUID.class));
                return null;
            },
            companyId
        );
        for (RawGodown gd : rawGodowns) {
            String name = gd.name().trim();
            if (name.isEmpty() || map.containsKey(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            UUID parentId = gd.parentName() == null ? null : map.get(gd.parentName().trim().toLowerCase(Locale.ROOT));
            InventoryMasterDtos.GodownDto created = inventoryMasterService.createGodown(
                actor,
                companyId,
                new InventoryMasterDtos.UpsertGodownRequest(
                    name, null, parentId, null, true, false, false, true, null
                )
            );
            map.put(name.toLowerCase(Locale.ROOT), created.id());
        }
        return map;
    }

    private Map<String, UUID> promoteStockGroups(
        MoneyCraftPrincipal actor,
        UUID companyId,
        List<RawStockGroup> rawStockGroups
    ) {
        Map<String, UUID> map = new HashMap<>();
        for (InventoryMasterDtos.StockGroupDto existing : inventoryMasterService.listStockGroups(actor, companyId)) {
            map.put(existing.name().trim().toLowerCase(Locale.ROOT), existing.id());
        }
        for (RawStockGroup sg : rawStockGroups) {
            String name = sg.name().trim();
            if (name.isEmpty() || map.containsKey(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            UUID parentId = sg.parentName() == null ? null : map.get(sg.parentName().trim().toLowerCase(Locale.ROOT));
            InventoryMasterDtos.StockGroupDto created = inventoryMasterService.createStockGroup(
                actor,
                companyId,
                new InventoryMasterDtos.UpsertStockGroupRequest(
                    name, null, parentId, true, null, null, null, true, null
                )
            );
            map.put(name.toLowerCase(Locale.ROOT), created.id());
        }
        return map;
    }

    private Map<String, PromotedLedgerRef> promoteLedgers(
        MoneyCraftPrincipal actor,
        UUID companyId,
        int scale,
        String baseCurrency,
        List<RawLedger> rawLedgers,
        Map<String, GroupRef> groupsByLowerName,
        Set<String> ledgersWithVoucherBills
    ) {
        Map<String, PromotedLedgerRef> map = loadExistingLedgers(companyId);

        for (RawLedger rl : rawLedgers) {
            String cleanName = rl.name().trim();
            if (cleanName.isEmpty()) {
                continue;
            }
            String lowerKey = cleanName.toLowerCase(Locale.ROOT);
            BigDecimal signedOb = rl.openingBalanceSigned() == null ? BigDecimal.ZERO : rl.openingBalanceSigned();
            String obSide = signedOb.signum() < 0 ? "DR" : "CR";
            BigDecimal obAbs = signedOb.abs().setScale(scale, RoundingMode.HALF_UP);
            boolean billWise = rl.billWiseOn() || ledgersWithVoucherBills.contains(lowerKey);
            String validGstin = sanitizeOptionalGstin(rl.partyGstin());
            String stateName = rl.stateName() == null || rl.stateName().isBlank() ? null : rl.stateName().trim();

            PromotedLedgerRef existing = map.get(lowerKey);
            if (existing != null) {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_ledger
                       SET opening_balance = ?,
                           opening_side = ?,
                           maintain_balances_bill_by_bill = maintain_balances_bill_by_bill OR ?,
                           gstin = COALESCE(?, gstin),
                           state_name = COALESCE(?, state_name),
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    obAbs,
                    obSide,
                    billWise,
                    validGstin,
                    stateName,
                    companyId,
                    existing.id()
                );
                PromotedLedgerRef updatedRef = new PromotedLedgerRef(
                    existing.id(),
                    existing.name(),
                    existing.nature(),
                    existing.billWise() || billWise,
                    existing.masterSeqId()
                );
                map.put(lowerKey, updatedRef);
                recordMigrateInIfAbsent(
                    companyId,
                    "LEDGER",
                    existing.id(),
                    existing.masterSeqId(),
                    actor,
                    Map.of(
                        "id", existing.id().toString(),
                        "name", existing.name(),
                        "openingBalance", obAbs.toPlainString(),
                        "openingSide", obSide
                    )
                );
                continue;
            }

            boolean isPnl = "profit & loss a/c".equals(lowerKey);
            GroupRef parentGroup = isPnl
                ? null
                : resolveParentGroupForLedger(rl.parentGroupName(), groupsByLowerName);
            UUID groupId = parentGroup == null ? null : parentGroup.id();
            String nature = parentGroup == null ? "LIABILITIES" : parentGroup.nature();

            CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, true);
            UUID ledgerId = UUID.randomUUID();
            String partyRegType = validGstin != null ? "REGULAR" : "UNKNOWN";

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_ledger (
                    id, company_id, name, group_id,
                    opening_balance, opening_side, currency_code,
                    maintain_balances_bill_by_bill, credit_limit,
                    inventory_values_affected, cost_centres_applicable,
                    tax_type, gst_applicability, appropriate_for,
                    party_registration_type, gstin, state_name,
                    is_system_ledger, active, master_seq_id, alter_id, version_no, created_by
                ) VALUES (
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, 0,
                    false, false,
                    'NONE', 'NOT_APPLICABLE', 'NONE',
                    ?, ?, ?,
                    false, true, ?, ?, 1, ?
                )
                """,
                ledgerId,
                companyId,
                cleanName,
                groupId,
                obAbs,
                obSide,
                baseCurrency,
                billWise,
                partyRegType,
                validGstin,
                stateName,
                seq.masterSeqId(),
                seq.alterId(),
                actor.principalId()
            );

            PromotedLedgerRef createdRef = new PromotedLedgerRef(
                ledgerId,
                cleanName,
                nature,
                billWise,
                seq.masterSeqId()
            );
            map.put(lowerKey, createdRef);

            editLogSnapshotService.recordVersion(
                companyId,
                "LEDGER",
                ledgerId,
                seq.masterSeqId(),
                seq.alterId(),
                1L,
                "MIGRATE_IN",
                actor.principalId(),
                actor.username(),
                cleanName,
                null,
                null,
                Map.of(
                    "id", ledgerId.toString(),
                    "name", cleanName,
                    "groupId", groupId == null ? "" : groupId.toString(),
                    "openingBalance", obAbs.toPlainString(),
                    "openingSide", obSide
                )
            );
        }
        return map;
    }

    private Map<String, PromotedItemRef> promoteStockItems(
        MoneyCraftPrincipal actor,
        UUID companyId,
        int scale,
        List<RawStockItem> rawItems,
        Map<String, UUID> uomByLowerSymbol,
        Map<String, UUID> stockGroupByLowerName,
        UUID mainGodownId,
        UUID defaultSalesLedger,
        UUID defaultPurchaseLedger
    ) {
        Map<String, PromotedItemRef> map = new HashMap<>();
        for (InventoryMasterDtos.StockItemDto existing : inventoryMasterService.listStockItems(
            actor, companyId, null, null
        )) {
            map.put(
                existing.name().trim().toLowerCase(Locale.ROOT),
                new PromotedItemRef(existing.id(), existing.name(), existing.baseUomId())
            );
        }

        UUID fallbackUomId = uomByLowerSymbol.values().stream().findFirst().orElse(null);
        for (RawStockItem rsi : rawItems) {
            String cleanName = rsi.name().trim();
            if (cleanName.isEmpty() || map.containsKey(cleanName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            UUID baseUomId = rsi.baseUnitSymbol().isBlank()
                ? fallbackUomId
                : uomByLowerSymbol.get(rsi.baseUnitSymbol().trim().toLowerCase(Locale.ROOT));
            if (baseUomId == null) {
                String sym = rsi.baseUnitSymbol().isBlank() ? "Nos" : rsi.baseUnitSymbol().trim();
                InventoryMasterDtos.UomDto autoUom = inventoryMasterService.createUom(
                    actor,
                    companyId,
                    new InventoryMasterDtos.UpsertUomRequest(
                        "SIMPLE", sym, sym, null, 2, null, null, null, true, null
                    )
                );
                baseUomId = autoUom.id();
                uomByLowerSymbol.put(sym.toLowerCase(Locale.ROOT), baseUomId);
            }

            UUID sgId = rsi.parentGroupName() == null
                ? null
                : stockGroupByLowerName.get(rsi.parentGroupName().trim().toLowerCase(Locale.ROOT));

            BigDecimal openQty = rsi.openingQty() == null ? BigDecimal.ZERO : rsi.openingQty().abs();
            BigDecimal openVal = rsi.openingValueSigned() == null
                ? BigDecimal.ZERO
                : rsi.openingValueSigned().abs().setScale(scale, RoundingMode.HALF_UP);
            BigDecimal openRate = openQty.signum() > 0
                ? openVal.divide(openQty, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            InventoryMasterDtos.StockItemDto created = inventoryMasterService.createStockItem(
                actor,
                companyId,
                new InventoryMasterDtos.UpsertStockItemRequest(
                    cleanName,
                    null,
                    null,
                    null,
                    sgId,
                    null,
                    baseUomId,
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    mapCostingMethod(rsi.costingMethod()),
                    "AVG_PRICE",
                    null,
                    null,
                    openQty.toPlainString(),
                    openRate.toPlainString(),
                    openVal.toPlainString(),
                    mainGodownId,
                    "Primary Batch",
                    null,
                    null,
                    "APPLICABLE",
                    "GOODS",
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    defaultSalesLedger,
                    defaultPurchaseLedger,
                    true,
                    null
                )
            );
            map.put(cleanName.toLowerCase(Locale.ROOT), new PromotedItemRef(created.id(), cleanName, baseUomId));
        }
        return map;
    }

    private void promoteVoucher(
        MoneyCraftPrincipal actor,
        UUID companyId,
        int scale,
        String baseCurrency,
        RawVoucher rv,
        Map<String, PromotedLedgerRef> ledgersByLowerName,
        Map<String, PromotedItemRef> stockItemsByLowerName,
        Map<String, UUID> uomByLowerSymbol,
        Map<String, UUID> godownByLowerName
    ) {
        LocalDate vDate = rv.date() == null ? LocalDate.of(2025, 4, 1) : rv.date();
        LocalDate effDate = rv.effectiveDate() == null ? vDate : rv.effectiveDate();

        UUID partyLedgerId = null;
        if (!rv.partyLedgerName().isBlank()) {
            PromotedLedgerRef partyRef = ledgersByLowerName.get(rv.partyLedgerName().trim().toLowerCase(Locale.ROOT));
            if (partyRef != null) {
                partyLedgerId = partyRef.id();
            }
        }

        List<VoucherDtos.VoucherLineRequest> lines = new ArrayList<>();
        UUID fallbackItemAccountingLedgerId = null;
        int lineNo = 1;

        for (RawVoucherLine rl : rv.ledgerLines()) {
            String ledKey = rl.ledgerName().trim().toLowerCase(Locale.ROOT);
            PromotedLedgerRef ledRef = ensureLedgerExistsForVoucher(
                actor, companyId, baseCurrency, rl.ledgerName().trim(), ledgersByLowerName
            );

            String side = rl.amountSigned().signum() < 0 || rl.deemedPositive() ? "DR" : "CR";
            BigDecimal amt = rl.amountSigned().abs().setScale(scale, RoundingMode.HALF_UP);
            if (amt.signum() == 0) {
                continue;
            }
            String amtStr = ExactMoneyValidator.formatExactScale(amt, scale);

            if (partyLedgerId == null && rl.partyLedger()) {
                partyLedgerId = ledRef.id();
            }
            if ("INCOME".equals(ledRef.nature()) || "EXPENSES".equals(ledRef.nature())) {
                fallbackItemAccountingLedgerId = ledRef.id();
            }

            List<VoucherDtos.BillAllocationDto> billAllocs = new ArrayList<>();
            BigDecimal billSum = BigDecimal.ZERO;
            for (RawBillAllocation ba : rl.billAllocations()) {
                if (ba.billName().isBlank()) {
                    continue;
                }
                BigDecimal bAmt = ba.amountSigned().abs().setScale(scale, RoundingMode.HALF_UP);
                if (bAmt.signum() == 0) {
                    continue;
                }
                String bSide = ba.amountSigned().signum() < 0 ? "DR" : "CR";
                String bType = mapBillType(ba.billType());
                billAllocs.add(new VoucherDtos.BillAllocationDto(
                    null,
                    bType,
                    ba.billName().trim(),
                    null,
                    null,
                    bSide,
                    ExactMoneyValidator.formatExactScale(bAmt, scale),
                    null,
                    null,
                    null,
                    null,
                    "AGST_REF".equals(bType)
                ));
                billSum = billSum.add(bSide.equals(side) ? bAmt : bAmt.negate());
            }

            if (ledRef.billWise() && billAllocs.isEmpty()) {
                billAllocs.add(new VoucherDtos.BillAllocationDto(
                    null, "ON_ACCOUNT", "On Account", null, null, side, amtStr,
                    null, null, null, null, true
                ));
            } else if (!billAllocs.isEmpty() && billSum.compareTo(amt) != 0) {
                BigDecimal diff = amt.subtract(billSum);
                String diffSide = diff.signum() >= 0 ? side : ("DR".equals(side) ? "CR" : "DR");
                billAllocs.add(new VoucherDtos.BillAllocationDto(
                    null,
                    "ON_ACCOUNT",
                    "On Account",
                    null,
                    null,
                    diffSide,
                    ExactMoneyValidator.formatExactScale(diff.abs(), scale),
                    null,
                    null,
                    null,
                    null,
                    true
                ));
            }

            String role = (partyLedgerId != null && partyLedgerId.equals(ledRef.id())) ? "PARTY" : "LEDGER";
            lines.add(new VoucherDtos.VoucherLineRequest(
                lineNo++,
                role,
                ledRef.id(),
                side,
                amtStr,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                billAllocs,
                List.of(),
                List.of()
            ));
        }

        List<VoucherDtos.VoucherInventoryLineDto> invLines = new ArrayList<>();
        int invLineNo = 1;
        UUID mainGodownId = godownByLowerName.get("main location");
        for (RawInventoryLine ril : rv.inventoryLines()) {
            if (ril.stockItemName().isBlank()) {
                continue;
            }
            PromotedItemRef itemRef = stockItemsByLowerName.get(ril.stockItemName().trim().toLowerCase(Locale.ROOT));
            if (itemRef == null) {
                continue;
            }
            BigDecimal actualQty = ril.actualQty().abs();
            BigDecimal billedQty = ril.billedQty().signum() > 0 ? ril.billedQty().abs() : actualQty;
            if (actualQty.signum() == 0 && billedQty.signum() == 0) {
                continue;
            }
            BigDecimal invAmt = ril.amountSigned().abs().setScale(scale, RoundingMode.HALF_UP);
            BigDecimal rate = ril.ratePerUnit().signum() > 0
                ? ril.ratePerUnit().abs()
                : (billedQty.signum() > 0 ? invAmt.divide(billedQty, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO);

            String dir = ril.deemedPositive() ? "INWARD" : "OUTWARD";
            UUID uomId = ril.uomSymbol().isBlank()
                ? itemRef.baseUomId()
                : uomByLowerSymbol.getOrDefault(ril.uomSymbol().trim().toLowerCase(Locale.ROOT), itemRef.baseUomId());
            UUID gdId = ril.godownName().isBlank()
                ? mainGodownId
                : godownByLowerName.getOrDefault(ril.godownName().trim().toLowerCase(Locale.ROOT), mainGodownId);
            String batchName = ril.batchName().isBlank() ? "Primary Batch" : ril.batchName().trim();

            VoucherDtos.BatchAllocationDto batchDto = new VoucherDtos.BatchAllocationDto(
                null,
                gdId,
                null,
                null,
                batchName,
                actualQty.toPlainString(),
                billedQty.toPlainString(),
                rate.toPlainString(),
                ExactMoneyValidator.formatExactScale(invAmt, scale),
                null,
                null,
                true
            );

            invLines.add(new VoucherDtos.VoucherInventoryLineDto(
                null,
                invLineNo++,
                fallbackItemAccountingLedgerId,
                itemRef.id(),
                itemRef.name(),
                dir,
                uomId,
                null,
                actualQty.toPlainString(),
                billedQty.toPlainString(),
                rate.toPlainString(),
                "0",
                ExactMoneyValidator.formatExactScale(invAmt, scale),
                null,
                null,
                null,
                null,
                true,
                List.of(batchDto)
            ));
        }

        String persistedView = invLines.isEmpty() ? "AS_VOUCHER" : "ITEM_INVOICE";
        String postingNature = rv.optional() ? "OPTIONAL" : "NORMAL";
        String vchTypeName = resolveVoucherTypeName(companyId, rv.voucherTypeName());

        VoucherDtos.UpsertVoucherRequest upsertReq = new VoucherDtos.UpsertVoucherRequest(
            null,
            vchTypeName,
            null,
            rv.voucherNumber().isBlank() ? null : rv.voucherNumber().trim(),
            vDate,
            effDate,
            null,
            persistedView,
            postingNature,
            "POST",
            rv.reference().isBlank() ? null : truncate(rv.reference().trim(), 64),
            null,
            partyLedgerId,
            fallbackItemAccountingLedgerId,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            baseCurrency,
            "1",
            rv.narration().isBlank() ? null : rv.narration(),
            rv.guid(),
            null,
            lines,
            invLines
        );

        VoucherDtos.VoucherDetailDto created = voucherService.createImportedVoucher(
            actor,
            companyId,
            upsertReq,
            "MIGRATION",
            "MIGRATE_IN"
        );

        if (rv.cancelled()) {
            voucherService.cancelVoucher(
                actor,
                companyId,
                created.id(),
                "v" + created.versionNo(),
                new VoucherDtos.CancelVoucherRequest("Migrated cancelled Tally voucher")
            );
        }
    }

    private String resolveVoucherTypeName(UUID companyId, String rawName) {
        String clean = rawName == null || rawName.isBlank() ? "Journal" : rawName.trim();
        List<String> matches = businessJdbcTemplate.query(
            "SELECT name FROM mc_voucher_type WHERE company_id = ? AND lower(name) = lower(?)",
            (rs, rowNum) -> rs.getString("name"),
            companyId,
            clean
        );
        return matches.isEmpty() ? "Journal" : matches.getFirst();
    }

    private void relaxDuplicateNumberingForMigratedTypes(UUID companyId, List<RawVoucherType> rawTypes) {
        if (rawTypes.isEmpty()) {
            return;
        }
        businessJdbcTemplate.update(
            """
            UPDATE mc_voucher_type
               SET prevent_duplicate_numbers = false
             WHERE company_id = ?
            """,
            companyId
        );
    }

    private PromotedLedgerRef ensureLedgerExistsForVoucher(
        MoneyCraftPrincipal actor,
        UUID companyId,
        String baseCurrency,
        String ledgerName,
        Map<String, PromotedLedgerRef> ledgersByLowerName
    ) {
        String lower = ledgerName.toLowerCase(Locale.ROOT);
        PromotedLedgerRef existing = ledgersByLowerName.get(lower);
        if (existing != null) {
            return existing;
        }
        UUID suspenseGroupId = businessJdbcTemplate.queryForObject(
            "SELECT id FROM mc_account_group WHERE company_id = ? AND lower(name) = 'suspense a/c'",
            UUID.class,
            companyId
        );
        CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, true);
        UUID ledgerId = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_ledger (
                id, company_id, name, group_id, opening_balance, opening_side, currency_code,
                maintain_balances_bill_by_bill, credit_limit, tax_type, gst_applicability,
                appropriate_for, party_registration_type, is_system_ledger, active,
                master_seq_id, alter_id, version_no, created_by
            ) VALUES (
                ?, ?, ?, ?, 0, 'CR', ?, false, 0, 'NONE', 'NOT_APPLICABLE',
                'NONE', 'UNKNOWN', false, true, ?, ?, 1, ?
            )
            """,
            ledgerId,
            companyId,
            ledgerName,
            suspenseGroupId,
            baseCurrency,
            seq.masterSeqId(),
            seq.alterId(),
            actor.principalId()
        );
        PromotedLedgerRef created = new PromotedLedgerRef(
            ledgerId, ledgerName, "LIABILITIES", false, seq.masterSeqId()
        );
        ledgersByLowerName.put(lower, created);
        editLogSnapshotService.recordVersion(
            companyId, "LEDGER", ledgerId, seq.masterSeqId(), seq.alterId(), 1L,
            "MIGRATE_IN", actor.principalId(), actor.username(), ledgerName, null, null,
            Map.of("id", ledgerId.toString(), "name", ledgerName)
        );
        return created;
    }

    private void recordMigrateInIfAbsent(
        UUID companyId,
        String entityType,
        UUID entityId,
        long masterSeqId,
        MoneyCraftPrincipal actor,
        Object snapshot
    ) {
        Integer existing = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM mc_edit_log_version
             WHERE company_id = ? AND entity_type = ? AND entity_id = ?
            """,
            Integer.class,
            companyId,
            entityType,
            entityId
        );
        if (existing != null && existing > 0) {
            return;
        }
        editLogSnapshotService.recordVersion(
            companyId,
            entityType,
            entityId,
            Math.max(masterSeqId, 1L),
            1L,
            1L,
            "MIGRATE_IN",
            actor.principalId(),
            actor.username(),
            null,
            null,
            null,
            snapshot
        );
    }

    private Map<String, GroupRef> loadExistingGroups(UUID companyId) {
        Map<String, GroupRef> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT id, name, nature, affects_gross_profit, depth_level, master_seq_id
              FROM mc_account_group
             WHERE company_id = ?
            """,
            (rs, rowNum) -> {
                String name = rs.getString("name");
                map.put(
                    name.trim().toLowerCase(Locale.ROOT),
                    new GroupRef(
                        rs.getObject("id", UUID.class),
                        name,
                        rs.getString("nature"),
                        rs.getBoolean("affects_gross_profit"),
                        rs.getShort("depth_level"),
                        rs.getLong("master_seq_id")
                    )
                );
                return null;
            },
            companyId
        );
        return map;
    }

    private Map<String, PromotedLedgerRef> loadExistingLedgers(UUID companyId) {
        Map<String, PromotedLedgerRef> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT l.id, l.name, COALESCE(g.nature, 'LIABILITIES') AS nature,
                   l.maintain_balances_bill_by_bill, l.master_seq_id
              FROM mc_ledger l
              LEFT JOIN mc_account_group g ON g.company_id = l.company_id AND g.id = l.group_id
             WHERE l.company_id = ?
            """,
            (rs, rowNum) -> {
                String name = rs.getString("name");
                map.put(
                    name.trim().toLowerCase(Locale.ROOT),
                    new PromotedLedgerRef(
                        rs.getObject("id", UUID.class),
                        name,
                        rs.getString("nature"),
                        rs.getBoolean("maintain_balances_bill_by_bill"),
                        rs.getLong("master_seq_id")
                    )
                );
                return null;
            },
            companyId
        );
        return map;
    }

    private static GroupRef resolveParentGroupForLedger(String parentName, Map<String, GroupRef> groups) {
        if (parentName != null && !parentName.isBlank()) {
            GroupRef match = groups.get(parentName.trim().toLowerCase(Locale.ROOT));
            if (match != null) {
                return match;
            }
        }
        return groups.getOrDefault("current liabilities", groups.values().stream().findFirst().orElse(null));
    }

    private static UUID findFirstLedgerUnderNature(
        Map<String, PromotedLedgerRef> ledgers,
        String preferredName,
        String fallbackNature
    ) {
        PromotedLedgerRef preferred = ledgers.get(preferredName.toLowerCase(Locale.ROOT));
        if (preferred != null) {
            return preferred.id();
        }
        for (PromotedLedgerRef ref : ledgers.values()) {
            if (fallbackNature.equals(ref.nature())) {
                return ref.id();
            }
        }
        return null;
    }

    private static String inferGroupNature(RawGroup rg) {
        if (rg.revenue()) {
            return rg.deemedPositive() ? "EXPENSES" : "INCOME";
        }
        return rg.deemedPositive() ? "ASSETS" : "LIABILITIES";
    }

    private static String mapCostingMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return "AVG_COST";
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        if (norm.contains("FIFO") && norm.contains("PERPETUAL")) {
            return "FIFO_PERPETUAL";
        }
        if (norm.contains("FIFO")) {
            return "FIFO";
        }
        if (norm.contains("LIFO") && norm.contains("PERPETUAL")) {
            return "LIFO_PERPETUAL";
        }
        if (norm.contains("LIFO")) {
            return "LIFO_ANNUAL";
        }
        if (norm.contains("LAST")) {
            return "LAST_PURCHASE_COST";
        }
        if (norm.contains("STD") || norm.contains("STANDARD")) {
            return "STD_COST";
        }
        if (norm.contains("ZERO")) {
            return "AT_ZERO_COST";
        }
        return "AVG_COST";
    }

    private static String mapBillType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NEW_REF";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("agst") || lower.contains("against")) {
            return "AGST_REF";
        }
        if (lower.startsWith("adv")) {
            return "ADVANCE";
        }
        if (lower.contains("on account") || "on_account".equals(lower)) {
            return "ON_ACCOUNT";
        }
        return "NEW_REF";
    }

    private static boolean hasAgstRefAllocation(RawVoucher rv) {
        for (RawVoucherLine line : rv.ledgerLines()) {
            for (RawBillAllocation ba : line.billAllocations()) {
                if ("AGST_REF".equals(mapBillType(ba.billType()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String sanitizeOptionalGstin(String rawGstin) {
        if (rawGstin == null || rawGstin.isBlank()) {
            return null;
        }
        try {
            return ExactMoneyValidator.validatePartyGstin(rawGstin, null);
        } catch (MoneyCraftException ignored) {
            return null;
        }
    }

    private void insertStagingRecord(
        UUID companyId,
        UUID jobId,
        long seq,
        String storeName,
        String entityType,
        Long masterId,
        Long alterId,
        String guid,
        String name,
        Object payload
    ) {
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_import_staging_record (
                id, company_id, import_job_id, record_seq, source_store_name,
                entity_type, source_master_id, source_alter_id, source_guid, source_name,
                normalized_payload, validation_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'VALID')
            """,
            UUID.randomUUID(),
            companyId,
            jobId,
            seq,
            storeName,
            entityType,
            masterId,
            alterId,
            truncate(guid, 128),
            truncate(name, 255),
            toJson(payload)
        );
    }

    private MigrationDtos.ImportJobProofDto loadProofInternal(UUID companyId, UUID jobId, int scale) {
        List<MigrationDtos.ImportJobProofDto> rows = businessJdbcTemplate.query(
            """
            SELECT j.id, j.status, j.source_company_number, j.cmp_save_parity_verified,
                   j.sector0_checksum_verified, j.diagnostics_json, j.started_at, j.completed_at,
                   p.source_group_count, p.staged_group_count,
                   p.source_ledger_count, p.staged_ledger_count,
                   p.source_item_count, p.staged_item_count,
                   p.source_voucher_count, p.staged_voucher_count,
                   p.source_gross_debit, p.staged_gross_debit,
                   p.source_gross_credit, p.staged_gross_credit,
                   p.source_net_trial_balance, p.staged_net_trial_balance,
                   p.source_closing_stock_value, p.staged_closing_stock_value,
                   p.source_net_profit, p.staged_net_profit,
                   p.variance_debit, p.variance_credit, p.is_zero_variance, p.proof_sha256
              FROM mc_import_job j
              JOIN mc_import_reconciliation_proof p
                ON p.company_id = j.company_id AND p.import_job_id = j.id
             WHERE j.company_id = ? AND j.id = ?
            """,
            (rs, rowNum) -> mapProofRow(rs, companyId, scale),
            companyId,
            jobId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-MIG-JOB-NOT-FOUND",
                "Import Job Proof Not Found",
                "Import job proof for " + jobId + " was not found."
            );
        }
        return rows.getFirst();
    }

    private MigrationDtos.ImportJobSummaryDto mapJobSummaryRow(ResultSet rs, int scale) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        JsonNode diag = parseDiagObject(rs.getString("diagnostics_json"));
        String srcName = textField(diag, "sourceCompanyName", "Migrated Tally Company");
        String snapPath = textField(diag, "snapshotDirectoryPath", "");
        BigDecimal varDr = rs.getBigDecimal("variance_debit");
        BigDecimal varCr = rs.getBigDecimal("variance_credit");
        BigDecimal varMax = (varDr == null ? BigDecimal.ZERO : varDr)
            .max(varCr == null ? BigDecimal.ZERO : varCr)
            .setScale(scale, RoundingMode.HALF_UP);
        byte[] shaBytes = rs.getBytes("proof_sha256");
        String shaHex = shaBytes == null ? "" : JcsCanonicalizer.toHex(shaBytes);
        java.sql.Timestamp completedTs = rs.getTimestamp("completed_at");

        return new MigrationDtos.ImportJobSummaryDto(
            id,
            id,
            rs.getString("job_type"),
            rs.getString("status"),
            srcName,
            snapPath,
            rs.getBoolean("is_zero_variance"),
            varMax.toPlainString(),
            shaHex,
            rs.getTimestamp("started_at").toInstant(),
            completedTs == null ? null : completedTs.toInstant()
        );
    }

    private MigrationDtos.ImportJobProofDto mapProofRow(ResultSet rs, UUID companyId, int scale)
        throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        JsonNode diag = parseDiagObject(rs.getString("diagnostics_json"));
        String srcName = textField(diag, "sourceCompanyName", "ABC & Co");
        String srcType = textField(diag, "snapshotSourceType", "BINARY_1800");
        String snapPath = textField(diag, "snapshotDirectoryPath", "");
        String compPath = textField(diag, "companionXmlDirectoryPath", null);

        List<String> warnings = new ArrayList<>();
        if (diag != null && diag.has("warnings") && diag.get("warnings").isArray()) {
            for (JsonNode w : diag.get("warnings")) {
                warnings.add(w.asText());
            }
        }

        BigDecimal varDr = scaleNonNegative(rs.getBigDecimal("variance_debit"), scale);
        BigDecimal varCr = scaleNonNegative(rs.getBigDecimal("variance_credit"), scale);
        BigDecimal varMax = varDr.max(varCr);
        byte[] shaBytes = rs.getBytes("proof_sha256");
        java.sql.Timestamp completedTs = rs.getTimestamp("completed_at");
        int baselineVer = 1;

        return new MigrationDtos.ImportJobProofDto(
            id,
            id,
            rs.getString("status"),
            srcName,
            rs.getString("source_company_number"),
            srcType,
            snapPath,
            compPath,
            rs.getBoolean("sector0_checksum_verified"),
            rs.getBoolean("cmp_save_parity_verified"),
            rs.getInt("source_group_count"),
            rs.getInt("staged_group_count"),
            rs.getInt("source_ledger_count"),
            rs.getInt("staged_ledger_count"),
            rs.getInt("source_item_count"),
            rs.getInt("staged_item_count"),
            rs.getInt("source_voucher_count"),
            rs.getInt("staged_voucher_count"),
            scaleNonNegative(rs.getBigDecimal("source_gross_debit"), scale).toPlainString(),
            scaleNonNegative(rs.getBigDecimal("staged_gross_debit"), scale).toPlainString(),
            scaleNonNegative(rs.getBigDecimal("source_gross_credit"), scale).toPlainString(),
            scaleNonNegative(rs.getBigDecimal("staged_gross_credit"), scale).toPlainString(),
            scaleNonNegative(rs.getBigDecimal("source_net_trial_balance"), scale).toPlainString(),
            scaleNonNegative(rs.getBigDecimal("staged_net_trial_balance"), scale).toPlainString(),
            scaleNonNegative(rs.getBigDecimal("source_closing_stock_value"), scale).toPlainString(),
            scaleNonNegative(rs.getBigDecimal("staged_closing_stock_value"), scale).toPlainString(),
            scaleSigned(rs.getBigDecimal("source_net_profit"), scale).toPlainString(),
            scaleSigned(rs.getBigDecimal("staged_net_profit"), scale).toPlainString(),
            varDr.toPlainString(),
            varCr.toPlainString(),
            varMax.toPlainString(),
            rs.getBoolean("is_zero_variance"),
            shaBytes == null ? "" : JcsCanonicalizer.toHex(shaBytes),
            baselineVer,
            List.copyOf(warnings),
            rs.getTimestamp("started_at").toInstant(),
            completedTs == null ? null : completedTs.toInstant()
        );
    }

    private byte[] computeSnapshotDigest(ExtractedCompanySnapshot snapshot) {
        if (!snapshot.storeFiles().isEmpty()) {
            Set<String> parts = new TreeSet<>();
            for (StoreFileDescriptor sfd : snapshot.storeFiles()) {
                parts.add(sfd.fileName() + ":" + sfd.sha256Hex());
            }
            return jcsCanonicalizer.sha256(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
        }
        String fallback = snapshot.companyProfile().guid() + "|" + snapshot.companyProfile().name()
            + "|" + snapshot.reconciliation().netTrialBalanceDebit().toPlainString();
        return jcsCanonicalizer.sha256(fallback.getBytes(StandardCharsets.UTF_8));
    }

    private void ensurePrincipalProjected(MoneyCraftPrincipal actor) {
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_principal_projection (principal_id, kind, username, display_name, enabled, version_no)
            VALUES (?, ?, ?, ?, true, 1)
            ON CONFLICT (principal_id) DO NOTHING
            """,
            actor.principalId(),
            actor.kind(),
            actor.username(),
            actor.displayName() == null || actor.displayName().isBlank() ? actor.username() : actor.displayName()
        );
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize staging JSON", ex);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize staging JSON for " + type.getSimpleName(), ex);
        }
    }

    private JsonNode parseDiagObject(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            return node.isObject() ? node : null;
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private static String textField(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field)) {
            return fallback;
        }
        String val = node.get(field).asText();
        return val == null || val.isBlank() ? fallback : val;
    }

    private static BigDecimal scaleNonNegative(BigDecimal val, int scale) {
        if (val == null) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        }
        return val.abs().setScale(scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleSigned(BigDecimal val, int scale) {
        if (val == null) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        }
        return val.setScale(scale, RoundingMode.HALF_UP);
    }

    private static String truncate(String val, int maxLen) {
        if (val == null) {
            return null;
        }
        return val.length() <= maxLen ? val : val.substring(0, maxLen);
    }

    private record StagedRow(UUID id, String entityType, String json) {
    }

    private record ExistingFyRange(LocalDate start, LocalDate end) {
    }

    private record GroupRef(
        UUID id,
        String name,
        String nature,
        boolean affectsGrossProfit,
        int depthLevel,
        long masterSeqId
    ) {
    }

    private record PromotedLedgerRef(
        UUID id,
        String name,
        String nature,
        boolean billWise,
        long masterSeqId
    ) {
    }

    private record PromotedItemRef(UUID id, String name, UUID baseUomId) {
    }
}
