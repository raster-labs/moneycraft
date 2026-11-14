package com.raster.moneycraft.ops.health;

import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.authority.InstallationSecurityState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness gate verifying single-writer advisory lease and non-quarantined installation state.
 */
@Component
public class AuthorityGuardHealthIndicator implements HealthIndicator {

    private final AuthorityGuard authorityGuard;

    public AuthorityGuardHealthIndicator(AuthorityGuard authorityGuard) {
        this.authorityGuard = authorityGuard;
    }

    @Override
    public Health health() {
        AuthorityGuard.GuardSnapshot snap = authorityGuard.currentSnapshot();
        if (!snap.writerLeaseHeld()) {
            return Health.outOfService()
                .withDetail("securityState", snap.securityState().name())
                .withDetail("fenceEpoch", snap.fenceEpoch())
                .withDetail("writerLeaseHeld", false)
                .build();
        }
        if (snap.securityState() == InstallationSecurityState.QUARANTINED) {
            return Health.outOfService()
                .withDetail("securityState", snap.securityState().name())
                .withDetail("fenceEpoch", snap.fenceEpoch())
                .withDetail("quarantineReason", snap.quarantineReason() == null ? "" : snap.quarantineReason())
                .build();
        }
        return Health.up()
            .withDetail("securityState", snap.securityState().name())
            .withDetail("fenceEpoch", snap.fenceEpoch())
            .withDetail("writerLeaseHeld", true)
            .build();
    }
}
