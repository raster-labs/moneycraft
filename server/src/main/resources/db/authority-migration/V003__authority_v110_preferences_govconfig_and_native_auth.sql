SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.1.0 — Protected Authority UI Preferences, Super Admin Protected
-- Government & Connected Banking Vault, and Host Native OAuth2/PKCE Issuer
-- Stream: db/authority-migration/V003__authority_v110_preferences_govconfig_and_native_auth.sql
-- Expand-only DDL preserving V001 and V002 contracts.
-- ============================================================================

-- 1. Authenticated Person UI Presentation & Keyboard Preferences (Doc 50 / Doc 52 §2)
CREATE TABLE ma_person_ui_preference (
    principal_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    subject_scope varchar(128) NOT NULL,
    preferences_json jsonb NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_person_ui_preference PRIMARY KEY (principal_id),
    CONSTRAINT uq_ma_person_ui_preference_scope UNIQUE (subject_scope),
    CONSTRAINT fk_ma_person_ui_pref_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_person_ui_pref_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_person_ui_pref_scope CHECK (
        subject_scope ~ '^[A-Za-z0-9_-]{16,128}$'
    ),
    CONSTRAINT chk_ma_person_ui_pref_revision CHECK (revision >= 0)
);

-- 2. Explicit Installation-Level Super Admin Person Designation (Doc 51 §4 / Doc 52 §3)
CREATE TABLE ma_super_admin_designation (
    principal_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    ceremony_source varchar(64) NOT NULL,
    ceremony_reason varchar(512) NOT NULL,
    designated_by uuid,
    designated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    CONSTRAINT pk_ma_super_admin_designation PRIMARY KEY (principal_id),
    CONSTRAINT fk_ma_super_admin_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_super_admin_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_super_admin_designated_by FOREIGN KEY (designated_by)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_super_admin_state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_ma_super_admin_revoked_ts CHECK (
        (state = 'ACTIVE' AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL)
    ),
    CONSTRAINT chk_ma_super_admin_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_ma_super_admin_active
    ON ma_super_admin_designation (installation_id, state)
    WHERE state = 'ACTIVE';

-- 3. Single-Use 5-Minute Target/Action/Revision-Bound Step-Up Intents (Doc 52 §3)
CREATE TABLE ma_step_up_intent (
    intent_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    incarnation uuid NOT NULL,
    principal_id uuid NOT NULL,
    target_profile_id uuid,
    action varchar(32) NOT NULL,
    expected_revision bigint,
    state varchar(16) NOT NULL DEFAULT 'ISSUED',
    issued_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz(6) NOT NULL,
    consumed_at timestamptz(6),
    consumed_by_receipt_seq bigint,
    CONSTRAINT pk_ma_step_up_intent PRIMARY KEY (intent_id),
    CONSTRAINT fk_ma_step_up_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_step_up_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_step_up_action CHECK (
        action IN (
            'CREATE',
            'UPDATE',
            'STAGE_CREDENTIAL',
            'ROTATE',
            'VALIDATE',
            'ACTIVATE',
            'DISABLE'
        )
    ),
    CONSTRAINT chk_ma_step_up_state CHECK (
        state IN ('ISSUED', 'CONSUMED', 'EXPIRED', 'INVALIDATED')
    ),
    CONSTRAINT chk_ma_step_up_binding CHECK (
        (action = 'CREATE' AND target_profile_id IS NULL AND expected_revision IS NULL)
        OR (
            action <> 'CREATE'
            AND target_profile_id IS NOT NULL
            AND expected_revision IS NOT NULL
            AND expected_revision >= 1
        )
    ),
    CONSTRAINT chk_ma_step_up_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX idx_ma_step_up_principal_active
    ON ma_step_up_intent (principal_id, expires_at)
    WHERE state = 'ISSUED';

-- 4. Government Statutory & Connected Banking Integration Profiles (Doc 51 §4 / UM-110-04)
CREATE TABLE ma_gov_integration_profile (
    profile_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    profile_code varchar(64) NOT NULL,
    display_name varchar(160) NOT NULL,
    provider_family varchar(32) NOT NULL,
    adapter_code varchar(64) NOT NULL,
    endpoint_profile_id varchar(96) NOT NULL,
    environment varchar(16) NOT NULL,
    company_id uuid NOT NULL,
    registration_type varchar(24) NOT NULL,
    registration_ref varchar(64) NOT NULL,
    allowed_operations jsonb NOT NULL DEFAULT '[]'::jsonb,
    consent_evidence_ref varchar(160) NOT NULL,
    certificate_fingerprint_sha256 varchar(64),
    certificate_expires_at timestamptz(6),
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    active_credential_revision bigint,
    staged_credential_revision bigint,
    last_validation_id uuid,
    last_validation_status varchar(24) NOT NULL DEFAULT 'NOT_VALIDATED',
    last_validated_revision bigint,
    last_validated_credential_revision bigint,
    last_validated_at timestamptz(6),
    disabled_reason varchar(256),
    reconciliation_note varchar(512),
    revision bigint NOT NULL DEFAULT 1,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_gov_integration_profile PRIMARY KEY (profile_id),
    CONSTRAINT uq_ma_gov_profile_code UNIQUE (installation_id, profile_code),
    CONSTRAINT fk_ma_gov_profile_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_gov_profile_created_by FOREIGN KEY (created_by)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_gov_profile_updated_by FOREIGN KEY (updated_by)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_gov_profile_code_grammar CHECK (
        profile_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$'
    ),
    CONSTRAINT chk_ma_gov_profile_family CHECK (
        provider_family IN ('GOVERNMENT_STATUTORY', 'CONNECTED_BANKING')
    ),
    CONSTRAINT chk_ma_gov_profile_env CHECK (
        environment IN ('SANDBOX', 'PRODUCTION')
    ),
    CONSTRAINT chk_ma_gov_profile_reg_type CHECK (
        registration_type IN ('GSTIN', 'PAN', 'TAN', 'CIN', 'BANK_CORP_ID')
    ),
    CONSTRAINT chk_ma_gov_profile_cert_fp CHECK (
        certificate_fingerprint_sha256 IS NULL
        OR certificate_fingerprint_sha256 ~ '^[0-9a-fA-F]{64}$'
    ),
    CONSTRAINT chk_ma_gov_profile_status CHECK (
        status IN (
            'DRAFT',
            'CONFIGURED',
            'VALIDATION_PENDING',
            'VALIDATED',
            'ACTIVE',
            'DISABLED',
            'EXPIRED_REVOKED',
            'FAILED'
        )
    ),
    CONSTRAINT chk_ma_gov_profile_val_status CHECK (
        last_validation_status IN (
            'NOT_VALIDATED',
            'PENDING',
            'SUCCEEDED',
            'FAILED',
            'NOT_AVAILABLE',
            'INVALIDATED',
            'UNCERTAIN'
        )
    ),
    CONSTRAINT chk_ma_gov_profile_revision CHECK (revision >= 1)
);

CREATE INDEX idx_ma_gov_profile_company_env
    ON ma_gov_integration_profile (company_id, adapter_code, environment, status);

-- 5. Write-Only Tink Envelope-Encrypted Credential Versions (Zero Secret Readback)
CREATE TABLE ma_gov_credential_version (
    credential_version_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    credential_revision bigint NOT NULL,
    environment_namespace varchar(16) NOT NULL,
    key_id uuid,
    secret_envelope_gcm bytea,
    slot_metadata_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    state varchar(20) NOT NULL DEFAULT 'STAGED',
    staged_by uuid NOT NULL,
    staged_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    activated_at timestamptz(6),
    retired_at timestamptz(6),
    CONSTRAINT pk_ma_gov_credential_version PRIMARY KEY (credential_version_id),
    CONSTRAINT uq_ma_gov_cred_profile_rev UNIQUE (profile_id, credential_revision),
    CONSTRAINT fk_ma_gov_cred_profile FOREIGN KEY (profile_id)
        REFERENCES ma_gov_integration_profile (profile_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_gov_cred_key FOREIGN KEY (key_id)
        REFERENCES ma_key (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_gov_cred_staged_by FOREIGN KEY (staged_by)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_gov_cred_rev CHECK (credential_revision >= 1),
    CONSTRAINT chk_ma_gov_cred_env CHECK (environment_namespace IN ('SANDBOX', 'PRODUCTION')),
    CONSTRAINT chk_ma_gov_cred_cipher_len CHECK (
        secret_envelope_gcm IS NULL OR octet_length(secret_envelope_gcm) BETWEEN 1 AND 65536
    ),
    CONSTRAINT chk_ma_gov_cred_state CHECK (
        state IN ('STAGED', 'ACTIVE', 'RETIRED', 'REVOKED', 'COMPROMISED')
    )
);

-- 6. Durable Safe Connection Validation Jobs (Executed Outside DB Locks)
CREATE TABLE ma_gov_validation_job (
    validation_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    profile_revision bigint NOT NULL,
    credential_revision bigint NOT NULL,
    probe_operation varchar(64) NOT NULL,
    probe_mode varchar(48) NOT NULL,
    side_effect_disclosure varchar(256) NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'PENDING',
    sanitised_summary varchar(512) NOT NULL,
    sanitised_error_code varchar(64),
    http_status integer,
    initiated_by uuid NOT NULL,
    started_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz(6),
    CONSTRAINT pk_ma_gov_validation_job PRIMARY KEY (validation_id),
    CONSTRAINT fk_ma_gov_val_profile FOREIGN KEY (profile_id)
        REFERENCES ma_gov_integration_profile (profile_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_gov_val_initiated_by FOREIGN KEY (initiated_by)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_gov_val_revisions CHECK (profile_revision >= 1 AND credential_revision >= 1),
    CONSTRAINT chk_ma_gov_val_state CHECK (
        state IN ('PENDING', 'SUCCEEDED', 'FAILED', 'NOT_VALIDATED', 'UNCERTAIN')
    )
);

CREATE INDEX idx_ma_gov_val_profile_lookup
    ON ma_gov_validation_job (profile_id, started_at DESC);

-- 7. Append-Only Secret-Free Government & Banking Administration Audit Trail
CREATE TABLE ma_gov_audit_event (
    audit_seq bigint GENERATED BY DEFAULT AS IDENTITY,
    event_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    profile_id uuid,
    actor_principal_id uuid,
    actor_kind varchar(16) NOT NULL,
    company_id uuid,
    registration_ref varchar(64),
    adapter_code varchar(64),
    environment varchar(16),
    profile_revision bigint,
    action varchar(64) NOT NULL,
    outcome varchar(24) NOT NULL,
    reason_code varchar(96),
    sanitised_detail varchar(512) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    recorded_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_gov_audit_event PRIMARY KEY (audit_seq),
    CONSTRAINT uq_ma_gov_audit_event_id UNIQUE (event_id),
    CONSTRAINT fk_ma_gov_audit_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_gov_audit_profile FOREIGN KEY (profile_id)
        REFERENCES ma_gov_integration_profile (profile_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_gov_audit_actor FOREIGN KEY (actor_principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_gov_audit_outcome CHECK (
        outcome IN ('ALLOWED', 'DENIED', 'SUCCEEDED', 'FAILED', 'UNCERTAIN')
    )
);

CREATE INDEX idx_ma_gov_audit_profile_seq
    ON ma_gov_audit_event (profile_id, audit_seq DESC);

CREATE OR REPLACE FUNCTION fn_ma_gov_audit_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '42501',
        MESSAGE = 'MC-IMMUTABLE-RECORD: ma_gov_audit_event rows are append-only and cannot be modified or deleted';
END;
$$;

CREATE TRIGGER trg_ma_gov_audit_immutable
BEFORE UPDATE OR DELETE ON ma_gov_audit_event
FOR EACH ROW
EXECUTE FUNCTION fn_ma_gov_audit_immutable();

-- 8. First-Party Host Native Public-Client OAuth2 + PKCE S256 Registry (Mobile Doc 02 / Swing Doc 02 §5)
CREATE TABLE ma_native_oauth_client (
    client_id varchar(96) NOT NULL,
    display_name varchar(160) NOT NULL,
    client_kind varchar(24) NOT NULL,
    allow_loopback_redirect boolean NOT NULL DEFAULT false,
    loopback_callback_path varchar(128) NOT NULL DEFAULT '/oauth2/callback',
    admitted_https_redirects jsonb NOT NULL DEFAULT '[]'::jsonb,
    allowed_scopes jsonb NOT NULL DEFAULT '["openid","profile"]'::jsonb,
    access_token_ttl_sec integer NOT NULL DEFAULT 300,
    refresh_token_ttl_sec integer NOT NULL DEFAULT 86400,
    state varchar(16) NOT NULL DEFAULT 'ENABLED',
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ma_native_oauth_client PRIMARY KEY (client_id),
    CONSTRAINT chk_ma_native_client_id_grammar CHECK (
        client_id ~ '^[a-z0-9][a-z0-9._-]{2,95}$'
        AND client_id NOT LIKE 'mc_sk_%'
    ),
    CONSTRAINT chk_ma_native_client_kind CHECK (
        client_kind IN ('DESKTOP_SWING', 'MOBILE_NATIVE')
    ),
    CONSTRAINT chk_ma_native_client_access_ttl CHECK (
        access_token_ttl_sec BETWEEN 60 AND 3600
    ),
    CONSTRAINT chk_ma_native_client_refresh_ttl CHECK (
        refresh_token_ttl_sec BETWEEN 300 AND 2592000
    ),
    CONSTRAINT chk_ma_native_client_state CHECK (
        state IN ('ENABLED', 'DISABLED')
    )
);

INSERT INTO ma_native_oauth_client (
    client_id,
    display_name,
    client_kind,
    allow_loopback_redirect,
    loopback_callback_path,
    admitted_https_redirects,
    allowed_scopes,
    access_token_ttl_sec,
    refresh_token_ttl_sec,
    state
) VALUES
    (
        'moneycraft-swing-desktop',
        'MoneyCraft Java 25 Desktop Client',
        'DESKTOP_SWING',
        true,
        '/oauth2/callback',
        '[]'::jsonb,
        '["openid","profile","offline_access"]'::jsonb,
        300,
        86400,
        'ENABLED'
    ),
    (
        'moneycraft-mobile-client',
        'MoneyCraft Native Mobile Companion',
        'MOBILE_NATIVE',
        false,
        '/oauth2/callback',
        '["https://mobile.moneycraft.local/oauth2/callback"]'::jsonb,
        '["openid","profile","offline_access"]'::jsonb,
        300,
        86400,
        'ENABLED'
    )
ON CONFLICT (client_id) DO NOTHING;

-- 9. Ephemeral 60-Second One-Time Native Authorization Codes (PKCE S256 Mandatory)
CREATE TABLE ma_native_auth_code (
    code_id uuid NOT NULL,
    code_sha256 bytea NOT NULL,
    installation_id uuid NOT NULL,
    incarnation uuid NOT NULL,
    client_id varchar(96) NOT NULL,
    principal_id uuid NOT NULL,
    issuer_uri varchar(512) NOT NULL,
    redirect_uri varchar(2048) NOT NULL,
    scopes jsonb NOT NULL DEFAULT '["openid","profile"]'::jsonb,
    code_challenge varchar(128) NOT NULL,
    code_challenge_method varchar(16) NOT NULL DEFAULT 'S256',
    state_param varchar(256) NOT NULL,
    nonce_param varchar(256),
    oidc_issuer_id uuid,
    oidc_link_id uuid,
    oidc_issuer_version bigint,
    oidc_link_version bigint,
    consumed boolean NOT NULL DEFAULT false,
    issued_token_family_id uuid,
    issued_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz(6) NOT NULL,
    consumed_at timestamptz(6),
    CONSTRAINT pk_ma_native_auth_code PRIMARY KEY (code_id),
    CONSTRAINT uq_ma_native_auth_code_hash UNIQUE (code_sha256),
    CONSTRAINT fk_ma_native_code_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_native_code_client FOREIGN KEY (client_id)
        REFERENCES ma_native_oauth_client (client_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_native_code_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_native_code_hash_len CHECK (octet_length(code_sha256) = 32),
    CONSTRAINT chk_ma_native_code_pkce_method CHECK (code_challenge_method = 'S256'),
    CONSTRAINT chk_ma_native_code_challenge_grammar CHECK (
        code_challenge ~ '^[A-Za-z0-9_-]{43,128}$'
    ),
    CONSTRAINT chk_ma_native_code_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX idx_ma_native_auth_code_active
    ON ma_native_auth_code (expires_at)
    WHERE consumed = false;

-- 10. Short-Lived Native Access Tokens & Rotated Refresh Token Families (RFC 9700 Replay Detection)
CREATE TABLE ma_native_token (
    token_id uuid NOT NULL,
    token_family_id uuid NOT NULL,
    generation integer NOT NULL DEFAULT 1,
    installation_id uuid NOT NULL,
    incarnation uuid NOT NULL,
    client_id varchar(96) NOT NULL,
    principal_id uuid NOT NULL,
    issuer_uri varchar(512) NOT NULL,
    scopes jsonb NOT NULL DEFAULT '["openid","profile"]'::jsonb,
    nonce_param varchar(256),
    access_token_sha256 bytea NOT NULL,
    refresh_token_sha256 bytea,
    access_expires_at timestamptz(6) NOT NULL,
    refresh_expires_at timestamptz(6),
    oidc_issuer_id uuid,
    oidc_link_id uuid,
    oidc_issuer_version bigint,
    oidc_link_version bigint,
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    issued_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    rotated_at timestamptz(6),
    revoked_at timestamptz(6),
    revoke_reason varchar(128),
    CONSTRAINT pk_ma_native_token PRIMARY KEY (token_id),
    CONSTRAINT uq_ma_native_access_token_hash UNIQUE (access_token_sha256),
    CONSTRAINT uq_ma_native_refresh_token_hash UNIQUE (refresh_token_sha256),
    CONSTRAINT fk_ma_native_token_installation FOREIGN KEY (installation_id)
        REFERENCES ma_state (installation_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_native_token_client FOREIGN KEY (client_id)
        REFERENCES ma_native_oauth_client (client_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ma_native_token_principal FOREIGN KEY (principal_id)
        REFERENCES ma_principal (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ma_native_token_gen CHECK (generation >= 1),
    CONSTRAINT chk_ma_native_access_hash_len CHECK (octet_length(access_token_sha256) = 32),
    CONSTRAINT chk_ma_native_refresh_hash_len CHECK (
        refresh_token_sha256 IS NULL OR octet_length(refresh_token_sha256) = 32
    ),
    CONSTRAINT chk_ma_native_token_state CHECK (
        state IN ('ACTIVE', 'ROTATED', 'REVOKED')
    )
);

CREATE INDEX idx_ma_native_token_family
    ON ma_native_token (token_family_id, state);

CREATE INDEX idx_ma_native_token_principal
    ON ma_native_token (principal_id, state);

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
