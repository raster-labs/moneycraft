package com.raster.moneycraft.desktop;

import com.raster.moneycraft.desktop.auth.AdmittedNativeIssuerProfile;
import com.raster.moneycraft.desktop.auth.HostNativeIssuerFactory;
import com.raster.moneycraft.desktop.auth.InMemoryAccessTokenHolder;
import com.raster.moneycraft.desktop.auth.NativePkceAuthClient;
import com.raster.moneycraft.desktop.auth.PlatformSecretStoreCredentialVault;
import com.raster.moneycraft.desktop.auth.SystemBrowserLauncher;
import com.raster.moneycraft.desktop.client.ContextEpochGuard;
import com.raster.moneycraft.desktop.client.DesktopApiClient;
import com.raster.moneycraft.desktop.client.DesktopClientConfig;
import com.raster.moneycraft.desktop.client.DesktopSessionCoordinator;
import com.raster.moneycraft.desktop.device.PrintAttemptLedger;
import com.raster.moneycraft.desktop.device.ScaleAndBarcodeCaptureManager;
import com.raster.moneycraft.desktop.help.ContextualHelpCatalog;
import com.raster.moneycraft.desktop.keyboard.DesktopCommandRegistry;
import com.raster.moneycraft.desktop.platform.NativeDesktopPlatformAdapter;
import com.raster.moneycraft.desktop.preferences.DesktopPreferencesManager;
import com.raster.moneycraft.desktop.security.EnrolledHostTrustManager;
import com.raster.moneycraft.desktop.ui.BrandSwingTheme;
import com.raster.moneycraft.desktop.ui.MoneyCraftDesktopFrame;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import javax.swing.SwingUtilities;

/**
 * Standalone entrypoint for the MoneyCraft 1.0.0 Personal Swing Desktop Client.
 * Configures OS screen-menu/high-DPI properties before Swing initialization and packages
 * independently of both server WAR distributions (SWG-002, DEP-003, DEP-004, ITER110-021..026).
 */
public final class MoneyCraftDesktopMain {

    private MoneyCraftDesktopMain() {
    }

    public static void main(String[] args) {
        boolean checkOnly = args != null && Arrays.asList(args).contains("--verify-theme");
        if (checkOnly) {
            System.setProperty("java.awt.headless", "true");
        }
        // Must execute before any AWT/Swing class touches Toolkit or UIManager!
        NativeDesktopPlatformAdapter.configurePreUiSystemProperties();
        Path userHome = Path.of(System.getProperty("user.home", System.getProperty("java.io.tmpdir")));
        Path appDir = userHome.resolve(".moneycraft-desktop");
        Path workstationPropsPath = appDir.resolve("workstation-host.properties");
        Properties workstationProps = loadWorkstationProperties(workstationPropsPath);
        URI hostOrigin = resolveConfiguredHostOrigin(args, workstationProps);
        EnrolledHostTrustManager trustManager = enrollConfiguredWorkstationTrust(hostOrigin, workstationProps);

        BrandSwingTheme theme = new BrandSwingTheme();
        DesktopCommandRegistry registry = new DesktopCommandRegistry();
        byte[] sessionKey = new byte[32];
        new SecureRandom().nextBytes(sessionKey);

        PrintAttemptLedger ledger = new PrintAttemptLedger(appDir.resolve("print-ledger.enc"), sessionKey);
        ScaleAndBarcodeCaptureManager captureManager = new ScaleAndBarcodeCaptureManager(registry);
        DesktopPreferencesManager preferencesManager = new DesktopPreferencesManager(
            hostOrigin,
            appDir.resolve("preferences"),
            "mc-swing-default-en-gb",
            registry,
            () -> false
        );
        ContextualHelpCatalog helpCatalog = new ContextualHelpCatalog();
        ContextEpochGuard epochGuard = new ContextEpochGuard();
        DesktopSessionCoordinator coordinator = buildSessionCoordinator(
            hostOrigin,
            trustManager,
            preferencesManager,
            registry,
            epochGuard
        );

        if (checkOnly || GraphicsEnvironment.isHeadless()) {
            MoneyCraftDesktopFrame headlessFrame = new MoneyCraftDesktopFrame(
                theme, registry, ledger, captureManager, preferencesManager, helpCatalog, epochGuard, null
            );
            headlessFrame.bindWorkstationHostConfiguration(
                hostOrigin,
                workstationPropsPath,
                newOrigin -> rebuildSessionCoordinatorForHost(
                    newOrigin,
                    workstationProps,
                    preferencesManager,
                    registry,
                    epochGuard
                )
            );
            headlessFrame.attachSessionCoordinator(coordinator);
            headlessFrame.enterWindowsStartupFlow();
            System.out.println(
                "MoneyCraft Desktop 1.0.0 ready [host=" + headlessFrame.configuredBackendHostOrigin()
                    + ", api=" + headlessFrame.configuredBackendApiBaseUri()
                    + ", trustProfile=" + (trustManager != null ? trustManager.enrolledTrustProfileId() : "LOOPBACK")
                    + ", startupView=" + headlessFrame.activeViewName()
                    + ", rupeeGlyph=" + theme.supportsRupeeGlyphInAmountFont()
                    + ", helpEntries=" + helpCatalog.entriesById().size() + "]"
            );
            coordinator.close();
            return;
        }

        SwingUtilities.invokeLater(() -> {
            MoneyCraftDesktopFrame frame = new MoneyCraftDesktopFrame(
                theme, registry, ledger, captureManager, preferencesManager, helpCatalog, epochGuard, null
            );
            frame.bindWorkstationHostConfiguration(
                hostOrigin,
                workstationPropsPath,
                newOrigin -> rebuildSessionCoordinatorForHost(
                    newOrigin,
                    workstationProps,
                    preferencesManager,
                    registry,
                    epochGuard
                )
            );
            frame.attachSessionCoordinator(coordinator);
            frame.platformAdapter().installSystemLookAndFeel();
            frame.platformAdapter().installNativeDesktopHandlers();
            frame.refreshThemeAndScale();
            frame.enterWindowsStartupFlow();
            frame.showInNativeWindow();
        });
    }

