package com.raster.moneycraft.statutory;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.DualPostgres18TestcontainersSupport;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end Phase 5 (Gate G3 Statutory & Audit) verification against isolated Dual-PostgreSQL 18 clusters:
 * Multi-GSTIN & Signed Rule Pack (IN-FY2025-26-V1), 6-Tier GST Inheritance, Freight Apportionment,
 * Rel 7.2 Tobacco Deemed MRP (Golden Vector A), RCM, Uncertain Transactions, MoneyCraft Parity Benchmark
 * GSTR-1/3B & One-Click GSTR-9 (.xlsx/.json), Rel 7.2 IMS Reconciliation, 30-Day Tink-Encrypted
 * NIC e-Invoice/EWB Connector SPI, TDS 194J / 206AA No-PAN / Form 26Q / Protean FVU 1.2, and
 * Immutable MCA Rule 3(1) Edit Log (AT-TAX-001..012 & AT-AUDIT-MCA-01).
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StatutoryGstImsTdsAndEditLogTest {

    private static final DualPostgres18TestcontainersSupport.ClusterPair CLUSTERS =
        DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;

    // Company 1: Pure MoneyCraft Parity Benchmark (TALLY_PARITY_BENCHMARK.md §4.4)
    private static UUID parityCompanyId;
    private static UUID parityFyId;
    private static UUID parityTnRegId;
    private static UUID paritySal001Id;
    private static UUID paritySal002Id;
    private static UUID parityJournal1Id;

    // Company 2: Statutory Lab (Golden Vector A Tobacco MRP, Freight Apportion, RCM, Uncertain, IMS)
    private static UUID labCompanyId;
    private static String labCompanyGstin;
    private static UUID labDefaultRegId;

    @DynamicPropertySource
    static void registerClusterProperties(DynamicPropertyRegistry registry) {
        DualPostgres18TestcontainersSupport.bindClusterProperties(registry, CLUSTERS);
    }

    @AfterAll
    static void stopClusters() {
        CLUSTERS.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MoneyCraftProperties moneyCraftProperties;

    @Autowired
    @Qualifier("businessJdbcTemplate")
    private JdbcTemplate businessJdbcTemplate;

    @Test
    @Order(1)
    void multiGstinMod36RulePackAndGstRegistrationEditLog() throws Exception {
        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "installationLabel": "MoneyCraft Phase 5 Statutory & Audit Cluster",
                      "ownerUsername": "stat_admin",
                      "ownerDisplayName": "Chief Statutory & Tax Officer",
                      "password": "ChiefStatutory#2026!"
                    }
                    """
                )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSessionCookie = latestCookie(initRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(initRes, "XSRF-TOKEN");

        parityCompanyId = createTestCompany(
            "MCPARITY05",
            "MoneyCraft MedTech Parity Pvt Ltd",
            "33",
            "2025-04-01"
        );
        labCompanyId = createTestCompany(
            "MCSTATLAB05",
            "MoneyCraft Statutory Lab Pvt Ltd",
            "33",
            "2025-04-01"
        );

        // Fetch FY ID for parity company
        MvcResult fyRes = mockMvc.perform(authedGet("/api/v1/companies/" + parityCompanyId + "/fiscal-years"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode fyArr = objectMapper.readTree(fyRes.getResponse().getContentAsByteArray());
        parityFyId = UUID.fromString(fyArr.get(0).get("id").asString());

        // 1. Declarative Signed Rule Pack (IN-FY2025-26-V1)
        MvcResult rpRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/statutory/rule-packs?asOfDate=2025-05-15")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode rpArr = objectMapper.readTree(rpRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(rpArr.size()).isEqualTo(1);
        JsonNode rp = rpArr.get(0);
        Assertions.assertThat(rp.get("packCode").asString()).isEqualTo("IN-FY2025-26-V1");
        Assertions.assertThat(rp.get("packSha256").asString()).hasSize(64);
        Assertions.assertThat(rp.get("gstSlabs").size()).isEqualTo(10);
        Assertions.assertThat(rp.get("tdsSections").size()).isEqualTo(6);

        // 2. Reject invalid Mod-36 GSTIN on POST /gst/registrations
        String badGstin = "33AABCM1234F1Z0";
        String goodPrefix33 = "33AABCM1234F1Z";
        String validTnGstin = goodPrefix33 + ExactMoneyValidator.computeGstinMod36CheckChar(goodPrefix33);
        if (badGstin.equals(validTnGstin)) {
            badGstin = "33AABCM1234F1Z9";
        }

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/gst/registrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "registrationName": "Invalid Checksum Branch",
                          "stateCode": "33",
                          "stateName": "Tamil Nadu",
                          "registrationType": "REGULAR",
                          "gstin": "%s",
                          "effectiveFrom": "2025-04-01",
                          "isDefault": true
                        }
                        """.formatted(badGstin)
                    ),
                "gstreg-bad"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity());

        // 3. Create valid Tamil Nadu default GST registration & Karnataka branch registration
        MvcResult tnRegRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/gst/registrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "registrationName": "Tamil Nadu Head Office",
                          "stateCode": "33",
                          "stateName": "Tamil Nadu",
                          "registrationType": "REGULAR",
                          "gstin": "%s",
                          "effectiveFrom": "2025-04-01",
                          "einvoiceApplicable": true,
                          "einvoiceBillFromPlace": "Chennai",
                          "ewayBillApplicable": true,
                          "isDefault": true
                        }
                        """.formatted(validTnGstin)
                    ),
                "gstreg-tn"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        parityTnRegId = UUID.fromString(
            objectMapper.readTree(tnRegRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        String kaPrefix = "29AABCM1234F1Z";
        String validKaGstin = kaPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(kaPrefix);
        MvcResult kaRegRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/gst/registrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "registrationName": "Karnataka Depot",
                          "stateCode": "29",
                          "stateName": "Karnataka",
                          "registrationType": "REGULAR",
                          "gstin": "%s",
                          "effectiveFrom": "2025-04-01",
                          "isDefault": false
                        }
                        """.formatted(validKaGstin)
                    ),
                "gstreg-ka"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID kaRegId = UUID.fromString(
            objectMapper.readTree(kaRegRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Alter Karnataka registration (v1 -> v2) and verify MCA Edit Log on GST_REGISTRATION
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + parityCompanyId + "/gst/registrations/" + kaRegId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "registrationName": "Bengaluru Regional Hub",
                          "stateCode": "29",
                          "stateName": "Karnataka",
                          "registrationType": "REGULAR",
                          "gstin": "%s",
                          "effectiveFrom": "2025-04-01",
                          "ewayBillApplicable": true,
                          "isDefault": false
                        }
                        """.formatted(validKaGstin)
                    ),
                "gstreg-ka-alter"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v2\""));

        MvcResult regEditLogRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + parityCompanyId
                    + "/edit-log/GST_REGISTRATION/" + kaRegId + "/versions"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode regVersions = objectMapper.readTree(regEditLogRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(regVersions.size()).isEqualTo(2);
        Assertions.assertThat(regVersions.get(0).get("operation").asString()).isEqualTo("CREATE");
        Assertions.assertThat(regVersions.get(1).get("operation").asString()).isEqualTo("ALTER");

        // Also create default GST registration on labCompanyId
        String labPrefix = "33AABCL9999K1Z";
        labCompanyGstin = labPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(labPrefix);
        MvcResult labRegRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/registrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "registrationName": "Lab TN Registration",
                          "stateCode": "33",
                          "stateName": "Tamil Nadu",
                          "registrationType": "REGULAR",
                          "gstin": "%s",
                          "effectiveFrom": "2025-04-01",
                          "defaultGstRatePct": "5.00",
                          "defaultHsnSacCode": "9997",
                          "isDefault": true
                        }
                        """.formatted(labCompanyGstin)
                    ),
                "gstreg-lab-tn"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        labDefaultRegId = UUID.fromString(
            objectMapper.readTree(labRegRes.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    @Test
    @Order(2)
    void sixTierInheritanceFreightApportionmentTobaccoDeemedMrpRcmAndUncertainTransactions() throws Exception {
        UUID salesGrp = findGroupIdByName(labCompanyId, "Sales Accounts");
        UUID purchGrp = findGroupIdByName(labCompanyId, "Purchase Accounts");
        UUID debtorsGrp = findGroupIdByName(labCompanyId, "Sundry Debtors");
        UUID creditorsGrp = findGroupIdByName(labCompanyId, "Sundry Creditors");
        UUID dutiesGrp = findGroupIdByName(labCompanyId, "Duties & Taxes");
        UUID directExpGrp = findGroupIdByName(labCompanyId, "Direct Expenses");
        UUID indirectIncGrp = findGroupIdByName(labCompanyId, "Indirect Incomes");

        UUID uomPackId = createSimpleUom(labCompanyId, "PACK", "Cigarette Pack", "PAC", 0);
        UUID uomNosId = createSimpleUom(labCompanyId, "NOS", "Numbers", "NOS", 0);

        UUID salesLedgerId = createSimpleLedger(labCompanyId, "Sales Local", salesGrp);
        UUID purchLedgerId = createSimpleLedger(labCompanyId, "Purchases Local", purchGrp);

        String custPrefix = "33AABCT1111A1Z";
        String custGstin = custPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(custPrefix);
        UUID customerLedgerId = createPartyLedger(labCompanyId, "Chennai Retail Mart", debtorsGrp, "33", custGstin);
        UUID gtaVendorId = createUnregisteredVendorLedger(labCompanyId, "Southern Roadways GTA", creditorsGrp, "29");

        UUID cgstLedgerId = createGstDutyLedger(labCompanyId, "CGST", dutiesGrp, "CGST");
        UUID sgstLedgerId = createGstDutyLedger(labCompanyId, "SGST", dutiesGrp, "SGST_UTGST");
        UUID igstLedgerId = createGstDutyLedger(labCompanyId, "IGST", dutiesGrp, "IGST");
        UUID unmappedDutyId = createSimpleLedger(labCompanyId, "Legacy Unclassified Duty", dutiesGrp);

        // Expense/Income ledger with appropriate_for = 'GST' (BY_VALUE)
        MvcResult freightLdgRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Freight & Packing Charges",
                          "groupId": "%s",
                          "appropriateFor": "GST",
                          "appropriationBasis": "BY_VALUE",
                          "openingBalance": "0.00",
                          "openingSide": "CR"
                        }
                        """.formatted(indirectIncGrp)
                    ),
                "ldg-freight-gst"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID freightGstLedgerId = UUID.fromString(
            objectMapper.readTree(freightLdgRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // RCM Expense ledger (5% SAC 996511)
        MvcResult gtaExpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "GTA Freight Inward (RCM)",
                          "groupId": "%s",
                          "gstRatePct": "5.00",
                          "hsnSacCode": "996511",
                          "openingBalance": "0.00",
                          "openingSide": "DR"
                        }
                        """.formatted(directExpGrp)
                    ),
                "ldg-gta-rcm"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID gtaRcmExpLedgerId = UUID.fromString(
            objectMapper.readTree(gtaExpRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Tier 4 Stock Group (12% GST, HSN 3004)
        MvcResult sgRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/stock-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "OTC Healthcare Group",
                          "hsnSacCode": "300490",
                          "gstRatePct": "12.00"
                        }
                        """
                    ),
                "sg-otc"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID otcStockGroupId = UUID.fromString(
            objectMapper.readTree(sgRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Item A inherits 12% from Tier 4 Stock Group; Item B defines 18% at Tier 3 Stock Item
        UUID itemInherited12Id = createTaxStockItem(
            labCompanyId,
            "Herbal Inhaler",
            otcStockGroupId,
            uomNosId,
            null,
            null,
            false,
            null,
            salesLedgerId,
            purchLedgerId
        );
        UUID itemDirect18Id = createTaxStockItem(
            labCompanyId,
            "Digital Nebulizer",
            otcStockGroupId,
            uomNosId,
            "901920",
            "18.00",
            false,
            null,
            salesLedgerId,
            purchLedgerId
        );

        // Tobacco Deemed MRP Item (28% GST, HSN 24022090, no default mrpPerUnit so missing MRP triggers 422)
        UUID tobaccoItemId = createTaxStockItem(
            labCompanyId,
            "Classic Virginia 20s",
            null,
            uomPackId,
            "24022090",
            "28.00",
            true,
            null,
            salesLedgerId,
            purchLedgerId
        );

        // 1. Golden Vector A: Missing MRP on Tobacco item -> 422 MC-TAX-MRP-REQUIRED
        MvcResult missingMrpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/compute-preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-05-10",
                          "partyLedgerId": "%s",
                          "accountingLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "10",
                              "billedQty": "10",
                              "rate": "200.00",
                              "amount": "2000.00"
                            }
                          ]
                        }
                        """.formatted(customerLedgerId, salesLedgerId, tobaccoItemId, uomPackId)
                    ),
                "prev-tobacco-missing"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        JsonNode errProblem = objectMapper.readTree(missingMrpRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(errProblem.get("errorCode").asString()).isEqualTo("MC-TAX-MRP-REQUIRED");

        // 2. Golden Vector A: Tobacco 10 PACK @ MRP 280.00 (28% GST) -> Deemed Taxable 2187.50, CGST 306.25, SGST 306.25
        MvcResult validMrpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/compute-preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-05-10",
                          "partyLedgerId": "%s",
                          "accountingLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "10",
                              "billedQty": "10",
                              "rate": "200.00",
                              "amount": "2000.00",
                              "declaredMrp": "280.00"
                            }
                          ]
                        }
                        """.formatted(customerLedgerId, salesLedgerId, tobaccoItemId, uomPackId)
                    ),
                "prev-tobacco-ok"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode mrpPrev = objectMapper.readTree(validMrpRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(mrpPrev.get("totalAssessableValue").asString()).isEqualTo("2187.50");
        Assertions.assertThat(mrpPrev.get("totalCgst").asString()).isEqualTo("306.25");
        Assertions.assertThat(mrpPrev.get("totalSgst").asString()).isEqualTo("306.25");
        Assertions.assertThat(mrpPrev.get("totalTax").asString()).isEqualTo("612.50");
        Assertions.assertThat(
            mrpPrev.get("lineBreakups").get(0).get("deemedMrpTaxableValue").asString()
        ).isEqualTo("2187.50");

        // 3. 6-Tier Inheritance + appropriate_for = 'GST' Freight Apportionment (BY_VALUE across 12% & 18%)
        // Item 1 (1000.00 @ inherited 12%) + Item 2 (3000.00 @ item 18%) + Freight (400.00 BY_VALUE -> +100 / +300)
        // Assessables: 1100.00 @ 12% (CGST 66, SGST 66) + 3300.00 @ 18% (CGST 297, SGST 297) => Total Tax 726.00
        MvcResult appPrevRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/compute-preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-05-11",
                          "partyLedgerId": "%s",
                          "accountingLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "10",
                              "billedQty": "10",
                              "rate": "100.00",
                              "amount": "1000.00"
                            },
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "3",
                              "billedQty": "3",
                              "rate": "1000.00",
                              "amount": "3000.00"
                            }
                          ],
                          "lines": [
                            {
                              "lineRole": "ADDITIONAL_LEDGER",
                              "ledgerId": "%s",
                              "side": "CR",
                              "amount": "400.00"
                            }
                          ]
                        }
                        """.formatted(
                            customerLedgerId,
                            salesLedgerId,
                            itemInherited12Id,
                            uomNosId,
                            itemDirect18Id,
                            uomNosId,
                            freightGstLedgerId
                        )
                    ),
                "prev-apportion"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode appPrev = objectMapper.readTree(appPrevRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(appPrev.get("totalAssessableValue").asString()).isEqualTo("4400.00");
        Assertions.assertThat(appPrev.get("totalCgst").asString()).isEqualTo("363.00");
        Assertions.assertThat(appPrev.get("totalSgst").asString()).isEqualTo("363.00");
        Assertions.assertThat(appPrev.get("lineBreakups").get(0).get("rateSourceLevel").asString())
            .isEqualTo("STOCK_GROUP");
        Assertions.assertThat(appPrev.get("lineBreakups").get(1).get("rateSourceLevel").asString())
            .isEqualTo("STOCK_ITEM");

        // 4. Post RCM Freight Purchase (4000.00 @ 5% inter-state from KA GTA -> IGST 200.00 in GSTR-3B 3.1(d) & 4(A)(3))
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Purchase",
                          "voucherDate": "2025-05-12",
                          "persistedView": "AS_VOUCHER",
                          "partyLedgerId": "%s",
                          "placeOfSupplyState": "33",
                          "partyStateCode": "29",
                          "isReverseCharge": true,
                          "supplierInvoiceNo": "GTA-901",
                          "supplierInvoiceDate": "2025-05-12",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "4000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "4000.00" }
                          ]
                        }
                        """.formatted(gtaVendorId, gtaRcmExpLedgerId, gtaVendorId)
                    ),
                "vch-rcm-gta"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult lab3bRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + labCompanyId + "/gst/gstr3b?fromDate=2025-05-01&toDate=2025-05-31")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode lab3b = objectMapper.readTree(lab3bRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(lab3b.get("inwardRcm").get("taxableValue").asString()).isEqualTo("4000.00");
        Assertions.assertThat(lab3b.get("inwardRcm").get("igstAmount").asString()).isEqualTo("200.00");
        Assertions.assertThat(lab3b.get("eligibleItc").get("igstAmount").asString()).isEqualTo("200.00");
        Assertions.assertThat(lab3b.get("netTaxPayable").get("igstAmount").asString()).isEqualTo("200.00");

        // 5. Uncertain Transactions: Manual 1-paisa GST override & Unclassified Duties & Taxes ledger
        // Item B 1 qty @ 1000.00 (18% -> expected CGST 90.00, SGST 90.00); book CGST 90.01, SGST 89.99
        MvcResult ovrVchRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-05-14",
                          "persistedView": "ITEM_INVOICE",
                          "partyLedgerId": "%s",
                          "accountingLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "1",
                              "billedQty": "1",
                              "rate": "1000.00",
                              "amount": "1000.00"
                            }
                          ],
                          "lines": [
                            { "lineRole": "PARTY", "ledgerId": "%s", "side": "DR", "amount": "1180.00" },
                            { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "90.01" },
                            { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "89.99" }
                          ]
                        }
                        """.formatted(
                            customerLedgerId,
                            salesLedgerId,
                            itemDirect18Id,
                            uomNosId,
                            customerLedgerId,
                            cgstLedgerId,
                            sgstLedgerId
                        )
                    ),
                "vch-1paisa-override"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID overrideVoucherId = UUID.fromString(
            objectMapper.readTree(ovrVchRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Also post a voucher using unclassified Duties & Taxes ledger (taxType = NONE)
        MvcResult unmappedVchRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2025-05-15",
                          "persistedView": "AS_VOUCHER",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "500.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "500.00" }
                          ]
                        }
                        """.formatted(customerLedgerId, unmappedDutyId)
                    ),
                "vch-unmapped-duty"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID unmappedVoucherId = UUID.fromString(
            objectMapper.readTree(unmappedVchRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        MvcResult uncRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + labCompanyId + "/gst/uncertain-transactions")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode uncList = objectMapper.readTree(uncRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(uncList.size()).isEqualTo(2);

        // Accept conflict on both uncertain vouchers and verify Uncertain list becomes empty
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + labCompanyId
                        + "/gst/uncertain-transactions/" + overrideVoucherId + "/accept-conflict"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Customer invoice rounded CGST by 1 paisa per PO\"}"),
                "acc-conflict-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + labCompanyId
                        + "/gst/uncertain-transactions/" + unmappedVoucherId + "/accept-conflict"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Municipal octroi duty non-GST\"}"),
                "acc-conflict-2"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        MvcResult uncAfterRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + labCompanyId + "/gst/uncertain-transactions")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(uncAfterRes.getResponse().getContentAsByteArray()).size()
        ).isEqualTo(0);
    }

    @Test
    @Order(3)
    void moneyCraftParityBenchmarkGstr1Gstr3bAndOneClickGstr9XlsxJson() throws Exception {
        UUID salesGrp = findGroupIdByName(parityCompanyId, "Sales Accounts");
        UUID purchGrp = findGroupIdByName(parityCompanyId, "Purchase Accounts");
        UUID debtorsGrp = findGroupIdByName(parityCompanyId, "Sundry Debtors");
        UUID creditorsGrp = findGroupIdByName(parityCompanyId, "Sundry Creditors");
        UUID dutiesGrp = findGroupIdByName(parityCompanyId, "Duties & Taxes");
        UUID indirectExpGrp = findGroupIdByName(parityCompanyId, "Indirect Expenses");

        UUID uomNosId = createSimpleUom(parityCompanyId, "NOS", "Numbers", "NOS", 0);
        UUID salesLedgerId = createSimpleLedger(parityCompanyId, "MedTech Sales", salesGrp);
        UUID purchLedgerId = createSimpleLedger(parityCompanyId, "MedTech Purchases", purchGrp);

        String kovaiPrefix = "33AABCK5501D1Z";
        String kovaiGstin = kovaiPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(kovaiPrefix);
        UUID kovaiMedSupplierId = createPartyLedger(
            parityCompanyId, "Kovai Medical Supplies", creditorsGrp, "33", kovaiGstin);

        String apolloPrefix = "33AABCA7701E1Z";
        String apolloGstin = apolloPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(apolloPrefix);
        UUID apolloChennaiId = createPartyLedger(
            parityCompanyId, "Apollo Hospitals Chennai", debtorsGrp, "33", apolloGstin);

        String narayanaPrefix = "29AABCN8802F1Z";
        String narayanaGstin = narayanaPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(narayanaPrefix);
        UUID narayanaBengaluruId = createPartyLedger(
            parityCompanyId, "Narayana Health Bengaluru", debtorsGrp, "29", narayanaGstin);

        UUID cgstId = createGstDutyLedger(parityCompanyId, "CGST", dutiesGrp, "CGST");
        UUID sgstId = createGstDutyLedger(parityCompanyId, "SGST", dutiesGrp, "SGST_UTGST");
        UUID igstId = createGstDutyLedger(parityCompanyId, "IGST", dutiesGrp, "IGST");

        // Two Medical Device Items: Pulse Oximeter (HSN 90189099, 12%) & Digital Thermometer (HSN 90251190, 18%)
        UUID pulseOximeterId = createTaxStockItem(
            parityCompanyId,
            "Pulse Oximeter Pro",
            null,
            uomNosId,
            "90189099",
            "12.00",
            false,
            null,
            salesLedgerId,
            purchLedgerId
        );
        UUID thermometerId = createTaxStockItem(
            parityCompanyId,
            "Digital IR Thermometer",
            null,
            uomNosId,
            "90251190",
            "18.00",
            false,
            null,
            salesLedgerId,
            purchLedgerId
        );

        // 1. Purchase KM-001 (Taxable: 84000 @ 12% + 28000 @ 18% = 112000.00; CGST 7560.00, SGST 7560.00)
        postItemInvoice(
            parityCompanyId,
            "Purchase",
            "2025-04-05",
            "KM-001",
            kovaiMedSupplierId,
            purchLedgerId,
            "33",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "20", "billedQty": "20", "rate": "4200.00", "amount": "84000.00" },
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "100", "billedQty": "100", "rate": "280.00", "amount": "28000.00" }
            ]
            """.formatted(pulseOximeterId, uomNosId, thermometerId, uomNosId),
            """
            [
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "CR", "amount": "127120.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "7560.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "7560.00" }
            ]
            """.formatted(kovaiMedSupplierId, cgstId, sgstId)
        );

        // 2. Purchase KM-019 (Taxable: 16500 @ 12% + 15600 @ 18% = 32100.00; CGST 2394.00, SGST 2394.00)
        // Cumulative Purchases: 144100.00 taxable, CGST 9954.00 + SGST 9954.00 = 19908.00 ITC!
        postItemInvoice(
            parityCompanyId,
            "Purchase",
            "2025-04-12",
            "KM-019",
            kovaiMedSupplierId,
            purchLedgerId,
            "33",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "5", "billedQty": "5", "rate": "3300.00", "amount": "16500.00" },
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "60", "billedQty": "60", "rate": "260.00", "amount": "15600.00" }
            ]
            """.formatted(pulseOximeterId, uomNosId, thermometerId, uomNosId),
            """
            [
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "CR", "amount": "36888.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "2394.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "DR", "amount": "2394.00" }
            ]
            """.formatted(kovaiMedSupplierId, cgstId, sgstId)
        );

        // 3. Sales SAL-001 Intra-State TN (Taxable: 72000 @ 12% + 28800 @ 18% = 100800.00; CGST 6912.00, SGST 6912.00)
        paritySal001Id = postItemInvoice(
            parityCompanyId,
            "Sales",
            "2025-04-18",
            null,
            apolloChennaiId,
            salesLedgerId,
            "33",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "12", "billedQty": "12", "rate": "6000.00", "amount": "72000.00" },
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "80", "billedQty": "80", "rate": "360.00", "amount": "28800.00" }
            ]
            """.formatted(pulseOximeterId, uomNosId, thermometerId, uomNosId),
            """
            [
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "DR", "amount": "114624.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "6912.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "6912.00" }
            ]
            """.formatted(apolloChennaiId, cgstId, sgstId)
        );

        // 4. Sales SAL-002 Inter-State KA (Taxable: 37000 @ 12% + 10000 @ 18% = 47000.00; IGST 6240.00)
        paritySal002Id = postItemInvoice(
            parityCompanyId,
            "Sales",
            "2025-04-22",
            null,
            narayanaBengaluruId,
            salesLedgerId,
            "29",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "5", "billedQty": "5", "rate": "7400.00", "amount": "37000.00" },
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "25", "billedQty": "25", "rate": "400.00", "amount": "10000.00" }
            ]
            """.formatted(pulseOximeterId, uomNosId, thermometerId, uomNosId),
            """
            [
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "DR", "amount": "53240.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "6240.00" }
            ]
            """.formatted(narayanaBengaluruId, igstId)
        );

        // 5. Post a 3rd Sales invoice and Cancel (Alt+X) it to verify GSTR-1 Table 13 cancelledCount!
        UUID sal003CancelledId = postItemInvoice(
            parityCompanyId,
            "Sales",
            "2025-04-25",
            null,
            apolloChennaiId,
            salesLedgerId,
            "33",
            """
            [
              { "stockItemId": "%s", "uomId": "%s", "actualQty": "1", "billedQty": "1", "rate": "6000.00", "amount": "6000.00" }
            ]
            """.formatted(pulseOximeterId, uomNosId),
            """
            [
              { "lineRole": "PARTY", "ledgerId": "%s", "side": "DR", "amount": "6720.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "360.00" },
              { "lineRole": "TAX", "ledgerId": "%s", "side": "CR", "amount": "360.00" }
            ]
            """.formatted(apolloChennaiId, cgstId, sgstId)
        );

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + parityCompanyId + "/vouchers/" + sal003CancelledId + "/cancel"
                )
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Customer cancelled order prior to dispatch\"}"),
                "cancel-sal003"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        // 6. Journal #1: TDS 194J Professional Fees — create v1 (40,000 -> TDS 4,000), then alter v2 (45,000 -> TDS 4,500)
        UUID auditFeesLedgerId = createSimpleLedger(parityCompanyId, "Audit & Legal Fees", indirectExpGrp);
        UUID rameshConsultantId = createTdsPartyLedger(
            parityCompanyId, "Ramesh & Associates", creditorsGrp, "AAAPR1234F", "194J");
        UUID tdsPayable194JId = createTdsDutyLedger(parityCompanyId, "TDS Payable - 194J", dutiesGrp, "194J");

        MvcResult j1CreateRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2025-04-28",
                          "persistedView": "AS_VOUCHER",
                          "narration": "Statutory audit retainer v1",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "40000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "36000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "4000.00" }
                          ]
                        }
                        """.formatted(auditFeesLedgerId, rameshConsultantId, tdsPayable194JId)
                    ),
                "vch-j1-v1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        parityJournal1Id = UUID.fromString(
            objectMapper.readTree(j1CreateRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put(
                    "/api/v1/companies/" + parityCompanyId + "/vouchers/" + parityJournal1Id
                )
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2025-04-28",
                          "persistedView": "AS_VOUCHER",
                          "narration": "Statutory audit retainer revised to 45000",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "45000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "40500.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "4500.00" }
                          ]
                        }
                        """.formatted(auditFeesLedgerId, rameshConsultantId, tdsPayable194JId)
                    ),
                "vch-j1-v2"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v2\""));

        // 7. Assert GSTR-1 Oracle Totals, Phase III Bifurcated HSN B2B, and Table 13 Cancelled Count!
        MvcResult gstr1Res = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + parityCompanyId
                    + "/gst/gstr1?fromDate=2025-04-01&toDate=2025-04-30"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode gstr1 = objectMapper.readTree(gstr1Res.getResponse().getContentAsByteArray());
        Assertions.assertThat(gstr1.get("totalTaxableValue").asString()).isEqualTo("147800.00");
        Assertions.assertThat(gstr1.get("totalIgst").asString()).isEqualTo("6240.00");
        Assertions.assertThat(gstr1.get("totalCgst").asString()).isEqualTo("6912.00");
        Assertions.assertThat(gstr1.get("totalSgst").asString()).isEqualTo("6912.00");
        Assertions.assertThat(gstr1.get("totalTax").asString()).isEqualTo("20064.00");
        Assertions.assertThat(gstr1.get("b2b").size()).isEqualTo(4);
        Assertions.assertThat(gstr1.get("hsnB2b").size()).isEqualTo(2);

        JsonNode docSummary = findInArray(
            gstr1.get("documentSummary"), "documentNature", "Invoices for outward supply");
        Assertions.assertThat(docSummary.get("totalCount").asInt()).isEqualTo(3);
        Assertions.assertThat(docSummary.get("cancelledCount").asInt()).isEqualTo(1);
        Assertions.assertThat(docSummary.get("netIssuedCount").asInt()).isEqualTo(2);

        // Export GSTN portal GSTR-1 JSON
        MvcResult gstr1ExportRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/gst/gstr1/export")
                    .param("fromDate", "2025-04-01")
                    .param("toDate", "2025-04-30"),
                "exp-gstr1-json"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode portalGstr1 = objectMapper.readTree(gstr1ExportRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(portalGstr1.get("hsn").get("hsn_b2b").size()).isEqualTo(2);

        // 8. Assert GSTR-3B Oracle Totals (Eligible ITC 19908.00, Rule 88A Net Payable 156.00)
        MvcResult gstr3bRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + parityCompanyId
                    + "/gst/gstr3b?fromDate=2025-04-01&toDate=2025-04-30"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode gstr3b = objectMapper.readTree(gstr3bRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(gstr3b.get("outwardTaxable").get("taxableValue").asString()).isEqualTo("147800.00");
        Assertions.assertThat(gstr3b.get("outwardTaxable").get("totalTax").asString()).isEqualTo("20064.00");
        Assertions.assertThat(gstr3b.get("eligibleItc").get("cgstAmount").asString()).isEqualTo("9954.00");
        Assertions.assertThat(gstr3b.get("eligibleItc").get("sgstAmount").asString()).isEqualTo("9954.00");
        Assertions.assertThat(gstr3b.get("eligibleItc").get("totalTax").asString()).isEqualTo("19908.00");
        Assertions.assertThat(gstr3b.get("netTaxPayable").get("totalTax").asString()).isEqualTo("156.00");

        // 9. Assert Rel 7.2 One-Click GSTR-9 Report + .json + .xlsx (parsed back via Apache POI XSSFWorkbook!)
        MvcResult gstr9JsonRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/gst/gstr9/export")
                    .param("fiscalYearId", parityFyId.toString())
                    .param("format", "JSON"),
                "exp-gstr9-json"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode gstr9Json = objectMapper.readTree(gstr9JsonRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(gstr9Json.get("totalOutwardTaxable").asString()).isEqualTo("147800.00");
        Assertions.assertThat(gstr9Json.get("totalOutwardTax").asString()).isEqualTo("20064.00");
        Assertions.assertThat(gstr9Json.get("totalItcAvailed").asString()).isEqualTo("19908.00");
        Assertions.assertThat(gstr9Json.get("netTaxPayable").asString()).isEqualTo("156.00");

        MvcResult gstr9XlsxRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/gst/gstr9/export")
                    .param("fiscalYearId", parityFyId.toString())
                    .param("format", "XLSX"),
                "exp-gstr9-xlsx"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        byte[] xlsxBytes = gstr9XlsxRes.getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            Assertions.assertThat(wb.getNumberOfSheets()).isGreaterThanOrEqualTo(4);
            String allWorkbookText = extractWorkbookText(wb);
            Assertions.assertThat(allWorkbookText).contains("147800.00");
            Assertions.assertThat(allWorkbookText).contains("20064.00");
            Assertions.assertThat(allWorkbookText).contains("19908.00");
            Assertions.assertThat(allWorkbookText).contains("90189099");
        }
    }

    @Test
    @Order(4)
    void rel72ImsReconciliationMandatoryRemarksItcReductionAndPendingCarryover() throws Exception {
        String suppPrefix = "33AABCS4401M1Z";
        String suppGstin = suppPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(suppPrefix);

        // Import 3 supplier invoices into May 2025 (052025) IMS workbench on labCompanyId
        MvcResult impRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/ims/import-json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "returnPeriod": "052025",
                          "gstRegistrationId": "%s",
                          "invoices": [
                            {
                              "supplierGstin": "%s",
                              "supplierName": "Madras Pharma Distributors",
                              "invoiceNumber": "MPD/25-26/101",
                              "invoiceDate": "2025-05-05",
                              "placeOfSupplyState": "33",
                              "taxableValue": "50000.00",
                              "igstAmount": "0.00",
                              "cgstAmount": "4500.00",
                              "sgstAmount": "4500.00"
                            },
                            {
                              "supplierGstin": "%s",
                              "supplierName": "Madras Pharma Distributors",
                              "invoiceNumber": "MPD/25-26/102",
                              "invoiceDate": "2025-05-08",
                              "placeOfSupplyState": "33",
                              "taxableValue": "20000.00",
                              "igstAmount": "0.00",
                              "cgstAmount": "1800.00",
                              "sgstAmount": "1800.00"
                            },
                            {
                              "supplierGstin": "%s",
                              "supplierName": "Madras Pharma Distributors",
                              "invoiceNumber": "MPD/25-26/103",
                              "invoiceDate": "2025-05-19",
                              "placeOfSupplyState": "33",
                              "taxableValue": "10000.00",
                              "igstAmount": "0.00",
                              "cgstAmount": "900.00",
                              "sgstAmount": "900.00"
                            }
                          ]
                        }
                        """.formatted(labDefaultRegId, suppGstin, suppGstin, suppGstin)
                    ),
                "ims-import-052025"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode imported = objectMapper.readTree(impRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(imported.size()).isEqualTo(3);

        UUID inv101Id = UUID.fromString(findInArray(imported, "invoiceNumber", "MPD/25-26/101").get("id").asString());
        UUID inv102Id = UUID.fromString(findInArray(imported, "invoiceNumber", "MPD/25-26/102").get("id").asString());
        UUID inv103Id = UUID.fromString(findInArray(imported, "invoiceNumber", "MPD/25-26/103").get("id").asString());

        // 1. REJECT without remarks -> 422 MC-IMS-REMARKS-REQUIRED
        MvcResult rejNoRem = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/ims/actions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "imsInvoiceId": "%s",
                          "action": "REJECT"
                        }
                        """.formatted(inv103Id)
                    ),
                "ims-rej-norem"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(rejNoRem.getResponse().getContentAsByteArray()).get("errorCode").asString()
        ).isEqualTo("MC-IMS-REMARKS-REQUIRED");

        // 2. Partial itcReductionAmount > 0 without remarks -> 422 MC-IMS-REMARKS-REQUIRED
        MvcResult redNoRem = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/ims/actions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "imsInvoiceId": "%s",
                          "action": "ACCEPT",
                          "itcReductionAmount": "1000.00"
                        }
                        """.formatted(inv101Id)
                    ),
                "ims-red-norem"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(redNoRem.getResponse().getContentAsByteArray()).get("errorCode").asString()
        ).isEqualTo("MC-IMS-REMARKS-REQUIRED");

        // 3. Apply valid Rel 7.2 actions:
        //    - inv101 (portal ITC 9000): ACCEPT with itcReductionAmount = 1000.00 -> net 8000.00 in 052025
        //    - inv102 (portal ITC 3600): PENDING deferred to 062025 -> 0 in 052025, 3600.00 deferred
        //    - inv103 (portal ITC 1800): REJECT with mandatory remarks -> 0 in 052025
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/ims/actions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "imsInvoiceId": "%s",
                          "action": "ACCEPT",
                          "itcReductionAmount": "1000.00",
                          "remarks": "10%% personal/exempt apportionment under Sec 17(2)"
                        }
                        """.formatted(inv101Id)
                    ),
                "ims-acc-101"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/ims/actions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "imsInvoiceId": "%s",
                          "action": "PENDING",
                          "effectiveFilingPeriod": "062025",
                          "remarks": "Goods in transit, GRN expected in June 2025"
                        }
                        """.formatted(inv102Id)
                    ),
                "ims-pend-102"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/ims/actions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "imsInvoiceId": "%s",
                          "action": "REJECT",
                          "remarks": "Wrong recipient GSTIN billed by supplier"
                        }
                        """.formatted(inv103Id)
                    ),
                "ims-rej-103"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        // Verify May 2025 (052025) GSTR-3B reflects 8000.00 IMS ITC + 200.00 RCM ITC = 8200.00,
        // 1000.00 IMS reduction, and 3600.00 PENDING deferred!
        MvcResult may3bRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + labCompanyId
                    + "/gst/gstr3b?fromDate=2025-05-01&toDate=2025-05-31&returnPeriod=052025"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode may3b = objectMapper.readTree(may3bRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(may3b.get("imsAcceptedCount").asInt()).isEqualTo(1);
        Assertions.assertThat(may3b.get("imsItcReductionTotal").asString()).isEqualTo("1000.00");
        Assertions.assertThat(may3b.get("imsPendingItcDeferred").asString()).isEqualTo("3600.00");
        Assertions.assertThat(may3b.get("eligibleItc").get("totalTax").asString()).isEqualTo("8200.00");

        // Now ACCEPT inv102 in June 2025 (062025) and verify June GSTR-3B claims the carried-over 3600.00 ITC!
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + labCompanyId + "/gst/ims/actions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "imsInvoiceId": "%s",
                          "action": "ACCEPT",
                          "effectiveFilingPeriod": "062025",
                          "remarks": "Goods received in June 2025, claiming ITC"
                        }
                        """.formatted(inv102Id)
                    ),
                "ims-acc-102-jun"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        MvcResult jun3bRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + labCompanyId
                    + "/gst/gstr3b?fromDate=2025-06-01&toDate=2025-06-30&returnPeriod=062025"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode jun3b = objectMapper.readTree(jun3bRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(jun3b.get("imsAcceptedCount").asInt()).isEqualTo(1);
        Assertions.assertThat(jun3b.get("eligibleItc").get("totalTax").asString()).isEqualTo("3600.00");
    }

    @Test
    @Order(5)
    void thirtyDayTinkEncryptedConnectorSessionAndOfflineEinvoiceEwbExports() throws Exception {
        // 1. Air-gapped default: live generate fails with 503 MC-CONNECTOR-DISABLED while offline JSON export works!
        MvcResult disabledRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + parityCompanyId + "/gst/einvoice/" + paritySal001Id + "/generate"
                ),
                "einv-disabled-check"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(disabledRes.getResponse().getContentAsByteArray()).get("errorCode").asString()
        ).isEqualTo("MC-CONNECTOR-DISABLED");

        MvcResult offlineEinvRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + parityCompanyId + "/gst/einvoice/export-offline-json"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"voucherIds\":[\"" + paritySal001Id + "\",\"" + paritySal002Id + "\"]}"),
                "einv-offline-export"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode offlineArr = objectMapper.readTree(offlineEinvRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(offlineArr.size()).isEqualTo(2);
        Assertions.assertThat(offlineArr.get(0).get("Version").asString()).isEqualTo("1.1");

        MvcResult offlineEwbRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + parityCompanyId + "/gst/ewaybill/export-offline-json"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"voucherIds\":[\"" + paritySal002Id + "\"]}"),
                "ewb-offline-export"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode offlineEwb = objectMapper.readTree(offlineEwbRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(offlineEwb.get("billLists").size()).isEqualTo(1);

        // 2. Start loopback NIC IRP & e-Way Bill mock HTTP server with atomic call counters
        AtomicInteger einvAuthCalls = new AtomicInteger(0);
        AtomicInteger einvGenerateCalls = new AtomicInteger(0);
        AtomicInteger ewbAuthCalls = new AtomicInteger(0);
        AtomicInteger ewbGenerateCalls = new AtomicInteger(0);
        String secretAuthToken = "NIC_SUPER_SECRET_30DAY_TOKEN_998877";

        HttpServer mockNicServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockNicServer.createContext("/eivital/v1.04/auth", exchange -> {
            einvAuthCalls.incrementAndGet();
            writeJson(
                exchange,
                200,
                """
                {
                  "Status": 1,
                  "Data": {
                    "ClientId": "MC-IRP-CLIENT",
                    "UserName": "medtech_irp_api",
                    "AuthToken": "%s",
                    "Sek": "sek_mock_base64",
                    "TokenExpiry": "2025-05-30 23:59:00"
                  }
                }
                """.formatted(secretAuthToken)
            );
        });
        mockNicServer.createContext("/eicore/v1.03/Invoice", exchange -> {
            int seq = einvGenerateCalls.incrementAndGet();
            String irn = String.format("%064x", 0xABCDEF0000L + seq);
            writeJson(
                exchange,
                200,
                """
                {
                  "Status": 1,
                  "Data": {
                    "AckNo": "112500000%d",
                    "AckDt": "2025-04-22 10:30:00",
                    "Irn": "%s",
                    "SignedInvoice": "eyJhbGciOiJSUzI1NiJ9.mockSignedInvoice.%d",
                    "SignedQRCode": "eyJhbGciOiJSUzI1NiJ9.mockSignedQr.%d"
                  }
                }
                """.formatted(seq, irn, seq, seq)
            );
        });
        mockNicServer.createContext("/ewb/v1.03/auth", exchange -> {
            ewbAuthCalls.incrementAndGet();
            writeJson(
                exchange,
                200,
                """
                {
                  "status": "1",
                  "authtoken": "%s"
                }
                """.formatted(secretAuthToken)
            );
        });
        mockNicServer.createContext("/ewb/v1.03/ewayapi", exchange -> {
            int seq = ewbGenerateCalls.incrementAndGet();
            writeJson(
                exchange,
                200,
                """
                {
                  "status": "1",
                  "Data": {
                    "ewbNo": "33100888777%d"
                  }
                }
                """.formatted(seq)
            );
        });
        mockNicServer.start();

        int port = mockNicServer.getAddress().getPort();
        String baseLoopbackUrl = "http://127.0.0.1:" + port;
        MoneyCraftProperties.Connector connProps = moneyCraftProperties.getConnector();
        boolean prevEnabled = connProps.isEnabled();
        boolean prevLoopback = connProps.isAllowLoopbackHttp();
        List<String> prevAllowlist = connProps.getEgressHostnameAllowlist();
        String prevEinvUrl = connProps.getEinvoiceBaseUrl();
        String prevEwbUrl = connProps.getEwayBillBaseUrl();

        try {
            connProps.setEnabled(true);
            connProps.setAllowLoopbackHttp(true);
            connProps.setEgressHostnameAllowlist(List.of("127.0.0.1"));
            connProps.setEinvoiceBaseUrl(baseLoopbackUrl);
            connProps.setEwayBillBaseUrl(baseLoopbackUrl);

            // Generate e-Invoice #1 (SAL-001) -> triggers 1 auth call + 1 invoice call
            MvcResult gen1Res = mockMvc.perform(
                authedMutate(
                    MockMvcRequestBuilders.post(
                        "/api/v1/companies/" + parityCompanyId + "/gst/einvoice/" + paritySal001Id + "/generate"
                    ),
                    "einv-gen-sal001"
                )
            )
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            JsonNode gen1 = objectMapper.readTree(gen1Res.getResponse().getContentAsByteArray());
            Assertions.assertThat(gen1.get("status").asString()).isEqualTo("GENERATED");
            Assertions.assertThat(gen1.get("irn").asString()).hasSize(64);

            // Generate e-Invoice #2 (SAL-002) -> MUST reuse 30-day cached Tink-encrypted session (0 new auth calls!)
            MvcResult gen2Res = mockMvc.perform(
                authedMutate(
                    MockMvcRequestBuilders.post(
                        "/api/v1/companies/" + parityCompanyId + "/gst/einvoice/" + paritySal002Id + "/generate"
                    ),
                    "einv-gen-sal002"
                )
            )
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            JsonNode gen2 = objectMapper.readTree(gen2Res.getResponse().getContentAsByteArray());
            Assertions.assertThat(gen2.get("status").asString()).isEqualTo("GENERATED");
            Assertions.assertThat(gen2.get("irn").asString()).hasSize(64);

            Assertions.assertThat(einvAuthCalls.get()).isEqualTo(1);
            Assertions.assertThat(einvGenerateCalls.get()).isEqualTo(2);

            // Verify mc_connector_session ciphertext does NOT contain plaintext token and last_reused_at is populated!
            TenantContextHolder.runWithCompany(parityCompanyId, () -> {
                byte[] cipherBytes = businessJdbcTemplate.queryForObject(
                    """
                    SELECT session_token_gcm
                      FROM mc_connector_session
                     WHERE company_id = ? AND connector_type = 'EINVOICE_NIC'
                    """,
                    byte[].class,
                    parityCompanyId
                );
                Assertions.assertThat(cipherBytes).isNotNull();
                String rawAsLatin1 = new String(cipherBytes, StandardCharsets.ISO_8859_1);
                Assertions.assertThat(rawAsLatin1).doesNotContain(secretAuthToken);

                Integer reusedCount = businessJdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                      FROM mc_connector_session
                     WHERE company_id = ? AND connector_type = 'EINVOICE_NIC' AND last_reused_at IS NOT NULL
                    """,
                    Integer.class,
                    parityCompanyId
                );
                Assertions.assertThat(reusedCount).isEqualTo(1);
            });

            // Also generate e-Way Bill on SAL-002
            MvcResult ewbGenRes = mockMvc.perform(
                authedMutate(
                    MockMvcRequestBuilders.post(
                        "/api/v1/companies/" + parityCompanyId + "/gst/ewaybill/" + paritySal002Id + "/generate"
                    )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "transportMode": "ROAD",
                              "distanceKm": 350,
                              "vehicleNo": "TN01AB1234",
                              "transporterName": "SRS Logistics"
                            }
                            """
                        ),
                    "ewb-gen-sal002"
                )
            )
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            JsonNode ewbGen = objectMapper.readTree(ewbGenRes.getResponse().getContentAsByteArray());
            Assertions.assertThat(ewbGen.get("status").asString()).isEqualTo("GENERATED");
            Assertions.assertThat(ewbGen.get("ewbNo").asString()).startsWith("33100888777");
        } finally {
            connProps.setEnabled(prevEnabled);
            connProps.setAllowLoopbackHttp(prevLoopback);
            connProps.setEgressHostnameAllowlist(prevAllowlist);
            connProps.setEinvoiceBaseUrl(prevEinvUrl);
            connProps.setEwayBillBaseUrl(prevEwbUrl);
            mockNicServer.stop(0);
        }
    }

    @Test
    @Order(6)
    void tdsForm26qSec206aaNoPanChallanItns281ProteanFvu12AndImmutableMcaEditLog() throws Exception {
        // 1. Verify TDS natures seeded for parityCompanyId
        MvcResult natRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + parityCompanyId + "/tds/natures")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(natRes.getResponse().getContentAsByteArray()).size()
        ).isEqualTo(6);

        // 2. Also post a Section 206AA No-PAN contractor journal (194C, PAN missing -> 20% higher rate!)
        UUID creditorsGrp = findGroupIdByName(parityCompanyId, "Sundry Creditors");
        UUID dutiesGrp = findGroupIdByName(parityCompanyId, "Duties & Taxes");
        UUID directExpGrp = findGroupIdByName(parityCompanyId, "Direct Expenses");

        UUID subContractExpId = createSimpleLedger(parityCompanyId, "Site Sub-Contract Charges", directExpGrp);
        UUID noPanContractorId = createTdsPartyLedger(
            parityCompanyId, "Unregistered Civil Contractor", creditorsGrp, null, "194C");
        UUID tdsPayable194CId = createTdsDutyLedger(parityCompanyId, "TDS Payable - 194C", dutiesGrp, "194C");

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Journal",
                          "voucherDate": "2025-05-20",
                          "persistedView": "AS_VOUCHER",
                          "narration": "Sub-contract bill without PAN attracting 20%% u/s 206AA",
                          "lines": [
                            { "ledgerId": "%s", "side": "DR", "amount": "50000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "40000.00" },
                            { "ledgerId": "%s", "side": "CR", "amount": "10000.00" }
                          ]
                        }
                        """.formatted(subContractExpId, noPanContractorId, tdsPayable194CId)
                    ),
                "vch-nopan-194c"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        // 3. Record ITNS 281 Challan for 194J (4,500.00) and verify Form 26Q + Protean FVU 1.2 export!
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/tds/challans")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "quarter": "Q1",
                          "fiscalYearId": "%s",
                          "sectionCode": "194J",
                          "bsrCode": "0510308",
                          "challanSerialNo": "45109",
                          "depositDate": "2025-05-07",
                          "tdsAmount": "4500.00"
                        }
                        """.formatted(parityFyId)
                    ),
                "tds-challan-194j"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult f26qRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + parityCompanyId
                    + "/tds/form26q?quarter=Q1&fiscalYearId=" + parityFyId
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode f26q = objectMapper.readTree(f26qRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(f26q.get("deductions").size()).isEqualTo(2);
        Assertions.assertThat(f26q.get("totalGrossAmount").asString()).isEqualTo("95000.00");
        Assertions.assertThat(f26q.get("totalTdsDeducted").asString()).isEqualTo("14500.00");
        Assertions.assertThat(f26q.get("totalTdsDeposited").asString()).isEqualTo("4500.00");
        Assertions.assertThat(f26q.get("unpaidTdsAmount").asString()).isEqualTo("10000.00");

        JsonNode rameshRow = findInArray(f26q.get("deductions"), "deducteeName", "Ramesh & Associates");
        Assertions.assertThat(rameshRow.get("deducteePan").asString()).isEqualTo("AAAPR1234F");
        Assertions.assertThat(rameshRow.get("grossAmount").asString()).isEqualTo("45000.00");
        Assertions.assertThat(rameshRow.get("tdsAmount").asString()).isEqualTo("4500.00");
        Assertions.assertThat(rameshRow.get("challanSerialNo").asString()).isEqualTo("45109");

        JsonNode noPanRow = findInArray(f26q.get("deductions"), "deducteeName", "Unregistered Civil Contractor");
        Assertions.assertThat(noPanRow.get("deducteePan").asString()).isEqualTo("PANNOTAVBL");
        Assertions.assertThat(noPanRow.get("higherRate206aaApplied").asBoolean()).isTrue();
        Assertions.assertThat(noPanRow.get("tdsRatePct").asString()).isEqualTo("20.00");
        Assertions.assertThat(noPanRow.get("tdsAmount").asString()).isEqualTo("10000.00");

        // Export Protean (NSDL) FVU 1.2 caret-delimited file
        MvcResult fvuRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + parityCompanyId + "/tds/export-fvu")
                    .param("quarter", "Q1")
                    .param("fiscalYearId", parityFyId.toString()),
                "tds-fvu-export"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        String fvuText = fvuRes.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertThat(fvuText).startsWith("FH^NS1^R^");
        Assertions.assertThat(fvuText).contains("\nBH^1^");
        Assertions.assertThat(fvuText).contains("^26Q^");
        Assertions.assertThat(fvuText).contains("\nCD^1^1^");
        Assertions.assertThat(fvuText).contains("^0510308^");
        Assertions.assertThat(fvuText).contains("AAAPR1234F^Ramesh & Associates");
        Assertions.assertThat(fvuText).contains("PANNOTAVBL^Unregistered Civil Contractor");

        // 4. AT-AUDIT-MCA-01: Immutable MCA Rule 3(1) Edit Log Timeline & Tamper Guards!
        MvcResult editLogRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + parityCompanyId
                    + "/edit-log/VOUCHER/" + parityJournal1Id + "/versions"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode versions = objectMapper.readTree(editLogRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(versions.size()).isEqualTo(2);
        Assertions.assertThat(versions.get(0).get("versionNo").asLong()).isEqualTo(1L);
        Assertions.assertThat(versions.get(0).get("operation").asString()).isEqualTo("CREATE");
        Assertions.assertThat(versions.get(0).get("snapshotSha256Hex").asString()).hasSize(64);
        Assertions.assertThat(versions.get(1).get("versionNo").asLong()).isEqualTo(2L);
        Assertions.assertThat(versions.get(1).get("operation").asString()).isEqualTo("ALTER");
        Assertions.assertThat(versions.get(1).get("changedFields").size()).isGreaterThan(0);
        Assertions.assertThat(versions.get(1).get("changedFields").toString()).contains("40000.00");
        Assertions.assertThat(versions.get(1).get("changedFields").toString()).contains("45000.00");

        // Raw SQL UPDATE & DELETE on mc_edit_log_version MUST be blocked at PostgreSQL trigger level!
        UUID v1EditLogId = UUID.fromString(versions.get(0).get("id").asString());
        TenantContextHolder.runWithCompany(parityCompanyId, () -> {
            Assertions.assertThatThrownBy(() ->
                businessJdbcTemplate.update(
                    "UPDATE mc_edit_log_version SET actor_username = 'tampered' WHERE company_id = ? AND id = ?",
                    parityCompanyId,
                    v1EditLogId
                )
            ).isInstanceOf(DataAccessException.class);

            Assertions.assertThatThrownBy(() ->
                businessJdbcTemplate.update(
                    "DELETE FROM mc_edit_log_version WHERE company_id = ? AND id = ?",
                    parityCompanyId,
                    v1EditLogId
                )
            ).isInstanceOf(DataAccessException.class);
        });

        // Attempting to disable mcaEditLogEnforced on an active MCA Edit Log company MUST fail with 403!
        MvcResult getCmpRes = mockMvc.perform(authedGet("/api/v1/companies/" + parityCompanyId))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        long cmpVer = objectMapper.readTree(getCmpRes.getResponse().getContentAsByteArray())
            .get("versionNo").asLong();

        MvcResult disableMcaRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + parityCompanyId)
                    .header("If-Match", "\"v" + cmpVer + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "code": "MCPARITY05",
                          "legalName": "MoneyCraft MedTech Parity Pvt Ltd",
                          "stateCode": "33",
                          "countryCode": "IN",
                          "baseCurrencyCode": "INR",
                          "baseCurrencySymbol": "₹",
                          "formalCurrencyName": "Indian Rupee",
                          "currencyScale": 2,
                          "financialYearFrom": "2025-04-01",
                          "booksBeginningFrom": "2025-04-01",
                          "mcaEditLogEnforced": false,
                          "features": {
                            "maintainAccounts": true,
                            "maintainInventory": true,
                            "gstEnabled": true,
                            "mcaEditLogEnforced": false
                          }
                        }
                        """
                    ),
                "cmp-disable-mca"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(disableMcaRes.getResponse().getContentAsByteArray())
                .get("errorCode").asString()
        ).contains("EDITLOG");
    }

    // -------------------------------------------------------------------------
    // Test Fixture Helpers
    // -------------------------------------------------------------------------

    private UUID createTestCompany(
        String code,
        String legalName,
        String stateCode,
        String fyStart
    ) throws Exception {
        String gstinPrefix = stateCode + "AABCC1596D1Z";
        String gstin = gstinPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(gstinPrefix);
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "code": "%s",
                          "legalName": "%s",
                          "stateCode": "%s",
                          "countryCode": "IN",
                          "baseCurrencyCode": "INR",
                          "baseCurrencySymbol": "₹",
                          "formalCurrencyName": "Indian Rupee",
                          "currencyScale": 2,
                          "financialYearFrom": "%s",
                          "booksBeginningFrom": "%s",
                          "mcaEditLogEnforced": true,
                          "features": {
                            "maintainAccounts": true,
                            "maintainInventory": true,
                            "billWiseEnabled": true,
                            "costCentresEnabled": true,
                            "gstEnabled": true,
                            "tdsEnabled": true,
                            "gstRegistrationType": "REGULAR",
                            "gstin": "%s",
                            "mcaEditLogEnforced": true
                          }
                        }
                        """.formatted(code, legalName, stateCode, fyStart, fyStart, gstin)
                    ),
                "cmp-" + code
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        MvcResult loginRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"stat_admin\",\"password\":\"ChiefStatutory#2026!\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Cookie refreshedSession = latestCookie(loginRes, "MCSESSION");
        Cookie refreshedXsrf = latestCookie(loginRes, "XSRF-TOKEN");
        if (refreshedSession != null) {
            ownerSessionCookie = refreshedSession;
        }
        if (refreshedXsrf != null) {
            ownerXsrfCookie = refreshedXsrf;
        }
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID findGroupIdByName(UUID targetCompanyId, String groupName) throws Exception {
        String cursor = null;
        do {
            String url = "/api/v1/companies/" + targetCompanyId + "/account-groups?limit=50"
                + (cursor == null ? "" : "&cursor=" + cursor);
            MvcResult res = mockMvc.perform(authedGet(url))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
            JsonNode page = objectMapper.readTree(res.getResponse().getContentAsByteArray());
            for (JsonNode g : page.get("items")) {
                if (groupName.equals(g.get("name").asString())) {
                    return UUID.fromString(g.get("id").asString());
                }
            }
            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asString() : null;
        } while (cursor != null);
        throw new IllegalStateException("Group not found: " + groupName);
    }

    private UUID createSimpleUom(
        UUID targetCompanyId,
        String symbol,
        String formalName,
        String uqc,
        int decimals
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/uoms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "uomType": "SIMPLE",
                          "symbol": "%s",
                          "formalName": "%s",
                          "gstUqcCode": "%s",
                          "decimalPlaces": %d
                        }
                        """.formatted(symbol, formalName, uqc, decimals)
                    ),
                "uom-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createSimpleLedger(UUID targetCompanyId, String name, UUID groupId) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "openingBalance": "0.00",
                          "openingSide": "DR"
                        }
                        """.formatted(name, groupId)
                    ),
                "ldg-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createPartyLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String stateCode,
        String gstin
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "partyRegistrationType": "REGULAR",
                          "countryCode": "IN",
                          "stateCode": "%s",
                          "gstin": "%s",
                          "maintainBalancesBillByBill": false,
                          "openingBalance": "0.00",
                          "openingSide": "DR"
                        }
                        """.formatted(name, groupId, stateCode, gstin)
                    ),
                "ldg-pty-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createUnregisteredVendorLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String stateCode
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "partyRegistrationType": "UNREGISTERED",
                          "countryCode": "IN",
                          "stateCode": "%s",
                          "maintainBalancesBillByBill": false,
                          "openingBalance": "0.00",
                          "openingSide": "CR"
                        }
                        """.formatted(name, groupId, stateCode)
                    ),
                "ldg-unreg-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createGstDutyLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String dutyHead
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "taxType": "GST",
                          "dutyHead": "%s",
                          "openingBalance": "0.00",
                          "openingSide": "CR"
                        }
                        """.formatted(name, groupId, dutyHead)
                    ),
                "ldg-gst-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createTdsPartyLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String panOrNull,
        String tdsNatureCode
    ) throws Exception {
        String panJson = panOrNull == null ? "null" : "\"" + panOrNull + "\"";
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "partyRegistrationType": "UNREGISTERED",
                          "countryCode": "IN",
                          "stateCode": "33",
                          "pan": %s,
                          "tdsApplicable": true,
                          "tdsNatureCode": "%s",
                          "tdsDeducteeType": "COMPANY",
                          "maintainBalancesBillByBill": false,
                          "openingBalance": "0.00",
                          "openingSide": "CR"
                        }
                        """.formatted(name, groupId, panJson, tdsNatureCode)
                    ),
                "ldg-tdspty-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createTdsDutyLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        String tdsNatureCode
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "groupId": "%s",
                          "taxType": "TDS",
                          "tdsApplicable": true,
                          "tdsNatureCode": "%s",
                          "openingBalance": "0.00",
                          "openingSide": "CR"
                        }
                        """.formatted(name, groupId, tdsNatureCode)
                    ),
                "ldg-tdsduty-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID createTaxStockItem(
        UUID targetCompanyId,
        String name,
        UUID stockGroupId,
        UUID baseUomId,
        String hsnSacCode,
        String gstRatePct,
        boolean isTobaccoMrpItem,
        String mrpPerUnit,
        UUID salesLedgerId,
        UUID purchLedgerId
    ) throws Exception {
        String grpJson = stockGroupId == null ? "null" : "\"" + stockGroupId + "\"";
        String hsnJson = hsnSacCode == null ? "null" : "\"" + hsnSacCode + "\"";
        String rateJson = gstRatePct == null ? "null" : "\"" + gstRatePct + "\"";
        String mrpJson = mrpPerUnit == null ? "null" : "\"" + mrpPerUnit + "\"";
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/stock-items")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "stockGroupId": %s,
                          "baseUomId": "%s",
                          "costingMethod": "AVG_COST",
                          "gstApplicability": "APPLICABLE",
                          "gstSupplyType": "GOODS",
                          "hsnSacCode": %s,
                          "gstRatePct": %s,
                          "isTobaccoMrpItem": %s,
                          "mrpPerUnit": %s,
                          "defaultSalesLedgerId": "%s",
                          "defaultPurchaseLedgerId": "%s"
                        }
                        """.formatted(
                            name,
                            grpJson,
                            baseUomId,
                            hsnJson,
                            rateJson,
                            isTobaccoMrpItem,
                            mrpJson,
                            salesLedgerId,
                            purchLedgerId
                        )
                    ),
                "sitem-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private UUID postItemInvoice(
        UUID targetCompanyId,
        String voucherTypeName,
        String voucherDate,
        String supplierInvoiceNo,
        UUID partyLedgerId,
        UUID accountingLedgerId,
        String posState,
        String inventoryLinesJson,
        String linesJson
    ) throws Exception {
        String suppNoJson = supplierInvoiceNo == null ? "null" : "\"" + supplierInvoiceNo + "\"";
        String suppDateJson = supplierInvoiceNo == null ? "null" : "\"" + voucherDate + "\"";
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "%s",
                          "voucherDate": "%s",
                          "persistedView": "ITEM_INVOICE",
                          "supplierInvoiceNo": %s,
                          "supplierInvoiceDate": %s,
                          "partyLedgerId": "%s",
                          "accountingLedgerId": "%s",
                          "placeOfSupplyState": "%s",
                          "inventoryLines": %s,
                          "lines": %s
                        }
                        """.formatted(
                            voucherTypeName,
                            voucherDate,
                            suppNoJson,
                            suppDateJson,
                            partyLedgerId,
                            accountingLedgerId,
                            posState,
                            inventoryLinesJson,
                            linesJson
                        )
                    ),
                "vch-inv-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private static String extractWorkbookText(XSSFWorkbook wb) {
        StringBuilder sb = new StringBuilder(2048);
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            sb.append(sheet.getSheetName()).append('\n');
            for (Row row : sheet) {
                for (Cell cell : row) {
                    sb.append(cell.toString()).append(' ');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static JsonNode findInArray(JsonNode array, String field, String expected) {
        for (JsonNode el : array) {
            if (el.has(field) && expected.equals(el.get(field).asString())) {
                return el;
            }
        }
        throw new AssertionError("Expected element with " + field + "=" + expected + " in " + array);
    }

    private static MockHttpServletRequestBuilder authedGet(String uri) {
        return MockMvcRequestBuilders.get(uri).cookie(ownerSessionCookie, ownerXsrfCookie);
    }

    private static MockHttpServletRequestBuilder authedMutate(
        MockHttpServletRequestBuilder builder,
        String idempotencyKey
    ) {
        String xsrfVal = URLDecoder.decode(ownerXsrfCookie.getValue(), StandardCharsets.UTF_8);
        return builder
            .cookie(ownerSessionCookie, ownerXsrfCookie)
            .header("X-XSRF-TOKEN", xsrfVal)
            .header("Idempotency-Key", idempotencyKey);
    }

    private static Cookie latestCookie(MvcResult result, String name) {
        Cookie[] cookies = result.getResponse().getCookies();
        Cookie found = null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                found = cookie;
            }
        }
        return found;
    }
}
