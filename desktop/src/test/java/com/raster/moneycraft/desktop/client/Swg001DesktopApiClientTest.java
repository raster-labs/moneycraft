package com.raster.moneycraft.desktop.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raster.moneycraft.desktop.client.dto.ApproveVoucherCommand;
import com.raster.moneycraft.desktop.client.dto.BalanceSheetReportDto;
import com.raster.moneycraft.desktop.client.dto.CoaTreeDto;
import com.raster.moneycraft.desktop.client.dto.CompanyDto;
import com.raster.moneycraft.desktop.client.dto.CreateLedgerCommand;
import com.raster.moneycraft.desktop.client.dto.CursorPageDto;
import com.raster.moneycraft.desktop.client.dto.LedgerDto;
import com.raster.moneycraft.desktop.client.dto.SystemStatusDto;
import com.raster.moneycraft.desktop.client.dto.TrialBalanceReportDto;
import com.raster.moneycraft.desktop.client.dto.UpsertVoucherCommand;
import com.raster.moneycraft.desktop.client.dto.VoucherDto;
import com.raster.moneycraft.desktop.client.dto.VoucherLineCommand;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.X509TrustManager;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Swg001DesktopApiClientTest {

    private HttpServer mockServer;
    private URI baseUri;
    private final List<String> receivedIdempotencyKeys = new ArrayList<>();
    private final AtomicBoolean dropNextVoucherPost = new AtomicBoolean(false);
    private final AtomicInteger committedVoucherCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = mockServer.getAddress().getPort();
        baseUri = URI.create("http://127.0.0.1:" + port);
        registerContractRoutes();
        mockServer.start();
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void at09Swg001A_executesAllContractsOffEdtMarshalsToSwingEdtAndPreservesExactBigDecimal() throws Exception {
        UUID companyId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID groupId = UUID.fromString("22222222-3333-4444-5555-666666666666");
        UUID debitLedgerId = UUID.fromString("33333333-4444-5555-6666-777777777777");
        UUID creditLedgerId = UUID.fromString("44444444-5555-6666-7777-888888888888");
        UUID voucherTypeId = UUID.fromString("55555555-6666-7777-8888-999999999999");

        try (DesktopApiClient client = new DesktopApiClient(
            DesktopClientConfig.loopbackTestConfig(baseUri),
            () -> Optional.of("eyJhbGciOiJSUzI1NiJ9.person-token")
        )) {
            SystemStatusDto status = client.getSystemStatus().get(5, TimeUnit.SECONDS);
            assertEquals("ACTIVE", status.securityState());
            assertEquals(7L, status.fenceEpoch());
            assertTrue(client.wasLastCallExecutedOffEdt());

            CountDownLatch edtLatch = new CountDownLatch(1);
            AtomicBoolean callbackRanOnEdt = new AtomicBoolean(false);
            AtomicReference<List<CompanyDto>> companiesRef = new AtomicReference<>();
            DesktopApiClient.deliverOnEdt(
                client.listCompanies(),
                companies -> {
                    callbackRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                    companiesRef.set(companies);
                    edtLatch.countDown();
                },
                err -> edtLatch.countDown()
            );
            assertTrue(edtLatch.await(5, TimeUnit.SECONDS));
            assertTrue(callbackRanOnEdt.get());
            assertEquals(1, companiesRef.get().size());
            assertEquals("\u20B9", companiesRef.get().get(0).currencySymbol());

            CoaTreeDto coa = client.getChartOfAccountsTree(companyId).get(5, TimeUnit.SECONDS);
            assertEquals(28, coa.totalGroups());
            assertEquals(new BigDecimal("1234567890.1234"), coa.totalOpeningDebit());

            CursorPageDto<LedgerDto> ledgerPage = client.listLedgers(companyId, "Sales", null, 25)
                .get(5, TimeUnit.SECONDS);
            assertEquals(1, ledgerPage.items().size());
            assertEquals(new BigDecimal("50000.75"), ledgerPage.items().get(0).openingBalance());

            VersionedResponse<LedgerDto> createdLedger = client.createLedger(
                companyId,
                new CreateLedgerCommand(
                    "Domestic Trade Receivables",
                    "DTR-01",
                    groupId,
                    new BigDecimal("2500.50"),
                    "DR",
                    "INR",
                    "29AABCR1234F1Z5"
                ),
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            ).get(5, TimeUnit.SECONDS);
            assertEquals(201, createdLedger.statusCode());
            assertEquals("\"v1\"", createdLedger.etag());

            UpsertVoucherCommand voucherCmd = new UpsertVoucherCommand(
                voucherTypeId,
                "Journal",
                "JRN-0001",
                LocalDate.of(2026, 4, 15),
                "ACCOUNTING_VOUCHER",
                "NORMAL",
                "SAVE_DRAFT",
                "INR",
                new BigDecimal("1.000000"),
                "Adjustment entry",
                null,
                List.of(
                    new VoucherLineCommand(debitLedgerId, "DR", new BigDecimal("98765432.19"), "Debit leg"),
                    new VoucherLineCommand(creditLedgerId, "CR", new BigDecimal("98765432.19"), "Credit leg")
                )
            );
            VersionedResponse<VoucherDto> createdVoucher = client.createVoucher(
                companyId,
                voucherCmd,
                UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")
            ).get(5, TimeUnit.SECONDS);
            assertEquals(new BigDecimal("98765432.19"), createdVoucher.body().totalDebit());
            assertEquals("\"v1\"", createdVoucher.etag());

            VersionedResponse<VoucherDto> approved = client.approveVoucher(
                companyId,
                createdVoucher.body().id(),
                new ApproveVoucherCommand(Boolean.FALSE, "Checked"),
                createdVoucher.etag(),
                UUID.randomUUID()
            ).get(5, TimeUnit.SECONDS);
            assertEquals("APPROVED", approved.body().status());

            VersionedResponse<VoucherDto> posted = client.postVoucher(
                companyId,
                createdVoucher.body().id(),
                approved.etag(),
                UUID.randomUUID()
            ).get(5, TimeUnit.SECONDS);
            assertEquals("POSTED", posted.body().status());

            TrialBalanceReportDto tb = client.getTrialBalance(
                companyId,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                false
            ).get(5, TimeUnit.SECONDS);
            assertTrue(tb.isBalanced());
            assertEquals(new BigDecimal("0.00"), tb.differenceAmount());

            BalanceSheetReportDto bs = client.getBalanceSheet(
                companyId,
                LocalDate.of(2026, 4, 30),
                "VERTICAL",
                false
            ).get(5, TimeUnit.SECONDS);
            assertTrue(bs.isBalanced());
            assertEquals(new BigDecimal("98765432.19"), bs.totalAssets());

            byte[] pdfBytes = client.streamReportPdf(
                companyId,
                "trial-balance",
                Map.of("fromDate", "2026-04-01", "toDate", "2026-04-30"),
                UUID.randomUUID()
            ).get(5, TimeUnit.SECONDS);
            assertArrayEquals("%PDF-1.7\n%MoneyCraft-Deterministic-Statement\n%%EOF".getBytes(StandardCharsets.UTF_8), pdfBytes);
        }
    }

    @Test
    void at09Swg001B_enforcesStaleIfMatchConflictPermissionDenialAndUncertainMutationSameKeyRetry() throws Exception {
        UUID companyId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID voucherId = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");
        UUID ledgerA = UUID.fromString("33333333-4444-5555-6666-777777777777");
        UUID ledgerB = UUID.fromString("44444444-5555-6666-7777-888888888888");
        UpsertVoucherCommand cmd = new UpsertVoucherCommand(
            UUID.fromString("55555555-6666-7777-8888-999999999999"),
            "Journal",
            "JRN-0009",
            LocalDate.of(2026, 4, 16),
            "ACCOUNTING_VOUCHER",
            "NORMAL",
            "SAVE_DRAFT",
            "INR",
            BigDecimal.ONE,
            "Optimistic concurrency test",
            1L,
            List.of(
                new VoucherLineCommand(ledgerA, "DR", new BigDecimal("1500.00"), null),
                new VoucherLineCommand(ledgerB, "CR", new BigDecimal("1500.00"), null)
            )
        );

        try (DesktopApiClient client = new DesktopApiClient(
            DesktopClientConfig.loopbackTestConfig(baseUri),
            () -> Optional.of("eyJhbGciOiJSUzI1NiJ9.person-token")
        )) {
            // 1. Stale ETag -> 412 Precondition Failed
            ExecutionException staleEx = assertThrows(
                ExecutionException.class,
                () -> client.updateVoucher(companyId, voucherId, cmd, "\"v0-stale\"", UUID.randomUUID())
                    .get(5, TimeUnit.SECONDS)
            );
            ApiProblemException staleProblem = (ApiProblemException) staleEx.getCause();
            assertTrue(staleProblem.isStaleOptimisticLock());
            assertEquals("MC-OPTIMISTIC-LOCK-CONFLICT", staleProblem.code());

            // 2. Unauthorized company -> 403 Forbidden
            UUID deniedCompanyId = UUID.fromString("99999999-9999-9999-9999-999999999999");
            ExecutionException deniedEx = assertThrows(
                ExecutionException.class,
                () -> client.getTrialBalance(deniedCompanyId, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), false)
                    .get(5, TimeUnit.SECONDS)
            );
            ApiProblemException deniedProblem = (ApiProblemException) deniedEx.getCause();
            assertTrue(deniedProblem.isForbidden());

            // 3. Dropped response mid-mutation -> UncertainTransportException -> same Idempotency-Key retry
            dropNextVoucherPost.set(true);
            UUID intentionId = UUID.fromString("12121212-3434-5656-7878-909090909090");
            ExecutionException dropEx = assertThrows(
                ExecutionException.class,
                () -> client.createVoucher(companyId, cmd, intentionId).get(5, TimeUnit.SECONDS)
            );
            UncertainTransportException uncertain = (UncertainTransportException) dropEx.getCause();
            MutationIntention savedIntention = uncertain.intention();
            assertNotNull(savedIntention);
            assertEquals(1, client.unresolvedMutations().size());

            VersionedResponse<VoucherDto> recovered = client
                .retryUncertainMutation(savedIntention, VoucherDto.class)
                .get(5, TimeUnit.SECONDS);
            assertEquals(201, recovered.statusCode());
            assertTrue(client.unresolvedMutations().isEmpty());
            assertEquals(1, committedVoucherCount.get());
            long distinctKeys = receivedIdempotencyKeys.stream().distinct().count();
            assertEquals(1L, distinctKeys);
        }
    }

    @Test
    void at09Swg001C_rejectsDatabaseConfigServiceApiKeysTrustAllTlsAndContainsZeroJdbcClasses() throws Exception {
        assertThrows(
            IllegalArgumentException.class,
            () -> DesktopClientConfig.httpsDefault(URI.create("jdbc:postgresql://127.0.0.1:5432/moneycraft"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DesktopClientConfig.httpsDefault(URI.create("https://dbadmin:secret@erp.example.internal/api"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DesktopClientConfig.httpsDefault(URI.create("http://erp.example.internal"))
        );

        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        assertThrows(IllegalArgumentException.class, () -> DesktopClientConfig.assertNotTrustAll(trustAll));

        try (DesktopApiClient serviceKeyClient = new DesktopApiClient(
            DesktopClientConfig.loopbackTestConfig(baseUri),
            () -> Optional.of("mc_sk_live_forbiddenServiceKey")
        )) {
            CompletableFuture<List<CompanyDto>> fut = serviceKeyClient.listCompanies();
            ExecutionException ex = assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
            assertTrue(ex.getCause().getMessage().contains("SWG-002 violation"));
        }

        // Verify zero JDBC drivers, Spring Data/JPA, or persistence runtimes on desktop classpath
        for (String forbiddenClass : List.of(
            "org.postgresql.Driver",
            "org.springframework.jdbc.core.JdbcTemplate",
            "jakarta.persistence.EntityManager"
        )) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(forbiddenClass));
        }

        Path desktopPom = resolveDesktopPomPath();
        String pomXml = Files.readString(desktopPom, StandardCharsets.UTF_8);
        assertTrue(pomXml.contains("<artifactId>jackson-databind</artifactId>"));
        assertFalse(pomXml.contains("postgresql"));
        assertFalse(pomXml.contains("spring-boot-starter-data-jpa"));
        assertFalse(pomXml.contains("spring-boot-starter-jdbc"));
    }

    private static Path resolveDesktopPomPath() throws IOException {
        Path direct = Path.of("pom.xml");
        if (Files.exists(direct)
            && Files.readString(direct, StandardCharsets.UTF_8).contains("<artifactId>moneycraft-desktop</artifactId>")) {
            return direct;
        }
        return Path.of("desktop", "pom.xml");
    }

    private void registerContractRoutes() {
        mockServer.createContext("/api/v1/system/status", exchange ->
            writeJson(exchange, 200, "{\"securityState\":\"ACTIVE\",\"fenceEpoch\":7,\"version\":\"1.0.0\",\"quarantineReason\":null}", null)
        );
        mockServer.createContext("/api/v1/companies", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("99999999-9999-9999-9999-999999999999")) {
                writeJson(
                    exchange,
                    403,
                    "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"No grant\",\"code\":\"MC-ACCESS-DENIED\",\"errorCode\":\"MC-ACCESS-DENIED\"}",
                    null
                );
                return;
            }
            if ("/api/v1/companies".equals(path)) {
                writeJson(
                    exchange,
                    200,
                    "[{\"id\":\"11111111-2222-3333-4444-555555555555\",\"code\":\"RASTER\",\"legalName\":\"Raster Images Pvt. Ltd.\",\"mailingName\":\"Raster Images\",\"countryCode\":\"IN\",\"stateCode\":\"29\",\"baseCurrencyCode\":\"INR\",\"currencySymbol\":\"\\u20B9\",\"currencyScale\":2,\"financialYearFrom\":\"2026-04-01\",\"booksBeginningFrom\":\"2026-04-01\",\"versionNo\":1}]",
                    null
                );
                return;
            }
            if (path.endsWith("/chart-of-accounts/tree")) {
                writeJson(
                    exchange,
                    200,
                    "{\"nodes\":[],\"totalGroups\":28,\"totalLedgers\":2,\"totalOpeningDebit\":\"1234567890.1234\",\"totalOpeningCredit\":\"1234567890.1234\",\"openingDifference\":\"0.0000\"}",
                    null
                );
                return;
            }
            if (path.endsWith("/ledgers")) {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(
                        exchange,
                        201,
                        "{\"id\":\"66666666-7777-8888-9999-000000000000\",\"companyId\":\"11111111-2222-3333-4444-555555555555\",\"name\":\"Domestic Trade Receivables\",\"aliasCode\":\"DTR-01\",\"groupId\":\"22222222-3333-4444-5555-666666666666\",\"groupName\":\"Sundry Debtors\",\"nature\":\"ASSETS\",\"openingBalance\":\"2500.50\",\"openingSide\":\"DR\",\"currencyCode\":\"INR\",\"taxType\":\"NONE\",\"gstin\":\"29AABCR1234F1Z5\",\"isSystemLedger\":false,\"versionNo\":1}",
                        "\"v1\""
                    );
                } else {
                    writeJson(
                        exchange,
                        200,
                        "{\"items\":[{\"id\":\"66666666-7777-8888-9999-000000000000\",\"companyId\":\"11111111-2222-3333-4444-555555555555\",\"name\":\"Sales Account\",\"aliasCode\":null,\"groupId\":null,\"groupName\":\"Sales Accounts\",\"nature\":\"INCOME\",\"openingBalance\":\"50000.75\",\"openingSide\":\"CR\",\"currencyCode\":\"INR\",\"taxType\":\"NONE\",\"gstin\":null,\"isSystemLedger\":false,\"versionNo\":1}],\"nextCursor\":null,\"hasMore\":false}",
                        null
                    );
                }
                return;
            }
            if (path.endsWith("/vouchers")) {
                String idemKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (idemKey != null) {
                    synchronized (receivedIdempotencyKeys) {
                        if (idemKey.contains("12121212-3434-5656-7878-909090909090")) {
                            receivedIdempotencyKeys.add(idemKey);
                        }
                    }
                }
                if (dropNextVoucherPost.compareAndSet(true, false)) {
                    committedVoucherCount.incrementAndGet();
                    exchange.close();
                    return;
                }
                if (idemKey != null && !idemKey.contains("12121212-3434-5656-7878-909090909090")) {
                    // First test's voucher creation
                    writeJson(exchange, 201, sampleVoucherJson("DRAFT", "98765432.19", 1L), "\"v1\"");
                    return;
                }
                writeJson(exchange, 201, sampleVoucherJson("DRAFT", "1500.00", 1L), "\"v1\"");
                return;
            }
            if (path.contains("/vouchers/") && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
                if (!"\"v1\"".equals(ifMatch)) {
                    writeJson(
                        exchange,
                        412,
                        "{\"type\":\"about:blank\",\"title\":\"Precondition Failed\",\"status\":412,\"detail\":\"Stale ETag\",\"code\":\"MC-OPTIMISTIC-LOCK-CONFLICT\",\"errorCode\":\"MC-OPTIMISTIC-LOCK-CONFLICT\"}",
                        null
                    );
                    return;
                }
                writeJson(exchange, 200, sampleVoucherJson("DRAFT", "1500.00", 2L), "\"v2\"");
                return;
            }
            if (path.endsWith("/approve")) {
                writeJson(exchange, 200, sampleVoucherJson("APPROVED", "98765432.19", 2L), "\"v2\"");
                return;
            }
            if (path.endsWith("/post")) {
                writeJson(exchange, 200, sampleVoucherJson("POSTED", "98765432.19", 3L), "\"v3\"");
                return;
            }
            if (path.endsWith("/reports/trial-balance")) {
                writeJson(
                    exchange,
                    200,
                    "{\"fromDate\":\"2026-04-01\",\"toDate\":\"2026-04-30\",\"includeOptional\":false,\"groupRows\":[],\"ledgerRows\":[],\"totalOpeningDebit\":\"0.00\",\"totalOpeningCredit\":\"0.00\",\"totalPeriodDebit\":\"98765432.19\",\"totalPeriodCredit\":\"98765432.19\",\"totalClosingDebit\":\"98765432.19\",\"totalClosingCredit\":\"98765432.19\",\"differenceAmount\":\"0.00\",\"isBalanced\":true}",
                    null
                );
                return;
            }
            if (path.endsWith("/reports/balance-sheet")) {
                writeJson(
                    exchange,
                    200,
                    "{\"asOfDate\":\"2026-04-30\",\"format\":\"VERTICAL\",\"totalLiabilities\":\"98765432.19\",\"totalAssets\":\"98765432.19\",\"differenceAmount\":\"0.00\",\"isBalanced\":true}",
                    null
                );
                return;
            }
            if (path.endsWith("/pdf")) {
                byte[] pdf = "%PDF-1.7\n%MoneyCraft-Deterministic-Statement\n%%EOF".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/pdf");
                exchange.sendResponseHeaders(200, pdf.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(pdf);
                }
                exchange.close();
            }
        });
    }

    private static String sampleVoucherJson(String status, String amount, long version) {
        return "{\"id\":\"77777777-8888-9999-aaaa-bbbbbbbbbbbb\",\"companyId\":\"11111111-2222-3333-4444-555555555555\","
            + "\"periodId\":\"88888888-1111-2222-3333-444444444444\",\"fiscalYearId\":\"88888888-2222-3333-4444-555555555555\","
            + "\"voucherTypeId\":\"55555555-6666-7777-8888-999999999999\",\"voucherTypeName\":\"Journal\","
            + "\"voucherTypeCategory\":\"JOURNAL\",\"status\":\"" + status + "\",\"postingNature\":\"NORMAL\","
            + "\"persistedView\":\"ACCOUNTING_VOUCHER\",\"approvalPolicy\":\"FOUR_EYES\",\"voucherNumber\":\"JRN-0001\","
            + "\"voucherDate\":\"2026-04-15\",\"effectiveDate\":\"2026-04-15\",\"currencyCode\":\"INR\","
            + "\"exchangeRate\":\"1.000000\",\"totalDebit\":\"" + amount + "\",\"totalCredit\":\"" + amount + "\","
            + "\"narration\":\"Adjustment entry\",\"versionNo\":" + version + ",\"lines\":[]}";
    }

    private static void writeJson(HttpExchange exchange, int status, String json, String etag) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (etag != null) {
            exchange.getResponseHeaders().set("ETag", etag);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }
}
