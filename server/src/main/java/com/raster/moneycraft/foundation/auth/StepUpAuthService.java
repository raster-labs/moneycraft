package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.OidcClockSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Issues and atomically consumes 5-minute single-use server-side step-up intents bound to
 * (principalId, installationId, incarnation, targetProfileId, action, expectedRevision) per Doc 52 §3.
 */
@Service
public class StepUpAuthService {

    public static final Duration STEP_UP_TTL = Duration.ofMinutes(5);

    private static final Set<String> ADMITTED_ACTIONS = Set.of(
        "CREATE",
        "UPDATE",
        "STAGE_CREDENTIAL",
        "ROTATE",
        "VALIDATE",
        "ACTIVATE",
        "DISABLE"
    );

    private final AuthorityGuard authorityGuard;
    private final OidcClockSource clockSource;
    private final Argon2PasswordEncoder passwordEncoder;
    private final JdbcTemplate authorityJdbcTemplate;

    public StepUpAuthService(
        AuthorityGuard authorityGuard,
        OidcClockSource clockSource,
        Argon2PasswordEncoder passwordEncoder,
        @Qualifier("authorityJdbcTemplate") JdbcTemplate authorityJdbcTemplate
    ) {
        this.authorityGuard = authorityGuard;
        this.clockSource = clockSource;
        this.passwordEncoder = passwordEncoder;
        this.authorityJdbcTemplate = authorityJdbcTemplate;
    }

    public AuthDtos.PasswordStepUpResponse issuePasswordStepUp(
        MoneyCraftPrincipal superAdmin,
        AuthDtos.PasswordStepUpRequest request
    ) {
        authorityGuard.assertWritable();
        if (request == null || request.password() == null || request.password().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-STEP-UP-PASSWORD-REQUIRED",
                "Step-Up Password Required",
                "Current Super Admin password is required to mint a step-up intent."
            );
        }

        String normalizedAction = normalizeAction(request.action());
        UUID targetProfileId = request.targetProfileId();
        Long expectedRevision = request.expectedRevision();

