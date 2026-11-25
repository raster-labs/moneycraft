package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Endpoints for the first-party host native public-client OAuth 2.0 + OIDC PKCE S256 issuer
 * (/api/v1/auth/native/*).
 */
@RestController
@RequestMapping("/api/v1/auth/native")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class NativeOAuthController {

    private final NativeOAuthPkceService nativeOAuthService;
    private final ObjectMapper objectMapper;

    public NativeOAuthController(
        NativeOAuthPkceService nativeOAuthService,
        ObjectMapper objectMapper
    ) {
        this.nativeOAuthService = nativeOAuthService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/.well-known/openid-configuration")
    public ResponseEntity<AuthDtos.NativeOidcDiscoveryDto> openidConfiguration(HttpServletRequest request) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(nativeOAuthService.buildDiscoveryDocument(request));
    }

    @GetMapping("/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(nativeOAuthService.buildJwksDocument());
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorizeBrowserRedirect(
        @RequestParam Map<String, String> queryParams,
        HttpServletRequest request
    ) {
        NativeOAuthPkceService.AuthorizeOutcome outcome =
            nativeOAuthService.handleBrowserAuthorizeGet(request, queryParams);
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .location(outcome.redirectTarget())
            .build();
    }

    @PostMapping("/authorize/approve")
    public ResponseEntity<AuthDtos.NativeAuthorizeApproveResponse> approveFromSpaSession(
        @RequestBody AuthDtos.NativeAuthorizeApproveRequest payload,
        HttpServletRequest request
    ) {
        AuthDtos.NativeAuthorizeApproveResponse approved =
            nativeOAuthService.approveFromAuthenticatedSession(request, payload);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(approved);
    }

    @PostMapping(
        value = "/token",
        consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<AuthDtos.NativeTokenResponseDto> tokenExchangeOrRefresh(
        HttpServletRequest request
    ) throws IOException {
        Map<String, String> params = extractFormOrJsonParameters(request);
        AuthDtos.NativeTokenResponseDto response = nativeOAuthService.exchangeOrRotateToken(params);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(response);
    }

    @PostMapping(
        value = "/revoke",
        consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<Void> revokeNativeToken(HttpServletRequest request) throws IOException {
        Map<String, String> params = extractFormOrJsonParameters(request);
        nativeOAuthService.revokeToken(params);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .build();
    }

    private Map<String, String> extractFormOrJsonParameters(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        byte[] bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
        if (lower.contains("application/json")) {
            if (bodyBytes.length == 0) {
                return Map.of();
            }
            try {
                JsonNode root = objectMapper.readTree(bodyBytes);
                if (!root.isObject()) {
                    throw new MoneyCraftException(
                        HttpStatus.BAD_REQUEST,
                        "MC-REQ-MALFORMED-JSON",
                        "Invalid JSON Object",
                        "Expected a JSON object body."
                    );
                }
                Map<String, String> out = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> field : root.properties()) {
                    JsonNode val = field.getValue();
                    if (val != null && !val.isNull() && val.isValueNode()) {
                        out.put(field.getKey(), val.asString());
                    }
                }
                return out;
            } catch (JacksonException ex) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-REQ-MALFORMED-JSON",
                    "Malformed JSON Body",
                    "Request body could not be parsed as valid JSON."
                );
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (bodyBytes.length > 0) {
            out.putAll(NativeOAuthPkceService.parseQueryString(new String(bodyBytes, StandardCharsets.UTF_8)));
        }
        request.getParameterMap().forEach((k, values) -> {
            if (values != null && values.length > 0 && !out.containsKey(k)) {
                out.put(k, values[0]);
            }
        });
        return out;
    }
}
