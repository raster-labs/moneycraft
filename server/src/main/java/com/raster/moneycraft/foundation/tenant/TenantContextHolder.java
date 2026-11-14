package com.raster.moneycraft.foundation.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class TenantContextHolder {

    private static final TenantScope NONE = new TenantScope.None();
    private static final ThreadLocal<TenantScope> CURRENT = ThreadLocal.withInitial(() -> NONE);

    private TenantContextHolder() {
    }

    public static TenantScope current() {
        return CURRENT.get();
    }

    public static Optional<UUID> currentCompanyId() {
        TenantScope scope = CURRENT.get();
        if (scope instanceof TenantScope.Company company) {
            return Optional.of(company.companyId());
        }
        return Optional.empty();
    }

    public static void set(TenantScope scope) {
        CURRENT.set(Objects.requireNonNull(scope, "scope must not be null"));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callWithCompany(UUID companyId, Supplier<T> action) {
        TenantScope previous = CURRENT.get();
        CURRENT.set(new TenantScope.Company(companyId));
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static void runWithCompany(UUID companyId, Runnable action) {
        callWithCompany(companyId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callWithSystemBypass(String reason, Supplier<T> action) {
        TenantScope previous = CURRENT.get();
        CURRENT.set(new TenantScope.SystemBypass(reason));
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static void runWithSystemBypass(String reason, Runnable action) {
        callWithSystemBypass(reason, () -> {
            action.run();
            return null;
        });
    }

    private static void restore(TenantScope previous) {
        if (previous == null || previous instanceof TenantScope.None) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
