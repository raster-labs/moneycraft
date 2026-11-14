package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.model.RawGodown;
import com.raster.moneycraft.reader.model.RawGroup;
import com.raster.moneycraft.reader.model.RawLedger;
import com.raster.moneycraft.reader.model.RawStockGroup;
import com.raster.moneycraft.reader.model.RawStockItem;
import com.raster.moneycraft.reader.model.RawUom;
import com.raster.moneycraft.reader.model.RawVoucherType;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clean-room parser for Manager.1800 accounting and inventory masters (G1_BINARY_FORMAT_SPEC Section 6.1).
 */
public final class ManagerParser {

    private static final int OBJECT_ROOT_BNODE_BYTES = 22;

    private final Sector512PageReader pageReader;
    private final TlvRecordDecoder tlvDecoder;

    public ManagerParser(Sector512PageReader pageReader, TlvRecordDecoder tlvDecoder) {
        this.pageReader = pageReader;
        this.tlvDecoder = tlvDecoder;
    }

    public ManagerStoreResult parse(String companyGuid, byte[] managerBytes) {
        int totalSectors = managerBytes.length / Sector512PageReader.SECTOR_BYTES;
        List<RawMasterEntry> entries = new ArrayList<>();
        Map<Integer, String> masterNamesById = new HashMap<>();

        for (int s = 1; s < totalSectors; s++) {
            Sector512PageReader.DataSectorHeader hdr = pageReader.readDataSectorHeader(managerBytes, s);
            if (!hdr.isObjectRootHead()) {
                continue;
            }
            byte[] payload = pageReader.reassembleStream("Manager.1800", managerBytes, s);
            if (payload.length < 4) {
                continue;
            }
            int rootHeaderLen = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            int tlvStart = rootHeaderLen + OBJECT_ROOT_BNODE_BYTES;
            if (tlvStart < 0 || tlvStart > payload.length) {
                continue;
            }
            List<TlvField> fields = tlvDecoder.decodeStream("Manager.1800", payload, tlvStart);

            int classId = 0;
            int parentId = 0;
            int alterId = 0;
            String primaryName = extractPrimaryName(fields);

            for (TlvField f : fields) {
                if (f.fieldId() == 0x0003 && f.wireType() == 0x0600) {
                    classId = (int) f.intValue();
                } else if (f.fieldId() == 0x0003 && f.wireType() == 0x0300) {
                    parentId = (int) f.intValue();
                } else if (f.fieldId() == 0x01F8 && f.wireType() == 0x0600) {
                    alterId = (int) f.intValue();
                }
            }

            if (classId > 0 && !primaryName.isEmpty()) {
                masterNamesById.put(hdr.masterId(), primaryName);
                entries.add(new RawMasterEntry(hdr.masterId(), classId, parentId, alterId, primaryName, fields));
            }
        }

        entries.sort(Comparator.comparingInt(RawMasterEntry::masterId));

        List<RawGroup> groups = new ArrayList<>();
        List<RawLedger> ledgers = new ArrayList<>();
        List<RawVoucherType> activeVoucherTypes = new ArrayList<>();
        int dormantVoucherTypeCount = 0;
        List<RawUom> uoms = new ArrayList<>();
        List<RawGodown> godowns = new ArrayList<>();
        List<RawStockGroup> stockGroups = new ArrayList<>();
        List<RawStockItem> stockItems = new ArrayList<>();
        String currencySymbol = "₹";
        String currencyFormalName = "INR";

        String guidPrefix = (companyGuid == null || companyGuid.isBlank())
                ? "00000000-0000-0000-0000-000000000000"
                : companyGuid;

        for (RawMasterEntry entry : entries) {
            String objectGuid = String.format("%s-%08x", guidPrefix, entry.masterId());
            String parentName = entry.parentId() == 0
                    ? "Primary"
                    : masterNamesById.getOrDefault(entry.parentId(), "Primary");

            switch (entry.classId()) {
                case 2 -> groups.add(parseGroup(entry, objectGuid, parentName));
                case 3 -> ledgers.add(parseLedger(entry, objectGuid, parentName));
                case 6 -> godowns.add(new RawGodown(entry.masterId(), objectGuid, entry.name(), parentName));
                case 7 -> stockGroups.add(new RawStockGroup(entry.masterId(), objectGuid, entry.name(), parentName));
                case 9 -> stockItems.add(parseStockItem(entry, objectGuid, parentName, masterNamesById));
                case 10 -> {
                    RawVoucherType vt = parseVoucherType(entry, objectGuid, parentName);
                    if (vt.active()) {
                        activeVoucherTypes.add(vt);
                    } else {
                        dormantVoucherTypeCount++;
                    }
                }
                case 11 -> {
                    currencySymbol = entry.name();
                    for (TlvField f : entry.fields()) {
                        if (f.wireType() == 0x0F00 && f.stringValue() != null && !f.stringValue().equals(currencySymbol)) {
                            currencyFormalName = f.stringValue();
                            break;
                        }
                    }
                }
                case 12 -> uoms.add(parseUom(entry, objectGuid));
                default -> {
                }
            }
        }

        groups.sort(Comparator.comparing(RawGroup::name));
        ledgers.sort(Comparator.comparing(RawLedger::name));
        activeVoucherTypes.sort(Comparator.comparing(RawVoucherType::name));
        uoms.sort(Comparator.comparing(RawUom::symbol));
        godowns.sort(Comparator.comparing(RawGodown::name));
        stockGroups.sort(Comparator.comparing(RawStockGroup::name));
        stockItems.sort(Comparator.comparing(RawStockItem::name));

        return new ManagerStoreResult(
                List.copyOf(groups),
                List.copyOf(ledgers),
                List.copyOf(activeVoucherTypes),
                dormantVoucherTypeCount,
                List.copyOf(uoms),
                List.copyOf(godowns),
                List.copyOf(stockGroups),
                List.copyOf(stockItems),
                Map.copyOf(masterNamesById),
                currencySymbol,
                currencyFormalName);
    }

