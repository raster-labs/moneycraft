package com.raster.moneycraft.extension.web;

import com.raster.moneycraft.extension.dto.ExtensionDtos;
import com.raster.moneycraft.extension.service.CustomFieldService;
import com.raster.moneycraft.extension.service.ExtensionWorkbenchService;
import com.raster.moneycraft.extension.service.OdbcProjectionQueryService;
import com.raster.moneycraft.extension.service.TdlStaticImporterService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST endpoints for the Increment X Visual Extension Workbench (EXT-001..008)
 * and Static TDL Importer / Functional Parity Matrix (TDL-001..004).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/extensions")
public class ExtensionController {

    private final ExtensionWorkbenchService workbenchService;
    private final CustomFieldService customFieldService;
    private final TdlStaticImporterService tdlImporterService;
    private final OdbcProjectionQueryService odbcQueryService;

    public ExtensionController(
        ExtensionWorkbenchService workbenchService,
        CustomFieldService customFieldService,
        TdlStaticImporterService tdlImporterService,
        OdbcProjectionQueryService odbcQueryService
    ) {
        this.workbenchService = workbenchService;
        this.customFieldService = customFieldService;
        this.tdlImporterService = tdlImporterService;
        this.odbcQueryService = odbcQueryService;
    }

    @GetMapping("/packages")
    public ResponseEntity<List<ExtensionDtos.ExtensionPackageDto>> listPackages(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return ResponseEntity.ok(workbenchService.listPackages(principal, companyId));
    }

    @GetMapping("/packages/{packageId}")
    public ResponseEntity<ExtensionDtos.ExtensionPackageDto> getPackage(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID packageId
    ) {
        ExtensionDtos.ExtensionPackageDto dto = workbenchService.getPackage(principal, companyId, packageId);
        return ResponseEntity.ok()
            .eTag("\"v" + dto.versionNo() + "\"")
            .body(dto);
    }

