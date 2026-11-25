package com.raster.moneycraft.foundation.preferences;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service authenticated PERSON UI presentation and keyboard preference endpoints (Doc 52 §2).
 */
@RestController
@RequestMapping("/api/v1/me/ui-preferences")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UiPreferencesController {

    private final UiPreferencesService uiPreferencesService;
    private final RbacAuthorizationService rbacGuard;

    public UiPreferencesController(
        UiPreferencesService uiPreferencesService,
        RbacAuthorizationService rbacGuard
    ) {
        this.uiPreferencesService = uiPreferencesService;
        this.rbacGuard = rbacGuard;
    }

    @GetMapping
    public ResponseEntity<UiPreferencesDtos.MeUiPreferencesResponse> getMyUiPreferences() {
        MoneyCraftPrincipal person = rbacGuard.requirePersonPrincipal();
        UiPreferencesService.PreferenceResult result = uiPreferencesService.readPreferences(person);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.ETAG, result.etag())
            .body(result.response());
    }

    @PatchMapping
    public ResponseEntity<UiPreferencesDtos.MeUiPreferencesResponse> patchMyUiPreferences(
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        HttpServletRequest request
    ) throws IOException {
        MoneyCraftPrincipal person = rbacGuard.requirePersonPrincipal();
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());
        UiPreferencesService.PreferenceResult result = uiPreferencesService.patchPreferences(
            person,
            ifMatch,
            idempotencyKey,
            rawBody
        );
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.ETAG, result.etag());
        if (result.replayed()) {
            builder.header("X-Idempotency-Replayed", "true");
        }
        return builder.body(result.response());
    }
}
