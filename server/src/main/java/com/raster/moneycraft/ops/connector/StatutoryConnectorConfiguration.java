package com.raster.moneycraft.ops.connector;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers air-gapped-by-default NIC / GSTN / GSP Outbound Connector SPI adapters.
 */
@Configuration
public class StatutoryConnectorConfiguration {

    @Bean
    public StatutoryConnectorHttpAdapter einvoiceNicConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "EINVOICE_NIC",
                "NIC e-Invoice IRP Portal Adapter",
                "GST_IRP",
                List.of("einvoice1.gst.gov.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter ewayBillNicConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "EWAYBILL_NIC",
                "NIC e-Way Bill Portal Adapter",
                "GST_EWB",
                List.of("ewaybillgst.gov.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter gstnImsConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "GSTN_IMS",
                "GSTN Invoice Management System (IMS) Adapter",
                "GST_IMS",
                List.of("services.gst.gov.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter gspUnifiedConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "GSP_UNIFIED",
                "Unified GST Suvidha Provider Adapter",
                "GST_GSP",
                List.of("gsp.adaequare.com")
            ),
            egressValidator
        );
    }
}
