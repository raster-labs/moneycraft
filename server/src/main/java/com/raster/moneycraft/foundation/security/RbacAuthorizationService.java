package com.raster.moneycraft.foundation.security;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class RbacAuthorizationService {

    private final JdbcTemplate authorityJdbcTemplate;
    private final ObjectMapper objectMapper;

    public RbacAuthorizationService(
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public MoneyCraftPrincipal requireAuthenticatedPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof MoneyCraftPrincipal principal)) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-AUTH-REQUIRED",
                "Authentication Required",
                "Sign in or supply a valid scoped service API key to access this resource."
            );
        }
        return refreshPrincipal(principal);
    }

    public MoneyCraftPrincipal refreshPrincipal(MoneyCraftPrincipal base) {
        List<MoneyCraftPrincipal> rows = authorityJdbcTemplate.query(
            """
            SELECT principal_id, kind, username::text AS username, display_name, status, system_roles::text AS roles_json
              FROM ma_principal
             WHERE principal_id = ?
            """,
            (rs, rowNum) -> {
                String status = rs.getString("status");
                if (!"ENABLED".equals(status)) {
                    throw new MoneyCraftException(
                        HttpStatus.FORBIDDEN,
                        "MC-PRINCIPAL-DISABLED",
                        "Principal Disabled or Locked",
                        "Principal status is " + status + "."
                    );
                }
                UUID pid = rs.getObject("principal_id", UUID.class);
                List<SystemRole> sysRoles = parseSystemRoles(rs.getString("roles_json"));
                List<CompanyGrantInfo> grants = loadActiveGrants(pid);
                boolean hasPasskey = hasActivePasskey(pid);
                return new MoneyCraftPrincipal(
                    pid,
                    rs.getString("kind"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    sysRoles,
                    grants,
                    hasPasskey,
                    base.serviceKeyId(),
                    base.serviceKeyCompanyAllowlist(),
                    base.serviceKeyEndpointAllowlist()
                );
            },
            base.principalId()
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-PRINCIPAL-NOT-FOUND",
                "Principal Not Found",
                "Authenticated principal no longer exists in Protected Authority DB."
            );
        }
        return rows.getFirst();
    }

    public MoneyCraftPrincipal loadPrincipalProfile(UUID principalId) {
        MoneyCraftPrincipal seed = new MoneyCraftPrincipal(
            principalId,
            "PERSON",
            "",
            "",
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            List.of()
        );
        return refreshPrincipal(seed);
    }

    public List<CompanyGrantInfo> loadActiveGrants(UUID principalId) {
        return authorityJdbcTemplate.query(
            """
            SELECT grant_id, company_id, role_code, voucher_type_allowlist::text AS allowlist_json,
                   backdated_days_limit, export_permitted
              FROM ma_grant
             WHERE principal_id = ? AND state = 'ACTIVE'
             ORDER BY created_at ASC
            """,
            (rs, rowNum) -> {
                Integer backdated = rs.getObject("backdated_days_limit", Integer.class);
                return new CompanyGrantInfo(
                    rs.getObject("grant_id", UUID.class),
                    rs.getObject("company_id", UUID.class),
                    CompanyRole.valueOf(rs.getString("role_code")),
                    parseStringArray(rs.getString("allowlist_json")),
                    backdated,
                    rs.getBoolean("export_permitted")
                );
            },
            principalId
        );
    }

    public boolean hasActivePasskey(UUID principalId) {
        Integer count = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ma_passkey WHERE principal_id = ? AND state = 'ACTIVE'",
            Integer.class,
            principalId
        );
        return count != null && count > 0;
    }

    public MoneyCraftPrincipal requireInstallationOwner() {
        MoneyCraftPrincipal principal = requireAuthenticatedPrincipal();
        if (!principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-RBAC-OWNER-REQUIRED",
                "Installation Owner Role Required",
                "Only INSTALLATION_OWNER principals may execute this administrative operation."
            );
        }
        return principal;
    }

    public MoneyCraftPrincipal requireAdminOrAuditorRead() {
        MoneyCraftPrincipal principal = requireAuthenticatedPrincipal();
        if (!principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)
            && !principal.hasSystemRole(SystemRole.SECURITY_AUDITOR)
            && !principal.hasSystemRole(SystemRole.SYSTEM_OPERATOR)) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-RBAC-ADMIN-REQUIRED",
                "Administrative Role Required",
                "Requires INSTALLATION_OWNER, SECURITY_AUDITOR, or SYSTEM_OPERATOR role."
            );
        }
        return principal;
    }

    public boolean canAccessCompany(MoneyCraftPrincipal principal, UUID companyId) {
        if (principal.isServiceKeyAuthenticated()) {
            List<UUID> allowedCompanies = principal.serviceKeyCompanyAllowlist();
            if (!allowedCompanies.isEmpty() && !allowedCompanies.contains(companyId)) {
                return false;
            }
        }
        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)
            || principal.hasSystemRole(SystemRole.SECURITY_AUDITOR)) {
            return true;
        }
        return principal.companyGrants().stream()
            .anyMatch(g -> g.companyId().equals(companyId));
    }

    public void requireCompanyAccess(MoneyCraftPrincipal principal, UUID companyId) {
        if (!canAccessCompany(principal, companyId)) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-RBAC-COMPANY-FORBIDDEN",
                "Company Access Denied",
                "Principal does not hold an active MC-OBJECT-1 grant for company " + companyId + "."
            );
        }
    }

    public void requireCompanyRole(
        MoneyCraftPrincipal principal,
        UUID companyId,
        Set<CompanyRole> allowedRoles
    ) {
        requireCompanyAccess(principal, companyId);
        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)) {
            return;
        }
        boolean matched = principal.companyGrants().stream()
            .anyMatch(g -> g.companyId().equals(companyId) && allowedRoles.contains(g.roleCode()));
        if (!matched) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-RBAC-ROLE-INSUFFICIENT",
                "Insufficient Company Role",
                "Operation requires one of " + allowedRoles + " on company " + companyId + "."
            );
        }
    }

    public List<SystemRole> parseSystemRoles(String rolesJson) {
        List<String> raw = parseStringArray(rolesJson);
        List<SystemRole> parsed = new ArrayList<>();
        for (String r : raw) {
            try {
                parsed.add(SystemRole.valueOf(r));
            } catch (IllegalArgumentException ignored) {
                // Ignore unknown forward-compatible role strings
            }
        }
        return List.copyOf(parsed);
    }

    public List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> items = new ArrayList<>();
            for (JsonNode child : node) {
                if (child.isString()) {
                    items.add(child.asString());
                }
            }
            return List.copyOf(items);
        } catch (JacksonException ex) {
            return List.of();
        }
    }
}
