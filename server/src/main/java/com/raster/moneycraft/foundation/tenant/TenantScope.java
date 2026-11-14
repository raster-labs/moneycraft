package com.raster.moneycraft.foundation.tenant;

import java.util.Objects;
import java.util.UUID;

public sealed interface TenantScope {

    String companyIdSetting();

    String bypassRlsSetting();

    record None() implements TenantScope {
        @Override
        public String companyIdSetting() {
            return "";
        }

        @Override
        public String bypassRlsSetting() {
            return "off";
        }
    }

    record Company(UUID companyId) implements TenantScope {
        public Company {
            Objects.requireNonNull(companyId, "companyId must not be null");
        }

        @Override
        public String companyIdSetting() {
            return companyId.toString();
        }

        @Override
        public String bypassRlsSetting() {
            return "off";
        }
    }

    record SystemBypass(String reason) implements TenantScope {
        public SystemBypass {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("System RLS bypass reason must be non-empty");
            }
        }

        @Override
        public String companyIdSetting() {
            return "";
        }

        @Override
        public String bypassRlsSetting() {
            return "on";
        }
    }
}