    public static DesktopSessionCoordinator rebuildSessionCoordinatorForHost(
        URI hostOrigin,
        Properties workstationProps,
        DesktopPreferencesManager preferencesManager,
        DesktopCommandRegistry registry,
        ContextEpochGuard epochGuard
    ) {
        EnrolledHostTrustManager trustManager = enrollConfiguredWorkstationTrust(hostOrigin, workstationProps);
        return buildSessionCoordinator(hostOrigin, trustManager, preferencesManager, registry, epochGuard);
    }

    public static DesktopSessionCoordinator buildSessionCoordinator(
        URI hostOrigin,
        EnrolledHostTrustManager trustManager,
        DesktopPreferencesManager preferencesManager,
        DesktopCommandRegistry registry,
        ContextEpochGuard epochGuard
    ) {
        boolean isLoopbackHttp = "http".equalsIgnoreCase(hostOrigin.getScheme());
        AdmittedNativeIssuerProfile issuerProfile = HostNativeIssuerFactory.forHostOrigin(
            hostOrigin,
            isLoopbackHttp
        );
        InMemoryAccessTokenHolder memoryTokenHolder = new InMemoryAccessTokenHolder();
        PlatformSecretStoreCredentialVault vault = new PlatformSecretStoreCredentialVault(
            hostOrigin,
            issuerProfile.clientId(),
            "default-desktop",
            memoryTokenHolder,
            true
        );
        NativePkceAuthClient authClient = new NativePkceAuthClient(
            vault,
            SystemBrowserLauncher.systemDefault(),
            Clock.systemUTC(),
            trustManager != null ? trustManager.activeSslContext() : null
        );
        DesktopClientConfig clientConfig = isLoopbackHttp
            ? DesktopClientConfig.loopbackTestConfig(hostOrigin)
            : new DesktopClientConfig(
                hostOrigin,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                trustManager != null ? trustManager.activeSslContext() : null,
                false,
                true
            );
        DesktopApiClient apiClient = new DesktopApiClient(clientConfig, authClient::currentAccessToken);
        return new DesktopSessionCoordinator(
            apiClient,
            authClient,
            issuerProfile,
            epochGuard,
            preferencesManager,
            registry
        );
    }

    public static Properties loadWorkstationProperties(Path propertiesFile) {
        Properties props = new Properties();
        if (propertiesFile != null && Files.isRegularFile(propertiesFile)) {
            try (InputStream in = Files.newInputStream(propertiesFile)) {
                props.load(in);
            } catch (IOException ignored) {
                return new Properties();
            }
        }
        return props;
    }

    public static void saveWorkstationHostProperties(
        Path propertiesFile,
        URI hostOrigin,
        Properties existingProps
    ) {
        if (propertiesFile == null || hostOrigin == null) {
            return;
        }
        Properties merged = new Properties();
        if (existingProps != null) {
            for (String key : existingProps.stringPropertyNames()) {
                merged.setProperty(key, existingProps.getProperty(key));
            }
        }
        String scheme = hostOrigin.getScheme() != null
            ? hostOrigin.getScheme().toLowerCase(Locale.ROOT)
            : "https";
        String host = hostOrigin.getHost() != null ? hostOrigin.getHost() : "localhost";
        int port = hostOrigin.getPort() > 0
            ? hostOrigin.getPort()
            : ("http".equals(scheme) ? 80 : 443);
        String formattedHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String canonicalOrigin = scheme + "://" + formattedHost + ":" + port;

        merged.setProperty("hostOrigin", canonicalOrigin);
        merged.setProperty("apiScheme", scheme);
        merged.setProperty("apiHost", host.replace("[", "").replace("]", ""));
        merged.setProperty("apiPort", String.valueOf(port));
        if (existingProps != null) {
            existingProps.setProperty("hostOrigin", canonicalOrigin);
            existingProps.setProperty("apiScheme", scheme);
            existingProps.setProperty("apiHost", host.replace("[", "").replace("]", ""));
            existingProps.setProperty("apiPort", String.valueOf(port));
        }

        try {
            Path parent = propertiesFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(propertiesFile)) {
                merged.store(out, "MoneyCraft Desktop Workstation Host & Backend API Endpoint Configuration");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to persist workstation host properties to " + propertiesFile, ex);
        }
    }

