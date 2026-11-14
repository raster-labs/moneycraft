SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Multi-GSTIN, Declarative StatRulePack, 6-Tier Tax Engine,
-- IMS Reconciliation (Rel 7.2 ITC Reduction & Remarks), 30-Day e-Invoice/EWB
-- Connector Sessions & TDS/TCS (Protean FVU 1.2)
-- Stream: db/migration/V006__statutory_gst_ims_einvoice_and_tds.sql
-- ============================================================================

-- 1. Global Signed Date-Effective Statutory Rule Packs (No Company RLS)
CREATE TABLE mc_stat_rule_pack (
    id uuid NOT NULL,
    pack_code varchar(64) NOT NULL,
    country_code char(2) NOT NULL DEFAULT 'IN',
    fiscal_year_label varchar(24) NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    schema_version varchar(16) NOT NULL DEFAULT '1.0.0',
    rules_json jsonb NOT NULL,
    pack_sha256 varchar(64) NOT NULL,
    signature_alg varchar(32) NOT NULL DEFAULT 'SHA256-DET',
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_stat_rule_pack PRIMARY KEY (id),
    CONSTRAINT uq_mc_stat_rule_pack_code UNIQUE (pack_code),
    CONSTRAINT chk_mc_stat_rule_pack_country CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_mc_stat_rule_pack_dates CHECK (
        effective_to IS NULL OR effective_to >= effective_from
    ),
    CONSTRAINT chk_mc_stat_rule_pack_sha256 CHECK (pack_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_mc_stat_rule_pack_effective
    ON mc_stat_rule_pack (country_code, active, effective_from DESC, effective_to);

-- 2. Company Multi-GSTIN Registrations
CREATE TABLE mc_gst_registration (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    registration_name varchar(120) NOT NULL,
    state_code varchar(4) NOT NULL,
    state_name varchar(100) NOT NULL,
    registration_type varchar(24) NOT NULL DEFAULT 'REGULAR',
    gstin varchar(15) NOT NULL,
    effective_from date NOT NULL,
    periodicity varchar(16) NOT NULL DEFAULT 'MONTHLY',
    einvoice_applicable boolean NOT NULL DEFAULT false,
    einvoice_applicable_from date,
    einvoice_bill_from_place varchar(100),
    eway_bill_applicable boolean NOT NULL DEFAULT true,
    eway_bill_applicable_from date,
    eway_bill_threshold_override numeric,
    default_gst_rate_pct numeric,
    default_hsn_sac_code varchar(16),
    lut_bond_no varchar(64),
    lut_valid_from date,
    lut_valid_to date,
    is_default boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    master_seq_id bigint NOT NULL DEFAULT 1,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_gst_registration PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_gst_registration_id UNIQUE (id),
    CONSTRAINT uq_mc_gst_registration_gstin UNIQUE (company_id, gstin),
    CONSTRAINT fk_mc_gst_registration_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_gst_registration_name CHECK (length(trim(registration_name)) > 0),
    CONSTRAINT chk_mc_gst_registration_type CHECK (
        registration_type IN (
            'REGULAR',
            'COMPOSITION',
            'ISD',
            'SEZ',
            'TDS_DEDUCTOR',
            'TCS_COLLECTOR'
        )
    ),
    CONSTRAINT chk_mc_gst_registration_gstin_fmt CHECK (
        gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'
    ),
    CONSTRAINT chk_mc_gst_registration_periodicity CHECK (
        periodicity IN ('MONTHLY', 'QUARTERLY_QRMP')
    ),
    CONSTRAINT chk_mc_gst_registration_ewb_thresh CHECK (
        eway_bill_threshold_override IS NULL
        OR (
            eway_bill_threshold_override >= 0
            AND eway_bill_threshold_override < 1000000000000000000
            AND eway_bill_threshold_override = trunc(eway_bill_threshold_override, 6)
        )
    ),
    CONSTRAINT chk_mc_gst_registration_default_rate CHECK (
        default_gst_rate_pct IS NULL
        OR (
            default_gst_rate_pct >= 0
            AND default_gst_rate_pct <= 100
            AND default_gst_rate_pct = trunc(default_gst_rate_pct, 6)
        )
    ),
    CONSTRAINT chk_mc_gst_registration_lut_dates CHECK (
        lut_valid_from IS NULL OR lut_valid_to IS NULL OR lut_valid_to >= lut_valid_from
    ),
    CONSTRAINT chk_mc_gst_registration_seq CHECK (
        master_seq_id >= 1 AND alter_id >= 1 AND version_no >= 1
    )
);

CREATE INDEX idx_mc_gst_registration_trgm_gstin
    ON mc_gst_registration USING gin (gstin gin_trgm_ops);

ALTER TABLE mc_gst_registration ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_gst_registration FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_gst_registration_tenant_isolation ON mc_gst_registration
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- Expand-safe composite foreign keys from V002 tables to mc_gst_registration
ALTER TABLE mc_number_series
    ADD CONSTRAINT fk_mc_number_series_gst_reg
    FOREIGN KEY (company_id, gst_registration_id)
    REFERENCES mc_gst_registration (company_id, id)
    ON UPDATE RESTRICT ON DELETE RESTRICT NOT VALID;

ALTER TABLE mc_number_series VALIDATE CONSTRAINT fk_mc_number_series_gst_reg;

ALTER TABLE mc_journal
    ADD CONSTRAINT fk_mc_journal_company_gstin
    FOREIGN KEY (company_id, company_gstin_id)
    REFERENCES mc_gst_registration (company_id, id)
    ON UPDATE RESTRICT ON DELETE RESTRICT NOT VALID;

ALTER TABLE mc_journal VALIDATE CONSTRAINT fk_mc_journal_company_gstin;

-- 3. 6-Tier GST & Tax Allocation Provenance per Voucher Line / Item
CREATE TABLE mc_tax_allocation (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    journal_line_id uuid,
    inventory_allocation_id uuid,
    gst_registration_id uuid,
    stat_rule_pack_id uuid,
    rate_source_level varchar(32) NOT NULL,
    supply_nature varchar(32) NOT NULL,
    hsn_sac_code varchar(16),
    gst_uqc_code varchar(16),
    billed_qty numeric NOT NULL DEFAULT 0,
    commercial_taxable_value numeric NOT NULL DEFAULT 0,
    apportioned_expense_value numeric NOT NULL DEFAULT 0,
    deemed_mrp_taxable_value numeric,
    assessable_value numeric NOT NULL DEFAULT 0,
    cgst_rate_pct numeric NOT NULL DEFAULT 0,
    cgst_amount numeric NOT NULL DEFAULT 0,
    sgst_utgst_rate_pct numeric NOT NULL DEFAULT 0,
    sgst_utgst_amount numeric NOT NULL DEFAULT 0,
    igst_rate_pct numeric NOT NULL DEFAULT 0,
    igst_amount numeric NOT NULL DEFAULT 0,
    cess_rate_pct numeric NOT NULL DEFAULT 0,
    cess_per_unit numeric NOT NULL DEFAULT 0,
    cess_amount numeric NOT NULL DEFAULT 0,
    is_rcm boolean NOT NULL DEFAULT false,
    itc_eligibility varchar(28) NOT NULL DEFAULT 'NOT_APPLICABLE',
    is_uncertain_transaction boolean NOT NULL DEFAULT false,
    uncertain_reason_code varchar(64),
    conflict_accepted_by uuid,
    conflict_accepted_reason varchar(255),
    effective_filing_period varchar(7),
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_tax_allocation PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_tax_allocation_id UNIQUE (id),
    CONSTRAINT fk_mc_tax_alloc_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tax_alloc_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tax_alloc_line FOREIGN KEY (company_id, journal_line_id)
        REFERENCES mc_journal_line (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tax_alloc_inv FOREIGN KEY (company_id, inventory_allocation_id)
        REFERENCES mc_inventory_allocation (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tax_alloc_gst_reg FOREIGN KEY (company_id, gst_registration_id)
        REFERENCES mc_gst_registration (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tax_alloc_rule_pack FOREIGN KEY (stat_rule_pack_id)
        REFERENCES mc_stat_rule_pack (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tax_alloc_accepted_by FOREIGN KEY (conflict_accepted_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_tax_alloc_source_level CHECK (
        rate_source_level IN (
            'VOUCHER_LINE_OVERRIDE',
            'ACCOUNTING_LEDGER',
            'STOCK_ITEM',
            'STOCK_GROUP',
            'ACCOUNT_GROUP',
            'COMPANY_GST_REGISTRATION'
        )
    ),
    CONSTRAINT chk_mc_tax_alloc_supply_nature CHECK (
        supply_nature IN (
            'INTRA_STATE_TAXABLE',
            'INTER_STATE_TAXABLE',
            'EXPORT_WPAY',
            'EXPORT_WOPAY',
            'SEZ_WPAY',
            'SEZ_WOPAY',
            'DEEMED_EXPORT',
            'EXEMPT',
            'NIL_RATED',
            'NON_GST',
            'REVERSE_CHARGE_INWARD'
        )
    ),
    CONSTRAINT chk_mc_tax_alloc_itc CHECK (
        itc_eligibility IN (
            'ELIGIBLE_INPUTS',
            'ELIGIBLE_CAPITAL_GOODS',
            'ELIGIBLE_INPUT_SERVICES',
            'INELIGIBLE_17_5',
            'NOT_APPLICABLE'
        )
    ),
    CONSTRAINT chk_mc_tax_alloc_values CHECK (
        billed_qty >= 0 AND billed_qty < 1000000000000000000 AND billed_qty = trunc(billed_qty, 6)
        AND commercial_taxable_value >= 0 AND commercial_taxable_value < 1000000000000000000 AND commercial_taxable_value = trunc(commercial_taxable_value, 6)
        AND apportioned_expense_value >= 0 AND apportioned_expense_value < 1000000000000000000 AND apportioned_expense_value = trunc(apportioned_expense_value, 6)
        AND (deemed_mrp_taxable_value IS NULL OR (deemed_mrp_taxable_value >= 0 AND deemed_mrp_taxable_value < 1000000000000000000 AND deemed_mrp_taxable_value = trunc(deemed_mrp_taxable_value, 6)))
        AND assessable_value >= 0 AND assessable_value < 1000000000000000000 AND assessable_value = trunc(assessable_value, 6)
    ),
    CONSTRAINT chk_mc_tax_alloc_taxes CHECK (
        cgst_rate_pct >= 0 AND cgst_rate_pct <= 100 AND cgst_rate_pct = trunc(cgst_rate_pct, 6)
        AND cgst_amount >= 0 AND cgst_amount < 1000000000000000000 AND cgst_amount = trunc(cgst_amount, 6)
        AND sgst_utgst_rate_pct >= 0 AND sgst_utgst_rate_pct <= 100 AND sgst_utgst_rate_pct = trunc(sgst_utgst_rate_pct, 6)
        AND sgst_utgst_amount >= 0 AND sgst_utgst_amount < 1000000000000000000 AND sgst_utgst_amount = trunc(sgst_utgst_amount, 6)
        AND igst_rate_pct >= 0 AND igst_rate_pct <= 100 AND igst_rate_pct = trunc(igst_rate_pct, 6)
        AND igst_amount >= 0 AND igst_amount < 1000000000000000000 AND igst_amount = trunc(igst_amount, 6)
        AND cess_rate_pct >= 0 AND cess_rate_pct <= 500 AND cess_rate_pct = trunc(cess_rate_pct, 6)
        AND cess_per_unit >= 0 AND cess_per_unit < 1000000000000000000 AND cess_per_unit = trunc(cess_per_unit, 6)
        AND cess_amount >= 0 AND cess_amount < 1000000000000000000 AND cess_amount = trunc(cess_amount, 6)
    )
);

CREATE INDEX idx_mc_tax_alloc_journal ON mc_tax_allocation (company_id, journal_id);
CREATE INDEX idx_mc_tax_alloc_filing_period
    ON mc_tax_allocation (company_id, gst_registration_id, effective_filing_period);
CREATE INDEX idx_mc_tax_alloc_uncertain
    ON mc_tax_allocation (company_id, is_uncertain_transaction)
    WHERE is_uncertain_transaction = true;

ALTER TABLE mc_tax_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_tax_allocation FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_tax_allocation_tenant_isolation ON mc_tax_allocation
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 4. IMS (Invoice Management System) Inward Invoices & Action Log (TallyPrime Rel 7.0-7.2)
CREATE TABLE mc_ims_inward_invoice (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    gst_registration_id uuid NOT NULL,
    supplier_gstin varchar(15) NOT NULL,
    supplier_legal_name varchar(200),
    doc_type varchar(16) NOT NULL DEFAULT 'INVOICE',
    invoice_number varchar(64) NOT NULL,
    invoice_date date NOT NULL,
    place_of_supply_state varchar(4) NOT NULL,
    is_rcm boolean NOT NULL DEFAULT false,
    portal_return_period varchar(7) NOT NULL,
    effective_filing_period varchar(7) NOT NULL,
    portal_taxable_value numeric NOT NULL DEFAULT 0,
    portal_igst numeric NOT NULL DEFAULT 0,
    portal_cgst numeric NOT NULL DEFAULT 0,
    portal_sgst numeric NOT NULL DEFAULT 0,
    portal_cess numeric NOT NULL DEFAULT 0,
    portal_itc_amount numeric NOT NULL DEFAULT 0,
    itc_reduction_amount numeric NOT NULL DEFAULT 0,
    ims_action varchar(16) NOT NULL DEFAULT 'NO_ACTION',
    remarks varchar(255),
    match_status varchar(24) NOT NULL DEFAULT 'MISSING_IN_BOOKS',
    matched_journal_id uuid,
    synced_to_portal boolean NOT NULL DEFAULT false,
    version_no bigint NOT NULL DEFAULT 1,
    imported_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_ims_inward_invoice PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_ims_inward_invoice_id UNIQUE (id),
    CONSTRAINT uq_mc_ims_inward_portal_doc UNIQUE (
        company_id, gst_registration_id, supplier_gstin, doc_type, invoice_number, invoice_date
    ),
    CONSTRAINT fk_mc_ims_inward_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_ims_inward_gst_reg FOREIGN KEY (company_id, gst_registration_id)
        REFERENCES mc_gst_registration (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_ims_inward_matched_journal FOREIGN KEY (company_id, matched_journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_ims_inward_doc_type CHECK (
        doc_type IN ('INVOICE', 'DEBIT_NOTE', 'CREDIT_NOTE', 'AMENDMENT')
    ),
    CONSTRAINT chk_mc_ims_inward_action CHECK (
        ims_action IN ('NO_ACTION', 'ACCEPT', 'REJECT', 'PENDING')
    ),
    CONSTRAINT chk_mc_ims_inward_match_status CHECK (
        match_status IN ('EXACT_MATCH', 'PARTIAL_MISMATCH', 'MISSING_IN_BOOKS', 'MISSING_IN_PORTAL')
    ),
    CONSTRAINT chk_mc_ims_inward_amounts CHECK (
        portal_taxable_value >= 0 AND portal_taxable_value < 1000000000000000000 AND portal_taxable_value = trunc(portal_taxable_value, 6)
        AND portal_igst >= 0 AND portal_igst < 1000000000000000000 AND portal_igst = trunc(portal_igst, 6)
        AND portal_cgst >= 0 AND portal_cgst < 1000000000000000000 AND portal_cgst = trunc(portal_cgst, 6)
        AND portal_sgst >= 0 AND portal_sgst < 1000000000000000000 AND portal_sgst = trunc(portal_sgst, 6)
        AND portal_cess >= 0 AND portal_cess < 1000000000000000000 AND portal_cess = trunc(portal_cess, 6)
        AND portal_itc_amount >= 0 AND portal_itc_amount < 1000000000000000000 AND portal_itc_amount = trunc(portal_itc_amount, 6)
        AND itc_reduction_amount >= 0 AND itc_reduction_amount < 1000000000000000000 AND itc_reduction_amount = trunc(itc_reduction_amount, 6)
        AND itc_reduction_amount <= portal_itc_amount
    ),
    CONSTRAINT chk_mc_ims_inward_rel72_remarks CHECK (
        (ims_action <> 'REJECT' AND itc_reduction_amount = 0)
        OR (remarks IS NOT NULL AND length(trim(remarks)) > 0)
    ),
    CONSTRAINT chk_mc_ims_inward_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_ims_inward_period_action
    ON mc_ims_inward_invoice (company_id, gst_registration_id, effective_filing_period, ims_action);

ALTER TABLE mc_ims_inward_invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_ims_inward_invoice FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_ims_inward_invoice_tenant_isolation ON mc_ims_inward_invoice
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_ims_action_log (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    ims_invoice_id uuid NOT NULL,
    prior_action varchar(16) NOT NULL,
    new_action varchar(16) NOT NULL,
    itc_reduction_amount numeric NOT NULL DEFAULT 0,
    remarks varchar(255),
    effective_filing_period varchar(7) NOT NULL,
    pushed_to_gstn boolean NOT NULL DEFAULT false,
    actor_principal_id uuid,
    acted_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_ims_action_log PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_ims_action_log_id UNIQUE (id),
    CONSTRAINT fk_mc_ims_action_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_ims_action_invoice FOREIGN KEY (company_id, ims_invoice_id)
        REFERENCES mc_ims_inward_invoice (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_ims_action_actor FOREIGN KEY (actor_principal_id)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_ims_action_prior CHECK (
        prior_action IN ('NO_ACTION', 'ACCEPT', 'REJECT', 'PENDING')
    ),
    CONSTRAINT chk_mc_ims_action_new CHECK (
        new_action IN ('NO_ACTION', 'ACCEPT', 'REJECT', 'PENDING')
    ),
    CONSTRAINT chk_mc_ims_action_reduction CHECK (
        itc_reduction_amount >= 0
        AND itc_reduction_amount < 1000000000000000000
        AND itc_reduction_amount = trunc(itc_reduction_amount, 6)
    )
);

CREATE INDEX idx_mc_ims_action_log_invoice ON mc_ims_action_log (company_id, ims_invoice_id, acted_at DESC);

ALTER TABLE mc_ims_action_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_ims_action_log FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_ims_action_log_tenant_isolation ON mc_ims_action_log
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 5. e-Invoice IRN, e-Way Bill & 30-Day Encrypted Connector Sessions (Rel 7.1/7.2)
CREATE TABLE mc_einvoice_irn (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    gst_registration_id uuid NOT NULL,
    irn varchar(64),
    ack_no varchar(32),
    ack_timestamp timestamptz(6),
    signed_invoice_jws text,
    signed_qr_code text,
    status varchar(24) NOT NULL DEFAULT 'GENERATED',
    cancel_reason_code varchar(8),
    cancel_remarks varchar(255),
    cancelled_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_einvoice_irn PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_einvoice_irn_id UNIQUE (id),
    CONSTRAINT uq_mc_einvoice_irn_hash UNIQUE (company_id, irn),
    CONSTRAINT fk_mc_einvoice_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_einvoice_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_einvoice_gst_reg FOREIGN KEY (company_id, gst_registration_id)
        REFERENCES mc_gst_registration (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_einvoice_status CHECK (
        status IN ('GENERATED', 'CANCELLED', 'FAILED', 'OFFLINE_EXPORTED')
    ),
    CONSTRAINT chk_mc_einvoice_irn_fmt CHECK (irn IS NULL OR irn ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT chk_mc_einvoice_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_einvoice_journal ON mc_einvoice_irn (company_id, journal_id);

ALTER TABLE mc_einvoice_irn ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_einvoice_irn FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_einvoice_irn_tenant_isolation ON mc_einvoice_irn
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_eway_bill (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    einvoice_irn_id uuid,
    gst_registration_id uuid NOT NULL,
    ewb_no varchar(20),
    ewb_timestamp timestamptz(6),
    valid_upto timestamptz(6),
    supply_type varchar(16) NOT NULL DEFAULT 'OUTWARD',
    sub_supply_type varchar(24) NOT NULL DEFAULT 'SUPPLY',
    distance_km integer NOT NULL DEFAULT 0,
    transporter_id varchar(15),
    transporter_name varchar(160),
    transport_mode varchar(16) NOT NULL DEFAULT 'ROAD',
    vehicle_no varchar(20),
    vehicle_type varchar(16) NOT NULL DEFAULT 'REGULAR',
    transport_doc_no varchar(32),
    transport_doc_date date,
    status varchar(24) NOT NULL DEFAULT 'GENERATED',
    cancel_remarks varchar(255),
    cancelled_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_eway_bill PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_eway_bill_id UNIQUE (id),
    CONSTRAINT fk_mc_eway_bill_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_eway_bill_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_eway_bill_irn FOREIGN KEY (company_id, einvoice_irn_id)
        REFERENCES mc_einvoice_irn (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_eway_bill_gst_reg FOREIGN KEY (company_id, gst_registration_id)
        REFERENCES mc_gst_registration (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_eway_bill_distance CHECK (distance_km BETWEEN 0 AND 4000),
    CONSTRAINT chk_mc_eway_bill_mode CHECK (transport_mode IN ('ROAD', 'RAIL', 'AIR', 'SHIP')),
    CONSTRAINT chk_mc_eway_bill_veh_type CHECK (vehicle_type IN ('REGULAR', 'ODC')),
    CONSTRAINT chk_mc_eway_bill_status CHECK (
        status IN ('GENERATED', 'PART_A_ONLY', 'CANCELLED', 'FAILED', 'OFFLINE_EXPORTED')
    ),
    CONSTRAINT chk_mc_eway_bill_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_eway_bill_journal ON mc_eway_bill (company_id, journal_id);

ALTER TABLE mc_eway_bill ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_eway_bill FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_eway_bill_tenant_isolation ON mc_eway_bill
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_connector_session (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    gst_registration_id uuid NOT NULL,
    connector_type varchar(24) NOT NULL,
    api_username varchar(120) NOT NULL,
    session_token_gcm bytea NOT NULL,
    sek_gcm bytea,
    key_generation_id uuid,
    issued_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz(6) NOT NULL,
    last_reused_at timestamptz(6),
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version_no bigint NOT NULL DEFAULT 1,
    CONSTRAINT pk_mc_connector_session PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_connector_session_id UNIQUE (id),
    CONSTRAINT fk_mc_connector_session_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_connector_session_gst_reg FOREIGN KEY (company_id, gst_registration_id)
        REFERENCES mc_gst_registration (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_connector_session_type CHECK (
        connector_type IN ('EINVOICE_NIC', 'EWAYBILL_NIC', 'GSTN_IMS', 'GSP_UNIFIED')
    ),
    CONSTRAINT chk_mc_connector_session_token_len CHECK (
        octet_length(session_token_gcm) BETWEEN 1 AND 16384
    ),
    CONSTRAINT chk_mc_connector_session_30day_window CHECK (
        expires_at > issued_at
        AND expires_at <= issued_at + interval '31 days'
    ),
    CONSTRAINT chk_mc_connector_session_state CHECK (
        state IN ('ACTIVE', 'EXPIRED', 'REVOKED')
    ),
    CONSTRAINT chk_mc_connector_session_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_connector_session_active
    ON mc_connector_session (company_id, gst_registration_id, connector_type, state, expires_at DESC);

ALTER TABLE mc_connector_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_connector_session FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_connector_session_tenant_isolation ON mc_connector_session
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 6. TDS / TCS Compliance Engine (194C, 194J, 194H, 194I, 194Q, 206C + FVU 1.2)
CREATE TABLE mc_tds_nature (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    section_code varchar(16) NOT NULL,
    payment_code varchar(8) NOT NULL,
    name varchar(160) NOT NULL,
    rate_individual_huf_pct numeric NOT NULL,
    rate_other_pct numeric NOT NULL,
    no_pan_rate_pct numeric NOT NULL DEFAULT 20,
    single_bill_threshold numeric NOT NULL DEFAULT 0,
    annual_aggregate_threshold numeric NOT NULL DEFAULT 0,
    is_tcs boolean NOT NULL DEFAULT false,
    effective_from date NOT NULL DEFAULT DATE '2025-04-01',
    active boolean NOT NULL DEFAULT true,
    version_no bigint NOT NULL DEFAULT 1,
    CONSTRAINT pk_mc_tds_nature PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_tds_nature_id UNIQUE (id),
    CONSTRAINT uq_mc_tds_nature_company_code UNIQUE (company_id, section_code, payment_code, effective_from),
    CONSTRAINT fk_mc_tds_nature_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_tds_nature_rates CHECK (
        rate_individual_huf_pct >= 0 AND rate_individual_huf_pct <= 100 AND rate_individual_huf_pct = trunc(rate_individual_huf_pct, 6)
        AND rate_other_pct >= 0 AND rate_other_pct <= 100 AND rate_other_pct = trunc(rate_other_pct, 6)
        AND no_pan_rate_pct >= 0 AND no_pan_rate_pct <= 100 AND no_pan_rate_pct = trunc(no_pan_rate_pct, 6)
    ),
    CONSTRAINT chk_mc_tds_nature_thresholds CHECK (
        single_bill_threshold >= 0 AND single_bill_threshold < 1000000000000000000 AND single_bill_threshold = trunc(single_bill_threshold, 6)
        AND annual_aggregate_threshold >= 0 AND annual_aggregate_threshold < 1000000000000000000 AND annual_aggregate_threshold = trunc(annual_aggregate_threshold, 6)
    ),
    CONSTRAINT chk_mc_tds_nature_version_no CHECK (version_no >= 1)
);

ALTER TABLE mc_tds_nature ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_tds_nature FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_tds_nature_tenant_isolation ON mc_tds_nature
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_tds_challan (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    payment_journal_id uuid,
    bank_ledger_id uuid,
    section_code varchar(16) NOT NULL,
    quarter varchar(2) NOT NULL,
    assessment_year varchar(9) NOT NULL,
    bsr_code char(7) NOT NULL,
    challan_serial_no varchar(10) NOT NULL,
    tender_date date NOT NULL,
    cheque_dd_utr_no varchar(32),
    tds_amount numeric NOT NULL DEFAULT 0,
    surcharge_amount numeric NOT NULL DEFAULT 0,
    cess_amount numeric NOT NULL DEFAULT 0,
    interest_amount numeric NOT NULL DEFAULT 0,
    fee_234e_amount numeric NOT NULL DEFAULT 0,
    other_penalty_amount numeric NOT NULL DEFAULT 0,
    total_challan_amount numeric NOT NULL,
    minor_head_code varchar(3) NOT NULL DEFAULT '200',
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_tds_challan PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_tds_challan_id UNIQUE (id),
    CONSTRAINT uq_mc_tds_challan_bsr_serial UNIQUE (company_id, bsr_code, tender_date, challan_serial_no),
    CONSTRAINT fk_mc_tds_challan_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tds_challan_journal FOREIGN KEY (company_id, payment_journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tds_challan_bank FOREIGN KEY (company_id, bank_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_tds_challan_quarter CHECK (quarter IN ('Q1', 'Q2', 'Q3', 'Q4')),
    CONSTRAINT chk_mc_tds_challan_bsr CHECK (bsr_code ~ '^[0-9]{7}$'),
    CONSTRAINT chk_mc_tds_challan_minor_head CHECK (minor_head_code IN ('200', '400')),
    CONSTRAINT chk_mc_tds_challan_amounts CHECK (
        tds_amount >= 0 AND tds_amount < 1000000000000000000 AND tds_amount = trunc(tds_amount, 6)
        AND surcharge_amount >= 0 AND surcharge_amount < 1000000000000000000 AND surcharge_amount = trunc(surcharge_amount, 6)
        AND cess_amount >= 0 AND cess_amount < 1000000000000000000 AND cess_amount = trunc(cess_amount, 6)
        AND interest_amount >= 0 AND interest_amount < 1000000000000000000 AND interest_amount = trunc(interest_amount, 6)
        AND fee_234e_amount >= 0 AND fee_234e_amount < 1000000000000000000 AND fee_234e_amount = trunc(fee_234e_amount, 6)
        AND other_penalty_amount >= 0 AND other_penalty_amount < 1000000000000000000 AND other_penalty_amount = trunc(other_penalty_amount, 6)
        AND total_challan_amount > 0 AND total_challan_amount < 1000000000000000000 AND total_challan_amount = trunc(total_challan_amount, 6)
        AND total_challan_amount = tds_amount + surcharge_amount + cess_amount + interest_amount + fee_234e_amount + other_penalty_amount
    ),
    CONSTRAINT chk_mc_tds_challan_version_no CHECK (version_no >= 1)
);

ALTER TABLE mc_tds_challan ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_tds_challan FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_tds_challan_tenant_isolation ON mc_tds_challan
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_tds_deduction (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    journal_line_id uuid,
    party_ledger_id uuid NOT NULL,
    tds_ledger_id uuid NOT NULL,
    tds_nature_id uuid NOT NULL,
    challan_id uuid,
    deduction_date date NOT NULL,
    gross_credited_amount numeric NOT NULL,
    applied_rate_pct numeric NOT NULL,
    is_no_pan_higher_rate boolean NOT NULL DEFAULT false,
    lower_deduction_cert_no varchar(64),
    tds_amount numeric NOT NULL,
    surcharge_amount numeric NOT NULL DEFAULT 0,
    cess_amount numeric NOT NULL DEFAULT 0,
    total_tax_deducted numeric NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_tds_deduction PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_tds_deduction_id UNIQUE (id),
    CONSTRAINT fk_mc_tds_deduction_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tds_deduction_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tds_deduction_line FOREIGN KEY (company_id, journal_line_id)
        REFERENCES mc_journal_line (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tds_deduction_party FOREIGN KEY (company_id, party_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tds_deduction_tds_ledger FOREIGN KEY (company_id, tds_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tds_deduction_nature FOREIGN KEY (company_id, tds_nature_id)
        REFERENCES mc_tds_nature (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_tds_deduction_challan FOREIGN KEY (company_id, challan_id)
        REFERENCES mc_tds_challan (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_tds_deduction_amounts CHECK (
        gross_credited_amount > 0 AND gross_credited_amount < 1000000000000000000 AND gross_credited_amount = trunc(gross_credited_amount, 6)
        AND applied_rate_pct >= 0 AND applied_rate_pct <= 100 AND applied_rate_pct = trunc(applied_rate_pct, 6)
        AND tds_amount >= 0 AND tds_amount < 1000000000000000000 AND tds_amount = trunc(tds_amount, 6)
        AND surcharge_amount >= 0 AND surcharge_amount < 1000000000000000000 AND surcharge_amount = trunc(surcharge_amount, 6)
        AND cess_amount >= 0 AND cess_amount < 1000000000000000000 AND cess_amount = trunc(cess_amount, 6)
        AND total_tax_deducted >= 0 AND total_tax_deducted < 1000000000000000000 AND total_tax_deducted = trunc(total_tax_deducted, 6)
        AND total_tax_deducted = tds_amount + surcharge_amount + cess_amount
    )
);

CREATE INDEX idx_mc_tds_deduction_party_fy
    ON mc_tds_deduction (company_id, party_ledger_id, tds_nature_id, deduction_date);
CREATE INDEX idx_mc_tds_deduction_challan
    ON mc_tds_deduction (company_id, challan_id);

ALTER TABLE mc_tds_deduction ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_tds_deduction FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_tds_deduction_tenant_isolation ON mc_tds_deduction
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 7. Seed Default India FY 2025-26 Statutory Rule Pack in mc_stat_rule_pack
INSERT INTO mc_stat_rule_pack (
    id,
    pack_code,
    country_code,
    fiscal_year_label,
    effective_from,
    effective_to,
    schema_version,
    rules_json,
    pack_sha256,
    signature_alg,
    active
)
VALUES (
    '01950000-0000-7000-8000-000020252026'::uuid,
    'IN-FY2025-26-V1',
    'IN',
    'FY 2025-26',
    DATE '2025-04-01',
    DATE '2026-03-31',
    '1.0.0',
    $json${
      "packCode": "IN-FY2025-26-V1",
      "countryCode": "IN",
      "fiscalYear": "FY 2025-26",
      "effectiveFrom": "2025-04-01",
      "effectiveTo": "2026-03-31",
      "schemaVersion": "1.0.0",
      "gst": {
        "slabsPct": ["0.00", "0.10", "0.25", "1.50", "3.00", "5.00", "12.00", "18.00", "28.00", "40.00"],
        "b2clInvoiceThresholdInr": "100000.00",
        "ewayBillDefaultThresholdInr": "50000.00",
        "einvoiceTurnoverThresholdInr": "50000000.00",
        "slabLevelRoundingMode": "HALF_UP",
        "inheritanceHierarchy": [
          "VOUCHER_LINE_OVERRIDE",
          "ACCOUNTING_LEDGER",
          "STOCK_ITEM",
          "STOCK_GROUP",
          "ACCOUNT_GROUP",
          "COMPANY_GST_REGISTRATION"
        ],
        "hsnPhaseIii": {
          "mandatoryB2bB2cBifurcation": true,
          "turnoverUpTo5CrMinDigits": 4,
          "turnoverAbove5CrMinDigits": 6,
          "validDigitLengths": [4, 6, 8],
          "sacPrefix": "99",
          "omitUqcForPureSac": true
        },
        "tobaccoDeemedMrpRel72": {
          "enabled": true,
          "formula": "round((declared_mrp * billed_qty * 100) / (100 + aggregate_ad_valorem_rate_pct), currency_scale)",
          "missingMrpErrorCode": "MC-TAX-MRP-REQUIRED"
        },
        "imsRel72": {
          "supportedActions": ["NO_ACTION", "ACCEPT", "REJECT", "PENDING"],
          "partialItcReductionEnabled": true,
          "remarksMandatoryOnReject": true,
          "remarksMandatoryOnItcReduction": true
        },
        "connectorSessionRel72": {
          "defaultOutboundEnabled": false,
          "maxSessionWindowHours": 720
        }
      },
      "tdsTcs": {
        "fvuSchemaVersion": "1.2",
        "supportedForms": ["26Q", "27Q", "27EQ"],
        "defaultNoPanRatePct": "20.00",
        "sections": [
          {
            "sectionCode": "194C",
            "paymentCode": "94C",
            "name": "Payment to Contractors and Sub-Contractors",
            "rateIndividualHufPct": "1.00",
            "rateOtherPct": "2.00",
            "noPanRatePct": "20.00",
            "singleBillThresholdInr": "30000.00",
            "annualAggregateThresholdInr": "100000.00",
            "isTcs": false
          },
          {
            "sectionCode": "194J",
            "paymentCode": "94J",
            "name": "Fees for Professional Services",
            "rateIndividualHufPct": "10.00",
            "rateOtherPct": "10.00",
            "noPanRatePct": "20.00",
            "singleBillThresholdInr": "0.00",
            "annualAggregateThresholdInr": "50000.00",
            "isTcs": false
          },
          {
            "sectionCode": "194H",
            "paymentCode": "94H",
            "name": "Commission or Brokerage",
            "rateIndividualHufPct": "2.00",
            "rateOtherPct": "2.00",
            "noPanRatePct": "20.00",
            "singleBillThresholdInr": "0.00",
            "annualAggregateThresholdInr": "20000.00",
            "isTcs": false
          },
          {
            "sectionCode": "194I",
            "paymentCode": "94I",
            "name": "Rent of Land, Building or Furniture",
            "rateIndividualHufPct": "10.00",
            "rateOtherPct": "10.00",
            "noPanRatePct": "20.00",
            "singleBillThresholdInr": "0.00",
            "annualAggregateThresholdInr": "600000.00",
            "isTcs": false
          },
          {
            "sectionCode": "194Q",
            "paymentCode": "94Q",
            "name": "Purchase of Goods",
            "rateIndividualHufPct": "0.10",
            "rateOtherPct": "0.10",
            "noPanRatePct": "5.00",
            "singleBillThresholdInr": "0.00",
            "annualAggregateThresholdInr": "5000000.00",
            "isTcs": false
          },
          {
            "sectionCode": "206C",
            "paymentCode": "6CA",
            "name": "Tax Collected at Source",
            "rateIndividualHufPct": "1.00",
            "rateOtherPct": "1.00",
            "noPanRatePct": "5.00",
            "singleBillThresholdInr": "0.00",
            "annualAggregateThresholdInr": "5000000.00",
            "isTcs": true
          }
        ]
      }
    }$json$::jsonb,
    encode(sha256(convert_to('IN-FY2025-26-V1:1.0.0:2025-04-01:2026-03-31', 'UTF8')), 'hex'),
    'SHA256-DET',
    true
)
ON CONFLICT (pack_code) DO NOTHING;

-- Seed Standard TDS/TCS Natures Automatically on Indian Company Creation
CREATE OR REPLACE FUNCTION fn_mc_seed_company_tds_natures()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_prev_company text;
BEGIN
    IF NEW.country_code <> 'IN' THEN
        RETURN NEW;
    END IF;

    v_prev_company := current_setting('app.current_company_id', true);
    PERFORM set_config('app.current_company_id', NEW.id::text, true);

    INSERT INTO mc_tds_nature (
        id, company_id, section_code, payment_code, name,
        rate_individual_huf_pct, rate_other_pct, no_pan_rate_pct,
        single_bill_threshold, annual_aggregate_threshold, is_tcs, effective_from
    )
    VALUES
        (fn_mc_seed_uuid(NEW.id, 'tds:194C'), NEW.id, '194C', '94C', 'Payment to Contractors and Sub-Contractors', 1.00, 2.00, 20.00, 30000.00, 100000.00, false, DATE '2025-04-01'),
        (fn_mc_seed_uuid(NEW.id, 'tds:194J'), NEW.id, '194J', '94J', 'Fees for Professional Services', 10.00, 10.00, 20.00, 0.00, 50000.00, false, DATE '2025-04-01'),
        (fn_mc_seed_uuid(NEW.id, 'tds:194H'), NEW.id, '194H', '94H', 'Commission or Brokerage', 2.00, 2.00, 20.00, 0.00, 20000.00, false, DATE '2025-04-01'),
        (fn_mc_seed_uuid(NEW.id, 'tds:194I'), NEW.id, '194I', '94I', 'Rent of Land, Building or Furniture', 10.00, 10.00, 20.00, 0.00, 600000.00, false, DATE '2025-04-01'),
        (fn_mc_seed_uuid(NEW.id, 'tds:194Q'), NEW.id, '194Q', '94Q', 'Purchase of Goods', 0.10, 0.10, 5.00, 0.00, 5000000.00, false, DATE '2025-04-01'),
        (fn_mc_seed_uuid(NEW.id, 'tds:206C'), NEW.id, '206C', '6CA', 'Tax Collected at Source', 1.00, 1.00, 5.00, 0.00, 5000000.00, true, DATE '2025-04-01')
    ON CONFLICT (company_id, section_code, payment_code, effective_from) DO NOTHING;

    PERFORM set_config('app.current_company_id', COALESCE(v_prev_company, ''), true);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mc_company_seed_tds_natures
AFTER INSERT ON mc_company
FOR EACH ROW
EXECUTE FUNCTION fn_mc_seed_company_tds_natures();
