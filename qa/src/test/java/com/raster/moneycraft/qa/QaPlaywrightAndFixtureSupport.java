package com.raster.moneycraft.qa;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Shared Playwright headless Chromium launcher, W3C WCAG 2.2 computed-CSS contrast verifier,
 * and multi-format spreadsheet (.xlsx, .ods, CSV) binary fixture builders for MoneyCraft 1.0.0 QA.
 */
public final class QaPlaywrightAndFixtureSupport {

    private static final Pattern RGB_PATTERN = Pattern.compile(
        "rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*[,/]\\s*([0-9.]+))?\\s*\\)"
    );

    private QaPlaywrightAndFixtureSupport() {
    }

    /**
     * Launches headless Chromium offline using cached Playwright browsers or local macOS/Linux Chromium.
     */
    public static Browser launchHeadlessChromium(Playwright playwright) {
        List<String> args = List.of("--disable-background-networking", "--no-sandbox");
        try {
            return playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(args)
            );
        } catch (RuntimeException primaryFailure) {
            Path discovered = discoverCachedChromiumExecutable();
            if (discovered != null) {
                return playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setExecutablePath(discovered)
                        .setArgs(args)
                );
            }
            return playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setChannel("chrome")
                    .setArgs(args)
            );
        }
    }

    /**
     * Computes exact W3C WCAG 2.2 contrast ratio `(L_lighter + 0.05) / (L_darker + 0.05)`
     * from actual browser `getComputedStyle` `rgb(r, g, b)` strings.
     */
    public static BigDecimal computeWcagContrastRatio(String cssForegroundRgb, String cssBackgroundRgb) {
        int[] fg = parseCssRgb(cssForegroundRgb);
        int[] bg = parseCssRgb(cssBackgroundRgb);
        double lumFg = relativeLuminance(fg[0], fg[1], fg[2]);
        double lumBg = relativeLuminance(bg[0], bg[1], bg[2]);
        double lighter = Math.max(lumFg, lumBg);
        double darker = Math.min(lumFg, lumBg);
        double ratio = (lighter + 0.05d) / (darker + 0.05d);
        return BigDecimal.valueOf(ratio).round(new MathContext(6, RoundingMode.HALF_UP));
    }

    public static String cssRgbToUpperHex(String cssRgb) {
        int[] rgb = parseCssRgb(cssRgb);
        return String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
    }

    public static int[] parseCssRgb(String cssColor) {
        if (cssColor == null) {
            throw new IllegalArgumentException("CSS color string must not be null");
        }
        Matcher matcher = RGB_PATTERN.matcher(cssColor.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported computed CSS color format: " + cssColor);
        }
        return new int[] {
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))
        };
    }

    /**
     * Builds a multi-ledger horizontal columnar `.xlsx` workbook in memory using Apache POI
     * (zero macros, exact string/text cells preserving leading zeros and decimal precision).
     */
    public static byte[] buildColumnarXlsxWorkbook(String sheetName, List<List<String>> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                List<String> cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    String value = cells.get(c);
                    row.createCell(c).setCellValue(value == null ? "" : value);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Builds a synthetic `.xlsx` workbook containing Excel's fictitious 1900 leap-year serial `60`
     * (`1900-02-29`) in cell `A2` (`s="1"` date number format `14`) to verify fail-closed rejection (`SHEET-004`).
     */
    public static byte[] buildInvalidLeapDay1900XlsxWorkbook() throws IOException {
        byte[] base = buildColumnarXlsxWorkbook(
            "Vouchers",
            List.of(
                List.of("VoucherDate", "VoucherType", "RefNo", "PartyLedger", "SalesLedger", "TotalAmount"),
                List.of("PLACEHOLDER_DATE", "Sales", "LEAP-001", "Salem Care Hospital", "Sales - GST", "1000.00")
            )
        );
        ByteArrayOutputStream mutated = new ByteArrayOutputStream();
        try (
            ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(base));
            ZipOutputStream zos = new ZipOutputStream(mutated)
        ) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] bytes = zis.readAllBytes();
                String name = entry.getName();
                zos.putNextEntry(new ZipEntry(name));
                if ("xl/worksheets/sheet1.xml".equals(name)) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    // Replace A2 cell with numeric date-formatted Excel serial 60 (fictitious 1900-02-29)
                    xml = xml.replaceFirst(
                        "<c r=\"A2\"[^>]*>.*?</c>",
                        "<c r=\"A2\" s=\"1\"><v>60</v></c>"
                    );
                    zos.write(xml.getBytes(StandardCharsets.UTF_8));
                } else if ("xl/styles.xml".equals(name)) {
                    String styles = new String(bytes, StandardCharsets.UTF_8);
                    styles = styles.replace(
                        "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>",
                        "<cellXfs count=\"2\">"
                            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                            + "<xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                            + "</cellXfs>"
                    );
                    zos.write(styles.getBytes(StandardCharsets.UTF_8));
                } else {
                    zos.write(bytes);
                }
                zos.closeEntry();
            }
        }
        return mutated.toByteArray();
    }

    /**
     * Builds a valid OpenDocument Spreadsheet 1.3 (`.ods`) archive including `mimetype`,
     * `META-INF/manifest.xml`, and `content.xml` exercising `table:number-columns-repeated`.
     */
    public static byte[] buildColumnarOdsWorkbook(String sheetName, List<List<String>> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("META-INF/manifest.xml"));
            String manifest = """
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3">
                  <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>
                  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                </manifest:manifest>
                """.trim();
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            StringBuilder sb = new StringBuilder(4096);
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            sb.append("<office:document-content ")
                .append("xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" ")
                .append("xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" ")
                .append("xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" ")
                .append("office:version=\"1.3\">");
            sb.append("<office:body><office:spreadsheet>");
            sb.append("<table:table table:name=\"").append(escapeXml(sheetName)).append("\">");
            for (List<String> row : rows) {
                sb.append("<table:table-row>");
                int col = 0;
                while (col < row.size()) {
                    String val = row.get(col) == null ? "" : row.get(col);
                    int repeat = 1;
                    while (col + repeat < row.size() && val.equals(row.get(col + repeat) == null ? "" : row.get(col + repeat))) {
                        repeat++;
                    }
                    sb.append("<table:table-cell office:value-type=\"string\"");
                    if (repeat > 1) {
                        sb.append(" table:number-columns-repeated=\"").append(repeat).append("\"");
                    }
                    sb.append("><text:p>").append(escapeXml(val)).append("</text:p></table:table-cell>");
                    col += repeat;
                }
                sb.append("</table:table-row>");
            }
            sb.append("</table:table></office:spreadsheet></office:body></office:document-content>");

            zos.putNextEntry(new ZipEntry("content.xml"));
            zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    private static double relativeLuminance(int r8, int g8, int b8) {
        double r = linearizeChannel(r8 / 255.0d);
        double g = linearizeChannel(g8 / 255.0d);
        double b = linearizeChannel(b8 / 255.0d);
        return 0.2126d * r + 0.7152d * g + 0.0722d * b;
    }

    private static double linearizeChannel(double c) {
        return c <= 0.04045d ? c / 12.92d : Math.pow((c + 0.055d) / 1.055d, 2.4d);
    }

    private static Path discoverCachedChromiumExecutable() {
        String home = System.getProperty("user.home", "");
        List<Path> cacheRoots = List.of(
            Path.of(home, "Library", "Caches", "ms-playwright"),
            Path.of(home, ".cache", "ms-playwright")
        );
        for (Path root : cacheRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                List<Path> chromiumDirs = children
                    .filter(p -> p.getFileName().toString().startsWith("chromium-")
                        || p.getFileName().toString().startsWith("chromium_headless_shell-"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
                for (Path dir : chromiumDirs) {
                    try (Stream<Path> walk = Files.walk(dir, 6)) {
                        Path bin = walk
                            .filter(Files::isExecutable)
                            .filter(Files::isRegularFile)
                            .filter(p -> {
                                String n = p.getFileName().toString();
                                return "Chromium".equals(n)
                                    || "Google Chrome for Testing".equals(n)
                                    || "chrome-headless-shell".equals(n)
                                    || "headless_shell".equals(n)
                                    || "chrome".equals(n);
                            })
                            .findFirst()
                            .orElse(null);
                        if (bin != null) {
                            return bin;
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private static String escapeXml(String raw) {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
