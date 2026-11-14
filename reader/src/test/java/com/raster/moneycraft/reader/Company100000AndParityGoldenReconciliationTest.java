package com.raster.moneycraft.reader;

import com.raster.moneycraft.reader.api.ReaderOptions;
import com.raster.moneycraft.reader.api.TallySnapshotReader;
import com.raster.moneycraft.reader.cli.MoneyCraftReaderCli;
import com.raster.moneycraft.reader.model.ExtractedCompanySnapshot;
import com.raster.moneycraft.reader.model.RawBillAllocation;
import com.raster.moneycraft.reader.model.RawVoucher;
import com.raster.moneycraft.reader.model.ReconciliationSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end penny-exact extraction and Trial Balance / P&L reconciliation tests against:
 * 1. ABC & Co (Company 100000) — pure binary .1800, hybrid .1800 + XML, and companion XML modes.
 * 2. MoneyCraft Parity Benchmark (parity_golden) — Avg. Cost + FIFO stock valuation & Trial Balance.
 * 3. MoneyCraftReaderCli standalone command-line verification & deterministic JSON output.
 */
class Company100000AndParityGoldenReconciliationTest {

    private static final Path WORKSPACE_ROOT = Path.of("/Users/pushparaj/projects/Money_Craft");
    private static final Path SOURCE_100000_DIR =
            WORKSPACE_ROOT.resolve("argon_workspace/Source/Binary/Tally Prime/Data/100000");
    private static final Path ORACLE_100000_XML_DIR =
            WORKSPACE_ROOT.resolve("argon_workspace/windows_lab_oracles/100000");
    private static final Path PARITY_GOLDEN_DIR =
            WORKSPACE_ROOT.resolve("argon_workspace/windows_lab_oracles/parity_golden");

    @Test
    void reconcilesCompany100000DirectlyFromPureBinary1800Stores(@TempDir Path tempDir) throws IOException {
        Path scratch100000 = SectorAndShadowVerificationTest.copyNonLicenseStoreFiles(
                SOURCE_100000_DIR, tempDir.resolve("100000"));

        TallySnapshotReader reader = new TallySnapshotReader();
        ExtractedCompanySnapshot snapshot = reader.read(scratch100000);

        assertEquals("BINARY_1800", snapshot.snapshotSourceType());
        assertEquals("100000", snapshot.companyProfile().companyNumber());
        assertEquals("ABC & Co", snapshot.companyProfile().name());
        assertEquals("5eeb242a-976c-44e3-898e-0423c32b464c", snapshot.companyProfile().guid());
        assertEquals("Tamil Nadu", snapshot.companyProfile().stateName());
        assertEquals("India", snapshot.companyProfile().countryName());
        assertEquals("636005", snapshot.companyProfile().pincode());
        assertEquals(LocalDate.of(2026, 4, 1), snapshot.companyProfile().startingFrom());
        assertEquals(LocalDate.of(2026, 4, 1), snapshot.companyProfile().booksFrom());

        ReconciliationSummary rec = snapshot.reconciliation();
        assertEquals(28, rec.groupCount());
        assertEquals(24, rec.ledgerCount());
        assertEquals(24, rec.voucherTypeCount());
        assertEquals(13, rec.postedVoucherCount());
        assertEquals(2, rec.deletedVoucherCount());
        assertTrue(rec.shadowCommitVerified());
        assertTrue(rec.voucherDoubleEntryBalanced());
        assertTrue(rec.trialBalanceReconciled());

        assertEquals(new BigDecimal("365376.00"), rec.grossDebitTurnover());
        assertEquals(new BigDecimal("365376.00"), rec.grossCreditTurnover());
        assertEquals(new BigDecimal("338580.00"), rec.netTrialBalanceDebit());
        assertEquals(new BigDecimal("338580.00"), rec.netTrialBalanceCredit());
        assertEquals(new BigDecimal("302843.94"), rec.topGroupTrialBalanceDebit());
        assertEquals(new BigDecimal("302843.94"), rec.topGroupTrialBalanceCredit());
        assertEquals(new BigDecimal("0.00"), rec.closingStockValue());
        assertEquals(new BigDecimal("80155.70"), rec.grossProfit());
        assertEquals(new BigDecimal("80155.41"), rec.netProfit());

        List<String> extractedBillRefs = snapshot.vouchers().stream()
                .filter(RawVoucher::isPostedAccounting)
                .flatMap(v -> v.ledgerLines().stream())
                .flatMap(l -> l.billAllocations().stream())
                .map(RawBillAllocation::billName)
                .toList();
        assertTrue(extractedBillRefs.contains("30691"), "Expected LinkMgr bill reference 30691");
        assertTrue(extractedBillRefs.contains("2500028"), "Expected LinkMgr settled bill reference 2500028");
    }

