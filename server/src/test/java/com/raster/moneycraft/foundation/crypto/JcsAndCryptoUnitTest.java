package com.raster.moneycraft.foundation.crypto;

import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.PasswordHasherConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Verifies RFC 8785 JCS deterministic canonicalization, exact BigDecimal RoundingMode.UNNECESSARY
 * validation, Luhn Mod-36 GSTIN verification, and full 65536 KiB Argon2id password parameters.
 */
class JcsAndCryptoUnitTest {

    private final JcsCanonicalizer jcs = new JcsCanonicalizer();

    @Test
    void rfc8785JcsSortsKeysAndNormalizesWhitespaceAndNumbers() {
        String inputA = "{ \"z\": [ 3, 1, 2 ], \"a\": 100.0, \"m\": { \"b\": true, \"a\": null } }";
        String inputB = "{\"m\":{\"a\":null,\"b\":true},\"a\":100,\"z\":[3,1,2]}";

        String canonA = jcs.canonicalizeJson(inputA);
        String canonB = jcs.canonicalizeJson(inputB);

        Assertions.assertThat(canonA)
            .isEqualTo("{\"a\":100,\"m\":{\"a\":null,\"b\":true},\"z\":[3,1,2]}")
            .isEqualTo(canonB);

        byte[] key = "installation-hmac-secret-key-32b".getBytes(StandardCharsets.UTF_8);
        byte[] macA = jcs.hmacSha256(key, canonA.getBytes(StandardCharsets.UTF_8));
        byte[] macB = jcs.hmacSha256(key, canonB.getBytes(StandardCharsets.UTF_8));
        Assertions.assertThat(macA).hasSize(32).isEqualTo(macB);
    }

    @Test
    void rfc8785JcsEscapesControlCharactersDeterministically() {
        String serialized = jcs.canonicalizeObject(Map.of("line", "A\nB\tC\"D\\E"));
        Assertions.assertThat(serialized).isEqualTo("{\"line\":\"A\\nB\\tC\\\"D\\\\E\"}");
    }

    @Test
    void exactMoneyValidatorEnforcesRoundingModeUnnecessaryAndBounds() {
        BigDecimal accepted = ExactMoneyValidator.parseAndValidateExactScale("125000.5", 2, "amount");
        Assertions.assertThat(accepted).isEqualByComparingTo(new BigDecimal("125000.50"));
        Assertions.assertThat(accepted.scale()).isEqualTo(2);

        Assertions.assertThatThrownBy(() ->
            ExactMoneyValidator.parseAndValidateExactScale("125000.505", 2, "amount")
        )
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-ACC-SCALE-OVERFLOW");

        Assertions.assertThatThrownBy(() ->
            ExactMoneyValidator.parseAndValidateExactScale("-1.00", 2, "amount")
        )
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-ACC-AMOUNT-BOUNDS");
    }

    @Test
    void gstinMod36ValidatorVerifiesCheckDigitAndStatePrefix() {
        String prefix14 = "33AABCM1234F1Z";
        char checkChar = ExactMoneyValidator.computeGstinMod36CheckChar(prefix14);
        String validGstin = prefix14 + checkChar;

        Assertions.assertThat(ExactMoneyValidator.validateGstin(validGstin, "33"))
            .isEqualTo(validGstin);

        char badChar = checkChar == '9' ? '8' : '9';
        Assertions.assertThatThrownBy(() ->
            ExactMoneyValidator.validateGstin(prefix14 + badChar, "33")
        )
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-STAT-INVALID-GSTIN");

        Assertions.assertThatThrownBy(() ->
            ExactMoneyValidator.validateGstin(validGstin, "29")
        )
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-STAT-GSTIN-STATE-MISMATCH");
    }

    @Test
    void argon2idUsesMandatory65536KibThreeIterationsSingleParallelism() {
        MoneyCraftProperties props = new MoneyCraftProperties();
        Argon2PasswordEncoder encoder = new PasswordHasherConfig().argon2PasswordEncoder(props);

        String encoded = encoder.encode("CorrectHorseBatteryStaple#2026");
        Assertions.assertThat(encoded).startsWith("$argon2id$v=19$m=65536,t=3,p=1$");
        Assertions.assertThat(encoder.matches("CorrectHorseBatteryStaple#2026", encoded)).isTrue();
        Assertions.assertThat(encoder.matches("WrongPassword#2026", encoded)).isFalse();
    }
}
