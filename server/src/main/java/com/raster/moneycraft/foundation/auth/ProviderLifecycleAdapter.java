package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AdminReceiptService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.authority.InstallationSecurityState;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.OidcClockSource;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Package-private Document 47 (MC-FED-01 Rev 2) OIDC federation lifecycle adapter:
 * startup signed MC-PROVIDER-CONFIG-1 loader, ordinary-restart freshness bookkeeper,
 * profile-change per-person cleanup-before-commit coordinator, and 60-second monotonic
 * 3-step bracketed Keycloak Admin REST user/group reconciler.
 */
@Component
@DependsOn("authorityGuard")
class ProviderLifecycleAdapter implements SmartLifecycle {

    static final long POLL_INTERVAL_SECONDS = 60L;
    static final long CONSERVATIVE_SUBTRACTION_SECONDS = 25L;
    static final long CYCLE_DEADLINE_NANOS = 30_000_000_000L;
    static final long MAX_CLOCK_DISCREPANCY_NANOS = 5_000_000_000L;
    static final int MAX_RESPONSE_BYTES = 262_144;
    static final int MAX_JSON_DEPTH = 16;
    static final int PAGE_SIZE = 100;
    static final int MAX_GROUP_PAGES = 41;
    static final int MAX_TOTAL_GROUPS = 4_096;

