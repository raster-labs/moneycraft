package com.raster.moneycraft.foundation.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record CompanyGrantInfo(
    UUID grantId,
    UUID companyId,
    CompanyRole roleCode,
    List<String> permissions,
    List<String> voucherTypeAllowlist,
    Integer backdatedDaysLimit,
    boolean exportPermitted,
    String source,
    String sourceRef
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 2L;

    public CompanyGrantInfo {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        voucherTypeAllowlist = voucherTypeAllowlist == null ? List.of() : List.copyOf(voucherTypeAllowlist);
        source = (source == null || source.isBlank()) ? "EXPLICIT" : source;
    }

    public CompanyGrantInfo(
        UUID grantId,
        UUID companyId,
        CompanyRole roleCode,
        List<String> voucherTypeAllowlist,
        Integer backdatedDaysLimit,
        boolean exportPermitted
    ) {
        this(
            grantId,
            companyId,
            roleCode,
            List.of(),
            voucherTypeAllowlist,
            backdatedDaysLimit,
            exportPermitted,
            "EXPLICIT",
            null
        );
    }
}
