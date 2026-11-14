package com.raster.moneycraft.foundation.config;

import com.raster.moneycraft.foundation.tenant.TenantAwareBusinessDataSource;
import com.raster.moneycraft.foundation.tenant.TenantAwareJpaTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource;
import org.springframework.session.jdbc.config.annotation.SpringSessionTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableTransactionManagement
public class DualDataSourceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DualDataSourceConfig.class);

    @Bean(name = "authorityDataSource", destroyMethod = "close")
    @SpringSessionDataSource
    public HikariDataSource authorityDataSource(DualDataSourceProperties properties) {
        return buildHikariPool("moneycraft-authority-pool", properties.getAuthority());
    }

    @Bean(name = "businessRawDataSource", destroyMethod = "close")
    public HikariDataSource businessRawDataSource(DualDataSourceProperties properties) {
        return buildHikariPool("moneycraft-business-pool", properties.getBusiness());
    }

    @Bean(name = "businessDataSource")
    @Primary
    public TenantAwareBusinessDataSource businessDataSource(
        @Qualifier("businessRawDataSource") HikariDataSource businessRawDataSource
    ) {
        return new TenantAwareBusinessDataSource(businessRawDataSource);
    }

    @Bean(name = "authorityFlyway")
    public Flyway authorityFlyway(DualDataSourceProperties properties) {
        DualDataSourceProperties.ClusterPool cfg = properties.getAuthority();
        String user = nonBlankOrDefault(cfg.getMigratorUsername(), cfg.getUsername());
        String pass = nonBlankOrDefault(cfg.getMigratorPassword(), cfg.getPassword());
        return Flyway.configure()
            .dataSource(cfg.getJdbcUrl(), user, pass)
            .locations("classpath:db/authority-migration")
            .table("flyway_schema_history")
            .placeholderReplacement(false)
            .validateMigrationNaming(true)
            .load();
    }

    @Bean(name = "businessFlyway")
    public Flyway businessFlyway(DualDataSourceProperties properties) {
        DualDataSourceProperties.ClusterPool cfg = properties.getBusiness();
        String user = nonBlankOrDefault(cfg.getMigratorUsername(), cfg.getUsername());
        String pass = nonBlankOrDefault(cfg.getMigratorPassword(), cfg.getPassword());
        return Flyway.configure()
            .dataSource(cfg.getJdbcUrl(), user, pass)
            .locations("classpath:db/migration")
            .table("flyway_schema_history")
            .placeholderReplacement(false)
            .validateMigrationNaming(true)
            .load();
    }

    @Bean(name = "dualFlywayInitializer")
    public DualFlywayMigrationSummary dualFlywayInitializer(
        MoneyCraftProperties moneyCraftProperties,
        @Qualifier("authorityFlyway") Flyway authorityFlyway,
        @Qualifier("businessFlyway") Flyway businessFlyway
    ) {
        if (!moneyCraftProperties.getFlyway().isEnabled()) {
            LOG.info("Dual-PG18 Flyway startup migration skipped (moneycraft.flyway.enabled=false).");
            return new DualFlywayMigrationSummary(false, 0, 0);
        }
        MigrateResult authResult = authorityFlyway.migrate();
        MigrateResult bizResult = businessFlyway.migrate();
        LOG.info(
            "Dual-PG18 Flyway migrations complete: authority={} applied, business={} applied.",
            authResult.migrationsExecuted,
            bizResult.migrationsExecuted
        );
        return new DualFlywayMigrationSummary(
            true,
            authResult.migrationsExecuted,
            bizResult.migrationsExecuted
        );
    }

    @Bean(name = "authorityJdbcTemplate")
    @DependsOn("dualFlywayInitializer")
    public JdbcTemplate authorityJdbcTemplate(
        @Qualifier("authorityDataSource") DataSource authorityDataSource
    ) {
        return new JdbcTemplate(authorityDataSource);
    }

    @Bean(name = "authorityNamedJdbcTemplate")
    @DependsOn("dualFlywayInitializer")
    public NamedParameterJdbcTemplate authorityNamedJdbcTemplate(
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate
    ) {
        return new NamedParameterJdbcTemplate(authorityJdbcTemplate);
    }

    @Bean(name = "authorityTransactionManager")
    @SpringSessionTransactionManager
    public PlatformTransactionManager authorityTransactionManager(
        @Qualifier("authorityDataSource") DataSource authorityDataSource
    ) {
        return new DataSourceTransactionManager(authorityDataSource);
    }

    @Bean(name = "authorityTransactionTemplate")
    public TransactionTemplate authorityTransactionTemplate(
        @Qualifier("authorityTransactionManager") PlatformTransactionManager authorityTransactionManager
    ) {
        return new TransactionTemplate(authorityTransactionManager);
    }

    @Bean(name = "businessJdbcTemplate")
    @Primary
    @DependsOn("dualFlywayInitializer")
    public JdbcTemplate businessJdbcTemplate(
        @Qualifier("businessDataSource") DataSource businessDataSource
    ) {
        return new JdbcTemplate(businessDataSource);
    }

    @Bean(name = "businessNamedJdbcTemplate")
    @Primary
    @DependsOn("dualFlywayInitializer")
    public NamedParameterJdbcTemplate businessNamedJdbcTemplate(
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate
    ) {
        return new NamedParameterJdbcTemplate(businessJdbcTemplate);
    }

    @Bean(name = "entityManagerFactory")
    @Primary
    @DependsOn("dualFlywayInitializer")
    public LocalContainerEntityManagerFactoryBean businessEntityManagerFactory(
        @Qualifier("businessDataSource") DataSource businessDataSource
    ) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(businessDataSource);
        emf.setPackagesToScan("com.raster.moneycraft");
        emf.setPersistenceUnitName("moneycraft-business");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(false);
        vendorAdapter.setShowSql(false);
        emf.setJpaVendorAdapter(vendorAdapter);

        Map<String, Object> jpaProps = new LinkedHashMap<>();
        jpaProps.put("hibernate.hbm2ddl.auto", "none");
        jpaProps.put("hibernate.jdbc.time_zone", "UTC");
        emf.setJpaPropertyMap(jpaProps);
        return emf;
    }

    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager businessTransactionManager(
        @Qualifier("entityManagerFactory") EntityManagerFactory entityManagerFactory,
        @Qualifier("businessDataSource") DataSource businessDataSource
    ) {
        return new TenantAwareJpaTransactionManager(entityManagerFactory, businessDataSource);
    }

    @Bean(name = "businessTransactionTemplate")
    @Primary
    public TransactionTemplate businessTransactionTemplate(
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        return new TransactionTemplate(businessTransactionManager);
    }

    private static HikariDataSource buildHikariPool(
        String poolName,
        DualDataSourceProperties.ClusterPool cfg
    ) {
        Objects.requireNonNull(cfg.getJdbcUrl(), poolName + " jdbcUrl must not be null");
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(poolName);
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setJdbcUrl(cfg.getJdbcUrl());
        hikari.setUsername(cfg.getUsername());
        hikari.setPassword(cfg.getPassword() == null ? "" : cfg.getPassword());
        hikari.setMaximumPoolSize(cfg.getMaximumPoolSize());
        hikari.setMinimumIdle(cfg.getMinimumIdle());
        hikari.setConnectionTimeout(10_000L);
        hikari.setValidationTimeout(3_000L);
        hikari.addDataSourceProperty("ApplicationName", poolName);
        hikari.addDataSourceProperty("reWriteBatchedInserts", "true");
        return new HikariDataSource(hikari);
    }

    private static String nonBlankOrDefault(String candidate, String fallback) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    public record DualFlywayMigrationSummary(
        boolean executed,
        int authorityMigrationsApplied,
        int businessMigrationsApplied
    ) {
    }
}
