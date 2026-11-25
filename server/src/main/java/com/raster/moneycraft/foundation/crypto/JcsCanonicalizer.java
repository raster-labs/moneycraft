package com.raster.moneycraft.foundation.crypto;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JcsCanonicalizer {

    private static final BigDecimal LOWER_FIXED_BOUND = new BigDecimal("0.000001");
    private static final BigDecimal UPPER_FIXED_BOUND = new BigDecimal("1000000000000000000000");

    private final JsonMapper jsonMapper;

    public JcsCanonicalizer() {
        this.jsonMapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    }

    public String canonicalizeJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "{}";
        }
        try {
            JsonNode tree = jsonMapper.readTree(rawJson);
            return canonicalizeNode(tree);
        } catch (JacksonException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-JCS-INVALID-JSON",
                "Invalid JSON Payload",
                "Payload could not be canonicalized under RFC 8785 JCS."
            );
        }
    }

    public String canonicalizeObject(Object value) {
        if (value == null) {
            return "null";
        }
        JsonNode tree = jsonMapper.valueToTree(value);
        return canonicalizeNode(tree);
    }

    public byte[] canonicalizeUtf8(String rawJson) {
        return canonicalizeJson(rawJson).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] canonicalize(byte[] rawJsonUtf8) {
        if (rawJsonUtf8 == null || rawJsonUtf8.length == 0) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
        return canonicalizeUtf8(new String(rawJsonUtf8, StandardCharsets.UTF_8));
    }

    public String canonicalizeNode(JsonNode node) {
        StringBuilder sb = new StringBuilder(128);
        appendNode(node, sb);
        return sb.toString();
    }

    public byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in JVM", ex);
        }
    }

    public byte[] sha256Concat(byte[] first, byte[] second) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(first);
            return md.digest(second);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in JVM", ex);
        }
    }

    public byte[] hmacSha256(byte[] secretKey, byte[] canonicalUtf8) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            return mac.doFinal(canonicalUtf8);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HmacSHA256 calculation failed", ex);
        }
    }

    public static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private void appendNode(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isString()) {
            appendEscapedString(node.stringValue(), sb);
            return;
        }
        if (node.isNumber()) {
            appendCanonicalNumber(node.decimalValue(), sb);
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            int len = node.size();
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendNode(node.get(i), sb);
            }
            sb.append(']');
            return;
        }
        if (node.isObject()) {
            sb.append('{');
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Map.Entry<String, JsonNode> entry = entries.get(i);
                appendEscapedString(entry.getKey(), sb);
                sb.append(':');
                appendNode(entry.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        appendEscapedString(node.asString(""), sb);
    }

    private static void appendEscapedString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (ch < 0x20) {
                        sb.append("\\u00");
                        sb.append(Character.forDigit((ch >> 4) & 0xF, 16));
                        sb.append(Character.forDigit(ch & 0xF, 16));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void appendCanonicalNumber(BigDecimal raw, StringBuilder sb) {
        if (raw.compareTo(BigDecimal.ZERO) == 0) {
            sb.append('0');
            return;
        }
        BigDecimal normalized = raw.stripTrailingZeros();
        BigDecimal abs = normalized.abs();
        if (abs.compareTo(LOWER_FIXED_BOUND) >= 0 && abs.compareTo(UPPER_FIXED_BOUND) < 0) {
            sb.append(normalized.toPlainString());
            return;
        }
        String unscaled = normalized.abs().unscaledValue().toString();
        int exponent = unscaled.length() - 1 - normalized.scale();
        if (normalized.signum() < 0) {
            sb.append('-');
        }
        sb.append(unscaled.charAt(0));
        if (unscaled.length() > 1) {
            sb.append('.').append(unscaled, 1, unscaled.length());
        }
        sb.append('e');
        if (exponent >= 0) {
            sb.append('+');
        }
        sb.append(exponent);
    }
}
