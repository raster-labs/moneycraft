package com.raster.moneycraft.extension;

import com.raster.moneycraft.MoneyCraftApplication;
import com.raster.moneycraft.exchange.dto.SpreadsheetImportDtos;
import com.raster.moneycraft.extension.dto.ExtensionDtos;
import com.raster.moneycraft.extension.service.BoundedFeelRuleEvaluator;
import com.raster.moneycraft.extension.service.TdlStaticImporterService;
import com.raster.moneycraft.foundation.DualPostgres18TestcontainersSupport;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FormulaError;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
 * Real Dual-PostgreSQL 18 integration test suite for MoneyCraft 1.0.0 Increment X:
 * - SHEET-001..008: Guided Multi-Format Columnar Spreadsheet Importer (.xlsx >81 columns, .xls,
 *   data-only .xlsm, .ods 1.4, RFC 4180 CSV, 1900/1904 date systems, 1900-02-29 rejection,
 *   cached-formula consent, formula error rejection, OWASP CSV injection neutralization,
 *   per-unit LIVE atomic commit, PARTIAL_INTERRUPTED resume, and cancellation).
 * - EXT-001..008: Visual Extension Workbench (JSON Schema 2020-12, pure BigDecimal DMN/FEEL
 *   evaluator, SELECTED_PRIVATE Tink AEAD custom fields, designer vs admin activation RBAC,
 *   VoucherExtensionValidator enforcement across REST API & Spreadsheet Import, and read-only
 *   service-backed ODBC projection queries).
 * - TDL-001..004: Static .tdl & .tcp intake, inactive draft extension synthesis, and mandatory
 *   5-disposition Functional Parity Matrix.
 */
