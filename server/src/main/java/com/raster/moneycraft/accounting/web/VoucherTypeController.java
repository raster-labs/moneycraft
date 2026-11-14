package com.raster.moneycraft.accounting.web;

import com.raster.moneycraft.accounting.dto.VoucherTypeDtos;
import com.raster.moneycraft.accounting.service.VoucherTypeService;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
 * TallyPrime 7.2 24 predefined + custom Voucher Type REST endpoints.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/voucher-types")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class VoucherTypeController {

    private final VoucherTypeService voucherTypeService;
    private final RbacAuthorizationService rbacGuard;

    public VoucherTypeController(
        VoucherTypeService voucherTypeService,
        RbacAuthorizationService rbacGuard
    ) {
        this.voucherTypeService = voucherTypeService;
        this.rbacGuard = rbacGuard;
    }

    @GetMapping
    public List<VoucherTypeDtos.VoucherTypeResponse> listVoucherTypes(
        @PathVariable("companyId") UUID companyId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return voucherTypeService.listVoucherTypes(principal, companyId);
    }

    @PostMapping
    public ResponseEntity<VoucherTypeDtos.VoucherTypeResponse> createVoucherType(
        @PathVariable("companyId") UUID companyId,
        @RequestBody VoucherTypeDtos.CreateOrUpdateVoucherTypeRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        VoucherTypeDtos.VoucherTypeResponse created =
            voucherTypeService.createVoucherType(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/voucher-types/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/{voucherTypeId}")
    public ResponseEntity<VoucherTypeDtos.VoucherTypeResponse> getVoucherType(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("voucherTypeId") UUID voucherTypeId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        VoucherTypeDtos.VoucherTypeResponse voucherType =
            voucherTypeService.getVoucherType(principal, companyId, voucherTypeId);
        return ResponseEntity.ok()
            .eTag("\"v" + voucherType.versionNo() + "\"")
            .body(voucherType);
    }

    @PutMapping("/{voucherTypeId}")
    public ResponseEntity<VoucherTypeDtos.VoucherTypeResponse> updateVoucherType(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("voucherTypeId") UUID voucherTypeId,
        @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
        @RequestBody VoucherTypeDtos.CreateOrUpdateVoucherTypeRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        VoucherTypeDtos.VoucherTypeResponse updated =
            voucherTypeService.updateVoucherType(actor, companyId, voucherTypeId, ifMatchHeader, payload);
        return ResponseEntity.ok()
            .eTag("\"v" + updated.versionNo() + "\"")
            .body(updated);
    }
}
