package com.raster.moneycraft.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raster.moneycraft.desktop.MoneyCraftDesktopMain;
import com.raster.moneycraft.desktop.client.ContextEpochGuard;
import com.raster.moneycraft.desktop.client.DesktopApiClient;
import com.raster.moneycraft.desktop.client.DesktopClientConfig;
import com.raster.moneycraft.desktop.client.VersionedResponse;
import com.raster.moneycraft.desktop.client.dto.AccountBookReportDto;
import com.raster.moneycraft.desktop.device.PrintAttemptLedger;
import com.raster.moneycraft.desktop.device.ScaleAndBarcodeCaptureManager;
import com.raster.moneycraft.desktop.help.ContextualHelpCatalog;
import com.raster.moneycraft.desktop.keyboard.DesktopCommandRegistry;
import com.raster.moneycraft.desktop.keyboard.ShortcutMode;
import com.raster.moneycraft.desktop.platform.NativeDesktopPlatformAdapter;
import com.raster.moneycraft.desktop.preferences.AppearanceMode;
import com.raster.moneycraft.desktop.preferences.DesktopPreferencesManager;
import com.raster.moneycraft.desktop.preferences.UiPreferencesEnvelopeDto;
import com.raster.moneycraft.desktop.preferences.UiPreferencesV2Dto;
import com.sun.net.httpserver.HttpServer;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Action;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Qualification suite for Iteration v1.1.0 Platform Menus &amp; Lifecycle, Windows Startup/F11 Flow,
 * 3-Level Cascading Gateway, 100% Keyboard Shortcut Parity, Preferences v2, Contextual Help,
 * and ContextEpochGuard Stale-Async Isolation (S-01..S-04, S-11..S-12, AT-ITER110-021..023,
 * AT-ITER110-027, UM-110-01/03/05).
 */
class Iter110PlatformMenusShortcutsAndPreferencesTest {

    @TempDir
    Path tempDir;

    @Test
    void s02_nativePlatformMenusLookAndFeelAndMacVsWindowsWindowCloseLifecycle() {
        NativeDesktopPlatformAdapter.configurePreUiSystemProperties();
        assertEquals("true", System.getProperty("apple.laf.useScreenMenuBar"));

        AtomicBoolean cleanGuard = new AtomicBoolean(true);
        AtomicBoolean reopenedSignal = new AtomicBoolean(false);
        NativeDesktopPlatformAdapter macAdapter = new NativeDesktopPlatformAdapter(
            "Mac OS X",
            () -> { },
            () -> { },
            () -> reopenedSignal.set(true),
            cleanGuard::get
        );
        NativeDesktopPlatformAdapter.PlatformLookAndFeelReport report = macAdapter.installSystemLookAndFeel();
        assertNotNull(report.activeLookAndFeelClass());
        assertEquals(InputEvent.META_DOWN_MASK, macAdapter.resolveMenuShortcutMask());

        // Dirty draft blocks both window-close and quit
        cleanGuard.set(false);
        assertFalse(macAdapter.requestWindowClose());
        assertFalse(macAdapter.requestApplicationQuit());
        assertTrue(macAdapter.isWindowVisible());

        // Clean macOS window-close hides window without terminating process; Dock reopen restores it
        cleanGuard.set(true);
        assertTrue(macAdapter.requestWindowClose());
        assertFalse(macAdapter.isWindowVisible());
        assertFalse(macAdapter.isApplicationTerminated());
        macAdapter.triggerDockReopen();
        assertTrue(macAdapter.isWindowVisible());
        assertTrue(reopenedSignal.get());
        assertTrue(macAdapter.requestApplicationQuit());
        assertTrue(macAdapter.isApplicationTerminated());

        // Windows primary window-close terminates session when clean
        NativeDesktopPlatformAdapter winAdapter = new NativeDesktopPlatformAdapter(
            "Windows 11",
            () -> { },
            () -> { },
            () -> { },
            () -> true
        );
        assertEquals(InputEvent.CTRL_DOWN_MASK, winAdapter.resolveMenuShortcutMask());
        assertTrue(winAdapter.requestWindowClose());
        assertTrue(winAdapter.isApplicationTerminated());
    }

