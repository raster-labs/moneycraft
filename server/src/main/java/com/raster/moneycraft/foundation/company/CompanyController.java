package com.raster.moneycraft.foundation.company;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-company ERP master, F11 features, fiscal years, and accounting period lock endpoints.
 */
@RestController
@RequestMapping("/api/v1/companies")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CompanyController {

    private final CompanyService companyService;
    private final FiscalPeriodService fiscalPeriodService;
    private final RbacAuthorizationService rbacGuard;

    public CompanyController(
        CompanyService companyService,
        FiscalPeriodService fiscalPeriodService,
        RbacAuthorizationService rbacGuard
    ) {
        this.companyService = companyService;
        this.fiscalPeriodService = fiscalPeriodService;
        this.rbacGuard = rbacGuard;
    }

    @GetMapping
    public List<CompanyDtos.CompanySummaryDto> listCompanies() {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return companyService.listAccessibleCompanies(principal);
    }

    @PostMapping
    public ResponseEntity<CompanyDtos.CompanySummaryDto> createCompany(
        @RequestBody CompanyDtos.CreateOrUpdateCompanyRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CompanyDtos.CompanySummaryDto created = companyService.createCompany(actor, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/{companyId}")
    public ResponseEntity<CompanyDtos.CompanySummaryDto> fetchCompanyById(
        @PathVariable("companyId") UUID companyId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        CompanyDtos.CompanySummaryDto company = companyService.getCompany(principal, companyId);
        return ResponseEntity.ok()
            .eTag("\"v" + company.versionNo() + "\"")
            .body(company);
    }

    @PutMapping("/{companyId}")
    public ResponseEntity<CompanyDtos.CompanySummaryDto> alterCompany(
        @PathVariable("companyId") UUID companyId,
        @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
        @RequestBody CompanyDtos.CreateOrUpdateCompanyRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CompanyDtos.CompanySummaryDto updated = companyService.updateCompany(
            actor,
            companyId,
            ifMatchHeader,
            payload
        );
        return ResponseEntity.ok()
            .eTag("\"v" + updated.versionNo() + "\"")
            .body(updated);
    }

    @GetMapping("/{companyId}/fiscal-years")
    public List<CompanyDtos.FiscalYearDto> listCompanyFiscalYears(
        @PathVariable("companyId") UUID companyId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return fiscalPeriodService.listFiscalYears(principal, companyId);
    }

    @PostMapping("/{companyId}/fiscal-years")
    public ResponseEntity<CompanyDtos.FiscalYearDto> createCompanyFiscalYear(
        @PathVariable("companyId") UUID companyId,
        @RequestBody CompanyDtos.CreateFiscalYearRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CompanyDtos.FiscalYearDto created = fiscalPeriodService.createFiscalYear(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/fiscal-years/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/{companyId}/periods")
    public List<CompanyDtos.AccountingPeriodDto> listCompanyAccountingPeriods(
        @PathVariable("companyId") UUID companyId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return fiscalPeriodService.listPeriods(principal, companyId);
    }

    @PatchMapping("/{companyId}/periods/{periodId}")
    public ResponseEntity<CompanyDtos.AccountingPeriodDto> changeAccountingPeriodState(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("periodId") UUID periodId,
        @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
        @RequestBody CompanyDtos.PatchPeriodStateRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CompanyDtos.AccountingPeriodDto updated = fiscalPeriodService.transitionPeriodState(
            actor,
            companyId,
            periodId,
            ifMatchHeader,
            payload
        );
        return ResponseEntity.ok()
            .eTag("\"v" + updated.versionNo() + "\"")
            .body(updated);
    }
}
