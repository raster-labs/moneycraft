package com.raster.moneycraft.ops.connector;

import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Central registry gating all optional external statutory and operational connectors.
 */
@Service
public class OutboundConnectorRegistry {

    private final MoneyCraftProperties properties;
    private final EgressAllowlistValidator egressValidator;
    private final Map<String, OutboundConnectorAdapter> adapters = new ConcurrentHashMap<>();

    public OutboundConnectorRegistry(
        MoneyCraftProperties properties,
        EgressAllowlistValidator egressValidator,
        List<OutboundConnectorAdapter> discoveredAdapters
    ) {
        this.properties = properties;
        this.egressValidator = egressValidator;
        if (discoveredAdapters != null) {
            for (OutboundConnectorAdapter adapter : discoveredAdapters) {
                adapters.put(adapter.descriptor().connectorCode(), adapter);
            }
        }
    }

    public boolean isConnectorSpiEnabled() {
        return properties.getConnector().isEnabled();
    }

    public List<OutboundConnectorDescriptor> listRegisteredDescriptors() {
        return adapters.values().stream()
            .map(OutboundConnectorAdapter::descriptor)
            .toList();
    }

    public OutboundConnectorAdapter requireEnabledConnector(String connectorCode, URI endpointUri) {
        egressValidator.assertOutboundPermitted(endpointUri);
        OutboundConnectorAdapter adapter = adapters.get(connectorCode);
        if (adapter == null) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-CONNECTOR-NOT-REGISTERED",
                "Outbound Connector Not Registered",
                "No outbound connector adapter registered for code '" + connectorCode + "'."
            );
        }
        return adapter;
    }
}
