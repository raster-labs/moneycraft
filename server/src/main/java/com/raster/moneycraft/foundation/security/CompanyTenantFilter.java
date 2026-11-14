package com.raster.moneycraft.foundation.security;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class CompanyTenantFilter extends OncePerRequestFilter {

    private static final String COMPANIES_PREFIX = "/api/v1/companies/";

    private final RbacAuthorizationService rbacAuthorizationService;
    private final ObjectMapper objectMapper;

    public CompanyTenantFilter(
        RbacAuthorizationService rbacAuthorizationService,
        ObjectMapper objectMapper
    ) {
        this.rbacAuthorizationService = rbacAuthorizationService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String path = stripContextPath(request);
        if (!path.startsWith(COMPANIES_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String remainder = path.substring(COMPANIES_PREFIX.length());
        if (remainder.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        int slashIdx = remainder.indexOf('/');
        String rawCompanyId = slashIdx >= 0 ? remainder.substring(0, slashIdx) : remainder;
        UUID companyId;
        try {
            companyId = UUID.fromString(rawCompanyId);
        } catch (IllegalArgumentException ex) {
            writeProblem(
                request,
                response,
                HttpStatus.BAD_REQUEST,
                "MC-BAD-COMPANY-ID",
                "Invalid Company Identifier",
                "Path parameter companyId must be a valid UUID."
            );
            return;
        }

        MoneyCraftPrincipal principal;
        try {
            principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
            rbacAuthorizationService.requireCompanyAccess(principal, companyId);
        } catch (MoneyCraftException ex) {
            writeProblem(
                request,
                response,
                ex.status(),
                ex.code(),
                ex.title(),
                ex.getMessage()
            );
            return;
        }

        final ServletException[] servletError = new ServletException[1];
        final IOException[] ioError = new IOException[1];
        TenantContextHolder.runWithCompany(companyId, () -> {
            try {
                filterChain.doFilter(request, response);
            } catch (ServletException ex) {
                servletError[0] = ex;
            } catch (IOException ex) {
                ioError[0] = ex;
            }
        });

        if (servletError[0] != null) {
            throw servletError[0];
        }
        if (ioError[0] != null) {
            throw ioError[0];
        }
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

    private static String stripContextPath(HttpServletRequest request) {
        String ctx = request.getContextPath();
        String uri = request.getRequestURI();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }
}
