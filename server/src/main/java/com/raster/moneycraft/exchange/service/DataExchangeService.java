package com.raster.moneycraft.exchange.service;

import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.accounting.service.VoucherService;
import com.raster.moneycraft.exchange.dto.ExchangeDtos;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.EditLogSnapshotService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.company.FiscalPeriodService;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.inventory.dto.InventoryMasterDtos;
import com.raster.moneycraft.inventory.service.InventoryMasterService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tally `<ENVELOPE>` XML, Rel 7.2 `JSONEx`, and Apache POI `.xlsx` Data Exchange service.
 * Enforces universal Tally sign convention (-Dr / +Cr), XXE hardening, &#4; stripping,
 * and idempotent GUID/name upsert with exact fixed-point BigDecimal strings.
 */
@Service
public class DataExchangeService {

    private static final DateTimeFormatter TALLY_DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern NUMERIC_ENTITY_CTRL_PATTERN = Pattern.compile(
        "&#(?:x0*[0-8bcef]|0*(?:[0-8]|1[124-9]|2[0-9]|3[01]));",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RAW_CTRL_CHARS_PATTERN = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
    private static final Set<String> VALID_TARGET_TYPES = Set.of(
        "LEDGERS", "STOCK_ITEMS", "ACCOUNTING_VOUCHERS", "ITEM_VOUCHERS", "BANK_STATEMENT"
    );
    private static final Set<String> VALID_DUP_MODES = Set.of("REJECT", "SKIP", "UPDATE_IF_DRAFT");

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final FiscalPeriodService fiscalPeriodService;
    private final InventoryMasterService inventoryMasterService;
    private final VoucherService voucherService;
    private final EditLogSnapshotService editLogSnapshotService;
    private final AuditChainService auditChainService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public DataExchangeService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        FiscalPeriodService fiscalPeriodService,
        InventoryMasterService inventoryMasterService,
        VoucherService voucherService,
        EditLogSnapshotService editLogSnapshotService,
        AuditChainService auditChainService,
        JcsCanonicalizer jcsCanonicalizer,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.fiscalPeriodService = fiscalPeriodService;
        this.inventoryMasterService = inventoryMasterService;
        this.voucherService = voucherService;
        this.editLogSnapshotService = editLogSnapshotService;
        this.auditChainService = auditChainService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    // =========================================================================
    // 1. Exchange Mapping Templates (mc_exchange_mapping_template)
    // =========================================================================

    public List<ExchangeDtos.ExchangeMappingTemplateDto> listTemplates(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessJdbcTemplate.query(
            """
            SELECT id, name, target_type, sheet_name, header_row_index,
                   column_mappings_json, default_values_json, duplicate_handling,
                   active, version_no, created_at
              FROM mc_exchange_mapping_template
             WHERE company_id = ? AND active = true
             ORDER BY lower(name) ASC, id ASC
            """,
            (rs, rowNum) -> mapTemplateRow(rs),
            companyId
        );
    }

    public ExchangeDtos.ExchangeMappingTemplateDto createTemplate(
        MoneyCraftPrincipal actor,
        UUID companyId,
        ExchangeDtos.CreateExchangeTemplateRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXG-TEMPLATE-REQUIRED",
                "Template Payload Required",
                "Provide a mapping template payload."
            );
        }
        String rawName = request.name() != null && !request.name().isBlank()
            ? request.name().trim()
            : (request.templateName() == null ? "" : request.templateName().trim());
        if (rawName.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXG-TEMPLATE-NAME-REQUIRED",
                "Template Name Required",
                "Specify name or templateName."
            );
        }

        String targetType = resolveTargetType(request.targetType(), request.entityScope());
        String dupMode = request.duplicateHandling() == null || request.duplicateHandling().isBlank()
            ? "SKIP"
            : request.duplicateHandling().trim().toUpperCase(Locale.ROOT);
        if (!VALID_DUP_MODES.contains(dupMode)) {
            dupMode = "SKIP";
        }
        int headerRow = request.headerRowIndex() == null || request.headerRowIndex() < 1
            ? 1
            : request.headerRowIndex();
        String sheetName = request.sheetName() == null || request.sheetName().isBlank()
            ? "Sheet1"
            : request.sheetName().trim();
        Map<String, String> mappings = request.columnMappings() == null ? Map.of() : request.columnMappings();
        Map<String, String> defaults = request.defaultValues() == null ? Map.of() : request.defaultValues();

        String finalDupMode = dupMode;
        return businessTxTemplate.execute(tx -> {
            ensurePrincipalProjected(actor);
            UUID id = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_exchange_mapping_template (
                    id, company_id, name, target_type, sheet_name, header_row_index,
                    column_mappings_json, default_values_json, duplicate_handling,
                    active, version_no, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, true, 1, ?)
                """,
                id,
                companyId,
                rawName,
                targetType,
                sheetName,
                headerRow,
                toJson(mappings),
                toJson(defaults),
                finalDupMode,
                actor.principalId()
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "EXCHANGE_TEMPLATE_CREATE",
                "EXCHANGE_TEMPLATE",
                id.toString(),
                "SUCCESS",
                null,
                Map.of("name", rawName, "targetType", targetType)
            );

            return businessJdbcTemplate.queryForObject(
                """
                SELECT id, name, target_type, sheet_name, header_row_index,
                       column_mappings_json, default_values_json, duplicate_handling,
                       active, version_no, created_at
                  FROM mc_exchange_mapping_template
                 WHERE company_id = ? AND id = ?
                """,
                (rs, rowNum) -> mapTemplateRow(rs),
                companyId,
                id
            );
        });
    }

    // =========================================================================
    // 2. Tally <ENVELOPE> XML Export & Import
    // =========================================================================

    public ExchangeDtos.ExportDataResultDto exportXml(
        MoneyCraftPrincipal principal,
        UUID companyId,
        ExchangeDtos.ExportDataRequest request
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        ExchangeDtos.JsonExDataDto bundle = collectCompanyExchangeBundle(principal, companyId, request);
        String companyName = queryCompanyName(companyId);

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!-- MoneyCraft 1.0.0 Canonical Tally XML Envelope Export (Universal Sign Law: -Dr / +Cr) -->\n");
        sb.append("<ENVELOPE>\n");
        sb.append("  <HEADER>\n");
        sb.append("    <TALLYREQUEST>Import Data</TALLYREQUEST>\n");
        sb.append("  </HEADER>\n");
        sb.append("  <BODY>\n");
        sb.append("    <IMPORTDATA>\n");
        sb.append("      <REQUESTDESC>\n");
        sb.append("        <REPORTNAME>All Masters</REPORTNAME>\n");
        sb.append("        <STATICVARIABLES>\n");
        sb.append("          <SVCURRENTCOMPANY>").append(escapeXml(companyName)).append("</SVCURRENTCOMPANY>\n");
        sb.append("        </STATICVARIABLES>\n");
        sb.append("      </REQUESTDESC>\n");
        sb.append("      <REQUESTDATA>\n");

        for (ExchangeDtos.JsonExGroupDto g : bundle.groups()) {
            sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
            sb.append("          <GROUP NAME=\"").append(escapeXml(g.name())).append("\" ACTION=\"Create\">\n");
            sb.append("            <GUID>").append(escapeXml(g.guid())).append("</GUID>\n");
            sb.append("            <NAME>").append(escapeXml(g.name())).append("</NAME>\n");
            sb.append("            <PARENT>").append(escapeXml(nullToEmpty(g.parent()))).append("</PARENT>\n");
            boolean isRev = "INCOME".equals(g.nature()) || "EXPENSES".equals(g.nature());
            boolean deemedPos = "ASSETS".equals(g.nature()) || "EXPENSES".equals(g.nature());
            sb.append("            <ISREVENUE>").append(isRev ? "Yes" : "No").append("</ISREVENUE>\n");
            sb.append("            <ISDEEMEDPOSITIVE>").append(deemedPos ? "Yes" : "No")
                .append("</ISDEEMEDPOSITIVE>\n");
            sb.append("            <AFFECTSGROSSPROFIT>").append(g.affectsGrossProfit() ? "Yes" : "No")
                .append("</AFFECTSGROSSPROFIT>\n");
            sb.append("            <ISSUBLEDGER>").append(g.behavesLikeSubledger() ? "Yes" : "No")
                .append("</ISSUBLEDGER>\n");
            sb.append("          </GROUP>\n");
            sb.append("        </TALLYMESSAGE>\n");
        }

        for (ExchangeDtos.JsonExUomDto u : bundle.uoms()) {
            sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
            sb.append("          <UNIT NAME=\"").append(escapeXml(u.symbol())).append("\" ACTION=\"Create\">\n");
            sb.append("            <NAME>").append(escapeXml(u.symbol())).append("</NAME>\n");
            sb.append("            <ORIGINALNAME>").append(escapeXml(nullToEmpty(u.formalName())))
                .append("</ORIGINALNAME>\n");
            sb.append("            <DECIMALPLACES>").append(u.decimalPlaces()).append("</DECIMALPLACES>\n");
            sb.append("            <ISSIMPLEUNIT>Yes</ISSIMPLEUNIT>\n");
            sb.append("          </UNIT>\n");
            sb.append("        </TALLYMESSAGE>\n");
        }

        for (ExchangeDtos.JsonExGodownDto gd : bundle.godowns()) {
            sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
            sb.append("          <GODOWN NAME=\"").append(escapeXml(gd.name())).append("\" ACTION=\"Create\">\n");
            sb.append("            <NAME>").append(escapeXml(gd.name())).append("</NAME>\n");
            sb.append("            <PARENT>").append(escapeXml(nullToEmpty(gd.parent()))).append("</PARENT>\n");
            sb.append("          </GODOWN>\n");
            sb.append("        </TALLYMESSAGE>\n");
        }

        for (ExchangeDtos.JsonExStockGroupDto sg : bundle.stockGroups()) {
            sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
            sb.append("          <STOCKGROUP NAME=\"").append(escapeXml(sg.name())).append("\" ACTION=\"Create\">\n");
            sb.append("            <NAME>").append(escapeXml(sg.name())).append("</NAME>\n");
            sb.append("            <PARENT>").append(escapeXml(nullToEmpty(sg.parent()))).append("</PARENT>\n");
            sb.append("          </STOCKGROUP>\n");
            sb.append("        </TALLYMESSAGE>\n");
        }

        for (ExchangeDtos.JsonExStockItemDto si : bundle.stockItems()) {
            sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
            sb.append("          <STOCKITEM NAME=\"").append(escapeXml(si.name())).append("\" ACTION=\"Create\">\n");
            sb.append("            <GUID>").append(escapeXml(si.guid())).append("</GUID>\n");
            sb.append("            <NAME>").append(escapeXml(si.name())).append("</NAME>\n");
            sb.append("            <PARENT>").append(escapeXml(nullToEmpty(si.stockGroup()))).append("</PARENT>\n");
            sb.append("            <BASEUNITS>").append(escapeXml(nullToEmpty(si.baseUom()))).append("</BASEUNITS>\n");
            sb.append("            <COSTINGMETHOD>").append(escapeXml(nullToEmpty(si.costingMethod())))
                .append("</COSTINGMETHOD>\n");
            sb.append("            <OPENINGBALANCE>").append(escapeXml(si.openingQty())).append("</OPENINGBALANCE>\n");
            sb.append("            <OPENINGRATE>").append(escapeXml(si.openingRate())).append("</OPENINGRATE>\n");
            BigDecimal openVal = new BigDecimal(si.openingValue());
            String signedOpenVal = openVal.signum() > 0 ? "-" + si.openingValue() : "0.00";
            sb.append("            <OPENINGVALUE>").append(escapeXml(signedOpenVal)).append("</OPENINGVALUE>\n");
            sb.append("          </STOCKITEM>\n");
            sb.append("        </TALLYMESSAGE>\n");
        }

        for (ExchangeDtos.JsonExLedgerDto l : bundle.ledgers()) {
            BigDecimal ob = new BigDecimal(l.openingBalance());
            String tallyOb = ob.signum() == 0
                ? "0.00"
                : ("DR".equals(l.openingSide()) ? "-" + l.openingBalance() : l.openingBalance());
            sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
            sb.append("          <LEDGER NAME=\"").append(escapeXml(l.name())).append("\" ACTION=\"Create\">\n");
            sb.append("            <GUID>").append(escapeXml(l.guid())).append("</GUID>\n");
            sb.append("            <NAME>").append(escapeXml(l.name())).append("</NAME>\n");
            sb.append("            <PARENT>").append(escapeXml(nullToEmpty(l.groupName()))).append("</PARENT>\n");
            sb.append("            <ISBILLWISEON>").append(l.maintainBalancesBillByBill() ? "Yes" : "No")
                .append("</ISBILLWISEON>\n");
            sb.append("            <OPENINGBALANCE>").append(escapeXml(tallyOb)).append("</OPENINGBALANCE>\n");
            if (l.gstin() != null && !l.gstin().isBlank()) {
                sb.append("            <PARTYGSTIN>").append(escapeXml(l.gstin())).append("</PARTYGSTIN>\n");
            }
            if (l.stateName() != null && !l.stateName().isBlank()) {
                sb.append("            <LEDSTATENAME>").append(escapeXml(l.stateName())).append("</LEDSTATENAME>\n");
            }
            sb.append("          </LEDGER>\n");
            sb.append("        </TALLYMESSAGE>\n");
        }

        for (ExchangeDtos.JsonExVoucherDto v : bundle.vouchers()) {
            sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
            sb.append("          <VOUCHER REMOTEID=\"").append(escapeXml(v.guid()))
                .append("\" VCHTYPE=\"").append(escapeXml(v.voucherTypeName()))
                .append("\" ACTION=\"Create\">\n");
            sb.append("            <GUID>").append(escapeXml(v.guid())).append("</GUID>\n");
            sb.append("            <DATE>").append(v.voucherDate().format(TALLY_DATE_FMT)).append("</DATE>\n");
            sb.append("            <EFFECTIVEDATE>").append(v.effectiveDate().format(TALLY_DATE_FMT))
                .append("</EFFECTIVEDATE>\n");
            sb.append("            <VOUCHERTYPENAME>").append(escapeXml(v.voucherTypeName()))
                .append("</VOUCHERTYPENAME>\n");
            sb.append("            <VOUCHERNUMBER>").append(escapeXml(nullToEmpty(v.voucherNumber())))
                .append("</VOUCHERNUMBER>\n");
            sb.append("            <PERSISTEDVIEW>").append(escapeXml(nullToEmpty(v.persistedView())))
                .append("</PERSISTEDVIEW>\n");
            if (v.partyLedgerName() != null && !v.partyLedgerName().isBlank()) {
                sb.append("            <PARTYLEDGERNAME>").append(escapeXml(v.partyLedgerName()))
                    .append("</PARTYLEDGERNAME>\n");
            }
            if (v.supplierInvoiceNo() != null && !v.supplierInvoiceNo().isBlank()) {
                sb.append("            <REFERENCE>").append(escapeXml(v.supplierInvoiceNo())).append("</REFERENCE>\n");
            }
            if (v.narration() != null && !v.narration().isBlank()) {
                sb.append("            <NARRATION>").append(escapeXml(v.narration())).append("</NARRATION>\n");
            }
            sb.append("            <ISOPTIONAL>")
                .append("OPTIONAL".equals(v.postingNature()) ? "Yes" : "No")
                .append("</ISOPTIONAL>\n");

            for (ExchangeDtos.JsonExVoucherLineDto line : v.lines()) {
                boolean isDr = "DR".equals(line.side());
                String signedAmt = isDr ? "-" + line.amount() : line.amount();
                sb.append("            <ALLLEDGERENTRIES.LIST>\n");
                sb.append("              <LEDGERNAME>").append(escapeXml(line.ledgerName())).append("</LEDGERNAME>\n");
                sb.append("              <ISDEEMEDPOSITIVE>").append(isDr ? "Yes" : "No")
                    .append("</ISDEEMEDPOSITIVE>\n");
                sb.append("              <ISPARTYLEDGER>")
                    .append("PARTY".equals(line.lineRole()) ? "Yes" : "No")
                    .append("</ISPARTYLEDGER>\n");
                sb.append("              <AMOUNT>").append(escapeXml(signedAmt)).append("</AMOUNT>\n");
                for (ExchangeDtos.JsonExBillAllocationDto ba : line.billAllocations()) {
                    boolean bDr = "DR".equals(ba.side());
                    String bSigned = bDr ? "-" + ba.amount() : ba.amount();
                    sb.append("              <BILLALLOCATIONS.LIST>\n");
                    sb.append("                <NAME>").append(escapeXml(ba.billName())).append("</NAME>\n");
                    sb.append("                <BILLTYPE>").append(escapeXml(toTallyBillType(ba.allocationType())))
                        .append("</BILLTYPE>\n");
                    sb.append("                <AMOUNT>").append(escapeXml(bSigned)).append("</AMOUNT>\n");
                    sb.append("              </BILLALLOCATIONS.LIST>\n");
                }
                sb.append("            </ALLLEDGERENTRIES.LIST>\n");
            }

            for (ExchangeDtos.JsonExInventoryLineDto inv : v.inventoryLines()) {
                boolean inward = "INWARD".equals(inv.movementDirection());
                String signedInvAmt = inward ? "-" + inv.amount() : inv.amount();
                sb.append("            <ALLINVENTORYENTRIES.LIST>\n");
                sb.append("              <STOCKITEMNAME>").append(escapeXml(inv.stockItemName()))
                    .append("</STOCKITEMNAME>\n");
                sb.append("              <ISDEEMEDPOSITIVE>").append(inward ? "Yes" : "No")
                    .append("</ISDEEMEDPOSITIVE>\n");
                sb.append("              <ACTUALQTY>").append(escapeXml(inv.actualQty())).append("</ACTUALQTY>\n");
                sb.append("              <BILLEDQTY>").append(escapeXml(inv.billedQty())).append("</BILLEDQTY>\n");
                sb.append("              <UOM>").append(escapeXml(nullToEmpty(inv.uomSymbol()))).append("</UOM>\n");
                sb.append("              <RATE>").append(escapeXml(inv.rate())).append("</RATE>\n");
                sb.append("              <AMOUNT>").append(escapeXml(signedInvAmt)).append("</AMOUNT>\n");
                sb.append("              <BATCHALLOCATIONS.LIST>\n");
                sb.append("                <GODOWNNAME>").append(escapeXml(nullToEmpty(inv.godownName())))
                    .append("</GODOWNNAME>\n");
                sb.append("                <BATCHNAME>").append(escapeXml(nullToEmpty(inv.batchName())))
                    .append("</BATCHNAME>\n");
                sb.append("                <ACTUALQTY>").append(escapeXml(inv.actualQty())).append("</ACTUALQTY>\n");
                sb.append("                <BILLEDQTY>").append(escapeXml(inv.billedQty())).append("</BILLEDQTY>\n");
                sb.append("                <AMOUNT>").append(escapeXml(signedInvAmt)).append("</AMOUNT>\n");
                sb.append("              </BATCHALLOCATIONS.LIST>\n");
                sb.append("            </ALLINVENTORYENTRIES.LIST>\n");
            }

            sb.append("          </VOUCHER>\n");
            sb.append("        </TALLYMESSAGE>\n");
        }

        sb.append("      </REQUESTDATA>\n");
        sb.append("    </IMPORTDATA>\n");
        sb.append("  </BODY>\n");
        sb.append("</ENVELOPE>\n");

        String xml = sb.toString();
        String shaHex = JcsCanonicalizer.toHex(jcsCanonicalizer.sha256(xml.getBytes(StandardCharsets.UTF_8)));
        int count = totalBundleCount(bundle);
        return new ExchangeDtos.ExportDataResultDto(
            "XML",
            "moneycraft-export.xml",
            "application/xml",
            xml,
            xml,
            count,
            shaHex
        );
    }

    public ExchangeDtos.ImportDataResultDto importXml(
        MoneyCraftPrincipal actor,
        UUID companyId,
        String rawXmlPayload,
        boolean dryRun
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        if (rawXmlPayload == null || rawXmlPayload.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXG-XML-EMPTY",
                "Empty XML Payload",
                "Provide a non-empty Tally <ENVELOPE> XML payload."
            );
        }

        String sanitizedXml = stripTallyControlCharacters(rawXmlPayload);
        Document doc = parseXmlSafely(sanitizedXml);
        ExchangeDtos.JsonExDataDto parsed = parseEnvelopeDomToBundle(doc);
        return applyExchangeBundle(actor, companyId, parsed, "XML", "IMPORT_XML", "TALLY_XML", dryRun);
    }

    // =========================================================================
    // 3. Rel 7.2 JSONEx Export & Import
    // =========================================================================

    public ExchangeDtos.JsonExExportResponseDto exportJsonEx(
        MoneyCraftPrincipal principal,
        UUID companyId,
        ExchangeDtos.ExportDataRequest request
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        ExchangeDtos.JsonExHeaderDto header = new ExchangeDtos.JsonExHeaderDto(
            1,
            "Export",
            "Data",
            "MoneyCraft JSONEx"
        );
        ExchangeDtos.JsonExDataDto data = collectCompanyExchangeBundle(principal, companyId, request);
        Map<String, Object> canonicalEnvelope = new LinkedHashMap<>();
        canonicalEnvelope.put("header", header);
        canonicalEnvelope.put("data", data);
        String content = toJson(canonicalEnvelope);
        String shaHex = JcsCanonicalizer.toHex(jcsCanonicalizer.sha256(content.getBytes(StandardCharsets.UTF_8)));

        return new ExchangeDtos.JsonExExportResponseDto(
            header,
            data,
            "JSONEX",
            "moneycraft-rel72-jsonex.json",
            "application/json",
            content,
            totalBundleCount(data),
            shaHex
        );
    }

    public ExchangeDtos.ImportDataResultDto importJsonEx(
        MoneyCraftPrincipal actor,
        UUID companyId,
        ExchangeDtos.ImportDataRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXG-JSONEX-EMPTY",
                "Empty JSONEx Payload",
                "Provide a Rel 7.2 JSONEx payload."
            );
        }

        ExchangeDtos.JsonExDataDto bundle = request.data();
        if (bundle == null) {
            String raw = request.content() != null && !request.content().isBlank()
                ? request.content()
                : request.rawJson();
            if (raw == null || raw.isBlank()) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-EXG-JSONEX-EMPTY",
                    "Empty JSONEx Content",
                    "Provide JSONEx 'data' object or 'content' string."
                );
            }
            try {
                JsonNode root = objectMapper.readTree(raw);
                JsonNode headerNode = root.get("header");
                if (headerNode != null && headerNode.has("version") && headerNode.get("version").asInt() < 1) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-EXG-JSONEX-VERSION",
                        "Unsupported JSONEx Version",
                        "JSONEx header.version must be >= 1."
                    );
                }
                JsonNode dataNode = root.has("data") ? root.get("data") : root;
                bundle = objectMapper.treeToValue(dataNode, ExchangeDtos.JsonExDataDto.class);
            } catch (JacksonException ex) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXG-JSONEX-INVALID",
                    "Malformed JSONEx Payload",
                    "Failed to parse JSONEx payload: " + ex.getMessage()
                );
            }
        }

        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        return applyExchangeBundle(actor, companyId, bundle, "JSONEX", "IMPORT_JSONEX", "TALLY_JSONEX", dryRun);
    }

    // =========================================================================
    // 4. OpenXML Excel (.xlsx) Export & Import
    // =========================================================================

    public byte[] exportExcel(
        MoneyCraftPrincipal principal,
        UUID companyId,
        ExchangeDtos.ExportDataRequest request
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        ExchangeDtos.JsonExDataDto bundle = collectCompanyExchangeBundle(principal, companyId, request);

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet groupsSheet = wb.createSheet("Groups");
            writeRow(groupsSheet, 0, "GUID", "Name", "Parent", "Nature", "AffectsGrossProfit", "Subledger");
            int rIdx = 1;
            for (ExchangeDtos.JsonExGroupDto g : bundle.groups()) {
                writeRow(
                    groupsSheet, rIdx++, g.guid(), g.name(), nullToEmpty(g.parent()),
                    g.nature(), Boolean.toString(g.affectsGrossProfit()), Boolean.toString(g.behavesLikeSubledger())
                );
            }

            Sheet uomSheet = wb.createSheet("UOMs");
            writeRow(uomSheet, 0, "Symbol", "FormalName", "DecimalPlaces");
            rIdx = 1;
            for (ExchangeDtos.JsonExUomDto u : bundle.uoms()) {
                writeRow(uomSheet, rIdx++, u.symbol(), nullToEmpty(u.formalName()), Integer.toString(u.decimalPlaces()));
            }

            Sheet gdSheet = wb.createSheet("Godowns");
            writeRow(gdSheet, 0, "Name", "Parent");
            rIdx = 1;
            for (ExchangeDtos.JsonExGodownDto gd : bundle.godowns()) {
                writeRow(gdSheet, rIdx++, gd.name(), nullToEmpty(gd.parent()));
            }

            Sheet sgSheet = wb.createSheet("StockGroups");
            writeRow(sgSheet, 0, "Name", "Parent");
            rIdx = 1;
            for (ExchangeDtos.JsonExStockGroupDto sg : bundle.stockGroups()) {
                writeRow(sgSheet, rIdx++, sg.name(), nullToEmpty(sg.parent()));
            }

            Sheet siSheet = wb.createSheet("StockItems");
            writeRow(
                siSheet, 0, "GUID", "Name", "StockGroup", "BaseUom",
                "CostingMethod", "OpeningQty", "OpeningRate", "OpeningValue"
            );
            rIdx = 1;
            for (ExchangeDtos.JsonExStockItemDto si : bundle.stockItems()) {
                writeRow(
                    siSheet, rIdx++, si.guid(), si.name(), nullToEmpty(si.stockGroup()),
                    nullToEmpty(si.baseUom()), nullToEmpty(si.costingMethod()),
                    si.openingQty(), si.openingRate(), si.openingValue()
                );
            }

            Sheet ledSheet = wb.createSheet("Ledgers");
            writeRow(
                ledSheet, 0, "GUID", "Name", "GroupName", "OpeningBalance",
                "OpeningSide", "BillWise", "GSTIN", "StateName"
            );
            rIdx = 1;
            for (ExchangeDtos.JsonExLedgerDto l : bundle.ledgers()) {
                writeRow(
                    ledSheet, rIdx++, l.guid(), l.name(), nullToEmpty(l.groupName()),
                    l.openingBalance(), l.openingSide(), Boolean.toString(l.maintainBalancesBillByBill()),
                    nullToEmpty(l.gstin()), nullToEmpty(l.stateName())
                );
            }

            Sheet vchSheet = wb.createSheet("Vouchers");
            writeRow(
                vchSheet, 0, "GUID", "VoucherType", "VoucherNumber", "VoucherDate", "EffectiveDate",
                "PersistedView", "PostingNature", "PartyLedger", "Reference", "Narration"
            );
            Sheet lineSheet = wb.createSheet("VoucherLines");
            writeRow(
                lineSheet, 0, "VoucherGUID", "LineNo", "LineRole", "LedgerName", "Side", "Amount", "BillAllocationsJson"
            );
            Sheet invSheet = wb.createSheet("InventoryLines");
            writeRow(
                invSheet, 0, "VoucherGUID", "LineNo", "StockItemName", "Direction", "UomSymbol",
                "ActualQty", "BilledQty", "Rate", "Amount", "GodownName", "BatchName"
            );

            int vRow = 1;
            int lRow = 1;
            int iRow = 1;
            for (ExchangeDtos.JsonExVoucherDto v : bundle.vouchers()) {
                writeRow(
                    vchSheet, vRow++, v.guid(), v.voucherTypeName(), nullToEmpty(v.voucherNumber()),
                    v.voucherDate().toString(), v.effectiveDate().toString(),
                    nullToEmpty(v.persistedView()), nullToEmpty(v.postingNature()),
                    nullToEmpty(v.partyLedgerName()), nullToEmpty(v.supplierInvoiceNo()), nullToEmpty(v.narration())
                );
                for (ExchangeDtos.JsonExVoucherLineDto line : v.lines()) {
                    writeRow(
                        lineSheet, lRow++, v.guid(), Integer.toString(line.lineNo()),
                        nullToEmpty(line.lineRole()), line.ledgerName(), line.side(), line.amount(),
                        toJson(line.billAllocations())
                    );
                }
                for (ExchangeDtos.JsonExInventoryLineDto inv : v.inventoryLines()) {
                    writeRow(
                        invSheet, iRow++, v.guid(), Integer.toString(inv.lineNo()),
                        inv.stockItemName(), inv.movementDirection(), nullToEmpty(inv.uomSymbol()),
                        inv.actualQty(), inv.billedQty(), inv.rate(), inv.amount(),
                        nullToEmpty(inv.godownName()), nullToEmpty(inv.batchName())
                    );
                }
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to render Excel workbook", ex);
        }
    }

    public ExchangeDtos.ImportDataResultDto importExcelBytes(
        MoneyCraftPrincipal actor,
        UUID companyId,
        byte[] workbookBytes,
        UUID templateId,
        String targetType,
        boolean dryRun
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        if (workbookBytes == null || workbookBytes.length == 0) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXG-EXCEL-EMPTY",
                "Empty Excel Workbook",
                "Provide non-empty .xlsx workbook bytes."
            );
        }

        ExchangeDtos.JsonExDataDto bundle;
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
            if (templateId != null) {
                ExchangeDtos.ExchangeMappingTemplateDto tpl = requireTemplateById(companyId, templateId);
                bundle = parseMappedExcelSheet(wb, tpl);
            } else if (wb.getSheet("Ledgers") != null || wb.getSheet("Vouchers") != null) {
                bundle = parseCanonicalMultiSheetWorkbook(wb);
            } else {
                ExchangeDtos.ExchangeMappingTemplateDto defaultTpl = new ExchangeDtos.ExchangeMappingTemplateDto(
                    null, "Default", "Default", resolveTargetType(targetType, null), "VOUCHERS",
                    wb.getSheetAt(0).getSheetName(), 1, Map.of(), Map.of(), "SKIP", true, 1L, null
                );
                bundle = parseMappedExcelSheet(wb, defaultTpl);
            }
        } catch (IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXG-EXCEL-INVALID",
                "Invalid .xlsx Workbook",
                "Failed to read OpenXML .xlsx workbook: " + ex.getMessage()
            );
        }

        return applyExchangeBundle(actor, companyId, bundle, "EXCEL", "IMPORT_EXCEL", "EXCEL_MAPPED", dryRun);
    }

    public ExchangeDtos.ImportDataResultDto importExcelJsonRequest(
        MoneyCraftPrincipal actor,
        UUID companyId,
        ExchangeDtos.ImportDataRequest request
    ) {
        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXG-EXCEL-EMPTY",
                "Empty Excel Request",
                "Provide base64Content or excelBase64."
            );
        }
        String b64 = firstNonBlank(request.excelBase64(), request.base64Content(), request.content());
        if (b64 == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXG-EXCEL-EMPTY",
                "Missing Base64 Excel Payload",
                "Provide base64Content or excelBase64."
            );
        }
        int commaIdx = b64.indexOf(',');
        if (b64.startsWith("data:") && commaIdx > 0) {
            b64 = b64.substring(commaIdx + 1);
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(b64.trim());
        } catch (IllegalArgumentException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXG-EXCEL-BASE64",
                "Invalid Base64 Excel Payload",
                "Failed to decode base64 .xlsx payload."
            );
        }
        return importExcelBytes(
            actor,
            companyId,
            decoded,
            request.templateId(),
            request.targetType(),
            Boolean.TRUE.equals(request.dryRun())
        );
    }

    // =========================================================================
    // Internal Bundle Export / Import Engine
    // =========================================================================

    private ExchangeDtos.JsonExDataDto collectCompanyExchangeBundle(
        MoneyCraftPrincipal principal,
        UUID companyId,
        ExchangeDtos.ExportDataRequest req
    ) {
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        String scope = req == null || req.entityScope() == null || req.entityScope().isBlank()
            ? "ALL"
            : req.entityScope().trim().toUpperCase(Locale.ROOT);
        boolean includeMasters = !"VOUCHERS".equals(scope)
            || (req != null && Boolean.TRUE.equals(req.includeDependentMasters()));
        boolean includeVouchers = !"MASTERS".equals(scope);

        List<ExchangeDtos.JsonExGroupDto> groups = new ArrayList<>();
        List<ExchangeDtos.JsonExUomDto> uoms = new ArrayList<>();
        List<ExchangeDtos.JsonExGodownDto> godowns = new ArrayList<>();
        List<ExchangeDtos.JsonExStockGroupDto> stockGroups = new ArrayList<>();
        List<ExchangeDtos.JsonExStockItemDto> stockItems = new ArrayList<>();
        List<ExchangeDtos.JsonExLedgerDto> ledgers = new ArrayList<>();
        List<ExchangeDtos.JsonExVoucherDto> vouchers = new ArrayList<>();

        if (includeMasters) {
            groups.addAll(businessJdbcTemplate.query(
                """
                SELECT g.id, g.name, pg.name AS parent_name, g.nature,
                       g.affects_gross_profit, g.behaves_like_subledger
                  FROM mc_account_group g
                  LEFT JOIN mc_account_group pg
                    ON pg.company_id = g.company_id AND pg.id = g.parent_group_id
                 WHERE g.company_id = ?
                 ORDER BY g.depth_level ASC, g.sort_order ASC, lower(g.name) ASC
                """,
                (rs, rowNum) -> new ExchangeDtos.JsonExGroupDto(
                    rs.getObject("id", UUID.class).toString(),
                    rs.getString("name"),
                    rs.getString("parent_name"),
                    rs.getString("nature"),
                    rs.getBoolean("affects_gross_profit"),
                    rs.getBoolean("behaves_like_subledger")
                ),
                companyId
            ));

            for (InventoryMasterDtos.UomDto u : inventoryMasterService.listUoms(principal, companyId)) {
                uoms.add(new ExchangeDtos.JsonExUomDto(u.symbol(), u.formalName(), u.decimalPlaces()));
            }

            godowns.addAll(businessJdbcTemplate.query(
                """
                SELECT g.name, pg.name AS parent_name
                  FROM mc_godown g
                  LEFT JOIN mc_godown pg ON pg.company_id = g.company_id AND pg.id = g.parent_godown_id
                 WHERE g.company_id = ? AND g.active = true
                 ORDER BY g.depth_level ASC, lower(g.name) ASC
                """,
                (rs, rowNum) -> new ExchangeDtos.JsonExGodownDto(
                    rs.getString("name"),
                    rs.getString("parent_name")
                ),
                companyId
            ));

            for (InventoryMasterDtos.StockGroupDto sg : inventoryMasterService.listStockGroups(principal, companyId)) {
                stockGroups.add(new ExchangeDtos.JsonExStockGroupDto(sg.name(), sg.parentStockGroupName()));
            }

            for (InventoryMasterDtos.StockItemDto si : inventoryMasterService.listStockItems(
                principal, companyId, null, null
            )) {
                stockItems.add(new ExchangeDtos.JsonExStockItemDto(
                    si.id().toString(),
                    si.name(),
                    si.stockGroupName(),
                    si.baseUomSymbol(),
                    si.costingMethod(),
                    si.openingQty(),
                    si.openingRate(),
                    si.openingValue()
                ));
            }

            ledgers.addAll(businessJdbcTemplate.query(
                """
                SELECT l.id, l.name, g.name AS group_name, l.opening_balance, trim(l.opening_side) AS opening_side,
                       l.maintain_balances_bill_by_bill, l.gstin, l.state_name, l.tax_type, l.duty_head
                  FROM mc_ledger l
                  LEFT JOIN mc_account_group g ON g.company_id = l.company_id AND g.id = l.group_id
                 WHERE l.company_id = ? AND l.active = true
                 ORDER BY lower(l.name) ASC, l.id ASC
                """,
                (rs, rowNum) -> new ExchangeDtos.JsonExLedgerDto(
                    rs.getObject("id", UUID.class).toString(),
                    rs.getString("name"),
                    rs.getString("group_name"),
                    ExactMoneyValidator.formatExactScale(rs.getBigDecimal("opening_balance"), scale),
                    rs.getString("opening_side"),
                    rs.getBoolean("maintain_balances_bill_by_bill"),
                    rs.getString("gstin"),
                    rs.getString("state_name"),
                    rs.getString("tax_type"),
                    rs.getString("duty_head")
                ),
                companyId
            ));
        }

        if (includeVouchers) {
            List<Object> params = new ArrayList<>();
            params.add(companyId);
            StringBuilder where = new StringBuilder(
                "j.company_id = ? AND j.status IN ('POSTED', 'OPTIONAL')"
            );
            if (req != null && req.fromDate() != null) {
                where.append(" AND j.voucher_date >= ?");
                params.add(req.fromDate());
            }
            if (req != null && req.toDate() != null) {
                where.append(" AND j.voucher_date <= ?");
                params.add(req.toDate());
            }

            List<VoucherHeaderRow> journalIds = businessJdbcTemplate.query(
                "SELECT j.id, COALESCE(j.source_guid, j.id::text) AS guid FROM mc_journal j WHERE "
                    + where + " ORDER BY j.voucher_date ASC, j.master_seq_id ASC",
                (rs, rowNum) -> new VoucherHeaderRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("guid")
                ),
                params.toArray()
            );

            for (VoucherHeaderRow jRow : journalIds) {
                VoucherDtos.VoucherDetailDto d = voucherService.getVoucher(principal, companyId, jRow.id());
                List<ExchangeDtos.JsonExVoucherLineDto> vLines = new ArrayList<>();
                for (VoucherDtos.VoucherLineDetailDto l : d.lines()) {
                    List<ExchangeDtos.JsonExBillAllocationDto> bList = new ArrayList<>();
                    for (VoucherDtos.BillAllocationDto ba : l.billAllocations()) {
                        bList.add(new ExchangeDtos.JsonExBillAllocationDto(
                            ba.allocationType(), ba.billName(), ba.side(), ba.amount()
                        ));
                    }
                    vLines.add(new ExchangeDtos.JsonExVoucherLineDto(
                        l.lineNo(), l.lineRole(), l.ledgerName(), l.side(), l.amount(), bList
                    ));
                }

                List<ExchangeDtos.JsonExInventoryLineDto> iLines = new ArrayList<>();
                int iSeq = 1;
                for (VoucherDtos.VoucherInventoryLineDto inv : d.inventoryLines()) {
                    String gdName = null;
                    String batchName = null;
                    if (inv.batchAllocations() != null && !inv.batchAllocations().isEmpty()) {
                        gdName = inv.batchAllocations().getFirst().godownName();
                        batchName = inv.batchAllocations().getFirst().batchName();
                    }
                    iLines.add(new ExchangeDtos.JsonExInventoryLineDto(
                        inv.lineNo() == null ? iSeq : inv.lineNo(),
                        inv.stockItemName(),
                        inv.movementDirection(),
                        inv.uomSymbol(),
                        inv.actualQty(),
                        inv.billedQty(),
                        inv.rate(),
                        inv.amount(),
                        gdName,
                        batchName
                    ));
                    iSeq++;
                }

                vouchers.add(new ExchangeDtos.JsonExVoucherDto(
                    jRow.guid(),
                    d.voucherTypeName(),
                    d.voucherNumber(),
                    d.voucherDate(),
                    d.effectiveDate(),
                    d.persistedView(),
                    d.postingNature(),
                    d.partyLedgerName(),
                    d.supplierInvoiceNo(),
                    d.narration(),
                    vLines,
                    iLines
                ));
            }
        }

        return new ExchangeDtos.JsonExDataDto(
            groups, uoms, godowns, stockGroups, stockItems, ledgers, vouchers
        );
    }

    private ExchangeDtos.ImportDataResultDto applyExchangeBundle(
        MoneyCraftPrincipal actor,
        UUID companyId,
        ExchangeDtos.JsonExDataDto bundle,
        String format,
        String journalOrigin,
        String jobType,
        boolean dryRun
    ) {
        List<ExchangeDtos.JsonExGroupDto> groups = bundle.groups() == null ? List.of() : bundle.groups();
        List<ExchangeDtos.JsonExUomDto> uoms = bundle.uoms() == null ? List.of() : bundle.uoms();
        List<ExchangeDtos.JsonExGodownDto> godowns = bundle.godowns() == null ? List.of() : bundle.godowns();
        List<ExchangeDtos.JsonExStockGroupDto> stockGroups =
            bundle.stockGroups() == null ? List.of() : bundle.stockGroups();
        List<ExchangeDtos.JsonExStockItemDto> stockItems =
            bundle.stockItems() == null ? List.of() : bundle.stockItems();
        List<ExchangeDtos.JsonExLedgerDto> ledgers = bundle.ledgers() == null ? List.of() : bundle.ledgers();
        List<ExchangeDtos.JsonExVoucherDto> vouchers = bundle.vouchers() == null ? List.of() : bundle.vouchers();

        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        BigDecimal totalDr = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        BigDecimal totalCr = BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        for (ExchangeDtos.JsonExVoucherDto v : vouchers) {
            if (v.lines() == null) {
                continue;
            }
            for (ExchangeDtos.JsonExVoucherLineDto l : v.lines()) {
                BigDecimal amt = ExactMoneyValidator.parseAndValidateExactScale(
                    l.amount(), scale, "voucherLine.amount"
                );
                if ("DR".equalsIgnoreCase(l.side())) {
                    totalDr = totalDr.add(amt);
                } else {
                    totalCr = totalCr.add(amt);
                }
            }
        }
        boolean balanced = totalDr.compareTo(totalCr) == 0;
        if (!balanced) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXG-UNBALANCED-PAYLOAD",
                "Unbalanced Exchange Payload",
                "Imported voucher lines total DR " + totalDr + " != total CR " + totalCr + "."
            );
        }

        if (dryRun) {
            return new ExchangeDtos.ImportDataResultDto(
                null,
                format,
                "DRY_RUN_VALIDATED",
                groups.size(),
                ledgers.size(),
                stockItems.size(),
                vouchers.size(),
                totalDr.toPlainString(),
                totalCr.toPlainString(),
                true,
                format + " dry-run validation succeeded with zero balance variance."
            );
        }

        BigDecimal finalDr = totalDr;
        BigDecimal finalCr = totalCr;
        return businessTxTemplate.execute(tx -> {
            ensurePrincipalProjected(actor);
            String baseCurrency = sequenceHelper.readCompanyCurrencyMeta(companyId).baseCurrencyCode();

            if (!vouchers.isEmpty()) {
                LocalDate minD = vouchers.stream()
                    .map(ExchangeDtos.JsonExVoucherDto::voucherDate)
                    .filter(d -> d != null)
                    .min(Comparator.naturalOrder())
                    .orElse(LocalDate.of(2025, 4, 1));
                LocalDate maxD = vouchers.stream()
                    .map(ExchangeDtos.JsonExVoucherDto::voucherDate)
                    .filter(d -> d != null)
                    .max(Comparator.naturalOrder())
                    .orElse(minD);
                ensureFiscalYearsCoverRange(companyId, actor.principalId(), minD, maxD);
            }

            int createdGroups = upsertGroups(actor, companyId, groups);
            Map<String, UUID> uomMap = upsertUoms(actor, companyId, uoms);
            Map<String, UUID> gdMap = upsertGodowns(actor, companyId, godowns);
            Map<String, UUID> sgMap = upsertStockGroups(actor, companyId, stockGroups);

            Set<String> billWiseLedgerNames = new HashSet<>();
            for (ExchangeDtos.JsonExVoucherDto v : vouchers) {
                if (v.lines() != null) {
                    for (ExchangeDtos.JsonExVoucherLineDto l : v.lines()) {
                        if (l.billAllocations() != null && !l.billAllocations().isEmpty()) {
                            billWiseLedgerNames.add(l.ledgerName().trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }

            UpsertedLedgersResult ledgerRes = upsertLedgers(
                actor, companyId, scale, baseCurrency, ledgers, billWiseLedgerNames
            );

            UUID salesLedger = ledgerRes.byLowerName().values().stream()
                .filter(r -> "INCOME".equals(r.nature()))
                .map(LedgerRef::id)
                .findFirst()
                .orElse(null);
            UUID purchaseLedger = ledgerRes.byLowerName().values().stream()
                .filter(r -> "EXPENSES".equals(r.nature()))
                .map(LedgerRef::id)
                .findFirst()
                .orElse(null);

            UpsertedItemsResult itemRes = upsertStockItems(
                actor, companyId, scale, stockItems, uomMap, sgMap,
                gdMap.get("main location"), salesLedger, purchaseLedger
            );

            int createdVouchers = upsertVouchers(
                actor, companyId, scale, baseCurrency, journalOrigin,
                vouchers, ledgerRes.byLowerName(), itemRes.byLowerName(), uomMap, gdMap
            );

            UUID jobId = UUID.randomUUID();
            int totalRead = groups.size() + ledgers.size() + stockItems.size() + vouchers.size();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_import_job (
                    id, company_id, job_type, status, total_records_read, valid_records_count,
                    error_records_count, diagnostics_json, initiated_by, committed_by, completed_at
                ) VALUES (?, ?, ?, 'COMMITTED', ?, ?, 0, ?::jsonb, ?, ?, clock_timestamp())
                """,
                jobId,
                companyId,
                jobType,
                totalRead,
                totalRead,
                toJson(Map.of("sourceCompanyName", format + " Exchange Import")),
                actor.principalId(),
                actor.principalId()
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "EXCHANGE_IMPORT_" + format,
                "IMPORT_JOB",
                jobId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "groupsImported", createdGroups,
                    "ledgersImported", ledgerRes.touchedCount(),
                    "stockItemsImported", itemRes.createdCount(),
                    "vouchersImported", createdVouchers
                )
            );

            return new ExchangeDtos.ImportDataResultDto(
                jobId,
                format,
                "IMPORTED",
                createdGroups,
                ledgerRes.touchedCount(),
                itemRes.createdCount(),
                createdVouchers,
                finalDr.toPlainString(),
                finalCr.toPlainString(),
                true,
                format + " import committed with zero double-entry variance."
            );
        });
    }

    private int upsertGroups(
        MoneyCraftPrincipal actor,
        UUID companyId,
        List<ExchangeDtos.JsonExGroupDto> groups
    ) {
        Map<String, ExistingGroup> byLower = loadGroupsMap(companyId);
        List<ExchangeDtos.JsonExGroupDto> pending = new ArrayList<>();
        for (ExchangeDtos.JsonExGroupDto g : groups) {
            if (g.name() != null && !g.name().isBlank()
                && !byLower.containsKey(g.name().trim().toLowerCase(Locale.ROOT))) {
                pending.add(g);
            }
        }
        int created = 0;
        int maxPasses = pending.size() + 2;
        for (int pass = 0; pass < maxPasses && !pending.isEmpty(); pass++) {
            List<ExchangeDtos.JsonExGroupDto> next = new ArrayList<>();
            for (ExchangeDtos.JsonExGroupDto g : pending) {
                String pKey = g.parent() == null ? "" : g.parent().trim().toLowerCase(Locale.ROOT);
                boolean root = pKey.isEmpty() || "primary".equals(pKey);
                ExistingGroup parent = root ? null : byLower.get(pKey);
                if (!root && parent == null && pass < maxPasses - 1) {
                    next.add(g);
                    continue;
                }
                String nature = parent != null
                    ? parent.nature()
                    : (g.nature() == null || g.nature().isBlank() ? "LIABILITIES" : g.nature());
                int depth = parent == null ? 0 : parent.depth() + 1;
                CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, true);
                UUID id = UUID.randomUUID();
                String clean = g.name().trim();
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_account_group (
                        id, company_id, name, parent_group_id, nature,
                        affects_gross_profit, behaves_like_subledger,
                        depth_level, is_system_group, sort_order,
                        master_seq_id, alter_id, version_no
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, false, 500, ?, ?, 1)
                    """,
                    id, companyId, clean, parent == null ? null : parent.id(), nature,
                    g.affectsGrossProfit(), g.behavesLikeSubledger(), (short) depth,
                    seq.masterSeqId(), seq.alterId()
                );
                byLower.put(clean.toLowerCase(Locale.ROOT), new ExistingGroup(id, clean, nature, depth));
                editLogSnapshotService.recordVersion(
                    companyId, "ACCOUNT_GROUP", id, seq.masterSeqId(), seq.alterId(), 1L,
                    "CREATE", actor.principalId(), actor.username(), clean, null, null,
                    Map.of("id", id.toString(), "name", clean, "nature", nature)
                );
                created++;
            }
            pending = next;
        }
        return created;
    }

    private Map<String, UUID> upsertUoms(
        MoneyCraftPrincipal actor,
        UUID companyId,
        List<ExchangeDtos.JsonExUomDto> uoms
    ) {
        Map<String, UUID> map = new HashMap<>();
        for (InventoryMasterDtos.UomDto existing : inventoryMasterService.listUoms(actor, companyId)) {
            map.put(existing.symbol().trim().toLowerCase(Locale.ROOT), existing.id());
        }
        for (ExchangeDtos.JsonExUomDto u : uoms) {
            if (u.symbol() == null || u.symbol().isBlank()) {
                continue;
            }
            String sym = u.symbol().trim();
            if (map.containsKey(sym.toLowerCase(Locale.ROOT))) {
                continue;
            }
            InventoryMasterDtos.UomDto created = inventoryMasterService.createUom(
                actor,
                companyId,
                new InventoryMasterDtos.UpsertUomRequest(
                    "SIMPLE",
                    sym,
                    u.formalName() == null || u.formalName().isBlank() ? sym : u.formalName().trim(),
                    null,
                    Math.max(0, Math.min(6, u.decimalPlaces())),
                    null,
                    null,
                    null,
                    true,
                    null
                )
            );
            map.put(sym.toLowerCase(Locale.ROOT), created.id());
        }
        return map;
    }

    private Map<String, UUID> upsertGodowns(
        MoneyCraftPrincipal actor,
        UUID companyId,
        List<ExchangeDtos.JsonExGodownDto> godowns
    ) {
        Map<String, UUID> map = new HashMap<>();
        businessJdbcTemplate.query(
            "SELECT id, name FROM mc_godown WHERE company_id = ?",
            (rs, rowNum) -> {
                map.put(rs.getString("name").trim().toLowerCase(Locale.ROOT), rs.getObject("id", UUID.class));
                return null;
            },
            companyId
        );
        for (ExchangeDtos.JsonExGodownDto gd : godowns) {
            if (gd.name() == null || gd.name().isBlank()) {
                continue;
            }
            String name = gd.name().trim();
            if (map.containsKey(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            UUID parentId = gd.parent() == null ? null : map.get(gd.parent().trim().toLowerCase(Locale.ROOT));
            InventoryMasterDtos.GodownDto created = inventoryMasterService.createGodown(
                actor, companyId,
                new InventoryMasterDtos.UpsertGodownRequest(name, null, parentId, null, true, false, false, true, null)
            );
            map.put(name.toLowerCase(Locale.ROOT), created.id());
        }
        return map;
    }

    private Map<String, UUID> upsertStockGroups(
        MoneyCraftPrincipal actor,
        UUID companyId,
        List<ExchangeDtos.JsonExStockGroupDto> stockGroups
    ) {
        Map<String, UUID> map = new HashMap<>();
        for (InventoryMasterDtos.StockGroupDto sg : inventoryMasterService.listStockGroups(actor, companyId)) {
            map.put(sg.name().trim().toLowerCase(Locale.ROOT), sg.id());
        }
        for (ExchangeDtos.JsonExStockGroupDto sg : stockGroups) {
            if (sg.name() == null || sg.name().isBlank()) {
                continue;
            }
            String name = sg.name().trim();
            if (map.containsKey(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            UUID parentId = sg.parent() == null ? null : map.get(sg.parent().trim().toLowerCase(Locale.ROOT));
            InventoryMasterDtos.StockGroupDto created = inventoryMasterService.createStockGroup(
                actor, companyId,
                new InventoryMasterDtos.UpsertStockGroupRequest(name, null, parentId, true, null, null, null, true, null)
            );
            map.put(name.toLowerCase(Locale.ROOT), created.id());
        }
        return map;
    }

    private UpsertedLedgersResult upsertLedgers(
        MoneyCraftPrincipal actor,
        UUID companyId,
        int scale,
        String baseCurrency,
        List<ExchangeDtos.JsonExLedgerDto> ledgers,
        Set<String> billWiseLedgerNames
    ) {
        Map<String, ExistingGroup> groups = loadGroupsMap(companyId);
        Map<String, LedgerRef> byLower = loadLedgersMap(companyId);
        int touched = 0;

        for (ExchangeDtos.JsonExLedgerDto l : ledgers) {
            if (l.name() == null || l.name().isBlank()) {
                continue;
            }
            String clean = l.name().trim();
            String lower = clean.toLowerCase(Locale.ROOT);
            BigDecimal ob = l.openingBalance() == null || l.openingBalance().isBlank()
                ? BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY)
                : ExactMoneyValidator.parseAndValidateExactScale(l.openingBalance(), scale, "openingBalance");
            String side = "DR".equalsIgnoreCase(l.openingSide()) ? "DR" : "CR";
            boolean billWise = l.maintainBalancesBillByBill() || billWiseLedgerNames.contains(lower);
            String gstin = sanitizeOptionalGstin(l.gstin());
            String stateName = l.stateName() == null || l.stateName().isBlank() ? null : l.stateName().trim();

            LedgerRef existing = byLower.get(lower);
            if (existing != null) {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_ledger
                       SET opening_balance = ?,
                           opening_side = ?,
                           maintain_balances_bill_by_bill = maintain_balances_bill_by_bill OR ?,
                           gstin = COALESCE(?, gstin),
                           state_name = COALESCE(?, state_name),
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    ob, side, billWise, gstin, stateName, companyId, existing.id()
                );
                byLower.put(lower, new LedgerRef(existing.id(), existing.name(), existing.nature(),
                    existing.billWise() || billWise));
                touched++;
                continue;
            }

            boolean isPnl = "profit & loss a/c".equals(lower);
            ExistingGroup pg = isPnl ? null : resolveGroup(l.groupName(), groups);
            UUID groupId = pg == null ? null : pg.id();
            String nature = pg == null ? "LIABILITIES" : pg.nature();
            String taxType = l.taxType() != null && !l.taxType().isBlank() ? l.taxType() : "NONE";
            String dutyHead = l.dutyHead() != null && !l.dutyHead().isBlank() ? l.dutyHead() : null;

            CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, true);
            UUID id = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_ledger (
                    id, company_id, name, group_id, opening_balance, opening_side, currency_code,
                    maintain_balances_bill_by_bill, credit_limit, inventory_values_affected,
                    cost_centres_applicable, tax_type, duty_head, gst_applicability, appropriate_for,
                    party_registration_type, gstin, state_name,
                    is_system_ledger, active, master_seq_id, alter_id, version_no, created_by
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?,
                    ?, 0, false,
                    false, ?, ?, 'NOT_APPLICABLE', 'NONE',
                    ?, ?, ?,
                    false, true, ?, ?, 1, ?
                )
                """,
                id, companyId, clean, groupId, ob, side, baseCurrency,
                billWise, taxType, dutyHead, gstin != null ? "REGULAR" : "UNKNOWN", gstin, stateName,
                seq.masterSeqId(), seq.alterId(), actor.principalId()
            );
            byLower.put(lower, new LedgerRef(id, clean, nature, billWise));
            editLogSnapshotService.recordVersion(
                companyId, "LEDGER", id, seq.masterSeqId(), seq.alterId(), 1L,
                "CREATE", actor.principalId(), actor.username(), clean, null, null,
                Map.of("id", id.toString(), "name", clean, "openingBalance", ob.toPlainString(), "openingSide", side)
            );
            touched++;
        }
        return new UpsertedLedgersResult(byLower, touched);
    }

    private UpsertedItemsResult upsertStockItems(
        MoneyCraftPrincipal actor,
        UUID companyId,
        int scale,
        List<ExchangeDtos.JsonExStockItemDto> items,
        Map<String, UUID> uomMap,
        Map<String, UUID> sgMap,
        UUID mainGodownId,
        UUID salesLedger,
        UUID purchaseLedger
    ) {
        Map<String, ItemRef> map = new HashMap<>();
        for (InventoryMasterDtos.StockItemDto existing : inventoryMasterService.listStockItems(
            actor, companyId, null, null
        )) {
            map.put(
                existing.name().trim().toLowerCase(Locale.ROOT),
                new ItemRef(existing.id(), existing.name(), existing.baseUomId())
            );
        }
        int createdCount = 0;
        for (ExchangeDtos.JsonExStockItemDto si : items) {
            if (si.name() == null || si.name().isBlank()) {
                continue;
            }
            String clean = si.name().trim();
            if (map.containsKey(clean.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String sym = si.baseUom() == null || si.baseUom().isBlank() ? "Nos" : si.baseUom().trim();
            UUID uomId = uomMap.get(sym.toLowerCase(Locale.ROOT));
            if (uomId == null) {
                InventoryMasterDtos.UomDto createdUom = inventoryMasterService.createUom(
                    actor, companyId,
                    new InventoryMasterDtos.UpsertUomRequest("SIMPLE", sym, sym, null, 2, null, null, null, true, null)
                );
                uomId = createdUom.id();
                uomMap.put(sym.toLowerCase(Locale.ROOT), uomId);
            }
            UUID sgId = si.stockGroup() == null ? null : sgMap.get(si.stockGroup().trim().toLowerCase(Locale.ROOT));
            String openQty = si.openingQty() == null || si.openingQty().isBlank() ? "0" : si.openingQty();
            String openVal = si.openingValue() == null || si.openingValue().isBlank()
                ? ExactMoneyValidator.formatExactScale(BigDecimal.ZERO, scale)
                : si.openingValue();
            BigDecimal qDec = new BigDecimal(openQty).abs();
            BigDecimal vDec = new BigDecimal(openVal).abs().setScale(scale, RoundingMode.HALF_UP);
            String openRate = si.openingRate() != null && !si.openingRate().isBlank()
                ? si.openingRate()
                : (qDec.signum() > 0 ? vDec.divide(qDec, 6, RoundingMode.HALF_UP).toPlainString() : "0");

            InventoryMasterDtos.StockItemDto created = inventoryMasterService.createStockItem(
                actor,
                companyId,
                new InventoryMasterDtos.UpsertStockItemRequest(
                    clean, null, null, null, sgId, null, uomId, null, null, null,
                    false, false, false, mapCosting(si.costingMethod()), "AVG_PRICE", null, null,
                    qDec.toPlainString(), openRate, vDec.toPlainString(), mainGodownId, "Primary Batch",
                    null, null, "APPLICABLE", "GOODS", null, null, null, null, false, null,
                    salesLedger, purchaseLedger, true, null
                )
            );
            map.put(clean.toLowerCase(Locale.ROOT), new ItemRef(created.id(), clean, uomId));
            createdCount++;
        }
        return new UpsertedItemsResult(map, createdCount);
    }

    private int upsertVouchers(
        MoneyCraftPrincipal actor,
        UUID companyId,
        int scale,
        String baseCurrency,
        String origin,
        List<ExchangeDtos.JsonExVoucherDto> vouchers,
        Map<String, LedgerRef> ledgers,
        Map<String, ItemRef> items,
        Map<String, UUID> uoms,
        Map<String, UUID> godowns
    ) {
        businessJdbcTemplate.update(
            "UPDATE mc_voucher_type SET prevent_duplicate_numbers = false WHERE company_id = ?",
            companyId
        );

        List<ExchangeDtos.JsonExVoucherDto> sorted = new ArrayList<>(vouchers);
        sorted.sort(
            Comparator.comparing((ExchangeDtos.JsonExVoucherDto v) ->
                v.voucherDate() == null ? LocalDate.of(2025, 4, 1) : v.voucherDate()
            ).thenComparing(DataExchangeService::hasAgstRef)
        );

        UUID mainGodownId = godowns.get("main location");
        int imported = 0;

        for (ExchangeDtos.JsonExVoucherDto v : sorted) {
            if (v.guid() != null && !v.guid().isBlank() && existsJournalBySourceGuid(companyId, v.guid().trim())) {
                continue;
            }
            if (v.lines() == null || v.lines().isEmpty()) {
                continue;
            }
            LocalDate vDate = v.voucherDate() == null ? LocalDate.of(2025, 4, 1) : v.voucherDate();
            LocalDate effDate = v.effectiveDate() == null ? vDate : v.effectiveDate();

            UUID partyLedgerId = null;
            if (v.partyLedgerName() != null && !v.partyLedgerName().isBlank()) {
                LedgerRef p = ledgers.get(v.partyLedgerName().trim().toLowerCase(Locale.ROOT));
                if (p != null) {
                    partyLedgerId = p.id();
                }
            }

            List<VoucherDtos.VoucherLineRequest> reqLines = new ArrayList<>();
            UUID fallbackAccLedgerId = null;
            int lineSeq = 1;
            for (ExchangeDtos.JsonExVoucherLineDto l : v.lines()) {
                LedgerRef lRef = ensureLedger(actor, companyId, baseCurrency, l.ledgerName().trim(), ledgers);
                String side = "DR".equalsIgnoreCase(l.side()) ? "DR" : "CR";
                BigDecimal amt = ExactMoneyValidator.parseAndValidateExactScale(l.amount(), scale, "amount");
                if (amt.signum() == 0) {
                    continue;
                }
                String amtStr = ExactMoneyValidator.formatExactScale(amt, scale);
                if ("INCOME".equals(lRef.nature()) || "EXPENSES".equals(lRef.nature())) {
                    fallbackAccLedgerId = lRef.id();
                }
                if (partyLedgerId == null && "PARTY".equalsIgnoreCase(l.lineRole())) {
                    partyLedgerId = lRef.id();
                }

                List<VoucherDtos.BillAllocationDto> bAllocs = new ArrayList<>();
                BigDecimal bSum = BigDecimal.ZERO;
                if (l.billAllocations() != null) {
                    for (ExchangeDtos.JsonExBillAllocationDto ba : l.billAllocations()) {
                        if (ba.billName() == null || ba.billName().isBlank()) {
                            continue;
                        }
                        BigDecimal bAmt = ExactMoneyValidator.parseAndValidateExactScale(
                            ba.amount(), scale, "billAmount"
                        );
                        if (bAmt.signum() == 0) {
                            continue;
                        }
                        String bSide = "DR".equalsIgnoreCase(ba.side()) ? "DR" : "CR";
                        String bType = normalizeBillAllocType(ba.allocationType());
                        bAllocs.add(new VoucherDtos.BillAllocationDto(
                            null, bType, ba.billName().trim(), null, null, bSide,
                            ExactMoneyValidator.formatExactScale(bAmt, scale),
                            null, null, null, null, "AGST_REF".equals(bType)
                        ));
                        bSum = bSum.add(bSide.equals(side) ? bAmt : bAmt.negate());
                    }
                }
                if (lRef.billWise() && bAllocs.isEmpty()) {
                    bAllocs.add(new VoucherDtos.BillAllocationDto(
                        null, "ON_ACCOUNT", "On Account", null, null, side, amtStr,
                        null, null, null, null, true
                    ));
                } else if (!bAllocs.isEmpty() && bSum.compareTo(amt) != 0) {
                    BigDecimal diff = amt.subtract(bSum);
                    String diffSide = diff.signum() >= 0 ? side : ("DR".equals(side) ? "CR" : "DR");
                    bAllocs.add(new VoucherDtos.BillAllocationDto(
                        null, "ON_ACCOUNT", "On Account", null, null, diffSide,
                        ExactMoneyValidator.formatExactScale(diff.abs(), scale),
                        null, null, null, null, true
                    ));
                }

                String role = (partyLedgerId != null && partyLedgerId.equals(lRef.id())) ? "PARTY" : "LEDGER";
                reqLines.add(new VoucherDtos.VoucherLineRequest(
                    lineSeq++, role, lRef.id(), side, amtStr,
                    null, null, null, null, null, false, null, null,
                    bAllocs, List.of(), List.of()
                ));
            }

            List<VoucherDtos.VoucherInventoryLineDto> invLines = new ArrayList<>();
            int invSeq = 1;
            if (v.inventoryLines() != null) {
                for (ExchangeDtos.JsonExInventoryLineDto inv : v.inventoryLines()) {
                    if (inv.stockItemName() == null || inv.stockItemName().isBlank()) {
                        continue;
                    }
                    ItemRef itemRef = items.get(inv.stockItemName().trim().toLowerCase(Locale.ROOT));
                    if (itemRef == null) {
                        continue;
                    }
                    UUID uomId = inv.uomSymbol() == null || inv.uomSymbol().isBlank()
                        ? itemRef.baseUomId()
                        : uoms.getOrDefault(inv.uomSymbol().trim().toLowerCase(Locale.ROOT), itemRef.baseUomId());
                    UUID gdId = inv.godownName() == null || inv.godownName().isBlank()
                        ? mainGodownId
                        : godowns.getOrDefault(inv.godownName().trim().toLowerCase(Locale.ROOT), mainGodownId);
                    String bName = inv.batchName() == null || inv.batchName().isBlank()
                        ? "Primary Batch"
                        : inv.batchName().trim();

                    VoucherDtos.BatchAllocationDto bDto = new VoucherDtos.BatchAllocationDto(
                        null, gdId, null, null, bName, inv.actualQty(), inv.billedQty(),
                        inv.rate(), inv.amount(), null, null, true
                    );
                    invLines.add(new VoucherDtos.VoucherInventoryLineDto(
                        null, invSeq++, fallbackAccLedgerId, itemRef.id(), itemRef.name(),
                        inv.movementDirection(), uomId, null, inv.actualQty(), inv.billedQty(),
                        inv.rate(), "0", inv.amount(), null, null, null, null, true, List.of(bDto)
                    ));
                }
            }

            String view = invLines.isEmpty() ? "AS_VOUCHER" : "ITEM_INVOICE";
            String nature = "OPTIONAL".equalsIgnoreCase(v.postingNature()) ? "OPTIONAL" : "NORMAL";
            String vtName = resolveVoucherTypeName(companyId, v.voucherTypeName());

            voucherService.createImportedVoucher(
                actor,
                companyId,
                new VoucherDtos.UpsertVoucherRequest(
                    null, vtName, null,
                    v.voucherNumber() == null || v.voucherNumber().isBlank() ? null : v.voucherNumber().trim(),
                    vDate, effDate, null, view, nature, "POST",
                    v.supplierInvoiceNo(), null, partyLedgerId, fallbackAccLedgerId,
                    null, null, null, null, null, false, null, baseCurrency, "1",
                    v.narration(), v.guid(), null, reqLines, invLines
                ),
                origin,
                "CREATE"
            );
            imported++;
        }
        return imported;
    }

    // =========================================================================
    // XML & Excel Parsing Helpers
    // =========================================================================

    public static String stripTallyControlCharacters(String rawXml) {
        String withoutEntities = NUMERIC_ENTITY_CTRL_PATTERN.matcher(rawXml).replaceAll("");
        return RAW_CTRL_CHARS_PATTERN.matcher(withoutEntities).replaceAll("");
    }

    private static Document parseXmlSafely(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXG-XML-INVALID",
                "Invalid Tally XML Payload",
                "Failed to parse Tally <ENVELOPE> XML: " + ex.getMessage()
            );
        }
    }

    private ExchangeDtos.JsonExDataDto parseEnvelopeDomToBundle(Document doc) {
        int scale = 2;
        List<ExchangeDtos.JsonExGroupDto> groups = new ArrayList<>();
        NodeList groupNodes = doc.getElementsByTagName("GROUP");
        for (int i = 0; i < groupNodes.getLength(); i++) {
            Element el = (Element) groupNodes.item(i);
            String name = firstNonBlank(el.getAttribute("NAME"), childText(el, "NAME"));
            if (name == null) {
                continue;
            }
            String guid = firstNonBlank(childText(el, "GUID"), UUID.randomUUID().toString());
            String parent = childText(el, "PARENT");
            boolean isRev = isYes(childText(el, "ISREVENUE"));
            boolean deemedPos = isYes(childText(el, "ISDEEMEDPOSITIVE"));
            String nature = isRev ? (deemedPos ? "EXPENSES" : "INCOME") : (deemedPos ? "ASSETS" : "LIABILITIES");
            groups.add(new ExchangeDtos.JsonExGroupDto(
                guid, name, parent, nature,
                isYes(childText(el, "AFFECTSGROSSPROFIT")),
                isYes(childText(el, "ISSUBLEDGER"))
            ));
        }

        List<ExchangeDtos.JsonExUomDto> uoms = new ArrayList<>();
        NodeList unitNodes = doc.getElementsByTagName("UNIT");
        for (int i = 0; i < unitNodes.getLength(); i++) {
            Element el = (Element) unitNodes.item(i);
            String sym = firstNonBlank(el.getAttribute("NAME"), childText(el, "NAME"));
            if (sym == null) {
                continue;
            }
            String decStr = childText(el, "DECIMALPLACES");
            int dec = decStr == null || decStr.isBlank() ? 2 : Integer.parseInt(decStr.trim());
            uoms.add(new ExchangeDtos.JsonExUomDto(sym, childText(el, "ORIGINALNAME"), dec));
        }

        List<ExchangeDtos.JsonExGodownDto> godowns = new ArrayList<>();
        NodeList gdNodes = doc.getElementsByTagName("GODOWN");
        for (int i = 0; i < gdNodes.getLength(); i++) {
            Element el = (Element) gdNodes.item(i);
            String name = firstNonBlank(el.getAttribute("NAME"), childText(el, "NAME"));
            if (name != null) {
                godowns.add(new ExchangeDtos.JsonExGodownDto(name, childText(el, "PARENT")));
            }
        }

        List<ExchangeDtos.JsonExStockGroupDto> stockGroups = new ArrayList<>();
        NodeList sgNodes = doc.getElementsByTagName("STOCKGROUP");
        for (int i = 0; i < sgNodes.getLength(); i++) {
            Element el = (Element) sgNodes.item(i);
            String name = firstNonBlank(el.getAttribute("NAME"), childText(el, "NAME"));
            if (name != null) {
                stockGroups.add(new ExchangeDtos.JsonExStockGroupDto(name, childText(el, "PARENT")));
            }
        }

        List<ExchangeDtos.JsonExStockItemDto> stockItems = new ArrayList<>();
        NodeList siNodes = doc.getElementsByTagName("STOCKITEM");
        for (int i = 0; i < siNodes.getLength(); i++) {
            Element el = (Element) siNodes.item(i);
            String name = firstNonBlank(el.getAttribute("NAME"), childText(el, "NAME"));
            if (name == null) {
                continue;
            }
            String openQty = parseLeadingDecimal(childText(el, "OPENINGBALANCE"), "0");
            String openRate = parseLeadingDecimal(childText(el, "OPENINGRATE"), "0");
            BigDecimal openVal = new BigDecimal(parseLeadingDecimal(childText(el, "OPENINGVALUE"), "0.00"))
                .abs().setScale(scale, RoundingMode.HALF_UP);
            stockItems.add(new ExchangeDtos.JsonExStockItemDto(
                firstNonBlank(childText(el, "GUID"), UUID.randomUUID().toString()),
                name,
                childText(el, "PARENT"),
                childText(el, "BASEUNITS"),
                childText(el, "COSTINGMETHOD"),
                openQty,
                openRate,
                openVal.toPlainString()
            ));
        }

        List<ExchangeDtos.JsonExLedgerDto> ledgers = new ArrayList<>();
        NodeList ledNodes = doc.getElementsByTagName("LEDGER");
        for (int i = 0; i < ledNodes.getLength(); i++) {
            Element el = (Element) ledNodes.item(i);
            String name = firstNonBlank(el.getAttribute("NAME"), childText(el, "NAME"));
            if (name == null) {
                continue;
            }
            BigDecimal signedOb = new BigDecimal(parseLeadingDecimal(childText(el, "OPENINGBALANCE"), "0.00"));
            String obSide = signedOb.signum() < 0 ? "DR" : "CR";
            String obStr = signedOb.abs().setScale(scale, RoundingMode.HALF_UP).toPlainString();
            ledgers.add(new ExchangeDtos.JsonExLedgerDto(
                firstNonBlank(childText(el, "GUID"), UUID.randomUUID().toString()),
                name,
                childText(el, "PARENT"),
                obStr,
                obSide,
                isYes(childText(el, "ISBILLWISEON")),
                childText(el, "PARTYGSTIN"),
                childText(el, "LEDSTATENAME"),
                "NONE",
                null
            ));
        }

        List<ExchangeDtos.JsonExVoucherDto> vouchers = new ArrayList<>();
        NodeList vNodes = doc.getElementsByTagName("VOUCHER");
        for (int i = 0; i < vNodes.getLength(); i++) {
            Element el = (Element) vNodes.item(i);
            if (isYes(childText(el, "ISDELETED")) || isYes(childText(el, "ISCANCELLED"))) {
                continue;
            }
            String guid = firstNonBlank(childText(el, "GUID"), el.getAttribute("REMOTEID"), UUID.randomUUID().toString());
            String vtName = firstNonBlank(childText(el, "VOUCHERTYPENAME"), el.getAttribute("VCHTYPE"), "Journal");
            LocalDate vDate = parseTallyDate(childText(el, "DATE"));
            LocalDate effDate = parseTallyDate(firstNonBlank(childText(el, "EFFECTIVEDATE"), childText(el, "DATE")));
            boolean optional = isYes(childText(el, "ISOPTIONAL"));

            List<ExchangeDtos.JsonExVoucherLineDto> lines = new ArrayList<>();
            List<Element> entryEls = directChildrenByTag(el, "ALLLEDGERENTRIES.LIST");
            if (entryEls.isEmpty()) {
                entryEls = directChildrenByTag(el, "LEDGERENTRIES.LIST");
            }
            int lNo = 1;
            for (Element le : entryEls) {
                String lName = childText(le, "LEDGERNAME");
                if (lName == null || lName.isBlank()) {
                    continue;
                }
                BigDecimal signedAmt = new BigDecimal(parseLeadingDecimal(childText(le, "AMOUNT"), "0.00"));
                boolean deemedPos = isYes(childText(le, "ISDEEMEDPOSITIVE"));
                String side = (signedAmt.signum() < 0 || (signedAmt.signum() == 0 && deemedPos)) ? "DR" : "CR";
                String amtStr = signedAmt.abs().setScale(scale, RoundingMode.HALF_UP).toPlainString();

                List<ExchangeDtos.JsonExBillAllocationDto> bAllocs = new ArrayList<>();
                for (Element be : directChildrenByTag(le, "BILLALLOCATIONS.LIST")) {
                    String bName = childText(be, "NAME");
                    if (bName == null || bName.isBlank()) {
                        continue;
                    }
                    BigDecimal bSigned = new BigDecimal(parseLeadingDecimal(childText(be, "AMOUNT"), "0.00"));
                    if (bSigned.signum() == 0) {
                        continue;
                    }
                    bAllocs.add(new ExchangeDtos.JsonExBillAllocationDto(
                        normalizeBillAllocType(childText(be, "BILLTYPE")),
                        bName,
                        bSigned.signum() < 0 ? "DR" : "CR",
                        bSigned.abs().setScale(scale, RoundingMode.HALF_UP).toPlainString()
                    ));
                }

                lines.add(new ExchangeDtos.JsonExVoucherLineDto(
                    lNo++,
                    isYes(childText(le, "ISPARTYLEDGER")) ? "PARTY" : "LEDGER",
                    lName,
                    side,
                    amtStr,
                    bAllocs
                ));
            }

            List<ExchangeDtos.JsonExInventoryLineDto> invLines = new ArrayList<>();
            int iNo = 1;
            for (Element ie : directChildrenByTag(el, "ALLINVENTORYENTRIES.LIST")) {
                String itemName = childText(ie, "STOCKITEMNAME");
                if (itemName == null || itemName.isBlank()) {
                    continue;
                }
                boolean inward = isYes(childText(ie, "ISDEEMEDPOSITIVE"));
                String actualQty = parseLeadingDecimal(childText(ie, "ACTUALQTY"), "0");
                String billedQty = parseLeadingDecimal(childText(ie, "BILLEDQTY"), actualQty);
                String rate = parseLeadingDecimal(childText(ie, "RATE"), "0");
                String amt = new BigDecimal(parseLeadingDecimal(childText(ie, "AMOUNT"), "0.00"))
                    .abs().setScale(scale, RoundingMode.HALF_UP).toPlainString();
                String uom = childText(ie, "UOM");
                String gdName = null;
                String batchName = null;
                List<Element> batchEls = directChildrenByTag(ie, "BATCHALLOCATIONS.LIST");
                if (!batchEls.isEmpty()) {
                    gdName = childText(batchEls.getFirst(), "GODOWNNAME");
                    batchName = childText(batchEls.getFirst(), "BATCHNAME");
                }
                invLines.add(new ExchangeDtos.JsonExInventoryLineDto(
                    iNo++, itemName, inward ? "INWARD" : "OUTWARD", uom,
                    actualQty, billedQty, rate, amt, gdName, batchName
                ));
            }

            vouchers.add(new ExchangeDtos.JsonExVoucherDto(
                guid, vtName, childText(el, "VOUCHERNUMBER"), vDate, effDate,
                invLines.isEmpty() ? "AS_VOUCHER" : "ITEM_INVOICE",
                optional ? "OPTIONAL" : "NORMAL",
                childText(el, "PARTYLEDGERNAME"),
                childText(el, "REFERENCE"),
                childText(el, "NARRATION"),
                lines,
                invLines
            ));
        }

        return new ExchangeDtos.JsonExDataDto(
            groups, uoms, godowns, stockGroups, stockItems, ledgers, vouchers
        );
    }

    private ExchangeDtos.JsonExDataDto parseCanonicalMultiSheetWorkbook(Workbook wb) {
        DataFormatter fmt = new DataFormatter();
        List<ExchangeDtos.JsonExGroupDto> groups = new ArrayList<>();
        Sheet gSheet = wb.getSheet("Groups");
        if (gSheet != null) {
            for (int r = 1; r <= gSheet.getLastRowNum(); r++) {
                Row row = gSheet.getRow(r);
                String name = cellText(row, 1, fmt);
                if (!name.isBlank()) {
                    groups.add(new ExchangeDtos.JsonExGroupDto(
                        cellText(row, 0, fmt), name, emptyToNull(cellText(row, 2, fmt)),
                        cellText(row, 3, fmt), Boolean.parseBoolean(cellText(row, 4, fmt)),
                        Boolean.parseBoolean(cellText(row, 5, fmt))
                    ));
                }
            }
        }

        List<ExchangeDtos.JsonExUomDto> uoms = new ArrayList<>();
        Sheet uSheet = wb.getSheet("UOMs");
        if (uSheet != null) {
            for (int r = 1; r <= uSheet.getLastRowNum(); r++) {
                Row row = uSheet.getRow(r);
                String sym = cellText(row, 0, fmt);
                if (!sym.isBlank()) {
                    String dec = cellText(row, 2, fmt);
                    uoms.add(new ExchangeDtos.JsonExUomDto(
                        sym, cellText(row, 1, fmt), dec.isBlank() ? 2 : Integer.parseInt(dec)
                    ));
                }
            }
        }

        List<ExchangeDtos.JsonExGodownDto> godowns = new ArrayList<>();
        Sheet gdSheet = wb.getSheet("Godowns");
        if (gdSheet != null) {
            for (int r = 1; r <= gdSheet.getLastRowNum(); r++) {
                Row row = gdSheet.getRow(r);
                String name = cellText(row, 0, fmt);
                if (!name.isBlank()) {
                    godowns.add(new ExchangeDtos.JsonExGodownDto(name, emptyToNull(cellText(row, 1, fmt))));
                }
            }
        }

        List<ExchangeDtos.JsonExStockGroupDto> stockGroups = new ArrayList<>();
        Sheet sgSheet = wb.getSheet("StockGroups");
        if (sgSheet != null) {
            for (int r = 1; r <= sgSheet.getLastRowNum(); r++) {
                Row row = sgSheet.getRow(r);
                String name = cellText(row, 0, fmt);
                if (!name.isBlank()) {
                    stockGroups.add(new ExchangeDtos.JsonExStockGroupDto(name, emptyToNull(cellText(row, 1, fmt))));
                }
            }
        }

        List<ExchangeDtos.JsonExStockItemDto> stockItems = new ArrayList<>();
        Sheet siSheet = wb.getSheet("StockItems");
        if (siSheet != null) {
            for (int r = 1; r <= siSheet.getLastRowNum(); r++) {
                Row row = siSheet.getRow(r);
                String name = cellText(row, 1, fmt);
                if (!name.isBlank()) {
                    stockItems.add(new ExchangeDtos.JsonExStockItemDto(
                        cellText(row, 0, fmt), name, emptyToNull(cellText(row, 2, fmt)),
                        emptyToNull(cellText(row, 3, fmt)), emptyToNull(cellText(row, 4, fmt)),
                        cellText(row, 5, fmt), cellText(row, 6, fmt), cellText(row, 7, fmt)
                    ));
                }
            }
        }

        List<ExchangeDtos.JsonExLedgerDto> ledgers = new ArrayList<>();
        Sheet lSheet = wb.getSheet("Ledgers");
        if (lSheet != null) {
            for (int r = 1; r <= lSheet.getLastRowNum(); r++) {
                Row row = lSheet.getRow(r);
                String name = cellText(row, 1, fmt);
                if (!name.isBlank()) {
                    ledgers.add(new ExchangeDtos.JsonExLedgerDto(
                        cellText(row, 0, fmt), name, emptyToNull(cellText(row, 2, fmt)),
                        cellText(row, 3, fmt), cellText(row, 4, fmt),
                        Boolean.parseBoolean(cellText(row, 5, fmt)),
                        emptyToNull(cellText(row, 6, fmt)), emptyToNull(cellText(row, 7, fmt)),
                        "NONE", null
                    ));
                }
            }
        }

        Map<String, List<ExchangeDtos.JsonExVoucherLineDto>> linesByVGuid = new LinkedHashMap<>();
        Sheet vlSheet = wb.getSheet("VoucherLines");
        if (vlSheet != null) {
            for (int r = 1; r <= vlSheet.getLastRowNum(); r++) {
                Row row = vlSheet.getRow(r);
                String vGuid = cellText(row, 0, fmt);
                if (vGuid.isBlank()) {
                    continue;
                }
                String billJson = cellText(row, 6, fmt);
                List<ExchangeDtos.JsonExBillAllocationDto> bills = billJson.isBlank()
                    ? List.of()
                    : parseBillAllocJson(billJson);
                linesByVGuid.computeIfAbsent(vGuid, k -> new ArrayList<>()).add(
                    new ExchangeDtos.JsonExVoucherLineDto(
                        Integer.parseInt(cellText(row, 1, fmt)),
                        cellText(row, 2, fmt),
                        cellText(row, 3, fmt),
                        cellText(row, 4, fmt),
                        cellText(row, 5, fmt),
                        bills
                    )
                );
            }
        }

        Map<String, List<ExchangeDtos.JsonExInventoryLineDto>> invByVGuid = new LinkedHashMap<>();
        Sheet ilSheet = wb.getSheet("InventoryLines");
        if (ilSheet != null) {
            for (int r = 1; r <= ilSheet.getLastRowNum(); r++) {
                Row row = ilSheet.getRow(r);
                String vGuid = cellText(row, 0, fmt);
                if (vGuid.isBlank()) {
                    continue;
                }
                invByVGuid.computeIfAbsent(vGuid, k -> new ArrayList<>()).add(
                    new ExchangeDtos.JsonExInventoryLineDto(
                        Integer.parseInt(cellText(row, 1, fmt)),
                        cellText(row, 2, fmt),
                        cellText(row, 3, fmt),
                        emptyToNull(cellText(row, 4, fmt)),
                        cellText(row, 5, fmt),
                        cellText(row, 6, fmt),
                        cellText(row, 7, fmt),
                        cellText(row, 8, fmt),
                        emptyToNull(cellText(row, 9, fmt)),
                        emptyToNull(cellText(row, 10, fmt))
                    )
                );
            }
        }

        List<ExchangeDtos.JsonExVoucherDto> vouchers = new ArrayList<>();
        Sheet vSheet = wb.getSheet("Vouchers");
        if (vSheet != null) {
            for (int r = 1; r <= vSheet.getLastRowNum(); r++) {
                Row row = vSheet.getRow(r);
                String vGuid = cellText(row, 0, fmt);
                if (vGuid.isBlank()) {
                    continue;
                }
                vouchers.add(new ExchangeDtos.JsonExVoucherDto(
                    vGuid,
                    cellText(row, 1, fmt),
                    emptyToNull(cellText(row, 2, fmt)),
                    LocalDate.parse(cellText(row, 3, fmt)),
                    LocalDate.parse(cellText(row, 4, fmt)),
                    emptyToNull(cellText(row, 5, fmt)),
                    emptyToNull(cellText(row, 6, fmt)),
                    emptyToNull(cellText(row, 7, fmt)),
                    emptyToNull(cellText(row, 8, fmt)),
                    emptyToNull(cellText(row, 9, fmt)),
                    linesByVGuid.getOrDefault(vGuid, List.of()),
                    invByVGuid.getOrDefault(vGuid, List.of())
                ));
            }
        }

        return new ExchangeDtos.JsonExDataDto(
            groups, uoms, godowns, stockGroups, stockItems, ledgers, vouchers
        );
    }

    private ExchangeDtos.JsonExDataDto parseMappedExcelSheet(
        Workbook wb,
        ExchangeDtos.ExchangeMappingTemplateDto tpl
    ) {
        DataFormatter fmt = new DataFormatter();
        Sheet sheet = tpl.sheetName() != null && wb.getSheet(tpl.sheetName()) != null
            ? wb.getSheet(tpl.sheetName())
            : wb.getSheetAt(0);
        int headerIdx = Math.max(0, tpl.headerRowIndex() - 1);
        Row headerRow = sheet.getRow(headerIdx);
        Map<String, Integer> colByHeader = new HashMap<>();
        if (headerRow != null) {
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String h = cellText(headerRow, c, fmt);
                if (!h.isBlank()) {
                    colByHeader.put(h.trim().toLowerCase(Locale.ROOT), c);
                }
            }
        }

        Map<String, String> fieldToHeader = new HashMap<>();
        for (Map.Entry<String, String> e : tpl.columnMappings().entrySet()) {
            fieldToHeader.put(e.getValue().trim().toLowerCase(Locale.ROOT), e.getKey().trim().toLowerCase(Locale.ROOT));
            fieldToHeader.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue().trim().toLowerCase(Locale.ROOT));
        }

        List<ExchangeDtos.JsonExLedgerDto> ledgers = new ArrayList<>();
        List<ExchangeDtos.JsonExVoucherDto> vouchers = new ArrayList<>();

        if ("LEDGERS".equals(tpl.targetType())) {
            for (int r = headerIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                String name = mappedCell(row, colByHeader, fieldToHeader, fmt, "name", "ledgername", "account name");
                if (name.isBlank()) {
                    continue;
                }
                String grp = mappedCell(row, colByHeader, fieldToHeader, fmt, "groupname", "group", "parent");
                String ob = mappedCell(row, colByHeader, fieldToHeader, fmt, "openingbalance", "opening");
                String side = mappedCell(row, colByHeader, fieldToHeader, fmt, "openingside", "side");
                ledgers.add(new ExchangeDtos.JsonExLedgerDto(
                    UUID.randomUUID().toString(),
                    name,
                    grp.isBlank() ? "Sundry Debtors" : grp,
                    ob.isBlank() ? "0.00" : ob,
                    side.isBlank() ? "DR" : side,
                    false,
                    null,
                    null,
                    "NONE",
                    null
                ));
            }
        } else {
            for (int r = headerIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                String drLedger = mappedCell(
                    row, colByHeader, fieldToHeader, fmt, "debitledger", "debit ledger", "drledger"
                );
                String crLedger = mappedCell(
                    row, colByHeader, fieldToHeader, fmt, "creditledger", "credit ledger", "crledger"
                );
                String amt = mappedCell(row, colByHeader, fieldToHeader, fmt, "amount", "value");
                if (drLedger.isBlank() || crLedger.isBlank() || amt.isBlank()) {
                    continue;
                }
                String vType = mappedCell(row, colByHeader, fieldToHeader, fmt, "vouchertype", "voucher type", "type");
                String vNo = mappedCell(row, colByHeader, fieldToHeader, fmt, "vouchernumber", "voucher no", "number");
                String dateRaw = mappedCell(row, colByHeader, fieldToHeader, fmt, "voucherdate", "date");
                String narr = mappedCell(row, colByHeader, fieldToHeader, fmt, "narration", "remarks");
                LocalDate vDate = dateRaw.isBlank() ? LocalDate.of(2025, 4, 1) : LocalDate.parse(dateRaw);

                vouchers.add(new ExchangeDtos.JsonExVoucherDto(
                    UUID.randomUUID().toString(),
                    vType.isBlank() ? "Journal" : vType,
                    emptyToNull(vNo),
                    vDate,
                    vDate,
                    "AS_VOUCHER",
                    "NORMAL",
                    null,
                    null,
                    emptyToNull(narr),
                    List.of(
                        new ExchangeDtos.JsonExVoucherLineDto(1, "LEDGER", drLedger, "DR", amt, List.of()),
                        new ExchangeDtos.JsonExVoucherLineDto(2, "LEDGER", crLedger, "CR", amt, List.of())
                    ),
                    List.of()
                ));
            }
        }

        return new ExchangeDtos.JsonExDataDto(
            List.of(), List.of(), List.of(), List.of(), List.of(), ledgers, vouchers
        );
    }

    // =========================================================================
    // Low-Level Utility Helpers
    // =========================================================================

    private void ensureFiscalYearsCoverRange(UUID companyId, UUID actorId, LocalDate minDate, LocalDate maxDate) {
        int startYear = minDate.getMonthValue() >= 4 ? minDate.getYear() : minDate.getYear() - 1;
        int endYear = maxDate.getMonthValue() >= 4 ? maxDate.getYear() : maxDate.getYear() - 1;
        LocalDate minFyStart = LocalDate.of(startYear, 4, 1);

        businessJdbcTemplate.update(
            """
            UPDATE mc_company
               SET financial_year_from = LEAST(financial_year_from, ?),
                   books_beginning_from = LEAST(books_beginning_from, ?),
                   updated_at = clock_timestamp()
             WHERE id = ?
            """,
            minFyStart, minFyStart, companyId
        );

        List<FySpan> spans = businessJdbcTemplate.query(
            "SELECT start_date, end_date FROM mc_fiscal_year WHERE company_id = ?",
            (rs, rowNum) -> new FySpan(
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class)
            ),
            companyId
        );

        for (int yr = startYear; yr <= endYear; yr++) {
            LocalDate fyS = LocalDate.of(yr, 4, 1);
            LocalDate fyE = fyS.plusYears(1).minusDays(1);
            boolean exists = spans.stream().anyMatch(s -> !fyE.isBefore(s.start()) && !fyS.isAfter(s.end()));
            if (!exists) {
                String code = "FY" + yr + "-" + String.format("%02d", (yr + 1) % 100);
                String label = "FY " + yr + "-" + String.format("%02d", (yr + 1) % 100);
                fiscalPeriodService.seedFiscalYearWithMonthlyPeriodsInCurrentTx(
                    companyId, actorId, code, label, fyS, fyE
                );
            }
        }
    }

    private ExchangeDtos.ExchangeMappingTemplateDto requireTemplateById(UUID companyId, UUID templateId) {
        List<ExchangeDtos.ExchangeMappingTemplateDto> rows = businessJdbcTemplate.query(
            """
            SELECT id, name, target_type, sheet_name, header_row_index,
                   column_mappings_json, default_values_json, duplicate_handling,
                   active, version_no, created_at
              FROM mc_exchange_mapping_template
             WHERE company_id = ? AND id = ?
            """,
            (rs, rowNum) -> mapTemplateRow(rs),
            companyId,
            templateId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-EXG-TEMPLATE-NOT-FOUND",
                "Mapping Template Not Found",
                "Exchange mapping template " + templateId + " was not found."
            );
        }
        return rows.getFirst();
    }

    private ExchangeDtos.ExchangeMappingTemplateDto mapTemplateRow(ResultSet rs) throws SQLException {
        String name = rs.getString("name");
        String targetType = rs.getString("target_type");
        String scope = ("LEDGERS".equals(targetType) || "STOCK_ITEMS".equals(targetType)) ? "MASTERS" : "VOUCHERS";
        Map<String, String> mappings = parseStringMap(rs.getString("column_mappings_json"));
        Map<String, String> defaults = parseStringMap(rs.getString("default_values_json"));
        return new ExchangeDtos.ExchangeMappingTemplateDto(
            rs.getObject("id", UUID.class),
            name,
            name,
            targetType,
            scope,
            rs.getString("sheet_name"),
            rs.getInt("header_row_index"),
            mappings,
            defaults,
            rs.getString("duplicate_handling"),
            rs.getBoolean("active"),
            rs.getLong("version_no"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private boolean existsJournalBySourceGuid(UUID companyId, String guid) {
        Integer cnt = businessJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mc_journal WHERE company_id = ? AND source_guid = ?",
            Integer.class,
            companyId,
            guid
        );
        return cnt != null && cnt > 0;
    }

    private String queryCompanyName(UUID companyId) {
        return businessJdbcTemplate.queryForObject(
            "SELECT legal_name FROM mc_company WHERE id = ?",
            String.class,
            companyId
        );
    }

    private String resolveVoucherTypeName(UUID companyId, String raw) {
        String clean = raw == null || raw.isBlank() ? "Journal" : raw.trim();
        List<String> rows = businessJdbcTemplate.query(
            "SELECT name FROM mc_voucher_type WHERE company_id = ? AND lower(name) = lower(?)",
            (rs, rowNum) -> rs.getString("name"),
            companyId,
            clean
        );
        return rows.isEmpty() ? "Journal" : rows.getFirst();
    }

    private LedgerRef ensureLedger(
        MoneyCraftPrincipal actor,
        UUID companyId,
        String baseCurrency,
        String name,
        Map<String, LedgerRef> ledgers
    ) {
        String lower = name.toLowerCase(Locale.ROOT);
        LedgerRef existing = ledgers.get(lower);
        if (existing != null) {
            return existing;
        }
        UUID suspenseId = businessJdbcTemplate.queryForObject(
            "SELECT id FROM mc_account_group WHERE company_id = ? AND lower(name) = 'suspense a/c'",
            UUID.class,
            companyId
        );
        CompanyMasterSequenceHelper.AllocatedMasterSeq seq = sequenceHelper.allocateForCreate(companyId, true);
        UUID id = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_ledger (
                id, company_id, name, group_id, opening_balance, opening_side, currency_code,
                maintain_balances_bill_by_bill, credit_limit, tax_type, gst_applicability,
                appropriate_for, party_registration_type, is_system_ledger, active,
                master_seq_id, alter_id, version_no, created_by
            ) VALUES (
                ?, ?, ?, ?, 0, 'CR', ?, false, 0, 'NONE', 'NOT_APPLICABLE',
                'NONE', 'UNKNOWN', false, true, ?, ?, 1, ?
            )
            """,
            id, companyId, name, suspenseId, baseCurrency, seq.masterSeqId(), seq.alterId(), actor.principalId()
        );
        LedgerRef created = new LedgerRef(id, name, "LIABILITIES", false);
        ledgers.put(lower, created);
        return created;
    }

    private Map<String, ExistingGroup> loadGroupsMap(UUID companyId) {
        Map<String, ExistingGroup> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            "SELECT id, name, nature, depth_level FROM mc_account_group WHERE company_id = ?",
            (rs, rowNum) -> {
                String n = rs.getString("name");
                map.put(n.trim().toLowerCase(Locale.ROOT), new ExistingGroup(
                    rs.getObject("id", UUID.class), n, rs.getString("nature"), rs.getShort("depth_level")
                ));
                return null;
            },
            companyId
        );
        return map;
    }

    private Map<String, LedgerRef> loadLedgersMap(UUID companyId) {
        Map<String, LedgerRef> map = new LinkedHashMap<>();
        businessJdbcTemplate.query(
            """
            SELECT l.id, l.name, COALESCE(g.nature, 'LIABILITIES') AS nature, l.maintain_balances_bill_by_bill
              FROM mc_ledger l
              LEFT JOIN mc_account_group g ON g.company_id = l.company_id AND g.id = l.group_id
             WHERE l.company_id = ?
            """,
            (rs, rowNum) -> {
                String n = rs.getString("name");
                map.put(n.trim().toLowerCase(Locale.ROOT), new LedgerRef(
                    rs.getObject("id", UUID.class), n, rs.getString("nature"),
                    rs.getBoolean("maintain_balances_bill_by_bill")
                ));
                return null;
            },
            companyId
        );
        return map;
    }

    private static ExistingGroup resolveGroup(String groupName, Map<String, ExistingGroup> groups) {
        if (groupName != null && !groupName.isBlank()) {
            ExistingGroup g = groups.get(groupName.trim().toLowerCase(Locale.ROOT));
            if (g != null) {
                return g;
            }
        }
        return groups.getOrDefault("current liabilities", groups.values().stream().findFirst().orElse(null));
    }

    private static String resolveTargetType(String rawTargetType, String rawEntityScope) {
        if (rawTargetType != null && !rawTargetType.isBlank()) {
            String upper = rawTargetType.trim().toUpperCase(Locale.ROOT);
            if (VALID_TARGET_TYPES.contains(upper)) {
                return upper;
            }
        }
        if (rawEntityScope != null && "MASTERS".equalsIgnoreCase(rawEntityScope.trim())) {
            return "LEDGERS";
        }
        return "ACCOUNTING_VOUCHERS";
    }

    private void ensurePrincipalProjected(MoneyCraftPrincipal actor) {
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_principal_projection (principal_id, kind, username, display_name, enabled, version_no)
            VALUES (?, ?, ?, ?, true, 1)
            ON CONFLICT (principal_id) DO NOTHING
            """,
            actor.principalId(),
            actor.kind(),
            actor.username(),
            actor.displayName() == null || actor.displayName().isBlank() ? actor.username() : actor.displayName()
        );
    }

    private static boolean hasAgstRef(ExchangeDtos.JsonExVoucherDto v) {
        if (v.lines() == null) {
            return false;
        }
        for (ExchangeDtos.JsonExVoucherLineDto l : v.lines()) {
            if (l.billAllocations() != null) {
                for (ExchangeDtos.JsonExBillAllocationDto ba : l.billAllocations()) {
                    if ("AGST_REF".equals(normalizeBillAllocType(ba.allocationType()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String normalizeBillAllocType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NEW_REF";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("AGST") || u.contains("AGAINST")) {
            return "AGST_REF";
        }
        if (u.startsWith("ADV")) {
            return "ADVANCE";
        }
        if (u.contains("ON_ACCOUNT") || u.contains("ON ACCOUNT")) {
            return "ON_ACCOUNT";
        }
        return "NEW_REF";
    }

    private static String toTallyBillType(String allocType) {
        if ("AGST_REF".equals(allocType)) {
            return "Agst Ref";
        }
        if ("ADVANCE".equals(allocType)) {
            return "Advance";
        }
        if ("ON_ACCOUNT".equals(allocType)) {
            return "On Account";
        }
        return "New Ref";
    }

    private static String mapCosting(String raw) {
        if (raw == null || raw.isBlank()) {
            return "AVG_COST";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.contains("FIFO_PERPETUAL")) {
            return "FIFO_PERPETUAL";
        }
        if (u.contains("FIFO")) {
            return "FIFO";
        }
        if (u.contains("LIFO_PERPETUAL")) {
            return "LIFO_PERPETUAL";
        }
        if (u.contains("LIFO")) {
            return "LIFO_ANNUAL";
        }
        if (u.contains("LAST")) {
            return "LAST_PURCHASE_COST";
        }
        if (u.contains("STD")) {
            return "STD_COST";
        }
        if (u.contains("ZERO")) {
            return "AT_ZERO_COST";
        }
        return "AVG_COST";
    }

    private static String sanitizeOptionalGstin(String rawGstin) {
        if (rawGstin == null || rawGstin.isBlank()) {
            return null;
        }
        try {
            return ExactMoneyValidator.validatePartyGstin(rawGstin, null);
        } catch (MoneyCraftException ignored) {
            return null;
        }
    }

    private static void writeRow(Sheet sheet, int rowIdx, String... values) {
        Row row = sheet.createRow(rowIdx);
        for (int c = 0; c < values.length; c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(values[c] == null ? "" : values[c]);
        }
    }

    private static String cellText(Row row, int colIdx, DataFormatter fmt) {
        if (row == null || colIdx < 0) {
            return "";
        }
        Cell cell = row.getCell(colIdx);
        return cell == null ? "" : fmt.formatCellValue(cell).trim();
    }

    private static String mappedCell(
        Row row,
        Map<String, Integer> colByHeader,
        Map<String, String> fieldToHeader,
        DataFormatter fmt,
        String... candidateFields
    ) {
        for (String cand : candidateFields) {
            String mappedHeader = fieldToHeader.get(cand);
            if (mappedHeader != null && colByHeader.containsKey(mappedHeader)) {
                return cellText(row, colByHeader.get(mappedHeader), fmt);
            }
            if (colByHeader.containsKey(cand)) {
                return cellText(row, colByHeader.get(cand), fmt);
            }
        }
        return "";
    }

    private static List<Element> directChildrenByTag(Element parent, String tagName) {
        List<Element> list = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element el && tagName.equals(el.getTagName())) {
                list.add(el);
            }
        }
        return list;
    }

    private static String childText(Element parent, String tagName) {
        List<Element> children = directChildrenByTag(parent, tagName);
        if (children.isEmpty()) {
            return null;
        }
        String text = children.getFirst().getTextContent();
        return text == null ? null : text.trim();
    }

    private static boolean isYes(String val) {
        return val != null && ("yes".equalsIgnoreCase(val.trim()) || "true".equalsIgnoreCase(val.trim()));
    }

    private static LocalDate parseTallyDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.of(2025, 4, 1);
        }
        String t = raw.trim();
        if (t.length() == 8 && t.chars().allMatch(Character::isDigit)) {
            return LocalDate.parse(t, TALLY_DATE_FMT);
        }
        return LocalDate.parse(t);
    }

    private static String parseLeadingDecimal(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.trim();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if ((ch >= '0' && ch <= '9') || ch == '.' || (ch == '-' && token.isEmpty())) {
                token.append(ch);
            } else if (!token.isEmpty()) {
                break;
            }
        }
        return token.isEmpty() || "-".equals(token.toString()) ? fallback : token.toString();
    }

    private static String escapeXml(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static int totalBundleCount(ExchangeDtos.JsonExDataDto b) {
        return b.groups().size() + b.uoms().size() + b.godowns().size()
            + b.stockGroups().size() + b.stockItems().size() + b.ledgers().size() + b.vouchers().size();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize exchange JSON", ex);
        }
    }

    private Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (JacksonException ignored) {
            return Map.of();
        }
    }

    private List<ExchangeDtos.JsonExBillAllocationDto> parseBillAllocJson(String json) {
        try {
            return objectMapper.readValue(
                json, new TypeReference<List<ExchangeDtos.JsonExBillAllocationDto>>() { }
            );
        } catch (JacksonException ignored) {
            return List.of();
        }
    }

    private record VoucherHeaderRow(UUID id, String guid) {
    }

    private record ExistingGroup(UUID id, String name, String nature, int depth) {
    }

    private record LedgerRef(UUID id, String name, String nature, boolean billWise) {
    }

    private record ItemRef(UUID id, String name, UUID baseUomId) {
    }

    private record UpsertedLedgersResult(Map<String, LedgerRef> byLowerName, int touchedCount) {
    }

    private record UpsertedItemsResult(Map<String, ItemRef> byLowerName, int createdCount) {
    }

    private record FySpan(LocalDate start, LocalDate end) {
    }
}
