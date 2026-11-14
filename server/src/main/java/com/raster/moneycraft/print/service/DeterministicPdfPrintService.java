package com.raster.moneycraft.print.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.raster.moneycraft.accounting.dto.CursorPage;
import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.accounting.service.VoucherService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.inventory.dto.SupplyChainReportDtos;
import com.raster.moneycraft.inventory.service.ValuationService;
import com.raster.moneycraft.print.dto.PrintDtos;
import com.raster.moneycraft.reporting.dto.ReportingDtos;
import com.raster.moneycraft.reporting.service.FinancialStatementService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deterministic air-gapped server-side PDF renderer enforcing Brand Identity v0.2.0:
 * - Embedded TrueType subsets of Manrope (prose + ₹ U+20B9 / − U+2212 tabular amounts)
 *   and JetBrains Mono (voucher numbers, GSTINs, HSNs, IRNs, SHA-256 digests).
 * - Strictly forbids U+20B9 inside JetBrains Mono and scrubs programming ligature codepoints.
 * - Supports Apache PDFBox 3 native vector rendering (default) and optional offline Playwright
 *   Chromium HTML-to-PDF rendering when {@code moneycraft.print.engine=playwright}.
 */
@Service
public class DeterministicPdfPrintService {

    private static final String MANROPE_CLASSPATH = "fonts/Manrope-Regular.ttf";
    private static final String JETBRAINS_MONO_CLASSPATH = "fonts/JetBrainsMono-Regular.ttf";

    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int LEFT_MARGIN = 36;
    private static final int RIGHT_MARGIN = 559;
    private static final int TOP_START_Y = 804;
    private static final int BOTTOM_LIMIT_Y = 66;

    private static final Set<String> SUPPORTED_REPORTS = Set.of(
        "trial-balance",
        "profit-and-loss",
        "balance-sheet",
        "schedule-iii",
        "day-book",
        "stock-summary",
        "ageing"
    );

    private final RbacAuthorizationService rbacGuard;
    private final VoucherService voucherService;
    private final FinancialStatementService financialStatementService;
    private final ValuationService valuationService;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;
    private final String printEngineMode;
    private final byte[] manropeFontBytes;
    private final byte[] jetBrainsMonoFontBytes;

    public DeterministicPdfPrintService(
        RbacAuthorizationService rbacGuard,
        VoucherService voucherService,
        FinancialStatementService financialStatementService,
        ValuationService valuationService,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager,
        @Value("${moneycraft.print.engine:pdfbox}") String printEngineMode
    ) {
        this.rbacGuard = rbacGuard;
        this.voucherService = voucherService;
        this.financialStatementService = financialStatementService;
        this.valuationService = valuationService;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
        this.printEngineMode = printEngineMode == null ? "pdfbox" : printEngineMode.trim().toLowerCase(Locale.ROOT);
        this.manropeFontBytes = readClasspathBytes(MANROPE_CLASSPATH);
        this.jetBrainsMonoFontBytes = readClasspathBytes(JETBRAINS_MONO_CLASSPATH);
    }

