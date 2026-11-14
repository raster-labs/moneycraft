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
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
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

        String reqContentType = request.getContentType();
        boolean isMultipart = isMultipartMediaType(reqContentType);
        byte[] bodyBytes = null;
        HttpServletRequest downstreamRequest = request;
        if (!isMultipart) {
            bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
            downstreamRequest = new CachedBodyHttpServletRequest(request, bodyBytes);
        }

        UUID companyScope = null;
        if (TenantContextHolder.current() instanceof TenantScope.Company company) {
            companyScope = company.companyId();
        }

        String rawEndpoint = method + " " + path;
        String endpointId = rawEndpoint.length() <= 160 ? rawEndpoint : rawEndpoint.substring(0, 160);

        TinkCryptoService.HmacDigestResult hmac;
        Optional<IdempotencyService.StoredIdempotencyReceipt> existing;
        try {
            if (isMultipart) {
                hmac = idempotencyService.computeRawPayloadHmac(extractMultipartCanonicalBytes(request));
            } else if (isJsonMediaType(reqContentType) || bodyBytes == null || bodyBytes.length == 0) {
                hmac = idempotencyService.computeRequestJcsHmac(bodyBytes);
            } else {
                hmac = idempotencyService.computeRawPayloadHmac(bodyBytes);
            }
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
                String storedJson = receipt.responseBodyJson();
                if (storedJson.contains("\"_mcBinaryBase64\"")) {
                    JsonNode env = objectMapper.readTree(storedJson);
                    if (env.has("_mcContentType") && !env.get("_mcContentType").isNull()) {
                        response.setContentType(env.get("_mcContentType").asString());
                    }
                    byte[] decoded = Base64.getDecoder().decode(env.get("_mcBinaryBase64").asString());
                    response.getOutputStream().write(decoded);
                } else {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getOutputStream().write(storedJson.getBytes(StandardCharsets.UTF_8));
                }
            }
            return;
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(downstreamRequest, responseWrapper);

        int status = responseWrapper.getStatus();
        if (status >= 200 && status < 300) {
            byte[] respBytes = responseWrapper.getContentAsByteArray();
            String respContentType = responseWrapper.getContentType();
            String respJson = null;
            if (respBytes.length > 0) {
                if (isJsonMediaType(respContentType)) {
                    respJson = new String(respBytes, StandardCharsets.UTF_8);
                } else {
                    respJson = objectMapper.writeValueAsString(
                        Map.of(
                            "_mcBinaryBase64", Base64.getEncoder().encodeToString(respBytes),
                            "_mcContentType", respContentType == null ? "application/octet-stream" : respContentType
                        )
                    );
                }
            }
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

    private static boolean isJsonMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("application/json") || lower.contains("+json");
    }

    private static boolean isMultipartMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        return contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private static byte[] extractMultipartCanonicalBytes(HttpServletRequest request)
        throws IOException, ServletException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Part> parts = new ArrayList<>(request.getParts());
        parts.sort(Comparator.comparing(Part::getName));
        for (Part part : parts) {
            out.write(("part:" + part.getName() + ":").getBytes(StandardCharsets.UTF_8));
            if (part.getSubmittedFileName() != null) {
                out.write(("file:" + part.getSubmittedFileName() + ":").getBytes(StandardCharsets.UTF_8));
            }
            byte[] partBytes = StreamUtils.copyToByteArray(part.getInputStream());
            out.write(partBytes);
            out.write('\n');
        }
        return out.toByteArray();
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