    private static String extractPrimaryName(List<TlvField> fields) {
        for (TlvField f : fields) {
            if (f.wireType() == 0x1000 && f.fieldId() == 0x0003 && f.keyPrefixes().length > 0) {
                for (TlvField child : f.children()) {
                    if (child.wireType() == 0x0F00 && child.stringValue() != null && !child.stringValue().isEmpty()) {
                        return child.stringValue();
                    }
                }
            }
        }
        for (TlvField f : fields) {
            if (f.wireType() == 0x0F00 && f.fieldId() == 0x0002 && f.stringValue() != null && !f.stringValue().isEmpty()) {
                return f.stringValue();
            }
        }
        return "";
    }

    private static RawGroup parseGroup(RawMasterEntry entry, String guid, String parentName) {
        Set<Integer> boolFlags = new HashSet<>();
        for (TlvField f : entry.fields()) {
            if (f.wireType() == 0x1000 && f.fieldId() == 0x0963) {
                for (TlvField child : f.children()) {
                    if (child.isBooleanTrue()) {
                        boolFlags.add(child.fieldId());
                    }
                }
            }
        }
        boolean deemedPositive = boolFlags.contains(0x01FC);
        boolean subledger = boolFlags.contains(0x0963);
        boolean revenue = boolFlags.contains(0x0964);
        boolean affectsGrossProfit = boolFlags.contains(0x0965);
        return new RawGroup(
                entry.masterId(),
                guid,
                entry.name(),
                parentName,
                revenue,
                deemedPositive,
                affectsGrossProfit,
                subledger,
                entry.alterId());
    }

