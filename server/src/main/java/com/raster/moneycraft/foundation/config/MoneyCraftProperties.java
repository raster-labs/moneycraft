package com.raster.moneycraft.foundation.config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moneycraft")
public class MoneyCraftProperties {

    private String version = "1.0.0-SNAPSHOT";
    private UUID installationId = UUID.fromString("00000000-0000-4000-8000-000000000000");
    private String baseUrl = "https://localhost:18443";
    private final Flyway flyway = new Flyway();
    private final Migrator migrator = new Migrator();
    private final Webauthn webauthn = new Webauthn();
    private final Security security = new Security();
    private final Crypto crypto = new Crypto();
    private final Connector connector = new Connector();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public UUID getInstallationId() {
        return installationId;
    }

    public void setInstallationId(UUID installationId) {
        this.installationId = installationId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Flyway getFlyway() {
        return flyway;
    }

    public Migrator getMigrator() {
        return migrator;
    }

    public Webauthn getWebauthn() {
        return webauthn;
    }

    public Security getSecurity() {
        return security;
    }

    public Crypto getCrypto() {
        return crypto;
    }

    public Connector getConnector() {
        return connector;
    }

    public static class Flyway {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Migrator {
        private boolean only;
        private boolean exitOnComplete;

        public boolean isOnly() {
            return only;
        }

        public void setOnly(boolean only) {
            this.only = only;
        }

        public boolean isExitOnComplete() {
            return exitOnComplete;
        }

        public void setExitOnComplete(boolean exitOnComplete) {
            this.exitOnComplete = exitOnComplete;
        }
    }

    public static class Webauthn {
        private String rpId = "localhost";
        private String rpName = "MoneyCraft";
        private List<String> allowedOrigins = new ArrayList<>();

        public String getRpId() {
            return rpId;
        }

        public void setRpId(String rpId) {
            this.rpId = rpId;
        }

        public String getRpName() {
            return rpName;
        }

        public void setRpName(String rpName) {
            this.rpName = rpName;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Security {
        private boolean cookieSecure = true;
        private final Argon2 argon2 = new Argon2();

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        public Argon2 getArgon2() {
            return argon2;
        }
    }

    public static class Argon2 {
        private int saltLength = 16;
        private int hashLength = 32;
        private int parallelism = 1;
        private int memoryKib = 65536;
        private int iterations = 3;

        public int getSaltLength() {
            return saltLength;
        }

        public void setSaltLength(int saltLength) {
            this.saltLength = saltLength;
        }

        public int getHashLength() {
            return hashLength;
        }

        public void setHashLength(int hashLength) {
            this.hashLength = hashLength;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public int getMemoryKib() {
            return memoryKib;
        }

        public void setMemoryKib(int memoryKib) {
            this.memoryKib = memoryKib;
        }

        public int getIterations() {
            return iterations;
        }

        public void setIterations(int iterations) {
            this.iterations = iterations;
        }
    }

    public static class Crypto {
        private String kekKeyId = "local-envelope-kek-v1";
        private String masterKek = "";
        private String cursorHmacSeed = "";
        private String idempotencyHmacSeed = "";
        private boolean allowEphemeralDevKek;

        public String getKekKeyId() {
            return kekKeyId;
        }

        public void setKekKeyId(String kekKeyId) {
            this.kekKeyId = kekKeyId;
        }

        public String getMasterKek() {
            return masterKek;
        }

        public void setMasterKek(String masterKek) {
            this.masterKek = masterKek;
        }

        public String getCursorHmacSeed() {
            return cursorHmacSeed;
        }

        public void setCursorHmacSeed(String cursorHmacSeed) {
            this.cursorHmacSeed = cursorHmacSeed;
        }

        public String getIdempotencyHmacSeed() {
            return idempotencyHmacSeed;
        }

        public void setIdempotencyHmacSeed(String idempotencyHmacSeed) {
            this.idempotencyHmacSeed = idempotencyHmacSeed;
        }

        public boolean isAllowEphemeralDevKek() {
            return allowEphemeralDevKek;
        }

        public void setAllowEphemeralDevKek(boolean allowEphemeralDevKek) {
            this.allowEphemeralDevKek = allowEphemeralDevKek;
        }
    }

    public static class Connector {
        private boolean enabled;
        private List<String> egressHostnameAllowlist = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getEgressHostnameAllowlist() {
            return egressHostnameAllowlist;
        }

        public void setEgressHostnameAllowlist(List<String> egressHostnameAllowlist) {
            this.egressHostnameAllowlist = egressHostnameAllowlist;
        }
    }
}
