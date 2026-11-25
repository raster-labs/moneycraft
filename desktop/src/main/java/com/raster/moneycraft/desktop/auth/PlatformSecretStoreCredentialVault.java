package com.raster.moneycraft.desktop.auth;

import com.raster.moneycraft.desktop.client.DesktopJcsCanonicalizer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * OS-qualified per-user secret store adapter for optional refresh-token custody across
 * macOS Keychain, Windows DPAPI (CurrentUser ProtectedData), and Linux Freedesktop Secret Service
 * (Swing Doc 02 §5, Swing Doc 03, S-06, AT-ITER110-024..025).
 *
 * <p>Invariants:
 * <ul>
 *   <li>Access tokens stay strictly inside {@link InMemoryAccessTokenHolder} and are never persisted.</li>
 *   <li>Every vault key is scoped to {@code (hostOrigin, issuerUri, clientId, profileId)}.</li>
 *   <li>When the OS vault is locked, biometric/PIN unlock is cancelled, or no secret service is
 *       present, persistent refresh storage degrades cleanly to interactive browser re-authentication
 *       and NEVER falls back to plaintext files, Java Preferences, or environment variables.</li>
 * </ul>
 */
public final class PlatformSecretStoreCredentialVault implements CredentialVault {

    public enum PlatformKind {
        MACOS_KEYCHAIN,
        WINDOWS_DPAPI,
        LINUX_SECRET_SERVICE,
        UNSUPPORTED_OS
    }

    public enum FallbackState {
        PROTECTED_PERSISTENT_REFRESH_ACTIVE,
        LOCKED_OR_UNAVAILABLE_INTERACTIVE_REAUTH_ONLY
    }

    private final URI hostOrigin;
    private final String clientId;
    private final String desktopProfileId;
    private final InMemoryAccessTokenHolder memoryAccessTokenHolder;
    private final OsSecretBackend secretBackend;
    private final DesktopJcsCanonicalizer canonicalizer = new DesktopJcsCanonicalizer();
    private final boolean interactiveFallbackWhenVaultUnavailable;
    private volatile String lastScopedIssuerUri;

    public PlatformSecretStoreCredentialVault(
        URI hostOrigin,
        String clientId,
        String desktopProfileId,
        InMemoryAccessTokenHolder memoryAccessTokenHolder,
        boolean interactiveFallbackWhenVaultUnavailable
    ) {
        this(
            hostOrigin,
            clientId,
            desktopProfileId,
            memoryAccessTokenHolder,
            detectSystemBackend(),
            interactiveFallbackWhenVaultUnavailable
        );
    }

    public PlatformSecretStoreCredentialVault(
        URI hostOrigin,
        String clientId,
        String desktopProfileId,
        InMemoryAccessTokenHolder memoryAccessTokenHolder,
        OsSecretBackend secretBackend,
        boolean interactiveFallbackWhenVaultUnavailable
    ) {
        this.hostOrigin = Objects.requireNonNull(hostOrigin, "hostOrigin must not be null");
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.desktopProfileId = Objects.requireNonNull(desktopProfileId, "desktopProfileId must not be null");
        this.memoryAccessTokenHolder = Objects.requireNonNull(
            memoryAccessTokenHolder,
            "memoryAccessTokenHolder must not be null"
        );
        this.secretBackend = Objects.requireNonNull(secretBackend, "secretBackend must not be null");
        this.interactiveFallbackWhenVaultUnavailable = interactiveFallbackWhenVaultUnavailable;
        this.lastScopedIssuerUri = hostOrigin.toString();
    }

    public PlatformKind platformKind() {
        return secretBackend.platformKind();
    }

    public FallbackState fallbackState() {
        return secretBackend.isUnlockedAndAvailable()
            ? FallbackState.PROTECTED_PERSISTENT_REFRESH_ACTIVE
            : FallbackState.LOCKED_OR_UNAVAILABLE_INTERACTIVE_REAUTH_ONLY;
    }

    @Override
    public boolean isAvailable() {
        return secretBackend.isUnlockedAndAvailable() || interactiveFallbackWhenVaultUnavailable;
    }

    public boolean isPersistentRefreshAvailable() {
        return secretBackend.isUnlockedAndAvailable();
    }

