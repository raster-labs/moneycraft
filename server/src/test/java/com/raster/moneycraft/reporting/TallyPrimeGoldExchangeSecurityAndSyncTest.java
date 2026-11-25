package com.raster.moneycraft.reporting;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.foundation.DualPostgres18TestcontainersSupport;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
 * End-to-End PostgreSQL 18 verification of TallyPrime Gold Release 7.2 (v1.1.7.2) Data Import/Export
 * duplicate rules (`COMBINE_OPENING_BALANCES`, `IGNORE_DUPLICATES`, `MODIFY_WITH_NEW_DATA`),
 * `overwriteVoucherByGuid`, Exception behaviors & resolution (`STOP_AT_FIRST_EXCEPTION`,
 * `RECORD_EXCEPTIONS_AND_IMPORT`, `IGNORE_EXCEPTIONS_AND_IMPORT`), Sample Excel Files (`Alt+L`),
 * `ASCII` (.csv) & `HTML` Web-Publishing exports, Mapping Template validations (`A..ZZ`, row exclusion,
 * nett Dr/Cr compute, `ALL_COMPANIES` scope), Multi-User Security Level runtime enforcement
 * (`backdatedDaysLimit`, `voucherTypeAllowlist`, `exportPermitted`), and Client/Server Synchronization (`Alt+Z`).
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TallyPrimeGoldExchangeSecurityAndSyncTest {

    private static final String ADMIN_USER = "owner";
    private static final String ADMIN_PASS = "OwnerGoldParity#2026!";

    private static final DualPostgres18TestcontainersSupport.ClusterPair CLUSTERS =
        DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

    private static Cookie ownerSessionCookie;
    private static Cookie ownerXsrfCookie;
    private static UUID goldCompanyId;

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

    @Test
    @Order(1)
    void masterDuplicateRulesCombineIgnoreModifyAndVoucherGuidOverwrite() throws Exception {
        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "installationLabel": "MoneyCraft TallyPrime Gold Parity Cluster",
                      "ownerUsername": "%s",
                      "ownerDisplayName": "Installation Owner",
                      "password": "%s"
                    }
                    """.formatted(ADMIN_USER, ADMIN_PASS)
                )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSessionCookie = latestCookie(initRes, "MCSESSION");
        ownerXsrfCookie = latestCookie(initRes, "XSRF-TOKEN");

        String prefix = "33AABCG7001R1Z";
        goldCompanyId = createTestCompany(
            "MCGOLD72",
            "TallyPrime Gold Parity Co",
            "33",
            "2026-04-01",
            prefix + ExactMoneyValidator.computeGstinMod36CheckChar(prefix)
        );
        // 1. Seed initial Ledger 'Alpha Traders' (1,000.00 DR) & Stock Item 'Gold Widget' (10 NOS @ 100 = 1,000.00)
        // plus one Voucher (guid = 'gold-vch-001', 500.00)
        String initialXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ENVELOPE>
              <HEADER><TALLYREQUEST>Import Data</TALLYREQUEST></HEADER>
              <BODY>
                <IMPORTDATA>
                  <REQUESTDATA>
                    <TALLYMESSAGE xmlns:UDF="TallyUDF">
                      <LEDGER NAME="Alpha Traders" ACTION="Create">
                        <GUID>alpha-guid-1</GUID>
                        <NAME>Alpha Traders</NAME>
                        <PARENT>Sundry Debtors</PARENT>
                        <OPENINGBALANCE>-1000.00</OPENINGBALANCE>
                      </LEDGER>
                    </TALLYMESSAGE>
                    <TALLYMESSAGE xmlns:UDF="TallyUDF">
                      <LEDGER NAME="Gold Sales A/c" ACTION="Create">
                        <GUID>sales-guid-1</GUID>
                        <NAME>Gold Sales A/c</NAME>
                        <PARENT>Sales Accounts</PARENT>
                        <OPENINGBALANCE>0.00</OPENINGBALANCE>
                      </LEDGER>
                    </TALLYMESSAGE>
                    <TALLYMESSAGE xmlns:UDF="TallyUDF">
                      <STOCKITEM NAME="Gold Widget" ACTION="Create">
                        <GUID>widget-guid-1</GUID>
                        <NAME>Gold Widget</NAME>
                        <BASEUNITS>NOS</BASEUNITS>
                        <COSTINGMETHOD>FIFO</COSTINGMETHOD>
                        <OPENINGBALANCE>10</OPENINGBALANCE>
                        <OPENINGRATE>100.00</OPENINGRATE>
                        <OPENINGVALUE>-1000.00</OPENINGVALUE>
                      </STOCKITEM>
                    </TALLYMESSAGE>
                    <TALLYMESSAGE xmlns:UDF="TallyUDF">
                      <VOUCHER REMOTEID="gold-vch-001" VCHTYPE="Sales" ACTION="Create">
                        <GUID>gold-vch-001</GUID>
                        <DATE>20260410</DATE>
                        <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>
                        <VOUCHERNUMBER>GLD-001</VOUCHERNUMBER>
                        <NARRATION>Initial Gold Sale</NARRATION>
                        <ALLLEDGERENTRIES.LIST>
                          <LEDGERNAME>Alpha Traders</LEDGERNAME>
                          <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
                          <AMOUNT>-500.00</AMOUNT>
                        </ALLLEDGERENTRIES.LIST>
                        <ALLLEDGERENTRIES.LIST>
                          <LEDGERNAME>Gold Sales A/c</LEDGERNAME>
                          <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
                          <AMOUNT>500.00</AMOUNT>
                        </ALLLEDGERENTRIES.LIST>
                      </VOUCHER>
                    </TALLYMESSAGE>
                  </REQUESTDATA>
                </IMPORTDATA>
              </BODY>
            </ENVELOPE>
            """;

        MvcResult seedRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/imports/xml")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "fileName", "seed.xml",
                        "rawXml", initialXml,
                        "masterDuplicateRule", "MODIFY_WITH_NEW_DATA",
                        "overwriteVoucherByGuid", false
                    ))),
                "gold-seed-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode seedJson = objectMapper.readTree(seedRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(seedJson.get("ledgersImported").asInt()).isEqualTo(2);
        Assertions.assertThat(seedJson.get("stockItemsImported").asInt()).isEqualTo(1);
        Assertions.assertThat(seedJson.get("vouchersImported").asInt()).isEqualTo(1);

        // 2. Import with IGNORE_DUPLICATES and overwriteVoucherByGuid = false
        String deltaXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ENVELOPE>
              <HEADER><TALLYREQUEST>Import Data</TALLYREQUEST></HEADER>
              <BODY>
                <IMPORTDATA>
                  <REQUESTDATA>
                    <TALLYMESSAGE xmlns:UDF="TallyUDF">
                      <LEDGER NAME="Alpha Traders" ACTION="Create">
                        <NAME>Alpha Traders</NAME>
                        <PARENT>Sundry Debtors</PARENT>
                        <OPENINGBALANCE>-400.00</OPENINGBALANCE>
                      </LEDGER>
                    </TALLYMESSAGE>
                    <TALLYMESSAGE xmlns:UDF="TallyUDF">
                      <STOCKITEM NAME="Gold Widget" ACTION="Create">
                        <NAME>Gold Widget</NAME>
                        <BASEUNITS>NOS</BASEUNITS>
                        <OPENINGBALANCE>5</OPENINGBALANCE>
                        <OPENINGRATE>100.00</OPENINGRATE>
                        <OPENINGVALUE>-500.00</OPENINGVALUE>
                      </STOCKITEM>
                    </TALLYMESSAGE>
                    <TALLYMESSAGE xmlns:UDF="TallyUDF">
                      <VOUCHER REMOTEID="gold-vch-001" VCHTYPE="Sales" ACTION="Create">
                        <GUID>gold-vch-001</GUID>
                        <DATE>20260410</DATE>
                        <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>
                        <VOUCHERNUMBER>GLD-001</VOUCHERNUMBER>
                        <NARRATION>Updated Gold Sale 800</NARRATION>
                        <ALLLEDGERENTRIES.LIST>
                          <LEDGERNAME>Alpha Traders</LEDGERNAME>
                          <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
                          <AMOUNT>-800.00</AMOUNT>
                        </ALLLEDGERENTRIES.LIST>
                        <ALLLEDGERENTRIES.LIST>
                          <LEDGERNAME>Gold Sales A/c</LEDGERNAME>
                          <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
                          <AMOUNT>800.00</AMOUNT>
                        </ALLLEDGERENTRIES.LIST>
                      </VOUCHER>
                    </TALLYMESSAGE>
                  </REQUESTDATA>
                </IMPORTDATA>
              </BODY>
            </ENVELOPE>
            """;

        MvcResult ignoreRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/imports/xml")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "fileName", "ignore.xml",
                        "rawXml", deltaXml,
                        "masterDuplicateRule", "IGNORE_DUPLICATES",
                        "overwriteVoucherByGuid", false
                    ))),
                "gold-ignore-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode ignoreJson = objectMapper.readTree(ignoreRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(ignoreJson.get("mastersSkipped").asInt()).isEqualTo(2);
        Assertions.assertThat(ignoreJson.get("vouchersSkipped").asInt()).isEqualTo(1);
        Assertions.assertThat(ignoreJson.get("vouchersUpdated").asInt()).isEqualTo(0);

        // Alpha Traders opening balance is still 1000.00 DR + 500.00 voucher = 1500.00 DR closing
        assertLedgerOpeningAndClosing(goldCompanyId, "Alpha Traders", "1000.00", "1500.00");

        // 3. Import with COMBINE_OPENING_BALANCES and overwriteVoucherByGuid = true
        MvcResult combineRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/imports/xml")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "fileName", "combine.xml",
                        "rawXml", deltaXml,
                        "masterDuplicateRule", "COMBINE_OPENING_BALANCES",
                        "overwriteVoucherByGuid", true
                    ))),
                "gold-combine-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode combineJson = objectMapper.readTree(combineRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(combineJson.get("vouchersUpdated").asInt()).isEqualTo(1);

        // Alpha Traders opening balance is now 1000.00 + 400.00 = 1400.00 DR, and updated voucher is 800.00 -> 2200.00 DR!
        assertLedgerOpeningAndClosing(goldCompanyId, "Alpha Traders", "1400.00", "2200.00");
    }

    @Test
    @Order(2)
    void importExceptionBehaviorsRecordAndResolveExceptionsAndSampleExcelRoundTrip() throws Exception {
        String mixedXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ENVELOPE>
              <HEADER><TALLYREQUEST>Import Data</TALLYREQUEST></HEADER>
              <BODY>
                <IMPORTDATA>
                  <REQUESTDATA>
                    <TALLYMESSAGE xmlns:UDF="TallyUDF">
                      <VOUCHER REMOTEID="gold-vch-valid-02" VCHTYPE="Sales" ACTION="Create">
                        <GUID>gold-vch-valid-02</GUID>
                        <DATE>20260412</DATE>
                        <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>
                        <VOUCHERNUMBER>GLD-002</VOUCHERNUMBER>
                        <ALLLEDGERENTRIES.LIST>
                          <LEDGERNAME>Alpha Traders</LEDGERNAME>
                          <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
                          <AMOUNT>-300.00</AMOUNT>
                        </ALLLEDGERENTRIES.LIST>
                        <ALLLEDGERENTRIES.LIST>
                          <LEDGERNAME>Gold Sales A/c</LEDGERNAME>
                          <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
                          <AMOUNT>300.00</AMOUNT>
                        </ALLLEDGERENTRIES.LIST>
                      </VOUCHER>
                    </TALLYMESSAGE>
                    <TALLYMESSAGE xmlns:UDF="TallyUDF">
                      <VOUCHER REMOTEID="gold-vch-bad-03" VCHTYPE="Sales" ACTION="Create">
                        <GUID>gold-vch-bad-03</GUID>
                        <DATE>20260413</DATE>
                        <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>
                        <VOUCHERNUMBER>GLD-BAD-003</VOUCHERNUMBER>
                        <ALLLEDGERENTRIES.LIST>
                          <LEDGERNAME>Alpha Traders</LEDGERNAME>
                          <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
                          <AMOUNT>-450.00</AMOUNT>
                        </ALLLEDGERENTRIES.LIST>
                        <ALLLEDGERENTRIES.LIST>
                          <LEDGERNAME>Gold Sales A/c</LEDGERNAME>
                          <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
                          <AMOUNT>200.00</AMOUNT>
                        </ALLLEDGERENTRIES.LIST>
                      </VOUCHER>
                    </TALLYMESSAGE>
                  </REQUESTDATA>
                </IMPORTDATA>
              </BODY>
            </ENVELOPE>
            """;

        // 1. STOP_AT_FIRST_EXCEPTION aborts with 422 MC-EXG-UNBALANCED-PAYLOAD
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/imports/xml")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "fileName", "mixed.xml",
                        "rawXml", mixedXml,
                        "exceptionBehavior", "STOP_AT_FIRST_EXCEPTION"
                    ))),
                "gold-exc-stop-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-EXG-UNBALANCED-PAYLOAD"));

        // 2. RECORD_EXCEPTIONS_AND_IMPORT imports the valid 300.00 voucher and records GLD-BAD-003 in exceptions
        MvcResult recRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/imports/xml")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "fileName", "mixed.xml",
                        "rawXml", mixedXml,
                        "exceptionBehavior", "RECORD_EXCEPTIONS_AND_IMPORT"
                    ))),
                "gold-exc-record-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode recBody = objectMapper.readTree(recRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(recBody.get("vouchersImported").asInt()).isEqualTo(1);
        Assertions.assertThat(recBody.get("exceptionsRecorded").asInt()).isEqualTo(1);
        String excId = recBody.get("exceptions").get(0).get("exceptionId").asString();

        // List exceptions via GET /exchange/exceptions
        MvcResult listExcRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + goldCompanyId + "/exchange/exceptions")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode listExcJson = objectMapper.readTree(listExcRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(listExcJson.size()).isGreaterThanOrEqualTo(1);

        // Loop 5 Pre-Resolution Data Verification: pendingImportExceptionCount == 1 -> readyForSplitOrSync == false
        MvcResult verifyBeforeResolve = mockMvc.perform(
            authedGet("/api/v1/companies/" + goldCompanyId + "/exchange/data-verification")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode verifyBeforeJson = objectMapper.readTree(verifyBeforeResolve.getResponse().getContentAsByteArray());
        Assertions.assertThat(verifyBeforeJson.get("pendingImportExceptionCount").asInt()).isEqualTo(1);
        Assertions.assertThat(verifyBeforeJson.get("readyForSplitOrSync").asBoolean()).isFalse();

        // Resolve the exception via POST /exchange/exceptions/{exceptionId}/resolve
        MvcResult resolveRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + goldCompanyId + "/exchange/exceptions/" + excId + "/resolve"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "voucherTypeName", "Sales",
                        "voucherDate", "2026-04-13",
                        "debitLedgerName", "Alpha Traders",
                        "creditLedgerName", "Gold Sales A/c",
                        "amount", "450.00",
                        "narration", "Resolved GLD-BAD-003"
                    ))),
                "gold-exc-resolve-1"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode resolvedNode = objectMapper.readTree(resolveRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(resolvedNode.get("resolved").asBoolean()).isTrue();
        Assertions.assertThat(resolvedNode.get("resolvedEntityId").asString()).isNotBlank();

        // Alpha Traders closing balance is now 2200.00 + 300.00 + 450.00 = 2950.00 DR
        assertLedgerOpeningAndClosing(goldCompanyId, "Alpha Traders", "1400.00", "2950.00");

        // 3. Download Sample Excel Files (Alt+L) and verify round-trip import into a new company
        MvcResult sampleXlsxRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + goldCompanyId + "/exchange/sample-excel?type=ALL")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        byte[] sampleBytes = sampleXlsxRes.getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(sampleBytes))) {
            Assertions.assertThat(wb.getSheet("Ledgers")).isNotNull();
            Assertions.assertThat(wb.getSheet("StockItems")).isNotNull();
            Assertions.assertThat(wb.getSheet("Vouchers")).isNotNull();
        }

        String smpPrefix = "33AABCS7002R1Z";
        UUID sampleTargetCmp = createTestCompany(
            "MCSMP72",
            "Sample Excel Import Target Co",
            "33",
            "2026-04-01",
            smpPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(smpPrefix)
        );
        MvcResult smpImpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + sampleTargetCmp + "/imports/excel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "fileName", "moneycraft-sample-all.xlsx",
                        "excelBase64", Base64.getEncoder().encodeToString(sampleBytes)
                    ))),
                "gold-sample-xlsx-imp"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode smpImpJson = objectMapper.readTree(smpImpRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(smpImpJson.get("vouchersImported").asInt()).isEqualTo(1);
    }

    @Test
    @Order(3)
    void mappingTemplateColumnValidationAllCompaniesScopeRowExclusionNettDrCrAndAsciiHtmlExports() throws Exception {
        // 1. Validate empty mappings -> 422 MC-EXG-TEMPLATE-MAPPING-EMPTY
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exchange/templates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "templateName", "Empty Mapping Template",
                        "entityScope", "MASTERS",
                        "sheetName", "Sheet1",
                        "columnMappings", Map.of()
                    ))),
                "gold-tpl-empty-err"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-EXG-TEMPLATE-MAPPING-EMPTY"));

        // 2. Validate invalid column range (fromColumn = Z, toColumn = A) -> 422 MC-EXG-TEMPLATE-COLUMN-RANGE
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exchange/templates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "templateName", "Inverted Column Template",
                        "entityScope", "MASTERS",
                        "sheetName", "Sheet1",
                        "fromColumn", "Z",
                        "toColumn", "A",
                        "columnMappings", Map.of("name", "Party Name")
                    ))),
                "gold-tpl-colrange-err"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-EXG-TEMPLATE-COLUMN-RANGE"));

        // 3. Create an ALL_COMPANIES template with Row Exclusion (Day CONTAINS Sunday),
        // Fixed Group (Sundry Debtors), and Separate Opening Debit / Credit columns
        MvcResult createSharedTplRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exchange/templates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "templateName": "Shared Nett OB & Exclude Sunday Template",
                          "entityScope": "MASTERS",
                          "sheetName": "PartySheet",
                          "headerRowIndex": 1,
                          "templateScope": "ALL_COMPANIES",
                          "fromColumn": "A",
                          "toColumn": "E",
                          "excludeRowColumn": "Day",
                          "excludeRowOperator": "CONTAINS",
                          "excludeRowValue": "Sunday",
                          "defaultValues": {
                            "groupName": "Sundry Debtors"
                          },
                          "columnMappings": {
                            "name": "Party Name",
                            "openingDebit": "Debit OB",
                            "openingCredit": "Credit OB"
                          }
                        }
                        """
                    ),
                "gold-tpl-shared-create"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        String sharedTplId = objectMapper.readTree(createSharedTplRes.getResponse().getContentAsByteArray())
            .get("id").asString();

        // Create a second company and verify it sees and uses the ALL_COMPANIES template!
        String cmp2Prefix = "33AABCT7003R1Z";
        UUID secondCompanyId = createTestCompany(
            "MCGOLD2",
            "Second Workstation Company",
            "33",
            "2026-04-01",
            cmp2Prefix + ExactMoneyValidator.computeGstinMod36CheckChar(cmp2Prefix)
        );
        MvcResult listSharedOnCmp2 = mockMvc.perform(
            authedGet("/api/v1/companies/" + secondCompanyId + "/exchange/templates")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode cmp2Templates = objectMapper.readTree(listSharedOnCmp2.getResponse().getContentAsByteArray());
        Assertions.assertThat(cmp2Templates.toString()).contains(sharedTplId);

        // Build custom Excel file with 2 rows:
        // Row 1 (Monday): 'Kaveri Agencies', Debit OB = 1500.00, Credit OB = 300.00 -> Nett = 1200.00 DR
        //   (plus Column G index 6 'GSTIN' outside fromColumn=A..toColumn=E, verifying column range bounding!)
        // Row 2 (Sunday - excluded!): 'Sunday Skip Party', Debit OB = 9999.00, Credit OB = 0.00
        byte[] customPartyXlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("PartySheet");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Party Name");
            h.createCell(1).setCellValue("Day");
            h.createCell(2).setCellValue("Debit OB");
            h.createCell(3).setCellValue("Credit OB");
            h.createCell(6).setCellValue("Credit OB");

            Row r1 = s.createRow(1);
            r1.createCell(0).setCellValue("Kaveri Agencies");
            r1.createCell(1).setCellValue("Monday");
            r1.createCell(2).setCellValue("1500.00");
            r1.createCell(3).setCellValue("300.00");
            r1.createCell(6).setCellValue("99999.00");

            Row r2 = s.createRow(2);
            r2.createCell(0).setCellValue("Sunday Skip Party");
            r2.createCell(1).setCellValue("Sunday");
            r2.createCell(2).setCellValue("9999.00");
            r2.createCell(3).setCellValue("0.00");

            wb.write(out);
            customPartyXlsx = out.toByteArray();
        }

        MvcResult mappedImpRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + secondCompanyId + "/imports/excel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "fileName", "party_nett.xlsx",
                        "templateId", sharedTplId,
                        "excelBase64", Base64.getEncoder().encodeToString(customPartyXlsx)
                    ))),
                "gold-mapped-nett-imp"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode mappedImpJson = objectMapper.readTree(mappedImpRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(mappedImpJson.get("ledgersImported").asInt()).isEqualTo(1);
        assertLedgerOpeningAndClosing(secondCompanyId, "Kaveri Agencies", "1200.00", "1200.00");

        // Delete the shared template so no global templates pollute other tests
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.delete(
                    "/api/v1/companies/" + goldCompanyId + "/exchange/templates/" + sharedTplId
                ),
                "gold-tpl-shared-delete"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isNoContent());

        // 4. Test ASCII (.csv) and HTML Web-Publishing exports + Export Configuration flags
        MvcResult asciiRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exports/ascii")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "entityScope", "ALL",
                        "includeNarration", false
                    ))),
                "gold-exp-ascii"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode asciiJson = objectMapper.readTree(asciiRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(asciiJson.get("format").asString()).isEqualTo("ASCII");
        Assertions.assertThat(asciiJson.get("content").asString()).contains("Alpha Traders");
        Assertions.assertThat(asciiJson.get("content").asString()).doesNotContain("Updated Gold Sale 800");

        MvcResult htmlRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exports/html")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "entityScope", "ALL",
                        "includeNarration", true,
                        "digitalSignatureSignerName", "CA Rajesh Kumar (DSC)"
                    ))),
                "gold-exp-html"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode htmlJson = objectMapper.readTree(htmlRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(htmlJson.get("format").asString()).isEqualTo("HTML");
        Assertions.assertThat(htmlJson.get("content").asString())
            .contains("Digital Signature Block:")
            .contains("CA Rajesh Kumar (DSC)")
            .contains("Alpha Traders");
    }

    @Test
    @Order(4)
    void multiUserSecurityLevelRuntimeEnforcementAndClientServerSync() throws Exception {
        // 1. Create a restricted user 'gold_clerk' with:
        //    - backdatedDaysLimit = 5
        //    - voucherTypeAllowlist = ["Sales"]
        //    - exportPermitted = false
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/admin/principals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "username", "gold_clerk",
                        "displayName", "Gold Sales Clerk",
                        "password", "GoldClerk#2026!",
                        "systemRoles", List.of()
                    ))),
                "gold-usr-clerk-create"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/admin/principals/gold_clerk/grants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "companyId", goldCompanyId.toString(),
                        "roleCode", "SENIOR_ACCOUNTANT",
                        "voucherTypeAllowlist", List.of("Sales"),
                        "backdatedDaysLimit", 5,
                        "exportPermitted", false
                    ))),
                "gold-usr-clerk-grant"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        MvcResult clerkLogin = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"gold_clerk\",\"password\":\"GoldClerk#2026!\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Cookie clerkSession = latestCookie(clerkLogin, "MCSESSION");
        Cookie clerkXsrf = latestCookie(clerkLogin, "XSRF-TOKEN");
        String clerkXsrfVal = URLDecoder.decode(clerkXsrf.getValue(), StandardCharsets.UTF_8);

        // A. Attempt export as gold_clerk (exportPermitted = false) -> 403 MC-SEC-EXPORT-FORBIDDEN
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exports/xml")
                .cookie(clerkSession, clerkXsrf)
                .header("X-XSRF-TOKEN", clerkXsrfVal)
                .header("Idempotency-Key", "clerk-exp-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entityScope\":\"ALL\"}")
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SEC-EXPORT-FORBIDDEN"));

        UUID alphaId = findLedgerIdByName(goldCompanyId, "Alpha Traders");
        UUID salesId = findLedgerIdByName(goldCompanyId, "Gold Sales A/c");

        // B. Attempt to create a 'Journal' voucher (not in voucherTypeAllowlist = ["Sales"]) -> 403 MC-SEC-VOUCHER-TYPE-FORBIDDEN
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/vouchers")
                .cookie(clerkSession, clerkXsrf)
                .header("X-XSRF-TOKEN", clerkXsrfVal)
                .header("Idempotency-Key", "clerk-vtype-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "voucherTypeName", "Journal",
                    "voucherDate", "2026-04-12",
                    "persistedView", "AS_VOUCHER",
                    "postingNature", "NORMAL",
                    "action", "POST",
                    "lines", List.of(
                        Map.of("lineNo", 1, "lineRole", "LEDGER", "ledgerId", alphaId.toString(), "side", "DR", "amount", "100.00"),
                        Map.of("lineNo", 2, "lineRole", "LEDGER", "ledgerId", salesId.toString(), "side", "CR", "amount", "100.00")
                    )
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SEC-VOUCHER-TYPE-FORBIDDEN"));

        // C. Attempt to create a 'Sales' voucher dated 2026-04-01 (12 days before latest 2026-04-13 > limit 5)
        // -> 403 MC-SEC-BACKDATED-LIMIT-EXCEEDED
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/vouchers")
                .cookie(clerkSession, clerkXsrf)
                .header("X-XSRF-TOKEN", clerkXsrfVal)
                .header("Idempotency-Key", "clerk-backdate-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "voucherTypeName", "Sales",
                    "voucherDate", "2026-04-01",
                    "persistedView", "AS_VOUCHER",
                    "postingNature", "NORMAL",
                    "action", "POST",
                    "lines", List.of(
                        Map.of("lineNo", 1, "lineRole", "LEDGER", "ledgerId", alphaId.toString(), "side", "DR", "amount", "100.00"),
                        Map.of("lineNo", 2, "lineRole", "LEDGER", "ledgerId", salesId.toString(), "side", "CR", "amount", "100.00")
                    )
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SEC-BACKDATED-LIMIT-EXCEEDED"));

        // D. Create a 'Sales' voucher dated 2026-04-11 (2 days before 2026-04-13 <= limit 5) -> 201 Created!
        MvcResult clerkCreatedRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/vouchers")
                .cookie(clerkSession, clerkXsrf)
                .header("X-XSRF-TOKEN", clerkXsrfVal)
                .header("Idempotency-Key", "clerk-sales-allowed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "voucherTypeName", "Sales",
                    "voucherDate", "2026-04-11",
                    "persistedView", "AS_VOUCHER",
                    "postingNature", "NORMAL",
                    "action", "POST",
                    "lines", List.of(
                        Map.of("lineNo", 1, "lineRole", "LEDGER", "ledgerId", alphaId.toString(), "side", "DR", "amount", "100.00"),
                        Map.of("lineNo", 2, "lineRole", "LEDGER", "ledgerId", salesId.toString(), "side", "CR", "amount", "100.00")
                    )
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        String clerkVoucherId = objectMapper.readTree(clerkCreatedRes.getResponse().getContentAsByteArray())
            .get("id").asString();

        // E. Loop 4 Alteration RBAC Parity: gold_clerk attempting to alter (`PUT`) an existing Sales voucher
        // to back-dated date 2026-04-01 (12 days before 2026-04-13 > limit 5) -> 403 MC-SEC-BACKDATED-LIMIT-EXCEEDED
        mockMvc.perform(
            MockMvcRequestBuilders.put("/api/v1/companies/" + goldCompanyId + "/vouchers/" + clerkVoucherId)
                .cookie(clerkSession, clerkXsrf)
                .header("X-XSRF-TOKEN", clerkXsrfVal)
                .header("Idempotency-Key", "clerk-alter-backdate-denied")
                .header("If-Match", "\"v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "voucherTypeName", "Sales",
                    "voucherDate", "2026-04-01",
                    "persistedView", "AS_VOUCHER",
                    "postingNature", "NORMAL",
                    "action", "POST",
                    "lines", List.of(
                        Map.of("lineNo", 1, "lineRole", "LEDGER", "ledgerId", alphaId.toString(), "side", "DR", "amount", "100.00"),
                        Map.of("lineNo", 2, "lineRole", "LEDGER", "ledgerId", salesId.toString(), "side", "CR", "amount", "100.00")
                    )
                )))
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SEC-BACKDATED-LIMIT-EXCEEDED"));

        // 2. Client/Server Data Synchronization (Alt+Z > Client/Server)
        // A. Reject self-sync when targetCompanyGuid == source companyId -> 422 MC-SYNC-SAME-COMPANY-GUID
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exchange/sync-rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "ruleName", "Invalid Self Sync Rule",
                        "ruleType", "FULL_SYNC",
                        "direction", "BOTH_WAYS",
                        "targetCompanyId", goldCompanyId.toString(),
                        "targetCompanyGuid", goldCompanyId.toString(),
                        "targetCompanyName", "Self Co"
                    ))),
                "gold-sync-self-err"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SYNC-SAME-COMPANY-GUID"));

        // B. Create valid Sync Rule between goldCompanyId and a fresh Branch Company, then execute sync
        String branchPrefix = "33AABCB7004R1Z";
        UUID branchCompanyId = createTestCompany(
            "MCBRANCH",
            "Branch Sync Target Co",
            "33",
            "2026-04-01",
            branchPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(branchPrefix)
        );

        MvcResult createRuleRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exchange/sync-rules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "ruleName", "HO to Branch Full Sync",
                        "ruleType", "FULL_SYNC",
                        "direction", "SEND_ONLY",
                        "targetCompanyId", branchCompanyId.toString(),
                        "targetCompanyGuid", branchCompanyId.toString(),
                        "targetCompanyName", "Branch Sync Target Co",
                        "masterDuplicateRule", "MODIFY_WITH_NEW_DATA",
                        "overwriteVoucherByGuid", true
                    ))),
                "gold-sync-rule-create"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        String syncRuleId = objectMapper.readTree(createRuleRes.getResponse().getContentAsByteArray())
            .get("id").asString();

        MvcResult execSyncRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post(
                    "/api/v1/companies/" + goldCompanyId + "/exchange/sync-rules/" + syncRuleId + "/execute"
                ),
                "gold-sync-rule-exec"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode syncSessionJson = objectMapper.readTree(execSyncRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(syncSessionJson.get("status").asString()).isEqualTo("COMPLETED");
        Assertions.assertThat(syncSessionJson.get("vouchersSynced").asInt()).isEqualTo(4);
        Assertions.assertThat(syncSessionJson.get("newAlterIdWatermark").asLong()).isGreaterThan(0L);

        // Verify Branch company now has identical Alpha Traders opening (1400.00) and closing (3050.00) balances!
        assertLedgerOpeningAndClosing(branchCompanyId, "Alpha Traders", "1400.00", "3050.00");
    }

    @Test
    @Order(5)
    void fiveLoopDeepDiveCreditLimitMultiAccountEditLogDashboardTilesAndDataVerification() throws Exception {
        // Loop 1 (VM-L1-001): Party Credit Limit Enforcement on Sales Vouchers
        UUID sundryDebtorsGroupId = findGroupIdByName(goldCompanyId, "Sundry Debtors");
        UUID salesLedgerId = findLedgerIdByName(goldCompanyId, "Gold Sales A/c");

        MvcResult cappedLedgerRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/ledgers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Credit Capped Party",
                          "groupId": "%s",
                          "openingBalance": "0.00",
                          "openingSide": "DR",
                          "checkCreditDaysDuringVoucher": true,
                          "creditLimit": "2000.00"
                        }
                        """.formatted(sundryDebtorsGroupId)
                    ),
                "loop1-capped-ledger"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        UUID cappedPartyId = UUID.fromString(
            objectMapper.readTree(cappedLedgerRes.getResponse().getContentAsByteArray()).get("id").asString()
        );

        // 1a. Voucher exceeding 2,000.00 credit limit -> 422 MC-VOUCHER-CREDIT-LIMIT-EXCEEDED
        MvcResult exceedRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "voucherTypeName", "Sales",
                        "voucherDate", "2026-04-14",
                        "persistedView", "AS_VOUCHER",
                        "postingNature", "NORMAL",
                        "action", "POST",
                        "lines", List.of(
                            Map.of(
                                "lineNo", 1,
                                "lineRole", "LEDGER",
                                "ledgerId", cappedPartyId.toString(),
                                "side", "DR",
                                "amount", "2500.00"
                            ),
                            Map.of(
                                "lineNo", 2,
                                "lineRole", "LEDGER",
                                "ledgerId", salesLedgerId.toString(),
                                "side", "CR",
                                "amount", "2500.00"
                            )
                        )
                    ))),
                "loop1-exceed-vch"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andReturn();
        Assertions.assertThat(
            objectMapper.readTree(exceedRes.getResponse().getContentAsByteArray()).path("code").asString()
        ).isEqualTo("MC-VOUCHER-CREDIT-LIMIT-EXCEEDED");

        // 1b. Voucher within 2,000.00 credit limit (1,500.00) -> 201 Created
        MvcResult withinVchRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "voucherTypeName", "Sales",
                        "voucherDate", "2026-04-14",
                        "persistedView", "AS_VOUCHER",
                        "postingNature", "NORMAL",
                        "action", "POST",
                        "lines", List.of(
                            Map.of(
                                "lineNo", 1,
                                "lineRole", "LEDGER",
                                "ledgerId", cappedPartyId.toString(),
                                "side", "DR",
                                "amount", "1500.00"
                            ),
                            Map.of(
                                "lineNo", 2,
                                "lineRole", "LEDGER",
                                "ledgerId", salesLedgerId.toString(),
                                "side", "CR",
                                "amount", "1500.00"
                            )
                        )
                    ))),
                "loop1-within-vch"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        String withinVchId = objectMapper.readTree(withinVchRes.getResponse().getContentAsByteArray())
            .get("id").asString();

        // 1c. Loop 2 Voucher Alteration Credit Limit Parity:
        // Altering existing Sales voucher from 1,500.00 to 2,500.00 (> 2,000.00 credit limit) -> 422 MC-VOUCHER-CREDIT-LIMIT-EXCEEDED
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.put("/api/v1/companies/" + goldCompanyId + "/vouchers/" + withinVchId)
                    .header("If-Match", "\"v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "voucherTypeName", "Sales",
                        "voucherDate", "2026-04-14",
                        "persistedView", "AS_VOUCHER",
                        "postingNature", "NORMAL",
                        "action", "POST",
                        "lines", List.of(
                            Map.of(
                                "lineNo", 1,
                                "lineRole", "LEDGER",
                                "ledgerId", cappedPartyId.toString(),
                                "side", "DR",
                                "amount", "2500.00"
                            ),
                            Map.of(
                                "lineNo", 2,
                                "lineRole", "LEDGER",
                                "ledgerId", salesLedgerId.toString(),
                                "side", "CR",
                                "amount", "2500.00"
                            )
                        )
                    ))),
                "loop2-alter-exceed-vch"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-VOUCHER-CREDIT-LIMIT-EXCEEDED"));

        // 1d. Loop 2 Bill-Wise Due Date < Voucher Date Guard -> 422 MC-ACC-BILL-DUE-DATE-BEFORE-VOUCHER-DATE
        UUID alphaId = findLedgerIdByName(goldCompanyId, "Alpha Traders");
        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "voucherTypeName", "Sales",
                        "voucherDate", "2026-04-14",
                        "persistedView", "AS_VOUCHER",
                        "postingNature", "NORMAL",
                        "action", "POST",
                        "lines", List.of(
                            Map.of(
                                "lineNo", 1,
                                "lineRole", "LEDGER",
                                "ledgerId", alphaId.toString(),
                                "side", "DR",
                                "amount", "500.00",
                                "billAllocations", List.of(
                                    Map.of(
                                        "allocationType", "NEW_REF",
                                        "billName", "BAD-DUE-001",
                                        "dueDate", "2026-04-01",
                                        "side", "DR",
                                        "amount", "500.00"
                                    )
                                )
                            ),
                            Map.of(
                                "lineNo", 2,
                                "lineRole", "LEDGER",
                                "ledgerId", salesLedgerId.toString(),
                                "side", "CR",
                                "amount", "500.00"
                            )
                        )
                    ))),
                "loop2-bill-duedate-err"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-BILL-DUE-DATE-BEFORE-VOUCHER-DATE"));

        // 1e. Loop 2 Duplicate Cost Centre in Cost Allocation Guard (`0x1410908d0`) -> 422 MC-ACC-DUPLICATE-COST-CENTRE
        MvcResult ccRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/cost-centres")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Chennai Branch CC\"}"),
                "loop2-create-cc"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        String ccId = objectMapper.readTree(ccRes.getResponse().getContentAsByteArray()).get("id").asString();

        mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/vouchers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "voucherTypeName", "Journal",
                        "voucherDate", "2026-04-14",
                        "persistedView", "AS_VOUCHER",
                        "postingNature", "NORMAL",
                        "action", "POST",
                        "lines", List.of(
                            Map.of(
                                "lineNo", 1,
                                "lineRole", "LEDGER",
                                "ledgerId", alphaId.toString(),
                                "side", "DR",
                                "amount", "400.00",
                                "costAllocations", List.of(
                                    Map.of("costCentreId", ccId, "side", "DR", "amount", "200.00"),
                                    Map.of("costCentreId", ccId, "side", "DR", "amount", "200.00")
                                )
                            ),
                            Map.of(
                                "lineNo", 2,
                                "lineRole", "LEDGER",
                                "ledgerId", salesLedgerId.toString(),
                                "side", "CR",
                                "amount", "400.00"
                            )
                        )
                    ))),
                "loop2-dup-cc-err"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-ACC-DUPLICATE-COST-CENTRE"));

        // Loop 2 (VM-L2-001): Multi-Account Export (Confirmation of Accounts & Reminder Letter)
        MvcResult multiAccRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exports/multi-account-report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "reportType", "CONFIRMATION_OF_ACCOUNTS",
                        "fromDate", "2026-04-01",
                        "toDate", "2027-03-31",
                        "format", "HTML"
                    ))),
                "loop2-multi-acc-html"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode multiAccJson = objectMapper.readTree(multiAccRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(multiAccJson.get("partyCount").asInt()).isGreaterThanOrEqualTo(2);
        Assertions.assertThat(multiAccJson.get("renderedContent").asString())
            .contains(
                "CONFIRMATION_OF_ACCOUNTS",
                "Alpha Traders",
                "Credit Capped Party",
                "3050.00",
                "1500.00",
                "31-60 Days",
                "61-90 Days",
                "&gt;90 Days"
            );

        MvcResult reminderAsciiRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exports/multi-account-report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "reportType", "REMINDER_LETTER",
                        "fromDate", "2026-04-01",
                        "toDate", "2027-03-31",
                        "format", "ASCII"
                    ))),
                "loop5-reminder-ascii"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode reminderAsciiJson = objectMapper.readTree(reminderAsciiRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(reminderAsciiJson.get("renderedContent").asString())
            .contains("SLAB_0_30,SLAB_31_60,SLAB_61_90,SLAB_OVER_90", "Alpha Traders");

        // Loop 3 & Loop 4 (VM-L3-001 / VM-L4-001): TallyPrime Gold Edit Log & TallyAudit Activity Filter (including STOCK_ITEM & username)
        MvcResult editLogRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + goldCompanyId + "/exchange/edit-log-summary?entityType=ALL&onlyAltered=true")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode editLogJson = objectMapper.readTree(editLogRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(editLogJson.get("onlyAltered").asBoolean()).isTrue();
        Assertions.assertThat(editLogJson.get("alteredItems").asInt()).isGreaterThanOrEqualTo(1);
        Assertions.assertThat(editLogJson.get("items").size()).isGreaterThanOrEqualTo(1);

        MvcResult stockItemEditLogRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + goldCompanyId
                    + "/exchange/edit-log-summary?entityType=STOCK_ITEM&onlyAltered=true&username=owner"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode stockItemEditLogJson = objectMapper.readTree(stockItemEditLogRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(stockItemEditLogJson.get("entityTypeFilter").asString()).isEqualTo("STOCK_ITEM");
        Assertions.assertThat(stockItemEditLogJson.get("items").size()).isEqualTo(1);
        Assertions.assertThat(stockItemEditLogJson.get("items").get(0).get("displayName").asString())
            .isEqualTo("Gold Widget");
        Assertions.assertThat(stockItemEditLogJson.get("items").get(0).get("versionCount").asInt())
            .isGreaterThanOrEqualTo(2);
        Assertions.assertThat(stockItemEditLogJson.get("items").get(0).get("lastModifiedBy").asString())
            .isEqualTo("owner");

        // Loop 4 (VM-L4-001): Dashboard Tiles & Statutory Diagnostics Export
        MvcResult tilesRes = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies/" + goldCompanyId + "/exports/dashboard-tiles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "fromDate", "2026-04-01",
                        "toDate", "2027-03-31",
                        "format", "HTML"
                    ))),
                "loop4-dashboard-tiles"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode tilesJson = objectMapper.readTree(tilesRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(tilesJson.get("totalTiles").asInt()).isEqualTo(5);
        Assertions.assertThat(tilesJson.get("renderedContent").asString())
            .contains("Trial Balance Summary", "Receivables &amp; Payables", "GST Liability &amp; Uncertain Transactions");

        // Loop 5 (VM-L5-001): Company Data Verification & Pre-Split/Sync Exception Audit
        MvcResult verifyRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + goldCompanyId + "/exchange/data-verification")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode verifyJson = objectMapper.readTree(verifyRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(verifyJson.get("trialBalanceDifference").asString()).isEqualTo("0.00");
        Assertions.assertThat(verifyJson.get("unbalancedVoucherCount").asInt()).isZero();
        Assertions.assertThat(verifyJson.get("unitMismatchCount").asInt()).isZero();
        Assertions.assertThat(verifyJson.get("pendingImportExceptionCount").asInt()).isZero();
        Assertions.assertThat(verifyJson.get("readyForSplitOrSync").asBoolean()).isTrue();
        Assertions.assertThat(verifyJson.get("checks").size()).isEqualTo(4);
    }

    private UUID findGroupIdByName(UUID companyId, String name) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/account-groups?limit=100")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode items = objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("items");
        for (JsonNode item : items) {
            if (name.equalsIgnoreCase(item.get("name").asString())) {
                return UUID.fromString(item.get("id").asString());
            }
        }
        throw new IllegalStateException("Group not found: " + name);
    }

    private void assertLedgerOpeningAndClosing(
        UUID companyId,
        String ledgerName,
        String expectedOpening,
        String expectedClosing
    ) throws Exception {
        MvcResult tbRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId
                    + "/reports/trial-balance?fromDate=2026-04-01&toDate=2027-03-31&includeLedgers=true"
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode tbJson = objectMapper.readTree(tbRes.getResponse().getContentAsByteArray());
        JsonNode rows = tbJson.has("ledgerRows") ? tbJson.get("ledgerRows") : tbJson.get("groupRows");
        JsonNode match = findTbRow(rows, ledgerName);
        if (match == null) {
            match = findTbRow(tbJson.get("groupRows"), ledgerName);
        }
        Assertions.assertThat(match)
            .withFailMessage("Expected ledger '%s' in Trial Balance for company %s", ledgerName, companyId)
            .isNotNull();
        Assertions.assertThat(match.get("openingDebit").asString()).isEqualTo(expectedOpening);
        Assertions.assertThat(match.get("closingDebit").asString()).isEqualTo(expectedClosing);
    }

    private JsonNode findTbRow(JsonNode rows, String name) {
        if (rows == null || !rows.isArray()) {
            return null;
        }
        for (JsonNode r : rows) {
            if (name.equalsIgnoreCase(r.path("name").asString())) {
                return r;
            }
            JsonNode child = findTbRow(r.get("children"), name);
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    private UUID findLedgerIdByName(UUID companyId, String name) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/ledgers?limit=200")
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode items = objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("items");
        for (JsonNode item : items) {
            if (name.equalsIgnoreCase(item.get("name").asString())) {
                return UUID.fromString(item.get("id").asString());
            }
        }
        throw new IllegalStateException("Ledger not found: " + name);
    }

    private UUID createTestCompany(
        String code,
        String legalName,
        String stateCode,
        String fyStart,
        String gstin
    ) throws Exception {
        MvcResult res = mockMvc.perform(
            authedMutate(
                MockMvcRequestBuilders.post("/api/v1/companies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "code": "%s",
                          "legalName": "%s",
                          "mailingName": "%s",
                          "stateCode": "%s",
                          "stateName": "Tamil Nadu",
                          "countryCode": "IN",
                          "baseCurrencyCode": "INR",
                          "baseCurrencySymbol": "₹",
                          "currencyScale": 2,
                          "financialYearFrom": "%s",
                          "booksBeginningFrom": "%s",
                          "gstin": "%s"
                        }
                        """.formatted(code, legalName, legalName, stateCode, fyStart, fyStart, gstin)
                    ),
                "create-cmp-" + code + "-" + UUID.randomUUID()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString()
        );
    }

    private MockHttpServletRequestBuilder authedGet(String url) {
        return MockMvcRequestBuilders.get(url).cookie(ownerSessionCookie, ownerXsrfCookie);
    }

    private MockHttpServletRequestBuilder authedMutate(MockHttpServletRequestBuilder builder, String idempotencyKey) {
        String xsrf = URLDecoder.decode(ownerXsrfCookie.getValue(), StandardCharsets.UTF_8);
        return builder
            .cookie(ownerSessionCookie, ownerXsrfCookie)
            .header("X-XSRF-TOKEN", xsrf)
            .header("Idempotency-Key", idempotencyKey);
    }

    private static Cookie latestCookie(MvcResult result, String name) {
        Cookie[] cookies = result.getResponse().getCookies();
        Cookie found = null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                found = c;
            }
        }
        return found;
    }
}
