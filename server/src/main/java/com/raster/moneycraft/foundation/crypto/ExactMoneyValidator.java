package com.raster.moneycraft.foundation.crypto;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class ExactMoneyValidator {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000000000");
    private static final String MOD36_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern GSTIN_REGEX = Pattern.compile(
        "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$"
    );
    private static final Pattern PAN_REGEX = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    private ExactMoneyValidator() {
    }

    public static String formatExactScale(BigDecimal amount, int scale) {
        BigDecimal safe = amount == null ? BigDecimal.ZERO : amount;
        return safe.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    public static String formatPlainDecimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.scale() < 0) {
            return value.setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        }
        return value.toPlainString();
    }

    public static BigDecimal parseAndValidateBoundedDecimal(
        String rawValue,
        int maxScale,
        BigDecimal minInclusive,
        BigDecimal maxInclusive,
        boolean strictlyPositive,
        String fieldName
    ) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-DECIMAL-REQUIRED",
                "Decimal Value Required",
                "Field '" + fieldName + "' must be a non-empty exact decimal string.",
                Map.of(fieldName, "Required exact decimal string")
            );
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(rawValue.trim());
        } catch (NumberFormatException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-DECIMAL-INVALID",
                "Invalid Decimal Format",
                "Field '" + fieldName + "' is not a valid decimal representation.",
                Map.of(fieldName, "Invalid decimal format")
            );
        }
        try {
            parsed.setScale(maxScale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-SCALE-OVERFLOW",
                "Excess Decimal Scale",
                "Field '" + fieldName + "' exceeds permitted fractional scale " + maxScale + ".",
                Map.of(fieldName, "Maximum fractional digits allowed: " + maxScale)
            );
        }
        if (parsed.scale() < 0) {
            parsed = parsed.setScale(0, RoundingMode.UNNECESSARY);
        }
        if (strictlyPositive && parsed.signum() <= 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-AMOUNT-BOUNDS",
                "Positive Value Required",
                "Field '" + fieldName + "' must be strictly greater than zero.",
                Map.of(fieldName, "Must be greater than 0")
            );
        }
        if (minInclusive != null && parsed.compareTo(minInclusive) < 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-AMOUNT-BOUNDS",
                "Decimal Value Below Minimum",
                "Field '" + fieldName + "' must be >= " + minInclusive.toPlainString() + ".",
                Map.of(fieldName, "Below minimum allowed value")
            );
        }
        if (maxInclusive != null && parsed.compareTo(maxInclusive) > 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-AMOUNT-BOUNDS",
                "Decimal Value Above Maximum",
                "Field '" + fieldName + "' must be <= " + maxInclusive.toPlainString() + ".",
                Map.of(fieldName, "Above maximum allowed value")
            );
        }
        if (maxInclusive == null && parsed.compareTo(MAX_AMOUNT) >= 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-AMOUNT-BOUNDS",
                "Decimal Value Out of Bounds",
                "Field '" + fieldName + "' must be < 10^18.",
                Map.of(fieldName, "Value out of bounds")
            );
        }
        return parsed;
    }

    public static BigDecimal parseAndValidateExactScale(String rawAmount, int maxScale, String fieldName) {
        if (rawAmount == null || rawAmount.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-DECIMAL-REQUIRED",
                "Monetary Value Required",
                "Field '" + fieldName + "' must be a non-empty exact decimal string.",
                Map.of(fieldName, "Required exact decimal string")
            );
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(rawAmount.trim());
        } catch (NumberFormatException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-DECIMAL-INVALID",
                "Invalid Monetary Format",
                "Field '" + fieldName + "' is not a valid decimal representation.",
                Map.of(fieldName, "Invalid decimal format")
            );
        }
        try {
            parsed = parsed.setScale(maxScale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-SCALE-OVERFLOW",
                "Excess Decimal Scale",
                "Field '" + fieldName + "' exceeds permitted minor-unit scale " + maxScale + ".",
                Map.of(fieldName, "Maximum fractional digits allowed: " + maxScale)
            );
        }
        if (parsed.signum() < 0 || parsed.compareTo(MAX_AMOUNT) >= 0) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-AMOUNT-BOUNDS",
                "Monetary Value Out of Bounds",
                "Field '" + fieldName + "' must satisfy 0 <= amount < 10^18.",
                Map.of(fieldName, "Amount out of bounds")
            );
        }
        return parsed;
    }

    public static String validateGstin(String rawGstin, String expectedStateCode) {
        String normalized = rawGstin == null ? "" : rawGstin.trim().toUpperCase(Locale.ROOT);
        if (!GSTIN_REGEX.matcher(normalized).matches()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-STAT-INVALID-GSTIN",
                "Invalid GSTIN Format",
                "GSTIN must be 15 characters matching India GSTIN structure.",
                Map.of("gstin", "Invalid 15-character GSTIN structure")
            );
        }
        if (expectedStateCode != null && !expectedStateCode.isBlank()) {
            String paddedState = expectedStateCode.trim().length() == 1
                ? "0" + expectedStateCode.trim()
                : expectedStateCode.trim();
            if (!normalized.startsWith(paddedState)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-STAT-GSTIN-STATE-MISMATCH",
                    "GSTIN State Prefix Mismatch",
                    "GSTIN state prefix '" + normalized.substring(0, 2)
                        + "' does not match company state code '" + paddedState + "'.",
                    Map.of("gstin", "State prefix does not match selected state")
                );
            }
        }
        char expectedCheck = computeGstinMod36CheckChar(normalized.substring(0, 14));
        if (normalized.charAt(14) != expectedCheck) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-STAT-INVALID-GSTIN",
                "Invalid GSTIN Checksum",
                "GSTIN Luhn Mod-36 check digit failed (expected '" + expectedCheck + "').",
                Map.of("gstin", "Luhn Mod-36 checksum mismatch")
            );
        }
        return normalized;
    }

    public static char computeGstinMod36CheckChar(String first14) {
        int factor = 1;
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int codePoint = MOD36_ALPHABET.indexOf(first14.charAt(i));
            int product = codePoint * factor;
            sum += (product / 36) + (product % 36);
            factor = factor == 1 ? 2 : 1;
        }
        int remainder = sum % 36;
        int checkIndex = (36 - remainder) % 36;
        return MOD36_ALPHABET.charAt(checkIndex);
    }

    public static String validatePartyGstin(String rawGstin, String expectedStateCode) {
        String normalized = rawGstin == null ? "" : rawGstin.trim().toUpperCase(Locale.ROOT);
        if (!GSTIN_REGEX.matcher(normalized).matches()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TAX-INVALID-GSTIN",
                "Invalid Party GSTIN Format",
                "Party GSTIN must be 15 characters matching India GSTIN structure.",
                Map.of("gstin", "Invalid 15-character GSTIN structure")
            );
        }
        if (expectedStateCode != null && !expectedStateCode.isBlank()) {
            String paddedState = expectedStateCode.trim().length() == 1
                ? "0" + expectedStateCode.trim()
                : expectedStateCode.trim();
            if (!normalized.startsWith(paddedState)) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-TAX-INVALID-GSTIN",
                    "GSTIN State Prefix Mismatch",
                    "Party GSTIN state prefix '" + normalized.substring(0, 2)
                        + "' does not match state code '" + paddedState + "'.",
                    Map.of("gstin", "State prefix does not match selected state")
                );
            }
        }
        char expectedCheck = computeGstinMod36CheckChar(normalized.substring(0, 14));
        if (normalized.charAt(14) != expectedCheck) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TAX-INVALID-GSTIN",
                "Invalid Party GSTIN Checksum",
                "Party GSTIN Luhn Mod-36 check digit failed (expected '" + expectedCheck + "').",
                Map.of("gstin", "Luhn Mod-36 checksum mismatch")
            );
        }
        return normalized;
    }

    public static String validatePan(String rawPan) {
        String normalized = rawPan == null ? "" : rawPan.trim().toUpperCase(Locale.ROOT);
        if (!PAN_REGEX.matcher(normalized).matches()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TAX-INVALID-PAN",
                "Invalid PAN Format",
                "PAN must be 10 characters matching [A-Z]{5}[0-9]{4}[A-Z].",
                Map.of("pan", "Invalid 10-character PAN format")
            );
        }
        return normalized;
    }

    public static String maskPan(String pan) {
        if (pan == null || pan.length() != 10) {
            return null;
        }
        return pan.substring(0, 5) + "****" + pan.substring(9);
    }
}
