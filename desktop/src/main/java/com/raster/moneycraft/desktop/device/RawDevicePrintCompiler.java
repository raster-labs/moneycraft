package com.raster.moneycraft.desktop.device;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Objects;

/**
 * Bounded native printer-language compiler (Doc 28 §5.2, ND-S07..S08, DEV-001 Path B).
 * Compiles an immutable {@link PrintableDocumentModel} into byte-preserving Epson ESC/POS
 * thermal receipts, ESC/P continuous-form dot-matrix vouchers, or TSPL barcode labels under an
 * admitted {@link DeviceProfile}.
 *
 * <p>Security &amp; Accounting Invariants:
 * <ul>
 *   <li>Strictly rejects all ASCII C0 control characters (0x00-0x1F including ESC 0x1B, FS 0x1C,
 *       GS 0x1D, LF, CR, FF), DEL 0x7F, and C1 controls (U+0080-U+009F) in untrusted customer/imported
 *       fields so adversarial text can never inject paper cuts, cash-drawer kicks (ESC p), code-page
 *       mutations, or extra copies (AT09-DEV-001-C).</li>
 *   <li>Uses {@link CodingErrorAction#REPORT} and blocks with {@link UnsupportedGlyphException} if
 *       required glyphs (such as U+20B9 '₹' or customer script characters) cannot be represented in
 *       the profile's declared charset without an explicit approved fallback (AT09-DEV-001-B).</li>
 * </ul>
 */
public final class RawDevicePrintCompiler {

    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;
    private static final byte LF = 0x0A;
    private static final byte CR = 0x0D;
    private static final byte FF = 0x0C;

    public byte[] compile(
        PrintableDocumentModel document,
        DeviceProfile profile,
        DeviceAuthorityContext authority
    ) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(authority, "authority must not be null");

        profile.assertAdmitted();
        authority.assertCanOutputDocument(document.companyId(), document.containsSelectedPrivateFields());
        inspectAllFieldsForControlBytes(document);
        verifyPeripheralActionPermissions(document, profile, authority);

