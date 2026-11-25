package com.raster.moneycraft.qa;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end Failsafe integration verification across real Dual-Cluster PostgreSQL 18.6:
 * - Authority V002 OIDC lifecycle schema & unauthenticated OIDC issuer discovery (MC-FED-01)
 * - Neutral legacy .1800 snapshot staging & zero-variance promotion (NAM-001)
 * - Complete Chart of Accounts (28 groups), custom masters, currencies, cost centres,
 *   UOMs, Godowns, Stock Groups, Stock Items, Multi-BOMs, and Price Lists
 * - All core & supply-chain voucher modes (Contra, Payment Agst Ref, Receipt, Journal 194J,
 *   Sales & Purchase Item Invoices, Optional voucher 0.00 TB impact, Stock/Manufacturing Journal,
 *   Physical Stock, Sales/Purchase Orders, Credit/Debit Notes, Day Book Cancel retaining serial
 *   for GSTR-1 Table 13, and MCA Rule 3(1) Edit Log v1 -> v2 redline diffs)
 * - Statutory returns (GSTR-1, GSTR-3B, One-Click GSTR-9, IMS ITC reduction + remarks,
 *   offline e-Invoice/EWB JSON, TDS Form 26Q + FVU), Banking (Cheques, Auto-BRS, OCR Staging),
 *   SmartFind, all Financial & Supply-Chain Reports, and PDFBox 3 embedded-font verification.
 */
