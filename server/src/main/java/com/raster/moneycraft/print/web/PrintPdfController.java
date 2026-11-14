package com.raster.moneycraft.print.web;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.print.dto.PrintDtos;
import com.raster.moneycraft.print.service.DeterministicPdfPrintService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for deterministic Brand Identity v0.2.0 PDF generation
 * (Tax Invoices / Vouchers and Financial / Ageing / Supply-Chain Statements).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class PrintPdfController {

    private final DeterministicPdfPrintService printService;

    public PrintPdfController(DeterministicPdfPrintService printService) {
        this.printService = printService;
    }

    @PostMapping(value = "/vouchers/{voucherId}/print-pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printVoucherPdf(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId,
        @RequestBody(required = false) PrintDtos.VoucherPrintRequest body
    ) {
        byte[] pdfBytes = printService.printVoucherPdf(principal, companyId, voucherId, body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
            ContentDisposition.attachment()
                .filename("voucher-" + voucherId + ".pdf")
                .build()
        );
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }

    @PostMapping(value = "/reports/{reportType}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printReportPdf(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable String reportType,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate comparativeAsOfDate,
        @RequestParam(required = false) String format,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String entityFormat,
        @RequestParam(required = false) String roundingScale,
        @RequestParam(required = false) Boolean includeOptional,
        @RequestParam(required = false) UUID stockGroupId,
        @RequestParam(required = false) String valuationMethodOverride,
        @RequestBody(required = false) PrintDtos.ReportPrintRequest body
    ) {
        PrintDtos.ReportPrintRequest merged = new PrintDtos.ReportPrintRequest(
            fromDate != null ? fromDate : (body != null ? body.fromDate() : null),
            toDate != null ? toDate : (body != null ? body.toDate() : null),
            asOfDate != null ? asOfDate : (body != null ? body.asOfDate() : null),
            comparativeAsOfDate != null ? comparativeAsOfDate : (body != null ? body.comparativeAsOfDate() : null),
            format != null ? format : (body != null ? body.format() : null),
            type != null ? type : (body != null ? body.type() : null),
            entityFormat != null ? entityFormat : (body != null ? body.entityFormat() : null),
            roundingScale != null ? roundingScale : (body != null ? body.roundingScale() : null),
            includeOptional != null ? includeOptional : (body != null ? body.includeOptional() : Boolean.FALSE),
            stockGroupId != null ? stockGroupId : (body != null ? body.stockGroupId() : null),
            valuationMethodOverride != null
                ? valuationMethodOverride
                : (body != null ? body.valuationMethodOverride() : null)
        );
        byte[] pdfBytes = printService.printReportPdf(principal, companyId, reportType, merged);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
            ContentDisposition.attachment()
                .filename(reportType + "-" + companyId + ".pdf")
                .build()
        );
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }
}
