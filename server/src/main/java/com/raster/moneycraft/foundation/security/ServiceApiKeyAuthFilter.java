package com.raster.moneycraft.foundation.security;

import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class ServiceApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String KEY_PREFIX_MARKER = "mc_sk_";

    private final JdbcTemplate authorityJdbcTemplate;
    private final RbacAuthorizationService rbacAuthorizationService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ServiceApiKeyAuthFilter(
        JdbcTemplate authorityJdbcTemplate,
        RbacAuthorizationService rbacAuthorizationService,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper
    ) {
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.rbacAuthorizationService = rbacAuthorizationService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String rawToken = extractServiceKeyToken(request);
        if (rawToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        ParsedServiceKey parsed = parseToken(rawToken);
        if (parsed == null) {
            writeProblem(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "MC-AUTH-INVALID-API-KEY",
                "Invalid Service API Key Format",
                "Service API keys must follow format mc_sk_<prefix>_<secret>."
            );
            return;
        }

        List<ServiceKeyRow> rows = authorityJdbcTemplate.query(
            """
            SELECT id, principal_id, secret_hash, company_allowlist::text AS companies_json,
                   endpoint_allowlist::text AS endpoints_json, state, expires_at
              FROM ma_service_key
             WHERE key_prefix = ?
            """,
            (rs, rowNum) -> new ServiceKeyRow(
                rs.getObject("id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getString("secret_hash"),
                rs.getString("companies_json"),
                rs.getString("endpoints_json"),
                rs.getString("state"),
                rs.getObject("expires_at", OffsetDateTime.class)
            ),
            parsed.keyPrefix()
        );

        if (rows.isEmpty()) {
            writeProblem(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "MC-AUTH-INVALID-API-KEY",
                "Unknown Service API Key",
                "No registered service API key matched the supplied prefix."
            );
            return;
        }

        ServiceKeyRow row = rows.getFirst();
        if (!"ACTIVE".equals(row.state()) || row.expiresAt().isBefore(OffsetDateTime.now())) {
            writeProblem(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "MC-AUTH-API-KEY-EXPIRED",
                "Service API Key Expired or Revoked",
                "The supplied service API key is no longer active."
            );
            return;
        }

        String candidateHash = hashServiceKeySecret(jcsCanonicalizer, parsed.keyPrefix(), parsed.rawSecret());
        if (!MessageDigest.isEqual(
            row.secretHash().getBytes(StandardCharsets.UTF_8),
            candidateHash.getBytes(StandardCharsets.UTF_8)
        )) {
            writeProblem(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "MC-AUTH-INVALID-API-KEY",
                "Invalid Service API Key Secret",
                "Service API key secret verification failed."
            );
            return;
        }

        List<String> endpointPatterns = rbacAuthorizationService.parseStringArray(row.endpointsJson());
        String requestPath = normalizePath(request);
        if (!endpointPatterns.isEmpty() && !matchesAnyEndpoint(request.getMethod(), requestPath, endpointPatterns)) {
            writeProblem(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "MC-SERVICE-KEY-ENDPOINT-FORBIDDEN",
                "Endpoint Not Permitted for Service Key",
                "Service API key allowlist does not permit " + request.getMethod() + " " + requestPath + "."
            );
            return;
        }

        List<UUID> companyAllowlist = new ArrayList<>();
        for (String rawUuid : rbacAuthorizationService.parseStringArray(row.companiesJson())) {
            try {
                companyAllowlist.add(UUID.fromString(rawUuid));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed UUID entry
            }
        }

        MoneyCraftPrincipal basePrincipal = rbacAuthorizationService.loadPrincipalProfile(row.principalId());
        MoneyCraftPrincipal scopedPrincipal = new MoneyCraftPrincipal(
            basePrincipal.principalId(),
            basePrincipal.kind(),
            basePrincipal.username(),
            basePrincipal.displayName(),
            basePrincipal.systemRoles(),
            basePrincipal.companyGrants(),
            basePrincipal.passkeyEnrolled(),
            row.id(),
            companyAllowlist,
            endpointPatterns
        );

        authorityJdbcTemplate.update(
            "UPDATE ma_service_key SET last_used_at = clock_timestamp() WHERE id = ?",
            row.id()
        );

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
            scopedPrincipal,
            null,
            scopedPrincipal.toGrantedAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authToken);
        filterChain.doFilter(request, response);
    }

    public static String extractServiceKeyToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer " + KEY_PREFIX_MARKER)) {
            return authHeader.substring("Bearer ".length()).trim();
        }
        String apiKeyHeader = request.getHeader("X-API-Key");
        if (apiKeyHeader != null && apiKeyHeader.startsWith(KEY_PREFIX_MARKER)) {
            return apiKeyHeader.trim();
        }
        return null;
    }

    public static String hashServiceKeySecret(
        JcsCanonicalizer canonicalizer,
        String keyPrefix,
        String rawSecret
    ) {
        byte[] digest = canonicalizer.sha256(
            ("MC-SERVICE-KEY-V1|" + keyPrefix + "|" + rawSecret).getBytes(StandardCharsets.UTF_8)
        );
        return "sha256$" + JcsCanonicalizer.toHex(digest);
    }

    private ParsedServiceKey parseToken(String token) {
        if (!token.startsWith(KEY_PREFIX_MARKER)) {
            return null;
        }
        String remainder = token.substring(KEY_PREFIX_MARKER.length());
        int sep = remainder.indexOf('_');
        if (sep < 6 || sep > 16 || sep + 1 >= remainder.length()) {
            return null;
        }
        String prefix = remainder.substring(0, sep);
        String secret = remainder.substring(sep + 1);
        return new ParsedServiceKey(prefix, secret);
    }

    private boolean matchesAnyEndpoint(String method, String path, List<String> patterns) {
        for (String pattern : patterns) {
            int colon = pattern.indexOf(':');
            if (colon > 0) {
                String patMethod = pattern.substring(0, colon).trim();
                String patPath = pattern.substring(colon + 1).trim();
                if (("*".equals(patMethod) || patMethod.equalsIgnoreCase(method))
                    && pathMatcher.match(patPath, path)) {
                    return true;
                }
            } else if (pathMatcher.match(pattern.trim(), path)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
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
        problem.setType(URI.create("urn:moneycraft:problem:" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("errorCode", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private record ParsedServiceKey(String keyPrefix, String rawSecret) {
    }

    private record ServiceKeyRow(
        UUID id,
        UUID principalId,
        String secretHash,
        String companiesJson,
        String endpointsJson,
        String state,
        OffsetDateTime expiresAt
    ) {
    }
}
