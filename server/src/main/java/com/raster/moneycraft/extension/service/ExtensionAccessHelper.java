package com.raster.moneycraft.extension.service;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.security.SystemRole;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Enforces Document 25 Increment X company-scoped permission checks (`extension.author`,
 * `extension.manage`, `private_fields_permitted`, `export_permitted`) and principal projection.
 */
@Component
public class ExtensionAccessHelper {

    public static final String PERM_EXTENSION_AUTHOR = "extension.author";
    public static final String PERM_EXTENSION_MANAGE = "extension.manage";

    private static final Set<CompanyRole> AUTHOR_ROLES = Set.of(
        CompanyRole.COMPANY_ADMIN,
        CompanyRole.SENIOR_ACCOUNTANT,
        CompanyRole.DATA_ENTRY_OPERATOR
    );

    private static final Set<CompanyRole> CUSTOM_FIELD_WRITE_ROLES = Set.of(
        CompanyRole.COMPANY_ADMIN,
        CompanyRole.SENIOR_ACCOUNTANT,
        CompanyRole.DATA_ENTRY_OPERATOR,
        CompanyRole.INVENTORY_CLERK
    );

    private final RbacAuthorizationService rbacGuard;
    private final JdbcTemplate businessJdbcTemplate;

    public ExtensionAccessHelper(
        RbacAuthorizationService rbacGuard,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate
    ) {
        this.rbacGuard = rbacGuard;
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    public void requireCompanyRead(MoneyCraftPrincipal principal, UUID companyId) {
        if (principal == null) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-AUTH-REQUIRED",
                "Authentication Required",
                "Authenticated principal is required."
            );
        }
        rbacGuard.requireCompanyAccess(principal, companyId);
    }

    public void requireExtensionAuthor(MoneyCraftPrincipal principal, UUID companyId) {
        requireCompanyRead(principal, companyId);
        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)) {
            return;
        }
        if (hasExplicitPermission(principal.principalId(), companyId, PERM_EXTENSION_AUTHOR)) {
            return;
        }
        rbacGuard.requireCompanyRole(principal, companyId, AUTHOR_ROLES);
    }

    public void requireExtensionManage(MoneyCraftPrincipal principal, UUID companyId) {
        requireCompanyRead(principal, companyId);
        if (principal.isServiceKeyAuthenticated() || !"PERSON".equalsIgnoreCase(principal.kind())) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-EXT-PERSON-SESSION-REQUIRED",
                "Interactive Person Session Required",
                "Extension activation and suspension require an interactive PERSON session, not a SERVICE key."
            );
        }
        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)) {
            return;
        }
        boolean isCompanyAdmin = principal.companyGrants().stream()
            .anyMatch(g -> g.companyId().equals(companyId) && g.roleCode() == CompanyRole.COMPANY_ADMIN);
        if (isCompanyAdmin || hasExplicitPermission(principal.principalId(), companyId, PERM_EXTENSION_MANAGE)) {
            return;
        }
        throw new MoneyCraftException(
            HttpStatus.FORBIDDEN,
            "MC-EXT-MANAGE-FORBIDDEN",
            "Extension Activation Authority Required",
            "Activating, replacing, or suspending a company extension requires COMPANY_ADMIN or 'extension.manage'."
        );
    }

    public void requireCustomFieldWrite(MoneyCraftPrincipal principal, UUID companyId) {
        requireCompanyRead(principal, companyId);
        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)) {
            return;
        }
        rbacGuard.requireCompanyRole(principal, companyId, CUSTOM_FIELD_WRITE_ROLES);
    }

    public boolean canAccessSelectedPrivateFields(MoneyCraftPrincipal principal, UUID companyId) {
        if (principal == null) {
            return false;
        }
        List<Boolean> flags = businessJdbcTemplate.query(
            """
            SELECT private_fields_permitted
              FROM mc_principal_grant
             WHERE principal_id = ? AND company_id = ? AND active = true
            """,
            (rs, rowNum) -> rs.getBoolean("private_fields_permitted"),
            principal.principalId(),
            companyId
        );
        if (!flags.isEmpty()) {
            return flags.stream().anyMatch(Boolean.TRUE::equals);
        }
        return principal.companyGrants().stream()
            .anyMatch(g -> g.companyId().equals(companyId)
                && (g.roleCode() == CompanyRole.COMPANY_ADMIN || g.roleCode() == CompanyRole.SENIOR_ACCOUNTANT));
    }

    public void requireSelectedPrivateFieldAccess(MoneyCraftPrincipal principal, UUID companyId) {
        requireCompanyRead(principal, companyId);
        if (!canAccessSelectedPrivateFields(principal, companyId)) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-EXT-PRIVATE-FIELD-FORBIDDEN",
                "Selected Private Custom Field Access Denied",
                "Principal lacks authorised company private-field grant for SELECTED_PRIVATE custom fields."
            );
        }
    }

    public boolean hasExplicitPermission(UUID principalId, UUID companyId, String permissionCode) {
        List<String> jsonArrays = businessJdbcTemplate.query(
            """
            SELECT permissions::text AS perms_json
              FROM mc_principal_grant
             WHERE principal_id = ? AND company_id = ? AND active = true
            """,
            (rs, rowNum) -> rs.getString("perms_json"),
            principalId,
            companyId
        );
        Set<String> allPerms = new HashSet<>();
        for (String json : jsonArrays) {
            for (String p : rbacGuard.parseStringArray(json)) {
                allPerms.add(p.trim().toLowerCase(Locale.ROOT));
            }
        }
        return allPerms.contains(permissionCode.toLowerCase(Locale.ROOT));
    }

    public void ensurePrincipalProjected(MoneyCraftPrincipal actor) {
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_principal_projection (principal_id, kind, username, display_name, enabled, version_no)
            VALUES (?, ?, ?, ?, true, 1)
            ON CONFLICT (principal_id) DO NOTHING
            """,
            actor.principalId(),
            actor.kind(),
            actor.username(),
            actor.displayName() == null || actor.displayName().isBlank() ? actor.username() : actor.displayName()
        );
    }

    public boolean isPrincipalProjectedAndEnabled(UUID principalId) {
        List<Boolean> rows = businessJdbcTemplate.query(
            "SELECT enabled FROM mc_principal_projection WHERE principal_id = ?",
            (rs, rowNum) -> rs.getBoolean("enabled"),
            principalId
        );
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
    }
}
