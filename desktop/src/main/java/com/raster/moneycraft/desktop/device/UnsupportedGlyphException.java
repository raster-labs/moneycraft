package com.raster.moneycraft.desktop.device;

/**
 * Thrown when a required character (such as U+20B9 Indian Rupee or customer text) cannot be
 * represented by the admitted printer profile's code page without silent corruption (AT09-DEV-001-B).
 */
public final class UnsupportedGlyphException extends DeviceSecurityException {

    private final String fieldName;
    private final int codePoint;
    private final String charsetName;

    public UnsupportedGlyphException(String fieldName, int codePoint, String charsetName, String guidance) {
        super(
            "MC-PRINT-UNSUPPORTED-GLYPH",
            "Cannot represent glyph U+" + String.format("%04X", codePoint)
                + " in field '" + fieldName + "' using charset '" + charsetName + "'. " + guidance
        );
        this.fieldName = fieldName;
        this.codePoint = codePoint;
        this.charsetName = charsetName;
    }

    public String fieldName() {
        return fieldName;
    }

    public int codePoint() {
        return codePoint;
    }

    public String charsetName() {
        return charsetName;
    }
}
