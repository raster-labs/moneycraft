package com.raster.moneycraft.desktop.platform;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.desktop.AppReopenedListener;
import java.awt.event.InputEvent;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

/**
 * OS-native Swing integration adapter for macOS, Windows, and Linux desktops
 * (Swing Doc 02 §3, Swing Doc 03, ITER110-022..023, S-02).
 *
 * <p>Configures {@code apple.laf.useScreenMenuBar} before Swing initialization, applies
 * {@link UIManager#getSystemLookAndFeelClassName()} while recording honest fallback metadata,
 * wires supported {@link Desktop} lifecycle handlers (About, Preferences, Quit, Dock reopen)
 * without private {@code com.apple.*} APIs, and resolves platform menu shortcut masks.
 */
public final class NativeDesktopPlatformAdapter {

    private final String osName;
    private final boolean macOs;
    private final boolean windowsOs;
    private final Runnable onAboutRequested;
    private final Runnable onPreferencesRequested;
    private final Runnable onWindowReopenRequested;
    private final BooleanSupplier canSafelyQuitOrCloseSupplier;

    private volatile boolean windowVisible = true;
    private volatile boolean applicationTerminated;
    private volatile int aboutOpenCount;
    private volatile int preferencesOpenCount;
    private volatile int dockReopenCount;

    public NativeDesktopPlatformAdapter(
        Runnable onAboutRequested,
        Runnable onPreferencesRequested,
        Runnable onWindowReopenRequested,
        BooleanSupplier canSafelyQuitOrCloseSupplier
    ) {
        this(
            System.getProperty("os.name", "Generic"),
            onAboutRequested,
            onPreferencesRequested,
            onWindowReopenRequested,
            canSafelyQuitOrCloseSupplier
        );
    }

    public NativeDesktopPlatformAdapter(
        String osName,
        Runnable onAboutRequested,
        Runnable onPreferencesRequested,
        Runnable onWindowReopenRequested,
        BooleanSupplier canSafelyQuitOrCloseSupplier
    ) {
        this.osName = Objects.requireNonNull(osName, "osName must not be null");
        String lower = osName.toLowerCase(Locale.ROOT);
        this.macOs = lower.contains("mac") || lower.contains("darwin");
        this.windowsOs = lower.contains("win");
        this.onAboutRequested = Objects.requireNonNull(onAboutRequested, "onAboutRequested must not be null");
        this.onPreferencesRequested = Objects.requireNonNull(onPreferencesRequested, "onPreferencesRequested must not be null");
        this.onWindowReopenRequested = Objects.requireNonNull(onWindowReopenRequested, "onWindowReopenRequested must not be null");
        this.canSafelyQuitOrCloseSupplier = Objects.requireNonNull(
            canSafelyQuitOrCloseSupplier,
            "canSafelyQuitOrCloseSupplier must not be null"
        );
    }

