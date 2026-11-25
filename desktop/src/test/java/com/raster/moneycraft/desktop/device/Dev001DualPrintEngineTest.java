package com.raster.moneycraft.desktop.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.print.DocFlavor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Dev001DualPrintEngineTest {

    @TempDir
    Path tempDir;

    private static final UUID COMPANY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    private static final UUID DOC_ID = UUID.fromString("dddddddd-1111-2222-3333-444444444444");

    @Test
    void at09Dev001A_and_B_pagePrintAdapterQueriesDocFlavorCapabilityBeforeTransferAndDistinguishesSpoolFromConfirm() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        PrintAttemptLedger ledger = new PrintAttemptLedger(tempDir.resolve("page-ledger.enc"), key);
        DeviceAuthorityContext authority = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("company.read", "export")
        );

        DeviceProfile spoolOnlyProfile = new DeviceProfile(
            "os-laser-spool",
            "1.0",
            true,
            "Generic",
            "LaserJet-OS",
            "fw-2.1",
            "macOS",
            "aarch64",
            DeviceProfile.TransportMode.OS_PRINT_DRIVER,
            DeviceProfile.CommandLanguage.OS_PAGE_PDF,
            "UTF-8",
            0,
            210,
            80,
            66,
            0,
            Set.of(),
            Set.of(),
            Optional.empty(),
            false,
            false,
            DeviceProfile.CompletionEvidenceCapability.SPOOLER_DATA_TRANSFERRED_ONLY
        );

        AtomicInteger submitCalls = new AtomicInteger(0);
        PagePrintAdapter rejectingAdapter = new PagePrintAdapter(ledger, new PagePrintAdapter.OsPagePrintBackend() {
            @Override
            public PagePrintAdapter.OsServiceCapability inspectServiceCapability(String serviceName, DocFlavor requiredFlavor) {
                return new PagePrintAdapter.OsServiceCapability(true, false, List.of("application/postscript"));
            }

            @Override
            public PagePrintAdapter.OsSubmissionOutcome submitPdfJob(String serviceName, String jobName, byte[] pdfBytes) {
                submitCalls.incrementAndGet();
                return new PagePrintAdapter.OsSubmissionOutcome(true, false, "Should never run");
            }
        });

        byte[] validPdf = "%PDF-1.7\n%Server rendered invoice\n%%EOF".getBytes(StandardCharsets.UTF_8);
        PrintAttemptRecord rejectedAttempt = rejectingAdapter.printServerPdf(
            authority, DOC_ID, 1L, false, validPdf, spoolOnlyProfile, "PostScriptOnlyPrinter"
        );
        assertEquals(PrintAttemptState.FAILED_BEFORE_SUBMISSION, rejectedAttempt.state());
        assertEquals(0, submitCalls.get());
        assertTrue(rejectedAttempt.diagnosticMessage().contains("Export PDF"));

        // Non-PDF arbitrary HTML/script rejected
        assertThrows(
            DeviceSecurityException.class,
            () -> rejectingAdapter.printServerPdf(
                authority,
                DOC_ID,
                1L,
                false,
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8),
                spoolOnlyProfile,
                "PostScriptOnlyPrinter"
            )
        );

        // Spooler byte transfer alone yields SUBMITTED, NOT CONFIRMED_COMPLETE
        PagePrintAdapter spoolAcceptingAdapter = new PagePrintAdapter(ledger, new PagePrintAdapter.OsPagePrintBackend() {
            @Override
            public PagePrintAdapter.OsServiceCapability inspectServiceCapability(String serviceName, DocFlavor requiredFlavor) {
                return new PagePrintAdapter.OsServiceCapability(true, true, List.of("application/pdf"));
            }

            @Override
            public PagePrintAdapter.OsSubmissionOutcome submitPdfJob(String serviceName, String jobName, byte[] pdfBytes) {
                return new PagePrintAdapter.OsSubmissionOutcome(true, true, "Spooler accepted bytes");
            }
        });
        PrintAttemptRecord submittedRecord = spoolAcceptingAdapter.printServerPdf(
            authority, UUID.randomUUID(), 1L, false, validPdf, spoolOnlyProfile, "OfficeLaserPDF"
        );
        assertEquals(PrintAttemptState.SUBMITTED, submittedRecord.state());
        assertEquals(CompletionEvidenceSource.SPOOLER_DATA_TRANSFERRED, submittedRecord.evidenceSource());
    }

    @Test
    void at09Dev001B_rawCompilerEmitsExactEscPosDotMatrixAndBarcodeBytesAndBlocksUnsupportedRupeeGlyph() {
        RawDevicePrintCompiler compiler = new RawDevicePrintCompiler();
        DeviceAuthorityContext authority = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("company.read", "export", "device.cutter")
        );

        PrintableDocumentModel receiptDoc = new PrintableDocumentModel(
            DOC_ID,
            COMPANY_ID,
            "Raster Images Pvt Ltd",
            PrintableDocumentModel.DocumentKind.THERMAL_RECEIPT,
            "POS-0042",
            1L,
            LocalDate.of(2026, 4, 18),
            "\u20B9",
            "Bengaluru Retail Counter",
            "Counter settlement",
            List.of(new PrintableLineItem("A4 Paper Ream", new BigDecimal("2.000"), "NOS", new BigDecimal("625.00"), new BigDecimal("1250.00"))),
            new BigDecimal("1250.00"),
            "0004589120",
            "CODE128",
            PrintableDocumentModel.CopyBanner.ORIGINAL,
            false,
            true,
            false
        );

        // 1. CP437 thermal profile WITHOUT approved currency fallback MUST block on U+20B9 '₹' (never emit '?')
        DeviceProfile cp437NoRupeeProfile = new DeviceProfile(
            "epson-tm-t82-cp437",
            "1.0",
            true,
            "Epson",
            "TM-T82III",
            "1.04",
            "macOS",
            "aarch64",
            DeviceProfile.TransportMode.USB_RAW_PORT,
            DeviceProfile.CommandLanguage.EPSON_ESC_POS,
            "IBM437",
            0,
            80,
            42,
            0,
            0,
            Set.of("CODE128"),
            Set.of(),
            Optional.empty(),
            true,
            false,
            DeviceProfile.CompletionEvidenceCapability.NONE
        );
        UnsupportedGlyphException glyphEx = assertThrows(
            UnsupportedGlyphException.class,
            () -> compiler.compile(receiptDoc, cp437NoRupeeProfile, authority)
        );
        assertEquals(0x20B9, glyphEx.codePoint());
        assertEquals("currencySymbol", glyphEx.fieldName());

        // 2. Admitted thermal profile with explicit approved 'INR ' token compiles exact ESC/POS + leading-zero barcode
        DeviceProfile cp437ApprovedInrProfile = new DeviceProfile(
            "epson-tm-t82-inr",
            "1.0",
            true,
            "Epson",
            "TM-T82III",
            "1.04",
            "macOS",
            "aarch64",
            DeviceProfile.TransportMode.USB_RAW_PORT,
            DeviceProfile.CommandLanguage.EPSON_ESC_POS,
            "IBM437",
            0,
            80,
            42,
            0,
            0,
            Set.of("CODE128"),
            Set.of(),
            Optional.of("INR "),
            true,
            false,
            DeviceProfile.CompletionEvidenceCapability.NONE
        );
        byte[] escPosBytes = compiler.compile(receiptDoc, cp437ApprovedInrProfile, authority);
        assertEquals(0x1B, escPosBytes[0]);
        assertEquals('@', escPosBytes[1]);
        String asciiView = new String(escPosBytes, StandardCharsets.ISO_8859_1);
        assertTrue(asciiView.contains("INR 1250.00"));
        assertTrue(asciiView.contains("{B0004589120"));
        assertFalse(asciiView.contains("\u001Bp"), "No cash-drawer kick byte sequence may appear");

        // 3. Dot-matrix continuous-form ESC/P compiles with FF (0x0C) top-of-form alignment
        DeviceProfile dotMatrixProfile = new DeviceProfile(
            "epson-lq-310",
            "1.0",
            true,
            "Epson",
            "LQ-310",
            "2.00",
            "Windows",
            "x86_64",
            DeviceProfile.TransportMode.USB_RAW_PORT,
            DeviceProfile.CommandLanguage.DOT_MATRIX_ESC_P,
            "US-ASCII",
            0,
            215,
            80,
            66,
            0,
            Set.of(),
            Set.of(),
            Optional.of("INR "),
            false,
            false,
            DeviceProfile.CompletionEvidenceCapability.NONE
        );
        PrintableDocumentModel continuousDoc = new PrintableDocumentModel(
            DOC_ID,
            COMPANY_ID,
            "Raster Images Pvt Ltd",
            PrintableDocumentModel.DocumentKind.CONTINUOUS_FORM_VOUCHER,
            "POS-0042",
            1L,
            LocalDate.of(2026, 4, 18),
            "\u20B9",
            "Bengaluru Retail Counter",
            "Counter settlement",
            List.of(new PrintableLineItem("A4 Paper Ream", new BigDecimal("2.000"), "NOS", new BigDecimal("625.00"), new BigDecimal("1250.00"))),
            new BigDecimal("1250.00"),
            "0004589120",
            "CODE128",
            PrintableDocumentModel.CopyBanner.ORIGINAL,
            false,
            false,
            false
        );
        byte[] escPBytes = compiler.compile(continuousDoc, dotMatrixProfile, authority);
        assertEquals(0x0C, escPBytes[escPBytes.length - 1], "Continuous-form output must end with Form Feed 0x0C");

        // 4. Barcode label TSPL preserves leading zeros and enforces quiet zone
        DeviceProfile labelProfile = new DeviceProfile(
            "tsc-te244",
            "1.0",
            true,
            "TSC",
            "TE244",
            "3.11",
            "Linux",
            "x86_64",
            DeviceProfile.TransportMode.USB_RAW_PORT,
            DeviceProfile.CommandLanguage.BARCODE_LABEL_TSPL,
            "US-ASCII",
            0,
            50,
            32,
            0,
            24,
            Set.of("CODE128", "EAN13"),
            Set.of(),
            Optional.of("INR "),
            false,
            false,
            DeviceProfile.CompletionEvidenceCapability.NONE
        );
        byte[] tsplBytes = compiler.compile(continuousDoc, labelProfile, authority);
        String tsplText = new String(tsplBytes, StandardCharsets.US_ASCII);
        assertTrue(tsplText.contains("BARCODE 24,100,\"128\",60,1,0,2,2,\"0004589120\""));
        assertTrue(tsplText.endsWith("PRINT 1,1\r\n"));
    }

    @Test
    void at09Dev001C_rawCompilerBlocksAdversarialControlBytesCashDrawerKicksPaperCutsAndCopyInjection() {
        RawDevicePrintCompiler compiler = new RawDevicePrintCompiler();
        DeviceAuthorityContext authority = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("company.read", "export")
        );
        DeviceProfile profile = new DeviceProfile(
            "epson-tm-t82-safe",
            "1.0",
            true,
            "Epson",
            "TM-T82III",
            "1.04",
            "macOS",
            "aarch64",
            DeviceProfile.TransportMode.USB_RAW_PORT,
            DeviceProfile.CommandLanguage.EPSON_ESC_POS,
            "US-ASCII",
            0,
            80,
            42,
            0,
            16,
            Set.of("CODE128"),
            Set.of(),
            Optional.of("INR "),
            false,
            true,
            DeviceProfile.CompletionEvidenceCapability.NONE
        );

        // Adversarial customer strings attempting ESC p drawer kick, GS V cut, FS, NUL, and newline injection
        for (String maliciousCustomer : List.of(
            "Acme\u001Bp\u0000\u0019\u00FAKickDrawer",
            "Acme\u001DV\u0042\u0000InjectCut",
            "Acme\u001CFSInject",
            "Acme\u0000NullByte",
            "Acme\r\nPRINT 1,99"
        )) {
            PrintableDocumentModel poisonedDoc = new PrintableDocumentModel(
                DOC_ID,
                COMPANY_ID,
                "Raster Images",
                PrintableDocumentModel.DocumentKind.THERMAL_RECEIPT,
                "POS-99",
                1L,
                LocalDate.of(2026, 4, 18),
                "INR ",
                maliciousCustomer,
                "Clean narration",
                List.of(),
                new BigDecimal("100.00"),
                "00012345",
                "CODE128",
                PrintableDocumentModel.CopyBanner.ORIGINAL,
                false,
                false,
                false
            );
            assertThrows(
                RawPrintCompilationException.class,
                () -> compiler.compile(poisonedDoc, profile, authority)
            );
        }

        // Unauthorised cash-drawer kick request denied when caller lacks 'device.cashDrawer'
        PrintableDocumentModel unauthorizedDrawerDoc = new PrintableDocumentModel(
            DOC_ID,
            COMPANY_ID,
            "Raster Images",
            PrintableDocumentModel.DocumentKind.THERMAL_RECEIPT,
            "POS-100",
            1L,
            LocalDate.of(2026, 4, 18),
            "INR ",
            "Valid Customer",
            "Settlement",
            List.of(),
            new BigDecimal("100.00"),
            null,
            null,
            PrintableDocumentModel.CopyBanner.ORIGINAL,
            false,
            false,
            true
        );
        assertThrows(
            DeviceSecurityException.class,
            () -> compiler.compile(unauthorizedDrawerDoc, profile, authority)
        );
    }
}
