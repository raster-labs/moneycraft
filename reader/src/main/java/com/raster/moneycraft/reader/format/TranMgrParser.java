package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.model.RawBillAllocation;
import com.raster.moneycraft.reader.model.RawVoucher;
import com.raster.moneycraft.reader.model.RawVoucherLine;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clean-room parser for TranMgr.1800 vouchers, double-entry ledger lines, bill allocations, and Edit Log versions.
 */
public final class TranMgrParser {

    private final Sector512PageReader pageReader;
    private final TlvRecordDecoder tlvDecoder;

    public TranMgrParser(Sector512PageReader pageReader, TlvRecordDecoder tlvDecoder) {
        this.pageReader = pageReader;
        this.tlvDecoder = tlvDecoder;
    }

    public List<RawVoucher> parse(
            String companyGuid,
            byte[] tranMgrBytes,
            Map<Integer, String> masterNamesById,
            Map<Integer, String> billReferenceNames) {
        int totalSectors = tranMgrBytes.length / Sector512PageReader.SECTOR_BYTES;
        Map<Integer, Integer> editLogStreamsByMasterId = new HashMap<>();

        for (int s = 1; s < totalSectors; s++) {
            Sector512PageReader.DataSectorHeader hdr = pageReader.readDataSectorHeader(tranMgrBytes, s);
            if (hdr.pageRole() == 0x05 && hdr.chainSectorSpan() > 0 && hdr.masterId() > 0) {
                editLogStreamsByMasterId.merge(hdr.masterId(), 1, Integer::sum);
            }
        }

        String guidPrefix = (companyGuid == null || companyGuid.isBlank())
                ? "00000000-0000-0000-0000-000000000000"
                : companyGuid;
        Set<String> seenBillRefs = new HashSet<>();
        List<RawVoucher> vouchers = new ArrayList<>();

        for (int s = 1; s < totalSectors; s++) {
            Sector512PageReader.DataSectorHeader hdr = pageReader.readDataSectorHeader(tranMgrBytes, s);
            if (!hdr.isObjectRootHead()) {
                continue;
            }
            byte[] rootPayload = pageReader.reassembleStream("TranMgr.1800", tranMgrBytes, s);
            if (rootPayload.length < 20) {
                continue;
            }
            ByteBuffer rootBuf = ByteBuffer.wrap(rootPayload).order(ByteOrder.LITTLE_ENDIAN);
            int sub1Sector = rootBuf.getInt(12);
            int sub2Sector = rootBuf.getInt(16);
            if (sub1Sector <= 0) {
                continue;
            }

            byte[] sub1Bytes = pageReader.reassembleStream("TranMgr.1800", tranMgrBytes, sub1Sector);
            List<TlvField> headerFields = tlvDecoder.decodeStream("TranMgr.1800", sub1Bytes, 0);

            boolean deleted = false;
            boolean cancelled = false;
            boolean optional = false;
            int voucherTypeId = 0;
            int partyLedgerId = 0;
            int alterId = hdr.masterId();
            LocalDate date = null;
            LocalDate effectiveDate = null;
            String voucherNumber = "";
            String reference = "";
            String narration = "";
            String placeOfSupply = "";

            for (TlvField f : headerFields) {
                if (f.isBooleanTrue()) {
                    if (f.fieldId() == 0x00CB) {
                        deleted = true;
                    } else if (f.fieldId() == 0x00CC) {
                        cancelled = true;
                    } else if (f.fieldId() == 0x00CE) {
                        optional = true;
                    }
                } else if (f.wireType() == 0x0300) {
                    if (f.fieldId() == 0x00CB) {
                        voucherTypeId = (int) f.intValue();
                    } else if (f.fieldId() == 0x00CC) {
                        partyLedgerId = (int) f.intValue();
                    }
                } else if (f.wireType() == 0x0600 && f.fieldId() == 0x0BBC) {
                    alterId = (int) f.intValue();
                } else if (f.wireType() == 0x0D00) {
                    if (f.fieldId() == 0x0067) {
                        date = f.toLocalDate();
                    } else if (f.fieldId() == 0x0068) {
                        effectiveDate = f.toLocalDate();
                    }
                } else if (f.wireType() == 0x0F00 && f.stringValue() != null) {
                    switch (f.fieldId()) {
                        case 0x07D5 -> voucherNumber = f.stringValue();
                        case 0x00CC -> reference = f.stringValue();
                        case 0x00CD -> narration = f.stringValue();
                        case 0x05FA -> placeOfSupply = f.stringValue();
                        default -> {
                        }
                    }
                }
            }

            List<RawVoucherLine> ledgerLines = new ArrayList<>();
            if (!deleted && !cancelled && sub2Sector > 0) {
                byte[] sub2Bytes = pageReader.reassembleStream("TranMgr.1800", tranMgrBytes, sub2Sector);
                List<TlvField> sub2Fields = tlvDecoder.decodeStream("TranMgr.1800", sub2Bytes, 0);
                for (TlvField entryField : sub2Fields) {
                    if (entryField.wireType() == 0x1000 && entryField.fieldId() == 0x2713) {
                        RawVoucherLine line = decodeLedgerLine(
                                entryField, masterNamesById, billReferenceNames, seenBillRefs);
                        if (line != null) {
                            ledgerLines.add(line);
                        }
                    }
                }
            }

            String voucherGuid = String.format("%s-%08x", guidPrefix, hdr.masterId());
            String voucherTypeName = masterNamesById.getOrDefault(voucherTypeId, "Unknown");
            String partyLedgerName = partyLedgerId == 0 ? "" : masterNamesById.getOrDefault(partyLedgerId, "");
            int editLogVersions = Math.max(1, editLogStreamsByMasterId.getOrDefault(hdr.masterId(), 1));

            vouchers.add(new RawVoucher(
                    hdr.masterId(),
                    alterId,
                    voucherGuid,
                    voucherTypeName,
                    voucherNumber,
                    date,
                    effectiveDate != null ? effectiveDate : date,
                    partyLedgerName,
                    reference,
                    narration,
                    placeOfSupply,
                    deleted,
                    cancelled,
                    optional,
                    editLogVersions,
                    ledgerLines,
                    List.of()));
        }

        vouchers.sort(Comparator.comparingInt(RawVoucher::masterId));
        return List.copyOf(vouchers);
    }

