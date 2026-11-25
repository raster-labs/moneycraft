package com.raster.moneycraft.desktop.preferences;

import com.raster.moneycraft.desktop.keyboard.ShortcutMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Closed schemaVersion 2 UI preferences wire DTO matching {@code ui_preferences.schema.json} (Doc 50, Doc 52 §2).
 */
public record UiPreferencesV2Dto(
    int schemaVersion,
    AppearanceMode appearance,
    BigDecimal textScalePercent,
    KeyboardWireValue keyboard
) {

    public static final Set<BigDecimal> ALLOWED_TEXT_SCALES = Set.of(
        new BigDecimal("75"),
        new BigDecimal("87.5"),
        new BigDecimal("100"),
        new BigDecimal("112.5"),
        new BigDecimal("125"),
        new BigDecimal("150"),
        new BigDecimal("175"),
        new BigDecimal("200")
    );

    public UiPreferencesV2Dto {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("Unsupported UI preference schemaVersion: " + schemaVersion);
        }
        Objects.requireNonNull(appearance, "appearance must not be null");
        Objects.requireNonNull(textScalePercent, "textScalePercent must not be null");
        Objects.requireNonNull(keyboard, "keyboard must not be null");
        if (!isAllowedScale(textScalePercent)) {
            throw new IllegalArgumentException("Unsupported textScalePercent: " + textScalePercent);
        }
    }

    public static boolean isAllowedScale(BigDecimal candidate) {
        if (candidate == null) {
            return false;
        }
        BigDecimal normalized = candidate.stripTrailingZeros();
        for (BigDecimal allowed : ALLOWED_TEXT_SCALES) {
            if (allowed.stripTrailingZeros().compareTo(normalized) == 0) {
                return true;
            }
        }
        return false;
    }

    public static UiPreferencesV2Dto defaults() {
        return new UiPreferencesV2Dto(
            2,
            AppearanceMode.SYSTEM,
            new BigDecimal("100"),
            KeyboardWireValue.defaults()
        );
    }

    public record KeyboardOverrideEntry(
        String contextId,
        String commandId,
        String chordId
    ) {
        public KeyboardOverrideEntry {
            Objects.requireNonNull(contextId, "contextId must not be null");
            Objects.requireNonNull(commandId, "commandId must not be null");
            Objects.requireNonNull(chordId, "chordId must not be null");
            if (contextId.isBlank() || commandId.isBlank() || chordId.isBlank()
                || contextId.length() > 128 || commandId.length() > 128 || chordId.length() > 128) {
                throw new IllegalArgumentException("Invalid keyboard override entry bounds.");
            }
        }
    }

    public record KeyboardWireValue(
        int version,
        ShortcutMode mode,
        String locale,
        String profileId,
        List<KeyboardOverrideEntry> overrides
    ) {
        public KeyboardWireValue {
            if (version != 1) {
                throw new IllegalArgumentException("Unsupported keyboard preference version: " + version);
            }
            Objects.requireNonNull(mode, "mode must not be null");
            Objects.requireNonNull(locale, "locale must not be null");
            if (!"en-GB".equals(locale)) {
                throw new IllegalArgumentException("Unsupported keyboard locale: " + locale);
            }
            if (profileId != null && profileId.length() > 128) {
                throw new IllegalArgumentException("profileId exceeds 128 characters.");
            }
            overrides = overrides == null ? List.of() : List.copyOf(overrides);
            if (overrides.size() > 256) {
                throw new IllegalArgumentException("Keyboard overrides exceed maximum of 256 items.");
            }
        }

        public static KeyboardWireValue defaults() {
            return new KeyboardWireValue(1, ShortcutMode.CHARACTER, "en-GB", null, List.of());
        }
    }
}
