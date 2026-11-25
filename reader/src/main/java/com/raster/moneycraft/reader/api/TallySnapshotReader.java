package com.raster.moneycraft.reader.api;

import com.raster.moneycraft.reader.format.BinaryStoreScanner;
import com.raster.moneycraft.reader.format.CompanionXmlSnapshotParser;
import com.raster.moneycraft.reader.format.Sector512PageReader;
import com.raster.moneycraft.reader.model.DiagnosticSeverity;
import com.raster.moneycraft.reader.model.ExtractedCompanySnapshot;
import com.raster.moneycraft.reader.model.ReaderDiagnostic;
import com.raster.moneycraft.reader.model.ReconciliationSummary;
import com.raster.moneycraft.reader.verify.SnapshotTrialBalanceCalculator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Standalone entry point for reading and reconciling legacy .1800 company snapshot directories
 * and companion canonical XML snapshots.
 * Zero Spring, Hibernate/JPA, Servlet, or network dependencies (MIG-003).
 */
public final class TallySnapshotReader {

    private final Sector512PageReader pageReader;
    private final BinaryStoreScanner binaryScanner;
    private final CompanionXmlSnapshotParser xmlParser;
    private final SnapshotTrialBalanceCalculator trialBalanceCalculator;

    public TallySnapshotReader() {
        this.pageReader = new Sector512PageReader();
        this.binaryScanner = new BinaryStoreScanner();
        this.xmlParser = new CompanionXmlSnapshotParser();
        this.trialBalanceCalculator = new SnapshotTrialBalanceCalculator();
    }

    public ExtractedCompanySnapshot read(Path inputDirectory) throws IOException {
        return read(ReaderOptions.strict(inputDirectory));
    }

    public ExtractedCompanySnapshot read(ReaderOptions options) throws IOException {
        Objects.requireNonNull(options, "options must not be null");
        Path inputDir = options.inputDirectory();
        if (!Files.isDirectory(inputDir)) {
            throw new CorruptSnapshotException(
                    "INPUT_NOT_DIRECTORY",
                    "Input snapshot path does not exist or is not a directory: " + inputDir);
        }

        List<Path> storeFiles = pageReader.discoverStoreFiles(inputDir);
        boolean hasBinaryStores = !storeFiles.isEmpty();

        if (hasBinaryStores) {
            return readBinaryWithOptionalCompanion(options);
        }
        return readXmlSnapshotOnly(options);
    }

