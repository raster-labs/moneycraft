package com.raster.moneycraft.foundation.audit;

import java.time.Instant;

public record AuditVerifyResult(
    boolean verified,
    long checkedEventCount,
    String tailHashSha256,
    Instant verifiedAt,
    String failureDetail
) {
}
