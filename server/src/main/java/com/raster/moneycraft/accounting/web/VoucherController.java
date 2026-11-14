package com.raster.moneycraft.accounting.web;

import com.raster.moneycraft.accounting.dto.CursorPage;
import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.accounting.service.VoucherService;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
 * REST endpoints for Phase 3 ACID Double-Entry Vouchers, Day Book, Lifecycle Transitions,
 * Next-Number Preview, and Bill-Wise Open-Bills Lookup.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class VoucherController {

    private final VoucherService voucherService;
    private final RbacAuthorizationService rbacAuthorizationService;

    public VoucherController(
        VoucherService voucherService,
        RbacAuthorizationService rbacAuthorizationService
    ) {
        this.voucherService = voucherService;
        this.rbacAuthorizationService = rbacAuthorizationService;
    }

    @PostMapping("/vouchers")
    public ResponseEntity<VoucherDtos.VoucherDetailDto> createVoucher(
        @PathVariable UUID companyId,
        @RequestBody VoucherDtos.UpsertVoucherRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        VoucherDtos.VoucherDetailDto created = voucherService.createVoucher(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/vouchers/" + created.id()))
            .eTag("v" + created.versionNo())
            .body(created);
    }

    @GetMapping("/vouchers")
    public CursorPage<VoucherDtos.VoucherSummaryDto> listVouchers(
        @PathVariable UUID companyId,
        @RequestParam(name = "fromDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(name = "toDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(name = "voucherTypeId", required = false) UUID voucherTypeId,
        @RequestParam(name = "voucherCategory", required = false) String voucherCategory,
        @RequestParam(name = "ledgerId", required = false) UUID ledgerId,
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "includeOptional", required = false) Boolean includeOptional,
        @RequestParam(name = "includeCancelled", required = false) Boolean includeCancelled,
        @RequestParam(name = "limit", required = false) Integer limit,
        @RequestParam(name = "cursor", required = false) String cursor
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return voucherService.listVouchers(
            principal,
            companyId,
            fromDate,
            toDate,
            voucherTypeId,
            voucherCategory,
            ledgerId,
            status,
            includeOptional,
            includeCancelled,
            limit,
            cursor
        );
    }

    @GetMapping("/vouchers/next-number")
    public VoucherDtos.NextVoucherNumberResponse previewNextVoucherNumber(
        @PathVariable UUID companyId,
        @RequestParam(name = "voucherTypeId", required = false) UUID voucherTypeId,
        @RequestParam(name = "voucherTypeName", required = false) String voucherTypeName,
        @RequestParam(name = "voucherDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate voucherDate
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return voucherService.previewNextVoucherNumber(
            principal,
            companyId,
            voucherTypeId,
            voucherTypeName,
            voucherDate
        );
    }

    @GetMapping("/vouchers/{voucherId:[0-9a-fA-F-]{36}}")
    public ResponseEntity<VoucherDtos.VoucherDetailDto> getVoucher(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        VoucherDtos.VoucherDetailDto voucher = voucherService.getVoucher(principal, companyId, voucherId);
        return ResponseEntity.ok()
            .eTag("v" + voucher.versionNo())
            .body(voucher);
    }

    @PutMapping("/vouchers/{voucherId:[0-9a-fA-F-]{36}}")
    public ResponseEntity<VoucherDtos.VoucherDetailDto> updateVoucher(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId,
        @RequestHeader(name = "If-Match", required = false) String ifMatchHeader,
        @RequestBody VoucherDtos.UpsertVoucherRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        VoucherDtos.VoucherDetailDto updated = voucherService.updateVoucher(
            actor,
            companyId,
            voucherId,
            ifMatchHeader,
            payload
        );
        return ResponseEntity.ok()
            .eTag("v" + updated.versionNo())
            .body(updated);
    }

    @PostMapping("/vouchers/{voucherId:[0-9a-fA-F-]{36}}/approve")
    public ResponseEntity<VoucherDtos.VoucherDetailDto> approveVoucher(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId,
        @RequestHeader(name = "If-Match", required = false) String ifMatchHeader,
        @RequestBody(required = false) VoucherDtos.ApproveVoucherRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        VoucherDtos.VoucherDetailDto approved = voucherService.approveVoucher(
            actor,
            companyId,
            voucherId,
            ifMatchHeader,
            payload
        );
        return ResponseEntity.ok()
            .eTag("v" + approved.versionNo())
            .body(approved);
    }

    @PostMapping("/vouchers/{voucherId:[0-9a-fA-F-]{36}}/post")
    public ResponseEntity<VoucherDtos.VoucherDetailDto> postVoucher(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId,
        @RequestHeader(name = "If-Match", required = false) String ifMatchHeader
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        VoucherDtos.VoucherDetailDto posted = voucherService.postVoucher(
            actor,
            companyId,
            voucherId,
            ifMatchHeader
        );
        return ResponseEntity.ok()
            .eTag("v" + posted.versionNo())
            .body(posted);
    }

    @PostMapping("/vouchers/{voucherId:[0-9a-fA-F-]{36}}/reverse")
    public ResponseEntity<VoucherDtos.VoucherDetailDto> reverseVoucher(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId,
        @RequestHeader(name = "If-Match", required = false) String ifMatchHeader,
        @RequestBody(required = false) VoucherDtos.ReverseVoucherRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        VoucherDtos.VoucherDetailDto reversal = voucherService.reverseVoucher(
            actor,
            companyId,
            voucherId,
            ifMatchHeader,
            payload
        );
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/vouchers/" + reversal.id()))
            .eTag("v" + reversal.versionNo())
            .body(reversal);
    }

    @PostMapping("/vouchers/{voucherId:[0-9a-fA-F-]{36}}/cancel")
    public ResponseEntity<VoucherDtos.VoucherDetailDto> cancelVoucher(
        @PathVariable UUID companyId,
        @PathVariable UUID voucherId,
        @RequestHeader(name = "If-Match", required = false) String ifMatchHeader,
        @RequestBody(required = false) VoucherDtos.CancelVoucherRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacAuthorizationService.requireAuthenticatedPrincipal();
        VoucherDtos.VoucherDetailDto cancelled = voucherService.cancelVoucher(
            actor,
            companyId,
            voucherId,
            ifMatchHeader,
            payload
        );
        return ResponseEntity.ok()
            .eTag("v" + cancelled.versionNo())
            .body(cancelled);
    }

    @GetMapping("/ledgers/{ledgerId}/open-bills")
    public List<VoucherDtos.OpenBillDto> listOpenBills(
        @PathVariable UUID companyId,
        @PathVariable UUID ledgerId,
        @RequestParam(name = "excludeVoucherId", required = false) UUID excludeVoucherId
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return voucherService.listOpenBills(principal, companyId, ledgerId, excludeVoucherId);
    }
}
