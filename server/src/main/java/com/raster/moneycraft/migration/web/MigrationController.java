package com.raster.moneycraft.migration.web;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.migration.dto.MigrationDtos;
import com.raster.moneycraft.migration.service.Tally1800MigrationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for legacy .1800 company snapshot staging, reconciliation proof inspection, and promotion.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/imports")
public class MigrationController {

    private final Tally1800MigrationService migrationService;

    public MigrationController(Tally1800MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @GetMapping
    public ResponseEntity<List<MigrationDtos.ImportJobSummaryDto>> listImportJobs(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return ResponseEntity.ok(migrationService.listImportJobs(principal, companyId));
    }

    @PostMapping({"/legacy-1800/stage", "/tally-1800/stage"})
    public ResponseEntity<MigrationDtos.ImportJobProofDto> stageLegacy1800(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody MigrationDtos.StageTally1800Request request
    ) {
        MigrationDtos.ImportJobProofDto proof = migrationService.stageLegacy1800(principal, companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(proof);
    }

    @GetMapping("/{jobId}/proof")
    public ResponseEntity<MigrationDtos.ImportJobProofDto> getImportJobProof(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(migrationService.getImportJobProof(principal, companyId, jobId));
    }

    @PostMapping("/{jobId}/commit")
    public ResponseEntity<MigrationDtos.ImportJobProofDto> commitImportJob(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(migrationService.commitImportJob(principal, companyId, jobId));
    }
}
