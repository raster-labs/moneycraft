package com.raster.moneycraft.ops.connector;

import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.net.URI;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies air-gapped disabled-by-default Outbound Connector SPI and HTTPS hostname allowlist enforcement.
 */
class OutboundConnectorRegistryTest {

    @Test
    void rejectsAllEgressByDefaultWhenSpiDisabled() {
        MoneyCraftProperties props = new MoneyCraftProperties();
        EgressAllowlistValidator validator = new EgressAllowlistValidator(props);
        OutboundConnectorRegistry registry = new OutboundConnectorRegistry(props, validator, List.of());

        Assertions.assertThat(registry.isConnectorSpiEnabled()).isFalse();
        Assertions.assertThatThrownBy(() ->
            registry.requireEnabledConnector("NIC_EINVOICE", URI.create("https://einvoice1.gst.gov.in"))
        )
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-CONNECTOR-DISABLED");
    }

    @Test
    void enforcesHttpsAndExplicitHostnameAllowlistWhenEnabled() {
        MoneyCraftProperties props = new MoneyCraftProperties();
        props.getConnector().setEnabled(true);
        props.getConnector().setEgressHostnameAllowlist(List.of("einvoice1.gst.gov.in"));

        OutboundConnectorAdapter adapter = () -> new OutboundConnectorDescriptor(
            "NIC_EINVOICE",
            "NIC e-Invoice IRP Adapter",
            "GST_IRP",
            List.of("einvoice1.gst.gov.in")
        );

        OutboundConnectorRegistry registry = new OutboundConnectorRegistry(
            props,
            new EgressAllowlistValidator(props),
            List.of(adapter)
        );

        Assertions.assertThatThrownBy(() ->
            registry.requireEnabledConnector("NIC_EINVOICE", URI.create("http://einvoice1.gst.gov.in"))
        )
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-CONNECTOR-TLS-REQUIRED");

        Assertions.assertThatThrownBy(() ->
            registry.requireEnabledConnector("NIC_EINVOICE", URI.create("https://unapproved.example.org"))
        )
            .isInstanceOf(MoneyCraftException.class)
            .extracting(ex -> ((MoneyCraftException) ex).code())
            .isEqualTo("MC-CONNECTOR-EGRESS-DENIED");

        OutboundConnectorAdapter resolved = registry.requireEnabledConnector(
            "NIC_EINVOICE",
            URI.create("https://einvoice1.gst.gov.in/eicore/v1.03/Invoice")
        );
        Assertions.assertThat(resolved.descriptor().connectorCode()).isEqualTo("NIC_EINVOICE");
    }
}
