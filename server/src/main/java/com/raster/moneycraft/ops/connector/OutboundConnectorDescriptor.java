package com.raster.moneycraft.ops.connector;

import java.util.List;

/**
 * Metadata describing an optional external statutory or operational connector.
 */
public record OutboundConnectorDescriptor(
    String connectorCode,
    String displayName,
    String category,
    List<String> requiredHostnames
) {
    public OutboundConnectorDescriptor {
        requiredHostnames = requiredHostnames == null ? List.of() : List.copyOf(requiredHostnames);
    }
}
