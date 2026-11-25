package com.raster.moneycraft.desktop.preferences;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Account preference envelope and PATCH request DTOs for {@code /api/v1/me/ui-preferences} (Doc 52 §2).
 */
public record UiPreferencesEnvelopeDto(
    UiPreferencesV2Dto preferences,
    String revision,
    String subjectScope
) {

    public UiPreferencesEnvelopeDto {
        Objects.requireNonNull(preferences, "preferences must not be null");
        Objects.requireNonNull(revision, "revision must not be null");
        Objects.requireNonNull(subjectScope, "subjectScope must not be null");
        if (subjectScope.length() < 16 || subjectScope.length() > 128) {
            throw new IllegalArgumentException("subjectScope must be 16..128 characters.");
        }
    }

    public record PatchChanges(
        AppearanceMode appearance,
        BigDecimal textScalePercent,
        UiPreferencesV2Dto.KeyboardWireValue keyboard
    ) {
        public PatchChanges {
            if (appearance == null && textScalePercent == null && keyboard == null) {
                throw new IllegalArgumentException("At least one preference change property must be supplied.");
            }
            if (textScalePercent != null && !UiPreferencesV2Dto.isAllowedScale(textScalePercent)) {
                throw new IllegalArgumentException("Unsupported textScalePercent: " + textScalePercent);
            }
        }
    }

    public record PatchRequest(
        int schemaVersion,
        PatchChanges changes
    ) {
        public PatchRequest {
            if (schemaVersion != 2) {
                throw new IllegalArgumentException("PATCH schemaVersion must be 2.");
            }
            Objects.requireNonNull(changes, "changes must not be null");
        }
    }
}
