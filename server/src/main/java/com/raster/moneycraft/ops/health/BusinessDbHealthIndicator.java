package com.raster.moneycraft.ops.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Business Accounting PostgreSQL 18 cluster connectivity probe.
 */
@Component
public class BusinessDbHealthIndicator implements HealthIndicator {

    private final JdbcTemplate businessJdbcTemplate;

    public BusinessDbHealthIndicator(@Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate) {
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            Integer probe = businessJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (probe != null && probe == 1) {
                return Health.up().withDetail("cluster", "business-pg18").build();
            }
            return Health.down().withDetail("cluster", "business-pg18").build();
        } catch (RuntimeException ex) {
            return Health.down(ex).withDetail("cluster", "business-pg18").build();
        }
    }
}
