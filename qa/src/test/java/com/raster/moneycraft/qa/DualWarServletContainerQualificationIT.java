package com.raster.moneycraft.qa;

import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gate G4 Dual-WAR External Jakarta EE 11 Servlet Container Qualification Suite.
 *
 * <p>Deploys the exact identical {@code server/target/moneycraft.war} artifact unmodified to:
 * <ol>
 *   <li>Apache Tomcat 11.0.x ({@code tomcat:11.0-jre25-temurin}) at {@code /usr/local/tomcat/webapps/ROOT.war}</li>
 *   <li>WildFly 42.0.0.Final ({@code quay.io/wildfly/wildfly:42.0.0.Final-jdk25}) at
 *       {@code /opt/jboss/wildfly/standalone/deployments/ROOT.war}</li>
 * </ol>
 * connected to real Testcontainers Dual-Cluster PostgreSQL 18.6 ({@code authority} + {@code business}),
 * executing live HTTP setup, company creation, balanced Sales &amp; Receipt voucher posting,
 * Trial Balance verification ({@code isBalanced == true}, {@code differenceAmount == "0.00"}),
 * and {@code MC-AUDIT-2} hash chain verification.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DualWarServletContainerQualificationIT {

    private static final Logger LOG = LoggerFactory.getLogger(DualWarServletContainerQualificationIT.class);

    private static Path warArtifactPath;
    private static String initialWarSha256;
    private static long initialWarByteSize;
    private static ObjectMapper objectMapper;

    private static ContainerQualificationResult tomcatQualification;
    private static ContainerQualificationResult wildflyQualification;

    @BeforeAll
    static void verifySharedWarArtifactAndStructure() throws Exception {
        QaDualClusterTestSupport.configureLocalDockerSocketIfAbsent();
        objectMapper = new ObjectMapper();
        warArtifactPath = resolveSharedWarPath();
        Assertions.assertThat(Files.isRegularFile(warArtifactPath))
            .as("Packaged Jakarta EE 11 WAR artifact must exist at %s", warArtifactPath)
            .isTrue();

        initialWarByteSize = Files.size(warArtifactPath);
        Assertions.assertThat(initialWarByteSize).isGreaterThan(1_000_000L);
        initialWarSha256 = sha256Hex(warArtifactPath);
        Assertions.assertThat(initialWarSha256).matches("^[0-9a-f]{64}$");

        try (ZipFile zip = new ZipFile(warArtifactPath.toFile())) {
            Set<String> entryNames = zip.stream()
                .map(ZipEntry::getName)
                .collect(Collectors.toSet());

            Assertions.assertThat(entryNames)
                .contains(
                    "WEB-INF/web.xml",
                    "WEB-INF/jboss-deployment-structure.xml",
                    "WEB-INF/classes/com/raster/moneycraft/MoneyCraftServletInitializer.class"
                );
            boolean hasProvidedTomcatIsolated = entryNames.stream()
                .anyMatch(name -> name.startsWith("WEB-INF/lib-provided/tomcat-embed-core-"));
            boolean leaksEmbeddedTomcatIntoWebInfLib = entryNames.stream()
                .anyMatch(name -> name.startsWith("WEB-INF/lib/tomcat-embed-core-"));
            Assertions.assertThat(hasProvidedTomcatIsolated).isTrue();
            Assertions.assertThat(leaksEmbeddedTomcatIntoWebInfLib).isFalse();
        }

        LOG.info(
            "Verified shared WAR artifact: path={}, bytes={}, sha256={}",
            warArtifactPath,
            initialWarByteSize,
            initialWarSha256
        );
    }

    @Test
    @Order(1)
    void apacheTomcat11QualifiesMoneyCraftWarEndToEndAgainstDualPostgres18() throws Exception {
        try (
            QaDualClusterTestSupport.NetworkedDualCluster clusters =
                QaDualClusterTestSupport.startNetworkedDualPostgres18();
            GenericContainer<?> tomcat = new GenericContainer<>(QaDualClusterTestSupport.TOMCAT_11_IMAGE)
                .withNetwork(clusters.network())
                .withNetworkAliases("moneycraft-tomcat11")
                .withExposedPorts(8080)
                .withFileSystemBind(
                    warArtifactPath.toAbsolutePath().toString(),
                    "/usr/local/tomcat/webapps/ROOT.war",
                    BindMode.READ_ONLY
                )
                .withEnv(commonWarEnvironment(clusters))
                .withEnv("CATALINA_OPTS", "-Xms512m -Xmx1536m -Djava.awt.headless=true")
                .waitingFor(
                    Wait.forHttp("/api/v1/system/status")
                        .forPort(8080)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(4))
                )
        ) {
            startContainerWithDiagnostics(tomcat, "Apache Tomcat 11");

            Container.ExecResult versionExec = tomcat.execInContainer("catalina.sh", "version");
            String versionBanner = (versionExec.getStdout() + "\n" + versionExec.getStderr()).trim();
            Assertions.assertThat(versionBanner)
                .contains("Server number:  11.0.")
                .contains("JVM Version:    25.");

            String baseUrl = "http://" + tomcat.getHost() + ":" + tomcat.getMappedPort(8080);
            tomcatQualification = runFullErpQualificationWorkflow(
                "Apache Tomcat 11",
                versionBanner,
                baseUrl,
                "TMC11",
                "tomcat11_owner"
            );
            LOG.info(
                "Apache Tomcat 11 qualification PASSED: periodDr={}, closingDr={}, diff={}",
                tomcatQualification.totalPeriodDebit(),
                tomcatQualification.totalClosingDebit(),
                tomcatQualification.differenceAmount()
            );
        }
    }

    @Test
    @Order(2)
    void wildFly42QualifiesIdenticalMoneyCraftWarEndToEndAgainstDualPostgres18() throws Exception {
        try (
            QaDualClusterTestSupport.NetworkedDualCluster clusters =
                QaDualClusterTestSupport.startNetworkedDualPostgres18();
            GenericContainer<?> wildfly = new GenericContainer<>(QaDualClusterTestSupport.WILDFLY_42_IMAGE)
                .withNetwork(clusters.network())
                .withNetworkAliases("moneycraft-wildfly42")
                .withExposedPorts(8080)
                .withFileSystemBind(
                    warArtifactPath.toAbsolutePath().toString(),
                    "/opt/jboss/wildfly/standalone/deployments/ROOT.war",
                    BindMode.READ_ONLY
                )
                .withEnv(commonWarEnvironment(clusters))
                .withEnv(
                    "JAVA_OPTS",
                    "-Xms512m -Xmx1536m -Djava.awt.headless=true -Djboss.as.management.blocking.timeout=600"
                )
                .withCommand(
                    "/bin/sh",
                    "-c",
                    wildfly42ServletBootstrapScript()
                )
                .waitingFor(
                    Wait.forHttp("/api/v1/system/status")
                        .forPort(8080)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(4))
                )
        ) {
            startContainerWithDiagnostics(wildfly, "WildFly 42.0.0.Final");

            Container.ExecResult versionExec = wildfly.execInContainer(
                "/opt/jboss/wildfly/bin/standalone.sh",
                "--version"
            );
            String versionBanner = (versionExec.getStdout() + "\n" + versionExec.getStderr()).trim();
            Assertions.assertThat(versionBanner).contains("WildFly 42.0.0.Final");

            String baseUrl = "http://" + wildfly.getHost() + ":" + wildfly.getMappedPort(8080);
            wildflyQualification = runFullErpQualificationWorkflow(
                "WildFly 42.0.0.Final",
                versionBanner,
                baseUrl,
                "WFL42",
                "wildfly42_owner"
            );
            LOG.info(
                "WildFly 42.0.0.Final qualification PASSED: periodDr={}, closingDr={}, diff={}",
                wildflyQualification.totalPeriodDebit(),
                wildflyQualification.totalClosingDebit(),
                wildflyQualification.differenceAmount()
            );
        }
    }

    @Test
    @Order(3)
    void warArtifactDigestRemainsUnchangedAndBothServletContainersProduceIdenticalTrialBalance() throws Exception {
        Assertions.assertThat(tomcatQualification).isNotNull();
        Assertions.assertThat(wildflyQualification).isNotNull();

        String postQualificationSha256 = sha256Hex(warArtifactPath);
        long postQualificationBytes = Files.size(warArtifactPath);
        Assertions.assertThat(postQualificationSha256).isEqualTo(initialWarSha256);
        Assertions.assertThat(postQualificationBytes).isEqualTo(initialWarByteSize);

        Assertions.assertThat(tomcatQualification.isBalanced()).isTrue();
        Assertions.assertThat(wildflyQualification.isBalanced()).isTrue();
        Assertions.assertThat(new BigDecimal(tomcatQualification.differenceAmount()))
            .isEqualByComparingTo(BigDecimal.ZERO);
        Assertions.assertThat(new BigDecimal(wildflyQualification.differenceAmount()))
            .isEqualByComparingTo(BigDecimal.ZERO);

        Assertions.assertThat(tomcatQualification.differenceAmount())
            .isEqualTo(wildflyQualification.differenceAmount())
            .isEqualTo("0.00");
        Assertions.assertThat(tomcatQualification.totalPeriodDebit())
            .isEqualTo(wildflyQualification.totalPeriodDebit())
            .isEqualTo("168000.00");
        Assertions.assertThat(tomcatQualification.totalPeriodCredit())
            .isEqualTo(wildflyQualification.totalPeriodCredit())
            .isEqualTo("168000.00");
        Assertions.assertThat(tomcatQualification.totalClosingDebit())
            .isEqualTo(wildflyQualification.totalClosingDebit())
            .isEqualTo("118000.00");
        Assertions.assertThat(tomcatQualification.totalClosingCredit())
            .isEqualTo(wildflyQualification.totalClosingCredit())
            .isEqualTo("118000.00");
    }

    @Test
    @Order(4)
    void warArtifactContainsZeroSwingAwtPrintOrDesktopModuleClasses() throws Exception {
        try (ZipFile zip = new ZipFile(warArtifactPath.toFile())) {
            List<String> entryNames = zip.stream()
                .map(ZipEntry::getName)
                .toList();

            Assertions.assertThat(entryNames)
                .as("SWG-002 / DEP-003: moneycraft.war must never package desktop module JARs or classes")
                .noneMatch(name -> name.contains("moneycraft-desktop"))
                .noneMatch(name -> name.startsWith("WEB-INF/classes/com/raster/moneycraft/desktop/"))
                .noneMatch(name -> name.startsWith("javax/swing/"))
                .noneMatch(name -> name.startsWith("java/awt/print/"));

            int scannedServerClasses = 0;
            for (ZipEntry entry : zip.stream().toList()) {
                String name = entry.getName();
                if (!name.startsWith("WEB-INF/classes/com/raster/moneycraft/") || !name.endsWith(".class")) {
                    continue;
                }
                scannedServerClasses++;
                try (InputStream in = zip.getInputStream(entry)) {
                    String constantPoolAscii = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    Assertions.assertThat(constantPoolAscii)
                        .as("Server class %s must not reference javax.swing, java.awt.print, or desktop package", name)
                        .doesNotContain("javax/swing/")
                        .doesNotContain("java/awt/print/")
                        .doesNotContain("com/raster/moneycraft/desktop/");
                }
            }
            Assertions.assertThat(scannedServerClasses).isGreaterThan(50);
        }
    }

    private static ContainerQualificationResult runFullErpQualificationWorkflow(
        String containerLabel,
        String serverVersionBanner,
        String baseUrl,
        String companyCode,
        String ownerUsername
    ) throws Exception {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

        // 1. Pre-bootstrap system status & CSP header check
        HttpResponse<String> statusRes = sendGet(client, baseUrl + "/api/v1/system/status");
        Assertions.assertThat(statusRes.statusCode()).isEqualTo(200);
        JsonNode statusJson = objectMapper.readTree(statusRes.body());
        Assertions.assertThat(statusJson.get("securityState").asString()).isEqualTo("UNINITIALISED");
        Assertions.assertThat(statusJson.get("fenceEpoch").asLong()).isGreaterThanOrEqualTo(1L);
        Assertions.assertThat(statusJson.get("quarantineReason").isNull()).isTrue();

        String cspHeader = statusRes.headers().firstValue("Content-Security-Policy").orElse("");
        Assertions.assertThat(cspHeader)
            .contains("default-src 'self'")
            .contains("font-src 'self'")
            .contains("'nonce-")
            .doesNotContain("https://")
            .doesNotContain("http://");

        // 2. Actuator readiness probe served on external servlet container port (8080)
        HttpResponse<String> readinessRes = sendGet(client, baseUrl + "/actuator/health/readiness");
        Assertions.assertThat(readinessRes.statusCode()).isEqualTo(200);
        Assertions.assertThat(objectMapper.readTree(readinessRes.body()).get("status").asString()).isEqualTo("UP");

        // 3. Initial Owner Bootstrap
        String ownerPassword = "DualWar#2026!Qualify";
        HttpResponse<String> initRes = sendPostPreAuth(
            client,
            baseUrl + "/api/v1/system/initialise",
            """
            {
              "installationLabel": "MoneyCraft %s Qualification Cluster",
              "ownerUsername": "%s",
              "ownerDisplayName": "%s Qualification Owner",
              "password": "%s"
            }
            """.formatted(containerLabel, ownerUsername, containerLabel, ownerPassword)
        );
        Assertions.assertThat(initRes.statusCode())
            .as("%s /api/v1/system/initialise body=%s", containerLabel, initRes.body())
            .isEqualTo(201);

        // 4. Create Company (INR scale 2, Bill-Wise, GST Regular, MCA Edit Log enforced)
        String gstinPrefix = "33AABCM4211D1Z";
        String validGstin = gstinPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(gstinPrefix);
        HttpResponse<String> companyRes = sendPostMutate(
            client,
            cookieManager,
            baseUrl + "/api/v1/companies",
            """
            {
              "code": "%s",
              "legalName": "MoneyCraft %s Qualification Pvt Ltd",
              "stateCode": "33",
              "countryCode": "IN",
              "baseCurrencyCode": "INR",
              "baseCurrencySymbol": "₹",
              "formalCurrencyName": "Indian Rupee",
              "currencyScale": 2,
              "financialYearFrom": "2025-04-01",
              "booksBeginningFrom": "2025-04-01",
              "features": {
                "maintainAccounts": true,
                "billWiseEnabled": true,
                "gstEnabled": true,
                "gstRegistrationType": "REGULAR",
                "gstin": "%s",
                "mcaEditLogEnforced": true
              }
            }
            """.formatted(companyCode, containerLabel, validGstin),
            "war-cmp-" + companyCode
        );
        Assertions.assertThat(companyRes.statusCode())
            .as("%s /api/v1/companies body=%s", containerLabel, companyRes.body())
            .isEqualTo(201);
        UUID companyId = UUID.fromString(objectMapper.readTree(companyRes.body()).get("id").asString());

        // 5. Re-authenticate so SPRING_SESSION refreshes principal grant set for newly created company
        HttpResponse<String> loginRes = sendPostPreAuth(
            client,
            baseUrl + "/api/v1/auth/login",
            """
            {
              "username": "%s",
              "password": "%s"
            }
            """.formatted(ownerUsername, ownerPassword)
        );
        Assertions.assertThat(loginRes.statusCode()).isEqualTo(200);

        // 6. Resolve seeded groups & Cash ledger, then create Sales & Bill-Wise Sundry Debtor ledgers
        UUID cashLedgerId = findLedgerIdByName(client, baseUrl, companyId, "Cash");
        UUID salesGroupId = findGroupIdByName(client, baseUrl, companyId, "Sales Accounts");
        UUID debtorsGroupId = findGroupIdByName(client, baseUrl, companyId, "Sundry Debtors");

        UUID salesLedgerId = createLedger(
            client,
            cookieManager,
            baseUrl,
            companyId,
            "Domestic ERP Sales",
            salesGroupId,
            false
        );
        UUID debtorLedgerId = createLedger(
            client,
            cookieManager,
            baseUrl,
            companyId,
            "Chola Manufacturing Ltd",
            debtorsGroupId,
            true
        );

        // 7. Post balanced Sales voucher (118,000.00 DR Debtor NEW_REF / 118,000.00 CR Sales)
        String salesIdemKey = "war-sales-" + companyCode + "-001";
        HttpResponse<String> salesRes = sendPostMutate(
            client,
            cookieManager,
            baseUrl + "/api/v1/companies/" + companyId + "/vouchers",
            """
            {
              "voucherTypeName": "Sales",
              "voucherDate": "2025-04-14",
              "persistedView": "AS_VOUCHER",
              "narration": "Dual-WAR qualification Sales invoice",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "118000.00",
                  "billAllocations": [
                    {
                      "allocationType": "NEW_REF",
                      "billName": "INV-WAR-001",
                      "amount": "118000.00"
                    }
                  ]
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "118000.00"
                }
              ]
            }
            """.formatted(debtorLedgerId, salesLedgerId),
            salesIdemKey
        );
        Assertions.assertThat(salesRes.statusCode())
            .as("%s Sales voucher body=%s", containerLabel, salesRes.body())
            .isEqualTo(201);
        JsonNode salesJson = objectMapper.readTree(salesRes.body());
        Assertions.assertThat(salesJson.get("status").asString()).isEqualTo("POSTED");
        String salesVoucherId = salesJson.get("id").asString();

        // Replay identical Idempotency-Key with reordered JSON keys on external servlet container
        HttpResponse<String> salesReplayRes = sendPostMutate(
            client,
            cookieManager,
            baseUrl + "/api/v1/companies/" + companyId + "/vouchers",
            """
            {
              "lines": [
                {
                  "billAllocations": [
                    { "amount": "118000.00", "billName": "INV-WAR-001", "allocationType": "NEW_REF" }
                  ],
                  "amount": "118000.00",
                  "side": "DR",
                  "ledgerId": "%s"
                },
                { "amount": "118000.00", "ledgerId": "%s", "side": "CR" }
              ],
              "narration": "Dual-WAR qualification Sales invoice",
              "persistedView": "AS_VOUCHER",
              "voucherDate": "2025-04-14",
              "voucherTypeName": "Sales"
            }
            """.formatted(debtorLedgerId, salesLedgerId),
            salesIdemKey
        );
        Assertions.assertThat(salesReplayRes.statusCode()).isEqualTo(201);
        Assertions.assertThat(salesReplayRes.headers().firstValue("X-Idempotency-Replayed")).contains("true");
        Assertions.assertThat(objectMapper.readTree(salesReplayRes.body()).get("id").asString())
            .isEqualTo(salesVoucherId);

        // 8. Post balanced Receipt voucher (50,000.00 DR Cash / 50,000.00 CR Debtor AGST_REF INV-WAR-001)
        HttpResponse<String> receiptRes = sendPostMutate(
            client,
            cookieManager,
            baseUrl + "/api/v1/companies/" + companyId + "/vouchers",
            """
            {
              "voucherTypeName": "Receipt",
              "voucherDate": "2025-04-20",
              "persistedView": "AS_VOUCHER",
              "narration": "Dual-WAR partial bill settlement receipt",
              "lines": [
                {
                  "ledgerId": "%s",
                  "side": "DR",
                  "amount": "50000.00"
                },
                {
                  "ledgerId": "%s",
                  "side": "CR",
                  "amount": "50000.00",
                  "billAllocations": [
                    {
                      "allocationType": "AGST_REF",
                      "billName": "INV-WAR-001",
                      "amount": "50000.00"
                    }
                  ]
                }
              ]
            }
            """.formatted(cashLedgerId, debtorLedgerId),
            "war-rcpt-" + companyCode + "-001"
        );
        Assertions.assertThat(receiptRes.statusCode())
            .as("%s Receipt voucher body=%s", containerLabel, receiptRes.body())
            .isEqualTo(201);
        Assertions.assertThat(objectMapper.readTree(receiptRes.body()).get("status").asString()).isEqualTo("POSTED");

        // 9. Query Trial Balance and assert exact balance (isBalanced == true, differenceAmount == "0.00")
        HttpResponse<String> tbRes = sendGet(
            client,
            baseUrl + "/api/v1/companies/" + companyId + "/reports/trial-balance"
        );
        Assertions.assertThat(tbRes.statusCode()).isEqualTo(200);
        JsonNode tbJson = objectMapper.readTree(tbRes.body());
        boolean isBalanced = tbJson.get("isBalanced").asBoolean();
        String differenceAmount = tbJson.get("differenceAmount").asString();
        String totalPeriodDebit = tbJson.get("totalPeriodDebit").asString();
        String totalPeriodCredit = tbJson.get("totalPeriodCredit").asString();
        String totalClosingDebit = tbJson.get("totalClosingDebit").asString();
        String totalClosingCredit = tbJson.get("totalClosingCredit").asString();

        Assertions.assertThat(isBalanced).isTrue();
        Assertions.assertThat(differenceAmount).isEqualTo("0.00");
        Assertions.assertThat(totalPeriodDebit).isEqualTo("168000.00");
        Assertions.assertThat(totalPeriodCredit).isEqualTo("168000.00");
        Assertions.assertThat(totalClosingDebit).isEqualTo("118000.00");
        Assertions.assertThat(totalClosingCredit).isEqualTo("118000.00");

        // 10. Verify deterministic PDFBox 3 embedded fonts (Manrope + JetBrainsMono) & glyphs on external WAR
        HttpResponse<byte[]> voucherPdfRes = sendPostPdfBytes(
            client,
            cookieManager,
            baseUrl + "/api/v1/companies/" + companyId + "/vouchers/" + salesVoucherId + "/print-pdf",
            "{}",
            "war-vch-pdf-" + companyCode + "-001"
        );
        Assertions.assertThat(voucherPdfRes.statusCode()).isEqualTo(200);
        verifyPdfBoxBrandInvariants(voucherPdfRes.body(), "INV-WAR-001", "SHA256:", "\u20B9");

        HttpResponse<byte[]> tbPdfRes = sendPostPdfBytes(
            client,
            cookieManager,
            baseUrl + "/api/v1/companies/" + companyId + "/reports/trial-balance/pdf",
            "{}",
            "war-tb-pdf-" + companyCode + "-001"
        );
        Assertions.assertThat(tbPdfRes.statusCode()).isEqualTo(200);
        verifyPdfBoxBrandInvariants(tbPdfRes.body(), "TRIAL BALANCE", "SHA256:", "\u20B9");

        // 11. Verify unbroken MC-AUDIT-2 hash chain on external servlet container
        HttpResponse<String> auditRes = sendGet(client, baseUrl + "/api/v1/admin/audit/verify");
        Assertions.assertThat(auditRes.statusCode()).isEqualTo(200);
        JsonNode auditJson = objectMapper.readTree(auditRes.body());
        Assertions.assertThat(auditJson.get("verified").asBoolean()).isTrue();
        Assertions.assertThat(auditJson.get("checkedEventCount").asLong()).isGreaterThanOrEqualTo(5L);

        return new ContainerQualificationResult(
            containerLabel,
            serverVersionBanner,
            isBalanced,
            differenceAmount,
            totalPeriodDebit,
            totalPeriodCredit,
            totalClosingDebit,
            totalClosingCredit
        );
    }

    private static Map<String, String> commonWarEnvironment(QaDualClusterTestSupport.NetworkedDualCluster clusters) {
        return Map.ofEntries(
            Map.entry("SERVER_PORT", "8080"),
            Map.entry("MANAGEMENT_SERVER_PORT", "8080"),
            Map.entry("MONEYCRAFT_SECURITY_COOKIE_SECURE", "false"),
            Map.entry("MC_BASE_URL", "http://localhost:8080"),
            Map.entry("MC_WEBAUTHN_RP_ID", "localhost"),
            Map.entry("MC_TINK_MASTER_KEK", QaDualClusterTestSupport.TEST_KEK),
            Map.entry("MC_CONNECTOR_SPI_ENABLED", "false"),
            Map.entry("MC_AUTHORITY_JDBC_URL", clusters.internalAuthorityJdbcUrl()),
            Map.entry("MC_AUTHORITY_DB_USER", QaDualClusterTestSupport.RUNTIME_USER),
            Map.entry("MC_AUTHORITY_DB_PASSWORD", QaDualClusterTestSupport.RUNTIME_PASS),
            Map.entry("MC_AUTHORITY_MIGRATOR_USER", QaDualClusterTestSupport.MIGRATOR_USER),
            Map.entry("MC_AUTHORITY_MIGRATOR_PASSWORD", QaDualClusterTestSupport.MIGRATOR_PASS),
            Map.entry("MC_BUSINESS_JDBC_URL", clusters.internalBusinessJdbcUrl()),
            Map.entry("MC_BUSINESS_DB_USER", QaDualClusterTestSupport.RUNTIME_USER),
            Map.entry("MC_BUSINESS_DB_PASSWORD", QaDualClusterTestSupport.RUNTIME_PASS),
            Map.entry("MC_BUSINESS_MIGRATOR_USER", QaDualClusterTestSupport.MIGRATOR_USER),
            Map.entry("MC_BUSINESS_MIGRATOR_PASSWORD", QaDualClusterTestSupport.MIGRATOR_PASS)
        );
    }

    private static String wildfly42ServletBootstrapScript() {
        return """
            set -eu
            WEBSERVER_JAR=$(/opt/java/openjdk/bin/jar tf /opt/jboss/wildfly/standalone/deployments/ROOT.war \
              | grep '^WEB-INF/lib-provided/spring-boot-web-server-.*\\.jar$')
            sed -E \
              -e '/module="org\\.(jboss\\.as\\.(weld|jsf)|wildfly\\.extension\\.(ee-security|microprofile\\.(config|jwt)-smallrye))"/d' \
              -e '/xmlns="urn:(jboss:domain:(weld|jsf|ee-security)|wildfly:microprofile-(config|jwt)-smallrye):/d' \
              /opt/jboss/wildfly/standalone/configuration/standalone.xml \
              > /opt/jboss/wildfly/standalone/configuration/standalone-servlet.xml
            cat > /tmp/jboss-ds-overlay.xml <<EOF
            <?xml version="1.0" encoding="UTF-8"?>
            <jboss-deployment-structure xmlns="urn:jboss:deployment-structure:1.2">
                <deployment>
                    <exclude-subsystems>
                        <subsystem name="jpa"/>
                        <subsystem name="jaxrs"/>
                        <subsystem name="bean-validation"/>
                        <subsystem name="logging"/>
                    </exclude-subsystems>
                    <resources>
                        <resource-root path="${WEBSERVER_JAR}"/>
                    </resources>
                </deployment>
            </jboss-deployment-structure>
            EOF
            /opt/jboss/wildfly/bin/jboss-cli.sh <<CLI
            embed-server --server-config=standalone-servlet.xml
            deployment-overlay add --name=spring-boot4-war --content=WEB-INF/jboss-deployment-structure.xml=/tmp/jboss-ds-overlay.xml --deployments=ROOT.war
            stop-embedded-server
            CLI
            exec /opt/jboss/wildfly/bin/standalone.sh -c standalone-servlet.xml -b 0.0.0.0
            """;
    }

    private static void startContainerWithDiagnostics(GenericContainer<?> container, String label) {
        try {
            container.start();
        } catch (RuntimeException ex) {
            String logs = container.getLogs();
            throw new IllegalStateException(
                label + " failed to deploy moneycraft.war cleanly. Container logs:\n" + logs,
                ex
            );
        }
    }

    private static UUID findGroupIdByName(
        HttpClient client,
        String baseUrl,
        UUID companyId,
        String groupName
    ) throws Exception {
        String cursor = null;
        do {
            String url = baseUrl + "/api/v1/companies/" + companyId + "/account-groups?limit=50"
                + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            HttpResponse<String> res = sendGet(client, url);
            Assertions.assertThat(res.statusCode()).isEqualTo(200);
            JsonNode page = objectMapper.readTree(res.body());
            for (JsonNode item : page.get("items")) {
                if (groupName.equals(item.get("name").asString())) {
                    return UUID.fromString(item.get("id").asString());
                }
            }
            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asString() : null;
        } while (cursor != null);
        throw new IllegalStateException("Seeded account group not found: " + groupName);
    }

    private static UUID findLedgerIdByName(
        HttpClient client,
        String baseUrl,
        UUID companyId,
        String ledgerName
    ) throws Exception {
        String encodedQuery = URLEncoder.encode(ledgerName, StandardCharsets.UTF_8);
        HttpResponse<String> res = sendGet(client, baseUrl + "/api/v1/companies/" + companyId + "/ledgers?q=" + encodedQuery);
        Assertions.assertThat(res.statusCode()).isEqualTo(200);
        for (JsonNode item : objectMapper.readTree(res.body()).get("items")) {
            if (ledgerName.equals(item.get("name").asString())) {
                return UUID.fromString(item.get("id").asString());
            }
        }
        throw new IllegalStateException("Ledger not found: " + ledgerName);
    }

    private static UUID createLedger(
        HttpClient client,
        CookieManager cookieManager,
        String baseUrl,
        UUID companyId,
        String ledgerName,
        UUID groupId,
        boolean billByBill
    ) throws Exception {
        HttpResponse<String> res = sendPostMutate(
            client,
            cookieManager,
            baseUrl + "/api/v1/companies/" + companyId + "/ledgers",
            """
            {
              "name": "%s",
              "groupId": "%s",
              "maintainBalancesBillByBill": %s,
              "openingBalance": "0.00",
              "openingBalanceSide": "DR"
            }
            """.formatted(ledgerName, groupId, billByBill),
            "war-ldg-" + UUID.randomUUID()
        );
        Assertions.assertThat(res.statusCode()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(res.body()).get("id").asString());
    }

    private static HttpResponse<String> sendGet(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> sendPostPreAuth(HttpClient client, String url, String jsonBody)
        throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> sendPostMutate(
        HttpClient client,
        CookieManager cookieManager,
        String url,
        String jsonBody,
        String idempotencyKey
    ) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", extractXsrfToken(cookieManager))
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<byte[]> sendPostPdfBytes(
        HttpClient client,
        CookieManager cookieManager,
        String url,
        String jsonBody,
        String idempotencyKey
    ) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/pdf")
            .header("X-XSRF-TOKEN", extractXsrfToken(cookieManager))
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void verifyPdfBoxBrandInvariants(
        byte[] pdfBytes,
        String expectedToken1,
        String expectedToken2,
        String expectedToken3
    ) throws Exception {
        Assertions.assertThat(pdfBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Assertions.assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            Set<String> embeddedFonts = new HashSet<>();
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                for (COSName fontName : resources.getFontNames()) {
                    PDFont font = resources.getFont(fontName);
                    Assertions.assertThat(font.isEmbedded()).isTrue();
                    embeddedFonts.add(font.getName());
                }
            }
            Assertions.assertThat(embeddedFonts.stream().anyMatch(n -> n.contains("Manrope"))).isTrue();
            Assertions.assertThat(embeddedFonts.stream().anyMatch(n -> n.contains("JetBrainsMono"))).isTrue();
            String extracted = new PDFTextStripper().getText(doc);
            Assertions.assertThat(extracted)
                .contains(expectedToken1)
                .contains(expectedToken2)
                .contains(expectedToken3);
        }
    }

    private static String extractXsrfToken(CookieManager cookieManager) {
        for (HttpCookie cookie : cookieManager.getCookieStore().getCookies()) {
            if ("XSRF-TOKEN".equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Missing XSRF-TOKEN cookie in CookieManager store");
    }

    private static Path resolveSharedWarPath() {
        Path fromQaModule = Path.of("..", "server", "target", "moneycraft.war").normalize().toAbsolutePath();
        if (Files.isRegularFile(fromQaModule)) {
            return fromQaModule;
        }
        Path fromRepoRoot = Path.of("server", "target", "moneycraft.war").normalize().toAbsolutePath();
        if (Files.isRegularFile(fromRepoRoot)) {
            return fromRepoRoot;
        }
        return fromQaModule;
    }

    private static String sha256Hex(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(path)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to hash WAR artifact: " + path, ex);
        }
        return QaDualClusterTestSupport.toLowerHex(digest.digest());
    }

    private record ContainerQualificationResult(
        String containerName,
        String serverVersionBanner,
        boolean isBalanced,
        String differenceAmount,
        String totalPeriodDebit,
        String totalPeriodCredit,
        String totalClosingDebit,
        String totalClosingCredit
    ) {
    }
}
