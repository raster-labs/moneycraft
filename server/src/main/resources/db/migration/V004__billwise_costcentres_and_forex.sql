SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Currencies, Forex Rates, Cost Categories/Centres &
-- Bill-Wise Sub-Ledger Allocations (NEW_REF, AGST_REF, ADVANCE, ON_ACCOUNT)
-- Stream: db/migration/V004__billwise_costcentres_and_forex.sql
-- ============================================================================

-- 1. Multi-Currency Master
CREATE TABLE mc_currency (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    iso_code char(3) NOT NULL,
    symbol varchar(8) NOT NULL,
    formal_name varchar(80) NOT NULL,
    decimal_places smallint NOT NULL DEFAULT 2,
    decimal_symbol varchar(24) NOT NULL DEFAULT 'paise',
    show_amount_in_millions boolean NOT NULL DEFAULT false,
    suffix_symbol_to_amount boolean NOT NULL DEFAULT false,
    add_space_between_amount_and_symbol boolean NOT NULL DEFAULT true,
    is_base_currency boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_currency PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_currency_id UNIQUE (id),
    CONSTRAINT uq_mc_currency_company_iso UNIQUE (company_id, iso_code),
    CONSTRAINT fk_mc_currency_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_currency_iso CHECK (iso_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_mc_currency_symbol CHECK (length(trim(symbol)) > 0),
    CONSTRAINT chk_mc_currency_formal CHECK (length(trim(formal_name)) > 0),
    CONSTRAINT chk_mc_currency_decimals CHECK (decimal_places BETWEEN 0 AND 6),
    CONSTRAINT chk_mc_currency_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

ALTER TABLE mc_currency ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_currency FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_currency_tenant_isolation ON mc_currency
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 2. Date-Effective Forex Rates (Standard, Selling, Buying)
CREATE TABLE mc_forex_rate (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    currency_id uuid NOT NULL,
    effective_date date NOT NULL,
    standard_rate numeric NOT NULL,
    selling_rate numeric,
    buying_rate numeric,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_forex_rate PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_forex_rate_id UNIQUE (id),
    CONSTRAINT uq_mc_forex_rate_ccy_date UNIQUE (company_id, currency_id, effective_date),
    CONSTRAINT fk_mc_forex_rate_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_forex_rate_currency FOREIGN KEY (company_id, currency_id)
        REFERENCES mc_currency (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_forex_rate_std CHECK (
        standard_rate >= 0
        AND standard_rate < 1000000000000000000
        AND standard_rate = trunc(standard_rate, 6)
        AND standard_rate > 0
    ),
    CONSTRAINT chk_mc_forex_rate_sell CHECK (
        selling_rate IS NULL
        OR (
            selling_rate >= 0
            AND selling_rate < 1000000000000000000
            AND selling_rate = trunc(selling_rate, 6)
            AND selling_rate > 0
        )
    ),
    CONSTRAINT chk_mc_forex_rate_buy CHECK (
        buying_rate IS NULL
        OR (
            buying_rate >= 0
            AND buying_rate < 1000000000000000000
            AND buying_rate = trunc(buying_rate, 6)
            AND buying_rate > 0
        )
    ),
    CONSTRAINT chk_mc_forex_rate_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_forex_rate_lookup ON mc_forex_rate (company_id, currency_id, effective_date DESC);

ALTER TABLE mc_forex_rate ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_forex_rate FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_forex_rate_tenant_isolation ON mc_forex_rate
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 3. Parallel Cost Categories
CREATE TABLE mc_cost_category (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    allocate_revenue_items boolean NOT NULL DEFAULT true,
    allocate_non_revenue_items boolean NOT NULL DEFAULT false,
    is_primary_category boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_cost_category PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_cost_category_id UNIQUE (id),
    CONSTRAINT fk_mc_cost_category_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_cost_category_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_cost_category_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_cost_category_company_lower_name
    ON mc_cost_category (company_id, lower(name));

ALTER TABLE mc_cost_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_cost_category FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_cost_category_tenant_isolation ON mc_cost_category
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 4. Hierarchical Cost Centres
CREATE TABLE mc_cost_centre (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    cost_category_id uuid NOT NULL,
    parent_cost_centre_id uuid,
    name varchar(160) NOT NULL,
    alias_code varchar(64),
    tree_path varchar(1024) NOT NULL DEFAULT '',
    depth_level smallint NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_cost_centre PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_cost_centre_id UNIQUE (id),
    CONSTRAINT fk_mc_cost_centre_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_centre_category FOREIGN KEY (company_id, cost_category_id)
        REFERENCES mc_cost_category (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_centre_parent FOREIGN KEY (company_id, parent_cost_centre_id)
        REFERENCES mc_cost_centre (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_cost_centre_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_cost_centre_depth CHECK (depth_level BETWEEN 0 AND 32),
    CONSTRAINT chk_mc_cost_centre_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_cost_centre_cat_lower_name
    ON mc_cost_centre (company_id, cost_category_id, lower(name));

CREATE INDEX idx_mc_cost_centre_trgm_name ON mc_cost_centre USING gin (name gin_trgm_ops);

CREATE OR REPLACE FUNCTION fn_mc_cost_centre_hierarchy_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_cursor_id uuid;
    v_parent_cat_id uuid;
    v_parent_path varchar(1024);
    v_parent_depth smallint;
    v_hops integer := 0;
BEGIN
    IF NEW.parent_cost_centre_id IS NULL THEN
        NEW.depth_level := 0;
        NEW.tree_path := '/' || NEW.id::text || '/';
        RETURN NEW;
    END IF;

    IF NEW.parent_cost_centre_id = NEW.id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'MC-ACC-COSTCENTRE-CYCLE: cost centre cannot be its own parent';
    END IF;

    v_cursor_id := NEW.parent_cost_centre_id;
    WHILE v_cursor_id IS NOT NULL LOOP
        v_hops := v_hops + 1;
        IF v_hops > 32 THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'MC-ACC-COSTCENTRE-DEPTH: cost centre hierarchy exceeds maximum depth 32';
        END IF;
        IF v_cursor_id = NEW.id THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'MC-ACC-COSTCENTRE-CYCLE: cycle detected in mc_cost_centre hierarchy';
        END IF;
        SELECT cc.parent_cost_centre_id
          INTO v_cursor_id
          FROM mc_cost_centre cc
         WHERE cc.company_id = NEW.company_id
           AND cc.id = v_cursor_id;
    END LOOP;

    SELECT cc.cost_category_id, cc.tree_path, cc.depth_level
      INTO v_parent_cat_id, v_parent_path, v_parent_depth
      FROM mc_cost_centre cc
     WHERE cc.company_id = NEW.company_id
       AND cc.id = NEW.parent_cost_centre_id;

    IF v_parent_cat_id IS DISTINCT FROM NEW.cost_category_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'MC-ACC-COSTCENTRE-CAT-MISMATCH: parent cost centre belongs to a different cost category';
    END IF;

    NEW.depth_level := v_parent_depth + 1;
    NEW.tree_path := v_parent_path || NEW.id::text || '/';
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_cost_centre_no_cycle
BEFORE INSERT OR UPDATE ON mc_cost_centre
FOR EACH ROW
EXECUTE FUNCTION fn_mc_cost_centre_hierarchy_guard();

ALTER TABLE mc_cost_centre ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_cost_centre FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_cost_centre_tenant_isolation ON mc_cost_centre
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 5. Bill-Wise Sub-Ledger Allocations (NEW_REF, AGST_REF, ADVANCE, ON_ACCOUNT)
CREATE TABLE mc_bill_allocation (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    journal_line_id uuid NOT NULL,
    ledger_id uuid NOT NULL,
    allocation_type varchar(16) NOT NULL,
    bill_name varchar(120) NOT NULL,
    credit_period_days integer,
    due_date date,
    side char(2) NOT NULL,
    amount numeric NOT NULL,
    forex_currency_code char(3),
    forex_amount numeric,
    forex_rate numeric,
    unadjusted_forex_delta numeric NOT NULL DEFAULT 0,
    forex_swept_journal_id uuid,
    allow_over_settlement boolean NOT NULL DEFAULT false,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_bill_allocation PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_bill_allocation_id UNIQUE (id),
    CONSTRAINT fk_mc_bill_allocation_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bill_allocation_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bill_allocation_line FOREIGN KEY (company_id, journal_line_id)
        REFERENCES mc_journal_line (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bill_allocation_ledger FOREIGN KEY (company_id, ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bill_allocation_forex_sweep FOREIGN KEY (company_id, forex_swept_journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_bill_allocation_type CHECK (
        allocation_type IN ('NEW_REF', 'AGST_REF', 'ADVANCE', 'ON_ACCOUNT')
    ),
    CONSTRAINT chk_mc_bill_allocation_name CHECK (length(trim(bill_name)) > 0),
    CONSTRAINT chk_mc_bill_allocation_credit_days CHECK (
        credit_period_days IS NULL OR credit_period_days >= 0
    ),
    CONSTRAINT chk_mc_bill_allocation_side CHECK (side IN ('DR', 'CR')),
    CONSTRAINT chk_mc_bill_allocation_amount CHECK (
        amount >= 0
        AND amount < 1000000000000000000
        AND amount = trunc(amount, 6)
        AND amount > 0
    ),
    CONSTRAINT chk_mc_bill_allocation_forex_ccy CHECK (
        forex_currency_code IS NULL OR forex_currency_code ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT chk_mc_bill_allocation_forex_amt CHECK (
        forex_amount IS NULL
        OR (
            forex_amount >= 0
            AND forex_amount < 1000000000000000000
            AND forex_amount = trunc(forex_amount, 6)
        )
    ),
    CONSTRAINT chk_mc_bill_allocation_forex_rate CHECK (
        forex_rate IS NULL
        OR (
            forex_rate > 0
            AND forex_rate < 1000000000000000000
            AND forex_rate = trunc(forex_rate, 6)
        )
    ),
    CONSTRAINT chk_mc_bill_allocation_forex_delta CHECK (
        abs(unadjusted_forex_delta) < 1000000000000000000
        AND unadjusted_forex_delta = trunc(unadjusted_forex_delta, 6)
    )
);

CREATE INDEX idx_mc_bill_allocation_open_ref
    ON mc_bill_allocation (company_id, ledger_id, bill_name, allocation_type);
CREATE INDEX idx_mc_bill_allocation_journal_line
    ON mc_bill_allocation (company_id, journal_line_id);
CREATE INDEX idx_mc_bill_allocation_trgm_bill_name
    ON mc_bill_allocation USING gin (bill_name gin_trgm_ops);

ALTER TABLE mc_bill_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_bill_allocation FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_bill_allocation_tenant_isolation ON mc_bill_allocation
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 6. Cost Centre Allocations
CREATE TABLE mc_cost_allocation (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    journal_line_id uuid NOT NULL,
    ledger_id uuid NOT NULL,
    cost_category_id uuid NOT NULL,
    cost_centre_id uuid NOT NULL,
    side char(2) NOT NULL,
    amount numeric NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_cost_allocation PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_cost_allocation_id UNIQUE (id),
    CONSTRAINT fk_mc_cost_allocation_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_allocation_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_allocation_line FOREIGN KEY (company_id, journal_line_id)
        REFERENCES mc_journal_line (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_allocation_ledger FOREIGN KEY (company_id, ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_allocation_category FOREIGN KEY (company_id, cost_category_id)
        REFERENCES mc_cost_category (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_allocation_centre FOREIGN KEY (company_id, cost_centre_id)
        REFERENCES mc_cost_centre (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_cost_allocation_side CHECK (side IN ('DR', 'CR')),
    CONSTRAINT chk_mc_cost_allocation_amount CHECK (
        amount >= 0
        AND amount < 1000000000000000000
        AND amount = trunc(amount, 6)
        AND amount > 0
    )
);

CREATE INDEX idx_mc_cost_allocation_centre
    ON mc_cost_allocation (company_id, cost_centre_id, journal_id);
CREATE INDEX idx_mc_cost_allocation_line
    ON mc_cost_allocation (company_id, journal_line_id);

ALTER TABLE mc_cost_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_cost_allocation FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_cost_allocation_tenant_isolation ON mc_cost_allocation
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 7. Seed Base Currency & Primary Cost Category Automatically on Company Creation
CREATE OR REPLACE FUNCTION fn_mc_seed_company_currency_and_cost()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_prev_company text;
BEGIN
    v_prev_company := current_setting('app.current_company_id', true);
    PERFORM set_config('app.current_company_id', NEW.id::text, true);

    INSERT INTO mc_currency (
        id, company_id, iso_code, symbol, formal_name,
        decimal_places, is_base_currency, active, master_seq_id
    )
    VALUES (
        fn_mc_seed_uuid(NEW.id, 'currency:' || NEW.base_currency_code),
        NEW.id,
        NEW.base_currency_code,
        NEW.base_currency_symbol,
        NEW.formal_currency_name,
        NEW.currency_scale,
        true,
        true,
        55
    )
    ON CONFLICT (company_id, iso_code) DO NOTHING;

    INSERT INTO mc_cost_category (
        id, company_id, name, allocate_revenue_items,
        allocate_non_revenue_items, is_primary_category, active, master_seq_id
    )
    VALUES (
        fn_mc_seed_uuid(NEW.id, 'costcat:Primary Cost Category'),
        NEW.id,
        'Primary Cost Category',
        true,
        false,
        true,
        true,
        56
    )
    ON CONFLICT (company_id, lower(name)) DO NOTHING;

    PERFORM set_config('app.current_company_id', COALESCE(v_prev_company, ''), true);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_company_seed_currency_and_cost
AFTER INSERT ON mc_company
FOR EACH ROW
EXECUTE FUNCTION fn_mc_seed_company_currency_and_cost();
