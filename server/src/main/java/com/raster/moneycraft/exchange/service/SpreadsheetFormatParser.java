package com.raster.moneycraft.exchange.service;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Safe, bounded multi-format tabular spreadsheet parser (SHEET-001 / SHEET-004).
 * Supports `.xlsx`, `.xls`, data-only `.xlsm`, OpenDocument `.ods` (1.2–1.4), and RFC 4180 `CSV`.
 * Never executes VBA macros or spreadsheet formulas, disables XML external entities (XXE),
 * rejects `.xlsb` and password-encrypted workbooks, and avoids binary floating-point arithmetic.
 */
@Component
public class SpreadsheetFormatParser {

    public static final int MAX_UPLOAD_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ZIP_ENTRIES = 512;
    public static final int MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ROWS = 50_000;
    public static final int MAX_COLUMNS = 1_024;

    private static final Set<String> FORMULA_ERROR_TOKENS = Set.of(
        "#REF!", "#VALUE!", "#DIV/0!", "#NAME?", "#N/A", "#NUM!", "#NULL!", "#GETTING_DATA"
    );

    private static final DateTimeFormatter STRICT_DD_MM_YYYY_SLASH = DateTimeFormatter
        .ofPattern("dd/MM/uuuu")
        .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter STRICT_DD_MM_YYYY_DASH = DateTimeFormatter
        .ofPattern("dd-MM-uuuu")
        .withResolverStyle(ResolverStyle.STRICT);

