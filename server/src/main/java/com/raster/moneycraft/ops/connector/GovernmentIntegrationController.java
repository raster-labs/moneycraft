package com.raster.moneycraft.ops.connector;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SUPER_ADMIN-only Government Statutory & Connected Banking Integration Configuration API
 * (Docs 51 & 52, UM-110-04).
 */
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GovernmentIntegrationController {

    private final GovernmentIntegrationService integrationService;

    public GovernmentIntegrationController(GovernmentIntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    @GetMapping("/government-interface-inventory")
    public ResponseEntity<GovernmentIntegrationDtos.GovernmentInterfaceInventoryResponse> getCapabilityInventory() {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(integrationService.getInventory());
    }

    @GetMapping("/government-integration-profiles")
    public ResponseEntity<List<GovernmentIntegrationDtos.GovernmentIntegrationProfileDto>> listProfiles() {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(integrationService.listProfiles());
    }

    @PostMapping("/government-integration-profiles")
    public ResponseEntity<GovernmentIntegrationDtos.GovernmentIntegrationProfileDto> createProfile(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Step-Up-Intent", required = false) String stepUpHeader,
        HttpServletRequest request
    ) throws IOException {
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());
        GovernmentIntegrationService.ProfileMutationResult res =
            integrationService.createProfile(idempotencyKey, stepUpHeader, rawBody);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.ETAG, res.etag());
        if (res.replayed()) {
            builder.header("X-Idempotency-Replayed", "true");
        }
        return builder.body(res.profile());
    }

    @GetMapping("/government-integration-profiles/{profileId}")
    public ResponseEntity<GovernmentIntegrationDtos.GovernmentIntegrationProfileDto> getProfile(
        @PathVariable("profileId") UUID profileId
    ) {
        GovernmentIntegrationService.ProfileMutationResult res = integrationService.getProfile(profileId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.ETAG, res.etag())
            .body(res.profile());
    }

    @PutMapping("/government-integration-profiles/{profileId}")
    public ResponseEntity<GovernmentIntegrationDtos.GovernmentIntegrationProfileDto> updateProfile(
        @PathVariable("profileId") UUID profileId,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Step-Up-Intent", required = false) String stepUpHeader,
        HttpServletRequest request
    ) throws IOException {
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());
        GovernmentIntegrationService.ProfileMutationResult res =
            integrationService.updateProfile(profileId, ifMatch, idempotencyKey, stepUpHeader, rawBody);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.ETAG, res.etag());
        if (res.replayed()) {
            builder.header("X-Idempotency-Replayed", "true");
        }
        return builder.body(res.profile());
    }

    @PostMapping("/government-integration-profiles/{profileId}/credential-versions")
    public ResponseEntity<GovernmentIntegrationDtos.StagedCredentialVersionResponse> stageCredentials(
        @PathVariable("profileId") UUID profileId,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Step-Up-Intent", required = false) String stepUpHeader,
        HttpServletRequest request
    ) throws IOException {
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());
        GovernmentIntegrationService.CredentialStageResult res =
            integrationService.stageCredentialVersion(profileId, ifMatch, idempotencyKey, stepUpHeader, rawBody);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.ETAG, res.etag());
        if (res.replayed()) {
            builder.header("X-Idempotency-Replayed", "true");
        }
        return builder.body(res.response());
    }

    @PostMapping("/government-integration-profiles/{profileId}/validations")
    public ResponseEntity<GovernmentIntegrationDtos.ValidationOutcomeDto> startValidation(
        @PathVariable("profileId") UUID profileId,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Step-Up-Intent", required = false) String stepUpHeader,
        HttpServletRequest request
    ) throws IOException {
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());
        GovernmentIntegrationService.ValidationStartResult res =
            integrationService.startSafeValidation(profileId, ifMatch, idempotencyKey, stepUpHeader, rawBody);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.ACCEPTED)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.ETAG, res.etag())
            .location(URI.create(res.location()));
        if (res.replayed()) {
            builder.header("X-Idempotency-Replayed", "true");
        }
        return builder.body(res.outcome());
    }

    @GetMapping("/government-integration-profiles/{profileId}/validations/{validationId}")
    public ResponseEntity<GovernmentIntegrationDtos.ValidationOutcomeDto> getValidation(
        @PathVariable("profileId") UUID profileId,
        @PathVariable("validationId") UUID validationId
    ) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(integrationService.getValidationJob(profileId, validationId));
    }

    @PostMapping("/government-integration-profiles/{profileId}/activate")
    public ResponseEntity<GovernmentIntegrationDtos.GovernmentIntegrationProfileDto> activateProfile(
        @PathVariable("profileId") UUID profileId,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Step-Up-Intent", required = false) String stepUpHeader,
        HttpServletRequest request
    ) throws IOException {
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());
        GovernmentIntegrationService.ProfileMutationResult res =
            integrationService.activateProfile(profileId, ifMatch, idempotencyKey, stepUpHeader, rawBody);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.ETAG, res.etag());
        if (res.replayed()) {
            builder.header("X-Idempotency-Replayed", "true");
        }
        return builder.body(res.profile());
    }

    @PostMapping("/government-integration-profiles/{profileId}/disable")
    public ResponseEntity<GovernmentIntegrationDtos.GovernmentIntegrationProfileDto> disableProfile(
        @PathVariable("profileId") UUID profileId,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Step-Up-Intent", required = false) String stepUpHeader,
        HttpServletRequest request
    ) throws IOException {
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());
        GovernmentIntegrationService.ProfileMutationResult res =
            integrationService.disableProfile(profileId, ifMatch, idempotencyKey, stepUpHeader, rawBody);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.ETAG, res.etag());
        if (res.replayed()) {
            builder.header("X-Idempotency-Replayed", "true");
        }
        return builder.body(res.profile());
    }

    @GetMapping("/government-integration-profiles/{profileId}/audit")
    public ResponseEntity<List<GovernmentIntegrationDtos.ProfileAuditEntryDto>> getProfileAudit(
        @PathVariable("profileId") UUID profileId,
        @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(integrationService.getProfileAudit(profileId, limit));
    }
}
