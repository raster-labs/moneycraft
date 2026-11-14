SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Business Accounting Cluster Foundation & MC-AUDIT-2 Chain
-- Stream: db/migration/V001__business_foundation_and_audit.sql
-- Trust Boundary: Zero password hashes, passkeys, OIDC secrets, API keys, or
-- SPRING_SESSION rows. Enforces AuthorityGuard fencing, composite tenant FKs,
-- PostgreSQL 18 Row-Level Security (RLS), JCS idempotency & append-only audit.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. Single-Active-Writer Installation Fence & Quarantine Gate
CREATE TABLE mc_installation_fence (
    id smallint NOT NULL DEFAULT 1,
    installation_id uuid,
    incarnation uuid,
    fence_epoch bigint NOT NULL DEFAULT 1,
    authority_epoch bigint NOT NULL DEFAULT 0,
    catalog_epoch bigint NOT NULL DEFAULT 0,
    system_state varchar(24) NOT NULL DEFAULT 'UNINITIALISED',
    quarantined boolean NOT NULL DEFAULT false,
    quarantine_reason varchar(512),
    quarantined_at timestamptz(6),
    writer_instance_id varchar(128),
    writer_acquired_at timestamptz(6),
    last_mirrored_receipt_seq bigint NOT NULL DEFAULT 0,
    version_no bigint NOT NULL DEFAULT 1,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_installation_fence PRIMARY KEY (id),
    CONSTRAINT uq_mc_installation_fence_inst_id UNIQUE (installation_id),
    CONSTRAINT chk_mc_installation_fence_singleton CHECK (id = 1),
    CONSTRAINT chk_mc_installation_fence_epoch CHECK (fence_epoch >= 1),
    CONSTRAINT chk_mc_installation_fence_auth_epoch CHECK (authority_epoch >= 0),
    CONSTRAINT chk_mc_installation_fence_cat_epoch CHECK (catalog_epoch >= 0),
    CONSTRAINT chk_mc_installation_fence_state CHECK (
        system_state IN ('UNINITIALISED', 'ACTIVE', 'MAINTENANCE', 'QUARANTINED', 'RECOVERING')
    ),
    CONSTRAINT chk_mc_installation_fence_receipt_seq CHECK (last_mirrored_receipt_seq >= 0),
    CONSTRAINT chk_mc_installation_fence_version_no CHECK (version_no >= 1)
);

INSERT INTO mc_installation_fence (id, fence_epoch, system_state, quarantined)
VALUES (1, 1, 'UNINITIALISED', false)
ON CONFLICT (id) DO NOTHING;

-- 2. Authority Admin Receipt Mirror (Detects Split-Brain Restore Divergence)
CREATE TABLE mc_admin_receipt_mirror (
    receipt_seq bigint NOT NULL,
    receipt_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    actor_principal_id uuid,
    company_scope uuid,
    operation varchar(96) NOT NULL,
    target_type varchar(48) NOT NULL,
    target_id varchar(256) NOT NULL,
    receipt_hash bytea NOT NULL,
    prev_receipt_hash bytea NOT NULL,
    authority_committed_at timestamptz(6) NOT NULL,
    mirrored_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_admin_receipt_mirror PRIMARY KEY (receipt_seq),
    CONSTRAINT uq_mc_admin_receipt_mirror_id UNIQUE (receipt_id),
    CONSTRAINT chk_mc_admin_receipt_mirror_seq CHECK (receipt_seq >= 1),
    CONSTRAINT chk_mc_admin_receipt_mirror_hash CHECK (octet_length(receipt_hash) = 32),
    CONSTRAINT chk_mc_admin_receipt_mirror_prev_hash CHECK (octet_length(prev_receipt_hash) = 32)
);

-- 3. Principal Attribution Projection (Credential-Free, Tombstone-Retained)
CREATE TABLE mc_principal_projection (
    principal_id uuid NOT NULL,
    kind varchar(16) NOT NULL DEFAULT 'PERSON',
    username citext,
    display_name varchar(160) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    system_roles jsonb NOT NULL DEFAULT '[]'::jsonb,
    authority_epoch bigint NOT NULL DEFAULT 1,
    authority_digest bytea,
    version_no bigint NOT NULL DEFAULT 1,
    synced_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_principal_projection PRIMARY KEY (principal_id),
    CONSTRAINT chk_mc_principal_projection_kind CHECK (kind IN ('PERSON', 'SERVICE')),
    CONSTRAINT chk_mc_principal_projection_name CHECK (length(trim(display_name)) > 0),
    CONSTRAINT chk_mc_principal_projection_epoch CHECK (authority_epoch >= 1),
    CONSTRAINT chk_mc_principal_projection_digest CHECK (
        authority_digest IS NULL OR octet_length(authority_digest) = 32
    ),
    CONSTRAINT chk_mc_principal_projection_version_no CHECK (version_no >= 1)
);

