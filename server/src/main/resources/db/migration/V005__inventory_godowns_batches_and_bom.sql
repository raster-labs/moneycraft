SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Inventory Masters, Hierarchical Godowns, Batches,
-- Price Lists, Bills of Materials (BOM), O2C/P2P Tracking & Cost Layers
-- Stream: db/migration/V005__inventory_godowns_batches_and_bom.sql
-- ============================================================================

-- 1. Units of Measure (Simple & Compound UOMs + GST UQC Codes)
CREATE TABLE mc_uom (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    uom_type varchar(16) NOT NULL DEFAULT 'SIMPLE',
    symbol varchar(32) NOT NULL,
    formal_name varchar(80),
    gst_uqc_code varchar(16),
    decimal_places smallint NOT NULL DEFAULT 0,
    first_uom_id uuid,
    conversion_factor numeric,
    second_uom_id uuid,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_uom PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_uom_id UNIQUE (id),
    CONSTRAINT fk_mc_uom_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_uom_first FOREIGN KEY (company_id, first_uom_id)
        REFERENCES mc_uom (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_uom_second FOREIGN KEY (company_id, second_uom_id)
        REFERENCES mc_uom (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_uom_type CHECK (uom_type IN ('SIMPLE', 'COMPOUND')),
    CONSTRAINT chk_mc_uom_symbol CHECK (length(trim(symbol)) > 0),
    CONSTRAINT chk_mc_uom_decimals CHECK (decimal_places BETWEEN 0 AND 6),
    CONSTRAINT chk_mc_uom_compound_structure CHECK (
        (
            uom_type = 'SIMPLE'
            AND first_uom_id IS NULL
            AND conversion_factor IS NULL
            AND second_uom_id IS NULL
        )
        OR (
            uom_type = 'COMPOUND'
            AND first_uom_id IS NOT NULL
            AND second_uom_id IS NOT NULL
            AND first_uom_id <> second_uom_id
            AND conversion_factor IS NOT NULL
            AND conversion_factor > 0
            AND conversion_factor < 1000000000000000000
            AND conversion_factor = trunc(conversion_factor, 6)
        )
    ),
    CONSTRAINT chk_mc_uom_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_uom_company_lower_symbol ON mc_uom (company_id, lower(symbol));

ALTER TABLE mc_uom ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_uom FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_uom_tenant_isolation ON mc_uom
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 2. Hierarchical Godowns / Locations (Main Location Seeded Automatically)
CREATE TABLE mc_godown (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    alias_code varchar(64),
    parent_godown_id uuid,
    address text NOT NULL DEFAULT '',
    allow_storage_of_materials boolean NOT NULL DEFAULT true,
    our_stock_with_third_party boolean NOT NULL DEFAULT false,
    third_party_stock_with_us boolean NOT NULL DEFAULT false,
    tree_path varchar(1024) NOT NULL DEFAULT '',
    depth_level smallint NOT NULL DEFAULT 0,
    is_system_godown boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_godown PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_godown_id UNIQUE (id),
    CONSTRAINT fk_mc_godown_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_godown_parent FOREIGN KEY (company_id, parent_godown_id)
        REFERENCES mc_godown (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_godown_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_godown_depth CHECK (depth_level BETWEEN 0 AND 32),
    CONSTRAINT chk_mc_godown_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_godown_company_lower_name ON mc_godown (company_id, lower(name));

CREATE OR REPLACE FUNCTION fn_mc_godown_hierarchy_and_protect()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_cursor_id uuid;
    v_parent_path varchar(1024);
    v_parent_depth smallint;
    v_hops integer := 0;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.is_system_godown = true THEN
            RAISE EXCEPTION USING
                ERRCODE = '42501',
                MESSAGE = format('MC-INV-SYSTEM-GODOWN-PROTECTED: default godown %s cannot be deleted', OLD.name);
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.is_system_godown = true AND NEW.is_system_godown = false THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'MC-INV-SYSTEM-GODOWN-PROTECTED: Main Location system flag cannot be cleared';
    END IF;

    IF NEW.parent_godown_id IS NULL THEN
        NEW.depth_level := 0;
        NEW.tree_path := '/' || NEW.id::text || '/';
        RETURN NEW;
    END IF;

    IF NEW.parent_godown_id = NEW.id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'MC-INV-GODOWN-CYCLE: godown cannot be its own parent';
    END IF;

    v_cursor_id := NEW.parent_godown_id;
    WHILE v_cursor_id IS NOT NULL LOOP
        v_hops := v_hops + 1;
        IF v_hops > 32 THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'MC-INV-GODOWN-DEPTH: godown hierarchy exceeds maximum depth 32';
        END IF;
        IF v_cursor_id = NEW.id THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'MC-INV-GODOWN-CYCLE: cycle detected in mc_godown hierarchy';
        END IF;
        SELECT g.parent_godown_id
          INTO v_cursor_id
          FROM mc_godown g
         WHERE g.company_id = NEW.company_id
           AND g.id = v_cursor_id;
    END LOOP;

    SELECT g.tree_path, g.depth_level
      INTO v_parent_path, v_parent_depth
      FROM mc_godown g
     WHERE g.company_id = NEW.company_id
       AND g.id = NEW.parent_godown_id;

    NEW.depth_level := v_parent_depth + 1;
    NEW.tree_path := v_parent_path || NEW.id::text || '/';
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_godown_no_cycle
BEFORE INSERT OR UPDATE OR DELETE ON mc_godown
FOR EACH ROW
EXECUTE FUNCTION fn_mc_godown_hierarchy_and_protect();

ALTER TABLE mc_godown ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_godown FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_godown_tenant_isolation ON mc_godown
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 3. Stock Groups & Stock Categories
CREATE TABLE mc_stock_group (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    alias_code varchar(64),
    parent_stock_group_id uuid,
    should_quantities_of_items_be_added boolean NOT NULL DEFAULT false,
    hsn_sac_code varchar(16),
    gst_rate_pct numeric,
    cess_rate_pct numeric,
    tree_path varchar(1024) NOT NULL DEFAULT '',
    depth_level smallint NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_stock_group PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_stock_group_id UNIQUE (id),
    CONSTRAINT fk_mc_stock_group_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_group_parent FOREIGN KEY (company_id, parent_stock_group_id)
        REFERENCES mc_stock_group (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_stock_group_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_stock_group_gst_rate CHECK (
        gst_rate_pct IS NULL
        OR (gst_rate_pct >= 0 AND gst_rate_pct <= 100 AND gst_rate_pct = trunc(gst_rate_pct, 6))
    ),
    CONSTRAINT chk_mc_stock_group_cess_rate CHECK (
        cess_rate_pct IS NULL
        OR (cess_rate_pct >= 0 AND cess_rate_pct <= 500 AND cess_rate_pct = trunc(cess_rate_pct, 6))
    ),
    CONSTRAINT chk_mc_stock_group_depth CHECK (depth_level BETWEEN 0 AND 32),
    CONSTRAINT chk_mc_stock_group_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_stock_group_company_lower_name
    ON mc_stock_group (company_id, lower(name));

CREATE OR REPLACE FUNCTION fn_mc_stock_group_no_cycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_cursor_id uuid;
    v_parent_path varchar(1024);
    v_parent_depth smallint;
    v_hops integer := 0;
BEGIN
    IF NEW.parent_stock_group_id IS NULL THEN
        NEW.depth_level := 0;
        NEW.tree_path := '/' || NEW.id::text || '/';
        RETURN NEW;
    END IF;
    IF NEW.parent_stock_group_id = NEW.id THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'MC-INV-STOCKGROUP-CYCLE: self parent prohibited';
    END IF;
    v_cursor_id := NEW.parent_stock_group_id;
    WHILE v_cursor_id IS NOT NULL LOOP
        v_hops := v_hops + 1;
        IF v_hops > 32 OR v_cursor_id = NEW.id THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'MC-INV-STOCKGROUP-CYCLE: cycle detected in mc_stock_group';
        END IF;
        SELECT sg.parent_stock_group_id INTO v_cursor_id
          FROM mc_stock_group sg
         WHERE sg.company_id = NEW.company_id AND sg.id = v_cursor_id;
    END LOOP;
    SELECT sg.tree_path, sg.depth_level INTO v_parent_path, v_parent_depth
      FROM mc_stock_group sg
     WHERE sg.company_id = NEW.company_id AND sg.id = NEW.parent_stock_group_id;
    NEW.depth_level := v_parent_depth + 1;
    NEW.tree_path := v_parent_path || NEW.id::text || '/';
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_stock_group_no_cycle
BEFORE INSERT OR UPDATE ON mc_stock_group
FOR EACH ROW
EXECUTE FUNCTION fn_mc_stock_group_no_cycle();

ALTER TABLE mc_stock_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_stock_group FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_stock_group_tenant_isolation ON mc_stock_group
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_stock_category (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    alias_code varchar(64),
    parent_stock_category_id uuid,
    tree_path varchar(1024) NOT NULL DEFAULT '',
    depth_level smallint NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_stock_category PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_stock_category_id UNIQUE (id),
    CONSTRAINT fk_mc_stock_category_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_category_parent FOREIGN KEY (company_id, parent_stock_category_id)
        REFERENCES mc_stock_category (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_stock_category_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_stock_category_depth CHECK (depth_level BETWEEN 0 AND 32),
    CONSTRAINT chk_mc_stock_category_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_stock_category_company_lower_name
    ON mc_stock_category (company_id, lower(name));

ALTER TABLE mc_stock_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_stock_category FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_stock_category_tenant_isolation ON mc_stock_category
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 4. Stock Items (8 Valuation Methods + Rel 7.2 Tobacco Deemed MRP Support)
CREATE TABLE mc_stock_item (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    alias_code varchar(64),
    part_no varchar(64),
    description text NOT NULL DEFAULT '',
    stock_group_id uuid,
    stock_category_id uuid,
    base_uom_id uuid NOT NULL,
    alternate_uom_id uuid,
    conversion_numerator numeric,
    conversion_denominator numeric,
    maintain_in_batches boolean NOT NULL DEFAULT false,
    track_date_of_mfg boolean NOT NULL DEFAULT false,
    use_expiry_dates boolean NOT NULL DEFAULT false,
    costing_method varchar(24) NOT NULL DEFAULT 'AVG_COST',
    market_valuation_method varchar(24) NOT NULL DEFAULT 'AVG_PRICE',
    standard_cost numeric,
    standard_selling_price numeric,
    opening_qty numeric NOT NULL DEFAULT 0,
    opening_rate numeric NOT NULL DEFAULT 0,
    opening_value numeric NOT NULL DEFAULT 0,
    gst_applicability varchar(24) NOT NULL DEFAULT 'APPLICABLE',
    gst_supply_type varchar(16) NOT NULL DEFAULT 'GOODS',
    hsn_sac_code varchar(16),
    gst_rate_pct numeric,
    cess_rate_pct numeric,
    cess_per_unit numeric,
    is_tobacco_mrp_item boolean NOT NULL DEFAULT false,
    mrp_per_unit numeric,
    default_sales_ledger_id uuid,
    default_purchase_ledger_id uuid,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_stock_item PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_stock_item_id UNIQUE (id),
    CONSTRAINT fk_mc_stock_item_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_item_group FOREIGN KEY (company_id, stock_group_id)
        REFERENCES mc_stock_group (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_item_category FOREIGN KEY (company_id, stock_category_id)
        REFERENCES mc_stock_category (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_item_base_uom FOREIGN KEY (company_id, base_uom_id)
        REFERENCES mc_uom (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_item_alt_uom FOREIGN KEY (company_id, alternate_uom_id)
        REFERENCES mc_uom (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_item_sales_ledger FOREIGN KEY (company_id, default_sales_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_item_purchase_ledger FOREIGN KEY (company_id, default_purchase_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_item_created_by FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_stock_item_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_mc_stock_item_alt_conv CHECK (
        (
            alternate_uom_id IS NULL
            AND conversion_numerator IS NULL
            AND conversion_denominator IS NULL
        )
        OR (
            alternate_uom_id IS NOT NULL
            AND alternate_uom_id <> base_uom_id
            AND conversion_numerator IS NOT NULL AND conversion_numerator > 0
            AND conversion_numerator < 1000000000000000000 AND conversion_numerator = trunc(conversion_numerator, 6)
            AND conversion_denominator IS NOT NULL AND conversion_denominator > 0
            AND conversion_denominator < 1000000000000000000 AND conversion_denominator = trunc(conversion_denominator, 6)
        )
    ),
    CONSTRAINT chk_mc_stock_item_costing CHECK (
        costing_method IN (
            'AVG_COST',
            'FIFO',
            'FIFO_PERPETUAL',
            'LIFO_ANNUAL',
            'LIFO_PERPETUAL',
            'LAST_PURCHASE_COST',
            'STD_COST',
            'AT_ZERO_COST'
        )
    ),
    CONSTRAINT chk_mc_stock_item_market_val CHECK (
        market_valuation_method IN ('AVG_PRICE', 'LAST_SALE_PRICE', 'STD_PRICE', 'AT_ZERO_PRICE')
    ),
    CONSTRAINT chk_mc_stock_item_opening_exact CHECK (
        opening_qty >= 0 AND opening_qty < 1000000000000000000 AND opening_qty = trunc(opening_qty, 6)
        AND opening_rate >= 0 AND opening_rate < 1000000000000000000 AND opening_rate = trunc(opening_rate, 6)
        AND opening_value >= 0 AND opening_value < 1000000000000000000 AND opening_value = trunc(opening_value, 6)
    ),
    CONSTRAINT chk_mc_stock_item_std_prices CHECK (
        (standard_cost IS NULL OR (standard_cost >= 0 AND standard_cost < 1000000000000000000 AND standard_cost = trunc(standard_cost, 6)))
        AND (standard_selling_price IS NULL OR (standard_selling_price >= 0 AND standard_selling_price < 1000000000000000000 AND standard_selling_price = trunc(standard_selling_price, 6)))
    ),
    CONSTRAINT chk_mc_stock_item_gst_app CHECK (
        gst_applicability IN ('APPLICABLE', 'NOT_APPLICABLE', 'EXEMPT', 'NIL_RATED', 'NON_GST')
    ),
    CONSTRAINT chk_mc_stock_item_supply CHECK (
        gst_supply_type IN ('GOODS', 'SERVICES', 'CAPITAL_GOODS')
    ),
    CONSTRAINT chk_mc_stock_item_rates CHECK (
        (gst_rate_pct IS NULL OR (gst_rate_pct >= 0 AND gst_rate_pct <= 100 AND gst_rate_pct = trunc(gst_rate_pct, 6)))
        AND (cess_rate_pct IS NULL OR (cess_rate_pct >= 0 AND cess_rate_pct <= 500 AND cess_rate_pct = trunc(cess_rate_pct, 6)))
        AND (cess_per_unit IS NULL OR (cess_per_unit >= 0 AND cess_per_unit < 1000000000000000000 AND cess_per_unit = trunc(cess_per_unit, 6)))
        AND (mrp_per_unit IS NULL OR (mrp_per_unit >= 0 AND mrp_per_unit < 1000000000000000000 AND mrp_per_unit = trunc(mrp_per_unit, 6)))
    ),
    CONSTRAINT chk_mc_stock_item_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE UNIQUE INDEX uq_mc_stock_item_company_lower_name ON mc_stock_item (company_id, lower(name));
CREATE INDEX idx_mc_stock_item_group ON mc_stock_item (company_id, stock_group_id, active);
CREATE INDEX idx_mc_stock_item_trgm_name ON mc_stock_item USING gin (name gin_trgm_ops);
CREATE INDEX idx_mc_stock_item_trgm_part ON mc_stock_item USING gin (part_no gin_trgm_ops)
    WHERE part_no IS NOT NULL;
CREATE INDEX idx_mc_stock_item_trgm_hsn ON mc_stock_item USING gin (hsn_sac_code gin_trgm_ops)
    WHERE hsn_sac_code IS NOT NULL;

ALTER TABLE mc_stock_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_stock_item FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_stock_item_tenant_isolation ON mc_stock_item
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 5. Stock Batches (Mfg Date, Expiry Date & Batch MRP per Godown)
CREATE TABLE mc_stock_batch (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    godown_id uuid NOT NULL,
    batch_name varchar(80) NOT NULL DEFAULT 'Primary Batch',
    mfg_date date,
    expiry_date date,
    mrp_per_unit numeric,
    opening_qty numeric NOT NULL DEFAULT 0,
    closing_qty numeric NOT NULL DEFAULT 0,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_stock_batch PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_stock_batch_id UNIQUE (id),
    CONSTRAINT uq_mc_stock_batch_tuple UNIQUE (company_id, stock_item_id, godown_id, batch_name),
    CONSTRAINT fk_mc_stock_batch_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_batch_item FOREIGN KEY (company_id, stock_item_id)
        REFERENCES mc_stock_item (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_stock_batch_godown FOREIGN KEY (company_id, godown_id)
        REFERENCES mc_godown (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_stock_batch_name CHECK (length(trim(batch_name)) > 0),
    CONSTRAINT chk_mc_stock_batch_dates CHECK (
        mfg_date IS NULL OR expiry_date IS NULL OR expiry_date >= mfg_date
    ),
    CONSTRAINT chk_mc_stock_batch_mrp CHECK (
        mrp_per_unit IS NULL
        OR (mrp_per_unit >= 0 AND mrp_per_unit < 1000000000000000000 AND mrp_per_unit = trunc(mrp_per_unit, 6))
    ),
    CONSTRAINT chk_mc_stock_batch_opening CHECK (
        opening_qty >= 0 AND opening_qty < 1000000000000000000 AND opening_qty = trunc(opening_qty, 6)
    ),
    CONSTRAINT chk_mc_stock_batch_closing CHECK (
        abs(closing_qty) < 1000000000000000000 AND closing_qty = trunc(closing_qty, 6)
    ),
    CONSTRAINT chk_mc_stock_batch_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_stock_batch_expiry ON mc_stock_batch (company_id, stock_item_id, expiry_date);

ALTER TABLE mc_stock_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_stock_batch FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_stock_batch_tenant_isolation ON mc_stock_batch
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 6. Price Lists & Staggered Quantity Slabs
CREATE TABLE mc_price_list (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    price_level_name varchar(80) NOT NULL,
    effective_from date NOT NULL,
    currency_code char(3) NOT NULL DEFAULT 'INR',
    active boolean NOT NULL DEFAULT true,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_price_list PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_price_list_id UNIQUE (id),
    CONSTRAINT uq_mc_price_list_level_date UNIQUE (company_id, price_level_name, effective_from),
    CONSTRAINT fk_mc_price_list_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_price_list_ccy CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_mc_price_list_version_no CHECK (version_no >= 1)
);

ALTER TABLE mc_price_list ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_price_list FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_price_list_tenant_isolation ON mc_price_list
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_price_tier (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    price_list_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    from_qty numeric NOT NULL DEFAULT 0,
    less_than_qty numeric,
    rate numeric NOT NULL,
    discount_pct numeric NOT NULL DEFAULT 0,
    CONSTRAINT pk_mc_price_tier PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_price_tier_id UNIQUE (id),
    CONSTRAINT fk_mc_price_tier_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_price_tier_list FOREIGN KEY (company_id, price_list_id)
        REFERENCES mc_price_list (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_price_tier_item FOREIGN KEY (company_id, stock_item_id)
        REFERENCES mc_stock_item (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_price_tier_qty CHECK (
        from_qty >= 0 AND from_qty < 1000000000000000000 AND from_qty = trunc(from_qty, 6)
        AND (
            less_than_qty IS NULL
            OR (
                less_than_qty > from_qty
                AND less_than_qty < 1000000000000000000
                AND less_than_qty = trunc(less_than_qty, 6)
            )
        )
    ),
    CONSTRAINT chk_mc_price_tier_rate CHECK (
        rate >= 0 AND rate < 1000000000000000000 AND rate = trunc(rate, 6)
    ),
    CONSTRAINT chk_mc_price_tier_discount CHECK (
        discount_pct >= 0 AND discount_pct <= 100 AND discount_pct = trunc(discount_pct, 6)
    )
);

CREATE INDEX idx_mc_price_tier_lookup ON mc_price_tier (company_id, price_list_id, stock_item_id, from_qty);

ALTER TABLE mc_price_tier ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_price_tier FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_price_tier_tenant_isolation ON mc_price_tier
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 7. Multi-BOM Manufacturing Definitions
CREATE TABLE mc_bom (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    bom_name varchar(120) NOT NULL,
    unit_of_manufacture numeric NOT NULL DEFAULT 1,
    additional_cost_rules jsonb NOT NULL DEFAULT '[]'::jsonb,
    active boolean NOT NULL DEFAULT true,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_bom PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_bom_id UNIQUE (id),
    CONSTRAINT uq_mc_bom_item_name UNIQUE (company_id, stock_item_id, bom_name),
    CONSTRAINT fk_mc_bom_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bom_item FOREIGN KEY (company_id, stock_item_id)
        REFERENCES mc_stock_item (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_bom_unit_qty CHECK (
        unit_of_manufacture > 0
        AND unit_of_manufacture < 1000000000000000000
        AND unit_of_manufacture = trunc(unit_of_manufacture, 6)
    ),
    CONSTRAINT chk_mc_bom_version_no CHECK (version_no >= 1)
);

ALTER TABLE mc_bom ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_bom FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_bom_tenant_isolation ON mc_bom
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_bom_component (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    bom_id uuid NOT NULL,
    line_no smallint NOT NULL,
    component_item_id uuid NOT NULL,
    component_type varchar(20) NOT NULL DEFAULT 'RAW_MATERIAL',
    godown_id uuid,
    quantity numeric NOT NULL,
    cost_allocation_pct numeric NOT NULL DEFAULT 0,
    CONSTRAINT pk_mc_bom_component PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_bom_component_id UNIQUE (id),
    CONSTRAINT uq_mc_bom_component_line UNIQUE (company_id, bom_id, line_no),
    CONSTRAINT fk_mc_bom_component_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bom_component_bom FOREIGN KEY (company_id, bom_id)
        REFERENCES mc_bom (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bom_component_item FOREIGN KEY (company_id, component_item_id)
        REFERENCES mc_stock_item (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bom_component_godown FOREIGN KEY (company_id, godown_id)
        REFERENCES mc_godown (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_bom_component_type CHECK (
        component_type IN ('RAW_MATERIAL', 'CO_PRODUCT', 'BY_PRODUCT', 'SCRAP')
    ),
    CONSTRAINT chk_mc_bom_component_qty CHECK (
        quantity > 0
        AND quantity < 1000000000000000000
        AND quantity = trunc(quantity, 6)
    ),
    CONSTRAINT chk_mc_bom_component_pct CHECK (
        cost_allocation_pct >= 0
        AND cost_allocation_pct <= 100
        AND cost_allocation_pct = trunc(cost_allocation_pct, 6)
    )
);

ALTER TABLE mc_bom_component ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_bom_component FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_bom_component_tenant_isolation ON mc_bom_component
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 8. Voucher Inventory Allocations, Batch Splits, O2C/P2P Tracking & Cost Layers
CREATE TABLE mc_inventory_allocation (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    journal_line_id uuid,
    line_no integer NOT NULL DEFAULT 1,
    stock_item_id uuid NOT NULL,
    movement_direction varchar(12) NOT NULL,
    uom_id uuid NOT NULL,
    actual_qty numeric NOT NULL,
    billed_qty numeric NOT NULL,
    rate numeric NOT NULL DEFAULT 0,
    discount_pct numeric NOT NULL DEFAULT 0,
    amount numeric NOT NULL DEFAULT 0,
    declared_mrp numeric,
    order_ref_no varchar(64),
    tracking_no varchar(64),
    linked_inward_allocation_id uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_inventory_allocation PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_inventory_allocation_id UNIQUE (id),
    CONSTRAINT fk_mc_inv_alloc_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_inv_alloc_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_inv_alloc_line FOREIGN KEY (company_id, journal_line_id)
        REFERENCES mc_journal_line (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_inv_alloc_item FOREIGN KEY (company_id, stock_item_id)
        REFERENCES mc_stock_item (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_inv_alloc_uom FOREIGN KEY (company_id, uom_id)
        REFERENCES mc_uom (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_inv_alloc_linked_inward FOREIGN KEY (company_id, linked_inward_allocation_id)
        REFERENCES mc_inventory_allocation (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_inv_alloc_dir CHECK (movement_direction IN ('INWARD', 'OUTWARD')),
    CONSTRAINT chk_mc_inv_alloc_qty CHECK (
        actual_qty >= 0 AND actual_qty < 1000000000000000000 AND actual_qty = trunc(actual_qty, 6)
        AND billed_qty >= 0 AND billed_qty < 1000000000000000000 AND billed_qty = trunc(billed_qty, 6)
        AND (actual_qty > 0 OR billed_qty > 0)
    ),
    CONSTRAINT chk_mc_inv_alloc_rate_amt CHECK (
        rate >= 0 AND rate < 1000000000000000000 AND rate = trunc(rate, 6)
        AND discount_pct >= 0 AND discount_pct <= 100 AND discount_pct = trunc(discount_pct, 6)
        AND amount >= 0 AND amount < 1000000000000000000 AND amount = trunc(amount, 6)
        AND (declared_mrp IS NULL OR (declared_mrp >= 0 AND declared_mrp < 1000000000000000000 AND declared_mrp = trunc(declared_mrp, 6)))
    )
);

CREATE INDEX idx_mc_inv_alloc_item_journal
    ON mc_inventory_allocation (company_id, stock_item_id, journal_id);
CREATE INDEX idx_mc_inv_alloc_tracking
    ON mc_inventory_allocation (company_id, tracking_no) WHERE tracking_no IS NOT NULL;

ALTER TABLE mc_inventory_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_inventory_allocation FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_inventory_allocation_tenant_isolation ON mc_inventory_allocation
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_batch_allocation (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    inventory_allocation_id uuid NOT NULL,
    godown_id uuid NOT NULL,
    stock_batch_id uuid,
    batch_name varchar(80) NOT NULL DEFAULT 'Primary Batch',
    actual_qty numeric NOT NULL,
    billed_qty numeric NOT NULL,
    rate numeric NOT NULL DEFAULT 0,
    amount numeric NOT NULL DEFAULT 0,
    mfg_date date,
    expiry_date date,
    CONSTRAINT pk_mc_batch_allocation PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_batch_allocation_id UNIQUE (id),
    CONSTRAINT fk_mc_batch_alloc_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_batch_alloc_inv FOREIGN KEY (company_id, inventory_allocation_id)
        REFERENCES mc_inventory_allocation (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_batch_alloc_godown FOREIGN KEY (company_id, godown_id)
        REFERENCES mc_godown (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_batch_alloc_batch FOREIGN KEY (company_id, stock_batch_id)
        REFERENCES mc_stock_batch (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_batch_alloc_qty_amt CHECK (
        actual_qty >= 0 AND actual_qty < 1000000000000000000 AND actual_qty = trunc(actual_qty, 6)
        AND billed_qty >= 0 AND billed_qty < 1000000000000000000 AND billed_qty = trunc(billed_qty, 6)
        AND rate >= 0 AND rate < 1000000000000000000 AND rate = trunc(rate, 6)
        AND amount >= 0 AND amount < 1000000000000000000 AND amount = trunc(amount, 6)
    )
);

CREATE INDEX idx_mc_batch_alloc_inv ON mc_batch_allocation (company_id, inventory_allocation_id);
CREATE INDEX idx_mc_batch_alloc_godown ON mc_batch_allocation (company_id, godown_id, stock_batch_id);

ALTER TABLE mc_batch_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_batch_allocation FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_batch_allocation_tenant_isolation ON mc_batch_allocation
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_order_line_fulfillment (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    order_journal_id uuid NOT NULL,
    order_allocation_id uuid NOT NULL,
    order_ref_no varchar(64) NOT NULL,
    party_ledger_id uuid,
    stock_item_id uuid NOT NULL,
    due_on_date date,
    ordered_qty numeric NOT NULL,
    delivered_received_qty numeric NOT NULL DEFAULT 0,
    billed_qty numeric NOT NULL DEFAULT 0,
    rejected_qty numeric NOT NULL DEFAULT 0,
    preclosed_qty numeric NOT NULL DEFAULT 0,
    preclose_reason varchar(255),
    tracking_no varchar(64),
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_order_line_fulfillment PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_order_line_fulfillment_id UNIQUE (id),
    CONSTRAINT fk_mc_order_fulfill_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_order_fulfill_journal FOREIGN KEY (company_id, order_journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_order_fulfill_alloc FOREIGN KEY (company_id, order_allocation_id)
        REFERENCES mc_inventory_allocation (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_order_fulfill_party FOREIGN KEY (company_id, party_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_order_fulfill_item FOREIGN KEY (company_id, stock_item_id)
        REFERENCES mc_stock_item (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_order_fulfill_qtys CHECK (
        ordered_qty > 0 AND ordered_qty < 1000000000000000000 AND ordered_qty = trunc(ordered_qty, 6)
        AND delivered_received_qty >= 0 AND delivered_received_qty < 1000000000000000000 AND delivered_received_qty = trunc(delivered_received_qty, 6)
        AND billed_qty >= 0 AND billed_qty < 1000000000000000000 AND billed_qty = trunc(billed_qty, 6)
        AND rejected_qty >= 0 AND rejected_qty < 1000000000000000000 AND rejected_qty = trunc(rejected_qty, 6)
        AND preclosed_qty >= 0 AND preclosed_qty < 1000000000000000000 AND preclosed_qty = trunc(preclosed_qty, 6)
    ),
    CONSTRAINT chk_mc_order_fulfill_status CHECK (
        status IN ('OPEN', 'PARTIAL', 'FULFILLED', 'PRECLOSED')
    )
);

CREATE INDEX idx_mc_order_fulfill_open
    ON mc_order_line_fulfillment (company_id, status, order_ref_no, stock_item_id);

ALTER TABLE mc_order_line_fulfillment ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_order_line_fulfillment FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_order_line_fulfillment_tenant_isolation ON mc_order_line_fulfillment
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- High-Precision Valuation Layers (Supports scale >= 10 AVG_COST & back-dated FIFO recomputation)
CREATE TABLE mc_stock_cost_layer (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    godown_id uuid,
    stock_batch_id uuid,
    source_journal_id uuid,
    source_allocation_id uuid,
    layer_date date NOT NULL,
    layer_seq bigint NOT NULL DEFAULT 1,
    inward_qty numeric NOT NULL,
    remaining_qty numeric NOT NULL,
    inward_value numeric NOT NULL,
    remaining_value numeric NOT NULL,
    unit_cost_high_prec numeric NOT NULL,
    is_opening_layer boolean NOT NULL DEFAULT false,
    exhausted boolean NOT NULL DEFAULT false,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_stock_cost_layer PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_stock_cost_layer_id UNIQUE (id),
    CONSTRAINT fk_mc_cost_layer_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_layer_item FOREIGN KEY (company_id, stock_item_id)
        REFERENCES mc_stock_item (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_layer_godown FOREIGN KEY (company_id, godown_id)
        REFERENCES mc_godown (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_layer_batch FOREIGN KEY (company_id, stock_batch_id)
        REFERENCES mc_stock_batch (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_layer_journal FOREIGN KEY (company_id, source_journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cost_layer_alloc FOREIGN KEY (company_id, source_allocation_id)
        REFERENCES mc_inventory_allocation (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_cost_layer_qtys CHECK (
        inward_qty >= 0 AND inward_qty < 1000000000000000000 AND inward_qty = trunc(inward_qty, 6)
        AND remaining_qty >= 0 AND remaining_qty <= inward_qty AND remaining_qty = trunc(remaining_qty, 6)
    ),
    CONSTRAINT chk_mc_cost_layer_values CHECK (
        inward_value >= 0 AND inward_value < 1000000000000000000 AND inward_value = trunc(inward_value, 6)
        AND remaining_value >= 0 AND remaining_value < 1000000000000000000 AND remaining_value = trunc(remaining_value, 6)
    ),
    CONSTRAINT chk_mc_cost_layer_unit_prec CHECK (
        unit_cost_high_prec >= 0
        AND unit_cost_high_prec < 1000000000000000000
        AND unit_cost_high_prec = trunc(unit_cost_high_prec, 18)
    )
);

CREATE INDEX idx_mc_stock_cost_layer_fifo
    ON mc_stock_cost_layer (company_id, stock_item_id, exhausted, layer_date, layer_seq);

ALTER TABLE mc_stock_cost_layer ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_stock_cost_layer FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_stock_cost_layer_tenant_isolation ON mc_stock_cost_layer
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 9. Seed Default Main Location Godown Automatically on Company Creation
CREATE OR REPLACE FUNCTION fn_mc_seed_company_main_location()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_prev_company text;
BEGIN
    v_prev_company := current_setting('app.current_company_id', true);
    PERFORM set_config('app.current_company_id', NEW.id::text, true);

    INSERT INTO mc_godown (
        id, company_id, name, parent_godown_id,
        allow_storage_of_materials, is_system_godown, active, master_seq_id
    )
    VALUES (
        fn_mc_seed_uuid(NEW.id, 'godown:Main Location'),
        NEW.id,
        'Main Location',
        NULL,
        true,
        true,
        true,
        57
    )
    ON CONFLICT (company_id, lower(name)) DO NOTHING;

    PERFORM set_config('app.current_company_id', COALESCE(v_prev_company, ''), true);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_company_seed_main_location
AFTER INSERT ON mc_company
FOR EACH ROW
EXECUTE FUNCTION fn_mc_seed_company_main_location();
