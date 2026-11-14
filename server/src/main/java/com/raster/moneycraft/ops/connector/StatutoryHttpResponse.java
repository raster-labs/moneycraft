package com.raster.moneycraft.ops.connector;

/**
 * Raw HTTP response payload returned by an allowlisted Outbound Connector SPI exchange.
 */
public record StatutoryHttpResponse(
    int statusCode,
    String body
) {
}
