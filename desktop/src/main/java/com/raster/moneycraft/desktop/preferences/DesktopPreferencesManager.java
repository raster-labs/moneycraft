package com.raster.moneycraft.desktop.preferences;

import com.raster.moneycraft.desktop.client.ApiProblemException;
import com.raster.moneycraft.desktop.client.DesktopApiClient;
import com.raster.moneycraft.desktop.client.DesktopJcsCanonicalizer;
import com.raster.moneycraft.desktop.client.VersionedResponse;
import com.raster.moneycraft.desktop.keyboard.DesktopCommandRegistry;
import com.raster.moneycraft.desktop.keyboard.KeyboardPreferenceState;
import com.raster.moneycraft.desktop.keyboard.ShortcutMode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.swing.KeyStroke;

/**
 * Coordinates My Account ({@code /api/v1/me/ui-preferences}) and This Desktop OS-user-scoped
 * presentation preferences with independent property resolution, schemaVersion 2 validation,
 * revision conflict reporting, User A -> User B isolation, and Swing profile qualification guards
 * (Doc 50, Doc 52 §2, Swing Doc 03, PREF-A..F, S-03).
 */
public final class DesktopPreferencesManager {

    private static final String SIGNED_OUT_SCOPE = "signed_out_anonymous_desktop";

    private final URI hostOrigin;
    private final Path localPrefsDirectory;
    private final String qualifiedSwingProfileId;
    private final DesktopCommandRegistry commandRegistry;
    private final BooleanSupplier osDarkPreferenceDetector;
    private final DesktopJcsCanonicalizer canonicalizer = new DesktopJcsCanonicalizer();

    private volatile boolean localStorageWritable = true;
    private String activeSubjectScope = SIGNED_OUT_SCOPE;
    private UiPreferencesV2Dto accountConfirmed = UiPreferencesV2Dto.defaults();
    private String accountEtag;
    private String accountRevision = "0";
    private LocalOverrideEnvelope activeLocalOverrides = LocalOverrideEnvelope.empty(SIGNED_OUT_SCOPE);
    private UiPreferencesV2Dto unsavedAccountPreview;
    private String statusBanner = "Using default settings";
    private ConflictDetails lastConflict;

    public DesktopPreferencesManager(
        URI hostOrigin,
        Path localPrefsDirectory,
        String qualifiedSwingProfileId,
        DesktopCommandRegistry commandRegistry,
        BooleanSupplier osDarkPreferenceDetector
    ) {
        this.hostOrigin = Objects.requireNonNull(hostOrigin, "hostOrigin must not be null");
        this.localPrefsDirectory = Objects.requireNonNull(localPrefsDirectory, "localPrefsDirectory must not be null");
        this.qualifiedSwingProfileId = Objects.requireNonNull(
            qualifiedSwingProfileId,
            "qualifiedSwingProfileId must not be null"
        );
        this.commandRegistry = Objects.requireNonNull(commandRegistry, "commandRegistry must not be null");
        this.osDarkPreferenceDetector = Objects.requireNonNull(
            osDarkPreferenceDetector,
            "osDarkPreferenceDetector must not be null"
        );
        this.activeLocalOverrides = readValidatedLocalOverrides(SIGNED_OUT_SCOPE);
        applyEffectiveKeyboardToRegistry();
    }

    public void setLocalStorageWritable(boolean writable) {
        this.localStorageWritable = writable;
    }

    public synchronized String statusBanner() {
        return statusBanner;
    }

    public synchronized Optional<ConflictDetails> lastConflict() {
        return Optional.ofNullable(lastConflict);
    }

    public synchronized String activeSubjectScope() {
        return activeSubjectScope;
    }

    public synchronized String accountRevision() {
        return accountRevision;
    }

    public synchronized UiPreferencesV2Dto accountConfirmed() {
        return accountConfirmed;
    }

    public synchronized AppearanceMode effectiveAppearanceChoice() {
        if (activeLocalOverrides.appearance() != null) {
            return activeLocalOverrides.appearance();
        }
        if (unsavedAccountPreview != null) {
            return unsavedAccountPreview.appearance();
        }
        return accountConfirmed.appearance();
    }

    /**
     * Resolves {@code SYSTEM} live against the OS dark-mode detector without rewriting the saved choice.
     */
    public synchronized boolean isEffectiveDarkTheme() {
        AppearanceMode choice = effectiveAppearanceChoice();
        if (choice == AppearanceMode.DARK) {
            return true;
        }
        if (choice == AppearanceMode.LIGHT) {
            return false;
        }
        return osDarkPreferenceDetector.getAsBoolean();
    }

