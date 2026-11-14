package com.raster.moneycraft.statutory.web;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.statutory.dto.StatutoryDtos;
import com.raster.moneycraft.statutory.service.TdsAndEditLogService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for Immutable MCA Rule 3(1) Proviso Edit Log (`Alt+Q`) version timelines,
 * redline field diffs, canonical snapshots, and SHA-256 hashes.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/edit-log")
public class EditLogController {

    private final TdsAndEditLogService tdsAndEditLogService;
    private final RbacAuthorizationService rbacAuthorizationService;

    public EditLogController(
        TdsAndEditLogService tdsAndEditLogService,
        RbacAuthorizationService rbacAuthorizationService
    ) {
        this.tdsAndEditLogService = tdsAndEditLogService;
        this.rbacAuthorizationService = rbacAuthorizationService;
    }

    @GetMapping("/{entityType}/{entityId:[0-9a-fA-F-]{36}}/versions")
    public List<StatutoryDtos.EditLogVersionDto> listEditLogVersions(
        @PathVariable UUID companyId,
        @PathVariable String entityType,
        @PathVariable UUID entityId
    ) {
        MoneyCraftPrincipal principal = rbacAuthorizationService.requireAuthenticatedPrincipal();
        return tdsAndEditLogService.listEditLogVersions(principal, companyId, entityType, entityId);
    }
}
