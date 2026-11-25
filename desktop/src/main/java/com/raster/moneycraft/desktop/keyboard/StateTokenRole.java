package com.raster.moneycraft.desktop.keyboard;

/**
 * Exhaustive semantic button/action token roles from Document 45 and Document 46 (VIS-001).
 */
public enum StateTokenRole {
    ACTION_PRIMARY("action.primary"),
    ACTION_SECONDARY("action.secondary"),
    ACTION_DANGER("action.danger");

    private final String wireToken;

    StateTokenRole(String wireToken) {
        this.wireToken = wireToken;
    }

    public String wireToken() {
        return wireToken;
    }
}
