package com.raster.moneycraft.foundation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.TestcontainersConfiguration;

/**
 * Boots two real PostgreSQL 18 (18.6-bookworm) clusters (Protected Authority + Business Books)
 * with least-privilege NOBYPASSRLS runtime role mc_app_runtime so PostgreSQL 18 Row-Level Security
 * and append-only table privileges are genuinely enforced in every test.
 */
public abstract class DualPostgres18TestcontainersSupport {

    protected static final String MIGRATOR_USER = "mc_flyway_migrator";
    protected static final String MIGRATOR_PASS = "MigratorTest#2026";
    protected static final String RUNTIME_USER = "mc_app_runtime";
    protected static final String RUNTIME_PASS = "RuntimeTest#2026";
    protected static final String TEST_KEK = "0123456789abcdef0123456789abcdef";

    protected static final PostgreSQLContainer AUTHORITY_PG;
    protected static final PostgreSQLContainer BUSINESS_PG;

    static {
        configureLocalDockerSocketIfAbsent();
        AUTHORITY_PG = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("moneycraft_authority")
            .withUsername("postgres")
            .withPassword("postgres");
        BUSINESS_PG = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("moneycraft_business")
            .withUsername("postgres")
            .withPassword("postgres");
        AUTHORITY_PG.start();
        BUSINESS_PG.start();
        provisionRolesAndExtensions(AUTHORITY_PG, false);
        provisionRolesAndExtensions(BUSINESS_PG, true);
    }

    private static void configureLocalDockerSocketIfAbsent() {
        String envHost = System.getenv("DOCKER_HOST");
        if (envHost != null && !envHost.isBlank()) {
            return;
        }
        if (Files.exists(Path.of("/var/run/docker.sock"))) {
            return;
        }
        String home = System.getProperty("user.home", "");
        List<Path> localSockets = List.of(
            Path.of(home, ".colima", "default", "docker.sock"),
            Path.of(home, ".orbstack", "run", "docker.sock"),
            Path.of(home, ".docker", "run", "docker.sock")
        );
        for (Path candidate : localSockets) {
            if (Files.exists(candidate)) {
                TestcontainersConfiguration.getInstance()
                    .updateUserConfig("docker.host", "unix://" + candidate.toAbsolutePath());
                return;
            }
        }
    }

    private static void provisionRolesAndExtensions(PostgreSQLContainer container, boolean includeTrgm) {
        try (
            Connection conn = DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword()
            );
            Statement stmt = conn.createStatement()
        ) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS citext");
            if (includeTrgm) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            }
            stmt.execute(
                "CREATE ROLE " + MIGRATOR_USER
                    + " WITH LOGIN PASSWORD '" + MIGRATOR_PASS + "' NOSUPERUSER CREATEDB"
            );
            stmt.execute(
                "CREATE ROLE " + RUNTIME_USER
                    + " WITH LOGIN PASSWORD '" + RUNTIME_PASS
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS"
            );
            stmt.execute("GRANT ALL PRIVILEGES ON DATABASE " + container.getDatabaseName() + " TO " + MIGRATOR_USER);
            stmt.execute("ALTER SCHEMA public OWNER TO " + MIGRATOR_USER);
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_USER);
        } catch (SQLException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void registerDualClusterProperties(DynamicPropertyRegistry registry) {
        registry.add("moneycraft.datasource.authority.jdbc-url", AUTHORITY_PG::getJdbcUrl);
        registry.add("moneycraft.datasource.authority.username", () -> RUNTIME_USER);
        registry.add("moneycraft.datasource.authority.password", () -> RUNTIME_PASS);
        registry.add("moneycraft.datasource.authority.migrator-username", () -> MIGRATOR_USER);
        registry.add("moneycraft.datasource.authority.migrator-password", () -> MIGRATOR_PASS);

        registry.add("moneycraft.datasource.business.jdbc-url", BUSINESS_PG::getJdbcUrl);
        registry.add("moneycraft.datasource.business.username", () -> RUNTIME_USER);
        registry.add("moneycraft.datasource.business.password", () -> RUNTIME_PASS);
        registry.add("moneycraft.datasource.business.migrator-username", () -> MIGRATOR_USER);
        registry.add("moneycraft.datasource.business.migrator-password", () -> MIGRATOR_PASS);

        registry.add("moneycraft.crypto.master-kek", () -> TEST_KEK);
        registry.add("moneycraft.security.cookie-secure", () -> "false");
        registry.add("moneycraft.base-url", () -> "http://localhost:8080");
        registry.add("moneycraft.webauthn.rp-id", () -> "localhost");
        registry.add("moneycraft.webauthn.allowed-origins[0]", () -> "http://localhost:8080");
        registry.add("management.server.port", () -> "0");
    }
}