        if ("CREATE".equals(normalizedAction)) {
            if (targetProfileId != null || expectedRevision != null) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-STEP-UP-BINDING-INVALID",
                    "Invalid Step-Up Binding",
                    "CREATE step-up intent must omit targetProfileId and expectedRevision."
                );
            }
        } else {
            if (targetProfileId == null || expectedRevision == null || expectedRevision < 1L) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-STEP-UP-BINDING-INVALID",
                    "Target Profile and Expected Revision Required",
                    "Action " + normalizedAction + " requires both targetProfileId and expectedRevision >= 1."
                );
            }
        }

        StateRow state = loadActiveInstallationState();

        List<String> passwordRows = authorityJdbcTemplate.query(
            """
            SELECT pw.argon2id_hash
              FROM ma_password pw
              JOIN ma_principal p ON p.principal_id = pw.principal_id
             WHERE pw.principal_id = ? AND p.status = 'ENABLED'
            """,
            (rs, rowNum) -> rs.getString("argon2id_hash"),
            superAdmin.principalId()
        );
        if (passwordRows.isEmpty() || !passwordEncoder.matches(request.password(), passwordRows.getFirst())) {
            recordStepUpAudit(
                state.installationId(),
                targetProfileId,
                superAdmin.principalId(),
                expectedRevision,
                "DENIED",
                "MC-STEP-UP-INVALID-CREDENTIALS",
                "Password step-up verification failed for action " + normalizedAction + "."
            );
            throw new MoneyCraftException(
                HttpStatus.UNAUTHORIZED,
                "MC-STEP-UP-INVALID-CREDENTIALS",
                "Step-Up Password Verification Failed",
                "Supplied password did not match the current Super Admin credential."
            );
        }

        if (!"CREATE".equals(normalizedAction)) {
            List<Long> currentProfileRevs = authorityJdbcTemplate.query(
                "SELECT revision FROM ma_gov_integration_profile WHERE profile_id = ? AND installation_id = ?",
                (rs, rowNum) -> rs.getLong("revision"),
                targetProfileId,
                state.installationId()
            );
            if (currentProfileRevs.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-GOV-PROFILE-NOT-FOUND",
                    "Integration Profile Not Found",
                    "Target government integration profile " + targetProfileId + " does not exist."
                );
            }
            if (!Objects.equals(currentProfileRevs.getFirst(), expectedRevision)) {
                recordStepUpAudit(
                    state.installationId(),
                    targetProfileId,
                    superAdmin.principalId(),
                    expectedRevision,
                    "DENIED",
                    "MC-STEP-UP-REVISION-STALE",
                    "Step-up requested against stale profile revision " + expectedRevision + "."
                );
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-STEP-UP-REVISION-STALE",
                    "Profile Revision Changed",
                    "Target profile revision is now " + currentProfileRevs.getFirst()
                        + "; refresh before requesting step-up."
                );
            }
        }

        Instant issuedInstant = clockSource.instant();
        Instant expiresInstant = issuedInstant.plus(STEP_UP_TTL);
        OffsetDateTime issuedAt = OffsetDateTime.ofInstant(issuedInstant, ZoneOffset.UTC);
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(expiresInstant, ZoneOffset.UTC);
        UUID intentId = UUID.randomUUID();

        authorityJdbcTemplate.update(
            """
            UPDATE ma_principal
               SET last_authenticated_at = ?,
                   updated_at = clock_timestamp()
             WHERE principal_id = ?
            """,
            issuedAt,
            superAdmin.principalId()
        );

        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_step_up_intent (
                intent_id, installation_id, incarnation, principal_id,
                target_profile_id, action, expected_revision, state, issued_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?)
            """,
            intentId,
            state.installationId(),
            state.incarnation(),
            superAdmin.principalId(),
            targetProfileId,
            normalizedAction,
            expectedRevision,
            issuedAt,
            expiresAt
        );

        recordStepUpAudit(
            state.installationId(),
            targetProfileId,
            superAdmin.principalId(),
            expectedRevision,
            "SUCCEEDED",
            null,
            "Issued 5-minute single-use step-up intent " + intentId + " for action " + normalizedAction + "."
        );

        return new AuthDtos.PasswordStepUpResponse(
            intentId,
            expiresAt,
            normalizedAction,
            targetProfileId,
            expectedRevision
        );
    }

    public void verifyAndConsumeStepUpIntent(
        UUID intentId,
        UUID actorPrincipalId,
        Set<String> acceptedActions,
        UUID expectedProfileId,
        Long expectedProfileRevision
    ) {
        if (intentId == null) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-STEP-UP-REQUIRED",
                "Step-Up Proof Required",
                "A fresh target/action/revision-bound stepUpIntentId is required for this operation."
            );
        }

        StateRow state = loadActiveInstallationState();
        List<IntentRow> rows = authorityJdbcTemplate.query(
            """
            SELECT intent_id, installation_id, incarnation, principal_id,
                   target_profile_id, action, expected_revision, state, expires_at
              FROM ma_step_up_intent
             WHERE intent_id = ?
               FOR UPDATE
            """,
            (rs, rowNum) -> new IntentRow(
                rs.getObject("intent_id", UUID.class),
                rs.getObject("installation_id", UUID.class),
                rs.getObject("incarnation", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getObject("target_profile_id", UUID.class),
                rs.getString("action"),
                rs.getObject("expected_revision", Long.class),
                rs.getString("state"),
                rs.getObject("expires_at", OffsetDateTime.class)
            ),
            intentId
        );

        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-STEP-UP-INVALID",
                "Step-Up Intent Unknown",
                "Step-up intent was not found in Protected Authority DB."
            );
        }

        IntentRow row = rows.getFirst();
        Instant now = clockSource.instant();
        if (!"ISSUED".equals(row.state())) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-STEP-UP-REPLAYED",
                "Step-Up Intent Already Used or Invalidated",
                "Single-use step-up intent is in state " + row.state() + " and cannot be reused."
            );
        }
        if (!row.expiresAt().toInstant().isAfter(now)) {
            authorityJdbcTemplate.update(
                "UPDATE ma_step_up_intent SET state = 'EXPIRED' WHERE intent_id = ? AND state = 'ISSUED'",
                intentId
            );
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-STEP-UP-EXPIRED",
                "Step-Up Intent Expired",
                "Step-up intent has expired beyond its 5-minute validity window."
            );
        }
        if (!row.installationId().equals(state.installationId())
            || !row.incarnation().equals(state.incarnation())
            || !row.principalId().equals(actorPrincipalId)
            || !acceptedActions.contains(row.action())
            || !Objects.equals(row.targetProfileId(), expectedProfileId)
            || !Objects.equals(row.expectedRevision(), expectedProfileRevision)) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-STEP-UP-BINDING-MISMATCH",
                "Step-Up Intent Binding Mismatch",
                "Step-up intent does not match current actor, incarnation, target profile, action, or revision."
            );
        }

        int updated = authorityJdbcTemplate.update(
            """
            UPDATE ma_step_up_intent
               SET state = 'CONSUMED',
                   consumed_at = ?
             WHERE intent_id = ? AND state = 'ISSUED'
            """,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            intentId
        );
        if (updated != 1) {
            throw new MoneyCraftException(
                HttpStatus.FORBIDDEN,
                "MC-STEP-UP-REPLAYED",
                "Step-Up Intent Already Consumed",
                "Concurrent request already consumed this single-use step-up intent."
            );
        }
    }

    public static String normalizeAction(String rawAction) {
        if (rawAction == null || rawAction.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-STEP-UP-ACTION-INVALID",
                "Invalid Step-Up Action",
                "Step-up action is required."
            );
        }
        String upper = rawAction.trim().toUpperCase(Locale.ROOT);
        String mapped = switch (upper) {
            case "GOVERNMENT.PROFILE.CREATE" -> "CREATE";
            case "GOVERNMENT.PROFILE.UPDATE", "EDIT" -> "UPDATE";
            case "GOVERNMENT.PROFILE.CREDENTIAL.STAGE", "CREDENTIAL_STAGE" -> "STAGE_CREDENTIAL";
            case "GOVERNMENT.PROFILE.VALIDATION.START", "VALIDATE_CONNECTION" -> "VALIDATE";
            case "GOVERNMENT.PROFILE.ACTIVATE", "APPLY" -> "ACTIVATE";
            case "GOVERNMENT.PROFILE.DISABLE", "REVOKE" -> "DISABLE";
            default -> upper;
        };
        if (!ADMITTED_ACTIONS.contains(mapped)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-STEP-UP-ACTION-INVALID",
                "Unsupported Step-Up Action",
                "Unsupported step-up action '" + rawAction + "'. Expected one of " + ADMITTED_ACTIONS + "."
            );
        }
        return mapped;
    }

    private StateRow loadActiveInstallationState() {
        List<StateRow> rows = authorityJdbcTemplate.query(
            "SELECT installation_id, incarnation FROM ma_state WHERE singleton = 1 AND security_state = 'ACTIVE'",
            (rs, rowNum) -> new StateRow(
                rs.getObject("installation_id", UUID.class),
                rs.getObject("incarnation", UUID.class)
            )
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MC-SYSTEM-NOT-ACTIVE",
                "Installation Authority Not Active",
                "Protected Authority installation state is not ACTIVE."
            );
        }
        return rows.getFirst();
    }

    private void recordStepUpAudit(
        UUID installationId,
        UUID profileId,
        UUID actorPrincipalId,
        Long expectedRevision,
        String outcome,
        String reasonCode,
        String sanitisedDetail
    ) {
        authorityJdbcTemplate.update(
            """
            INSERT INTO ma_gov_audit_event (
                event_id, installation_id, profile_id, actor_principal_id, actor_kind,
                profile_revision, action, outcome, reason_code, sanitised_detail, correlation_id
            ) VALUES (?, ?, ?, ?, 'PERSON', ?, 'STEP_UP_PASSWORD', ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
            installationId,
            profileId,
            actorPrincipalId,
            expectedRevision,
            outcome,
            reasonCode,
            sanitisedDetail,
            UUID.randomUUID().toString()
        );
    }

    private record StateRow(UUID installationId, UUID incarnation) {
    }

    private record IntentRow(
        UUID intentId,
        UUID installationId,
        UUID incarnation,
        UUID principalId,
        UUID targetProfileId,
        String action,
        Long expectedRevision,
        String state,
        OffsetDateTime expiresAt
    ) {
    }
}
