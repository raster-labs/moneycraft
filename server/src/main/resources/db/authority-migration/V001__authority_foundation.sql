SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Protected Authority Cluster Foundation (PostgreSQL 18)
-- Stream: db/authority-migration/V001__authority_foundation.sql
-- Trust Boundary: Owns credentials, passkeys, OIDC links, Tink wrapped DEKs,
-- RBAC grants, monotonic admin receipts, recovery ceremonies & SPRING_SESSION.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS citext;

-- 1. Installation Singleton Authority State
CREATE TABLE ma_state (
    installation_id uuid NOT NULL,
    singleton smallint NOT NULL DEFAULT 1,
    incarnation uuid NOT NULL,
    security_state varchar(24) NOT NULL DEFAULT 'UNINITIALISED',
    authority_epoch bigint NOT NULL DEFAULT 0,
    fence_epoch bigint NOT NULL DEFAULT 1,
    last_receipt_seq bigint NOT NULL DEFAULT 0,
    quarantine_reason varchar(512),
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_state PRIMARY KEY (installation_id),
    CONSTRAINT uq_ma_state_singleton UNIQUE (singleton),
    CONSTRAINT chk_ma_state_singleton CHECK (singleton = 1),
    CONSTRAINT chk_ma_state_security_state CHECK (
        security_state IN ('UNINITIALISED', 'ACTIVE', 'MAINTENANCE', 'QUARANTINED', 'RECOVERING')
    ),
    CONSTRAINT chk_ma_state_authority_epoch CHECK (authority_epoch >= 0),
    CONSTRAINT chk_ma_state_fence_epoch CHECK (fence_epoch >= 1),
    CONSTRAINT chk_ma_state_last_receipt_seq CHECK (last_receipt_seq >= 0),
    CONSTRAINT chk_ma_state_version_no CHECK (version_no >= 1)
);

-- 2. Cryptographic Key Registry (Wrapped Tink DEKs & HMAC Keys — Zero Raw KEKs)
CREATE TABLE ma_key (
    id uuid NOT NULL,
    installation_id uuid NOT NULL,
    company_scope uuid,
    key_purpose varchar(32) NOT NULL,
    key_generation bigint NOT NULL DEFAULT 1,
    kek_key_id varchar(160) NOT NULL,
    wrapped_dek bytea NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'ACTIVE',
    activated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    retired_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_key PRIMARY KEY (id),
    CONSTRAINT fk_ma_key_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_key_purpose CHECK (
        key_purpose IN (
            'SIV_LOOKUP',
            'GCM_PAYLOAD',
            'CURSOR_HMAC',
            'IDEMPOTENCY_HMAC',
            'BACKUP_AEAD',
            'RECOVERY_VERIFIER',
            'TOKEN_GCM'
        )
    ),
    CONSTRAINT chk_ma_key_generation CHECK (key_generation >= 1),
    CONSTRAINT chk_ma_key_kek_id_nonempty CHECK (length(trim(kek_key_id)) > 0),
    CONSTRAINT chk_ma_key_wrapped_dek_len CHECK (octet_length(wrapped_dek) BETWEEN 1 AND 65536),
    CONSTRAINT chk_ma_key_state CHECK (
        state IN ('PREPARED', 'ACTIVE', 'DECRYPT_ONLY', 'VERIFY_ONLY', 'ROTATED', 'RETIRED', 'REVOKED')
    ),
    CONSTRAINT chk_ma_key_version_no CHECK (version_no >= 1)
);

CREATE UNIQUE INDEX uq_ma_key_purpose_scope_gen
    ON ma_key (
        key_purpose,
        COALESCE(company_scope, '00000000-0000-0000-0000-000000000000'::uuid),
        key_generation
    );

CREATE INDEX idx_ma_key_active_lookup
    ON ma_key (key_purpose, company_scope, state);

