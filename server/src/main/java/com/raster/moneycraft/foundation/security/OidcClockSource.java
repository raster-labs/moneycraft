package com.raster.moneycraft.foundation.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Shared wall-clock and monotonic time source for OIDC federation lifecycle reconciliation
 * and session freshness admission checks. Uses system UTC and System.nanoTime() in production.
 */
@Component
public class OidcClockSource {

    private volatile Clock utcClock = Clock.systemUTC();
    private volatile LongSupplier nanoClock = System::nanoTime;

    public Instant instant() {
        return utcClock.instant();
    }

    public long nanoTime() {
        return nanoClock.getAsLong();
    }

    public void overrideForTesting(Clock customUtcClock, LongSupplier customNanoClock) {
        this.utcClock = Objects.requireNonNull(customUtcClock, "customUtcClock is required");
        this.nanoClock = Objects.requireNonNull(customNanoClock, "customNanoClock is required");
    }

    public void resetSystemDefault() {
        this.utcClock = Clock.systemUTC();
        this.nanoClock = System::nanoTime;
    }
}
