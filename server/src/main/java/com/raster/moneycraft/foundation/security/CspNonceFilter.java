package com.raster.moneycraft.foundation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.web.filter.OncePerRequestFilter;

public class CspNonceFilter extends OncePerRequestFilter {

    public static final String CSP_NONCE_ATTRIBUTE = "cspNonce";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        byte[] nonceBytes = new byte[18];
        SECURE_RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);

        request.setAttribute(CSP_NONCE_ATTRIBUTE, nonce);
        String cspHeader = "default-src 'self'; "
            + "script-src 'self' 'nonce-" + nonce + "'; "
            + "style-src 'self' 'nonce-" + nonce + "'; "
            + "img-src 'self' data:; "
            + "font-src 'self'; "
            + "connect-src 'self' https: http:; "
            + "object-src 'none'; "
            + "frame-ancestors 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'";

        response.setHeader("Content-Security-Policy", cspHeader);
        response.setHeader("X-CSP-Nonce", nonce);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        filterChain.doFilter(request, response);
    }
}