    @Override
    public synchronized void store(StoredNativeCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials must not be null");
        String effectiveIssuer = credentials.issuerUri() != null && !credentials.issuerUri().isBlank()
            ? credentials.issuerUri()
            : hostOrigin.toString();
        this.lastScopedIssuerUri = effectiveIssuer;

        // Always hold short-lived access_token in process memory only!
        memoryAccessTokenHolder.storeInMemory(
            hostOrigin,
            effectiveIssuer,
            clientId,
            credentials.subject(),
            credentials.accessToken(),
            null,
            credentials.expiresAt()
        );

        if (!secretBackend.isUnlockedAndAvailable()) {
            if (interactiveFallbackWhenVaultUnavailable) {
                // S-06 / AT-ITER110-025: Never write plaintext; session remains interactive memory-only.
                return;
            }
            throw new VaultUnavailableException(
                "OS secret store (" + secretBackend.platformKind() + ") is locked or unavailable."
            );
        }

        if (credentials.refreshToken() != null && !credentials.refreshToken().isBlank()) {
            PersistedRefreshEnvelope envelope = new PersistedRefreshEnvelope(
                hostOrigin.toString(),
                effectiveIssuer,
                clientId,
                credentials.subject(),
                credentials.audience(),
                credentials.refreshToken(),
                credentials.expiresAt(),
                credentials.scopes()
            );
            String scopedKey = scopedVaultKey(effectiveIssuer);
            secretBackend.putSecret(scopedKey, canonicalizer.writeWireJson(envelope));
        }
    }

    @Override
    public synchronized Optional<StoredNativeCredentials> load() {
        Optional<InMemoryAccessTokenHolder.ScopedMemoryToken> memSnapshot =
            memoryAccessTokenHolder.tokenSnapshotForOrigin(hostOrigin);
        if (!secretBackend.isUnlockedAndAvailable()) {
            if (!interactiveFallbackWhenVaultUnavailable) {
                throw new VaultUnavailableException(
                    "OS secret store (" + secretBackend.platformKind() + ") is locked; interactive sign-in required."
                );
            }
            return memSnapshot.map(snap -> new StoredNativeCredentials(
                snap.issuerUri(),
                snap.subject(),
                "moneycraft-api",
                snap.accessToken(),
                null,
                snap.expiresAt(),
                List.of("openid", "profile")
            ));
        }

        Optional<String> rawJson = secretBackend.getSecret(scopedVaultKey(lastScopedIssuerUri));
        if (rawJson.isEmpty() && !hostOrigin.toString().equals(lastScopedIssuerUri)) {
            rawJson = secretBackend.getSecret(scopedVaultKey(hostOrigin.toString()));
        }
        if (rawJson.isEmpty()) {
            return memSnapshot.map(snap -> new StoredNativeCredentials(
                snap.issuerUri(),
                snap.subject(),
                "moneycraft-api",
                snap.accessToken(),
                null,
                snap.expiresAt(),
                List.of("openid", "profile")
            ));
        }
        try {
            PersistedRefreshEnvelope env = canonicalizer.wireMapper()
                .readValue(rawJson.get(), PersistedRefreshEnvelope.class);
            if (!hostOrigin.toString().equals(env.hostOrigin()) || !clientId.equals(env.clientId())) {
                throw new NativeAuthSecurityException(
                    "MC-VAULT-HOST-SCOPE-MISMATCH",
                    "Stored refresh token scope did not match current enrolled host/client."
                );
            }
            String effectiveAccess = memSnapshot
                .map(InMemoryAccessTokenHolder.ScopedMemoryToken::accessToken)
                .orElse("REFRESH_REQUIRED_NO_MEMORY_ACCESS_TOKEN");
            Instant effectiveExpiry = memSnapshot
                .map(InMemoryAccessTokenHolder.ScopedMemoryToken::expiresAt)
                .orElse(env.expiresAt());
            return Optional.of(new StoredNativeCredentials(
                env.issuerUri(),
                env.subject(),
                env.audience(),
                effectiveAccess,
                env.refreshToken(),
                effectiveExpiry,
                env.scopes()
            ));
        } catch (NativeAuthSecurityException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NativeAuthSecurityException("MC-VAULT-CORRUPT", "Malformed OS vault refresh record.", ex);
        }
    }

    @Override
    public synchronized void clear() {
        memoryAccessTokenHolder.clear();
        if (secretBackend.isUnlockedAndAvailable()) {
            secretBackend.deleteSecret(scopedVaultKey(lastScopedIssuerUri));
            if (!hostOrigin.toString().equals(lastScopedIssuerUri)) {
                secretBackend.deleteSecret(scopedVaultKey(hostOrigin.toString()));
            }
        }
    }

