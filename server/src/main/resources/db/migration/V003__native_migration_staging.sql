SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Clean-Room .1800 Snapshot Staging & Exchange Templates
-- Stream: db/migration/V003__native_migration_staging.sql
-- ============================================================================

-- 1. Import & Native .1800 Migration Jobs
CREATE TABLE mc_import_job (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    job_type varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'STAGED',
    source_company_number varchar(32),
    source_release_version varchar(32),
    source_snapshot_sha256 bytea,
    cmp_save_parity_verified boolean NOT NULL DEFAULT false,
    sector0_checksum_verified boolean NOT NULL DEFAULT false,
    total_records_read integer NOT NULL DEFAULT 0,
    valid_records_count integer NOT NULL DEFAULT 0,
    error_records_count integer NOT NULL DEFAULT 0,
    diagnostics_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    initiated_by uuid,
    committed_by uuid,
    started_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    CONSTRAINT pk_mc_import_job PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_import_job_id UNIQUE (id),
    CONSTRAINT fk_mc_import_job_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_import_job_initiated_by FOREIGN KEY (initiated_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_import_job_committed_by FOREIGN KEY (committed_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_import_job_type CHECK (
        job_type IN ('TALLY_1800', 'TALLY_XML', 'TALLY_JSONEX', 'EXCEL_MAPPED')
    ),
    CONSTRAINT chk_mc_import_job_status CHECK (
        status IN (
            'STAGED',
            'VALIDATING',
            'READY_TO_COMMIT',
            'VALIDATION_FAILED',
            'COMMITTING',
            'COMMITTED',
            'CANCELLED'
        )
    ),
    CONSTRAINT chk_mc_import_job_sha256 CHECK (
        source_snapshot_sha256 IS NULL OR octet_length(source_snapshot_sha256) = 32
    ),
    CONSTRAINT chk_mc_import_job_counts CHECK (
        total_records_read >= 0
        AND valid_records_count >= 0
        AND error_records_count >= 0
    ),
    CONSTRAINT chk_mc_import_job_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_import_job_status ON mc_import_job (company_id, status, started_at DESC);

ALTER TABLE mc_import_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_import_job FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_import_job_tenant_isolation ON mc_import_job
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 2. Staged Master & Transaction Records Extracted Prior to Atomic Promotion
CREATE TABLE mc_import_staging_record (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    record_seq bigint NOT NULL,
    source_store_name varchar(32) NOT NULL,
    entity_type varchar(32) NOT NULL,
    source_master_id bigint,
    source_alter_id bigint,
    source_guid varchar(128),
    source_name varchar(255),
    normalized_payload jsonb NOT NULL,
    validation_status varchar(16) NOT NULL DEFAULT 'PENDING',
    validation_errors jsonb NOT NULL DEFAULT '[]'::jsonb,
    promoted_entity_id uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_import_staging_record PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_import_staging_record_id UNIQUE (id),
    CONSTRAINT uq_mc_import_staging_record_seq UNIQUE (company_id, import_job_id, record_seq),
    CONSTRAINT fk_mc_import_staging_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_import_staging_job FOREIGN KEY (company_id, import_job_id)
        REFERENCES mc_import_job (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_import_staging_seq CHECK (record_seq >= 1),
    CONSTRAINT chk_mc_import_staging_entity_type CHECK (
        entity_type IN (
            'COMPANY_META',
            'ACCOUNT_GROUP',
            'LEDGER',
            'CURRENCY',
            'COST_CATEGORY',
            'COST_CENTRE',
            'UOM',
            'GODOWN',
            'STOCK_GROUP',
            'STOCK_CATEGORY',
            'STOCK_ITEM',
            'VOUCHER_TYPE',
            'VOUCHER',
            'EDIT_LOG_HISTORY',
            'STATUTORY_STATUS'
        )
    ),
    CONSTRAINT chk_mc_import_staging_val_status CHECK (
        validation_status IN ('PENDING', 'VALID', 'WARNING', 'ERROR', 'PROMOTED')
    )
);

CREATE INDEX idx_mc_import_staging_job_type
    ON mc_import_staging_record (company_id, import_job_id, entity_type, record_seq);

ALTER TABLE mc_import_staging_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_import_staging_record FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_import_staging_record_tenant_isolation ON mc_import_staging_record
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 3. Pre-Commit & Post-Commit Trial Balance Reconciliation Proof (MIG-004)
CREATE TABLE mc_import_reconciliation_proof (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    source_group_count integer NOT NULL DEFAULT 0,
    staged_group_count integer NOT NULL DEFAULT 0,
    source_ledger_count integer NOT NULL DEFAULT 0,
    staged_ledger_count integer NOT NULL DEFAULT 0,
    source_item_count integer NOT NULL DEFAULT 0,
    staged_item_count integer NOT NULL DEFAULT 0,
    source_voucher_count integer NOT NULL DEFAULT 0,
    staged_voucher_count integer NOT NULL DEFAULT 0,
    source_gross_debit numeric NOT NULL DEFAULT 0,
    staged_gross_debit numeric NOT NULL DEFAULT 0,
    source_gross_credit numeric NOT NULL DEFAULT 0,
    staged_gross_credit numeric NOT NULL DEFAULT 0,
    source_net_trial_balance numeric NOT NULL DEFAULT 0,
    staged_net_trial_balance numeric NOT NULL DEFAULT 0,
    source_closing_stock_value numeric NOT NULL DEFAULT 0,
    staged_closing_stock_value numeric NOT NULL DEFAULT 0,
    source_net_profit numeric NOT NULL DEFAULT 0,
    staged_net_profit numeric NOT NULL DEFAULT 0,
    variance_debit numeric NOT NULL DEFAULT 0,
    variance_credit numeric NOT NULL DEFAULT 0,
    is_zero_variance boolean NOT NULL DEFAULT false,
    ledger_level_diff_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    proof_sha256 bytea NOT NULL,
    verified_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_import_reconciliation_proof PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_import_reconciliation_proof_id UNIQUE (id),
    CONSTRAINT uq_mc_import_reconciliation_job UNIQUE (company_id, import_job_id),
    CONSTRAINT fk_mc_import_recon_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_import_recon_job FOREIGN KEY (company_id, import_job_id)
        REFERENCES mc_import_job (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_import_recon_src_dr CHECK (
        source_gross_debit >= 0 AND source_gross_debit < 1000000000000000000 AND source_gross_debit = trunc(source_gross_debit, 6)
    ),
    CONSTRAINT chk_mc_import_recon_stg_dr CHECK (
        staged_gross_debit >= 0 AND staged_gross_debit < 1000000000000000000 AND staged_gross_debit = trunc(staged_gross_debit, 6)
    ),
    CONSTRAINT chk_mc_import_recon_src_cr CHECK (
        source_gross_credit >= 0 AND source_gross_credit < 1000000000000000000 AND source_gross_credit = trunc(source_gross_credit, 6)
    ),
    CONSTRAINT chk_mc_import_recon_stg_cr CHECK (
        staged_gross_credit >= 0 AND staged_gross_credit < 1000000000000000000 AND staged_gross_credit = trunc(staged_gross_credit, 6)
    ),
    CONSTRAINT chk_mc_import_recon_src_tb CHECK (
        source_net_trial_balance >= 0 AND source_net_trial_balance < 1000000000000000000 AND source_net_trial_balance = trunc(source_net_trial_balance, 6)
    ),
    CONSTRAINT chk_mc_import_recon_stg_tb CHECK (
        staged_net_trial_balance >= 0 AND staged_net_trial_balance < 1000000000000000000 AND staged_net_trial_balance = trunc(staged_net_trial_balance, 6)
    ),
    CONSTRAINT chk_mc_import_recon_stock CHECK (
        source_closing_stock_value >= 0 AND source_closing_stock_value < 1000000000000000000 AND source_closing_stock_value = trunc(source_closing_stock_value, 6)
        AND staged_closing_stock_value >= 0 AND staged_closing_stock_value < 1000000000000000000 AND staged_closing_stock_value = trunc(staged_closing_stock_value, 6)
    ),
    CONSTRAINT chk_mc_import_recon_pnl CHECK (
        abs(source_net_profit) < 1000000000000000000 AND source_net_profit = trunc(source_net_profit, 6)
        AND abs(staged_net_profit) < 1000000000000000000 AND staged_net_profit = trunc(staged_net_profit, 6)
    ),
    CONSTRAINT chk_mc_import_recon_var CHECK (
        variance_debit >= 0 AND variance_debit < 1000000000000000000 AND variance_debit = trunc(variance_debit, 6)
        AND variance_credit >= 0 AND variance_credit < 1000000000000000000 AND variance_credit = trunc(variance_credit, 6)
    ),
    CONSTRAINT chk_mc_import_recon_sha256 CHECK (octet_length(proof_sha256) = 32)
);

ALTER TABLE mc_import_reconciliation_proof ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_import_reconciliation_proof FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_import_reconciliation_proof_tenant_isolation ON mc_import_reconciliation_proof
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 4. Custom-Mapped OpenXML Excel (.xlsx) & Data Exchange Mapping Templates
CREATE TABLE mc_exchange_mapping_template (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    target_type varchar(32) NOT NULL,
    sheet_name varchar(120),
    header_row_index integer NOT NULL DEFAULT 1,
    column_mappings_json jsonb NOT NULL,
    default_values_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    duplicate_handling varchar(24) NOT NULL DEFAULT 'REJECT',
    active boolean NOT NULL DEFAULT true,
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_exchange_mapping_template PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_exchange_mapping_template_id UNIQUE (id),
    CONSTRAINT fk_mc_exchange_template_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_exchange_template_created_by FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_exchange_template_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_exchange_template_target CHECK (
        target_type IN (
            'LEDGERS',
            'STOCK_ITEMS',
            'ACCOUNTING_VOUCHERS',
            'ITEM_VOUCHERS',
            'BANK_STATEMENT'
        )
    ),
    CONSTRAINT chk_mc_exchange_template_header_row CHECK (header_row_index >= 1),
    CONSTRAINT chk_mc_exchange_template_dup CHECK (
        duplicate_handling IN ('REJECT', 'SKIP', 'UPDATE_IF_DRAFT')
    ),
    CONSTRAINT chk_mc_exchange_template_version_no CHECK (version_no >= 1)
);

CREATE UNIQUE INDEX uq_mc_exchange_template_company_name
    ON mc_exchange_mapping_template (company_id, lower(name));

ALTER TABLE mc_exchange_mapping_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_exchange_mapping_template FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_exchange_mapping_template_tenant_isolation ON mc_exchange_mapping_template
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );
