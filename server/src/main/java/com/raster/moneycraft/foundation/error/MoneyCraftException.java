package com.raster.moneycraft.foundation.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class MoneyCraftException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Map<String, String> fieldErrors;

    public MoneyCraftException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, Collections.emptyMap());
    }

    public MoneyCraftException(
        HttpStatus status,
        String code,
        String title,
        String detail,
        Map<String, String> fieldErrors
    ) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.fieldErrors = fieldErrors == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
