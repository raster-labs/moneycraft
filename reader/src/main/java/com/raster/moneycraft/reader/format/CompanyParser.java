package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.model.RawCompanyProfile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.util.List;

/**
 * Clean-room parser for Company.1800 company profile metadata (G1_BINARY_FORMAT_SPEC Section 6.2).
 */
public final class CompanyParser {

    private final Sector512PageReader pageReader;
    private final TlvRecordDecoder tlvDecoder;

    public CompanyParser(Sector512PageReader pageReader, TlvRecordDecoder tlvDecoder) {
        this.pageReader = pageReader;
        this.tlvDecoder = tlvDecoder;
    }

    public RawCompanyProfile parse(
            String companyNumberHint,
            byte[] companyBytes,
            String currencySymbol,
            String currencyFormalName) {
        int totalSectors = companyBytes.length / Sector512PageReader.SECTOR_BYTES;
        if (totalSectors < 2) {
            throw new CorruptSnapshotException(
                    "EMPTY_COMPANY_STORE",
                    "Company.1800 has no data sectors (totalSectors=" + totalSectors + ")");
        }

        int profileStreamHead = 0;
        for (int s = 1; s < totalSectors; s++) {
            Sector512PageReader.DataSectorHeader hdr = pageReader.readDataSectorHeader(companyBytes, s);
            if (hdr.isObjectRootHead()) {
                byte[] rootPayload = pageReader.reassembleStream("Company.1800", companyBytes, s);
                if (rootPayload.length >= 16) {
                    profileStreamHead = ByteBuffer.wrap(rootPayload).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
                }
                break;
            }
        }
        if (profileStreamHead <= 0) {
            profileStreamHead = totalSectors > 2 ? 2 : 1;
        }

        byte[] profileStream = pageReader.reassembleStream("Company.1800", companyBytes, profileStreamHead);
        List<TlvField> fields = tlvDecoder.decodeStream("Company.1800", profileStream, 0);

        String guid = "";
        String name = "";
        String formalName = "";
        String stateName = "";
        String countryName = "";
        String pincode = "";
        String email = "";
        LocalDate startingFrom = null;
        LocalDate booksFrom = null;
        LocalDate auditedUpTo = null;

        for (TlvField field : fields) {
            if (field.wireType() == 0x0D00) {
                switch (field.fieldId()) {
                    case 0x001B -> startingFrom = field.toLocalDate();
                    case 0x001C -> booksFrom = field.toLocalDate();
                    case 0x09C6 -> auditedUpTo = field.toLocalDate();
                    default -> {
                    }
                }
            } else if (field.wireType() == 0x0F00 && field.stringValue() != null) {
                switch (field.fieldId()) {
                    case 0x001B -> name = field.stringValue();
                    case 0x001C -> formalName = field.stringValue();
                    case 0x001E -> guid = field.stringValue();
                    case 0x0066 -> email = field.stringValue();
                    case 0x0067 -> stateName = field.stringValue();
                    case 0x0068 -> pincode = field.stringValue();
                    case 0x00D2 -> countryName = field.stringValue();
                    default -> {
                    }
                }
            }
        }

        String companyNumber = normalizeCompanyNumber(companyNumberHint);
        return new RawCompanyProfile(
                companyNumber,
                guid,
                name,
                formalName,
                stateName,
                countryName,
                pincode,
                email,
                startingFrom,
                booksFrom,
                auditedUpTo,
                currencySymbol,
                currencyFormalName);
    }

    private static String normalizeCompanyNumber(String hint) {
        if (hint == null || hint.isBlank()) {
            return "000000";
        }
        String trimmed = hint.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return "000000";
            }
        }
        return trimmed;
    }
}
