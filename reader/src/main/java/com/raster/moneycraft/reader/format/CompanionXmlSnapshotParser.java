package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.model.DiagnosticSeverity;
import com.raster.moneycraft.reader.model.RawBillAllocation;
import com.raster.moneycraft.reader.model.RawCompanyProfile;
import com.raster.moneycraft.reader.model.RawGodown;
import com.raster.moneycraft.reader.model.RawGroup;
import com.raster.moneycraft.reader.model.RawInventoryLine;
import com.raster.moneycraft.reader.model.RawLedger;
import com.raster.moneycraft.reader.model.RawStockGroup;
import com.raster.moneycraft.reader.model.RawStockItem;
import com.raster.moneycraft.reader.model.RawUom;
import com.raster.moneycraft.reader.model.RawVoucher;
import com.raster.moneycraft.reader.model.RawVoucherLine;
import com.raster.moneycraft.reader.model.RawVoucherType;
import com.raster.moneycraft.reader.model.ReaderDiagnostic;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Pure java.base parser for canonical XML ENVELOPE exports (masters, vouchers, and report snapshots).
 * Sanitizes &#4; control entities and enforces the universal -Debit / +Credit sign convention (MIG-005).
 */
public final class CompanionXmlSnapshotParser {

    private static final DateTimeFormatter BASIC_ISO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public XmlSnapshotResult parseDirectory(Path xmlDirectory) throws IOException {
        if (!Files.isDirectory(xmlDirectory)) {
            throw new CorruptSnapshotException(
                    "COMPANION_XML_NOT_DIRECTORY",
                    "Companion XML path is not a directory: " + xmlDirectory);
        }

        List<Path> allXmlFiles;
        try (Stream<Path> walk = Files.walk(xmlDirectory, 2)) {
            allXmlFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(this::isEligibleXmlFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        if (allXmlFiles.isEmpty()) {
            throw new CorruptSnapshotException(
                    "NO_COMPANION_XML_FILES",
                    "No eligible .xml snapshot files found under: " + xmlDirectory);
        }

        boolean hasFullVoucherCollection = allXmlFiles.stream().anyMatch(p -> {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            return n.contains("voucher_collection") || n.contains("vouchers_collection");
        });
        boolean hasFullMastersFile = allXmlFiles.stream().anyMatch(p ->
                p.getFileName().toString().toLowerCase(Locale.ROOT).contains("all_masters_full"));

        Map<String, RawGroup> groupsByName = new LinkedHashMap<>();
        Map<String, RawLedger> ledgersByName = new LinkedHashMap<>();
        Map<String, RawVoucherType> voucherTypesByName = new LinkedHashMap<>();
        Map<String, RawUom> uomsBySymbol = new LinkedHashMap<>();
        Map<String, RawGodown> godownsByName = new LinkedHashMap<>();
        Map<String, RawStockGroup> stockGroupsByName = new LinkedHashMap<>();
        Map<String, RawStockItem> stockItemsByName = new LinkedHashMap<>();
        Map<String, RawVoucher> vouchersByKey = new LinkedHashMap<>();
        Map<String, String> reportLedgerParents = new HashMap<>();
        Map<String, BigDecimal> stockSummaryClosingValues = new HashMap<>();
        Map<String, BigDecimal> stockSummaryClosingRates = new HashMap<>();
        List<ReaderDiagnostic> diagnostics = new ArrayList<>();

        RawCompanyProfile companyProfile = null;

        for (Path xmlFile : allXmlFiles) {
            String lowerName = xmlFile.getFileName().toString().toLowerCase(Locale.ROOT);
            if (hasFullVoucherCollection && (lowerName.contains("daybook") || lowerName.equals("day_book.xml"))) {
                continue;
            }
            if (hasFullMastersFile && (lowerName.startsWith("03_")
                    || lowerName.startsWith("04_")
                    || lowerName.startsWith("05_")
                    || lowerName.startsWith("06"))) {
                continue;
            }

            String xmlText = readAndSanitizeXml(xmlFile);
            XmlNode root = parseXmlDocument(xmlFile.getFileName().toString(), xmlText);
            if (root == null) {
                continue;
            }

            if (lowerName.equals("trial_balance.xml")) {
                extractTrialBalanceHierarchy(root, reportLedgerParents);
                continue;
            }
            if (lowerName.equals("stock_summary.xml")) {
                extractStockSummaryHints(root, stockSummaryClosingValues, stockSummaryClosingRates);
                continue;
            }
            if (lowerName.equals("balance_sheet.xml")
                    || lowerName.equals("profit_and_loss.xml")
                    || lowerName.equals("bills_payable.xml")
                    || lowerName.equals("bills_receivable.xml")
                    || lowerName.equals("cash_flow.xml")
                    || lowerName.equals("ratio_analysis.xml")
                    || lowerName.startsWith("gstr")) {
                continue;
            }

            List<XmlNode> companyNodes = new ArrayList<>();
            collectElementsByTag(root, "COMPANY", companyNodes);
            for (XmlNode cmpNode : companyNodes) {
                if (!cmpNode.children().isEmpty() && cmpNode.childText("GUID") != null) {
                    companyProfile = parseCompanyNode(xmlDirectory, cmpNode);
                }
            }

            List<XmlNode> groupNodes = new ArrayList<>();
            collectElementsByTag(root, "GROUP", groupNodes);
            for (XmlNode gNode : groupNodes) {
                if (gNode.attribute("NAME") != null || !gNode.children().isEmpty()) {
                    RawGroup g = parseGroupNode(gNode, groupsByName.size() + 1);
                    if (g != null) {
                        groupsByName.putIfAbsent(g.name(), g);
                    }
                }
            }

            List<XmlNode> ledgerNodes = new ArrayList<>();
            collectElementsByTag(root, "LEDGER", ledgerNodes);
            for (XmlNode lNode : ledgerNodes) {
                if (lNode.attribute("NAME") != null) {
                    RawLedger l = parseLedgerNode(lNode, ledgersByName.size() + 1);
                    if (l != null) {
                        ledgersByName.putIfAbsent(l.name(), l);
                    }
                }
            }

            List<XmlNode> vtypeNodes = new ArrayList<>();
            collectElementsByTag(root, "VOUCHERTYPE", vtypeNodes);
            for (XmlNode vtNode : vtypeNodes) {
                if (vtNode.attribute("NAME") != null) {
                    RawVoucherType vt = parseVoucherTypeNode(vtNode, voucherTypesByName.size() + 1);
                    if (vt != null) {
                        voucherTypesByName.putIfAbsent(vt.name(), vt);
                    }
                }
            }

            List<XmlNode> unitNodes = new ArrayList<>();
            collectElementsByTag(root, "UNIT", unitNodes);
            for (XmlNode uNode : unitNodes) {
                if (uNode.attribute("NAME") != null) {
                    RawUom uom = parseUnitNode(uNode, uomsBySymbol.size() + 1);
                    if (uom != null) {
                        uomsBySymbol.putIfAbsent(uom.symbol(), uom);
                    }
                }
            }

            List<XmlNode> godownNodes = new ArrayList<>();
            collectElementsByTag(root, "GODOWN", godownNodes);
            for (XmlNode gdNode : godownNodes) {
                if (gdNode.attribute("NAME") != null) {
                    RawGodown gd = parseGodownNode(gdNode, godownsByName.size() + 1);
                    if (gd != null) {
                        godownsByName.putIfAbsent(gd.name(), gd);
                    }
                }
            }

            List<XmlNode> stockGroupNodes = new ArrayList<>();
            collectElementsByTag(root, "STOCKGROUP", stockGroupNodes);
            for (XmlNode sgNode : stockGroupNodes) {
                if (sgNode.attribute("NAME") != null) {
                    RawStockGroup sg = parseStockGroupNode(sgNode, stockGroupsByName.size() + 1);
                    if (sg != null) {
                        stockGroupsByName.putIfAbsent(sg.name(), sg);
                    }
                }
            }

            List<XmlNode> stockItemNodes = new ArrayList<>();
            collectElementsByTag(root, "STOCKITEM", stockItemNodes);
            for (XmlNode siNode : stockItemNodes) {
                if (siNode.attribute("NAME") != null) {
                    RawStockItem si = parseStockItemNode(siNode, stockItemsByName.size() + 1);
                    if (si != null) {
                        stockItemsByName.putIfAbsent(si.name(), si);
                    }
                }
            }

            List<XmlNode> voucherNodes = new ArrayList<>();
            collectElementsByTag(root, "VOUCHER", voucherNodes);
            for (XmlNode vNode : voucherNodes) {
                if (!vNode.children().isEmpty() && vNode.childText("VOUCHERTYPENAME") != null) {
                    RawVoucher v = parseVoucherNode(vNode, vouchersByKey.size() + 1);
                    if (v != null) {
                        String key = v.guid().isEmpty() ? ("mid:" + v.masterId()) : v.guid();
                        RawVoucher existing = vouchersByKey.get(key);
                        if (existing == null || v.alterId() >= existing.alterId()) {
                            vouchersByKey.put(key, v);
                        }
                    }
                }
            }
        }

        String inferredGuid = inferCompanyGuid(companyProfile, vouchersByKey.values());
        if (companyProfile == null) {
            companyProfile = synthesizeCompanyProfile(xmlDirectory, inferredGuid, vouchersByKey.values());
        }

        if (groupsByName.isEmpty()) {
            for (RawGroup g : PredefinedTallyCatalog.canonical28Groups(inferredGuid)) {
                groupsByName.put(g.name(), g);
            }
            diagnostics.add(new ReaderDiagnostic(
                    DiagnosticSeverity.INFO,
                    "PREDEFINED_28_GROUPS_SEEDED",
                    "XML",
                    "Seeded canonical 28 predefined accounting groups for voucher-only XML snapshot"));
        }

        if (voucherTypesByName.isEmpty()) {
            for (RawVoucherType vt : PredefinedTallyCatalog.canonical24VoucherTypes(inferredGuid)) {
                voucherTypesByName.put(vt.name(), vt);
            }
        }

        synthesizeMissingMastersFromVouchers(
                inferredGuid,
                vouchersByKey.values(),
                groupsByName.keySet(),
                ledgersByName,
                stockItemsByName,
                uomsBySymbol,
                godownsByName,
                reportLedgerParents,
                stockSummaryClosingRates);

        diagnostics.add(new ReaderDiagnostic(
                DiagnosticSeverity.INFO,
                "COMPANION_XML_PARSED",
                "XML",
                "Parsed " + groupsByName.size() + " groups, " + ledgersByName.size() + " ledgers, "
                        + vouchersByKey.size() + " vouchers from XML directory " + xmlDirectory.getFileName()));

        return new XmlSnapshotResult(
                companyProfile,
                sortedList(groupsByName.values(), Comparator.comparing(RawGroup::name)),
                sortedList(ledgersByName.values(), Comparator.comparing(RawLedger::name)),
                sortedList(voucherTypesByName.values(), Comparator.comparing(RawVoucherType::name)),
                sortedList(uomsBySymbol.values(), Comparator.comparing(RawUom::symbol)),
                sortedList(godownsByName.values(), Comparator.comparing(RawGodown::name)),
                sortedList(stockGroupsByName.values(), Comparator.comparing(RawStockGroup::name)),
                sortedList(stockItemsByName.values(), Comparator.comparing(RawStockItem::name)),
                sortedList(vouchersByKey.values(), Comparator.comparingInt(RawVoucher::masterId)),
                List.copyOf(diagnostics));
    }

    private boolean isEligibleXmlFile(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.contains("lic") || !lower.endsWith(".xml")) {
            return false;
        }
        return !lower.endsWith("_resp.xml")
                && !lower.endsWith("_response.xml")
                && !lower.contains("before_alter");
    }

    private static RawCompanyProfile parseCompanyNode(Path xmlDirectory, XmlNode cmpNode) {
        String folderName = xmlDirectory.getFileName() != null ? xmlDirectory.getFileName().toString() : "000000";
        String cmpNum = textOrDefault(cmpNode, "COMPANYNUMBER", folderName);
        if (!cmpNum.chars().allMatch(Character::isDigit)) {
            cmpNum = folderName.chars().allMatch(Character::isDigit) ? folderName : "000000";
        }
        String guid = textOrDefault(cmpNode, "GUID", "00000000-0000-0000-0000-000000000000");
        String name = cmpNode.attribute("NAME");
        if (name == null || name.isBlank()) {
            name = textOrDefault(cmpNode, "NAME", folderName);
        }
        String formalName = textOrDefault(cmpNode, "BASICCOMPANYFORMALNAME", name);
        String state = textOrDefault(cmpNode, "STATENAME", textOrDefault(cmpNode, "PRIORSTATENAME", ""));
        String country = textOrDefault(cmpNode, "COUNTRYNAME", "");
        String pincode = textOrDefault(cmpNode, "PINCODE", "");
        String email = textOrDefault(cmpNode, "EMAIL", "");
        LocalDate startingFrom = parseCompactXmlDate(cmpNode.childText("STARTINGFROM"));
        LocalDate booksFrom = parseCompactXmlDate(cmpNode.childText("BOOKSFROM"));
        LocalDate auditedUpTo = parseCompactXmlDate(cmpNode.childText("AUDITEDUPTO"));
        String currencySymbol = textOrDefault(cmpNode, "BASECURRENCYSYMBOL", "₹");
        String currencyFormal = textOrDefault(cmpNode, "BASECURRENCYNAME", "INR");
        return new RawCompanyProfile(
                cmpNum,
                guid,
                name,
                formalName,
                state,
                country,
                pincode,
                email,
                startingFrom,
                booksFrom,
                auditedUpTo,
                currencySymbol,
                currencyFormal);
    }

    private static RawGroup parseGroupNode(XmlNode node, int fallbackId) {
        String name = extractMasterName(node);
        if (name.isEmpty()) {
            return null;
        }
        int masterId = parseIntOrDefault(node.childText("MASTERID"), fallbackId);
        int alterId = parseIntOrDefault(node.childText("ALTERID"), masterId);
        String guid = textOrDefault(node, "GUID", "group-" + masterId);
        String parent = normalizeParent(node.childText("PARENT"));
        boolean revenue = isYes(node.childText("ISREVENUE"));
        boolean deemedPositive = isYes(node.childText("ISDEEMEDPOSITIVE"));
        boolean affectsGp = isYes(node.childText("AFFECTSGROSSPROFIT"));
        boolean subledger = isYes(node.childText("ISSUBLEDGER"));
        return new RawGroup(masterId, guid, name, parent, revenue, deemedPositive, affectsGp, subledger, alterId);
    }

    private static RawLedger parseLedgerNode(XmlNode node, int fallbackId) {
        String name = extractMasterName(node);
        if (name.isEmpty()) {
            return null;
        }
        int masterId = parseIntOrDefault(node.childText("MASTERID"), fallbackId);
        int alterId = parseIntOrDefault(node.childText("ALTERID"), masterId);
        String guid = textOrDefault(node, "GUID", "ledger-" + masterId);
        String parent = normalizeParent(node.childText("PARENT"));
        BigDecimal openingBalance = parseAmount(node.childText("OPENINGBALANCE"));
        boolean billWise = isYes(node.childText("ISBILLWISEON"));
        String gstin = textOrDefault(node, "PARTYGSTIN", "");
        String state = textOrDefault(node, "LEDSTATENAME", textOrDefault(node, "PRIORSTATENAME", ""));
        return new RawLedger(masterId, guid, name, parent, openingBalance, billWise, gstin, state, alterId);
    }

    private static RawVoucherType parseVoucherTypeNode(XmlNode node, int fallbackId) {
        String name = extractMasterName(node);
        if (name.isEmpty()) {
            return null;
        }
        int masterId = parseIntOrDefault(node.childText("MASTERID"), fallbackId);
        int alterId = parseIntOrDefault(node.childText("ALTERID"), masterId);
        String guid = textOrDefault(node, "GUID", "vtype-" + masterId);
        String parent = normalizeOptionalParent(node.childText("PARENT"));
        String abbr = textOrDefault(node, "ABBREVIATION", "");
        boolean active = !"No".equalsIgnoreCase(textOrDefault(node, "ISACTIVE", "Yes"));
        return new RawVoucherType(masterId, guid, name, parent, abbr, 0, active, alterId);
    }

    private static RawUom parseUnitNode(XmlNode node, int fallbackId) {
        String symbol = extractMasterName(node);
        if (symbol.isEmpty()) {
            return null;
        }
        int masterId = parseIntOrDefault(node.childText("MASTERID"), fallbackId);
        String guid = textOrDefault(node, "GUID", "uom-" + masterId);
        String formalName = textOrDefault(node, "ORIGINALNAME", "");
        int decimals = parseIntOrDefault(node.childText("DECIMALPLACES"), 0);
        boolean simple = !"No".equalsIgnoreCase(textOrDefault(node, "ISSIMPLEUNIT", "Yes"));
        return new RawUom(masterId, guid, symbol, formalName, decimals, simple);
    }

    private static RawGodown parseGodownNode(XmlNode node, int fallbackId) {
        String name = extractMasterName(node);
        if (name.isEmpty()) {
            return null;
        }
        int masterId = parseIntOrDefault(node.childText("MASTERID"), fallbackId);
        String guid = textOrDefault(node, "GUID", "godown-" + masterId);
        String parent = normalizeParent(node.childText("PARENT"));
        return new RawGodown(masterId, guid, name, parent);
    }

    private static RawStockGroup parseStockGroupNode(XmlNode node, int fallbackId) {
        String name = extractMasterName(node);
        if (name.isEmpty()) {
            return null;
        }
        int masterId = parseIntOrDefault(node.childText("MASTERID"), fallbackId);
        String guid = textOrDefault(node, "GUID", "stkgrp-" + masterId);
        String parent = normalizeParent(node.childText("PARENT"));
        return new RawStockGroup(masterId, guid, name, parent);
    }

    private static RawStockItem parseStockItemNode(XmlNode node, int fallbackId) {
        String name = extractMasterName(node);
        if (name.isEmpty()) {
            return null;
        }
        int masterId = parseIntOrDefault(node.childText("MASTERID"), fallbackId);
        String guid = textOrDefault(node, "GUID", "stkitem-" + masterId);
        String parent = normalizeParent(node.childText("PARENT"));
        String baseUnits = normalizeOptionalParent(node.childText("BASEUNITS"));
        String costingMethod = textOrDefault(node, "COSTINGMETHOD", "Avg. Cost");
        QtyWithUom opQty = parseQuantity(node.childText("OPENINGBALANCE"));
        BigDecimal opVal = parseAmount(node.childText("OPENINGVALUE"));
        return new RawStockItem(masterId, guid, name, parent, baseUnits, costingMethod, opQty.qty(), opVal);
    }

    private static RawVoucher parseVoucherNode(XmlNode node, int fallbackId) {
        String voucherTypeName = textOrDefault(node, "VOUCHERTYPENAME", node.attribute("VCHTYPE"));
        if (voucherTypeName == null || voucherTypeName.isBlank()) {
            return null;
        }
        int masterId = parseIntOrDefault(node.childText("MASTERID"), fallbackId);
        int alterId = parseIntOrDefault(node.childText("ALTERID"), masterId);
        String guid = textOrDefault(node, "GUID", node.attribute("REMOTEID") != null ? node.attribute("REMOTEID") : "vch-" + masterId);
        String voucherNumber = textOrDefault(node, "VOUCHERNUMBER", "");
        LocalDate date = parseCompactXmlDate(node.childText("DATE"));
        LocalDate effDate = parseCompactXmlDate(node.childText("EFFECTIVEDATE"));
        String partyLedger = textOrDefault(node, "PARTYLEDGERNAME", "");
        String reference = textOrDefault(node, "REFERENCE", "");
        String narration = textOrDefault(node, "NARRATION", "");
        String pos = textOrDefault(node, "PLACEOFSUPPLY", "");
        boolean deleted = isYes(node.childText("ISDELETED"));
        boolean cancelled = isYes(node.childText("ISCANCELLED"));
        boolean optional = isYes(node.childText("ISOPTIONAL"));

        List<RawVoucherLine> ledgerLines = new ArrayList<>();
        for (XmlNode child : node.children()) {
            if ("ALLLEDGERENTRIES.LIST".equals(child.tag())) {
                RawVoucherLine line = parseLedgerEntryNode(child);
                if (line != null) {
                    ledgerLines.add(line);
                }
            }
        }

        List<RawInventoryLine> inventoryLines = new ArrayList<>();
        List<RawVoucherLine> fallbackLines = new ArrayList<>();
        for (XmlNode child : node.children()) {
            if ("LEDGERENTRIES.LIST".equals(child.tag())) {
                RawVoucherLine line = parseLedgerEntryNode(child);
                if (line != null) {
                    fallbackLines.add(line);
                }
            } else if ("ALLINVENTORYENTRIES.LIST".equals(child.tag())
                    || "INVENTORYENTRIESIN.LIST".equals(child.tag())
                    || "INVENTORYENTRIESOUT.LIST".equals(child.tag())) {
                RawInventoryLine invLine = parseInventoryEntryNode(child);
                if (invLine != null) {
                    inventoryLines.add(invLine);
                }
                for (XmlNode sub : child.children()) {
                    if ("ACCOUNTINGALLOCATIONS.LIST".equals(sub.tag())) {
                        RawVoucherLine allocLine = parseLedgerEntryNode(sub);
                        if (allocLine != null) {
                            fallbackLines.add(allocLine);
                        }
                    }
                }
            }
        }
        if (ledgerLines.isEmpty() && !fallbackLines.isEmpty()) {
            ledgerLines.addAll(fallbackLines);
        }

        return new RawVoucher(
                masterId,
                alterId,
                guid,
                voucherTypeName,
                voucherNumber,
                date,
                effDate != null ? effDate : date,
                partyLedger,
                reference,
                narration,
                pos,
                deleted,
                cancelled,
                optional,
                1,
                ledgerLines,
                inventoryLines);
    }

    private static RawVoucherLine parseLedgerEntryNode(XmlNode entryNode) {
        String ledgerName = textOrDefault(entryNode, "LEDGERNAME", "");
        if (ledgerName.isEmpty()) {
            return null;
        }
        BigDecimal amountSigned = parseAmount(entryNode.childText("AMOUNT"));
        boolean deemedPositive = isYes(entryNode.childText("ISDEEMEDPOSITIVE")) || amountSigned.signum() < 0;
        boolean partyLedger = isYes(entryNode.childText("ISPARTYLEDGER"));
        List<RawBillAllocation> bills = new ArrayList<>();
        for (XmlNode child : entryNode.children()) {
            if ("BILLALLOCATIONS.LIST".equals(child.tag())) {
                String billName = textOrDefault(child, "NAME", "");
                if (!billName.isEmpty()) {
                    String billType = textOrDefault(child, "BILLTYPE", "New Ref");
                    BigDecimal billAmt = parseAmount(child.childText("AMOUNT"));
                    bills.add(new RawBillAllocation(billName, billType, billAmt));
                }
            }
        }
        return new RawVoucherLine(ledgerName, deemedPositive, partyLedger, amountSigned, bills);
    }

    private static RawInventoryLine parseInventoryEntryNode(XmlNode invNode) {
        String itemName = textOrDefault(invNode, "STOCKITEMNAME", "");
        if (itemName.isEmpty()) {
            return null;
        }
        BigDecimal amountSigned = parseAmount(invNode.childText("AMOUNT"));
        boolean deemedPositive = isYes(invNode.childText("ISDEEMEDPOSITIVE")) || amountSigned.signum() < 0;
        QtyWithUom actual = parseQuantity(invNode.childText("ACTUALQTY"));
        QtyWithUom billed = parseQuantity(invNode.childText("BILLEDQTY"));
        BigDecimal rate = parseRate(invNode.childText("RATE"));
        String uom = !actual.uom().isEmpty() ? actual.uom() : billed.uom();
        String godown = "";
        String batch = "";
        for (XmlNode child : invNode.children()) {
            if ("BATCHALLOCATIONS.LIST".equals(child.tag())) {
                godown = textOrDefault(child, "GODOWNNAME", godown);
                batch = textOrDefault(child, "BATCHNAME", batch);
            }
        }
        return new RawInventoryLine(
                itemName,
                deemedPositive,
                actual.qty(),
                billed.qty(),
                uom,
                rate,
                amountSigned,
                godown,
                batch);
    }

    private static void extractTrialBalanceHierarchy(XmlNode root, Map<String, String> reportLedgerParents) {
        Set<String> canonicalGroupNames = new HashSet<>();
        for (RawGroup g : PredefinedTallyCatalog.canonical28Groups("")) {
            canonicalGroupNames.add(g.name());
        }
        String currentGroup = "Primary";
        for (XmlNode child : root.children()) {
            if ("DSPACCNAME".equals(child.tag())) {
                String dispName = textOrDefault(child, "DSPDISPNAME", "");
                if (dispName.isEmpty() || "Opening Stock".equals(dispName)) {
                    continue;
                }
                if (canonicalGroupNames.contains(dispName)) {
                    currentGroup = dispName;
                } else {
                    reportLedgerParents.putIfAbsent(dispName, currentGroup);
                }
            }
        }
    }

    private static void extractStockSummaryHints(
            XmlNode root,
            Map<String, BigDecimal> closingValues,
            Map<String, BigDecimal> closingRates) {
        String pendingName = null;
        for (XmlNode child : root.children()) {
            if ("DSPACCNAME".equals(child.tag())) {
                pendingName = textOrDefault(child, "DSPDISPNAME", "");
            } else if ("DSPSTKINFO".equals(child.tag()) && pendingName != null && !pendingName.isEmpty()) {
                for (XmlNode stkCl : child.children()) {
                    if ("DSPSTKCL".equals(stkCl.tag())) {
                        closingRates.put(pendingName, parseRate(stkCl.childText("DSPCLRATE")));
                        closingValues.put(pendingName, parseAmount(stkCl.childText("DSPCLAMTA")).abs());
                    }
                }
                pendingName = null;
            }
        }
    }

    private static void synthesizeMissingMastersFromVouchers(
            String companyGuid,
            Iterable<RawVoucher> vouchers,
            Set<String> knownGroups,
            Map<String, RawLedger> ledgersByName,
            Map<String, RawStockItem> stockItemsByName,
            Map<String, RawUom> uomsBySymbol,
            Map<String, RawGodown> godownsByName,
            Map<String, String> reportLedgerParents,
            Map<String, BigDecimal> stockSummaryClosingRates) {
        Map<String, String> partyRoleHints = new HashMap<>();
        Map<String, BigDecimal> lastPurchaseRateByItem = new HashMap<>();
        Map<String, String> itemUomHints = new HashMap<>();

        for (RawVoucher v : vouchers) {
            if (!v.partyLedgerName().isEmpty()) {
                if ("Purchase".equalsIgnoreCase(v.voucherTypeName()) || "Credit Note".equalsIgnoreCase(v.voucherTypeName())) {
                    partyRoleHints.putIfAbsent(v.partyLedgerName(), "Sundry Creditors");
                } else if ("Sales".equalsIgnoreCase(v.voucherTypeName()) || "Debit Note".equalsIgnoreCase(v.voucherTypeName())) {
                    partyRoleHints.putIfAbsent(v.partyLedgerName(), "Sundry Debtors");
                }
            }
            for (RawVoucherLine line : v.ledgerLines()) {
                if (!line.billAllocations().isEmpty()) {
                    partyRoleHints.putIfAbsent(
                            line.ledgerName(),
                            line.amountSigned().signum() > 0 ? "Sundry Creditors" : "Sundry Debtors");
                }
            }
            for (RawInventoryLine inv : v.inventoryLines()) {
                if (!inv.uomSymbol().isEmpty()) {
                    itemUomHints.putIfAbsent(inv.stockItemName(), inv.uomSymbol());
                    uomsBySymbol.computeIfAbsent(inv.uomSymbol(), sym -> new RawUom(
                            uomsBySymbol.size() + 1,
                            companyGuid + "-uom-" + (uomsBySymbol.size() + 1),
                            sym,
                            sym,
                            0,
                            true));
                }
                if (!inv.godownName().isEmpty()) {
                    godownsByName.computeIfAbsent(inv.godownName(), gd -> new RawGodown(
                            godownsByName.size() + 1,
                            companyGuid + "-gd-" + (godownsByName.size() + 1),
                            gd,
                            "Primary"));
                }
                if (inv.deemedPositive() && inv.ratePerUnit().signum() > 0) {
                    lastPurchaseRateByItem.put(inv.stockItemName(), inv.ratePerUnit());
                }
            }
        }

        for (RawVoucher v : vouchers) {
            for (RawVoucherLine line : v.ledgerLines()) {
                String lname = line.ledgerName();
                if (!ledgersByName.containsKey(lname)) {
                    String parent = inferLedgerGroup(lname, knownGroups, reportLedgerParents, partyRoleHints);
                    int nextId = ledgersByName.size() + 1;
                    ledgersByName.put(lname, new RawLedger(
                            nextId,
                            String.format("%s-led-%04x", companyGuid, nextId),
                            lname,
                            parent,
                            BigDecimal.ZERO,
                            !line.billAllocations().isEmpty(),
                            "",
                            "",
                            nextId));
                }
            }
            for (RawInventoryLine inv : v.inventoryLines()) {
                String iname = inv.stockItemName();
                if (!stockItemsByName.containsKey(iname)) {
                    BigDecimal hintedClosingRate = stockSummaryClosingRates.get(iname);
                    BigDecimal lastLotRate = lastPurchaseRateByItem.get(iname);
                    String costingMethod = (hintedClosingRate != null
                            && lastLotRate != null
                            && hintedClosingRate.compareTo(lastLotRate) == 0)
                            ? "FIFO"
                            : "Avg. Cost";
                    int nextId = stockItemsByName.size() + 1;
                    stockItemsByName.put(iname, new RawStockItem(
                            nextId,
                            String.format("%s-itm-%04x", companyGuid, nextId),
                            iname,
                            "Primary",
                            itemUomHints.getOrDefault(iname, ""),
                            costingMethod,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO));
                }
            }
        }
    }

    private static String inferLedgerGroup(
            String ledgerName,
            Set<String> knownGroups,
            Map<String, String> reportLedgerParents,
            Map<String, String> partyRoleHints) {
        String reported = reportLedgerParents.get(ledgerName);
        if (reported != null && knownGroups.contains(reported)) {
            return reported;
        }
        String partyHint = partyRoleHints.get(ledgerName);
        if (partyHint != null && knownGroups.contains(partyHint)) {
            return partyHint;
        }
        String upper = ledgerName.toUpperCase(Locale.ROOT);
        if (upper.contains("CGST") || upper.contains("SGST") || upper.contains("IGST")
                || upper.contains("TDS") || upper.contains("TCS") || upper.contains("CESS")) {
            return "Duties & Taxes";
        }
        if (upper.contains("CASH")) {
            return "Cash-in-Hand";
        }
        if (upper.contains("BANK") || upper.contains("HDFC") || upper.contains("ICICI")
                || upper.contains("SBI") || upper.contains("CURRENT A/C")) {
            return "Bank Accounts";
        }
        if (upper.contains("CAPITAL")) {
            return "Capital Account";
        }
        if (upper.startsWith("SALES")) {
            return "Sales Accounts";
        }
        if (upper.startsWith("PURCHASE")) {
            return "Purchase Accounts";
        }
        if (upper.contains("ROUND OFF")) {
            return "Indirect Expenses";
        }
        return "Indirect Expenses";
    }

    private static String inferCompanyGuid(RawCompanyProfile profile, Iterable<RawVoucher> vouchers) {
        if (profile != null && !profile.guid().isBlank()) {
            return profile.guid();
        }
        for (RawVoucher v : vouchers) {
            if (v.guid().length() >= 36) {
                return v.guid().substring(0, 36);
            }
        }
        return "00000000-0000-0000-0000-000000000000";
    }

    private static RawCompanyProfile synthesizeCompanyProfile(
            Path xmlDirectory,
            String companyGuid,
            Iterable<RawVoucher> vouchers) {
        String folderName = xmlDirectory.getFileName() != null ? xmlDirectory.getFileName().toString() : "000000";
        String cmpNumber = folderName.chars().allMatch(Character::isDigit) ? folderName : "000000";
        LocalDate minDate = null;
        for (RawVoucher v : vouchers) {
            if (v.date() != null && (minDate == null || v.date().isBefore(minDate))) {
                minDate = v.date();
            }
        }
        LocalDate fyStart = minDate != null ? LocalDate.of(minDate.getYear(), 4, 1) : LocalDate.of(2025, 4, 1);
        return new RawCompanyProfile(
                cmpNumber,
                companyGuid,
                folderName,
                folderName,
                "",
                "India",
                "",
                "",
                fyStart,
                fyStart,
                null,
                "₹",
                "INR");
    }

    private static String extractMasterName(XmlNode node) {
        String attrName = node.attribute("NAME");
        if (attrName != null && !attrName.isBlank()) {
            return TlvRecordDecoder.sanitizeControlPrefix(attrName);
        }
        for (XmlNode child : node.children()) {
            if ("LANGUAGENAME.LIST".equals(child.tag())) {
                for (XmlNode sub : child.children()) {
                    if ("NAME.LIST".equals(sub.tag())) {
                        String n = sub.childText("NAME");
                        if (n != null && !n.isBlank()) {
                            return TlvRecordDecoder.sanitizeControlPrefix(n);
                        }
                    }
                }
            }
        }
        return textOrDefault(node, "NAME", "");
    }

    private static String normalizeParent(String raw) {
        if (raw == null) {
            return "Primary";
        }
        String cleaned = TlvRecordDecoder.sanitizeControlPrefix(raw);
        return cleaned.isEmpty() ? "Primary" : cleaned;
    }

    private static String normalizeOptionalParent(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = TlvRecordDecoder.sanitizeControlPrefix(raw);
        return ("Primary".equalsIgnoreCase(cleaned) || "Not Applicable".equalsIgnoreCase(cleaned)) ? "" : cleaned;
    }

    private static String textOrDefault(XmlNode node, String childTag, String fallback) {
        String val = node.childText(childTag);
        if (val == null) {
            return fallback;
        }
        String cleaned = TlvRecordDecoder.sanitizeControlPrefix(val);
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static boolean isYes(String val) {
        return val != null && "Yes".equalsIgnoreCase(val.trim());
    }

    private static int parseIntOrDefault(String val, int fallback) {
        if (val == null || val.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static LocalDate parseCompactXmlDate(String val) {
        if (val == null || val.trim().length() != 8) {
            return null;
        }
        String trimmed = val.trim();
        for (int i = 0; i < 8; i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return null;
            }
        }
        return LocalDate.parse(trimmed, BASIC_ISO_DATE);
    }

    public static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO.setScale(2);
        }
        String cleaned = raw.trim().replace(",", "");
        int spaceIdx = cleaned.indexOf(' ');
        if (spaceIdx > 0) {
            cleaned = cleaned.substring(0, spaceIdx);
        }
        if (cleaned.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        return new BigDecimal(cleaned);
    }

    private static QtyWithUom parseQuantity(String raw) {
        if (raw == null || raw.isBlank()) {
            return new QtyWithUom(BigDecimal.ZERO, "");
        }
        String trimmed = raw.trim();
        int idx = 0;
        if (idx < trimmed.length() && (trimmed.charAt(idx) == '-' || trimmed.charAt(idx) == '+')) {
            idx++;
        }
        while (idx < trimmed.length() && (Character.isDigit(trimmed.charAt(idx)) || trimmed.charAt(idx) == '.')) {
            idx++;
        }
        if (idx == 0 || (idx == 1 && !Character.isDigit(trimmed.charAt(0)))) {
            return new QtyWithUom(BigDecimal.ZERO, "");
        }
        BigDecimal qty = new BigDecimal(trimmed.substring(0, idx)).abs();
        String uom = trimmed.substring(idx).trim();
        return new QtyWithUom(qty, uom);
    }

    private static BigDecimal parseRate(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        String trimmed = raw.trim();
        int slashIdx = trimmed.indexOf('/');
        String numPart = slashIdx >= 0 ? trimmed.substring(0, slashIdx).trim() : trimmed;
        numPart = numPart.replace(",", "");
        if (numPart.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(numPart);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static <T> List<T> sortedList(Iterable<T> source, Comparator<T> comparator) {
        List<T> copy = new ArrayList<>();
        for (T item : source) {
            copy.add(item);
        }
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static void collectElementsByTag(XmlNode node, String targetTag, List<XmlNode> out) {
        if (targetTag.equals(node.tag())) {
            out.add(node);
        }
        for (XmlNode child : node.children()) {
            collectElementsByTag(child, targetTag, out);
        }
    }

    public static String readAndSanitizeXml(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String raw;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            raw = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            raw = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        } else if (bytes.length >= 4 && bytes[1] == 0 && bytes[3] == 0) {
            raw = new String(bytes, StandardCharsets.UTF_16LE);
        } else if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            raw = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        } else {
            raw = new String(bytes, StandardCharsets.UTF_8);
        }
        return raw;
    }

    private static XmlNode parseXmlDocument(String sourceName, String xml) {
        XmlCursor cursor = new XmlCursor(sourceName, xml);
        while (cursor.hasMore()) {
            cursor.skipWhitespaceAndDeclarations();
            if (!cursor.hasMore()) {
                return null;
            }
            if (cursor.peek() == '<') {
                return cursor.parseElement();
            }
            cursor.advance();
        }
        return null;
    }

    private record QtyWithUom(BigDecimal qty, String uom) {
    }

    private record XmlNode(
            String tag,
            Map<String, String> attributes,
            List<XmlNode> children,
            String text
    ) {
        String attribute(String name) {
            return attributes.get(name);
        }

        String childText(String childTag) {
            for (XmlNode c : children) {
                if (childTag.equals(c.tag)) {
                    return c.text;
                }
            }
            return null;
        }
    }

    private static final class XmlCursor {
        private final String sourceName;
        private final String s;
        private final int len;
        private int pos;

        XmlCursor(String sourceName, String s) {
            this.sourceName = sourceName;
            this.s = s;
            this.len = s.length();
            this.pos = 0;
        }

        boolean hasMore() {
            return pos < len;
        }

        char peek() {
            return s.charAt(pos);
        }

        void advance() {
            pos++;
        }

        void skipWhitespace() {
            while (pos < len && s.charAt(pos) <= ' ') {
                pos++;
            }
        }

        void skipWhitespaceAndDeclarations() {
            while (pos < len) {
                skipWhitespace();
                if (pos + 4 <= len && s.startsWith("<!--", pos)) {
                    int end = s.indexOf("-->", pos + 4);
                    pos = end < 0 ? len : end + 3;
                } else if (pos + 2 <= len && s.startsWith("<?", pos)) {
                    int end = s.indexOf("?>", pos + 2);
                    pos = end < 0 ? len : end + 2;
                } else if (pos + 9 <= len && s.startsWith("<!DOCTYPE", pos)) {
                    int end = s.indexOf('>', pos + 9);
                    pos = end < 0 ? len : end + 1;
                } else {
                    break;
                }
            }
        }

        XmlNode parseElement() {
            if (s.charAt(pos) != '<') {
                throw new CorruptSnapshotException("MALFORMED_XML", "Expected '<' in " + sourceName + " at " + pos);
            }
            pos++;
            int tagStart = pos;
            while (pos < len) {
                char ch = s.charAt(pos);
                if (ch <= ' ' || ch == '/' || ch == '>') {
                    break;
                }
                pos++;
            }
            String tag = s.substring(tagStart, pos);
            Map<String, String> attrs = Map.of();
            Map<String, String> mutableAttrs = null;

            while (pos < len) {
                skipWhitespace();
                if (pos >= len) {
                    break;
                }
                char ch = s.charAt(pos);
                if (ch == '/') {
                    pos++;
                    if (pos < len && s.charAt(pos) == '>') {
                        pos++;
                    }
                    return new XmlNode(tag, mutableAttrs == null ? attrs : mutableAttrs, List.of(), "");
                }
                if (ch == '>') {
                    pos++;
                    break;
                }
                int attrStart = pos;
                while (pos < len && s.charAt(pos) != '=' && s.charAt(pos) > ' ' && s.charAt(pos) != '>') {
                    pos++;
                }
                String attrName = s.substring(attrStart, pos);
                skipWhitespace();
                String attrVal = "";
                if (pos < len && s.charAt(pos) == '=') {
                    pos++;
                    skipWhitespace();
                    if (pos < len && (s.charAt(pos) == '"' || s.charAt(pos) == '\'')) {
                        char quote = s.charAt(pos++);
                        int valStart = pos;
                        while (pos < len && s.charAt(pos) != quote) {
                            pos++;
                        }
                        attrVal = decodeEntities(s.substring(valStart, pos));
                        if (pos < len) {
                            pos++;
                        }
                    }
                }
                if (!attrName.isEmpty()) {
                    if (mutableAttrs == null) {
                        mutableAttrs = new HashMap<>();
                    }
                    mutableAttrs.put(attrName, attrVal);
                }
            }

            List<XmlNode> children = null;
            StringBuilder textBuf = null;

            while (pos < len) {
                int lt = s.indexOf('<', pos);
                if (lt < 0) {
                    pos = len;
                    break;
                }
                if (lt > pos) {
                    if (textBuf == null) {
                        textBuf = new StringBuilder();
                    }
                    textBuf.append(s, pos, lt);
                    pos = lt;
                }
                if (pos + 2 <= len && s.charAt(pos + 1) == '/') {
                    int endGt = s.indexOf('>', pos + 2);
                    pos = endGt < 0 ? len : endGt + 1;
                    break;
                } else if (pos + 4 <= len && s.startsWith("<!--", pos)) {
                    int end = s.indexOf("-->", pos + 4);
                    pos = end < 0 ? len : end + 3;
                } else if (pos + 9 <= len && s.startsWith("<![CDATA[", pos)) {
                    int end = s.indexOf("]]>", pos + 9);
                    if (textBuf == null) {
                        textBuf = new StringBuilder();
                    }
                    if (end < 0) {
                        textBuf.append(s, pos + 9, len);
                        pos = len;
                    } else {
                        textBuf.append(s, pos + 9, end);
                        pos = end + 3;
                    }
                } else {
                    if (children == null) {
                        children = new ArrayList<>();
                    }
                    children.add(parseElement());
                }
            }

            String decodedText = textBuf == null ? "" : decodeEntities(textBuf.toString()).trim();
            return new XmlNode(
                    tag,
                    mutableAttrs == null ? attrs : mutableAttrs,
                    children == null ? List.of() : children,
                    decodedText);
        }

        private static String decodeEntities(String input) {
            if (input.indexOf('&') < 0) {
                return input;
            }
            StringBuilder out = new StringBuilder(input.length());
            int i = 0;
            int n = input.length();
            while (i < n) {
                char c = input.charAt(i);
                if (c == '&') {
                    int semi = input.indexOf(';', i + 1);
                    if (semi > i + 1 && semi - i <= 12) {
                        String entity = input.substring(i + 1, semi);
                        switch (entity) {
                            case "amp" -> out.append('&');
                            case "lt" -> out.append('<');
                            case "gt" -> out.append('>');
                            case "quot" -> out.append('"');
                            case "apos" -> out.append('\'');
                            default -> {
                                if (entity.charAt(0) == '#') {
                                    int codePoint = parseNumericEntity(entity);
                                    if (codePoint >= 0x20 || codePoint == '\t' || codePoint == '\n' || codePoint == '\r') {
                                        out.appendCodePoint(codePoint);
                                    }
                                } else {
                                    out.append('&').append(entity).append(';');
                                }
                            }
                        }
                        i = semi + 1;
                        continue;
                    }
                }
                out.append(c);
                i++;
            }
            return out.toString();
        }

        private static int parseNumericEntity(String entity) {
            try {
                if (entity.length() > 2 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')) {
                    return Integer.parseInt(entity.substring(2), 16);
                }
                return Integer.parseInt(entity.substring(1), 10);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
    }

    /**
     * Extracted canonical structures from a companion XML directory.
     */
    public record XmlSnapshotResult(
            RawCompanyProfile companyProfile,
            List<RawGroup> groups,
            List<RawLedger> ledgers,
            List<RawVoucherType> voucherTypes,
            List<RawUom> uoms,
            List<RawGodown> godowns,
            List<RawStockGroup> stockGroups,
            List<RawStockItem> stockItems,
            List<RawVoucher> vouchers,
            List<ReaderDiagnostic> diagnostics
    ) {
    }
}
