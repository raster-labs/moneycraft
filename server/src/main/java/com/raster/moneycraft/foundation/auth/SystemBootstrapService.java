package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AdminReceiptService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.SystemRole;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SystemBootstrapService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MoneyCraftProperties properties;
    private final AuthorityGuard authorityGuard;
    private final TinkCryptoService tinkCryptoService;
    private final AdminReceiptService adminReceiptService;
    private final AuditChainService auditChainService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final Argon2PasswordEncoder passwordEncoder;
    private final JdbcTemplate authorityJdbcTemplate;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate authorityTxTemplate;
    private final TransactionTemplate businessTxTemplate;

    public SystemBootstrapService(
        MoneyCraftProperties properties,
        AuthorityGuard authorityGuard,
        TinkCryptoService tinkCryptoService,
        AdminReceiptService adminReceiptService,
        AuditChainService auditChainService,
        JcsCanonicalizer jcsCanonicalizer,
        Argon2PasswordEncoder passwordEncoder,
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
        this.passwordEncoder = passwordEncoder;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.authorityTxTemplate = new TransactionTemplate(authorityTransactionManager);
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public AuthDtos.SystemStatusResponse getSystemStatus() {
        AuthorityGuard.GuardSnapshot snap = authorityGuard.currentSnapshot();
        return new AuthDtos.SystemStatusResponse(
            snap.securityState(),
            snap.fenceEpoch(),
            properties.getVersion(),
            snap.quarantineReason()
        );
    }

    public synchronized BootstrapResult initialiseInstallation(AuthDtos.InitialiseInstallationRequest request) {
        validateBootstrapRequest(request);
        authorityGuard.assertLeaseForBootstrap();

        Integer existingStateCount = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ma_state WHERE singleton = 1",
            Integer.class
        );
        if (existingStateCount != null && existingStateCount > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-SYSTEM-ALREADY-INITIALISED",
                "Installation Already Initialised",
                "Protected Authority ma_state has already completed first-run initialisation."
            );
        }

        UUID installationId = resolveInstallationId();
        UUID incarnation = UUID.randomUUID();
        UUID ownerPrincipalId = UUID.randomUUID();
        String username = request.ownerUsername().trim();
        String displayName = request.ownerDisplayName().trim();
        String label = (request.installationLabel() == null || request.installationLabel().isBlank())
            ? "MoneyCraft Primary Installation"
            : request.installationLabel().trim();

        String recoveryReceiptCode = generateRecoveryReceiptCode();
        byte[] recoveryVerifierHash = authorityGuard.computeRecoveryReceiptVerifier(
            installationId,
            recoveryReceiptCode
        );
        String argon2idHash = passwordEncoder.encode(request.password());
        MoneyCraftProperties.Argon2 argonCfg = properties.getSecurity().getArgon2();

        authorityTxTemplate.executeWithoutResult(status -> {
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_state (
                    installation_id, singleton, incarnation, security_state,
                    authority_epoch, fence_epoch, last_receipt_seq
                ) VALUES (?, 1, ?, 'ACTIVE', 1, 1, 0)
                """,
                installationId,
                incarnation
            );

            Map<String, UUID> dekIds = tinkCryptoService.provisionInstallationKeys(installationId);

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_policy (
                    id, installation_id, company_scope, policy_code, policy_state,
                    active_key_id, session_idle_timeout_sec, session_absolute_timeout_sec,
                    outbound_connectors_enabled, egress_hostname_allowlist, config_json
                ) VALUES (?, ?, NULL, 'INSTALLATION_SECURITY_DEFAULT', 'ENFORCED', ?, 1800, 43200, false, '[]'::jsonb, ?::jsonb)
                """,
                UUID.randomUUID(),
                installationId,
                dekIds.get(TinkCryptoService.PURPOSE_GCM_PAYLOAD),
                jcsCanonicalizer.canonicalizeObject(Map.of("installationLabel", label))
            );

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_principal (
                    principal_id, installation_id, kind, username, display_name,
                    status, auth_authority, system_roles, last_authenticated_at
                ) VALUES (?, ?, 'PERSON', ?, ?, 'ENABLED', 'LOCAL', '["INSTALLATION_OWNER"]'::jsonb, clock_timestamp())
                """,
                ownerPrincipalId,
                installationId,
                username,
                displayName
            );

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_password (
                    principal_id, argon2id_hash, memory_kib, iterations, parallelism, must_change_password
                ) VALUES (?, ?, ?, ?, ?, false)
                """,
                ownerPrincipalId,
                argon2idHash,
                Math.max(19456, argonCfg.getMemoryKib()),
                argonCfg.getIterations(),
                argonCfg.getParallelism()
            );

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_initialisation (
                    id, installation_id, bootstrap_state, owner_principal_id,
                    bootstrap_token_hash, recovery_kit_acknowledged, initialised_at
                ) VALUES (1, ?, 'COMPLETE', ?, ?, true, clock_timestamp())
                """,
                installationId,
                ownerPrincipalId,
                recoveryVerifierHash
            );
        });

        byte[] authorityDigest = jcsCanonicalizer.sha256(
            ("PRINCIPAL|" + ownerPrincipalId + "|" + username + "|INSTALLATION_OWNER").getBytes(StandardCharsets.UTF_8)
        );

        TenantContextHolder.callWithSystemBypass("installation-bootstrap", () -> {
            businessTxTemplate.executeWithoutResult(status -> {
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_installation_fence
                       SET installation_id = ?,
                           incarnation = ?,
                           authority_epoch = 1,
                           system_state = 'ACTIVE',
                           quarantined = false,
                           quarantine_reason = NULL,
                           quarantined_at = NULL,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE id = 1
                    """,
                    installationId,
                    incarnation
                );

                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_principal_projection (
                        principal_id, kind, username, display_name, enabled,
                        system_roles, authority_epoch, authority_digest
                    ) VALUES (?, 'PERSON', ?, ?, true, '["INSTALLATION_OWNER"]'::jsonb, 1, ?)
                    ON CONFLICT (principal_id) DO UPDATE
                       SET username = EXCLUDED.username,
                           display_name = EXCLUDED.display_name,
                           enabled = true,
                           system_roles = EXCLUDED.system_roles,
                           authority_digest = EXCLUDED.authority_digest,
                           version_no = mc_principal_projection.version_no + 1,
                           synced_at = clock_timestamp()
                    """,
                    ownerPrincipalId,
                    username,
                    displayName,
                    authorityDigest
                );

                adminReceiptService.recordAndMirrorAdminReceipt(
                    installationId,
                    ownerPrincipalId,
                    null,
                    "SYSTEM_INITIALISE",
                    "INSTALLATION",
                    installationId.toString(),
                    null,
                    null,
                    Map.of(
                        "installationLabel", label,
                        "ownerUsername", username
                    )
                );

                auditChainService.appendInstallationEvent(
                    installationId,
                    ownerPrincipalId,
                    "PERSON",
                    "SYSTEM_INITIALISE",
                    "INSTALLATION",
                    installationId.toString(),
                    "SUCCESS",
                    null,
                    Map.of(
                        "installationLabel", label,
                        "ownerUsername", username,
                        "ownerPrincipalId", ownerPrincipalId.toString()
                    )
                );
            });
            return null;
        });

        authorityGuard.markInitialisedActive(installationId, incarnation);
        MoneyCraftPrincipal ownerPrincipal = new MoneyCraftPrincipal(
            ownerPrincipalId,
            "PERSON",
            username,
            displayName,
            List.of(SystemRole.INSTALLATION_OWNER),
            List.of(),
            false,
            null,
            List.of(),
            List.of()
        );
        return new BootstrapResult(installationId, ownerPrincipal, recoveryReceiptCode);
    }

    private UUID resolveInstallationId() {
        UUID configured = properties.getInstallationId();
        UUID zeroDefault = UUID.fromString("00000000-0000-4000-8000-000000000000");
        if (configured != null && !configured.equals(zeroDefault)) {
            return configured;
        }
        return UUID.randomUUID();
    }

    private static String generateRecoveryReceiptCode() {
        byte[] entropy = new byte[16];
        SECURE_RANDOM.nextBytes(entropy);
        String hex = HexFormat.of().withUpperCase().formatHex(entropy);
        return "MCRK-" + hex.substring(0, 8) + "-" + hex.substring(8, 16)
            + "-" + hex.substring(16, 24) + "-" + hex.substring(24, 32);
    }

    private static void validateBootstrapRequest(AuthDtos.InitialiseInstallationRequest req) {
        if (req == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-BOOTSTRAP-INVALID",
                "Invalid Initialisation Payload",
                "Initialisation request body is required."
            );
        }
        if (req.ownerUsername() == null || req.ownerUsername().trim().isEmpty()
            || req.ownerUsername().trim().length() > 100) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-BOOTSTRAP-USERNAME-INVALID",
                "Invalid Owner Username",
                "ownerUsername must be between 1 and 100 characters."
            );
        }
        if (req.ownerDisplayName() == null || req.ownerDisplayName().trim().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-BOOTSTRAP-DISPLAYNAME-INVALID",
                "Invalid Owner Display Name",
                "ownerDisplayName is required."
            );
        }
        if (req.password() == null || req.password().length() < 12) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-BOOTSTRAP-PASSWORD-WEAK",
                "Master Password Too Short",
                "Master password must be at least 12 characters long."
            );
        }
    }

    public record BootstrapResult(
        UUID installationId,
        MoneyCraftPrincipal ownerPrincipal,
        String recoveryReceiptCode
    ) {
    }
}