    /**
     * Must execute before any Swing component or AWT Toolkit initialization so macOS Aqua
     * moves the main JMenuBar into the global screen menu bar and enables High-DPI scaling.
     */
    public static void configurePreUiSystemProperties() {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "MoneyCraft");
        System.setProperty("apple.awt.application.appearance", "system");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
    }

    /**
     * Applies the OS system look-and-feel and returns honest runtime qualification metadata.
     */
    public PlatformLookAndFeelReport installSystemLookAndFeel() {
        configurePreUiSystemProperties();
        String requestedClass = UIManager.getSystemLookAndFeelClassName();
        String crossPlatformClass = UIManager.getCrossPlatformLookAndFeelClassName();
        boolean appliedSucceeded = false;
        try {
            UIManager.setLookAndFeel(requestedClass);
            appliedSucceeded = true;
        } catch (Exception ignored) {
            // Retain existing look-and-feel and record fallback truthfully.
        }
        String activeClass = UIManager.getLookAndFeel() != null
            ? UIManager.getLookAndFeel().getClass().getName()
            : "UNKNOWN";
        String activeId = UIManager.getLookAndFeel() != null
            ? UIManager.getLookAndFeel().getID()
            : "UNKNOWN";
        boolean crossPlatformFallback = activeClass.equals(crossPlatformClass);
        return new PlatformLookAndFeelReport(
            osName,
            System.getProperty("os.version", "unknown"),
            System.getProperty("os.arch", "unknown"),
            System.getProperty("java.runtime.version", System.getProperty("java.version", "25")),
            requestedClass,
            activeClass,
            activeId,
            appliedSucceeded && !crossPlatformFallback,
            crossPlatformFallback,
            resolveMenuShortcutMask()
        );
    }

    /**
     * Registers supported {@link java.awt.Desktop} handlers when running on a non-headless desktop.
     */
    public DesktopHandlerCapabilities installNativeDesktopHandlers() {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            return new DesktopHandlerCapabilities(false, false, false, false);
        }
        Desktop desktop = Desktop.getDesktop();
        boolean aboutSupported = desktop.isSupported(Desktop.Action.APP_ABOUT);
        boolean prefsSupported = desktop.isSupported(Desktop.Action.APP_PREFERENCES);
        boolean quitSupported = desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER);
        boolean reopenSupported = desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED);

        if (aboutSupported) {
            desktop.setAboutHandler(event -> triggerAbout());
        }
        if (prefsSupported) {
            desktop.setPreferencesHandler(event -> triggerPreferences());
        }
        if (quitSupported) {
            desktop.setQuitHandler((event, response) -> {
                if (requestApplicationQuit()) {
                    response.performQuit();
                } else {
                    response.cancelQuit();
                }
            });
        }
        if (reopenSupported) {
            AppReopenedListener listener = event -> triggerDockReopen();
            desktop.addAppEventListener(listener);
        }
        return new DesktopHandlerCapabilities(aboutSupported, prefsSupported, quitSupported, reopenSupported);
    }

    /**
     * Resolves the platform menu accelerator modifier using {@link Toolkit#getMenuShortcutKeyMaskEx()}
     * when a display toolkit is present, or deterministic OS rules in headless tests.
     */
    public int resolveMenuShortcutMask() {
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
            } catch (Exception ignored) {
                // Fall back to OS-specific mask below.
            }
        }
        return macOs ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    public KeyStroke platformMenuKeyStroke(int keyCode, int extraModifiers) {
        return KeyStroke.getKeyStroke(keyCode, resolveMenuShortcutMask() | extraModifiers);
    }

    public boolean isMacOs() {
        return macOs;
    }

    public boolean isWindowsOs() {
        return windowsOs;
    }

    public boolean isWindowVisible() {
        return windowVisible;
    }

    public boolean isApplicationTerminated() {
        return applicationTerminated;
    }

    public int aboutOpenCount() {
        return aboutOpenCount;
    }

    public int preferencesOpenCount() {
        return preferencesOpenCount;
    }

    public int dockReopenCount() {
        return dockReopenCount;
    }

    public void triggerAbout() {
        aboutOpenCount++;
        onAboutRequested.run();
    }

    public void triggerPreferences() {
        preferencesOpenCount++;
        onPreferencesRequested.run();
    }

    public void triggerDockReopen() {
        dockReopenCount++;
        if (!windowVisible && !applicationTerminated) {
            windowVisible = true;
            onWindowReopenRequested.run();
        }
    }

    /**
     * Handles closing a window: on macOS, closing the window leaves the application alive with
     * no-active-window menu state so Dock reopen restores it; on Windows/Linux, closing the primary
     * window terminates the session once dirty/uncertain guards pass (S-02, AT-ITER110-023).
     */
    public boolean requestWindowClose() {
        if (!canSafelyQuitOrCloseSupplier.getAsBoolean()) {
            return false;
        }
        this.windowVisible = false;
        if (!macOs) {
            this.applicationTerminated = true;
        }
        return true;
    }

    /**
     * Handles explicit application quit (Cmd+Q / Ctrl+Q / File -> Exit).
     */
    public boolean requestApplicationQuit() {
        if (!canSafelyQuitOrCloseSupplier.getAsBoolean()) {
            return false;
        }
        this.windowVisible = false;
        this.applicationTerminated = true;
        return true;
    }

    public record PlatformLookAndFeelReport(
        String osName,
        String osVersion,
        String architecture,
        String javaRuntimeVersion,
        String requestedLookAndFeelClass,
        String activeLookAndFeelClass,
        String activeLookAndFeelId,
        boolean nativeLookAndFeelActive,
        boolean fellBackToCrossPlatform,
        int menuShortcutMaskEx
    ) {
    }

    public record DesktopHandlerCapabilities(
        boolean aboutSupported,
        boolean preferencesSupported,
        boolean quitHandlerSupported,
        boolean dockReopenSupported
    ) {
    }
}
