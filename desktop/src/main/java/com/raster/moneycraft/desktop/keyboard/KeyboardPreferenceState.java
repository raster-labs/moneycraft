package com.raster.moneycraft.desktop.keyboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.swing.KeyStroke;

/**
 * In-memory per-user keyboard preference state (KEY-001, KEY-002).
 * Cleared on logout or unexpected principal change; contains zero financial data or secrets.
 */
public record KeyboardPreferenceState(
    int version,
    String ownerPrincipalId,
    ShortcutMode mode,
    String locale,
    String qualifiedProfileId,
    Map<String, KeyStroke> modifierOverrides
) {

    public KeyboardPreferenceState {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(locale, "locale must not be null");
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported keyboard preference version: " + version);
        }
        if (locale.isBlank()) {
            throw new IllegalArgumentException("locale must not be blank.");
        }
        modifierOverrides = modifierOverrides == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(modifierOverrides));
    }

    public static KeyboardPreferenceState defaults(String ownerPrincipalId) {
        return new KeyboardPreferenceState(1, ownerPrincipalId, ShortcutMode.CHARACTER, "en-GB", null, Map.of());
    }

    public static String overrideKey(String contextId, String commandId) {
        return contextId + "::" + commandId;
    }
}
