package com.raster.moneycraft.accounting.web;

import com.raster.moneycraft.accounting.dto.CostMasterDtos;
import com.raster.moneycraft.accounting.service.CostCentreService;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Parallel Cost Category and hierarchical Cost Centre REST endpoints.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CostMasterController {

    private final CostCentreService costCentreService;
    private final RbacAuthorizationService rbacGuard;

    public CostMasterController(
        CostCentreService costCentreService,
        RbacAuthorizationService rbacGuard
    ) {
        this.costCentreService = costCentreService;
        this.rbacGuard = rbacGuard;
    }

    @GetMapping("/cost-categories")
    public List<CostMasterDtos.CostCategoryResponse> listCostCategories(
        @PathVariable("companyId") UUID companyId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return costCentreService.listCostCategories(principal, companyId);
    }

    @PostMapping("/cost-categories")
    public ResponseEntity<CostMasterDtos.CostCategoryResponse> createCostCategory(
        @PathVariable("companyId") UUID companyId,
        @RequestBody CostMasterDtos.CreateOrUpdateCostCategoryRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CostMasterDtos.CostCategoryResponse created =
            costCentreService.createCostCategory(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/cost-categories/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @PutMapping("/cost-categories/{categoryId}")
    public ResponseEntity<CostMasterDtos.CostCategoryResponse> updateCostCategory(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("categoryId") UUID categoryId,
        @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
        @RequestBody CostMasterDtos.CreateOrUpdateCostCategoryRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CostMasterDtos.CostCategoryResponse updated =
            costCentreService.updateCostCategory(actor, companyId, categoryId, ifMatchHeader, payload);
        return ResponseEntity.ok()
            .eTag("\"v" + updated.versionNo() + "\"")
            .body(updated);
    }

    @GetMapping("/cost-centres")
    public List<CostMasterDtos.CostCentreResponse> listCostCentres(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "categoryId", required = false) UUID categoryId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return costCentreService.listCostCentres(principal, companyId, categoryId);
    }

    @PostMapping("/cost-centres")
    public ResponseEntity<CostMasterDtos.CostCentreResponse> createCostCentre(
        @PathVariable("companyId") UUID companyId,
        @RequestBody CostMasterDtos.CreateOrUpdateCostCentreRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CostMasterDtos.CostCentreResponse created =
            costCentreService.createCostCentre(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/cost-centres/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/cost-centres/{centreId}")
    public ResponseEntity<CostMasterDtos.CostCentreResponse> getCostCentre(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("centreId") UUID centreId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        CostMasterDtos.CostCentreResponse centre =
            costCentreService.getCostCentre(principal, companyId, centreId);
        return ResponseEntity.ok()
            .eTag("\"v" + centre.versionNo() + "\"")
            .body(centre);
    }

    @PutMapping("/cost-centres/{centreId}")
    public ResponseEntity<CostMasterDtos.CostCentreResponse> updateCostCentre(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("centreId") UUID centreId,
        @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
        @RequestBody CostMasterDtos.CreateOrUpdateCostCentreRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CostMasterDtos.CostCentreResponse updated =
            costCentreService.updateCostCentre(actor, companyId, centreId, ifMatchHeader, payload);
        return ResponseEntity.ok()
            .eTag("\"v" + updated.versionNo() + "\"")
            .body(updated);
    }

    @DeleteMapping("/cost-centres/{centreId}")
    public ResponseEntity<Void> deleteCostCentre(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("centreId") UUID centreId
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        costCentreService.deleteCostCentre(actor, companyId, centreId);
        return ResponseEntity.noContent().build();
    }
}