    @PostMapping("/packages")
    public ResponseEntity<ExtensionDtos.ExtensionPackageDto> createDraftPackage(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody ExtensionDtos.CreateExtensionPackageRequest request
    ) {
        ExtensionDtos.ExtensionPackageDto created = workbenchService.createDraftPackage(principal, companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @PostMapping("/packages/{packageId}/validate")
    public ResponseEntity<ExtensionDtos.ExtensionPackageDto> validatePackage(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID packageId
    ) {
        ExtensionDtos.ExtensionPackageDto validated = workbenchService.validatePackage(principal, companyId, packageId);
        return ResponseEntity.ok()
            .eTag("\"v" + validated.versionNo() + "\"")
            .body(validated);
    }

    @PostMapping("/packages/{packageId}/activate")
    public ResponseEntity<ExtensionDtos.ExtensionBindingDto> activatePackage(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID packageId,
        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        ExtensionDtos.ExtensionBindingDto binding =
            workbenchService.activatePackage(principal, companyId, packageId, ifMatch);
        return ResponseEntity.ok()
            .eTag("\"v" + binding.versionNo() + "\"")
            .body(binding);
    }

    @PostMapping("/packages/{packageId}/suspend")
    public ResponseEntity<ExtensionDtos.ExtensionBindingDto> suspendPackage(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID packageId,
        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        ExtensionDtos.ExtensionBindingDto binding =
            workbenchService.suspendPackage(principal, companyId, packageId, ifMatch);
        return ResponseEntity.ok()
            .eTag("\"v" + binding.versionNo() + "\"")
            .body(binding);
    }

    @GetMapping("/bindings")
    public ResponseEntity<List<ExtensionDtos.ExtensionBindingDto>> listBindings(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return ResponseEntity.ok(workbenchService.listBindings(principal, companyId));
    }

    @GetMapping("/custom-fields")
    public ResponseEntity<List<ExtensionDtos.CustomFieldDefDto>> listCustomFields(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam(name = "ownerEntityType", required = false) String ownerEntityType
    ) {
        return ResponseEntity.ok(customFieldService.listFieldDefinitions(principal, companyId, ownerEntityType));
    }

    @PostMapping("/custom-fields")
    public ResponseEntity<ExtensionDtos.CustomFieldDefDto> createCustomField(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody ExtensionDtos.UpsertCustomFieldDefRequest request
    ) {
        ExtensionDtos.CustomFieldDefDto def =
            customFieldService.upsertFieldDefinition(principal, companyId, null, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"v" + def.versionNo() + "\"")
            .body(def);
    }

    @GetMapping("/custom-fields/values/{entityType}/{entityId}")
    public ResponseEntity<List<ExtensionDtos.CustomFieldValueDto>> readCustomFieldValues(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable String entityType,
        @PathVariable UUID entityId,
        @RequestParam(name = "strictPrivateAccess", defaultValue = "false") boolean strictPrivateAccess
    ) {
        return ResponseEntity.ok(
            customFieldService.readEntityValues(principal, companyId, entityType, entityId, strictPrivateAccess)
        );
    }

    @PutMapping("/custom-fields/values/{entityType}/{entityId}")
    public ResponseEntity<List<ExtensionDtos.CustomFieldValueDto>> putCustomFieldValues(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable String entityType,
        @PathVariable UUID entityId,
        @RequestBody ExtensionDtos.UpsertCustomFieldValuesRequest request
    ) {
        return ResponseEntity.ok(
            customFieldService.writeEntityValues(principal, companyId, entityType, entityId, request)
        );
    }

    @PostMapping("/custom-fields/values/{entityType}/{entityId}")
    public ResponseEntity<List<ExtensionDtos.CustomFieldValueDto>> postCustomFieldValues(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable String entityType,
        @PathVariable UUID entityId,
        @RequestBody ExtensionDtos.UpsertCustomFieldValuesRequest request
    ) {
        return ResponseEntity.ok(
            customFieldService.writeEntityValues(principal, companyId, entityType, entityId, request)
        );
    }

    @PostMapping("/evaluate-preview")
    public ResponseEntity<ExtensionDtos.EvaluatePreviewResponse> evaluatePreview(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody ExtensionDtos.EvaluatePreviewRequest request
    ) {
        return ResponseEntity.ok(workbenchService.evaluatePreview(principal, companyId, request));
    }

    @GetMapping("/tdl-imports")
    public ResponseEntity<List<ExtensionDtos.TdlImportProjectDto>> listTdlImports(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId
    ) {
        return ResponseEntity.ok(tdlImporterService.listProjects(principal, companyId));
    }

    @GetMapping("/tdl-imports/{projectId}")
    public ResponseEntity<ExtensionDtos.TdlImportProjectDto> getTdlImport(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @PathVariable UUID projectId
    ) {
        return ResponseEntity.ok(tdlImporterService.getProject(principal, companyId, projectId));
    }

    @PostMapping(
        path = "/tdl-imports",
        consumes = {MediaType.APPLICATION_JSON_VALUE, "application/*+json"}
    )
    public ResponseEntity<ExtensionDtos.TdlImportProjectDto> importTdlJson(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody ExtensionDtos.ImportTdlProjectRequest request
    ) {
        ExtensionDtos.TdlImportProjectDto imported = tdlImporterService.importTdlProject(principal, companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(imported);
    }

    @PostMapping(path = "/tdl-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExtensionDtos.TdlImportProjectDto> importTdlMultipart(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(name = "projectName", required = false) String projectName,
        @RequestParam(name = "sourceVersionClaim", required = false) String sourceVersionClaim
    ) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-TDL-UPLOAD-READ-FAILED",
                "Failed to Read Uploaded TDL File",
                "Could not read uploaded TDL file bytes."
            );
        }
        String fileName = file.getOriginalFilename() == null ? "project.tdl" : file.getOriginalFilename();
        ExtensionDtos.ImportTdlProjectRequest req = new ExtensionDtos.ImportTdlProjectRequest(
            projectName,
            fileName,
            sourceVersionClaim,
            null,
            Base64.getEncoder().encodeToString(bytes),
            Map.of()
        );
        ExtensionDtos.TdlImportProjectDto imported = tdlImporterService.importTdlProject(principal, companyId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(imported);
    }

    @PostMapping("/odbc-query")
    public ResponseEntity<ExtensionDtos.OdbcProjectionQueryResponse> executeOdbcQuery(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestBody ExtensionDtos.OdbcProjectionQueryRequest request
    ) {
        return ResponseEntity.ok(odbcQueryService.executeQuery(principal, companyId, request));
    }
}