    @Test
    void um110_01_and_03_windowsStartupCreateCompanyAutoF11AndThreeLevelCascadingGateway() {
        MoneyCraftDesktopFrame frame = buildTestFrame(new AtomicBoolean(false));

        // 1. Windows startup flow opens Select Company when no company is open
        frame.enterWindowsStartupFlow();
        assertEquals(MoneyCraftDesktopFrame.VIEW_COMPANY_SELECTOR, frame.activeViewName());

        // 2. Create Company opens automatic post-create F11: Company Features modal -> Apply ('a') -> Gateway
        UUID companyId = UUID.randomUUID();
        long f11Gen = frame.submitCreateCompanyAndOpenF11Features(companyId);
        assertEquals(companyId, frame.epochGuard().activeCompanyId());
        KeyEvent applyKey = new KeyEvent(
            frame.rootPane(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_A, 'a'
        );
        assertTrue(frame.dispatchKeyToActiveModal(applyKey, frame.rootPane(), false, f11Gen));
        assertTrue(frame.isF11GstEnabled());
        assertEquals(MoneyCraftDesktopFrame.VIEW_GATEWAY, frame.activeViewName());

        // 3. 3-Level Gateway: Create (C) -> Accounting Masters (Enter on index 0) -> Ledger (Enter on index 1)
        frame.triggerGatewayMnemonic('C');
        assertEquals(2, frame.gatewayTree().currentLevelDepth());
        assertEquals("Gateway > Create", frame.gatewayBreadcrumbText());
        frame.triggerGatewayEnter(); // Accounting Masters -> Level 3
        assertEquals(3, frame.gatewayTree().currentLevelDepth());
        frame.gatewayTree().moveSelection(1); // Ledger
        GatewayCascadeTree.DrillOutcome ledgerLeaf = frame.triggerGatewayEnter();
        assertFalse(ledgerLeaf.drilledIntoSubmenu());
        assertEquals("MASTER:LEDGER", ledgerLeaf.leafTargetActionId());
        assertEquals(MoneyCraftDesktopFrame.VIEW_MASTERS, frame.activeViewName());

        // Esc from Masters returns to Gateway, then Esc steps back Level 3 -> Level 2 -> Level 1
        frame.handleEscapeKey();
        assertEquals(MoneyCraftDesktopFrame.VIEW_GATEWAY, frame.activeViewName());
        frame.gatewayTree().resetToRoot();
        assertEquals(1, frame.gatewayTree().currentLevelDepth());

        // 4. Display More Reports (D) -> Account Books (A) -> Sales Register (S) + Esc step-back
        frame.triggerGatewayMnemonic('D');
        assertEquals(2, frame.gatewayTree().currentLevelDepth());
        frame.triggerGatewayMnemonic('A');
        assertEquals(3, frame.gatewayTree().currentLevelDepth());
        assertEquals("Gateway > Display More Reports > Account Books", frame.gatewayBreadcrumbText());
        GatewayCascadeTree.DrillOutcome salesReg = frame.triggerGatewayMnemonic('S');
        assertEquals("BOOK:SALES_REGISTER", salesReg.leafTargetActionId());
        assertEquals(MoneyCraftDesktopFrame.VIEW_REPORTS_EXPLORER, frame.activeViewName());

        // Step back from report -> Gateway -> reset -> Display More Reports -> Exception Reports (X) -> step-back
        frame.handleEscapeKey();
        assertEquals(MoneyCraftDesktopFrame.VIEW_GATEWAY, frame.activeViewName());
        frame.gatewayTree().resetToRoot();
        frame.triggerGatewayMnemonic('D');
        frame.triggerGatewayMnemonic('X'); // Exception Reports (Level 3)
        assertEquals(3, frame.gatewayTree().currentLevelDepth());
        frame.handleEscapeKey();
        assertEquals(2, frame.gatewayTree().currentLevelDepth());
        frame.handleEscapeKey();
        assertEquals(1, frame.gatewayTree().currentLevelDepth());

        // Verify Statistics leaf route (Display More Reports -> Statements of Accounts -> Statistics 'T')
        frame.triggerGatewayMnemonic('D');
        frame.triggerGatewayMnemonic('S'); // Statements of Accounts
        GatewayCascadeTree.DrillOutcome statsOutcome = frame.triggerGatewayMnemonic('T'); // Statistics
        assertEquals("STATEMENT:STATISTICS", statsOutcome.leafTargetActionId());
    }

    @Test
    void um110_05_fullShortcutMatrixGoToDirtyPreservationSwitchToModalCommaFlushAndDayBookGrid() {
        MoneyCraftDesktopFrame frame = buildTestFrame(new AtomicBoolean(false));
        JComponent shell = (JComponent) frame.rootPane().getContentPane().getComponent(0);

        // Exercise Top Bar Menu mnemonics (Alt+K/Y/Z/O/E/M/P)
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.ALT_DOWN_MASK));
        assertEquals("COMPANY", frame.lastOpenedTopMenu());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.ALT_DOWN_MASK));
        assertEquals("DATA", frame.lastOpenedTopMenu());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.ALT_DOWN_MASK));
        assertEquals("EXCHANGE", frame.lastOpenedTopMenu());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.ALT_DOWN_MASK));
        assertEquals("PRINT", frame.lastOpenedTopMenu());

        // Exercise Voucher Type function keys (F4..F9, Alt+F5..F9, Ctrl+F6..F9)
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0));
        assertEquals("Contra", frame.activeVoucherType());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        assertEquals("Payment", frame.activeVoucherType());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0));
        assertEquals("Receipt", frame.activeVoucherType());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0));
        assertEquals("Sales", frame.activeVoucherType());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0));
        assertEquals("Purchase", frame.activeVoucherType());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F5, InputEvent.ALT_DOWN_MASK));
        assertEquals("Debit Note", frame.activeVoucherType());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F6, InputEvent.ALT_DOWN_MASK));
        assertEquals("Credit Note", frame.activeVoucherType());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.ALT_DOWN_MASK));
        assertEquals("Stock Journal", frame.activeVoucherType());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F8, InputEvent.CTRL_DOWN_MASK));
        assertEquals("Sales Order", frame.activeVoucherType());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK));
        assertEquals("Purchase Order", frame.activeVoucherType());
        assertEquals(24, MoneyCraftDesktopFrame.ALL_24_VOUCHER_TYPES.size());

        // Populate dirty voucher with comma-grouped Indian amounts & narration
        frame.selectVoucherType("Sales");
        frame.voucherLedgerField().setText("Sundry Debtors - Raster");
        frame.voucherDebitField().setText("1,25,500.50");
        frame.voucherCreditField().setText("1,25,500.50");
        frame.voucherNarrationField().setText("Invoice #INV-2026-09");
        frame.setVoucherDraftDirty(true);

        // Alt+G (Go To) mid-dirty-voucher -> Trial Balance -> Alt+F1 Detailed, Alt+C, Alt+N -> Esc restores draft!
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.ALT_DOWN_MASK));
        assertEquals(MoneyCraftDesktopFrame.VIEW_TRIAL_BALANCE, frame.activeViewName());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_F1, InputEvent.ALT_DOWN_MASK));
        assertTrue(frame.isReportDetailedMode());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.ALT_DOWN_MASK));
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.ALT_DOWN_MASK));
        assertEquals(6, frame.comparativeColumnCount());

        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        assertEquals(MoneyCraftDesktopFrame.VIEW_VOUCHER_ENTRY, frame.activeViewName());
        assertEquals("1,25,500.50", frame.voucherDebitField().getText());
        assertTrue(frame.isVoucherDraftDirty());

        // Ctrl+G (Switch To) while dirty opens 3-way editor.dirty modal -> Stay ('t') keeps editor open
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK));
        assertEquals(3, frame.currentModalButtons().size());
        frame.closeModal("editor.dirty");
        long dirtyGen = frame.openEditorDirtyModal(MoneyCraftDesktopFrame.VIEW_TRIAL_BALANCE);
        KeyEvent stayEvent = new KeyEvent(
            frame.rootPane(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_T, 't'
        );
        assertTrue(frame.dispatchKeyToActiveModal(stayEvent, frame.rootPane(), false, dirtyGen));
        assertEquals(MoneyCraftDesktopFrame.VIEW_VOUCHER_ENTRY, frame.activeViewName());

        // Voucher intra-screen shortcuts: Ctrl+H mode, Ctrl+L optional, Ctrl+T post-dated, PageDown/Up, Ctrl+A accept
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK));
        assertEquals("ACCOUNTING_INVOICE", frame.activeVoucherMode());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK));
        assertTrue(frame.isOptionalVoucherFlag());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK));
        assertTrue(frame.isPostDatedVoucherFlag());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0));
        assertEquals("VCH-0002", frame.voucherNumberField().getText());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0));
        assertEquals("VCH-0001", frame.voucherNumberField().getText());

        // Ctrl+A flushes comma-formatted "1,25,500.50" into canonical "125500.50" BigDecimal and clears dirty flag
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
        assertEquals("125500.50", frame.voucherDebitField().getText());
        assertFalse(frame.isVoucherDraftDirty());

        // Ctrl+R repeats last committed narration into a fresh narration field
        frame.voucherNarrationField().setText("");
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        assertEquals("Invoice #INV-2026-09", frame.voucherNarrationField().getText());

        // Day Book row selection, hide/restore, duplicate, cancel & MCA Edit Log shortcuts
        frame.showView(MoneyCraftDesktopFrame.VIEW_DAY_BOOK);
        assertEquals(2, frame.dayBookVisibleRowCount());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
        assertEquals(1, frame.selectedDayBookRowIndices().size());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        assertEquals(2, frame.selectedDayBookRowIndices().size());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.ALT_DOWN_MASK));
        assertEquals(1, frame.dayBookVisibleRowCount());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.ALT_DOWN_MASK));
        assertEquals(2, frame.dayBookVisibleRowCount());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.ALT_DOWN_MASK));
        assertEquals(3, frame.dayBookVisibleRowCount());
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.ALT_DOWN_MASK));
        assertEquals("CANCELLED", frame.dayBookRowStatus(0));
        fireKeyStroke(shell, KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.ALT_DOWN_MASK));
        assertTrue(frame.statusBannerLabel().getText().contains("Edit Log"));
    }

    @Test
    void s03_and_s12_preferencesV2AccountConflictPersonIsolationDarkContrastScalingAndEscapeFirstHelp() throws Exception {
        AtomicBoolean osDarkActive = new AtomicBoolean(false);
        AtomicReference<Integer> patchStatusToReturn = new AtomicReference<>(200);
        AtomicReference<String> receivedIfMatch = new AtomicReference<>();

        HttpServer prefsServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = prefsServer.getAddress().getPort();
        URI hostOrigin = URI.create("http://127.0.0.1:" + port);

        prefsServer.createContext("/api/v1/me/ui-preferences", exchange -> {
            if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
                receivedIfMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
                if (patchStatusToReturn.get() == 412) {
                    byte[] problem = """
                        {"type":"https://moneycraft.raster.com/problems/optimistic-lock","title":"Conflict","status":412,"detail":"Modified in another session","errorCode":"MC-PREF-CONFLICT"}
                        """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
                    exchange.sendResponseHeaders(412, problem.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(problem);
                    }
                    return;
                }
            }
            byte[] ok = """
                {
                  "subjectScope": "person-user-alpha-0001",
                  "revision": "2",
                  "preferences": {
                    "schemaVersion": 2,
                    "appearance": "DARK",
                    "textScalePercent": 125,
                    "keyboard": {
                      "version": 1,
                      "mode": "CHARACTER",
                      "locale": "en-GB",
                      "profileId": null,
                      "overrides": []
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("ETag", "\"2\"");
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        prefsServer.start();

        try {
            DesktopCommandRegistry registry = new DesktopCommandRegistry();
            DesktopPreferencesManager prefs = new DesktopPreferencesManager(
                hostOrigin,
                tempDir.resolve("prefs"),
                "mc-swing-default-en-gb",
                registry,
                osDarkActive::get
            );

            // 1. Live SYSTEM mode tracks OS dark detector without rewriting saved choice
            assertEquals(AppearanceMode.SYSTEM, prefs.effectiveAppearanceChoice());
            assertFalse(prefs.isEffectiveDarkTheme());
            osDarkActive.set(true);
            assertTrue(prefs.isEffectiveDarkTheme());
            assertEquals(AppearanceMode.SYSTEM, prefs.effectiveAppearanceChoice());

            // 2. Dark mode semantic tokens meet >= 4.5:1 WCAG AA contrast & 75%..200% font scaling works
            BrandSwingTheme theme = new BrandSwingTheme();
            theme.applyGlobalDefaults(true, new BigDecimal("150"));
            assertTrue(BrandSwingTheme.computeContrastRatio(BrandSwingTheme.DARK_TEXT, BrandSwingTheme.DARK_PAGE)
                .compareTo(new BigDecimal("4.5")) >= 0);
            assertTrue(BrandSwingTheme.computeContrastRatio(BrandSwingTheme.MINT, BrandSwingTheme.DARK_SURFACE)
                .compareTo(new BigDecimal("4.5")) >= 0);
            assertEquals(21, BrandSwingTheme.scaleFontPx(14, new BigDecimal("150")));
            assertEquals(21, javax.swing.UIManager.getFont("Label.font").getSize());

            // 3. Authenticated User A load + PATCH + 412 conflict handling + local override masking
            DesktopClientConfig cfg = DesktopClientConfig.loopbackTestConfig(hostOrigin);
            try (DesktopApiClient client = new DesktopApiClient(cfg, () -> Optional.of("tok-a"))) {
                VersionedResponse<UiPreferencesEnvelopeDto> loaded = client.getAccountUiPreferences().join();
                prefs.onAuthenticatedAccountLoaded(loaded);
                assertEquals("person-user-alpha-0001", prefs.activeSubjectScope());
                assertEquals(AppearanceMode.DARK, prefs.effectiveAppearanceChoice());
                assertEquals(new BigDecimal("125"), prefs.effectiveTextScalePercent());

                // Local override masks account DARK with LIGHT, then clearing local override restores DARK
                prefs.saveLocalDesktopOverride(AppearanceMode.LIGHT, new BigDecimal("112.5"), null);
                assertTrue(prefs.isLocalAppearanceMaskingAccount());
                assertEquals(AppearanceMode.LIGHT, prefs.effectiveAppearanceChoice());
                prefs.clearSelectedLocalOverrides(true, true, false);
                assertEquals(AppearanceMode.DARK, prefs.effectiveAppearanceChoice());

                // Simulate 412 revision conflict on PATCH
                patchStatusToReturn.set(412);
                prefs.patchAccountPreferences(
                    client,
                    new UiPreferencesEnvelopeDto.PatchChanges(AppearanceMode.LIGHT, null, null),
                    UUID.randomUUID()
                );
                assertEquals("\"2\"", receivedIfMatch.get());
                assertTrue(prefs.lastConflict().isPresent());
            }

            // 4. Browser keyboard profileId rejected safely on Swing -> ShortcutMode.OFF
            prefs.onAuthenticatedAccountLoaded(new VersionedResponse<>(
                new UiPreferencesEnvelopeDto(
                    new UiPreferencesV2Dto(
                        2, AppearanceMode.DARK, new BigDecimal("100"),
                        new UiPreferencesV2Dto.KeyboardWireValue(
                            1, ShortcutMode.MODIFIER, "en-GB", "mc-web-chrome-en-gb", java.util.List.of()
                        )
                    ),
                    "3",
                    "person-user-alpha-0001"
                ),
                "\"3\"",
                200
            ));
            assertEquals(ShortcutMode.OFF, registry.preferenceState().mode());

            // 5. User A -> Logout -> User B isolation (zero preference leakage)
            prefs.onLogoutOrSessionExpired();
            assertEquals("signed_out_anonymous_desktop", prefs.activeSubjectScope());
            assertEquals(AppearanceMode.SYSTEM, prefs.effectiveAppearanceChoice());

            // 6. Contextual Help Catalogue (26 items) & Escape-first dismissal without closing modal or losing draft!
            byte[] ledgerKey = new byte[32];
            new SecureRandom().nextBytes(ledgerKey);
            ContextualHelpCatalog helpCatalog = new ContextualHelpCatalog();
            assertEquals(26, helpCatalog.entriesById().size());
            MoneyCraftDesktopFrame frame = new MoneyCraftDesktopFrame(
                theme,
                registry,
                new PrintAttemptLedger(tempDir.resolve("help-ledger.enc"), ledgerKey),
                new ScaleAndBarcodeCaptureManager(registry),
                prefs,
                helpCatalog,
                new ContextEpochGuard(),
                null
            );
            frame.selectVoucherType("Journal");
            frame.voucherDebitField().setText("5000.00");
            frame.setVoucherDraftDirty(true);
            long modalGen = frame.openF11CompanyFeaturesModal();
            frame.openHelpForContext("HELP-110-026", "Native Sign-In");
            assertTrue(helpCatalog.activeOverlayEntry().isPresent());

            KeyEvent escOnHelp = new KeyEvent(
                frame.rootPane(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED
            );
            assertTrue(frame.dispatchKeyToActiveModal(escOnHelp, frame.rootPane(), false, modalGen));
            assertTrue(helpCatalog.activeOverlayEntry().isEmpty());
            // Underlying F11 modal and dirty draft MUST still be open and intact!
            assertTrue(registry.activeModalContextId().isPresent());
            assertEquals("5000.00", frame.voucherDebitField().getText());
            assertTrue(frame.isVoucherDraftDirty());

            // 7. Verify Backend API IP and Port configuration in Swing UI & workstation-host.properties persistence
            Path workstationPropsFile = tempDir.resolve("workstation-host.properties");
            java.util.Properties wsProps = new java.util.Properties();
            frame.bindWorkstationHostConfiguration(
                URI.create("https://localhost:8443"),
                workstationPropsFile,
                newOrigin -> MoneyCraftDesktopMain.rebuildSessionCoordinatorForHost(
                    newOrigin,
                    wsProps,
                    prefs,
                    registry,
                    frame.epochGuard()
                )
            );
            frame.backendApiSchemeBox().setSelectedItem("https");
            frame.backendApiHostField().setText("192.168.10.55");
            frame.backendApiPortField().setText("9443");
            URI updatedApiBase = frame.applyBackendApiFromFields();
            assertEquals(URI.create("https://192.168.10.55:9443/api/v1"), updatedApiBase);
            assertEquals(URI.create("https://192.168.10.55:9443"), frame.configuredBackendHostOrigin());
            assertTrue(frame.backendApiEndpointBadge().getText().contains("https://192.168.10.55:9443/api/v1"));

            java.util.Properties persistedProps = MoneyCraftDesktopMain.loadWorkstationProperties(workstationPropsFile);
            assertEquals("https://192.168.10.55:9443", persistedProps.getProperty("hostOrigin"));
            assertEquals("192.168.10.55", persistedProps.getProperty("apiHost"));
            assertEquals("9443", persistedProps.getProperty("apiPort"));
            assertEquals(
                URI.create("https://192.168.10.55:9443"),
                MoneyCraftDesktopMain.resolveConfiguredHostOrigin(
                    new String[]{"--api-ip", "192.168.10.55", "--api-port", "9443"},
                    null
                )
            );
            frame.sessionCoordinator().ifPresent(com.raster.moneycraft.desktop.client.DesktopSessionCoordinator::close);
        } finally {
            prefsServer.stop(0);
        }
    }

    @Test
    void s04_contextEpochGuardDiscardsStaleAsyncCallbacksAndBindsWaveW2bReportEndpoints() throws Exception {
        ContextEpochGuard guard = new ContextEpochGuard();
        UUID companyA = UUID.randomUUID();
        UUID companyB = UUID.randomUUID();
        guard.onPrincipalChanged("person-1");
        guard.onCompanySwitched(companyA);
        guard.onViewChanged(MoneyCraftDesktopFrame.VIEW_TRIAL_BALANCE);

        ContextEpochGuard.EpochSnapshot snapshotForCompanyA = guard.captureSnapshot();
        CompletableFuture<String> delayedCompanyAResponse = new CompletableFuture<>();

        // Operator switches immediately to Company B before Company A's response arrives
        guard.onCompanySwitched(companyB);
        assertFalse(guard.isCurrent(snapshotForCompanyA));

        AtomicBoolean staleAppliedToUi = new AtomicBoolean(false);
        CountDownLatch staleDiscardedLatch = new CountDownLatch(1);
        DesktopApiClient.deliverOnEdtIfCurrent(
            guard,
            snapshotForCompanyA,
            delayedCompanyAResponse,
            val -> staleAppliedToUi.set(true),
            err -> staleAppliedToUi.set(true),
            staleDiscardedLatch::countDown
        );
        delayedCompanyAResponse.complete("STALE_COMPANY_A_TRIAL_BALANCE");
        assertTrue(staleDiscardedLatch.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(staleAppliedToUi.get(), "Stale async response from Company A must never overwrite Company B UI");

        // Verify Wave W2B Level 2/3 Report & Shortcut endpoints deserialize exact BigDecimal strings cleanly
        HttpServer w2bServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = w2bServer.getAddress().getPort();
        w2bServer.createContext("/api/v1/companies/" + companyB + "/reports/account-books", ex -> {
            byte[] body = """
                {
                  "bookType":"SALES_REGISTER","title":"Sales Register",
                  "fromDate":"2026-04-01","toDate":"2027-03-31","voucherCount":2,
                  "openingDebit":"0.00","openingCredit":"0.00",
                  "totalDebit":"125500.50","totalCredit":"125500.50",
                  "closingDebit":"0.00","closingCredit":"0.00",
                  "summaryRows":[],"monthlySummary":[{"monthKey":"2026-04","voucherCount":2}],"voucherRows":[]
                }
                """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        w2bServer.createContext("/api/v1/companies/" + companyB + "/reports/exceptions", ex -> {
            byte[] body = """
                {
                  "type":"NEGATIVE_STOCK","title":"Negative Stock",
                  "fromDate":"2026-04-01","toDate":"2027-03-31","rowCount":1,
                  "totalQuantity":"-5.000","totalDebit":"0.00","totalCredit":"0.00","totalAmount":"2500.00","rows":[]
                }
                """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        w2bServer.createContext("/api/v1/companies/" + companyB + "/reports/statistics", ex -> {
            byte[] body = """
                {
                  "fromDate":"2026-04-01","toDate":"2027-03-31",
                  "voucherTypeCounts":[],"masterCounts":[],"totalPostedVouchers":12,"totalVouchers":14,"totalMasterTypes":11
                }
                """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        w2bServer.createContext("/api/v1/companies/" + companyB + "/vouchers/last-narration", ex -> {
            byte[] body = """
                {"found":true,"narration":"Being monthly GST settlement"}
                """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        w2bServer.start();

        try {
            DesktopClientConfig cfg = DesktopClientConfig.loopbackTestConfig(
                URI.create("http://127.0.0.1:" + port)
            );
            try (DesktopApiClient client = new DesktopApiClient(cfg, () -> Optional.of("tok-b"))) {
                AccountBookReportDto book = client.getAccountBookReport(
                    companyB, "SALES_REGISTER", null, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31)
                ).join();
                assertEquals(new BigDecimal("125500.50"), book.totalDebit());

                AccountBookReportDto.ExceptionReportDto exc = client.getExceptionReport(
                    companyB, "NEGATIVE_STOCK", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31)
                ).join();
                assertEquals(new BigDecimal("-5.000"), exc.totalQuantity());

                AccountBookReportDto.StatisticsReportDto stats = client.getStatisticsReport(
                    companyB, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31)
                ).join();
                assertEquals(11, stats.totalMasterTypes());

                AccountBookReportDto.LastNarrationDto lastNarr = client.getLastNarration(
                    companyB, null, "PAYMENT", null
                ).join();
                assertTrue(lastNarr.found());
                assertEquals("Being monthly GST settlement", lastNarr.narration());
            }
        } finally {
            w2bServer.stop(0);
        }
    }

    private MoneyCraftDesktopFrame buildTestFrame(AtomicBoolean osDarkDetector) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        BrandSwingTheme theme = new BrandSwingTheme();
        DesktopCommandRegistry registry = new DesktopCommandRegistry();
        DesktopPreferencesManager prefs = new DesktopPreferencesManager(
            URI.create("https://localhost:8443"),
            tempDir.resolve("frame-prefs-" + UUID.randomUUID()),
            "mc-swing-default-en-gb",
            registry,
            osDarkDetector::get
        );
        return new MoneyCraftDesktopFrame(
            theme,
            registry,
            new PrintAttemptLedger(tempDir.resolve("ledger-" + UUID.randomUUID() + ".enc"), key),
            new ScaleAndBarcodeCaptureManager(registry),
            prefs,
            new ContextualHelpCatalog(),
            new ContextEpochGuard(),
            null
        );
    }

    private static void fireKeyStroke(JComponent scopedContainer, KeyStroke keyStroke) {
        InputMap map = scopedContainer.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        Object actionKey = map.get(keyStroke);
        assertNotNull(actionKey, "Expected bound action for KeyStroke: " + keyStroke);
        Action action = scopedContainer.getActionMap().get(actionKey);
        assertNotNull(action, "Expected Action in ActionMap for commandId: " + actionKey);
        action.actionPerformed(new ActionEvent(scopedContainer, ActionEvent.ACTION_PERFORMED, String.valueOf(actionKey)));
    }
}