CREATE OR REPLACE FUNCTION fn_mc_forbid_principal_projection_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = 'MC-FND-TOMBSTONE-REQUIRED: mc_principal_projection rows cannot be deleted; set enabled = false instead';
END;
$$;

CREATE TRIGGER trg_mc_principal_projection_no_delete
BEFORE DELETE ON mc_principal_projection
FOR EACH ROW
EXECUTE FUNCTION fn_mc_forbid_principal_projection_delete();

-- 4. Canonical Company Master (Unlimited Multi-Company ERP Root)
CREATE TABLE mc_company (
    id uuid NOT NULL,
    code varchar(24) NOT NULL,
    legal_name varchar(200) NOT NULL,
    mailing_name varchar(200) NOT NULL,
    address_lines text NOT NULL DEFAULT '',
    country_code char(2) NOT NULL DEFAULT 'IN',
    state_code varchar(4) NOT NULL DEFAULT '33',
    state_name varchar(100) NOT NULL DEFAULT '',
    pincode varchar(16),
    telephone varchar(40),
    email citext,
    website varchar(255),
    pan_siv bytea,
    pan_plain_masked varchar(16),
    cin varchar(25),
    base_currency_code char(3) NOT NULL DEFAULT 'INR',
    base_currency_symbol varchar(8) NOT NULL DEFAULT '₹',
    formal_currency_name varchar(64) NOT NULL DEFAULT 'Indian Rupee',
    currency_scale smallint NOT NULL DEFAULT 2,
    number_format_style varchar(16) NOT NULL DEFAULT 'INDIAN',
    books_beginning_from date NOT NULL,
    financial_year_from date NOT NULL,
    default_approval_policy varchar(16) NOT NULL DEFAULT 'SINGLE_STEP',
    alteration_mode varchar(24) NOT NULL DEFAULT 'TALLY_EDIT_LOG',
    forex_settlement_mode varchar(24) NOT NULL DEFAULT 'UNADJUSTED_FOREX',
    mca_edit_log_enforced boolean NOT NULL DEFAULT false,
    inventory_integrated boolean NOT NULL DEFAULT true,
    maintain_inventory boolean NOT NULL DEFAULT true,
    bill_wise_enabled boolean NOT NULL DEFAULT true,
    cost_centres_enabled boolean NOT NULL DEFAULT false,
    gst_enabled boolean NOT NULL DEFAULT true,
    tds_enabled boolean NOT NULL DEFAULT false,
    tcs_enabled boolean NOT NULL DEFAULT false,
    payroll_enabled boolean NOT NULL DEFAULT false,
    multi_gstin_enabled boolean NOT NULL DEFAULT false,
    visibility varchar(16) NOT NULL DEFAULT 'ACTIVE',
    next_post_seq bigint NOT NULL DEFAULT 1,
    next_master_seq_id bigint NOT NULL DEFAULT 1,
    next_alter_id bigint NOT NULL DEFAULT 1,
    ledger_catalog_epoch bigint NOT NULL DEFAULT 0,
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_company PRIMARY KEY (id),
    CONSTRAINT uq_mc_company_code UNIQUE (code),
    CONSTRAINT fk_mc_company_created_by FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_company_code_grammar CHECK (code ~ '^[A-Za-z0-9_-]{1,24}$'),
    CONSTRAINT chk_mc_company_legal_name CHECK (length(trim(legal_name)) > 0),
    CONSTRAINT chk_mc_company_mailing_name CHECK (length(trim(mailing_name)) > 0),
    CONSTRAINT chk_mc_company_country CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_mc_company_currency CHECK (base_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_mc_company_currency_scale CHECK (currency_scale BETWEEN 0 AND 6),
    CONSTRAINT chk_mc_company_number_format CHECK (
        number_format_style IN ('INDIAN', 'INTERNATIONAL')
    ),
    CONSTRAINT chk_mc_company_books_date CHECK (books_beginning_from >= financial_year_from),
    CONSTRAINT chk_mc_company_approval_policy CHECK (
        default_approval_policy IN ('SINGLE_STEP', 'FOUR_EYES')
    ),
    CONSTRAINT chk_mc_company_alteration_mode CHECK (
        alteration_mode IN ('TALLY_EDIT_LOG', 'IMMUTABLE_LEDGER')
    ),
    CONSTRAINT chk_mc_company_forex_mode CHECK (
        forex_settlement_mode IN ('UNADJUSTED_FOREX', 'IMMEDIATE_REALIZED')
    ),
    CONSTRAINT chk_mc_company_visibility CHECK (
        visibility IN ('STAGING', 'ACTIVE', 'ARCHIVED')
    ),
    CONSTRAINT chk_mc_company_next_post_seq CHECK (next_post_seq >= 1),
    CONSTRAINT chk_mc_company_next_master_seq CHECK (next_master_seq_id >= 1),
    CONSTRAINT chk_mc_company_next_alter_id CHECK (next_alter_id >= 1),
    CONSTRAINT chk_mc_company_catalog_epoch CHECK (ledger_catalog_epoch >= 0),
    CONSTRAINT chk_mc_company_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_company_visibility_code ON mc_company (visibility, code);

CREATE OR REPLACE FUNCTION fn_mc_company_immutable_guards()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.mca_edit_log_enforced = true AND NEW.mca_edit_log_enforced = false THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'MC-EDITLOG-MANDATORY: mca_edit_log_enforced cannot be disabled once enabled';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_company_mca_edit_log_guard
BEFORE UPDATE ON mc_company
FOR EACH ROW
EXECUTE FUNCTION fn_mc_company_immutable_guards();

-- 5. Company RBAC Grant Projection
CREATE TABLE mc_principal_grant (
    grant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    company_id uuid NOT NULL,
    role_code varchar(32) NOT NULL,
    permissions jsonb NOT NULL DEFAULT '[]'::jsonb,
    voucher_type_allowlist jsonb NOT NULL DEFAULT '[]'::jsonb,
    backdated_days_limit integer,
    export_permitted boolean NOT NULL DEFAULT false,
    private_fields_permitted boolean NOT NULL DEFAULT true,
    active boolean NOT NULL DEFAULT true,
    grant_epoch bigint NOT NULL DEFAULT 1,
    authority_epoch bigint NOT NULL DEFAULT 1,
    authority_digest bytea,
    version_no bigint NOT NULL DEFAULT 1,
    synced_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_principal_grant PRIMARY KEY (grant_id),
    CONSTRAINT uq_mc_principal_grant_tuple UNIQUE (principal_id, company_id, role_code),
    CONSTRAINT fk_mc_principal_grant_principal FOREIGN KEY (principal_id)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_principal_grant_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_principal_grant_role CHECK (
        role_code IN (
            'COMPANY_ADMIN',
            'AUDITOR_APPROVER',
            'SENIOR_ACCOUNTANT',
            'DATA_ENTRY_OPERATOR',
            'INVENTORY_CLERK',
            'READ_ONLY_VIEWER'
        )
    ),
    CONSTRAINT chk_mc_principal_grant_backdated CHECK (
        backdated_days_limit IS NULL OR backdated_days_limit >= 0
    ),
    CONSTRAINT chk_mc_principal_grant_epoch CHECK (grant_epoch >= 1 AND authority_epoch >= 1),
    CONSTRAINT chk_mc_principal_grant_digest CHECK (
        authority_digest IS NULL OR octet_length(authority_digest) = 32
    ),
    CONSTRAINT chk_mc_principal_grant_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_principal_grant_lookup ON mc_principal_grant (principal_id, company_id, active);

-- 6. Fiscal Years (Tenant-Isolated via Composite PK & PostgreSQL 18 RLS)
CREATE TABLE mc_fiscal_year (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    code varchar(24) NOT NULL,
    display_label varchar(64) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    is_closed boolean NOT NULL DEFAULT false,
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_fiscal_year PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_fiscal_year_id UNIQUE (id),
    CONSTRAINT uq_mc_fiscal_year_company_code UNIQUE (company_id, code),
    CONSTRAINT fk_mc_fiscal_year_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_fiscal_year_created_by FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_fiscal_year_dates CHECK (end_date >= start_date),
    CONSTRAINT chk_mc_fiscal_year_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_fiscal_year_date_span ON mc_fiscal_year (company_id, start_date, end_date);

CREATE OR REPLACE FUNCTION fn_mc_fiscal_year_no_overlap()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM mc_fiscal_year fy
         WHERE fy.company_id = NEW.company_id
           AND fy.id <> NEW.id
           AND daterange(fy.start_date, fy.end_date, '[]') && daterange(NEW.start_date, NEW.end_date, '[]')
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23P01',
            MESSAGE = 'MC-ACC-FY-OVERLAP: overlapping fiscal year date range within company';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_fiscal_year_no_overlap
BEFORE INSERT OR UPDATE ON mc_fiscal_year
FOR EACH ROW
EXECUTE FUNCTION fn_mc_fiscal_year_no_overlap();

ALTER TABLE mc_fiscal_year ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_fiscal_year FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_fiscal_year_tenant_isolation ON mc_fiscal_year
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 7. Accounting Periods (OPEN / SOFT_LOCKED / CLOSED)
CREATE TABLE mc_period (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    fiscal_year_id uuid NOT NULL,
    period_no smallint NOT NULL DEFAULT 1,
    code varchar(24) NOT NULL,
    name varchar(64) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'OPEN',
    locked_reason varchar(255),
    state_changed_by uuid,
    state_changed_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_period PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_period_id UNIQUE (id),
    CONSTRAINT uq_mc_period_company_code UNIQUE (company_id, code),
    CONSTRAINT fk_mc_period_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_period_fiscal_year FOREIGN KEY (company_id, fiscal_year_id)
        REFERENCES mc_fiscal_year (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_period_changed_by FOREIGN KEY (state_changed_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_period_no CHECK (period_no BETWEEN 1 AND 366),
    CONSTRAINT chk_mc_period_dates CHECK (end_date >= start_date),
    CONSTRAINT chk_mc_period_state CHECK (state IN ('OPEN', 'SOFT_LOCKED', 'CLOSED')),
    CONSTRAINT chk_mc_period_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_period_date_lookup ON mc_period (company_id, start_date, end_date, state);

CREATE OR REPLACE FUNCTION fn_mc_period_bounds_no_overlap()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_fy_start date;
    v_fy_end date;
BEGIN
    SELECT fy.start_date, fy.end_date
      INTO v_fy_start, v_fy_end
      FROM mc_fiscal_year fy
     WHERE fy.company_id = NEW.company_id
       AND fy.id = NEW.fiscal_year_id;

    IF v_fy_start IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            MESSAGE = 'MC-ACC-FY-NOT-FOUND: parent fiscal year not found for period';
    END IF;

    IF NEW.start_date < v_fy_start OR NEW.end_date > v_fy_end THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'MC-ACC-PERIOD-OUT-OF-FY: mc_period dates must lie inside parent mc_fiscal_year';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM mc_period p
         WHERE p.company_id = NEW.company_id
           AND p.id <> NEW.id
           AND daterange(p.start_date, p.end_date, '[]') && daterange(NEW.start_date, NEW.end_date, '[]')
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23P01',
            MESSAGE = 'MC-ACC-PERIOD-OVERLAP: overlapping accounting period date range within company';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_period_bounds_no_overlap
BEFORE INSERT OR UPDATE ON mc_period
FOR EACH ROW
EXECUTE FUNCTION fn_mc_period_bounds_no_overlap();

ALTER TABLE mc_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_period FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_period_tenant_isolation ON mc_period
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 8. 90-Day RFC 8785 JCS + HMAC-SHA256 Idempotency Receipts (MCJ1-RETRY-01)
CREATE TABLE mc_retry (
    company_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    endpoint_id varchar(160) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    canon_version smallint NOT NULL DEFAULT 1,
    hmac_key_id uuid,
    request_jcs_hmac bytea NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'COMPLETE',
    http_status smallint NOT NULL,
    response_etag varchar(64),
    response_location varchar(512),
    response_body jsonb,
    resource_type varchar(48),
    resource_id uuid,
    correlation_id uuid,
    committed_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz(6) NOT NULL DEFAULT (clock_timestamp() + interval '90 days'),
    CONSTRAINT pk_mc_retry PRIMARY KEY (company_id, principal_id, endpoint_id, idempotency_key),
    CONSTRAINT fk_mc_retry_principal FOREIGN KEY (principal_id)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_retry_canon_v CHECK (canon_version >= 1),
    CONSTRAINT chk_mc_retry_hmac_len CHECK (octet_length(request_jcs_hmac) = 32),
    CONSTRAINT chk_mc_retry_state CHECK (state IN ('COMPLETE', 'TOMBSTONE')),
    CONSTRAINT chk_mc_retry_http_status CHECK (http_status BETWEEN 100 AND 599),
    CONSTRAINT chk_mc_retry_endpoint_nonempty CHECK (length(trim(endpoint_id)) > 0),
    CONSTRAINT chk_mc_retry_key_nonempty CHECK (length(trim(idempotency_key)) > 0)
);

CREATE INDEX idx_mc_retry_expiry ON mc_retry (expires_at) WHERE state = 'COMPLETE';

ALTER TABLE mc_retry ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_retry FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_retry_tenant_isolation ON mc_retry
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = '00000000-0000-0000-0000-000000000000'::uuid
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = '00000000-0000-0000-0000-000000000000'::uuid
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 9. MC-AUDIT-2 Append-Only Cryptographic SHA-256 Hash Chain
CREATE TABLE mc_audit_tail (
    scope_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    next_seq bigint NOT NULL DEFAULT 1,
    last_event_hash bytea NOT NULL,
    version_no bigint NOT NULL DEFAULT 1,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_audit_tail PRIMARY KEY (scope_id),
    CONSTRAINT chk_mc_audit_tail_scope_type CHECK (scope_type IN ('INSTALLATION', 'COMPANY')),
    CONSTRAINT chk_mc_audit_tail_next_seq CHECK (next_seq >= 1),
    CONSTRAINT chk_mc_audit_tail_hash_len CHECK (octet_length(last_event_hash) = 32),
    CONSTRAINT chk_mc_audit_tail_version_no CHECK (version_no >= 1)
);

CREATE TABLE mc_audit_event (
    id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_id uuid NOT NULL,
    company_id uuid,
    seq_no bigint NOT NULL,
    actor_principal_id uuid,
    actor_kind varchar(16) NOT NULL,
    operation varchar(96) NOT NULL,
    entity_type varchar(48) NOT NULL,
    entity_id varchar(256),
    outcome varchar(16) NOT NULL,
    error_code varchar(64),
    correlation_id uuid,
    redacted_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    prev_hash bytea NOT NULL,
    event_hash bytea NOT NULL,
    occurred_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_audit_event PRIMARY KEY (id),
    CONSTRAINT uq_mc_audit_event_scope_seq UNIQUE (scope_id, seq_no),
    CONSTRAINT fk_mc_audit_event_tail FOREIGN KEY (scope_id)
        REFERENCES mc_audit_tail (scope_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_audit_event_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_audit_event_actor FOREIGN KEY (actor_principal_id)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_audit_event_scope_type CHECK (scope_type IN ('INSTALLATION', 'COMPANY')),
    CONSTRAINT chk_mc_audit_event_seq CHECK (seq_no >= 1),
    CONSTRAINT chk_mc_audit_event_actor_kind CHECK (
        actor_kind IN ('PERSON', 'SERVICE', 'SYSTEM', 'CUSTODIAN')
    ),
    CONSTRAINT chk_mc_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    CONSTRAINT chk_mc_audit_event_prev_hash CHECK (octet_length(prev_hash) = 32),
    CONSTRAINT chk_mc_audit_event_hash CHECK (octet_length(event_hash) = 32)
);

CREATE INDEX idx_mc_audit_event_company_time ON mc_audit_event (company_id, occurred_at DESC);
CREATE INDEX idx_mc_audit_event_correlation ON mc_audit_event (correlation_id)
    WHERE correlation_id IS NOT NULL;

CREATE OR REPLACE FUNCTION fn_mc_forbid_append_only_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = format('MC-IMMUTABLE-RECORD: table %I is strictly append-only; %s is prohibited', TG_TABLE_NAME, TG_OP);
END;
$$;

CREATE TRIGGER trg_mc_audit_event_immutable
BEFORE UPDATE OR DELETE ON mc_audit_event
FOR EACH ROW
EXECUTE FUNCTION fn_mc_forbid_append_only_mutation();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mc_app_runtime') THEN
        GRANT USAGE ON SCHEMA public TO mc_app_runtime;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mc_app_runtime;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mc_app_runtime;
        REVOKE UPDATE, DELETE, TRUNCATE ON mc_audit_event FROM mc_app_runtime;
        GRANT SELECT, INSERT ON mc_audit_event TO mc_app_runtime;
    END IF;
END;
$$;
