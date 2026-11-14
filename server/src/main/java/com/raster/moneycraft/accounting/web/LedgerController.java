package com.raster.moneycraft.accounting.web;

import com.raster.moneycraft.accounting.dto.CursorPage;
import com.raster.moneycraft.accounting.dto.LedgerDtos;
import com.raster.moneycraft.accounting.service.LedgerService;
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
 * Accounting Ledger master and sub-15ms Typeahead REST endpoints.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/ledgers")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LedgerController {

    private final LedgerService ledgerService;
    private final RbacAuthorizationService rbacGuard;

    public LedgerController(
        LedgerService ledgerService,
        RbacAuthorizationService rbacGuard
    ) {
        this.ledgerService = ledgerService;
        this.rbacGuard = rbacGuard;
    }

    @GetMapping
    public CursorPage<LedgerDtos.LedgerResponse> listLedgers(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "q", required = false) String q,
        @RequestParam(value = "groupId", required = false) UUID groupId,
        @RequestParam(value = "taxType", required = false) String taxType
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return ledgerService.listLedgers(principal, companyId, cursor, limit, q, groupId, taxType);
    }

    @GetMapping("/typeahead")
    public List<LedgerDtos.LedgerTypeaheadResponse> typeaheadLedgers(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "q", required = false) String q,
        @RequestParam(value = "filter", required = false) String filter,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return ledgerService.typeaheadLedgers(principal, companyId, q, filter, limit);
    }

    @PostMapping
    public ResponseEntity<LedgerDtos.LedgerResponse> createLedger(
        @PathVariable("companyId") UUID companyId,
        @RequestBody LedgerDtos.CreateOrUpdateLedgerRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        LedgerDtos.LedgerResponse created = ledgerService.createLedger(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/ledgers/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/{ledgerId}")
    public ResponseEntity<LedgerDtos.LedgerResponse> getLedger(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("ledgerId") UUID ledgerId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        LedgerDtos.LedgerResponse ledger = ledgerService.getLedger(principal, companyId, ledgerId);
        return ResponseEntity.ok()
            .eTag("\"v" + ledger.versionNo() + "\"")
            .body(ledger);
    }

    @PutMapping("/{ledgerId}")
    public ResponseEntity<LedgerDtos.LedgerResponse> updateLedger(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("ledgerId") UUID ledgerId,
        @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
        @RequestBody LedgerDtos.CreateOrUpdateLedgerRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        LedgerDtos.LedgerResponse updated =
            ledgerService.updateLedger(actor, companyId, ledgerId, ifMatchHeader, payload);
        return ResponseEntity.ok()
            .eTag("\"v" + updated.versionNo() + "\"")
            .body(updated);
    }

    @DeleteMapping("/{ledgerId}")
    public ResponseEntity<Void> deleteLedger(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("ledgerId") UUID ledgerId
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        ledgerService.deleteLedger(actor, companyId, ledgerId);
        return ResponseEntity.noContent().build();
    }
}
