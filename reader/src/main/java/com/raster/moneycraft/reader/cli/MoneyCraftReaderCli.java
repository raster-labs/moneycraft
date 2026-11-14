package com.raster.moneycraft.reader.cli;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.api.ReaderOptions;
import com.raster.moneycraft.reader.api.TallySnapshotReader;
import com.raster.moneycraft.reader.model.ExtractedCompanySnapshot;
import com.raster.moneycraft.reader.model.RawCompanyProfile;
import com.raster.moneycraft.reader.model.ReaderDiagnostic;
import com.raster.moneycraft.reader.model.ReconciliationSummary;
import com.raster.moneycraft.reader.model.StoreFileDescriptor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone CLI entry point for MoneyCraft .1800 binary & companion XML snapshot verification.
 * Usage: --input &lt;dir&gt; [--verify] [--companion-xml &lt;dir&gt;] [--output-json &lt;file&gt;]
 */
public final class MoneyCraftReaderCli {

    private MoneyCraftReaderCli() {
    }

    public static void main(String[] args) {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        Path inputDir = null;
        Path companionXmlDir = null;
        Path outputJsonFile = null;
        boolean verifyStrict = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--input" -> {
                    if (i + 1 >= args.length) {
                        err.println("Missing directory argument after --input");
                        return 2;
                    }
                    inputDir = Path.of(args[++i]);
                }
                case "--companion-xml" -> {
                    if (i + 1 >= args.length) {
                        err.println("Missing directory argument after --companion-xml");
                        return 2;
                    }
                    companionXmlDir = Path.of(args[++i]);
                }
                case "--output-json" -> {
                    if (i + 1 >= args.length) {
                        err.println("Missing file argument after --output-json");
                        return 2;
                    }
                    outputJsonFile = Path.of(args[++i]);
                }
                case "--verify" -> verifyStrict = true;
                case "--help", "-h" -> {
                    out.println("Usage: moneycraft-reader --input <dir> [--verify] [--companion-xml <dir>] [--output-json <file>]");
                    return 0;
                }
                default -> {
                    err.println("Unknown CLI option: " + arg);
                    return 2;
                }
            }
        }

        if (inputDir == null) {
            err.println("Required option --input <dir> was not provided");
            return 2;
        }

        try {
            ReaderOptions options = new ReaderOptions(
                    inputDir,
                    companionXmlDir,
                    verifyStrict,
                    verifyStrict);
            TallySnapshotReader reader = new TallySnapshotReader();
            ExtractedCompanySnapshot snapshot = reader.read(options);
            String json = renderDeterministicJson(snapshot);
            if (outputJsonFile != null) {
                if (outputJsonFile.getParent() != null) {
                    Files.createDirectories(outputJsonFile.getParent());
                }
                Files.writeString(outputJsonFile, json, StandardCharsets.UTF_8);
            }
            out.println(json);
            if (verifyStrict && (!snapshot.reconciliation().shadowCommitVerified()
                    || !snapshot.reconciliation().trialBalanceReconciled())) {
                return 1;
            }
            return 0;
        } catch (CorruptSnapshotException ex) {
            err.println("SNAPSHOT_VERIFICATION_FAILED [" + ex.errorCode() + "]: " + ex.getMessage());
            return 1;
        } catch (IOException ex) {
            err.println("IO_ERROR: " + ex.getMessage());
            return 1;
        }
    }

    public static String renderDeterministicJson(ExtractedCompanySnapshot snapshot) {
        RawCompanyProfile cmp = snapshot.companyProfile();
        ReconciliationSummary rec = snapshot.reconciliation();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");
        appendField(sb, 1, "snapshotSourceType", snapshot.snapshotSourceType(), true);

        sb.append("  \"company\": {\n");
        appendField(sb, 2, "companyNumber", cmp.companyNumber(), true);
        appendField(sb, 2, "guid", cmp.guid(), true);
        appendField(sb, 2, "name", cmp.name(), true);
        appendField(sb, 2, "stateName", cmp.stateName(), true);
        appendField(sb, 2, "countryName", cmp.countryName(), true);
        appendField(sb, 2, "startingFrom", cmp.startingFrom() != null ? cmp.startingFrom().toString() : "", true);
        appendField(sb, 2, "booksFrom", cmp.booksFrom() != null ? cmp.booksFrom().toString() : "", true);
        appendField(sb, 2, "currency", cmp.baseCurrencyFormalName(), false);
        sb.append("  },\n");

        sb.append("  \"reconciliation\": {\n");
        appendIntField(sb, 2, "groupCount", rec.groupCount(), true);
        appendIntField(sb, 2, "ledgerCount", rec.ledgerCount(), true);
        appendIntField(sb, 2, "voucherTypeCount", rec.voucherTypeCount(), true);
        appendIntField(sb, 2, "postedVoucherCount", rec.postedVoucherCount(), true);
        appendIntField(sb, 2, "deletedVoucherCount", rec.deletedVoucherCount(), true);
        appendField(sb, 2, "grossDebitTurnover", rec.grossDebitTurnover().toPlainString(), true);
        appendField(sb, 2, "grossCreditTurnover", rec.grossCreditTurnover().toPlainString(), true);
        appendField(sb, 2, "netTrialBalanceDebit", rec.netTrialBalanceDebit().toPlainString(), true);
        appendField(sb, 2, "netTrialBalanceCredit", rec.netTrialBalanceCredit().toPlainString(), true);
        appendField(sb, 2, "topGroupTrialBalanceDebit", rec.topGroupTrialBalanceDebit().toPlainString(), true);
        appendField(sb, 2, "topGroupTrialBalanceCredit", rec.topGroupTrialBalanceCredit().toPlainString(), true);
        appendField(sb, 2, "closingStockValue", rec.closingStockValue().toPlainString(), true);
        appendField(sb, 2, "grossProfit", rec.grossProfit().toPlainString(), true);
        appendField(sb, 2, "netProfit", rec.netProfit().toPlainString(), true);
        appendBoolField(sb, 2, "shadowCommitVerified", rec.shadowCommitVerified(), true);
        appendBoolField(sb, 2, "voucherDoubleEntryBalanced", rec.voucherDoubleEntryBalanced(), true);
        appendBoolField(sb, 2, "trialBalanceReconciled", rec.trialBalanceReconciled(), false);
        sb.append("  },\n");

        sb.append("  \"storeFiles\": [");
        if (!snapshot.storeFiles().isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < snapshot.storeFiles().size(); i++) {
                StoreFileDescriptor sf = snapshot.storeFiles().get(i);
                sb.append("    {\n");
                appendField(sb, 3, "fileName", sf.fileName(), true);
                appendLongField(sb, 3, "byteSize", sf.byteSize(), true);
                appendLongField(sb, 3, "totalSectors", sf.totalSectors(), true);
                appendLongField(sb, 3, "dataSectorCount", sf.sector0Header().dataSectorCount(), true);
                appendField(sb, 3, "releaseVersion", sf.sector0Header().releaseVersion().versionLabel(), true);
                appendField(sb, 3, "sha256", sf.sha256Hex(), false);
                sb.append("    }").append(i + 1 < snapshot.storeFiles().size() ? ",\n" : "\n");
            }
            sb.append("  ");
        }
        sb.append("],\n");

        sb.append("  \"diagnostics\": [");
        if (!snapshot.diagnostics().isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < snapshot.diagnostics().size(); i++) {
                ReaderDiagnostic d = snapshot.diagnostics().get(i);
                sb.append("    {\n");
                appendField(sb, 3, "severity", d.severity().name(), true);
                appendField(sb, 3, "code", d.code(), true);
                appendField(sb, 3, "storeName", d.storeName(), true);
                appendField(sb, 3, "message", d.message(), false);
                sb.append("    }").append(i + 1 < snapshot.diagnostics().size() ? ",\n" : "\n");
            }
            sb.append("  ");
        }
        sb.append("]\n");
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, int indentLevel, String key, String value, boolean comma) {
        indent(sb, indentLevel);
        sb.append('"').append(escapeJson(key)).append("\": \"").append(escapeJson(value)).append('"');
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendIntField(StringBuilder sb, int indentLevel, String key, int value, boolean comma) {
        indent(sb, indentLevel);
        sb.append('"').append(escapeJson(key)).append("\": ").append(value);
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendLongField(StringBuilder sb, int indentLevel, String key, long value, boolean comma) {
        indent(sb, indentLevel);
        sb.append('"').append(escapeJson(key)).append("\": ").append(value);
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendBoolField(StringBuilder sb, int indentLevel, String key, boolean value, boolean comma) {
        indent(sb, indentLevel);
        sb.append('"').append(escapeJson(key)).append("\": ").append(value);
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    public static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
