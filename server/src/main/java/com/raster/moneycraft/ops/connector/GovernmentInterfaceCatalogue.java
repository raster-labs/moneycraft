package com.raster.moneycraft.ops.connector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Closed server-side catalogue reconciling Document 51's 39 GOV-D01..GOV-D39 statutory rows,
 * 5 Connected Banking rows (UM-110-04), and admitted provider/endpoint/slot schemas.
 */
@Component
public class GovernmentInterfaceCatalogue {

    private static final String INVENTORY_CSV_PATH = "statutory-packs/government_interface_inventory.csv";

    private final List<GovernmentIntegrationDtos.CapabilityInventoryRowDto> capabilityRows;
    private final Map<String, AdmittedProviderDefinition> providersByCode;

    public GovernmentInterfaceCatalogue() {
        this.providersByCode = buildAdmittedProviders();
        this.capabilityRows = loadAndReconcileCapabilities();
    }

    public List<GovernmentIntegrationDtos.CapabilityInventoryRowDto> capabilities() {
        return capabilityRows;
    }

    public List<GovernmentIntegrationDtos.AdmittedProviderSchemaDto> providerSchemas() {
        return providersByCode.values().stream()
            .map(AdmittedProviderDefinition::schema)
            .toList();
    }

    public AdmittedProviderDefinition findProvider(String providerCode) {
        if (providerCode == null) {
            return null;
        }
        return providersByCode.get(providerCode.trim());
    }