    public byte[] printVoucherPdf(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID voucherId,
        PrintDtos.VoucherPrintRequest request
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        VoucherDtos.VoucherDetailDto voucher = voucherService.getVoucher(principal, companyId, voucherId);
        CompanyPrintHeader header = loadCompanyPrintHeader(companyId, voucher.companyGstinId(), voucher.partyLedgerId());
        VoucherStatutoryPrintMeta statMeta = loadVoucherStatutoryMeta(companyId, voucherId);

        boolean showNarration = request == null || request.showNarration() == null || request.showNarration();
        boolean showBills = request == null || request.showBillAllocations() == null || request.showBillAllocations();
        boolean showInv = request == null || request.showInventoryDetails() == null || request.showInventoryDetails();
        String copiesLabel = request != null && request.copiesLabel() != null && !request.copiesLabel().isBlank()
            ? sanitizeProse(request.copiesLabel())
            : "ORIGINAL FOR RECIPIENT";
        String docTitle = resolveVoucherPrintTitle(voucher, request != null ? request.title() : null);

        List<PrintSectionRow> rows = new ArrayList<>();
        rows.add(PrintSectionRow.kv(
            "Party / Particulars",
            voucher.partyLedgerName() != null ? voucher.partyLedgerName() : firstLineLedgerName(voucher),
            voucher.partyGstin() != null && !voucher.partyGstin().isBlank()
                ? voucher.partyGstin()
                : header.fallbackPartyGstin()
        ));

        if (showInv && voucher.inventoryLines() != null && !voucher.inventoryLines().isEmpty()) {
            Map<UUID, String> itemHsns = loadItemHsnMap(companyId, voucher.inventoryLines());
            rows.add(PrintSectionRow.sectionHeader("ITEM / INVENTORY DETAILS", "HSN/SAC", "QTY & RATE", "AMOUNT"));
            for (VoucherDtos.VoucherInventoryLineDto inv : voucher.inventoryLines()) {
                String hsn = itemHsns.getOrDefault(inv.stockItemId(), "-");
                String qtyRate = inv.billedQty()
                    + (inv.uomSymbol() != null && !inv.uomSymbol().isBlank() ? " " + inv.uomSymbol() : "")
                    + " @ \u20B9 " + formatSignedDisplay(inv.rate());
                rows.add(PrintSectionRow.itemLine(
                    inv.stockItemName() != null ? inv.stockItemName() : "Item",
                    hsn,
                    qtyRate,
                    "\u20B9 " + formatSignedDisplay(inv.amount())
                ));
            }
        }

        rows.add(PrintSectionRow.sectionHeader("ACCOUNTING LEDGER ALLOCATIONS", "SIDE", "BILL REF", "AMOUNT"));
        for (VoucherDtos.VoucherLineDetailDto line : voucher.lines()) {
            String billCode = "-";
            if (showBills && line.billAllocations() != null && !line.billAllocations().isEmpty()) {
                VoucherDtos.BillAllocationDto firstBill = line.billAllocations().getFirst();
                billCode = firstBill.billName() + " (" + firstBill.allocationType() + ")";
            }
            rows.add(PrintSectionRow.itemLine(
                line.ledgerName() != null ? line.ledgerName() : "Ledger",
                line.side(),
                billCode,
                "\u20B9 " + formatSignedDisplay(line.amount())
            ));
        }

        rows.add(PrintSectionRow.totalLine(
            "Total Voucher Amount (Debit = Credit)",
            "DR=CR",
            "\u20B9 " + formatSignedDisplay(voucher.totalDebit())
        ));

        if (showNarration && voucher.narration() != null && !voucher.narration().isBlank()) {
            rows.add(PrintSectionRow.proseNote("Narration: " + voucher.narration()));
        }
        if (statMeta.irn() != null && !statMeta.irn().isBlank()) {
            rows.add(PrintSectionRow.codeMeta("e-Invoice IRN", statMeta.irn()));
        }
        if (statMeta.ewbNo() != null && !statMeta.ewbNo().isBlank()) {
            rows.add(PrintSectionRow.codeMeta("e-Way Bill No", statMeta.ewbNo()));
        }

        String docCode = (voucher.voucherTypeName() != null ? voucher.voucherTypeName() : "VOUCHER")
            + " #" + (voucher.voucherNumber() != null ? voucher.voucherNumber() : "DRAFT");
        String periodOrDate = voucher.voucherDate() != null ? voucher.voucherDate().toString() : "";
        String digest = computeVerificationDigest(
            companyId + "|" + voucher.id() + "|" + docCode + "|" + voucher.totalDebit() + "|" + voucher.versionNo()
        );

        PrintDocumentModel model = new PrintDocumentModel(
            header.companyName(),
            header.companyCode(),
            header.companyGstin(),
            header.stateName(),
            docTitle + " (" + copiesLabel + ")",
            docCode,
            periodOrDate,
            rows,
            digest
        );

        return renderDocument(model);
    }