@SpringBootTest(
    classes = MoneyCraftApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompleteErpWorkflowAndStatutoryIT {

    private static final String OWNER_USER = "qa_erp_verifier";
    private static final String OWNER_PASS = "QaErpWorkflow#2026!";

    private static final QaDualClusterTestSupport.NetworkedDualCluster CLUSTERS =
        QaDualClusterTestSupport.startNetworkedDualPostgres18();

    private static CookieManager cookieManager;
    private static HttpClient httpClient;

    private static UUID workflowCompanyId;
    private static UUID hdfcBankLedgerId;
    private static UUID cashLedgerId;
    private static UUID capitalLedgerId;
    private static UUID debtorLedgerId;
    private static UUID creditorLedgerId;
    private static UUID advisorLedgerId;
    private static UUID salesLedgerId;
    private static UUID purchaseLedgerId;
    private static UUID consultancyLedgerId;
    private static UUID cgstLedgerId;
    private static UUID sgstLedgerId;
    private static UUID igstLedgerId;
    private static UUID tdsLedgerId;
    private static UUID costCategoryId;
    private static UUID costCentreId;
    private static UUID uomNosId;
    private static UUID plantGodownId;
    private static UUID rawMcuItemId;
    private static UUID smartControllerItemId;
    private static UUID controllerBomId;
    private static UUID primarySalesVoucherId;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("authorityJdbcTemplate")
    private JdbcTemplate authorityJdbcTemplate;

    @DynamicPropertySource
    static void registerDualClusterProperties(DynamicPropertyRegistry registry) {
        registry.add("moneycraft.datasource.authority.jdbc-url", CLUSTERS.authorityPg()::getJdbcUrl);
        registry.add("moneycraft.datasource.authority.username", () -> QaDualClusterTestSupport.RUNTIME_USER);
        registry.add("moneycraft.datasource.authority.password", () -> QaDualClusterTestSupport.RUNTIME_PASS);
        registry.add("moneycraft.datasource.authority.migrator-username", () -> QaDualClusterTestSupport.MIGRATOR_USER);
        registry.add("moneycraft.datasource.authority.migrator-password", () -> QaDualClusterTestSupport.MIGRATOR_PASS);

        registry.add("moneycraft.datasource.business.jdbc-url", CLUSTERS.businessPg()::getJdbcUrl);
        registry.add("moneycraft.datasource.business.username", () -> QaDualClusterTestSupport.RUNTIME_USER);
        registry.add("moneycraft.datasource.business.password", () -> QaDualClusterTestSupport.RUNTIME_PASS);
        registry.add("moneycraft.datasource.business.migrator-username", () -> QaDualClusterTestSupport.MIGRATOR_USER);
        registry.add("moneycraft.datasource.business.migrator-password", () -> QaDualClusterTestSupport.MIGRATOR_PASS);

        registry.add("moneycraft.crypto.master-kek", () -> QaDualClusterTestSupport.TEST_KEK);
        registry.add("moneycraft.security.cookie-secure", () -> "false");
        registry.add("moneycraft.base-url", () -> "http://localhost:8080");
        registry.add("moneycraft.webauthn.rp-id", () -> "localhost");
        registry.add("moneycraft.webauthn.allowed-origins[0]", () -> "http://localhost:8080");
        registry.add("management.server.port", () -> "0");
    }

    @BeforeAll
    static void initClient() {
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        httpClient = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    @AfterAll
    static void tearDownClusters() {
        CLUSTERS.close();
    }

    @Test
    @Order(1)
    void authorityV002SchemaOidcIssuersAndNeutralLegacy1800Migration(@TempDir Path tempDir) throws Exception {
        // 1. Verify Authority V002 schema columns for MC-FED-01 lifecycle
        Integer issuerCols = authorityJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM information_schema.columns
             WHERE table_name = 'ma_issuer'
               AND column_name IN ('checked_at', 'metadata_cipher', 'config_digest', 'federation_component_id', 'lifecycle_profile')
            """,
            Integer.class
        );
        Assertions.assertThat(issuerCols).isEqualTo(5);

        Integer linkCols = authorityJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM information_schema.columns
             WHERE table_name = 'ma_oidc_link'
               AND column_name = 'checked_at'
            """,
            Integer.class
        );
        Assertions.assertThat(linkCols).isEqualTo(1);

        Integer grantCols = authorityJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM information_schema.columns
             WHERE table_name = 'ma_grant'
               AND column_name IN ('source', 'source_ref')
            """,
            Integer.class
        );
        Assertions.assertThat(grantCols).isEqualTo(2);

        // 2. Verify unauthenticated OIDC issuers endpoint for Login UI
        HttpResponse<String> oidcIssuersRes = getJson("/api/v1/auth/oidc/issuers");
        Assertions.assertThat(oidcIssuersRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(oidcIssuersRes.body()).isArray()).isTrue();

        // 3. Bootstrap owner
        HttpResponse<String> initRes = postPreAuth(
            "/api/v1/system/initialise",
            """
            {
              "installationLabel": "MoneyCraft Complete Workflow QA Cluster",
              "ownerUsername": "%s",
              "ownerDisplayName": "Principal QA Verifier",
              "password": "%s"
            }
            """.formatted(OWNER_USER, OWNER_PASS)
        );
        Assertions.assertThat(initRes.statusCode()).isEqualTo(201);

        // 4. Exercise neutral NAM-001 /imports/legacy-1800/stage route against isolated copy of 100000 snapshot
        Path oracle100000XmlDir = resolveWorkspacePath("argon_workspace", "windows_lab_oracles", "100000");
        Path source100000Dir = discoverSnapshot100000Dir();
        if (source100000Dir != null && Files.isDirectory(oracle100000XmlDir)) {
            Path isolatedCopy = tempDir.resolve("legacy_1800_copy");
            Files.createDirectories(isolatedCopy);
            int copiedCount = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source100000Dir, "*.1800")) {
                for (Path file : stream) {
                    Files.copy(file, isolatedCopy.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    copiedCount++;
                }
            }
            Assertions.assertThat(copiedCount).isGreaterThanOrEqualTo(5);

            String migPrefix = "33AABCM9100R1Z";
            String migGstin = migPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(migPrefix);
            UUID migratedCmpId = createCompany("MCMIG1800", "ABC Legacy Snapshot QA Ltd", "2026-04-01", migGstin);
            relogin();

            HttpResponse<String> stageRes = postMutate(
                "/api/v1/companies/" + migratedCmpId + "/imports/legacy-1800/stage",
                """
                {
                  "snapshotDirectoryPath": "%s",
                  "companionXmlDirectoryPath": "%s"
                }
                """.formatted(
                    isolatedCopy.toAbsolutePath().toString().replace("\\", "/"),
                    oracle100000XmlDir.toAbsolutePath().toString().replace("\\", "/")
                ),
                "qa-legacy-1800-stage-001"
            );
            Assertions.assertThat(stageRes.statusCode())
                .as("Neutral /imports/legacy-1800/stage response: %s", stageRes.body())
                .isEqualTo(201);
            JsonNode proof = objectMapper.readTree(stageRes.body());
            Assertions.assertThat(proof.get("sector0Verified").asBoolean()).isTrue();
            Assertions.assertThat(proof.get("isZeroVariance").asBoolean()).isTrue();
            String jobId = proof.get("id").asString();

            HttpResponse<String> commitRes = postMutate(
                "/api/v1/companies/" + migratedCmpId + "/imports/" + jobId + "/commit",
                "{}",
                "qa-legacy-1800-commit-001"
            );
            Assertions.assertThat(commitRes.statusCode()).isEqualTo(200);
            Assertions.assertThat(objectMapper.readTree(commitRes.body()).get("status").asString())
                .isEqualTo("COMMITTED");
        }
    }

    @Test
    @Order(2)
    void completeMastersInventoryBomAndPriceListWorkflow() throws Exception {
        String prefix = "33AABCW2026Q1Z";
        String companyGstin = prefix + ExactMoneyValidator.computeGstinMod36CheckChar(prefix);
        workflowCompanyId = createCompany(
            "MCQAERP01",
            "MoneyCraft Complete Workflow Verification Ltd",
            "2025-04-01",
            companyGstin
        );
        relogin();

        // Verify 28 predefined Account Groups & CoA Tree
        HttpResponse<String> groupsRes = getJson("/api/v1/companies/" + workflowCompanyId + "/account-groups?limit=100");
        Assertions.assertThat(groupsRes.statusCode()).isEqualTo(200);
        JsonNode groupsItems = objectMapper.readTree(groupsRes.body()).get("items");
        Assertions.assertThat(groupsItems.size()).isEqualTo(28);

        HttpResponse<String> coaTreeRes = getJson("/api/v1/companies/" + workflowCompanyId + "/chart-of-accounts/tree");
        Assertions.assertThat(coaTreeRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(coaTreeRes.body()).get("openingDifference").asString())
            .isEqualTo("0.00");

        UUID sundryDebtorsId = findGroupId("Sundry Debtors");
        UUID sundryCreditorsId = findGroupId("Sundry Creditors");
        UUID capitalGroupId = findGroupId("Capital Account");
        UUID bankGroupId = findGroupId("Bank Accounts");
        UUID salesGroupId = findGroupId("Sales Accounts");
        UUID purchaseGroupId = findGroupId("Purchase Accounts");
        UUID indirectExpGroupId = findGroupId("Indirect Expenses");
        UUID dutiesTaxesGroupId = findGroupId("Duties & Taxes");

        // Create custom sub-group under Sundry Debtors
        HttpResponse<String> customGrpRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/account-groups",
            """
            {
              "name": "Institutional Healthcare Debtors",
              "parentGroupId": "%s",
              "behavesLikeSubledger": true
            }
            """.formatted(sundryDebtorsId),
            "qa-grp-inst-debtors"
        );
        Assertions.assertThat(customGrpRes.statusCode()).isEqualTo(201);
        UUID customDebtorsGroupId = UUID.fromString(objectMapper.readTree(customGrpRes.body()).get("id").asString());

        cashLedgerId = findLedgerId("Cash");
        capitalLedgerId = createLedger("Founder Equity Capital", capitalGroupId, "{ \"maintainBalancesBillByBill\": false }");
        hdfcBankLedgerId = createLedger(
            "HDFC Operating Current A/c",
            bankGroupId,
            """
            {
              "maintainBalancesBillByBill": false,
              "bankDetails": {
                "accountHolder": "MoneyCraft Complete Workflow Verification Ltd",
                "accountNumber": "50200099887711",
                "ifscCode": "HDFC0000123",
                "bankName": "HDFC Bank"
              }
            }
            """
        );

        String debtorPrefix = "33AABCS9012H1Z";
        String debtorGstin = debtorPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(debtorPrefix);
        debtorLedgerId = createLedger(
            "Salem Regional Hospital",
            customDebtorsGroupId,
            """
            {
              "maintainBalancesBillByBill": true,
              "partyRegistrationType": "REGULAR",
              "countryCode": "IN",
              "stateCode": "33",
              "gstin": "%s"
            }
            """.formatted(debtorGstin)
        );

        String creditorPrefix = "33AAFCK5678G1Z";
        String creditorGstin = creditorPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(creditorPrefix);
        creditorLedgerId = createLedger(
            "Kaveri Electronics Pvt Ltd",
            sundryCreditorsId,
            """
            {
              "maintainBalancesBillByBill": true,
              "partyRegistrationType": "REGULAR",
              "countryCode": "IN",
              "stateCode": "33",
              "gstin": "%s"
            }
            """.formatted(creditorGstin)
        );

        advisorLedgerId = createLedger(
            "Apex Statutory Advisors",
            sundryCreditorsId,
            """
            {
              "maintainBalancesBillByBill": true,
              "countryCode": "IN",
              "stateCode": "33",
              "pan": "AABFA4321K",
              "tdsApplicable": true,
              "tdsDeducteeType": "PARTNERSHIP_FIRM"
            }
            """
        );

        salesLedgerId = createLedger(
            "Sales - GST Goods",
            salesGroupId,
            """
            {
              "inventoryValuesAffected": true,
              "gstApplicability": "APPLICABLE",
              "gstSupplyType": "GOODS"
            }
            """
        );
        purchaseLedgerId = createLedger(
            "Purchases - GST Goods",
            purchaseGroupId,
            """
            {
              "inventoryValuesAffected": true,
              "gstApplicability": "APPLICABLE",
              "gstSupplyType": "GOODS"
            }
            """
        );
        consultancyLedgerId = createLedger(
            "Professional & Audit Fees",
            indirectExpGroupId,
            """
            {
              "costCentresApplicable": true,
              "tdsApplicable": true,
              "tdsNatureCode": "194J"
            }
            """
        );
        cgstLedgerId = createLedger(
            "CGST",
            dutiesTaxesGroupId,
            "{ \"taxType\": \"GST\", \"dutyHead\": \"CGST\" }"
        );
        sgstLedgerId = createLedger(
            "SGST",
            dutiesTaxesGroupId,
            "{ \"taxType\": \"GST\", \"dutyHead\": \"SGST_UTGST\" }"
        );
        igstLedgerId = createLedger(
            "IGST",
            dutiesTaxesGroupId,
            "{ \"taxType\": \"GST\", \"dutyHead\": \"IGST\" }"
        );
        Assertions.assertThat(igstLedgerId).isNotNull();
        tdsLedgerId = createLedger(
            "TDS Payable - 194J",
            dutiesTaxesGroupId,
            "{ \"taxType\": \"TDS\", \"dutyHead\": \"TDS_SEC\", \"tdsNatureCode\": \"194J\" }"
        );

        // Foreign Currency & Forex Rate
        HttpResponse<String> usdRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/currencies",
            """
            {
              "isoCode": "USD",
              "symbol": "$",
              "formalName": "US Dollar",
              "decimalPlaces": 2
            }
            """,
            "qa-cur-usd"
        );
        Assertions.assertThat(usdRes.statusCode()).isEqualTo(201);
        UUID usdId = UUID.fromString(objectMapper.readTree(usdRes.body()).get("id").asString());

        HttpResponse<String> rateRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/currencies/" + usdId + "/rates",
            """
            {
              "effectiveDate": "2025-04-01",
              "standardRate": "84.50",
              "sellingRate": "84.75",
              "buyingRate": "84.25"
            }
            """,
            "qa-cur-usd-rate"
        );
        Assertions.assertThat(rateRes.statusCode()).isIn(200, 201);

        // Cost Category & Cost Centre
        HttpResponse<String> ccCatRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/cost-categories",
            """
            {
              "name": "Operating Divisions",
              "allocateRevenueItems": true,
              "allocateNonRevenueItems": true
            }
            """,
            "qa-cc-cat"
        );
        Assertions.assertThat(ccCatRes.statusCode()).isEqualTo(201);
        costCategoryId = UUID.fromString(objectMapper.readTree(ccCatRes.body()).get("id").asString());

        HttpResponse<String> ccRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/cost-centres",
            """
            {
              "categoryId": "%s",
              "name": "Chennai Engineering Lab",
              "aliasCode": "CEN-LAB"
            }
            """.formatted(costCategoryId),
            "qa-cc-lab"
        );
        Assertions.assertThat(ccRes.statusCode()).isEqualTo(201);
        costCentreId = UUID.fromString(objectMapper.readTree(ccRes.body()).get("id").asString());

        // Inventory Masters: UOM, Godown, Stock Group, Stock Items, Multi-BOM, Price List
        HttpResponse<String> uomRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/uoms",
            """
            {
              "uomType": "SIMPLE",
              "symbol": "NOS",
              "formalName": "Numbers",
              "gstUqcCode": "NOS",
              "decimalPlaces": 0
            }
            """,
            "qa-uom-nos"
        );
        Assertions.assertThat(uomRes.statusCode()).isEqualTo(201);
        uomNosId = UUID.fromString(objectMapper.readTree(uomRes.body()).get("id").asString());

        HttpResponse<String> godownRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/godowns",
            """
            {
              "name": "Chennai Assembly Plant",
              "aliasCode": "CAP-01",
              "allowStorageOfMaterials": true
            }
            """,
            "qa-godown-plant"
        );
        Assertions.assertThat(godownRes.statusCode()).isEqualTo(201);
        plantGodownId = UUID.fromString(objectMapper.readTree(godownRes.body()).get("id").asString());

        HttpResponse<String> sgRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/stock-groups",
            """
            {
              "name": "Industrial Automation",
              "shouldQuantitiesOfItemsBeAdded": true,
              "hsnSacCode": "85371000",
              "gstRatePct": "18"
            }
            """,
            "qa-sg-auto"
        );
        Assertions.assertThat(sgRes.statusCode()).isEqualTo(201);
        UUID stockGroupId = UUID.fromString(objectMapper.readTree(sgRes.body()).get("id").asString());

        HttpResponse<String> mcuRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/stock-items",
            """
            {
              "name": "MCU Core Module",
              "stockGroupId": "%s",
              "baseUomId": "%s",
              "costingMethod": "FIFO",
              "gstApplicability": "APPLICABLE",
              "gstSupplyType": "GOODS",
              "hsnSacCode": "85423100",
              "gstRatePct": "18"
            }
            """.formatted(stockGroupId, uomNosId),
            "qa-item-mcu"
        );
        Assertions.assertThat(mcuRes.statusCode()).isEqualTo(201);
        rawMcuItemId = UUID.fromString(objectMapper.readTree(mcuRes.body()).get("id").asString());

        HttpResponse<String> ctrlRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/stock-items",
            """
            {
              "name": "Smart PLC Controller",
              "stockGroupId": "%s",
              "baseUomId": "%s",
              "costingMethod": "AVG_COST",
              "gstApplicability": "APPLICABLE",
              "gstSupplyType": "GOODS",
              "hsnSacCode": "85371000",
              "gstRatePct": "18"
            }
            """.formatted(stockGroupId, uomNosId),
            "qa-item-plc"
        );
        Assertions.assertThat(ctrlRes.statusCode()).isEqualTo(201);
        smartControllerItemId = UUID.fromString(objectMapper.readTree(ctrlRes.body()).get("id").asString());

        // Create Multi-BOM: 1 Smart PLC Controller consumes 2 MCU Core Modules
        HttpResponse<String> bomRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/boms",
            """
            {
              "stockItemId": "%s",
              "bomName": "PLC Standard BOM v1",
              "unitOfManufacture": "1",
              "components": [
                {
                  "lineNo": 1,
                  "componentItemId": "%s",
                  "componentType": "COMPONENT",
                  "uomId": "%s",
                  "quantity": "2"
                }
              ]
            }
            """.formatted(smartControllerItemId, rawMcuItemId, uomNosId),
            "qa-bom-plc"
        );
        Assertions.assertThat(bomRes.statusCode()).isEqualTo(201);
        controllerBomId = UUID.fromString(objectMapper.readTree(bomRes.body()).get("id").asString());

        // Create Price List & verify slab lookup
        HttpResponse<String> plRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/price-lists",
            """
            {
              "priceLevelName": "Institutional Tier",
              "effectiveFrom": "2025-04-01",
              "currencyCode": "INR",
              "tiers": [
                {
                  "stockItemId": "%s",
                  "fromQty": "1",
                  "lessThanQty": "100",
                  "rate": "3000.00",
                  "discountPct": "0"
                }
              ]
            }
            """.formatted(smartControllerItemId),
            "qa-pl-inst"
        );
        Assertions.assertThat(plRes.statusCode()).isEqualTo(201);

        HttpResponse<String> lookupRes = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/price-lists/lookup?priceLevelName="
                + URLEncoder.encode("Institutional Tier", StandardCharsets.UTF_8)
                + "&stockItemId=" + smartControllerItemId + "&qty=5&asOfDate=2025-04-15"
        );
        Assertions.assertThat(lookupRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(new BigDecimal(objectMapper.readTree(lookupRes.body()).get("rate").asString()))
            .isEqualByComparingTo("3000.00");
    }

    @Test
    @Order(3)
    void completeVoucherModesOptionalCancelTable13AndMcaEditLogWorkflow() throws Exception {
        // 1. F6 Receipt: Seed Capital 5,00,000.00 into HDFC Operating Current A/c
        HttpResponse<String> rcptRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Receipt",
              "voucherDate": "2025-04-01",
              "persistedView": "SINGLE_ENTRY",
              "narration": "Seed capital received via NEFT",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "500000.00",
                  "bankAllocations": [
                    {
                      "bankLedgerId": "%s",
                      "transactionType": "NEFT",
                      "instrumentNo": "UTR90001",
                      "instrumentDate": "2025-04-01",
                      "side": "DR",
                      "amount": "500000.00"
                    }
                  ]
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "500000.00"
                }
              ]
            }
            """.formatted(hdfcBankLedgerId, hdfcBankLedgerId, capitalLedgerId),
            "qa-vch-rcpt-01"
        );
        Assertions.assertThat(rcptRes.statusCode()).isEqualTo(201);

        // 2. F4 Contra: Withdraw 25,000.00 Cash from HDFC Operating Current A/c
        HttpResponse<String> contraRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Contra",
              "voucherDate": "2025-04-02",
              "persistedView": "SINGLE_ENTRY",
              "narration": "Petty cash float withdrawal",
              "lines": [
                { "ledgerId": "%s", "side": "DR", "amount": "25000.00" },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "25000.00",
                  "bankAllocations": [
                    {
                      "bankLedgerId": "%s",
                      "transactionType": "CHEQUE",
                      "instrumentNo": "400101",
                      "instrumentDate": "2025-04-02",
                      "side": "CR",
                      "amount": "25000.00"
                    }
                  ]
                }
              ]
            }
            """.formatted(cashLedgerId, hdfcBankLedgerId, hdfcBankLedgerId),
            "qa-vch-contra-01"
        );
        Assertions.assertThat(contraRes.statusCode()).isEqualTo(201);

        // 3. Ctrl+F8 Sales Order & /orders/open check
        HttpResponse<String> soRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Sales Order",
              "voucherDate": "2025-04-03",
              "action": "POST",
              "partyLedgerId": "%s",
              "inventoryLines": [
                {
                  "stockItemId": "%s",
                  "uomId": "%s",
                  "actualQty": "8",
                  "billedQty": "8",
                  "rate": "3000.00",
                  "amount": "24000.00",
                  "orderRefNo": "SO-QA-2025-01"
                }
              ]
            }
            """.formatted(debtorLedgerId, smartControllerItemId, uomNosId),
            "qa-vch-so-01"
        );
        Assertions.assertThat(soRes.statusCode()).isEqualTo(201);

        HttpResponse<String> openOrdersRes = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/orders/open?orderType=SALES_ORDER"
        );
        Assertions.assertThat(openOrdersRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(openOrdersRes.body()).size()).isGreaterThanOrEqualTo(1);

        // 4. F9 Purchase (Item Invoice): 50 NOS MCU Core Module @ 1000 = 50,000 + CGST 4,500 + SGST 4,500 = 59,000
        HttpResponse<String> purchRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Purchase",
              "voucherDate": "2025-04-05",
              "persistedView": "ITEM_INVOICE",
              "supplierInvoiceNo": "KAV-INV-101",
              "supplierInvoiceDate": "2025-04-05",
              "partyLedgerId": "%s",
              "accountingLedgerId": "%s",
              "placeOfSupplyState": "33",
              "narration": "Purchase 50 NOS MCU Core Module",
              "inventoryLines": [
                {
                  "lineNo": 1,
                  "accountingLedgerId": "%s",
                  "stockItemId": "%s",
                  "movementDirection": "INWARD",
                  "uomId": "%s",
                  "actualQty": "50",
                  "billedQty": "50",
                  "rate": "1000.00",
                  "amount": "50000.00",
                  "batchAllocations": [
                    {
                      "godownId": "%s",
                      "actualQty": "50",
                      "billedQty": "50",
                      "rate": "1000.00",
                      "amount": "50000.00"
                    }
                  ]
                }
              ],
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "50000.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "ratePct": "9",
                  "amount": "4500.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "ratePct": "9",
                  "amount": "4500.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "59000.00",
                  "billAllocations": [
                    {
                      "allocationType": "NEW_REF",
                      "billName": "KAV-INV-101",
                      "amount": "59000.00"
                    }
                  ]
                }
              ]
            }
            """.formatted(
                creditorLedgerId,
                purchaseLedgerId,
                purchaseLedgerId,
                rawMcuItemId,
                uomNosId,
                plantGodownId,
                purchaseLedgerId,
                cgstLedgerId,
                sgstLedgerId,
                creditorLedgerId
            ),
            "qa-vch-pur-01"
        );
        Assertions.assertThat(purchRes.statusCode())
            .as("Purchase Item Invoice response: %s", purchRes.body())
            .isEqualTo(201);

        // 5. Alt+F7 Manufacturing Preview + Stock Journal: Manufacture 10 NOS Smart PLC Controller consuming 20 NOS MCU
        HttpResponse<String> mfgPreviewRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers/manufacturing-preview",
            """
            {
              "bomId": "%s",
              "finishedStockItemId": "%s",
              "finishedQty": "10",
              "godownId": "%s",
              "asOfDate": "2025-04-08"
            }
            """.formatted(controllerBomId, smartControllerItemId, plantGodownId),
            "qa-mfg-preview-01"
        );
        Assertions.assertThat(mfgPreviewRes.statusCode()).isEqualTo(200);
        JsonNode mfgPreview = objectMapper.readTree(mfgPreviewRes.body());
        Assertions.assertThat(new BigDecimal(mfgPreview.get("componentTotalCost").asString()))
            .isEqualByComparingTo("20000.00");
        Assertions.assertThat(new BigDecimal(mfgPreview.get("effectiveUnitRate").asString()))
            .isEqualByComparingTo("2000.00");

        HttpResponse<String> stockJrnlRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Stock Journal",
              "voucherDate": "2025-04-08",
              "action": "POST",
              "narration": "Manufacture 10 NOS Smart PLC Controller per BOM v1",
              "inventoryLines": [
                {
                  "stockItemId": "%s",
                  "movementDirection": "OUTWARD",
                  "uomId": "%s",
                  "actualQty": "20",
                  "billedQty": "20",
                  "rate": "1000.00",
                  "amount": "20000.00",
                  "batchAllocations": [
                    {
                      "godownId": "%s",
                      "actualQty": "20",
                      "billedQty": "20",
                      "rate": "1000.00",
                      "amount": "20000.00"
                    }
                  ]
                },
                {
                  "stockItemId": "%s",
                  "movementDirection": "INWARD",
                  "uomId": "%s",
                  "actualQty": "10",
                  "billedQty": "10",
                  "rate": "2000.00",
                  "amount": "20000.00",
                  "batchAllocations": [
                    {
                      "godownId": "%s",
                      "actualQty": "10",
                      "billedQty": "10",
                      "rate": "2000.00",
                      "amount": "20000.00"
                    }
                  ]
                }
              ]
            }
            """.formatted(
                rawMcuItemId,
                uomNosId,
                plantGodownId,
                smartControllerItemId,
                uomNosId,
                plantGodownId
            ),
            "qa-vch-stkj-01"
        );
        Assertions.assertThat(stockJrnlRes.statusCode()).isEqualTo(201);

        // 6. F8 Sales (Item Invoice): Sell 5 NOS Smart PLC Controller @ 3,000 = 15,000 + CGST 1,350 + SGST 1,350 = 17,700
        HttpResponse<String> salesRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Sales",
              "voucherDate": "2025-04-12",
              "persistedView": "ITEM_INVOICE",
              "partyLedgerId": "%s",
              "accountingLedgerId": "%s",
              "placeOfSupplyState": "33",
              "narration": "Sale of 5 NOS Smart PLC Controller",
              "inventoryLines": [
                {
                  "lineNo": 1,
                  "accountingLedgerId": "%s",
                  "stockItemId": "%s",
                  "movementDirection": "OUTWARD",
                  "uomId": "%s",
                  "actualQty": "5",
                  "billedQty": "5",
                  "rate": "3000.00",
                  "amount": "15000.00",
                  "batchAllocations": [
                    {
                      "godownId": "%s",
                      "actualQty": "5",
                      "billedQty": "5",
                      "rate": "3000.00",
                      "amount": "15000.00"
                    }
                  ]
                }
              ],
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "17700.00",
                  "billAllocations": [
                    {
                      "allocationType": "NEW_REF",
                      "billName": "SAL-QA-01",
                      "amount": "17700.00"
                    }
                  ]
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "15000.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "ratePct": "9",
                  "amount": "1350.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "ratePct": "9",
                  "amount": "1350.00"
                }
              ]
            }
            """.formatted(
                debtorLedgerId,
                salesLedgerId,
                salesLedgerId,
                smartControllerItemId,
                uomNosId,
                plantGodownId,
                debtorLedgerId,
                salesLedgerId,
                cgstLedgerId,
                sgstLedgerId
            ),
            "qa-vch-sal-01"
        );
        Assertions.assertThat(salesRes.statusCode()).isEqualTo(201);
        primarySalesVoucherId = UUID.fromString(objectMapper.readTree(salesRes.body()).get("id").asString());

        // Capture Trial Balance before Optional voucher
        String tbDrBeforeOptional = fetchTrialBalanceClosingDebit(false);

        // 7. Ctrl+L Optional Voucher: Must save cleanly with 0.00 impact on Trial Balance!
        HttpResponse<String> optRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Sales",
              "voucherDate": "2025-04-14",
              "persistedView": "ACCOUNTING_INVOICE",
              "postingNature": "OPTIONAL",
              "narration": "Proforma optional quote with zero GL impact",
              "lines": [
                { "ledgerId": "%s", "side": "DR", "amount": "88888.00" },
                { "ledgerId": "%s", "side": "CR", "amount": "88888.00" }
              ]
            }
            """.formatted(debtorLedgerId, salesLedgerId),
            "qa-vch-opt-01"
        );
        Assertions.assertThat(optRes.statusCode()).isEqualTo(201);
        Assertions.assertThat(fetchTrialBalanceClosingDebit(false)).isEqualTo(tbDrBeforeOptional);

        // 8. F5 Payment (Agst Ref): Settle 30,000.00 against KAV-INV-101
        HttpResponse<String> payRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Payment",
              "voucherDate": "2025-04-16",
              "persistedView": "SINGLE_ENTRY",
              "narration": "Part settlement against KAV-INV-101",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "30000.00",
                  "billAllocations": [
                    {
                      "allocationType": "AGST_REF",
                      "billName": "KAV-INV-101",
                      "amount": "30000.00"
                    }
                  ]
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "30000.00",
                  "bankAllocations": [
                    {
                      "bankLedgerId": "%s",
                      "transactionType": "NEFT",
                      "instrumentNo": "UTR90045",
                      "instrumentDate": "2025-04-16",
                      "side": "CR",
                      "amount": "30000.00"
                    }
                  ]
                }
              ]
            }
            """.formatted(creditorLedgerId, hdfcBankLedgerId, hdfcBankLedgerId),
            "qa-vch-pay-01"
        );
        Assertions.assertThat(payRes.statusCode()).isEqualTo(201);

        // 9. F7 Journal (194J TDS + Cost Centre) v1 -> v2 MCA Edit Log redline verification
        HttpResponse<String> jrnlRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Journal",
              "voucherDate": "2025-04-18",
              "persistedView": "AS_VOUCHER",
              "narration": "Audit retainer v1 40000 with 10%% TDS 194J",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "40000.00",
                  "costAllocations": [
                    {
                      "costCategoryId": "%s",
                      "costCentreId": "%s",
                      "side": "DR",
                      "amount": "40000.00"
                    }
                  ]
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "4000.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "36000.00",
                  "billAllocations": [
                    {
                      "allocationType": "NEW_REF",
                      "billName": "APEX-FEE-01",
                      "amount": "36000.00"
                    }
                  ]
                }
              ]
            }
            """.formatted(consultancyLedgerId, costCategoryId, costCentreId, tdsLedgerId, advisorLedgerId),
            "qa-vch-jrnl-01"
        );
        Assertions.assertThat(jrnlRes.statusCode()).isEqualTo(201);
        UUID journalId = UUID.fromString(objectMapper.readTree(jrnlRes.body()).get("id").asString());

        HttpResponse<String> alterJrnlRes = putMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers/" + journalId,
            "\"v1\"",
            """
            {
              "voucherTypeName": "Journal",
              "voucherDate": "2025-04-18",
              "persistedView": "AS_VOUCHER",
              "narration": "Audit retainer v2 revised to 45000 with 10%% TDS 194J",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "45000.00",
                  "costAllocations": [
                    {
                      "costCategoryId": "%s",
                      "costCentreId": "%s",
                      "side": "DR",
                      "amount": "45000.00"
                    }
                  ]
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "4500.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "40500.00",
                  "billAllocations": [
                    {
                      "allocationType": "NEW_REF",
                      "billName": "APEX-FEE-01",
                      "amount": "40500.00"
                    }
                  ]
                }
              ]
            }
            """.formatted(consultancyLedgerId, costCategoryId, costCentreId, tdsLedgerId, advisorLedgerId),
            "qa-vch-jrnl-01-alter"
        );
        Assertions.assertThat(alterJrnlRes.statusCode()).isEqualTo(200);

        HttpResponse<String> editLogRes = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/edit-log/VOUCHER/" + journalId + "/versions"
        );
        Assertions.assertThat(editLogRes.statusCode()).isEqualTo(200);
        JsonNode versions = objectMapper.readTree(editLogRes.body());
        Assertions.assertThat(versions.size()).isEqualTo(2);
        Assertions.assertThat(versions.get(0).get("operation").asString()).isEqualTo("CREATE");
        Assertions.assertThat(versions.get(1).get("operation").asString()).isEqualTo("ALTER");
        Assertions.assertThat(versions.get(1).get("changedFields").size()).isGreaterThanOrEqualTo(1);

        // 10. Ctrl+F7 Physical Stock, Alt+F5 Debit Note, Alt+F6 Credit Note & Alt+X Cancel Sales Voucher
        HttpResponse<String> physStockRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Physical Stock",
              "voucherDate": "2025-04-19",
              "action": "POST",
              "narration": "Monthly physical count verification",
              "inventoryLines": [
                {
                  "stockItemId": "%s",
                  "uomId": "%s",
                  "actualQty": "5",
                  "billedQty": "5",
                  "rate": "2000.00",
                  "amount": "10000.00",
                  "batchAllocations": [
                    {
                      "godownId": "%s",
                      "actualQty": "5",
                      "billedQty": "5",
                      "rate": "2000.00",
                      "amount": "10000.00"
                    }
                  ]
                }
              ]
            }
            """.formatted(smartControllerItemId, uomNosId, plantGodownId),
            "qa-vch-phys-01"
        );
        Assertions.assertThat(physStockRes.statusCode()).isEqualTo(201);

        HttpResponse<String> dnRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Debit Note",
              "voucherDate": "2025-04-19",
              "persistedView": "ACCOUNTING_INVOICE",
              "partyLedgerId": "%s",
              "placeOfSupplyState": "33",
              "narration": "Supplier short-shipment debit note",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "590.00",
                  "billAllocations": [
                    {
                      "allocationType": "AGST_REF",
                      "billName": "KAV-INV-101",
                      "amount": "590.00"
                    }
                  ]
                },
                { "ledgerId": "%s", "side": "CR", "amount": "500.00" },
                { "ledgerId": "%s", "side": "CR", "ratePct": "9", "amount": "45.00" },
                { "ledgerId": "%s", "side": "CR", "ratePct": "9", "amount": "45.00" }
              ]
            }
            """.formatted(creditorLedgerId, creditorLedgerId, purchaseLedgerId, cgstLedgerId, sgstLedgerId),
            "qa-vch-dn-01"
        );
        Assertions.assertThat(dnRes.statusCode()).isEqualTo(201);

        HttpResponse<String> cnRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Credit Note",
              "voucherDate": "2025-04-20",
              "persistedView": "ACCOUNTING_INVOICE",
              "partyLedgerId": "%s",
              "placeOfSupplyState": "33",
              "narration": "Post-sale price rebate credit note",
              "lines": [
                { "ledgerId": "%s", "side": "DR", "amount": "1000.00" },
                { "ledgerId": "%s", "side": "DR", "ratePct": "9", "amount": "90.00" },
                { "ledgerId": "%s", "side": "DR", "ratePct": "9", "amount": "90.00" },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "1180.00",
                  "billAllocations": [
                    {
                      "allocationType": "AGST_REF",
                      "billName": "SAL-QA-01",
                      "amount": "1180.00"
                    }
                  ]
                }
              ]
            }
            """.formatted(debtorLedgerId, salesLedgerId, cgstLedgerId, sgstLedgerId, debtorLedgerId),
            "qa-vch-cn-01"
        );
        Assertions.assertThat(cnRes.statusCode()).isEqualTo(201);

        HttpResponse<String> salesToCancelRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Sales",
              "voucherDate": "2025-04-22",
              "persistedView": "AS_VOUCHER",
              "partyLedgerId": "%s",
              "narration": "Invoice issued then cancelled for GSTR-1 Table 13 verification",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "5000.00",
                  "billAllocations": [
                    {
                      "allocationType": "NEW_REF",
                      "billName": "SAL-CANCEL-01",
                      "amount": "5000.00"
                    }
                  ]
                },
                { "ledgerId": "%s", "side": "CR", "amount": "5000.00" }
              ]
            }
            """.formatted(debtorLedgerId, debtorLedgerId, salesLedgerId),
            "qa-vch-sal-cancel-src"
        );
        Assertions.assertThat(salesToCancelRes.statusCode()).isEqualTo(201);
        JsonNode toCancelJson = objectMapper.readTree(salesToCancelRes.body());
        String cancelId = toCancelJson.get("id").asString();
        String retainedVchNo = toCancelJson.get("voucherNumber").asString();

        HttpResponse<String> cancelledRes = postMutateWithIfMatch(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers/" + cancelId + "/cancel",
            "\"v1\"",
            "{ \"reason\": \"Customer duplicate PO cancelled prior to dispatch\" }",
            "qa-vch-sal-cancel-act"
        );
        Assertions.assertThat(cancelledRes.statusCode()).isEqualTo(200);
        JsonNode cancelledBody = objectMapper.readTree(cancelledRes.body());
        Assertions.assertThat(cancelledBody.get("status").asString()).isEqualTo("CANCELLED");
        Assertions.assertThat(cancelledBody.get("voucherNumber").asString()).isEqualTo(retainedVchNo);
    }

    @Test
    @Order(4)
    void statutoryReturnsImsEinvoiceTds26qBankingOcrSmartFindReportsAndPdfBox3() throws Exception {
        // 1. SmartFind cross-entity search
        HttpResponse<String> smartFindRes = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/search/smartfind?q=Controller"
        );
        Assertions.assertThat(smartFindRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(smartFindRes.body()).contains("Smart PLC Controller");

        // 2. GSTR-1 (including Table 13 cancelled document count), GSTR-3B, GSTR-9 (.xlsx + .json)
        HttpResponse<String> gstr1Res = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/gst/gstr1?fromDate=2025-04-01&toDate=2025-04-30"
        );
        Assertions.assertThat(gstr1Res.statusCode()).isEqualTo(200);
        JsonNode gstr1 = objectMapper.readTree(gstr1Res.body());
        long totalCancelledDocs = 0L;
        for (JsonNode docRow : gstr1.get("documentSummary")) {
            totalCancelledDocs += docRow.get("cancelledCount").asLong();
        }
        Assertions.assertThat(totalCancelledDocs)
            .as("GSTR-1 Table 13 Document Summary must reflect cancelled Sales serial")
            .isGreaterThanOrEqualTo(1L);

        HttpResponse<byte[]> gstr9XlsxRes = postBinary(
            "/api/v1/companies/" + workflowCompanyId + "/gst/gstr9/export?format=XLSX",
            "{}",
            "qa-gstr9-xlsx"
        );
        Assertions.assertThat(gstr9XlsxRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(gstr9XlsxRes.body().length).isGreaterThan(500);

        // 3. IMS Reconciliation: Import Supplier B2B JSON -> ACCEPT_WITH_REDUCTION + remarks -> GSTR-3B reflects reduction
        String supplierGstin = "33AAFCK5678G1Z" + ExactMoneyValidator.computeGstinMod36CheckChar("33AAFCK5678G1Z");
        HttpResponse<String> imsImportRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/gst/ims/import-json",
            """
            {
              "returnPeriod": "042025",
              "invoices": [
                {
                  "supplierGstin": "%s",
                  "supplierName": "Kaveri Electronics Pvt Ltd",
                  "docType": "INVOICE",
                  "invoiceNumber": "KAV-INV-101",
                  "invoiceDate": "2025-04-05",
                  "placeOfSupplyState": "33",
                  "invoiceValue": "59000.00",
                  "taxableValue": "50000.00",
                  "cgstAmount": "4500.00",
                  "sgstAmount": "4500.00",
                  "igstAmount": "0.00"
                }
              ]
            }
            """.formatted(supplierGstin),
            "qa-ims-import-01"
        );
        Assertions.assertThat(imsImportRes.statusCode()).isEqualTo(200);
        UUID imsInvoiceId = UUID.fromString(objectMapper.readTree(imsImportRes.body()).get(0).get("id").asString());

        HttpResponse<String> imsActionRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/gst/ims/actions",
            """
            {
              "imsInvoiceId": "%s",
              "action": "ACCEPT",
              "itcReductionAmount": "500.00",
              "remarks": "Damaged unit ITC reduction documented per supplier debit note"
            }
            """.formatted(imsInvoiceId),
            "qa-ims-act-01"
        );
        Assertions.assertThat(imsActionRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(imsActionRes.body()).get("remarks").asString())
            .contains("Damaged unit ITC reduction");

        HttpResponse<String> gstr3bRes = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/gst/gstr3b?fromDate=2025-04-01&toDate=2025-04-30"
        );
        Assertions.assertThat(gstr3bRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(new BigDecimal(objectMapper.readTree(gstr3bRes.body()).get("imsItcReductionTotal").asString()))
            .isEqualByComparingTo("500.00");

        // 4. Offline e-Invoice JSON export & TDS Form 26Q + Protean FVU export
        HttpResponse<byte[]> einvExportRes = postBinary(
            "/api/v1/companies/" + workflowCompanyId + "/gst/einvoice/export-offline-json",
            "{ \"voucherIds\": [\"%s\"] }".formatted(primarySalesVoucherId),
            "qa-einv-offline"
        );
        Assertions.assertThat(einvExportRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(new String(einvExportRes.body(), StandardCharsets.UTF_8)).contains("85371000");

        HttpResponse<String> tds26qRes = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/tds/form26q?quarter=Q1"
        );
        Assertions.assertThat(tds26qRes.statusCode()).isEqualTo(200);
        JsonNode tds26q = objectMapper.readTree(tds26qRes.body());
        Assertions.assertThat(new BigDecimal(tds26q.get("totalGrossAmount").asString()))
            .isEqualByComparingTo("45000.00");
        Assertions.assertThat(new BigDecimal(tds26q.get("totalTdsDeducted").asString()))
            .isEqualByComparingTo("4500.00");

        HttpResponse<byte[]> fvuRes = postBinary(
            "/api/v1/companies/" + workflowCompanyId + "/tds/export-fvu?quarter=Q1",
            "{}",
            "qa-tds-fvu"
        );
        Assertions.assertThat(fvuRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(new String(fvuRes.body(), StandardCharsets.UTF_8)).contains("AABFA4321K");

        // 5. Banking: Cheque Book, CSV Statement Import + Auto-BRS, and OCR Staging Review Queue
        HttpResponse<String> chqBookRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/banking/cheque-books",
            """
            {
              "bankLedgerId": "%s",
              "bookName": "HDFC Primary Series",
              "fromNumber": 400101,
              "toNumber": 400125
            }
            """.formatted(hdfcBankLedgerId),
            "qa-chq-book-01"
        );
        Assertions.assertThat(chqBookRes.statusCode()).isEqualTo(201);
        UUID chqBookId = UUID.fromString(objectMapper.readTree(chqBookRes.body()).get("id").asString());

        HttpResponse<String> leavesRes = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/banking/cheque-books/" + chqBookId + "/leaves"
        );
        Assertions.assertThat(leavesRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(leavesRes.body()).size()).isEqualTo(25);

        HttpResponse<String> stmtRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/banking/statements/import",
            """
            {
              "bankLedgerId": "%s",
              "fileName": "hdfc_apr_2025.csv",
              "sourceFormat": "CSV",
              "statementFromDate": "2025-04-01",
              "statementToDate": "2025-04-30",
              "lines": [
                {
                  "txnDate": "2025-04-01",
                  "valueDate": "2025-04-01",
                  "instrumentNo": "UTR90001",
                  "description": "NEFT Seed Capital",
                  "withdrawalAmount": "0.00",
                  "depositAmount": "500000.00",
                  "runningBalance": "500000.00"
                },
                {
                  "txnDate": "2025-04-02",
                  "valueDate": "2025-04-02",
                  "instrumentNo": "400101",
                  "description": "Self Cash Withdrawal",
                  "withdrawalAmount": "25000.00",
                  "depositAmount": "0.00",
                  "runningBalance": "475000.00"
                },
                {
                  "txnDate": "2025-04-16",
                  "valueDate": "2025-04-16",
                  "instrumentNo": "UTR90045",
                  "description": "Vendor Payment Kaveri",
                  "withdrawalAmount": "30000.00",
                  "depositAmount": "0.00",
                  "runningBalance": "445000.00"
                }
              ]
            }
            """.formatted(hdfcBankLedgerId),
            "qa-brs-import-01"
        );
        Assertions.assertThat(stmtRes.statusCode()).isEqualTo(201);
        UUID stmtImportId = UUID.fromString(objectMapper.readTree(stmtRes.body()).get("id").asString());

        HttpResponse<String> autoMatchRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/banking/brs/auto-match",
            """
            {
              "bankLedgerId": "%s",
              "statementImportId": "%s",
              "dateWindowDays": 3
            }
            """.formatted(hdfcBankLedgerId, stmtImportId),
            "qa-brs-automatch-01"
        );
        Assertions.assertThat(autoMatchRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(autoMatchRes.body()).get("matchedCount").asInt())
            .isGreaterThanOrEqualTo(3);

        HttpResponse<String> ocrStageRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/banking/ocr-staging/documents",
            """
            {
              "fileName": "vendor_bill_scan_01.pdf",
              "contentType": "application/pdf",
              "documentType": "PURCHASE_INVOICE",
              "targetVoucherCategory": "JOURNAL",
              "supplierName": "Kaveri Electronics Pvt Ltd",
              "invoiceNumber": "OCR-KAV-202",
              "invoiceDate": "2025-04-25",
              "taxableAmount": "2500.00",
              "taxAmount": "0.00",
              "totalAmount": "2500.00",
              "mappedPartyLedgerId": "%s"
            }
            """.formatted(creditorLedgerId),
            "qa-ocr-stage-01"
        );
        Assertions.assertThat(ocrStageRes.statusCode()).isEqualTo(201);
        UUID ocrDocId = UUID.fromString(objectMapper.readTree(ocrStageRes.body()).get("id").asString());

        HttpResponse<String> ocrConvertRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/banking/ocr-staging/documents/" + ocrDocId + "/approve-convert",
            """
            {
              "voucherTypeName": "Purchase",
              "partyLedgerId": "%s",
              "expenseOrPurchaseLedgerId": "%s",
              "verifiedTotalAmount": "2500.00",
              "voucherDate": "2025-04-25",
              "narration": "Approved OCR staged vendor invoice"
            }
            """.formatted(creditorLedgerId, purchaseLedgerId),
            "qa-ocr-convert-01"
        );
        Assertions.assertThat(ocrConvertRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(ocrConvertRes.body()).get("convertedVoucherId").asString())
            .isNotBlank();

        // 6. All Financial & Supply-Chain Reports + PDFBox 3 Verification
        JsonNode tb = objectMapper.readTree(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/trial-balance?fromDate=2025-04-01&toDate=2025-04-30").body()
        );
        Assertions.assertThat(tb.get("isBalanced").asBoolean()).isTrue();
        Assertions.assertThat(tb.get("differenceAmount").asString()).isEqualTo("0.00");

        JsonNode bs = objectMapper.readTree(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/balance-sheet?asOfDate=2025-04-30&format=VERTICAL").body()
        );
        Assertions.assertThat(bs.get("isBalanced").asBoolean()).isTrue();
        Assertions.assertThat(bs.get("differenceAmount").asString()).isEqualTo("0.00");

        Assertions.assertThat(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/profit-and-loss?fromDate=2025-04-01&toDate=2025-04-30").statusCode()
        ).isEqualTo(200);
        Assertions.assertThat(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/schedule-iii?asOfDate=2025-04-30").statusCode()
        ).isEqualTo(200);
        Assertions.assertThat(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/cash-flow?fromDate=2025-04-01&toDate=2025-04-30").statusCode()
        ).isEqualTo(200);
        Assertions.assertThat(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/ratios?fromDate=2025-04-01&toDate=2025-04-30").statusCode()
        ).isEqualTo(200);
        Assertions.assertThat(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/ageing?type=RECEIVABLES&asOfDate=2025-04-30").statusCode()
        ).isEqualTo(200);
        Assertions.assertThat(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/stock-summary?fromDate=2025-04-01&toDate=2025-04-30").statusCode()
        ).isEqualTo(200);
        Assertions.assertThat(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/godown-summary?asOfDate=2025-04-30").statusCode()
        ).isEqualTo(200);
        Assertions.assertThat(
            getJson(
                "/api/v1/companies/" + workflowCompanyId + "/reports/movement-analysis?stockItemId="
                    + smartControllerItemId + "&fromDate=2025-04-01&toDate=2025-04-30"
            ).statusCode()
        ).isEqualTo(200);
        Assertions.assertThat(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/batch-ageing?asOfDate=2025-04-30").statusCode()
        ).isEqualTo(200);

        // Verify PDFBox 3 across all 7 report types + Sales Tax Invoice PDF
        for (String reportType : List.of(
            "trial-balance",
            "profit-and-loss",
            "balance-sheet",
            "schedule-iii",
            "day-book",
            "stock-summary",
            "ageing"
        )) {
            HttpResponse<byte[]> pdfRes = postBinary(
                "/api/v1/companies/" + workflowCompanyId + "/reports/" + reportType + "/pdf",
                "{ \"fromDate\": \"2025-04-01\", \"toDate\": \"2025-04-30\", \"asOfDate\": \"2025-04-30\" }",
                "qa-pdf-" + reportType
            );
            Assertions.assertThat(pdfRes.statusCode()).isEqualTo(200);
            assertPdfEmbeddedFontsAndTokens(pdfRes.body(), "MoneyCraft 1.0.0", "SHA256:", "\u20B9");
        }

        HttpResponse<byte[]> invoicePdfRes = postBinary(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers/" + primarySalesVoucherId + "/print-pdf",
            "{}",
            "qa-pdf-sales-inv"
        );
        Assertions.assertThat(invoicePdfRes.statusCode()).isEqualTo(200);
        assertPdfEmbeddedFontsAndTokens(invoicePdfRes.body(), "Smart PLC Controller", "SHA256:", "\u20B9");

        // 7. Verify unbroken MC-AUDIT-2 hash chain
        HttpResponse<String> auditRes = getJson("/api/v1/admin/audit/verify");
        Assertions.assertThat(auditRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(auditRes.body()).get("verified").asBoolean()).isTrue();
    }

    @Test
    @Order(5)
    void incrementXGuidedSpreadsheetExtensionFeelAndTdlParityMatrixWorkflow() throws Exception {
        // 1. Multi-format Columnar Spreadsheet Importer (.xlsx, .ods, CSV + 1900-02-29 rejection + CSV injection safety)
        byte[] xlsxBytes = QaPlaywrightAndFixtureSupport.buildColumnarXlsxWorkbook(
            "ColumnarSales",
            List.of(
                List.of("Date", "RefNo", "Party", "Total", "Sales", "CGST", "SGST", "Narration"),
                List.of(
                    "2025-04-26",
                    "XLSX-SAL-01",
                    "Salem Regional Hospital",
                    "11800.00",
                    "10000.00",
                    "900.00",
                    "900.00",
                    "Guided columnar XLSX sales voucher"
                )
            )
        );
        String columnarMappingJson = """
            {
              "targetType": "ACCOUNTING_VOUCHERS",
              "voucherAction": "POST",
              "defaultVoucherType": "Sales",
              "voucherDateColumn": "Date",
              "voucherNumberColumn": "RefNo",
              "referenceNumberColumn": "RefNo",
              "narrationColumn": "Narration",
              "partyLedgerNameColumn": "Party",
              "partyAmountColumn": "Total",
              "partySide": "DR",
              "ledgerColumns": [
                { "sourceColumn": "Sales", "ledgerName": "Sales - GST Goods", "ledgerId": "%s", "side": "CR", "lineRole": "ACCOUNTING" },
                { "sourceColumn": "CGST", "ledgerName": "CGST", "ledgerId": "%s", "side": "CR", "lineRole": "TAX" },
                { "sourceColumn": "SGST", "ledgerName": "SGST", "ledgerId": "%s", "side": "CR", "lineRole": "TAX" }
              ],
              "datePattern": "AUTO",
              "duplicateHandling": "REJECT"
            }
            """.formatted(salesLedgerId, cgstLedgerId, sgstLedgerId);

        HttpResponse<String> xlsxPrepRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/spreadsheet-imports/prepare",
            """
            {
              "fileName": "april-columnar-sales.xlsx",
              "formatHint": "XLSX",
              "base64Content": "%s",
              "sheetName": "ColumnarSales",
              "headerRowIndex": 1,
              "cachedFormulaConsent": false,
              "mapping": %s
            }
            """.formatted(Base64.getEncoder().encodeToString(xlsxBytes), columnarMappingJson),
            "qa-sheet-xlsx-prep"
        );
        Assertions.assertThat(xlsxPrepRes.statusCode())
            .as("Spreadsheet XLSX prepare body: %s", xlsxPrepRes.body())
            .isEqualTo(201);
        JsonNode xlsxBatch = objectMapper.readTree(xlsxPrepRes.body());
        Assertions.assertThat(xlsxBatch.get("status").asString()).isEqualTo("READY");
        Assertions.assertThat(xlsxBatch.get("validUnits").asInt()).isEqualTo(1);
        Assertions.assertThat(xlsxBatch.get("totalDebit").asString()).isEqualTo("11800.00");
        Assertions.assertThat(xlsxBatch.get("totalCredit").asString()).isEqualTo("11800.00");
        String xlsxBatchId = xlsxBatch.get("id").asString();

        HttpResponse<String> xlsxExecRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/spreadsheet-imports/batches/" + xlsxBatchId + "/execute",
            "{ \"action\": \"POST\", \"allowValidUnitsOnly\": false }",
            "qa-sheet-xlsx-exec"
        );
        Assertions.assertThat(xlsxExecRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(xlsxExecRes.body()).get("status").asString())
            .isEqualTo("COMPLETED");

        // OpenDocument .ods (v1.3) multi-column import
        byte[] odsBytes = QaPlaywrightAndFixtureSupport.buildColumnarOdsWorkbook(
            "OdsSales",
            List.of(
                List.of("Date", "RefNo", "Party", "Total", "Sales", "CGST", "SGST", "Narration"),
                List.of(
                    "2025-04-27",
                    "ODS-SAL-01",
                    "Salem Regional Hospital",
                    "5900.00",
                    "5000.00",
                    "450.00",
                    "450.00",
                    "Guided columnar ODS sales voucher"
                )
            )
        );
        HttpResponse<String> odsPrepRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/spreadsheet-imports/prepare",
            """
            {
              "fileName": "april-columnar-sales.ods",
              "formatHint": "ODS",
              "base64Content": "%s",
              "sheetName": "OdsSales",
              "headerRowIndex": 1,
              "cachedFormulaConsent": false,
              "mapping": %s
            }
            """.formatted(Base64.getEncoder().encodeToString(odsBytes), columnarMappingJson),
            "qa-sheet-ods-prep"
        );
        Assertions.assertThat(odsPrepRes.statusCode()).isEqualTo(201);
        String odsBatchId = objectMapper.readTree(odsPrepRes.body()).get("id").asString();
        HttpResponse<String> odsExecRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/spreadsheet-imports/batches/" + odsBatchId + "/execute",
            "{ \"action\": \"POST\" }",
            "qa-sheet-ods-exec"
        );
        Assertions.assertThat(odsExecRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(odsExecRes.body()).get("committedUnits").asInt()).isEqualTo(1);

        // Fictitious Excel 1900-02-29 serial 60 MUST fail closed with HTTP 422 MC-SHEET-INVALID-DATE
        byte[] invalidLeapBytes = QaPlaywrightAndFixtureSupport.buildInvalidLeapDay1900XlsxWorkbook();
        HttpResponse<String> leapRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/spreadsheet-imports/prepare",
            """
            {
              "fileName": "leap-1900-02-29.xlsx",
              "base64Content": "%s",
              "headerRowIndex": 1
            }
            """.formatted(Base64.getEncoder().encodeToString(invalidLeapBytes)),
            "qa-sheet-leap-1900"
        );
        Assertions.assertThat(leapRes.statusCode()).isEqualTo(422);
        Assertions.assertThat(objectMapper.readTree(leapRes.body()).get("code").asString())
            .isEqualTo("MC-SHEET-INVALID-DATE");

        // CSV with invalid ledger + formula-injection token -> errors.csv neutralizes leading '=' with '\''
        String maliciousCsv = "Date,RefNo,Party,Total,Sales,CGST,SGST,Narration\n"
            + "2025-04-28,CSV-BAD-01,=CMD|' /C calc'!A0,1180.00,1000.00,90.00,90.00,Adversarial cell test\n";
        HttpResponse<String> badCsvPrep = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/spreadsheet-imports/prepare",
            """
            {
              "fileName": "adversarial.csv",
              "formatHint": "CSV",
              "csvText": %s,
              "headerRowIndex": 1,
              "mapping": %s
            }
            """.formatted(objectMapper.writeValueAsString(maliciousCsv), columnarMappingJson),
            "qa-sheet-csv-bad"
        );
        Assertions.assertThat(badCsvPrep.statusCode()).isEqualTo(201);
        JsonNode badCsvBatch = objectMapper.readTree(badCsvPrep.body());
        Assertions.assertThat(badCsvBatch.get("invalidUnits").asInt()).isEqualTo(1);
        String badCsvBatchId = badCsvBatch.get("id").asString();
        HttpResponse<String> errCsvRes = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/spreadsheet-imports/batches/" + badCsvBatchId + "/errors.csv"
        );
        Assertions.assertThat(errCsvRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(errCsvRes.body()).contains("'=CMD|");

        // 2. Visual Extension Workbench: Custom Field (SELECTED_PRIVATE) + DMN/FEEL Rule blocking invalid voucher
        HttpResponse<String> extPkgRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/extensions/packages",
            """
            {
              "namespace": "com.raster.qacontrols",
              "displayName": "QA Governance Controls Extension",
              "targetProfiles": ["BROWSER"],
              "customFields": [
                {
                  "namespace": "com.raster.qacontrols",
                  "ownerEntityType": "LEDGER",
                  "fieldCode": "boardCreditNote",
                  "label": "Board Credit Sanction Note",
                  "dataType": "TEXT",
                  "protectionClass": "SELECTED_PRIVATE",
                  "uiAnchor": "ledger.header.compliance",
                  "requiredFlag": false
                }
              ],
              "feelRules": [
                {
                  "ruleCode": "QA_HIGH_VALUE_NARRATION_GATE",
                  "label": "Require narration (>= 6 chars) above 60000.00",
                  "targetEntityType": "VOUCHER",
                  "severity": "BLOCKING",
                  "conditionExpression": "totalDebit <= 60000.00 or (not isBlank(narration) and stringLength(narration) >= 6)",
                  "errorMessage": "Vouchers above 60,000.00 require narration of at least 6 characters.",
                  "orderNo": 10
                }
              ]
            }
            """,
            "qa-ext-pkg-create"
        );
        Assertions.assertThat(extPkgRes.statusCode()).isEqualTo(201);
        String extPkgId = objectMapper.readTree(extPkgRes.body()).get("id").asString();

        HttpResponse<String> extValRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/extensions/packages/" + extPkgId + "/validate",
            "{}",
            "qa-ext-pkg-validate"
        );
        Assertions.assertThat(extValRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(extValRes.body()).get("status").asString())
            .isEqualTo("VALIDATED");

        HttpResponse<String> extActRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/extensions/packages/" + extPkgId + "/activate",
            "{}",
            "qa-ext-pkg-activate"
        );
        Assertions.assertThat(extActRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(extActRes.body()).get("state").asString())
            .isEqualTo("ACTIVE");

        // Store & read back Tink-encrypted SELECTED_PRIVATE custom field
        HttpResponse<String> cfWriteRes = putMutate(
            "/api/v1/companies/" + workflowCompanyId + "/extensions/custom-fields/values/LEDGER/" + debtorLedgerId,
            "\"v1\"",
            """
            {
              "values": {
                "com.raster.qacontrols:boardCreditNote": "SANCTION-2025-PRIVATE-991"
              }
            }
            """,
            "qa-ext-cf-write"
        );
        Assertions.assertThat(cfWriteRes.statusCode()).isEqualTo(200);
        HttpResponse<String> cfReadRes = getJson(
            "/api/v1/companies/" + workflowCompanyId + "/extensions/custom-fields/values/LEDGER/" + debtorLedgerId
        );
        Assertions.assertThat(cfReadRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(cfReadRes.body()).contains("SANCTION-2025-PRIVATE-991");

        // Active FEEL rule MUST block a 70,000.00 voucher with blank narration (422 MC-EXT-VOUCHER-RULE-BLOCKED)
        HttpResponse<String> blockedVchRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Receipt",
              "voucherDate": "2025-04-28",
              "persistedView": "AS_VOUCHER",
              "narration": "",
              "lines": [
                { "ledgerId": "%s", "side": "DR", "amount": "70000.00" },
                { "ledgerId": "%s", "side": "CR", "amount": "70000.00" }
              ]
            }
            """.formatted(cashLedgerId, capitalLedgerId),
            "qa-ext-blocked-vch"
        );
        Assertions.assertThat(blockedVchRes.statusCode()).isEqualTo(422);
        Assertions.assertThat(objectMapper.readTree(blockedVchRes.body()).get("code").asString())
            .isEqualTo("MC-EXT-VOUCHER-RULE-BLOCKED");

        // Same 70,000.00 voucher WITH proper narration passes FEEL rule and posts cleanly!
        HttpResponse<String> allowedVchRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/vouchers",
            """
            {
              "voucherTypeName": "Receipt",
              "voucherDate": "2025-04-28",
              "persistedView": "AS_VOUCHER",
              "narration": "Approved tranche 2 capital infusion",
              "lines": [
                { "ledgerId": "%s", "side": "DR", "amount": "70000.00" },
                { "ledgerId": "%s", "side": "CR", "amount": "70000.00" }
              ]
            }
            """.formatted(cashLedgerId, capitalLedgerId),
            "qa-ext-allowed-vch"
        );
        Assertions.assertThat(allowedVchRes.statusCode()).isEqualTo(201);

        // 3. Static .tdl Importer & 5-Disposition Functional Parity Matrix + Service-Backed ODBC Projection
        String tdlSource = """
            [Include: missing_module.tdl]
            [#Menu: Gateway of Main]
                Add: Item: Dispatch Audit : Display : DispatchAuditRep
            [Report: DispatchAuditRep]
                Form: DispatchAuditForm
            [Part: CustomUnmappedPart]
                Line: CustomUnmappedLine
            [System: UDF]
                GatePassRef : String : 1001
            [System: Formula]
                SimpleCap : $Amount <= 100000
                ContextWalk : $$CollAmtTotal:InventoryEntries:$Amount > 0
            [Function: MutateLoop]
                01 : WALK COLLECTION : Vouchers
                02 : SAVE TARGET
            [Collection: LegacyOdbc]
                ODBC : "DSN=WarehouseDsn"
            [Field: HardwareSerialGate]
                Set as : $$SerialNumber
            """;
        HttpResponse<String> tdlRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/extensions/tdl-imports",
            """
            {
              "projectName": "Dispatch Gate Pass Customisation",
              "fileName": "dispatch_gate_pass.tdl",
              "sourceVersionClaim": "Reference Client 7.2",
              "tdlText": %s
            }
            """.formatted(objectMapper.writeValueAsString(tdlSource)),
            "qa-ext-tdl-import"
        );
        Assertions.assertThat(tdlRes.statusCode())
            .as("TDL import response: %s", tdlRes.body())
            .isEqualTo(201);
        JsonNode tdlJson = objectMapper.readTree(tdlRes.body());
        Set<String> dispositions = new HashSet<>();
        for (JsonNode row : tdlJson.get("parityMatrix")) {
            dispositions.add(row.get("disposition").asString());
            Assertions.assertThat(row.get("executionStatus").asString()).isEqualTo("NOT_RUN");
        }
        Assertions.assertThat(dispositions).contains(
            "TRANSLATED_VERIFICATION_PENDING",
            "REQUIRES_MAPPING",
            "UNSUPPORTED",
            "BLOCKED_DEPENDENCY",
            "NOT_APPLICABLE_WITH_EXPLANATION"
        );

        // Service-backed ODBC projection query (TRIAL_BALANCE succeeds; rawSql fails closed with 422)
        HttpResponse<String> odbcOkRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/extensions/odbc-query",
            "{ \"projection\": \"TRIAL_BALANCE\", \"fromDate\": \"2025-04-01\", \"toDate\": \"2025-04-30\" }",
            "qa-ext-odbc-ok"
        );
        Assertions.assertThat(odbcOkRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(odbcOkRes.body()).get("rowCount").asInt()).isGreaterThan(5);

        HttpResponse<String> odbcSqlBlockedRes = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/extensions/odbc-query",
            "{ \"projection\": \"TRIAL_BALANCE\", \"rawSql\": \"SELECT * FROM mc_entry\" }",
            "qa-ext-odbc-rawsql"
        );
        Assertions.assertThat(odbcSqlBlockedRes.statusCode()).isEqualTo(422);
        Assertions.assertThat(objectMapper.readTree(odbcSqlBlockedRes.body()).get("code").asString())
            .isEqualTo("MC-EXT-ODBC-RAW-SQL-FORBIDDEN");

        // Final Trial Balance check after all Spreadsheet & Extension transactions
        JsonNode finalTb = objectMapper.readTree(
            getJson("/api/v1/companies/" + workflowCompanyId + "/reports/trial-balance?fromDate=2025-04-01&toDate=2025-04-30").body()
        );
        Assertions.assertThat(finalTb.get("isBalanced").asBoolean()).isTrue();
        Assertions.assertThat(finalTb.get("differenceAmount").asString()).isEqualTo("0.00");
    }

    private String fetchTrialBalanceClosingDebit(boolean includeOptional) throws Exception {
        HttpResponse<String> res = getJson(
            "/api/v1/companies/" + workflowCompanyId
                + "/reports/trial-balance?fromDate=2025-04-01&toDate=2025-04-30&includeOptional=" + includeOptional
        );
        Assertions.assertThat(res.statusCode()).isEqualTo(200);
        return objectMapper.readTree(res.body()).get("totalClosingDebit").asString();
    }

    private UUID createCompany(String code, String legalName, String fyStart, String gstin) throws Exception {
        HttpResponse<String> res = postMutate(
            "/api/v1/companies",
            """
            {
              "code": "%s",
              "legalName": "%s",
              "stateCode": "33",
              "countryCode": "IN",
              "baseCurrencyCode": "INR",
              "baseCurrencySymbol": "₹",
              "formalCurrencyName": "Indian Rupee",
              "currencyScale": 2,
              "financialYearFrom": "%s",
              "booksBeginningFrom": "%s",
              "features": {
                "maintainAccounts": true,
                "billWiseEnabled": true,
                "costCentresEnabled": true,
                "maintainInventory": true,
                "integrateAccountsWithInventory": true,
                "gstEnabled": true,
                "gstRegistrationType": "REGULAR",
                "gstin": "%s",
                "tdsEnabled": true,
                "mcaEditLogEnforced": true
              }
            }
            """.formatted(code, legalName, fyStart, fyStart, gstin),
            "qa-cmp-" + code
        );
        Assertions.assertThat(res.statusCode()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(res.body()).get("id").asString());
    }

    private void relogin() throws Exception {
        HttpResponse<String> loginRes = postPreAuth(
            "/api/v1/auth/login",
            """
            {
              "username": "%s",
              "password": "%s"
            }
            """.formatted(OWNER_USER, OWNER_PASS)
        );
        Assertions.assertThat(loginRes.statusCode()).isEqualTo(200);
    }

    private UUID findGroupId(String groupName) throws Exception {
        HttpResponse<String> res = getJson("/api/v1/companies/" + workflowCompanyId + "/account-groups?limit=100");
        Assertions.assertThat(res.statusCode()).isEqualTo(200);
        for (JsonNode item : objectMapper.readTree(res.body()).get("items")) {
            if (groupName.equals(item.get("name").asString())) {
                return UUID.fromString(item.get("id").asString());
            }
        }
        throw new IllegalStateException("Group not found: " + groupName);
    }

    private UUID findLedgerId(String ledgerName) throws Exception {
        String encoded = URLEncoder.encode(ledgerName, StandardCharsets.UTF_8);
        HttpResponse<String> res = getJson("/api/v1/companies/" + workflowCompanyId + "/ledgers?q=" + encoded);
        Assertions.assertThat(res.statusCode()).isEqualTo(200);
        for (JsonNode item : objectMapper.readTree(res.body()).get("items")) {
            if (ledgerName.equals(item.get("name").asString())) {
                return UUID.fromString(item.get("id").asString());
            }
        }
        throw new IllegalStateException("Ledger not found: " + ledgerName);
    }

    private UUID createLedger(String name, UUID groupId, String extraPropsJsonObject) throws Exception {
        JsonNode extra = objectMapper.readTree(extraPropsJsonObject);
        String extraFields = extraPropsJsonObject.trim();
        String inner = extraFields.substring(1, extraFields.length() - 1).trim();
        String payload = """
            {
              "name": "%s",
              "groupId": "%s",
              "openingBalance": "0.00",
              "openingSide": "DR"%s
            }
            """.formatted(name, groupId, extra.isEmpty() ? "" : ",\n" + inner);
        HttpResponse<String> res = postMutate(
            "/api/v1/companies/" + workflowCompanyId + "/ledgers",
            payload,
            "qa-ldg-" + UUID.randomUUID()
        );
        Assertions.assertThat(res.statusCode())
            .as("Create ledger %s response: %s", name, res.body())
            .isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(res.body()).get("id").asString());
    }

    private HttpResponse<String> getJson(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + serverPort + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postPreAuth(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + serverPort + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postMutate(String path, String jsonBody, String idempotencyKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + serverPort + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", extractXsrf())
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postMutateWithIfMatch(
        String path,
        String ifMatch,
        String jsonBody,
        String idempotencyKey
    ) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + serverPort + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("If-Match", ifMatch)
            .header("X-XSRF-TOKEN", extractXsrf())
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> putMutate(
        String path,
        String ifMatch,
        String jsonBody,
        String idempotencyKey
    ) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + serverPort + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("If-Match", ifMatch)
            .header("X-XSRF-TOKEN", extractXsrf())
            .header("Idempotency-Key", idempotencyKey)
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> postBinary(String path, String jsonBody, String idempotencyKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + serverPort + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", extractXsrf())
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private String extractXsrf() {
        for (HttpCookie cookie : cookieManager.getCookieStore().getCookies()) {
            if ("XSRF-TOKEN".equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Missing XSRF-TOKEN cookie");
    }

    private static void assertPdfEmbeddedFontsAndTokens(
        byte[] pdfBytes,
        String token1,
        String token2,
        String token3
    ) throws Exception {
        Assertions.assertThat(pdfBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Assertions.assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            Set<String> fontNames = new HashSet<>();
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                for (COSName name : res.getFontNames()) {
                    PDFont font = res.getFont(name);
                    Assertions.assertThat(font.isEmbedded()).isTrue();
                    fontNames.add(font.getName());
                }
            }
            Assertions.assertThat(fontNames.stream().anyMatch(n -> n.contains("Manrope"))).isTrue();
            Assertions.assertThat(fontNames.stream().anyMatch(n -> n.contains("JetBrainsMono"))).isTrue();
            String text = new PDFTextStripper().getText(doc);
            Assertions.assertThat(text).contains(token1).contains(token2).contains(token3);
        }
    }

    private static Path resolveWorkspacePath(String... segments) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path workspaceRoot = cwd.getFileName().toString().equals("qa")
            ? cwd.getParent().getParent()
            : cwd.getParent();
        Path candidate = workspaceRoot;
        for (String seg : segments) {
            candidate = candidate.resolve(seg);
        }
        return candidate;
    }

    private static Path discoverSnapshot100000Dir() {
        Path binaryRoot = resolveWorkspacePath("argon_workspace", "Source", "Binary");
        if (!Files.isDirectory(binaryRoot)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(binaryRoot, 4)) {
            return walk
                .filter(Files::isDirectory)
                .filter(p -> "100000".equals(p.getFileName().toString()))
                .findFirst()
                .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }
}
