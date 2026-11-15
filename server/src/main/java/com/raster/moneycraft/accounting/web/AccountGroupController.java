package com.raster.moneycraft.accounting.web;

import com.raster.moneycraft.accounting.dto.AccountGroupDtos;
import com.raster.moneycraft.accounting.dto.CursorPage;
import com.raster.moneycraft.accounting.service.AccountGroupService;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.net.URI;
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
 * Chart of Accounts hierarchical tree and Account Group master REST endpoints.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AccountGroupController {

    private final AccountGroupService accountGroupService;
    private final RbacAuthorizationService rbacGuard;

    public AccountGroupController(
        AccountGroupService accountGroupService,
        RbacAuthorizationService rbacGuard
    ) {
        this.accountGroupService = accountGroupService;
        this.rbacGuard = rbacGuard;
    }

    @GetMapping("/chart-of-accounts/tree")
    public AccountGroupDtos.ChartOfAccountsTreeResponse getChartOfAccountsTree(
        @PathVariable("companyId") UUID companyId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return accountGroupService.getChartOfAccountsTree(principal, companyId);
    }

    @GetMapping({"/account-groups", "/groups"})
    public CursorPage<AccountGroupDtos.AccountGroupResponse> listAccountGroups(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "q", required = false) String q
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return accountGroupService.listAccountGroups(principal, companyId, cursor, limit, q);
    }

    @PostMapping({"/account-groups", "/groups"})
    public ResponseEntity<AccountGroupDtos.AccountGroupResponse> createAccountGroup(
        @PathVariable("companyId") UUID companyId,
        @RequestBody AccountGroupDtos.CreateOrUpdateAccountGroupRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        AccountGroupDtos.AccountGroupResponse created =
            accountGroupService.createAccountGroup(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/account-groups/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping({"/account-groups/{groupId}", "/groups/{groupId}"})
    public ResponseEntity<AccountGroupDtos.AccountGroupResponse> getAccountGroup(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("groupId") UUID groupId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        AccountGroupDtos.AccountGroupResponse group =
            accountGroupService.getAccountGroup(principal, companyId, groupId);
        return ResponseEntity.ok()
            .eTag("\"v" + group.versionNo() + "\"")
            .body(group);
    }

    @PutMapping({"/account-groups/{groupId}", "/groups/{groupId}"})
    public ResponseEntity<AccountGroupDtos.AccountGroupResponse> updateAccountGroup(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("groupId") UUID groupId,
        @RequestHeader(value = "If-Match", required = false) String ifMatchHeader,
        @RequestBody AccountGroupDtos.CreateOrUpdateAccountGroupRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        AccountGroupDtos.AccountGroupResponse updated =
            accountGroupService.updateAccountGroup(actor, companyId, groupId, ifMatchHeader, payload);
        return ResponseEntity.ok()
            .eTag("\"v" + updated.versionNo() + "\"")
            .body(updated);
    }

    @DeleteMapping({"/account-groups/{groupId}", "/groups/{groupId}"})
    public ResponseEntity<Void> deleteAccountGroup(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("groupId") UUID groupId
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        accountGroupService.deleteAccountGroup(actor, companyId, groupId);
        return ResponseEntity.noContent().build();
    }
}