    @Test
    void reconcilesCompany100000HybridAndCompanionXmlParity(@TempDir Path tempDir) throws IOException {
        Path scratch100000 = SectorAndShadowVerificationTest.copyNonLicenseStoreFiles(
                SOURCE_100000_DIR, tempDir.resolve("100000"));
        TallySnapshotReader reader = new TallySnapshotReader();

        ExtractedCompanySnapshot hybrid = reader.read(
                ReaderOptions.strictWithCompanion(scratch100000, ORACLE_100000_XML_DIR));
        assertEquals("BINARY_1800_WITH_COMPANION_XML", hybrid.snapshotSourceType());
        assertTrue(hybrid.diagnostics().stream().anyMatch(d -> "COMPANION_XML_PARITY_VERIFIED".equals(d.code())));

        ExtractedCompanySnapshot xmlOnly = reader.read(ORACLE_100000_XML_DIR);
        assertEquals("COMPANION_XML", xmlOnly.snapshotSourceType());
        assertEquals(28, xmlOnly.reconciliation().groupCount());
        assertEquals(24, xmlOnly.reconciliation().ledgerCount());
        assertEquals(24, xmlOnly.reconciliation().voucherTypeCount());
        assertEquals(13, xmlOnly.reconciliation().postedVoucherCount());
        assertEquals(new BigDecimal("365376.00"), xmlOnly.reconciliation().grossDebitTurnover());
        assertEquals(new BigDecimal("338580.00"), xmlOnly.reconciliation().netTrialBalanceDebit());
        assertEquals(new BigDecimal("80155.70"), xmlOnly.reconciliation().grossProfit());
        assertEquals(new BigDecimal("80155.41"), xmlOnly.reconciliation().netProfit());
    }

    @Test
    void reconcilesMoneyCraftParityBenchmarkAvgCostFifoAndTrialBalance() throws IOException {
        TallySnapshotReader reader = new TallySnapshotReader();
        ExtractedCompanySnapshot parity = reader.read(PARITY_GOLDEN_DIR);

        ReconciliationSummary rec = parity.reconciliation();
        assertEquals(28, rec.groupCount());
        assertEquals(24, rec.voucherTypeCount());
        assertEquals(9, rec.postedVoucherCount());
        assertEquals(2, parity.stockItems().size());
        assertTrue(rec.voucherDoubleEntryBalanced());
        assertTrue(rec.trialBalanceReconciled());

        assertEquals(new BigDecimal("760448.00"), rec.netTrialBalanceDebit());
        assertEquals(new BigDecimal("760448.00"), rec.netTrialBalanceCredit());
        assertEquals(new BigDecimal("45685.71"), rec.closingStockValue());
        assertEquals(new BigDecimal("49385.71"), rec.grossProfit());
        assertEquals(new BigDecimal("4385.71"), rec.netProfit());
    }

    @Test
    void executesStandaloneCliVerifyAndWritesDeterministicJson(@TempDir Path tempDir) throws IOException {
        Path scratch100000 = SectorAndShadowVerificationTest.copyNonLicenseStoreFiles(
                SOURCE_100000_DIR, tempDir.resolve("100000"));
        Path outJson = tempDir.resolve("out/reconciliation_100000.json");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = MoneyCraftReaderCli.execute(
                new String[]{
                        "--input", scratch100000.toString(),
                        "--verify",
                        "--companion-xml", ORACLE_100000_XML_DIR.toString(),
                        "--output-json", outJson.toString()
                },
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertEquals(0, exitCode, () -> "CLI stderr: " + stderr.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(outJson));
        String rendered = Files.readString(outJson, StandardCharsets.UTF_8);
        assertTrue(rendered.contains("\"name\": \"ABC & Co\""));
        assertTrue(rendered.contains("\"grossDebitTurnover\": \"365376.00\""));
        assertTrue(rendered.contains("\"netTrialBalanceDebit\": \"338580.00\""));
        assertTrue(rendered.contains("\"netProfit\": \"80155.41\""));
        assertTrue(rendered.contains("\"trialBalanceReconciled\": true"));

        // Corrupt CmpSave.1800 and confirm CLI --verify exits non-zero (1)
        Path cmpSave = scratch100000.resolve("CmpSave.1800");
        byte[] bytes = Files.readAllBytes(cmpSave);
        bytes[3] ^= (byte) 0x55;
        Files.write(cmpSave, bytes);

        int failExit = MoneyCraftReaderCli.execute(
                new String[]{"--input", scratch100000.toString(), "--verify"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertEquals(1, failExit);
    }
}