    public synchronized BigDecimal effectiveTextScalePercent() {
        if (activeLocalOverrides.textScalePercent() != null) {
            return activeLocalOverrides.textScalePercent();
        }
        if (unsavedAccountPreview != null) {
            return unsavedAccountPreview.textScalePercent();
        }
        return accountConfirmed.textScalePercent();
    }

    public synchronized boolean isLocalAppearanceMaskingAccount() {
        return !SIGNED_OUT_SCOPE.equals(activeSubjectScope) && activeLocalOverrides.appearance() != null;
    }

    /**
     * Loads authenticated account preferences after sign-in, discarding any prior person's state
     * without uploading signed-out choices automatically (Doc 50 §5, PREF-B).
     */
    public synchronized void onAuthenticatedAccountLoaded(VersionedResponse<UiPreferencesEnvelopeDto> response) {
        Objects.requireNonNull(response, "response must not be null");
        UiPreferencesEnvelopeDto env = response.body();
        this.activeSubjectScope = env.subjectScope();
        this.accountConfirmed = env.preferences();
        this.accountRevision = env.revision();
        this.accountEtag = response.etag();
        this.unsavedAccountPreview = null;
        this.lastConflict = null;
        this.activeLocalOverrides = readValidatedLocalOverrides(env.subjectScope());
        this.statusBanner = "Loaded account settings (rev " + env.revision() + ")";
        applyEffectiveKeyboardToRegistry();
    }

    public synchronized void onAccountFetchFailed(String safeScopeOrNull) {
        if (safeScopeOrNull != null && !safeScopeOrNull.isBlank()) {
            this.activeSubjectScope = safeScopeOrNull;
            this.activeLocalOverrides = readValidatedLocalOverrides(safeScopeOrNull);
        }
        this.statusBanner = "Account settings could not be loaded. Your saved settings have not been changed.";
        applyEffectiveKeyboardToRegistry();
    }

    /**
     * Clears authenticated in-memory state and pending previews on logout/expiry/person switch (PREF-B).
     */
    public synchronized void onLogoutOrSessionExpired() {
        this.activeSubjectScope = SIGNED_OUT_SCOPE;
        this.accountConfirmed = UiPreferencesV2Dto.defaults();
        this.accountRevision = "0";
        this.accountEtag = null;
        this.unsavedAccountPreview = null;
        this.lastConflict = null;
        this.activeLocalOverrides = readValidatedLocalOverrides(SIGNED_OUT_SCOPE);
        this.statusBanner = "Signed out; using signed-out desktop appearance settings.";
        commandRegistry.clearPreferencesOnLogout();
        applyEffectiveKeyboardToRegistry();
    }

    /**
     * Saves an explicit local override for this desktop installation and current subjectScope only.
     */
    public synchronized void saveLocalDesktopOverride(
        AppearanceMode appearanceOrInherit,
        BigDecimal textScaleOrInherit,
        UiPreferencesV2Dto.KeyboardWireValue keyboardOrInherit
    ) {
        if (textScaleOrInherit != null && !UiPreferencesV2Dto.isAllowedScale(textScaleOrInherit)) {
            throw new IllegalArgumentException("Unsupported textScalePercent: " + textScaleOrInherit);
        }
        LocalOverrideEnvelope updated = new LocalOverrideEnvelope(
            2,
            activeSubjectScope,
            qualifiedSwingProfileId,
            appearanceOrInherit,
            textScaleOrInherit,
            keyboardOrInherit
        );
        this.activeLocalOverrides = updated;
        persistLocalOverrides(updated);
        applyEffectiveKeyboardToRegistry();
    }

    public synchronized void clearSelectedLocalOverrides(
        boolean clearAppearance,
        boolean clearTextScale,
        boolean clearKeyboard
    ) {
        LocalOverrideEnvelope updated = new LocalOverrideEnvelope(
            2,
            activeSubjectScope,
            qualifiedSwingProfileId,
            clearAppearance ? null : activeLocalOverrides.appearance(),
            clearTextScale ? null : activeLocalOverrides.textScalePercent(),
            clearKeyboard ? null : activeLocalOverrides.keyboard()
        );
        this.activeLocalOverrides = updated;
        persistLocalOverrides(updated);
        this.statusBanner = "Using account settings for cleared local properties.";
        applyEffectiveKeyboardToRegistry();
    }