    public ParsedWorkbook parse(
        String fileName,
        String formatHint,
        byte[] rawBytes,
        char csvDelimiter
    ) {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-SHEET-EMPTY-INPUT",
                "Empty Spreadsheet Input",
                "Uploaded spreadsheet bytes must not be empty."
            );
        }
        if (rawBytes.length > MAX_UPLOAD_BYTES) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-SIZE-LIMIT",
                "Spreadsheet Exceeds Upload Limit",
                "Spreadsheet size " + rawBytes.length + " exceeds maximum " + MAX_UPLOAD_BYTES + " bytes."
            );
        }

        String safeName = fileName == null ? "upload.xlsx" : fileName.trim();
        String lowerName = safeName.toLowerCase(Locale.ROOT);
        String hint = formatHint == null ? "" : formatHint.trim().toUpperCase(Locale.ROOT);

        if (lowerName.endsWith(".xlsb") || "XLSB".equals(hint)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-UNSUPPORTED-FORMAT",
                "Binary .xlsb Format Not Admitted",
                "Excel binary .xlsb workbooks are outside the qualified profile; export as .xlsx, .ods, or CSV."
            );
        }
        if (lowerName.endsWith(".fods") || lowerName.endsWith(".odt") || lowerName.endsWith(".odp")) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-UNSUPPORTED-FORMAT",
                "Unsupported Document Format",
                "Flat .fods and non-spreadsheet ODF documents are outside the qualified spreadsheet profile."
            );
        }

        if (isZipMagic(rawBytes)) {
            ZipInspection zipInfo = inspectZipArchive(rawBytes);
            if (zipInfo.hasXlsbBinaryPart()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-SHEET-UNSUPPORTED-FORMAT",
                    "Binary .xlsb Archive Detected",
                    "Archive contains xl/workbook.bin (.xlsb); export as standard .xlsx, .ods, or CSV."
                );
            }
            if (zipInfo.isOdsArchive() || lowerName.endsWith(".ods") || "ODS".equals(hint)) {
                return parseOdsArchive(zipInfo);
            }
            return parseXssfWorkbook(rawBytes, zipInfo.hasVbaProject() || lowerName.endsWith(".xlsm"));
        }

        if (isOle2Magic(rawBytes)) {
            return parseHssfOrDetectEncryptedOle2(rawBytes);
        }

        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls") || lowerName.endsWith(".xlsm")
            || lowerName.endsWith(".ods")) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-MALFORMED-ARCHIVE",
                "Malformed Spreadsheet Container",
                "File '" + safeName + "' does not match valid OOXML/OLE2/ODF container magic bytes."
            );
        }

        return parseCsvBytes(safeName, rawBytes, csvDelimiter);
    }

    public ParsedWorkbook buildFromGoogleSheetsRows(
        String sheetName,
        List<List<String>> frozenRows
    ) {
        String effectiveSheet = (sheetName == null || sheetName.isBlank()) ? "Sheet1" : sheetName.trim();
        List<ParsedRow> rows = new ArrayList<>();
        if (frozenRows != null) {
            for (int r = 0; r < frozenRows.size(); r++) {
                List<String> srcRow = frozenRows.get(r);
                List<ParsedCell> cells = new ArrayList<>();
                if (srcRow != null) {
                    for (int c = 0; c < srcRow.size(); c++) {
                        String val = srcRow.get(c) == null ? "" : srcRow.get(c);
                        String trimmed = val.trim();
                        CellKind kind = trimmed.isEmpty() ? CellKind.BLANK : CellKind.TEXT;
                        if (FORMULA_ERROR_TOKENS.contains(trimmed.toUpperCase(Locale.ROOT))) {
                            kind = CellKind.FORMULA_ERROR;
                        }
                        cells.add(new ParsedCell(
                            c + 1,
                            toColumnLetters(c) + (r + 1),
                            kind,
                            val,
                            null,
                            null,
                            false,
                            null
                        ));
                    }
                }
                rows.add(new ParsedRow(r + 1, List.copyOf(cells)));
            }
        }
        ParsedSheet sheet = new ParsedSheet(effectiveSheet, List.copyOf(rows));
        return new ParsedWorkbook(
            "GOOGLE_SHEETS",
            "ISO_TEXT",
            false,
            false,
            "1.0",
            List.of(sheet),
            List.of("Captured frozen read-only Google Sheets snapshot prior to validation.")
        );
    }

    public static LocalDate resolveAccountingDate(
        ParsedCell cell,
        String workbookDateSystem,
        String datePatternHint,
        String sheetName
    ) {
        if (cell == null || cell.kind() == CellKind.BLANK) {
            throw invalidDate(sheetName, cell, "Voucher date cell is empty.");
        }
        if (cell.dateValue() != null) {
            return cell.dateValue();
        }
        if (cell.kind() == CellKind.NUMERIC && cell.numericValue() != null) {
            return excelSerialToLocalDate(cell.numericValue(), workbookDateSystem, sheetName, cell);
        }
        String raw = cell.rawText() == null ? "" : cell.rawText().trim();
        if (raw.isEmpty()) {
            throw invalidDate(sheetName, cell, "Voucher date cell is blank.");
        }
        if ("1900-02-29".equals(raw) || "29/02/1900".equals(raw) || "29-02-1900".equals(raw)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-INVALID-DATE",
                "Fictitious 1900-02-29 Accounting Date Rejected",
                "Cell " + formatCoord(sheetName, cell)
                    + " contains fictitious date 1900-02-29, which does not exist in the Gregorian calendar."
            );
        }
        if (raw.chars().allMatch(Character::isDigit) && raw.length() <= 7) {
            return excelSerialToLocalDate(new BigDecimal(raw), workbookDateSystem, sheetName, cell);
        }
        if (raw.contains("T") || raw.contains(" ")) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-DATETIME-TRUNCATION-FORBIDDEN",
                "Datetime Requires Explicit Timezone Policy",
                "Cell " + formatCoord(sheetName, cell)
                    + " contains a timestamp ('" + raw + "'); supply an accounting date without silent truncation."
            );
        }

        String hint = datePatternHint == null ? "AUTO" : datePatternHint.trim().toUpperCase(Locale.ROOT);
        try {
            if ("DD/MM/YYYY".equals(hint) || (raw.length() == 10 && raw.charAt(2) == '/' && raw.charAt(5) == '/')) {
                return LocalDate.parse(raw, STRICT_DD_MM_YYYY_SLASH);
            }
            if ("DD-MM-YYYY".equals(hint) || (raw.length() == 10 && raw.charAt(2) == '-' && raw.charAt(5) == '-')) {
                return LocalDate.parse(raw, STRICT_DD_MM_YYYY_DASH);
            }
            return LocalDate.parse(raw);
        } catch (DateTimeParseException ex) {
            throw invalidDate(
                sheetName,
                cell,
                "Cannot parse accounting date '" + raw + "' (expected YYYY-MM-DD or explicit DD/MM/YYYY)."
            );
        }
    }

    public static LocalDate excelSerialToLocalDate(
        BigDecimal serialDecimal,
        String dateSystem,
        String sheetName,
        ParsedCell cell
    ) {
        BigDecimal stripped = serialDecimal.stripTrailingZeros();
        if (stripped.scale() > 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-INVALID-DATE",
                "Fractional Excel Serial Datetime Rejected",
                "Cell " + formatCoord(sheetName, cell) + " serial " + serialDecimal.toPlainString()
                    + " includes a fractional time-of-day; silent truncation into an accounting date is forbidden."
            );
        }
        long serial;
        try {
            serial = stripped.longValueExact();
        } catch (ArithmeticException ex) {
            throw invalidDate(sheetName, cell, "Excel date serial out of bounds: " + serialDecimal.toPlainString());
        }

        if ("1904".equals(dateSystem)) {
            if (serial < 0L || serial > 2_957_003L) {
                throw invalidDate(sheetName, cell, "Excel 1904 serial out of range: " + serial);
            }
            return LocalDate.of(1904, 1, 1).plusDays(serial);
        }

        if (serial == 60L) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-INVALID-DATE",
                "Fictitious Excel 1900-02-29 Date Rejected",
                "Cell " + formatCoord(sheetName, cell)
                    + " carries Excel 1900-epoch serial 60 (fictitious 1900-02-29 leap day), which is invalid."
            );
        }
        if (serial < 1L || serial > 2_958_465L) {
            throw invalidDate(sheetName, cell, "Excel 1900 serial out of range: " + serial);
        }
        if (serial <= 59L) {
            return LocalDate.of(1899, 12, 31).plusDays(serial);
        }
        return LocalDate.of(1899, 12, 30).plusDays(serial);
    }

    public static String toColumnLetters(int zeroBasedCol) {
        int n = zeroBasedCol;
        StringBuilder sb = new StringBuilder();
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = (n / 26) - 1;
        }
        return sb.toString();
    }

    private static MoneyCraftException invalidDate(String sheetName, ParsedCell cell, String detail) {
        return new MoneyCraftException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MC-SHEET-INVALID-DATE",
            "Invalid Spreadsheet Accounting Date",
            "Cell " + formatCoord(sheetName, cell) + ": " + detail
        );
    }

    private static String formatCoord(String sheetName, ParsedCell cell) {
        String s = sheetName == null || sheetName.isBlank() ? "Sheet1" : sheetName;
        String ref = cell == null || cell.cellRef() == null ? "?" : cell.cellRef();
        return s + "!" + ref;
    }

    // =========================================================================
    // 1. OOXML (.xlsx / data-only .xlsm) & BIFF8 (.xls) via Apache POI
    // =========================================================================

    private ParsedWorkbook parseXssfWorkbook(byte[] rawBytes, boolean macroDetected) {
        byte[] dataOnlyBytes = macroDetected ? stripOoxmlMacroParts(rawBytes) : rawBytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(dataOnlyBytes))) {
            boolean is1904 = wb.isDate1904();
            String dateSystem = is1904 ? "1904" : "1900";
            String formatProfile = macroDetected ? "XLSM_DATA_ONLY" : "XLSX";
            List<String> diagnostics = new ArrayList<>();
            if (macroDetected) {
                diagnostics.add(
                    "Workbook contains VBA macro project storage; macros were physically stripped and never executed (XLSM_DATA_ONLY)."
                );
            }
            return extractPoiWorkbook(wb, formatProfile, dateSystem, macroDetected, diagnostics);
        } catch (EncryptedDocumentException ex) {
            throw encryptedWorkbookError();
        } catch (MoneyCraftException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-MALFORMED-WORKBOOK",
                "Invalid OOXML Spreadsheet",
                "Failed to parse .xlsx/.xlsm workbook: " + ex.getMessage()
            );
        }
    }

    private byte[] stripOoxmlMacroParts(byte[] rawBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(rawBytes));
             ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith("vbaproject.bin")
                    || lower.endsWith("vbaprojectsignature.bin")
                    || lower.contains("xl/macrosheets/")
                    || lower.contains("xl/dialogsheets/")) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(entry.getName()));
                if ("xl/_rels/workbook.xml.rels".equals(lower)) {
                    String relsXml = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    String cleaned = relsXml.replaceAll("<Relationship[^>]*vbaProject[^>]*/>", "");
                    zos.write(cleaned.getBytes(StandardCharsets.UTF_8));
                } else {
                    zis.transferTo(zos);
                }
                zos.closeEntry();
            }
            zos.finish();
            return bos.toByteArray();
        } catch (IOException ex) {
            return rawBytes;
        }
    }

    private ParsedWorkbook parseHssfOrDetectEncryptedOle2(byte[] rawBytes) {
        try (POIFSFileSystem poifs = new POIFSFileSystem(new ByteArrayInputStream(rawBytes))) {
            DirectoryNode root = poifs.getRoot();
            if (root.hasEntryCaseInsensitive("EncryptionInfo")
                || root.hasEntryCaseInsensitive("EncryptedPackage")) {
                throw encryptedWorkbookError();
            }
            boolean macroDetected = root.hasEntryCaseInsensitive("_VBA_PROJECT_CUR")
                || root.hasEntryCaseInsensitive("Macros");
            try (HSSFWorkbook wb = new HSSFWorkbook(poifs, false)) {
                boolean is1904 = wb.getInternalWorkbook().isUsing1904DateWindowing();
                String dateSystem = is1904 ? "1904" : "1900";
                List<String> diagnostics = new ArrayList<>();
                if (macroDetected) {
                    diagnostics.add(
                        "Legacy .xls workbook contains macro streams; macros were ignored and never executed."
                    );
                }
                return extractPoiWorkbook(wb, "XLS", dateSystem, macroDetected, diagnostics);
            }
        } catch (EncryptedDocumentException ex) {
            throw encryptedWorkbookError();
        } catch (MoneyCraftException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().toLowerCase(Locale.ROOT).contains("password")) {
                throw encryptedWorkbookError();
            }
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-MALFORMED-WORKBOOK",
                "Invalid Legacy .xls Workbook",
                "Failed to parse .xls workbook: " + ex.getMessage()
            );
        }
    }

    private ParsedWorkbook extractPoiWorkbook(
        Workbook wb,
        String formatProfile,
        String dateSystem,
        boolean macroDetected,
        List<String> diagnostics
    ) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<ParsedSheet> sheets = new ArrayList<>();
        boolean anyFormula = false;

        for (int sIdx = 0; sIdx < wb.getNumberOfSheets(); sIdx++) {
            Sheet sheet = wb.getSheetAt(sIdx);
            String sName = sheet.getSheetName();
            int lastRow = Math.min(sheet.getLastRowNum(), MAX_ROWS);
            List<ParsedRow> parsedRows = new ArrayList<>();

            for (int rIdx = 0; rIdx <= lastRow; rIdx++) {
                Row row = sheet.getRow(rIdx);
                if (row == null) {
                    continue;
                }
                int lastCol = Math.min((int) row.getLastCellNum(), MAX_COLUMNS);
                if (lastCol <= 0) {
                    continue;
                }
                List<ParsedCell> cells = new ArrayList<>(lastCol);
                boolean anyNonBlankInRow = false;
                for (int cIdx = 0; cIdx < lastCol; cIdx++) {
                    Cell cell = row.getCell(cIdx, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    ParsedCell parsedCell = extractPoiCell(cell, rIdx, cIdx, sName, dateSystem, formatter);
                    if (parsedCell.fromFormula()) {
                        anyFormula = true;
                    }
                    if (parsedCell.kind() != CellKind.BLANK) {
                        anyNonBlankInRow = true;
                    }
                    cells.add(parsedCell);
                }
                if (anyNonBlankInRow) {
                    parsedRows.add(new ParsedRow(rIdx + 1, List.copyOf(cells)));
                }
            }
            sheets.add(new ParsedSheet(sName, List.copyOf(parsedRows)));
        }

        return new ParsedWorkbook(
            formatProfile,
            dateSystem,
            macroDetected,
            anyFormula,
            "1.0",
            List.copyOf(sheets),
            List.copyOf(diagnostics)
        );
    }

    private static ParsedCell extractPoiCell(
        Cell cell,
        int zeroBasedRow,
        int zeroBasedCol,
        String sheetName,
        String dateSystem,
        DataFormatter formatter
    ) {
        String cellRef = toColumnLetters(zeroBasedCol) + (zeroBasedRow + 1);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return new ParsedCell(zeroBasedCol + 1, cellRef, CellKind.BLANK, "", null, null, false, null);
        }

        CellType type = cell.getCellType();
        boolean fromFormula = false;
        String formulaText = null;
        if (type == CellType.FORMULA) {
            fromFormula = true;
            formulaText = cell.getCellFormula();
            type = cell.getCachedFormulaResultType();
            if (type == CellType.BLANK || type == CellType._NONE) {
                return new ParsedCell(
                    zeroBasedCol + 1,
                    cellRef,
                    CellKind.FORMULA_ERROR,
                    "#MISSING_CACHE!",
                    null,
                    null,
                    true,
                    formulaText
                );
            }
        }

        return switch (type) {
            case ERROR -> {
                byte errCode = cell.getErrorCellValue();
                String errStr;
                try {
                    errStr = FormulaError.forInt(errCode).getString();
                } catch (RuntimeException ignored) {
                    errStr = "#ERROR!";
                }
                yield new ParsedCell(
                    zeroBasedCol + 1,
                    cellRef,
                    CellKind.FORMULA_ERROR,
                    errStr,
                    null,
                    null,
                    fromFormula,
                    formulaText
                );
            }
            case BOOLEAN -> {
                boolean b = cell.getBooleanCellValue();
                yield new ParsedCell(
                    zeroBasedCol + 1,
                    cellRef,
                    CellKind.BOOLEAN,
                    Boolean.toString(b),
                    null,
                    null,
                    fromFormula,
                    formulaText
                );
            }
            case STRING -> {
                String txt = cell.getRichStringCellValue().getString();
                String trimmedUpper = txt == null ? "" : txt.trim().toUpperCase(Locale.ROOT);
                if (FORMULA_ERROR_TOKENS.contains(trimmedUpper)) {
                    yield new ParsedCell(
                        zeroBasedCol + 1,
                        cellRef,
                        CellKind.FORMULA_ERROR,
                        txt.trim(),
                        null,
                        null,
                        fromFormula,
                        formulaText
                    );
                }
                yield new ParsedCell(
                    zeroBasedCol + 1,
                    cellRef,
                    txt == null || txt.isBlank() ? CellKind.BLANK : CellKind.TEXT,
                    txt == null ? "" : txt,
                    null,
                    null,
                    fromFormula,
                    formulaText
                );
            }
            case NUMERIC -> {
                BigDecimal exactNum = readPoiExactDecimal(cell);
                if (isPoiCellDateStyled(cell)) {
                    ParsedCell temp = new ParsedCell(
                        zeroBasedCol + 1, cellRef, CellKind.NUMERIC, exactNum.toPlainString(), exactNum, null,
                        fromFormula, formulaText
                    );
                    LocalDate ld = excelSerialToLocalDate(exactNum, dateSystem, sheetName, temp);
                    yield new ParsedCell(
                        zeroBasedCol + 1,
                        cellRef,
                        CellKind.DATE,
                        ld.toString(),
                        exactNum,
                        ld,
                        fromFormula,
                        formulaText
                    );
                }
                String formatted = formatter.formatCellValue(cell);
                String plain = exactNum.stripTrailingZeros().scale() < 0
                    ? exactNum.setScale(0).toPlainString()
                    : exactNum.stripTrailingZeros().toPlainString();
                String rawDisplay = (!fromFormula && formatted != null && formatted.startsWith("0")
                    && formatted.chars().allMatch(Character::isDigit))
                    ? formatted
                    : plain;
                yield new ParsedCell(
                    zeroBasedCol + 1,
                    cellRef,
                    CellKind.NUMERIC,
                    rawDisplay,
                    exactNum,
                    null,
                    fromFormula,
                    formulaText
                );
            }
            default -> new ParsedCell(zeroBasedCol + 1, cellRef, CellKind.BLANK, "", null, null, fromFormula, null);
        };
    }

    private static BigDecimal readPoiExactDecimal(Cell cell) {
        if (cell instanceof XSSFCell xssfCell) {
            String rawXmlVal = xssfCell.getRawValue();
            if (rawXmlVal != null && !rawXmlVal.isBlank()) {
                return new BigDecimal(rawXmlVal.trim());
            }
        }
        return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
    }

    private static boolean isPoiCellDateStyled(Cell cell) {
        CellStyle style = cell.getCellStyle();
        if (style == null) {
            return false;
        }
        return DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString());
    }

    // =========================================================================
    // 2. OpenDocument Spreadsheet (.ods 1.2 / 1.3 / 1.4) XXE-Safe Parser
    // =========================================================================

    private ParsedWorkbook parseOdsArchive(ZipInspection zipInfo) {
        if (zipInfo.odsMimeType() != null
            && !zipInfo.odsMimeType().isBlank()
            && !zipInfo.odsMimeType().contains("opendocument.spreadsheet")) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-UNSUPPORTED-FORMAT",
                "Non-Spreadsheet ODF Container Rejected",
                "ODF container mimetype '" + zipInfo.odsMimeType()
                    + "' is not application/vnd.oasis.opendocument.spreadsheet."
            );
        }
        if (zipInfo.odsContentXml() == null || zipInfo.odsContentXml().length == 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-MALFORMED-ODS",
                "Missing content.xml in .ods Archive",
                "OpenDocument .ods archive does not contain content.xml."
            );
        }

        Document doc = parseXmlNoXxe(zipInfo.odsContentXml());
        Element docRoot = doc.getDocumentElement();
        String odfVersion = findAttrByLocalName(docRoot, "version");
        if (odfVersion == null || odfVersion.isBlank()) {
            odfVersion = "1.2";
        }
        if (!Set.of("1.2", "1.3", "1.4").contains(odfVersion.trim())) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-ODS-VERSION-UNSUPPORTED",
                "Unsupported ODF Spreadsheet Version",
                "ODF version '" + odfVersion + "' is outside the qualified 1.2–1.4 profile."
            );
        }

        List<ParsedSheet> sheets = new ArrayList<>();
        boolean[] anyFormula = new boolean[1];
        NodeList tables = doc.getElementsByTagNameNS("*", "table");
        for (int t = 0; t < tables.getLength(); t++) {
            Element tableEl = (Element) tables.item(t);
            String tableName = findAttrByLocalName(tableEl, "name");
            if (tableName == null || tableName.isBlank()) {
                tableName = "Sheet" + (t + 1);
            }
            List<ParsedRow> rows = extractOdsTableRows(tableEl, tableName, anyFormula);
            sheets.add(new ParsedSheet(tableName, rows));
        }

        return new ParsedWorkbook(
            "ODS",
            "ISO_TEXT",
            false,
            anyFormula[0],
            odfVersion.trim(),
            List.copyOf(sheets),
            List.of("Parsed OpenDocument Spreadsheet profile v" + odfVersion.trim() + " with XXE disabled.")
        );
    }

    private static List<ParsedRow> extractOdsTableRows(
        Element tableEl,
        String sheetName,
        boolean[] anyFormula
    ) {
        List<ParsedRow> parsedRows = new ArrayList<>();
        int logicalRowNum = 1;
        NodeList children = tableEl.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element el)) {
                continue;
            }
            String local = localNameOf(el);
            if ("table-header-rows".equals(local) || "table-rows".equals(local)) {
                NodeList nested = el.getChildNodes();
                for (int j = 0; j < nested.getLength(); j++) {
                    if (nested.item(j) instanceof Element rowEl && "table-row".equals(localNameOf(rowEl))) {
                        logicalRowNum = appendOdsRow(rowEl, sheetName, logicalRowNum, parsedRows, anyFormula);
                    }
                }
            } else if ("table-row".equals(local)) {
                logicalRowNum = appendOdsRow(el, sheetName, logicalRowNum, parsedRows, anyFormula);
            }
            if (logicalRowNum > MAX_ROWS) {
                break;
            }
        }
        return List.copyOf(parsedRows);
    }

    private static int appendOdsRow(
        Element rowEl,
        String sheetName,
        int currentRowNo,
        List<ParsedRow> outRows,
        boolean[] anyFormula
    ) {
        int rowRepeat = parsePositiveIntAttr(rowEl, "number-rows-repeated", 1);
        List<OdsRawCell> rawCells = new ArrayList<>();
        NodeList cellNodes = rowEl.getChildNodes();

        for (int c = 0; c < cellNodes.getLength(); c++) {
            if (!(cellNodes.item(c) instanceof Element cellEl)) {
                continue;
            }
            String local = localNameOf(cellEl);
            if (!"table-cell".equals(local) && !"covered-table-cell".equals(local)) {
                continue;
            }
            boolean covered = "covered-table-cell".equals(local);
            int colRepeat = parsePositiveIntAttr(cellEl, "number-columns-repeated", 1);
            OdsRawCell extracted = extractOdsRawCell(cellEl, covered);
            if (extracted.fromFormula) {
                anyFormula[0] = true;
            }
            rawCells.add(new OdsRawCell(
                extracted.kind,
                extracted.rawText,
                extracted.numericValue,
                extracted.dateValue,
                extracted.fromFormula,
                extracted.formulaText,
                colRepeat
            ));
        }

        // Trim trailing blank repeated cells (LibreOffice often repeats 1024 trailing blank cells)
        while (!rawCells.isEmpty() && rawCells.getLast().kind == CellKind.BLANK) {
            rawCells.removeLast();
        }
        if (rawCells.isEmpty()) {
            return currentRowNo + Math.min(rowRepeat, MAX_ROWS);
        }

        int boundedRepeat = Math.min(rowRepeat, 256);
        for (int r = 0; r < boundedRepeat && (currentRowNo + r) <= MAX_ROWS; r++) {
            int rowNo = currentRowNo + r;
            List<ParsedCell> rowCells = new ArrayList<>();
            int colZeroIdx = 0;
            for (OdsRawCell rc : rawCells) {
                int reps = Math.min(rc.repeatCount, MAX_COLUMNS - colZeroIdx);
                for (int k = 0; k < reps; k++) {
                    String cellRef = toColumnLetters(colZeroIdx) + rowNo;
                    if (rc.dateValue != null && "1900-02-29".equals(rc.rawText)) {
                        throw new MoneyCraftException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "MC-SHEET-INVALID-DATE",
                            "Fictitious 1900-02-29 Rejected",
                            "Cell " + sheetName + "!" + cellRef + " contains invalid date 1900-02-29."
                        );
                    }
                    rowCells.add(new ParsedCell(
                        colZeroIdx + 1,
                        cellRef,
                        rc.kind,
                        rc.rawText,
                        rc.numericValue,
                        rc.dateValue,
                        rc.fromFormula,
                        rc.formulaText
                    ));
                    colZeroIdx++;
                }
                if (colZeroIdx >= MAX_COLUMNS) {
                    break;
                }
            }
            outRows.add(new ParsedRow(rowNo, List.copyOf(rowCells)));
        }
        return currentRowNo + boundedRepeat;
    }

    private static OdsRawCell extractOdsRawCell(Element cellEl, boolean covered) {
        if (covered) {
            return new OdsRawCell(CellKind.BLANK, "", null, null, false, null, 1);
        }
        String formula = findAttrByLocalName(cellEl, "formula");
        boolean hasFormula = formula != null && !formula.isBlank();
        String valueType = findAttrByLocalName(cellEl, "value-type");
        String displayText = readOdsTextParagraphs(cellEl);
        String trimmedDisplay = displayText.trim();

        if (hasFormula && (trimmedDisplay.startsWith("Err:")
            || FORMULA_ERROR_TOKENS.contains(trimmedDisplay.toUpperCase(Locale.ROOT)))) {
            return new OdsRawCell(CellKind.FORMULA_ERROR, trimmedDisplay, null, null, true, formula, 1);
        }

        if (valueType == null || valueType.isBlank()) {
            if (trimmedDisplay.isEmpty()) {
                return new OdsRawCell(
                    hasFormula ? CellKind.FORMULA_ERROR : CellKind.BLANK,
                    hasFormula ? "#MISSING_CACHE!" : "",
                    null,
                    null,
                    hasFormula,
                    formula,
                    1
                );
            }
            return new OdsRawCell(CellKind.TEXT, displayText, null, null, hasFormula, formula, 1);
        }

        return switch (valueType.trim().toLowerCase(Locale.ROOT)) {
            case "float", "currency", "percentage" -> {
                String rawVal = findAttrByLocalName(cellEl, "value");
                if (rawVal == null || rawVal.isBlank()) {
                    yield new OdsRawCell(
                        hasFormula ? CellKind.FORMULA_ERROR : CellKind.BLANK,
                        trimmedDisplay,
                        null,
                        null,
                        hasFormula,
                        formula,
                        1
                    );
                }
                BigDecimal bd = new BigDecimal(rawVal.trim());
                String plain = bd.stripTrailingZeros().scale() < 0
                    ? bd.setScale(0).toPlainString()
                    : bd.stripTrailingZeros().toPlainString();
                yield new OdsRawCell(CellKind.NUMERIC, plain, bd, null, hasFormula, formula, 1);
            }
            case "date" -> {
                String rawDate = findAttrByLocalName(cellEl, "date-value");
                if (rawDate == null || rawDate.isBlank()) {
                    yield new OdsRawCell(CellKind.TEXT, displayText, null, null, hasFormula, formula, 1);
                }
                String trimmedDate = rawDate.trim();
                if ("1900-02-29".equals(trimmedDate)) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-SHEET-INVALID-DATE",
                        "Fictitious 1900-02-29 Date Rejected",
                        "ODS cell office:date-value '1900-02-29' is invalid."
                    );
                }
                if (trimmedDate.contains("T")) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-SHEET-DATETIME-TRUNCATION-FORBIDDEN",
                        "ODS Datetime Requires Explicit Timezone Policy",
                        "ODS office:date-value '" + trimmedDate + "' includes time-of-day."
                    );
                }
                LocalDate ld = LocalDate.parse(trimmedDate);
                yield new OdsRawCell(CellKind.DATE, ld.toString(), null, ld, hasFormula, formula, 1);
            }
            case "boolean" -> {
                String bVal = findAttrByLocalName(cellEl, "boolean-value");
                boolean b = Boolean.parseBoolean(bVal == null ? trimmedDisplay : bVal);
                yield new OdsRawCell(CellKind.BOOLEAN, Boolean.toString(b), null, null, hasFormula, formula, 1);
            }
            default -> {
                String strAttr = findAttrByLocalName(cellEl, "string-value");
                String effective = strAttr != null ? strAttr : displayText;
                if (FORMULA_ERROR_TOKENS.contains(effective.trim().toUpperCase(Locale.ROOT))) {
                    yield new OdsRawCell(CellKind.FORMULA_ERROR, effective.trim(), null, null, hasFormula, formula, 1);
                }
                yield new OdsRawCell(
                    effective.isBlank() ? CellKind.BLANK : CellKind.TEXT,
                    effective,
                    null,
                    null,
                    hasFormula,
                    formula,
                    1
                );
            }
        };
    }

    private static String readOdsTextParagraphs(Element cellEl) {
        StringBuilder sb = new StringBuilder();
        NodeList children = cellEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element p && "p".equals(localNameOf(p))) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(p.getTextContent() == null ? "" : p.getTextContent());
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // 3. RFC 4180 CSV State-Machine Parser (BOM, Quotes, Multiline, Leading 0s)
    // =========================================================================

    private ParsedWorkbook parseCsvBytes(String fileName, byte[] rawBytes, char delimiter) {
        String text = decodeStrictUtf8Csv(rawBytes);
        char sep = delimiter == '\0' ? ',' : delimiter;

        List<List<String>> rawRecords = new ArrayList<>();
        List<String> currentRecord = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;

        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        currentField.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    currentField.append(ch);
                    i++;
                }
                continue;
            }

            if (ch == '"' && currentField.isEmpty() && !fieldWasQuoted) {
                inQuotes = true;
                fieldWasQuoted = true;
                i++;
            } else if (ch == sep) {
                currentRecord.add(currentField.toString());
                currentField.setLength(0);
                fieldWasQuoted = false;
                i++;
                if (currentRecord.size() > MAX_COLUMNS) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-SHEET-COLUMN-LIMIT",
                        "CSV Exceeds Column Limit",
                        "CSV record exceeds maximum " + MAX_COLUMNS + " columns."
                    );
                }
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i += 2;
                } else {
                    i++;
                }
                currentRecord.add(currentField.toString());
                currentField.setLength(0);
                fieldWasQuoted = false;
                rawRecords.add(List.copyOf(currentRecord));
                currentRecord.clear();
                if (rawRecords.size() > MAX_ROWS) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-SHEET-ROW-LIMIT",
                        "CSV Exceeds Row Limit",
                        "CSV exceeds maximum " + MAX_ROWS + " rows."
                    );
                }
            } else {
                currentField.append(ch);
                i++;
            }
        }

        if (inQuotes) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-CSV-MALFORMED",
                "Unterminated Quoted CSV Field",
                "RFC 4180 CSV contains an unclosed double-quoted field."
            );
        }
        if (!currentRecord.isEmpty() || !currentField.isEmpty()) {
            currentRecord.add(currentField.toString());
            rawRecords.add(List.copyOf(currentRecord));
        }

        List<ParsedRow> rows = new ArrayList<>();
        for (int rIdx = 0; rIdx < rawRecords.size(); rIdx++) {
            List<String> fields = rawRecords.get(rIdx);
            boolean anyNonBlank = fields.stream().anyMatch(f -> f != null && !f.isBlank());
            if (!anyNonBlank) {
                continue;
            }
            List<ParsedCell> cells = new ArrayList<>(fields.size());
            for (int cIdx = 0; cIdx < fields.size(); cIdx++) {
                String raw = fields.get(cIdx);
                String trimmed = raw.trim();
                CellKind kind = trimmed.isEmpty() ? CellKind.BLANK : CellKind.TEXT;
                if (FORMULA_ERROR_TOKENS.contains(trimmed.toUpperCase(Locale.ROOT))) {
                    kind = CellKind.FORMULA_ERROR;
                }
                cells.add(new ParsedCell(
                    cIdx + 1,
                    toColumnLetters(cIdx) + (rIdx + 1),
                    kind,
                    raw,
                    null,
                    null,
                    false,
                    null
                ));
            }
            rows.add(new ParsedRow(rIdx + 1, List.copyOf(cells)));
        }

        String sheetName = fileName.endsWith(".csv") && fileName.length() > 4
            ? fileName.substring(0, fileName.length() - 4)
            : "CSV";
        return new ParsedWorkbook(
            "CSV",
            "ISO_TEXT",
            false,
            false,
            "RFC4180",
            List.of(new ParsedSheet(sheetName, List.copyOf(rows))),
            List.of("Parsed UTF-8 RFC 4180 CSV with delimiter '" + (sep == '\t' ? "\\t" : sep) + "'.")
        );
    }

    private static String decodeStrictUtf8Csv(byte[] rawBytes) {
        try {
            int offset = (rawBytes.length >= 3
                && (rawBytes[0] & 0xFF) == 0xEF
                && (rawBytes[1] & 0xFF) == 0xBB
                && (rawBytes[2] & 0xFF) == 0xBF) ? 3 : 0;
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes, offset, rawBytes.length - offset));
            return decoded.toString();
        } catch (CharacterCodingException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-CSV-ENCODING",
                "Invalid UTF-8 CSV Encoding",
                "CSV bytes are not valid UTF-8."
            );
        }
    }

    // =========================================================================
    // Low-Level ZIP / XML / Magic Inspection Helpers
    // =========================================================================

    private static boolean isZipMagic(byte[] b) {
        return b.length >= 4 && b[0] == 0x50 && b[1] == 0x4B && b[2] == 0x03 && b[3] == 0x04;
    }

    private static boolean isOle2Magic(byte[] b) {
        return b.length >= 8
            && (b[0] & 0xFF) == 0xD0
            && (b[1] & 0xFF) == 0xCF
            && (b[2] & 0xFF) == 0x11
            && (b[3] & 0xFF) == 0xE0
            && (b[4] & 0xFF) == 0xA1
            && (b[5] & 0xFF) == 0xB1
            && (b[6] & 0xFF) == 0x1A
            && (b[7] & 0xFF) == 0xE1;
    }

    private static ZipInspection inspectZipArchive(byte[] rawBytes) {
        int entries = 0;
        long totalExpanded = 0L;
        boolean hasVba = false;
        boolean hasXlsbBin = false;
        boolean hasContentXml = false;
        String odsMime = null;
        byte[] contentXmlBytes = null;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(rawBytes))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-SHEET-ZIP-BOMB",
                        "Archive Entry Count Limit Exceeded",
                        "Spreadsheet archive exceeds maximum " + MAX_ZIP_ENTRIES + " ZIP entries."
                    );
                }
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("..") || lower.startsWith("/")) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-SHEET-ZIP-TRAVERSAL",
                        "Archive Path Traversal Rejected",
                        "Spreadsheet archive contains unsafe entry path '" + name + "'."
                    );
                }
                if (lower.endsWith("vbaproject.bin")) {
                    hasVba = true;
                }
                if ("xl/workbook.bin".equals(lower)) {
                    hasXlsbBin = true;
                }

                boolean captureBytes = "mimetype".equals(name) || "content.xml".equals(name);
                ByteArrayOutputStream captured = captureBytes ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = zis.read(buf)) != -1) {
                    totalExpanded += read;
                    if (totalExpanded > MAX_UNCOMPRESSED_BYTES) {
                        throw new MoneyCraftException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "MC-SHEET-ZIP-BOMB",
                            "Archive Expansion Limit Exceeded",
                            "Uncompressed archive size exceeds maximum " + MAX_UNCOMPRESSED_BYTES + " bytes."
                        );
                    }
                    if (captured != null) {
                        captured.write(buf, 0, read);
                    }
                }
                if ("mimetype".equals(name) && captured != null) {
                    odsMime = captured.toString(StandardCharsets.UTF_8).trim();
                } else if ("content.xml".equals(name) && captured != null) {
                    hasContentXml = true;
                    contentXmlBytes = captured.toByteArray();
                }
            }
        } catch (MoneyCraftException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-MALFORMED-ARCHIVE",
                "Corrupted ZIP Spreadsheet Container",
                "Failed to read spreadsheet archive: " + ex.getMessage()
            );
        }

        boolean isOds = hasContentXml
            || (odsMime != null && odsMime.contains("oasis.opendocument"));
        return new ZipInspection(hasVba, hasXlsbBin, isOds, odsMime, contentXmlBytes);
    }

    private static Document parseXmlNoXxe(byte[] xmlBytes) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xmlBytes));
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SHEET-XML-UNSAFE-OR-INVALID",
                "Rejected Spreadsheet XML (XXE / Malformed)",
                "Spreadsheet XML failed strict XXE-hardened parsing: " + ex.getMessage()
            );
        }
    }

    private static String localNameOf(Element el) {
        if (el.getLocalName() != null) {
            return el.getLocalName();
        }
        String tag = el.getTagName();
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    private static String findAttrByLocalName(Element el, String localName) {
        if (el.getAttributes() == null) {
            return null;
        }
        for (int i = 0; i < el.getAttributes().getLength(); i++) {
            Node attr = el.getAttributes().item(i);
            String name = attr.getLocalName();
            if (name == null) {
                String raw = attr.getNodeName();
                int colon = raw.indexOf(':');
                name = colon >= 0 ? raw.substring(colon + 1) : raw;
            }
            if (localName.equals(name)) {
                return attr.getNodeValue();
            }
        }
        return null;
    }

    private static int parsePositiveIntAttr(Element el, String attrLocalName, int fallback) {
        String raw = findAttrByLocalName(el, attrLocalName);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int val = Integer.parseInt(raw.trim());
            return val > 0 ? val : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static MoneyCraftException encryptedWorkbookError() {
        return new MoneyCraftException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MC-SHEET-ENCRYPTED-WORKBOOK",
            "Password-Encrypted Workbook Rejected",
            "Password-encrypted spreadsheets are rejected; supply an authorised decrypted export."
        );
    }

    private record ZipInspection(
        boolean hasVbaProject,
        boolean hasXlsbBinaryPart,
        boolean isOdsArchive,
        String odsMimeType,
        byte[] odsContentXml
    ) {
    }

    private record OdsRawCell(
        CellKind kind,
        String rawText,
        BigDecimal numericValue,
        LocalDate dateValue,
        boolean fromFormula,
        String formulaText,
        int repeatCount
    ) {
    }

    public enum CellKind {
        BLANK,
        TEXT,
        NUMERIC,
        DATE,
        BOOLEAN,
        FORMULA_ERROR
    }

    public record ParsedCell(
        int columnIndex,
        String cellRef,
        CellKind kind,
        String rawText,
        BigDecimal numericValue,
        LocalDate dateValue,
        boolean fromFormula,
        String formulaText
    ) {
    }

    public record ParsedRow(
        int rowNumber,
        List<ParsedCell> cells
    ) {
        public ParsedCell cellAtOneBased(int oneBasedCol) {
            if (oneBasedCol < 1 || oneBasedCol > cells.size()) {
                return new ParsedCell(
                    oneBasedCol,
                    toColumnLetters(Math.max(0, oneBasedCol - 1)) + rowNumber,
                    CellKind.BLANK,
                    "",
                    null,
                    null,
                    false,
                    null
                );
            }
            return cells.get(oneBasedCol - 1);
        }
    }

    public record ParsedSheet(
        String name,
        List<ParsedRow> rows
    ) {
    }

    public record ParsedWorkbook(
        String formatProfile,
        String dateSystem,
        boolean macroDetected,
        boolean formulaCellsDetected,
        String profileVersion,
        List<ParsedSheet> sheets,
        List<String> diagnostics
    ) {
    }
}
