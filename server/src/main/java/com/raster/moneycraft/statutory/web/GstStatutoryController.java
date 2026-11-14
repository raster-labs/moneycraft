package com.raster.moneycraft.statutory.web;

import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.statutory.dto.StatutoryDtos;
import com.raster.moneycraft.statutory.service.EInvoiceAndEWayBillService;
import com.raster.moneycraft.statutory.service.GstAndTdsComputationService;
import com.raster.moneycraft.statutory.service.GstReturnsAndImsService;
import com.raster.moneycraft.statutory.service.StatRulePackAndGstMasterService;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Phase 5 Multi-GSTIN, Signed Statutory Rule Packs, Live GST Compute Preview,
 * Uncertain Transactions, GSTR-1/3B, One-Click GSTR-9 (.xlsx/.json), Rel 7.2 IMS Workbench,
 * and 30-Day Tink-Encrypted NIC e-Invoice / e-Way Bill Connector SPI.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class GstStatutoryController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final StatRulePackAndGstMasterService gstMasterService;
    private final GstAndTdsComputationService gstComputationService;
    private final GstReturnsAndImsService gstReturnsAndImsService;
    private final EInvoiceAndEWayBillService einvoiceAndEwbService;
    private final RbacAuthorizationService rbacAuthorizationService;

    public GstStatutoryController(
        StatRulePackAndGstMasterService gstMasterService,
        GstAndTdsComputationService gstComputationService,
        GstReturnsAndImsService gstReturnsAndImsService,
        EInvoiceAndEWayBillService einvoiceAndEwbService,
        RbacAuthorizationService rbacAuthorizationService
    ) {
        this.gstMasterService = gstMasterService;
        this.gstComputationService = gstComputationService;
        this.gstReturnsAndImsService = gstReturnsAndImsService;
        this.einvoiceAndEwbService = einvoiceAndEwbService;
        this.rbacAuthorizationService = rbacAuthorizationService;
    }

    // -------------------------------------------------------------------------
    // 1. Multi-GSTIN & Signed Statutory Rule Packs
    // -------------------------------------------------------------------------

    @GetMapping("/statutory/rule-packs")
    public List<StatutoryDtos.StatutoryRulePackDto> listRulePacks(
        @PathVariable UUID companyId,
        @RequestParam(name = "asOfDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstMasterService.listRulePacks(principal, companyId, asOfDate);
    }

    @GetMapping("/gst/registrations")
    public List<StatutoryDtos.GstRegistrationDto> listGstRegistrations(@PathVariable UUID companyId) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstMasterService.listGstRegistrations(principal, companyId);
    }

    @PostMapping("/gst/registrations")
    public ResponseEntity<StatutoryDtos.GstRegistrationDto> createGstRegistration(
        @PathVariable UUID companyId,
        @RequestBody StatutoryDtos.UpsertGstRegistrationRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        StatutoryDtos.GstRegistrationDto created =
            gstMasterService.createGstRegistration(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/gst/registrations/" + created.id()))
            .eTag("v" + created.versionNo())
            .body(created);
    }

    @GetMapping("/gst/registrations/{registrationId:[0-9a-fA-F-]{36}}")
    public ResponseEntity<StatutoryDtos.GstRegistrationDto> getGstRegistration(
        @PathVariable UUID companyId,
        @PathVariable UUID registrationId
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        StatutoryDtos.GstRegistrationDto dto =
            gstMasterService.getGstRegistration(principal, companyId, registrationId);
        return ResponseEntity.ok()
            .eTag("v" + dto.versionNo())
            .body(dto);
    }

    @PutMapping("/gst/registrations/{registrationId:[0-9a-fA-F-]{36}}")
    public ResponseEntity<StatutoryDtos.GstRegistrationDto> updateGstRegistration(
        @PathVariable UUID companyId,
        @PathVariable UUID registrationId,
        @RequestHeader(name = "If-Match", required = false) String ifMatchHeader,
        @RequestBody StatutoryDtos.UpsertGstRegistrationRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        StatutoryDtos.GstRegistrationDto updated = gstMasterService.updateGstRegistration(
            actor,
            companyId,
            registrationId,
            ifMatchHeader,
            payload
        );
        return ResponseEntity.ok()
            .eTag("v" + updated.versionNo())
            .body(updated);
    }

    // -------------------------------------------------------------------------
    // 2. Live GST Compute Preview & Uncertain Transactions (`Ctrl+J`)
    // -------------------------------------------------------------------------

    @PostMapping("/gst/compute-preview")
    public StatutoryDtos.GstComputePreviewResponse computeGstPreview(
        @PathVariable UUID companyId,
        @RequestBody VoucherDtos.UpsertVoucherRequest payload
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstComputationService.computePreview(principal, companyId, payload);
    }

    @GetMapping("/gst/uncertain-transactions")
    public List<StatutoryDtos.UncertainTransactionDto> listUncertainTransactions(
        @PathVariable UUID companyId,
        @RequestParam(name = "fromDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(name = "toDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstComputationService.listUncertainTransactions(
            principal,
            companyId,
            fromDate,
            toDate
        );
    }

    @PostMapping("/gst/uncertain-transactions/{voucherId:[0-9a-fA-F-]{36}}/accept-conflict")
    public StatutoryDtos.UncertainTransactionDto acceptUncertainConflict(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId,
        @RequestBody(required = false) StatutoryDtos.AcceptGstConflictRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstComputationService.acceptUncertainConflict(actor, companyId, voucherId, payload);
    }

    // -------------------------------------------------------------------------
    // 3. GST Returns (`GSTR-1`, `GSTR-3B`, One-Click `GSTR-9`)
    // -------------------------------------------------------------------------

    @GetMapping("/gst/gstr1")
    public StatutoryDtos.Gstr1ReportDto getGstr1(
        @PathVariable UUID companyId,
        @RequestParam(name = "fromDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(name = "toDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(name = "gstRegistrationId", required = false) UUID gstRegistrationId
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstReturnsAndImsService.getGstr1(
            principal,
            companyId,
            fromDate,
            toDate,
            gstRegistrationId
        );
    }

    @PostMapping("/gst/gstr1/export")
    public ResponseEntity<byte[]> exportGstr1(
        @PathVariable UUID companyId,
        @RequestParam(name = "fromDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDateParam,
        @RequestParam(name = "toDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDateParam,
        @RequestParam(name = "gstRegistrationId", required = false) UUID gstRegistrationIdParam,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        LocalDate fromDate = fromDateParam != null ? fromDateParam : parseDateFromMap(body, "fromDate");
        LocalDate toDate = toDateParam != null ? toDateParam : parseDateFromMap(body, "toDate");
        UUID gstRegId = gstRegistrationIdParam != null
            ? gstRegistrationIdParam
            : parseUuidFromMap(body, "gstRegistrationId");

        byte[] jsonBytes = gstReturnsAndImsService.exportGstr1Json(
            principal,
            companyId,
            fromDate,
            toDate,
            gstRegId
        );
        String filename = "GSTR1_" + (toDate != null ? toDate.toString() : "return") + ".json";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(jsonBytes);
    }

    @GetMapping("/gst/gstr3b")
    public StatutoryDtos.Gstr3bReportDto getGstr3b(
        @PathVariable UUID companyId,
        @RequestParam(name = "fromDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(name = "toDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(name = "returnPeriod", required = false) String returnPeriod,
        @RequestParam(name = "gstRegistrationId", required = false) UUID gstRegistrationId
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstReturnsAndImsService.getGstr3b(
            principal,
            companyId,
            fromDate,
            toDate,
            returnPeriod,
            gstRegistrationId
        );
    }

    @GetMapping("/gst/gstr9")
    public StatutoryDtos.Gstr9ReportDto getGstr9(
        @PathVariable UUID companyId,
        @RequestParam(name = "fiscalYearId", required = false) UUID fiscalYearId
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstReturnsAndImsService.getGstr9(principal, companyId, fiscalYearId);
    }

    @PostMapping("/gst/gstr9/export")
    public ResponseEntity<byte[]> exportGstr9(
        @PathVariable UUID companyId,
        @RequestParam(name = "fiscalYearId", required = false) UUID fiscalYearIdParam,
        @RequestParam(name = "format", required = false) String formatParam,
        @RequestBody(required = false) StatutoryDtos.Gstr9ExportRequest body
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        UUID fiscalYearId = fiscalYearIdParam != null
            ? fiscalYearIdParam
            : (body != null ? body.fiscalYearId() : null);
        String rawFormat = formatParam != null && !formatParam.isBlank()
            ? formatParam
            : (body != null && body.format() != null ? body.format() : "XLSX");
        String normalizedFormat = rawFormat.trim().toUpperCase(Locale.ROOT);

        byte[] payload = gstReturnsAndImsService.exportGstr9(
            principal,
            companyId,
            fiscalYearId,
            normalizedFormat
        );
        boolean isJson = "JSON".equals(normalizedFormat);
        String ext = isJson ? "json" : "xlsx";
        String filename = "GSTR9_" + (fiscalYearId != null ? fiscalYearId : "annual") + "." + ext;
        return ResponseEntity.ok()
            .contentType(isJson ? MediaType.APPLICATION_JSON : XLSX_MEDIA_TYPE)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(payload);
    }

    // -------------------------------------------------------------------------
    // 4. Rel 7.2 IMS (Invoice Management System) Reconciliation
    // -------------------------------------------------------------------------

    @PostMapping("/gst/ims/import-json")
    public List<StatutoryDtos.ImsInvoiceDto> importImsJson(
        @PathVariable UUID companyId,
        @RequestBody StatutoryDtos.ImsImportRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstReturnsAndImsService.importImsJson(actor, companyId, payload);
    }

    @GetMapping("/gst/ims/invoices")
    public List<StatutoryDtos.ImsInvoiceDto> listImsInvoices(
        @PathVariable UUID companyId,
        @RequestParam(name = "returnPeriod", required = false) String returnPeriod,
        @RequestParam(name = "gstRegistrationId", required = false) UUID gstRegistrationId
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstReturnsAndImsService.listImsInvoices(
            principal,
            companyId,
            returnPeriod,
            gstRegistrationId
        );
    }

    @PostMapping("/gst/ims/actions")
    public StatutoryDtos.ImsInvoiceDto applyImsAction(
        @PathVariable UUID companyId,
        @RequestBody StatutoryDtos.ImsActionRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return gstReturnsAndImsService.applyImsAction(actor, companyId, payload);
    }

    // -------------------------------------------------------------------------
    // 5. 30-Day Tink-Encrypted NIC e-Invoice & e-Way Bill Connector SPI
    // -------------------------------------------------------------------------

    @PostMapping("/gst/einvoice/{voucherId:[0-9a-fA-F-]{36}}/generate")
    public StatutoryDtos.EinvoiceGenerateResponse generateEinvoice(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return einvoiceAndEwbService.generateEInvoice(actor, companyId, voucherId);
    }

    @PostMapping("/gst/einvoice/{voucherId:[0-9a-fA-F-]{36}}/cancel")
    public StatutoryDtos.EinvoiceGenerateResponse cancelEinvoice(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId,
        @RequestBody StatutoryDtos.EinvoiceCancelRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return einvoiceAndEwbService.cancelEInvoice(actor, companyId, voucherId, payload);
    }

    @PostMapping("/gst/einvoice/export-offline-json")
    public ResponseEntity<byte[]> exportEinvoiceOfflineJson(
        @PathVariable UUID companyId,
        @RequestBody(required = false) StatutoryDtos.OfflineBulkExportRequest payload
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        byte[] jsonBytes = einvoiceAndEwbService.exportEInvoiceOfflineJson(
            principal,
            companyId,
            payload
        );
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"einvoice_bulk_offline.json\"")
            .body(jsonBytes);
    }

    @PostMapping("/gst/ewaybill/{voucherId:[0-9a-fA-F-]{36}}/generate")
    public StatutoryDtos.EwayBillGenerateResponse generateEwayBill(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId,
        @RequestBody(required = false) StatutoryDtos.EwayBillGenerateRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return einvoiceAndEwbService.generateEWayBill(actor, companyId, voucherId, payload);
    }

    @PostMapping("/gst/ewaybill/export-offline-json")
    public ResponseEntity<byte[]> exportEwayBillOfflineJson(
        @PathVariable UUID companyId,
        @RequestBody(required = false) StatutoryDtos.OfflineBulkExportRequest payload
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        byte[] jsonBytes = einvoiceAndEwbService.exportEWayBillOfflineJson(
            principal,
            companyId,
            payload
        );
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ewaybill_bulk_offline.json\"")
            .body(jsonBytes);
    }

    private LocalDate parseDateFromMap(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object raw = body.get(key);
        if (raw instanceof String str && !str.isBlank()) {
            return LocalDate.parse(str.trim());
        }
        return null;
    }

    private UUID parseUuidFromMap(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object raw = body.get(key);
        if (raw instanceof String str && !str.isBlank()) {
            return UUID.fromString(str.trim());
        }
        return null;
    }
}
