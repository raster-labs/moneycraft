package com.raster.moneycraft.foundation.authority;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.audit.AuditVerifyResult;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.events.SseBroadcasterService;
import com.raster.moneycraft.foundation.tenant.TenantAwareBusinessDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@DependsOn("dualFlywayInitializer")
public class AuthorityGuard implements SmartLifecycle {

    public static final int ADVISORY_LOCK_KEY_1 = 1296253269;
    public static final int ADVISORY_LOCK_KEY_2 = 1;

    private static final Logger LOG = LoggerFactory.getLogger(AuthorityGuard.class);

    private final MoneyCraftProperties properties;
    private final TenantAwareBusinessDataSource businessDataSource;
    private final JdbcTemplate authorityJdbcTemplate;
    private final JdbcTemplate businessJdbcTemplate;
    private final AdminReceiptService adminReceiptService;
    private final AuditChainService auditChainService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final SseBroadcasterService sseBroadcasterService;

    private final String writerInstanceId = "mc-writer-" + UUID.randomUUID();
    private final Object leaseMonitor = new Object();
    private final AtomicReference<GuardSnapshot> snapshotRef = new AtomicReference<>(
        new GuardSnapshot(InstallationSecurityState.UNINITIALISED, 1L, null, false)
    );

    private volatile Connection pinnedLeaseConnection;
    private volatile boolean running;
    private volatile long lastLeaseValidatedAtNanos;

    public AuthorityGuard(
        MoneyCraftProperties properties,
        @Qualifier("businessDataSource") TenantAwareBusinessDataSource businessDataSource,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        AdminReceiptService adminReceiptService,
        AuditChainService auditChainService,
        JcsCanonicalizer jcsCanonicalizer,
        SseBroadcasterService sseBroadcasterService
    ) {
        this.properties = properties;
        this.businessDataSource = businessDataSource;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.adminReceiptService = adminReceiptService;
        this.auditChainService = auditChainService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.sseBroadcasterService = sseBroadcasterService;
    }

    @Override
    public void start() {
        if (properties.getMigrator().isOnly() || properties.getMigrator().isExitOnComplete()) {
            LOG.info("AuthorityGuard writer lease skipped in one-shot Flyway migrator mode.");
            this.running = true;
            return;
        }
        synchronized (leaseMonitor) {
            acquireWriterLeaseInternal();
            reconcileAndVerify();
            this.running = true;
        }
    }

    @Override
    public void stop() {
        synchronized (leaseMonitor) {
            this.running = false;
            releaseWriterLeaseInternal();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1000;
    }

    public GuardSnapshot currentSnapshot() {
        if (!properties.getMigrator().isOnly() && !properties.getMigrator().isExitOnComplete()) {
            reconcileAndVerify();
        }
        return snapshotRef.get();
    }

    public GuardSnapshot cachedSnapshot() {
        return snapshotRef.get();
    }

    public GuardSnapshot reconcileFenceAndReceipts() {
        if (properties.getMigrator().isOnly() || properties.getMigrator().isExitOnComplete()) {
            return snapshotRef.get();
        }
        return reconcileInternal(false);
    }

    public boolean isLeaseHealthy() {
        synchronized (leaseMonitor) {
            if (pinnedLeaseConnection == null) {
                return false;
            }
            try {
                if (pinnedLeaseConnection.isClosed()) {
                    return false;
                }
                long now = System.nanoTime();
                if (now - lastLeaseValidatedAtNanos < 1_000_000_000L) {
                    return true;
                }
                boolean valid = pinnedLeaseConnection.isValid(2);
                if (valid) {
                    lastLeaseValidatedAtNanos = now;
                }
                return valid;
            } catch (SQLException ex) {
                return false;
            }
        }
    }

    public void assertActiveLease() {
        assertWritable();
    }

    public void assertLeaseForBootstrap() {
        synchronized (leaseMonitor) {
            if (!ensureLeaseHeld()) {
                throw new MoneyCraftException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MC-AUTHORITY-LEASE-UNAVAILABLE",
                    "Single-Writer Lease Unavailable",
                    "Another MoneyCraft writer instance currently holds pg_try_advisory_lock(1296253269, 1)."
                );
            }
            GuardSnapshot snap = reconcileAndVerify();
            if (snap.securityState() == InstallationSecurityState.QUARANTINED) {
                throw quarantinedException(snap.quarantineReason());
            }
        }
    }