-- 3. Security, Session & Egress Allowlist Policy
CREATE TABLE ma_policy (
    id uuid NOT NULL,
    installation_id uuid NOT NULL,
    company_scope uuid,
    policy_code varchar(64) NOT NULL,
    policy_state varchar(24) NOT NULL DEFAULT 'ENABLED',
    active_key_id uuid,
    session_idle_timeout_sec integer NOT NULL DEFAULT 1800,
    session_absolute_timeout_sec integer NOT NULL DEFAULT 43200,
    outbound_connectors_enabled boolean NOT NULL DEFAULT false,
    egress_hostname_allowlist jsonb NOT NULL DEFAULT '[]'::jsonb,
    config_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_policy PRIMARY KEY (id),
    CONSTRAINT fk_ma_policy_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_policy_active_key FOREIGN KEY (active_key_id)
        REFERENCES ma_key (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_policy_code_nonempty CHECK (length(trim(policy_code)) > 0),
    CONSTRAINT chk_ma_policy_state CHECK (
        policy_state IN ('DISABLED', 'ENABLED', 'ROTATING', 'ENFORCED')
    ),
    CONSTRAINT chk_ma_policy_idle_timeout CHECK (session_idle_timeout_sec BETWEEN 60 AND 86400),
    CONSTRAINT chk_ma_policy_abs_timeout CHECK (
        session_absolute_timeout_sec BETWEEN 300 AND 172800
        AND session_absolute_timeout_sec >= session_idle_timeout_sec
    ),
    CONSTRAINT chk_ma_policy_version_no CHECK (version_no >= 1)
);

CREATE UNIQUE INDEX uq_ma_policy_scope_code
    ON ma_policy (
        COALESCE(company_scope, '00000000-0000-0000-0000-000000000000'::uuid),
        policy_code
    );

-- 4. Enterprise OIDC Identity Provider Registry (PKCE Mandatory)
CREATE TABLE ma_issuer (
    id uuid NOT NULL,
    installation_id uuid NOT NULL,
    registration_id varchar(64) NOT NULL,
    display_name varchar(160) NOT NULL,
    issuer_uri varchar(2048) NOT NULL,
    authorization_uri varchar(2048),
    token_uri varchar(2048),
    jwks_uri varchar(2048),
    userinfo_uri varchar(2048),
    client_id varchar(256) NOT NULL,
    client_secret_gcm bytea,
    key_id uuid,
    scopes varchar(512) NOT NULL DEFAULT 'openid profile email',
    pkce_required boolean NOT NULL DEFAULT true,
    state varchar(16) NOT NULL DEFAULT 'ENABLED',
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_issuer PRIMARY KEY (id),
    CONSTRAINT uq_ma_issuer_registration_id UNIQUE (registration_id),
    CONSTRAINT uq_ma_issuer_uri UNIQUE (issuer_uri),
    CONSTRAINT fk_ma_issuer_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_issuer_key FOREIGN KEY (key_id)
        REFERENCES ma_key (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_issuer_reg_grammar CHECK (registration_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT chk_ma_issuer_pkce CHECK (pkce_required = true),
    CONSTRAINT chk_ma_issuer_state CHECK (state IN ('ENABLED', 'DISABLED')),
    CONSTRAINT chk_ma_issuer_version_no CHECK (version_no >= 1)
);

-- 5. Canonical Principals (Human Operators & Machine-to-Machine Services)
CREATE TABLE ma_principal (
    principal_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    kind varchar(16) NOT NULL DEFAULT 'PERSON',
    username citext,
    display_name varchar(160) NOT NULL,
    email citext,
    status varchar(16) NOT NULL DEFAULT 'ENABLED',
    auth_authority varchar(16) NOT NULL DEFAULT 'LOCAL',
    system_roles jsonb NOT NULL DEFAULT '[]'::jsonb,
    owner_principal_id uuid,
    issuer_id uuid,
    failed_login_count integer NOT NULL DEFAULT 0,
    locked_until timestamptz(6),
    last_authenticated_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_principal PRIMARY KEY (principal_id),
    CONSTRAINT uq_ma_principal_username UNIQUE (username),
    CONSTRAINT fk_ma_principal_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_principal_owner FOREIGN KEY (owner_principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_principal_issuer FOREIGN KEY (issuer_id)
        REFERENCES ma_issuer (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_principal_kind CHECK (kind IN ('PERSON', 'SERVICE')),
    CONSTRAINT chk_ma_principal_status CHECK (
        status IN ('ENABLED', 'DISABLED', 'LOCKED', 'QUARANTINED')
    ),
    CONSTRAINT chk_ma_principal_authority CHECK (
        auth_authority IN ('LOCAL', 'OIDC', 'SERVICE_KEY')
    ),
    CONSTRAINT chk_ma_principal_display_name CHECK (length(trim(display_name)) > 0),
    CONSTRAINT chk_ma_principal_username_present CHECK (
        kind = 'SERVICE' OR (username IS NOT NULL AND length(trim(username::text)) BETWEEN 1 AND 100)
    ),
    CONSTRAINT chk_ma_principal_failed_count CHECK (failed_login_count >= 0),
    CONSTRAINT chk_ma_principal_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_ma_principal_status ON ma_principal (status, kind);

-- 6. One-Time Installation Bootstrap Ceremony
CREATE TABLE ma_initialisation (
    id smallint NOT NULL DEFAULT 1,
    installation_id uuid NOT NULL,
    bootstrap_state varchar(24) NOT NULL DEFAULT 'PENDING',
    owner_principal_id uuid,
    bootstrap_token_hash bytea,
    recovery_kit_acknowledged boolean NOT NULL DEFAULT false,
    initialised_at timestamptz(6),
    expires_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_initialisation PRIMARY KEY (id),
    CONSTRAINT chk_ma_initialisation_singleton CHECK (id = 1),
    CONSTRAINT fk_ma_initialisation_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_initialisation_owner FOREIGN KEY (owner_principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_initialisation_state CHECK (
        bootstrap_state IN ('PENDING', 'BOOTSTRAP', 'COMPLETE')
    ),
    CONSTRAINT chk_ma_initialisation_complete_owner CHECK (
        bootstrap_state <> 'COMPLETE' OR (owner_principal_id IS NOT NULL AND initialised_at IS NOT NULL)
    ),
    CONSTRAINT chk_ma_initialisation_version_no CHECK (version_no >= 1)
);

-- 7. Argon2id Local Password Verifiers (m=65536 KiB, t=3, p=1)
CREATE TABLE ma_password (
    principal_id uuid NOT NULL,
    argon2id_hash text NOT NULL,
    memory_kib integer NOT NULL DEFAULT 65536,
    iterations integer NOT NULL DEFAULT 3,
    parallelism integer NOT NULL DEFAULT 1,
    must_change_password boolean NOT NULL DEFAULT false,
    password_changed_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    version_no bigint NOT NULL DEFAULT 1,
    CONSTRAINT pk_ma_password PRIMARY KEY (principal_id),
    CONSTRAINT fk_ma_password_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_password_argon2id_prefix CHECK (argon2id_hash LIKE '$argon2id$%'),
    CONSTRAINT chk_ma_password_memory_kib CHECK (memory_kib >= 19456),
    CONSTRAINT chk_ma_password_iterations CHECK (iterations >= 1),
    CONSTRAINT chk_ma_password_parallelism CHECK (parallelism >= 1),
    CONSTRAINT chk_ma_password_version_no CHECK (version_no >= 1)
);

-- 8. WebAuthn / FIDO2 Passkey Credentials
CREATE TABLE ma_passkey (
    id uuid NOT NULL,
    principal_id uuid NOT NULL,
    credential_id bytea NOT NULL,
    user_handle bytea NOT NULL,
    public_key_cose bytea NOT NULL,
    sign_count bigint NOT NULL DEFAULT 0,
    label varchar(120) NOT NULL,
    aaguid uuid,
    transports jsonb NOT NULL DEFAULT '[]'::jsonb,
    backup_eligible boolean NOT NULL DEFAULT false,
    backup_state boolean NOT NULL DEFAULT false,
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    last_used_at timestamptz(6),
    revoked_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_passkey PRIMARY KEY (id),
    CONSTRAINT uq_ma_passkey_credential_id UNIQUE (credential_id),
    CONSTRAINT fk_ma_passkey_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_passkey_cred_len CHECK (octet_length(credential_id) BETWEEN 1 AND 1024),
    CONSTRAINT chk_ma_passkey_handle_len CHECK (octet_length(user_handle) BETWEEN 1 AND 64),
    CONSTRAINT chk_ma_passkey_cose_len CHECK (octet_length(public_key_cose) BETWEEN 1 AND 4096),
    CONSTRAINT chk_ma_passkey_sign_count CHECK (sign_count BETWEEN 0 AND 4294967295),
    CONSTRAINT chk_ma_passkey_state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_ma_passkey_revoked_ts CHECK (
        (state = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (state = 'ACTIVE' AND revoked_at IS NULL)
    ),
    CONSTRAINT chk_ma_passkey_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_ma_passkey_principal_state ON ma_passkey (principal_id, state);

-- 9. Enterprise OIDC Subject Links
CREATE TABLE ma_oidc_link (
    id uuid NOT NULL,
    principal_id uuid NOT NULL,
    issuer_id uuid NOT NULL,
    subject varchar(255) NOT NULL,
    external_email citext,
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    linked_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    last_verified_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    CONSTRAINT pk_ma_oidc_link PRIMARY KEY (id),
    CONSTRAINT uq_ma_oidc_link_issuer_subject UNIQUE (issuer_id, subject),
    CONSTRAINT fk_ma_oidc_link_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_oidc_link_issuer FOREIGN KEY (issuer_id)
        REFERENCES ma_issuer (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_oidc_link_subject_nonempty CHECK (length(trim(subject)) > 0),
    CONSTRAINT chk_ma_oidc_link_state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_ma_oidc_link_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_ma_oidc_link_principal ON ma_oidc_link (principal_id, state);

-- 10. Scoped Machine-to-Machine Service API Keys
CREATE TABLE ma_service_key (
    id uuid NOT NULL,
    principal_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    key_prefix varchar(16) NOT NULL,
    secret_hash text NOT NULL,
    scopes jsonb NOT NULL DEFAULT '[]'::jsonb,
    company_allowlist jsonb NOT NULL DEFAULT '[]'::jsonb,
    endpoint_allowlist jsonb NOT NULL DEFAULT '[]'::jsonb,
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    expires_at timestamptz(6) NOT NULL,
    last_used_at timestamptz(6),
    revoked_at timestamptz(6),
    created_by uuid,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_service_key PRIMARY KEY (id),
    CONSTRAINT uq_ma_service_key_prefix UNIQUE (key_prefix),
    CONSTRAINT fk_ma_service_key_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_service_key_created_by FOREIGN KEY (created_by)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_service_key_prefix_len CHECK (length(trim(key_prefix)) BETWEEN 6 AND 16),
    CONSTRAINT chk_ma_service_key_secret_nonempty CHECK (length(trim(secret_hash)) > 0),
    CONSTRAINT chk_ma_service_key_state CHECK (state IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_ma_service_key_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_ma_service_key_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_ma_service_key_principal_state ON ma_service_key (principal_id, state);

-- 11. Per-Principal Company Grant Guard & Company RBAC Grants
CREATE TABLE ma_grant_guard (
    principal_id uuid NOT NULL,
    company_id uuid NOT NULL,
    grant_epoch bigint NOT NULL DEFAULT 1,
    guard_digest bytea,
    version_no bigint NOT NULL DEFAULT 1,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_grant_guard PRIMARY KEY (principal_id, company_id),
    CONSTRAINT fk_ma_grant_guard_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_grant_guard_epoch CHECK (grant_epoch >= 1),
    CONSTRAINT chk_ma_grant_guard_digest CHECK (guard_digest IS NULL OR octet_length(guard_digest) = 32),
    CONSTRAINT chk_ma_grant_guard_version_no CHECK (version_no >= 1)
);

CREATE TABLE ma_grant (
    grant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    company_id uuid NOT NULL,
    role_code varchar(32) NOT NULL,
    permissions jsonb NOT NULL DEFAULT '[]'::jsonb,
    voucher_type_allowlist jsonb NOT NULL DEFAULT '[]'::jsonb,
    backdated_days_limit integer,
    export_permitted boolean NOT NULL DEFAULT false,
    private_fields_permitted boolean NOT NULL DEFAULT true,
    grant_epoch bigint NOT NULL DEFAULT 1,
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    granted_by uuid,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_grant PRIMARY KEY (grant_id),
    CONSTRAINT uq_ma_grant_principal_company_role UNIQUE (principal_id, company_id, role_code),
    CONSTRAINT fk_ma_grant_guard FOREIGN KEY (principal_id, company_id)
        REFERENCES ma_grant_guard (principal_id, company_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_grant_granted_by FOREIGN KEY (granted_by)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_grant_role_code CHECK (
        role_code IN (
            'COMPANY_ADMIN',
            'AUDITOR_APPROVER',
            'SENIOR_ACCOUNTANT',
            'DATA_ENTRY_OPERATOR',
            'INVENTORY_CLERK',
            'READ_ONLY_VIEWER'
        )
    ),
    CONSTRAINT chk_ma_grant_backdated_limit CHECK (
        backdated_days_limit IS NULL OR backdated_days_limit >= 0
    ),
    CONSTRAINT chk_ma_grant_epoch CHECK (grant_epoch >= 1),
    CONSTRAINT chk_ma_grant_state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_ma_grant_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_ma_grant_company_lookup ON ma_grant (company_id, principal_id, state);

-- 12. Signed Keyset Pagination Cursor Key Lifecycle
CREATE TABLE ma_cursor_lifecycle (
    key_id uuid NOT NULL,
    cursor_epoch bigint NOT NULL DEFAULT 1,
    max_issued_expires_at timestamptz(6),
    retired_at timestamptz(6),
    verify_until timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_cursor_lifecycle PRIMARY KEY (key_id),
    CONSTRAINT fk_ma_cursor_lifecycle_key FOREIGN KEY (key_id)
        REFERENCES ma_key (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_cursor_lifecycle_epoch CHECK (cursor_epoch >= 1),
    CONSTRAINT chk_ma_cursor_lifecycle_retirement CHECK (
        (retired_at IS NULL AND verify_until IS NULL)
        OR (retired_at IS NOT NULL AND verify_until IS NOT NULL AND verify_until >= retired_at)
    ),
    CONSTRAINT chk_ma_cursor_lifecycle_version_no CHECK (version_no >= 1)
);

-- 13. Cross-Cluster Monotonic Admin Receipts (Reconciled with Business DB)
CREATE TABLE ma_receipt (
    receipt_seq bigint GENERATED BY DEFAULT AS IDENTITY,
    receipt_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    actor_principal_id uuid,
    company_scope uuid,
    operation varchar(96) NOT NULL,
    target_type varchar(48) NOT NULL,
    target_id varchar(256) NOT NULL,
    idempotency_key varchar(128),
    request_jcs_hmac bytea,
    receipt_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    receipt_hash bytea NOT NULL,
    prev_receipt_hash bytea NOT NULL,
    mirrored_to_business boolean NOT NULL DEFAULT false,
    mirrored_at timestamptz(6),
    committed_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz(6) NOT NULL DEFAULT (clock_timestamp() + interval '90 days'),
    CONSTRAINT pk_ma_receipt PRIMARY KEY (receipt_seq),
    CONSTRAINT uq_ma_receipt_id UNIQUE (receipt_id),
    CONSTRAINT fk_ma_receipt_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_receipt_actor FOREIGN KEY (actor_principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_receipt_seq_pos CHECK (receipt_seq >= 1),
    CONSTRAINT chk_ma_receipt_hmac_len CHECK (
        request_jcs_hmac IS NULL OR octet_length(request_jcs_hmac) = 32
    ),
    CONSTRAINT chk_ma_receipt_hash_len CHECK (octet_length(receipt_hash) = 32),
    CONSTRAINT chk_ma_receipt_prev_hash_len CHECK (octet_length(prev_receipt_hash) = 32)
);

CREATE INDEX idx_ma_receipt_unmirrored ON ma_receipt (receipt_seq) WHERE mirrored_to_business = false;
CREATE INDEX idx_ma_receipt_idempotency ON ma_receipt (actor_principal_id, operation, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 14. Disaster Recovery & Quarantine Ceremonies
CREATE TABLE ma_recovery (
    incident_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    prior_incarnation uuid NOT NULL,
    new_incarnation uuid NOT NULL,
    recovery_state varchar(24) NOT NULL DEFAULT 'INITIATED',
    initiated_by uuid,
    reason varchar(512) NOT NULL,
    authority_lsn varchar(64),
    business_lsn varchar(64),
    reconciled_receipt_seq bigint NOT NULL DEFAULT 0,
    evidence_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    initiated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    CONSTRAINT pk_ma_recovery PRIMARY KEY (incident_id),
    CONSTRAINT fk_ma_recovery_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_recovery_initiated_by FOREIGN KEY (initiated_by)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_recovery_state CHECK (
        recovery_state IN ('INITIATED', 'VALIDATING', 'COMPLETED', 'ABORTED')
    ),
    CONSTRAINT chk_ma_recovery_receipt_seq CHECK (reconciled_receipt_seq >= 0),
    CONSTRAINT chk_ma_recovery_version_no CHECK (version_no >= 1)
);

CREATE TABLE ma_service_recovery (
    id uuid NOT NULL,
    incident_id uuid NOT NULL,
    service_principal_id uuid NOT NULL,
    owner_principal_id uuid NOT NULL,
    authorised_by uuid NOT NULL,
    new_service_key_id uuid,
    evidence_ref varchar(160) NOT NULL,
    recorded_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    version_no bigint NOT NULL DEFAULT 1,
    CONSTRAINT pk_ma_service_recovery PRIMARY KEY (id),
    CONSTRAINT uq_ma_service_recovery_incident_service UNIQUE (incident_id, service_principal_id),
    CONSTRAINT fk_ma_service_recovery_incident FOREIGN KEY (incident_id)
        REFERENCES ma_recovery (incident_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_service_recovery_service FOREIGN KEY (service_principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_service_recovery_owner FOREIGN KEY (owner_principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_service_recovery_authorised FOREIGN KEY (authorised_by)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_service_recovery_new_key FOREIGN KEY (new_service_key_id)
        REFERENCES ma_service_key (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_service_recovery_version_no CHECK (version_no >= 1)
);

-- 15. Spring Session JDBC Canonical PostgreSQL Schema (Spring Session 4 / Boot 4.1)
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

-- 16. Ephemeral WebAuthn & OIDC PKCE Ceremony Challenges
CREATE TABLE ma_auth_challenge (
    challenge_id uuid NOT NULL,
    principal_id uuid,
    session_primary_id char(36),
    purpose varchar(32) NOT NULL,
    challenge_bytes bytea NOT NULL,
    pkce_verifier_gcm bytea,
    state_nonce varchar(128),
    metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    consumed boolean NOT NULL DEFAULT false,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz(6) NOT NULL,
    consumed_at timestamptz(6),
    CONSTRAINT pk_ma_auth_challenge PRIMARY KEY (challenge_id),
    CONSTRAINT fk_ma_auth_challenge_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_auth_challenge_session FOREIGN KEY (session_primary_id)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE,
    CONSTRAINT chk_ma_auth_challenge_purpose CHECK (
        purpose IN (
            'WEBAUTHN_REGISTER',
            'WEBAUTHN_AUTHENTICATE',
            'WEBAUTHN_STEP_UP',
            'OIDC_AUTHORIZE'
        )
    ),
    CONSTRAINT chk_ma_auth_challenge_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_ma_auth_challenge_expiry ON ma_auth_challenge (expires_at) WHERE consumed = false;

DO $$
DECLARE
    v_role text;
BEGIN
    FOREACH v_role IN ARRAY ARRAY['mc_app_runtime', 'ma_app_runtime'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
            EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', v_role);
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', v_role);
            EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', v_role);
        END IF;
    END LOOP;
END;
$$;

