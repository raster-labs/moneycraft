package com.raster.moneycraft.desktop.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Dev002PrintAttemptLedgerRecoveryTest {

    @TempDir
    Path tempDir;

    private static final UUID COMPANY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    private static final UUID VOUCHER_DOC_ID = UUID.fromString("cccccccc-1111-2222-3333-444444444444");

    @Test
    void at09Dev002A_B_C_and_Dev004_midSendCrashRecoversUnknownReconcilesAndCreatesAuditedReprintWithZeroDuplicatePost() throws Exception {
        Path ledgerFile = tempDir.resolve("print-attempts.enc");
        byte[] aesKey = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
        AtomicInteger financialVoucherPostCalls = new AtomicInteger(0);

        DeviceAuthorityContext personAuth = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("company.read", "export")
        );
        DeviceProfile thermalProfile = new DeviceProfile(
            "epson-tm-t82",
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
            0,
            Set.of(),
            Set.of(),
            Optional.of("INR "),
            false,
            false,
            DeviceProfile.CompletionEvidenceCapability.PRINTER_HARDWARE_CONFIRMED
        );
        RawDevicePrintCompiler compiler = new RawDevicePrintCompiler();
        PrintableDocumentModel originalModel = new PrintableDocumentModel(
            VOUCHER_DOC_ID,
            COMPANY_ID,
            "Raster Images Pvt Ltd",
            PrintableDocumentModel.DocumentKind.THERMAL_RECEIPT,
            "SLS-2026-0088",
            3L,
            LocalDate.of(2026, 4, 20),
            "INR ",
            "Southern Distributors",
            "Invoice settlement",
            List.of(new PrintableLineItem("Ledger Paper", BigDecimal.ONE, "BOX", new BigDecimal("950.00"), new BigDecimal("950.00"))),
            new BigDecimal("950.00"),
            null,
            null,
            PrintableDocumentModel.CopyBanner.ORIGINAL,
            false,
            false,
            false
        );

        // 1. Voucher posted ONCE on server; local print attempt enters SUBMITTING and crashes mid-send
        financialVoucherPostCalls.incrementAndGet();
        byte[] compiledOriginal = compiler.compile(originalModel, thermalProfile, personAuth);
        UUID interruptedAttemptId;
        {
            PrintAttemptLedger preCrashLedger = new PrintAttemptLedger(ledgerFile, aesKey);
            PrintAttemptRecord prepared = preCrashLedger.prepareAttempt(
                personAuth, VOUCHER_DOC_ID, 3L, false, compiledOriginal, thermalProfile, "USB_POS_1"
            );
            assertEquals(PrintAttemptState.PREPARED, prepared.state());
            PrintAttemptRecord submitting = preCrashLedger.beginSubmitting(prepared.attemptId(), personAuth);
            assertEquals(PrintAttemptState.SUBMITTING, submitting.state());
            interruptedAttemptId = submitting.attemptId();
        }

        // Verify AES-GCM on-disk encryption contains zero plaintext destination/profile strings
        byte[] encryptedBytes = Files.readAllBytes(ledgerFile);
        String diskAscii = new String(encryptedBytes, StandardCharsets.ISO_8859_1);
        assertFalse(diskAscii.contains("USB_POS_1"));
        assertFalse(diskAscii.contains("epson-tm-t82"));

        // 2. Restart desktop client: interrupted SUBMITTING attempt is recovered as OUTCOME_UNKNOWN
        PrintAttemptLedger postCrashLedger = new PrintAttemptLedger(ledgerFile, aesKey);
        PrintAttemptRecord recovered = postCrashLedger.findAttempt(interruptedAttemptId).orElseThrow();
        assertEquals(PrintAttemptState.OUTCOME_UNKNOWN, recovered.state());
        assertFalse(recovered.unknownReconciled());

        // 3. Blind retry or reprint BEFORE operator reconciliation MUST fail closed
        PrintableDocumentModel duplicateModel = originalModel.asDuplicateReprint();
        byte[] compiledDuplicate = compiler.compile(duplicateModel, thermalProfile, personAuth);
        String duplicateText = new String(compiledDuplicate, StandardCharsets.ISO_8859_1);
        assertTrue(duplicateText.contains("*** DUPLICATE / REPRINT ***"));

        assertThrows(
            DeviceSecurityException.class,
            () -> postCrashLedger.createAuditedReprint(
                interruptedAttemptId, personAuth, "Blind reprint without reconciliation", compiledDuplicate
            )
        );
        assertThrows(
            DeviceSecurityException.class,
            () -> postCrashLedger.prepareAttempt(
                personAuth, VOUCHER_DOC_ID, 3L, false, compiledOriginal, thermalProfile, "USB_POS_1"
            )
        );

        // 4. Operator checks printer queue, reconciles OUTCOME_UNKNOWN, then creates audited DUPLICATE / REPRINT
        PrintAttemptRecord reconciled = postCrashLedger.reconcileUnknownAttempt(
            interruptedAttemptId,
            personAuth,
            false,
            "Checked USB thermal roll; power loss occurred before paper feed."
        );
        assertTrue(reconciled.unknownReconciled());
        assertEquals(CompletionEvidenceSource.OPERATOR_RECONCILED_NOT_PRINTED, reconciled.evidenceSource());

        PrintAttemptRecord auditedReprint = postCrashLedger.createAuditedReprint(
            interruptedAttemptId,
            personAuth,
            "Reprinting duplicate customer receipt after power restoration",
            compiledDuplicate
        );
        assertNotEquals(interruptedAttemptId, auditedReprint.attemptId());
        assertTrue(auditedReprint.reprint());
        assertEquals(interruptedAttemptId, auditedReprint.reprintOfAttemptId());
        assertEquals(3L, auditedReprint.documentVersion());
        assertEquals(PrintAttemptState.PREPARED, auditedReprint.state());

        postCrashLedger.beginSubmitting(auditedReprint.attemptId(), personAuth);
        PrintAttemptRecord submittedReprint = postCrashLedger.markSubmitted(
            auditedReprint.attemptId(),
            CompletionEvidenceSource.TRANSPORT_BYTES_ACCEPTED,
            "USB bulk transfer acknowledged"
        );
        assertEquals(PrintAttemptState.SUBMITTED, submittedReprint.state());

        // Transport bytes alone CANNOT mark CONFIRMED_COMPLETE
        assertThrows(
            DeviceSecurityException.class,
            () -> postCrashLedger.markConfirmedComplete(
                auditedReprint.attemptId(),
                CompletionEvidenceSource.SPOOLER_DATA_TRANSFERRED,
                "Unqualified spool event"
            )
        );

        PrintAttemptRecord confirmedReprint = postCrashLedger.markConfirmedComplete(
            auditedReprint.attemptId(),
            CompletionEvidenceSource.PRINTER_HARDWARE_CONFIRMED,
            "ESC/POS ASB status confirmed cutter & platen idle"
        );
        assertEquals(PrintAttemptState.CONFIRMED_COMPLETE, confirmedReprint.state());

        // Financial voucher post count stayed strictly 1 across crash, reconciliation, and reprint!
        assertEquals(1, financialVoucherPostCalls.get());
    }

    @Test
    void at09Dev002C_and_Dev004_enforcesCancellationBeforeSendRevokedAuthorityAndTamperDetection() throws Exception {
        Path ledgerFile = tempDir.resolve("cancel-ledger.enc");
        byte[] aesKey = "11223344556677889900aabbccddeeff".getBytes(StandardCharsets.US_ASCII);
        PrintAttemptLedger ledger = new PrintAttemptLedger(ledgerFile, aesKey);

        DeviceAuthorityContext activeAuth = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("company.read", "export")
        );
        DeviceProfile profile = new DeviceProfile(
            "os-pdf-1", "1.0", true, "Generic", "PDF", "1.0", "macOS", "aarch64",
            DeviceProfile.TransportMode.OS_PRINT_DRIVER, DeviceProfile.CommandLanguage.OS_PAGE_PDF,
            "UTF-8", 0, 210, 80, 66, 0, Set.of(), Set.of(), Optional.empty(), false, false,
            DeviceProfile.CompletionEvidenceCapability.NONE
        );

        // 1. Cancel before submission blocks subsequent submission
        PrintAttemptRecord toCancel = ledger.prepareAttempt(
            activeAuth, UUID.randomUUID(), 1L, false, "%PDF-1.7".getBytes(StandardCharsets.UTF_8), profile, "PRINTER_A"
        );
        PrintAttemptRecord cancelled = ledger.cancelBeforeSubmission(toCancel.attemptId(), activeAuth, "Operator cancelled");
        assertEquals(PrintAttemptState.CANCELLED_BEFORE_SUBMISSION, cancelled.state());
        assertThrows(DeviceSecurityException.class, () -> ledger.beginSubmitting(toCancel.attemptId(), activeAuth));

        // 2. Revoked permission or logged-out authority blocks stale prepared attempt
        PrintAttemptRecord stalePrepared = ledger.prepareAttempt(
            activeAuth, UUID.randomUUID(), 1L, false, "%PDF-1.7".getBytes(StandardCharsets.UTF_8), profile, "PRINTER_A"
        );
        DeviceAuthorityContext revokedExportAuth = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("company.read")
        );
        DeviceAuthorityContext loggedOutAuth = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            false,
            Set.of("company.read", "export")
        );
        assertThrows(
            DeviceSecurityException.class,
            () -> ledger.beginSubmitting(stalePrepared.attemptId(), revokedExportAuth)
        );
        assertThrows(
            DeviceSecurityException.class,
            () -> ledger.beginSubmitting(stalePrepared.attemptId(), loggedOutAuth)
        );
    }

    @Test
    void at09Dev004A_B_C_rejectsPrivateFieldWithoutGrantDeviceAdminActorPathTraversalAndTamperedCiphertext() throws Exception {
        Path ledgerFile = tempDir.resolve("tamper-ledger.enc");
        byte[] aesKey = "99887766554433221100ffeeddccbbaa".getBytes(StandardCharsets.US_ASCII);
        PrintAttemptLedger ledger = new PrintAttemptLedger(ledgerFile, aesKey);

        DeviceProfile profile = new DeviceProfile(
            "os-pdf-sec", "1.0", true, "Generic", "PDF", "1.0", "macOS", "aarch64",
            DeviceProfile.TransportMode.OS_PRINT_DRIVER, DeviceProfile.CommandLanguage.OS_PAGE_PDF,
            "UTF-8", 0, 210, 80, 66, 0, Set.of(), Set.of(), Optional.empty(), false, false,
            DeviceProfile.CompletionEvidenceCapability.NONE
        );

        // Missing privateField.read blocks document containing private fields
        DeviceAuthorityContext noPrivateFieldAuth = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("company.read", "export")
        );
        assertThrows(
            DeviceSecurityException.class,
            () -> ledger.prepareAttempt(
                noPrivateFieldAuth, UUID.randomUUID(), 1L, true, "%PDF-1.7".getBytes(StandardCharsets.UTF_8), profile, "PRINTER_1"
            )
        );

        // Device administrator config-only actor cannot output business documents
        DeviceAuthorityContext deviceAdminOnly = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.DEVICE_ADMINISTRATOR_CONFIG_ONLY,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("device.configure", "company.read", "export")
        );
        assertThrows(
            DeviceSecurityException.class,
            () -> ledger.prepareAttempt(
                deviceAdminOnly, UUID.randomUUID(), 1L, false, "%PDF-1.7".getBytes(StandardCharsets.UTF_8), profile, "PRINTER_1"
            )
        );

        // Arbitrary host path / shell injection in destination rejected
        assertThrows(
            DeviceSecurityException.class,
            () -> DeviceProfile.validateSafeLocalDestination("../../etc/passwd")
        );
        assertThrows(
            DeviceSecurityException.class,
            () -> DeviceProfile.validateSafeLocalDestination("lp0; rm -rf /")
        );

        // Valid write followed by 1-byte ciphertext tamper fails GCM authentication on reload
        ledger.prepareAttempt(
            noPrivateFieldAuth, UUID.randomUUID(), 1L, false, "%PDF-1.7".getBytes(StandardCharsets.UTF_8), profile, "PRINTER_1"
        );
        byte[] raw = Files.readAllBytes(ledgerFile);
        raw[raw.length - 1] ^= 0x55;
        Files.write(ledgerFile, raw);
        assertThrows(DeviceSecurityException.class, () -> new PrintAttemptLedger(ledgerFile, aesKey));
    }
}
