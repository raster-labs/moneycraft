package com.raster.moneycraft.ops.connector;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * JSON HTTP transport adapter isolated inside {@code ops.connector} per ArchUnit boundary rules,
 * gating every outbound request behind {@link EgressAllowlistValidator}.
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
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public OutboundConnectorDescriptor descriptor() {
        return descriptor;
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
                "Failed to reach statutory endpoint '" + endpointUri + "': " + ex.getMessage()
            );
        }
    }
}
