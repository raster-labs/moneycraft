package com.raster.moneycraft.foundation.idempotency;

import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import com.raster.moneycraft.foundation.tenant.TenantScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String method = request.getMethod().toUpperCase();
        if (!MUTATING_METHODS.contains(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = stripContextPath(request);
        boolean companyMutation = "/api/v1/companies".equals(path) || path.startsWith("/api/v1/companies/");
        boolean adminMutation = path.startsWith("/api/v1/admin/") && !path.startsWith("/api/v1/admin/quarantine/");
        if (!companyMutation && !adminMutation) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawKey = request.getHeader(IDEMPOTENCY_HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            if (companyMutation) {
                writeProblem(
                    request,
                    response,
                    HttpStatus.BAD_REQUEST,
                    "MC-IDEMPOTENCY-KEY-REQUIRED",
                    "Idempotency-Key Header Required",
                    "Every mutating business endpoint requires an Idempotency-Key header (RFC 8785 JCS + HMAC-SHA256)."
                );
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = rawKey.trim();
        if (idempotencyKey.length() > 128) {
            writeProblem(
                request,
                response,
                HttpStatus.BAD_REQUEST,
                "MC-IDEMPOTENCY-KEY-INVALID",
                "Idempotency-Key Too Long",
                "Idempotency-Key must be between 1 and 128 characters."
            );
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MoneyCraftPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        byte[] bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request, bodyBytes);

        UUID companyScope = null;
        if (TenantContextHolder.current() instanceof TenantScope.Company company) {
            companyScope = company.companyId();
        }

        String rawEndpoint = method + " " + path;
        String endpointId = rawEndpoint.length() <= 160 ? rawEndpoint : rawEndpoint.substring(0, 160);

        TinkCryptoService.HmacDigestResult hmac;
        Optional<IdempotencyService.StoredIdempotencyReceipt> existing;
        try {
            hmac = idempotencyService.computeRequestJcsHmac(bodyBytes);
            existing = idempotencyService.checkExistingReceipt(
                companyScope,
                principal.principalId(),
                endpointId,
                idempotencyKey,
                hmac.digest32()
            );
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

        if (existing.isPresent()) {
            IdempotencyService.StoredIdempotencyReceipt receipt = existing.get();
            response.setStatus(receipt.httpStatus());
            response.setHeader("X-Idempotency-Replayed", "true");
            if (receipt.responseEtag() != null) {
                response.setHeader("ETag", receipt.responseEtag());
            }
            if (receipt.responseLocation() != null) {
                response.setHeader("Location", receipt.responseLocation());
            }
            if (receipt.responseBodyJson() != null) {
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getOutputStream().write(receipt.responseBodyJson().getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(cachedRequest, responseWrapper);

        int status = responseWrapper.getStatus();
        if (status >= 200 && status < 300) {
            byte[] respBytes = responseWrapper.getContentAsByteArray();
            String respJson = respBytes.length == 0 ? null : new String(respBytes, StandardCharsets.UTF_8);
            idempotencyService.saveReceipt(
                companyScope,
                principal.principalId(),
                endpointId,
                idempotencyKey,
                hmac.keyId(),
                hmac.digest32(),
                status,
                responseWrapper.getHeader("ETag"),
                responseWrapper.getHeader("Location"),
                respJson
            );
        }
        responseWrapper.copyBodyToResponse();
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

    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteStream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return byteStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous buffered stream
                }

                @Override
                public int read() {
                    return byteStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