    public synchronized void resetThisDesktopAppearanceSettings() {
        this.activeLocalOverrides = LocalOverrideEnvelope.empty(activeSubjectScope);
        Path file = localOverrideFilePath(activeSubjectScope);
        try {
            Files.deleteIfExists(file);
            this.statusBanner = "Reset this desktop's local appearance overrides.";
        } catch (IOException ignored) {
            this.statusBanner = "Desktop settings cleared for this session only.";
        }
        applyEffectiveKeyboardToRegistry();
    }

    /**
     * Patches authenticated account settings via {@link DesktopApiClient} using If-Match and UUID
     * Idempotency-Key; retains a labelled in-memory preview on failure without promoting to local storage.
     */
    public synchronized void patchAccountPreferences(
        DesktopApiClient apiClient,
        UiPreferencesEnvelopeDto.PatchChanges changes,
        UUID intentionId
    ) {
        Objects.requireNonNull(apiClient, "apiClient must not be null");
        Objects.requireNonNull(changes, "changes must not be null");
        if (SIGNED_OUT_SCOPE.equals(activeSubjectScope) || accountEtag == null) {
            throw new IllegalStateException("Authenticate and load account preferences before saving to My account.");
        }

        UiPreferencesV2Dto previewCandidate = new UiPreferencesV2Dto(
            2,
            changes.appearance() != null ? changes.appearance() : accountConfirmed.appearance(),
            changes.textScalePercent() != null ? changes.textScalePercent() : accountConfirmed.textScalePercent(),
            changes.keyboard() != null ? changes.keyboard() : accountConfirmed.keyboard()
        );
        this.unsavedAccountPreview = previewCandidate;
        this.statusBanner = "Saving account settings...";

        try {
            VersionedResponse<UiPreferencesEnvelopeDto> committed = apiClient.patchAccountUiPreferences(
                new UiPreferencesEnvelopeDto.PatchRequest(2, changes),
                accountEtag,
                intentionId == null ? UUID.randomUUID() : intentionId
            ).join();
            this.accountConfirmed = committed.body().preferences();
            this.accountRevision = committed.body().revision();
            this.accountEtag = committed.etag();
            this.unsavedAccountPreview = null;
            this.lastConflict = null;
            this.statusBanner = isLocalAppearanceMaskingAccount()
                ? "Saved to your account (masked on this desktop by local override)."
                : "Saved to your account (rev " + accountRevision + ").";
            applyEffectiveKeyboardToRegistry();
        } catch (RuntimeException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof ApiProblemException problem && problem.isStaleOptimisticLock()) {
                this.lastConflict = new ConflictDetails(accountConfirmed, previewCandidate, problem.detail());
                this.statusBanner = "These settings changed in another session. Review the saved values before applying your changes.";
            } else {
                this.statusBanner = "Your account setting could not be saved. This preview is still visible. Retry or revert.";
            }
            applyEffectiveKeyboardToRegistry();
        }
    }

    private void applyEffectiveKeyboardToRegistry() {
        UiPreferencesV2Dto.KeyboardWireValue effectiveWire = activeLocalOverrides.keyboard();
        if (effectiveWire == null) {
            effectiveWire = unsavedAccountPreview != null
                ? unsavedAccountPreview.keyboard()
                : accountConfirmed.keyboard();
        }
        String ownerId = SIGNED_OUT_SCOPE.equals(activeSubjectScope) ? null : activeSubjectScope;
        if (effectiveWire.mode() == ShortcutMode.MODIFIER) {
            // Swing Doc 03 & Doc 50 §8: Never interpret a browser profileId as Swing-qualified!
            if (!qualifiedSwingProfileId.equals(effectiveWire.profileId())) {
                commandRegistry.applyPreferences(new KeyboardPreferenceState(
                    1, ownerId, ShortcutMode.OFF, "en-GB", null, Map.of()
                ));
                this.statusBanner = "Saved keyboard profile '" + effectiveWire.profileId()
                    + "' is not qualified for this Swing desktop; character/modifier shortcuts set to OFF.";
                return;
            }
            Map<String, KeyStroke> mappedOverrides = new LinkedHashMap<>();
            for (UiPreferencesV2Dto.KeyboardOverrideEntry entry : mapEntries(effectiveWire)) {
                KeyStroke ks = KeyStroke.getKeyStroke(entry.chordId());
                if (ks == null) {
                    commandRegistry.applyPreferences(new KeyboardPreferenceState(
                        1, ownerId, ShortcutMode.OFF, "en-GB", null, Map.of()
                    ));
                    return;
                }
                mappedOverrides.put(
                    KeyboardPreferenceState.overrideKey(entry.contextId(), entry.commandId()),
                    ks
                );
            }
            try {
                commandRegistry.applyPreferences(new KeyboardPreferenceState(
                    1, ownerId, ShortcutMode.MODIFIER, "en-GB", qualifiedSwingProfileId, mappedOverrides
                ));
            } catch (RuntimeException invalid) {
                commandRegistry.applyPreferences(new KeyboardPreferenceState(
                    1, ownerId, ShortcutMode.OFF, "en-GB", null, Map.of()
                ));
            }
            return;
        }

        commandRegistry.applyPreferences(new KeyboardPreferenceState(
            1, ownerId, effectiveWire.mode(), "en-GB", null, Map.of()
        ));
    }

    private static java.util.List<UiPreferencesV2Dto.KeyboardOverrideEntry> mapEntries(
        UiPreferencesV2Dto.KeyboardWireValue wire
    ) {
        return wire.overrides();
    }

    private LocalOverrideEnvelope readValidatedLocalOverrides(String scope) {
        if (!localStorageWritable) {
            return LocalOverrideEnvelope.empty(scope);
        }
        Path file = localOverrideFilePath(scope);
        if (!Files.exists(file)) {
            return LocalOverrideEnvelope.empty(scope);
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > 16384) {
                return LocalOverrideEnvelope.empty(scope);
            }
            LocalOverrideEnvelope parsed = canonicalizer.wireMapper()
                .readValue(new String(bytes, StandardCharsets.UTF_8), LocalOverrideEnvelope.class);
            if (parsed.schemaVersion() != 2 || !scope.equals(parsed.subjectScope())) {
                return LocalOverrideEnvelope.empty(scope);
            }
            if (parsed.textScalePercent() != null && !UiPreferencesV2Dto.isAllowedScale(parsed.textScalePercent())) {
                return new LocalOverrideEnvelope(
                    2, scope, qualifiedSwingProfileId, parsed.appearance(), null, parsed.keyboard()
                );
            }
            return parsed;
        } catch (Exception ignored) {
            // Malformed or newer unsupported schema falls back safely without overwriting.
            return LocalOverrideEnvelope.empty(scope);
        }
    }

    private void persistLocalOverrides(LocalOverrideEnvelope envelope) {
        if (!localStorageWritable) {
            this.statusBanner = "Desktop settings could not be stored. Applies for this session only.";
            return;
        }
        try {
            Files.createDirectories(localPrefsDirectory);
            Path target = localOverrideFilePath(envelope.subjectScope());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            byte[] jsonBytes = canonicalizer.writeWireJson(envelope).getBytes(StandardCharsets.UTF_8);
            Files.write(tmp, jsonBytes);
            PosixFileAttributeView posix = Files.getFileAttributeView(tmp, PosixFileAttributeView.class);
            if (posix != null) {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                );
                Files.setPosixFilePermissions(tmp, perms);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            this.statusBanner = "Saved local override for this desktop.";
        } catch (IOException ignored) {
            this.statusBanner = "Desktop settings could not be stored. Applies for this session only.";
        }
    }

    private Path localOverrideFilePath(String scope) {
        String nsHash = canonicalizer.sha256Hex(hostOrigin + "|" + scope).substring(0, 24);
        return localPrefsDirectory.resolve("ui-prefs-v2-" + nsHash + ".json");
    }

    public record LocalOverrideEnvelope(
        int schemaVersion,
        String subjectScope,
        String desktopProfileId,
        AppearanceMode appearance,
        BigDecimal textScalePercent,
        UiPreferencesV2Dto.KeyboardWireValue keyboard
    ) {
        public static LocalOverrideEnvelope empty(String scope) {
            return new LocalOverrideEnvelope(2, scope, "mc-swing-default-en-gb", null, null, null);
        }
    }

    public record ConflictDetails(
        UiPreferencesV2Dto currentAccountValue,
        UiPreferencesV2Dto proposedPreviewValue,
        String serverDetail
    ) {
    }
}
