package com.raster.moneycraft.foundation.security;

import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

    private static final Set<String> AUDITOR_READ_PERMISSIONS = Set.of(
        CompanyPermissions.COMPANY_READ,
        CompanyPermissions.MASTER_READ,
        CompanyPermissions.VOUCHER_READ,
        CompanyPermissions.REPORT_READ,
        CompanyPermissions.IMPORT_READ
    );

    private final JdbcTemplate authorityJdbcTemplate;
    private final OidcClockSource clockSource;
    private final ObjectMapper objectMapper;

    public RbacAuthorizationService(
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        OidcClockSource clockSource,
        ObjectMapper objectMapper
    ) {
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.clockSource = clockSource;
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
                "Sign in or supply a valid scoped credential to access this resource."
            );
        }
        return refreshPrincipal(principal);
    }

    public MoneyCraftPrincipal requirePersonPrincipal() {
        MoneyCraftPrincipal principal = requireAuthenticatedPrincipal();
        if (!"PERSON".equals(principal.kind()) || principal.isServiceKeyAuthenticated()) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-PERSON-PRINCIPAL-REQUIRED",
                "Person Identity Required",
                "Service identities and machine API keys are not permitted on person-only endpoints."
            );
        }
        return principal;
    }

    public MoneyCraftPrincipal requireSuperAdminPerson() {
        MoneyCraftPrincipal principal = requireAuthenticatedPrincipal();
        if (!"PERSON".equals(principal.kind())
            || principal.isServiceKeyAuthenticated()
            || !principal.superAdmin()) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-SUPER-ADMIN-REQUIRED",
                "Super Admin Designation Required",
                "Only an enabled PERSON holding an active installation Super Admin designation may access this surface."
            );
        }
        return principal;
    }

    public void requireRecentPersonAuthentication(UUID principalId, Duration maxAge) {
        List<OffsetDateTime> rows = authorityJdbcTemplate.query(
            "SELECT last_authenticated_at FROM ma_principal WHERE principal_id = ? AND status = 'ENABLED'",
            (rs, rowNum) -> rs.getObject("last_authenticated_at", OffsetDateTime.class),
            principalId
        );
        Instant now = clockSource.instant();
        if (rows.isEmpty() || rows.getFirst() == null) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-RECENT-AUTH-REQUIRED",
                "Recent Authentication Required",
                "This operation requires fresh person authentication within the last " + maxAge.toMinutes() + " minutes."
            );
        }
        Instant lastAuth = rows.getFirst().toInstant();
        if (lastAuth.plus(maxAge).isBefore(now)) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-RECENT-AUTH-REQUIRED",
                "Recent Authentication Expired",
                "Recent person authentication window (" + maxAge.toMinutes() + " minutes) has elapsed; complete step-up."
            );
        }
    }

    public MoneyCraftPrincipal refreshPrincipal(MoneyCraftPrincipal base) {
        List<RawPrincipalRow> rows = authorityJdbcTemplate.query(
            """
            SELECT principal_id, kind, username::text AS username, display_name, status, system_roles::text AS roles_json
              FROM ma_principal
             WHERE principal_id = ?
            """,
            (rs, rowNum) -> new RawPrincipalRow(
                rs.getObject("principal_id", UUID.class),
                rs.getString("kind"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getString("roles_json")
            ),
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
        RawPrincipalRow raw = rows.getFirst();
        if (!"ENABLED".equals(raw.status())) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-PRINCIPAL-DISABLED",
                "Principal Disabled or Locked",
                "Principal status is " + raw.status() + "."
            );
        }
        List<SystemRole> sysRoles = parseSystemRoles(raw.rolesJson());
        List<CompanyGrantInfo> grants = loadActiveGrants(raw.principalId());
        boolean hasPasskey = hasActivePasskey(raw.principalId());
        boolean isSuperAdmin = "PERSON".equals(raw.kind())
            && base.serviceKeyId() == null
            && hasActiveSuperAdminDesignation(raw.principalId());
        return new MoneyCraftPrincipal(
            raw.principalId(),
            raw.kind(),
            raw.username(),
            raw.displayName(),
            sysRoles,
            grants,
            hasPasskey,
            isSuperAdmin,
            base.serviceKeyId(),
            base.serviceKeyCompanyAllowlist(),
            base.serviceKeyEndpointAllowlist(),
            base.oidcIssuerId(),
            base.oidcLinkId(),
            base.oidcIssuerVersion(),
            base.oidcLinkVersion(),
            base.nativeClientId()
        );
    }

    private record RawPrincipalRow(
        UUID principalId,
        String kind,
        String username,
        String displayName,
        String status,
        String rolesJson
    ) {
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

    public boolean hasActiveSuperAdminDesignation(UUID principalId) {
        Integer count = authorityJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM ma_super_admin_designation d
              JOIN ma_principal p ON p.principal_id = d.principal_id
             WHERE d.principal_id = ?
               AND d.state = 'ACTIVE'
               AND p.kind = 'PERSON'
               AND p.status = 'ENABLED'
            """,
            Integer.class,
            principalId
        );
        return count != null && count > 0;
    }

    public List<CompanyGrantInfo> loadActiveGrants(UUID principalId) {
        return authorityJdbcTemplate.query(
            """
            SELECT grant_id, company_id, role_code, permissions::text AS permissions_json,
                   voucher_type_allowlist::text AS allowlist_json,
                   backdated_days_limit, export_permitted, source, source_ref
              FROM ma_grant
             WHERE principal_id = ? AND state = 'ACTIVE'
             ORDER BY created_at ASC, grant_id ASC
            """,
            (rs, rowNum) -> {
                Integer backdated = rs.getObject("backdated_days_limit", Integer.class);
                return new CompanyGrantInfo(
                    rs.getObject("grant_id", UUID.class),
                    rs.getObject("company_id", UUID.class),
                    CompanyRole.valueOf(rs.getString("role_code")),
                    parseStringArray(rs.getString("permissions_json")),
                    parseStringArray(rs.getString("allowlist_json")),
                    backdated,
                    rs.getBoolean("export_permitted"),
                    rs.getString("source"),
                    rs.getString("source_ref")
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

    public boolean hasCompanyPermission(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String permission
    ) {
        if (permission == null || permission.isBlank() || !canAccessCompany(principal, companyId)) {
            return false;
        }
        if (principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)) {
            return true;
        }
        if (principal.hasSystemRole(SystemRole.SECURITY_AUDITOR)
            && AUDITOR_READ_PERMISSIONS.contains(permission)) {
            return true;
        }
        for (CompanyGrantInfo grant : principal.companyGrants()) {
            if (!grant.companyId().equals(companyId)) {
                continue;
            }
            if (!grant.permissions().isEmpty()) {
                if (grant.permissions().contains(permission)) {
                    return true;
                }
            } else if ("EXPLICIT".equalsIgnoreCase(grant.source())
                && grant.roleCode().defaultPermissions().contains(permission)) {
                return true;
            }
        }
        return false;
    }

    public void requireCompanyPermission(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String permission
    ) {
        requireCompanyAccess(principal, companyId);
        if (!hasCompanyPermission(principal, companyId, permission)) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-RBAC-PERMISSION-DENIED",
                "Company Permission Denied",
                "Principal lacks required permission '" + permission + "' on company " + companyId + "."
            );
        }
    }

    public void requireExportPermitted(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        requireCompanyAccess(principal, companyId);
        if (principal.isServiceKeyAuthenticated()
            || principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)
            || principal.hasSystemRole(SystemRole.SECURITY_AUDITOR)) {
            return;
        }
        boolean allowed = principal.companyGrants().stream()
            .filter(g -> g.companyId().equals(companyId))
            .anyMatch(g -> g.roleCode() == CompanyRole.COMPANY_ADMIN || g.exportPermitted());
        if (!allowed) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-SEC-EXPORT-FORBIDDEN",
                "Export Not Permitted by Security Level",
                "You do not have access to Print/Export/Share for company " + companyId + "."
            );
        }
    }

    public void requireVoucherTypeAllowed(
        MoneyCraftPrincipal principal,
        UUID companyId,
        String voucherTypeName,
        String baseCategory
    ) {
        requireCompanyAccess(principal, companyId);
        if (principal.isServiceKeyAuthenticated()
            || principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)) {
            return;
        }
        List<CompanyGrantInfo> grants = principal.companyGrants().stream()
            .filter(g -> g.companyId().equals(companyId))
            .toList();
        if (grants.isEmpty()) {
            return;
        }
        for (CompanyGrantInfo g : grants) {
            if (g.roleCode() == CompanyRole.COMPANY_ADMIN || g.voucherTypeAllowlist().isEmpty()) {
                return;
            }
            for (String allowedType : g.voucherTypeAllowlist()) {
                if (allowedType != null
                    && (allowedType.equalsIgnoreCase(voucherTypeName)
                        || (baseCategory != null && allowedType.equalsIgnoreCase(baseCategory)))) {
                    return;
                }
            }
        }
        throw new MoneyCraftException(
            HttpStatus.FORBIDDEN,
            "MC-SEC-VOUCHER-TYPE-FORBIDDEN",
            "Voucher Type Restricted by Security Level",
            "Security Level does not permit creating or altering voucher type '" + voucherTypeName + "'."
        );
    }

    public void requireBackdatedDaysWithinLimit(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate voucherDate,
        LocalDate referenceAnchorDate
    ) {
        if (voucherDate == null || referenceAnchorDate == null || !voucherDate.isBefore(referenceAnchorDate)) {
            return;
        }
        if (principal.isServiceKeyAuthenticated()
            || principal.hasSystemRole(SystemRole.INSTALLATION_OWNER)) {
            return;
        }
        List<CompanyGrantInfo> grants = principal.companyGrants().stream()
            .filter(g -> g.companyId().equals(companyId))
            .toList();
        if (grants.isEmpty()) {
            return;
        }
        int maxAllowedDays = -1;
        for (CompanyGrantInfo g : grants) {
            if (g.roleCode() == CompanyRole.COMPANY_ADMIN || g.backdatedDaysLimit() == null) {
                return;
            }
            if (g.backdatedDaysLimit() > maxAllowedDays) {
                maxAllowedDays = g.backdatedDaysLimit();
            }
        }
        long daysBack = ChronoUnit.DAYS.between(voucherDate, referenceAnchorDate);
        if (daysBack > maxAllowedDays) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-SEC-BACKDATED-LIMIT-EXCEEDED",
                "Back-Dated Voucher Limit Exceeded",
                "Security Violation! Voucher date " + voucherDate + " is " + daysBack
                    + " day(s) before latest company voucher date " + referenceAnchorDate
                    + ", exceeding allowed back-dated limit of " + maxAllowedDays + " day(s)."
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