    private ExtractedCompanySnapshot readBinaryWithOptionalCompanion(ReaderOptions options) throws IOException {
        BinaryStoreScanner.BinaryScanResult binaryResult =
                binaryScanner.scanDirectory(options.inputDirectory(), options.failOnShadowMismatch());
        List<ReaderDiagnostic> diagnostics = new ArrayList<>(binaryResult.diagnostics());

        if (options.companionXmlDirectory() != null) {
            CompanionXmlSnapshotParser.XmlSnapshotResult xmlResult =
                    xmlParser.parseDirectory(options.companionXmlDirectory());
            diagnostics.addAll(xmlResult.diagnostics());

            ReconciliationSummary binarySummary = trialBalanceCalculator.calculate(
                    binaryResult.groups(),
                    binaryResult.ledgers(),
                    binaryResult.voucherTypes(),
                    binaryResult.stockItems(),
                    binaryResult.vouchers(),
                    binaryResult.shadowCommitVerified());
            ReconciliationSummary xmlSummary = trialBalanceCalculator.calculate(
                    xmlResult.groups(),
                    xmlResult.ledgers(),
                    xmlResult.voucherTypes(),
                    xmlResult.stockItems(),
                    xmlResult.vouchers(),
                    binaryResult.shadowCommitVerified());

            if (binarySummary.netTrialBalanceDebit().compareTo(xmlSummary.netTrialBalanceDebit()) == 0
                    && binarySummary.grossDebitTurnover().compareTo(xmlSummary.grossDebitTurnover()) == 0) {
                diagnostics.add(new ReaderDiagnostic(
                        DiagnosticSeverity.INFO,
                        "COMPANION_XML_PARITY_VERIFIED",
                        "HYBRID",
                        "Binary .1800 extraction and companion XML reconcile identically (TB="
                                + binarySummary.netTrialBalanceDebit() + ")"));
            } else {
                ReaderDiagnostic mismatch = new ReaderDiagnostic(
                        DiagnosticSeverity.ERROR,
                        "COMPANION_XML_PARITY_MISMATCH",
                        "HYBRID",
                        "Binary TB (" + binarySummary.netTrialBalanceDebit()
                                + ") != Companion XML TB (" + xmlSummary.netTrialBalanceDebit() + ")");
                diagnostics.add(mismatch);
                if (options.failOnReconciliationMismatch()) {
                    throw new CorruptSnapshotException(mismatch.code(), mismatch.message());
                }
            }

            enforceReconciliationIfStrict(options, binarySummary);
            return new ExtractedCompanySnapshot(
                    "BINARY_1800_WITH_COMPANION_XML",
                    binaryResult.companyProfile(),
                    binaryResult.storeFiles(),
                    binaryResult.groups(),
                    binaryResult.ledgers(),
                    binaryResult.voucherTypes(),
                    binaryResult.uoms(),
                    binaryResult.godowns(),
                    binaryResult.stockGroups(),
                    binaryResult.stockItems(),
                    binaryResult.vouchers(),
                    binarySummary,
                    diagnostics);
        }

        ReconciliationSummary summary = trialBalanceCalculator.calculate(
                binaryResult.groups(),
                binaryResult.ledgers(),
                binaryResult.voucherTypes(),
                binaryResult.stockItems(),
                binaryResult.vouchers(),
                binaryResult.shadowCommitVerified());
        enforceReconciliationIfStrict(options, summary);

        return new ExtractedCompanySnapshot(
                "BINARY_1800",
                binaryResult.companyProfile(),
                binaryResult.storeFiles(),
                binaryResult.groups(),
                binaryResult.ledgers(),
                binaryResult.voucherTypes(),
                binaryResult.uoms(),
                binaryResult.godowns(),
                binaryResult.stockGroups(),
                binaryResult.stockItems(),
                binaryResult.vouchers(),
                summary,
                diagnostics);
    }

    private ExtractedCompanySnapshot readXmlSnapshotOnly(ReaderOptions options) throws IOException {
        Path xmlDir = options.companionXmlDirectory() != null
                ? options.companionXmlDirectory()
                : options.inputDirectory();
        CompanionXmlSnapshotParser.XmlSnapshotResult xmlResult = xmlParser.parseDirectory(xmlDir);
        ReconciliationSummary summary = trialBalanceCalculator.calculate(
                xmlResult.groups(),
                xmlResult.ledgers(),
                xmlResult.voucherTypes(),
                xmlResult.stockItems(),
                xmlResult.vouchers(),
                true);
        enforceReconciliationIfStrict(options, summary);

        return new ExtractedCompanySnapshot(
                "COMPANION_XML",
                xmlResult.companyProfile(),
                List.of(),
                xmlResult.groups(),
                xmlResult.ledgers(),
                xmlResult.voucherTypes(),
                xmlResult.uoms(),
                xmlResult.godowns(),
                xmlResult.stockGroups(),
                xmlResult.stockItems(),
                xmlResult.vouchers(),
                summary,
                xmlResult.diagnostics());
    }

    private static void enforceReconciliationIfStrict(ReaderOptions options, ReconciliationSummary summary) {
        if (options.failOnReconciliationMismatch() && !summary.trialBalanceReconciled()) {
            throw new CorruptSnapshotException(
                    "TRIAL_BALANCE_OUT_OF_BALANCE",
                    "Snapshot reconciliation failed: GrossDR=" + summary.grossDebitTurnover()
                            + ", GrossCR=" + summary.grossCreditTurnover()
                            + ", NetTB_DR=" + summary.netTrialBalanceDebit()
                            + ", NetTB_CR=" + summary.netTrialBalanceCredit());
        }
    }
}
