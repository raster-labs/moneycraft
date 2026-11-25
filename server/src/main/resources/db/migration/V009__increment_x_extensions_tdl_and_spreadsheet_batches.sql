SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Increment X: Guided Multi-Format Spreadsheet Importer,
-- Visual Extension Workbench (JSON Schema 2020-12 + Bounded DMN/FEEL),
-- Namespaced Custom Fields (SIV/GCM) & Static TDL Intake / Parity Matrix
-- Stream: db/migration/V009__increment_x_extensions_tdl_and_spreadsheet_batches.sql
-- ============================================================================

-- 1. Zero-Downtime Expand CHECK Constraints for Clean Neutral Enum Aliases
ALTER TABLE mc_company DROP CONSTRAINT IF EXISTS chk_mc_company_alteration_mode;
ALTER TABLE mc_company
    ADD CONSTRAINT chk_mc_company_alteration_mode CHECK (
        alteration_mode IN ('TALLY_EDIT_LOG', 'EDIT_LOG_VERSIONED', 'IMMUTABLE_LEDGER')
    ) NOT VALID;
ALTER TABLE mc_company VALIDATE CONSTRAINT chk_mc_company_alteration_mode;

ALTER TABLE mc_import_job DROP CONSTRAINT IF EXISTS chk_mc_import_job_type;
ALTER TABLE mc_import_job
    ADD CONSTRAINT chk_mc_import_job_type CHECK (
        job_type IN (
            'TALLY_1800',
            'TALLY_XML',
            'TALLY_JSONEX',
            'EXCEL_MAPPED',
            'LEGACY_1800',
            'LEGACY_XML',
            'LEGACY_JSONEX',
            'SHEET_GUIDED'
        )
    ) NOT VALID;
ALTER TABLE mc_import_job VALIDATE CONSTRAINT chk_mc_import_job_type;

