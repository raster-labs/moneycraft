package com.raster.moneycraft.desktop.auth;

import java.util.Optional;

/**
 * Pluggable host credential storage contract (macOS Keychain / encrypted host vault) with
 * explicit fail-closed availability semantics and logout clearing (SWG-002, DEV-004).
 */
public interface CredentialVault {

    boolean isAvailable();

    void store(StoredNativeCredentials credentials);

    Optional<StoredNativeCredentials> load();

    void clear();
}
