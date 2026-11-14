package com.raster.moneycraft.statutory.web;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.statutory.dto.StatutoryDtos;
import com.raster.moneycraft.statutory.service.TdsAndEditLogService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for TDS Natures (194C, 194J, 194H, 194I, 194Q, 206C),
 * Challan ITNS 281 recording, Quarterly Form 26Q return, and Protean FVU 1.2 export.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/tds")
public class TdsStatutoryController {

    private static final MediaType FVU_TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final TdsAndEditLogService tdsAndEditLogService;
    private final RbacAuthorizationService rbacAuthorizationService;

    public TdsStatutoryController(
        TdsAndEditLogService tdsAndEditLogService,
        RbacAuthorizationService rbacAuthorizationService
    ) {
        this.tdsAndEditLogService = tdsAndEditLogService;
        this.rbacAuthorizationService = rbacAuthorizationService;
    }

    @GetMapping("/natures")
    public List<StatutoryDtos.TdsNatureDto> listTdsNatures(@PathVariable UUID companyId) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return tdsAndEditLogService.listTdsNatures(principal, companyId);
    }

    @PostMapping("/challans")
    public ResponseEntity<StatutoryDtos.TdsChallanDto> createTdsChallan(
        @PathVariable UUID companyId,
        @RequestBody StatutoryDtos.CreateTdsChallanRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        StatutoryDtos.TdsChallanDto created =
            tdsAndEditLogService.createTdsChallan(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/tds/challans/" + created.id()))
            .body(created);
    }

    @GetMapping("/form26q")
    public StatutoryDtos.TdsForm26qReportDto getForm26q(
        @PathVariable UUID companyId,
        @RequestParam(name = "quarter", required = false, defaultValue = "Q1") String quarter,
        @RequestParam(name = "fiscalYearId", required = false) UUID fiscalYearId
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return tdsAndEditLogService.getForm26q(principal, companyId, quarter, fiscalYearId);
    }

    @PostMapping("/export-fvu")
    public ResponseEntity<byte[]> exportForm26qFvu(
        @PathVariable UUID companyId,
        @RequestParam(name = "quarter", required = false) String quarterParam,
        @RequestParam(name = "fiscalYearId", required = false) UUID fiscalYearIdParam,
        @RequestBody(required = false) StatutoryDtos.TdsExportFvuRequest body
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        String quarter = quarterParam != null && !quarterParam.isBlank()
            ? quarterParam
            : (body != null && body.quarter() != null ? body.quarter() : "Q1");
        UUID fiscalYearId = fiscalYearIdParam != null
            ? fiscalYearIdParam
            : (body != null ? body.fiscalYearId() : null);

        byte[] fvuBytes = tdsAndEditLogService.exportForm26qFvu(
            actor,
            companyId,
            quarter,
            fiscalYearId
        );
        String filename = "Form26Q_" + quarter + "_" + (fiscalYearId != null ? fiscalYearId : "FY") + ".fvu";
        return ResponseEntity.ok()
            .contentType(FVU_TEXT_UTF8)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(fvuBytes);
    }
}
