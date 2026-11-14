package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.api.CorruptSnapshotException;
import com.raster.moneycraft.reader.model.DiagnosticSeverity;
import com.raster.moneycraft.reader.model.RawCompanyProfile;
import com.raster.moneycraft.reader.model.RawGodown;
import com.raster.moneycraft.reader.model.RawGroup;
import com.raster.moneycraft.reader.model.RawLedger;
import com.raster.moneycraft.reader.model.RawStockGroup;
import com.raster.moneycraft.reader.model.RawStockItem;
import com.raster.moneycraft.reader.model.RawUom;
import com.raster.moneycraft.reader.model.RawVoucher;
import com.raster.moneycraft.reader.model.RawVoucherType;
import com.raster.moneycraft.reader.model.ReaderDiagnostic;
import com.raster.moneycraft.reader.model.StoreFileDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates physical .1800 store discovery, Sector-0 header verification, CmpSave shadow verification,
 * and clean-room binary TLV extraction across Company, Manager, LinkMgr, TranMgr, Aggr, and ExtMngr stores.
 */
public final class BinaryStoreScanner {

    private final Sector512PageReader pageReader;
    private final CmpSaveShadowVerifier shadowVerifier;
    private final CompanyParser companyParser;
    private final ManagerParser managerParser;
    private final LinkMgrParser linkMgrParser;
    private final TranMgrParser tranMgrParser;
    private final AggrParser aggrParser;
    private final ExtMngrParser extMngrParser;

    public BinaryStoreScanner() {
        this.pageReader = new Sector512PageReader();
        this.shadowVerifier = new CmpSaveShadowVerifier();
        TlvRecordDecoder tlvDecoder = new TlvRecordDecoder();
        this.companyParser = new CompanyParser(pageReader, tlvDecoder);
        this.managerParser = new ManagerParser(pageReader, tlvDecoder);
        this.linkMgrParser = new LinkMgrParser(pageReader);
        this.tranMgrParser = new TranMgrParser(pageReader, tlvDecoder);
        this.aggrParser = new AggrParser(pageReader);
        this.extMngrParser = new ExtMngrParser(pageReader);
    }

