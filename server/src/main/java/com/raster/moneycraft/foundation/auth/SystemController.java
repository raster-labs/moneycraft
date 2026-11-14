package com.raster.moneycraft.foundation.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MoneyCraft installation status and first-run ceremony endpoints (/api/v1/system).
 */
@RestController
@RequestMapping("/api/v1/system")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SystemController {

    private final SystemBootstrapService systemBootstrapService;
    private final AuthService moneyCraftAuthService;
    private final CookieCsrfTokenRepository xsrfCookieRepository;

    public SystemController(
        SystemBootstrapService systemBootstrapService,
        AuthService moneyCraftAuthService,
        CookieCsrfTokenRepository xsrfCookieRepository
    ) {
        this.systemBootstrapService = systemBootstrapService;
        this.moneyCraftAuthService = moneyCraftAuthService;
        this.xsrfCookieRepository = xsrfCookieRepository;
    }

    @GetMapping("/status")
    public AuthDtos.SystemStatusResponse queryInstallationStatus() {
        return systemBootstrapService.getSystemStatus();
    }

    @PostMapping("/initialise")
    public ResponseEntity<AuthDtos.InitialiseInstallationResponse> executeFirstRunBootstrap(
        @RequestBody AuthDtos.InitialiseInstallationRequest payload,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        SystemBootstrapService.BootstrapResult ceremony = systemBootstrapService.initialiseInstallation(payload);
        AuthDtos.AuthenticatedPrincipalDto ownerDto = moneyCraftAuthService.establishSession(
            servletRequest,
            servletResponse,
            xsrfCookieRepository,
            ceremony.ownerPrincipal()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(
            new AuthDtos.InitialiseInstallationResponse(
                ceremony.installationId(),
                ownerDto,
                ceremony.recoveryReceiptCode()
            )
        );
    }
}
