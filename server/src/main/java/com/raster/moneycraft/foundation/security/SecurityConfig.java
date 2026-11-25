package com.raster.moneycraft.foundation.security;

import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.idempotency.IdempotencyFilter;
import com.raster.moneycraft.foundation.idempotency.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> PRE_AUTH_POST_PATHS = Set.of(
        "/api/v1/system/initialise",
        "/api/v1/auth/login",
        "/api/v1/auth/passkeys/authenticate/options",
        "/api/v1/auth/passkeys/authenticate/verify",
        "/api/v1/auth/oidc/callback",
        "/api/v1/auth/native/token",
        "/api/v1/auth/native/revoke"
    );

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(MoneyCraftProperties properties) {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookieName("XSRF-TOKEN");
        repo.setHeaderName("X-XSRF-TOKEN");
        repo.setCookieCustomizer(cookie -> cookie
            .sameSite("Strict")
            .secure(properties.getSecurity().isCookieSecure())
            .path("/")
        );
        return repo;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        CookieCsrfTokenRepository csrfTokenRepository,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        RbacAuthorizationService rbacAuthorizationService,
        AuthorityGuard authorityGuard,
        OidcClockSource oidcClockSource,
        IdempotencyService idempotencyService,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper
    ) throws Exception {
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        CspNonceFilter cspNonceFilter = new CspNonceFilter();
        MixedCredentialsGuardFilter mixedCredentialsGuardFilter = new MixedCredentialsGuardFilter(objectMapper);
        ServiceApiKeyAuthFilter serviceApiKeyAuthFilter = new ServiceApiKeyAuthFilter(
            authorityJdbcTemplate,
            rbacAuthorizationService,
            jcsCanonicalizer,
            objectMapper
        );
        NativeBearerTokenAuthFilter nativeBearerTokenAuthFilter = new NativeBearerTokenAuthFilter(
            authorityJdbcTemplate,
            rbacAuthorizationService,
            oidcClockSource,
            jcsCanonicalizer,
            objectMapper
        );
        QuarantineEnforcementFilter quarantineFilter = new QuarantineEnforcementFilter(authorityGuard, objectMapper);
        OidcSessionFreshnessFilter oidcFreshnessFilter = new OidcSessionFreshnessFilter(
            authorityJdbcTemplate,
            oidcClockSource,
            objectMapper
        );
        CompanyTenantFilter companyTenantFilter = new CompanyTenantFilter(rbacAuthorizationService, objectMapper);
        IdempotencyFilter idempotencyFilter = new IdempotencyFilter(idempotencyService, objectMapper);

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(requestHandler)
                .requireCsrfProtectionMatcher(request -> requiresCsrfProtection(request))
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/api/v1/system/status",
                    "/api/v1/system/initialise",
                    "/api/v1/auth/login",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/passkeys/authenticate/options",
                    "/api/v1/auth/passkeys/authenticate/verify",
                    "/api/v1/auth/oidc/issuers",
                    "/api/v1/auth/oidc/*/authorize",
                    "/api/v1/auth/oidc/callback",
                    "/api/v1/auth/native/.well-known/openid-configuration",
                    "/api/v1/auth/native/jwks.json",
                    "/api/v1/auth/native/authorize",
                    "/api/v1/auth/native/token",
                    "/api/v1/auth/native/revoke"
                ).permitAll()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> writeProblem(
                    request,
                    response,
                    objectMapper,
                    HttpStatus.UNAUTHORIZED,
                    "MC-AUTH-REQUIRED",
                    "Authentication Required",
                    "Sign in via session cookie, WebAuthn passkey, OIDC PKCE, native OAuth Bearer, or service API key."
                ))
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    boolean csrfFailure = accessDeniedException instanceof org.springframework.security.web.csrf.CsrfException;
                    String code = csrfFailure ? "MC-CSRF-INVALID" : "MC-AUTH-FORBIDDEN";
                    String title = csrfFailure ? "CSRF Token Missing or Invalid" : "Access Denied";
                    writeProblem(
                        request,
                        response,
                        objectMapper,
                        HttpStatus.FORBIDDEN,
                        code,
                        title,
                        accessDeniedException.getMessage()
                    );
                })
            )
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .addFilterBefore(cspNonceFilter, CsrfFilter.class)
            .addFilterBefore(mixedCredentialsGuardFilter, CsrfFilter.class)
            .addFilterAfter(new EagerCsrfCookieFilter(), CsrfFilter.class)
            .addFilterBefore(serviceApiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(nativeBearerTokenAuthFilter, ServiceApiKeyAuthFilter.class)
            .addFilterAfter(quarantineFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(oidcFreshnessFilter, QuarantineEnforcementFilter.class)
            .addFilterAfter(companyTenantFilter, OidcSessionFreshnessFilter.class)
            .addFilterAfter(idempotencyFilter, CompanyTenantFilter.class);

        return http.build();
    }

    private static boolean requiresCsrfProtection(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase())) {
            return false;
        }
        if (!NativeBearerTokenAuthFilter.hasMixedCredentials(request)
            && ServiceApiKeyAuthFilter.extractServiceKeyToken(request) != null) {
            return false;
        }
        if (NativeBearerTokenAuthFilter.hasPureNativeBearer(request)) {
            return false;
        }
        String path = stripContextPath(request);
        if (PRE_AUTH_POST_PATHS.contains(path)) {
            return false;
        }
        return true;
    }

    private static String stripContextPath(HttpServletRequest request) {
        String ctx = request.getContextPath();
        String uri = request.getRequestURI();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    private static void writeProblem(
        HttpServletRequest request,
        HttpServletResponse response,
        ObjectMapper objectMapper,
        HttpStatus status,
        String code,
        String title,
        String detail
    ) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:moneycraft:problem:" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("errorCode", code);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static final class MixedCredentialsGuardFilter extends OncePerRequestFilter {

        private final ObjectMapper objectMapper;

        MixedCredentialsGuardFilter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
        ) throws ServletException, IOException {
            if (NativeBearerTokenAuthFilter.hasMixedCredentials(request)) {
                writeProblem(
                    request,
                    response,
                    objectMapper,
                    HttpStatus.BAD_REQUEST,
                    "MC-MIXED-CREDENTIALS",
                    "Mixed Authentication Credentials Forbidden",
                    "Combining Authorization Bearer token, browser session cookie, or X-API-Key on a single request is prohibited."
                );
                return;
            }
            filterChain.doFilter(request, response);
        }
    }

    private static final class EagerCsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
        ) throws ServletException, IOException {
            if (!NativeBearerTokenAuthFilter.hasPureNativeBearer(request)
                && ServiceApiKeyAuthFilter.extractServiceKeyToken(request) == null) {
                CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                if (csrfToken != null) {
                    csrfToken.getToken();
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}