    private static final Logger LOG = LoggerFactory.getLogger(ProviderLifecycleAdapter.class);
    private static final Pattern CANONICAL_UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );

    private static final JsonMapper STRICT_HTTP_JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();

    private final MoneyCraftProperties properties;
    private final AuthorityGuard authorityGuard;
    private final TinkCryptoService tinkCryptoService;
    private final AdminReceiptService adminReceiptService;
    private final AuditChainService auditChainService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final OidcClockSource clockSource;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate authorityJdbcTemplate;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate authorityTxTemplate;
    private final TransactionTemplate businessTxTemplate;

    private final ExecutorService readExecutor;
    private final ScheduledExecutorService scheduler;
    private final HttpClient httpClient;
    private final Object sweepMonitor = new Object();
    private final Map<UUID, CachedClientToken> tokenCache = new ConcurrentHashMap<>();
    private final AtomicBoolean workerPaused = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile Runnable postReadBeforeReconcileHook;

    ProviderLifecycleAdapter(
        MoneyCraftProperties properties,
        AuthorityGuard authorityGuard,
        TinkCryptoService tinkCryptoService,
        AdminReceiptService adminReceiptService,
        AuditChainService auditChainService,
        JcsCanonicalizer jcsCanonicalizer,
        OidcClockSource clockSource,
        ObjectMapper objectMapper,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("authorityTransactionManager") PlatformTransactionManager authorityTransactionManager,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.properties = properties;
        this.authorityGuard = authorityGuard;
        this.tinkCryptoService = tinkCryptoService;
        this.adminReceiptService = adminReceiptService;
        this.auditChainService = auditChainService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.clockSource = clockSource;
        this.objectMapper = objectMapper;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.authorityTxTemplate = new TransactionTemplate(authorityTransactionManager);
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);

        AtomicInteger threadSeq = new AtomicInteger(1);
        this.readExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread t = new Thread(runnable, "mc-oidc-lifecycle-read-" + threadSeq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "mc-oidc-lifecycle-poll");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public void start() {
        if (properties.getMigrator().isOnly() || properties.getMigrator().isExitOnComplete()) {
            this.running = true;
            return;
        }
        tokenCache.clear();
        performGuardedStartupCheck();
        this.running = true;
        this.scheduler.scheduleAtFixedRate(
            this::scheduledMonotonicSweepTick,
            POLL_INTERVAL_SECONDS,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    @Override
    public void stop() {
        this.running = false;
        this.tokenCache.clear();
        this.scheduler.shutdownNow();
        this.readExecutor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 2000;
    }

    void setWorkerPausedForTesting(boolean paused) {
        this.workerPaused.set(paused);
    }

    void setPostReadBeforeReconcileHookForTesting(Runnable hook) {
        this.postReadBeforeReconcileHook = hook;
    }

    void clearTokenCache() {
        this.tokenCache.clear();
    }

    private void performGuardedStartupCheck() {
        List<AuthorityStateRow> stateRows = readAuthorityState();
        if (stateRows.isEmpty()) {
            return;
        }
        String cfgPath = properties.getSecurity().getProviderConfigPath();
        if (cfgPath == null || cfgPath.isBlank()) {
            performOrdinaryRestartBookkeeping();
            return;
        }

        Path path = Path.of(cfgPath.trim());
        if (!Files.isRegularFile(path)) {
            LOG.warn("Configured provider-config-path does not exist; closing OIDC provider freshness while keeping LOCAL open.");
            performOrdinaryRestartBookkeeping();
            return;
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            applySignedProviderConfigJson(raw, null);
        } catch (IOException | MoneyCraftException ex) {
            LOG.warn("Provider configuration startup ingestion denied ({}); OIDC closed, LOCAL remains operational.", ex.getMessage());
            performOrdinaryRestartBookkeeping();
        }
    }

    /**
     * Executes Document 47 Rev 2 ordinary-restart bookkeeping: clears ma_issuer.checked_at and
     * ma_oidc_link.checked_at to null without bumping version_no or authority_epoch, terminalises
     * CLAIMED one-use callbacks without replaying code exchange, and preserves SPRING_SESSION,
     * ISSUED/REDIRECTED/VERIFIED_LINK challenges, and unchanged PROVIDER grants.
     */
    void performOrdinaryRestartBookkeeping() {
        requireHealthyWriterLease();
        tokenCache.clear();
        authorityTxTemplate.executeWithoutResult(status -> {
            authorityJdbcTemplate.execute("SET LOCAL ma.bookkeeping_checked_at_only = 'on'");
            authorityJdbcTemplate.update("UPDATE ma_issuer SET checked_at = NULL WHERE checked_at IS NOT NULL");
            authorityJdbcTemplate.update("UPDATE ma_oidc_link SET checked_at = NULL WHERE checked_at IS NOT NULL");
            authorityJdbcTemplate.update(
                """
                UPDATE ma_auth_challenge
                   SET ceremony_stage = 'CONSUMED',
                       consumed = true,
                       consumed_at = COALESCE(consumed_at, clock_timestamp())
                 WHERE purpose = 'OIDC_AUTHORIZE'
                   AND ceremony_stage = 'CLAIMED'
                   AND consumed = false
                """
            );
        });
    }

    ConfigApplyOutcome applySignedProviderConfigJson(String rawJson, FaultHook faultHook) {
        requireHealthyWriterLease();
        boolean allowTest = properties.getSecurity().isAllowTestProviderProfile();
        IssuerConfig2.SignedProviderConfig signed = IssuerConfig2.parseAndValidateSignedEnvelope(
            rawJson,
            jcsCanonicalizer,
            allowTest
        );
        IssuerConfig2.verifyEd25519Signature(
            signed,
            properties.getSecurity().getProviderCustodianPublicKey()
        );

        AuthorityStateRow authState = requireMatchingAuthorityStateForConfig(signed);
        ExistingIssuerRow existing = findIssuerRowById(signed.issuerId());

        // Document 47 Rev 2 identical installed config + state rule: configuration no-op even if input expired
        if (existing != null
            && Objects.equals(existing.configDigest(), signed.configDigest())
            && Objects.equals(existing.state(), signed.state())) {
            performOrdinaryRestartBookkeeping();
            return new ConfigApplyOutcome(false, existing.versionNo(), signed.issuerId());
        }

        // New or changed profile: enforce time validity, expectedIssuerVersion, secret/qualification bindings
        Instant now = clockSource.instant();
        if (signed.issuedAt().isAfter(now.plusSeconds(5)) || !now.isBefore(signed.expiresAt())) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-PROVIDER-CONFIG-EXPIRED",
                "Signed Provider Configuration Expired",
                "New or changed MC-PROVIDER-CONFIG-1 input is outside its valid [issuedAt, expiresAt) window."
            );
        }

        if (existing == null) {
            if (signed.expectedIssuerVersion() != null) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-PROVIDER-VERSION-MISMATCH",
                    "Expected Issuer Version Mismatch",
                    "expectedIssuerVersion must be null when installing a new issuer."
                );
            }
        } else {
            if (signed.expectedIssuerVersion() == null
                || signed.expectedIssuerVersion().longValue() != existing.versionNo()) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-PROVIDER-VERSION-MISMATCH",
                    "Expected Issuer Version Mismatch",
                    "expectedIssuerVersion did not match current ma_issuer.version_no."
                );
            }
            if (!existing.issuerUri().equals(signed.config().issuer())
                || !existing.registrationId().equals(signed.registrationId())
                || (existing.federationComponentId() != null
                    && !existing.federationComponentId().equals(signed.config().lifecycle().federationComponentId()))) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-PROVIDER-IDENTITY-IMMUTABLE",
                    "Immutable Provider Identity Changed",
                    "issuerUri, registrationId, and federationComponentId cannot be mutated on an existing issuer."
                );
            }
        }

        String clientSecret = resolveRequiredSecret(signed.config().clientSecretRef());
        resolveRequiredSecret(signed.config().lifecycle().lifecycleSecretRef());
        verifyQualificationBinding(
            signed.config().lifecycle().qualificationRef(),
            signed.config().lifecycle().qualificationDigest()
        );

        // Step 1: Close freshness immediately before any per-person cleanup
        performOrdinaryRestartBookkeeping();
        if (faultHook != null) {
            faultHook.onStep("BEFORE_CLEANUP");
        }

        // Step 2: Bounded per-person OIDC session/challenge deletion + PROVIDER grant revocation BEFORE final commit
        if (existing != null) {
            List<LinkSnapshot> links = authorityJdbcTemplate.query(
                """
                SELECT id, principal_id, subject, state, version_no
                  FROM ma_oidc_link
                 WHERE issuer_id = ?
                 ORDER BY id ASC
                """,
                (rs, rowNum) -> new LinkSnapshot(
                    rs.getObject("id", UUID.class),
                    rs.getObject("principal_id", UUID.class),
                    rs.getString("subject"),
                    rs.getString("state"),
                    rs.getLong("version_no")
                ),
                signed.issuerId()
            );

            int idx = 0;
            for (LinkSnapshot link : links) {
                revalidateConfigInstallGuards(authState, signed.issuerId(), existing.versionNo());
                invalidateSinglePersonForIssuerChange(authState.installationId(), signed.issuerId(), link);
                if (faultHook != null) {
                    faultHook.onStep("DURING_CLEANUP_" + idx);
                }
                idx++;
            }
        }

        if (faultHook != null) {
            faultHook.onStep("BEFORE_FINAL_ISSUER_COMMIT");
        }

        // Step 3: Final versioned ma_issuer commit + provider.configure receipt
        revalidateConfigInstallGuards(
            authState,
            signed.issuerId(),
            existing == null ? null : existing.versionNo()
        );
        long nextVersion = existing == null ? 1L : existing.versionNo() + 1L;
        byte[] metadataEnvelope = IssuerConfig2.sealAuthorityEnvelope(
            signed.config(),
            authState.installationId(),
            authState.incarnation(),
            signed.issuerId(),
            nextVersion,
            null,
            tinkCryptoService,
            jcsCanonicalizer
        );
        byte[] clientSecretGcm = tinkCryptoService.encryptGcm(
            ("ma_issuer|" + signed.issuerId()).getBytes(StandardCharsets.UTF_8),
            clientSecret.getBytes(StandardCharsets.UTF_8)
        );
        String displayName = "OIDC (" + signed.registrationId() + ")";

        authorityTxTemplate.executeWithoutResult(status -> {
            if (existing == null) {
                authorityJdbcTemplate.update(
                    """
                    INSERT INTO ma_issuer (
                        id, installation_id, registration_id, display_name, issuer_uri,
                        authorization_uri, token_uri, jwks_uri, userinfo_uri,
                        client_id, client_secret_gcm, scopes, pkce_required, state,
                        version_no, checked_at, metadata_cipher, config_digest,
                        federation_component_id, lifecycle_profile
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'openid profile email', true, ?, 1, NULL, ?, ?, ?, ?)
                    """,
                    signed.issuerId(),
                    authState.installationId(),
                    signed.registrationId(),
                    displayName,
                    signed.config().issuer(),
                    signed.config().authorizationUri(),
                    signed.config().tokenUri(),
                    signed.config().jwksUri(),
                    signed.config().userInfoUri(),
                    signed.config().clientId(),
                    clientSecretGcm,
                    signed.state(),
                    metadataEnvelope,
                    signed.configDigest(),
                    signed.config().lifecycle().federationComponentId(),
                    IssuerConfig2.PROFILE_MC_FED_01
                );
            } else {
                int updated = authorityJdbcTemplate.update(
                    """
                    UPDATE ma_issuer
                       SET authorization_uri = ?,
                           token_uri = ?,
                           jwks_uri = ?,
                           userinfo_uri = ?,
                           client_id = ?,
                           client_secret_gcm = ?,
                           state = ?,
                           checked_at = NULL,
                           metadata_cipher = ?,
                           config_digest = ?,
                           lifecycle_profile = ?,
                           version_no = ?,
                           updated_at = clock_timestamp()
                     WHERE id = ? AND version_no = ?
                    """,
                    signed.config().authorizationUri(),
                    signed.config().tokenUri(),
                    signed.config().jwksUri(),
                    signed.config().userInfoUri(),
                    signed.config().clientId(),
                    clientSecretGcm,
                    signed.state(),
                    metadataEnvelope,
                    signed.configDigest(),
                    IssuerConfig2.PROFILE_MC_FED_01,
                    nextVersion,
                    signed.issuerId(),
                    existing.versionNo()
                );
                if (updated != 1) {
                    throw new MoneyCraftException(
                        HttpStatus.CONFLICT,
                        "MC-PROVIDER-VERSION-MISMATCH",
                        "Concurrent Issuer Modification",
                        "ma_issuer version changed before final configuration commit."
                    );
                }
            }
            authorityJdbcTemplate.update(
                """
                UPDATE ma_state
                   SET authority_epoch = authority_epoch + 1,
                       updated_at = clock_timestamp()
                 WHERE installation_id = ?
                """,
                authState.installationId()
            );
        });

        recordInternalAuthorityOperation(
            authState.installationId(),
            "provider.configure",
            "ISSUER",
            signed.issuerId().toString(),
            Map.of(
                "issuerId", signed.issuerId().toString(),
                "registrationId", signed.registrationId(),
                "state", signed.state(),
                "issuerVersion", nextVersion,
                "configDigest", signed.configDigest(),
                "custodianRef", signed.custodianRef(),
                "evidenceRef", signed.evidenceRef()
            )
        );
        tokenCache.remove(signed.issuerId());

        if (faultHook != null) {
            faultHook.onStep("AFTER_FINAL_ISSUER_COMMIT");
        }
        return new ConfigApplyOutcome(true, nextVersion, signed.issuerId());
    }

    private void scheduledMonotonicSweepTick() {
        if (!running || workerPaused.get()) {
            return;
        }
        AuthorityGuard.GuardSnapshot snap = authorityGuard.cachedSnapshot();
        if (snap.securityState() != InstallationSecurityState.ACTIVE || !authorityGuard.isLeaseHealthy()) {
            return;
        }
        List<UUID> enabledFedIssuers = authorityJdbcTemplate.query(
            """
            SELECT id FROM ma_issuer
             WHERE state = 'ENABLED' AND lifecycle_profile = 'MC-FED-01'
             ORDER BY id ASC
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class)
        );
        for (UUID issuerId : enabledFedIssuers) {
            try {
                runSweep(issuerId);
            } catch (RuntimeException ex) {
                LOG.warn("Scheduled MC-FED-01 lifecycle sweep did not complete positively for issuer {}", issuerId);
            }
        }
    }

    SweepResult runSweep(UUID issuerId) {
        return executeSweepInternal(issuerId, null);
    }

    SweepResult runAugmentedSweepForSubject(UUID issuerId, String verifiedSubject) {
        if (verifiedSubject == null || !CANONICAL_UUID_PATTERN.matcher(verifiedSubject).matches()) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-SUBJECT-INVALID",
                "Invalid Canonical OIDC Subject",
                "MC-FED-01 requires OIDC sub to be a canonical lowercase UUID."
            );
        }
        SweepResult result = executeSweepInternal(issuerId, verifiedSubject);
        if (!result.issuerFresh() || !result.augmentedSubjectEnabled()) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-CALLBACK-LIFECYCLE-DENIED",
                "OIDC Subject Lifecycle Verification Failed",
                "Augmented provider lifecycle sweep did not verify active subject state."
            );
        }
        return result;
    }

    /**
     * Narrow recovery-proof single-subject verification (Document 47 §4) for RECOVERING/ADMIN or VALIDATING:
     * verifies exact proved subject without running a multi-user sweep or granting PROVIDER permissions.
     */
    Instant verifyRecoveryTargetSubject(UUID issuerId, String verifiedSubject) {
        if (verifiedSubject == null || !CANONICAL_UUID_PATTERN.matcher(verifiedSubject).matches()) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-SUBJECT-INVALID",
                "Invalid Canonical OIDC Subject",
                "MC-FED-01 recovery proof requires canonical UUID subject."
            );
        }
        CapturedSweepContext captured = captureSweepContext(issuerId, true);
        Instant t0 = clockSource.instant();
        long monoStart = clockSource.nanoTime();
        long deadlineNano = monoStart + CYCLE_DEADLINE_NANOS;

        SubjectObservation obs = observeSingleSubjectBracketed(captured, verifiedSubject, deadlineNano);
        long monoEnd = clockSource.nanoTime();
        Instant t1 = clockSource.instant();

        if (!obs.globalHealthy() || !obs.subjectEnabled() || !isClockAndDeadlineValid(t0, t1, monoStart, monoEnd)) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-OIDC-RECOVERY-PROOF-FAILED",
                "Recovery Subject Verification Failed",
                "Target-bound recovery OIDC observation failed or exceeded timing bounds."
            );
        }
        return t0.minusSeconds(CONSERVATIVE_SUBTRACTION_SECONDS);
    }

    private SweepResult executeSweepInternal(UUID issuerId, String augmentedSubject) {
        synchronized (sweepMonitor) {
            CapturedSweepContext captured = captureSweepContext(issuerId, false);
            Instant t0 = clockSource.instant();
            long monoStart = clockSource.nanoTime();
            long deadlineNano = monoStart + CYCLE_DEADLINE_NANOS;

            Set<String> subjectsToObserve = new LinkedHashSet<>();
            for (CapturedLinkRow link : captured.links()) {
                subjectsToObserve.add(link.subject());
            }
            if (augmentedSubject != null) {
                subjectsToObserve.add(augmentedSubject);
            }

            // Empty link inventory without augmented subject cannot establish provider freshness (Doc 47 §4 step 7)
            if (subjectsToObserve.isEmpty()) {
                return new SweepResult(false, null, false, "EMPTY_LINK_INVENTORY");
            }

            Map<String, SubjectObservation> observations = new LinkedHashMap<>();
            List<Callable<Map.Entry<String, SubjectObservation>>> tasks = new ArrayList<>();
            for (String subj : subjectsToObserve) {
                tasks.add(() -> Map.entry(
                    subj,
                    observeSingleSubjectBracketed(captured, subj, deadlineNano)
                ));
            }

            boolean globalError = false;
            String failureReason = null;
            List<Future<Map.Entry<String, SubjectObservation>>> futures = new ArrayList<>();
            for (Callable<Map.Entry<String, SubjectObservation>> task : tasks) {
                futures.add(readExecutor.submit(task));
            }

            for (Future<Map.Entry<String, SubjectObservation>> future : futures) {
                long remainingNanos = deadlineNano - clockSource.nanoTime();
                if (remainingNanos <= 0L) {
                    future.cancel(true);
                    globalError = true;
                    failureReason = "CYCLE_DEADLINE_EXCEEDED";
                    continue;
                }
                try {
                    Map.Entry<String, SubjectObservation> entry = future.get(remainingNanos, TimeUnit.NANOSECONDS);
                    observations.put(entry.getKey(), entry.getValue());
                    if (!entry.getValue().globalHealthy()) {
                        globalError = true;
                        failureReason = entry.getValue().errorReason();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    globalError = true;
                    failureReason = "INTERRUPTED";
                } catch (ExecutionException | TimeoutException ex) {
                    future.cancel(true);
                    globalError = true;
                    failureReason = "EXECUTION_TIMEOUT_OR_ERROR";
                }
            }

            Runnable hook = this.postReadBeforeReconcileHook;
            if (hook != null) {
                hook.run();
            }

            long monoEnd = clockSource.nanoTime();
            Instant t1 = clockSource.instant();
            if (!isClockAndDeadlineValid(t0, t1, monoStart, monoEnd)) {
                clearIssuerFreshnessIfLeaseHeld(issuerId);
                return new SweepResult(false, null, false, "CLOCK_OR_DEADLINE_INVALID");
            }

            if (globalError) {
                clearIssuerFreshnessIfLeaseHeld(issuerId);
                return new SweepResult(false, null, false, failureReason);
            }

            Instant s = t0.minusSeconds(CONSERVATIVE_SUBTRACTION_SECONDS);
            Set<UUID> existingCompanyIds = loadExistingCompanyIds();

            boolean allLinksProcessedCleanly = true;
            for (CapturedLinkRow link : captured.links()) {
                SubjectObservation obs = observations.get(link.subject());
                if (obs == null) {
                    allLinksProcessedCleanly = false;
                    continue;
                }
                boolean applied = reconcileSingleCapturedLink(
                    captured,
                    link,
                    obs,
                    s,
                    existingCompanyIds
                );
                if (!applied) {
                    allLinksProcessedCleanly = false;
                }
            }

            boolean augEnabled = augmentedSubject != null
                && observations.containsKey(augmentedSubject)
                && observations.get(augmentedSubject).subjectEnabled();

            if (!allLinksProcessedCleanly) {
                return new SweepResult(false, null, augEnabled, "STALE_CAPTURE_DISCARDED");
            }

            boolean issuerStamped = commitIssuerFreshnessIfEligible(captured, s);
            return new SweepResult(issuerStamped, issuerStamped ? s : null, augEnabled, issuerStamped ? "OK" : "NOT_ELIGIBLE");
        }
    }

    private SubjectObservation observeSingleSubjectBracketed(
        CapturedSweepContext ctx,
        String subject,
        long deadlineNano
    ) {
        if (!CANONICAL_UUID_PATTERN.matcher(subject).matches()) {
            return SubjectObservation.perLinkNegative("NON_UUID_SUBJECT");
        }
        String realmSegment = percentEncodePathSegment(ctx.config().lifecycle().realm());
        String subjectSegment = percentEncodePathSegment(subject);
        String base = stripTrailingSlash(ctx.config().lifecycle().adminBaseUri());
        URI userUri = URI.create(base + "/admin/realms/" + realmSegment + "/users/" + subjectSegment);

        // Step 1: Opening user GET
        HttpJsonResult opening = sendAdminGetWithSingle401Retry(ctx, userUri, deadlineNano);
        if (opening.globalFailure()) {
            return SubjectObservation.globalFailure(opening.errorReason());
        }
        if (opening.statusCode() == 404) {
            return SubjectObservation.perLinkNegative("USER_NOT_FOUND_404");
        }
        UserReadState openUser = parseUserRepresentation(
            opening.json(),
            subject,
            ctx.config().lifecycle().federationComponentId()
        );
        if (!openUser.validSchema()) {
            return SubjectObservation.globalFailure("MALFORMED_OPENING_USER");
        }
        if (!openUser.matchedBinding() || !openUser.enabled()) {
            return SubjectObservation.perLinkNegative("OPENING_USER_DISABLED_OR_UNMATCHED");
        }

        // Step 2: Complete paged direct group list (?first=0&max=100&briefRepresentation=true)
        Set<UUID> observedGroups = new LinkedHashSet<>();
        for (int page = 0; page < MAX_GROUP_PAGES; page++) {
            int first = page * PAGE_SIZE;
            URI groupsUri = URI.create(
                base + "/admin/realms/" + realmSegment + "/users/" + subjectSegment
                    + "/groups?first=" + first + "&max=" + PAGE_SIZE + "&briefRepresentation=true"
            );
            HttpJsonResult groupResp = sendAdminGetWithSingle401Retry(ctx, groupsUri, deadlineNano);
            if (groupResp.globalFailure()) {
                return SubjectObservation.globalFailure(groupResp.errorReason());
            }
            if (groupResp.statusCode() != 200 || groupResp.json() == null || !groupResp.json().isArray()) {
                return SubjectObservation.globalFailure("GROUP_PAGE_INVALID_STATUS_" + groupResp.statusCode());
            }
            JsonNode arr = groupResp.json();
            int count = arr.size();
            if (count > PAGE_SIZE) {
                return SubjectObservation.globalFailure("GROUP_PAGE_OVERSIZED");
            }
            if (page == MAX_GROUP_PAGES - 1 && count > (MAX_TOTAL_GROUPS - first)) {
                return SubjectObservation.globalFailure("GROUP_COUNT_OVERFLOW_4096");
            }
            for (JsonNode item : arr) {
                if (!item.isObject()) {
                    return SubjectObservation.globalFailure("MALFORMED_GROUP_ENTRY");
                }
                JsonNode idNode = item.get("id");
                if (idNode == null || !idNode.isString() || !CANONICAL_UUID_PATTERN.matcher(idNode.asString()).matches()) {
                    return SubjectObservation.globalFailure("NON_UUID_GROUP_ID");
                }
                UUID groupId = UUID.fromString(idNode.asString());
                if (!observedGroups.add(groupId)) {
                    return SubjectObservation.globalFailure("DUPLICATE_GROUP_ID_IN_RESPONSE");
                }
            }
            if (count < PAGE_SIZE) {
                break;
            }
        }

        // Step 3: Closing user GET
        HttpJsonResult closing = sendAdminGetWithSingle401Retry(ctx, userUri, deadlineNano);
        if (closing.globalFailure()) {
            return SubjectObservation.globalFailure(closing.errorReason());
        }
        if (closing.statusCode() == 404) {
            return SubjectObservation.perLinkNegative("CLOSING_USER_404");
        }
        UserReadState closeUser = parseUserRepresentation(
            closing.json(),
            subject,
            ctx.config().lifecycle().federationComponentId()
        );
        if (!closeUser.validSchema()) {
            return SubjectObservation.globalFailure("MALFORMED_CLOSING_USER");
        }
        if (!closeUser.matchedBinding() || !closeUser.enabled()) {
            return SubjectObservation.perLinkNegative("CLOSING_USER_MISMATCH_OR_DISABLED");
        }

        return SubjectObservation.positive(Set.copyOf(observedGroups));
    }

    private UserReadState parseUserRepresentation(
        JsonNode node,
        String expectedSubject,
        UUID expectedFederationComponentId
    ) {
        if (node == null || !node.isObject()) {
            return new UserReadState(false, false, false);
        }
        JsonNode idNode = node.get("id");
        JsonNode enabledNode = node.get("enabled");
        JsonNode fedLinkNode = node.get("federationLink");
        if (idNode == null || !idNode.isString() || enabledNode == null || !enabledNode.isBoolean()) {
            return new UserReadState(false, false, false);
        }
        String actualId = idNode.asString();
        if (!CANONICAL_UUID_PATTERN.matcher(actualId).matches()) {
            return new UserReadState(false, false, false);
        }
        String fedLink = (fedLinkNode != null && fedLinkNode.isString()) ? fedLinkNode.asString() : "";
        boolean bindingOk = expectedSubject.equals(actualId)
            && expectedFederationComponentId.toString().equals(fedLink);
        return new UserReadState(true, bindingOk, enabledNode.booleanValue());
    }

    private HttpJsonResult sendAdminGetWithSingle401Retry(
        CapturedSweepContext ctx,
        URI targetUri,
        long deadlineNano
    ) {
        String bearer;
        try {
            bearer = obtainLifecycleBearerToken(ctx, false, deadlineNano);
        } catch (LifecycleTransportException ex) {
            return HttpJsonResult.failure(ex.reason());
        }

        HttpJsonResult first = executeBoundedGet(targetUri, bearer, deadlineNano);
        if (first.statusCode() == 401) {
            tokenCache.remove(ctx.issuerId());
            String freshBearer;
            try {
                freshBearer = obtainLifecycleBearerToken(ctx, true, deadlineNano);
            } catch (LifecycleTransportException ex) {
                return HttpJsonResult.failure(ex.reason());
            }
            HttpJsonResult retry = executeBoundedGet(targetUri, freshBearer, deadlineNano);
            if (retry.statusCode() == 401 || retry.statusCode() == 403) {
                return HttpJsonResult.failure("SECOND_401_OR_403_PROFILE_FAILURE");
            }
            return retry;
        }
        if (first.statusCode() == 403) {
            return HttpJsonResult.failure("FORBIDDEN_403_PROFILE_FAILURE");
        }
        return first;
    }

    private String obtainLifecycleBearerToken(
        CapturedSweepContext ctx,
        boolean forceRefresh,
        long deadlineNano
    ) throws LifecycleTransportException {
        Instant now = clockSource.instant();
        if (!forceRefresh) {
            CachedClientToken cached = tokenCache.get(ctx.issuerId());
            if (cached != null && now.isBefore(cached.refreshBefore())) {
                return cached.accessToken();
            }
        }

        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNano - clockSource.nanoTime());
        if (remainingMs <= 0L) {
            throw new LifecycleTransportException("TOKEN_CYCLE_DEADLINE_EXCEEDED");
        }

        String form = "grant_type=client_credentials"
            + "&client_id=" + urlEncodeForm(ctx.config().lifecycle().lifecycleClientId())
            + "&client_secret=" + urlEncodeForm(ctx.lifecycleSecret());

        Duration reqTimeout = Duration.ofMillis(Math.min(5_000L, Math.max(100L, remainingMs)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(ctx.config().tokenUri()))
            .timeout(reqTimeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
            byte[] body = readBoundedBytes(resp.body(), MAX_RESPONSE_BYTES);
            if (code != 200) {
                throw new LifecycleTransportException("TOKEN_HTTP_" + code);
            }
            JsonNode json = parseBoundedJson(body);
            JsonNode tokNode = json.get("access_token");
            if (tokNode == null || !tokNode.isString() || tokNode.asString().isBlank()) {
                throw new LifecycleTransportException("TOKEN_MISSING_ACCESS_TOKEN");
            }
            long expiresIn = json.hasNonNull("expires_in") && json.get("expires_in").isNumber()
                ? json.get("expires_in").longValue()
                : 60L;
            long safeLifetime = Math.max(1L, expiresIn - 5L);
            Instant refreshBefore = clockSource.instant().plusSeconds(safeLifetime);
            String token = tokNode.asString();
            tokenCache.put(ctx.issuerId(), new CachedClientToken(token, refreshBefore));
            return token;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LifecycleTransportException("TOKEN_IO_OR_TIMEOUT");
        }
    }

    private HttpJsonResult executeBoundedGet(URI uri, String bearerToken, long deadlineNano) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNano - clockSource.nanoTime());
        if (remainingMs <= 0L) {
            return HttpJsonResult.failure("REQUEST_CYCLE_DEADLINE_EXCEEDED");
        }
        Duration reqTimeout = Duration.ofMillis(Math.min(5_000L, Math.max(100L, remainingMs)));
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(reqTimeout)
            .header("Authorization", "Bearer " + bearerToken)
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            byte[] body = readBoundedBytes(resp.body(), MAX_RESPONSE_BYTES);
            if (status == 401 || status == 403 || status == 404) {
                return new HttpJsonResult(status, null, false, null);
            }
            if (status != 200) {
                return HttpJsonResult.failure("UNEXPECTED_HTTP_STATUS_" + status);
            }
            JsonNode parsed = parseBoundedJson(body);
            return new HttpJsonResult(200, parsed, false, null);
        } catch (LifecycleTransportException ex) {
            return HttpJsonResult.failure(ex.reason());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return HttpJsonResult.failure("HTTP_IO_OR_TIMEOUT");
        }
    }

    private boolean reconcileSingleCapturedLink(
        CapturedSweepContext captured,
        CapturedLinkRow capturedLink,
        SubjectObservation obs,
        Instant observationInstant,
        Set<UUID> existingCompanyIds
    ) {
        if (!authorityGuard.isLeaseHealthy()) {
            return false;
        }
        AuthorityGuard.GuardSnapshot guardSnap = authorityGuard.cachedSnapshot();
        if (guardSnap.securityState() != InstallationSecurityState.ACTIVE
            || guardSnap.fenceEpoch() != captured.fenceEpoch()) {
            return false;
        }

        // Revalidate current rows under exclusive AuthorityGuard before applying observation
        List<RevalidatedLinkState> currentRows = authorityJdbcTemplate.query(
            """
            SELECT s.incarnation,
                   i.state AS issuer_state,
                   i.version_no AS issuer_v,
                   l.state AS link_state,
                   l.version_no AS link_v,
                   p.status AS principal_status,
                   p.auth_authority
              FROM ma_state s
              JOIN ma_issuer i ON i.installation_id = s.installation_id AND i.id = ?
              JOIN ma_oidc_link l ON l.issuer_id = i.id AND l.id = ?
              JOIN ma_principal p ON p.principal_id = l.principal_id
             WHERE s.singleton = 1
            """,
            (rs, rowNum) -> new RevalidatedLinkState(
                rs.getObject("incarnation", UUID.class),
                rs.getString("issuer_state"),
                rs.getLong("issuer_v"),
                rs.getString("link_state"),
                rs.getLong("link_v"),
                rs.getString("principal_status"),
                rs.getString("auth_authority")
            ),
            captured.issuerId(),
            capturedLink.linkId()
        );

        if (currentRows.isEmpty()) {
            return false;
        }
        RevalidatedLinkState cur = currentRows.getFirst();
        if (!captured.incarnation().equals(cur.incarnation())
            || !"ENABLED".equals(cur.issuerState())
            || cur.issuerVersion() != captured.issuerVersion()
            || !"ACTIVE".equals(cur.linkState())
            || cur.linkVersion() != capturedLink.linkVersion()
            || !Objects.equals(capturedLink.authAuthority(), cur.authAuthority())) {
            return false;
        }

        if (!obs.subjectEnabled()) {
            // Disabled or absent provider identity: clear checked_at, revoke PROVIDER grants, delete OIDC sessions
            invalidateSinglePersonOnNegativeObservation(
                captured.installationId(),
                captured.issuerId(),
                capturedLink.linkId(),
                capturedLink.principalId()
            );
            return true;
        }

        // Compute desired PROVIDER grant tuples from observed group UUIDs
        List<DesiredProviderGrant> desired = computeDesiredProviderGrants(
            captured.issuerId(),
            captured.config(),
            obs.groupIds(),
            existingCompanyIds
        );

        boolean semanticGrantChange = syncProviderGrantsAndStampLink(
            captured.installationId(),
            captured.issuerId(),
            capturedLink.linkId(),
            capturedLink.principalId(),
            desired,
            observationInstant
        );

        if (semanticGrantChange) {
            recordInternalAuthorityOperation(
                captured.installationId(),
                "provider.reconcile",
                "OIDC_LINK",
                capturedLink.linkId().toString(),
                Map.of(
                    "issuerId", captured.issuerId().toString(),
                    "linkId", capturedLink.linkId().toString(),
                    "principalId", capturedLink.principalId().toString(),
                    "activeProviderGrants", desired.size()
                )
            );
        }
        return true;
    }

    private List<DesiredProviderGrant> computeDesiredProviderGrants(
        UUID issuerId,
        IssuerConfig2 config,
        Set<UUID> observedGroupIds,
        Set<UUID> existingCompanyIds
    ) {
        List<DesiredProviderGrant> list = new ArrayList<>();
        for (IssuerConfig2.GroupMapping gm : config.lifecycle().groupMappings()) {
            if (!observedGroupIds.contains(gm.groupId())) {
                continue;
            }
            String sourceRef = "oidc:" + issuerId + ":group:" + gm.groupId();
            for (IssuerConfig2.GroupGrantTuple tuple : gm.grants()) {
                if (!existingCompanyIds.contains(tuple.companyId())) {
                    continue;
                }
                list.add(new DesiredProviderGrant(
                    tuple.companyId(),
                    tuple.operation(),
                    tuple.fieldGroup(),
                    sourceRef
                ));
            }
        }
        list.sort(Comparator.comparing(DesiredProviderGrant::canonicalKey));
        return list;
    }

    private boolean syncProviderGrantsAndStampLink(
        UUID installationId,
        UUID issuerId,
        UUID linkId,
        UUID principalId,
        List<DesiredProviderGrant> desired,
        Instant observationInstant
    ) {
        String sourcePrefix = "oidc:" + issuerId + ":group:%";
        List<ExistingProviderGrantRow> existingRows = authorityJdbcTemplate.query(
            """
            SELECT grant_id, company_id, permissions::text AS perms_json, field_group, state, source_ref
              FROM ma_grant
             WHERE principal_id = ?
               AND source = 'PROVIDER'
               AND source_ref LIKE ?
             ORDER BY created_at ASC, grant_id ASC
            """,
            (rs, rowNum) -> new ExistingProviderGrantRow(
                rs.getObject("grant_id", UUID.class),
                rs.getObject("company_id", UUID.class),
                extractSinglePermission(rs.getString("perms_json")),
                rs.getString("field_group"),
                rs.getString("state"),
                rs.getString("source_ref")
            ),
            principalId,
            sourcePrefix
        );

        Map<String, ExistingProviderGrantRow> existingByKey = new LinkedHashMap<>();
        for (ExistingProviderGrantRow row : existingRows) {
            existingByKey.putIfAbsent(row.canonicalKey(), row);
        }

        Set<String> desiredKeys = new LinkedHashSet<>();
        for (DesiredProviderGrant d : desired) {
            desiredKeys.add(d.canonicalKey());
        }

        List<DesiredProviderGrant> toInsert = new ArrayList<>();
        List<ExistingProviderGrantRow> toReactivate = new ArrayList<>();
        List<ExistingProviderGrantRow> toRevoke = new ArrayList<>();

        for (DesiredProviderGrant d : desired) {
            ExistingProviderGrantRow match = existingByKey.get(d.canonicalKey());
            if (match == null) {
                toInsert.add(d);
            } else if (!"ACTIVE".equals(match.state())) {
                toReactivate.add(match);
            }
        }
        for (ExistingProviderGrantRow row : existingRows) {
            if ("ACTIVE".equals(row.state()) && !desiredKeys.contains(row.canonicalKey())) {
                toRevoke.add(row);
            }
        }

        if (toInsert.isEmpty() && toReactivate.isEmpty() && toRevoke.isEmpty()) {
            // Pure timestamp-only renewal: zero version_no or authority_epoch churn!
            authorityTxTemplate.executeWithoutResult(status -> {
                authorityJdbcTemplate.execute("SET LOCAL ma.bookkeeping_checked_at_only = 'on'");
                authorityJdbcTemplate.update(
                    "UPDATE ma_oidc_link SET checked_at = ? WHERE id = ?",
                    Timestamp.from(observationInstant),
                    linkId
                );
            });
            return false;
        }

        Set<UUID> touchedCompanies = new LinkedHashSet<>();
        authorityTxTemplate.executeWithoutResult(status -> {
            for (DesiredProviderGrant d : toInsert) {
                touchedCompanies.add(d.companyId());
                long grantEpoch = bumpGrantGuard(principalId, d.companyId());
                String permsJson = "[\"" + d.operation() + "\"]";
                boolean exportFlag = "export".equals(d.operation());
                authorityJdbcTemplate.update(
                    """
                    INSERT INTO ma_grant (
                        grant_id, principal_id, company_id, role_code, permissions,
                        voucher_type_allowlist, backdated_days_limit, export_permitted,
                        private_fields_permitted, grant_epoch, state, granted_by,
                        source, source_ref, field_group
                    ) VALUES (?, ?, ?, ?, ?::jsonb, '[]'::jsonb, NULL, ?, ?, ?, 'ACTIVE', NULL, 'PROVIDER', ?, ?)
                    """,
                    UUID.randomUUID(),
                    principalId,
                    d.companyId(),
                    CompanyRole.READ_ONLY_VIEWER.name(),
                    permsJson,
                    exportFlag,
                    !"NONE".equals(d.fieldGroup()),
                    grantEpoch,
                    d.sourceRef(),
                    d.fieldGroup()
                );
            }

            for (ExistingProviderGrantRow r : toReactivate) {
                touchedCompanies.add(r.companyId());
                long grantEpoch = bumpGrantGuard(principalId, r.companyId());
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_grant
                       SET state = 'ACTIVE',
                           grant_epoch = ?,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE grant_id = ?
                    """,
                    grantEpoch,
                    r.grantId()
                );
            }

            for (ExistingProviderGrantRow r : toRevoke) {
                touchedCompanies.add(r.companyId());
                long grantEpoch = bumpGrantGuard(principalId, r.companyId());
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_grant
                       SET state = 'REVOKED',
                           grant_epoch = ?,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE grant_id = ?
                    """,
                    grantEpoch,
                    r.grantId()
                );
            }

            // Changed mapped grants revoke that person's current OIDC sessions so stale sessions never conceal changes
            deleteOidcSessionsForPrincipalAndIssuer(principalId, issuerId);

            authorityJdbcTemplate.execute("SET LOCAL ma.bookkeeping_checked_at_only = 'on'");
            authorityJdbcTemplate.update(
                "UPDATE ma_oidc_link SET checked_at = ? WHERE id = ?",
                Timestamp.from(observationInstant),
                linkId
            );
            authorityJdbcTemplate.update(
                """
                UPDATE ma_state
                   SET authority_epoch = authority_epoch + 1,
                       updated_at = clock_timestamp()
                 WHERE installation_id = ?
                """,
                installationId
            );
        });

        syncBusinessGrantProjectionForPrincipalCompanies(principalId, touchedCompanies);
        return true;
    }

    private void invalidateSinglePersonOnNegativeObservation(
        UUID installationId,
        UUID issuerId,
        UUID linkId,
        UUID principalId
    ) {
        String sourcePrefix = "oidc:" + issuerId + ":group:%";
        Set<UUID> touchedCompanies = new LinkedHashSet<>();

        Boolean semanticChanged = authorityTxTemplate.execute(status -> {
            List<UUID> activeGrantCompanies = authorityJdbcTemplate.query(
                """
                SELECT DISTINCT company_id
                  FROM ma_grant
                 WHERE principal_id = ?
                   AND source = 'PROVIDER'
                   AND source_ref LIKE ?
                   AND state = 'ACTIVE'
                """,
                (rs, rowNum) -> rs.getObject("company_id", UUID.class),
                principalId,
                sourcePrefix
            );
            touchedCompanies.addAll(activeGrantCompanies);

            int revokedGrants = 0;
            for (UUID companyId : activeGrantCompanies) {
                long epoch = bumpGrantGuard(principalId, companyId);
                revokedGrants += authorityJdbcTemplate.update(
                    """
                    UPDATE ma_grant
                       SET state = 'REVOKED',
                           grant_epoch = ?,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE principal_id = ?
                       AND company_id = ?
                       AND source = 'PROVIDER'
                       AND source_ref LIKE ?
                       AND state = 'ACTIVE'
                    """,
                    epoch,
                    principalId,
                    companyId,
                    sourcePrefix
                );
            }

            int deletedSessions = deleteOidcSessionsForPrincipalAndIssuer(principalId, issuerId);
            int deletedChallenges = deletePendingOidcChallengesForIssuerAndPrincipal(issuerId, principalId);

            if (revokedGrants > 0 || deletedSessions > 0 || deletedChallenges > 0) {
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_oidc_link
                       SET checked_at = NULL,
                           version_no = version_no + 1
                     WHERE id = ?
                    """,
                    linkId
                );
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_state
                       SET authority_epoch = authority_epoch + 1,
                           updated_at = clock_timestamp()
                     WHERE installation_id = ?
                    """,
                    installationId
                );
                return true;
            }

            authorityJdbcTemplate.execute("SET LOCAL ma.bookkeeping_checked_at_only = 'on'");
            authorityJdbcTemplate.update(
                "UPDATE ma_oidc_link SET checked_at = NULL WHERE id = ? AND checked_at IS NOT NULL",
                linkId
            );
            return false;
        });

        if (!touchedCompanies.isEmpty()) {
            syncBusinessGrantProjectionForPrincipalCompanies(principalId, touchedCompanies);
        }
        if (Boolean.TRUE.equals(semanticChanged)) {
            recordInternalAuthorityOperation(
                installationId,
                "provider.invalidate",
                "OIDC_LINK",
                linkId.toString(),
                Map.of(
                    "issuerId", issuerId.toString(),
                    "linkId", linkId.toString(),
                    "principalId", principalId.toString(),
                    "reason", "NEGATIVE_PROVIDER_OBSERVATION"
                )
            );
        }
    }

    private void invalidateSinglePersonForIssuerChange(
        UUID installationId,
        UUID issuerId,
        LinkSnapshot link
    ) {
        String sourcePrefix = "oidc:" + issuerId + ":group:%";
        Set<UUID> touchedCompanies = new LinkedHashSet<>();

        Boolean semanticChanged = authorityTxTemplate.execute(status -> {
            List<UUID> activeCompanies = authorityJdbcTemplate.query(
                """
                SELECT DISTINCT company_id
                  FROM ma_grant
                 WHERE principal_id = ?
                   AND source = 'PROVIDER'
                   AND source_ref LIKE ?
                   AND state = 'ACTIVE'
                """,
                (rs, rowNum) -> rs.getObject("company_id", UUID.class),
                link.principalId(),
                sourcePrefix
            );
            touchedCompanies.addAll(activeCompanies);

            int revoked = 0;
            for (UUID companyId : activeCompanies) {
                long epoch = bumpGrantGuard(link.principalId(), companyId);
                revoked += authorityJdbcTemplate.update(
                    """
                    UPDATE ma_grant
                       SET state = 'REVOKED',
                           grant_epoch = ?,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE principal_id = ?
                       AND company_id = ?
                       AND source = 'PROVIDER'
                       AND source_ref LIKE ?
                       AND state = 'ACTIVE'
                    """,
                    epoch,
                    link.principalId(),
                    companyId,
                    sourcePrefix
                );
            }

            int deletedSessions = deleteOidcSessionsForPrincipalAndIssuer(link.principalId(), issuerId);
            int deletedChallenges = deletePendingOidcChallengesForIssuerAndPrincipal(issuerId, link.principalId());

            if (revoked > 0 || deletedSessions > 0 || deletedChallenges > 0) {
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_oidc_link
                       SET checked_at = NULL,
                           version_no = version_no + 1
                     WHERE id = ?
                    """,
                    link.linkId()
                );
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_state
                       SET authority_epoch = authority_epoch + 1,
                           updated_at = clock_timestamp()
                     WHERE installation_id = ?
                    """,
                    installationId
                );
                return true;
            }
            return false;
        });

        if (!touchedCompanies.isEmpty()) {
            syncBusinessGrantProjectionForPrincipalCompanies(link.principalId(), touchedCompanies);
        }
        if (Boolean.TRUE.equals(semanticChanged)) {
            recordInternalAuthorityOperation(
                installationId,
                "provider.invalidate",
                "OIDC_LINK",
                link.linkId().toString(),
                Map.of(
                    "issuerId", issuerId.toString(),
                    "linkId", link.linkId().toString(),
                    "principalId", link.principalId().toString(),
                    "reason", "PROVIDER_PROFILE_REPLACEMENT"
                )
            );
        }
    }

    private boolean commitIssuerFreshnessIfEligible(CapturedSweepContext captured, Instant s) {
        if (!authorityGuard.isLeaseHealthy()) {
            return false;
        }
        AuthorityGuard.GuardSnapshot snap = authorityGuard.cachedSnapshot();
        if (snap.securityState() != InstallationSecurityState.ACTIVE
            || snap.fenceEpoch() != captured.fenceEpoch()) {
            return false;
        }

        Boolean committed = authorityTxTemplate.execute(status -> {
            List<IssuerEligibilityRow> checkRows = authorityJdbcTemplate.query(
                """
                SELECT s.incarnation, i.state, i.version_no, i.config_digest
                  FROM ma_state s
                  JOIN ma_issuer i ON i.installation_id = s.installation_id AND i.id = ?
                 WHERE s.singleton = 1
                """,
                (rs, rowNum) -> new IssuerEligibilityRow(
                    rs.getObject("incarnation", UUID.class),
                    rs.getString("state"),
                    rs.getLong("version_no"),
                    rs.getString("config_digest")
                ),
                captured.issuerId()
            );
            if (checkRows.isEmpty()) {
                return false;
            }
            IssuerEligibilityRow cur = checkRows.getFirst();
            if (!captured.incarnation().equals(cur.incarnation())
                || !"ENABLED".equals(cur.state())
                || cur.versionNo() != captured.issuerVersion()
                || !Objects.equals(captured.configDigest(), cur.configDigest())) {
                return false;
            }

            // Require every currently ACTIVE link to have checked_at >= s or NULL (no stale positive stamps!)
            Integer stalePositiveLinks = authorityJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM ma_oidc_link
                 WHERE issuer_id = ?
                   AND state = 'ACTIVE'
                   AND checked_at IS NOT NULL
                   AND checked_at < ?
                """,
                Integer.class,
                captured.issuerId(),
                Timestamp.from(s)
            );
            if (stalePositiveLinks != null && stalePositiveLinks > 0) {
                return false;
            }

            authorityJdbcTemplate.execute("SET LOCAL ma.bookkeeping_checked_at_only = 'on'");
            authorityJdbcTemplate.update(
                "UPDATE ma_issuer SET checked_at = ? WHERE id = ?",
                Timestamp.from(s),
                captured.issuerId()
            );
            return true;
        });

        return Boolean.TRUE.equals(committed);
    }

    private void clearIssuerFreshnessIfLeaseHeld(UUID issuerId) {
        if (!authorityGuard.isLeaseHealthy()) {
            return;
        }
        authorityTxTemplate.executeWithoutResult(status -> {
            authorityJdbcTemplate.execute("SET LOCAL ma.bookkeeping_checked_at_only = 'on'");
            authorityJdbcTemplate.update(
                "UPDATE ma_issuer SET checked_at = NULL WHERE id = ? AND checked_at IS NOT NULL",
                issuerId
            );
        });
    }

    private CapturedSweepContext captureSweepContext(UUID issuerId, boolean allowRecoveryPhase) {
        List<AuthorityStateRow> authRows = readAuthorityState();
        if (authRows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-SYSTEM-UNINITIALISED",
                "Installation Not Initialised",
                "Cannot execute OIDC lifecycle sweep before initialisation."
            );
        }
        AuthorityStateRow auth = authRows.getFirst();
        AuthorityGuard.GuardSnapshot guardSnap = authorityGuard.cachedSnapshot();
        if (!allowRecoveryPhase && guardSnap.securityState() != InstallationSecurityState.ACTIVE) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-OIDC-SWEEP-FENCED",
                "OIDC Lifecycle Worker Fenced",
                "Ordinary OIDC sweep requires ACTIVE installation mode."
            );
        }

        ExistingIssuerRow issuer = findIssuerRowById(issuerId);
        if (issuer == null || !"ENABLED".equals(issuer.state())
            || !IssuerConfig2.PROFILE_MC_FED_01.equals(issuer.lifecycleProfile())) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-OIDC-ISSUER-UNAVAILABLE",
                "OIDC Federation Issuer Not Enabled",
                "Requested issuer is not an enabled MC-FED-01 provider."
            );
        }

        IssuerConfig2 config = IssuerConfig2.openAuthorityEnvelope(
            issuer.metadataCipher(),
            auth.installationId(),
            auth.incarnation(),
            issuer.id(),
            issuer.versionNo(),
            tinkCryptoService,
            jcsCanonicalizer,
            properties.getSecurity().isAllowTestProviderProfile()
        );
        String lifecycleSecret = resolveRequiredSecret(config.lifecycle().lifecycleSecretRef());

        List<CapturedLinkRow> links = authorityJdbcTemplate.query(
            """
            SELECT l.id, l.principal_id, l.subject, l.version_no, p.status, p.auth_authority
              FROM ma_oidc_link l
              JOIN ma_principal p ON p.principal_id = l.principal_id
             WHERE l.issuer_id = ? AND l.state = 'ACTIVE'
             ORDER BY l.id ASC
            """,
            (rs, rowNum) -> new CapturedLinkRow(
                rs.getObject("id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getString("subject"),
                rs.getLong("version_no"),
                rs.getString("status"),
                rs.getString("auth_authority")
            ),
            issuerId
        );

        return new CapturedSweepContext(
            auth.installationId(),
            auth.incarnation(),
            guardSnap.fenceEpoch(),
            issuer.id(),
            issuer.versionNo(),
            issuer.configDigest(),
            config,
            lifecycleSecret,
            links
        );
    }

    private void syncBusinessGrantProjectionForPrincipalCompanies(UUID principalId, Set<UUID> companyIds) {
        if (companyIds.isEmpty()) {
            return;
        }
        TenantContextHolder.callWithSystemBypass("oidc-provider-grant-projection", () -> {
            businessTxTemplate.executeWithoutResult(status -> {
                ensurePrincipalProjectedInBusiness(principalId);
                for (UUID companyId : companyIds) {
                    List<RoleGrantAggregate> roleAggs = loadAuthorityGrantAggregates(principalId, companyId);
                    for (RoleGrantAggregate agg : roleAggs) {
                        String permsJson = toJsonString(agg.mergedPermissions());
                        byte[] digest = jcsCanonicalizer.sha256(
                            ("GRANT_PROJ|" + principalId + "|" + companyId + "|" + agg.roleCode() + "|" + agg.active())
                                .getBytes(StandardCharsets.UTF_8)
                        );
                        businessJdbcTemplate.update(
                            """
                            INSERT INTO mc_principal_grant (
                                grant_id, principal_id, company_id, role_code, permissions,
                                voucher_type_allowlist, backdated_days_limit, export_permitted,
                                private_fields_permitted, active, grant_epoch, authority_epoch, authority_digest
                            ) VALUES (?, ?, ?, ?, ?::jsonb, '[]'::jsonb, NULL, ?, true, ?, 1, 1, ?)
                            ON CONFLICT (principal_id, company_id, role_code) DO UPDATE
                               SET permissions = EXCLUDED.permissions,
                                   export_permitted = EXCLUDED.export_permitted,
                                   active = EXCLUDED.active,
                                   grant_epoch = mc_principal_grant.grant_epoch + 1,
                                   authority_digest = EXCLUDED.authority_digest,
                                   version_no = mc_principal_grant.version_no + 1,
                                   synced_at = clock_timestamp()
                            """,
                            agg.representativeGrantId(),
                            principalId,
                            companyId,
                            agg.roleCode(),
                            permsJson,
                            agg.exportPermitted(),
                            agg.active(),
                            digest
                        );
                    }
                }
            });
            return null;
        });
    }

    private List<RoleGrantAggregate> loadAuthorityGrantAggregates(UUID principalId, UUID companyId) {
        Map<String, List<RawGrantProjRow>> byRole = new LinkedHashMap<>();
        authorityJdbcTemplate.query(
            """
            SELECT grant_id, role_code, permissions::text AS perms_json, export_permitted, state
              FROM ma_grant
             WHERE principal_id = ? AND company_id = ?
             ORDER BY created_at ASC, grant_id ASC
            """,
            (rs, rowNum) -> {
                String role = rs.getString("role_code");
                byRole.computeIfAbsent(role, k -> new ArrayList<>()).add(new RawGrantProjRow(
                    rs.getObject("grant_id", UUID.class),
                    role,
                    rs.getString("perms_json"),
                    rs.getBoolean("export_permitted"),
                    rs.getString("state")
                ));
                return null;
            },
            principalId,
            companyId
        );

        List<RoleGrantAggregate> result = new ArrayList<>();
        for (Map.Entry<String, List<RawGrantProjRow>> entry : byRole.entrySet()) {
            List<RawGrantProjRow> rows = entry.getValue();
            UUID repId = rows.getFirst().grantId();
            Set<String> perms = new LinkedHashSet<>();
            boolean active = false;
            boolean export = false;
            for (RawGrantProjRow r : rows) {
                if ("ACTIVE".equals(r.state())) {
                    active = true;
                    export = export || r.exportPermitted();
                    perms.addAll(parseJsonStringList(r.permsJson()));
                }
            }
            result.add(new RoleGrantAggregate(repId, entry.getKey(), List.copyOf(perms), export, active));
        }
        return result;
    }

    private void ensurePrincipalProjectedInBusiness(UUID principalId) {
        List<Map<String, Object>> rows = authorityJdbcTemplate.queryForList(
            """
            SELECT kind, COALESCE(username::text, display_name) AS uname, display_name, status
              FROM ma_principal
             WHERE principal_id = ?
            """,
            principalId
        );
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> row = rows.getFirst();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_principal_projection (principal_id, kind, username, display_name, enabled, version_no)
            VALUES (?, ?, ?, ?, ?, 1)
            ON CONFLICT (principal_id) DO NOTHING
            """,
            principalId,
            row.get("kind"),
            row.get("uname"),
            row.get("display_name"),
            "ENABLED".equals(row.get("status"))
        );
    }

    private void recordInternalAuthorityOperation(
        UUID installationId,
        String operation,
        String entityType,
        String entityId,
        Map<String, Object> redactedPayload
    ) {
        TenantContextHolder.callWithSystemBypass("oidc-internal-authority-op", () -> {
            businessTxTemplate.executeWithoutResult(status -> {
                adminReceiptService.recordAndMirrorAdminReceipt(
                    installationId,
                    null,
                    null,
                    operation,
                    entityType,
                    entityId,
                    null,
                    null,
                    redactedPayload
                );
                auditChainService.appendInstallationEvent(
                    installationId,
                    null,
                    "SYSTEM",
                    operation,
                    entityType,
                    entityId,
                    "SUCCESS",
                    null,
                    redactedPayload
                );
            });
            return null;
        });
    }

    private long bumpGrantGuard(UUID principalId, UUID companyId) {
        byte[] digest = jcsCanonicalizer.sha256(
            ("PROVIDER_GUARD|" + principalId + "|" + companyId + "|" + UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8)
        );
        Long epoch = authorityJdbcTemplate.queryForObject(
            """
            INSERT INTO ma_grant_guard (principal_id, company_id, grant_epoch, guard_digest)
            VALUES (?, ?, 1, ?)
            ON CONFLICT (principal_id, company_id) DO UPDATE
               SET grant_epoch = ma_grant_guard.grant_epoch + 1,
                   guard_digest = EXCLUDED.guard_digest,
                   version_no = ma_grant_guard.version_no + 1,
                   updated_at = clock_timestamp()
            RETURNING grant_epoch
            """,
            Long.class,
            principalId,
            companyId,
            digest
        );
        return epoch == null ? 1L : epoch;
    }

    private int deleteOidcSessionsForPrincipalAndIssuer(UUID principalId, UUID issuerId) {
        String attrName = "MC_OIDC_BINDING:" + principalId + ":" + issuerId;
        return authorityJdbcTemplate.update(
            """
            DELETE FROM SPRING_SESSION
             WHERE PRIMARY_ID IN (
                 SELECT SESSION_PRIMARY_ID
                   FROM SPRING_SESSION_ATTRIBUTES
                  WHERE ATTRIBUTE_NAME = ?
             )
            """,
            attrName
        );
    }

    private int deletePendingOidcChallengesForIssuerAndPrincipal(UUID issuerId, UUID principalId) {
        return authorityJdbcTemplate.update(
            """
            DELETE FROM ma_auth_challenge
             WHERE purpose = 'OIDC_AUTHORIZE'
               AND consumed = false
               AND (
                   principal_id = ?
                   OR (principal_id IS NULL AND metadata_json ->> 'issuerId' = ?)
               )
            """,
            principalId,
            issuerId.toString()
        );
    }

    private Set<UUID> loadExistingCompanyIds() {
        List<UUID> ids = TenantContextHolder.callWithSystemBypass("load-company-ids-for-oidc", () ->
            businessJdbcTemplate.query(
                "SELECT id FROM mc_company",
                (rs, rowNum) -> rs.getObject("id", UUID.class)
            )
        );
        return new HashSet<>(ids);
    }

    private AuthorityStateRow requireMatchingAuthorityStateForConfig(IssuerConfig2.SignedProviderConfig signed) {
        List<AuthorityStateRow> rows = readAuthorityState();
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-SYSTEM-UNINITIALISED",
                "Installation Not Initialised",
                "Cannot ingest provider configuration before installation bootstrap."
            );
        }
        AuthorityStateRow state = rows.getFirst();
        if (!state.installationId().equals(signed.installationId())
            || !state.incarnation().equals(signed.incarnation())) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-PROVIDER-BINDING-MISMATCH",
                "Provider Configuration Installation or Incarnation Mismatch",
                "Signed provider configuration does not match current installationId or incarnation."
            );
        }

        if ("ACTIVE".equals(state.securityState())) {
            if (signed.incidentId() != null) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-PROVIDER-INCIDENT-INVALID",
                    "Unexpected Recovery Incident ID",
                    "incidentId must be null when installation security_state is ACTIVE."
                );
            }
            return state;
        }

        if ("RECOVERING".equals(state.securityState()) && signed.incidentId() != null) {
            Integer matchingIncident = authorityJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM ma_recovery
                 WHERE incident_id = ?
                   AND installation_id = ?
                   AND recovery_state IN ('INITIATED', 'VALIDATING')
                """,
                Integer.class,
                signed.incidentId(),
                state.installationId()
            );
            if (matchingIncident != null && matchingIncident > 0) {
                return state;
            }
        }

        throw new MoneyCraftException(
            HttpStatus.CONFLICT,
            "MC-PROVIDER-STATE-FORBIDDEN",
            "Provider Configuration Ingestion Not Admitted",
            "Startup provider configuration requires ACTIVE (incidentId=null) or active RECOVERING incident."
        );
    }

    private void revalidateConfigInstallGuards(
        AuthorityStateRow expectedAuth,
        UUID issuerId,
        Long expectedOldVersion
    ) {
        requireHealthyWriterLease();
        List<AuthorityStateRow> rows = readAuthorityState();
        if (rows.isEmpty()
            || !rows.getFirst().incarnation().equals(expectedAuth.incarnation())
            || !rows.getFirst().securityState().equals(expectedAuth.securityState())) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-PROVIDER-FENCE-CHANGED",
                "Authority State Changed During Provider Reconfiguration",
                "Installation incarnation or security_state changed during startup provider reconfiguration."
            );
        }
        ExistingIssuerRow current = findIssuerRowById(issuerId);
        if (expectedOldVersion == null) {
            if (current != null) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-PROVIDER-VERSION-MISMATCH",
                    "Concurrent Issuer Creation",
                    "ma_issuer appeared unexpectedly during new configuration installation."
                );
            }
        } else if (current == null || current.versionNo() != expectedOldVersion.longValue()) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-PROVIDER-VERSION-MISMATCH",
                "Issuer Version Changed During Cleanup",
                "ma_issuer version_no changed during bounded per-person invalidation."
            );
        }
    }

    private void requireHealthyWriterLease() {
        if (!authorityGuard.isLeaseHealthy()) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-AUTHORITY-LEASE-UNAVAILABLE",
                "Single-Writer Lease Unavailable",
                "Refusing OIDC lifecycle or freshness mutation without active AuthorityGuard writer lease."
            );
        }
    }

    private String resolveRequiredSecret(String secretRef) {
        String val = properties.getSecurity().getSecretRegistry().get(secretRef);
        if (val == null || val.isBlank() || "UNAVAILABLE".equalsIgnoreCase(val)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-PROVIDER-SECRET-UNRESOLVED",
                "Provider Secret Reference Unresolved",
                "Protected secret registry does not contain a valid secret for '" + secretRef + "'."
            );
        }
        if ("deterministic-test-secret".equals(val) && !properties.getSecurity().isAllowTestProviderProfile()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-PROVIDER-TEST-SECRET-FORBIDDEN",
                "Test Credential Refused",
                "Deterministic test credentials are refused outside test profile."
            );
        }
        return val;
    }

    private void verifyQualificationBinding(String qualificationRef, String expectedHexDigest) {
        String registered = properties.getSecurity().getEvidenceRegistry().get(qualificationRef);
        if (registered == null || registered.isBlank() || "UNAVAILABLE".equalsIgnoreCase(registered)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-PROVIDER-QUALIFICATION-MISSING",
                "Provider Qualification Evidence Missing",
                "Protected evidence registry has no executed qualification record for '" + qualificationRef + "'."
            );
        }
        String normalized = registered.trim().toLowerCase();
        String actualDigest = normalized.matches("^[0-9a-f]{64}$")
            ? normalized
            : JcsCanonicalizer.toHex(jcsCanonicalizer.sha256(registered.getBytes(StandardCharsets.UTF_8)));
        if (!expectedHexDigest.equals(actualDigest)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-PROVIDER-QUALIFICATION-DIGEST-MISMATCH",
                "Provider Qualification Digest Mismatch",
                "qualificationDigest did not match protected qualification evidence bytes."
            );
        }
    }

    private List<AuthorityStateRow> readAuthorityState() {
        return authorityJdbcTemplate.query(
            """
            SELECT installation_id, incarnation, security_state, authority_epoch, fence_epoch
              FROM ma_state
             WHERE singleton = 1
            """,
            (rs, rowNum) -> new AuthorityStateRow(
                rs.getObject("installation_id", UUID.class),
                rs.getObject("incarnation", UUID.class),
                rs.getString("security_state"),
                rs.getLong("authority_epoch"),
                rs.getLong("fence_epoch")
            )
        );
    }

    private ExistingIssuerRow findIssuerRowById(UUID issuerId) {
        List<ExistingIssuerRow> rows = authorityJdbcTemplate.query(
            """
            SELECT id, registration_id, issuer_uri, state, version_no,
                   metadata_cipher, config_digest, federation_component_id, lifecycle_profile
              FROM ma_issuer
             WHERE id = ?
            """,
            (rs, rowNum) -> new ExistingIssuerRow(
                rs.getObject("id", UUID.class),
                rs.getString("registration_id"),
                rs.getString("issuer_uri"),
                rs.getString("state"),
                rs.getLong("version_no"),
                rs.getBytes("metadata_cipher"),
                rs.getString("config_digest"),
                rs.getObject("federation_component_id", UUID.class),
                rs.getString("lifecycle_profile")
            ),
            issuerId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static boolean isClockAndDeadlineValid(
        Instant t0,
        Instant t1,
        long monoStart,
        long monoEnd
    ) {
        long monoElapsedNanos = monoEnd - monoStart;
        if (monoElapsedNanos < 0L || monoElapsedNanos > CYCLE_DEADLINE_NANOS) {
            return false;
        }
        long utcElapsedNanos;
        try {
            utcElapsedNanos = Duration.between(t0, t1).toNanos();
        } catch (ArithmeticException ex) {
            return false;
        }
        long discrepancy = Math.abs(utcElapsedNanos - monoElapsedNanos);
        return discrepancy <= MAX_CLOCK_DISCREPANCY_NANOS;
    }

    private static byte[] readBoundedBytes(InputStream in, int maxBytes) throws LifecycleTransportException, IOException {
        try (InputStream stream = in) {
            byte[] chunk = stream.readNBytes(maxBytes + 1);
            if (chunk.length > maxBytes) {
                throw new LifecycleTransportException("RESPONSE_EXCEEDS_256KIB");
            }
            return chunk;
        }
    }

    private static JsonNode parseBoundedJson(byte[] utf8Bytes) throws LifecycleTransportException {
        if (utf8Bytes == null || utf8Bytes.length == 0) {
            throw new LifecycleTransportException("EMPTY_JSON_RESPONSE");
        }
        try {
            JsonNode tree = STRICT_HTTP_JSON.readTree(utf8Bytes);
            if (computeDepth(tree, 1) > MAX_JSON_DEPTH) {
                throw new LifecycleTransportException("JSON_DEPTH_EXCEEDED");
            }
            return tree;
        } catch (JacksonException ex) {
            throw new LifecycleTransportException("MALFORMED_OR_DUPLICATE_KEY_JSON");
        }
    }

    private static int computeDepth(JsonNode node, int current) {
        if (current > MAX_JSON_DEPTH || node == null || (!node.isObject() && !node.isArray())) {
            return current;
        }
        int max = current;
        if (node.isArray()) {
            for (JsonNode child : node) {
                max = Math.max(max, computeDepth(child, current + 1));
            }
        } else {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                max = Math.max(max, computeDepth(entry.getValue(), current + 1));
            }
        }
        return max;
    }

    private static String percentEncodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String urlEncodeForm(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private String extractSinglePermission(String jsonArray) {
        List<String> list = parseJsonStringList(jsonArray);
        return list.isEmpty() ? "" : list.getFirst();
    }

    private List<String> parseJsonStringList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(jsonArray);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isString()) {
                    out.add(item.asString());
                }
            }
            return out;
        } catch (JacksonException ex) {
            return List.of();
        }
    }

    private String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize JSON", ex);
        }
    }

    @FunctionalInterface
    interface FaultHook {
        void onStep(String stepName);
    }

    record ConfigApplyOutcome(boolean changed, long issuerVersion, UUID issuerId) {
    }

    record SweepResult(boolean issuerFresh, Instant observationInstant, boolean augmentedSubjectEnabled, String detail) {
    }

    private record CachedClientToken(String accessToken, Instant refreshBefore) {
    }

    private record AuthorityStateRow(
        UUID installationId,
        UUID incarnation,
        String securityState,
        long authorityEpoch,
        long fenceEpoch
    ) {
    }

    private record ExistingIssuerRow(
        UUID id,
        String registrationId,
        String issuerUri,
        String state,
        long versionNo,
        byte[] metadataCipher,
        String configDigest,
        UUID federationComponentId,
        String lifecycleProfile
    ) {
    }

    private record CapturedLinkRow(
        UUID linkId,
        UUID principalId,
        String subject,
        long linkVersion,
        String principalStatus,
        String authAuthority
    ) {
    }

    private record CapturedSweepContext(
        UUID installationId,
        UUID incarnation,
        long fenceEpoch,
        UUID issuerId,
        long issuerVersion,
        String configDigest,
        IssuerConfig2 config,
        String lifecycleSecret,
        List<CapturedLinkRow> links
    ) {
    }

    private record RevalidatedLinkState(
        UUID incarnation,
        String issuerState,
        long issuerVersion,
        String linkState,
        long linkVersion,
        String principalStatus,
        String authAuthority
    ) {
    }

    private record IssuerEligibilityRow(
        UUID incarnation,
        String state,
        long versionNo,
        String configDigest
    ) {
    }

    private record LinkSnapshot(
        UUID linkId,
        UUID principalId,
        String subject,
        String state,
        long versionNo
    ) {
    }

    private record UserReadState(boolean validSchema, boolean matchedBinding, boolean enabled) {
    }

    private record HttpJsonResult(int statusCode, JsonNode json, boolean globalFailure, String errorReason) {
        static HttpJsonResult failure(String reason) {
            return new HttpJsonResult(-1, null, true, reason);
        }
    }

    private record SubjectObservation(
        boolean globalHealthy,
        boolean subjectEnabled,
        Set<UUID> groupIds,
        String errorReason
    ) {
        static SubjectObservation positive(Set<UUID> groups) {
            return new SubjectObservation(true, true, groups, null);
        }

        static SubjectObservation perLinkNegative(String reason) {
            return new SubjectObservation(true, false, Set.of(), reason);
        }

        static SubjectObservation globalFailure(String reason) {
            return new SubjectObservation(false, false, Set.of(), reason);
        }
    }

    private record DesiredProviderGrant(
        UUID companyId,
        String operation,
        String fieldGroup,
        String sourceRef
    ) {
        String canonicalKey() {
            return companyId + "|" + operation + "|" + fieldGroup + "|" + sourceRef;
        }
    }

    private record ExistingProviderGrantRow(
        UUID grantId,
        UUID companyId,
        String operation,
        String fieldGroup,
        String state,
        String sourceRef
    ) {
        String canonicalKey() {
            return companyId + "|" + operation + "|" + fieldGroup + "|" + sourceRef;
        }
    }

    private record RawGrantProjRow(
        UUID grantId,
        String roleCode,
        String permsJson,
        boolean exportPermitted,
        String state
    ) {
    }

    private record RoleGrantAggregate(
        UUID representativeGrantId,
        String roleCode,
        List<String> mergedPermissions,
        boolean exportPermitted,
        boolean active
    ) {
    }

    private static final class LifecycleTransportException extends Exception {
        private final String reason;

        LifecycleTransportException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
