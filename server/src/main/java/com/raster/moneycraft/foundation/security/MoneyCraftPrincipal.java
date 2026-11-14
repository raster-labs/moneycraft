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
    UUID serviceKeyId,
    List<UUID> serviceKeyCompanyAllowlist,
    List<String> serviceKeyEndpointAllowlist
) implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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

    @Override
    public String getName() {
        return username != null && !username.isBlank() ? username : principalId.toString();
    }

    public boolean isServiceKeyAuthenticated() {
        return serviceKeyId != null;
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
        return List.copyOf(authorities);
    }
}
