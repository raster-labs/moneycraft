package com.raster.moneycraft.desktop.client;

import com.raster.moneycraft.desktop.client.dto.AccountBookReportDto;
import com.raster.moneycraft.desktop.client.dto.ApproveVoucherCommand;
import com.raster.moneycraft.desktop.client.dto.BalanceSheetReportDto;
import com.raster.moneycraft.desktop.client.dto.CoaTreeDto;
import com.raster.moneycraft.desktop.client.dto.CompanyDto;
import com.raster.moneycraft.desktop.client.dto.CreateCompanyCommand;
import com.raster.moneycraft.desktop.client.dto.CreateLedgerCommand;
import com.raster.moneycraft.desktop.client.dto.CursorPageDto;
import com.raster.moneycraft.desktop.client.dto.LedgerDto;
import com.raster.moneycraft.desktop.client.dto.ProfitAndLossReportDto;
import com.raster.moneycraft.desktop.client.dto.SystemStatusDto;
import com.raster.moneycraft.desktop.client.dto.TrialBalanceReportDto;
import com.raster.moneycraft.desktop.client.dto.UpsertVoucherCommand;
import com.raster.moneycraft.desktop.client.dto.VoucherDto;
import com.raster.moneycraft.desktop.client.dto.VoucherSummaryDto;
import com.raster.moneycraft.desktop.preferences.UiPreferencesEnvelopeDto;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * Non-blocking Java 25 {@link HttpClient} transport for the MoneyCraft 1.0.0 desktop client (SWG-001).
 * Executes all serialization and network operations off the Swing Event Dispatch Thread and marshals
 * UI callbacks onto {@link SwingUtilities#invokeLater(Runnable)}.
 */
public final class DesktopApiClient implements AutoCloseable {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final DesktopClientConfig config;
    private final DesktopJcsCanonicalizer canonicalizer;
    private final Supplier<Optional<String>> accessTokenSupplier;
    private final ExecutorService ioExecutor;
    private final HttpClient httpClient;
    private final Map<UUID, MutationIntention> unresolvedMutations = new ConcurrentHashMap<>();
    private final AtomicBoolean lastCallExecutedOffEdt = new AtomicBoolean(false);

    public DesktopApiClient(
        DesktopClientConfig config,
        Supplier<Optional<String>> accessTokenSupplier
    ) {
        this(config, new DesktopJcsCanonicalizer(), accessTokenSupplier);
    }

    public DesktopApiClient(
        DesktopClientConfig config,
        DesktopJcsCanonicalizer canonicalizer,
        Supplier<Optional<String>> accessTokenSupplier
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer must not be null");
        this.accessTokenSupplier = Objects.requireNonNull(accessTokenSupplier, "accessTokenSupplier must not be null");
        this.ioExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("mc-desktop-io-", 0).factory()
        );

        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(ioExecutor);
        if (config.sslContext() != null) {
            builder.sslContext(config.sslContext());
        }
        if ("https".equalsIgnoreCase(config.baseApiUri().getScheme())) {
            builder.sslParameters(config.verifiedSslParameters());
        }
        this.httpClient = builder.build();
    }

    public DesktopClientConfig config() {
        return config;
    }

    public DesktopJcsCanonicalizer canonicalizer() {
        return canonicalizer;
    }

    public boolean wasLastCallExecutedOffEdt() {
        return lastCallExecutedOffEdt.get();
    }

    public Map<UUID, MutationIntention> unresolvedMutations() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(unresolvedMutations));
    }

    public void clearTransientStateOnLogout() {
        unresolvedMutations.clear();
    }

    /**
     * Marshals completion of an asynchronous background API operation onto the Swing EDT.
     */
    public static <T> void deliverOnEdt(
        CompletableFuture<T> future,
        Consumer<T> onSuccess,
        Consumer<Throwable> onError
    ) {
        Objects.requireNonNull(future, "future must not be null");
        Objects.requireNonNull(onSuccess, "onSuccess must not be null");
        Objects.requireNonNull(onError, "onError must not be null");
        future.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                Throwable unwrapped = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
                onError.accept(unwrapped);
            } else {
                onSuccess.accept(result);
            }
        }));
    }

    public CompletableFuture<SystemStatusDto> getSystemStatus() {
        return sendGetJson("/api/v1/system/status", false, new TypeReference<>() {
        });
    }

    public CompletableFuture<List<CompanyDto>> listCompanies() {
        return sendGetJson("/api/v1/companies", true, new TypeReference<>() {
        });
    }

    /**
     * Delivers an async completion onto the Swing EDT only if {@link ContextEpochGuard} confirms
     * the originating window, authority, company, and view context is still current (S-04, AT-ITER110-027).
     */
    public static <T> void deliverOnEdtIfCurrent(
        ContextEpochGuard epochGuard,
        ContextEpochGuard.EpochSnapshot expectedSnapshot,
        CompletableFuture<T> future,
        Consumer<T> onSuccess,
        Consumer<Throwable> onError,
        Runnable onStaleDiscarded
    ) {
        Objects.requireNonNull(epochGuard, "epochGuard must not be null");
        Objects.requireNonNull(expectedSnapshot, "expectedSnapshot must not be null");
        deliverOnEdt(
            future,
            result -> {
                if (epochGuard.isCurrent(expectedSnapshot)) {
                    onSuccess.accept(result);
                } else if (onStaleDiscarded != null) {
                    onStaleDiscarded.run();
                }
            },
            err -> {
                if (epochGuard.isCurrent(expectedSnapshot)) {
                    onError.accept(err);
                } else if (onStaleDiscarded != null) {
                    onStaleDiscarded.run();
                }
            }
        );
    }

    public CompletableFuture<VersionedResponse<CompanyDto>> createCompany(
        CreateCompanyCommand command,
        UUID intentionId
    ) {
        Objects.requireNonNull(command, "command must not be null");
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("company.create", resolvedIntention, command);
        return sendMutationJson(
            resolvedIntention,
            "company.create",
            "POST",
            "/api/v1/companies",
            command,
            idempotencyKey,
            null,
            CompanyDto.class
        );
    }

    public CompletableFuture<VersionedResponse<CompanyDto>> updateCompanyFeatures(
        UUID companyId,
        CreateCompanyCommand command,
        String ifMatchEtag,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (ifMatchEtag == null || ifMatchEtag.isBlank()) {
            throw new IllegalArgumentException("If-Match ETag is required when updating F11 Company Features.");
        }
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("company.alter", resolvedIntention, command);
        return sendMutationJson(
            resolvedIntention,
            "company.alter",
            "PUT",
            "/api/v1/companies/" + companyId,
            command,
            idempotencyKey,
            ifMatchEtag,
            CompanyDto.class
        );
    }

    public CompletableFuture<VersionedResponse<UiPreferencesEnvelopeDto>> getAccountUiPreferences() {
        return CompletableFuture.supplyAsync(() -> {
            recordOffEdtExecution();
            URI uri = resolvePath("/api/v1/me/ui-preferences");
            HttpRequest req = baseRequest(uri, true).GET().build();
            return executeJsonVersioned(req, UiPreferencesEnvelopeDto.class);
        }, ioExecutor);
    }

    public CompletableFuture<VersionedResponse<UiPreferencesEnvelopeDto>> patchAccountUiPreferences(
        UiPreferencesEnvelopeDto.PatchRequest patchRequest,
        String ifMatchEtag,
        UUID idempotencyUuid
    ) {
        Objects.requireNonNull(patchRequest, "patchRequest must not be null");
        Objects.requireNonNull(idempotencyUuid, "idempotencyUuid must not be null");
        if (ifMatchEtag == null || ifMatchEtag.isBlank()) {
            throw new IllegalArgumentException("If-Match ETag is required for PATCH /api/v1/me/ui-preferences.");
        }
        return sendMutationJson(
            idempotencyUuid,
            "preferences.update",
            "PATCH",
            "/api/v1/me/ui-preferences",
            patchRequest,
            idempotencyUuid.toString(),
            ifMatchEtag,
            UiPreferencesEnvelopeDto.class
        );
    }

    public CompletableFuture<CoaTreeDto> getChartOfAccountsTree(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/chart-of-accounts/tree",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<CursorPageDto<LedgerDto>> listLedgers(
        UUID companyId,
        String query,
        String cursor,
        Integer limit
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            params.put("q", query);
        }
        if (cursor != null && !cursor.isBlank()) {
            params.put("cursor", cursor);
        }
        if (limit != null) {
            params.put("limit", limit.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/ledgers", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<LedgerDto>> createLedger(
        UUID companyId,
        CreateLedgerCommand command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("ledger.create", resolvedIntention, command);
        return sendMutationJson(
            resolvedIntention,
            "ledger.create",
            "POST",
            "/api/v1/companies/" + companyId + "/ledgers",
            command,
            idempotencyKey,
            null,
            LedgerDto.class
        );
    }

    public CompletableFuture<CursorPageDto<VoucherSummaryDto>> listVouchers(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        String cursor,
        Integer limit
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        if (cursor != null && !cursor.isBlank()) {
            params.put("cursor", cursor);
        }
        if (limit != null) {
            params.put("limit", limit.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/vouchers", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<VoucherDto>> getVoucher(UUID companyId, UUID voucherId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(voucherId, "voucherId must not be null");
        return CompletableFuture.supplyAsync(() -> {
            recordOffEdtExecution();
            URI uri = resolvePath("/api/v1/companies/" + companyId + "/vouchers/" + voucherId);
            HttpRequest.Builder req = baseRequest(uri, true).GET();
            return executeJsonVersioned(req.build(), VoucherDto.class);
        }, ioExecutor);
    }

    public CompletableFuture<VersionedResponse<VoucherDto>> createVoucher(
        UUID companyId,
        UpsertVoucherCommand command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("voucher.create", resolvedIntention, command);
        return sendMutationJson(
            resolvedIntention,
            "voucher.create",
            "POST",
            "/api/v1/companies/" + companyId + "/vouchers",
            command,
            idempotencyKey,
            null,
            VoucherDto.class
        );
    }

    public CompletableFuture<VersionedResponse<VoucherDto>> updateVoucher(
        UUID companyId,
        UUID voucherId,
        UpsertVoucherCommand command,
        String ifMatchEtag,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(voucherId, "voucherId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (ifMatchEtag == null || ifMatchEtag.isBlank()) {
            throw new IllegalArgumentException("If-Match ETag is mandatory for optimistic concurrency on voucher updates.");
        }
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("voucher.update", resolvedIntention, command);
        return sendMutationJson(
            resolvedIntention,
            "voucher.update",
            "PUT",
            "/api/v1/companies/" + companyId + "/vouchers/" + voucherId,
            command,
            idempotencyKey,
            ifMatchEtag,
            VoucherDto.class
        );
    }

    public CompletableFuture<VersionedResponse<VoucherDto>> approveVoucher(
        UUID companyId,
        UUID voucherId,
        ApproveVoucherCommand command,
        String ifMatchEtag,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(voucherId, "voucherId must not be null");
        ApproveVoucherCommand body = command == null ? new ApproveVoucherCommand(Boolean.FALSE, null) : command;
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("voucher.approve", resolvedIntention, body);
        return sendMutationJson(
            resolvedIntention,
            "voucher.approve",
            "POST",
            "/api/v1/companies/" + companyId + "/vouchers/" + voucherId + "/approve",
            body,
            idempotencyKey,
            ifMatchEtag,
            VoucherDto.class
        );
    }

    public CompletableFuture<VersionedResponse<VoucherDto>> postVoucher(
        UUID companyId,
        UUID voucherId,
        String ifMatchEtag,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(voucherId, "voucherId must not be null");
        Map<String, Object> emptyBody = Map.of();
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("voucher.post", resolvedIntention, voucherId.toString());
        return sendMutationJson(
            resolvedIntention,
            "voucher.post",
            "POST",
            "/api/v1/companies/" + companyId + "/vouchers/" + voucherId + "/post",
            emptyBody,
            idempotencyKey,
            ifMatchEtag,
            VoucherDto.class
        );
    }

    /**
     * Retries an unresolved mutation using its exact original Idempotency-Key, If-Match header,
     * and wire payload (UX-009 / AT09-SWG-001-B). Never allocates a second financial key.
     */
    public <T> CompletableFuture<VersionedResponse<T>> retryUncertainMutation(
        MutationIntention intention,
        Class<T> responseType
    ) {
        Objects.requireNonNull(intention, "intention must not be null");
        Objects.requireNonNull(responseType, "responseType must not be null");
        return CompletableFuture.supplyAsync(() -> {
            recordOffEdtExecution();
            return executeMutationIntention(intention, responseType);
        }, ioExecutor);
    }

    public CompletableFuture<TrialBalanceReportDto> getTrialBalance(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        boolean includeOptional
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        params.put("includeOptional", Boolean.toString(includeOptional));
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/trial-balance", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<BalanceSheetReportDto> getBalanceSheet(
        UUID companyId,
        LocalDate asOfDate,
        String format,
        boolean includeOptional
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (asOfDate != null) {
            params.put("asOfDate", asOfDate.toString());
        }
        if (format != null && !format.isBlank()) {
            params.put("format", format);
        }
        params.put("includeOptional", Boolean.toString(includeOptional));
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/balance-sheet", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<AccountBookReportDto> getAccountBookReport(
        UUID companyId,
        String bookType,
        UUID groupId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        return getAccountBookReport(companyId, bookType, groupId, null, fromDate, toDate);
    }

    public CompletableFuture<AccountBookReportDto> getAccountBookReport(
        UUID companyId,
        String bookType,
        UUID groupId,
        UUID ledgerId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(bookType, "bookType must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bookType", bookType);
        if (groupId != null) {
            params.put("groupId", groupId.toString());
        }
        if (ledgerId != null) {
            params.put("ledgerId", ledgerId.toString());
        }
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/account-books", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<AccountBookReportDto.ExceptionReportDto> getExceptionReport(
        UUID companyId,
        String exceptionType,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        return getExceptionReport(companyId, exceptionType, fromDate, toDate, null);
    }

    public CompletableFuture<AccountBookReportDto.ExceptionReportDto> getExceptionReport(
        UUID companyId,
        String exceptionType,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOfDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(exceptionType, "exceptionType must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", exceptionType);
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        if (asOfDate != null) {
            params.put("asOfDate", asOfDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/exceptions", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<AccountBookReportDto.StatisticsReportDto> getStatisticsReport(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/statistics", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<AccountBookReportDto.StockQueryReportDto> getStockQueryReport(
        UUID companyId,
        UUID stockItemId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (stockItemId != null) {
            params.put("stockItemId", stockItemId.toString());
        }
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/stock-query", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<AccountBookReportDto.AdjacentVoucherDto> getAdjacentVoucher(
        UUID companyId,
        UUID currentVoucherId,
        String voucherCategory,
        LocalDate voucherDate,
        String direction
    ) {
        return getAdjacentVoucher(companyId, currentVoucherId, null, voucherCategory, voucherDate, direction);
    }

    public CompletableFuture<AccountBookReportDto.AdjacentVoucherDto> getAdjacentVoucher(
        UUID companyId,
        UUID currentVoucherId,
        UUID voucherTypeId,
        String voucherCategory,
        LocalDate voucherDate,
        String direction
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (currentVoucherId != null) {
            params.put("currentVoucherId", currentVoucherId.toString());
        }
        if (voucherTypeId != null) {
            params.put("voucherTypeId", voucherTypeId.toString());
        }
        if (voucherCategory != null && !voucherCategory.isBlank()) {
            params.put("voucherCategory", voucherCategory);
        }
        if (voucherDate != null) {
            params.put("voucherDate", voucherDate.toString());
        }
        params.put("direction", direction);
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/vouchers/adjacent", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<AccountBookReportDto.LastNarrationDto> getLastNarration(
        UUID companyId,
        UUID voucherTypeId,
        String voucherCategory,
        UUID ledgerId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (voucherTypeId != null) {
            params.put("voucherTypeId", voucherTypeId.toString());
        }
        if (voucherCategory != null && !voucherCategory.isBlank()) {
            params.put("voucherCategory", voucherCategory);
        }
        if (ledgerId != null) {
            params.put("ledgerId", ledgerId.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/vouchers/last-narration", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<VoucherDto>> cancelVoucher(
        UUID companyId,
        UUID voucherId,
        String reason,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(voucherId, "voucherId must not be null");
        Map<String, Object> body = Map.of("reason", reason == null ? "Cancelled by operator" : reason);
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("voucher.cancel", resolvedIntention, body);
        return sendMutationJson(
            resolvedIntention,
            "voucher.cancel",
            "POST",
            "/api/v1/companies/" + companyId + "/vouchers/" + voucherId + "/cancel",
            body,
            idempotencyKey,
            null,
            VoucherDto.class
        );
    }

    /**
     * Streams deterministic server-rendered PDF bytes for a report without passing HTML/scripts/options.
     */
    public CompletableFuture<byte[]> streamReportPdf(
        UUID companyId,
        String reportType,
        Map<String, Object> filterBody,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(reportType, "reportType must not be null");
        if (reportType.isBlank() || reportType.contains("/") || reportType.contains("..")) {
            throw new IllegalArgumentException("Invalid reportType: " + reportType);
        }
        Map<String, Object> safeBody = filterBody == null ? Map.of() : filterBody;
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("report.pdf." + reportType, resolvedIntention, safeBody);
        return streamPdfPost(
            "/api/v1/companies/" + companyId + "/reports/" + reportType + "/pdf",
            safeBody,
            idempotencyKey
        );
    }

    public CompletableFuture<byte[]> streamVoucherPdf(UUID companyId, UUID voucherId, UUID intentionId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(voucherId, "voucherId must not be null");
        UUID resolvedIntention = intentionId == null ? UUID.randomUUID() : intentionId;
        String idempotencyKey = canonicalizer.deriveIdempotencyKey("voucher.pdf", resolvedIntention, voucherId.toString());
        return streamPdfPost(
            "/api/v1/companies/" + companyId + "/vouchers/" + voucherId + "/print-pdf",
            Map.of(),
            idempotencyKey
        );
    }

    // =========================================================================
    // Domain 1: System, Principal Hydration & Native OIDC Discovery
    // =========================================================================

    public CompletableFuture<JsonNode> getAuthenticatedMe() {
        return sendGetJson("/api/v1/auth/me", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<JsonNode> getNativeOpenIdConfiguration() {
        return sendGetJson("/api/v1/auth/native/.well-known/openid-configuration", false, new TypeReference<>() {
        });
    }

    public CompletableFuture<JsonNode> getNativeJwks() {
        return sendGetJson("/api/v1/auth/native/jwks.json", false, new TypeReference<>() {
        });
    }

    // =========================================================================
    // Domain 3: Companies, Fiscal Years & Accounting Periods
    // =========================================================================

    public CompletableFuture<VersionedResponse<CompanyDto>> getCompany(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return CompletableFuture.supplyAsync(() -> {
            recordOffEdtExecution();
            URI uri = resolvePath("/api/v1/companies/" + companyId);
            HttpRequest req = baseRequest(uri, true).GET().build();
            return executeJsonVersioned(req, CompanyDto.class);
        }, ioExecutor);
    }

    public CompletableFuture<List<Map<String, Object>>> listFiscalYears(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/fiscal-years", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createFiscalYear(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("company.fy.create", resolved, command);
        return sendMutationJson(
            resolved, "company.fy.create", "POST",
            "/api/v1/companies/" + companyId + "/fiscal-years",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<List<Map<String, Object>>> listPeriods(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/periods", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> patchPeriodState(
        UUID companyId,
        UUID periodId,
        Map<String, Object> command,
        String ifMatchEtag,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(periodId, "periodId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("company.period.patch", resolved, command);
        return sendMutationJson(
            resolved, "company.period.patch", "PATCH",
            "/api/v1/companies/" + companyId + "/periods/" + periodId,
            command, key, ifMatchEtag, JsonNode.class
        );
    }

    // =========================================================================
    // Domain 4: Accounting Masters (Groups, Ledgers, Currencies, Cost, Voucher Types)
    // =========================================================================

    public CompletableFuture<JsonNode> listAccountGroups(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/account-groups", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createAccountGroup(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("group.create", resolved, command);
        return sendMutationJson(
            resolved, "group.create", "POST",
            "/api/v1/companies/" + companyId + "/account-groups",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> updateAccountGroup(
        UUID companyId,
        UUID groupId,
        Map<String, Object> command,
        String ifMatchEtag,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("group.update", resolved, command);
        return sendMutationJson(
            resolved, "group.update", "PUT",
            "/api/v1/companies/" + companyId + "/account-groups/" + groupId,
            command, key, ifMatchEtag, JsonNode.class
        );
    }

    public CompletableFuture<List<LedgerDto>> typeaheadLedgers(UUID companyId, String query, Integer limit) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            params.put("q", query);
        }
        if (limit != null) {
            params.put("limit", limit.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/ledgers/typeahead", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<LedgerDto>> getLedger(UUID companyId, UUID ledgerId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(ledgerId, "ledgerId must not be null");
        return CompletableFuture.supplyAsync(() -> {
            recordOffEdtExecution();
            URI uri = resolvePath("/api/v1/companies/" + companyId + "/ledgers/" + ledgerId);
            HttpRequest req = baseRequest(uri, true).GET().build();
            return executeJsonVersioned(req, LedgerDto.class);
        }, ioExecutor);
    }

    public CompletableFuture<VersionedResponse<LedgerDto>> updateLedger(
        UUID companyId,
        UUID ledgerId,
        CreateLedgerCommand command,
        String ifMatchEtag,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(ledgerId, "ledgerId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("ledger.update", resolved, command);
        return sendMutationJson(
            resolved, "ledger.update", "PUT",
            "/api/v1/companies/" + companyId + "/ledgers/" + ledgerId,
            command, key, ifMatchEtag, LedgerDto.class
        );
    }

    public CompletableFuture<JsonNode> listCurrencies(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/currencies", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createCurrency(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("currency.create", resolved, command);
        return sendMutationJson(
            resolved, "currency.create", "POST",
            "/api/v1/companies/" + companyId + "/currencies",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listForexRates(UUID companyId, UUID currencyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(currencyId, "currencyId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/currencies/" + currencyId + "/rates",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createForexRate(
        UUID companyId,
        UUID currencyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(currencyId, "currencyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("forex.create", resolved, command);
        return sendMutationJson(
            resolved, "forex.create", "POST",
            "/api/v1/companies/" + companyId + "/currencies/" + currencyId + "/rates",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listCostCategories(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/cost-categories", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createCostCategory(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("costCategory.create", resolved, command);
        return sendMutationJson(
            resolved, "costCategory.create", "POST",
            "/api/v1/companies/" + companyId + "/cost-categories",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listCostCentres(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/cost-centres", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createCostCentre(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("costCentre.create", resolved, command);
        return sendMutationJson(
            resolved, "costCentre.create", "POST",
            "/api/v1/companies/" + companyId + "/cost-centres",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listVoucherTypes(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/voucher-types", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createVoucherType(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("voucherType.create", resolved, command);
        return sendMutationJson(
            resolved, "voucherType.create", "POST",
            "/api/v1/companies/" + companyId + "/voucher-types",
            command, key, null, JsonNode.class
        );
    }

    // =========================================================================
    // Domain 5: Inventory Masters, Batches, Price Lists & BOM
    // =========================================================================

    public CompletableFuture<JsonNode> listUoms(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/uoms", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createUom(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("uom.create", resolved, command);
        return sendMutationJson(
            resolved, "uom.create", "POST",
            "/api/v1/companies/" + companyId + "/uoms",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listGodowns(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/godowns", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createGodown(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("godown.create", resolved, command);
        return sendMutationJson(
            resolved, "godown.create", "POST",
            "/api/v1/companies/" + companyId + "/godowns",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listStockGroups(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/stock-groups", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createStockGroup(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("stockGroup.create", resolved, command);
        return sendMutationJson(
            resolved, "stockGroup.create", "POST",
            "/api/v1/companies/" + companyId + "/stock-groups",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listStockCategories(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/stock-categories", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createStockCategory(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("stockCategory.create", resolved, command);
        return sendMutationJson(
            resolved, "stockCategory.create", "POST",
            "/api/v1/companies/" + companyId + "/stock-categories",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listStockItems(
        UUID companyId,
        String query,
        String cursor,
        Integer limit
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            params.put("q", query);
        }
        if (cursor != null && !cursor.isBlank()) {
            params.put("cursor", cursor);
        }
        if (limit != null) {
            params.put("limit", limit.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/stock-items", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> typeaheadStockItems(UUID companyId, String query, Integer limit) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            params.put("q", query);
        }
        if (limit != null) {
            params.put("limit", limit.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/stock-items/typeahead", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createStockItem(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("stockItem.create", resolved, command);
        return sendMutationJson(
            resolved, "stockItem.create", "POST",
            "/api/v1/companies/" + companyId + "/stock-items",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listStockItemBatches(UUID companyId, UUID stockItemId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(stockItemId, "stockItemId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/stock-items/" + stockItemId + "/batches",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> listPriceLists(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/price-lists", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<JsonNode> lookupPriceListRate(
        UUID companyId,
        String priceLevel,
        UUID stockItemId,
        LocalDate effectiveDate,
        BigDecimal quantity
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (priceLevel != null && !priceLevel.isBlank()) {
            params.put("priceLevel", priceLevel);
        }
        if (stockItemId != null) {
            params.put("stockItemId", stockItemId.toString());
        }
        if (effectiveDate != null) {
            params.put("effectiveDate", effectiveDate.toString());
        }
        if (quantity != null) {
            params.put("quantity", quantity.toPlainString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/price-lists/lookup", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createPriceList(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("priceList.create", resolved, command);
        return sendMutationJson(
            resolved, "priceList.create", "POST",
            "/api/v1/companies/" + companyId + "/price-lists",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listBoms(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/boms", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createBom(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("bom.create", resolved, command);
        return sendMutationJson(
            resolved, "bom.create", "POST",
            "/api/v1/companies/" + companyId + "/boms",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listOpenOrders(UUID companyId, String orderType) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (orderType != null && !orderType.isBlank()) {
            params.put("orderType", orderType);
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/orders/open", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> previewManufacturingBom(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("voucher.mfgPreview", resolved, command);
        return sendMutationJson(
            resolved, "voucher.mfgPreview", "POST",
            "/api/v1/companies/" + companyId + "/vouchers/manufacturing-preview",
            command, key, null, JsonNode.class
        );
    }

    // =========================================================================
    // Domain 6: Additional Voucher & Open-Bill Operations
    // =========================================================================

    public CompletableFuture<JsonNode> getNextVoucherNumber(
        UUID companyId,
        UUID voucherTypeId,
        LocalDate voucherDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (voucherTypeId != null) {
            params.put("voucherTypeId", voucherTypeId.toString());
        }
        if (voucherDate != null) {
            params.put("voucherDate", voucherDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/vouchers/next-number", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<VoucherDto>> reverseVoucher(
        UUID companyId,
        UUID voucherId,
        Map<String, Object> command,
        String ifMatchEtag,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(voucherId, "voucherId must not be null");
        Map<String, Object> safeBody = command == null ? Map.of() : command;
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("voucher.reverse", resolved, safeBody);
        return sendMutationJson(
            resolved, "voucher.reverse", "POST",
            "/api/v1/companies/" + companyId + "/vouchers/" + voucherId + "/reverse",
            safeBody, key, ifMatchEtag, VoucherDto.class
        );
    }

    public CompletableFuture<JsonNode> listOpenBills(UUID companyId, UUID ledgerId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(ledgerId, "ledgerId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/ledgers/" + ledgerId + "/open-bills",
            true,
            new TypeReference<>() {
            }
        );
    }

    // =========================================================================
    // Domain 7: Financial & Inventory Reports, SmartFind & MCA Edit Log
    // =========================================================================

    public CompletableFuture<ProfitAndLossReportDto> getProfitAndLoss(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        boolean includeOptional
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        params.put("includeOptional", Boolean.toString(includeOptional));
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/profit-and-loss", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getScheduleIiiReport(
        UUID companyId,
        LocalDate asOfDate,
        LocalDate comparativeAsOfDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (asOfDate != null) {
            params.put("asOfDate", asOfDate.toString());
        }
        if (comparativeAsOfDate != null) {
            params.put("comparativeAsOfDate", comparativeAsOfDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/schedule-iii", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getCashFlowReport(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/cash-flow", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getRatioAnalysisReport(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/ratios", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getAgeingReport(
        UUID companyId,
        String nature,
        LocalDate asOfDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (nature != null && !nature.isBlank()) {
            params.put("nature", nature);
        }
        if (asOfDate != null) {
            params.put("asOfDate", asOfDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/ageing", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getStockSummaryReport(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/stock-summary", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getGodownSummaryReport(
        UUID companyId,
        UUID godownId,
        LocalDate asOfDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (godownId != null) {
            params.put("godownId", godownId.toString());
        }
        if (asOfDate != null) {
            params.put("asOfDate", asOfDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/godown-summary", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getMovementAnalysisReport(
        UUID companyId,
        UUID stockItemId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (stockItemId != null) {
            params.put("stockItemId", stockItemId.toString());
        }
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/movement-analysis", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getBatchAgeingReport(UUID companyId, LocalDate asOfDate) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (asOfDate != null) {
            params.put("asOfDate", asOfDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/reports/batch-ageing", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> smartFind(UUID companyId, String query, Integer limit) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            params.put("q", query);
        }
        if (limit != null) {
            params.put("limit", limit.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/search/smartfind", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getEditLogVersions(UUID companyId, String entityType, UUID entityId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/edit-log/" + entityType + "/" + entityId + "/versions",
            true,
            new TypeReference<>() {
            }
        );
    }

    // =========================================================================
    // Domain 8: Statutory GST (Multi-GSTIN, GSTR-1/2B/3B/9, IMS, e-Inv) & TDS
    // =========================================================================

    public CompletableFuture<JsonNode> listStatutoryRulePacks(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/statutory/rule-packs", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<JsonNode> listGstRegistrations(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/gst/registrations", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createGstRegistration(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("gst.registration.create", resolved, command);
        return sendMutationJson(
            resolved, "gst.registration.create", "POST",
            "/api/v1/companies/" + companyId + "/gst/registrations",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> computeGstPreview(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("gst.computePreview", resolved, command);
        return sendMutationJson(
            resolved, "gst.computePreview", "POST",
            "/api/v1/companies/" + companyId + "/gst/compute-preview",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listGstUncertainTransactions(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/gst/uncertain-transactions", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> acceptGstConflict(
        UUID companyId,
        UUID voucherId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(voucherId, "voucherId must not be null");
        Map<String, Object> safeBody = command == null ? Map.of() : command;
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("gst.acceptConflict", resolved, safeBody);
        return sendMutationJson(
            resolved, "gst.acceptConflict", "POST",
            "/api/v1/companies/" + companyId + "/gst/uncertain-transactions/" + voucherId + "/accept-conflict",
            safeBody, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> getGstr1Report(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/gst/gstr1", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> exportGstr1(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, Object> safeBody = command == null ? Map.of() : command;
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("gst.gstr1.export", resolved, safeBody);
        return sendMutationJson(
            resolved, "gst.gstr1.export", "POST",
            "/api/v1/companies/" + companyId + "/gst/gstr1/export",
            safeBody, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> getGstr3bReport(
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/gst/gstr3b", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> getGstr9Report(UUID companyId, String financialYear) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (financialYear != null && !financialYear.isBlank()) {
            params.put("financialYear", financialYear);
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/gst/gstr9", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> exportGstr9(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, Object> safeBody = command == null ? Map.of() : command;
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("gst.gstr9.export", resolved, safeBody);
        return sendMutationJson(
            resolved, "gst.gstr9.export", "POST",
            "/api/v1/companies/" + companyId + "/gst/gstr9/export",
            safeBody, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listImsInvoices(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/gst/ims/invoices", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> importImsJson(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("gst.ims.import", resolved, command);
        return sendMutationJson(
            resolved, "gst.ims.import", "POST",
            "/api/v1/companies/" + companyId + "/gst/ims/import-json",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> applyImsAction(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("gst.ims.action", resolved, command);
        return sendMutationJson(
            resolved, "gst.ims.action", "POST",
            "/api/v1/companies/" + companyId + "/gst/ims/actions",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listTdsNatures(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/tds/natures", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> recordTdsChallan(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("tds.challan.create", resolved, command);
        return sendMutationJson(
            resolved, "tds.challan.create", "POST",
            "/api/v1/companies/" + companyId + "/tds/challans",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> getForm26QReport(
        UUID companyId,
        String financialYear,
        String quarter
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (financialYear != null && !financialYear.isBlank()) {
            params.put("financialYear", financialYear);
        }
        if (quarter != null && !quarter.isBlank()) {
            params.put("quarter", quarter);
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/tds/form26q", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> exportTdsFvu(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, Object> safeBody = command == null ? Map.of() : command;
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("tds.fvu.export", resolved, safeBody);
        return sendMutationJson(
            resolved, "tds.fvu.export", "POST",
            "/api/v1/companies/" + companyId + "/tds/export-fvu",
            safeBody, key, null, JsonNode.class
        );
    }

    // =========================================================================
    // Domain 9: Banking, BRS, Payroll & OCR Staging
    // =========================================================================

    public CompletableFuture<JsonNode> listChequeBooks(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/banking/cheque-books", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> createChequeBook(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("banking.chequeBook.create", resolved, command);
        return sendMutationJson(
            resolved, "banking.chequeBook.create", "POST",
            "/api/v1/companies/" + companyId + "/banking/cheque-books",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listChequeLeaves(UUID companyId, UUID bookId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(bookId, "bookId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/banking/cheque-books/" + bookId + "/leaves",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> importBankStatement(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("banking.statement.import", resolved, command);
        return sendMutationJson(
            resolved, "banking.statement.import", "POST",
            "/api/v1/companies/" + companyId + "/banking/statements/import",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> getBrsReport(
        UUID companyId,
        UUID bankLedgerId,
        LocalDate fromDate,
        LocalDate toDate
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, String> params = new LinkedHashMap<>();
        if (bankLedgerId != null) {
            params.put("bankLedgerId", bankLedgerId.toString());
        }
        if (fromDate != null) {
            params.put("fromDate", fromDate.toString());
        }
        if (toDate != null) {
            params.put("toDate", toDate.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/companies/" + companyId + "/banking/brs", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> autoMatchBrs(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, Object> safeBody = command == null ? Map.of() : command;
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("banking.brs.autoMatch", resolved, safeBody);
        return sendMutationJson(
            resolved, "banking.brs.autoMatch", "POST",
            "/api/v1/companies/" + companyId + "/banking/brs/auto-match",
            safeBody, key, null, JsonNode.class
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> reconcileBrsLine(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("banking.brs.reconcile", resolved, command);
        return sendMutationJson(
            resolved, "banking.brs.reconcile", "PUT",
            "/api/v1/companies/" + companyId + "/banking/brs/reconcile",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listPayrollHeads(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/banking/payroll/heads", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<JsonNode> listEmployees(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/banking/payroll/employees",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> listOcrStagingDocuments(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/banking/ocr-staging/documents",
            true,
            new TypeReference<>() {
            }
        );
    }

    // =========================================================================
    // Domain 10: Import, Exchange, Legacy .1800 Migration & Extensions
    // =========================================================================

    public CompletableFuture<JsonNode> listMigrationImports(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/imports", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> stageLegacy1800Import(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("migration.1800.stage", resolved, command);
        return sendMutationJson(
            resolved, "migration.1800.stage", "POST",
            "/api/v1/companies/" + companyId + "/imports/legacy-1800/stage",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> getMigrationProof(UUID companyId, UUID jobId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/imports/" + jobId + "/proof",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> commitMigrationImport(
        UUID companyId,
        UUID jobId,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        Map<String, Object> body = Map.of();
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("migration.1800.commit", resolved, jobId.toString());
        return sendMutationJson(
            resolved, "migration.1800.commit", "POST",
            "/api/v1/companies/" + companyId + "/imports/" + jobId + "/commit",
            body, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listExchangeTemplates(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/exchange/templates", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<VersionedResponse<JsonNode>> exportCanonicalXml(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, Object> safeBody = command == null ? Map.of() : command;
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("exchange.xml.export", resolved, safeBody);
        return sendMutationJson(
            resolved, "exchange.xml.export", "POST",
            "/api/v1/companies/" + companyId + "/exports/xml",
            safeBody, key, null, JsonNode.class
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> importCanonicalXml(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("exchange.xml.import", resolved, command);
        return sendMutationJson(
            resolved, "exchange.xml.import", "POST",
            "/api/v1/companies/" + companyId + "/imports/xml",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> exportJsonEx(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Map<String, Object> safeBody = command == null ? Map.of() : command;
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("exchange.jsonex.export", resolved, safeBody);
        return sendMutationJson(
            resolved, "exchange.jsonex.export", "POST",
            "/api/v1/companies/" + companyId + "/exports/jsonex",
            safeBody, key, null, JsonNode.class
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> importJsonEx(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("exchange.jsonex.import", resolved, command);
        return sendMutationJson(
            resolved, "exchange.jsonex.import", "POST",
            "/api/v1/companies/" + companyId + "/imports/jsonex",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listSpreadsheetTemplates(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/spreadsheet-imports/templates",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<JsonNode> listSpreadsheetBatches(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> stageSpreadsheetBatch(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("spreadsheet.batch.stage", resolved, command);
        return sendMutationJson(
            resolved, "spreadsheet.batch.stage", "POST",
            "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> executeSpreadsheetBatch(
        UUID companyId,
        UUID batchId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(batchId, "batchId must not be null");
        Map<String, Object> safeBody = command == null ? Map.of() : command;
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("spreadsheet.batch.execute", resolved, safeBody);
        return sendMutationJson(
            resolved, "spreadsheet.batch.execute", "POST",
            "/api/v1/companies/" + companyId + "/spreadsheet-imports/batches/" + batchId + "/execute",
            safeBody, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listExtensionPackages(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/extensions/packages", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<JsonNode> listExtensionBindings(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson("/api/v1/companies/" + companyId + "/extensions/bindings", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<JsonNode> listCustomFields(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/extensions/custom-fields",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> evaluateExtensionPreview(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("extension.evaluatePreview", resolved, command);
        return sendMutationJson(
            resolved, "extension.evaluatePreview", "POST",
            "/api/v1/companies/" + companyId + "/extensions/evaluate-preview",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<JsonNode> listTdlImports(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        return sendGetJson(
            "/api/v1/companies/" + companyId + "/extensions/tdl-imports",
            true,
            new TypeReference<>() {
            }
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> stageTdlImport(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("extension.tdl.stage", resolved, command);
        return sendMutationJson(
            resolved, "extension.tdl.stage", "POST",
            "/api/v1/companies/" + companyId + "/extensions/tdl-imports",
            command, key, null, JsonNode.class
        );
    }

    public CompletableFuture<VersionedResponse<JsonNode>> executeOdbcQuery(
        UUID companyId,
        Map<String, Object> command,
        UUID intentionId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        UUID resolved = intentionId == null ? UUID.randomUUID() : intentionId;
        String key = canonicalizer.deriveIdempotencyKey("extension.odbc.query", resolved, command);
        return sendMutationJson(
            resolved, "extension.odbc.query", "POST",
            "/api/v1/companies/" + companyId + "/extensions/odbc-query",
            command, key, null, JsonNode.class
        );
    }

    // =========================================================================
    // Domain 11: Admin Audit Hash-Chain & Government Interface Inventory
    // =========================================================================

    public CompletableFuture<JsonNode> verifyAuditHashChain() {
        return sendGetJson("/api/v1/admin/audit/verify", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<JsonNode> getGovernmentInterfaceInventory() {
        return sendGetJson("/api/v1/admin/government-interface-inventory", true, new TypeReference<>() {
        });
    }

    public CompletableFuture<JsonNode> listGovernmentIntegrationProfiles(UUID companyId) {
        Map<String, String> params = new LinkedHashMap<>();
        if (companyId != null) {
            params.put("companyId", companyId.toString());
        }
        return sendGetJson(
            buildPathWithQuery("/api/v1/admin/government-integration-profiles", params),
            true,
            new TypeReference<>() {
            }
        );
    }

    @Override
    public void close() {
        unresolvedMutations.clear();
        httpClient.close();
        ioExecutor.close();
    }

    private CompletableFuture<byte[]> streamPdfPost(String path, Object body, String idempotencyKey) {
        return CompletableFuture.supplyAsync(() -> {
            recordOffEdtExecution();
            URI uri = resolvePath(path);
            String jsonBody = canonicalizer.writeWireJson(body);
            HttpRequest request = baseRequest(uri, true)
                .header("Accept", "application/pdf, application/problem+json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
            try {
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    byte[] bytes = in.readAllBytes();
                    if (response.statusCode() >= 400) {
                        throw parseProblemException(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
                    }
                    verifyPdfSignature(bytes);
                    return bytes;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while streaming PDF output.", ex);
            } catch (IOException ex) {
                throw new IllegalStateException("Transport failure while streaming PDF output: " + ex.getMessage(), ex);
            }
        }, ioExecutor);
    }

    private <T> CompletableFuture<T> sendGetJson(
        String pathAndQuery,
        boolean authenticated,
        TypeReference<T> typeReference
    ) {
        return CompletableFuture.supplyAsync(() -> {
            recordOffEdtExecution();
            URI uri = resolvePath(pathAndQuery);
            HttpRequest request = baseRequest(uri, authenticated).GET().build();
            try {
                HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() >= 400) {
                    throw parseProblemException(response.statusCode(), response.body());
                }
                return canonicalizer.wireMapper().readValue(response.body(), typeReference);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during API read.", ex);
            } catch (JacksonException ex) {
                throw new IllegalStateException("Failed to deserialize server JSON response.", ex);
            } catch (IOException ex) {
                throw new IllegalStateException("Network read failure: " + ex.getMessage(), ex);
            }
        }, ioExecutor);
    }

    private <T> CompletableFuture<VersionedResponse<T>> sendMutationJson(
        UUID intentionId,
        String operationCode,
        String method,
        String path,
        Object payload,
        String idempotencyKey,
        String ifMatchEtag,
        Class<T> responseType
    ) {
        return CompletableFuture.supplyAsync(() -> {
            recordOffEdtExecution();
            URI uri = resolvePath(path);
            String wireJson = canonicalizer.writeWireJson(payload);
            String canonicalSha256 = canonicalizer.sha256Hex(canonicalizer.canonicalizeJson(wireJson));
            MutationIntention intention = new MutationIntention(
                intentionId,
                operationCode,
                method,
                uri,
                idempotencyKey,
                ifMatchEtag,
                wireJson,
                canonicalSha256,
                Instant.now()
            );
            return executeMutationIntention(intention, responseType);
        }, ioExecutor);
    }

    private <T> VersionedResponse<T> executeMutationIntention(MutationIntention intention, Class<T> responseType) {
        HttpRequest.Builder reqBuilder = baseRequest(intention.requestUri(), true)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", intention.idempotencyKey());
        if (intention.ifMatchEtag() != null && !intention.ifMatchEtag().isBlank()) {
            reqBuilder.header("If-Match", intention.ifMatchEtag());
        }
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString(
            intention.wirePayloadJson(),
            StandardCharsets.UTF_8
        );
        HttpRequest request = reqBuilder.method(intention.httpMethod(), publisher).build();
        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            unresolvedMutations.remove(intention.intentionId());
            if (response.statusCode() >= 400) {
                throw parseProblemException(response.statusCode(), response.body());
            }
            T parsed = canonicalizer.wireMapper().readValue(response.body(), responseType);
            String etag = response.headers().firstValue("ETag").orElse(null);
            return new VersionedResponse<>(parsed, etag, response.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            unresolvedMutations.put(intention.intentionId(), intention);
            throw new UncertainTransportException(intention, ex);
        } catch (JacksonException ex) {
            unresolvedMutations.remove(intention.intentionId());
            throw new IllegalStateException("Failed to deserialize mutation response.", ex);
        } catch (IOException ex) {
            unresolvedMutations.put(intention.intentionId(), intention);
            throw new UncertainTransportException(intention, ex);
        }
    }

    private <T> VersionedResponse<T> executeJsonVersioned(HttpRequest request, Class<T> responseType) {
        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() >= 400) {
                throw parseProblemException(response.statusCode(), response.body());
            }
            T parsed = canonicalizer.wireMapper().readValue(response.body(), responseType);
            String etag = response.headers().firstValue("ETag").orElse(null);
            return new VersionedResponse<>(parsed, etag, response.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during API read.", ex);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize server JSON response.", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Network read failure: " + ex.getMessage(), ex);
        }
    }

    private HttpRequest.Builder baseRequest(URI uri, boolean authenticated) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(config.requestTimeout())
            .header("Accept", "application/json, application/problem+json")
            .header("X-MoneyCraft-Client-Profile", "SWING_PERSONAL_V1");
        if (authenticated) {
            String token = accessTokenSupplier.get()
                .filter(val -> !val.isBlank())
                .orElseThrow(() -> new ApiProblemException(
                    401,
                    "MC-AUTH-REQUIRED",
                    "MC-AUTH-REQUIRED",
                    "Authentication Required",
                    "Sign in via the external system browser PKCE flow before invoking protected endpoints.",
                    Map.of()
                ));
            if (config.rejectServiceApiKeys() && token.startsWith("mc_sk_")) {
                throw new IllegalStateException(
                    "SWG-002 violation: interactive Swing personal client must not borrow a service API key."
                );
            }
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private ApiProblemException parseProblemException(int statusCode, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return new ApiProblemException(statusCode, null, null, "HTTP " + statusCode, "", Map.of());
        }
        try {
            JsonNode root = canonicalizer.wireMapper().readTree(rawBody);
            String code = textOrNull(root, "code");
            String errorCode = textOrNull(root, "errorCode");
            String title = textOrNull(root, "title");
            String detail = textOrNull(root, "detail");
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            JsonNode fieldsNode = root.get("fieldErrors");
            if (fieldsNode != null && fieldsNode.isObject()) {
                for (Map.Entry<String, JsonNode> entry : fieldsNode.properties()) {
                    fieldErrors.put(entry.getKey(), entry.getValue().asString(""));
                }
            }
            return new ApiProblemException(statusCode, code, errorCode, title, detail, fieldErrors);
        } catch (JacksonException ignored) {
            return new ApiProblemException(statusCode, null, null, "HTTP " + statusCode, rawBody, Map.of());
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.asString(null);
    }

    private static void verifyPdfSignature(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC.length) {
            throw new IllegalStateException("Server PDF response was empty or truncated.");
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                throw new IllegalStateException("Server response did not contain a valid %PDF- document header.");
            }
        }
    }

    private URI resolvePath(String pathAndQuery) {
        String base = config.baseApiUri().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        return URI.create(base + suffix);
    }

    private static String buildPathWithQuery(String path, Map<String, String> params) {
        if (params.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private void recordOffEdtExecution() {
        boolean onEdt = SwingUtilities.isEventDispatchThread();
        lastCallExecutedOffEdt.set(!onEdt);
        if (onEdt) {
            throw new IllegalStateException("ND-S01 / SWG-001 violation: network I/O must never run on the Swing EDT.");
        }
    }
}
