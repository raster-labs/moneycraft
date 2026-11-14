package com.raster.moneycraft.inventory;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.DualPostgres18TestcontainersSupport;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
 * End-to-end Phase 4 (Gate G3 Supply Chain) verification against isolated Dual-PostgreSQL 18 clusters:
 * Inventory Masters (UOMs, Hierarchical Godowns, Stock Groups/Categories, Stock Items, Batches,
 * Staggered Price Lists, Multi-BOM), O2C & P2P Tracking without double-counting stock, ITEM_INVOICE
 * accounting synthesis, Manufacturing Preview (Golden Vector C), Golden Vector B back-dated insert +
 * Purchase Return across all 8 valuation methods, and Medical Devices parity benchmark (AT-INV-001..010).
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryAndValuationIntegrationTest {

    private static final DualPostgres18TestcontainersSupport.ClusterPair CLUSTERS =
        DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;
    private static UUID companyId;

    private static UUID mainLocationId;
    private static UUID chennaiWarehouseId;
    private static UUID coldStorageRackId;

    private static UUID uomNosId;
    private static UUID uomKgId;
    private static UUID uomPcsId;
    private static UUID uomBoxId;
    private static UUID uomBox10PcsId;

    private static UUID grpMedicalDevicesId;
    private static UUID grpEngineeringGoodsId;
    private static UUID catCriticalSparesId;

    private static UUID salesLedgerId;
    private static UUID purchaseLedgerId;
    private static UUID debtorLedgerId;
    private static UUID creditorLedgerId;

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
    @Qualifier("businessJdbcTemplate")
    private JdbcTemplate businessJdbcTemplate;

    @Test
    @Order(1)
    void inventoryMastersUomsGodownsCategoriesGroupsAndSystemGodownProtection() throws Exception {
        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "installationLabel": "MoneyCraft Phase 4 Supply Chain Cluster",
                      "ownerUsername": "inv_admin",
                      "ownerDisplayName": "Chief Supply Chain Officer",
                      "password": "ChiefInventory#2026!"
                    }
                    """
                )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSessionCookie = latestCookie(initRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(initRes, "XSRF-TOKEN");

        companyId = createTestCompany(
            "MCINV01",
            "MoneyCraft Supply Chain Labs Pvt Ltd",
            "33",
            "2025-04-01"
        );

        // 1. Verify Main Location godown was seeded automatically & is protected from deletion
        MvcResult godownsRes = mockMvc.perform(authedGet("/api/v1/companies/" + companyId + "/godowns"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode godowns = objectMapper.readTree(godownsRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(godowns.isArray()).isTrue();
        Assertions.assertThat(godowns.size()).isEqualTo(1);
        Assertions.assertThat(godowns.get(0).get("name").asString()).isEqualTo("Main Location");
        Assertions.assertThat(godowns.get(0).get("isSystemGodown").asBoolean()).isTrue();
        mainLocationId = UUID.fromString(godowns.get(0).get("id").asString());

        MvcResult delMainRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.delete("/api/v1/companies/" + companyId + "/godowns/" + mainLocationId),
                "del-main-godown"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(delMainRes.getResponse().getContentAsByteArray()).get("errorCode").asString()
        ).isEqualTo("MC-INV-SYSTEM-GODOWN-PROTECTED");

        // Create hierarchical godowns & verify cycle guard
        chennaiWarehouseId = createGodown(companyId, "Chennai Warehouse", "CHN-WH", mainLocationId);
        coldStorageRackId = createGodown(companyId, "Cold Storage Rack A", "COLD-A", chennaiWarehouseId);

        MvcResult cycleGodownRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyId + "/godowns/" + chennaiWarehouseId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Chennai Warehouse",
                          "aliasCode": "CHN-WH",
                          "parentGodownId": "%s",
                          "allowStorageOfMaterials": true
                        }
                        """.formatted(coldStorageRackId)
                    ),
                "godown-cycle-check"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(cycleGodownRes.getResponse().getContentAsByteArray()).get("errorCode").asString()
        ).isEqualTo("MC-INV-GODOWN-CYCLE");

        // 2. Create Simple & Compound UOMs
        uomNosId = createSimpleUom(companyId, "NOS", "Numbers", "NOS", 0);
        uomKgId = createSimpleUom(companyId, "KG", "Kilograms", "KGS", 3);
        uomPcsId = createSimpleUom(companyId, "PCS", "Pieces", "PCS", 0);
        uomBoxId = createSimpleUom(companyId, "BOX", "Boxes", "BOX", 0);

        MvcResult compUomRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/uoms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "uomType": "COMPOUND",
                          "symbol": "BOX-10-PCS",
                          "formalName": "Box of 10 Pieces",
                          "decimalPlaces": 0,
                          "firstUomId": "%s",
                          "conversionFactor": "10",
                          "secondUomId": "%s"
                        }
                        """.formatted(uomBoxId, uomPcsId)
                    ),
                "uom-compound-box-pcs"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        uomBox10PcsId = UUID.fromString(
            objectMapper.readTree(compUomRes.getResponse().getContentAsByteArray()).get("id").asString()
        );
        Assertions.assertThat(uomBox10PcsId).isNotNull();

        // 3. Create Stock Groups & Stock Categories + verify cycle guards
        grpMedicalDevicesId = createStockGroup(companyId, "Medical Devices", null, "9018", "12");
        grpEngineeringGoodsId = createStockGroup(companyId, "Engineering Goods", null, "8483", "18");
        UUID grpPrecisionGearsId = createStockGroup(
            companyId, "Precision Gears", grpEngineeringGoodsId, "84834000", "18"
        );

        MvcResult grpCycleRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put(
                    "/api/v1/companies/" + companyId + "/stock-groups/" + grpEngineeringGoodsId
                )
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Engineering Goods",
                          "parentStockGroupId": "%s",
                          "shouldQuantitiesOfItemsBeAdded": true
                        }
                        """.formatted(grpPrecisionGearsId)
                    ),
                "stkgrp-cycle-check"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(grpCycleRes.getResponse().getContentAsByteArray()).get("errorCode").asString()
        ).isEqualTo("MC-INV-STOCKGROUP-CYCLE");

        catCriticalSparesId = createStockCategory(companyId, "Critical Spares", null);
        UUID catCncSparesId = createStockCategory(companyId, "CNC Spares", catCriticalSparesId);

        MvcResult catCycleRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put(
                    "/api/v1/companies/" + companyId + "/stock-categories/" + catCriticalSparesId
                )
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Critical Spares",
                          "parentStockCategoryId": "%s"
                        }
                        """.formatted(catCncSparesId)
                    ),
                "stkcat-cycle-check"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(catCycleRes.getResponse().getContentAsByteArray()).get("errorCode").asString()
        ).isEqualTo("MC-INV-STOCKCATEGORY-CYCLE");

        // 4. Create Sales, Purchase, Debtor & Creditor ledgers
        UUID salesAccountsGrp = findGroupIdByName(companyId, "Sales Accounts");
        UUID purchaseAccountsGrp = findGroupIdByName(companyId, "Purchase Accounts");
        UUID debtorsGrp = findGroupIdByName(companyId, "Sundry Debtors");
        UUID creditorsGrp = findGroupIdByName(companyId, "Sundry Creditors");

        salesLedgerId = createLedger(companyId, "Domestic Sales A/c", salesAccountsGrp, false);
        purchaseLedgerId = createLedger(companyId, "Domestic Purchase A/c", purchaseAccountsGrp, false);
        debtorLedgerId = createLedger(companyId, "Apollo Hospitals Debtor", debtorsGrp, true);
        creditorLedgerId = createLedger(companyId, "Bharat Steel Supplier", creditorsGrp, true);
    }

    @Test
    @Order(2)
    void stockItemsOpeningLayersEditLogStaggeredPriceListsAndBatchExpiryEnforcement() throws Exception {
        // Create batch-tracked + expiry-tracked item with Alternate UOM (1 BOX = 10 PCS) & Opening Stock
        MvcResult createRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/stock-items")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Surgical Gloves Sterile",
                          "aliasCode": "SG-100",
                          "partNo": "MED-SG-100",
                          "stockGroupId": "%s",
                          "stockCategoryId": "%s",
                          "baseUomId": "%s",
                          "alternateUomId": "%s",
                          "conversionNumerator": "10",
                          "conversionDenominator": "1",
                          "maintainInBatches": true,
                          "trackDateOfMfg": true,
                          "useExpiryDates": true,
                          "costingMethod": "FIFO",
                          "openingQty": "50",
                          "openingRate": "12.00",
                          "openingValue": "600.00",
                          "openingGodownId": "%s",
                          "openingBatchName": "OPEN-LOT-25",
                          "openingMfgDate": "2025-01-15",
                          "openingExpiryDate": "2026-01-15",
                          "gstApplicability": "APPLICABLE",
                          "gstSupplyType": "GOODS",
                          "hsnSacCode": "40151100",
                          "gstRatePct": "12",
                          "defaultSalesLedgerId": "%s",
                          "defaultPurchaseLedgerId": "%s"
                        }
                        """.formatted(
                            grpMedicalDevicesId,
                            catCriticalSparesId,
                            uomPcsId,
                            uomBoxId,
                            coldStorageRackId,
                            salesLedgerId,
                            purchaseLedgerId
                        )
                    ),
                "item-surgical-gloves"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v1\""))
            .andReturn();

        UUID glovesItemId = UUID.fromString(
            objectMapper.readTree(createRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Alter Stock Item (v1 -> v2) and verify ETag optimistic concurrency + MCA Edit Log v1 & v2
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyId + "/stock-items/" + glovesItemId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Surgical Gloves Sterile",
                          "aliasCode": "SG-100",
                          "partNo": "MED-SG-100-REV2",
                          "description": "Powder-free sterile latex surgical gloves",
                          "stockGroupId": "%s",
                          "stockCategoryId": "%s",
                          "baseUomId": "%s",
                          "alternateUomId": "%s",
                          "conversionNumerator": "10",
                          "conversionDenominator": "1",
                          "maintainInBatches": true,
                          "trackDateOfMfg": true,
                          "useExpiryDates": true,
                          "costingMethod": "FIFO",
                          "openingQty": "50",
                          "openingRate": "12.00",
                          "openingValue": "600.00",
                          "openingGodownId": "%s",
                          "openingBatchName": "OPEN-LOT-25",
                          "openingMfgDate": "2025-01-15",
                          "openingExpiryDate": "2026-01-15",
                          "gstApplicability": "APPLICABLE",
                          "gstSupplyType": "GOODS",
                          "hsnSacCode": "40151100",
                          "gstRatePct": "12",
                          "defaultSalesLedgerId": "%s",
                          "defaultPurchaseLedgerId": "%s"
                        }
                        """.formatted(
                            grpMedicalDevicesId,
                            catCriticalSparesId,
                            uomPcsId,
                            uomBoxId,
                            coldStorageRackId,
                            salesLedgerId,
                            purchaseLedgerId
                        )
                    ),
                "item-surgical-gloves-upd"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("ETag", "\"v2\""));

        // Stale v1 If-Match must fail with 412 Precondition Failed
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + companyId + "/stock-items/" + glovesItemId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Surgical Gloves Sterile",
                          "baseUomId": "%s",
                          "costingMethod": "FIFO"
                        }
                        """.formatted(uomPcsId)
                    ),
                "item-surgical-gloves-stale"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isPreconditionFailed());

        Long editLogVersions = TenantContextHolder.callWithCompany(companyId, () ->
            businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_edit_log_version WHERE company_id = ? AND entity_type = 'STOCK_ITEM'"
                    + " AND entity_id = ?",
                Long.class,
                companyId,
                glovesItemId
            )
        );
        Assertions.assertThat(editLogVersions).isEqualTo(2L);

        // Verify typeahead
        MvcResult taRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/stock-items/typeahead?q=Surgical")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(taRes.getResponse().getContentAsByteArray()).size())
            .isEqualTo(1);

        // Staggered Price List & /price-lists/lookup
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/price-lists")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "priceLevelName": "Wholesale",
                          "applicableFrom": "2025-04-01",
                          "currencyCode": "INR",
                          "tiers": [
                            {
                              "stockItemId": "%s",
                              "fromQty": "0",
                              "lessThanQty": "100",
                              "rate": "18.00",
                              "discountPct": "0"
                            },
                            {
                              "stockItemId": "%s",
                              "fromQty": "100",
                              "lessThanQty": null,
                              "rate": "16.50",
                              "discountPct": "5"
                            }
                          ]
                        }
                        """.formatted(glovesItemId, glovesItemId)
                    ),
                "pl-wholesale-2025"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult tier1Lookup = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId + "/price-lists/lookup"
                    + "?priceLevelName=Wholesale&stockItemId=" + glovesItemId
                    + "&qty=40&asOfDate=2025-04-15"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode t1Json = objectMapper.readTree(tier1Lookup.getResponse().getContentAsByteArray());
        Assertions.assertThat(t1Json.get("rate").asString()).isEqualTo("18.00");
        Assertions.assertThat(t1Json.get("discountPct").asString()).isEqualTo("0");

        MvcResult tier2Lookup = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId + "/price-lists/lookup"
                    + "?priceLevelName=Wholesale&stockItemId=" + glovesItemId
                    + "&qty=150&asOfDate=2025-04-15"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode t2Json = objectMapper.readTree(tier2Lookup.getResponse().getContentAsByteArray());
        Assertions.assertThat(t2Json.get("rate").asString()).isEqualTo("16.50");
        Assertions.assertThat(t2Json.get("discountPct").asString()).isEqualTo("5");

        // Post Purchase ITEM_INVOICE using Alternate UOM (2 BOX = 20 PCS) into batch expiring 2025-05-01
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Purchase",
                          "voucherDate": "2025-04-10",
                          "persistedView": "ITEM_INVOICE",
                          "action": "POST",
                          "supplierInvoiceNo": "SUP-GLV-01",
                          "supplierInvoiceDate": "2025-04-10",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "2",
                              "billedQty": "2",
                              "rate": "120.00",
                              "amount": "240.00",
                              "batchAllocations": [
                                {
                                  "godownId": "%s",
                                  "batchName": "EXP-MAY25",
                                  "actualQty": "2",
                                  "billedQty": "2",
                                  "rate": "120.00",
                                  "amount": "240.00",
                                  "mfgDate": "2025-01-01",
                                  "expiryDate": "2025-05-01"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(creditorLedgerId, glovesItemId, uomBoxId, coldStorageRackId)
                    ),
                "vch-purch-gloves-alt-uom"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        // Verify /batches shows 20 PCS in EXP-MAY25 and marks it expired as of 2025-05-15
        MvcResult batchesRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId + "/stock-items/" + glovesItemId
                    + "/batches?asOfDate=2025-05-15"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode batches = objectMapper.readTree(batchesRes.getResponse().getContentAsByteArray());
        JsonNode expBatch = findInArray(batches, "batchName", "EXP-MAY25");
        Assertions.assertThat(expBatch.get("closingQty").asString()).isEqualTo("20");
        Assertions.assertThat(expBatch.get("isExpired").asBoolean()).isTrue();

        // Outward issue from expired batch on 2025-05-15 MUST fail with 422 MC-INV-BATCH-EXPIRED
        MvcResult expiredSaleRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-05-15",
                          "persistedView": "ITEM_INVOICE",
                          "action": "POST",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "5",
                              "billedQty": "5",
                              "rate": "18.00",
                              "amount": "90.00",
                              "batchAllocations": [
                                {
                                  "godownId": "%s",
                                  "batchName": "EXP-MAY25",
                                  "actualQty": "5",
                                  "billedQty": "5",
                                  "rate": "18.00",
                                  "amount": "90.00"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(debtorLedgerId, glovesItemId, uomPcsId, coldStorageRackId)
                    ),
                "vch-expired-batch-blocked"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(expiredSaleRes.getResponse().getContentAsByteArray()).get("errorCode").asString()
        ).isEqualTo("MC-INV-BATCH-EXPIRED");

        // Explicit scrap write-off with allowExpiredBatch=true succeeds
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Stock Journal",
                          "voucherDate": "2025-05-15",
                          "action": "POST",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "movementDirection": "OUTWARD",
                              "uomId": "%s",
                              "actualQty": "5",
                              "billedQty": "5",
                              "rate": "12.00",
                              "amount": "60.00",
                              "allowExpiredBatch": true,
                              "batchAllocations": [
                                {
                                  "godownId": "%s",
                                  "batchName": "EXP-MAY25",
                                  "actualQty": "5",
                                  "billedQty": "5",
                                  "rate": "12.00",
                                  "amount": "60.00"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(glovesItemId, uomPcsId, coldStorageRackId)
                    ),
                "vch-expired-batch-scrap-override"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        // Verify /reports/batch-ageing reports remaining 15 PCS in EXP-MAY25 as expired
        MvcResult ageingRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId + "/reports/batch-ageing"
                    + "?asOfDate=2025-05-15&stockItemId=" + glovesItemId
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode ageingBatches = objectMapper.readTree(ageingRes.getResponse().getContentAsByteArray())
            .get("batches");
        JsonNode expAgeing = findInArray(ageingBatches, "batchName", "EXP-MAY25");
        Assertions.assertThat(expAgeing.get("closingQty").asString()).isEqualTo("15");
        Assertions.assertThat(expAgeing.get("isExpired").asBoolean()).isTrue();
    }

    @Test
    @Order(3)
    void o2cAndP2pOrderTrackingLifecycleWithoutDoubleCountingStock() throws Exception {
        UUID motorItemId = createSimpleStockItem(
            companyId,
            "CNC Spindle Motor",
            grpEngineeringGoodsId,
            uomNosId,
            "FIFO",
            null,
            salesLedgerId,
            purchaseLedgerId
        );

        // --- P2P: Purchase Order -> Receipt Note (tracking RN-1001) -> Purchase ITEM_INVOICE ---
        MvcResult poRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Purchase Order",
                          "voucherDate": "2025-04-05",
                          "action": "POST",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "10",
                              "billedQty": "10",
                              "rate": "5000.00",
                              "amount": "50000.00",
                              "orderRefNo": "PO-2025-001"
                            }
                          ]
                        }
                        """.formatted(creditorLedgerId, motorItemId, uomNosId)
                    ),
                "vch-po-001"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID poJournalId = UUID.fromString(
            objectMapper.readTree(poRes.getResponse().getContentAsByteArray()).get("id").asString()
        );
        Assertions.assertThat(countGlEntries(companyId, poJournalId)).isZero();
        Assertions.assertThat(readItemClosingQty(companyId, motorItemId, "2025-04-30")).isEqualTo("0");

        JsonNode openPos1 = getOpenOrders(companyId, "PURCHASE_ORDER");
        JsonNode poLine1 = findInArray(openPos1, "orderNo", "PO-2025-001");
        Assertions.assertThat(poLine1.get("orderedQty").asString()).isEqualTo("10");
        Assertions.assertThat(poLine1.get("deliveredReceivedQty").asString()).isEqualTo("0");
        Assertions.assertThat(poLine1.get("billedQty").asString()).isEqualTo("0");
        Assertions.assertThat(poLine1.get("pendingQty").asString()).isEqualTo("10");
        Assertions.assertThat(poLine1.get("status").asString()).isEqualTo("OPEN");

        // Receipt Note physically moves +10 into Chennai Warehouse with 0 GL entries
        MvcResult rnRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Receipt Note",
                          "voucherDate": "2025-04-07",
                          "action": "POST",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "10",
                              "billedQty": "10",
                              "rate": "5000.00",
                              "amount": "50000.00",
                              "orderRefNo": "PO-2025-001",
                              "trackingNo": "RN-1001",
                              "batchAllocations": [
                                {
                                  "godownId": "%s",
                                  "batchName": "Primary Batch",
                                  "actualQty": "10",
                                  "billedQty": "10",
                                  "rate": "5000.00",
                                  "amount": "50000.00"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(creditorLedgerId, motorItemId, uomNosId, chennaiWarehouseId)
                    ),
                "vch-rn-1001"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID rnJournalId = UUID.fromString(
            objectMapper.readTree(rnRes.getResponse().getContentAsByteArray()).get("id").asString()
        );
        Assertions.assertThat(countGlEntries(companyId, rnJournalId)).isZero();
        Assertions.assertThat(readItemClosingQty(companyId, motorItemId, "2025-04-30")).isEqualTo("10");

        JsonNode openPos2 = getOpenOrders(companyId, "PURCHASE_ORDER");
        JsonNode poLine2 = findInArray(openPos2, "orderNo", "PO-2025-001");
        Assertions.assertThat(poLine2.get("deliveredReceivedQty").asString()).isEqualTo("10");
        Assertions.assertThat(poLine2.get("billedQty").asString()).isEqualTo("0");
        Assertions.assertThat(poLine2.get("status").asString()).isEqualTo("PARTIAL");

        // Purchase ITEM_INVOICE referencing trackingNo RN-1001 synthesizes GL entries WITHOUT double-adding stock
        MvcResult purchInvRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Purchase",
                          "voucherDate": "2025-04-08",
                          "persistedView": "ITEM_INVOICE",
                          "action": "POST",
                          "supplierInvoiceNo": "BHARAT-INV-88",
                          "supplierInvoiceDate": "2025-04-08",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "10",
                              "billedQty": "10",
                              "rate": "5000.00",
                              "amount": "50000.00",
                              "orderRefNo": "PO-2025-001",
                              "trackingNo": "RN-1001",
                              "batchAllocations": [
                                {
                                  "godownId": "%s",
                                  "batchName": "Primary Batch",
                                  "actualQty": "10",
                                  "billedQty": "10",
                                  "rate": "5000.00",
                                  "amount": "50000.00"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(creditorLedgerId, motorItemId, uomNosId, chennaiWarehouseId)
                    ),
                "vch-purch-rn-1001"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID purchJournalId = UUID.fromString(
            objectMapper.readTree(purchInvRes.getResponse().getContentAsByteArray()).get("id").asString()
        );
        Assertions.assertThat(countGlEntries(companyId, purchJournalId)).isEqualTo(2L);
        // Stock MUST remain 10 (NOT 20!)
        Assertions.assertThat(readItemClosingQty(companyId, motorItemId, "2025-04-30")).isEqualTo("10");
        Assertions.assertThat(getOpenOrders(companyId, "PURCHASE_ORDER").size()).isZero();

        // --- O2C: Sales Order -> Delivery Note (tracking DN-2001) -> Sales ITEM_INVOICE ---
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales Order",
                          "voucherDate": "2025-04-10",
                          "action": "POST",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "6",
                              "billedQty": "6",
                              "rate": "7500.00",
                              "amount": "45000.00",
                              "orderRefNo": "SO-2025-001"
                            }
                          ]
                        }
                        """.formatted(debtorLedgerId, motorItemId, uomNosId)
                    ),
                "vch-so-001"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());
        Assertions.assertThat(readItemClosingQty(companyId, motorItemId, "2025-04-30")).isEqualTo("10");

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Delivery Note",
                          "voucherDate": "2025-04-12",
                          "action": "POST",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "6",
                              "billedQty": "6",
                              "rate": "7500.00",
                              "amount": "45000.00",
                              "orderRefNo": "SO-2025-001",
                              "trackingNo": "DN-2001",
                              "batchAllocations": [
                                {
                                  "godownId": "%s",
                                  "batchName": "Primary Batch",
                                  "actualQty": "6",
                                  "billedQty": "6",
                                  "rate": "7500.00",
                                  "amount": "45000.00"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(debtorLedgerId, motorItemId, uomNosId, chennaiWarehouseId)
                    ),
                "vch-dn-2001"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());
        Assertions.assertThat(readItemClosingQty(companyId, motorItemId, "2025-04-30")).isEqualTo("4");

        MvcResult salesInvRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Sales",
                          "voucherDate": "2025-04-14",
                          "persistedView": "ITEM_INVOICE",
                          "action": "POST",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "6",
                              "billedQty": "6",
                              "rate": "7500.00",
                              "amount": "45000.00",
                              "orderRefNo": "SO-2025-001",
                              "trackingNo": "DN-2001",
                              "batchAllocations": [
                                {
                                  "godownId": "%s",
                                  "batchName": "Primary Batch",
                                  "actualQty": "6",
                                  "billedQty": "6",
                                  "rate": "7500.00",
                                  "amount": "45000.00"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(debtorLedgerId, motorItemId, uomNosId, chennaiWarehouseId)
                    ),
                "vch-sales-dn-2001"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID salesJournalId = UUID.fromString(
            objectMapper.readTree(salesInvRes.getResponse().getContentAsByteArray()).get("id").asString()
        );
        Assertions.assertThat(countGlEntries(companyId, salesJournalId)).isEqualTo(2L);
        // Stock MUST remain 4 (NOT -2!)
        Assertions.assertThat(readItemClosingQty(companyId, motorItemId, "2025-04-30")).isEqualTo("4");
        Assertions.assertThat(getOpenOrders(companyId, "SALES_ORDER").size()).isZero();
    }

    @Test
    @Order(4)
    void multiBomManufacturingPreviewAndStockJournalGoldenVectorC() throws Exception {
        UUID rawSteelId = createSimpleStockItem(
            companyId,
            "Raw-Steel-Kg",
            grpEngineeringGoodsId,
            uomKgId,
            "FIFO",
            null,
            salesLedgerId,
            purchaseLedgerId
        );
        UUID steelScrapId = createSimpleStockItem(
            companyId,
            "Steel-Scrap-Kg",
            grpEngineeringGoodsId,
            uomKgId,
            "FIFO",
            null,
            salesLedgerId,
            purchaseLedgerId
        );
        UUID finishedGearId = createSimpleStockItem(
            companyId,
            "Finished-Gear",
            grpEngineeringGoodsId,
            uomNosId,
            "FIFO",
            null,
            salesLedgerId,
            purchaseLedgerId
        );

        // Purchase 20 KG of Raw-Steel-Kg @ 80.00 = 1,600.00
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Purchase",
                          "voucherDate": "2025-04-05",
                          "persistedView": "ITEM_INVOICE",
                          "action": "POST",
                          "supplierInvoiceNo": "STEEL-001",
                          "supplierInvoiceDate": "2025-04-05",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "20",
                              "billedQty": "20",
                              "rate": "80.00",
                              "amount": "1600.00"
                            }
                          ]
                        }
                        """.formatted(creditorLedgerId, rawSteelId, uomKgId)
                    ),
                "vch-purch-raw-steel"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        // Create Multi-BOM for Finished-Gear (10 NOS consumes 20 KG Raw-Steel-Kg, yields 1 KG Scrap @ 5% cost)
        MvcResult bomRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/boms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "stockItemId": "%s",
                          "bomName": "Standard Gear BOM",
                          "unitOfManufacture": "10",
                          "components": [
                            {
                              "stockItemId": "%s",
                              "componentType": "COMPONENT",
                              "uomId": "%s",
                              "quantity": "20",
                              "costAllocationPct": "0"
                            },
                            {
                              "stockItemId": "%s",
                              "componentType": "SCRAP",
                              "uomId": "%s",
                              "quantity": "1",
                              "costAllocationPct": "5"
                            }
                          ]
                        }
                        """.formatted(finishedGearId, rawSteelId, uomKgId, steelScrapId, uomKgId)
                    ),
                "bom-standard-gear"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID bomId = UUID.fromString(
            objectMapper.readTree(bomRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // Preview Manufacturing (Golden Vector C): 1600.00 - 80.00 (5% scrap) = 1520.00 -> 152.00 / NOS
        MvcResult previewRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + companyId + "/vouchers/manufacturing-preview"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "bomId": "%s",
                          "finishedStockItemId": "%s",
                          "manufacturedQty": "10",
                          "asOfDate": "2025-04-10"
                        }
                        """.formatted(bomId, finishedGearId)
                    ),
                "mfg-preview-gear-10"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode preview = objectMapper.readTree(previewRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(preview.get("componentTotalCost").asString()).isEqualTo("1600.00");
        Assertions.assertThat(preview.get("scrapDeductionTotal").asString()).isEqualTo("80.00");
        Assertions.assertThat(preview.get("totalManufacturingCost").asString()).isEqualTo("1520.00");
        Assertions.assertThat(preview.get("finishedTotalCost").asString()).isEqualTo("1520.00");
        Assertions.assertThat(preview.get("effectiveUnitRate").asString()).isEqualTo("152.00");
        Assertions.assertThat(preview.get("finishedUnitCost").asString()).isEqualTo("152.00");

        // Post Manufacturing Stock Journal
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + companyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Stock Journal",
                          "voucherDate": "2025-04-10",
                          "action": "POST",
                          "narration": "Manufacture 10 NOS Finished-Gear per Standard Gear BOM",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "movementDirection": "OUTWARD",
                              "uomId": "%s",
                              "actualQty": "20",
                              "billedQty": "20",
                              "rate": "80.00",
                              "amount": "1600.00"
                            },
                            {
                              "stockItemId": "%s",
                              "movementDirection": "INWARD",
                              "uomId": "%s",
                              "actualQty": "10",
                              "billedQty": "10",
                              "rate": "152.00",
                              "amount": "1520.00"
                            },
                            {
                              "stockItemId": "%s",
                              "movementDirection": "INWARD",
                              "uomId": "%s",
                              "actualQty": "1",
                              "billedQty": "1",
                              "rate": "80.00",
                              "amount": "80.00"
                            }
                          ]
                        }
                        """.formatted(rawSteelId, uomKgId, finishedGearId, uomNosId, steelScrapId, uomKgId)
                    ),
                "vch-mfg-gear-10"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        MvcResult summaryRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId
                    + "/reports/stock-summary?fromDate=2025-04-01&toDate=2025-04-30"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode items = objectMapper.readTree(summaryRes.getResponse().getContentAsByteArray()).get("items");

        JsonNode gearRow = findInArray(items, "stockItemName", "Finished-Gear");
        Assertions.assertThat(gearRow.get("closingQty").asString()).isEqualTo("10");
        Assertions.assertThat(gearRow.get("closingRate").asString()).isEqualTo("152.00");
        Assertions.assertThat(gearRow.get("closingValue").asString()).isEqualTo("1520.00");

        JsonNode scrapRow = findInArray(items, "stockItemName", "Steel-Scrap-Kg");
        Assertions.assertThat(scrapRow.get("closingQty").asString()).isEqualTo("1");
        Assertions.assertThat(scrapRow.get("closingValue").asString()).isEqualTo("80.00");

        JsonNode steelRow = findInArray(items, "stockItemName", "Raw-Steel-Kg");
        Assertions.assertThat(steelRow.get("closingQty").asString()).isEqualTo("0");
        Assertions.assertThat(steelRow.get("closingValue").asString()).isEqualTo("0.00");
    }

    @Test
    @Order(5)
    void goldenVectorBBackDatedInsertPurchaseReturnAndAll8CostingMethods() throws Exception {
        UUID vecCompanyId = createTestCompany(
            "MCVECB",
            "MoneyCraft Golden Vector B Valuation Ltd",
            "33",
            "2025-04-01"
        );

        UUID vecNosId = createSimpleUom(vecCompanyId, "NOS", "Numbers", "NOS", 0);
        UUID vecSalesId = createLedger(
            vecCompanyId,
            "Sales A/c",
            findGroupIdByName(vecCompanyId, "Sales Accounts"),
            false
        );
        UUID vecPurchaseId = createLedger(
            vecCompanyId,
            "Purchase A/c",
            findGroupIdByName(vecCompanyId, "Purchase Accounts"),
            false
        );
        UUID vecPartyId = createLedger(
            vecCompanyId,
            "Universal Trading Party",
            findGroupIdByName(vecCompanyId, "Sundry Creditors"),
            false
        );

        UUID widgetFifoId = createSimpleStockItem(
            vecCompanyId,
            "Widget-FIFO",
            null,
            vecNosId,
            "FIFO",
            "110.00",
            vecSalesId,
            vecPurchaseId
        );
        UUID widgetAvgId = createSimpleStockItem(
            vecCompanyId,
            "Widget-AVG",
            null,
            vecNosId,
            "AVG_COST",
            "110.00",
            vecSalesId,
            vecPurchaseId
        );

        // 1. Initial sequence: Apr 5 Buy 10@100, Apr 15 Buy 10@130, Apr 20 Sell 12@200
        postDualItemInvoice(
            vecCompanyId, "Purchase", "2025-04-05", vecPartyId, vecNosId,
            widgetFifoId, widgetAvgId, "10", "100.00", "1000.00", "vecb-p-apr05"
        );
        JsonNode apr15Voucher = postDualItemInvoice(
            vecCompanyId, "Purchase", "2025-04-15", vecPartyId, vecNosId,
            widgetFifoId, widgetAvgId, "10", "130.00", "1300.00", "vecb-p-apr15"
        );
        postDualItemInvoice(
            vecCompanyId, "Sales", "2025-04-20", vecPartyId, vecNosId,
            widgetFifoId, widgetAvgId, "12", "200.00", "2400.00", "vecb-s-apr20"
        );

        MvcResult initialRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + vecCompanyId
                    + "/reports/stock-summary?fromDate=2025-04-01&toDate=2025-04-30"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode initialItems = objectMapper.readTree(initialRes.getResponse().getContentAsByteArray())
            .get("items");

        JsonNode fifoInitial = findInArray(initialItems, "stockItemName", "Widget-FIFO");
        Assertions.assertThat(fifoInitial.get("closingQty").asString()).isEqualTo("8");
        Assertions.assertThat(fifoInitial.get("closingRate").asString()).isEqualTo("130.00");
        Assertions.assertThat(fifoInitial.get("closingValue").asString()).isEqualTo("1040.00");

        JsonNode avgInitial = findInArray(initialItems, "stockItemName", "Widget-AVG");
        Assertions.assertThat(avgInitial.get("closingQty").asString()).isEqualTo("8");
        Assertions.assertThat(avgInitial.get("closingRate").asString()).isEqualTo("115.00");
        Assertions.assertThat(avgInitial.get("closingValue").asString()).isEqualTo("920.00");

        // 2. Back-dated Purchase on 2025-04-02 (5 @ 70.00 = 350.00)
        postDualItemInvoice(
            vecCompanyId, "Purchase", "2025-04-02", vecPartyId, vecNosId,
            widgetFifoId, widgetAvgId, "5", "70.00", "350.00", "vecb-p-backdated-apr02"
        );

        // 3. Purchase Return (Debit Note) on 2025-05-02 (2 @ 130.00 = 260.00) linked to Apr 15 inward layer
        JsonNode apr15InvLines = apr15Voucher.get("inventoryLines");
        UUID fifoApr15AllocId = UUID.fromString(
            findInArray(apr15InvLines, "stockItemName", "Widget-FIFO").get("id").asString()
        );
        UUID avgApr15AllocId = UUID.fromString(
            findInArray(apr15InvLines, "stockItemName", "Widget-AVG").get("id").asString()
        );

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + vecCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "Debit Note",
                          "voucherDate": "2025-05-02",
                          "persistedView": "ITEM_INVOICE",
                          "action": "POST",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "2",
                              "billedQty": "2",
                              "rate": "130.00",
                              "amount": "260.00",
                              "linkedInwardAllocationId": "%s"
                            },
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "2",
                              "billedQty": "2",
                              "rate": "130.00",
                              "amount": "260.00",
                              "linkedInwardAllocationId": "%s"
                            }
                          ]
                        }
                        """.formatted(
                            vecPartyId,
                            widgetFifoId, vecNosId, fifoApr15AllocId,
                            widgetAvgId, vecNosId, avgApr15AllocId
                        )
                    ),
                "vecb-dn-may02"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        // Assert exact Golden Vector B closing valuations: FIFO = 1340.00, AVG_COST = 1143.04
        MvcResult finalRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + vecCompanyId
                    + "/reports/stock-summary?fromDate=2025-04-01&toDate=2025-05-31"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode finalItems = objectMapper.readTree(finalRes.getResponse().getContentAsByteArray())
            .get("items");

        JsonNode fifoFinal = findInArray(finalItems, "stockItemName", "Widget-FIFO");
        Assertions.assertThat(fifoFinal.get("closingQty").asString()).isEqualTo("11");
        Assertions.assertThat(fifoFinal.get("closingRate").asString()).isEqualTo("121.82");
        Assertions.assertThat(fifoFinal.get("closingValue").asString()).isEqualTo("1340.00");

        JsonNode avgFinal = findInArray(finalItems, "stockItemName", "Widget-AVG");
        Assertions.assertThat(avgFinal.get("closingQty").asString()).isEqualTo("11");
        Assertions.assertThat(avgFinal.get("closingRate").asString()).isEqualTo("103.91");
        Assertions.assertThat(avgFinal.get("closingValue").asString()).isEqualTo("1143.04");

        // Verify remaining 6 valuation methods via valuationMethodOverride
        Assertions.assertThat(
            readItemOverrideClosingValue(vecCompanyId, "Widget-FIFO", "FIFO_PERPETUAL")
        ).isEqualTo("1340.00");
        Assertions.assertThat(
            readItemOverrideClosingValue(vecCompanyId, "Widget-FIFO", "LIFO_PERPETUAL")
        ).isEqualTo("950.00");
        Assertions.assertThat(
            readItemOverrideClosingValue(vecCompanyId, "Widget-FIFO", "LIFO_ANNUAL")
        ).isEqualTo("950.00");
        Assertions.assertThat(
            readItemOverrideClosingValue(vecCompanyId, "Widget-FIFO", "LAST_PURCHASE_COST")
        ).isEqualTo("1430.00");
        Assertions.assertThat(
            readItemOverrideClosingValue(vecCompanyId, "Widget-FIFO", "STD_COST")
        ).isEqualTo("1210.00");
        Assertions.assertThat(
            readItemOverrideClosingValue(vecCompanyId, "Widget-FIFO", "AT_ZERO_COST")
        ).isEqualTo("0.00");
    }

    @Test
    @Order(6)
    void moneyCraftMedicalDevicesParityBenchmarkAndSupplyChainReports() throws Exception {
        UUID medCompanyId = createTestCompany(
            "MCMED01",
            "MoneyCraft Medical Devices Parity Ltd",
            "33",
            "2025-04-01"
        );

        UUID medNosId = createSimpleUom(medCompanyId, "NOS", "Numbers", "NOS", 0);
        UUID medVaultGodownId = createGodown(medCompanyId, "Med Vault", "MV-01", null);
        UUID medGroupId = createStockGroup(medCompanyId, "Medical Devices", null, "9018", "12");

        UUID medSalesId = createLedger(
            medCompanyId,
            "Med Sales A/c",
            findGroupIdByName(medCompanyId, "Sales Accounts"),
            false
        );
        UUID medPurchId = createLedger(
            medCompanyId,
            "Med Purchase A/c",
            findGroupIdByName(medCompanyId, "Purchase Accounts"),
            false
        );
        UUID medPartyId = createLedger(
            medCompanyId,
            "Healthcare Distributor",
            findGroupIdByName(medCompanyId, "Sundry Debtors"),
            false
        );

        UUID oximeterId = createSimpleStockItem(
            medCompanyId,
            "Pulse Oximeter Pro",
            medGroupId,
            medNosId,
            "FIFO",
            null,
            medSalesId,
            medPurchId
        );
        UUID thermometerId = createSimpleStockItem(
            medCompanyId,
            "Digital Thermometer X1",
            medGroupId,
            medNosId,
            "AVG_COST",
            null,
            medSalesId,
            medPurchId
        );

        // Pulse Oximeter Pro (FIFO): Buy 50@1200, Buy 30@1350, Sell 40, Sell 20 -> 20 @ 1350.00 = 27000.00
        postSingleItemInvoiceInGodown(
            medCompanyId, "Purchase", "2025-04-03", medPartyId, medNosId, medVaultGodownId,
            oximeterId, "50", "1200.00", "60000.00", "med-ox-p1"
        );
        postSingleItemInvoiceInGodown(
            medCompanyId, "Purchase", "2025-04-10", medPartyId, medNosId, medVaultGodownId,
            oximeterId, "30", "1350.00", "40500.00", "med-ox-p2"
        );
        postSingleItemInvoiceInGodown(
            medCompanyId, "Sales", "2025-04-15", medPartyId, medNosId, medVaultGodownId,
            oximeterId, "40", "1800.00", "72000.00", "med-ox-s1"
        );
        postSingleItemInvoiceInGodown(
            medCompanyId, "Sales", "2025-04-22", medPartyId, medNosId, medVaultGodownId,
            oximeterId, "20", "1850.00", "37000.00", "med-ox-s2"
        );

        // Digital Thermometer X1 (AVG_COST): Buy 100@310, Buy 40@315, Sell 50, Sell 30 -> 60 @ 311.43 = 18685.71
        postSingleItemInvoiceInGodown(
            medCompanyId, "Purchase", "2025-04-04", medPartyId, medNosId, medVaultGodownId,
            thermometerId, "100", "310.00", "31000.00", "med-th-p1"
        );
        postSingleItemInvoiceInGodown(
            medCompanyId, "Purchase", "2025-04-11", medPartyId, medNosId, medVaultGodownId,
            thermometerId, "40", "315.00", "12600.00", "med-th-p2"
        );
        postSingleItemInvoiceInGodown(
            medCompanyId, "Sales", "2025-04-16", medPartyId, medNosId, medVaultGodownId,
            thermometerId, "50", "450.00", "22500.00", "med-th-s1"
        );
        postSingleItemInvoiceInGodown(
            medCompanyId, "Sales", "2025-04-24", medPartyId, medNosId, medVaultGodownId,
            thermometerId, "30", "460.00", "13800.00", "med-th-s2"
        );

        MvcResult summaryRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + medCompanyId + "/reports/stock-summary"
                    + "?fromDate=2025-04-01&toDate=2025-04-30&stockGroupId=" + medGroupId
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode report = objectMapper.readTree(summaryRes.getResponse().getContentAsByteArray());
        JsonNode items = report.get("items");

        JsonNode oxRow = findInArray(items, "stockItemName", "Pulse Oximeter Pro");
        Assertions.assertThat(oxRow.get("closingQty").asString()).isEqualTo("20");
        Assertions.assertThat(oxRow.get("closingRate").asString()).isEqualTo("1350.00");
        Assertions.assertThat(oxRow.get("closingValue").asString()).isEqualTo("27000.00");

        JsonNode thRow = findInArray(items, "stockItemName", "Digital Thermometer X1");
        Assertions.assertThat(thRow.get("closingQty").asString()).isEqualTo("60");
        Assertions.assertThat(thRow.get("closingRate").asString()).isEqualTo("311.43");
        Assertions.assertThat(thRow.get("closingValue").asString()).isEqualTo("18685.71");

        Assertions.assertThat(report.get("totalClosingValue").asString()).isEqualTo("45685.71");

        // Verify Godown Summary & Movement Analysis reports
        MvcResult godownSumRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + medCompanyId + "/reports/godown-summary"
                    + "?toDate=2025-04-30&godownId=" + medVaultGodownId
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode godownReport = objectMapper.readTree(godownSumRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(godownReport.get("rows").size()).isEqualTo(2);
        Assertions.assertThat(godownReport.get("totalClosingValue").asString()).isEqualTo("45685.71");

        MvcResult movRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + medCompanyId + "/reports/movement-analysis"
                    + "?stockItemId=" + thermometerId + "&fromDate=2025-04-01&toDate=2025-04-30"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode movReport = objectMapper.readTree(movRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(movReport.get("inwardQty").asString()).isEqualTo("140");
        Assertions.assertThat(movReport.get("inwardValue").asString()).isEqualTo("43600.00");
        Assertions.assertThat(movReport.get("outwardQty").asString()).isEqualTo("80");
        Assertions.assertThat(movReport.get("outwardValue").asString()).isEqualTo("36300.00");
        Assertions.assertThat(movReport.get("movements").size()).isEqualTo(4);
    }

    // =========================================================================
    // Test Fixture Helpers
    // =========================================================================

    private String readItemOverrideClosingValue(
        UUID targetCompanyId,
        String itemName,
        String overrideMethod
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + targetCompanyId + "/reports/stock-summary"
                    + "?fromDate=2025-04-01&toDate=2025-05-31&valuationMethodOverride=" + overrideMethod
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode items = objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("items");
        return findInArray(items, "stockItemName", itemName).get("closingValue").asString();
    }

    private JsonNode postDualItemInvoice(
        UUID targetCompanyId,
        String voucherTypeName,
        String voucherDate,
        UUID partyId,
        UUID uomId,
        UUID item1Id,
        UUID item2Id,
        String qty,
        String rate,
        String amount,
        String idemKey
    ) throws Exception {
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
                          "action": "POST",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "%s",
                              "billedQty": "%s",
                              "rate": "%s",
                              "amount": "%s"
                            },
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "%s",
                              "billedQty": "%s",
                              "rate": "%s",
                              "amount": "%s"
                            }
                          ]
                        }
                        """.formatted(
                            voucherTypeName, voucherDate, partyId,
                            item1Id, uomId, qty, qty, rate, amount,
                            item2Id, uomId, qty, qty, rate, amount
                        )
                    ),
                idemKey
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsByteArray());
    }

    private void postSingleItemInvoiceInGodown(
        UUID targetCompanyId,
        String voucherTypeName,
        String voucherDate,
        UUID partyId,
        UUID uomId,
        UUID godownId,
        UUID itemId,
        String qty,
        String rate,
        String amount,
        String idemKey
    ) throws Exception {
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "voucherTypeName": "%s",
                          "voucherDate": "%s",
                          "persistedView": "ITEM_INVOICE",
                          "action": "POST",
                          "partyLedgerId": "%s",
                          "inventoryLines": [
                            {
                              "stockItemId": "%s",
                              "uomId": "%s",
                              "actualQty": "%s",
                              "billedQty": "%s",
                              "rate": "%s",
                              "amount": "%s",
                              "batchAllocations": [
                                {
                                  "godownId": "%s",
                                  "batchName": "Primary Batch",
                                  "actualQty": "%s",
                                  "billedQty": "%s",
                                  "rate": "%s",
                                  "amount": "%s"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(
                            voucherTypeName, voucherDate, partyId,
                            itemId, uomId, qty, qty, rate, amount,
                            godownId, qty, qty, rate, amount
                        )
                    ),
                idemKey
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());
    }

    private long countGlEntries(UUID targetCompanyId, UUID journalId) {
        return TenantContextHolder.callWithCompany(targetCompanyId, () -> {
            Long cnt = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_entry WHERE company_id = ? AND journal_id = ?",
                Long.class,
                targetCompanyId,
                journalId
            );
            return cnt == null ? 0L : cnt;
        });
    }

    private String readItemClosingQty(UUID targetCompanyId, UUID stockItemId, String toDate) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + targetCompanyId
                    + "/reports/stock-summary?fromDate=2025-04-01&toDate=" + toDate
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode items = objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("items");
        for (JsonNode item : items) {
            if (stockItemId.toString().equals(item.get("stockItemId").asString())) {
                return item.get("closingQty").asString();
            }
        }
        return "0";
    }

    private JsonNode getOpenOrders(UUID targetCompanyId, String orderType) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet("/api/v1/companies/" + targetCompanyId + "/orders/open?orderType=" + orderType)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsByteArray());
    }

    private UUID createSimpleUom(
        UUID targetCompanyId,
        String symbol,
        String formalName,
        String gstUqcCode,
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
                        """.formatted(symbol, formalName, gstUqcCode, decimals)
                    ),
                "uom-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createGodown(
        UUID targetCompanyId,
        String name,
        String aliasCode,
        UUID parentId
    ) throws Exception {
        String parentJson = parentId == null ? "null" : "\"" + parentId + "\"";
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/godowns")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "aliasCode": "%s",
                          "parentGodownId": %s,
                          "allowStorageOfMaterials": true
                        }
                        """.formatted(name, aliasCode, parentJson)
                    ),
                "gdn-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createStockGroup(
        UUID targetCompanyId,
        String name,
        UUID parentId,
        String hsnSacCode,
        String gstRatePct
    ) throws Exception {
        String parentJson = parentId == null ? "null" : "\"" + parentId + "\"";
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/stock-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "parentStockGroupId": %s,
                          "shouldQuantitiesOfItemsBeAdded": true,
                          "hsnSacCode": "%s",
                          "gstRatePct": "%s"
                        }
                        """.formatted(name, parentJson, hsnSacCode, gstRatePct)
                    ),
                "sgrp-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createStockCategory(UUID targetCompanyId, String name, UUID parentId) throws Exception {
        String parentJson = parentId == null ? "null" : "\"" + parentId + "\"";
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + targetCompanyId + "/stock-categories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "parentStockCategoryId": %s
                        }
                        """.formatted(name, parentJson)
                    ),
                "scat-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createSimpleStockItem(
        UUID targetCompanyId,
        String name,
        UUID stockGroupId,
        UUID baseUomId,
        String costingMethod,
        String standardCost,
        UUID defaultSalesId,
        UUID defaultPurchaseId
    ) throws Exception {
        String grpJson = stockGroupId == null ? "null" : "\"" + stockGroupId + "\"";
        String stdCostJson = standardCost == null ? "null" : "\"" + standardCost + "\"";
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
                          "costingMethod": "%s",
                          "standardCost": %s,
                          "defaultSalesLedgerId": "%s",
                          "defaultPurchaseLedgerId": "%s"
                        }
                        """.formatted(
                            name, grpJson, baseUomId, costingMethod, stdCostJson,
                            defaultSalesId, defaultPurchaseId
                        )
                    ),
                "sitem-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
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

    private UUID createLedger(
        UUID targetCompanyId,
        String name,
        UUID groupId,
        boolean billWise
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
                          "maintainBalancesBillByBill": %s,
                          "openingBalance": "0.00",
                          "openingBalanceSide": "DR"
                        }
                        """.formatted(name, groupId, billWise)
                    ),
                "ldg-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

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
                          "features": {
                            "maintainAccounts": true,
                            "maintainInventory": true,
                            "billWiseEnabled": true,
                            "costCentresEnabled": true,
                            "gstEnabled": true,
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
                .content("{\"username\":\"inv_admin\",\"password\":\"ChiefInventory#2026!\"}")
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
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
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
