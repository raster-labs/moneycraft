package com.raster.moneycraft.foundation.security;

import com.raster.moneycraft.foundation.auth.NativeOAuthPkceService;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticates first-party host native public-client OAuth 2.0 Bearer access tokens,
 * enforces mixed-credential rejection (Bearer + session cookie or Bearer + API key -> 400 MC-MIXED-CREDENTIALS),
 * rejects OIDC id_token substitution, and verifies live Authority state, incarnation, and principal grants.
 */
public class NativeBearerTokenAuthFilter extends OncePerRequestFilter {

    private final JdbcTemplate authorityJdbcTemplate;
    private final RbacAuthorizationService rbacAuthorizationService;
    private final OidcClockSource clockSource;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;

    public NativeBearerTokenAuthFilter(
        JdbcTemplate authorityJdbcTemplate,
        RbacAuthorizationService rbacAuthorizationService,
        OidcClockSource clockSource,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper
    ) {
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.rbacAuthorizationService = rbacAuthorizationService;
        this.clockSource = clockSource;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (hasMixedCredentials(request)) {
            writeProblem(
                request,
                response,
                HttpStatus.BAD_REQUEST,
                "MC-MIXED-CREDENTIALS",
                "Mixed Authentication Credentials Forbidden",
                "Combining Authorization Bearer token, browser session cookie, or X-API-Key on a single request is prohibited."
            );
            return;
        }

        String rawBearer = extractNativeBearerToken(request);
        if (rawBearer == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (looksLikeJwt(rawBearer) || !rawBearer.startsWith(NativeOAuthPkceService.ACCESS_TOKEN_PREFIX)) {
            writeProblem(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-ID-TOKEN-FORBIDDEN",
                "Invalid Native Access Token",
                "An OIDC id_token or unrecognized bearer string cannot be used as a MoneyCraft API access token."
            );
            return;
        }

        byte[] tokenSha = jcsCanonicalizer.sha256(rawBearer.getBytes(StandardCharsets.UTF_8));
        List<ActiveTokenRow> rows = authorityJdbcTemplate.query(
            """
            SELECT t.token_id, t.client_id, t.principal_id, t.incarnation AS token_incarnation,
                   t.access_expires_at, t.state AS token_state,
                   t.oidc_issuer_id, t.oidc_link_id, t.oidc_issuer_version, t.oidc_link_version,
                   s.incarnation AS state_incarnation, s.security_state,
                   c.state AS client_state
              FROM ma_native_token t
              JOIN ma_state s ON s.installation_id = t.installation_id AND s.singleton = 1
              JOIN ma_native_oauth_client c ON c.client_id = t.client_id
             WHERE t.access_token_sha256 = ?
            """,
            (rs, rowNum) -> new ActiveTokenRow(
                rs.getObject("token_id", UUID.class),
                rs.getString("client_id"),
                rs.getObject("principal_id", UUID.class),
                rs.getObject("token_incarnation", UUID.class),
                rs.getObject("access_expires_at", OffsetDateTime.class),
                rs.getString("token_state"),
                rs.getObject("oidc_issuer_id", UUID.class),
                rs.getObject("oidc_link_id", UUID.class),
                rs.getObject("oidc_issuer_version", Long.class),
                rs.getObject("oidc_link_version", Long.class),
                rs.getObject("state_incarnation", UUID.class),
                rs.getString("security_state"),
                rs.getString("client_state")
            ),
            tokenSha
        );

        if (rows.isEmpty()) {
            writeProblem(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-TOKEN-INVALID",
                "Unknown Native Access Token",
                "Supplied native Bearer access token was not recognised."
            );
            return;
        }

        ActiveTokenRow row = rows.getFirst();
        Instant now = clockSource.instant();
        if (!"ACTIVE".equals(row.tokenState())
            || !"ENABLED".equals(row.clientState())
            || !row.tokenIncarnation().equals(row.stateIncarnation())
            || !row.accessExpiresAt().toInstant().isAfter(now)) {
            writeProblem(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "MC-NATIVE-TOKEN-EXPIRED",
                "Native Access Token Expired or Revoked",
                "Native Bearer access token is expired, rotated, revoked, or from a prior authority incarnation."
            );
            return;
        }

        MoneyCraftPrincipal livePrincipal;
        try {
            livePrincipal = rbacAuthorizationService.loadPrincipalProfile(row.principalId());
        } catch (MoneyCraftException ex) {
            writeProblem(request, response, ex.status(), ex.code(), ex.title(), ex.getMessage());
            return;
        }

        if (!"PERSON".equals(livePrincipal.kind())) {
            writeProblem(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "MC-NATIVE-PERSON-REQUIRED",
                "Invalid Principal Kind",
                "Native Bearer access tokens are valid only for enabled PERSON principals."
            );
            return;
        }

        MoneyCraftPrincipal boundPrincipal = livePrincipal.withNativeClientBinding(row.clientId());
        if (row.oidcIssuerId() != null && row.oidcLinkId() != null) {
            boundPrincipal = boundPrincipal.withOidcSessionBinding(
                row.oidcIssuerId(),
                row.oidcLinkId(),
                row.oidcIssuerVersion() == null ? 1L : row.oidcIssuerVersion(),
                row.oidcLinkVersion() == null ? 1L : row.oidcLinkVersion()
            );
        }

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
            boundPrincipal,
            null,
            boundPrincipal.toGrantedAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authToken);
        filterChain.doFilter(request, response);
    }

    public static boolean hasMixedCredentials(HttpServletRequest request) {
        boolean hasSessionCookie = hasBrowserSessionCookie(request);
        String authHeader = request.getHeader("Authorization");
        String apiKeyHeader = request.getHeader("X-API-Key");

        boolean hasAuthBearer = authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)
            && !authHeader.substring(7).isBlank();
        boolean hasApiKeyHeader = apiKeyHeader != null && !apiKeyHeader.isBlank();

        if (hasAuthBearer && hasApiKeyHeader) {
            return true;
        }
        if ((hasAuthBearer || hasApiKeyHeader) && hasSessionCookie) {
            return true;
        }
        return false;
    }

    public static boolean hasPureNativeBearer(HttpServletRequest request) {
        return !hasMixedCredentials(request) && extractNativeBearerToken(request) != null;
    }

    public static String extractNativeBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty() || token.startsWith(ServiceApiKeyAuthFilter.KEY_PREFIX_MARKER)) {
            return null;
        }
        return token;
    }

    public static boolean hasBrowserSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            String name = cookie.getName();
            if (("MCSESSION".equals(name) || "JSESSIONID".equals(name))
                && cookie.getValue() != null
                && !cookie.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeJwt(String token) {
        int dots = 0;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) == '.') {
                dots++;
            }
        }
        return dots == 2;
    }

    private void writeProblem(
        HttpServletRequest request,
        HttpServletResponse response,
        HttpStatus status,
        String code,
        String title,
        String detail
    ) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:moneycraft:problem:" + code.toLowerCase()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("errorCode", code);
        response.setStatus(status.value());
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private record ActiveTokenRow(
        UUID tokenId,
        String clientId,
        UUID principalId,
        UUID tokenIncarnation,
        OffsetDateTime accessExpiresAt,
        String tokenState,
        UUID oidcIssuerId,
        UUID oidcLinkId,
        Long oidcIssuerVersion,
        Long oidcLinkVersion,
        UUID stateIncarnation,
        String securityState,
        String clientState
    ) {
    }
}
