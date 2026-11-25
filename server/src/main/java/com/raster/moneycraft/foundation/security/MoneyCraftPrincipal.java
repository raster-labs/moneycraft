package com.raster.moneycraft.foundation.security;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public record MoneyCraftPrincipal(
    UUID principalId,
    String kind,
    String username,
    String displayName,
    List<SystemRole> systemRoles,
    List<CompanyGrantInfo> companyGrants,
    boolean passkeyEnrolled,
    boolean superAdmin,
    UUID serviceKeyId,
    List<UUID> serviceKeyCompanyAllowlist,
    List<String> serviceKeyEndpointAllowlist,
    UUID oidcIssuerId,
    UUID oidcLinkId,
    Long oidcIssuerVersion,
    Long oidcLinkVersion,
    String nativeClientId
) implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 3L;

    public MoneyCraftPrincipal {
        systemRoles = systemRoles == null ? List.of() : List.copyOf(systemRoles);
        companyGrants = companyGrants == null ? List.of() : List.copyOf(companyGrants);
        serviceKeyCompanyAllowlist = serviceKeyCompanyAllowlist == null
            ? List.of()
            : List.copyOf(serviceKeyCompanyAllowlist);
        serviceKeyEndpointAllowlist = serviceKeyEndpointAllowlist == null
            ? List.of()
            : List.copyOf(serviceKeyEndpointAllowlist);
    }

    public MoneyCraftPrincipal(
        UUID principalId,
        String kind,
        String username,
        String displayName,
        List<SystemRole> systemRoles,
        List<CompanyGrantInfo> companyGrants,
        boolean passkeyEnrolled,
        UUID serviceKeyId,
        List<UUID> serviceKeyCompanyAllowlist,
        List<String> serviceKeyEndpointAllowlist,
        UUID oidcIssuerId,
        UUID oidcLinkId,
        Long oidcIssuerVersion,
        Long oidcLinkVersion
    ) {
        this(
            principalId,
            kind,
            username,
            displayName,
            systemRoles,
            companyGrants,
            passkeyEnrolled,
            false,
            serviceKeyId,
            serviceKeyCompanyAllowlist,
            serviceKeyEndpointAllowlist,
            oidcIssuerId,
            oidcLinkId,
            oidcIssuerVersion,
            oidcLinkVersion,
            null
        );
    }

    public MoneyCraftPrincipal(
        UUID principalId,
        String kind,
        String username,
        String displayName,
        List<SystemRole> systemRoles,
        List<CompanyGrantInfo> companyGrants,
        boolean passkeyEnrolled,
        UUID serviceKeyId,
        List<UUID> serviceKeyCompanyAllowlist,
        List<String> serviceKeyEndpointAllowlist
    ) {
        this(
            principalId,
            kind,
            username,
            displayName,
            systemRoles,
            companyGrants,
            passkeyEnrolled,
            false,
            serviceKeyId,
            serviceKeyCompanyAllowlist,
            serviceKeyEndpointAllowlist,
            null,
            null,
            null,
            null,
            null
        );
    }

    public MoneyCraftPrincipal withOidcSessionBinding(
        UUID issuerId,
        UUID linkId,
        long issuerVersion,
        long linkVersion
    ) {
        return new MoneyCraftPrincipal(
            principalId,
            kind,
            username,
            displayName,
            systemRoles,
            companyGrants,
            passkeyEnrolled,
            superAdmin,
            serviceKeyId,
            serviceKeyCompanyAllowlist,
            serviceKeyEndpointAllowlist,
            issuerId,
            oidcLinkIdOr(linkId),
            issuerVersion,
            linkVersion,
            nativeClientId
        );
    }

    public MoneyCraftPrincipal withNativeClientBinding(String clientId) {
        return new MoneyCraftPrincipal(
            principalId,
            kind,
            username,
            displayName,
            systemRoles,
            companyGrants,
            passkeyEnrolled,
            superAdmin,
            serviceKeyId,
            serviceKeyCompanyAllowlist,
            serviceKeyEndpointAllowlist,
            oidcIssuerId,
            oidcLinkId,
            oidcIssuerVersion,
            oidcLinkVersion,
            clientId
        );
    }

    public MoneyCraftPrincipal withSuperAdmin(boolean designated) {
        return new MoneyCraftPrincipal(
            principalId,
            kind,
            username,
            displayName,
            systemRoles,
            companyGrants,
            passkeyEnrolled,
            designated,
            serviceKeyId,
            serviceKeyCompanyAllowlist,
            serviceKeyEndpointAllowlist,
            oidcIssuerId,
            oidcLinkId,
            oidcIssuerVersion,
            oidcLinkVersion,
            nativeClientId
        );
    }

    private static UUID oidcLinkIdOr(UUID candidate) {
        return candidate;
    }

    @Override
    public String getName() {
        return username != null && !username.isBlank() ? username : principalId.toString();
    }

    public boolean isServiceKeyAuthenticated() {
        return serviceKeyId != null;
    }

    public boolean isOidcAuthenticated() {
        return oidcIssuerId != null && oidcLinkId != null;
    }

    public boolean isNativeBearerAuthenticated() {
        return nativeClientId != null && !nativeClientId.isBlank();
    }

    public boolean hasSystemRole(SystemRole role) {
        return systemRoles.contains(role);
    }

    public Collection<GrantedAuthority> toGrantedAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"));
        for (SystemRole sysRole : systemRoles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + sysRole.name()));
        }
        if (superAdmin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        }
        return List.copyOf(authorities);
    }
}
