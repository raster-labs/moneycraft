package com.raster.moneycraft.qa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.TestcontainersConfiguration;

/**
 * Shared Testcontainers PostgreSQL 18.6 Dual-Cluster (Protected Authority + Business Books)
 * provisioning utility for MoneyCraft Gate G2, G3, and G4 Failsafe integration suites.
 */
public final class QaDualClusterTestSupport {

    public static final String POSTGRES_IMAGE = "postgres:18.6-bookworm";
    public static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.11.0";
    public static final String TOMCAT_11_IMAGE = "tomcat:11.0-jre25-temurin";
    public static final String WILDFLY_42_IMAGE = "quay.io/wildfly/wildfly:42.0.0.Final-jdk25";

    public static final String AUTHORITY_ALIAS = "authority-pg";
    public static final String BUSINESS_ALIAS = "business-pg";

    public static final String AUTHORITY_DB_NAME = "moneycraft_authority";
    public static final String BUSINESS_DB_NAME = "moneycraft_business";

    public static final String SUPERUSER = "postgres";
    public static final String SUPERUSER_PASS = "postgres";
    public static final String MIGRATOR_USER = "mc_flyway_migrator";
    public static final String MIGRATOR_PASS = "MigratorQa#2026";
    public static final String RUNTIME_USER = "mc_app_runtime";
    public static final String RUNTIME_PASS = "RuntimeQa#2026";
    public static final String TEST_KEK = "0123456789abcdef0123456789abcdef";

    private QaDualClusterTestSupport() {
    }

    public static void configureLocalDockerSocketIfAbsent() {
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

    public static NetworkedDualCluster startNetworkedDualPostgres18() {
        configureLocalDockerSocketIfAbsent();
        Network network = Network.newNetwork();
        PostgreSQLContainer authorityPg = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases(AUTHORITY_ALIAS)
            .withDatabaseName(AUTHORITY_DB_NAME)
            .withUsername(SUPERUSER)
            .withPassword(SUPERUSER_PASS);
        PostgreSQLContainer businessPg = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases(BUSINESS_ALIAS)
            .withDatabaseName(BUSINESS_DB_NAME)
            .withUsername(SUPERUSER)
            .withPassword(SUPERUSER_PASS);

        authorityPg.start();
        businessPg.start();

        provisionRolesAndExtensions(authorityPg, false);
        provisionRolesAndExtensions(businessPg, true);

        return new NetworkedDualCluster(network, authorityPg, businessPg);
    }

    public static String toLowerHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
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
            stmt.execute(
                "GRANT ALL PRIVILEGES ON DATABASE " + container.getDatabaseName() + " TO " + MIGRATOR_USER
            );
            stmt.execute("ALTER SCHEMA public OWNER TO " + MIGRATOR_USER);
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_USER);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to provision PostgreSQL 18 QA roles and extensions", ex);
        }
    }

    public record NetworkedDualCluster(
        Network network,
        PostgreSQLContainer authorityPg,
        PostgreSQLContainer businessPg
    ) implements AutoCloseable {

        public String internalAuthorityJdbcUrl() {
            return "jdbc:postgresql://" + AUTHORITY_ALIAS + ":5432/" + AUTHORITY_DB_NAME;
        }

        public String internalBusinessJdbcUrl() {
            return "jdbc:postgresql://" + BUSINESS_ALIAS + ":5432/" + BUSINESS_DB_NAME;
        }

        @Override
        public void close() {
            try {
                businessPg.stop();
            } finally {
                try {
                    authorityPg.stop();
                } finally {
                    network.close();
                }
            }
        }
    }
}
