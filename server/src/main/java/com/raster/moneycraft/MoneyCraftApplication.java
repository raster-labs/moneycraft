package com.raster.moneycraft;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    DataSourceHealthContributorAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    FlywayAutoConfiguration.class,
    OAuth2ClientAutoConfiguration.class,
    OAuth2ClientWebSecurityAutoConfiguration.class
})
@ConfigurationPropertiesScan
@EnableScheduling
public class MoneyCraftApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MoneyCraftApplication.class);
        boolean cliMigratorOnly = Arrays.stream(args)
            .anyMatch(arg -> arg.startsWith("--moneycraft.migrator.only=true")
                || arg.startsWith("--moneycraft.migrator.exit-on-complete=true"));
        if (cliMigratorOnly) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = app.run(args);
        boolean exitAfterMigration = Boolean.parseBoolean(
            context.getEnvironment().getProperty("moneycraft.migrator.exit-on-complete", "false")
        ) || Boolean.parseBoolean(
            context.getEnvironment().getProperty("moneycraft.migrator.only", "false")
        );
        if (exitAfterMigration) {
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }
    }
}
