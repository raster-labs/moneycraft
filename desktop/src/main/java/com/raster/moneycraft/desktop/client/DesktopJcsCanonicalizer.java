package com.raster.moneycraft.desktop.client;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Exact-decimal JSON mapper and RFC 8785 JSON Canonicalization Scheme (JCS) helper
 * for client-independent MoneyCraft desktop wire payloads (SWG-001).
 */
public final class DesktopJcsCanonicalizer {

    private static final BigDecimal LOWER_FIXED_BOUND = new BigDecimal("0.000001");
    private static final BigDecimal UPPER_FIXED_BOUND = new BigDecimal("1000000000000000000000");

    private final JsonMapper wireMapper;
    private final JsonMapper jcsMapper;

    public DesktopJcsCanonicalizer() {
        SimpleModule exactDecimalModule = new SimpleModule("MoneyCraftDesktopExactDecimal");
        exactDecimalModule.addSerializer(BigDecimal.class, new PlainBigDecimalStringSerializer());

        this.wireMapper = JsonMapper.builder()
            .addModule(exactDecimalModule)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

        this.jcsMapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    }

    public JsonMapper wireMapper() {
        return wireMapper;
    }

    public String writeWireJson(Object value) {
        try {
            return wireMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Failed to serialize wire payload as JSON.", ex);
        }
    }

    public String canonicalizeJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "{}";
        }
        try {
            JsonNode tree = jcsMapper.readTree(rawJson);
            return canonicalizeNode(tree);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Payload could not be canonicalized under RFC 8785 JCS.", ex);
        }
    }

    public String canonicalizeObject(Object value) {
        if (value == null) {
            return "null";
        }
        String serialized = writeWireJson(value);
        return canonicalizeJson(serialized);
    }

    public String canonicalizeNode(JsonNode node) {
        StringBuilder sb = new StringBuilder(128);
        appendNode(node, sb);
        return sb.toString();
    }

    public String sha256Hex(String utf8Text) {
        Objects.requireNonNull(utf8Text, "utf8Text must not be null");
        return sha256Hex(utf8Text.getBytes(StandardCharsets.UTF_8));
    }

    public String sha256Hex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in Java runtime.", ex);
        }
    }

    /**
     * Derives a deterministic, RFC 8785 JCS-bound Idempotency-Key for a client intention.
     */
    public String deriveIdempotencyKey(String operationCode, UUID intentionId, Object payload) {
        Objects.requireNonNull(operationCode, "operationCode must not be null");
        Objects.requireNonNull(intentionId, "intentionId must not be null");
        if (operationCode.isBlank()) {
            throw new IllegalArgumentException("operationCode must not be blank");
        }
        String canonical = canonicalizeObject(payload);
        String material = operationCode.trim() + "|" + intentionId + "|" + canonical;
        String digest = sha256Hex(material);
        return "mc-swg-" + intentionId + "-" + digest.substring(0, 24);
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
            int size = node.size();
            for (int i = 0; i < size; i++) {
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
        String unscaled = abs.unscaledValue().toString();
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

    private static final class PlainBigDecimalStringSerializer extends ValueSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(value.toPlainString());
            }
        }
    }
}
