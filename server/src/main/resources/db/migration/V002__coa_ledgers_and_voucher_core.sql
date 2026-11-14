SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Chart of Accounts (28 Groups), Ledgers, 24 Voucher Types,
-- Double-Entry General Ledger (mc_entry) & Synchronous Period Rollups
-- Stream: db/migration/V002__coa_ledgers_and_voucher_core.sql
-- ============================================================================

-- Deterministic RFC-4122 UUID helper for idempotent company seed records
CREATE OR REPLACE FUNCTION fn_mc_seed_uuid(p_company_id uuid, p_seed_key text)
RETURNS uuid
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT (
        substr(h, 1, 8) || '-' ||
        substr(h, 9, 4) || '-' ||
        '5' || substr(h, 14, 3) || '-' ||
        'a' || substr(h, 18, 3) || '-' ||
        substr(h, 21, 12)
    )::uuid
    FROM (SELECT md5(p_company_id::text || ':' || p_seed_key) AS h) s;
$$;

-- 1. Hierarchical Account Groups (TallyPrime 28 Predefined + User Custom)
CREATE TABLE mc_account_group (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    alias_code varchar(64),
    parent_group_id uuid,
    nature varchar(16) NOT NULL,
    affects_gross_profit boolean NOT NULL DEFAULT false,
    behaves_like_subledger boolean NOT NULL DEFAULT false,
    net_debit_credit_for_reporting boolean NOT NULL DEFAULT false,
    used_for_calculation boolean NOT NULL DEFAULT false,
    method_to_allocate_purchase varchar(24) NOT NULL DEFAULT 'NOT_APPLICABLE',
    schedule_iii_line_code varchar(64),
    default_gst_rate_pct numeric,
    default_hsn_sac_code varchar(16),
    tree_path varchar(1024) NOT NULL DEFAULT '',
    depth_level smallint NOT NULL DEFAULT 0,
    is_system_group boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 100,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_account_group PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_account_group_id UNIQUE (id),
    CONSTRAINT fk_mc_account_group_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_account_group_parent FOREIGN KEY (company_id, parent_group_id)
        REFERENCES mc_account_group (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_account_group_created_by FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_account_group_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_account_group_nature CHECK (
        nature IN ('ASSETS', 'LIABILITIES', 'INCOME', 'EXPENSES')
    ),
    CONSTRAINT chk_mc_account_group_alloc_method CHECK (
        method_to_allocate_purchase IN ('NOT_APPLICABLE', 'APPROPRIATE_BY_QTY', 'APPROPRIATE_BY_VALUE')
    ),
    CONSTRAINT chk_mc_account_group_gst_rate CHECK (
        default_gst_rate_pct IS NULL
        OR (
            default_gst_rate_pct >= 0
            AND default_gst_rate_pct <= 100
            AND default_gst_rate_pct = trunc(default_gst_rate_pct, 6)
        )
    ),
    CONSTRAINT chk_mc_account_group_depth CHECK (depth_level BETWEEN 0 AND 32),
    CONSTRAINT chk_mc_account_group_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_account_group_company_lower_name
    ON mc_account_group (company_id, lower(name));

CREATE INDEX idx_mc_account_group_parent
    ON mc_account_group (company_id, parent_group_id, sort_order);

CREATE INDEX idx_mc_account_group_trgm_name
    ON mc_account_group USING gin (name gin_trgm_ops);

CREATE OR REPLACE FUNCTION fn_mc_account_group_hierarchy_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_cursor_id uuid;
    v_parent_nature varchar(16);
    v_parent_path varchar(1024);
    v_parent_depth smallint;
    v_hops integer := 0;
BEGIN
    IF NEW.parent_group_id IS NULL THEN
        NEW.depth_level := 0;
        NEW.tree_path := '/' || NEW.id::text || '/';
        RETURN NEW;
    END IF;

    IF NEW.parent_group_id = NEW.id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'MC-ACC-GROUP-CYCLE: account group cannot be its own parent';
    END IF;

    v_cursor_id := NEW.parent_group_id;
    WHILE v_cursor_id IS NOT NULL LOOP
        v_hops := v_hops + 1;
        IF v_hops > 32 THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'MC-ACC-GROUP-DEPTH: account group hierarchy exceeds maximum depth 32';
        END IF;

        IF v_cursor_id = NEW.id THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'MC-ACC-GROUP-CYCLE: cycle detected in mc_account_group hierarchy';
        END IF;

        SELECT g.parent_group_id
          INTO v_cursor_id
          FROM mc_account_group g
         WHERE g.company_id = NEW.company_id
           AND g.id = v_cursor_id;
    END LOOP;

    SELECT g.nature, g.tree_path, g.depth_level
      INTO v_parent_nature, v_parent_path, v_parent_depth
      FROM mc_account_group g
     WHERE g.company_id = NEW.company_id
       AND g.id = NEW.parent_group_id;

    IF v_parent_nature IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            MESSAGE = 'MC-ACC-GROUP-PARENT-MISSING: parent group does not exist in company';
    END IF;

    NEW.nature := v_parent_nature;
    NEW.depth_level := v_parent_depth + 1;
    NEW.tree_path := v_parent_path || NEW.id::text || '/';
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_account_group_no_cycle
BEFORE INSERT OR UPDATE ON mc_account_group
FOR EACH ROW
EXECUTE FUNCTION fn_mc_account_group_hierarchy_guard();

CREATE OR REPLACE FUNCTION fn_mc_account_group_protect_system()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.is_system_group = true THEN
            RAISE EXCEPTION USING
                ERRCODE = '42501',
                MESSAGE = format('MC-ACC-SYSTEM-GROUP-PROTECTED: predefined system group %s cannot be deleted', OLD.name);
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.is_system_group = true AND (NEW.is_system_group = false OR lower(NEW.name) <> lower(OLD.name)) THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = format('MC-ACC-SYSTEM-GROUP-PROTECTED: predefined system group %s cannot be renamed or demoted', OLD.name);
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_account_group_protect_system
BEFORE UPDATE OR DELETE ON mc_account_group
FOR EACH ROW
EXECUTE FUNCTION fn_mc_account_group_protect_system();

ALTER TABLE mc_account_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_account_group FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_account_group_tenant_isolation ON mc_account_group
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 2. Accounting Ledgers (General, Party, Cash/Bank, Duties & Taxes, Expense/Income)
CREATE TABLE mc_ledger (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    alias_code varchar(64),
    group_id uuid,
    opening_balance numeric NOT NULL DEFAULT 0,
    opening_side char(2) NOT NULL DEFAULT 'DR',
    currency_code char(3) NOT NULL DEFAULT 'INR',
    maintain_balances_bill_by_bill boolean NOT NULL DEFAULT false,
    default_credit_period_days integer,
    check_credit_days_during_voucher boolean NOT NULL DEFAULT false,
    credit_limit numeric,
    override_credit_limit_post_dated boolean NOT NULL DEFAULT false,
    inventory_values_affected boolean NOT NULL DEFAULT false,
    cost_centres_applicable boolean NOT NULL DEFAULT false,
    tax_type varchar(16) NOT NULL DEFAULT 'NONE',
    duty_head varchar(24),
    gst_applicability varchar(24) NOT NULL DEFAULT 'NOT_APPLICABLE',
    gst_supply_type varchar(16) NOT NULL DEFAULT 'GOODS',
    hsn_sac_code varchar(16),
    gst_rate_pct numeric,
    cess_rate_pct numeric,
    appropriate_for varchar(24) NOT NULL DEFAULT 'NONE',
    appropriation_basis varchar(16) NOT NULL DEFAULT 'BY_VALUE',
    rounding_method varchar(16) NOT NULL DEFAULT 'NONE',
    rounding_limit numeric,
    party_registration_type varchar(24) NOT NULL DEFAULT 'UNKNOWN',
    gstin varchar(15),
    country_code char(2) NOT NULL DEFAULT 'IN',
    state_code varchar(4),
    state_name varchar(100),
    pincode varchar(16),
    mailing_name varchar(200),
    mailing_address text,
    pan_siv bytea,
    pan_plain_masked varchar(16),
    msme_registration_type varchar(24),
    msme_udyam_no varchar(32),
    tds_applicable boolean NOT NULL DEFAULT false,
    tds_nature_code varchar(24),
    tds_deductee_type varchar(32),
    lower_tds_cert_no varchar(64),
    lower_tds_rate_pct numeric,
    lower_tds_valid_from date,
    lower_tds_valid_to date,
    bank_account_holder varchar(160),
    bank_ifsc_code varchar(16),
    bank_name varchar(120),
    bank_branch varchar(120),
    bank_account_siv bytea,
    bank_details_gcm bytea,
    upi_id varchar(120),
    is_system_ledger boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_ledger PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_ledger_id UNIQUE (id),
    CONSTRAINT fk_mc_ledger_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_ledger_group FOREIGN KEY (company_id, group_id)
        REFERENCES mc_account_group (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_ledger_created_by FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_ledger_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_ledger_group_required CHECK (
        name = 'Profit & Loss A/c' OR group_id IS NOT NULL
    ),
    CONSTRAINT chk_mc_ledger_opening_exact_numeric CHECK (
        opening_balance >= 0
        AND opening_balance < 1000000000000000000
        AND opening_balance = trunc(opening_balance, 6)
    ),
    CONSTRAINT chk_mc_ledger_opening_side CHECK (opening_side IN ('DR', 'CR')),
    CONSTRAINT chk_mc_ledger_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_mc_ledger_credit_days CHECK (
        default_credit_period_days IS NULL OR default_credit_period_days >= 0
    ),
    CONSTRAINT chk_mc_ledger_credit_limit CHECK (
        credit_limit IS NULL
        OR (
            credit_limit >= 0
            AND credit_limit < 1000000000000000000
            AND credit_limit = trunc(credit_limit, 6)
        )
    ),
    CONSTRAINT chk_mc_ledger_tax_type CHECK (
        tax_type IN ('NONE', 'GST', 'TDS', 'TCS', 'OTHERS')
    ),
    CONSTRAINT chk_mc_ledger_duty_head CHECK (
        duty_head IS NULL
        OR duty_head IN ('CGST', 'SGST_UTGST', 'IGST', 'CESS', 'TDS_SEC', 'TCS_SEC', 'OTHERS')
    ),
    CONSTRAINT chk_mc_ledger_gst_app CHECK (
        gst_applicability IN ('APPLICABLE', 'NOT_APPLICABLE', 'UNDEFINED')
    ),
    CONSTRAINT chk_mc_ledger_supply_type CHECK (
        gst_supply_type IN ('GOODS', 'SERVICES', 'CAPITAL_GOODS')
    ),
    CONSTRAINT chk_mc_ledger_gst_rate CHECK (
        gst_rate_pct IS NULL
        OR (gst_rate_pct >= 0 AND gst_rate_pct <= 100 AND gst_rate_pct = trunc(gst_rate_pct, 6))
    ),
    CONSTRAINT chk_mc_ledger_cess_rate CHECK (
        cess_rate_pct IS NULL
        OR (cess_rate_pct >= 0 AND cess_rate_pct <= 500 AND cess_rate_pct = trunc(cess_rate_pct, 6))
    ),
    CONSTRAINT chk_mc_ledger_appropriate_for CHECK (
        appropriate_for IN ('NONE', 'GST', 'BOTH_QTY', 'BOTH_VALUE')
    ),
    CONSTRAINT chk_mc_ledger_approp_basis CHECK (
        appropriation_basis IN ('BY_VALUE', 'BY_QTY')
    ),
    CONSTRAINT chk_mc_ledger_rounding CHECK (
        rounding_method IN ('NONE', 'NORMAL_HALF_UP', 'UPWARD', 'DOWNWARD')
    ),
    CONSTRAINT chk_mc_ledger_rounding_limit CHECK (
        rounding_limit IS NULL
        OR (
            rounding_limit >= 0
            AND rounding_limit < 1000000000000000000
            AND rounding_limit = trunc(rounding_limit, 6)
        )
    ),
    CONSTRAINT chk_mc_ledger_party_reg CHECK (
        party_registration_type IN (
            'UNKNOWN',
            'REGULAR',
            'COMPOSITION',
            'UNREGISTERED',
            'CONSUMER',
            'OVERSEAS',
            'SEZ',
            'GOVERNMENT_ENTITY'
        )
    ),
    CONSTRAINT chk_mc_ledger_lower_tds_rate CHECK (
        lower_tds_rate_pct IS NULL
        OR (
            lower_tds_rate_pct >= 0
            AND lower_tds_rate_pct <= 100
            AND lower_tds_rate_pct = trunc(lower_tds_rate_pct, 6)
        )
    ),
    CONSTRAINT chk_mc_ledger_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_ledger_company_lower_name
    ON mc_ledger (company_id, lower(name));

CREATE INDEX idx_mc_ledger_group_lookup ON mc_ledger (company_id, group_id, active);
CREATE INDEX idx_mc_ledger_pan_siv ON mc_ledger (company_id, pan_siv) WHERE pan_siv IS NOT NULL;
CREATE INDEX idx_mc_ledger_trgm_name ON mc_ledger USING gin (name gin_trgm_ops);
CREATE INDEX idx_mc_ledger_trgm_alias ON mc_ledger USING gin (alias_code gin_trgm_ops)
    WHERE alias_code IS NOT NULL;
CREATE INDEX idx_mc_ledger_trgm_gstin ON mc_ledger USING gin (gstin gin_trgm_ops)
    WHERE gstin IS NOT NULL;

CREATE OR REPLACE FUNCTION fn_mc_ledger_protect_and_scale()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_scale smallint;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.is_system_ledger = true THEN
            RAISE EXCEPTION USING
                ERRCODE = '42501',
                MESSAGE = format('MC-ACC-SYSTEM-LEDGER-PROTECTED: system ledger %s cannot be deleted', OLD.name);
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.is_system_ledger = true THEN
        IF NEW.is_system_ledger = false OR lower(NEW.name) <> lower(OLD.name) THEN
            RAISE EXCEPTION USING
                ERRCODE = '42501',
                MESSAGE = format('MC-ACC-SYSTEM-LEDGER-PROTECTED: system ledger %s cannot be renamed or demoted', OLD.name);
        END IF;
    END IF;

    SELECT c.currency_scale
      INTO v_scale
      FROM mc_company c
     WHERE c.id = NEW.company_id;

    IF v_scale IS NOT NULL AND NEW.opening_balance <> trunc(NEW.opening_balance, v_scale) THEN
        RAISE EXCEPTION USING
            ERRCODE = '22003',
            MESSAGE = format('MC-ACC-SCALE-OVERFLOW: mc_ledger.opening_balance exceeds company currency_scale %s', v_scale);
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_ledger_protect_system_and_scale
BEFORE INSERT OR UPDATE OR DELETE ON mc_ledger
FOR EACH ROW
EXECUTE FUNCTION fn_mc_ledger_protect_and_scale();

ALTER TABLE mc_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_ledger FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_ledger_tenant_isolation ON mc_ledger
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 3. Voucher Types (TallyPrime 24 Predefined + Custom Sub-Types)
CREATE TABLE mc_voucher_type (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    abbreviation varchar(24) NOT NULL,
    base_category varchar(32) NOT NULL,
    parent_voucher_type_id uuid,
    posts_accounting_entries boolean NOT NULL DEFAULT true,
    moves_inventory boolean NOT NULL DEFAULT false,
    default_entry_mode varchar(24) NOT NULL DEFAULT 'AS_VOUCHER',
    numbering_method varchar(32) NOT NULL DEFAULT 'AUTOMATIC_RETAIN_NUMBERS',
    prevent_duplicate_numbers boolean NOT NULL DEFAULT true,
    use_effective_dates boolean NOT NULL DEFAULT false,
    allow_zero_valued_transactions boolean NOT NULL DEFAULT false,
    make_optional_by_default boolean NOT NULL DEFAULT false,
    allow_narration_per_ledger boolean NOT NULL DEFAULT false,
    print_after_save boolean NOT NULL DEFAULT false,
    is_manufacturing_journal boolean NOT NULL DEFAULT false,
    approval_policy varchar(16) NOT NULL DEFAULT 'SINGLE_STEP',
    shortcut_key varchar(24),
    is_system_voucher_type boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_voucher_type PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_voucher_type_id UNIQUE (id),
    CONSTRAINT fk_mc_voucher_type_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_voucher_type_parent FOREIGN KEY (company_id, parent_voucher_type_id)
        REFERENCES mc_voucher_type (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_voucher_type_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_voucher_type_category CHECK (
        base_category IN (
            'CONTRA',
            'PAYMENT',
            'RECEIPT',
            'JOURNAL',
            'SALES',
            'PURCHASE',
            'CREDIT_NOTE',
            'DEBIT_NOTE',
            'MEMORANDUM',
            'REVERSING_JOURNAL',
            'SALES_ORDER',
            'PURCHASE_ORDER',
            'JOB_WORK_OUT_ORDER',
            'JOB_WORK_IN_ORDER',
            'DELIVERY_NOTE',
            'RECEIPT_NOTE',
            'REJECTIONS_IN',
            'REJECTIONS_OUT',
            'STOCK_JOURNAL',
            'PHYSICAL_STOCK',
            'MATERIAL_IN',
            'MATERIAL_OUT',
            'ATTENDANCE',
            'PAYROLL'
        )
    ),
    CONSTRAINT chk_mc_voucher_type_entry_mode CHECK (
        default_entry_mode IN (
            'SINGLE_ENTRY',
            'DOUBLE_ENTRY',
            'ITEM_INVOICE',
            'ACCOUNTING_INVOICE',
            'AS_VOUCHER'
        )
    ),
    CONSTRAINT chk_mc_voucher_type_numbering CHECK (
        numbering_method IN (
            'AUTOMATIC_RETAIN_NUMBERS',
            'AUTOMATIC_RENUMBER',
            'MULTI_USER_AUTO',
            'MANUAL',
            'NONE'
        )
    ),
    CONSTRAINT chk_mc_voucher_type_approval CHECK (
        approval_policy IN ('SINGLE_STEP', 'FOUR_EYES')
    ),
    CONSTRAINT chk_mc_voucher_type_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_voucher_type_company_lower_name
    ON mc_voucher_type (company_id, lower(name));

CREATE OR REPLACE FUNCTION fn_mc_voucher_type_protect_system()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.is_system_voucher_type = true THEN
            RAISE EXCEPTION USING
                ERRCODE = '42501',
                MESSAGE = format('MC-ACC-SYSTEM-VCHTYPE-PROTECTED: predefined voucher type %s cannot be deleted', OLD.name);
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.is_system_voucher_type = true
       AND (NEW.is_system_voucher_type = false OR lower(NEW.name) <> lower(OLD.name)) THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = format('MC-ACC-SYSTEM-VCHTYPE-PROTECTED: predefined voucher type %s cannot be renamed', OLD.name);
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_voucher_type_protect_system
BEFORE UPDATE OR DELETE ON mc_voucher_type
FOR EACH ROW
EXECUTE FUNCTION fn_mc_voucher_type_protect_system();

ALTER TABLE mc_voucher_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_voucher_type FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_voucher_type_tenant_isolation ON mc_voucher_type
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 4. Voucher Classes (Pre-Configured Accounting/Tax/Forex Templates)
CREATE TABLE mc_voucher_class (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    voucher_type_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    default_accounting_ledger_id uuid,
    exclude_groups jsonb NOT NULL DEFAULT '[]'::jsonb,
    include_groups jsonb NOT NULL DEFAULT '[]'::jsonb,
    auto_additional_ledgers jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_forex_gain_loss_class boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_voucher_class PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_voucher_class_id UNIQUE (id),
    CONSTRAINT uq_mc_voucher_class_name UNIQUE (company_id, voucher_type_id, name),
    CONSTRAINT fk_mc_voucher_class_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_voucher_class_vch_type FOREIGN KEY (company_id, voucher_type_id)
        REFERENCES mc_voucher_type (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_voucher_class_default_ledger FOREIGN KEY (company_id, default_accounting_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_voucher_class_version_no CHECK (version_no >= 1)
);

ALTER TABLE mc_voucher_class ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_voucher_class FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_voucher_class_tenant_isolation ON mc_voucher_class
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 5. Transactional Gap-Free Number Series & Unused Statutory Voucher Numbers
CREATE TABLE mc_number_series (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    voucher_type_id uuid NOT NULL,
    fiscal_year_id uuid,
    gst_registration_id uuid,
    series_name varchar(80) NOT NULL DEFAULT 'Default',
    prefix varchar(16) NOT NULL DEFAULT '',
    suffix varchar(16) NOT NULL DEFAULT '',
    starting_number bigint NOT NULL DEFAULT 1,
    next_number bigint NOT NULL DEFAULT 1,
    min_width smallint NOT NULL DEFAULT 1,
    prefill_with_zero boolean NOT NULL DEFAULT false,
    restart_periodicity varchar(16) NOT NULL DEFAULT 'YEARLY',
    version_no bigint NOT NULL DEFAULT 1,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_number_series PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_number_series_id UNIQUE (id),
    CONSTRAINT fk_mc_number_series_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_number_series_vch_type FOREIGN KEY (company_id, voucher_type_id)
        REFERENCES mc_voucher_type (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_number_series_fy FOREIGN KEY (company_id, fiscal_year_id)
        REFERENCES mc_fiscal_year (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_number_series_starting CHECK (starting_number >= 1),
    CONSTRAINT chk_mc_number_series_next CHECK (next_number BETWEEN 1 AND 1000000000000),
    CONSTRAINT chk_mc_number_series_width CHECK (min_width BETWEEN 1 AND 16),
    CONSTRAINT chk_mc_number_series_periodicity CHECK (
        restart_periodicity IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY', 'NEVER')
    ),
    CONSTRAINT chk_mc_number_series_version_no CHECK (version_no >= 1)
);

CREATE UNIQUE INDEX uq_mc_number_series_scope
    ON mc_number_series (
        company_id,
        voucher_type_id,
        COALESCE(fiscal_year_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(gst_registration_id, '00000000-0000-0000-0000-000000000000'::uuid),
        lower(series_name)
    );

ALTER TABLE mc_number_series ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_number_series FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_number_series_tenant_isolation ON mc_number_series
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_unused_voucher_number (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    number_series_id uuid NOT NULL,
    voucher_type_id uuid NOT NULL,
    voucher_number varchar(32) NOT NULL,
    numeric_seq bigint,
    released_from_journal_id uuid,
    released_reason varchar(120),
    reused_by_journal_id uuid,
    status varchar(24) NOT NULL DEFAULT 'AVAILABLE',
    released_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_unused_voucher_number PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_unused_voucher_number_id UNIQUE (id),
    CONSTRAINT fk_mc_unused_vch_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_unused_vch_series FOREIGN KEY (company_id, number_series_id)
        REFERENCES mc_number_series (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_unused_vch_type FOREIGN KEY (company_id, voucher_type_id)
        REFERENCES mc_voucher_type (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_unused_vch_status CHECK (
        status IN ('AVAILABLE', 'REUSED', 'CANCELLED_FOR_GSTR1')
    )
);

CREATE INDEX idx_mc_unused_vch_available
    ON mc_unused_voucher_number (company_id, number_series_id, status, numeric_seq);

ALTER TABLE mc_unused_voucher_number ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_unused_voucher_number FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_unused_voucher_number_tenant_isolation ON mc_unused_voucher_number
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 6. Commercial & Operational Journal Header (mc_journal)
CREATE TABLE mc_journal (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    period_id uuid NOT NULL,
    fiscal_year_id uuid,
    voucher_type_id uuid NOT NULL,
    voucher_class_id uuid,
    number_series_id uuid,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    posting_nature varchar(24) NOT NULL DEFAULT 'NORMAL',
    persisted_view varchar(24) NOT NULL DEFAULT 'AS_VOUCHER',
    approval_policy varchar(16) NOT NULL DEFAULT 'SINGLE_STEP',
    voucher_number varchar(32),
    voucher_seq_no bigint,
    post_seq bigint,
    supplier_invoice_no varchar(64),
    supplier_invoice_date date,
    voucher_date date NOT NULL,
    effective_date date NOT NULL,
    reversing_applicable_upto date,
    party_ledger_id uuid,
    accounting_ledger_id uuid,
    place_of_supply_state varchar(4),
    company_gstin_id uuid,
    party_gstin varchar(15),
    party_mailing_name varchar(200),
    party_state_code varchar(4),
    is_reverse_charge boolean NOT NULL DEFAULT false,
    export_type varchar(16),
    shipping_bill_no varchar(16),
    shipping_bill_date date,
    shipping_port_code varchar(10),
    currency_code char(3) NOT NULL DEFAULT 'INR',
    exchange_rate numeric NOT NULL DEFAULT 1,
    total_debit numeric NOT NULL DEFAULT 0,
    total_credit numeric NOT NULL DEFAULT 0,
    narration text NOT NULL DEFAULT '',
    narration_gcm bytea,
    reversal_of_journal_id uuid,
    origin varchar(16) NOT NULL DEFAULT 'LIVE',
    source_guid varchar(128),
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    current_posting_batch_no integer NOT NULL DEFAULT 0,
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid NOT NULL,
    updated_by uuid,
    approved_by uuid,
    posted_by uuid,
    cancelled_by uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    approved_at timestamptz(6),
    posted_at timestamptz(6),
    cancelled_at timestamptz(6),
    CONSTRAINT pk_mc_journal PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_journal_id UNIQUE (id),
    CONSTRAINT fk_mc_journal_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_period FOREIGN KEY (company_id, period_id)
        REFERENCES mc_period (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_fy FOREIGN KEY (company_id, fiscal_year_id)
        REFERENCES mc_fiscal_year (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_vch_type FOREIGN KEY (company_id, voucher_type_id)
        REFERENCES mc_voucher_type (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_vch_class FOREIGN KEY (company_id, voucher_class_id)
        REFERENCES mc_voucher_class (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_series FOREIGN KEY (company_id, number_series_id)
        REFERENCES mc_number_series (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_party_ledger FOREIGN KEY (company_id, party_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_acct_ledger FOREIGN KEY (company_id, accounting_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_reversal_of FOREIGN KEY (company_id, reversal_of_journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_created_by FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_updated_by FOREIGN KEY (updated_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_approved_by FOREIGN KEY (approved_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_posted_by FOREIGN KEY (posted_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_cancelled_by FOREIGN KEY (cancelled_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_journal_status CHECK (
        status IN ('DRAFT', 'APPROVED', 'POSTED', 'REVERSED', 'CANCELLED')
    ),
    CONSTRAINT chk_mc_journal_posting_nature CHECK (
        posting_nature IN ('NORMAL', 'OPTIONAL', 'MEMORANDUM', 'REVERSING_JOURNAL')
    ),
    CONSTRAINT chk_mc_journal_persisted_view CHECK (
        persisted_view IN ('ITEM_INVOICE', 'ACCOUNTING_INVOICE', 'AS_VOUCHER', 'SINGLE_ENTRY')
    ),
    CONSTRAINT chk_mc_journal_approval_policy CHECK (
        approval_policy IN ('SINGLE_STEP', 'FOUR_EYES')
    ),
    CONSTRAINT chk_mc_journal_four_eyes CHECK (
        approval_policy <> 'FOUR_EYES'
        OR status NOT IN ('APPROVED', 'POSTED')
        OR (approved_by IS NOT NULL AND approved_by <> created_by)
    ),
    CONSTRAINT chk_mc_journal_export_type CHECK (
        export_type IS NULL
        OR export_type IN ('WPAY', 'WOPAY', 'SEZ_WPAY', 'SEZ_WOPAY', 'DEEMED_EXPORT')
    ),
    CONSTRAINT chk_mc_journal_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_mc_journal_exchange_rate CHECK (
        exchange_rate > 0
        AND exchange_rate < 1000000000000000000
        AND exchange_rate = trunc(exchange_rate, 6)
    ),
    CONSTRAINT chk_mc_journal_total_debit CHECK (
        total_debit >= 0
        AND total_debit < 1000000000000000000
        AND total_debit = trunc(total_debit, 6)
    ),
    CONSTRAINT chk_mc_journal_total_credit CHECK (
        total_credit >= 0
        AND total_credit < 1000000000000000000
        AND total_credit = trunc(total_credit, 6)
    ),
    CONSTRAINT chk_mc_journal_origin CHECK (
        origin IN ('LIVE', 'MIGRATION', 'IMPORT_XML', 'IMPORT_JSONEX', 'IMPORT_EXCEL', 'OCR_STAGED')
    ),
    CONSTRAINT chk_mc_journal_seq CHECK (
        master_seq_id >= 1
        AND alter_id >= 1
        AND current_posting_batch_no >= 0
        AND version_no >= 1
    )
);

CREATE INDEX idx_mc_journal_daybook ON mc_journal (company_id, voucher_date, post_seq, id);
CREATE INDEX idx_mc_journal_period_status ON mc_journal (company_id, period_id, status, posting_nature);
CREATE INDEX idx_mc_journal_vch_type_number ON mc_journal (company_id, voucher_type_id, voucher_number);
CREATE INDEX idx_mc_journal_party ON mc_journal (company_id, party_ledger_id, voucher_date)
    WHERE party_ledger_id IS NOT NULL;
CREATE INDEX idx_mc_journal_trgm_vch_no ON mc_journal USING gin (voucher_number gin_trgm_ops)
    WHERE voucher_number IS NOT NULL;
CREATE INDEX idx_mc_journal_trgm_supp_inv ON mc_journal USING gin (supplier_invoice_no gin_trgm_ops)
    WHERE supplier_invoice_no IS NOT NULL;
CREATE INDEX idx_mc_journal_trgm_narration ON mc_journal USING gin (narration gin_trgm_ops);

ALTER TABLE mc_journal ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_journal FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_journal_tenant_isolation ON mc_journal
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 7. Commercial Presentation Journal Lines (mc_journal_line)
CREATE TABLE mc_journal_line (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    line_no integer NOT NULL,
    line_role varchar(24) NOT NULL DEFAULT 'LEDGER',
    ledger_id uuid,
    side char(2) NOT NULL,
    amount numeric NOT NULL DEFAULT 0,
    forex_currency_code char(3),
    forex_amount numeric,
    forex_rate numeric,
    rate_pct numeric,
    computed_tax_amount numeric,
    is_gst_overridden boolean NOT NULL DEFAULT false,
    gst_override_reason varchar(255),
    line_narration text NOT NULL DEFAULT '',
    CONSTRAINT pk_mc_journal_line PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_journal_line_id UNIQUE (id),
    CONSTRAINT uq_mc_journal_line_seq UNIQUE (company_id, journal_id, line_no),
    CONSTRAINT fk_mc_journal_line_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_line_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_journal_line_ledger FOREIGN KEY (company_id, ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_journal_line_no CHECK (line_no >= 1),
    CONSTRAINT chk_mc_journal_line_role CHECK (
        line_role IN (
            'PARTY',
            'ACCOUNTING',
            'LEDGER',
            'ITEM_ACCOUNTING',
            'TAX',
            'DISCOUNT',
            'ADDITIONAL_CHARGE',
            'ROUND_OFF'
        )
    ),
    CONSTRAINT chk_mc_journal_line_side CHECK (side IN ('DR', 'CR')),
    CONSTRAINT chk_mc_journal_line_amount CHECK (
        amount >= 0
        AND amount < 1000000000000000000
        AND amount = trunc(amount, 6)
    ),
    CONSTRAINT chk_mc_journal_line_forex_ccy CHECK (
        forex_currency_code IS NULL OR forex_currency_code ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT chk_mc_journal_line_forex_amt CHECK (
        forex_amount IS NULL
        OR (
            forex_amount >= 0
            AND forex_amount < 1000000000000000000
            AND forex_amount = trunc(forex_amount, 6)
        )
    ),
    CONSTRAINT chk_mc_journal_line_forex_rate CHECK (
        forex_rate IS NULL
        OR (
            forex_rate > 0
            AND forex_rate < 1000000000000000000
            AND forex_rate = trunc(forex_rate, 6)
        )
    ),
    CONSTRAINT chk_mc_journal_line_rate_pct CHECK (
        rate_pct IS NULL
        OR (abs(rate_pct) <= 1000 AND rate_pct = trunc(rate_pct, 6))
    ),
    CONSTRAINT chk_mc_journal_line_computed_tax CHECK (
        computed_tax_amount IS NULL
        OR (
            computed_tax_amount >= 0
            AND computed_tax_amount < 1000000000000000000
            AND computed_tax_amount = trunc(computed_tax_amount, 6)
        )
    )
);

CREATE INDEX idx_mc_journal_line_journal ON mc_journal_line (company_id, journal_id, line_no);
CREATE INDEX idx_mc_journal_line_ledger ON mc_journal_line (company_id, ledger_id)
    WHERE ledger_id IS NOT NULL;

ALTER TABLE mc_journal_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_journal_line FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_journal_line_tenant_isolation ON mc_journal_line
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 8. Synchronous Period Rollup Acceleration Table (mc_ledger_period_bal)
CREATE TABLE mc_ledger_period_bal (
    company_id uuid NOT NULL,
    period_id uuid NOT NULL,
    ledger_id uuid NOT NULL,
    dr_total numeric NOT NULL DEFAULT 0,
    cr_total numeric NOT NULL DEFAULT 0,
    entry_count bigint NOT NULL DEFAULT 0,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_ledger_period_bal PRIMARY KEY (company_id, period_id, ledger_id),
    CONSTRAINT fk_mc_ledger_period_bal_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_ledger_period_bal_period FOREIGN KEY (company_id, period_id)
        REFERENCES mc_period (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_ledger_period_bal_ledger FOREIGN KEY (company_id, ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_ledger_period_bal_dr CHECK (
        dr_total >= 0
        AND dr_total < 1000000000000000000
        AND dr_total = trunc(dr_total, 6)
    ),
    CONSTRAINT chk_mc_ledger_period_bal_cr CHECK (
        cr_total >= 0
        AND cr_total < 1000000000000000000
        AND cr_total = trunc(cr_total, 6)
    ),
    CONSTRAINT chk_mc_ledger_period_bal_cnt CHECK (entry_count >= 0)
);

CREATE INDEX idx_mc_ledger_period_bal_ledger ON mc_ledger_period_bal (company_id, ledger_id, period_id);

ALTER TABLE mc_ledger_period_bal ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_ledger_period_bal FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_ledger_period_bal_tenant_isolation ON mc_ledger_period_bal
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 9. Immutable Double-Entry General Ledger (mc_entry)
CREATE TABLE mc_entry (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    journal_line_id uuid,
    period_id uuid NOT NULL,
    entry_date date NOT NULL,
    ledger_id uuid NOT NULL,
    side char(2) NOT NULL,
    amount numeric NOT NULL,
    base_currency_amount numeric NOT NULL,
    forex_currency_code char(3),
    forex_amount numeric,
    forex_rate numeric,
    posting_batch_no integer NOT NULL DEFAULT 1,
    is_reversal boolean NOT NULL DEFAULT false,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_entry PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_entry_id UNIQUE (id),
    CONSTRAINT fk_mc_entry_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_entry_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_entry_journal_line FOREIGN KEY (company_id, journal_line_id)
        REFERENCES mc_journal_line (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_entry_period FOREIGN KEY (company_id, period_id)
        REFERENCES mc_period (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_entry_ledger FOREIGN KEY (company_id, ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_entry_side CHECK (side IN ('DR', 'CR')),
    CONSTRAINT chk_mc_entry_amount_exact_numeric CHECK (
        amount >= 0
        AND amount < 1000000000000000000
        AND amount = trunc(amount, 6)
        AND amount > 0
    ),
    CONSTRAINT chk_mc_entry_base_amount_exact_numeric CHECK (
        base_currency_amount >= 0
        AND base_currency_amount < 1000000000000000000
        AND base_currency_amount = trunc(base_currency_amount, 6)
        AND base_currency_amount = amount
    ),
    CONSTRAINT chk_mc_entry_forex_ccy CHECK (
        forex_currency_code IS NULL OR forex_currency_code ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT chk_mc_entry_forex_amt CHECK (
        forex_amount IS NULL
        OR (
            forex_amount >= 0
            AND forex_amount < 1000000000000000000
            AND forex_amount = trunc(forex_amount, 6)
        )
    ),
    CONSTRAINT chk_mc_entry_forex_rate CHECK (
        forex_rate IS NULL
        OR (
            forex_rate > 0
            AND forex_rate < 1000000000000000000
            AND forex_rate = trunc(forex_rate, 6)
        )
    ),
    CONSTRAINT chk_mc_entry_batch_no CHECK (posting_batch_no >= 1)
);

CREATE INDEX idx_mc_entry_journal ON mc_entry (company_id, journal_id, posting_batch_no);
CREATE INDEX idx_mc_entry_ledger_register ON mc_entry (company_id, ledger_id, entry_date, id);
CREATE INDEX idx_mc_entry_period_ledger ON mc_entry (company_id, period_id, ledger_id);

-- 9A. Strict Append-Only Immutability Trigger on mc_entry
CREATE TRIGGER trg_mc_entry_immutable
BEFORE UPDATE OR DELETE ON mc_entry
FOR EACH ROW
EXECUTE FUNCTION fn_mc_forbid_append_only_mutation();

-- 9B. Per-Company Currency Scale & Open-Period Guard on mc_entry INSERT
CREATE OR REPLACE FUNCTION fn_mc_entry_company_scale_and_period_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_scale smallint;
    v_period_state varchar(16);
    v_period_start date;
    v_period_end date;
BEGIN
    IF NEW.base_currency_amount IS NULL THEN
        NEW.base_currency_amount := NEW.amount;
    END IF;

    SELECT c.currency_scale
      INTO v_scale
      FROM mc_company c
     WHERE c.id = NEW.company_id;

    IF v_scale IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            MESSAGE = 'MC-ACC-COMPANY-NOT-FOUND: owning mc_company does not exist';
    END IF;

    IF NEW.amount <> trunc(NEW.amount, v_scale) THEN
        RAISE EXCEPTION USING
            ERRCODE = '22003',
            MESSAGE = format(
                'MC-ACC-SCALE-OVERFLOW: mc_entry.amount (%s) exceeds company currency_scale (%s)',
                NEW.amount,
                v_scale
            );
    END IF;

    SELECT p.state, p.start_date, p.end_date
      INTO v_period_state, v_period_start, v_period_end
      FROM mc_period p
     WHERE p.company_id = NEW.company_id
       AND p.id = NEW.period_id;

    IF v_period_state IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            MESSAGE = 'MC-ACC-PERIOD-NOT-FOUND: owning mc_period does not exist';
    END IF;

    IF v_period_state <> 'OPEN' THEN
        RAISE EXCEPTION USING
            ERRCODE = '55000',
            MESSAGE = format('MC-ACC-PERIOD-NOT-OPEN: cannot post mc_entry into %s period', v_period_state);
    END IF;

    IF NEW.entry_date < v_period_start OR NEW.entry_date > v_period_end THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'MC-ACC-ENTRY-DATE-OUT-OF-PERIOD: mc_entry.entry_date lies outside mc_period date range';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_entry_company_scale
BEFORE INSERT ON mc_entry
FOR EACH ROW
EXECUTE FUNCTION fn_mc_entry_company_scale_and_period_guard();

-- 9C. Synchronous Period Balance Rollup Trigger on mc_entry INSERT
CREATE OR REPLACE FUNCTION fn_mc_entry_sync_period_bal()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO mc_ledger_period_bal (
        company_id,
        period_id,
        ledger_id,
        dr_total,
        cr_total,
        entry_count,
        updated_at
    )
    VALUES (
        NEW.company_id,
        NEW.period_id,
        NEW.ledger_id,
        CASE WHEN NEW.side = 'DR' THEN NEW.amount ELSE 0 END,
        CASE WHEN NEW.side = 'CR' THEN NEW.amount ELSE 0 END,
        1,
        clock_timestamp()
    )
    ON CONFLICT (company_id, period_id, ledger_id) DO UPDATE
        SET dr_total = mc_ledger_period_bal.dr_total
                + CASE WHEN EXCLUDED.dr_total > 0 THEN EXCLUDED.dr_total ELSE 0 END,
            cr_total = mc_ledger_period_bal.cr_total
                + CASE WHEN EXCLUDED.cr_total > 0 THEN EXCLUDED.cr_total ELSE 0 END,
            entry_count = mc_ledger_period_bal.entry_count + 1,
            updated_at = clock_timestamp();

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_entry_sync_period_bal
AFTER INSERT ON mc_entry
FOR EACH ROW
EXECUTE FUNCTION fn_mc_entry_sync_period_bal();

-- 9D. Deferred Double-Entry Ledger Integrity Constraint Trigger
CREATE OR REPLACE FUNCTION fn_assert_journal_balanced()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_company_id uuid;
    v_journal_id uuid;
    v_status varchar(16);
    v_posting_nature varchar(24);
    v_period_id uuid;
    v_period_state varchar(16);
    v_posts_entries boolean;
    v_entry_count bigint;
    v_dr_sum numeric;
    v_cr_sum numeric;
    v_unzeroed_ledgers bigint;
BEGIN
    IF TG_TABLE_NAME = 'mc_entry' THEN
        v_company_id := COALESCE(NEW.company_id, OLD.company_id);
        v_journal_id := COALESCE(NEW.journal_id, OLD.journal_id);
    ELSE
        v_company_id := NEW.company_id;
        v_journal_id := NEW.id;
    END IF;

    SELECT j.status, j.posting_nature, j.period_id, vt.posts_accounting_entries
      INTO v_status, v_posting_nature, v_period_id, v_posts_entries
      FROM mc_journal j
      JOIN mc_voucher_type vt
        ON vt.company_id = j.company_id
       AND vt.id = j.voucher_type_id
     WHERE j.company_id = v_company_id
       AND j.id = v_journal_id;

    IF v_status IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            MESSAGE = 'MC-ACC-JOURNAL-MISSING: parent mc_journal does not exist for mc_entry';
    END IF;

    SELECT COUNT(*),
           COALESCE(SUM(CASE WHEN e.side = 'DR' THEN e.amount ELSE 0 END), 0),
           COALESCE(SUM(CASE WHEN e.side = 'CR' THEN e.amount ELSE 0 END), 0)
      INTO v_entry_count, v_dr_sum, v_cr_sum
      FROM mc_entry e
     WHERE e.company_id = v_company_id
       AND e.journal_id = v_journal_id;

    -- Non-posting states & exception voucher natures
    IF v_status IN ('DRAFT', 'APPROVED') OR v_posting_nature IN ('MEMORANDUM', 'REVERSING_JOURNAL') OR v_posts_entries = false THEN
        IF v_entry_count > 0 THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = format(
                    'MC-ACC-NONPOSTING-HAS-ENTRIES: journal %s (status=%s, nature=%s, posts=%s) must not hold GL entries',
                    v_journal_id,
                    v_status,
                    v_posting_nature,
                    v_posts_entries
                );
        END IF;
        RETURN NULL;
    END IF;

    -- OPTIONAL vouchers: either never posted (0 entries) or compensated to 0 net per ledger
    IF v_posting_nature = 'OPTIONAL' THEN
        IF v_entry_count > 0 THEN
            IF v_entry_count < 2 OR v_dr_sum <> v_cr_sum THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    MESSAGE = 'MC-ACC-UNBALANCED-OPTIONAL: optional voucher compensation entries are unbalanced';
            END IF;

            SELECT COUNT(*)
              INTO v_unzeroed_ledgers
              FROM (
                  SELECT e.ledger_id
                    FROM mc_entry e
                   WHERE e.company_id = v_company_id
                     AND e.journal_id = v_journal_id
                   GROUP BY e.ledger_id
                  HAVING SUM(CASE WHEN e.side = 'DR' THEN e.amount ELSE -e.amount END) <> 0
              ) residual;

            IF v_unzeroed_ledgers > 0 THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    MESSAGE = 'MC-ACC-OPTIONAL-NONZERO-GL: optional voucher converted from posted must net to zero on every ledger';
            END IF;
        END IF;
        RETURN NULL;
    END IF;

    -- CANCELLED vouchers (Alt+X): retains header/voucher_number for GSTR-1 Table 13 while GL impact nets to 0
    IF v_status = 'CANCELLED' THEN
        IF v_entry_count > 0 THEN
            IF v_entry_count < 2 OR v_dr_sum <> v_cr_sum THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    MESSAGE = 'MC-ACC-UNBALANCED-CANCEL: cancelled journal compensating entries do not balance';
            END IF;

            SELECT COUNT(*)
              INTO v_unzeroed_ledgers
              FROM (
                  SELECT e.ledger_id
                    FROM mc_entry e
                   WHERE e.company_id = v_company_id
                     AND e.journal_id = v_journal_id
                   GROUP BY e.ledger_id
                  HAVING SUM(CASE WHEN e.side = 'DR' THEN e.amount ELSE -e.amount END) <> 0
              ) residual;

            IF v_unzeroed_ledgers > 0 THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    MESSAGE = 'MC-ACC-CANCEL-NONZERO-GL: cancelled journal must have zero net GL balance across every ledger';
            END IF;
        END IF;
        RETURN NULL;
    END IF;

    -- REVERSED vouchers: original balanced entries remain intact
    IF v_status = 'REVERSED' THEN
        IF v_entry_count < 2 OR v_dr_sum <> v_cr_sum OR v_dr_sum <= 0 THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'MC-ACC-UNBALANCED-REVERSED: reversed journal entries violate double-entry balance';
        END IF;
        RETURN NULL;
    END IF;

    -- POSTED + NORMAL accounting voucher: enforce strict double-entry & OPEN period
    IF v_status = 'POSTED' AND v_posting_nature = 'NORMAL' AND v_posts_entries = true THEN
        SELECT p.state
          INTO v_period_state
          FROM mc_period p
         WHERE p.company_id = v_company_id
           AND p.id = v_period_id;

        IF v_period_state IS DISTINCT FROM 'OPEN' THEN
            RAISE EXCEPTION USING
                ERRCODE = '55000',
                MESSAGE = format('MC-ACC-PERIOD-NOT-OPEN: posted journal period state is %s (expected OPEN)', v_period_state);
        END IF;

        IF v_entry_count < 2 THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = format('MC-ACC-MIN-TWO-ENTRIES: posted journal %s has %s entries (minimum 2 required)', v_journal_id, v_entry_count);
        END IF;

        IF v_dr_sum <> v_cr_sum THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = format('MC-ACC-UNBALANCED-JOURNAL: journal %s DR (%s) <> CR (%s)', v_journal_id, v_dr_sum, v_cr_sum);
        END IF;

        IF v_dr_sum <= 0 THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = format('MC-ACC-ZERO-SUM-JOURNAL: journal %s total DR/CR must be strictly > 0', v_journal_id);
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_mc_journal_double_entry_balance
AFTER INSERT OR UPDATE OR DELETE ON mc_entry
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION fn_assert_journal_balanced();

CREATE CONSTRAINT TRIGGER trg_mc_journal_posted_requires_entries
AFTER INSERT OR UPDATE ON mc_journal
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION fn_assert_journal_balanced();

ALTER TABLE mc_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_entry FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_entry_tenant_isolation ON mc_entry
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mc_app_runtime') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON mc_entry FROM mc_app_runtime;
        GRANT SELECT, INSERT ON mc_entry TO mc_app_runtime;
    END IF;
END;
$$;

-- 10. Automatic Company Seed Function: 28 Groups + Cash & P&L + 24 Voucher Types
CREATE OR REPLACE FUNCTION fn_mc_seed_company_accounting_defaults()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_prev_company text;
    v_cid uuid := NEW.id;
    v_ccy char(3) := NEW.base_currency_code;
    v_pol varchar(16) := NEW.default_approval_policy;
    v_g_capital uuid := fn_mc_seed_uuid(v_cid, 'group:Capital Account');
    v_g_loans_liab uuid := fn_mc_seed_uuid(v_cid, 'group:Loans (Liability)');
    v_g_curr_liab uuid := fn_mc_seed_uuid(v_cid, 'group:Current Liabilities');
    v_g_curr_assets uuid := fn_mc_seed_uuid(v_cid, 'group:Current Assets');
    v_g_cash_in_hand uuid := fn_mc_seed_uuid(v_cid, 'group:Cash-in-Hand');
BEGIN
    v_prev_company := current_setting('app.current_company_id', true);
    PERFORM set_config('app.current_company_id', v_cid::text, true);

    -- 15 Primary Accounting Groups
    INSERT INTO mc_account_group (
        id, company_id, name, parent_group_id, nature, affects_gross_profit,
        behaves_like_subledger, schedule_iii_line_code, is_system_group, sort_order, master_seq_id
    )
    VALUES
        (v_g_capital, v_cid, 'Capital Account', NULL, 'LIABILITIES', false, false, 'SCH3_SHARE_CAPITAL', true, 10, 1),
        (v_g_loans_liab, v_cid, 'Loans (Liability)', NULL, 'LIABILITIES', false, false, 'SCH3_BORROWINGS', true, 20, 2),
        (v_g_curr_liab, v_cid, 'Current Liabilities', NULL, 'LIABILITIES', false, false, 'SCH3_CURRENT_LIABILITIES', true, 30, 3),
        (fn_mc_seed_uuid(v_cid, 'group:Fixed Assets'), v_cid, 'Fixed Assets', NULL, 'ASSETS', false, false, 'SCH3_PPE_FIXED_ASSETS', true, 40, 4),
        (fn_mc_seed_uuid(v_cid, 'group:Investments'), v_cid, 'Investments', NULL, 'ASSETS', false, false, 'SCH3_INVESTMENTS', true, 50, 5),
        (v_g_curr_assets, v_cid, 'Current Assets', NULL, 'ASSETS', false, false, 'SCH3_CURRENT_ASSETS', true, 60, 6),
        (fn_mc_seed_uuid(v_cid, 'group:Branch / Divisions'), v_cid, 'Branch / Divisions', NULL, 'LIABILITIES', false, false, 'SCH3_INTER_BRANCH', true, 70, 7),
        (fn_mc_seed_uuid(v_cid, 'group:Misc. Expenses (ASSET)'), v_cid, 'Misc. Expenses (ASSET)', NULL, 'ASSETS', false, false, 'SCH3_OTHER_NON_CURRENT_ASSETS', true, 80, 8),
        (fn_mc_seed_uuid(v_cid, 'group:Suspense A/c'), v_cid, 'Suspense A/c', NULL, 'LIABILITIES', false, false, 'SCH3_SUSPENSE', true, 90, 9),
        (fn_mc_seed_uuid(v_cid, 'group:Sales Accounts'), v_cid, 'Sales Accounts', NULL, 'INCOME', true, false, 'SCH3_REVENUE_FROM_OPERATIONS', true, 100, 10),
        (fn_mc_seed_uuid(v_cid, 'group:Purchase Accounts'), v_cid, 'Purchase Accounts', NULL, 'EXPENSES', true, false, 'SCH3_PURCHASES_STOCK_IN_TRADE', true, 110, 11),
        (fn_mc_seed_uuid(v_cid, 'group:Direct Incomes'), v_cid, 'Direct Incomes', NULL, 'INCOME', true, false, 'SCH3_OTHER_OPERATING_REVENUE', true, 120, 12),
        (fn_mc_seed_uuid(v_cid, 'group:Direct Expenses'), v_cid, 'Direct Expenses', NULL, 'EXPENSES', true, false, 'SCH3_DIRECT_EXPENSES', true, 130, 13),
        (fn_mc_seed_uuid(v_cid, 'group:Indirect Incomes'), v_cid, 'Indirect Incomes', NULL, 'INCOME', false, false, 'SCH3_OTHER_INCOME', true, 140, 14),
        (fn_mc_seed_uuid(v_cid, 'group:Indirect Expenses'), v_cid, 'Indirect Expenses', NULL, 'EXPENSES', false, false, 'SCH3_OTHER_EXPENSES', true, 150, 15)
    ON CONFLICT (company_id, lower(name)) DO NOTHING;

    -- 13 Predefined Sub-Groups
    INSERT INTO mc_account_group (
        id, company_id, name, parent_group_id, nature, affects_gross_profit,
        behaves_like_subledger, schedule_iii_line_code, is_system_group, sort_order, master_seq_id
    )
    VALUES
        (fn_mc_seed_uuid(v_cid, 'group:Reserves & Surplus'), v_cid, 'Reserves & Surplus', v_g_capital, 'LIABILITIES', false, false, 'SCH3_RESERVES_AND_SURPLUS', true, 16, 16),
        (fn_mc_seed_uuid(v_cid, 'group:Bank OD A/c'), v_cid, 'Bank OD A/c', v_g_loans_liab, 'LIABILITIES', false, false, 'SCH3_SHORT_TERM_BORROWINGS', true, 21, 17),
        (fn_mc_seed_uuid(v_cid, 'group:Secured Loans'), v_cid, 'Secured Loans', v_g_loans_liab, 'LIABILITIES', false, false, 'SCH3_SECURED_LOANS', true, 22, 18),
        (fn_mc_seed_uuid(v_cid, 'group:Unsecured Loans'), v_cid, 'Unsecured Loans', v_g_loans_liab, 'LIABILITIES', false, false, 'SCH3_UNSECURED_LOANS', true, 23, 19),
        (fn_mc_seed_uuid(v_cid, 'group:Duties & Taxes'), v_cid, 'Duties & Taxes', v_g_curr_liab, 'LIABILITIES', false, false, 'SCH3_STATUTORY_DUES', true, 31, 20),
        (fn_mc_seed_uuid(v_cid, 'group:Provisions'), v_cid, 'Provisions', v_g_curr_liab, 'LIABILITIES', false, false, 'SCH3_PROVISIONS', true, 32, 21),
        (fn_mc_seed_uuid(v_cid, 'group:Sundry Creditors'), v_cid, 'Sundry Creditors', v_g_curr_liab, 'LIABILITIES', false, true, 'SCH3_TRADE_PAYABLES', true, 33, 22),
        (fn_mc_seed_uuid(v_cid, 'group:Stock-in-Hand'), v_cid, 'Stock-in-Hand', v_g_curr_assets, 'ASSETS', false, false, 'SCH3_INVENTORIES', true, 61, 23),
        (fn_mc_seed_uuid(v_cid, 'group:Deposits (Asset)'), v_cid, 'Deposits (Asset)', v_g_curr_assets, 'ASSETS', false, false, 'SCH3_SECURITY_DEPOSITS', true, 62, 24),
        (fn_mc_seed_uuid(v_cid, 'group:Loans & Advances (Asset)'), v_cid, 'Loans & Advances (Asset)', v_g_curr_assets, 'ASSETS', false, false, 'SCH3_LOANS_AND_ADVANCES', true, 63, 25),
        (fn_mc_seed_uuid(v_cid, 'group:Sundry Debtors'), v_cid, 'Sundry Debtors', v_g_curr_assets, 'ASSETS', false, true, 'SCH3_TRADE_RECEIVABLES', true, 64, 26),
        (v_g_cash_in_hand, v_cid, 'Cash-in-Hand', v_g_curr_assets, 'ASSETS', false, false, 'SCH3_CASH_AND_CASH_EQUIVALENTS', true, 65, 27),
        (fn_mc_seed_uuid(v_cid, 'group:Bank Accounts'), v_cid, 'Bank Accounts', v_g_curr_assets, 'ASSETS', false, false, 'SCH3_BANK_BALANCES', true, 66, 28)
    ON CONFLICT (company_id, lower(name)) DO NOTHING;

    -- 2 Canonical System Ledgers (Cash + Profit & Loss A/c)
    INSERT INTO mc_ledger (
        id, company_id, name, group_id, opening_balance, opening_side,
        currency_code, is_system_ledger, active, master_seq_id
    )
    VALUES
        (fn_mc_seed_uuid(v_cid, 'ledger:Cash'), v_cid, 'Cash', v_g_cash_in_hand, 0, 'DR', v_ccy, true, true, 29),
        (fn_mc_seed_uuid(v_cid, 'ledger:Profit & Loss A/c'), v_cid, 'Profit & Loss A/c', NULL, 0, 'CR', v_ccy, true, true, 30)
    ON CONFLICT (company_id, lower(name)) DO NOTHING;

    -- 24 Predefined Voucher Types
    INSERT INTO mc_voucher_type (
        id, company_id, name, abbreviation, base_category,
        posts_accounting_entries, moves_inventory, default_entry_mode,
        numbering_method, approval_policy, shortcut_key, is_system_voucher_type, master_seq_id
    )
    VALUES
        (fn_mc_seed_uuid(v_cid, 'vchtype:Contra'), v_cid, 'Contra', 'Ctr', 'CONTRA', true, false, 'SINGLE_ENTRY', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'F4', true, 31),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Payment'), v_cid, 'Payment', 'Pymt', 'PAYMENT', true, false, 'SINGLE_ENTRY', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'F5', true, 32),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Receipt'), v_cid, 'Receipt', 'Rcpt', 'RECEIPT', true, false, 'SINGLE_ENTRY', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'F6', true, 33),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Journal'), v_cid, 'Journal', 'Jrnl', 'JOURNAL', true, false, 'AS_VOUCHER', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'F7', true, 34),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Sales'), v_cid, 'Sales', 'Sale', 'SALES', true, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'F8', true, 35),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Purchase'), v_cid, 'Purchase', 'Purc', 'PURCHASE', true, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'F9', true, 36),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Credit Note'), v_cid, 'Credit Note', 'C/Note', 'CREDIT_NOTE', true, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Alt+F6', true, 37),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Debit Note'), v_cid, 'Debit Note', 'D/Note', 'DEBIT_NOTE', true, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Alt+F5', true, 38),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Memorandum'), v_cid, 'Memorandum', 'Memo', 'MEMORANDUM', false, false, 'AS_VOUCHER', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Ctrl+F9', true, 39),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Reversing Journal'), v_cid, 'Reversing Journal', 'RevJrnl', 'REVERSING_JOURNAL', false, false, 'AS_VOUCHER', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'F10', true, 40),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Sales Order'), v_cid, 'Sales Order', 'SO', 'SALES_ORDER', false, false, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Ctrl+F8', true, 41),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Purchase Order'), v_cid, 'Purchase Order', 'PO', 'PURCHASE_ORDER', false, false, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Ctrl+F9', true, 42),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Job Work Out Order'), v_cid, 'Job Work Out Order', 'JWOut', 'JOB_WORK_OUT_ORDER', false, false, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Ctrl+J', true, 43),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Job Work In Order'), v_cid, 'Job Work In Order', 'JWIn', 'JOB_WORK_IN_ORDER', false, false, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Alt+J', true, 44),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Delivery Note'), v_cid, 'Delivery Note', 'DelyNote', 'DELIVERY_NOTE', false, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Alt+F8', true, 45),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Receipt Note'), v_cid, 'Receipt Note', 'RcptNote', 'RECEIPT_NOTE', false, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Alt+F9', true, 46),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Rejections In'), v_cid, 'Rejections In', 'RejIn', 'REJECTIONS_IN', false, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Ctrl+F6', true, 47),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Rejections Out'), v_cid, 'Rejections Out', 'RejOut', 'REJECTIONS_OUT', false, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Ctrl+F5', true, 48),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Stock Journal'), v_cid, 'Stock Journal', 'StkJrnl', 'STOCK_JOURNAL', false, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Alt+F7', true, 49),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Physical Stock'), v_cid, 'Physical Stock', 'PhyStk', 'PHYSICAL_STOCK', false, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Ctrl+F7', true, 50),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Material In'), v_cid, 'Material In', 'MatIn', 'MATERIAL_IN', false, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, NULL, true, 51),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Material Out'), v_cid, 'Material Out', 'MatOut', 'MATERIAL_OUT', false, true, 'ITEM_INVOICE', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, NULL, true, 52),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Attendance'), v_cid, 'Attendance', 'Attd', 'ATTENDANCE', false, false, 'AS_VOUCHER', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, NULL, true, 53),
        (fn_mc_seed_uuid(v_cid, 'vchtype:Payroll'), v_cid, 'Payroll', 'Pyrl', 'PAYROLL', true, false, 'AS_VOUCHER', 'AUTOMATIC_RETAIN_NUMBERS', v_pol, 'Ctrl+F4', true, 54)
    ON CONFLICT (company_id, lower(name)) DO NOTHING;

    UPDATE mc_company
       SET next_master_seq_id = GREATEST(next_master_seq_id, 60)
     WHERE id = v_cid;

    PERFORM set_config('app.current_company_id', COALESCE(v_prev_company, ''), true);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_company_seed_coa_and_vouchers
AFTER INSERT ON mc_company
FOR EACH ROW
EXECUTE FUNCTION fn_mc_seed_company_accounting_defaults();
