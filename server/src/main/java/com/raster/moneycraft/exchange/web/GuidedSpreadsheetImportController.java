package com.raster.moneycraft.exchange.web;

import com.raster.moneycraft.exchange.dto.SpreadsheetImportDtos;
import com.raster.moneycraft.exchange.service.GuidedSpreadsheetImportService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * REST endpoints for the Increment X Guided Multi-Format Columnar Spreadsheet Importer (SHEET-001..008).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/spreadsheet-imports")
public class GuidedSpreadsheetImportController {

    private static final MediaType CSV_UTF8_MEDIA_TYPE = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final GuidedSpreadsheetImportService importService;
    private final ObjectMapper objectMapper;

    public GuidedSpreadsheetImportController(
        GuidedSpreadsheetImportService importService,
        ObjectMapper objectMapper
    ) {
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/templates")
    public ResponseEntity<List<SpreadsheetImportDtos.SpreadsheetTemplateDto>> listTemplates(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return ResponseEntity.ok(importService.listSpreadsheetTemplates(principal, companyId));
    }

    @PostMapping("/templates")
    public ResponseEntity<SpreadsheetImportDtos.SpreadsheetTemplateDto> saveTemplate(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody SpreadsheetImportDtos.SaveSpreadsheetTemplateRequest request
    ) {
        SpreadsheetImportDtos.SpreadsheetTemplateDto saved =
            importService.saveSpreadsheetTemplate(principal, companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"v" + saved.versionNo() + "\"")
            .body(saved);
    }

    @GetMapping("/batches")
    public ResponseEntity<List<SpreadsheetImportDtos.SpreadsheetBatchDto>> listBatches(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return ResponseEntity.ok(importService.listBatches(principal, companyId));
    }

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<SpreadsheetImportDtos.SpreadsheetBatchDto> getBatch(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID batchId
    ) {
        SpreadsheetImportDtos.SpreadsheetBatchDto dto = importService.getBatch(principal, companyId, batchId);
        return ResponseEntity.ok()
            .eTag("\"v" + dto.versionNo() + "\"")
            .body(dto);
    }

    @PostMapping(
        path = {"/prepare", "/batches"},
        consumes = {MediaType.APPLICATION_JSON_VALUE, "application/*+json"}
    )
    public ResponseEntity<SpreadsheetImportDtos.SpreadsheetBatchDto> prepareBatchJson(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest request
    ) {
        SpreadsheetImportDtos.SpreadsheetBatchDto created =
            importService.prepareBatch(principal, companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @PostMapping(
        path = {"/prepare", "/batches"},
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<SpreadsheetImportDtos.SpreadsheetBatchDto> prepareBatchMultipart(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(name = "sheetName", required = false) String sheetName,
        @RequestParam(name = "headerRowIndex", required = false) Integer headerRowIndex,
        @RequestParam(name = "delimiter", required = false) String delimiter,
        @RequestParam(name = "cachedFormulaConsent", defaultValue = "false") boolean cachedFormulaConsent,
        @RequestParam(name = "templateId", required = false) UUID templateId,
        @RequestParam(name = "targetType", required = false) String targetType,
        @RequestParam(name = "mappingJson", required = false) String mappingJson
    ) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-SHEET-UPLOAD-READ-FAILED",
                "Failed to Read Uploaded Spreadsheet",
                "Could not read uploaded spreadsheet bytes."
            );
        }
        SpreadsheetImportDtos.SpreadsheetMappingSpecDto mapping = null;
        if (mappingJson != null && !mappingJson.isBlank()) {
            try {
                mapping = objectMapper.readValue(
                    mappingJson,
                    SpreadsheetImportDtos.SpreadsheetMappingSpecDto.class
                );
            } catch (JacksonException ex) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-SHEET-MAPPING-JSON-INVALID",
                    "Invalid mappingJson Parameter",
                    "Failed to parse mappingJson: " + ex.getMessage()
                );
            }
        } else if (targetType != null && !targetType.isBlank()) {
            mapping = new SpreadsheetImportDtos.SpreadsheetMappingSpecDto(
                targetType, "POST", "Journal",
                null, null, null, null, null, null,
                null, null, "DR",
                null, null, null,
                List.of(),
                null, null, null, null, null, null, null, null, null,
                "AUTO", "REJECT"
            );
        }

        String fileName = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest req =
            new SpreadsheetImportDtos.PrepareSpreadsheetBatchRequest(
                fileName,
                null,
                Base64.getEncoder().encodeToString(bytes),
                null,
                sheetName,
                headerRowIndex,
                delimiter,
                cachedFormulaConsent,
                false,
                templateId,
                mapping,
                null
            );
        SpreadsheetImportDtos.SpreadsheetBatchDto created =
            importService.prepareBatch(principal, companyId, req);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @PostMapping("/batches/{batchId}/remap")
    public ResponseEntity<SpreadsheetImportDtos.SpreadsheetBatchDto> remapBatch(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID batchId,
        @RequestBody SpreadsheetImportDtos.RemapSpreadsheetBatchRequest request
    ) {
        SpreadsheetImportDtos.SpreadsheetBatchDto updated =
            importService.remapBatch(principal, companyId, batchId, request);
        return ResponseEntity.ok()
            .eTag("\"v" + updated.versionNo() + "\"")
            .body(updated);
    }

    @PostMapping({"/batches/{batchId}/execute", "/batches/{batchId}/resume"})
    public ResponseEntity<SpreadsheetImportDtos.SpreadsheetBatchDto> executeOrResumeBatch(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID batchId,
        @RequestBody(required = false) SpreadsheetImportDtos.ExecuteSpreadsheetBatchRequest request
    ) {
        SpreadsheetImportDtos.SpreadsheetBatchDto executed =
            importService.executeBatch(principal, companyId, batchId, request);
        return ResponseEntity.ok()
            .eTag("\"v" + executed.versionNo() + "\"")
            .body(executed);
    }

    @PostMapping("/batches/{batchId}/cancel")
    public ResponseEntity<SpreadsheetImportDtos.SpreadsheetBatchDto> cancelBatch(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID batchId
    ) {
        SpreadsheetImportDtos.SpreadsheetBatchDto cancelled =
            importService.cancelBatch(principal, companyId, batchId);
        return ResponseEntity.ok()
            .eTag("\"v" + cancelled.versionNo() + "\"")
            .body(cancelled);
    }

    @GetMapping("/batches/{batchId}/errors.csv")
    public ResponseEntity<byte[]> downloadErrorsCsv(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID batchId
    ) {
        String csv = importService.exportErrorsCsv(principal, companyId, batchId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(CSV_UTF8_MEDIA_TYPE);
        headers.setContentDisposition(
            ContentDisposition.attachment()
                .filename("spreadsheet-import-errors-" + batchId + ".csv")
                .build()
        );
        return new ResponseEntity<>(csv.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }
}
