package com.raster.moneycraft.desktop.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 9457 ProblemDetail exception returned by the authoritative MoneyCraft server.
 */
public final class ApiProblemException extends RuntimeException {

    private final int statusCode;
    private final String code;
    private final String errorCode;
    private final String title;
    private final String detail;
    private final Map<String, String> fieldErrors;

    public ApiProblemException(
        int statusCode,
        String code,
        String errorCode,
        String title,
        String detail,
        Map<String, String> fieldErrors
    ) {
        super(formatMessage(statusCode, code, title, detail));
        this.statusCode = statusCode;
        this.code = code == null ? "MC-HTTP-" + statusCode : code;
        this.errorCode = errorCode == null ? this.code : errorCode;
        this.title = title == null ? "HTTP " + statusCode : title;
        this.detail = detail == null ? "" : detail;
        this.fieldErrors = fieldErrors == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public int statusCode() {
        return statusCode;
    }

    public String code() {
        return code;
    }

    public String errorCode() {
        return errorCode;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    public boolean isStaleOptimisticLock() {
        return statusCode == 412;
    }

    public boolean isForbidden() {
        return statusCode == 403;
    }

    public boolean isUnauthorized() {
        return statusCode == 401;
    }

    public boolean isConflict() {
        return statusCode == 409;
    }

    private static String formatMessage(int statusCode, String code, String title, String detail) {
        String effectiveCode = code == null ? "MC-HTTP-" + statusCode : code;
        String effectiveTitle = title == null ? "API Error" : title;
        String effectiveDetail = detail == null ? "" : ": " + detail;
        return "[" + statusCode + " " + effectiveCode + "] " + effectiveTitle + effectiveDetail;
    }
}