-- 2. Guided Multi-Format Spreadsheet Import Batches (Frozen Encrypted Snapshot)
CREATE TABLE mc_sheet_import_batch (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    source_file_name varchar(255) NOT NULL,
    source_cipher_gcm bytea NOT NULL,
    source_sha256 bytea NOT NULL,
    format_profile varchar(24) NOT NULL,
    target_type varchar(32) NOT NULL DEFAULT 'ACCOUNTING_VOUCHERS',
    sheet_name varchar(120) NOT NULL DEFAULT 'Sheet1',
    available_sheets_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    detected_headers_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    header_row_index integer NOT NULL DEFAULT 1,
    date_system varchar(16) NOT NULL DEFAULT '1900',
    macro_detected boolean NOT NULL DEFAULT false,
    formula_cells_detected boolean NOT NULL DEFAULT false,
    template_id uuid,
    template_version bigint,
    mapping_revision integer NOT NULL DEFAULT 1,
    mapping_snapshot_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    cached_formula_policy_accepted boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL DEFAULT 'PREPARED',
    total_units integer NOT NULL DEFAULT 0,
    valid_units integer NOT NULL DEFAULT 0,
    invalid_units integer NOT NULL DEFAULT 0,
    committed_units integer NOT NULL DEFAULT 0,
    failed_units integer NOT NULL DEFAULT 0,
    skipped_units integer NOT NULL DEFAULT 0,
    uncertain_units integer NOT NULL DEFAULT 0,
    total_debit numeric NOT NULL DEFAULT 0,
    total_credit numeric NOT NULL DEFAULT 0,
    diagnostics_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    actor_principal_id uuid NOT NULL,
    executed_by uuid,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz(6),
    CONSTRAINT pk_mc_sheet_import_batch PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_sheet_import_batch_id UNIQUE (id),
    CONSTRAINT fk_mc_sheet_import_batch_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_sheet_import_batch_template FOREIGN KEY (company_id, template_id)
        REFERENCES mc_exchange_mapping_template (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_sheet_import_batch_actor FOREIGN KEY (actor_principal_id)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_sheet_import_batch_executor FOREIGN KEY (executed_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_sheet_batch_filename CHECK (length(trim(source_file_name)) > 0),
    CONSTRAINT chk_mc_sheet_batch_cipher CHECK (octet_length(source_cipher_gcm) > 0),
    CONSTRAINT chk_mc_sheet_batch_sha256 CHECK (octet_length(source_sha256) = 32),
    CONSTRAINT chk_mc_sheet_batch_format CHECK (
        format_profile IN ('XLSX', 'XLS', 'XLSM_DATA_ONLY', 'ODS', 'CSV', 'GOOGLE_SHEETS')
    ),
    CONSTRAINT chk_mc_sheet_batch_target CHECK (
        target_type IN ('ACCOUNTING_VOUCHERS', 'LEDGERS')
    ),
    CONSTRAINT chk_mc_sheet_batch_header_row CHECK (header_row_index >= 1),
    CONSTRAINT chk_mc_sheet_batch_date_system CHECK (
        date_system IN ('1900', '1904', 'ISO_TEXT')
    ),
    CONSTRAINT chk_mc_sheet_batch_tpl_ver CHECK (
        template_version IS NULL OR template_version >= 1
    ),
    CONSTRAINT chk_mc_sheet_batch_map_rev CHECK (mapping_revision >= 1),
    CONSTRAINT chk_mc_sheet_batch_status CHECK (
        status IN (
            'PREPARED',
            'READY',
            'EXECUTING',
            'PARTIAL_INTERRUPTED',
            'COMPLETED',
            'FAILED_BEFORE_WRITE',
            'CANCELLED'
        )
    ),
    CONSTRAINT chk_mc_sheet_batch_counts CHECK (
        total_units >= 0
        AND valid_units >= 0
        AND invalid_units >= 0
        AND committed_units >= 0
        AND failed_units >= 0
        AND skipped_units >= 0
        AND uncertain_units >= 0
    ),
    CONSTRAINT chk_mc_sheet_batch_total_dr CHECK (
        total_debit >= 0
        AND total_debit < 1000000000000000000
        AND total_debit = trunc(total_debit, 6)
    ),
    CONSTRAINT chk_mc_sheet_batch_total_cr CHECK (
        total_credit >= 0
        AND total_credit < 1000000000000000000
        AND total_credit = trunc(total_credit, 6)
    ),
    CONSTRAINT chk_mc_sheet_batch_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_sheet_import_batch_status
    ON mc_sheet_import_batch (company_id, status, created_at DESC);
CREATE INDEX idx_mc_sheet_import_batch_sha256
    ON mc_sheet_import_batch (company_id, source_sha256);

ALTER TABLE mc_sheet_import_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_sheet_import_batch FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_sheet_import_batch_tenant_isolation ON mc_sheet_import_batch
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 3. Per-Business-Unit Atomic Checkpoints & Outcomes (mc_sheet_import_unit)
CREATE TABLE mc_sheet_import_unit (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    mapping_revision integer NOT NULL DEFAULT 1,
    unit_seq integer NOT NULL,
    unit_key varchar(160) NOT NULL,
    target_type varchar(32) NOT NULL,
    source_coordinates_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    intended_payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    unit_debit numeric NOT NULL DEFAULT 0,
    unit_credit numeric NOT NULL DEFAULT 0,
    validation_status varchar(16) NOT NULL DEFAULT 'VALID',
    validation_errors_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    unit_idempotency_key varchar(128) NOT NULL,
    execution_status varchar(16) NOT NULL DEFAULT 'PENDING',
    execution_error_code varchar(64),
    execution_error_message varchar(512),
    resulting_entity_id uuid,
    resulting_reference varchar(64),
    committed_at timestamptz(6),
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_sheet_import_unit PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_sheet_import_unit_id UNIQUE (id),
    CONSTRAINT uq_mc_sheet_import_unit_seq UNIQUE (company_id, batch_id, mapping_revision, unit_seq),
    CONSTRAINT uq_mc_sheet_import_unit_key UNIQUE (company_id, batch_id, mapping_revision, unit_key),
    CONSTRAINT uq_mc_sheet_import_unit_idem UNIQUE (company_id, unit_idempotency_key),
    CONSTRAINT fk_mc_sheet_import_unit_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_sheet_import_unit_batch FOREIGN KEY (company_id, batch_id)
        REFERENCES mc_sheet_import_batch (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_sheet_unit_map_rev CHECK (mapping_revision >= 1),
    CONSTRAINT chk_mc_sheet_unit_seq CHECK (unit_seq >= 1),
    CONSTRAINT chk_mc_sheet_unit_key_nonempty CHECK (length(trim(unit_key)) > 0),
    CONSTRAINT chk_mc_sheet_unit_target CHECK (
        target_type IN ('ACCOUNTING_VOUCHERS', 'LEDGERS')
    ),
    CONSTRAINT chk_mc_sheet_unit_dr CHECK (
        unit_debit >= 0
        AND unit_debit < 1000000000000000000
        AND unit_debit = trunc(unit_debit, 6)
    ),
    CONSTRAINT chk_mc_sheet_unit_cr CHECK (
        unit_credit >= 0
        AND unit_credit < 1000000000000000000
        AND unit_credit = trunc(unit_credit, 6)
    ),
    CONSTRAINT chk_mc_sheet_unit_val_status CHECK (
        validation_status IN ('VALID', 'WARNING', 'INVALID')
    ),
    CONSTRAINT chk_mc_sheet_unit_exec_status CHECK (
        execution_status IN ('PENDING', 'COMMITTED', 'FAILED', 'SKIPPED', 'UNCERTAIN')
    ),
    CONSTRAINT chk_mc_sheet_unit_idem_nonempty CHECK (length(trim(unit_idempotency_key)) > 0)
);

CREATE INDEX idx_mc_sheet_import_unit_batch_exec
    ON mc_sheet_import_unit (company_id, batch_id, mapping_revision, execution_status, unit_seq);

ALTER TABLE mc_sheet_import_unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_sheet_import_unit FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_sheet_import_unit_tenant_isolation ON mc_sheet_import_unit
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 4. Immutable Versioned Extension Packages (JSON Schema 2020-12 + DMN/FEEL)
CREATE TABLE mc_extension_package (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    namespace varchar(96) NOT NULL,
    extension_uuid uuid NOT NULL,
    display_name varchar(160) NOT NULL,
    revision_no integer NOT NULL DEFAULT 1,
    schema_dialect varchar(128) NOT NULL DEFAULT 'https://json-schema.org/draft/2020-12/schema',
    definition_cipher_gcm bytea NOT NULL,
    definition_sha256 bytea NOT NULL,
    summary_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    validation_diagnostics_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_extension_package PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_extension_package_id UNIQUE (id),
    CONSTRAINT uq_mc_extension_package_rev UNIQUE (company_id, namespace, extension_uuid, revision_no),
    CONSTRAINT fk_mc_extension_package_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_extension_package_creator FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_extension_pkg_namespace CHECK (
        namespace ~ '^[a-z0-9][a-z0-9._-]{1,95}$'
    ),
    CONSTRAINT chk_mc_extension_pkg_name CHECK (length(trim(display_name)) > 0),
    CONSTRAINT chk_mc_extension_pkg_rev CHECK (revision_no >= 1),
    CONSTRAINT chk_mc_extension_pkg_dialect CHECK (length(trim(schema_dialect)) > 0),
    CONSTRAINT chk_mc_extension_pkg_cipher CHECK (octet_length(definition_cipher_gcm) > 0),
    CONSTRAINT chk_mc_extension_pkg_sha256 CHECK (octet_length(definition_sha256) = 32),
    CONSTRAINT chk_mc_extension_pkg_status CHECK (
        status IN ('DRAFT', 'VALIDATED', 'RETIRED')
    ),
    CONSTRAINT chk_mc_extension_pkg_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_extension_package_lookup
    ON mc_extension_package (company_id, extension_uuid, revision_no DESC);

CREATE OR REPLACE FUNCTION fn_mc_extension_package_immutable_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '55000',
            MESSAGE = 'MC-EXT-IMMUTABLE-PACKAGE: extension revisions cannot be deleted; retire revision instead';
    END IF;

    IF OLD.status IN ('VALIDATED', 'RETIRED') THEN
        IF NEW.namespace <> OLD.namespace
           OR NEW.extension_uuid <> OLD.extension_uuid
           OR NEW.revision_no <> OLD.revision_no
           OR NEW.schema_dialect <> OLD.schema_dialect
           OR NEW.definition_sha256 <> OLD.definition_sha256
           OR NEW.definition_cipher_gcm <> OLD.definition_cipher_gcm THEN
            RAISE EXCEPTION USING
                ERRCODE = '55000',
                MESSAGE = 'MC-EXT-IMMUTABLE-PACKAGE: validated extension definition bytes cannot be altered; publish a new revision_no';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_extension_package_immutable
BEFORE UPDATE OR DELETE ON mc_extension_package
FOR EACH ROW
EXECUTE FUNCTION fn_mc_extension_package_immutable_guard();

ALTER TABLE mc_extension_package ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_extension_package FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_extension_package_tenant_isolation ON mc_extension_package
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 5. Active Company Extension Bindings (mc_extension_company_binding)
CREATE TABLE mc_extension_company_binding (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    namespace varchar(96) NOT NULL,
    extension_uuid uuid NOT NULL,
    extension_package_id uuid NOT NULL,
    bound_definition_sha256 bytea NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'INACTIVE',
    activated_by uuid,
    activated_at timestamptz(6),
    suspended_by uuid,
    suspended_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_extension_company_binding PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_extension_company_binding_id UNIQUE (id),
    CONSTRAINT uq_mc_extension_company_binding_ext UNIQUE (company_id, extension_uuid),
    CONSTRAINT fk_mc_extension_binding_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_extension_binding_package FOREIGN KEY (company_id, extension_package_id)
        REFERENCES mc_extension_package (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_extension_binding_activated_by FOREIGN KEY (activated_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_extension_binding_suspended_by FOREIGN KEY (suspended_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_extension_binding_sha256 CHECK (octet_length(bound_definition_sha256) = 32),
    CONSTRAINT chk_mc_extension_binding_state CHECK (
        state IN ('INACTIVE', 'ACTIVE', 'SUSPENDED')
    ),
    CONSTRAINT chk_mc_extension_binding_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_extension_company_binding_active
    ON mc_extension_company_binding (company_id, state);

ALTER TABLE mc_extension_company_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_extension_company_binding FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_extension_company_binding_tenant_isolation ON mc_extension_company_binding
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 6. Namespaced Custom Field Definitions & Encrypted Values (EXT-003)
CREATE TABLE mc_custom_field_def (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    extension_package_id uuid,
    namespace varchar(96) NOT NULL,
    field_uuid uuid NOT NULL,
    owner_entity_type varchar(24) NOT NULL,
    field_code varchar(64) NOT NULL,
    label varchar(160) NOT NULL,
    data_type varchar(24) NOT NULL,
    decimal_scale smallint,
    enum_options_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    default_value_text varchar(512),
    protection_class varchar(24) NOT NULL DEFAULT 'STANDARD',
    ui_anchor varchar(96) NOT NULL DEFAULT 'HEADER_EXTRA',
    required_flag boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_custom_field_def PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_custom_field_def_id UNIQUE (id),
    CONSTRAINT uq_mc_custom_field_def_uuid UNIQUE (company_id, namespace, field_uuid),
    CONSTRAINT uq_mc_custom_field_def_code UNIQUE (company_id, owner_entity_type, namespace, field_code),
    CONSTRAINT fk_mc_custom_field_def_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_custom_field_def_package FOREIGN KEY (company_id, extension_package_id)
        REFERENCES mc_extension_package (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_custom_field_def_creator FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_custom_field_namespace CHECK (
        namespace ~ '^[a-z0-9][a-z0-9._-]{1,95}$'
    ),
    CONSTRAINT chk_mc_custom_field_owner CHECK (
        owner_entity_type IN ('LEDGER', 'STOCK_ITEM', 'VOUCHER')
    ),
    CONSTRAINT chk_mc_custom_field_code CHECK (
        field_code ~ '^[A-Za-z][A-Za-z0-9_]{1,63}$'
    ),
    CONSTRAINT chk_mc_custom_field_label CHECK (length(trim(label)) > 0),
    CONSTRAINT chk_mc_custom_field_type CHECK (
        data_type IN ('TEXT', 'EXACT_DECIMAL', 'DATE', 'BOOLEAN', 'ENUM')
    ),
    CONSTRAINT chk_mc_custom_field_scale CHECK (
        decimal_scale IS NULL OR decimal_scale BETWEEN 0 AND 6
    ),
    CONSTRAINT chk_mc_custom_field_protection CHECK (
        protection_class IN ('STANDARD', 'SELECTED_PRIVATE')
    ),
    CONSTRAINT chk_mc_custom_field_anchor CHECK (length(trim(ui_anchor)) > 0),
    CONSTRAINT chk_mc_custom_field_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_custom_field_def_entity
    ON mc_custom_field_def (company_id, owner_entity_type, active);

ALTER TABLE mc_custom_field_def ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_custom_field_def FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_custom_field_def_tenant_isolation ON mc_custom_field_def
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_custom_field_value (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    field_def_id uuid NOT NULL,
    owner_entity_type varchar(24) NOT NULL,
    entity_id uuid NOT NULL,
    plain_value_text text,
    siv_ciphertext bytea,
    gcm_ciphertext bytea,
    value_version bigint NOT NULL DEFAULT 1,
    updated_by uuid,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_custom_field_value PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_custom_field_value_id UNIQUE (id),
    CONSTRAINT uq_mc_custom_field_value_slot UNIQUE (company_id, field_def_id, entity_id),
    CONSTRAINT fk_mc_custom_field_value_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_custom_field_value_def FOREIGN KEY (company_id, field_def_id)
        REFERENCES mc_custom_field_def (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_custom_field_value_actor FOREIGN KEY (updated_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_custom_field_val_owner CHECK (
        owner_entity_type IN ('LEDGER', 'STOCK_ITEM', 'VOUCHER')
    ),
    CONSTRAINT chk_mc_custom_field_val_storage CHECK (
        (plain_value_text IS NOT NULL AND gcm_ciphertext IS NULL)
        OR (plain_value_text IS NULL AND gcm_ciphertext IS NOT NULL)
    ),
    CONSTRAINT chk_mc_custom_field_val_version CHECK (value_version >= 1)
);

CREATE INDEX idx_mc_custom_field_value_entity
    ON mc_custom_field_value (company_id, owner_entity_type, entity_id);
CREATE INDEX idx_mc_custom_field_value_siv
    ON mc_custom_field_value (company_id, field_def_id, siv_ciphertext)
    WHERE siv_ciphertext IS NOT NULL;

ALTER TABLE mc_custom_field_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_custom_field_value FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_custom_field_value_tenant_isolation ON mc_custom_field_value
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 7. Static TDL Source Intake & 5-Disposition Functional Parity Matrix (TDL-001..004)
CREATE TABLE mc_tdl_import_project (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    project_name varchar(160) NOT NULL,
    file_name varchar(255) NOT NULL,
    source_version_claim varchar(64),
    source_sha256 bytea NOT NULL,
    source_cipher_gcm bytea NOT NULL,
    has_compiled_tcp_block boolean NOT NULL DEFAULT false,
    has_native_dll_block boolean NOT NULL DEFAULT false,
    has_unresolved_includes boolean NOT NULL DEFAULT false,
    constructs_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    parity_matrix_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    summary_counts_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    draft_extension_id uuid,
    status varchar(32) NOT NULL DEFAULT 'TRANSLATED_DRAFT',
    created_by uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_tdl_import_project PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_tdl_import_project_id UNIQUE (id),
    CONSTRAINT fk_mc_tdl_import_project_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tdl_import_project_draft FOREIGN KEY (company_id, draft_extension_id)
        REFERENCES mc_extension_package (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tdl_import_project_creator FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_tdl_project_name CHECK (length(trim(project_name)) > 0),
    CONSTRAINT chk_mc_tdl_project_filename CHECK (length(trim(file_name)) > 0),
    CONSTRAINT chk_mc_tdl_project_sha256 CHECK (octet_length(source_sha256) = 32),
    CONSTRAINT chk_mc_tdl_project_cipher CHECK (octet_length(source_cipher_gcm) > 0),
    CONSTRAINT chk_mc_tdl_project_status CHECK (
        status IN ('TRANSLATED_DRAFT', 'BLOCKED_DEPENDENCY', 'PARTIAL_PENDING_MAPPING')
    )
);

CREATE INDEX idx_mc_tdl_import_project_created
    ON mc_tdl_import_project (company_id, created_at DESC);

CREATE TRIGGER trg_mc_tdl_import_project_immutable
BEFORE UPDATE OR DELETE ON mc_tdl_import_project
FOR EACH ROW
EXECUTE FUNCTION fn_mc_forbid_append_only_mutation();

ALTER TABLE mc_tdl_import_project ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_tdl_import_project FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_tdl_import_project_tenant_isolation ON mc_tdl_import_project
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 8. Least-Privilege Grants & Append-Only Revocations for mc_app_runtime
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mc_app_runtime') THEN
        GRANT SELECT, INSERT, UPDATE ON
            mc_sheet_import_batch,
            mc_sheet_import_unit,
            mc_extension_package,
            mc_extension_company_binding,
            mc_custom_field_def,
            mc_custom_field_value
        TO mc_app_runtime;

        GRANT DELETE ON mc_custom_field_value TO mc_app_runtime;

        REVOKE DELETE, TRUNCATE ON
            mc_sheet_import_batch,
            mc_sheet_import_unit,
            mc_extension_package,
            mc_extension_company_binding,
            mc_custom_field_def
        FROM mc_app_runtime;

        GRANT SELECT, INSERT ON mc_tdl_import_project TO mc_app_runtime;
        REVOKE UPDATE, DELETE, TRUNCATE ON mc_tdl_import_project FROM mc_app_runtime;
    END IF;
END;
$$;