    private static RawVoucherLine decodeLedgerLine(
            TlvField entryField,
            Map<Integer, String> masterNamesById,
            Map<Integer, String> billReferenceNames,
            Set<String> seenBillRefs) {
        boolean partyLedger = false;
        boolean deemedPositive = false;
        int ledgerId = 0;
        BigDecimal amountSigned = BigDecimal.ZERO;
        List<RawBillAllocation> billAllocations = new ArrayList<>();

        for (TlvField c1 : entryField.children()) {
            if (c1.isBooleanTrue()) {
                if (c1.fieldId() == 0x0002) {
                    partyLedger = true;
                } else if (c1.fieldId() == 0x0005) {
                    deemedPositive = true;
                }
            } else if (c1.wireType() == 0x1000 && c1.fieldId() == 0x0002) {
                for (TlvField c2 : c1.children()) {
                    if (c2.wireType() == 0x1000 && c2.fieldId() == 0x0002) {
                        for (TlvField c3 : c2.children()) {
                            if (c3.fieldId() == 0x0002 && c3.wireType() == 0x0300) {
                                ledgerId = (int) c3.intValue();
                            } else if (c3.fieldId() == 0x0002 && c3.wireType() == 0x0900) {
                                amountSigned = c3.toScaledAmount();
                                if (c3.isDeemedPositiveFlagged() || amountSigned.signum() < 0) {
                                    deemedPositive = true;
                                }
                            } else if (c3.fieldId() == 0x01F6 && c3.wireType() == 0x1000) {
                                RawBillAllocation bill = decodeBillAllocation(
                                        c3, ledgerId, billReferenceNames, seenBillRefs);
                                if (bill != null) {
                                    billAllocations.add(bill);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (ledgerId <= 0) {
            return null;
        }
        String ledgerName = masterNamesById.getOrDefault(ledgerId, "Ledger#" + ledgerId);
        return new RawVoucherLine(ledgerName, deemedPositive, partyLedger, amountSigned, billAllocations);
    }

    private static RawBillAllocation decodeBillAllocation(
            TlvField billField,
            int ledgerId,
            Map<Integer, String> billReferenceNames,
            Set<String> seenBillRefs) {
        int linkBillId = 0;
        BigDecimal billAmount = BigDecimal.ZERO;
        for (TlvField bf : billField.children()) {
            if (bf.fieldId() == 0x0002 && bf.wireType() == 0x0500) {
                linkBillId = (int) bf.intValue();
            } else if (bf.fieldId() == 0x0002 && bf.wireType() == 0x0900) {
                billAmount = bf.toScaledAmount();
            }
        }
        if (linkBillId <= 0) {
            return null;
        }
        String billName = billReferenceNames.getOrDefault(linkBillId, String.valueOf(linkBillId));
        String billKey = ledgerId + ":" + linkBillId;
        String billType = seenBillRefs.add(billKey) ? "New Ref" : "Agst Ref";
        return new RawBillAllocation(billName, billType, billAmount);
    }
}