        Charset charset = resolveExplicitCharset(profile);
        return switch (profile.commandLanguage()) {
            case EPSON_ESC_POS -> compileEpsonEscPos(document, profile, charset);
            case DOT_MATRIX_ESC_P -> compileDotMatrixEscP(document, profile, charset);
            case BARCODE_LABEL_TSPL -> compileBarcodeLabelTspl(document, profile, charset);
            case OS_PAGE_PDF -> throw new RawPrintCompilationException(
                "MC-RAW-PRINT-WRONG-ROUTE",
                "OS_PAGE_PDF profiles must use PagePrintAdapter rather than RawDevicePrintCompiler."
            );
        };
    }

    private static void verifyPeripheralActionPermissions(
        PrintableDocumentModel document,
        DeviceProfile profile,
        DeviceAuthorityContext authority
    ) {
        if (document.requestCashDrawerKick()) {
            if (!profile.cashDrawerKickPermitted() || !authority.hasClosedPermission("device.cashDrawer")) {
                throw new DeviceSecurityException(
                    "MC-DEVICE-CASH-DRAWER-DENIED",
                    "Cash-drawer kick requires both an admitted profile capability and 'device.cashDrawer' permission."
                );
            }
        }
        if (document.requestPaperCut() && !profile.cutterPermitted()) {
            throw new DeviceSecurityException(
                "MC-DEVICE-CUTTER-DENIED",
                "Paper cutter operation is not permitted by profile '" + profile.profileId() + "'."
            );
        }
    }

    private static void inspectAllFieldsForControlBytes(PrintableDocumentModel doc) {
        assertCleanText("companyLegalName", doc.companyLegalName());
        assertCleanText("voucherNumber", doc.voucherNumber());
        assertCleanText("currencySymbol", doc.currencySymbol());
        if (doc.customerName() != null) {
            assertCleanText("customerName", doc.customerName());
        }
        if (doc.narration() != null) {
            assertCleanText("narration", doc.narration());
        }
        if (doc.barcodeValue() != null) {
            assertCleanBarcodeText("barcodeValue", doc.barcodeValue());
        }
        for (int i = 0; i < doc.lineItems().size(); i++) {
            PrintableLineItem item = doc.lineItems().get(i);
            assertCleanText("lineItems[" + i + "].description", item.description());
            assertCleanText("lineItems[" + i + "].uomSymbol", item.uomSymbol());
        }
    }

    /**
     * Rejects ASCII C0 (0x00..0x1F, including ESC 0x1B, FS 0x1C, GS 0x1D), DEL 0x7F, and C1 (0x80..0x9F).
     */
    public static void assertCleanText(String fieldName, String value) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch <= 0x1F || ch == 0x7F || (ch >= 0x80 && ch <= 0x9F)) {
                throw new RawPrintCompilationException(
                    "MC-PRINT-CONTROL-BYTE-REJECTED",
                    "Rejected forbidden control character 0x" + String.format("%02X", (int) ch)
                        + " in untrusted field '" + fieldName + "' at index " + i + " (AT09-DEV-001-C)."
                );
            }
        }
    }

    private static void assertCleanBarcodeText(String fieldName, String barcode) {
        assertCleanText(fieldName, barcode);
        if (barcode.isBlank()) {
            throw new RawPrintCompilationException("MC-BARCODE-EMPTY", "Barcode identifier must not be blank.");
        }
        for (int i = 0; i < barcode.length(); i++) {
            char ch = barcode.charAt(i);
            if (ch < 0x20 || ch > 0x7E || ch == '"' || ch == '\\') {
                throw new RawPrintCompilationException(
                    "MC-BARCODE-INVALID-CHAR",
                    "Unsupported delimiter or non-ASCII character '" + ch + "' in barcode '" + fieldName + "'."
                );
            }
        }
    }

    private static byte[] compileEpsonEscPos(
        PrintableDocumentModel doc,
        DeviceProfile profile,
        Charset charset
    ) {
        int cols = profile.columnsPerLine();
        String currency = resolveRepresentableCurrency(doc.currencySymbol(), profile, charset);

        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        // ESC @ : Initialize printer
        out.write(ESC);
        out.write('@');
        // ESC t n : Select character code table explicitly
        out.write(ESC);
        out.write('t');
        out.write(profile.escPosCodePageByte() & 0xFF);

        // Center alignment: ESC a 1
        out.write(ESC);
        out.write('a');
        out.write(1);
        writeEncodedLine(out, "companyLegalName", fitOrFail("companyLegalName", doc.companyLegalName(), cols), profile, charset, false);
        if (doc.copyBanner() == PrintableDocumentModel.CopyBanner.DUPLICATE_REPRINT) {
            writeEncodedLine(out, "copyBanner", "*** DUPLICATE / REPRINT ***", profile, charset, false);
        }

        // Left alignment: ESC a 0
        out.write(ESC);
        out.write('a');
        out.write(0);
        writeEncodedLine(out, "separator", "-".repeat(cols), profile, charset, false);
        writeEncodedLine(
            out,
            "voucherHeader",
            fitOrFail("voucherHeader", "Vch: " + doc.voucherNumber() + "  Dt: " + doc.documentDate(), cols),
            profile,
            charset,
            false
        );
        if (doc.customerName() != null && !doc.customerName().isBlank()) {
            writeEncodedLine(
                out,
                "customerName",
                fitOrFail("customerName", "Party: " + doc.customerName(), cols),
                profile,
                charset,
                false
            );
        }
        writeEncodedLine(out, "separator", "-".repeat(cols), profile, charset, false);

        for (int i = 0; i < doc.lineItems().size(); i++) {
            PrintableLineItem item = doc.lineItems().get(i);
            String amtText = formatMoney(currency, item.amount());
            String qtyLine = item.quantity().toPlainString() + " " + item.uomSymbol() + " @ " + item.rate().toPlainString();
            writeEncodedLine(out, "lineItems[" + i + "].description", truncateSafe(item.description(), cols), profile, charset, false);
            writeEncodedLine(out, "lineItems[" + i + "].amount", twoColumnLine("  " + qtyLine, amtText, cols, "lineAmount"), profile, charset, false);
        }

        writeEncodedLine(out, "separator", "-".repeat(cols), profile, charset, false);
        String totalRendered = formatMoney(currency, doc.totalAmount());
        writeEncodedLine(out, "totalAmount", twoColumnLine("TOTAL", totalRendered, cols, "totalAmount"), profile, charset, false);

        if (doc.narration() != null && !doc.narration().isBlank()) {
            writeEncodedLine(out, "narration", truncateSafe("Ref: " + doc.narration(), cols), profile, charset, false);
        }

        if (doc.barcodeValue() != null) {
            String symbology = doc.barcodeSymbology() == null ? "CODE128" : doc.barcodeSymbology();
            if (!profile.supportedBarcodeSymbologies().contains(symbology)) {
                throw new RawPrintCompilationException(
                    "MC-BARCODE-SYMBOLOGY-UNSUPPORTED",
                    "Profile '" + profile.profileId() + "' does not admit barcode symbology '" + symbology + "'."
                );
            }
            byte[] codeBytes = doc.barcodeValue().getBytes(StandardCharsets.US_ASCII);
            // GS k 73 len {B <data> (ESC/POS Code128 Function B, preserving leading zeros verbatim)
            out.write(GS);
            out.write('k');
            out.write(73);
            out.write(codeBytes.length + 2);
            out.write('{');
            out.write('B');
            out.writeBytes(codeBytes);
            out.write(LF);
        }

        // ESC d 3 : Feed 3 lines
        out.write(ESC);
        out.write('d');
        out.write(3);

        if (doc.requestPaperCut()) {
            // GS V 66 0 : Partial cut with feed
            out.write(GS);
            out.write('V');
            out.write(66);
            out.write(0);
        }
        if (doc.requestCashDrawerKick()) {
            // ESC p 0 25 250 : Explicit authorised drawer pulse
            out.write(ESC);
            out.write('p');
            out.write(0);
            out.write(25);
            out.write(250);
        }

        return out.toByteArray();
    }

    private static byte[] compileDotMatrixEscP(
        PrintableDocumentModel doc,
        DeviceProfile profile,
        Charset charset
    ) {
        int cols = profile.columnsPerLine();
        int maxLines = profile.linesPerPage();
        if (maxLines <= 0) {
            throw new RawPrintCompilationException(
                "MC-DOTMATRIX-GEOMETRY-INVALID",
                "Dot-matrix continuous-form profile requires positive linesPerPage."
            );
        }
        String currency = resolveRepresentableCurrency(doc.currencySymbol(), profile, charset);

        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        // ESC @ : Initialize ESC/P continuous-form impact printer
        out.write(ESC);
        out.write('@');

        int emittedLines = 0;
        writeEncodedLine(out, "companyLegalName", fitOrFail("companyLegalName", doc.companyLegalName(), cols), profile, charset, true);
        emittedLines++;

        if (doc.copyBanner() == PrintableDocumentModel.CopyBanner.DUPLICATE_REPRINT) {
            writeEncodedLine(out, "copyBanner", "DUPLICATE / REPRINT COPY", profile, charset, true);
            emittedLines++;
        }

        writeEncodedLine(
            out,
            "voucherHeader",
            twoColumnLine("Voucher No: " + doc.voucherNumber(), "Date: " + doc.documentDate(), cols, "voucherHeader"),
            profile,
            charset,
            true
        );
        emittedLines++;

        if (doc.customerName() != null && !doc.customerName().isBlank()) {
            writeEncodedLine(out, "customerName", fitOrFail("customerName", "Account: " + doc.customerName(), cols), profile, charset, true);
            emittedLines++;
        }

        writeEncodedLine(out, "rule", "=".repeat(cols), profile, charset, true);
        emittedLines++;

        for (int i = 0; i < doc.lineItems().size(); i++) {
            if (emittedLines >= maxLines - 3) {
                out.write(FF);
                emittedLines = 0;
            }
            PrintableLineItem item = doc.lineItems().get(i);
            String left = truncateSafe(item.description() + " (" + item.quantity().toPlainString() + " " + item.uomSymbol() + ")", cols - 20);
            String right = formatMoney(currency, item.amount());
            writeEncodedLine(out, "lineItems[" + i + "]", twoColumnLine(left, right, cols, "lineAmount"), profile, charset, true);
            emittedLines++;
        }

        writeEncodedLine(out, "rule", "=".repeat(cols), profile, charset, true);
        writeEncodedLine(
            out,
            "totalAmount",
            twoColumnLine("TOTAL", formatMoney(currency, doc.totalAmount()), cols, "totalAmount"),
            profile,
            charset,
            true
        );
        // Form feed (0x0C) aligns continuous tractor-feed perforation to top-of-form
        out.write(FF);
        return out.toByteArray();
    }

    private static byte[] compileBarcodeLabelTspl(
        PrintableDocumentModel doc,
        DeviceProfile profile,
        Charset charset
    ) {
        if (doc.barcodeValue() == null || doc.barcodeValue().isBlank()) {
            throw new RawPrintCompilationException(
                "MC-BARCODE-REQUIRED",
                "BARCODE_LABEL_TSPL requires a non-blank barcodeValue."
            );
        }
        String symbology = doc.barcodeSymbology() == null ? "CODE128" : doc.barcodeSymbology();
        if (!profile.supportedBarcodeSymbologies().contains(symbology)) {
            throw new RawPrintCompilationException(
                "MC-BARCODE-SYMBOLOGY-UNSUPPORTED",
                "Barcode profile '" + profile.profileId() + "' does not support symbology '" + symbology + "'."
            );
        }
        if (profile.quietZoneDots() < 10) {
            throw new RawPrintCompilationException(
                "MC-BARCODE-QUIET-ZONE-TOO-SMALL",
                "Barcode profile must enforce at least a 10-dot quiet zone."
            );
        }
        if ("EAN13".equals(symbology) && !doc.barcodeValue().matches("^\\d{12,13}$")) {
            throw new RawPrintCompilationException(
                "MC-BARCODE-EAN13-INVALID",
                "EAN13 barcode requires 12 or 13 exact ASCII digits."
            );
        }

        String currency = resolveRepresentableCurrency(doc.currencySymbol(), profile, charset);
        String safeTitle = sanitizeTsplLiteral("companyLegalName", truncateSafe(doc.companyLegalName(), profile.columnsPerLine()));
        String safePrice = sanitizeTsplLiteral("totalAmount", formatMoney(currency, doc.totalAmount()));
        String exactBarcode = sanitizeTsplLiteral("barcodeValue", doc.barcodeValue());
        int xStart = profile.quietZoneDots();

        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        writeAsciiCommand(out, "SIZE " + profile.paperWidthMm() + " mm, 40 mm");
        writeAsciiCommand(out, "GAP 2 mm, 0 mm");
        writeAsciiCommand(out, "CLS");
        if (doc.copyBanner() == PrintableDocumentModel.CopyBanner.DUPLICATE_REPRINT) {
            writeAsciiCommand(out, "TEXT " + xStart + ",10,\"2\",0,1,1,\"DUPLICATE / REPRINT\"");
        }
        writeEncodedTsplText(out, "companyLegalName", xStart, 35, safeTitle, profile, charset);
        writeEncodedTsplText(out, "totalAmount", xStart, 65, safePrice, profile, charset);
        String tsplSym = "EAN13".equals(symbology) ? "EAN13" : "128";
        writeAsciiCommand(out, "BARCODE " + xStart + ",100,\"" + tsplSym + "\",60,1,0,2,2,\"" + exactBarcode + "\"");
        writeAsciiCommand(out, "PRINT 1,1");
        return out.toByteArray();
    }

    private static String sanitizeTsplLiteral(String fieldName, String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\\') >= 0) {
            throw new RawPrintCompilationException(
                "MC-BARCODE-DELIMITER-REJECTED",
                "Rejected TSPL quote/escape delimiter in field '" + fieldName + "'."
            );
        }
        return value;
    }

    private static void writeAsciiCommand(ByteArrayOutputStream out, String command) {
        out.writeBytes(command.getBytes(StandardCharsets.US_ASCII));
        out.write(CR);
        out.write(LF);
    }

    private static void writeEncodedTsplText(
        ByteArrayOutputStream out,
        String fieldName,
        int x,
        int y,
        String text,
        DeviceProfile profile,
        Charset charset
    ) {
        byte[] encodedText = encodeStrict(fieldName, text, profile, charset);
        String prefix = "TEXT " + x + "," + y + ",\"2\",0,1,1,\"";
        out.writeBytes(prefix.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(encodedText);
        out.write('"');
        out.write(CR);
        out.write(LF);
    }

    private static String resolveRepresentableCurrency(
        String currencySymbol,
        DeviceProfile profile,
        Charset charset
    ) {
        if (canEncodeAll(currencySymbol, profile, charset)) {
            return currencySymbol;
        }
        if (profile.approvedCurrencyFallbackToken().isPresent()) {
            String fallback = profile.approvedCurrencyFallbackToken().get();
            assertCleanText("approvedCurrencyFallbackToken", fallback);
            if (!canEncodeAll(fallback, profile, charset)) {
                throw new UnsupportedGlyphException(
                    "approvedCurrencyFallbackToken",
                    fallback.codePointAt(0),
                    profile.charsetName(),
                    "Configured fallback token cannot be encoded in profile charset."
                );
            }
            return fallback;
        }
        int unencodableCp = findFirstUnencodableCodePoint(currencySymbol, profile, charset);
        throw new UnsupportedGlyphException(
            "currencySymbol",
            unencodableCp,
            profile.charsetName(),
            "Select a printer profile with glyph support, configure an explicitly approved currency token "
                + "(such as 'INR '), or print via the server PDF page path."
        );
    }

    private static String formatMoney(String currencyToken, BigDecimal amount) {
        String sep = currencyToken.endsWith(" ") ? "" : " ";
        return currencyToken + sep + amount.toPlainString();
    }

    private static String twoColumnLine(String left, String right, int width, String fieldName) {
        int minRequired = left.length() + 1 + right.length();
        if (minRequired > width) {
            if (right.length() + 2 > width) {
                throw new RawPrintCompilationException(
                    "MC-PRINT-AMOUNT-OVERFLOW",
                    "Formatted amount '" + right + "' exceeds profile column width " + width + " in '" + fieldName + "'."
                );
            }
            int trimmedLeftLen = width - right.length() - 1;
            String trimmedLeft = left.substring(0, trimmedLeftLen);
            return trimmedLeft + " " + right;
        }
        int spaces = width - left.length() - right.length();
        return left + " ".repeat(spaces) + right;
    }

    private static String fitOrFail(String fieldName, String text, int width) {
        if (text.length() > width) {
            throw new RawPrintCompilationException(
                "MC-PRINT-GEOMETRY-OVERFLOW",
                "Field '" + fieldName + "' length (" + text.length()
                    + ") exceeds device column width (" + width + ")."
            );
        }
        return text;
    }

    private static String truncateSafe(String text, int maxCols) {
        if (text.length() <= maxCols) {
            return text;
        }
        return text.substring(0, maxCols);
    }

    private static void writeEncodedLine(
        ByteArrayOutputStream out,
        String fieldName,
        String line,
        DeviceProfile profile,
        Charset charset,
        boolean crLf
    ) {
        byte[] encoded = encodeStrict(fieldName, line, profile, charset);
        out.writeBytes(encoded);
        if (crLf) {
            out.write(CR);
        }
        out.write(LF);
    }

    private static byte[] encodeStrict(
        String fieldName,
        String value,
        DeviceProfile profile,
        Charset charset
    ) {
        int badCp = findFirstUnencodableCodePoint(value, profile, charset);
        if (badCp >= 0) {
            throw new UnsupportedGlyphException(
                fieldName,
                badCp,
                profile.charsetName(),
                "Silent character substitution ('?') is prohibited; use a compatible code page or page PDF output."
            );
        }
        CharsetEncoder encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteArrayOutputStream encodedOut = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            String chunk = new String(Character.toChars(cp));
            if (cp <= 0xFFFF && profile.customMappedGlyphs().contains((char) cp) && !encoder.canEncode(chunk)) {
                // Profile explicitly maps this custom single-byte character slot (e.g., downloadable user char 0xFE)
                encodedOut.write(0xFE);
            } else {
                try {
                    ByteBuffer bb = encoder.encode(CharBuffer.wrap(chunk));
                    byte[] slice = new byte[bb.remaining()];
                    bb.get(slice);
                    encodedOut.writeBytes(slice);
                } catch (CharacterCodingException ex) {
                    throw new UnsupportedGlyphException(
                        fieldName,
                        cp,
                        profile.charsetName(),
                        "Character encoding failed without silent replacement."
                    );
                }
            }
            i += Character.charCount(cp);
        }
        return encodedOut.toByteArray();
    }

    private static boolean canEncodeAll(String text, DeviceProfile profile, Charset charset) {
        return findFirstUnencodableCodePoint(text, profile, charset) < 0;
    }

    private static int findFirstUnencodableCodePoint(String text, DeviceProfile profile, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String chunk = new String(Character.toChars(cp));
            boolean customAllowed = cp <= 0xFFFF && profile.customMappedGlyphs().contains((char) cp);
            if (!customAllowed && !encoder.canEncode(chunk)) {
                return cp;
            }
            i += Character.charCount(cp);
        }
        return -1;
    }

    private static Charset resolveExplicitCharset(DeviceProfile profile) {
        try {
            return Charset.forName(profile.charsetName());
        } catch (UnsupportedCharsetException ex) {
            throw new RawPrintCompilationException(
                "MC-PRINT-CHARSET-UNSUPPORTED",
                "JVM does not support declared printer profile charset '" + profile.charsetName() + "'."
            );
        }
    }
}
