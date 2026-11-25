package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MoneyCraft operator authentication, step-up proof, FIDO2 passkey ceremony, and OIDC PKCE endpoints.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {

    private final AuthService moneyCraftAuthService;
    private final StepUpAuthService stepUpAuthService;
    private final PasskeyWebAuthnService fidoPasskeyService;
    private final OidcPkceService enterpriseOidcService;
    private final RbacAuthorizationService rbacGuard;
    private final CookieCsrfTokenRepository xsrfCookieRepository;

    public AuthController(
        AuthService moneyCraftAuthService,
        StepUpAuthService stepUpAuthService,
        PasskeyWebAuthnService fidoPasskeyService,
        OidcPkceService enterpriseOidcService,
        RbacAuthorizationService rbacGuard,
        CookieCsrfTokenRepository xsrfCookieRepository
    ) {
        this.moneyCraftAuthService = moneyCraftAuthService;
        this.stepUpAuthService = stepUpAuthService;
        this.fidoPasskeyService = fidoPasskeyService;
        this.enterpriseOidcService = enterpriseOidcService;
        this.rbacGuard = rbacGuard;
        this.xsrfCookieRepository = xsrfCookieRepository;
    }

    @PostMapping("/login")
    public AuthDtos.AuthenticatedPrincipalDto signInWithArgon2Password(
        @RequestBody AuthDtos.LoginRequest credentials,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        MoneyCraftPrincipal operator = moneyCraftAuthService.authenticatePassword(credentials);
        return moneyCraftAuthService.establishSession(
            servletRequest,
            servletResponse,
            xsrfCookieRepository,
            operator
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> signOutCurrentSession(
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        moneyCraftAuthService.logoutSession(servletRequest, servletResponse, xsrfCookieRepository);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthDtos.AuthenticatedPrincipalDto currentOperatorProfile(
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        MoneyCraftPrincipal liveProfile = rbacGuard.requireAuthenticatedPrincipal();
        if (liveProfile.isNativeBearerAuthenticated() || liveProfile.isServiceKeyAuthenticated()) {
            servletResponse.setHeader("Cache-Control", "no-store");
            return AuthDtos.AuthenticatedPrincipalDto.from(liveProfile, null);
        }
        CsrfToken activeXsrf = xsrfCookieRepository.loadToken(servletRequest);
        if (activeXsrf == null) {
            activeXsrf = xsrfCookieRepository.generateToken(servletRequest);
            xsrfCookieRepository.saveToken(activeXsrf, servletRequest, servletResponse);
        }
        return AuthDtos.AuthenticatedPrincipalDto.from(liveProfile, activeXsrf.getToken());
    }

    @PostMapping("/step-up/password")
    public ResponseEntity<AuthDtos.PasswordStepUpResponse> createPasswordStepUpIntent(
        @RequestBody AuthDtos.PasswordStepUpRequest request
    ) {
        MoneyCraftPrincipal superAdmin = rbacGuard.requireSuperAdminPerson();
        AuthDtos.PasswordStepUpResponse issued = stepUpAuthService.issuePasswordStepUp(superAdmin, request);
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .body(issued);
    }

    @PostMapping("/passkeys/register/options")
    public AuthDtos.PasskeyCreationOptionsResponse beginPasskeyEnrollment(
        @RequestBody(required = false) AuthDtos.PasskeyRegisterOptionsRequest payload,
        HttpServletRequest servletRequest
    ) {
        MoneyCraftPrincipal operator = rbacGuard.requireAuthenticatedPrincipal();
        HttpSession activeSession = servletRequest.getSession(false);
        String sessionHandle = activeSession == null ? "" : activeSession.getId();
        return fidoPasskeyService.createRegistrationOptions(operator, sessionHandle, payload);
    }

    @PostMapping("/passkeys/register/verify")
    public ResponseEntity<Void> finishPasskeyEnrollment(
        @RequestBody AuthDtos.PasskeyRegisterVerifyRequest attestationPayload
    ) {
        MoneyCraftPrincipal operator = rbacGuard.requireAuthenticatedPrincipal();
        fidoPasskeyService.verifyRegistration(operator, attestationPayload);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/passkeys/authenticate/options")
    public AuthDtos.PasskeyRequestOptionsResponse beginPasskeyAssertion(
        @RequestBody(required = false) AuthDtos.PasskeyAuthOptionsRequest hintPayload,
        HttpServletRequest servletRequest
    ) {
        HttpSession ceremonySession = servletRequest.getSession(true);
        return fidoPasskeyService.createAuthenticationOptions(ceremonySession.getId(), hintPayload);
    }

    @PostMapping("/passkeys/authenticate/verify")
    public AuthDtos.AuthenticatedPrincipalDto finishPasskeyAssertion(
        @RequestBody AuthDtos.PasskeyAuthVerifyRequest assertionPayload,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        HttpSession ceremonySession = servletRequest.getSession(false);
        String sessionHandle = ceremonySession == null ? null : ceremonySession.getId();
        MoneyCraftPrincipal operator = fidoPasskeyService.verifyAuthentication(sessionHandle, assertionPayload);
        return moneyCraftAuthService.establishSession(
            servletRequest,
            servletResponse,
            xsrfCookieRepository,
            operator
        );
    }

    @GetMapping("/oidc/issuers")
    public List<AuthDtos.PublicOidcIssuerDto> listPublicOidcIssuers() {
        return enterpriseOidcService.listPublicIssuers();
    }

    @GetMapping("/oidc/{issuerId}/authorize")
    public AuthDtos.OidcAuthorizeResponse beginOidcPkceRedirect(
        @PathVariable("issuerId") String issuerRef
    ) {
        return enterpriseOidcService.buildAuthorizationRedirect(issuerRef);
    }

    @PostMapping("/oidc/callback")
    public AuthDtos.AuthenticatedPrincipalDto completeOidcPkceExchange(
        @RequestBody AuthDtos.OidcCallbackRequest callbackPayload,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        MoneyCraftPrincipal operator = enterpriseOidcService.completePkceCallback(callbackPayload);
        return moneyCraftAuthService.establishSession(
            servletRequest,
            servletResponse,
            xsrfCookieRepository,
            operator
        );
    }
}