    public String scopedVaultKey(String issuerUri) {
        String material = hostOrigin + "|" + issuerUri + "|" + clientId + "|" + desktopProfileId;
        return "mc-swing-" + canonicalizer.sha256Hex(material).substring(0, 32);
    }

    public static OsSecretBackend detectSystemBackend() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new MacOsKeychainBackend();
        }
        if (os.contains("win")) {
            return new WindowsDpapiBackend(
                Path.of(System.getProperty("user.home", "."), "AppData", "Local", "MoneyCraft", "vault")
            );
        }
        if (os.contains("linux")) {
            return new LinuxSecretServiceBackend();
        }
        return new UnavailableOsSecretBackend(PlatformKind.UNSUPPORTED_OS);
    }

    public interface OsSecretBackend {
        PlatformKind platformKind();

        boolean isUnlockedAndAvailable();

        void putSecret(String scopedKey, String secretPayload);

        Optional<String> getSecret(String scopedKey);

        void deleteSecret(String scopedKey);
    }

    public static final class UnavailableOsSecretBackend implements OsSecretBackend {
        private final PlatformKind kind;

        public UnavailableOsSecretBackend(PlatformKind kind) {
            this.kind = kind;
        }

        @Override
        public PlatformKind platformKind() {
            return kind;
        }

        @Override
        public boolean isUnlockedAndAvailable() {
            return false;
        }

        @Override
        public void putSecret(String scopedKey, String secretPayload) {
            throw new VaultUnavailableException("OS secret backend unavailable.");
        }

        @Override
        public Optional<String> getSecret(String scopedKey) {
            return Optional.empty();
        }

        @Override
        public void deleteSecret(String scopedKey) {
        }
    }

    /**
     * macOS Keychain Services adapter invoking {@code /usr/bin/security} for user login keychain items.
     */
    public static final class MacOsKeychainBackend implements OsSecretBackend {
        private static final Path SECURITY_BIN = Path.of("/usr/bin/security");
        private static final String SERVICE_NAME = "com.raster.moneycraft.desktop.refresh";

        @Override
        public PlatformKind platformKind() {
            return PlatformKind.MACOS_KEYCHAIN;
        }

        @Override
        public boolean isUnlockedAndAvailable() {
            if (!Files.isExecutable(SECURITY_BIN)) {
                return false;
            }
            try {
                Process p = new ProcessBuilder(SECURITY_BIN.toString(), "show-keychain-info")
                    .redirectErrorStream(true)
                    .start();
                return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception ignored) {
                return false;
            }
        }

        @Override
        public void putSecret(String scopedKey, String secretPayload) {
            String hexEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(secretPayload.getBytes(StandardCharsets.UTF_8));
            runSecurityCommand(List.of(
                SECURITY_BIN.toString(), "add-generic-password", "-U",
                "-a", scopedKey, "-s", SERVICE_NAME, "-w", hexEncoded
            ));
        }

        @Override
        public Optional<String> getSecret(String scopedKey) {
            try {
                Process p = new ProcessBuilder(
                    SECURITY_BIN.toString(), "find-generic-password", "-a", scopedKey, "-s", SERVICE_NAME, "-w"
                ).start();
                if (!p.waitFor(3, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    return Optional.empty();
                }
                String encoded = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (encoded.isEmpty()) {
                    return Optional.empty();
                }
                byte[] decoded = Base64.getUrlDecoder().decode(encoded);
                return Optional.of(new String(decoded, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }

        @Override
        public void deleteSecret(String scopedKey) {
            try {
                Process p = new ProcessBuilder(
                    SECURITY_BIN.toString(), "delete-generic-password", "-a", scopedKey, "-s", SERVICE_NAME
                ).start();
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Item may already be absent.
            }
        }

        private static void runSecurityCommand(List<String> command) {
            try {
                Process p = new ProcessBuilder(command).start();
                if (!p.waitFor(3, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    throw new VaultUnavailableException("macOS Keychain rejected secret write.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new VaultUnavailableException("Interrupted writing to macOS Keychain.", ex);
            } catch (IOException ex) {
                throw new VaultUnavailableException("Failed executing /usr/bin/security.", ex);
            }
        }
    }

    /**
     * Windows CurrentUser DPAPI ({@code ProtectedData::Protect}) per-user encrypted blob store.
     */
    public static final class WindowsDpapiBackend implements OsSecretBackend {
        private final Path dpapiDirectory;

        public WindowsDpapiBackend(Path dpapiDirectory) {
            this.dpapiDirectory = Objects.requireNonNull(dpapiDirectory, "dpapiDirectory must not be null");
        }

        @Override
        public PlatformKind platformKind() {
            return PlatformKind.WINDOWS_DPAPI;
        }

        @Override
        public boolean isUnlockedAndAvailable() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return os.contains("win");
        }

        @Override
        public void putSecret(String scopedKey, String secretPayload) {
            try {
                Files.createDirectories(dpapiDirectory);
                Path target = dpapiDirectory.resolve(scopedKey + ".dpapi");
                Process p = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Add-Type -AssemblyName System.Security; "
                        + "$in = [Console]::In.ReadToEnd(); "
                        + "$bytes = [Text.Encoding]::UTF8.GetBytes($in); "
                        + "$enc = [Security.Cryptography.ProtectedData]::Protect("
                        + "$bytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser); "
                        + "[IO.File]::WriteAllBytes('" + target.toAbsolutePath().toString().replace("'", "''") + "', $enc)"
                ).start();
                try (OutputStream stdin = p.getOutputStream()) {
                    stdin.write(secretPayload.getBytes(StandardCharsets.UTF_8));
                }
                if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    throw new VaultUnavailableException("Windows DPAPI CurrentUser protection failed.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new VaultUnavailableException("Interrupted during Windows DPAPI protect.", ex);
            } catch (IOException ex) {
                throw new VaultUnavailableException("Failed invoking Windows DPAPI.", ex);
            }
        }

        @Override
        public Optional<String> getSecret(String scopedKey) {
            Path target = dpapiDirectory.resolve(scopedKey + ".dpapi");
            if (!Files.exists(target)) {
                return Optional.empty();
            }
            try {
                Process p = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Add-Type -AssemblyName System.Security; "
                        + "$enc = [IO.File]::ReadAllBytes('" + target.toAbsolutePath().toString().replace("'", "''") + "'); "
                        + "$dec = [Security.Cryptography.ProtectedData]::Unprotect("
                        + "$enc, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser); "
                        + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($dec))"
                ).start();
                if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    return Optional.empty();
                }
                return Optional.of(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }

        @Override
        public void deleteSecret(String scopedKey) {
            try {
                Files.deleteIfExists(dpapiDirectory.resolve(scopedKey + ".dpapi"));
            } catch (IOException ignored) {
                // Best-effort removal.
            }
        }
    }

    /**
     * Linux Freedesktop Secret Service adapter via {@code secret-tool}.
     */
    public static final class LinuxSecretServiceBackend implements OsSecretBackend {

        @Override
        public PlatformKind platformKind() {
            return PlatformKind.LINUX_SECRET_SERVICE;
        }

        @Override
        public boolean isUnlockedAndAvailable() {
            return Files.isExecutable(Path.of("/usr/bin/secret-tool"));
        }

        @Override
        public void putSecret(String scopedKey, String secretPayload) {
            try {
                Process p = new ProcessBuilder(
                    "/usr/bin/secret-tool", "store", "--label=MoneyCraft Desktop Refresh",
                    "application", "moneycraft-desktop", "scope", scopedKey
                ).start();
                try (OutputStream stdin = p.getOutputStream()) {
                    stdin.write(secretPayload.getBytes(StandardCharsets.UTF_8));
                }
                if (!p.waitFor(3, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    throw new VaultUnavailableException("Freedesktop secret-tool store failed.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new VaultUnavailableException("Interrupted writing to secret-tool.", ex);
            } catch (IOException ex) {
                throw new VaultUnavailableException("Failed running secret-tool.", ex);
            }
        }

        @Override
        public Optional<String> getSecret(String scopedKey) {
            try {
                Process p = new ProcessBuilder(
                    "/usr/bin/secret-tool", "lookup", "application", "moneycraft-desktop", "scope", scopedKey
                ).start();
                if (!p.waitFor(3, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    return Optional.empty();
                }
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return out.isBlank() ? Optional.empty() : Optional.of(out);
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }

        @Override
        public void deleteSecret(String scopedKey) {
            try {
                Process p = new ProcessBuilder(
                    "/usr/bin/secret-tool", "clear", "application", "moneycraft-desktop", "scope", scopedKey
                ).start();
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Item may already be absent.
            }
        }
    }

    private record PersistedRefreshEnvelope(
        String hostOrigin,
        String issuerUri,
        String clientId,
        String subject,
        String audience,
        String refreshToken,
        Instant expiresAt,
        List<String> scopes
    ) {
    }
}
