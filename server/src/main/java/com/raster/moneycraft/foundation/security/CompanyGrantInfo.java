package com.raster.moneycraft.foundation.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record CompanyGrantInfo(
    UUID grantId,
    UUID companyId,
    CompanyRole roleCode,
    List<String> voucherTypeAllowlist,
    Integer backdatedDaysLimit,
    boolean exportPermitted
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public CompanyGrantInfo {
        voucherTypeAllowlist = voucherTypeAllowlist == null ? List.of() : List.copyOf(voucherTypeAllowlist);
    }
}
