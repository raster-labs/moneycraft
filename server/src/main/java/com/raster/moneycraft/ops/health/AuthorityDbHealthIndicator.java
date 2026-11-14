package com.raster.moneycraft.ops.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Protected Authority PostgreSQL 18 cluster connectivity probe.
 */
@Component
public class AuthorityDbHealthIndicator implements HealthIndicator {

    private final JdbcTemplate authorityJdbcTemplate;

    public AuthorityDbHealthIndicator(@Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate) {
        this.authorityJdbcTemplate = authorityJdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            Integer probe = authorityJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (probe != null && probe == 1) {
                return Health.up().withDetail("cluster", "authority-pg18").build();
            }
            return Health.down().withDetail("cluster", "authority-pg18").build();
        } catch (RuntimeException ex) {
            return Health.down(ex).withDetail("cluster", "authority-pg18").build();
        }
    }
}
