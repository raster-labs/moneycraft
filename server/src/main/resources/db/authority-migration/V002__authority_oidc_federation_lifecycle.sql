SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Protected Authority OIDC Federation Lifecycle (MC-FED-01)
-- Stream: db/authority-migration/V002__authority_oidc_federation_lifecycle.sql
-- Expand-only DDL adding IssuerConfig2 envelope storage, 120s freshness stamps,
-- PROVIDER group-mapped grant attribution, and durable ceremony stages without
-- churning semantic version_no or authority_epoch on checked_at bookkeeping.
-- ============================================================================

-- 1. Extend ma_issuer with MC-ISSUER-CONFIG-2 envelope & MC-FED-01 freshness columns
ALTER TABLE ma_issuer
    ADD COLUMN IF NOT EXISTS checked_at timestamptz(6),
    ADD COLUMN IF NOT EXISTS metadata_cipher bytea,
    ADD COLUMN IF NOT EXISTS config_digest varchar(64),
    ADD COLUMN IF NOT EXISTS federation_component_id uuid,
    ADD COLUMN IF NOT EXISTS lifecycle_profile varchar(32);

ALTER TABLE ma_issuer
    ADD CONSTRAINT chk_ma_issuer_config_digest CHECK (
        config_digest IS NULL OR config_digest ~ '^[0-9a-f]{64}$'
    ) NOT VALID;

ALTER TABLE ma_issuer VALIDATE CONSTRAINT chk_ma_issuer_config_digest;

ALTER TABLE ma_issuer
    ADD CONSTRAINT chk_ma_issuer_metadata_cipher_len CHECK (
        metadata_cipher IS NULL OR octet_length(metadata_cipher) BETWEEN 1 AND 65536
    ) NOT VALID;

ALTER TABLE ma_issuer VALIDATE CONSTRAINT chk_ma_issuer_metadata_cipher_len;

ALTER TABLE ma_issuer
    ADD CONSTRAINT chk_ma_issuer_lifecycle_profile CHECK (
        lifecycle_profile IS NULL OR lifecycle_profile IN ('MC-FED-01')
    ) NOT VALID;

ALTER TABLE ma_issuer VALIDATE CONSTRAINT chk_ma_issuer_lifecycle_profile;

-- 2. Extend ma_oidc_link with per-link observation freshness timestamp
ALTER TABLE ma_oidc_link
    ADD COLUMN IF NOT EXISTS checked_at timestamptz(6);

-- 3. Extend ma_grant to distinguish EXPLICIT grants from OIDC PROVIDER group grants
ALTER TABLE ma_grant
    ADD COLUMN IF NOT EXISTS source varchar(16) NOT NULL DEFAULT 'EXPLICIT',
    ADD COLUMN IF NOT EXISTS source_ref varchar(128),
    ADD COLUMN IF NOT EXISTS field_group varchar(16) NOT NULL DEFAULT 'NONE';

-- Widen uniqueness so EXPLICIT grants and multiple PROVIDER group-operation tuples coexist cleanly
ALTER TABLE ma_grant
    DROP CONSTRAINT IF EXISTS uq_ma_grant_principal_company_role;

ALTER TABLE ma_grant
    ADD CONSTRAINT chk_ma_grant_source CHECK (
        source IN ('EXPLICIT', 'PROVIDER')
    ) NOT VALID;

ALTER TABLE ma_grant VALIDATE CONSTRAINT chk_ma_grant_source;

ALTER TABLE ma_grant
    ADD CONSTRAINT chk_ma_grant_source_ref_binding CHECK (
        (source = 'EXPLICIT' AND source_ref IS NULL)
        OR (
            source = 'PROVIDER'
            AND source_ref IS NOT NULL
            AND length(source_ref) = 84
            AND source_ref ~ '^oidc:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:group:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        )
    ) NOT VALID;

ALTER TABLE ma_grant VALIDATE CONSTRAINT chk_ma_grant_source_ref_binding;

ALTER TABLE ma_grant
    ADD CONSTRAINT chk_ma_grant_field_group CHECK (
        field_group IN ('NONE', 'E1', 'E2', 'E3', 'E4')
    ) NOT VALID;

ALTER TABLE ma_grant VALIDATE CONSTRAINT chk_ma_grant_field_group;

-- 4. Extend ma_auth_challenge with explicit durable OIDC ceremony stage
ALTER TABLE ma_auth_challenge
    ADD COLUMN IF NOT EXISTS ceremony_stage varchar(24) NOT NULL DEFAULT 'ISSUED';

ALTER TABLE ma_auth_challenge
    ADD CONSTRAINT chk_ma_auth_challenge_stage CHECK (
        ceremony_stage IN ('ISSUED', 'REDIRECTED', 'CLAIMED', 'VERIFIED_LINK', 'CONSUMED')
    ) NOT VALID;

ALTER TABLE ma_auth_challenge VALIDATE CONSTRAINT chk_ma_auth_challenge_stage;

-- 5. Guarded checked_at-only bookkeeping triggers (never churn version_no on pure freshness updates/clears)
CREATE OR REPLACE FUNCTION fn_ma_preserve_version_on_checked_at_bookkeeping()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_setting('ma.bookkeeping_checked_at_only', true) = 'on' THEN
        NEW.version_no := OLD.version_no;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_ma_issuer_checked_at_bookkeeping ON ma_issuer;
CREATE TRIGGER trg_ma_issuer_checked_at_bookkeeping
BEFORE UPDATE ON ma_issuer
FOR EACH ROW
EXECUTE FUNCTION fn_ma_preserve_version_on_checked_at_bookkeeping();

DROP TRIGGER IF EXISTS trg_ma_oidc_link_checked_at_bookkeeping ON ma_oidc_link;
CREATE TRIGGER trg_ma_oidc_link_checked_at_bookkeeping
BEFORE UPDATE ON ma_oidc_link
FOR EACH ROW
EXECUTE FUNCTION fn_ma_preserve_version_on_checked_at_bookkeeping();

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
