package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AdminReceiptService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.events.SseBroadcasterService;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.security.ServiceApiKeyAuthFilter;
import com.raster.moneycraft.foundation.security.SystemRole;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_FAILED_ATTEMPTS = 10;

    private final MoneyCraftProperties properties;
    private final AuthorityGuard authorityGuard;
    private final AdminReceiptService adminReceiptService;
    private final AuditChainService auditChainService;
    private final RbacAuthorizationService rbacAuthorizationService;
    private final SseBroadcasterService sseBroadcasterService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final Argon2PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate authorityJdbcTemplate;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate authorityTxTemplate;
    private final TransactionTemplate businessTxTemplate;

    public AuthService(
        MoneyCraftProperties properties,
        AuthorityGuard authorityGuard,
        AdminReceiptService adminReceiptService,
        AuditChainService auditChainService,
        RbacAuthorizationService rbacAuthorizationService,
        SseBroadcasterService sseBroadcasterService,
        JcsCanonicalizer jcsCanonicalizer,
        Argon2PasswordEncoder passwordEncoder,
        ObjectMapper objectMapper,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("authorityTransactionManager") PlatformTransactionManager authorityTransactionManager,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.properties = properties;
        this.authorityGuard = authorityGuard;
        this.adminReceiptService = adminReceiptService;
        this.auditChainService = auditChainService;
        this.rbacAuthorizationService = rbacAuthorizationService;
        this.sseBroadcasterService = sseBroadcasterService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.authorityTxTemplate = new TransactionTemplate(authorityTransactionManager);
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public MoneyCraftPrincipal authenticatePassword(AuthDtos.LoginRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()
            || request.password() == null || request.password().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-AUTH-CREDENTIALS-REQUIRED",
                "Credentials Required",
                "Both username and password are required."
            );
        }

        List<LoginTargetRow> rows = authorityJdbcTemplate.query(
            """
            SELECT p.principal_id, p.status, p.failed_login_count, p.locked_until, pw.argon2id_hash
              FROM ma_principal p
              JOIN ma_password pw ON pw.principal_id = p.principal_id
             WHERE p.username = ?
            """,
            (rs, rowNum) -> new LoginTargetRow(
                rs.getObject("principal_id", UUID.class),
                rs.getString("status"),
                rs.getInt("failed_login_count"),
                rs.getObject("locked_until", OffsetDateTime.class),
                rs.getString("argon2id_hash")
            ),
            request.username().trim()
        );

        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-AUTH-INVALID-CREDENTIALS",
                "Invalid Credentials",
                "Username or password could not be verified."
            );
        }

        LoginTargetRow target = rows.getFirst();
        if (!"ENABLED".equals(target.status())
            || (target.lockedUntil() != null && target.lockedUntil().isAfter(OffsetDateTime.now()))) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-AUTH-ACCOUNT-LOCKED",
                "Account Locked or Disabled",
                "Principal account is currently locked or disabled."
            );
        }

        if (!passwordEncoder.matches(request.password(), target.argon2idHash())) {
            int nextFailed = target.failedLoginCount() + 1;
            if (nextFailed >= MAX_FAILED_ATTEMPTS) {
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_principal
                       SET failed_login_count = ?,
                           locked_until = clock_timestamp() + interval '15 minutes',
                           updated_at = clock_timestamp()
                     WHERE principal_id = ?
                    """,
                    nextFailed,
                    target.principalId()
                );
            } else {
                authorityJdbcTemplate.update(
                    """
                    UPDATE ma_principal
                       SET failed_login_count = ?,
                           updated_at = clock_timestamp()
                     WHERE principal_id = ?
                    """,
                    nextFailed,
                    target.principalId()
                );
            }
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-AUTH-INVALID-CREDENTIALS",
                "Invalid Credentials",
                "Username or password could not be verified."
            );
        }

        authorityJdbcTemplate.update(
            """
            UPDATE ma_principal
               SET failed_login_count = 0,
                   locked_until = NULL,
                   last_authenticated_at = clock_timestamp(),
                   updated_at = clock_timestamp()
             WHERE principal_id = ?
            """,
            target.principalId()
        );

        return rbacAuthorizationService.loadPrincipalProfile(target.principalId());
    }

    public AuthDtos.AuthenticatedPrincipalDto establishSession(
        HttpServletRequest request,
        HttpServletResponse response,
        CookieCsrfTokenRepository csrfTokenRepository,
        MoneyCraftPrincipal principal
    ) {
        HttpSession existing = request.getSession(false);
        HttpSession session;
        if (existing != null) {
            request.changeSessionId();
            session = existing;
        } else {
            session = request.getSession(true);
        }

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            principal.toGrantedAuthorities()
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authToken);
        SecurityContextHolder.setContext(context);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        CsrfToken csrf = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(csrf, request, response);
        return AuthDtos.AuthenticatedPrincipalDto.from(principal, csrf.getToken());
    }

    public void logoutSession(
        HttpServletRequest request,
        HttpServletResponse response,
        CookieCsrfTokenRepository csrfTokenRepository
    ) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
    }

    public AuthDtos.AdminPrincipalRecordDto createPrincipal(
        MoneyCraftPrincipal actor,
        AuthDtos.CreatePrincipalRequest request
    ) {
        authorityGuard.assertWritable();
        if (request == null || request.username() == null || request.username().isBlank()
            || request.displayName() == null || request.displayName().isBlank()
            || request.password() == null || request.password().length() < 8) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PRINCIPAL-INVALID",
                "Invalid Principal Payload",
                "username, displayName, and password (minimum 8 characters) are required."
            );
        }

        String username = request.username().trim();
        String displayName = request.displayName().trim();
        String email = (request.email() == null || request.email().isBlank()) ? null : request.email().trim();
        List<SystemRole> sysRoles = request.systemRoles() == null ? List.of() : List.copyOf(request.systemRoles());

        Integer dupCount = authorityJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ma_principal WHERE username = ?",
            Integer.class,
            username
        );
        if (dupCount != null && dupCount > 0) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-PRINCIPAL-DUPLICATE-USERNAME",
                "Username Already Exists",
                "A principal with username '" + username + "' already exists."
            );
        }

        UUID installationId = requireActiveInstallationId();
        UUID newPrincipalId = UUID.randomUUID();
        String rolesJson = toJsonString(sysRoles.stream().map(Enum::name).toList());
        String argonHash = passwordEncoder.encode(request.password());
        MoneyCraftProperties.Argon2 argonCfg = properties.getSecurity().getArgon2();

        authorityTxTemplate.executeWithoutResult(status -> {
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_principal (
                    principal_id, installation_id, kind, username, display_name, email,
                    status, auth_authority, system_roles, owner_principal_id
                ) VALUES (?, ?, 'PERSON', ?, ?, ?, 'ENABLED', 'LOCAL', ?::jsonb, ?)
                """,
                newPrincipalId,
                installationId,
                username,
                displayName,
                email,
                rolesJson,
                actor.principalId()
            );

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_password (
                    principal_id, argon2id_hash, memory_kib, iterations, parallelism, must_change_password
                ) VALUES (?, ?, ?, ?, ?, false)
                """,
                newPrincipalId,
                argonHash,
                Math.max(19456, argonCfg.getMemoryKib()),
                argonCfg.getIterations(),
                argonCfg.getParallelism()
            );
        });

        byte[] digest = jcsCanonicalizer.sha256(
            ("PRINCIPAL|" + newPrincipalId + "|" + username + "|" + rolesJson).getBytes(StandardCharsets.UTF_8)
        );

        TenantContextHolder.callWithSystemBypass("admin-create-principal", () -> {
            businessTxTemplate.executeWithoutResult(status -> {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_principal_projection (
                        principal_id, kind, username, display_name, enabled,
                        system_roles, authority_epoch, authority_digest
                    ) VALUES (?, 'PERSON', ?, ?, true, ?::jsonb, 1, ?)
                    """,
                    newPrincipalId,
                    username,
                    displayName,
                    rolesJson,
                    digest
                );

                adminReceiptService.recordAndMirrorAdminReceipt(
                    installationId,
                    actor.principalId(),
                    null,
                    "PRINCIPAL_CREATE",
                    "PRINCIPAL",
                    newPrincipalId.toString(),
                    null,
                    null,
                    Map.of("username", username, "systemRoles", sysRoles.stream().map(Enum::name).toList())
                );

                auditChainService.appendInstallationEvent(
                    installationId,
                    actor.principalId(),
                    actor.kind(),
                    "PRINCIPAL_CREATE",
                    "PRINCIPAL",
                    newPrincipalId.toString(),
                    "SUCCESS",
                    null,
                    Map.of(
                        "username", username,
                        "displayName", displayName,
                        "systemRoles", sysRoles.stream().map(Enum::name).toList()
                    )
                );
            });
            return null;
        });

        return new AuthDtos.AdminPrincipalRecordDto(
            newPrincipalId,
            username,
            displayName,
            sysRoles,
            true
        );
    }

    public List<AuthDtos.AdminPrincipalRecordDto> listPrincipals() {
        return authorityJdbcTemplate.query(
            """
            SELECT principal_id, COALESCE(username::text, display_name) AS username,
                   display_name, status, system_roles::text AS roles_json
              FROM ma_principal
             ORDER BY created_at ASC
            """,
            (rs, rowNum) -> new AuthDtos.AdminPrincipalRecordDto(
                rs.getObject("principal_id", UUID.class),
                rs.getString("username"),
                rs.getString("display_name"),
                rbacAuthorizationService.parseSystemRoles(rs.getString("roles_json")),
                "ENABLED".equals(rs.getString("status"))
            )
        );
    }

    public AuthDtos.CompanyGrantSummaryDto assignCompanyGrant(
        MoneyCraftPrincipal actor,
        String principalRef,
        AuthDtos.AssignCompanyGrantRequest request
    ) {
        authorityGuard.assertWritable();
        if (request == null || request.companyId() == null || request.roleCode() == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GRANT-INVALID",
                "Invalid Role Grant Payload",
                "companyId and roleCode are required."
            );
        }
        if (request.backdatedDaysLimit() != null && request.backdatedDaysLimit() < 0) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-GRANT-BACKDATED-INVALID",
                "Invalid Backdated Days Limit",
                "backdatedDaysLimit must be greater than or equal to 0."
            );
        }

        UUID targetPrincipalId = resolvePrincipalReference(principalRef);
        UUID companyId = request.companyId();

        Integer compExists = TenantContextHolder.callWithSystemBypass("verify-company-exists", () ->
            businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_company WHERE id = ?",
                Integer.class,
                companyId
            )
        );
        if (compExists == null || compExists == 0) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-COMPANY-NOT-FOUND",
                "Company Not Found",
                "Target company " + companyId + " does not exist."
            );
        }

        UUID installationId = requireActiveInstallationId();
        List<String> voucherAllowlist = request.voucherTypeAllowlist() == null
            ? List.of()
            : List.copyOf(request.voucherTypeAllowlist());
        boolean exportPermitted = request.exportPermitted() != null
            ? request.exportPermitted()
            : (request.roleCode() == CompanyRole.COMPANY_ADMIN
                || request.roleCode() == CompanyRole.AUDITOR_APPROVER
                || request.roleCode() == CompanyRole.SENIOR_ACCOUNTANT);

        UUID grantId = upsertAuthorityAndBusinessGrant(
            installationId,
            actor.principalId(),
            actor.kind(),
            targetPrincipalId,
            companyId,
            request.roleCode(),
            voucherAllowlist,
            request.backdatedDaysLimit(),
            exportPermitted,
            true
        );

        sseBroadcasterService.broadcast(
            "GRANT_UPDATED",
            companyId,
            Map.of(
                "principalId", targetPrincipalId.toString(),
                "companyId", companyId.toString(),
                "roleCode", request.roleCode().name()
            )
        );

        return new AuthDtos.CompanyGrantSummaryDto(
            grantId,
            companyId,
            request.roleCode(),
            voucherAllowlist,
            request.backdatedDaysLimit(),
            exportPermitted
        );
    }

    public UUID grantCompanyCreatorAdminWithinCurrentTx(
        UUID installationId,
        UUID companyId,
        MoneyCraftPrincipal creator
    ) {
        return upsertAuthorityAndBusinessGrant(
            installationId,
            creator.principalId(),
            creator.kind(),
            creator.principalId(),
            companyId,
            CompanyRole.COMPANY_ADMIN,
            List.of(),
            null,
            true,
            false
        );
    }

    public AuthDtos.CreatedServiceKeyResponse issueServiceKey(
        MoneyCraftPrincipal actor,
        AuthDtos.CreateServiceKeyRequest request
    ) {
        authorityGuard.assertWritable();
        if (request == null || request.label() == null || request.label().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-SERVICE-KEY-LABEL-REQUIRED",
                "Service Key Label Required",
                "Provide a descriptive label for the scoped service API key."
            );
        }

        String label = request.label().trim();
        OffsetDateTime expiresAt = request.expiresAt() != null
            ? request.expiresAt()
            : OffsetDateTime.now().plusDays(90);
        if (!expiresAt.isAfter(OffsetDateTime.now().plusMinutes(1))) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-SERVICE-KEY-EXPIRY-INVALID",
                "Invalid Service Key Expiration",
                "Service API key expiration must be in the future."
            );
        }

        List<UUID> companyIds = request.companyIds() == null ? List.of() : List.copyOf(request.companyIds());
        List<String> endpointAllowlist = request.endpointAllowlist() == null
            ? List.of()
            : List.copyOf(request.endpointAllowlist());

        UUID installationId = requireActiveInstallationId();
        UUID servicePrincipalId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();

        byte[] prefixBytes = new byte[5];
        SECURE_RANDOM.nextBytes(prefixBytes);
        String keyPrefix = HexFormat.of().formatHex(prefixBytes);

        byte[] secretBytes = new byte[32];
        SECURE_RANDOM.nextBytes(secretBytes);
        String rawSecretPart = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String fullTokenOnce = ServiceApiKeyAuthFilter.KEY_PREFIX_MARKER + keyPrefix + "_" + rawSecretPart;
        String secretHash = ServiceApiKeyAuthFilter.hashServiceKeySecret(
            jcsCanonicalizer,
            keyPrefix,
            rawSecretPart
        );

        String companiesJson = toJsonString(companyIds.stream().map(UUID::toString).toList());
        String endpointsJson = toJsonString(endpointAllowlist);
        String svcUsername = "svc-" + keyPrefix;

        authorityTxTemplate.executeWithoutResult(status -> {
            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_principal (
                    principal_id, installation_id, kind, username, display_name,
                    status, auth_authority, system_roles, owner_principal_id
                ) VALUES (?, ?, 'SERVICE', ?, ?, 'ENABLED', 'SERVICE_KEY', '[]'::jsonb, ?)
                """,
                servicePrincipalId,
                installationId,
                svcUsername,
                label,
                actor.principalId()
            );

            authorityJdbcTemplate.update(
                """
                INSERT INTO ma_service_key (
                    id, principal_id, name, key_prefix, secret_hash,
                    scopes, company_allowlist, endpoint_allowlist,
                    state, expires_at, created_by
                ) VALUES (?, ?, ?, ?, ?, '["COMPANY_API"]'::jsonb, ?::jsonb, ?::jsonb, 'ACTIVE', ?, ?)
                """,
                keyId,
                servicePrincipalId,
                label,
                keyPrefix,
                secretHash,
                companiesJson,
                endpointsJson,
                expiresAt,
                actor.principalId()
            );
        });

        byte[] svcDigest = jcsCanonicalizer.sha256(
            ("SERVICE_PRINCIPAL|" + servicePrincipalId + "|" + keyPrefix).getBytes(StandardCharsets.UTF_8)
        );

        TenantContextHolder.callWithSystemBypass("admin-issue-service-key", () -> {
            businessTxTemplate.executeWithoutResult(status -> {
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_principal_projection (
                        principal_id, kind, username, display_name, enabled,
                        system_roles, authority_epoch, authority_digest
                    ) VALUES (?, 'SERVICE', ?, ?, true, '[]'::jsonb, 1, ?)
                    """,
                    servicePrincipalId,
                    svcUsername,
                    label,
                    svcDigest
                );

                adminReceiptService.recordAndMirrorAdminReceipt(
                    installationId,
                    actor.principalId(),
                    null,
                    "SERVICE_KEY_ISSUE",
                    "SERVICE_KEY",
                    keyId.toString(),
                    null,
                    null,
                    Map.of(
                        "keyPrefix", keyPrefix,
                        "label", label,
                        "companyCount", companyIds.size()
                    )
                );

                auditChainService.appendInstallationEvent(
                    installationId,
                    actor.principalId(),
                    actor.kind(),
                    "SERVICE_KEY_ISSUE",
                    "SERVICE_KEY",
                    keyId.toString(),
                    "SUCCESS",
                    null,
                    Map.of(
                        "keyPrefix", keyPrefix,
                        "label", label,
                        "servicePrincipalId", servicePrincipalId.toString(),
                        "companyIds", companyIds.stream().map(UUID::toString).toList()
                    )
                );
            });
            return null;
        });

        for (UUID companyId : companyIds) {
            upsertAuthorityAndBusinessGrant(
                installationId,
                actor.principalId(),
                actor.kind(),
                servicePrincipalId,
                companyId,
                CompanyRole.COMPANY_ADMIN,
                List.of(),
                null,
                true,
                false
            );
        }

        return new AuthDtos.CreatedServiceKeyResponse(
            keyId,
            label,
            keyPrefix,
            fullTokenOnce,
            expiresAt
        );
    }

    public UUID requireActiveInstallationId() {
        List<UUID> ids = authorityJdbcTemplate.query(
            "SELECT installation_id FROM ma_state WHERE singleton = 1",
            (rs, rowNum) -> rs.getObject("installation_id", UUID.class)
        );
        if (ids.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-SYSTEM-UNINITIALISED",
                "Installation Not Initialised",
                "Protected Authority ma_state has not been initialised yet."
            );
        }
        return ids.getFirst();
    }

    private UUID resolvePrincipalReference(String principalRef) {
        if (principalRef == null || principalRef.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PRINCIPAL-REF-REQUIRED",
                "Principal Identifier Required",
                "Specify a valid principal UUID or username."
            );
        }
        String trimmed = principalRef.trim();
        try {
            UUID parsed = UUID.fromString(trimmed);
            List<UUID> byId = authorityJdbcTemplate.query(
                "SELECT principal_id FROM ma_principal WHERE principal_id = ?",
                (rs, rowNum) -> rs.getObject("principal_id", UUID.class),
                parsed
            );
            if (!byId.isEmpty()) {
                return byId.getFirst();
            }
        } catch (IllegalArgumentException ignored) {
            // Look up by username below
        }

        List<UUID> byUsername = authorityJdbcTemplate.query(
            "SELECT principal_id FROM ma_principal WHERE username = ?",
            (rs, rowNum) -> rs.getObject("principal_id", UUID.class),
            trimmed
        );
        if (byUsername.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-PRINCIPAL-NOT-FOUND",
                "Principal Not Found",
                "No principal found matching '" + trimmed + "'."
            );
        }
        return byUsername.getFirst();
    }

    private UUID upsertAuthorityAndBusinessGrant(
        UUID installationId,
        UUID actorPrincipalId,
        String actorKind,
        UUID targetPrincipalId,
        UUID companyId,
        CompanyRole roleCode,
        List<String> voucherAllowlist,
        Integer backdatedDaysLimit,
        boolean exportPermitted,
        boolean appendStandaloneAudit
    ) {
        String allowlistJson = toJsonString(voucherAllowlist);
        byte[] guardDigest = jcsCanonicalizer.sha256(
            ("GRANT|" + targetPrincipalId + "|" + companyId + "|" + roleCode.name()).getBytes(StandardCharsets.UTF_8)
        );

        UUID grantId = authorityTxTemplate.execute(status -> {
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
                targetPrincipalId,
                companyId,
                guardDigest
            );
            long resolvedEpoch = epoch == null ? 1L : epoch;

            UUID candidateId = UUID.randomUUID();
            return authorityJdbcTemplate.queryForObject(
                """
                INSERT INTO ma_grant (
                    grant_id, principal_id, company_id, role_code, permissions,
                    voucher_type_allowlist, backdated_days_limit, export_permitted,
                    private_fields_permitted, grant_epoch, state, granted_by
                ) VALUES (?, ?, ?, ?, '[]'::jsonb, ?::jsonb, ?, ?, true, ?, 'ACTIVE', ?)
                ON CONFLICT (principal_id, company_id, role_code) DO UPDATE
                   SET voucher_type_allowlist = EXCLUDED.voucher_type_allowlist,
                       backdated_days_limit = EXCLUDED.backdated_days_limit,
                       export_permitted = EXCLUDED.export_permitted,
                       grant_epoch = EXCLUDED.grant_epoch,
                       state = 'ACTIVE',
                       granted_by = EXCLUDED.granted_by,
                       version_no = ma_grant.version_no + 1,
                       updated_at = clock_timestamp()
                RETURNING grant_id
                """,
                (rs, rowNum) -> rs.getObject("grant_id", UUID.class),
                candidateId,
                targetPrincipalId,
                companyId,
                roleCode.name(),
                allowlistJson,
                backdatedDaysLimit,
                exportPermitted,
                resolvedEpoch,
                actorPrincipalId
            );
        });

        Runnable mirrorAndProject = () -> {
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_principal_grant (
                    grant_id, principal_id, company_id, role_code, permissions,
                    voucher_type_allowlist, backdated_days_limit, export_permitted,
                    private_fields_permitted, active, grant_epoch, authority_epoch, authority_digest
                ) VALUES (?, ?, ?, ?, '[]'::jsonb, ?::jsonb, ?, ?, true, true, 1, 1, ?)
                ON CONFLICT (principal_id, company_id, role_code) DO UPDATE
                   SET voucher_type_allowlist = EXCLUDED.voucher_type_allowlist,
                       backdated_days_limit = EXCLUDED.backdated_days_limit,
                       export_permitted = EXCLUDED.export_permitted,
                       active = true,
                       grant_epoch = mc_principal_grant.grant_epoch + 1,
                       authority_digest = EXCLUDED.authority_digest,
                       version_no = mc_principal_grant.version_no + 1,
                       synced_at = clock_timestamp()
                """,
                grantId,
                targetPrincipalId,
                companyId,
                roleCode.name(),
                allowlistJson,
                backdatedDaysLimit,
                exportPermitted,
                guardDigest
            );

            adminReceiptService.recordAndMirrorAdminReceipt(
                installationId,
                actorPrincipalId,
                companyId,
                "GRANT_ASSIGN",
                "GRANT",
                grantId.toString(),
                null,
                null,
                Map.of(
                    "principalId", targetPrincipalId.toString(),
                    "companyId", companyId.toString(),
                    "roleCode", roleCode.name()
                )
            );

            if (appendStandaloneAudit) {
                auditChainService.appendCompanyEvent(
                    companyId,
                    actorPrincipalId,
                    actorKind,
                    "GRANT_ASSIGN",
                    "GRANT",
                    grantId.toString(),
                    "SUCCESS",
                    null,
                    Map.of(
                        "principalId", targetPrincipalId.toString(),
                        "roleCode", roleCode.name(),
                        "backdatedDaysLimit", backdatedDaysLimit == null ? -1 : backdatedDaysLimit,
                        "exportPermitted", exportPermitted
                    )
                );
            }
        };

        if (appendStandaloneAudit) {
            TenantContextHolder.callWithSystemBypass("admin-assign-grant", () -> {
                businessTxTemplate.executeWithoutResult(status -> mirrorAndProject.run());
                return null;
            });
        } else {
            mirrorAndProject.run();
        }
        return grantId;
    }

    private String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize JSON array", ex);
        }
    }

    private record LoginTargetRow(
        UUID principalId,
        String status,
        int failedLoginCount,
        OffsetDateTime lockedUntil,
        String argon2idHash
    ) {
    }
}
