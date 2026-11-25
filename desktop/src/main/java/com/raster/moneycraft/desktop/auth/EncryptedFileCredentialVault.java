package com.raster.moneycraft.desktop.auth;

import com.raster.moneycraft.desktop.client.DesktopJcsCanonicalizer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM host-user-bound encrypted file credential vault with POSIX 0600 permissions
 * and zero-plaintext persistence (SWG-002, AT09-DEV-004-B).
 */
public final class EncryptedFileCredentialVault implements CredentialVault {

    private static final byte[] MAGIC = {'M', 'C', 'V', '1'};
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Path vaultFile;
    private final SecretKey secretKey;
    private final byte[] associatedData;
    private final DesktopJcsCanonicalizer canonicalizer;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile boolean available;

    public EncryptedFileCredentialVault(Path vaultFile, byte[] keyBytes32) {
        this(vaultFile, keyBytes32, true);
    }

    public EncryptedFileCredentialVault(Path vaultFile, byte[] keyBytes32, boolean available) {
        this.vaultFile = Objects.requireNonNull(vaultFile, "vaultFile must not be null").toAbsolutePath().normalize();
        Objects.requireNonNull(keyBytes32, "keyBytes32 must not be null");
        if (keyBytes32.length != 32) {
            throw new IllegalArgumentException("AES-256-GCM credential vault requires a 32-byte key.");
        }
        this.secretKey = new SecretKeySpec(Arrays.copyOf(keyBytes32, 32), "AES");
        String hostUser = System.getProperty("user.name", "unknown");
        this.associatedData = ("MC-DESKTOP-VAULT-V1|" + hostUser + "|" + this.vaultFile)
            .getBytes(StandardCharsets.UTF_8);
        this.canonicalizer = new DesktopJcsCanonicalizer();
        this.available = available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public synchronized void store(StoredNativeCredentials credentials) {
        ensureAvailable();
        Objects.requireNonNull(credentials, "credentials must not be null");
        try {
            Path parent = vaultFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);

            byte[] plaintext = canonicalizer.writeWireJson(credentials).getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] ciphertext = cipher.doFinal(plaintext);
            Arrays.fill(plaintext, (byte) 0);

            ByteBuffer buffer = ByteBuffer.allocate(MAGIC.length + GCM_IV_BYTES + ciphertext.length);
            buffer.put(MAGIC);
            buffer.put(iv);
            buffer.put(ciphertext);

            Path tmpFile = vaultFile.resolveSibling(vaultFile.getFileName() + ".tmp");
            Files.write(tmpFile, buffer.array());
            restrictOwnerOnlyPermissions(tmpFile);
            Files.move(tmpFile, vaultFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            restrictOwnerOnlyPermissions(vaultFile);
        } catch (IOException | GeneralSecurityException ex) {
            throw new VaultUnavailableException("Failed to write AES-GCM encrypted credential vault.", ex);
        }
    }

    @Override
    public synchronized Optional<StoredNativeCredentials> load() {
        ensureAvailable();
        if (!Files.exists(vaultFile)) {
            return Optional.empty();
        }
        try {
            byte[] raw = Files.readAllBytes(vaultFile);
            if (raw.length <= MAGIC.length + GCM_IV_BYTES + 16) {
                throw new NativeAuthSecurityException("MC-VAULT-CORRUPT", "Credential vault envelope is truncated.");
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (raw[i] != MAGIC[i]) {
                    throw new NativeAuthSecurityException("MC-VAULT-CORRUPT", "Credential vault header mismatch.");
                }
            }
            byte[] iv = Arrays.copyOfRange(raw, MAGIC.length, MAGIC.length + GCM_IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(raw, MAGIC.length + GCM_IV_BYTES, raw.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] plaintext = cipher.doFinal(ciphertext);
            try {
                String json = new String(plaintext, StandardCharsets.UTF_8);
                StoredNativeCredentials decoded = canonicalizer.wireMapper()
                    .readValue(json, StoredNativeCredentials.class);
                return Optional.of(decoded);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (NativeAuthSecurityException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NativeAuthSecurityException(
                "MC-VAULT-AUTH-TAG-FAILED",
                "Credential vault integrity or decryption check failed.",
                ex
            );
        }
    }

    @Override
    public synchronized void clear() {
        try {
            if (Files.exists(vaultFile)) {
                long size = Files.size(vaultFile);
                if (size > 0 && size <= 65536) {
                    byte[] wipe = new byte[(int) size];
                    secureRandom.nextBytes(wipe);
                    Files.write(vaultFile, wipe);
                }
                Files.deleteIfExists(vaultFile);
            }
        } catch (IOException ex) {
            throw new VaultUnavailableException("Failed to clear local credential vault on logout.", ex);
        }
    }

    private void ensureAvailable() {
        if (!available) {
            throw new VaultUnavailableException(
                "Qualified host credential storage is unavailable; refusing to fall back to unprotected token storage."
            );
        }
    }

    private static void restrictOwnerOnlyPermissions(Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view != null) {
            Set<PosixFilePermission> ownerReadWrite = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(path, ownerReadWrite);
        }
    }
}
