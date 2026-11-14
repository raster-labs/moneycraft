package com.raster.moneycraft.foundation.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalProblemExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalProblemExceptionHandler.class);
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler(MoneyCraftException.class)
    public ResponseEntity<ProblemDetail> handleMoneyCraftException(
        MoneyCraftException ex,
        HttpServletRequest request
    ) {
        ProblemDetail pd = buildProblem(
            ex.status(),
            ex.code(),
            ex.title(),
            ex.getMessage(),
            request.getRequestURI(),
            ex.fieldErrors()
        );
        return ResponseEntity.status(ex.status()).contentType(PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
        MethodArgumentNotValidException ex,
        HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage());
        }
        ProblemDetail pd = buildProblem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MC-VAL-001",
            "Request Validation Failed",
            "One or more fields failed MoneyCraft validation rules.",
            request.getRequestURI(),
            fieldErrors
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).contentType(PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMalformedJson(
        HttpMessageNotReadableException ex,
        HttpServletRequest request
    ) {
        ProblemDetail pd = buildProblem(
            HttpStatus.BAD_REQUEST,
            "MC-REQ-MALFORMED-JSON",
            "Malformed JSON Request",
            "Request body could not be parsed as valid JSON.",
            request.getRequestURI(),
            Map.of()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuth(
        AuthenticationException ex,
        HttpServletRequest request
    ) {
        ProblemDetail pd = buildProblem(
            HttpStatus.UNAUTHORIZED,
            "MC-AUTH-UNAUTHENTICATED",
            "Authentication Required",
            ex.getMessage() == null ? "Valid session or API key required." : ex.getMessage(),
            request.getRequestURI(),
            Map.of()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
        AccessDeniedException ex,
        HttpServletRequest request
    ) {
        ProblemDetail pd = buildProblem(
            HttpStatus.FORBIDDEN,
            "MC-RBAC-FORBIDDEN",
            "Access Denied",
            ex.getMessage() == null ? "Insufficient role or company grant." : ex.getMessage(),
            request.getRequestURI(),
            Map.of()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> handleDuplicate(
        DuplicateKeyException ex,
        HttpServletRequest request
    ) {
        ProblemDetail pd = buildProblem(
            HttpStatus.CONFLICT,
            "MC-DATA-DUPLICATE",
            "Duplicate Resource Conflict",
            "A resource with the same unique identifier or code already exists.",
            request.getRequestURI(),
            Map.of()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> handleDataAccess(
        DataAccessException ex,
        HttpServletRequest request
    ) {
        String rawMsg = ex.getMostSpecificCause().getMessage();
        if (rawMsg != null) {
            if (rawMsg.contains("MC-EDITLOG-MANDATORY")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(PROBLEM_JSON).body(
                    buildProblem(
                        HttpStatus.FORBIDDEN,
                        "MC-EDITLOG-MANDATORY",
                        "MCA Edit Log Mandatory",
                        "mca_edit_log_enforced cannot be disabled once enabled.",
                        request.getRequestURI(),
                        Map.of()
                    )
                );
            }
            if (rawMsg.contains("MC-ACC-FY-OVERLAP")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).contentType(PROBLEM_JSON).body(
                    buildProblem(
                        HttpStatus.CONFLICT,
                        "MC-ACC-FY-OVERLAP",
                        "Fiscal Year Overlap",
                        "Overlapping fiscal year date range within company.",
                        request.getRequestURI(),
                        Map.of()
                    )
                );
            }
            if (rawMsg.contains("MC-ACC-PERIOD-OVERLAP") || rawMsg.contains("MC-ACC-PERIOD-OUT-OF-FY")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).contentType(PROBLEM_JSON).body(
                    buildProblem(
                        HttpStatus.CONFLICT,
                        "MC-ACC-PERIOD-CONFLICT",
                        "Accounting Period Range Conflict",
                        "Accounting period overlaps an existing period or falls outside parent fiscal year.",
                        request.getRequestURI(),
                        Map.of()
                    )
                );
            }
            if (rawMsg.contains("MC-IMMUTABLE-RECORD")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(PROBLEM_JSON).body(
                    buildProblem(
                        HttpStatus.FORBIDDEN,
                        "MC-IMMUTABLE-RECORD",
                        "Immutable Append-Only Record",
                        "Append-only audit and ledger records cannot be mutated or deleted.",
                        request.getRequestURI(),
                        Map.of()
                    )
                );
            }
        }
        LOG.error("Database exception on {}", request.getRequestURI(), ex);
        ProblemDetail pd = buildProblem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "MC-DB-500",
            "Database Persistence Error",
            "A database constraint or persistence error occurred.",
            request.getRequestURI(),
            Map.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleFallback(
        Exception ex,
        HttpServletRequest request
    ) {
        LOG.error("Unhandled exception on {}", request.getRequestURI(), ex);
        ProblemDetail pd = buildProblem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "MC-SYS-500",
            "Internal Server Error",
            "An unexpected internal error occurred.",
            request.getRequestURI(),
            Map.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(PROBLEM_JSON).body(pd);
    }

    public static ProblemDetail buildProblem(
        HttpStatus status,
        String code,
        String title,
        String detail,
        String instanceUri,
        Map<String, String> fieldErrors
    ) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        pd.setTitle(title);
        pd.setType(URI.create("urn:moneycraft:problem:" + code.toLowerCase(Locale.ROOT)));
        if (instanceUri != null && !instanceUri.isBlank()) {
            pd.setInstance(URI.create(instanceUri));
        }
        pd.setProperty("code", code);
        pd.setProperty("errorCode", code);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            pd.setProperty("fieldErrors", fieldErrors);
        }
        return pd;
    }
}
