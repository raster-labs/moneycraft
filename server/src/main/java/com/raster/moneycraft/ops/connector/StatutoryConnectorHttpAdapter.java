package com.raster.moneycraft.ops.connector;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * JSON HTTP transport adapter isolated inside {@code ops.connector} per ArchUnit boundary rules,
 * gating every outbound request behind {@link EgressAllowlistValidator} with Redirect.NEVER.
 */
public class StatutoryConnectorHttpAdapter implements OutboundConnectorAdapter {

    private final OutboundConnectorDescriptor descriptor;
    private final EgressAllowlistValidator egressValidator;
    private final HttpClient httpClient;

    public StatutoryConnectorHttpAdapter(
        OutboundConnectorDescriptor descriptor,
        EgressAllowlistValidator egressValidator
    ) {
        this.descriptor = descriptor;
        this.egressValidator = egressValidator;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public OutboundConnectorDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Harmless read-only health/auth probe (Doc 51 §4.3); never follows redirects or mutates remote state.
     */
    public StatutoryHttpResponse getSafeProbe(URI endpointUri, Map<String, String> headers, Duration timeout) {
        egressValidator.assertOutboundPermitted(endpointUri);
        Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpointUri)
            .timeout(effectiveTimeout)
            .header("Accept", "application/json")
            .GET();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        }
        try {
            HttpResponse<String> response = httpClient.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            return new StatutoryHttpResponse(response.statusCode(), response.body());
        } catch (HttpTimeoutException ex) {
            throw new MoneyCraftException(
                HttpStatus.GATEWAY_TIMEOUT,
                "MC-CONNECTOR-TIMEOUT",
                "Outbound Connector Timeout",
                "Outbound safe probe timed out awaiting provider response."
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MoneyCraftException(
                HttpStatus.BAD_GATEWAY,
                "MC-CONNECTOR-INTERRUPTED",
                "Statutory Connector Interrupted",
                "Outbound connector exchange was interrupted."
            );
        } catch (IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_GATEWAY,
                "MC-CONNECTOR-IO-ERROR",
                "Statutory Connector Transport Error",
                "Failed to reach outbound endpoint."
            );
        }
    }

    public StatutoryHttpResponse postJson(URI endpointUri, Map<String, String> headers, String jsonPayload) {
        egressValidator.assertOutboundPermitted(endpointUri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpointUri)
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        }
        String safeBody = jsonPayload == null ? "{}" : jsonPayload;
        HttpRequest request = builder
            .POST(HttpRequest.BodyPublishers.ofString(safeBody, StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            return new StatutoryHttpResponse(response.statusCode(), response.body());
        } catch (HttpTimeoutException ex) {
            throw new MoneyCraftException(
                HttpStatus.GATEWAY_TIMEOUT,
                "MC-CONNECTOR-TIMEOUT",
                "Statutory Connector Timeout",
                "Outbound connector timed out after request transmission; outcome is uncertain."
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MoneyCraftException(
                HttpStatus.BAD_GATEWAY,
                "MC-CONNECTOR-INTERRUPTED",
                "Statutory Connector Interrupted",
                "Outbound statutory portal exchange was interrupted."
            );
        } catch (IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_GATEWAY,
                "MC-CONNECTOR-IO-ERROR",
                "Statutory Connector Transport Error",
                "Failed to reach statutory endpoint '" + endpointUri.getHost() + "'."
            );
        }
    }
}
