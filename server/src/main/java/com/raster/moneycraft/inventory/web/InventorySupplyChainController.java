package com.raster.moneycraft.inventory.web;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.inventory.dto.SupplyChainReportDtos;
import com.raster.moneycraft.inventory.service.ValuationService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Phase 4 Supply Chain, Manufacturing Preview,
 * and 8-Method Inventory Valuation Reports.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InventorySupplyChainController {

    private final ValuationService valuationService;
    private final RbacAuthorizationService rbacGuard;

    public InventorySupplyChainController(
        ValuationService valuationService,
        RbacAuthorizationService rbacGuard
    ) {
        this.valuationService = valuationService;
        this.rbacGuard = rbacGuard;
    }

    @GetMapping("/orders/open")
    public List<SupplyChainReportDtos.OpenOrderLineDto> listOpenOrders(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "orderType", required = false) String orderType,
        @RequestParam(value = "partyLedgerId", required = false) UUID partyLedgerId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return valuationService.listOpenOrders(principal, companyId, orderType, partyLedgerId);
    }

    @PostMapping("/vouchers/manufacturing-preview")
    public SupplyChainReportDtos.ManufacturingPreviewResponse previewManufacturing(
        @PathVariable("companyId") UUID companyId,
        @RequestBody SupplyChainReportDtos.ManufacturingPreviewRequest request
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return valuationService.previewManufacturing(principal, companyId, request);
    }

    @GetMapping("/reports/stock-summary")
    public SupplyChainReportDtos.StockSummaryReportDto getStockSummary(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "fromDate", required = false) LocalDate fromDate,
        @RequestParam(value = "toDate", required = false) LocalDate toDate,
        @RequestParam(value = "stockGroupId", required = false) UUID stockGroupId,
        @RequestParam(value = "valuationMethodOverride", required = false) String valuationMethodOverride
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return valuationService.getStockSummary(
            principal,
            companyId,
            fromDate,
            toDate,
            stockGroupId,
            valuationMethodOverride
        );
    }

    @GetMapping("/reports/godown-summary")
    public SupplyChainReportDtos.GodownSummaryReportDto getGodownSummary(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "godownId", required = false) UUID godownId,
        @RequestParam(value = "asOfDate", required = false) LocalDate asOfDate,
        @RequestParam(value = "toDate", required = false) LocalDate toDate
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : toDate;
        return valuationService.getGodownSummary(principal, companyId, godownId, effectiveAsOf);
    }

    @GetMapping("/reports/movement-analysis")
    public SupplyChainReportDtos.MovementAnalysisReportDto getMovementAnalysis(
        @PathVariable("companyId") UUID companyId,
        @RequestParam("stockItemId") UUID stockItemId,
        @RequestParam(value = "fromDate", required = false) LocalDate fromDate,
        @RequestParam(value = "toDate", required = false) LocalDate toDate
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return valuationService.getMovementAnalysis(principal, companyId, stockItemId, fromDate, toDate);
    }

    @GetMapping("/reports/batch-ageing")
    public SupplyChainReportDtos.BatchAgeingReportDto getBatchAgeing(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "stockItemId", required = false) UUID stockItemId,
        @RequestParam(value = "asOfDate", required = false) LocalDate asOfDate
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return valuationService.getBatchAgeing(principal, companyId, stockItemId, asOfDate);
    }
}