    public static URI resolveConfiguredHostOrigin(String[] args, Properties workstationProps) {
        String cliHost = null;
        String cliPort = null;
        String cliScheme = null;
        if (args != null) {
            for (int i = 0; i < args.length - 1; i++) {
                if ("--host".equals(args[i])) {
                    return URI.create(args[i + 1].trim());
                } else if ("--api-host".equals(args[i]) || "--api-ip".equals(args[i])) {
                    cliHost = args[i + 1].trim();
                } else if ("--api-port".equals(args[i])) {
                    cliPort = args[i + 1].trim();
                } else if ("--api-scheme".equals(args[i])) {
                    cliScheme = args[i + 1].trim();
                }
            }
        }
        if (cliHost != null && !cliHost.isBlank()) {
            return composeHostOriginUri(cliScheme, cliHost, cliPort);
        }

        String envHostUrl = System.getenv("MONEYCRAFT_HOST_URL");
        if (envHostUrl != null && !envHostUrl.isBlank()) {
            return URI.create(envHostUrl.trim());
        }
        String envApiHost = System.getenv("MONEYCRAFT_API_HOST");
        if (envApiHost == null || envApiHost.isBlank()) {
            envApiHost = System.getenv("MONEYCRAFT_API_IP");
        }
        String envApiPort = System.getenv("MONEYCRAFT_API_PORT");
        String envApiScheme = System.getenv("MONEYCRAFT_API_SCHEME");
        if (envApiHost != null && !envApiHost.isBlank()) {
            return composeHostOriginUri(envApiScheme, envApiHost, envApiPort);
        }

        if (workstationProps != null) {
            String propApiHost = workstationProps.getProperty("apiHost");
            String propApiPort = workstationProps.getProperty("apiPort");
            String propApiScheme = workstationProps.getProperty("apiScheme");
            String propHost = workstationProps.getProperty("hostOrigin");
            if (propHost != null && !propHost.isBlank()) {
                return URI.create(propHost.trim());
            }
            if (propApiHost != null && !propApiHost.isBlank()) {
                return composeHostOriginUri(propApiScheme, propApiHost, propApiPort);
            }
        }
        return URI.create("https://localhost:8443");
    }

    public static URI composeHostOriginUri(String schemeRaw, String hostRaw, String portRaw) {
        String scheme = (schemeRaw == null || schemeRaw.isBlank())
            ? "https"
            : schemeRaw.trim().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("Unsupported API scheme: " + scheme);
        }
        String cleanedHost = (hostRaw == null ? "" : hostRaw.trim()).replace("[", "").replace("]", "");
        if (cleanedHost.isEmpty() || cleanedHost.contains("/") || cleanedHost.contains("@") || cleanedHost.contains(" ")) {
            throw new IllegalArgumentException("Invalid backend API IP or hostname: " + hostRaw);
        }
        int port = 8443;
        if (portRaw != null && !portRaw.isBlank()) {
            try {
                port = Integer.parseInt(portRaw.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Backend API port must be an integer between 1 and 65535: " + portRaw, ex);
            }
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Backend API port must be between 1 and 65535: " + port);
        }
        String formattedHost = cleanedHost.contains(":") ? "[" + cleanedHost + "]" : cleanedHost;
        return URI.create(scheme + "://" + formattedHost + ":" + port);
    }

    private static EnrolledHostTrustManager enrollConfiguredWorkstationTrust(
        URI hostOrigin,
        Properties workstationProps
    ) {
        if (!"https".equalsIgnoreCase(hostOrigin.getScheme())) {
            return null;
        }
        EnrolledHostTrustManager trustManager = new EnrolledHostTrustManager(hostOrigin);
        if (workstationProps != null) {
            String caPemPath = workstationProps.getProperty("caPemPath");
            String expectedSha256 = workstationProps.getProperty("caExpectedSha256");
            if (caPemPath != null && !caPemPath.isBlank() && expectedSha256 != null && !expectedSha256.isBlank()) {
                try {
                    String pem = Files.readString(Path.of(caPemPath.trim()), StandardCharsets.UTF_8);
                    trustManager.enrollProvisionedPrivateCa(
                        workstationProps.getProperty("trustProfileId", "WORKSTATION_PROVISIONED_CA"),
                        pem,
                        expectedSha256.trim(),
                        workstationProps.getProperty("administratorReference", "workstation-installer")
                    );
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to read enrolled private-LAN CA PEM: " + caPemPath, ex);
                }
            }
        }
        return trustManager;
    }
}
