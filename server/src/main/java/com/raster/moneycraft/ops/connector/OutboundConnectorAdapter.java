package com.raster.moneycraft.ops.connector;

/**
 * Disabled-by-default Outbound Connector SPI for air-gapped-first statutory adapters
 * (e-Invoice, e-Way Bill, IMS, SMTP, OCR).
 */
public interface OutboundConnectorAdapter {

    OutboundConnectorDescriptor descriptor();
}
