package com.raster.moneycraft.reporting.web;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.reporting.dto.ReportingDtos;
import com.raster.moneycraft.reporting.service.FinancialStatementService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Trial Balance, Trading & P&L, Balance Sheet, MCA Schedule III,
 * Cash Flow, Ratio Analysis, and Bills Receivable/Payable Ageing.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/reports")
public class FinancialReportingController {

    private final FinancialStatementService financialStatementService;

    public FinancialReportingController(FinancialStatementService financialStatementService) {
        this.financialStatementService = financialStatementService;
    }

    @GetMapping("/trial-balance")
    public ReportingDtos.TrialBalanceReportDto getTrialBalance(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(defaultValue = "false") boolean includeOptional
    ) {
        return financialStatementService.getTrialBalance(principal, companyId, fromDate, toDate, includeOptional);
    }

    @GetMapping("/profit-and-loss")
    public ReportingDtos.ProfitAndLossReportDto getProfitAndLoss(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(defaultValue = "false") boolean includeOptional
    ) {
        return financialStatementService.getProfitAndLoss(principal, companyId, fromDate, toDate, includeOptional);
    }

    @GetMapping("/balance-sheet")
    public ReportingDtos.BalanceSheetReportDto getBalanceSheet(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
        @RequestParam(defaultValue = "HORIZONTAL") String format,
        @RequestParam(defaultValue = "false") boolean includeOptional
    ) {
        return financialStatementService.getBalanceSheet(principal, companyId, asOfDate, format, includeOptional);
    }

    @GetMapping("/schedule-iii")
    public ReportingDtos.ScheduleIiiReportDto getScheduleIii(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate comparativeAsOfDate,
        @RequestParam(defaultValue = "DIVISION_I") String entityFormat,
        @RequestParam(defaultValue = "ACTUAL") String roundingScale
    ) {
        return financialStatementService.getScheduleIii(
            principal, companyId, asOfDate, comparativeAsOfDate, entityFormat, roundingScale
        );
    }

    @GetMapping("/cash-flow")
    public ReportingDtos.CashFlowReportDto getCashFlow(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        return financialStatementService.getCashFlow(principal, companyId, fromDate, toDate);
    }

    @GetMapping("/ratios")
    public ReportingDtos.RatioAnalysisReportDto getRatios(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        return financialStatementService.getRatios(principal, companyId, fromDate, toDate);
    }

    @GetMapping("/ageing")
    public ReportingDtos.AgeingReportDto getAgeing(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam(defaultValue = "RECEIVABLES") String type,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate
    ) {
        return financialStatementService.getAgeing(principal, companyId, type, asOfDate);
    }
}
