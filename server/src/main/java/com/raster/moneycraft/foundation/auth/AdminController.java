package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.AuditVerifyResult;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MoneyCraft Protected Authority administration, RBAC grants, service keys, Super Admin ceremonies,
 * MC-AUDIT-2 verification, and fail-closed QUARANTINE recovery endpoints (/api/v1/admin).
 */
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminController {

    private final MoneyCraftProperties moneyCraftProperties;
    private final AuthService moneyCraftAuthService;
    private final SuperAdminService superAdminService;
    private final OidcPkceService enterpriseOidcService;
    private final AuditChainService mcAuditChainService;
    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;

    public AdminController(
        MoneyCraftProperties moneyCraftProperties,
        AuthService moneyCraftAuthService,
        SuperAdminService superAdminService,
        OidcPkceService enterpriseOidcService,
        AuditChainService mcAuditChainService,
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard
    ) {
        this.moneyCraftProperties = moneyCraftProperties;
        this.moneyCraftAuthService = moneyCraftAuthService;
        this.superAdminService = superAdminService;
        this.enterpriseOidcService = enterpriseOidcService;
        this.mcAuditChainService = mcAuditChainService;
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
    }

    @PostMapping("/principals")
    public ResponseEntity<AuthDtos.AdminPrincipalRecordDto> createOperatorPrincipal(
        @RequestBody AuthDtos.CreatePrincipalRequest payload
    ) {
        MoneyCraftPrincipal owner = rbacGuard.requireInstallationOwner();
        AuthDtos.AdminPrincipalRecordDto created = moneyCraftAuthService.createPrincipal(owner, payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/principals")
    public List<AuthDtos.AdminPrincipalRecordDto> listOperatorPrincipals() {
        rbacGuard.requireAdminOrAuditorRead();
        return moneyCraftAuthService.listPrincipals();
    }

    @PutMapping("/principals/{principalRef}/grants")
    public AuthDtos.CompanyGrantSummaryDto upsertPrincipalCompanyGrant(
        @PathVariable("principalRef") String principalRef,
        @RequestBody AuthDtos.AssignCompanyGrantRequest payload
    ) {
        MoneyCraftPrincipal owner = rbacGuard.requireInstallationOwner();
        return moneyCraftAuthService.assignCompanyGrant(owner, principalRef, payload);
    }

    @PutMapping("/super-admin-designations/{principalId}")
    public AuthDtos.SuperAdminDesignationDto designateInstallationSuperAdmin(
        @PathVariable("principalId") UUID principalId,
        @RequestBody(required = false) AuthDtos.SuperAdminDesignationRequest payload
    ) {
        MoneyCraftPrincipal owner = rbacGuard.requireInstallationOwner();
        String reason = payload == null ? null : payload.reason();
        return superAdminService.designateSuperAdmin(owner, principalId, reason);
    }

    @DeleteMapping("/super-admin-designations/{principalId}")
    public AuthDtos.SuperAdminDesignationDto revokeInstallationSuperAdmin(
        @PathVariable("principalId") UUID principalId,
        @RequestBody(required = false) AuthDtos.SuperAdminDesignationRequest payload
    ) {
        MoneyCraftPrincipal owner = rbacGuard.requireInstallationOwner();
        String reason = payload == null ? null : payload.reason();
        return superAdminService.revokeSuperAdmin(owner, principalId, reason);
    }

    @PostMapping("/service-keys")
    public ResponseEntity<AuthDtos.CreatedServiceKeyResponse> issueScopedServiceApiKey(
        @RequestBody AuthDtos.CreateServiceKeyRequest payload
    ) {
        MoneyCraftPrincipal owner = rbacGuard.requireInstallationOwner();
        AuthDtos.CreatedServiceKeyResponse issued = moneyCraftAuthService.issueServiceKey(owner, payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    @PostMapping("/oidc-issuers")
    public ResponseEntity<AuthDtos.OidcIssuerDto> registerEnterpriseOidcIssuer(
        @RequestBody AuthDtos.CreateOidcIssuerRequest payload
    ) {
        MoneyCraftPrincipal owner = rbacGuard.requireInstallationOwner();
        AuthDtos.OidcIssuerDto registered = enterpriseOidcService.registerIssuer(owner, payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(registered);
    }

    @GetMapping("/oidc-issuers")
    public List<AuthDtos.OidcIssuerDto> listEnterpriseOidcIssuers() {
        rbacGuard.requireAdminOrAuditorRead();
        return enterpriseOidcService.listIssuers();
    }

    @GetMapping("/audit/verify")
    public AuthDtos.AuditChainVerifyResponseDto verifyMcAudit2Chain() {
        rbacGuard.requireAdminOrAuditorRead();
        AuditVerifyResult verified = mcAuditChainService.verifyAllStreams();
        return new AuthDtos.AuditChainVerifyResponseDto(
            verified.verified(),
            verified.checkedEventCount(),
            verified.tailHashSha256(),
            verified.verifiedAt().toString(),
            verified.failureDetail()
        );
    }

    @PostMapping("/quarantine/enter")
    public AuthDtos.SystemStatusResponse enterFailClosedQuarantine(
        @RequestBody(required = false) AuthDtos.EnterQuarantineRequest payload
    ) {
        MoneyCraftPrincipal owner = rbacGuard.requireInstallationOwner();
        String reason = payload == null ? null : payload.reason();
        AuthorityGuard.GuardSnapshot snap = authorityGuard.enterQuarantine(owner.principalId(), reason);
        return new AuthDtos.SystemStatusResponse(
            snap.securityState(),
            snap.fenceEpoch(),
            moneyCraftProperties.getVersion(),
            snap.quarantineReason()
        );
    }

    @PostMapping("/quarantine/recover")
    public AuthDtos.SystemStatusResponse recoverFromQuarantineCeremony(
        @RequestBody AuthDtos.RecoverQuarantineRequest payload
    ) {
        MoneyCraftPrincipal owner = rbacGuard.requireInstallationOwner();
        String receipt = payload == null ? "" : payload.resolvedReceipt();
        AuthorityGuard.GuardSnapshot snap = authorityGuard.recoverFromQuarantine(owner.principalId(), receipt);
        return new AuthDtos.SystemStatusResponse(
            snap.securityState(),
            snap.fenceEpoch(),
            moneyCraftProperties.getVersion(),
            snap.quarantineReason()
        );
    }
}