    public void assertWritable() {
        synchronized (leaseMonitor) {
            if (!ensureLeaseHeld()) {
                throw new MoneyCraftException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MC-AUTHORITY-LEASE-UNAVAILABLE",
                    "Single-Writer Lease Unavailable",
                    "AuthorityGuard does not hold Business DB advisory writer lease."
                );
            }
            GuardSnapshot snap = reconcileInternal(false);
            if (snap.securityState() == InstallationSecurityState.QUARANTINED) {
                throw quarantinedException(snap.quarantineReason());
            }
            if (snap.securityState() == InstallationSecurityState.UNINITIALISED) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-SYSTEM-UNINITIALISED",
                    "Installation Not Initialised",
                    "Complete POST /api/v1/system/initialise before executing business mutations."
                );
            }
        }
    }

    public void markInitialisedActive(UUID installationId, UUID incarnation) {
        synchronized (leaseMonitor) {
            FenceState fence = readBusinessFence();
            snapshotRef.set(
                new GuardSnapshot(
                    InstallationSecurityState.ACTIVE,
                    fence.fenceEpoch(),
                    null,
                    ensureLeaseHeld()
                )
            );
        }
    }

    public GuardSnapshot enterQuarantine(UUID actorPrincipalId, String reason) {
        String cleanReason = (reason == null || reason.isBlank())
            ? "Manual operator quarantine triggered"
            : reason.trim();
        synchronized (leaseMonitor) {
            return transitionToQuarantineInternal(actorPrincipalId, cleanReason);
        }
    }

    public GuardSnapshot recoverFromQuarantine(UUID actorPrincipalId, String providedRecoveryReceipt) {
        if (providedRecoveryReceipt == null || providedRecoveryReceipt.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-RECOVERY-RECEIPT-REQUIRED",
                "Recovery Receipt Required",
                "Provide the offline installation recovery receipt code to exit QUARANTINE."
            );
        }

        synchronized (leaseMonitor) {
            if (!ensureLeaseHeld()) {
                throw new MoneyCraftException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MC-AUTHORITY-LEASE-UNAVAILABLE",
                    "Single-Writer Lease Unavailable",
                    "Cannot execute quarantine recovery without single-writer advisory lease."
                );
            }

            List<AuthorityStateRow> authRows = readAuthorityState();
            if (authRows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-SYSTEM-UNINITIALISED",
                    "Installation Not Initialised",
                    "Cannot recover an uninitialised installation."
                );
            }
            AuthorityStateRow authState = authRows.getFirst();

            List<byte[]> verifierRows = authorityJdbcTemplate.query(
                "SELECT bootstrap_token_hash FROM ma_initialisation WHERE id = 1",
                (rs, rowNum) -> rs.getBytes("bootstrap_token_hash")
            );
            if (verifierRows.isEmpty() || verifierRows.getFirst() == null) {
                throw new MoneyCraftException(
                    HttpStatus.FORBIDDEN,
                    "MC-RECOVERY-VERIFIER-MISSING",
                    "Recovery Verifier Unavailable",
                    "No recovery verifier hash is registered in ma_initialisation."
                );
            }

            byte[] candidateHash = computeRecoveryReceiptVerifier(
                authState.installationId(),
                providedRecoveryReceipt.trim()
            );
            if (!MessageDigest.isEqual(verifierRows.getFirst(), candidateHash)) {
                throw new MoneyCraftException(
                    HttpStatus.FORBIDDEN,
                    "MC-RECOVERY-RECEIPT-INVALID",
                    "Invalid Recovery Receipt",
                    "Provided recovery receipt code did not match Protected Authority verifier."
                );
            }

            AuditVerifyResult auditCheck = auditChainService.verifyAllStreams();
            if (!auditCheck.verified()) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-AUDIT-CHAIN-BROKEN",
                    "Audit Chain Tampering Unresolved",
                    "Cannot exit QUARANTINE while MC-AUDIT-2 chain is broken: " + auditCheck.failureDetail()
                );
            }

            long syncedSeq = adminReceiptService.resyncAllReceiptsDuringRecovery();
            UUID newIncarnation = UUID.randomUUID();
            UUID incidentId = UUID.randomUUID();

            // Doc 47 §4 REOPENING rule: clear all OIDC freshness stamps and delete remaining OIDC
            // sessions/pending OIDC proofs before final OPEN release while preserving LOCAL sessions.
            authorityJdbcTemplate.update("UPDATE ma_issuer SET checked_at = NULL WHERE checked_at IS NOT NULL");
            authorityJdbcTemplate.update("UPDATE ma_oidc_link SET checked_at = NULL WHERE checked_at IS NOT NULL");
            authorityJdbcTemplate.update(
                """
                DELETE FROM ma_auth_challenge
                 WHERE purpose = 'OIDC_AUTHORIZE'
                """
            );
            authorityJdbcTemplate.update(
                """
                DELETE FROM SPRING_SESSION
                 WHERE PRIMARY_ID IN (
                     SELECT SESSION_PRIMARY_ID
                       FROM SPRING_SESSION_ATTRIBUTES
                      WHERE ATTRIBUTE_NAME LIKE 'MC_OIDC_BINDING:%'
                 )
                """
            );

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_recovery (
                    incident_id, installation_id, prior_incarnation, new_incarnation,
                    recovery_state, initiated_by, reason, reconciled_receipt_seq, completed_at
                ) VALUES (?, ?, ?, ?, 'COMPLETED', ?, 'Verified quarantine recovery ceremony', ?, clock_timestamp())
                """,
                incidentId,
                authState.installationId(),
                authState.incarnation(),
                newIncarnation,
                actorPrincipalId,
                syncedSeq
            );

            Long nextFenceEpoch = businessJdbcTemplate.queryForObject(
                """
                UPDATE mc_installation_fence
                   SET incarnation = ?,
                       fence_epoch = fence_epoch + 1,
                       authority_epoch = authority_epoch + 1,
                       system_state = 'ACTIVE',
                       quarantined = false,
                       quarantine_reason = NULL,
                       quarantined_at = NULL,
                       last_mirrored_receipt_seq = ?,
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE id = 1
                RETURNING fence_epoch
                """,
                Long.class,
                newIncarnation,
                syncedSeq
            );
            long epoch = nextFenceEpoch == null ? authState.fenceEpoch() + 1L : nextFenceEpoch;

            authorityJdbcTemplate.update(
                """
                UPDATE ma_state
                   SET incarnation = ?,
                       security_state = 'ACTIVE',
                       authority_epoch = authority_epoch + 1,
                       fence_epoch = ?,
                       last_receipt_seq = ?,
                       quarantine_reason = NULL,
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE installation_id = ?
                """,
                newIncarnation,
                epoch,
                syncedSeq,
                authState.installationId()
            );

            GuardSnapshot recovered = new GuardSnapshot(
                InstallationSecurityState.ACTIVE,
                epoch,
                null,
                true
            );
            snapshotRef.set(recovered);
            sseBroadcasterService.broadcast(
                "QUARANTINE_CHANGED",
                null,
                Map.of("securityState", "ACTIVE", "fenceEpoch", epoch)
            );
            LOG.info("Quarantine recovery ceremony completed at fence_epoch={} synced_receipt_seq={}", epoch, syncedSeq);
            return recovered;
        }
    }

    public byte[] computeRecoveryReceiptVerifier(UUID installationId, String recoveryReceiptCode) {
        String input = "MC-RECOVERY-V1|" + installationId + "|" + recoveryReceiptCode.trim();
        return jcsCanonicalizer.sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    @Scheduled(fixedDelay = 15_000L, initialDelay = 15_000L)
    public void scheduledReconcileTick() {
        if (!running || properties.getMigrator().isOnly() || properties.getMigrator().isExitOnComplete()) {
            return;
        }
        try {
            reconcileAndVerify();
        } catch (RuntimeException ex) {
            LOG.warn("Scheduled AuthorityGuard reconciliation encountered error", ex);
        }
    }

    public GuardSnapshot reconcileAndVerify() {
        return reconcileInternal(true);
    }

    private GuardSnapshot reconcileInternal(boolean verifyFullAuditStreams) {
        synchronized (leaseMonitor) {
            boolean leaseHeld = ensureLeaseHeld();
            List<AuthorityStateRow> authRows = readAuthorityState();
            FenceState fence = readBusinessFence();

            if (authRows.isEmpty() && "UNINITIALISED".equals(fence.systemState()) && !fence.quarantined()) {
                GuardSnapshot uninit = new GuardSnapshot(
                    InstallationSecurityState.UNINITIALISED,
                    fence.fenceEpoch(),
                    null,
                    leaseHeld
                );
                snapshotRef.set(uninit);
                return uninit;
            }

            if (authRows.isEmpty()) {
                return transitionToQuarantineInternal(
                    null,
                    "Protected Authority ma_state missing while Business DB is initialised."
                );
            }

            AuthorityStateRow auth = authRows.getFirst();
            if ("QUARANTINED".equals(auth.securityState()) || "RECOVERING".equals(auth.securityState())
                || fence.quarantined() || "QUARANTINED".equals(fence.systemState())) {
                String reason = auth.quarantineReason() != null
                    ? auth.quarantineReason()
                    : (fence.quarantineReason() != null ? fence.quarantineReason() : "Installation is quarantined");
                GuardSnapshot q = new GuardSnapshot(
                    InstallationSecurityState.QUARANTINED,
                    fence.fenceEpoch(),
                    reason,
                    leaseHeld
                );
                snapshotRef.set(q);
                return q;
            }

            if ("UNINITIALISED".equals(auth.securityState())) {
                GuardSnapshot uninit = new GuardSnapshot(
                    InstallationSecurityState.UNINITIALISED,
                    fence.fenceEpoch(),
                    null,
                    leaseHeld
                );
                snapshotRef.set(uninit);
                return uninit;
            }

            String receiptDrift = detectReceiptDivergence(fence.lastMirroredReceiptSeq());
            if (receiptDrift != null) {
                return transitionToQuarantineInternal(null, receiptDrift);
            }

            if (verifyFullAuditStreams) {
                AuditVerifyResult auditVerify = auditChainService.verifyAllStreams();
                if (!auditVerify.verified()) {
                    return transitionToQuarantineInternal(
                        null,
                        "MC-AUDIT-2 hash chain tamper detected: " + auditVerify.failureDetail()
                    );
                }
            }

            GuardSnapshot active = new GuardSnapshot(
                InstallationSecurityState.ACTIVE,
                fence.fenceEpoch(),
                null,
                leaseHeld
            );
            snapshotRef.set(active);
            return active;
        }
    }

    private String detectReceiptDivergence(long fenceMirroredSeq) {
        ReceiptSummary authSummary = authorityJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) AS cnt, COALESCE(MAX(receipt_seq), 0) AS max_seq
              FROM ma_receipt
            """,
            (rs, rowNum) -> new ReceiptSummary(rs.getLong("cnt"), rs.getLong("max_seq"))
        );
        ReceiptSummary bizSummary = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) AS cnt, COALESCE(MAX(receipt_seq), 0) AS max_seq
              FROM mc_admin_receipt_mirror
            """,
            (rs, rowNum) -> new ReceiptSummary(rs.getLong("cnt"), rs.getLong("max_seq"))
        );
        if (authSummary == null || bizSummary == null) {
            return "Unable to query admin receipt parity counters.";
        }
        if (authSummary.count() != bizSummary.count()
            || authSummary.maxSeq() != bizSummary.maxSeq()
            || authSummary.maxSeq() != fenceMirroredSeq) {
            return "Authority/Business receipt divergence: ma_receipt(count=" + authSummary.count()
                + ", max=" + authSummary.maxSeq() + ") vs mc_admin_receipt_mirror(count="
                + bizSummary.count() + ", max=" + bizSummary.maxSeq()
                + ", fence=" + fenceMirroredSeq + ")";
        }

        if (authSummary.maxSeq() > 0L) {
            byte[] authTailHash = authorityJdbcTemplate.queryForObject(
                "SELECT receipt_hash FROM ma_receipt WHERE receipt_seq = ?",
                (rs, rowNum) -> rs.getBytes("receipt_hash"),
                authSummary.maxSeq()
            );
            byte[] bizTailHash = businessJdbcTemplate.queryForObject(
                "SELECT receipt_hash FROM mc_admin_receipt_mirror WHERE receipt_seq = ?",
                (rs, rowNum) -> rs.getBytes("receipt_hash"),
                bizSummary.maxSeq()
            );
            if (!MessageDigest.isEqual(authTailHash, bizTailHash)) {
                return "Admin receipt cryptographic hash mismatch at seq " + authSummary.maxSeq();
            }
        }
        return null;
    }

    private GuardSnapshot transitionToQuarantineInternal(UUID actorPrincipalId, String reason) {
        LOG.error("TRANSITIONING INSTALLATION TO FAIL-CLOSED QUARANTINE: {}", reason);
        FenceState currentFence = readBusinessFence();
        businessJdbcTemplate.update(
            """
            UPDATE mc_installation_fence
               SET system_state = 'QUARANTINED',
                   quarantined = true,
                   quarantine_reason = ?,
                   quarantined_at = COALESCE(quarantined_at, clock_timestamp()),
                   version_no = version_no + 1,
                   updated_at = clock_timestamp()
             WHERE id = 1
            """,
            reason
        );

        List<AuthorityStateRow> authRows = readAuthorityState();
        if (!authRows.isEmpty()) {
            AuthorityStateRow auth = authRows.getFirst();
            authorityJdbcTemplate.update(
                """
                UPDATE ma_state
                   SET security_state = 'QUARANTINED',
                       quarantine_reason = ?,
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE installation_id = ?
                """,
                reason,
                auth.installationId()
            );
            if (actorPrincipalId != null) {
                authorityJdbcTemplate.update(
                    """
                    INSERT INTO ma_recovery (
                        incident_id, installation_id, prior_incarnation, new_incarnation,
                        recovery_state, initiated_by, reason, reconciled_receipt_seq
                    ) VALUES (?, ?, ?, ?, 'INITIATED', ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    auth.installationId(),
                    auth.incarnation(),
                    auth.incarnation(),
                    actorPrincipalId,
                    reason,
                    currentFence.lastMirroredReceiptSeq()
                );
            }
        }

        GuardSnapshot quarantined = new GuardSnapshot(
            InstallationSecurityState.QUARANTINED,
            currentFence.fenceEpoch(),
            reason,
            isLeaseHealthy()
        );
        snapshotRef.set(quarantined);
        sseBroadcasterService.broadcast(
            "QUARANTINE_CHANGED",
            null,
            Map.of(
                "securityState", "QUARANTINED",
                "fenceEpoch", currentFence.fenceEpoch(),
                "reason", reason
            )
        );
        return quarantined;
    }

    private boolean ensureLeaseHeld() {
        if (isLeaseHealthy()) {
            return true;
        }
        releaseWriterLeaseInternal();
        return acquireWriterLeaseInternal();
    }

    private boolean acquireWriterLeaseInternal() {
        try {
            Connection conn = businessDataSource.getPinnedRawConnection();
            conn.setAutoCommit(true);
            boolean acquired;
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                ps.setInt(1, ADVISORY_LOCK_KEY_1);
                ps.setInt(2, ADVISORY_LOCK_KEY_2);
                try (ResultSet rs = ps.executeQuery()) {
                    acquired = rs.next() && rs.getBoolean(1);
                }
            }
            if (!acquired) {
                conn.close();
                LOG.warn("Could not acquire single-writer PostgreSQL advisory lock (1296253269, 1).");
                return false;
            }
            this.pinnedLeaseConnection = conn;
            Long bumpedEpoch = businessJdbcTemplate.queryForObject(
                """
                UPDATE mc_installation_fence
                   SET fence_epoch = fence_epoch + 1,
                       writer_instance_id = ?,
                       writer_acquired_at = clock_timestamp(),
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE id = 1
                RETURNING fence_epoch
                """,
                Long.class,
                writerInstanceId
            );
            authorityJdbcTemplate.update(
                """
                UPDATE ma_state
                   SET fence_epoch = GREATEST(fence_epoch, ?),
                       updated_at = clock_timestamp()
                 WHERE singleton = 1
                """,
                bumpedEpoch == null ? 1L : bumpedEpoch
            );
            LOG.info(
                "AuthorityGuard acquired Business DB advisory lock ({}, {}) at fence_epoch={} writer={}",
                ADVISORY_LOCK_KEY_1,
                ADVISORY_LOCK_KEY_2,
                bumpedEpoch,
                writerInstanceId
            );
            return true;
        } catch (SQLException ex) {
            LOG.error("Failed to acquire Business DB single-writer advisory lease", ex);
            return false;
        }
    }

    private void releaseWriterLeaseInternal() {
        Connection conn = this.pinnedLeaseConnection;
        this.pinnedLeaseConnection = null;
        if (conn == null) {
            return;
        }
        try {
            if (!conn.isClosed()) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                    ps.setInt(1, ADVISORY_LOCK_KEY_1);
                    ps.setInt(2, ADVISORY_LOCK_KEY_2);
                    ps.execute();
                }
                conn.close();
            }
        } catch (SQLException ignored) {
            // Connection already closed during container shutdown.
        }
    }

    private List<AuthorityStateRow> readAuthorityState() {
        return authorityJdbcTemplate.query(
            """
            SELECT installation_id, incarnation, security_state, fence_epoch, last_receipt_seq, quarantine_reason
              FROM ma_state
             WHERE singleton = 1
            """,
            (rs, rowNum) -> new AuthorityStateRow(
                rs.getObject("installation_id", UUID.class),
                rs.getObject("incarnation", UUID.class),
                rs.getString("security_state"),
                rs.getLong("fence_epoch"),
                rs.getLong("last_receipt_seq"),
                rs.getString("quarantine_reason")
            )
        );
    }

    private FenceState readBusinessFence() {
        FenceState state = businessJdbcTemplate.queryForObject(
            """
            SELECT fence_epoch, system_state, quarantined, quarantine_reason, last_mirrored_receipt_seq
              FROM mc_installation_fence
             WHERE id = 1
            """,
            (rs, rowNum) -> new FenceState(
                rs.getLong("fence_epoch"),
                rs.getString("system_state"),
                rs.getBoolean("quarantined"),
                rs.getString("quarantine_reason"),
                rs.getLong("last_mirrored_receipt_seq")
            )
        );
        if (state == null) {
            return new FenceState(1L, "UNINITIALISED", false, null, 0L);
        }
        return state;
    }

    public static MoneyCraftException quarantinedException(String reason) {
        return new MoneyCraftException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MC-SYSTEM-QUARANTINED",
            "Installation Quarantined (Fail-Closed DR Mode)",
            reason == null || reason.isBlank()
                ? "Business mutations are suspended while installation is in QUARANTINE."
                : "Business mutations suspended: " + reason
        );
    }

    public record GuardSnapshot(
        InstallationSecurityState securityState,
        long fenceEpoch,
        String quarantineReason,
        boolean writerLeaseHeld
    ) {
    }

    private record AuthorityStateRow(
        UUID installationId,
        UUID incarnation,
        String securityState,
        long fenceEpoch,
        long lastReceiptSeq,
        String quarantineReason
    ) {
    }

    private record FenceState(
        long fenceEpoch,
        String systemState,
        boolean quarantined,
        String quarantineReason,
        long lastMirroredReceiptSeq
    ) {
    }

    private record ReceiptSummary(long count, long maxSeq) {
    }
}
