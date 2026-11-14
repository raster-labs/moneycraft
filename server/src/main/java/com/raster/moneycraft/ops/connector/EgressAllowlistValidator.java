package com.raster.moneycraft.ops.connector;

import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Enforces air-gapped default posture and explicit HTTPS hostname allowlisting for all outbound calls.
 */
@Component
public class EgressAllowlistValidator {

    private final MoneyCraftProperties properties;

    public EgressAllowlistValidator(MoneyCraftProperties properties) {
        this.properties = properties;
    }

    public void assertOutboundPermitted(URI targetUri) {
        if (!properties.getConnector().isEnabled()) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-CONNECTOR-DISABLED",
                "Outbound Connector SPI Disabled",
                "MoneyCraft operates offline-first by default; outbound network calls are disabled."
            );
        }
        if (targetUri == null || targetUri.getHost() == null || targetUri.getHost().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-CONNECTOR-URI-INVALID",
                "Invalid Outbound Target URI",
                "Outbound connector target URI must contain a valid hostname."
            );
        }
        String normalizedHost = targetUri.getHost().trim().toLowerCase(Locale.ROOT);
        boolean permittedLoopbackHttp = "http".equalsIgnoreCase(targetUri.getScheme())
            && properties.getConnector().isAllowLoopbackHttp()
            && ("127.0.0.1".equals(normalizedHost) || "localhost".equals(normalizedHost));
        if (!"https".equalsIgnoreCase(targetUri.getScheme()) && !permittedLoopbackHttp) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-CONNECTOR-TLS-REQUIRED",
                "HTTPS Transport Required",
                "Outbound connector calls must use HTTPS."
            );
        }

        Set<String> allowlist = properties.getConnector().getEgressHostnameAllowlist().stream()
            .filter(h -> h != null && !h.isBlank())
            .map(h -> h.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

        if (!allowlist.contains(normalizedHost)) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-CONNECTOR-EGRESS-DENIED",
                "Hostname Not In Egress Allowlist",
                "Target hostname '" + normalizedHost + "' is not permitted by egressHostnameAllowlist."
            );
        }
    }
}
