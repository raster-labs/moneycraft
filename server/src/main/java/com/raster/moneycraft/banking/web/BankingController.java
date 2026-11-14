package com.raster.moneycraft.banking.web;

import com.raster.moneycraft.banking.dto.BankingDtos;
import com.raster.moneycraft.banking.service.BankingAndBrsService;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Banking Cheque Books, CSV/MT940/CAMT.053 BRS, Payroll Masters,
 * and Docs-by-Ira OCR Document Review Queue.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/banking")
public class BankingController {

    private final BankingAndBrsService bankingService;

    public BankingController(BankingAndBrsService bankingService) {
        this.bankingService = bankingService;
    }

    @GetMapping("/cheque-books")
    public List<BankingDtos.ChequeBookDto> listChequeBooks(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return bankingService.listChequeBooks(principal, companyId);
    }

    @PostMapping("/cheque-books")
    @ResponseStatus(HttpStatus.CREATED)
    public BankingDtos.ChequeBookDto createChequeBook(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody BankingDtos.CreateChequeBookRequest request
    ) {
        return bankingService.createChequeBook(principal, companyId, request);
    }

    @GetMapping("/cheque-books/{bookId}/leaves")
    public List<BankingDtos.ChequeLeafDto> listChequeLeaves(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID bookId
    ) {
        return bankingService.listChequeLeaves(principal, companyId, bookId);
    }

    @PostMapping("/statements/import")
    @ResponseStatus(HttpStatus.CREATED)
    public BankingDtos.BankStatementImportResponse importBankStatement(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody BankingDtos.ImportBankStatementRequest request
    ) {
        return bankingService.importBankStatement(principal, companyId, request);
    }

    @GetMapping("/brs")
    public BankingDtos.BrsSummaryDto getBrsSummary(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam UUID bankLedgerId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate
    ) {
        return bankingService.getBrsSummary(principal, companyId, bankLedgerId, asOfDate);
    }

    @PostMapping("/brs/auto-match")
    public BankingDtos.AutoMatchBrsResponse autoMatchBrs(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody BankingDtos.AutoMatchBrsRequest request
    ) {
        return bankingService.autoMatchBrs(principal, companyId, request);
    }

    @PutMapping("/brs/reconcile")
    public BankingDtos.BrsSummaryDto manualReconcileBrs(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody BankingDtos.ManualReconcileBrsRequest request
    ) {
        return bankingService.manualReconcileBrs(principal, companyId, request);
    }

    @GetMapping("/payroll/heads")
    public List<BankingDtos.PayrollHeadDto> listPayrollHeads(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return bankingService.listPayrollHeads(principal, companyId);
    }

    @PostMapping("/payroll/heads")
    @ResponseStatus(HttpStatus.CREATED)
    public BankingDtos.PayrollHeadDto createPayrollHead(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody BankingDtos.CreatePayrollHeadRequest request
    ) {
        return bankingService.createPayrollHead(principal, companyId, request);
    }

    @GetMapping("/payroll/employees")
    public List<BankingDtos.EmployeeDto> listEmployees(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return bankingService.listEmployees(principal, companyId);
    }

    @PostMapping("/payroll/employees")
    @ResponseStatus(HttpStatus.CREATED)
    public BankingDtos.EmployeeDto createEmployee(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody BankingDtos.CreateEmployeeRequest request
    ) {
        return bankingService.createEmployee(principal, companyId, request);
    }

    @GetMapping("/ocr-staging/documents")
    public List<BankingDtos.OcrStagedDocumentDto> listOcrDocuments(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return bankingService.listOcrDocuments(principal, companyId);
    }

    @PostMapping("/ocr-staging/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public BankingDtos.OcrStagedDocumentDto stageOcrDocument(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody BankingDtos.CreateOcrStagedDocRequest request
    ) {
        return bankingService.stageOcrDocument(principal, companyId, request);
    }

    @PostMapping("/ocr-staging/documents/{docId}/approve-convert")
    public BankingDtos.ApproveConvertOcrResponse approveAndConvertOcrDocument(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID docId,
        @RequestBody(required = false) BankingDtos.ApproveConvertOcrRequest request
    ) {
        return bankingService.approveAndConvertOcrDocument(principal, companyId, docId, request);
    }
}
