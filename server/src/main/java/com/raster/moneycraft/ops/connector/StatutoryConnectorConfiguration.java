package com.raster.moneycraft.ops.connector;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers air-gapped-by-default Government Statutory and Connected Banking Outbound Connector SPI adapters.
 */
@Configuration
public class StatutoryConnectorConfiguration {

    @Bean
    public StatutoryConnectorHttpAdapter einvoiceNicConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "EINVOICE_NIC",
                "NIC e-Invoice IRP1 Portal Adapter",
                "GST_IRP",
                List.of("einvoice1.gst.gov.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter einvoiceIrp6ConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "EINVOICE_IRP6",
                "GSTN IRP6 e-Invoice Partner Portal Adapter",
                "GST_IRP6",
                List.of("einvoice6.gst.gov.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter ewayBillNicConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "EWAYBILL_NIC",
                "NIC e-Way Bill v1.03 Portal Adapter",
                "GST_EWB",
                List.of("ewaybillgst.gov.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter gstinLookupConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "GSTIN_LOOKUP",
                "Authenticated GetGSTINDetails Lookup Adapter",
                "GST_LOOKUP",
                List.of("ewaybillgst.gov.in", "einvoice6.gst.gov.in")
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

    @Bean
    public StatutoryConnectorHttpAdapter itdEriType2ConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "ITD_ERI_TYPE2",
                "Income Tax Department Type-2 ERI Adapter",
                "ITD_ERI",
                List.of("eportal.incometax.gov.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter proteanPanVerifyConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "PROTEAN_PAN_VERIFY",
                "Protean Authorised-Entity PAN Verification Adapter",
                "PAN_VERIFY",
                List.of("opv.proteantech.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter iciciCibConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "ICICI_CIB",
                "ICICI Bank Corporate Connected Banking (CIB) Adapter",
                "BANKING_CIB",
                List.of("apibankingone.icicibank.com", "apigwuat.icicibank.com")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter hdfcCbxConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "HDFC_CBX",
                "HDFC Bank Corporate CBX / ENet Connected Banking Adapter",
                "BANKING_CBX",
                List.of("api.hdfcbank.com", "api-uat.hdfcbank.com")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter axisCorpConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "AXIS_CORP",
                "Axis Bank Corporate Neo / ARS Connected Banking Adapter",
                "BANKING_NEO",
                List.of("apiportal.axisbank.com", "sakshamuat.axisbank.co.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter sbiCmpConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "SBI_CMP",
                "State Bank of India YONO Business / CMP Connected Banking Adapter",
                "BANKING_CMP",
                List.of("api.sbi.co.in", "devapi.sbi.co.in")
            ),
            egressValidator
        );
    }

    @Bean
    public StatutoryConnectorHttpAdapter aggregatorPayoutConnectorAdapter(EgressAllowlistValidator egressValidator) {
        return new StatutoryConnectorHttpAdapter(
            new OutboundConnectorDescriptor(
                "AGGREGATOR_PAYOUT",
                "Corporate Payout & Verification Aggregator Adapter",
                "BANKING_AGGREGATOR",
                List.of("api.razorpay.com", "payout-api.cashfree.com", "prod.setu.co")
            ),
            egressValidator
        );
    }
}