    public byte[] printReportPdf(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String rawReportType,
        PrintDtos.ReportPrintRequest request
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        String reportType = rawReportType == null ? "" : rawReportType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_REPORTS.contains(reportType)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PDF-UNSUPPORTED-REPORT",
                "Unsupported Report Type",
                "Supported PDF report types: " + SUPPORTED_REPORTS
            );
        }

        CompanyPrintHeader header = loadCompanyPrintHeader(companyId, null, null);
        PrintDtos.ReportPrintRequest req = request != null
            ? request
            : new PrintDtos.ReportPrintRequest(null, null, null, null, null, null, null, null, false, null, null);
        boolean includeOpt = Boolean.TRUE.equals(req.includeOptional());

        List<PrintSectionRow> rows = new ArrayList<>();
        String title;
        String codeLabel;
        String dateBanner;
        String summarySeed;

        switch (reportType) {
            case "trial-balance" -> {
                ReportingDtos.TrialBalanceReportDto tb = financialStatementService.getTrialBalance(
                    principal, companyId, req.fromDate(), req.toDate(), includeOpt
                );
                title = "TRIAL BALANCE";
                codeLabel = "RPT-TB";
                dateBanner = tb.fromDate() + " to " + tb.toDate();
                rows.add(PrintSectionRow.sectionHeader("LEDGER / GROUP", "CODE", "CLOSING DR", "CLOSING CR"));
                for (ReportingDtos.TrialBalanceRowDto lr : tb.ledgerRows()) {
                    rows.add(PrintSectionRow.itemLine(
                        lr.name(),
                        lr.code() != null && !lr.code().isBlank() ? lr.code() : lr.rowType(),
                        "\u20B9 " + formatSignedDisplay(lr.closingDebit()),
                        "\u20B9 " + formatSignedDisplay(lr.closingCredit())
                    ));
                }
                rows.add(PrintSectionRow.totalLine(
                    "Total Closing Trial Balance",
                    "DR \u20B9 " + formatSignedDisplay(tb.totalClosingDebit()),
                    "CR \u20B9 " + formatSignedDisplay(tb.totalClosingCredit())
                ));
                summarySeed = tb.totalClosingDebit() + "|" + tb.totalClosingCredit();
            }
            case "profit-and-loss" -> {
                ReportingDtos.ProfitAndLossReportDto pnl = financialStatementService.getProfitAndLoss(
                    principal, companyId, req.fromDate(), req.toDate(), includeOpt
                );
                title = "PROFIT & LOSS ACCOUNT";
                codeLabel = "RPT-PNL";
                dateBanner = pnl.fromDate() + " to " + pnl.toDate();
                rows.add(PrintSectionRow.sectionHeader("TRADING & P&L PARTICULARS", "LINE CODE", "SIDE", "AMOUNT"));
                for (ReportingDtos.FinancialStatementLineDto l : pnl.tradingCreditLines()) {
                    rows.add(PrintSectionRow.itemLine(
                        l.name(),
                        l.code() != null ? l.code() : "TRD-CR",
                        "CREDIT",
                        "\u20B9 " + formatSignedDisplay(l.amount())
                    ));
                }
                for (ReportingDtos.FinancialStatementLineDto l : pnl.tradingDebitLines()) {
                    rows.add(PrintSectionRow.itemLine(
                        l.name(),
                        l.code() != null ? l.code() : "TRD-DR",
                        "DEBIT",
                        "\u20B9 " + formatSignedDisplay(l.amount())
                    ));
                }
                for (ReportingDtos.FinancialStatementLineDto l : pnl.pnlDebitLines()) {
                    rows.add(PrintSectionRow.itemLine(
                        l.name(),
                        l.code() != null ? l.code() : "PNL-DR",
                        "DEBIT",
                        "\u20B9 " + formatSignedDisplay(l.amount())
                    ));
                }
                for (ReportingDtos.FinancialStatementLineDto l : pnl.pnlCreditLines()) {
                    rows.add(PrintSectionRow.itemLine(
                        l.name(),
                        l.code() != null ? l.code() : "PNL-CR",
                        "CREDIT",
                        "\u20B9 " + formatSignedDisplay(l.amount())
                    ));
                }
                rows.add(PrintSectionRow.totalLine(
                    "Gross Profit: \u20B9 " + formatSignedDisplay(pnl.grossProfit()) + " | Net Profit",
                    "NET",
                    "\u20B9 " + formatSignedDisplay(pnl.signedNetProfit())
                ));
                summarySeed = pnl.grossProfit() + "|" + pnl.signedNetProfit();
            }
            case "balance-sheet" -> {
                LocalDate bsDate = req.asOfDate() != null ? req.asOfDate() : req.toDate();
                ReportingDtos.BalanceSheetReportDto bs = financialStatementService.getBalanceSheet(
                    principal, companyId, bsDate, req.format(), includeOpt
                );
                title = "BALANCE SHEET";
                codeLabel = "RPT-BS-" + bs.format();
                dateBanner = "As at " + bs.asOfDate();
                rows.add(PrintSectionRow.sectionHeader("LIABILITIES & EQUITY", "SCH-III", "SIDE", "AMOUNT"));
                for (ReportingDtos.FinancialStatementLineDto l : bs.liabilityLines()) {
                    rows.add(PrintSectionRow.itemLine(
                        l.name(),
                        l.code() != null ? l.code() : "LIAB",
                        "LIABILITIES",
                        "\u20B9 " + formatSignedDisplay(l.amount())
                    ));
                    appendStatementChildren(rows, l.children(), "LIAB");
                }
                rows.add(PrintSectionRow.sectionHeader("ASSETS", "SCH-III", "SIDE", "AMOUNT"));
                for (ReportingDtos.FinancialStatementLineDto a : bs.assetLines()) {
                    rows.add(PrintSectionRow.itemLine(
                        a.name(),
                        a.code() != null ? a.code() : "ASSET",
                        "ASSETS",
                        "\u20B9 " + formatSignedDisplay(a.amount())
                    ));
                    appendStatementChildren(rows, a.children(), "ASSET");
                }
                rows.add(PrintSectionRow.totalLine(
                    "Total Liabilities: \u20B9 " + formatSignedDisplay(bs.totalLiabilities()) + " | Total Assets",
                    "BALANCED=" + bs.isBalanced(),
                    "\u20B9 " + formatSignedDisplay(bs.totalAssets())
                ));
                summarySeed = bs.totalLiabilities() + "|" + bs.totalAssets();
            }
            case "schedule-iii" -> {
                LocalDate sDate = req.asOfDate() != null ? req.asOfDate() : req.toDate();
                ReportingDtos.ScheduleIiiReportDto s3 = financialStatementService.getScheduleIii(
                    principal,
                    companyId,
                    sDate,
                    req.comparativeAsOfDate(),
                    req.entityFormat(),
                    req.roundingScale()
                );
                title = "MCA SCHEDULE III BALANCE SHEET & STATEMENT OF P&L (" + s3.entityFormat() + ")";
                codeLabel = "SCH3-" + s3.roundingScale();
                dateBanner = "As at " + s3.asOfDate();
                rows.add(PrintSectionRow.sectionHeader("PARTICULARS", "NOTE / CODE", "COMPARATIVE", "CURRENT"));
                for (ReportingDtos.ScheduleIiiLineDto l : s3.balanceSheetLines()) {
                    rows.add(PrintSectionRow.itemLine(
                        l.particulars(),
                        l.noteNo() != null ? "N-" + l.noteNo() + " " + l.lineCode() : l.lineCode(),
                        "\u20B9 " + formatSignedDisplay(l.comparativeAmount()),
                        "\u20B9 " + formatSignedDisplay(l.currentAmount())
                    ));
                }
                rows.add(PrintSectionRow.totalLine(
                    "Total Equity & Liabilities / Total Assets",
                    "SCH-III",
                    "\u20B9 " + formatSignedDisplay(s3.totalAssets())
                ));
                summarySeed = s3.totalEquityAndLiabilities() + "|" + s3.totalAssets();
            }
            case "day-book" -> {
                CursorPage<VoucherDtos.VoucherSummaryDto> page = voucherService.listVouchers(
                    principal,
                    companyId,
                    req.fromDate(),
                    req.toDate(),
                    null,
                    null,
                    null,
                    null,
                    includeOpt,
                    true,
                    200,
                    null
                );
                title = "DAY BOOK REGISTER";
                codeLabel = "RPT-DAYBOOK";
                dateBanner = (req.fromDate() != null ? req.fromDate().toString() : "Start")
                    + " to " + (req.toDate() != null ? req.toDate().toString() : "Current");
                rows.add(PrintSectionRow.sectionHeader("PARTICULARS & TYPE", "VCH NO / DATE", "STATUS", "DEBIT AMOUNT"));
                for (VoucherDtos.VoucherSummaryDto v : page.items()) {
                    rows.add(PrintSectionRow.itemLine(
                        (v.primaryParticulars() != null ? v.primaryParticulars() : v.voucherTypeName())
                            + " [" + v.voucherTypeName() + "]",
                        (v.voucherNumber() != null ? v.voucherNumber() : "-") + " " + v.voucherDate(),
                        v.status(),
                        "\u20B9 " + formatSignedDisplay(v.totalDebit())
                    ));
                }
                rows.add(PrintSectionRow.totalLine(
                    "Total Vouchers Listed",
                    "COUNT=" + page.items().size(),
                    "\u20B9 " + (page.items().isEmpty() ? "0.00" : formatSignedDisplay(page.items().getFirst().totalDebit()))
                ));
                summarySeed = "DAYBOOK|" + page.items().size();
            }
            case "stock-summary" -> {
                SupplyChainReportDtos.StockSummaryReportDto ss = valuationService.getStockSummary(
                    principal,
                    companyId,
                    req.fromDate(),
                    req.toDate(),
                    req.stockGroupId(),
                    req.valuationMethodOverride()
                );
                title = "STOCK SUMMARY STATEMENT";
                codeLabel = "RPT-STOCK";
                dateBanner = ss.fromDate() + " to " + ss.toDate();
                rows.add(PrintSectionRow.sectionHeader("STOCK ITEM", "HSN / METHOD", "CLOSING QTY", "CLOSING VALUE"));
                for (SupplyChainReportDtos.StockSummaryItemDto item : ss.items()) {
                    rows.add(PrintSectionRow.itemLine(
                        item.stockItemName(),
                        (item.hsnSacCode() != null ? item.hsnSacCode() : "-") + " / " + item.costingMethod(),
                        item.closingQty() + " " + item.uomSymbol(),
                        "\u20B9 " + formatSignedDisplay(item.closingValue())
                    ));
                }
                rows.add(PrintSectionRow.totalLine(
                    "Total Closing Inventory Value",
                    "QTY=" + ss.totalClosingQty(),
                    "\u20B9 " + formatSignedDisplay(ss.totalClosingValue())
                ));
                summarySeed = ss.totalClosingValue();
            }
            default -> {
                LocalDate aDate = req.asOfDate() != null ? req.asOfDate() : req.toDate();
                ReportingDtos.AgeingReportDto ag = financialStatementService.getAgeing(
                    principal, companyId, req.type(), aDate
                );
                title = "BILLS OUTSTANDING AGEING (" + ag.type() + ")";
                codeLabel = "RPT-AGE-" + ag.type();
                dateBanner = "As at " + ag.asOfDate();
                rows.add(PrintSectionRow.sectionHeader("PARTY LEDGER", "BILL REF / DUE", "AGE BUCKET", "PENDING"));
                for (ReportingDtos.AgeingBillRowDto b : ag.bills()) {
                    rows.add(PrintSectionRow.itemLine(
                        b.partyName(),
                        b.billName() + " (" + b.dueDate() + ")",
                        b.bucket() + " (" + b.ageDays() + "d)",
                        "\u20B9 " + formatSignedDisplay(b.pendingAmount())
                    ));
                }
                rows.add(PrintSectionRow.totalLine(
                    "Total Pending " + ag.type(),
                    "BILLS=" + ag.bills().size(),
                    "\u20B9 " + formatSignedDisplay(ag.totalPendingAmount())
                ));
                summarySeed = ag.type() + "|" + ag.totalPendingAmount();
            }
        }

        String digest = computeVerificationDigest(companyId + "|" + reportType + "|" + dateBanner + "|" + summarySeed);
        PrintDocumentModel model = new PrintDocumentModel(
            header.companyName(),
            header.companyCode(),
            header.companyGstin(),
            header.stateName(),
            title,
            codeLabel,
            dateBanner,
            rows,
            digest
        );
        return renderDocument(model);
    }

    private byte[] renderDocument(PrintDocumentModel model) {
        if ("playwright".equals(printEngineMode)) {
            return renderWithPlaywrightChromium(model);
        }
        return renderWithPdfBoxVectorEngine(model);
    }

    private byte[] renderWithPdfBoxVectorEngine(PrintDocumentModel model) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(sanitizeProse(model.documentTitle()));
            info.setAuthor("MoneyCraft 1.0.0 Print Engine");
            info.setCreator("MoneyCraft 1.0.0 (Brand Identity v0.2.0)");
            info.setProducer("Apache PDFBox 3.0.8 TrueType Subset Renderer");

            PDType0Font manrope;
            PDType0Font mono;
            try (
                InputStream mIn = new ByteArrayInputStream(manropeFontBytes);
                InputStream jIn = new ByteArrayInputStream(jetBrainsMonoFontBytes)
            ) {
                manrope = PDType0Font.load(doc, mIn, true);
                mono = PDType0Font.load(doc, jIn, true);
            }

            List<List<PrintSectionRow>> pages = paginateRows(model.rows(), 28);
            if (pages.isEmpty()) {
                pages.add(List.of());
            }

            int totalPages = pages.size();
            for (int pageIdx = 0; pageIdx < totalPages; pageIdx++) {
                PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    int y = TOP_START_Y;

                    // Top brand header
                    drawText(cs, manrope, 14, LEFT_MARGIN, y, sanitizeProse(model.companyName()));
                    drawText(
                        cs,
                        mono,
                        9,
                        370,
                        y,
                        sanitizeMonoCode("GSTIN: " + (model.companyGstin() != null ? model.companyGstin() : "UNREGISTERED"))
                    );
                    y -= 16;

                    drawText(
                        cs,
                        manrope,
                        9,
                        LEFT_MARGIN,
                        y,
                        sanitizeProse("State: " + model.stateName() + " | Currency: \u20B9 INR")
                    );
                    drawText(
                        cs,
                        mono,
                        9,
                        370,
                        y,
                        sanitizeMonoCode("DOC: " + model.documentCode() + " | " + model.dateBanner())
                    );
                    y -= 10;

                    drawHorizontalRule(cs, y);
                    y -= 16;

                    drawText(cs, manrope, 12, LEFT_MARGIN, y, sanitizeProse(model.documentTitle()));
                    y -= 12;
                    drawHorizontalRule(cs, y);
                    y -= 16;

                    for (PrintSectionRow row : pages.get(pageIdx)) {
                        if (y < BOTTOM_LIMIT_Y + 24) {
                            break;
                        }
                        switch (row.kind()) {
                            case KV -> {
                                drawText(
                                    cs,
                                    manrope,
                                    10,
                                    LEFT_MARGIN,
                                    y,
                                    sanitizeProse(row.col1() + ": " + truncate(row.col2(), 42))
                                );
                                if (row.col3() != null && !row.col3().isBlank()) {
                                    drawText(
                                        cs,
                                        mono,
                                        9,
                                        360,
                                        y,
                                        sanitizeMonoCode("PARTY GSTIN: " + row.col3())
                                    );
                                }
                                y -= 16;
                            }
                            case SECTION_HEADER -> {
                                drawHorizontalRule(cs, y + 9);
                                drawText(cs, manrope, 9, LEFT_MARGIN, y, sanitizeProse(truncate(row.col1(), 34)));
                                drawText(cs, mono, 8, 248, y, sanitizeMonoCode(truncate(row.col2(), 18)));
                                drawText(cs, manrope, 9, 368, y, sanitizeProse(truncate(row.col3(), 18)));
                                drawRightAlignedAmount(cs, manrope, 9, RIGHT_MARGIN, y, sanitizeProse(row.col4()));
                                y -= 6;
                                drawHorizontalRule(cs, y);
                                y -= 14;
                            }
                            case ITEM_LINE -> {
                                drawText(cs, manrope, 9, LEFT_MARGIN, y, sanitizeProse(truncate(row.col1(), 36)));
                                drawText(cs, mono, 8, 248, y, sanitizeMonoCode(truncate(row.col2(), 20)));
                                if (row.col3() != null && row.col3().contains("\u20B9")) {
                                    drawText(cs, manrope, 9, 364, y, sanitizeProse(truncate(row.col3(), 20)));
                                } else {
                                    drawText(cs, mono, 8, 364, y, sanitizeMonoCode(truncate(row.col3(), 20)));
                                }
                                drawRightAlignedAmount(cs, manrope, 9, RIGHT_MARGIN, y, sanitizeProse(row.col4()));
                                y -= 14;
                            }
                            case TOTAL_LINE -> {
                                drawHorizontalRule(cs, y + 10);
                                drawText(cs, manrope, 10, LEFT_MARGIN, y, sanitizeProse(truncate(row.col1(), 44)));
                                if (row.col2() != null && row.col2().contains("\u20B9")) {
                                    drawText(cs, manrope, 9, 320, y, sanitizeProse(truncate(row.col2(), 22)));
                                } else {
                                    drawText(cs, mono, 8, 320, y, sanitizeMonoCode(truncate(row.col2(), 22)));
                                }
                                drawRightAlignedAmount(cs, manrope, 10, RIGHT_MARGIN, y, sanitizeProse(row.col4()));
                                y -= 6;
                                drawHorizontalRule(cs, y);
                                y -= 16;
                            }
                            case PROSE_NOTE -> {
                                drawText(cs, manrope, 9, LEFT_MARGIN, y, sanitizeProse(truncate(row.col1(), 88)));
                                y -= 14;
                            }
                            case CODE_META -> {
                                drawText(cs, manrope, 9, LEFT_MARGIN, y, sanitizeProse(row.col1() + ":"));
                                drawText(cs, mono, 8, 128, y, sanitizeMonoCode(truncate(row.col2(), 72)));
                                y -= 14;
                            }
                        }
                    }

                    // Deterministic footer with SHA-256 verification digest in JetBrains Mono
                    drawHorizontalRule(cs, 52);
                    drawText(
                        cs,
                        manrope,
                        8,
                        LEFT_MARGIN,
                        38,
                        sanitizeProse("MoneyCraft 1.0.0 | Exact Fixed-Point Statement | Page " + (pageIdx + 1) + " of " + totalPages)
                    );
                    drawText(
                        cs,
                        mono,
                        8,
                        300,
                        38,
                        sanitizeMonoCode("SHA256:" + model.sha256DigestHex().substring(0, 24))
                    );
                }
            }

            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MC-PDF-RENDER-ERROR",
                "PDF Generation Failed",
                "Failed to generate deterministic PDF document: " + ex.getMessage()
            );
        }
    }

    private byte[] renderWithPlaywrightChromium(PrintDocumentModel model) {
        String manropeBase64 = Base64.getEncoder().encodeToString(manropeFontBytes);
        String monoBase64 = Base64.getEncoder().encodeToString(jetBrainsMonoFontBytes);
        String html = buildOfflineBrandHtml(model, manropeBase64, monoBase64);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--disable-background-networking", "--no-sandbox"))
            );
            try (Page page = browser.newPage()) {
                page.route("**/*", route -> {
                    String url = route.request().url();
                    if (url.startsWith("data:") || url.startsWith("about:")) {
                        route.resume();
                    } else {
                        route.abort();
                    }
                });
                page.setContent(html);
                return page.pdf(
                    new Page.PdfOptions()
                        .setFormat("A4")
                        .setPrintBackground(true)
                );
            } finally {
                browser.close();
            }
        } catch (RuntimeException ex) {
            // Fallback deterministically to embedded PDFBox 3 vector renderer if Chromium binary is absent
            return renderWithPdfBoxVectorEngine(model);
        }
    }

    private String buildOfflineBrandHtml(PrintDocumentModel model, String manropeB64, String monoB64) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><style>");
        sb.append("@font-face{font-family:'Manrope';src:url('data:font/ttf;base64,")
            .append(manropeB64)
            .append("') format('truetype');font-weight:400 700;font-style:normal;}");
        sb.append("@font-face{font-family:'JetBrains Mono';src:url('data:font/ttf;base64,")
            .append(monoB64)
            .append("') format('truetype');font-weight:400;font-style:normal;}");
        sb.append("body{font-family:'Manrope',sans-serif;font-weight:500;color:#0f172a;margin:24px;font-size:12px;}");
        sb.append(".mc-amount{font-family:'Manrope',sans-serif;font-weight:600;font-variant-numeric:tabular-nums lining-nums;text-align:right;}");
        sb.append(".mc-code{font-family:'JetBrains Mono',monospace;font-weight:400;font-feature-settings:'zero' 1,'calt' 0,'liga' 0;font-variant-ligatures:none;}");
        sb.append("table{width:100%;border-collapse:collapse;margin-top:12px;}th,td{border-bottom:1px solid #cbd5e1;padding:6px 4px;}");
        sb.append("</style></head><body>");
        sb.append("<div style=\"display:flex;justify-content:space-between;align-items:baseline;\">");
        sb.append("<h2>").append(escapeHtml(sanitizeProse(model.companyName()))).append("</h2>");
        sb.append("<span class=\"mc-code\">GSTIN: ")
            .append(escapeHtml(sanitizeMonoCode(model.companyGstin() != null ? model.companyGstin() : "UNREGISTERED")))
            .append("</span></div>");
        sb.append("<div>State: ").append(escapeHtml(sanitizeProse(model.stateName())))
            .append(" | Currency: &#x20B9; INR | <span class=\"mc-code\">")
            .append(escapeHtml(sanitizeMonoCode(model.documentCode() + " | " + model.dateBanner())))
            .append("</span></div>");
        sb.append("<h3>").append(escapeHtml(sanitizeProse(model.documentTitle()))).append("</h3>");
        sb.append("<table><tbody>");
        for (PrintSectionRow row : model.rows()) {
            sb.append("<tr><td>").append(escapeHtml(sanitizeProse(row.col1()))).append("</td>");
            sb.append("<td class=\"mc-code\">").append(escapeHtml(sanitizeMonoCode(row.col2()))).append("</td>");
            sb.append("<td>").append(escapeHtml(sanitizeProse(row.col3()))).append("</td>");
            sb.append("<td class=\"mc-amount\">").append(escapeHtml(sanitizeProse(row.col4()))).append("</td></tr>");
        }
        sb.append("</tbody></table>");
        sb.append("<footer style=\"margin-top:18px;font-size:10px;\">MoneyCraft 1.0.0 | <span class=\"mc-code\">SHA256:")
            .append(escapeHtml(sanitizeMonoCode(model.sha256DigestHex().substring(0, 24))))
            .append("</span></footer></body></html>");
        return sb.toString();
    }

    private static void appendStatementChildren(
        List<PrintSectionRow> rows,
        List<ReportingDtos.FinancialStatementLineDto> children,
        String sideTag
    ) {
        if (children == null) {
            return;
        }
        for (ReportingDtos.FinancialStatementLineDto child : children) {
            rows.add(PrintSectionRow.itemLine(
                "  " + child.name(),
                child.code() != null ? child.code() : child.lineType(),
                sideTag,
                "\u20B9 " + formatSignedDisplay(child.amount())
            ));
        }
    }

    private static List<List<PrintSectionRow>> paginateRows(List<PrintSectionRow> allRows, int maxPerPage) {
        List<List<PrintSectionRow>> pages = new ArrayList<>();
        for (int i = 0; i < allRows.size(); i += maxPerPage) {
            pages.add(allRows.subList(i, Math.min(allRows.size(), i + maxPerPage)));
        }
        return pages;
    }

    private static void drawText(
        PDPageContentStream cs,
        PDType0Font font,
        int fontSize,
        int x,
        int y,
        String text
    ) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static void drawRightAlignedAmount(
        PDPageContentStream cs,
        PDType0Font manropeFont,
        int fontSize,
        int rightEdgeX,
        int y,
        String text
    ) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        int approxCharWidth = fontSize / 2 + 1;
        int startX = Math.max(415, rightEdgeX - text.length() * approxCharWidth);
        drawText(cs, manropeFont, fontSize, startX, y, text);
    }

    private static void drawHorizontalRule(PDPageContentStream cs, int y) throws IOException {
        cs.setLineWidth(1);
        cs.moveTo(LEFT_MARGIN, y);
        cs.lineTo(RIGHT_MARGIN, y);
        cs.stroke();
    }

    /**
     * Formats exact monetary wire strings using U+2212 (−) for negative amounts while preserving
     * exact digits so substring extraction finds both U+20B9 (₹) and exact totals.
     */
    private static String formatSignedDisplay(String rawAmount) {
        if (rawAmount == null || rawAmount.isBlank()) {
            return "0.00";
        }
        String trimmed = rawAmount.trim();
        if (trimmed.startsWith("-")) {
            return "\u2212" + trimmed.substring(1);
        }
        return trimmed;
    }

    /**
     * Ensures JetBrains Mono code runs NEVER contain U+20B9 (₹), control characters, or
     * programming ligature presentation codepoints (U+FB00..U+FB06).
     */
    static String sanitizeMonoCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\u20B9') {
                sb.append("INR");
            } else if (ch >= '\uFB00' && ch <= '\uFB06') {
                continue;
            } else if (ch >= 0x20 && ch <= 0x7E) {
                sb.append(ch);
            } else if (ch == '\u2212') {
                sb.append('-');
            }
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    /**
     * Scrubs control characters and programming ligature codepoints while preserving U+20B9 (₹)
     * and U+2212 (−) for Manrope prose/monetary rendering.
     */
    static String sanitizeProse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '\uFB00' && ch <= '\uFB06') {
                continue;
            }
            if (ch == '\u20B9' || ch == '\u2212' || (ch >= 0x20 && ch <= 0x00FF)) {
                sb.append(ch);
            } else if (Character.isWhitespace(ch)) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static String truncate(String val, int maxChars) {
        if (val == null) {
            return "";
        }
        if (val.length() <= maxChars) {
            return val;
        }
        return val.substring(0, Math.max(1, maxChars - 1));
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String resolveVoucherPrintTitle(VoucherDtos.VoucherDetailDto voucher, String customTitle) {
        if (customTitle != null && !customTitle.isBlank()) {
            return sanitizeProse(customTitle);
        }
        String cat = voucher.voucherTypeCategory() == null ? "" : voucher.voucherTypeCategory();
        return switch (cat) {
            case "SALES" -> "TAX INVOICE";
            case "PURCHASE" -> "PURCHASE INVOICE";
            case "CREDIT_NOTE" -> "CREDIT NOTE";
            case "DEBIT_NOTE" -> "DEBIT NOTE";
            case "PAYMENT" -> "PAYMENT VOUCHER";
            case "RECEIPT" -> "RECEIPT VOUCHER";
            case "CONTRA" -> "CONTRA VOUCHER";
            default -> (voucher.voucherTypeName() != null
                ? voucher.voucherTypeName().toUpperCase(Locale.ROOT) + " VOUCHER"
                : "ACCOUNTING VOUCHER");
        };
    }

    private static String firstLineLedgerName(VoucherDtos.VoucherDetailDto voucher) {
        if (voucher.lines() != null && !voucher.lines().isEmpty()) {
            return voucher.lines().getFirst().ledgerName();
        }
        return "-";
    }

    private CompanyPrintHeader loadCompanyPrintHeader(UUID companyId, UUID preferredGstinId, UUID partyLedgerId) {
        return businessTxTemplate.execute(tx -> {
            List<CompanyPrintHeader> headers = businessJdbcTemplate.query(
                """
                SELECT c.code, c.legal_name AS name, COALESCE(c.state_code, '33') AS state_code,
                       (
                         SELECT g.gstin
                           FROM mc_gst_registration g
                          WHERE g.company_id = c.id
                            AND g.active = true
                          ORDER BY CASE WHEN g.id = ? THEN 0 WHEN g.is_default THEN 1 ELSE 2 END,
                                   g.created_at ASC
                          LIMIT 1
                       ) AS company_gstin,
                       (
                         SELECT l.gstin
                           FROM mc_ledger l
                          WHERE l.company_id = c.id
                            AND l.id = ?
                       ) AS party_gstin
                  FROM mc_company c
                 WHERE c.id = ?
                """,
                (rs, rowNum) -> new CompanyPrintHeader(
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("state_code"),
                    rs.getString("company_gstin"),
                    rs.getString("party_gstin")
                ),
                preferredGstinId,
                partyLedgerId,
                companyId
            );
            if (headers.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-COMPANY-NOT-FOUND",
                    "Company Not Found",
                    "Company " + companyId + " does not exist."
                );
            }
            return headers.getFirst();
        });
    }

    private VoucherStatutoryPrintMeta loadVoucherStatutoryMeta(UUID companyId, UUID journalId) {
        return businessTxTemplate.execute(tx -> {
            String irn = businessJdbcTemplate.query(
                """
                SELECT irn FROM mc_einvoice_irn
                 WHERE company_id = ? AND journal_id = ? AND irn IS NOT NULL
                 ORDER BY updated_at DESC LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("irn") : null,
                companyId,
                journalId
            );
            String ewb = businessJdbcTemplate.query(
                """
                SELECT ewb_no FROM mc_eway_bill
                 WHERE company_id = ? AND journal_id = ? AND ewb_no IS NOT NULL
                 ORDER BY updated_at DESC LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("ewb_no") : null,
                companyId,
                journalId
            );
            return new VoucherStatutoryPrintMeta(irn, ewb);
        });
    }

    private Map<UUID, String> loadItemHsnMap(
        UUID companyId,
        List<VoucherDtos.VoucherInventoryLineDto> invLines
    ) {
        Map<UUID, String> map = new LinkedHashMap<>();
        if (invLines == null || invLines.isEmpty()) {
            return map;
        }
        businessTxTemplate.executeWithoutResult(tx -> {
            businessJdbcTemplate.query(
                """
                SELECT si.id, COALESCE(si.hsn_sac_code, sg.hsn_sac_code, '-') AS hsn
                  FROM mc_stock_item si
                  LEFT JOIN mc_stock_group sg
                         ON sg.company_id = si.company_id AND sg.id = si.stock_group_id
                 WHERE si.company_id = ?
                """,
                rs -> {
                    map.put(rs.getObject("id", UUID.class), rs.getString("hsn"));
                },
                companyId
            );
        });
        return map;
    }

    private static byte[] readClasspathBytes(String resourcePath) {
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Missing bundled Brand v0.2.0 TrueType font: " + resourcePath, ex);
        }
    }

    private static String computeVerificationDigest(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record CompanyPrintHeader(
        String companyCode,
        String companyName,
        String stateName,
        String companyGstin,
        String fallbackPartyGstin
    ) {
    }

    private record VoucherStatutoryPrintMeta(String irn, String ewbNo) {
    }

    private enum RowKind {
        KV,
        SECTION_HEADER,
        ITEM_LINE,
        TOTAL_LINE,
        PROSE_NOTE,
        CODE_META
    }

    private record PrintSectionRow(
        RowKind kind,
        String col1,
        String col2,
        String col3,
        String col4
    ) {
        static PrintSectionRow kv(String label, String value, String codeRight) {
            return new PrintSectionRow(RowKind.KV, label, value, codeRight, "");
        }

        static PrintSectionRow sectionHeader(String c1, String c2, String c3, String c4) {
            return new PrintSectionRow(RowKind.SECTION_HEADER, c1, c2, c3, c4);
        }

        static PrintSectionRow itemLine(String c1, String c2, String c3, String c4) {
            return new PrintSectionRow(RowKind.ITEM_LINE, c1, c2, c3, c4);
        }

        static PrintSectionRow totalLine(String label, String badge, String totalAmt) {
            return new PrintSectionRow(RowKind.TOTAL_LINE, label, badge, "", totalAmt);
        }

        static PrintSectionRow proseNote(String note) {
            return new PrintSectionRow(RowKind.PROSE_NOTE, note, "", "", "");
        }

        static PrintSectionRow codeMeta(String label, String code) {
            return new PrintSectionRow(RowKind.CODE_META, label, code, "", "");
        }
    }

    private record PrintDocumentModel(
        String companyName,
        String companyCode,
        String companyGstin,
        String stateName,
        String documentTitle,
        String documentCode,
        String dateBanner,
        List<PrintSectionRow> rows,
        String sha256DigestHex
    ) {
    }
}
