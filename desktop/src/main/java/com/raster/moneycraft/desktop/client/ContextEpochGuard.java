package com.raster.moneycraft.desktop.client;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks monotonic client context epochs across window lifecycle, authenticated person/authority,
 * active company, and active view so delayed background network, print, or serial completions
 * never overwrite a switched company/window/draft or falsely claim server rollback (S-04, AT-ITER110-027).
 */
public final class ContextEpochGuard {

    private final AtomicLong windowEpoch = new AtomicLong(1L);
    private final AtomicLong authorityEpoch = new AtomicLong(1L);
    private final AtomicLong companyEpoch = new AtomicLong(1L);
    private final AtomicLong viewEpoch = new AtomicLong(1L);

    private volatile String activePrincipalId;
    private volatile UUID activeCompanyId;
    private volatile String activeViewId = "SELECT_COMPANY";

    public synchronized EpochSnapshot captureSnapshot() {
        return new EpochSnapshot(
            windowEpoch.get(),
            authorityEpoch.get(),
            companyEpoch.get(),
            viewEpoch.get(),
            activePrincipalId,
            activeCompanyId,
            activeViewId
        );
    }

    public synchronized boolean isCurrent(EpochSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return snapshot.windowEpoch() == windowEpoch.get()
            && snapshot.authorityEpoch() == authorityEpoch.get()
            && snapshot.companyEpoch() == companyEpoch.get()
            && snapshot.viewEpoch() == viewEpoch.get()
            && Objects.equals(snapshot.principalId(), activePrincipalId)
            && Objects.equals(snapshot.companyId(), activeCompanyId)
            && Objects.equals(snapshot.viewId(), activeViewId);
    }

    public synchronized boolean isCompanyAndAuthorityCurrent(EpochSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return snapshot.windowEpoch() == windowEpoch.get()
            && snapshot.authorityEpoch() == authorityEpoch.get()
            && snapshot.companyEpoch() == companyEpoch.get()
            && Objects.equals(snapshot.principalId(), activePrincipalId)
            && Objects.equals(snapshot.companyId(), activeCompanyId);
    }

    public synchronized void onPrincipalChanged(String nextPrincipalId) {
        this.activePrincipalId = nextPrincipalId;
        authorityEpoch.incrementAndGet();
        companyEpoch.incrementAndGet();
        viewEpoch.incrementAndGet();
    }

    public synchronized void onCompanySwitched(UUID nextCompanyId) {
        this.activeCompanyId = nextCompanyId;
        companyEpoch.incrementAndGet();
        viewEpoch.incrementAndGet();
    }

    public synchronized void onViewChanged(String nextViewId) {
        this.activeViewId = Objects.requireNonNull(nextViewId, "nextViewId must not be null");
        viewEpoch.incrementAndGet();
    }

    public synchronized void onWindowClosedOrReset() {
        windowEpoch.incrementAndGet();
        viewEpoch.incrementAndGet();
    }

    public UUID activeCompanyId() {
        return activeCompanyId;
    }

    public String activePrincipalId() {
        return activePrincipalId;
    }

    public String activeViewId() {
        return activeViewId;
    }

    public record EpochSnapshot(
        long windowEpoch,
        long authorityEpoch,
        long companyEpoch,
        long viewEpoch,
        String principalId,
        UUID companyId,
        String viewId
    ) {
    }
}
