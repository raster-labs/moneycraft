package com.raster.moneycraft.exchange.web;

import com.raster.moneycraft.exchange.dto.ExchangeDtos;
import com.raster.moneycraft.exchange.service.DataExchangeService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import java.io.IOException;
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

/**
 * REST endpoints for Tally `<ENVELOPE>` XML, Rel 7.2 `JSONEx`, and OpenXML `.xlsx` Data Exchange.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class ExchangeController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final DataExchangeService dataExchangeService;

    public ExchangeController(DataExchangeService dataExchangeService) {
        this.dataExchangeService = dataExchangeService;
    }

    @GetMapping("/exchange/templates")
    public ResponseEntity<List<ExchangeDtos.ExchangeMappingTemplateDto>> listTemplates(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return ResponseEntity.ok(dataExchangeService.listTemplates(principal, companyId));
    }

    @PostMapping("/exchange/templates")
    public ResponseEntity<ExchangeDtos.ExchangeMappingTemplateDto> createTemplate(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody ExchangeDtos.CreateExchangeTemplateRequest request
    ) {
        ExchangeDtos.ExchangeMappingTemplateDto created =
            dataExchangeService.createTemplate(principal, companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/exports/xml")
    public ResponseEntity<ExchangeDtos.ExportDataResultDto> exportXml(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody(required = false) ExchangeDtos.ExportDataRequest request
    ) {
        return ResponseEntity.ok(dataExchangeService.exportXml(principal, companyId, request));
    }

    @PostMapping(
        path = "/imports/xml",
        consumes = {MediaType.APPLICATION_JSON_VALUE, "application/*+json"}
    )
    public ResponseEntity<ExchangeDtos.ImportDataResultDto> importXmlJson(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody ExchangeDtos.ImportDataRequest request
    ) {
        String rawXml = request != null && request.rawXml() != null && !request.rawXml().isBlank()
            ? request.rawXml()
            : (request == null ? null : request.content());
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        return ResponseEntity.ok(dataExchangeService.importXml(principal, companyId, rawXml, dryRun));
    }

    @PostMapping(
        path = "/imports/xml",
        consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE}
    )
    public ResponseEntity<ExchangeDtos.ImportDataResultDto> importXmlRaw(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody String rawXml,
        @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun
    ) {
        return ResponseEntity.ok(dataExchangeService.importXml(principal, companyId, rawXml, dryRun));
    }

    @PostMapping("/exports/jsonex")
    public ResponseEntity<ExchangeDtos.JsonExExportResponseDto> exportJsonEx(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody(required = false) ExchangeDtos.ExportDataRequest request
    ) {
        return ResponseEntity.ok(dataExchangeService.exportJsonEx(principal, companyId, request));
    }

    @PostMapping("/imports/jsonex")
    public ResponseEntity<ExchangeDtos.ImportDataResultDto> importJsonEx(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody ExchangeDtos.ImportDataRequest request
    ) {
        return ResponseEntity.ok(dataExchangeService.importJsonEx(principal, companyId, request));
    }

    @PostMapping("/exports/excel")
    public ResponseEntity<byte[]> exportExcel(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody(required = false) ExchangeDtos.ExportDataRequest request
    ) {
        byte[] bytes = dataExchangeService.exportExcel(principal, companyId, request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX_MEDIA_TYPE);
        headers.setContentDisposition(
            ContentDisposition.attachment().filename("moneycraft-export.xlsx").build()
        );
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    @PostMapping(
        path = "/imports/excel",
        consumes = {MediaType.APPLICATION_JSON_VALUE, "application/*+json"}
    )
    public ResponseEntity<ExchangeDtos.ImportDataResultDto> importExcelJson(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody ExchangeDtos.ImportDataRequest request
    ) {
        return ResponseEntity.ok(dataExchangeService.importExcelJsonRequest(principal, companyId, request));
    }

    @PostMapping(path = "/imports/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExchangeDtos.ImportDataResultDto> importExcelMultipart(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(name = "templateId", required = false) UUID templateId,
        @RequestParam(name = "targetType", required = false) String targetType,
        @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun
    ) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXG-EXCEL-READ",
                "Failed to Read Uploaded Workbook",
                "Could not read uploaded .xlsx file."
            );
        }
        return ResponseEntity.ok(
            dataExchangeService.importExcelBytes(principal, companyId, bytes, templateId, targetType, dryRun)
        );
    }
}
