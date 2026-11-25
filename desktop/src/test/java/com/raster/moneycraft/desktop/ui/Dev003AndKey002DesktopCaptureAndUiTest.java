package com.raster.moneycraft.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raster.moneycraft.desktop.device.BarcodeCaptureResult;
import com.raster.moneycraft.desktop.device.BarcodeScannerProfile;
import com.raster.moneycraft.desktop.device.DeviceAuthorityContext;
import com.raster.moneycraft.desktop.device.DeviceSecurityException;
import com.raster.moneycraft.desktop.device.PrintAttemptLedger;
import com.raster.moneycraft.desktop.device.ScaleAndBarcodeCaptureManager;
import com.raster.moneycraft.desktop.device.ScaleReadingResult;
import com.raster.moneycraft.desktop.device.WeighingScaleProfile;
import com.raster.moneycraft.desktop.keyboard.DesktopCommandRegistry;
import com.raster.moneycraft.desktop.keyboard.InvalidModalActionSetException;
import com.raster.moneycraft.desktop.keyboard.KeyboardPreferenceState;
import com.raster.moneycraft.desktop.keyboard.ModalActionSpec;
import com.raster.moneycraft.desktop.keyboard.ShortcutMode;
import com.raster.moneycraft.desktop.keyboard.StateTokenRole;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Action;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Dev003AndKey002DesktopCaptureAndUiTest {

    @TempDir
    Path tempDir;

    private static final UUID COMPANY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");

    @Test
    void at09Dev003A_B_C_validatesSerialScaleFramesPreservesLeadingZeroBarcodesAndRejectsUnclaimedPosCapabilities() {
        DesktopCommandRegistry registry = new DesktopCommandRegistry();
        ScaleAndBarcodeCaptureManager manager = new ScaleAndBarcodeCaptureManager(registry);
        DeviceAuthorityContext authority = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("company.read", "device.scaleCapture", "device.barcodeCapture")
        );

        WeighingScaleProfile scaleProfile = new WeighingScaleProfile(
            "mettler-rs232-kg",
            true,
            "Mettler",
            "IND246",
            9600,
            8,
            "NONE",
            1,
            "\r\n",
            Duration.ofSeconds(2),
            "KG",
            3,
            new BigDecimal("0.005"),
            new BigDecimal("150.000")
        );

        Instant t0 = Instant.parse("2026-11-16T08:00:00Z");

        // 1. Valid stable reading: gross 12.500 - tare 0.500 = net 12.000 KG
        ScaleReadingResult valid = manager.captureScaleFrame(
            scaleProfile, authority, true, "ST,GS,  12.500,  0.500, 12.000,KG\r\n", t0, t0.plusMillis(400)
        );
        assertTrue(valid.accepted());
        assertEquals(new BigDecimal("12.000"), valid.netWeight());
        assertEquals("KG", valid.unit());

        // 2. Unstable ('US') rejected with null weight (never 0, never reusing 12.000)
        ScaleReadingResult unstable = manager.captureScaleFrame(
            scaleProfile, authority, true, "US,GS,  12.500,  0.500, 12.000,KG\r\n", t0, t0.plusMillis(500)
        );
        assertFalse(unstable.accepted());
        assertNull(unstable.netWeight());
        assertEquals(ScaleReadingResult.RejectionReason.UNSTABLE_READING, unstable.rejectionReason());

        // 3. Stale frame exceeding 2s timeout rejected
        ScaleReadingResult stale = manager.captureScaleFrame(
            scaleProfile, authority, true, "ST,NT, 10.000,KG\r\n", t0, t0.plusSeconds(3)
        );
        assertFalse(stale.accepted());
        assertNull(stale.netWeight());
        assertEquals(ScaleReadingResult.RejectionReason.STALE_TIMEOUT, stale.rejectionReason());

        // 4. Wrong unit ('LB' vs 'KG') rejected
        ScaleReadingResult wrongUnit = manager.captureScaleFrame(
            scaleProfile, authority, true, "ST,NT, 10.000,LB\r\n", t0, t0.plusMillis(100)
        );
        assertFalse(wrongUnit.accepted());
        assertEquals(ScaleReadingResult.RejectionReason.UNIT_MISMATCH, wrongUnit.rejectionReason());

        // 5. Tare/gross/net mismatch rejected
        ScaleReadingResult badTare = manager.captureScaleFrame(
            scaleProfile, authority, true, "ST,GS, 12.500, 0.500, 11.200,KG\r\n", t0, t0.plusMillis(100)
        );
        assertFalse(badTare.accepted());
        assertEquals(ScaleReadingResult.RejectionReason.TARE_GROSS_NET_MISMATCH, badTare.rejectionReason());

        // 6. Disconnected scale rejected (never 0)
        ScaleReadingResult disconnected = manager.captureScaleFrame(
            scaleProfile, authority, false, null, t0, t0.plusMillis(100)
        );
        assertFalse(disconnected.accepted());
        assertNull(disconnected.netWeight());
        assertEquals(ScaleReadingResult.RejectionReason.DISCONNECTED, disconnected.rejectionReason());

        // 7. Barcode capture preserves exact leading zeros and rejects consecutive duplicates & control bytes
        BarcodeScannerProfile framedScanner = new BarcodeScannerProfile(
            "zebra-ds2208-serial",
            true,
            "Zebra",
            "DS2208",
            BarcodeScannerProfile.IsolationMode.FRAMED_SERIAL_OR_NATIVE,
            "[SCAN]",
            "[/SCAN]",
            true,
            BarcodeScannerProfile.DuplicateScanPolicy.REJECT_CONSECUTIVE_DUPLICATE,
            4,
            32
        );
        manager.activateBarcodeWorkflow(framedScanner, authority);
        assertFalse(registry.isUnframedWedgeScannerActive());

        BarcodeCaptureResult firstScan = manager.captureBarcode(authority, "[SCAN]0000890123450[/SCAN]", "itemBarcode");
        assertTrue(firstScan.accepted());
        assertEquals("0000890123450", firstScan.exactIdentifier());

        BarcodeCaptureResult duplicateScan = manager.captureBarcode(authority, "[SCAN]0000890123450[/SCAN]", "itemBarcode");
        assertFalse(duplicateScan.accepted());
        assertEquals(BarcodeCaptureResult.RejectionReason.CONSECUTIVE_DUPLICATE_SCAN, duplicateScan.rejectionReason());

        BarcodeCaptureResult controlInjected = manager.captureBarcode(authority, "[SCAN]0012\u001B34[/SCAN]", "itemBarcode");
        assertFalse(controlInjected.accepted());
        assertEquals(BarcodeCaptureResult.RejectionReason.CONTROL_CHARACTER_INJECTION, controlInjected.rejectionReason());

        // 8. Unclaimed payment-terminal / fiscal-device and speed-based scanner heuristics rejected
        assertThrows(
            DeviceSecurityException.class,
            () -> ScaleAndBarcodeCaptureManager.rejectUnclaimedPaymentOrFiscalCapability("CARDHOLDER_PAYMENT_TERMINAL")
        );
        assertThrows(
            DeviceSecurityException.class,
            ScaleAndBarcodeCaptureManager::rejectKeystrokeTimingScannerHeuristic
        );
    }

    @Test
    void at09Key002A_B_C_and_Vis001_verifiesSwingThemeContrastScopedBindingsModalGuardsAndWedgeSuppression() {
        BrandSwingTheme theme = new BrandSwingTheme();
        assertTrue(theme.supportsRupeeGlyphInAmountFont(), "Manrope 4.505 TTF must display U+20B9 Rupee glyph");
        assertTrue(theme.manropeLicenseNotice().contains("SIL OPEN FONT LICENSE"));
        assertTrue(theme.jetBrainsMonoLicenseNotice().contains("SIL OPEN FONT LICENSE"));

        // Verify Brand v0.2.0 + Doc 46 WCAG contrast ratios using exact BigDecimal math
        BigDecimal whiteOnFold = BrandSwingTheme.computeContrastRatio(BrandSwingTheme.WHITE, BrandSwingTheme.FOLD);
        BigDecimal inkOnPaper = BrandSwingTheme.computeContrastRatio(BrandSwingTheme.INK, BrandSwingTheme.PAPER);
        BigDecimal whiteOnTeal = BrandSwingTheme.computeContrastRatio(BrandSwingTheme.WHITE, BrandSwingTheme.TEAL);
        assertTrue(whiteOnFold.compareTo(new BigDecimal("4.500")) >= 0, "Fold #006D68 + White must pass 4.5:1 AA");
        assertTrue(inkOnPaper.compareTo(new BigDecimal("13.000")) >= 0, "Ink #142F42 + Paper #F6FAF9 >= 13:1");
        assertTrue(whiteOnTeal.compareTo(new BigDecimal("4.500")) < 0, "Teal #008D87 + White < 4.5:1 (why Fold is used)");

        JButton disabledPrimary = theme.createStyledActionButton("Post [P/p]", StateTokenRole.ACTION_PRIMARY);
        assertEquals(BrandSwingTheme.FOLD, disabledPrimary.getBackground());
        assertEquals(BrandSwingTheme.WHITE, disabledPrimary.getForeground());
        disabledPrimary.setEnabled(false);
        assertEquals(BrandSwingTheme.PAPER, disabledPrimary.getBackground());
        assertEquals(BrandSwingTheme.INK, disabledPrimary.getForeground());

        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        PrintAttemptLedger ledger = new PrintAttemptLedger(tempDir.resolve("ui-ledger.enc"), key);
        DesktopCommandRegistry registry = new DesktopCommandRegistry();
        ScaleAndBarcodeCaptureManager captureManager = new ScaleAndBarcodeCaptureManager(registry);
        MoneyCraftDesktopFrame frame = new MoneyCraftDesktopFrame(theme, registry, ledger, captureManager);

        // Verify Trial Balance empty state uses exact text "No matching records" with zero icon (Doc 46 §2)
        frame.populateTrialBalanceRows(List.of());
        assertEquals("No matching records", frame.trialBalanceEmptyStateLabel().getText());
        assertNull(frame.trialBalanceEmptyStateLabel().getIcon());

        // Render headless snapshots across all 5 desktop views
        for (String view : List.of(
            MoneyCraftDesktopFrame.VIEW_GATEWAY,
            MoneyCraftDesktopFrame.VIEW_COMPANY_SELECTOR,
            MoneyCraftDesktopFrame.VIEW_VOUCHER_ENTRY,
            MoneyCraftDesktopFrame.VIEW_TRIAL_BALANCE,
            MoneyCraftDesktopFrame.VIEW_DEVICE_CONSOLE
        )) {
            frame.showView(view);
            BufferedImage snapshot = frame.renderSnapshotImage(1024, 640);
            assertNotNull(snapshot);
            assertEquals(1024, snapshot.getWidth());
        }

        // Verify scoped ANCESTOR InputMap/ActionMap navigation & Look-and-Feel refresh (AT09-KEY-002-A/C)
        JComponent shellPanel = (JComponent) frame.rootPane().getContentPane().getComponent(0);
        InputMap inputMap = shellPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        Object f3CommandId = inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0));
        assertEquals("company.open", f3CommandId);
        Action openCompanyAction = shellPanel.getActionMap().get("company.open");
        openCompanyAction.actionPerformed(new java.awt.event.ActionEvent(shellPanel, 0, "company.open"));
        assertEquals(MoneyCraftDesktopFrame.VIEW_COMPANY_SELECTOR, frame.activeViewName());

        registry.refreshAfterLookAndFeelChange();
        assertEquals("report.open", shellPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .get(KeyStroke.getKeyStroke("alt G")));

        // Verify non-Save/Cancel modal action set + held-key/text-field/IME/generation/disabled guards
        AtomicInteger checkCount = new AtomicInteger(0);
        AtomicInteger retryCount = new AtomicInteger(0);
        AtomicInteger detailsCount = new AtomicInteger(0);

        // Pre-hold 'k' BEFORE modal opens -> must NOT trigger Check status until key release + fresh press
        KeyEvent preHeldPress = new KeyEvent(
            frame.rootPane(), KeyEvent.KEY_PRESSED, 1L, 0, KeyEvent.VK_K, 'k'
        );
        registry.processModalKeyEvent(preHeldPress, frame.rootPane(), false, registry.currentGeneration());

        long modalGen = frame.openModal("request.uncertain", "Checking posting status", List.of(
            new ModalActionSpec(
                "request.uncertain", "en-GB", "request.check", "Check status", 'k', 'K',
                KeyStroke.getKeyStroke("ctrl shift K"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.refresh", "authenticated", () -> true, checkCount::incrementAndGet
            ),
            new ModalActionSpec(
                "request.uncertain", "en-GB", "request.retryOriginal", "Retry original", 'r', 'R',
                KeyStroke.getKeyStroke("ctrl shift R"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.refresh", "authenticated", () -> false, retryCount::incrementAndGet
            ),
            new ModalActionSpec(
                "request.uncertain", "en-GB", "request.details", "Details", 'd', 'D',
                KeyStroke.getKeyStroke("ctrl shift I"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.details", "authenticated", () -> true, detailsCount::incrementAndGet
            )
        ));

        // Repeat of pre-held 'k' ignored!
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_PRESSED, 2L, 0, KeyEvent.VK_K, 'k'),
            frame.rootPane(), false, modalGen
        );
        assertEquals(0, checkCount.get());

        // Release 'k', then press fresh upper-case 'K' -> dispatches once!
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_RELEASED, 3L, 0, KeyEvent.VK_K, 'k'),
            frame.rootPane(), false, modalGen
        );
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_PRESSED, 4L, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_K, 'K'),
            frame.rootPane(), false, modalGen
        );
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_RELEASED, 5L, 0, KeyEvent.VK_K, 'K'),
            frame.rootPane(), false, modalGen
        );
        assertEquals(1, checkCount.get());

        // Disabled 'R/r' reserves its pair and executes zero times
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_PRESSED, 6L, 0, KeyEvent.VK_R, 'r'),
            frame.rootPane(), false, modalGen
        );
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_RELEASED, 7L, 0, KeyEvent.VK_R, 'r'),
            frame.rootPane(), false, modalGen
        );
        assertEquals(0, retryCount.get());

        // Typing 'd' inside a JTextField or during IME composition consumes data with ZERO action dispatch
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.companySearchField(), KeyEvent.KEY_PRESSED, 8L, 0, KeyEvent.VK_D, 'd'),
            frame.companySearchField(), false, modalGen
        );
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.companySearchField(), KeyEvent.KEY_RELEASED, 9L, 0, KeyEvent.VK_D, 'd'),
            frame.companySearchField(), false, modalGen
        );
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_PRESSED, 10L, 0, KeyEvent.VK_D, 'd'),
            frame.rootPane(), true, modalGen
        );
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_RELEASED, 11L, 0, KeyEvent.VK_D, 'd'),
            frame.rootPane(), true, modalGen
        );
        assertEquals(0, detailsCount.get());

        // Stale generation dropped
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_PRESSED, 12L, 0, KeyEvent.VK_D, 'd'),
            frame.rootPane(), false, modalGen - 1
        );
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_RELEASED, 13L, 0, KeyEvent.VK_D, 'd'),
            frame.rootPane(), false, modalGen - 1
        );
        assertEquals(0, detailsCount.get());

        // Activate UNFRAMED_KEYBOARD_WEDGE scanner profile -> suppresses character shortcuts globally,
        // including in newly opened modals!
        DeviceAuthorityContext barcodeAuth = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            ACTOR_ID,
            COMPANY_ID,
            true,
            Set.of("company.read", "device.barcodeCapture")
        );
        BarcodeScannerProfile unframedWedge = new BarcodeScannerProfile(
            "generic-usb-wedge",
            true,
            "Generic",
            "USB-Wedge",
            BarcodeScannerProfile.IsolationMode.UNFRAMED_KEYBOARD_WEDGE,
            "",
            "",
            true,
            BarcodeScannerProfile.DuplicateScanPolicy.ALLOW_REPEATED_SCAN,
            4,
            32
        );
        captureManager.activateBarcodeWorkflow(unframedWedge, barcodeAuth);
        assertTrue(registry.isUnframedWedgeScannerActive());

        AtomicInteger postCount = new AtomicInteger(0);
        long wedgeModalGen = frame.openPostConfirmationModal(postCount::incrementAndGet);
        // Scanner emits 'P' and 'p' outside an input -> ZERO character shortcut execution!
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_PRESSED, 14L, 0, KeyEvent.VK_P, 'p'),
            frame.rootPane(), false, wedgeModalGen
        );
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_RELEASED, 15L, 0, KeyEvent.VK_P, 'p'),
            frame.rootPane(), false, wedgeModalGen
        );
        assertEquals(0, postCount.get());

        // Ordinary button activation remains available while wedge suppression is active
        frame.currentModalButtons().get(0).doClick();
        assertEquals(1, postCount.get());

        captureManager.deactivateBarcodeWorkflow();
        assertFalse(registry.isUnframedWedgeScannerActive());

        // Colliding modal action letters rejected before activation (AT10-KEY-001-E / AT09-KEY-002-C)
        assertThrows(InvalidModalActionSetException.class, () -> registry.pushModalContext(
            "collision.ctx",
            "en-GB",
            List.of(
                new ModalActionSpec(
                    "collision.ctx", "en-GB", "request.retryOriginal", "Retry", 'r', 'R',
                    null, StateTokenRole.ACTION_SECONDARY, null, "authenticated", () -> true, () -> { }
                ),
                new ModalActionSpec(
                    "collision.ctx", "en-GB", "editor.close", "Return", 'r', 'R',
                    null, StateTokenRole.ACTION_SECONDARY, null, "authenticated", () -> true, () -> { }
                )
            )
        ));

        // Qualified MODIFIER preference mode suppresses plain letters while firing Ctrl+Shift+P
        registry.applyPreferences(new KeyboardPreferenceState(
            1, "operator-1", ShortcutMode.MODIFIER, "en-GB", "mc-swing-default-en-gb", Map.of()
        ));
        long modGen = frame.openPostConfirmationModal(postCount::incrementAndGet);
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_PRESSED, 16L, 0, KeyEvent.VK_P, 'p'),
            frame.rootPane(), false, modGen
        );
        frame.dispatchKeyToActiveModal(
            new KeyEvent(frame.rootPane(), KeyEvent.KEY_RELEASED, 17L, 0, KeyEvent.VK_P, 'p'),
            frame.rootPane(), false, modGen
        );
        assertEquals(1, postCount.get());

        frame.dispatchKeyToActiveModal(
            new KeyEvent(
                frame.rootPane(),
                KeyEvent.KEY_PRESSED,
                18L,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_P,
                'P'
            ),
            frame.rootPane(),
            false,
            modGen
        );
        assertEquals(2, postCount.get());
    }
}
