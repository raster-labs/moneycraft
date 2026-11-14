package com.raster.moneycraft.accounting.web;

import com.raster.moneycraft.accounting.dto.CurrencyForexDtos;
import com.raster.moneycraft.accounting.service.CurrencyForexService;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-currency master and date-effective forex rate REST endpoints.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/currencies")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CurrencyForexController {

    private final CurrencyForexService currencyForexService;
    private final RbacAuthorizationService rbacGuard;

    public CurrencyForexController(
        CurrencyForexService currencyForexService,
        RbacAuthorizationService rbacGuard
    ) {
        this.currencyForexService = currencyForexService;
        this.rbacGuard = rbacGuard;
    }

    @GetMapping
    public List<CurrencyForexDtos.CurrencyResponse> listCurrencies(
        @PathVariable("companyId") UUID companyId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return currencyForexService.listCurrencies(principal, companyId);
    }

    @PostMapping
    public ResponseEntity<CurrencyForexDtos.CurrencyResponse> createCurrency(
        @PathVariable("companyId") UUID companyId,
        @RequestBody CurrencyForexDtos.CreateOrUpdateCurrencyRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CurrencyForexDtos.CurrencyResponse created =
            currencyForexService.createCurrency(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/currencies/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/{currencyId}")
    public ResponseEntity<CurrencyForexDtos.CurrencyResponse> getCurrency(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("currencyId") UUID currencyId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        CurrencyForexDtos.CurrencyResponse currency =
            currencyForexService.getCurrency(principal, companyId, currencyId);
        return ResponseEntity.ok()
            .eTag("\"v" + currency.versionNo() + "\"")
            .body(currency);
    }

    @PutMapping("/{currencyId}")
    public ResponseEntity<CurrencyForexDtos.CurrencyResponse> updateCurrency(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("currencyId") UUID currencyId,
        @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
        @RequestBody CurrencyForexDtos.CreateOrUpdateCurrencyRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CurrencyForexDtos.CurrencyResponse updated =
            currencyForexService.updateCurrency(actor, companyId, currencyId, ifMatchHeader, payload);
        return ResponseEntity.ok()
            .eTag("\"v" + updated.versionNo() + "\"")
            .body(updated);
    }

    @GetMapping("/{currencyId}/rates")
    public List<CurrencyForexDtos.ForexRateResponse> listForexRates(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("currencyId") UUID currencyId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return currencyForexService.listForexRates(principal, companyId, currencyId);
    }

    @PostMapping("/{currencyId}/rates")
    public ResponseEntity<CurrencyForexDtos.ForexRateResponse> upsertForexRate(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("currencyId") UUID currencyId,
        @RequestBody CurrencyForexDtos.UpsertForexRateRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        CurrencyForexDtos.ForexRateResponse saved =
            currencyForexService.upsertForexRate(actor, companyId, currencyId, payload);
        HttpStatus status = saved.versionNo() == 1L ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
            .eTag("\"v" + saved.versionNo() + "\"")
            .body(saved);
    }
}
