package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AdminReceiptService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.authority.InstallationSecurityState;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manages explicit installation-level Super Admin PERSON designations (Doc 51 §4, Doc 52 §3).
 * Never inferred from generic INSTALLATION_OWNER/security.admin or OIDC group mappings.
 */
@Service
@DependsOn("authorityGuard")
public class SuperAdminService implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SuperAdminService.class);

    private final MoneyCraftProperties properties;
    private final AuthorityGuard authorityGuard;
    private final AdminReceiptService adminReceiptService;
    private final AuditChainService auditChainService;
    private final JdbcTemplate authorityJdbcTemplate;
    private final TransactionTemplate authorityTxTemplate;
    private final TransactionTemplate businessTxTemplate;
    private volatile boolean running;

    public SuperAdminService(
        MoneyCraftProperties properties,
        AuthorityGuard authorityGuard,
        AdminReceiptService adminReceiptService,
        AuditChainService auditChainService,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("authorityTransactionManager") PlatformTransactionManager authorityTxManager,
        @Qualifier("transactionManager") PlatformTransactionManager businessTxManager
    ) {
        this.properties = properties;
        this.authorityGuard = authorityGuard;
        this.adminReceiptService = adminReceiptService;
        this.auditChainService = auditChainService;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.authorityTxTemplate = new TransactionTemplate(authorityTxManager);
        this.businessTxTemplate = new TransactionTemplate(businessTxManager);
    }

    @Override
    public void start() {
        if (!properties.getMigrator().isOnly() && !properties.getMigrator().isExitOnComplete()) {
            try {
                reconcileConfiguredInitialSuperAdmin();
            } catch (RuntimeException ex) {
                LOG.warn("Deferred startup initial-super-admin ceremony check: {}", ex.getMessage());
            }
        }
        this.running = true;
    }

    @Override
    public void stop() {
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1500;
    }

    public boolean reconcileConfiguredInitialSuperAdmin() {
        String configuredUsername = properties.getSecurity().getInitialSuperAdminUsername();
        if (configuredUsername == null || configuredUsername.isBlank()) {
            return false;
        }
        AuthorityGuard.GuardSnapshot snap = authorityGuard.cachedSnapshot();
        if (snap.securityState() != InstallationSecurityState.ACTIVE) {
            return false;
        }
        String trimmedUsername = configuredUsername.trim();
        List<CandidateRow> candidates = authorityJdbcTemplate.query(
            """
            SELECT p.principal_id, p.installation_id, p.username::text AS username, p.kind, p.status
              FROM ma_principal p
              JOIN ma_state s ON s.installation_id = p.installation_id AND s.singleton = 1
             WHERE p.username = ?
               AND s.security_state = 'ACTIVE'
            """,
            (rs, rowNum) -> new CandidateRow(
                rs.getObject("principal_id", UUID.class),
                rs.getObject("installation_id", UUID.class),
                rs.getString("username"),
                rs.getString("kind"),
                rs.getString("status")
            ),
            trimmedUsername
        );
        if (candidates.isEmpty()) {
            return false;
        }
        CandidateRow target = candidates.getFirst();
        if (!"PERSON".equals(target.kind()) || !"ENABLED".equals(target.status())) {
            return false;
        }
        Integer existingRowCount = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ma_super_admin_designation WHERE principal_id = ?",
            Integer.class,
            target.principalId()
        );
        if (existingRowCount != null && existingRowCount > 0) {
            return false;
        }

        authorityTxTemplate.executeWithoutResult(status -> {
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_super_admin_designation (
                    principal_id, installation_id, state, ceremony_source, ceremony_reason, designated_by
                ) VALUES (?, ?, 'ACTIVE', 'BOOTSTRAP_CONFIG_CEREMONY', ?, ?)
                ON CONFLICT (principal_id) DO NOTHING
                """,
                target.principalId(),
                target.installationId(),
                "Explicit startup configuration property moneycraft.security.initial-super-admin-username",
                target.principalId()
            );
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_gov_audit_event (
                    event_id, installation_id, profile_id, actor_principal_id, actor_kind,
                    action, outcome, reason_code, sanitised_detail, correlation_id
                ) VALUES (?, ?, NULL, ?, 'PERSON', 'SUPER_ADMIN_DESIGNATE', 'SUCCEEDED',
                          'BOOTSTRAP_CONFIG_CEREMONY', ?, ?)
                """,
                UUID.randomUUID(),
                target.installationId(),
                target.principalId(),
                "Designated initial Super Admin PERSON via configured startup ceremony.",
                UUID.randomUUID().toString()
            );
        });

        TenantContextHolder.callWithSystemBypass("super-admin-bootstrap", () -> {
            businessTxTemplate.executeWithoutResult(status -> {
                adminReceiptService.recordAndMirrorAdminReceipt(
                    target.installationId(),
                    target.principalId(),
                    null,
                    "super_admin.designate",
                    "PERSON",
                    target.principalId().toString(),
                    null,
                    null,
                    Map.of(
                        "ceremonySource", "BOOTSTRAP_CONFIG_CEREMONY",
                        "principalId", target.principalId().toString()
                    )
                );
                auditChainService.appendInstallationEvent(
                    target.installationId(),
                    target.principalId(),
                    "PERSON",
                    "SUPER_ADMIN_DESIGNATE",
                    "PERSON",
                    target.principalId().toString(),
                    "SUCCESS",
                    null,
                    Map.of("ceremonySource", "BOOTSTRAP_CONFIG_CEREMONY")
                );
            });
            return null;
        });

        LOG.info("Designated initial Super Admin PERSON principal {} via explicit config ceremony.", target.principalId());
        return true;
    }

    public boolean applyDuringBootstrapIfConfigured(UUID installationId, UUID ownerPrincipalId, String ownerUsername) {
        String configuredUsername = properties.getSecurity().getInitialSuperAdminUsername();
        if (configuredUsername == null || configuredUsername.isBlank()) {
            return false;
        }
        if (!configuredUsername.trim().equalsIgnoreCase(ownerUsername.trim())) {
            return false;
        }
        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_super_admin_designation (
                principal_id, installation_id, state, ceremony_source, ceremony_reason, designated_by
            ) VALUES (?, ?, 'ACTIVE', 'BOOTSTRAP_CONFIG_CEREMONY', ?, ?)
            ON CONFLICT (principal_id) DO NOTHING
            """,
            ownerPrincipalId,
            installationId,
            "Explicit installation bootstrap ceremony via moneycraft.security.initial-super-admin-username",
            ownerPrincipalId
        );
        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_gov_audit_event (
                event_id, installation_id, profile_id, actor_principal_id, actor_kind,
                action, outcome, reason_code, sanitised_detail, correlation_id
            ) VALUES (?, ?, NULL, ?, 'PERSON', 'SUPER_ADMIN_DESIGNATE', 'SUCCEEDED',
                      'BOOTSTRAP_CONFIG_CEREMONY', ?, ?)
            """,
            UUID.randomUUID(),
            installationId,
            ownerPrincipalId,
            "Designated owner PERSON as Super Admin during installation bootstrap ceremony.",
            UUID.randomUUID().toString()
        );
        return true;
    }

    public AuthDtos.SuperAdminDesignationDto designateSuperAdmin(
        MoneyCraftPrincipal ownerActor,
        UUID targetPrincipalId,
        String reason
    ) {
        authorityGuard.assertWritable();
        if (!"PERSON".equals(ownerActor.kind()) || ownerActor.isServiceKeyAuthenticated()) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-SUPER-ADMIN-CEREMONY-FORBIDDEN",
                "Person Owner Ceremony Required",
                "Service identities cannot assign Super Admin designations."
            );
        }
        String cleanReason = (reason == null || reason.isBlank())
            ? "Owner-controlled installation Super Admin ceremony"
            : reason.trim();
        if (cleanReason.length() > 512) {
            cleanReason = cleanReason.substring(0, 512);
        }

        CandidateRow target = loadTargetPerson(targetPrincipalId);
        String finalReason = cleanReason;

        authorityTxTemplate.executeWithoutResult(status -> {
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_super_admin_designation (
                    principal_id, installation_id, state, ceremony_source, ceremony_reason,
                    designated_by, designated_at, revoked_at, version_no
                ) VALUES (?, ?, 'ACTIVE', 'OWNER_CEREMONY', ?, ?, clock_timestamp(), NULL, 1)
                ON CONFLICT (principal_id) DO UPDATE
                   SET state = 'ACTIVE',
                       ceremony_source = 'OWNER_CEREMONY',
                       ceremony_reason = EXCLUDED.ceremony_reason,
                       designated_by = EXCLUDED.designated_by,
                       designated_at = clock_timestamp(),
                       revoked_at = NULL,
                       version_no = ma_super_admin_designation.version_no + 1
                """,
                target.principalId(),
                target.installationId(),
                finalReason,
                ownerActor.principalId()
            );
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_gov_audit_event (
                    event_id, installation_id, profile_id, actor_principal_id, actor_kind,
                    action, outcome, reason_code, sanitised_detail, correlation_id
                ) VALUES (?, ?, NULL, ?, 'PERSON', 'SUPER_ADMIN_DESIGNATE', 'SUCCEEDED',
                          'OWNER_CEREMONY', ?, ?)
                """,
                UUID.randomUUID(),
                target.installationId(),
                ownerActor.principalId(),
                "Designated PERSON " + target.principalId() + " as installation Super Admin.",
                UUID.randomUUID().toString()
            );
        });

        TenantContextHolder.callWithSystemBypass("super-admin-designate", () -> {
            businessTxTemplate.executeWithoutResult(status -> adminReceiptService.recordAndMirrorAdminReceipt(
                target.installationId(),
                ownerActor.principalId(),
                null,
                "super_admin.designate",
                "PERSON",
                target.principalId().toString(),
                null,
                null,
                Map.of(
                    "ceremonySource", "OWNER_CEREMONY",
                    "targetPrincipalId", target.principalId().toString()
                )
            ));
            return null;
        });

        return readDesignation(target.principalId());
    }

    public AuthDtos.SuperAdminDesignationDto revokeSuperAdmin(
        MoneyCraftPrincipal ownerActor,
        UUID targetPrincipalId,
        String reason
    ) {
        authorityGuard.assertWritable();
        if (!"PERSON".equals(ownerActor.kind()) || ownerActor.isServiceKeyAuthenticated()) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-SUPER-ADMIN-CEREMONY-FORBIDDEN",
                "Person Owner Ceremony Required",
                "Service identities cannot revoke Super Admin designations."
            );
        }
        String cleanReason = (reason == null || reason.isBlank())
            ? "Owner-controlled Super Admin designation revocation"
            : reason.trim();
        if (cleanReason.length() > 512) {
            cleanReason = cleanReason.substring(0, 512);
        }

        CandidateRow target = loadTargetPerson(targetPrincipalId);
        String finalReason = cleanReason;

        authorityTxTemplate.executeWithoutResult(status -> {
            int updated = authorityJdbcTemplate.update(
                """
                UPDATE ma_super_admin_designation
                   SET state = 'REVOKED',
                       ceremony_reason = ?,
                       revoked_at = clock_timestamp(),
                       version_no = version_no + 1
                 WHERE principal_id = ? AND state = 'ACTIVE'
                """,
                finalReason,
                target.principalId()
            );
            if (updated == 0) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-SUPER-ADMIN-NOT-DESIGNATED",
                    "Super Admin Designation Not Active",
                    "Target principal does not hold an active Super Admin designation."
                );
            }
            authorityJdbcTemplate.update(
                """
                UPDATE ma_step_up_intent
                   SET state = 'INVALIDATED'
                 WHERE principal_id = ? AND state = 'ISSUED'
                """,
                target.principalId()
            );
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_gov_audit_event (
                    event_id, installation_id, profile_id, actor_principal_id, actor_kind,
                    action, outcome, reason_code, sanitised_detail, correlation_id
                ) VALUES (?, ?, NULL, ?, 'PERSON', 'SUPER_ADMIN_REVOKE', 'SUCCEEDED',
                          'OWNER_REVOCATION', ?, ?)
                """,
                UUID.randomUUID(),
                target.installationId(),
                ownerActor.principalId(),
                "Revoked Super Admin designation for PERSON " + target.principalId() + ".",
                UUID.randomUUID().toString()
            );
        });

        TenantContextHolder.callWithSystemBypass("super-admin-revoke", () -> {
            businessTxTemplate.executeWithoutResult(status -> adminReceiptService.recordAndMirrorAdminReceipt(
                target.installationId(),
                ownerActor.principalId(),
                null,
                "super_admin.revoke",
                "PERSON",
                target.principalId().toString(),
                null,
                null,
                Map.of("targetPrincipalId", target.principalId().toString())
            ));
            return null;
        });

        return readDesignation(target.principalId());
    }

    private CandidateRow loadTargetPerson(UUID targetPrincipalId) {
        List<CandidateRow> rows = authorityJdbcTemplate.query(
            """
            SELECT principal_id, installation_id, username::text AS username, kind, status
              FROM ma_principal
             WHERE principal_id = ?
            """,
            (rs, rowNum) -> new CandidateRow(
                rs.getObject("principal_id", UUID.class),
                rs.getObject("installation_id", UUID.class),
                rs.getString("username"),
                rs.getString("kind"),
                rs.getString("status")
            ),
            targetPrincipalId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-PRINCIPAL-NOT-FOUND",
                "Principal Not Found",
                "Target principal " + targetPrincipalId + " was not found."
            );
        }
        CandidateRow target = rows.getFirst();
        if (!"PERSON".equals(target.kind()) || !"ENABLED".equals(target.status())) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-SUPER-ADMIN-TARGET-INVALID",
                "Invalid Super Admin Target",
                "Only an enabled PERSON principal can be designated as Super Admin."
            );
        }
        return target;
    }

    private AuthDtos.SuperAdminDesignationDto readDesignation(UUID principalId) {
        return authorityJdbcTemplate.queryForObject(
            """
            SELECT d.principal_id, p.username::text AS username, d.state, d.ceremony_source,
                   d.ceremony_reason, d.designated_at, d.revoked_at
              FROM ma_super_admin_designation d
              JOIN ma_principal p ON p.principal_id = d.principal_id
             WHERE d.principal_id = ?
            """,
            (rs, rowNum) -> new AuthDtos.SuperAdminDesignationDto(
                rs.getObject("principal_id", UUID.class),
                rs.getString("username"),
                rs.getString("state"),
                rs.getString("ceremony_source"),
                rs.getString("ceremony_reason"),
                rs.getObject("designated_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class)
            ),
            principalId
        );
    }

    private record CandidateRow(
        UUID principalId,
        UUID installationId,
        String username,
        String kind,
        String status
    ) {
    }
}
