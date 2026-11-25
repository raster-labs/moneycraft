package com.raster.moneycraft.desktop.auth;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * Launches external system browser for RFC 8252 native OAuth 2.0 / OIDC sign-in (SWG-002).
 * Never embeds an in-application web view or collects directory passwords in Swing.
 */
@FunctionalInterface
public interface SystemBrowserLauncher {

    void openExternalBrowser(URI authorizationUri);

    static SystemBrowserLauncher systemDefault() {
        return authorizationUri -> {
            Objects.requireNonNull(authorizationUri, "authorizationUri must not be null");
            if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
                throw new NativeAuthSecurityException(
                    "MC-BROWSER-UNAVAILABLE",
                    "External OS browser launch is unavailable in a headless desktop environment."
                );
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                throw new NativeAuthSecurityException(
                    "MC-BROWSER-ACTION-UNSUPPORTED",
                    "Host OS Desktop does not support external BROWSE action required by RFC 8252."
                );
            }
            try {
                desktop.browse(authorizationUri);
            } catch (IOException ex) {
                throw new NativeAuthSecurityException(
                    "MC-BROWSER-LAUNCH-FAILED",
                    "Failed to open external system browser for PKCE sign-in.",
                    ex
                );
            }
        };
    }
}