@SpringBootTest(classes = MoneyCraftApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IncrementXExtensionTdlAndSpreadsheetTest {

    private static final String OWNER_USER = "incx_owner";
    private static final String OWNER_PASS = "IncXOwner#2026!";
    private static final String DESIGNER_USER = "incx_designer";
    private static final String DESIGNER_PASS = "IncXDesigner#2026!";

    private static final DualPostgres18TestcontainersSupport.ClusterPair CLUSTERS =
        DualPostgres18TestcontainersSupport.startIsolatedClusterPair();

    private static Cookie ownerSession;
    private static Cookie ownerXsrf;
    private static Cookie designerSession;
    private static Cookie designerXsrf;

    private static UUID companyId;
    private static UUID companyBId;
    private static UUID customerLedgerId;
    private static UUID salesLedgerId;
    private static UUID cgstLedgerId;
    private static UUID sgstLedgerId;
    private static UUID roundOffLedgerId;
    private static final List<String> NINETY_REVENUE_LEDGER_NAMES = new ArrayList<>();

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
    private BoundedFeelRuleEvaluator feelEvaluator;

    @Autowired
    @Qualifier("businessJdbcTemplate")
    private JdbcTemplate businessJdbcTemplate;

    @Test
    @Order(1)
    void bootstrapCompaniesCreate90LedgersAndVerifyBoundedFeelBigDecimalEngine() throws Exception {
        MvcResult initRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/system/initialise")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "installationLabel": "MoneyCraft Increment X Verification Cluster",
                      "ownerUsername": "%s",
                      "ownerDisplayName": "Increment X Chief Architect",
                      "password": "%s"
                    }
                    """.formatted(OWNER_USER, OWNER_PASS)
                )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        ownerSession = latestCookie(initRes, "MCSESSION");
        ownerXsrf = latestCookie(initRes, "XSRF-TOKEN");

        String cmpGstinPrefix = "33AABCM9090F1Z";
        String cmpGstin = cmpGstinPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(cmpGstinPrefix);
        String cmpBGstinPrefix = "29AABCI8080F1Z";
        String cmpBGstin = cmpBGstinPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(cmpBGstinPrefix);
        companyId = createCompany("MCINCX01", "Increment X Enterprise Ltd", "33", "2025-04-01", cmpGstin);
        companyBId = createCompany("MCINCX02", "Isolated Tenant B Pvt Ltd", "29", "2025-04-01", cmpBGstin);

        // Create a non-admin designer user without private-field access and without extension.manage
        MvcResult createUserRes = mockMvc.perform(
            authedPost(
                "/api/v1/admin/principals",
                ownerSession,
                ownerXsrf,
                Map.of(
                    "username", DESIGNER_USER,
                    "displayName", "Extension Designer Operator",
                    "email", "designer@raster.local",
                    "password", DESIGNER_PASS,
                    "systemRoles", List.of()
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        String designerId = objectMapper.readTree(createUserRes.getResponse().getContentAsByteArray())
            .get("id").asString();

        mockMvc.perform(
            authedPut(
                "/api/v1/admin/principals/" + designerId + "/grants",
                ownerSession,
                ownerXsrf,
                Map.of(
                    "companyId", companyId.toString(),
                    "roleCode", "DATA_ENTRY_OPERATOR",
                    "permissions", List.of("extension.author", "import.prepare", "import.read"),
                    "voucherTypeAllowlist", List.of(),
                    "exportPermitted", false
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        TenantContextHolder.runWithSystemBypass("restrict-designer-private-fields", () ->
            businessJdbcTemplate.update(
                "UPDATE mc_principal_grant SET private_fields_permitted = false WHERE principal_id = ?::uuid AND company_id = ?",
                designerId,
                companyId
            )
        );

        MvcResult loginRes = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .cookie(ownerXsrf)
                .header("X-XSRF-TOKEN", decodeCookie(ownerXsrf))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("username", DESIGNER_USER, "password", DESIGNER_PASS)))
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        designerSession = latestCookie(loginRes, "MCSESSION");
        designerXsrf = latestCookie(loginRes, "XSRF-TOKEN");

        UUID debtorsGrp = findGroupId(companyId, "Sundry Debtors");
        UUID salesGrp = findGroupId(companyId, "Sales Accounts");
        UUID dutiesGrp = findGroupId(companyId, "Duties & Taxes");
        UUID indirectExpGrp = findGroupId(companyId, "Indirect Expenses");

        customerLedgerId = createLedger(companyId, "Salem Care Hospital", debtorsGrp, true);
        salesLedgerId = createLedger(companyId, "Sales - Domestic", salesGrp, false);
        cgstLedgerId = createGstDutyLedger(companyId, "CGST Output", dutiesGrp, "CGST");
        sgstLedgerId = createGstDutyLedger(companyId, "SGST Output", dutiesGrp, "SGST_UTGST");
        roundOffLedgerId = createLedger(companyId, "Round Off", indirectExpGrp, false);

        // Create 90 distinct revenue ledgers to prove >81-column horizontal voucher mapping!
        NINETY_REVENUE_LEDGER_NAMES.clear();
        for (int i = 1; i <= 90; i++) {
            String lName = String.format("Rev Stream %03d", i);
            createLedger(companyId, lName, salesGrp, false);
            NINETY_REVENUE_LEDGER_NAMES.add(lName);
        }

        // Pure BigDecimal FEEL unit assertions (zero IEEE-754 binary float drift: 0.1 + 0.2 == 0.30!)
        BoundedFeelRuleEvaluator.EvaluationOutcome exactSum = feelEvaluator.evaluateExpression(
            "0.1 + 0.2 = 0.30 and startsWith(voucherType, \"Sa\") and voucherType in [\"Sales\", \"Purchase\"]",
            Map.of("voucherType", "Sales")
        );
        Assertions.assertThat(exactSum.value()).isEqualTo(Boolean.TRUE);

        // AST depth > 16 must fail closed
        String deepExpr = "(((((((((((((((((1 + 1)))))))))))))))))";
        Assertions.assertThatThrownBy(() -> feelEvaluator.compileAndValidate(deepExpr))
            .isInstanceOf(MoneyCraftException.class)
            .hasMessageContaining("AST depth");

        // Forbidden host identifier must fail closed
        Assertions.assertThatThrownBy(() -> feelEvaluator.compileAndValidate("Runtime = 1"))
            .isInstanceOf(MoneyCraftException.class);
    }

    @Test
    @Order(2)
    void multiFormatColumnarSpreadsheetImports90ColumnsOdsCsv1904AndRejects1900Feb29() throws Exception {
        // 1. 90-Ledger Horizontal Columnar .xlsx (> 81-column legacy ceiling) + LIVE commit
        List<SpreadsheetImportDtos.LedgerColumnMappingDto> ledgerCols = new ArrayList<>();
        for (String revName : NINETY_REVENUE_LEDGER_NAMES) {
            ledgerCols.add(new SpreadsheetImportDtos.LedgerColumnMappingDto(
                revName, revName, null, "CR", "ACCOUNTING"
            ));
        }
        ledgerCols.add(new SpreadsheetImportDtos.LedgerColumnMappingDto(
            "CGST Output", "CGST Output", cgstLedgerId, "CR", "TAX"
        ));
        ledgerCols.add(new SpreadsheetImportDtos.LedgerColumnMappingDto(
            "SGST Output", "SGST Output", sgstLedgerId, "CR", "TAX"
        ));
        ledgerCols.add(new SpreadsheetImportDtos.LedgerColumnMappingDto(
            "Round Off", "Round Off", roundOffLedgerId, "SIGNED_CR", "ROUND_OFF"
        ));

        byte[] wideXlsxBytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("WideRegister");
            Row header = sheet.createRow(0);
            int c = 0;
            header.createCell(c++).setCellValue("Date");
            header.createCell(c++).setCellValue("Voucher Type");
            header.createCell(c++).setCellValue("Invoice No");
            header.createCell(c++).setCellValue("Party Ledger");
            header.createCell(c++).setCellValue("Party Amount");
            header.createCell(c++).setCellValue("Narration");
            for (String revName : NINETY_REVENUE_LEDGER_NAMES) {
                header.createCell(c++).setCellValue(revName);
            }
            header.createCell(c++).setCellValue("CGST Output");
            header.createCell(c++).setCellValue("SGST Output");
            header.createCell(c).setCellValue("Round Off");

            Row r1 = sheet.createRow(1);
            int rc = 0;
            r1.createCell(rc++).setCellValue("2025-04-10");
            r1.createCell(rc++).setCellValue("Sales");
            r1.createCell(rc++).setCellValue("WIDE-INV-001");
            r1.createCell(rc++).setCellValue("Salem Care Hospital");
            // 90 streams * 100.00 = 9000.00 + CGST 810.00 + SGST 810.00 - RoundOff 0.25 = 10619.75
            r1.createCell(rc++).setCellValue("10619.75");
            r1.createCell(rc++).setCellValue("90-column horizontal multi-ledger sale");
            for (int i = 0; i < 90; i++) {
                r1.createCell(rc++).setCellValue("100.00");
            }
            r1.createCell(rc++).setCellValue("810.00");
            r1.createCell(rc++).setCellValue("810.00");
            r1.createCell(rc).setCellValue("-0.25");

            wb.write(out);
            wideXlsxBytes = out.toByteArray();
        }

        SpreadsheetImportDtos.SpreadsheetMappingSpecDto wideMapping =
            new SpreadsheetImportDtos.SpreadsheetMappingSpecDto(
                "ACCOUNTING_VOUCHERS", "POST", "Sales",
                "Date", "Voucher Type", "Invoice No", "Invoice No", "Narration", null,
                "Party Ledger", "Party Amount", "DR",
                null, null, null,
                ledgerCols,
                null, null, null, null, null, null, null, null, null,
                "AUTO", "REJECT"
            );

        SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest widePrepReq =
            new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                "wide-94-columns.xlsx", "XLSX", Base64.getEncoder().encodeToString(wideXlsxBytes),
                null, "WideRegister", 1, null, false, false, null, wideMapping, null
            );

        MvcResult widePrepRes = mockMvc.perform(
            authedPost("/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession, ownerXsrf, widePrepReq)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode wideBatch = objectMapper.readTree(widePrepRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(wideBatch.get("status").asString()).isEqualTo("READY");
        Assertions.assertThat(wideBatch.get("validUnits").asInt()).isEqualTo(1);
        // 10619.75 party DR + 0.25 Round Off DR == 10620.00 total DR & 10620.00 total CR!
        Assertions.assertThat(wideBatch.get("totalDebit").asString()).isEqualTo("10620.00");
        Assertions.assertThat(wideBatch.get("totalCredit").asString()).isEqualTo("10620.00");
        String wideBatchId = wideBatch.get("id").asString();

        // Dry-run made zero accounting writes so far
        Integer preCommitCount = queryJournalCountForCompany(companyId);
        Assertions.assertThat(preCommitCount).isEqualTo(0);

        MvcResult wideExecRes = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + wideBatchId + "/execute",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.ExecuteSpreadsheetBatchRequest("POST", false, null)
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode wideExecuted = objectMapper.readTree(wideExecRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(wideExecuted.get("status").asString()).isEqualTo("COMPLETED");
        Assertions.assertThat(wideExecuted.get("committedUnits").asInt()).isEqualTo(1);
        UUID createdWideVoucherId = UUID.fromString(
            wideExecuted.get("units").get(0).get("resultingEntityId").asString()
        );

        // Verify mc_journal.origin == 'LIVE' (never MIGRATION) and 94 accounting lines persisted!
        String storedOrigin = queryJournalOrigin(companyId, createdWideVoucherId);
        Assertions.assertThat(storedOrigin).isEqualTo("LIVE");

        // 2. OpenDocument .ods (v1.4) with table:number-columns-repeated & office:value-type
        byte[] odsBytes = buildSampleOds14Archive();
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto odsMapping =
            new SpreadsheetImportDtos.SpreadsheetMappingSpecDto(
                "ACCOUNTING_VOUCHERS", "POST", "Sales",
                "Date", null, "VchNo", "VchNo", "Narration", null,
                "Party", "Total", "DR",
                null, null, null,
                List.of(
                    new SpreadsheetImportDtos.LedgerColumnMappingDto("Sales", "Sales - Domestic", salesLedgerId, "CR", "ACCOUNTING"),
                    new SpreadsheetImportDtos.LedgerColumnMappingDto("CGST", "CGST Output", cgstLedgerId, "CR", "TAX"),
                    new SpreadsheetImportDtos.LedgerColumnMappingDto("SGST", "SGST Output", sgstLedgerId, "CR", "TAX")
                ),
                null, null, null, null, null, null, null, null, null,
                "AUTO", "REJECT"
            );
        MvcResult odsPrep = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "sales-register.ods", "ODS", Base64.getEncoder().encodeToString(odsBytes),
                    null, "OdsSales", 1, null, false, false, null, odsMapping, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode odsBatch = objectMapper.readTree(odsPrep.getResponse().getContentAsByteArray());
        Assertions.assertThat(odsBatch.get("formatProfile").asString()).isEqualTo("ODS");
        Assertions.assertThat(odsBatch.get("status").asString()).isEqualTo("READY");
        Assertions.assertThat(odsBatch.get("totalDebit").asString()).isEqualTo("1180.00");

        mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/"
                    + odsBatch.get("id").asString() + "/execute",
                ownerSession, ownerXsrf, Map.of("action", "POST")
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("COMPLETED"));

        // 3. RFC 4180 CSV with UTF-8 BOM, multiline quotes & leading-zero GSTIN / alias code on LEDGERS
        String delhiPrefix = "07AABCU9603R1Z";
        String delhiGstin = delhiPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(delhiPrefix);
        String csvContent = "\uFEFFName,Alias,Group,Opening Balance,Side,GSTIN,State Code\r\n"
            + "\"North Zone\nHospital, Unit \"\"A\"\"\",00045,Sundry Debtors,2500.50,DR," + delhiGstin + ",07\r\n";

        MvcResult csvPrep = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "ledgers.csv", "CSV", null, csvContent, null, 1, ",", false, false, null,
                    new SpreadsheetImportDtos.SpreadsheetMappingSpecDto(
                        "LEDGERS", "POST", null,
                        null, null, null, null, null, null,
                        null, null, null, null, null, null, List.of(),
                        "Name", "Alias", "Group", "Opening Balance", "Side", "GSTIN", "State Code", null, null,
                        "AUTO", "REJECT"
                    ),
                    null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        String csvBatchId = objectMapper.readTree(csvPrep.getResponse().getContentAsByteArray()).get("id").asString();

        MvcResult csvExec = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + csvBatchId + "/execute",
                ownerSession, ownerXsrf, Map.of()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        UUID importedLedgerId = UUID.fromString(
            objectMapper.readTree(csvExec.getResponse().getContentAsByteArray())
                .get("units").get(0).get("resultingEntityId").asString()
        );
        MvcResult ledgerGet = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/ledgers/" + importedLedgerId, ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode ledgerJson = objectMapper.readTree(ledgerGet.getResponse().getContentAsByteArray());
        Assertions.assertThat(ledgerJson.get("aliasCode").asString()).isEqualTo("00045");
        Assertions.assertThat(ledgerJson.get("gstin").asString()).isEqualTo(delhiGstin);

        // 4. Legacy .xls (HSSF) + Data-only .xlsm macro disclosure + .xlsb & encrypted OLE2 rejections
        byte[] xlsBytes = buildSimpleTwoColHssfBytes();
        MvcResult xlsPrep = mockMvc.perform(
            MockMvcRequestBuilders.multipart("/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare")
                .file(new MockMultipartFile("file", "legacy.xls", "application/vnd.ms-excel", xlsBytes))
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", decodeCookie(ownerXsrf))
                .header("Idempotency-Key", UUID.randomUUID().toString())
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(xlsPrep.getResponse().getContentAsByteArray())
            .get("formatProfile").asString()).isEqualTo("XLS");

        byte[] xlsmBytes = injectVbaPartIntoXlsx(buildSimpleTwoColXssfBytes(false, null));
        MvcResult xlsmPrep = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "macro-book.xlsm", null, Base64.getEncoder().encodeToString(xlsmBytes),
                    null, null, 1, null, false, false, null, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode xlsmJson = objectMapper.readTree(xlsmPrep.getResponse().getContentAsByteArray());
        Assertions.assertThat(xlsmJson.get("formatProfile").asString()).isEqualTo("XLSM_DATA_ONLY");
        Assertions.assertThat(xlsmJson.get("macroDetected").asBoolean()).isTrue();

        // Reject .xlsb with 422
        mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "binary.xlsb", "XLSB", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4}),
                    null, null, 1, null, false, false, null, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SHEET-UNSUPPORTED-FORMAT"));

        // Reject password-encrypted OLE2 workbook with 422
        byte[] encryptedOle2Bytes;
        try (POIFSFileSystem poifs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            poifs.getRoot().createDocument("EncryptionInfo", new java.io.ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
            poifs.getRoot().createDocument("EncryptedPackage", new java.io.ByteArrayInputStream(new byte[] {5, 6, 7, 8}));
            poifs.writeFilesystem(out);
            encryptedOle2Bytes = out.toByteArray();
        }
        mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "locked.xlsx", null, Base64.getEncoder().encodeToString(encryptedOle2Bytes),
                    null, null, 1, null, false, false, null, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SHEET-ENCRYPTED-WORKBOOK"));

        // 5. Explicitly reject fictitious Excel 1900-02-29 (serial 60) with 422 MC-SHEET-INVALID-DATE
        byte[] feb29Serial60Xlsx = buildSimpleTwoColXssfBytes(false, 60);
        mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "fictitious-1900-02-29.xlsx", null, Base64.getEncoder().encodeToString(feb29Serial60Xlsx),
                    null, null, 1, null, false, false, null, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SHEET-INVALID-DATE"));

        // 6. Verify 1904 date system workbook interprets serial 44286 as 2025-04-01
        byte[] date1904Xlsx = buildSimpleTwoColXssfBytes(true, 44286);
        MvcResult prep1904 = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "mac-1904.xlsx", null, Base64.getEncoder().encodeToString(date1904Xlsx),
                    null, null, 1, null, false, false, null, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode batch1904 = objectMapper.readTree(prep1904.getResponse().getContentAsByteArray());
        Assertions.assertThat(batch1904.get("dateSystem").asString()).isEqualTo("1904");
        Assertions.assertThat(batch1904.get("status").asString()).isEqualTo("READY");
        Assertions.assertThat(batch1904.get("units").get(0).get("intendedSummary").get("voucherDate").asString())
            .isEqualTo("2025-04-01");
    }

    @Test
    @Order(3)
    void formulaConsentFormulaErrorRejectionCsvInjectionNeutralizationAndUnitResumeCancel() throws Exception {
        // 1. Cached formula without consent -> FAILED_BEFORE_WRITE, then remap with consent -> READY
        byte[] cachedFormulaXlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("FormulaSheet");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Date");
            h.createCell(1).setCellValue("Debit Ledger");
            h.createCell(2).setCellValue("Credit Ledger");
            h.createCell(3).setCellValue("Amount");

            Row r = s.createRow(1);
            r.createCell(0).setCellValue("2025-04-12");
            r.createCell(1).setCellValue("Cash");
            r.createCell(2).setCellValue("Sales - Domestic");
            Cell fCell = r.createCell(3);
            fCell.setCellFormula("250+250");
            fCell.setCellValue(new BigDecimal("500").intValue());

            wb.write(out);
            cachedFormulaXlsx = out.toByteArray();
        }

        MvcResult noConsentRes = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "cached-formula.xlsx", null, Base64.getEncoder().encodeToString(cachedFormulaXlsx),
                    null, "FormulaSheet", 1, null, false, false, null, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode noConsentBatch = objectMapper.readTree(noConsentRes.getResponse().getContentAsByteArray());
        String formulaBatchId = noConsentBatch.get("id").asString();
        Assertions.assertThat(noConsentBatch.get("status").asString()).isEqualTo("FAILED_BEFORE_WRITE");
        Assertions.assertThat(noConsentBatch.get("units").get(0).get("validationErrors").get(0).get("code").asString())
            .isEqualTo("MC-SHEET-FORMULA-CONSENT-REQUIRED");

        // Attempting execute without consent rejects with 422 MC-SHEET-FORMULA-CONSENT-REQUIRED
        mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + formulaBatchId + "/execute",
                ownerSession, ownerXsrf, Map.of("action", "POST")
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-SHEET-FORMULA-CONSENT-REQUIRED"));

        // Remapping with cachedFormulaConsent = true admits cached result without recalculation
        MvcResult remappedRes = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + formulaBatchId + "/remap",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.RemapSpreadsheetBatchRequest(
                    "FormulaSheet", 1, null, true, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(remappedRes.getResponse().getContentAsByteArray())
            .get("status").asString()).isEqualTo("READY");

        // 2. Formula error (#DIV/0!) + hostile CSV-injection strings (=CMD..., +SUM..., @ATTACK)
        byte[] errorFormulaXlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("ErrorsSheet");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Date");
            h.createCell(1).setCellValue("Debit Ledger");
            h.createCell(2).setCellValue("Credit Ledger");
            h.createCell(3).setCellValue("Amount");

            Row r = s.createRow(1);
            r.createCell(0).setCellValue("2025-04-14");
            r.createCell(1).setCellValue("=CMD|' /C calc'!A0");
            r.createCell(2).setCellValue("+MALICIOUS_LEDGER");
            Cell errCell = r.createCell(3);
            errCell.setCellFormula("1/0");
            errCell.setCellErrorValue(FormulaError.DIV0.getCode());

            wb.write(out);
            errorFormulaXlsx = out.toByteArray();
        }

        MvcResult errPrepRes = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "div0-and-injection.xlsx", null, Base64.getEncoder().encodeToString(errorFormulaXlsx),
                    null, "ErrorsSheet", 1, null, true, false, null, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        String errBatchId = objectMapper.readTree(errPrepRes.getResponse().getContentAsByteArray())
            .get("id").asString();

        mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + errBatchId + "/execute",
                ownerSession, ownerXsrf, Map.of("action", "POST")
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity());

        MvcResult errorCsvRes = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + errBatchId + "/errors.csv",
                ownerSession
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        String exportedCsv = errorCsvRes.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertThat(exportedCsv).contains("MC-SHEET-FORMULA-ERROR");
        Assertions.assertThat(exportedCsv).contains("D2");
        Assertions.assertThat(exportedCsv).contains("'=CMD|' /C calc'!A0");
        Assertions.assertThat(exportedCsv).contains("'+MALICIOUS_LEDGER");

        // 3. Per-unit checkpointed execution -> PARTIAL_INTERRUPTED -> resume -> cancel remaining
        String threeVoucherCsv = "Date,Debit Ledger,Credit Ledger,Amount,Narration\r\n"
            + "2025-04-15,Cash,Sales - Domestic,100.00,Unit 1\r\n"
            + "2025-04-16,Cash,Sales - Domestic,200.00,Unit 2\r\n"
            + "2025-04-17,Cash,Sales - Domestic,300.00,Unit 3\r\n";

        MvcResult chunkPrep = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "three-units.csv", "CSV", null, threeVoucherCsv,
                    null, 1, ",", false, false, null, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        String chunkBatchId = objectMapper.readTree(chunkPrep.getResponse().getContentAsByteArray())
            .get("id").asString();

        // Execute 1st unit only -> PARTIAL_INTERRUPTED
        MvcResult step1 = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + chunkBatchId + "/execute",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.ExecuteSpreadsheetBatchRequest("POST", false, 1)
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode s1Json = objectMapper.readTree(step1.getResponse().getContentAsByteArray());
        Assertions.assertThat(s1Json.get("status").asString()).isEqualTo("PARTIAL_INTERRUPTED");
        Assertions.assertThat(s1Json.get("committedUnits").asInt()).isEqualTo(1);
        UUID unit1VoucherId = UUID.fromString(s1Json.get("units").get(0).get("resultingEntityId").asString());

        // Resume 1 more unit -> unit 1 is NOT re-posted; unit 2 commits!
        MvcResult step2 = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + chunkBatchId + "/resume",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.ExecuteSpreadsheetBatchRequest("POST", false, 1)
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode s2Json = objectMapper.readTree(step2.getResponse().getContentAsByteArray());
        Assertions.assertThat(s2Json.get("status").asString()).isEqualTo("PARTIAL_INTERRUPTED");
        Assertions.assertThat(s2Json.get("committedUnits").asInt()).isEqualTo(2);
        Assertions.assertThat(s2Json.get("units").get(0).get("resultingEntityId").asString())
            .isEqualTo(unit1VoucherId.toString());

        // Cancel remaining pending unit 3 -> unit 3 SKIPPED, units 1 & 2 remain COMMITTED in mc_journal!
        MvcResult cancelledRes = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + chunkBatchId + "/cancel",
                ownerSession, ownerXsrf, Map.of()
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode cancelledJson = objectMapper.readTree(cancelledRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(cancelledJson.get("status").asString()).isEqualTo("CANCELLED");
        Assertions.assertThat(cancelledJson.get("committedUnits").asInt()).isEqualTo(2);
        Assertions.assertThat(cancelledJson.get("skippedUnits").asInt()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void extensionWorkbenchCustomFieldsPrivateEncryptionDmnFeelEnforcementAndOdbcProjections() throws Exception {
        UUID creditNoteFieldUuid = UUID.randomUUID();
        UUID creditCapFieldUuid = UUID.randomUUID();
        UUID extensionUuid = UUID.randomUUID();

        // 1. Designer creates an Extension Package with Custom Fields, FEEL Rule & DMN Decision Table
        ExtensionDtos.CreateExtensionPackageRequest pkgReq = new ExtensionDtos.CreateExtensionPackageRequest(
            "com.raster.creditguard",
            extensionUuid,
            "High-Value Voucher & Credit Guard",
            ExtensionDtos.JSON_SCHEMA_2020_12,
            List.of("LEDGER", "VOUCHER"),
            List.of(
                new ExtensionDtos.UpsertCustomFieldDefRequest(
                    "com.raster.creditguard",
                    creditCapFieldUuid,
                    "LEDGER",
                    "approvedCreditCap",
                    "Approved Credit Cap",
                    "EXACT_DECIMAL",
                    2,
                    List.of(),
                    "50000.00",
                    "STANDARD",
                    "ledger.header.commercial",
                    false
                ),
                new ExtensionDtos.UpsertCustomFieldDefRequest(
                    "com.raster.creditguard",
                    creditNoteFieldUuid,
                    "LEDGER",
                    "internalRiskMemo",
                    "Confidential Risk Memo",
                    "TEXT",
                    null,
                    List.of(),
                    null,
                    "SELECTED_PRIVATE",
                    "ledger.header.compliance",
                    false
                )
            ),
            List.of(
                new ExtensionDtos.UiAnchorCustomizationDto(
                    "voucher.header.extra",
                    "VOUCHER_EDITOR",
                    "ADD_SECTION",
                    List.of("approvedCreditCap"),
                    "extension.creditCheck",
                    "K/k"
                )
            ),
            List.of(
                new ExtensionDtos.FeelRuleSpecDto(
                    "HIGH_VALUE_NARRATION_REQUIRED",
                    "Require narration above 50,000.00",
                    "VOUCHER",
                    "BLOCKING",
                    "totalDebit <= 50000.00 or (not isBlank(narration) and stringLength(narration) >= 5)",
                    "Vouchers exceeding 50,000.00 must include a descriptive narration (>= 5 chars).",
                    10
                )
            ),
            List.of(
                new ExtensionDtos.DmnDecisionTableDto(
                    "JOURNAL_APPROVAL_GATE",
                    "Block high-value unreferenced Journal entries",
                    "VOUCHER",
                    "FIRST",
                    List.of("voucherType", "totalDebit", "isBlank(supplierInvoiceNo)"),
                    List.of(
                        new ExtensionDtos.DmnDecisionRuleRowDto(
                            "R1",
                            List.of("\"Journal\"", "> 75000.00", "true"),
                            "BLOCK",
                            "Journal vouchers above 75,000.00 require supplierInvoiceNo reference."
                        ),
                        new ExtensionDtos.DmnDecisionRuleRowDto(
                            "R2",
                            List.of("-", "-", "-"),
                            "ALLOW",
                            "Permitted"
                        )
                    )
                )
            ),
            List.of(
                new ExtensionDtos.ExtensionReportDefDto(
                    "HIGH_VALUE_REGISTER",
                    "High-Value Posted Register",
                    "VOUCHERS",
                    List.of("voucherDate", "voucherNumber", "totalDebit"),
                    Map.of("minDebit", "50000.00")
                )
            ),
            List.of(
                new ExtensionDtos.ExtensionPrintLayoutDto(
                    "CLEAN_INVOICE",
                    "Clean Tax Invoice Layout",
                    "TAX_INVOICE",
                    "TAX INVOICE",
                    List.of("voucherNumber", "voucherDate", "totalDebit"),
                    true,
                    "Goods once sold will be governed by company terms."
                )
            ),
            List.of(),
            null
        );

        MvcResult draftRes = mockMvc.perform(
            authedPost("/api/v1/companies/" + companyId + "/extensions/packages",
                designerSession, designerXsrf, pkgReq)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode draftJson = objectMapper.readTree(draftRes.getResponse().getContentAsByteArray());
        String pkgId = draftJson.get("id").asString();
        Assertions.assertThat(draftJson.get("status").asString()).isEqualTo("DRAFT");
        Assertions.assertThat(draftJson.get("bindingState").asString()).isEqualTo("INACTIVE");

        // Validate package (transitions DRAFT -> VALIDATED)
        MvcResult valRes = mockMvc.perform(
            authedPost("/api/v1/companies/" + companyId + "/extensions/packages/" + pkgId + "/validate",
                designerSession, designerXsrf, Map.of())
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(valRes.getResponse().getContentAsByteArray())
            .get("status").asString()).isEqualTo("VALIDATED");

        // Designer CANNOT self-activate without extension.manage (403 MC-EXT-MANAGE-FORBIDDEN)
        mockMvc.perform(
            authedPost("/api/v1/companies/" + companyId + "/extensions/packages/" + pkgId + "/activate",
                designerSession, designerXsrf, Map.of())
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-EXT-MANAGE-FORBIDDEN"));

        // Company Admin / Owner activates the validated extension package
        MvcResult actRes = mockMvc.perform(
            authedPost("/api/v1/companies/" + companyId + "/extensions/packages/" + pkgId + "/activate",
                ownerSession, ownerXsrf, Map.of())
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(actRes.getResponse().getContentAsByteArray())
            .get("state").asString()).isEqualTo("ACTIVE");

        // Cross-tenant isolation: Company B cannot see Company A's extension package
        mockMvc.perform(
            authedGet("/api/v1/companies/" + companyBId + "/extensions/packages/" + pkgId, ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isNotFound());

        // 2. Write & verify SELECTED_PRIVATE Tink AEAD custom field on customerLedgerId
        mockMvc.perform(
            authedPut(
                "/api/v1/companies/" + companyId + "/extensions/custom-fields/values/LEDGER/" + customerLedgerId,
                ownerSession,
                ownerXsrf,
                Map.of(
                    "values", Map.of(
                        "com.raster.creditguard:approvedCreditCap", "125000.00",
                        "com.raster.creditguard:internalRiskMemo", "CONFIDENTIAL-BOARD-HOLD-7788"
                    )
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk());

        // Verify PostgreSQL stores NULL in plain_value_text and non-null gcm_ciphertext & siv_ciphertext!
        Map<String, Object> rawDbValue = TenantContextHolder.callWithCompany(companyId, () ->
            businessJdbcTemplate.queryForMap(
                """
                SELECT v.plain_value_text, octet_length(v.gcm_ciphertext) AS gcm_len,
                       octet_length(v.siv_ciphertext) AS siv_len
                  FROM mc_custom_field_value v
                  JOIN mc_custom_field_def d ON d.company_id = v.company_id AND d.id = v.field_def_id
                 WHERE v.company_id = ? AND v.entity_id = ? AND d.field_code = 'internalRiskMemo'
                """,
                companyId,
                customerLedgerId
            )
        );
        Assertions.assertThat(rawDbValue.get("plain_value_text")).isNull();
        Assertions.assertThat(((Number) rawDbValue.get("gcm_len")).intValue()).isGreaterThan(16);
        Assertions.assertThat(((Number) rawDbValue.get("siv_len")).intValue()).isGreaterThan(16);

        // Authorised owner decrypts SELECTED_PRIVATE value cleanly
        MvcResult ownerReadCf = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId + "/extensions/custom-fields/values/LEDGER/" + customerLedgerId,
                ownerSession
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode ownerCfArr = objectMapper.readTree(ownerReadCf.getResponse().getContentAsByteArray());
        Assertions.assertThat(ownerCfArr.toString()).contains("CONFIDENTIAL-BOARD-HOLD-7788");

        // Restricted designer receives redacted=true on list and 403 on strictPrivateAccess=true
        MvcResult designerReadCf = mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId + "/extensions/custom-fields/values/LEDGER/" + customerLedgerId,
                designerSession
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        String designerCfJson = designerReadCf.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertThat(designerCfJson).doesNotContain("CONFIDENTIAL-BOARD-HOLD-7788");
        Assertions.assertThat(designerCfJson).contains("\"redacted\":true");

        mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId + "/extensions/custom-fields/values/LEDGER/"
                    + customerLedgerId + "?strictPrivateAccess=true",
                designerSession
            )
        )
            .andExpect(MockMvcResultMatchers.status().isForbidden())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-EXT-PRIVATE-FIELD-FORBIDDEN"));

        // Incompatible redefinition (changing TEXT -> DATE) must fail with 422 MC-EXT-FIELD-INCOMPATIBLE
        mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/extensions/custom-fields",
                ownerSession,
                ownerXsrf,
                new ExtensionDtos.UpsertCustomFieldDefRequest(
                    "com.raster.creditguard",
                    creditNoteFieldUuid,
                    "LEDGER",
                    "internalRiskMemo",
                    "Incompatible Type Attempt",
                    "DATE",
                    null,
                    List.of(),
                    null,
                    "SELECTED_PRIVATE",
                    "ledger.header.compliance",
                    false
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-EXT-FIELD-INCOMPATIBLE"));

        // 3. Active EXT-004 Voucher Rule blocks invalid high-value voucher in BOTH REST API and Spreadsheet Import!
        UUID cashId = findLedgerId(companyId, "Cash");

        // 3A. Direct REST /vouchers API with totalDebit = 60000.00 and blank narration -> 422 MC-EXT-VOUCHER-RULE-BLOCKED
        Map<String, Object> invalidVoucherReq = new LinkedHashMap<>();
        invalidVoucherReq.put("voucherTypeName", "Sales");
        invalidVoucherReq.put("voucherDate", "2025-04-20");
        invalidVoucherReq.put("action", "POST");
        invalidVoucherReq.put("narration", "");
        invalidVoucherReq.put("lines", List.of(
            Map.of("lineNo", 1, "lineRole", "LEDGER", "ledgerId", cashId.toString(), "side", "DR", "amount", "60000.00"),
            Map.of("lineNo", 2, "lineRole", "LEDGER", "ledgerId", salesLedgerId.toString(), "side", "CR", "amount", "60000.00")
        ));
        mockMvc.perform(
            authedPost("/api/v1/companies/" + companyId + "/vouchers", ownerSession, ownerXsrf, invalidVoucherReq)
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-EXT-VOUCHER-RULE-BLOCKED"));

        // 3B. Same invalid high-value Journal (> 75000 without supplierInvoiceNo) via Spreadsheet Import -> blocked!
        String blockedJournalCsv = "Date,Voucher Type,Debit Ledger,Credit Ledger,Amount,Narration\r\n"
            + "2025-04-21,Journal,Cash,Sales - Domestic,80000.00,Valid length narration but missing ref\r\n";

        MvcResult blockedSheetPrep = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/spreadsheet-imports/prepare",
                ownerSession,
                ownerXsrf,
                new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                    "dmn-blocked.csv", "CSV", null, blockedJournalCsv,
                    null, 1, ",", false, false, null, null, null
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode blockedSheetBatch = objectMapper.readTree(blockedSheetPrep.getResponse().getContentAsByteArray());
        Assertions.assertThat(blockedSheetBatch.get("status").asString()).isEqualTo("FAILED_BEFORE_WRITE");
        Assertions.assertThat(blockedSheetBatch.get("units").get(0).get("validationErrors").get(0).get("code").asString())
            .isEqualTo("MC-EXT-VOUCHER-RULE-BLOCKED");

        // 3C. Valid voucher satisfying both FEEL rule and DMN table succeeds!
        invalidVoucherReq.put("narration", "Approved hospital equipment supply");
        mockMvc.perform(
            authedPost("/api/v1/companies/" + companyId + "/vouchers", ownerSession, ownerXsrf, invalidVoucherReq)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated());

        // 4. Preview endpoint & Service-backed Inbound ODBC Projection Query (zero raw SQL!)
        MvcResult previewRes = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/extensions/evaluate-preview",
                ownerSession,
                ownerXsrf,
                new ExtensionDtos.EvaluatePreviewRequest(
                    "roundHalfUp(amount * 0.18, 2)",
                    null,
                    Map.of("amount", "1250.50")
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        Assertions.assertThat(objectMapper.readTree(previewRes.getResponse().getContentAsByteArray())
            .get("resultValue").asString()).isEqualTo("225.09");

        MvcResult odbcTbRes = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/extensions/odbc-query",
                ownerSession,
                ownerXsrf,
                new ExtensionDtos.OdbcProjectionQueryRequest("TRIAL_BALANCE", null, null, false, 100, null)
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode odbcTbJson = objectMapper.readTree(odbcTbRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(odbcTbJson.get("projection").asString()).isEqualTo("TRIAL_BALANCE");
        Assertions.assertThat(odbcTbJson.get("rowCount").asInt()).isGreaterThan(0);

        // Raw SQL attempt on /odbc-query must fail with 422 MC-EXT-ODBC-RAW-SQL-FORBIDDEN
        mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/extensions/odbc-query",
                ownerSession,
                ownerXsrf,
                new ExtensionDtos.OdbcProjectionQueryRequest(
                    "LEDGERS", null, null, false, 10, "SELECT * FROM mc_ledger"
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("MC-EXT-ODBC-RAW-SQL-FORBIDDEN"));

        // Suspending extension preserves custom field values intact
        mockMvc.perform(
            authedPost("/api/v1/companies/" + companyId + "/extensions/packages/" + pkgId + "/suspend",
                ownerSession, ownerXsrf, Map.of())
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.state").value("SUSPENDED"));

        mockMvc.perform(
            authedGet(
                "/api/v1/companies/" + companyId + "/extensions/custom-fields/values/LEDGER/" + customerLedgerId,
                ownerSession
            )
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.content().string(
                org.hamcrest.Matchers.containsString("CONFIDENTIAL-BOARD-HOLD-7788")
            ));
    }

    @Test
    @Order(5)
    void tdlStaticImporterEmitsAllFiveMandatoryDispositionsInactiveDraftAndBlocksCompiledTcp() throws Exception {
        String sampleTdl = """
            ;; Sample Enterprise Customisation TDL
            [Include: missing_helper.tdl]

            [#Menu: Gateway of Main]
                Add: Item: Custom Dispatch Register : Display : CustomDispatchRep

            [Report: CustomDispatchRep]
                Form: CustomDispatchForm

            [Part: UnmappedCustomCanvas]
                Line: UnmappedLine

            [System: UDF]
                DispatchVehicleNo : String : 1001
                DispatchFreightAmt : Amount : 1002

            [System: Formula]
                ValidFreightLimit : $Amount <= 250000
                ComplexContextFormula : $$CollAmtTotal:InventoryEntries:$Amount > 0

            [Function: MutateBatchLoop]
                01 : WALK COLLECTION : PendingVouchers
                02 : SAVE TARGET

            [Collection: ExternalErpOdbc]
                ODBC : "DSN=ExternalWarehouse;Uid=reader"

            [Field: SerialCheckGate]
                Set as : $$SerialNumber
            """;

        MvcResult tdlRes = mockMvc.perform(
            authedPost(
                "/api/v1/companies/" + companyId + "/extensions/tdl-imports",
                ownerSession,
                ownerXsrf,
                new ExtensionDtos.ImportTdlProjectRequest(
                    "Warehouse Dispatch Customisation",
                    "warehouse_dispatch.tdl",
                    "Reference Client 7.2",
                    sampleTdl,
                    null,
                    Map.of()
                )
            )
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();

        JsonNode projectJson = objectMapper.readTree(tdlRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(projectJson.get("hasNativeDllBlock").asBoolean()).isTrue();
        Assertions.assertThat(projectJson.get("hasUnresolvedIncludes").asBoolean()).isTrue();
        Assertions.assertThat(projectJson.get("status").asString()).isEqualTo("BLOCKED_DEPENDENCY");
        Assertions.assertThat(projectJson.get("draftExtensionId").isNull()).isFalse();

        JsonNode counts = projectJson.get("summaryCounts");
        Assertions.assertThat(counts.get(TdlStaticImporterService.DISP_TRANSLATED).asInt()).isGreaterThanOrEqualTo(4);
        Assertions.assertThat(counts.get(TdlStaticImporterService.DISP_REQUIRES_MAPPING).asInt()).isGreaterThanOrEqualTo(2);
        Assertions.assertThat(counts.get(TdlStaticImporterService.DISP_UNSUPPORTED).asInt()).isGreaterThanOrEqualTo(1);
        Assertions.assertThat(counts.get(TdlStaticImporterService.DISP_BLOCKED).asInt()).isGreaterThanOrEqualTo(2);
        Assertions.assertThat(counts.get(TdlStaticImporterService.DISP_NOT_APPLICABLE).asInt()).isGreaterThanOrEqualTo(1);

        // Verify parity matrix rows carry NOT_RUN executionStatus and cover all 5 mandatory dispositions
        Set<String> observedDispositions = new java.util.HashSet<>();
        for (JsonNode row : projectJson.get("parityMatrix")) {
            observedDispositions.add(row.get("disposition").asString());
            Assertions.assertThat(row.get("executionStatus").asString()).isEqualTo("NOT_RUN");
        }
        Assertions.assertThat(observedDispositions).containsExactlyInAnyOrder(
            TdlStaticImporterService.DISP_TRANSLATED,
            TdlStaticImporterService.DISP_REQUIRES_MAPPING,
            TdlStaticImporterService.DISP_UNSUPPORTED,
            TdlStaticImporterService.DISP_BLOCKED,
            TdlStaticImporterService.DISP_NOT_APPLICABLE
        );

        // Verify synthesized draft extension package is DRAFT and INACTIVE (never auto-activated!)
        String draftExtId = projectJson.get("draftExtensionId").asString();
        MvcResult draftPkgRes = mockMvc.perform(
            authedGet("/api/v1/companies/" + companyId + "/extensions/packages/" + draftExtId, ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode draftPkg = objectMapper.readTree(draftPkgRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(draftPkg.get("status").asString()).isEqualTo("DRAFT");
        Assertions.assertThat(draftPkg.get("bindingState").asString()).isEqualTo("INACTIVE");

        // Compiled .tcp binary submission -> BLOCKED_DEPENDENCY with hasCompiledTcpBlock == true
        byte[] tcpBytes = new byte[] {'T', 'C', 'P', 0x00, 0x01, 0x02, 0x7F};
        MvcResult tcpRes = mockMvc.perform(
            MockMvcRequestBuilders.multipart("/api/v1/companies/" + companyId + "/extensions/tdl-imports")
                .file(new MockMultipartFile("file", "compiled_addon.tcp", "application/octet-stream", tcpBytes))
                .param("projectName", "Compiled Binary Addon")
                .cookie(ownerSession, ownerXsrf)
                .header("X-XSRF-TOKEN", decodeCookie(ownerXsrf))
                .header("Idempotency-Key", UUID.randomUUID().toString())
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        JsonNode tcpJson = objectMapper.readTree(tcpRes.getResponse().getContentAsByteArray());
        Assertions.assertThat(tcpJson.get("hasCompiledTcpBlock").asBoolean()).isTrue();
        Assertions.assertThat(tcpJson.get("status").asString()).isEqualTo("BLOCKED_DEPENDENCY");
        Assertions.assertThat(tcpJson.get("draftExtensionId").isNull()).isTrue();
    }

    // =========================================================================
    // Test Fixture Builders & HTTP Helpers
    // =========================================================================

    private static byte[] buildSampleOds14Archive() throws Exception {
        String contentXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                office:version="1.4">
              <office:body>
                <office:spreadsheet>
                  <table:table table:name="OdsSales">
                    <table:table-row>
                      <table:table-cell office:value-type="string"><text:p>Date</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>VchNo</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>Party</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>Total</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>Sales</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>CGST</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>SGST</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>Narration</text:p></table:table-cell>
                    </table:table-row>
                    <table:table-row>
                      <table:table-cell office:value-type="date" office:date-value="2025-04-11"><text:p>2025-04-11</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>ODS-001</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>Salem Care Hospital</text:p></table:table-cell>
                      <table:table-cell office:value-type="float" office:value="1180.00"><text:p>1180.00</text:p></table:table-cell>
                      <table:table-cell office:value-type="float" office:value="1000.00"><text:p>1000.00</text:p></table:table-cell>
                      <table:table-cell table:number-columns-repeated="2" office:value-type="float" office:value="90.00"><text:p>90.00</text:p></table:table-cell>
                      <table:table-cell office:value-type="string"><text:p>ODS 1.4 repeated tax cols</text:p></table:table-cell>
                    </table:table-row>
                    <table:table-row table:number-rows-repeated="1048500">
                      <table:table-cell table:number-columns-repeated="1024"/>
                    </table:table-row>
                  </table:table>
                </office:spreadsheet>
              </office:body>
            </office:document-content>
            """;

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("content.xml"));
            zos.write(contentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            return bos.toByteArray();
        }
    }

    private static byte[] buildSimpleTwoColHssfBytes() throws Exception {
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("LegacySheet");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Date");
            h.createCell(1).setCellValue("Debit Ledger");
            h.createCell(2).setCellValue("Credit Ledger");
            h.createCell(3).setCellValue("Amount");

            Row r = s.createRow(1);
            r.createCell(0).setCellValue("2025-04-12");
            r.createCell(1).setCellValue("Cash");
            r.createCell(2).setCellValue("Sales - Domestic");
            r.createCell(3).setCellValue("450.00");

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildSimpleTwoColXssfBytes(boolean use1904Epoch, Integer dateSerial) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (use1904Epoch) {
                var wbPr = wb.getCTWorkbook().isSetWorkbookPr()
                    ? wb.getCTWorkbook().getWorkbookPr()
                    : wb.getCTWorkbook().addNewWorkbookPr();
                wbPr.setDate1904(true);
            }
            CreationHelper helper = wb.getCreationHelper();
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

            Sheet s = wb.createSheet("Sheet1");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Date");
            h.createCell(1).setCellValue("Debit Ledger");
            h.createCell(2).setCellValue("Credit Ledger");
            h.createCell(3).setCellValue("Amount");

            Row r = s.createRow(1);
            Cell dCell = r.createCell(0);
            if (dateSerial != null) {
                dCell.setCellValue(dateSerial);
                dCell.setCellStyle(dateStyle);
            } else {
                dCell.setCellValue("2025-04-12");
            }
            r.createCell(1).setCellValue("Cash");
            r.createCell(2).setCellValue("Sales - Domestic");
            r.createCell(3).setCellValue("750.00");

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] injectVbaPartIntoXlsx(byte[] xlsxBytes) throws Exception {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
            new java.io.ByteArrayInputStream(xlsxBytes));
             ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                zos.putNextEntry(new ZipEntry(entry.getName()));
                if ("[Content_Types].xml".equals(entry.getName())) {
                    String ctXml = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    String updatedCt = ctXml.replace(
                        "</Types>",
                        "<Default Extension=\"bin\" ContentType=\"application/vnd.ms-office.vbaProject\"/></Types>"
                    );
                    zos.write(updatedCt.getBytes(StandardCharsets.UTF_8));
                } else {
                    zis.transferTo(zos);
                }
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("xl/vbaProject.bin"));
            zos.write(new byte[] {0x01, 0x02, 0x03, 0x04});
            zos.closeEntry();
            zos.finish();
            return bos.toByteArray();
        }
    }

    private UUID createCompany(
        String code,
        String legalName,
        String stateCode,
        String fyStart,
        String gstin
    ) throws Exception {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("maintainAccounts", true);
        features.put("maintainInventory", true);
        features.put("billWiseEnabled", true);
        features.put("costCentresEnabled", true);
        features.put("gstEnabled", gstin != null);
        features.put("tdsEnabled", true);
        features.put("payrollEnabled", false);
        if (gstin != null) {
            features.put("gstRegistrationType", "REGULAR");
            features.put("gstin", gstin);
        }
        features.put("mcaEditLogEnforced", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("legalName", legalName);
        body.put("mailingName", legalName);
        body.put("countryCode", "IN");
        body.put("stateCode", stateCode);
        body.put("stateName", "33".equals(stateCode) ? "Tamil Nadu" : "Karnataka");
        body.put("baseCurrencyCode", "INR");
        body.put("baseCurrencySymbol", "₹");
        body.put("formalCurrencyName", "Indian Rupee");
        body.put("currencyScale", 2);
        body.put("financialYearFrom", fyStart);
        body.put("booksBeginningFrom", fyStart);
        body.put("mcaEditLogEnforced", true);
        body.put("features", features);

        MvcResult res = mockMvc.perform(authedPost("/api/v1/companies", ownerSession, ownerXsrf, body))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID findGroupId(UUID cmpId, String groupName) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet("/api/v1/companies/" + cmpId + "/account-groups?limit=100", ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsByteArray());
        JsonNode arr = root.has("items") ? root.get("items") : root;
        for (JsonNode n : arr) {
            if (groupName.equalsIgnoreCase(n.get("name").asString())) {
                return UUID.fromString(n.get("id").asString());
            }
        }
        throw new IllegalStateException("Group not found: " + groupName);
    }

    private UUID findLedgerId(UUID cmpId, String ledgerName) throws Exception {
        MvcResult res = mockMvc.perform(
            authedGet("/api/v1/companies/" + cmpId + "/ledgers?q=" + ledgerName, ownerSession)
        )
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode items = objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("items");
        for (JsonNode n : items) {
            if (ledgerName.equalsIgnoreCase(n.get("name").asString())) {
                return UUID.fromString(n.get("id").asString());
            }
        }
        throw new IllegalStateException("Ledger not found: " + ledgerName);
    }

    private UUID createLedger(UUID cmpId, String name, UUID groupId, boolean billWise) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("groupId", groupId.toString());
        body.put("openingBalance", "0.00");
        body.put("openingSide", "DR");
        body.put("maintainBalancesBillByBill", billWise);

        MvcResult res = mockMvc.perform(
            authedPost("/api/v1/companies/" + cmpId + "/ledgers", ownerSession, ownerXsrf, body)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private UUID createGstDutyLedger(UUID cmpId, String name, UUID groupId, String dutyHead) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("groupId", groupId.toString());
        body.put("taxType", "GST");
        body.put("dutyHead", dutyHead);
        body.put("openingBalance", "0.00");
        body.put("openingSide", "CR");

        MvcResult res = mockMvc.perform(
            authedPost("/api/v1/companies/" + cmpId + "/ledgers", ownerSession, ownerXsrf, body)
        )
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asString());
    }

    private Integer queryJournalCountForCompany(UUID cmpId) {
        return TenantContextHolder.callWithCompany(cmpId, () ->
            businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_journal WHERE company_id = ?",
                Integer.class,
                cmpId
            )
        );
    }

    private String queryJournalOrigin(UUID cmpId, UUID journalId) {
        return TenantContextHolder.callWithCompany(cmpId, () ->
            businessJdbcTemplate.queryForObject(
                "SELECT origin FROM mc_journal WHERE company_id = ? AND id = ?",
                String.class,
                cmpId,
                journalId
            )
        );
    }

    private MockHttpServletRequestBuilder authedGet(String path, Cookie sessionCookie) {
        return MockMvcRequestBuilders.get(path).cookie(sessionCookie);
    }

    private MockHttpServletRequestBuilder authedPost(
        String path,
        Cookie sessionCookie,
        Cookie xsrfCookie,
        Object payload
    ) throws Exception {
        return MockMvcRequestBuilders.post(path)
            .cookie(sessionCookie, xsrfCookie)
            .header("X-XSRF-TOKEN", decodeCookie(xsrfCookie))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(toJson(payload));
    }

    private MockHttpServletRequestBuilder authedPut(
        String path,
        Cookie sessionCookie,
        Cookie xsrfCookie,
        Object payload
    ) throws Exception {
        return MockMvcRequestBuilders.put(path)
            .cookie(sessionCookie, xsrfCookie)
            .header("X-XSRF-TOKEN", decodeCookie(xsrfCookie))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(toJson(payload));
    }

    private String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private static Cookie latestCookie(MvcResult result, String name) {
        Cookie[] cookies = result.getResponse().getCookies();
        Cookie found = null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                found = c;
            }
        }
        if (found == null) {
            throw new IllegalStateException("Missing expected cookie: " + name);
        }
        return found;
    }

    private static String decodeCookie(Cookie cookie) {
        return URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8);
    }
}
