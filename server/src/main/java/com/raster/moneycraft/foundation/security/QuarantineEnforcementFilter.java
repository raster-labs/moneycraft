package com.raster.moneycraft.foundation.security;

import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.authority.InstallationSecurityState;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class QuarantineEnforcementFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AuthorityGuard authorityGuard;
    private final ObjectMapper objectMapper;

    public QuarantineEnforcementFilter(AuthorityGuard authorityGuard, ObjectMapper objectMapper) {
        this.authorityGuard = authorityGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String path = stripContextPath(request);
        if (!path.startsWith("/api/v1/")) {
            filterChain.doFilter(request, response);
            return;
        }

        AuthorityGuard.GuardSnapshot snap = authorityGuard.currentSnapshot();
        if (snap.securityState() != InstallationSecurityState.QUARANTINED) {
            filterChain.doFilter(request, response);
            return;
        }

        String method = request.getMethod().toUpperCase();
        if (isAllowedDuringQuarantine(method, path)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (MUTATING_METHODS.contains(method)) {
            writeQuarantineProblem(request, response, snap.quarantineReason());
            return;
        }

        // Read-only forensic inspection in QUARANTINE is restricted to INSTALLATION_OWNER & SECURITY_AUDITOR
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MoneyCraftPrincipal principal
            && (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)
                || principal.hasSystemRole(SystemRole.SECURITY_AUDITOR))) {
            filterChain.doFilter(request, response);
            return;
        }

        writeQuarantineProblem(request, response, snap.quarantineReason());
    }

    private static boolean isAllowedDuringQuarantine(String method, String path) {
        if ("GET".equals(method) && ("/api/v1/system/status".equals(path) || "/api/v1/auth/me".equals(path)
            || "/api/v1/events/stream".equals(path))) {
            return true;
        }
        if ("POST".equals(method) && (
            "/api/v1/admin/quarantine/recover".equals(path)
                || "/api/v1/auth/login".equals(path)
                || "/api/v1/auth/logout".equals(path)
                || "/api/v1/auth/passkeys/authenticate/options".equals(path)
                || "/api/v1/auth/passkeys/authenticate/verify".equals(path)
        )) {
            return true;
        }
        return false;
    }

    private void writeQuarantineProblem(
        HttpServletRequest request,
        HttpServletResponse response,
        String reason
    ) throws IOException {
        String detail = (reason == null || reason.isBlank())
            ? "Installation is in fail-closed QUARANTINE mode. All business mutations are suspended."
            : "Installation is in fail-closed QUARANTINE mode: " + reason;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        problem.setTitle("Installation Quarantined (Fail-Closed DR Mode)");
        problem.setType(URI.create("urn:moneycraft:problem:MC-SYSTEM-QUARANTINED"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "MC-SYSTEM-QUARANTINED");
        problem.setProperty("errorCode", "MC-SYSTEM-QUARANTINED");

        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
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
}