    private static RawLedger parseLedger(RawMasterEntry entry, String guid, String parentName) {
        boolean billWiseOn = false;
        BigDecimal openingBalanceSigned = BigDecimal.ZERO;
        String gstin = "";
        String stateName = "";

        for (TlvField f : entry.fields()) {
            if (f.fieldId() == 0x0A2F && f.isBooleanTrue()) {
                billWiseOn = true;
            } else if (f.wireType() == 0x0900) {
                openingBalanceSigned = f.toScaledAmount();
            } else if (f.wireType() == 0x0F00 && f.stringValue() != null) {
                String s = f.stringValue();
                if (s.length() == 15 && Character.isDigit(s.charAt(0)) && Character.isDigit(s.charAt(1))) {
                    gstin = s;
                }
            } else if (f.wireType() == 0x1000) {
                for (TlvField child : f.children()) {
                    if (child.wireType() == 0x0F00 && child.stringValue() != null) {
                        String cs = child.stringValue();
                        if (cs.length() == 15 && Character.isDigit(cs.charAt(0)) && Character.isDigit(cs.charAt(1))) {
                            gstin = cs;
                        } else if (child.fieldId() == 0x0002 && stateName.isEmpty() && entry.fieldIdMatchesState(f.fieldId())) {
                            stateName = cs;
                        }
                    }
                }
            }
        }
        return new RawLedger(
                entry.masterId(),
                guid,
                entry.name(),
                parentName,
                openingBalanceSigned,
                billWiseOn,
                gstin,
                stateName,
                entry.alterId());
    }

    private static RawVoucherType parseVoucherType(RawMasterEntry entry, String guid, String parentName) {
        int baseTypeId = 0;
        String abbreviation = "";
        for (TlvField f : entry.fields()) {
            if (f.fieldId() == 0x0FA2 && f.wireType() == 0x0600) {
                baseTypeId = (int) f.intValue();
            } else if (f.fieldId() == 0x01F7 && f.wireType() == 0x0F00 && f.stringValue() != null) {
                abbreviation = f.stringValue();
            }
        }
        boolean active = entry.parentId() != 0 || PredefinedTallyCatalog.isActiveBuiltinVoucherBaseId(baseTypeId);
        String resolvedParent = entry.parentId() == 0 ? "" : parentName;
        return new RawVoucherType(
                entry.masterId(),
                guid,
                entry.name(),
                resolvedParent,
                abbreviation,
                baseTypeId,
                active,
                entry.alterId());
    }

    private static RawUom parseUom(RawMasterEntry entry, String guid) {
        String formalName = "";
        int decimals = 0;
        for (TlvField f : entry.fields()) {
            if (f.wireType() == 0x0F00 && f.stringValue() != null && !f.stringValue().equals(entry.name())) {
                formalName = f.stringValue();
            } else if (f.wireType() == 0x0200 && f.fieldId() == 0x0004) {
                decimals = (int) f.intValue();
            }
        }
        return new RawUom(entry.masterId(), guid, entry.name(), formalName, decimals, true);
    }

    private static RawStockItem parseStockItem(
            RawMasterEntry entry,
            String guid,
            String parentGroupName,
            Map<Integer, String> masterNamesById) {
        String baseUom = "";
        BigDecimal openingValue = BigDecimal.ZERO;
        for (TlvField f : entry.fields()) {
            if (f.wireType() == 0x0300 && f.fieldId() != 0x0003) {
                String candidate = masterNamesById.get((int) f.intValue());
                if (candidate != null && baseUom.isEmpty()) {
                    baseUom = candidate;
                }
            } else if (f.wireType() == 0x0900) {
                openingValue = f.toScaledAmount();
            }
        }
        return new RawStockItem(
                entry.masterId(),
                guid,
                entry.name(),
                parentGroupName,
                baseUom,
                "Avg. Cost",
                BigDecimal.ZERO,
                openingValue);
    }

    private record RawMasterEntry(
            int masterId,
            int classId,
            int parentId,
            int alterId,
            String name,
            List<TlvField> fields
    ) {
        boolean fieldIdMatchesState(int fieldId) {
            return fieldId == 0x0A39 || fieldId == 0x0067;
        }
    }

    /**
     * Aggregated masters extracted from Manager.1800.
     */
    public record ManagerStoreResult(
            List<RawGroup> groups,
            List<RawLedger> ledgers,
            List<RawVoucherType> voucherTypes,
            int dormantVoucherTypeCount,
            List<RawUom> uoms,
            List<RawGodown> godowns,
            List<RawStockGroup> stockGroups,
            List<RawStockItem> stockItems,
            Map<Integer, String> masterNamesById,
            String currencySymbol,
            String currencyFormalName
    ) {
    }
}