    private List<GovernmentIntegrationDtos.CapabilityInventoryRowDto> loadAndReconcileCapabilities() {
        List<GovernmentIntegrationDtos.CapabilityInventoryRowDto> rows = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(INVENTORY_CSV_PATH);
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine != null) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    List<String> cols = parseCsvLine(line);
                    if (cols.size() < 19) {
                        continue;
                    }
                    String id = cols.get(0);
                    String implStatus = reconcileMoneyCraftImplementationStatus(id, cols.get(16));
                    rows.add(new GovernmentIntegrationDtos.CapabilityInventoryRowDto(
                        id,
                        cols.get(1),
                        cols.get(2),
                        cols.get(3),
                        cols.get(4),
                        cols.get(5),
                        cols.get(7),
                        cols.get(8),
                        cols.get(9),
                        cols.get(10),
                        cols.get(11),
                        implStatus,
                        cols.get(17),
                        cols.get(18)
                    ));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed loading " + INVENTORY_CSV_PATH, ex);
        }

        // Append the 5 Connected Banking capability rows (UM-110-04)
        rows.add(new GovernmentIntegrationDtos.CapabilityInventoryRowDto(
            "BANK-CB01",
            "Connected Banking — ICICI CIB",
            "Corporate balance inquiry, statement pull, payout status, composite payout",
            "AUTHENTICATED_API_MTLS",
            "OFFICIAL_BANK_PORTAL_DOCUMENTED",
            "UM-110-04",
            "ICICI CIB ERP aggregator onboarding, X.509 cert exchange, static IP whitelist",
            "AUTH-ICICI-CIB",
            "Balance/statement reads are non-mutating; composite payout initiates bank transfer",
            "NONE",
            "NOT APPLICABLE (SOURCE)",
            "ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (ICICI_CIB)",
            "NOT RUN",
            "Air-gapped disabled by default; live bank connectivity requires commercial CIB onboarding"
        ));
        rows.add(new GovernmentIntegrationDtos.CapabilityInventoryRowDto(
            "BANK-CB02",
            "Connected Banking — HDFC CBX / ENet",
            "Corporate balance, statement pull, bulk/single payout, virtual account reconciliation",
            "AUTHENTICATED_API_MTLS",
            "OFFICIAL_BANK_PORTAL_DOCUMENTED",
            "UM-110-04",
            "HDFC Corporate CMS/CBX onboarding, mTLS + JWS/JWE certificates, static IP whitelist",
            "AUTH-HDFC-CBX",
            "Balance/statement reads are non-mutating; payout mutates bank settlement",
            "NONE",
            "NOT APPLICABLE (SOURCE)",
            "ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (HDFC_CBX)",
            "NOT RUN",
            "Air-gapped disabled by default; requires HDFC CBX UAT & production admission"
        ));
        rows.add(new GovernmentIntegrationDtos.CapabilityInventoryRowDto(
            "BANK-CB03",
            "Connected Banking — Axis Corporate Neo",
            "Corporate account balance, statement inquiry, TransferPayment & status",
            "AUTHENTICATED_API_MTLS",
            "OFFICIAL_BANK_PORTAL_DOCUMENTED",
            "UM-110-04",
            "Axis IBM API Connect subscription, mTLS cert & static IP whitelist",
            "AUTH-AXIS-CORP",
            "Inquiry read-only; TransferPayment initiates corporate transfer",
            "NONE",
            "NOT APPLICABLE (SOURCE)",
            "ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (AXIS_CORP)",
            "NOT RUN",
            "Air-gapped disabled by default; requires Axis Corporate UAT sign-off"
        ));
        rows.add(new GovernmentIntegrationDtos.CapabilityInventoryRowDto(
            "BANK-CB04",
            "Connected Banking — SBI YONO Business / CMP",
            "Corporate balance, statement pull, Cash Management Product (CMP) payments",
            "AUTHENTICATED_API_MTLS",
            "OFFICIAL_BANK_PORTAL_DOCUMENTED",
            "UM-110-04",
            "SBI YONO Business / CMP ERP partner agreement, mTLS + RSA-2048 certs, static IP",
            "AUTH-SBI-CMP",
            "Inquiry read-only; CMP instruction initiates vendor payment",
            "NONE",
            "NOT APPLICABLE (SOURCE)",
            "ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (SBI_CMP)",
            "NOT RUN",
            "Air-gapped disabled by default; requires SBI CMP onboarding"
        ));
        rows.add(new GovernmentIntegrationDtos.CapabilityInventoryRowDto(
            "BANK-CB05",
            "Connected Banking — Payout & Verification Aggregator",
            "Source balance, penny-drop verification, payout status & composite payouts",
            "AUTHENTICATED_API",
            "OFFICIAL_PROVIDER_DOCUMENTED",
            "UM-110-04",
            "Aggregator KYC, Key ID/Secret, IP allowlist & webhook HMAC secret",
            "AUTH-AGGREGATOR",
            "Penny-drop and payouts incur provider charges or move funds; safe probe uses read-only key check",
            "NONE",
            "NOT APPLICABLE (SOURCE)",
            "ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (AGGREGATOR_PAYOUT)",
            "NOT RUN",
            "Air-gapped disabled by default; requires contracted aggregator credentials"
        ));

        return Collections.unmodifiableList(rows);
    }

    private static String reconcileMoneyCraftImplementationStatus(String capabilityId, String baselineCsvStatus) {
        return switch (capabilityId) {
            case "GOV-D06", "GOV-D08", "GOV-D09" ->
                "LOCAL_JSON_RETURN_EXPORT_AND_GSP_ADAPTER_STUBBED_DISABLED (GstrStatutoryService / GSP_UNIFIED)";
            case "GOV-D10" ->
                "LOCAL_IMS_WORKBENCH_AND_CONNECTOR_SPI_DISABLED (ImsReconciliationService / GSTN_IMS)";
            case "GOV-D12", "GOV-D13", "GOV-D14", "GOV-D15", "GOV-D17" ->
                "ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (EINVOICE_NIC / EINVOICE_IRP6)";
            case "GOV-D18", "GOV-D19", "GOV-D22", "GOV-D24" ->
                "ADAPTER_REGISTERED_DISABLED_BY_DEFAULT (EWAYBILL_NIC)";
            case "GOV-D34" ->
                "LOCAL_FVU_TEXT_ARTEFACT_EXPORT_IMPLEMENTED (TdsStatutoryService /tds/export-fvu)";
            default -> baselineCsvStatus;
        };
    }

    private static Map<String, AdmittedProviderDefinition> buildAdmittedProviders() {
        Map<String, AdmittedProviderDefinition> map = new LinkedHashMap<>();

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "GSP_UNIFIED",
                "GSP_UNIFIED_V1",
                "Unified GST Suvidha Provider (GSTR-1 / 2B / 3B / IMS)",
                "GOVERNMENT_STATUTORY",
                "IN-GSTN / GSP-ASP",
                "GSP_SANDBOX_V1",
                "GSP_PRODUCTION_V1",
                "GSTIN",
                List.of("GOV-D05", "GOV-D06", "GOV-D08", "GOV-D09", "GOV-D10"),
                List.of(
                    slot("aspClientId", "ASP Client ID", "SECRET_VALUE", 128, true, "Contracted GSP partner ASP client ID."),
                    slot("aspClientSecret", "ASP Client Secret", "SECRET_VALUE", 256, true, "Write-only GSP ASP secret."),
                    slot("gspAppKey", "GSP Application Key / Public Cert Ref", "SECRET_VALUE", 512, true, "GSP session/RSA key ref."),
                    slot("taxpayerPortalUsername", "Taxpayer GST API Username", "SECRET_VALUE", 128, true, "Taxpayer API username.")
                ),
                true,
                "GSP partner authentication & taxpayer consent token check",
                "Creates/verifies read-only GSP auth session; performs zero return upload or filing."
            ),
            "GSP_UNIFIED",
            URI.create("https://gsp.adaequare.com/sandbox/health"),
            URI.create("https://gsp.adaequare.com/gst/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "EINVOICE_IRP6",
                "EINVOICE_IRP6_V1",
                "GSTN IRP6 e-Invoice Partner Portal Adapter",
                "GOVERNMENT_STATUTORY",
                "IN-GST-IRP6",
                "IRP6_SANDBOX_V1",
                "IRP6_PRODUCTION_V1",
                "GSTIN",
                List.of("GOV-D03", "GOV-D12", "GOV-D13", "GOV-D14", "GOV-D15", "GOV-D16", "GOV-D17"),
                List.of(
                    slot("clientId", "IRP6 Partner Client ID", "SECRET_VALUE", 128, true, "IRP6 partner client ID."),
                    slot("clientSecret", "IRP6 Partner Client Secret", "SECRET_VALUE", 256, true, "Write-only partner secret."),
                    slot("taxpayerApiUsername", "Taxpayer e-Invoice API Username", "SECRET_VALUE", 128, true, "Dedicated IRP API username."),
                    slot("taxpayerApiPassword", "Taxpayer e-Invoice API Password", "SECRET_VALUE", 256, true, "Write-only IRP API password.")
                ),
                true,
                "IRP6 Core Service Health & Partner Session Probe (GOV-D17)",
                "Executes harmless IRP health/session handshake only. Never generates or cancels an IRN."
            ),
            "EINVOICE_IRP6",
            URI.create("https://einvoice6.gst.gov.in/eicore/sandbox/v1.03/health"),
            URI.create("https://einvoice6.gst.gov.in/eicore/v1.03/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "EINVOICE_NIC",
                "EINVOICE_NIC_V1",
                "NIC e-Invoice IRP1 Portal Adapter",
                "GOVERNMENT_STATUTORY",
                "IN-GST-NIC-IRP1",
                "NIC_IRP_SANDBOX_V1",
                "NIC_IRP_PRODUCTION_V1",
                "GSTIN",
                List.of("GOV-D12", "GOV-D13", "GOV-D14", "GOV-D15", "GOV-D17"),
                List.of(
                    slot("clientId", "NIC IRP Client ID", "SECRET_VALUE", 128, true, "NIC / GSP issued client identifier."),
                    slot("clientSecret", "NIC IRP Client Secret", "SECRET_VALUE", 256, true, "Write-only client secret."),
                    slot("taxpayerApiUsername", "e-Invoice API Username", "SECRET_VALUE", 128, true, "Taxpayer IRP API username."),
                    slot("taxpayerApiPassword", "e-Invoice API Password", "SECRET_VALUE", 256, true, "Write-only taxpayer IRP API password.")
                ),
                true,
                "NIC IRP1 Authentication & Health Probe (GOV-D17)",
                "Establishes short-lived auth context only; zero fiscal document mutation."
            ),
            "EINVOICE_NIC",
            URI.create("https://einvoice1.gst.gov.in/eicore/sandbox/v1.03/health"),
            URI.create("https://einvoice1.gst.gov.in/eicore/v1.03/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "EWAYBILL_NIC",
                "EWAYBILL_NIC_V103",
                "NIC e-Way Bill v1.03 Adapter",
                "GOVERNMENT_STATUTORY",
                "IN-NIC-EWB-V103",
                "NIC_EWB_SANDBOX_V103",
                "NIC_EWB_PRODUCTION_V103",
                "GSTIN",
                List.of("GOV-D02", "GOV-D18", "GOV-D19", "GOV-D20", "GOV-D21", "GOV-D22", "GOV-D23", "GOV-D24", "GOV-D25"),
                List.of(
                    slot("gspClientId", "EWB / GSP Client ID", "SECRET_VALUE", 128, true, "NIC direct or GSP client ID."),
                    slot("gspClientSecret", "EWB / GSP Client Secret", "SECRET_VALUE", 256, true, "Write-only v1.03 client secret."),
                    slot("ewbApiUsername", "Taxpayer EWB API Username", "SECRET_VALUE", 128, true, "API username under ewaybillgst.gov.in."),
                    slot("ewbApiPassword", "Taxpayer EWB API Password", "SECRET_VALUE", 256, true, "Write-only v1.03 API password.")
                ),
                true,
                "NIC EWB Authenticate v1.03 Session Check (GOV-D18)",
                "Validates v1.03 encrypted auth payload only. Never generates, updates, or cancels an e-Way Bill."
            ),
            "EWAYBILL_NIC",
            URI.create("https://ewaybillgst.gov.in/ewbsandbox/v1.03/health"),
            URI.create("https://ewaybillgst.gov.in/ewbapi/v1.03/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "GSTN_IMS",
                "GSTN_IMS_V1",
                "GSTN Invoice Management System (IMS) Adapter",
                "GOVERNMENT_STATUTORY",
                "IN-GSTN-IMS",
                "GSTN_IMS_SANDBOX_V1",
                "GSTN_IMS_PRODUCTION_V1",
                "GSTIN",
                List.of("GOV-D05", "GOV-D08", "GOV-D10"),
                List.of(
                    slot("aspClientId", "ASP Client ID", "SECRET_VALUE", 128, true, "GSP/ASP client ID."),
                    slot("aspClientSecret", "ASP Client Secret", "SECRET_VALUE", 256, true, "Write-only ASP secret."),
                    slot("taxpayerPortalUsername", "Taxpayer GST API Username", "SECRET_VALUE", 128, true, "Taxpayer API username.")
                ),
                true,
                "GSTN IMS Read-Only Session & Consent Check",
                "Checks read-only session status; never uploads or resets IMS invoice actions."
            ),
            "GSTN_IMS",
            URI.create("https://services.gst.gov.in/ims/sandbox/health"),
            URI.create("https://services.gst.gov.in/ims/v1/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "GSTIN_LOOKUP",
                "NIC_GSTIN_LOOKUP_V103",
                "Authenticated GetGSTINDetails Lookup Adapter",
                "GOVERNMENT_STATUTORY",
                "IN-NIC-MASTER-LOOKUP",
                "GSTIN_LOOKUP_SANDBOX_V103",
                "GSTIN_LOOKUP_PRODUCTION_V103",
                "GSTIN",
                List.of("GOV-D02", "GOV-D03"),
                List.of(
                    slot("clientId", "Service Client ID", "SECRET_VALUE", 128, true, "Authorised lookup client ID."),
                    slot("clientSecret", "Service Client Secret", "SECRET_VALUE", 256, true, "Write-only lookup client secret.")
                ),
                true,
                "Read-only GetGSTINDetails self-registration check (GOV-D02)",
                "Read-only lookup; never alters accounting masters without user acceptance."
            ),
            "GSTIN_LOOKUP",
            URI.create("https://ewaybillgst.gov.in/Master/sandbox/GetGSTINDetails"),
            URI.create("https://ewaybillgst.gov.in/Master/GetGSTINDetails")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "ITD_ERI_TYPE2",
                "ITD_ERI_TYPE2_V11",
                "Income Tax Department Type-2 ERI Adapter",
                "GOVERNMENT_STATUTORY",
                "IN-ITD-ERI-TYPE2",
                "ITD_ERI_SANDBOX_V11",
                "ITD_ERI_PRODUCTION_V11",
                "PAN",
                List.of("GOV-D26", "GOV-D27", "GOV-D28", "GOV-D29", "GOV-D30", "GOV-D31"),
                List.of(
                    slot("eriUserId", "Registered Type-2 ERI User ID", "SECRET_VALUE", 128, true, "Approved ITD Type-2 ERI ID."),
                    slot("gatewayClientSecret", "ITD Gateway Client Secret", "SECRET_VALUE", 256, true, "Write-only ERI gateway secret."),
                    slot("dscSigningKeyRef", "Server DSC Signing Key Reference", "SERVER_SECRET_REF", 256, true, "Server DSC reference.")
                ),
                false,
                "Not validated — current post-2021 ITD ERI live probe contract pending admission",
                "No automated safe probe executed; remains NOT_VALIDATED until current ITD gateway schema is admitted."
            ),
            "ITD_ERI_TYPE2",
            URI.create("https://eportal.incometax.gov.in/eri/sandbox/health"),
            URI.create("https://eportal.incometax.gov.in/eri/v1/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "PROTEAN_PAN_VERIFY",
                "PROTEAN_PAN_V1",
                "Protean Authorised-Entity Online PAN Verification",
                "GOVERNMENT_STATUTORY",
                "IN-PROTEAN-PAN",
                "PROTEAN_PAN_SANDBOX_V1",
                "PROTEAN_PAN_PRODUCTION_V1",
                "PAN",
                List.of("GOV-D32", "GOV-D33"),
                List.of(
                    slot("proteanUserId", "Protean Enrolment User ID", "SECRET_VALUE", 128, true, "Protean agency user ID."),
                    slot("apiAccessToken", "Protean API Access Secret", "SECRET_VALUE", 256, true, "Write-only Protean API secret."),
                    slot("signingCertKeyRef", "mTLS / Signing Certificate Reference", "SERVER_SECRET_REF", 256, true, "Server X.509 cert ref.")
                ),
                true,
                "Protean mTLS & Agency Credential Check",
                "Verifies certificate & credential envelope; live verification calls may incur per-query provider fee."
            ),
            "PROTEAN_PAN_VERIFY",
            URI.create("https://opv.proteantech.in/sandbox/health"),
            URI.create("https://opv.proteantech.in/api/v1/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "ICICI_CIB",
                "ICICI_CIB_CONNECTED_V1",
                "ICICI Bank Corporate Connected Banking (CIB)",
                "CONNECTED_BANKING",
                "IN-BANK-ICICI-CIB",
                "ICICI_CIB_SANDBOX_V1",
                "ICICI_CIB_PRODUCTION_V1",
                "CORPORATE_BANK_ID",
                List.of("BANK-BAL-INQUIRY", "BANK-STMT-PULL", "BANK-PAYOUT-STATUS", "BANK-COMPOSITE-PAYOUT"),
                List.of(
                    slot("apiKey", "ICICI Developer API Key", "SECRET_VALUE", 256, true, "Write-only ICICI gateway apikey."),
                    slot("aggregatorId", "Aggregator ID (AGGRID)", "SECRET_VALUE", 128, true, "Assigned ERP aggregator identifier."),
                    slot("corpIdAndUrn", "Corporate ID / URN Binding", "SECRET_VALUE", 128, true, "Approved CIB Corporate ID and URN."),
                    slot("rsaPrivateKeyRef", "Hybrid RSA-2048 Key Reference", "SERVER_SECRET_REF", 256, true, "Server RSA key reference.")
                ),
                true,
                "ICICI CIB Read-Only Handshake / Balance Probe",
                "Read-only inquiry only; never initiates NEFT/RTGS/IMPS/UPI payout."
            ),
            "ICICI_CIB",
            URI.create("https://apigwuat.icicibank.com/cib/v1/health"),
            URI.create("https://apibankingone.icicibank.com/cib/v1/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "HDFC_CBX",
                "HDFC_CBX_ENET_V1",
                "HDFC Bank Corporate CBX / ENet Connected Banking",
                "CONNECTED_BANKING",
                "IN-BANK-HDFC-CBX",
                "HDFC_CBX_SANDBOX_V1",
                "HDFC_CBX_PRODUCTION_V1",
                "CORPORATE_BANK_ID",
                List.of("BANK-BAL-INQUIRY", "BANK-STMT-PULL", "BANK-PAYOUT-STATUS", "BANK-BULK-PAYOUT"),
                List.of(
                    slot("clientId", "HDFC API Client ID", "SECRET_VALUE", 128, true, "Corporate portal client ID."),
                    slot("clientSecret", "HDFC API Client Secret", "SECRET_VALUE", 256, true, "Write-only client secret."),
                    slot("mtlsAndJweKeyRef", "mTLS + JWS/JWE Key Reference", "SERVER_SECRET_REF", 256, true, "Server X.509 mTLS + JWS/JWE ref.")
                ),
                true,
                "HDFC mTLS & OAuth Token Read-Only Probe",
                "Verifies mutual TLS & scope token only; zero payment dispatch."
            ),
            "HDFC_CBX",
            URI.create("https://api-uat.hdfcbank.com/cbx/v1/health"),
            URI.create("https://api.hdfcbank.com/cbx/v1/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "AXIS_CORP",
                "AXIS_CORP_NEO_V1",
                "Axis Bank Corporate Neo / ARS Connected Banking",
                "CONNECTED_BANKING",
                "IN-BANK-AXIS-CORP",
                "AXIS_CORP_SANDBOX_V1",
                "AXIS_CORP_PRODUCTION_V1",
                "CORPORATE_BANK_ID",
                List.of("BANK-BAL-INQUIRY", "BANK-STMT-PULL", "BANK-PAYOUT-STATUS", "BANK-TRANSFER-PAYMENT"),
                List.of(
                    slot("ibmClientId", "X-IBM-Client-Id", "SECRET_VALUE", 128, true, "Axis API Connect client ID."),
                    slot("ibmClientSecret", "X-IBM-Client-Secret", "SECRET_VALUE", 256, true, "Write-only Axis client secret."),
                    slot("aesAndMtlsKeyRef", "AES-256 & mTLS Certificate Reference", "SERVER_SECRET_REF", 256, true, "Server mTLS + AES key ref.")
                ),
                true,
                "Axis IBM Gateway mTLS & Account Status Probe",
                "Read-only status check; never executes TransferPayment."
            ),
            "AXIS_CORP",
            URI.create("https://sakshamuat.axisbank.co.in/corp/v1/health"),
            URI.create("https://apiportal.axisbank.com/corp/v1/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "SBI_CMP",
                "SBI_YONO_CMP_V1",
                "State Bank of India YONO Business / CMP Connected Banking",
                "CONNECTED_BANKING",
                "IN-BANK-SBI-CMP",
                "SBI_CMP_SANDBOX_V1",
                "SBI_CMP_PRODUCTION_V1",
                "CORPORATE_BANK_ID",
                List.of("BANK-BAL-INQUIRY", "BANK-STMT-PULL", "BANK-CMP-STATUS", "BANK-CMP-PAYMENT"),
                List.of(
                    slot("partnerClientCode", "SBI Partner Client Code / CIN", "SECRET_VALUE", 128, true, "SBI YONO/CMP partner code."),
                    slot("aesGcmSecretKey", "AES-256-GCM Session Secret", "SECRET_VALUE", 256, true, "Write-only SBI payload key."),
                    slot("mtlsSigningCertRef", "mTLS & RSA-2048 Signing Cert Ref", "SERVER_SECRET_REF", 256, true, "Server mTLS + signing ref.")
                ),
                true,
                "SBI CMP Partner mTLS & Balance Inquiry Probe",
                "Read-only partner handshake; zero CMP payment instruction."
            ),
            "SBI_CMP",
            URI.create("https://devapi.sbi.co.in/cmp/v1/health"),
            URI.create("https://api.sbi.co.in/cmp/v1/health")
        ));

        register(map, new AdmittedProviderDefinition(
            new GovernmentIntegrationDtos.AdmittedProviderSchemaDto(
                "AGGREGATOR_PAYOUT",
                "AGGREGATOR_PAYOUT_V1",
                "Corporate Payout & Verification Aggregator (RazorpayX / Cashfree / Setu)",
                "CONNECTED_BANKING",
                "IN-PAYOUT-AGGREGATOR",
                "AGGREGATOR_SANDBOX_V1",
                "AGGREGATOR_PRODUCTION_V1",
                "CORPORATE_BANK_ID",
                List.of("BANK-BAL-INQUIRY", "BANK-PENNY-DROP-VERIFY", "BANK-PAYOUT-STATUS", "BANK-COMPOSITE-PAYOUT"),
                List.of(
                    slot("keyId", "Aggregator Key ID", "SECRET_VALUE", 128, true, "Issued Sandbox or Live Key ID."),
                    slot("keySecret", "Aggregator Key Secret", "SECRET_VALUE", 256, true, "Write-only Key Secret."),
                    slot("webhookVerifySecret", "Webhook HMAC Verification Secret", "SECRET_VALUE", 256, false, "Optional webhook secret.")
                ),
                true,
                "Aggregator Key Auth & Source Account Read Probe",
                "Read-only credential verification; zero payout or chargeable penny-drop."
            ),
            "AGGREGATOR_PAYOUT",
            URI.create("https://api.razorpay.com/v1/sandbox/health"),
            URI.create("https://api.razorpay.com/v1/health")
        ));

        return Collections.unmodifiableMap(map);
    }

    private static void register(Map<String, AdmittedProviderDefinition> map, AdmittedProviderDefinition def) {
        map.put(def.schema().providerCode(), def);
    }

    private static GovernmentIntegrationDtos.CredentialSlotSchemaDto slot(
        String key,
        String label,
        String secretKind,
        int maxLength,
        boolean required,
        String hint
    ) {
        return new GovernmentIntegrationDtos.CredentialSlotSchemaDto(
            key,
            label,
            secretKind,
            maxLength,
            required,
            hint
        );
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        out.add(current.toString());
        return out;
    }

    public record AdmittedProviderDefinition(
        GovernmentIntegrationDtos.AdmittedProviderSchemaDto schema,
        String connectorCode,
        URI defaultSandboxProbeUri,
        URI defaultProductionProbeUri
    ) {
        public String expectedEndpointProfileId(String environment) {
            return "PRODUCTION".equalsIgnoreCase(environment)
                ? schema.productionEndpointProfileId()
                : schema.sandboxEndpointProfileId();
        }

        public URI defaultProbeUri(String environment) {
            return "PRODUCTION".equalsIgnoreCase(environment)
                ? defaultProductionProbeUri
                : defaultSandboxProbeUri;
        }
    }
}
