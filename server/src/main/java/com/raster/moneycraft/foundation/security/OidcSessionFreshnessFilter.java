package com.raster.moneycraft.foundation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces Document 47 (MC-FED-01) OIDC session freshness and semantic version bindings
 * before business database locks or Tink decryption. Independent LOCAL and SERVICE_KEY
 * principals pass through untouched.
 */
public class OidcSessionFreshnessFilter extends OncePerRequestFilter {

    private static final long MAX_FRESHNESS_AGE_SECONDS = 120L;

    private final JdbcTemplate authorityJdbcTemplate;
    private final OidcClockSource clockSource;
    private final ObjectMapper objectMapper;

    public OidcSessionFreshnessFilter(
        JdbcTemplate authorityJdbcTemplate,
        OidcClockSource clockSource,
        ObjectMapper objectMapper
    ) {
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.clockSource = clockSource;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String path = stripContextPath(request);
        if ("/api/v1/auth/logout".equals(path)
            || "/api/v1/system/status".equals(path)
            || path.startsWith("/actuator/")) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
            || !(auth.getPrincipal() instanceof MoneyCraftPrincipal principal)
            || !principal.isOidcAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        List<OidcAdmissionState> rows = authorityJdbcTemplate.query(
            """
            SELECT p.status AS principal_status,
                   i.state AS issuer_state,
                   i.version_no AS issuer_version,
                   i.lifecycle_profile,
                   i.checked_at AS issuer_checked_at,
                   l.state AS link_state,
                   l.version_no AS link_version,
                   l.checked_at AS link_checked_at
              FROM ma_principal p
              JOIN ma_oidc_link l ON l.principal_id = p.principal_id AND l.id = ?
              JOIN ma_issuer i ON i.id = l.issuer_id AND i.id = ?
             WHERE p.principal_id = ?
            """,
            (rs, rowNum) -> {
                OffsetDateTime issuerChecked = rs.getObject("issuer_checked_at", OffsetDateTime.class);
                OffsetDateTime linkChecked = rs.getObject("link_checked_at", OffsetDateTime.class);
                return new OidcAdmissionState(
                    rs.getString("principal_status"),
                    rs.getString("issuer_state"),
                    rs.getLong("issuer_version"),
                    rs.getString("lifecycle_profile"),
                    issuerChecked == null ? null : issuerChecked.toInstant(),
                    rs.getString("link_state"),
                    rs.getLong("link_version"),
                    linkChecked == null ? null : linkChecked.toInstant()
                );
            },
            principal.oidcLinkId(),
            principal.oidcIssuerId(),
            principal.principalId()
        );

        if (rows.isEmpty()) {
            denyOidcSession(
                request,
                response,
                "MC-OIDC-SESSION-INVALID",
                "OIDC Session Binding Invalid",
                "OIDC principal, issuer, or subject link is no longer active."
            );
            return;
        }

        OidcAdmissionState state = rows.getFirst();
        if (!"ENABLED".equals(state.principalStatus())
            || !"ENABLED".equals(state.issuerState())
            || !"ACTIVE".equals(state.linkState())) {
            denyOidcSession(
                request,
                response,
                "MC-OIDC-SESSION-INVALID",
                "OIDC Principal or Issuer Disabled",
                "Federated access is denied because the principal, link, or issuer is not active."
            );
            return;
        }

        if (principal.oidcIssuerVersion() != null && state.issuerVersion() != principal.oidcIssuerVersion()) {
            denyOidcSession(
                request,
                response,
                "MC-OIDC-ISSUER-VERSION-CHANGED",
                "OIDC Issuer Configuration Changed",
                "Session predates the current OIDC issuer semantic configuration version."
            );
            return;
        }

        if (principal.oidcLinkVersion() != null && state.linkVersion() != principal.oidcLinkVersion()) {
            denyOidcSession(
                request,
                response,
                "MC-OIDC-LINK-VERSION-CHANGED",
                "OIDC Link Version Changed",
                "Session predates the current OIDC subject link version."
            );
            return;
        }

        if ("MC-FED-01".equals(state.lifecycleProfile())) {
            Instant now = clockSource.instant();
            if (!isFresh(state.issuerCheckedAt(), now) || !isFresh(state.linkCheckedAt(), now)) {
                denyOidcSession(
                    request,
                    response,
                    "MC-OIDC-FRESHNESS-EXPIRED",
                    "OIDC Provider Authority Freshness Unavailable",
                    "Federated session requires current qualified provider and subject freshness within 120 seconds."
                );
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    public static boolean isFresh(Instant checkedAt, Instant now) {
        if (checkedAt == null || now == null) {
            return false;
        }
        if (checkedAt.isAfter(now)) {
            return false;
        }
        return now.isBefore(checkedAt.plusSeconds(MAX_FRESHNESS_AGE_SECONDS));
    }

    private void denyOidcSession(
        HttpServletRequest request,
        HttpServletResponse response,
        String code,
        String title,
        String detail
    ) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:moneycraft:problem:" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("errorCode", code);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static String stripContextPath(HttpServletRequest request) {
        String ctx = request.getContextPath();
        String uri = request.getRequestURI();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    private record OidcAdmissionState(
        String principalStatus,
        String issuerState,
        long issuerVersion,
        String lifecycleProfile,
        Instant issuerCheckedAt,
        String linkState,
        long linkVersion,
        Instant linkCheckedAt
    ) {
    }
}
