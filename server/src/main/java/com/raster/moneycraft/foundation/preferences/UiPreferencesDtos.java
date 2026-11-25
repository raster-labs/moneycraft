package com.raster.moneycraft.foundation.preferences;

import java.math.BigDecimal;
import java.util.List;

/**
 * Closed wire contracts for GET/PATCH /api/v1/me/ui-preferences (Doc 50, Doc 52 §2, ui_preferences.schema.json).
 */
public final class UiPreferencesDtos {

    public static final int SCHEMA_VERSION_2 = 2;
    public static final int KEYBOARD_VERSION_1 = 1;
    public static final String LOCALE_EN_GB = "en-GB";

    private UiPreferencesDtos() {
    }

    public record KeyboardOverrideDto(
        String contextId,
        String commandId,
        String chordId
    ) {
    }

    public record KeyboardPreferenceV1Dto(
        int version,
        String mode,
        String locale,
        String profileId,
        List<KeyboardOverrideDto> overrides
    ) {
        public static KeyboardPreferenceV1Dto defaults() {
            return new KeyboardPreferenceV1Dto(
                KEYBOARD_VERSION_1,
                "CHARACTER",
                LOCALE_EN_GB,
                null,
                List.of()
            );
        }
    }

    public record UiPreferencesV2Dto(
        int schemaVersion,
        String appearance,
        BigDecimal textScalePercent,
        KeyboardPreferenceV1Dto keyboard
    ) {
        public static UiPreferencesV2Dto defaults() {
            return new UiPreferencesV2Dto(
                SCHEMA_VERSION_2,
                "SYSTEM",
                new BigDecimal("100"),
                KeyboardPreferenceV1Dto.defaults()
            );
        }
    }

    public record MeUiPreferencesResponse(
        UiPreferencesV2Dto preferences,
        String revision,
        String subjectScope
    ) {
    }
}