    public BinaryScanResult scanDirectory(Path snapshotDirectory, boolean failOnShadowMismatch) throws IOException {
        List<Path> storePaths = pageReader.discoverStoreFiles(snapshotDirectory);
        if (storePaths.isEmpty()) {
            throw new CorruptSnapshotException(
                    "NO_1800_STORES_FOUND",
                    "No .1800 store files found in directory: " + snapshotDirectory);
        }

        List<StoreFileDescriptor> descriptors = new ArrayList<>();
        Map<String, byte[]> bytesByStoreName = new HashMap<>();
        for (Path storePath : storePaths) {
            byte[] bytes = Files.readAllBytes(storePath);
            String fileName = storePath.getFileName().toString();
            StoreFileDescriptor descriptor = pageReader.inspectStoreBytes(fileName, bytes);
            descriptors.add(descriptor);
            bytesByStoreName.put(fileName, bytes);
        }

        List<ReaderDiagnostic> diagnostics = new ArrayList<>();
        CmpSaveShadowVerifier.ShadowVerificationResult shadowResult =
                shadowVerifier.verify(snapshotDirectory, failOnShadowMismatch);
        diagnostics.addAll(shadowResult.diagnostics());

        byte[] companyBytes = bytesByStoreName.get("Company.1800");
        if (companyBytes == null) {
            throw new CorruptSnapshotException(
                    "MISSING_COMPANY_STORE",
                    "Company.1800 is required to extract binary snapshot");
        }

        byte[] managerBytes = bytesByStoreName.get("Manager.1800");
        String folderName = snapshotDirectory.getFileName() != null
                ? snapshotDirectory.getFileName().toString()
                : "000000";

        RawCompanyProfile prelimProfile = companyParser.parse(folderName, companyBytes, "₹", "INR");
        ManagerParser.ManagerStoreResult managerResult;
        if (managerBytes != null && managerBytes.length > Sector512PageReader.SECTOR_BYTES) {
            managerResult = managerParser.parse(prelimProfile.guid(), managerBytes);
            if (managerResult.dormantVoucherTypeCount() > 0) {
                diagnostics.add(new ReaderDiagnostic(
                        DiagnosticSeverity.INFO,
                        "DORMANT_VOUCHER_TYPES_FILTERED",
                        "Manager.1800",
                        "Filtered " + managerResult.dormantVoucherTypeCount()
                                + " dormant built-in voucher types; retained "
                                + managerResult.voucherTypes().size() + " active voucher types"));
            }
        } else {
            managerResult = new ManagerParser.ManagerStoreResult(
                    List.of(), List.of(), List.of(), 0, List.of(), List.of(), List.of(), List.of(),
                    Map.of(), "₹", "INR");
        }

        RawCompanyProfile companyProfile = new RawCompanyProfile(
                preliminaryNumber(prelimProfile),
                prelimProfile.guid(),
                prelimProfile.name(),
                prelimProfile.formalName(),
                prelimProfile.stateName(),
                prelimProfile.countryName(),
                prelimProfile.pincode(),
                prelimProfile.email(),
                prelimProfile.startingFrom(),
                prelimProfile.booksFrom(),
                prelimProfile.auditedUpTo(),
                managerResult.currencySymbol(),
                managerResult.currencyFormalName());

        byte[] linkMgrBytes = bytesByStoreName.get("LinkMgr.1800");
        Map<Integer, String> billReferenceNames = (linkMgrBytes != null && linkMgrBytes.length > Sector512PageReader.SECTOR_BYTES)
                ? linkMgrParser.parseBillReferenceNames(linkMgrBytes)
                : Map.of();

        byte[] tranMgrBytes = bytesByStoreName.get("TranMgr.1800");
        List<RawVoucher> vouchers = (tranMgrBytes != null && tranMgrBytes.length > Sector512PageReader.SECTOR_BYTES)
                ? tranMgrParser.parse(
                        companyProfile.guid(),
                        tranMgrBytes,
                        managerResult.masterNamesById(),
                        billReferenceNames)
                : List.of();

        long deletedCount = vouchers.stream().filter(RawVoucher::deleted).count();
        if (deletedCount > 0) {
            diagnostics.add(new ReaderDiagnostic(
                    DiagnosticSeverity.INFO,
                    "SOFT_DELETED_VOUCHERS_EXCLUDED",
                    "TranMgr.1800",
                    "Excluded " + deletedCount + " soft-deleted voucher root(s) from active turnover"));
        }

        byte[] aggrBytes = bytesByStoreName.get("Aggr.1800");
        if (aggrBytes != null) {
            diagnostics.add(aggrParser.verifyRollupSectors(aggrBytes));
        }

        for (String auxStore : List.of("ExtMngr.1800", "VchStatus.1800", "StatStatus.1800")) {
            byte[] auxBytes = bytesByStoreName.get(auxStore);
            if (auxBytes != null) {
                diagnostics.add(extMngrParser.verifyAuxiliaryStore(auxStore, auxBytes));
            }
        }

        return new BinaryScanResult(
                companyProfile,
                List.copyOf(descriptors),
                managerResult.groups(),
                managerResult.ledgers(),
                managerResult.voucherTypes(),
                managerResult.uoms(),
                managerResult.godowns(),
                managerResult.stockGroups(),
                managerResult.stockItems(),
                vouchers,
                shadowResult.verified(),
                List.copyOf(diagnostics));
    }

    private static String preliminaryNumber(RawCompanyProfile prelimProfile) {
        return prelimProfile.companyNumber();
    }

    /**
     * Complete structural result of scanning a .1800 binary directory.
     */
    public record BinaryScanResult(
            RawCompanyProfile companyProfile,
            List<StoreFileDescriptor> storeFiles,
            List<RawGroup> groups,
            List<RawLedger> ledgers,
            List<RawVoucherType> voucherTypes,
            List<RawUom> uoms,
            List<RawGodown> godowns,
            List<RawStockGroup> stockGroups,
            List<RawStockItem> stockItems,
            List<RawVoucher> vouchers,
            boolean shadowCommitVerified,
            List<ReaderDiagnostic> diagnostics
    ) {
    }
}
