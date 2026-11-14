package com.raster.moneycraft.foundation.crypto;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.DeterministicAead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.Mac;
import com.google.crypto.tink.Parameters;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.AesGcmKey;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import com.google.crypto.tink.daead.DeterministicAeadConfig;
import com.google.crypto.tink.daead.PredefinedDeterministicAeadParameters;
import com.google.crypto.tink.mac.MacConfig;
import com.google.crypto.tink.mac.PredefinedMacParameters;
import com.google.crypto.tink.util.SecretBytes;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TinkCryptoService {

    public static final String PURPOSE_SIV_LOOKUP = "SIV_LOOKUP";
    public static final String PURPOSE_GCM_PAYLOAD = "GCM_PAYLOAD";
    public static final String PURPOSE_CURSOR_HMAC = "CURSOR_HMAC";
    public static final String PURPOSE_IDEMPOTENCY_HMAC = "IDEMPOTENCY_HMAC";

    static {
        try {
            AeadConfig.register();
            DeterministicAeadConfig.register();
            MacConfig.register();
        } catch (GeneralSecurityException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final MoneyCraftProperties properties;
    private final JdbcTemplate authorityJdbcTemplate;
    private final Map<String, CachedKeyset> keysetCache = new ConcurrentHashMap<>();

    public TinkCryptoService(
        MoneyCraftProperties properties,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate
    ) {
        this.properties = properties;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
    }

    public Map<String, UUID> provisionInstallationKeys(UUID installationId) {
        Aead kekAead = resolveKekAead();
        String kekId = properties.getCrypto().getKekKeyId();

        UUID sivKeyId = generateAndPersistWrappedKey(
            installationId,
            PURPOSE_SIV_LOOKUP,
            PredefinedDeterministicAeadParameters.AES256_SIV,
            kekId,
            kekAead
        );
        UUID gcmKeyId = generateAndPersistWrappedKey(
            installationId,
            PURPOSE_GCM_PAYLOAD,
            PredefinedAeadParameters.AES256_GCM,
            kekId,
            kekAead
        );
        UUID cursorKeyId = generateAndPersistWrappedKey(
            installationId,
            PURPOSE_CURSOR_HMAC,
            PredefinedMacParameters.HMAC_SHA256_256BITTAG,
            kekId,
            kekAead
        );
        UUID idempotencyKeyId = generateAndPersistWrappedKey(
            installationId,
            PURPOSE_IDEMPOTENCY_HMAC,
            PredefinedMacParameters.HMAC_SHA256_256BITTAG,
            kekId,
            kekAead
        );

        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_cursor_lifecycle (key_id, cursor_epoch)
            VALUES (?, 1)
            ON CONFLICT (key_id) DO NOTHING
            """,
            cursorKeyId
        );

        keysetCache.clear();
        return Map.of(
            PURPOSE_SIV_LOOKUP, sivKeyId,
            PURPOSE_GCM_PAYLOAD, gcmKeyId,
            PURPOSE_CURSOR_HMAC, cursorKeyId,
            PURPOSE_IDEMPOTENCY_HMAC, idempotencyKeyId
        );
    }

    public byte[] encryptDeterministicSiv(
        UUID companyId,
        String tableName,
        String columnName,
        String plaintext
    ) {
        Objects.requireNonNull(companyId, "companyId is required");
        Objects.requireNonNull(plaintext, "plaintext is required");
        byte[] ad = buildSivAssociatedData(companyId, tableName, columnName);
        try {
            CachedKeyset cached = loadActiveKeyset(PURPOSE_SIV_LOOKUP);
            DeterministicAead daead = cached.handle().getPrimitive(
                RegistryConfiguration.get(),
                DeterministicAead.class
            );
            return daead.encryptDeterministically(plaintext.getBytes(StandardCharsets.UTF_8), ad);
        } catch (GeneralSecurityException ex) {
            throw new MoneyCraftException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MC-CRYPTO-SIV-ENCRYPT",
                "Deterministic Encryption Failed",
                "Failed to encrypt lookup field via Tink AES256_SIV."
            );
        }
    }

    public String decryptDeterministicSiv(
        UUID companyId,
        String tableName,
        String columnName,
        byte[] ciphertext
    ) {
        Objects.requireNonNull(companyId, "companyId is required");
        Objects.requireNonNull(ciphertext, "ciphertext is required");
        byte[] ad = buildSivAssociatedData(companyId, tableName, columnName);
        try {
            CachedKeyset cached = loadActiveKeyset(PURPOSE_SIV_LOOKUP);
            DeterministicAead daead = cached.handle().getPrimitive(
                RegistryConfiguration.get(),
                DeterministicAead.class
            );
            byte[] plain = daead.decryptDeterministically(ciphertext, ad);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-CRYPTO-SIV-DECRYPT",
                "Deterministic Decryption Failed",
                "Ciphertext or associated data authentication failed under AES256_SIV."
            );
        }
    }

    public byte[] encryptGcm(byte[] associatedData, byte[] plaintext) {
        Objects.requireNonNull(plaintext, "plaintext is required");
        byte[] ad = associatedData == null ? new byte[0] : associatedData;
        try {
            CachedKeyset cached = loadActiveKeyset(PURPOSE_GCM_PAYLOAD);
            Aead aead = cached.handle().getPrimitive(RegistryConfiguration.get(), Aead.class);
            return aead.encrypt(plaintext, ad);
        } catch (GeneralSecurityException ex) {
            throw new MoneyCraftException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MC-CRYPTO-GCM-ENCRYPT",
                "Payload Encryption Failed",
                "Failed to encrypt payload via Tink AES256_GCM."
            );
        }
    }

    public byte[] decryptGcm(byte[] associatedData, byte[] ciphertext) {
        Objects.requireNonNull(ciphertext, "ciphertext is required");
        byte[] ad = associatedData == null ? new byte[0] : associatedData;
        try {
            CachedKeyset cached = loadActiveKeyset(PURPOSE_GCM_PAYLOAD);
            Aead aead = cached.handle().getPrimitive(RegistryConfiguration.get(), Aead.class);
            return aead.decrypt(ciphertext, ad);
        } catch (GeneralSecurityException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-CRYPTO-GCM-DECRYPT",
                "Payload Decryption Failed",
                "Ciphertext authentication failed under AES256_GCM."
            );
        }
    }

    public HmacDigestResult computeIdempotencyHmac(byte[] canonicalJcsUtf8) {
        return computePurposeSha256Digest(PURPOSE_IDEMPOTENCY_HMAC, canonicalJcsUtf8);
    }

    public HmacDigestResult computeCursorSignature(byte[] signingInputUtf8) {
        return computePurposeSha256Digest(PURPOSE_CURSOR_HMAC, signingInputUtf8);
    }

    public void evictCache() {
        keysetCache.clear();
    }

    private HmacDigestResult computePurposeSha256Digest(String purpose, byte[] input) {
        try {
            CachedKeyset cached = loadActiveKeyset(purpose);
            Mac tinkMac = cached.handle().getPrimitive(RegistryConfiguration.get(), Mac.class);
            byte[] tagWithPrefix = tinkMac.computeMac(input);
            // Normalize to exact 32-byte digest for PostgreSQL chk_mc_retry_hmac_len (octet_length = 32)
            byte[] digest32 = sha256(tagWithPrefix);
            return new HmacDigestResult(cached.keyId(), digest32);
        } catch (GeneralSecurityException ex) {
            throw new MoneyCraftException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MC-CRYPTO-HMAC-ERROR",
                "Cryptographic MAC Error",
                "Failed to compute installation-scoped Tink HMAC."
            );
        }
    }

    private UUID generateAndPersistWrappedKey(
        UUID installationId,
        String purpose,
        Parameters parameters,
        String kekKeyId,
        Aead kekAead
    ) {
        List<UUID> existing = authorityJdbcTemplate.query(
            """
            SELECT id FROM ma_key
             WHERE installation_id = ? AND key_purpose = ? AND company_scope IS NULL AND state = 'ACTIVE'
             ORDER BY key_generation DESC LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            installationId,
            purpose
        );
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        UUID keyId = UUID.randomUUID();
        byte[] wrapAd = buildKeyWrapAssociatedData(installationId, purpose, 1L);
        try {
            KeysetHandle dekHandle = KeysetHandle.generateNew(parameters);
            byte[] wrappedDek = TinkProtoKeysetFormat.serializeEncryptedKeyset(dekHandle, kekAead, wrapAd);
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_key (
                    id, installation_id, company_scope, key_purpose,
                    key_generation, kek_key_id, wrapped_dek, state
                ) VALUES (?, ?, NULL, ?, 1, ?, ?, 'ACTIVE')
                """,
                keyId,
                installationId,
                purpose,
                kekKeyId,
                wrappedDek
            );
            return keyId;
        } catch (GeneralSecurityException ex) {
            throw new MoneyCraftException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MC-CRYPTO-KEYGEN",
                "Envelope Key Generation Failed",
                "Failed to generate and wrap Tink DEK for " + purpose
            );
        }
    }

    private CachedKeyset loadActiveKeyset(String purpose) {
        return keysetCache.computeIfAbsent(purpose, p -> {
            List<KeyRow> rows = authorityJdbcTemplate.query(
                """
                SELECT id, installation_id, key_generation, wrapped_dek
                  FROM ma_key
                 WHERE key_purpose = ? AND company_scope IS NULL AND state = 'ACTIVE'
                 ORDER BY key_generation DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> new KeyRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("installation_id", UUID.class),
                    rs.getLong("key_generation"),
                    rs.getBytes("wrapped_dek")
                ),
                p
            );
            if (rows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MC-CRYPTO-UNINITIALISED",
                    "Cryptographic Keys Not Provisioned",
                    "Active Tink DEK not found in ma_key for purpose " + p + ". Complete installation bootstrap first."
                );
            }
            KeyRow row = rows.getFirst();
            byte[] wrapAd = buildKeyWrapAssociatedData(row.installationId(), p, row.generation());
            try {
                KeysetHandle handle = TinkProtoKeysetFormat.parseEncryptedKeyset(
                    row.wrappedDek(),
                    resolveKekAead(),
                    wrapAd
                );
                return new CachedKeyset(row.id(), handle);
            } catch (GeneralSecurityException ex) {
                throw new MoneyCraftException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "MC-CRYPTO-UNWRAP-FAILED",
                    "DEK Unwrap Failed",
                    "Master KEK could not unwrap ma_key entry for purpose " + p
                );
            }
        });
    }

    private Aead resolveKekAead() {
        String configuredKek = properties.getCrypto().getMasterKek();
        if (configuredKek == null || configuredKek.isBlank()) {
            if (!properties.getCrypto().isAllowEphemeralDevKek()) {
                throw new MoneyCraftException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MC-CRYPTO-KEK-MISSING",
                    "Master KEK Not Configured",
                    "Externalized master KEK (MC_TINK_MASTER_KEK or /run/secrets/mc_tink_master_kek) is required."
                );
            }
            configuredKek = "moneycraft-ephemeral-dev-kek-fallback-v1";
        }

        byte[] raw32 = deriveKekBytes(configuredKek.trim());
        try {
            AesGcmKey kekKey = AesGcmKey.builder()
                .setParameters(PredefinedAeadParameters.AES256_GCM)
                .setKeyBytes(SecretBytes.copyFrom(raw32, InsecureSecretKeyAccess.get()))
                .setIdRequirement(1)
                .build();
            KeysetHandle kekHandle = KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(kekKey).withFixedId(1).makePrimary())
                .build();
            return kekHandle.getPrimitive(RegistryConfiguration.get(), Aead.class);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to initialize master KEK AEAD", ex);
        }
    }

    private static byte[] deriveKekBytes(String rawKekMaterial) {
        try {
            byte[] decoded = Base64.getDecoder().decode(rawKekMaterial);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to domain-separated SHA-256 derivation when passphrase or hex string is supplied.
        }
        return sha256(("MC-TINK-KEK-V1:" + rawKekMaterial).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildKeyWrapAssociatedData(UUID installationId, String purpose, long generation) {
        String ad = "ma_key|" + installationId + "|" + purpose + "|" + generation;
        return ad.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildSivAssociatedData(UUID companyId, String tableName, String columnName) {
        String ad = companyId + "|" + tableName + "|" + columnName;
        return ad.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record KeyRow(UUID id, UUID installationId, long generation, byte[] wrappedDek) {
    }

    private record CachedKeyset(UUID keyId, KeysetHandle handle) {
    }

    public record HmacDigestResult(UUID keyId, byte[] digest32) {
    }
}
