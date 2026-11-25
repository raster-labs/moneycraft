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
    private final Map<String, Object> extraProperties;
    private final String responseEtag;

    public MoneyCraftException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, Collections.emptyMap(), Collections.emptyMap(), null);
    }

    public MoneyCraftException(
        HttpStatus status,
        String code,
        String title,
        String detail,
        Map<String, String> fieldErrors
    ) {
        this(status, code, title, detail, fieldErrors, Collections.emptyMap(), null);
    }

    public MoneyCraftException(
        HttpStatus status,
        String code,
        String title,
        String detail,
        Map<String, String> fieldErrors,
        Map<String, Object> extraProperties,
        String responseEtag
    ) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.fieldErrors = fieldErrors == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
        this.extraProperties = extraProperties == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(extraProperties));
        this.responseEtag = responseEtag;
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

    public Map<String, Object> extraProperties() {
        return extraProperties;
    }

    public String responseEtag() {
        return responseEtag;
    }
}
