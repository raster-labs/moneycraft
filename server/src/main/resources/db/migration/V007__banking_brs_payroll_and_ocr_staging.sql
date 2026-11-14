SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Banking, Cheque Books, Auto-BRS, Optional Payroll,
-- OCR Document Review Queue & GIN pg_trgm SmartFind Indexes
-- Stream: db/migration/V007__banking_brs_payroll_and_ocr_staging.sql
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE EXTENSION pg_trgm;
    END IF;
END;
$$;

-- 1. Cheque Books & Individual Cheque Leaves
CREATE TABLE mc_cheque_book (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    bank_ledger_id uuid NOT NULL,
    book_name varchar(120) NOT NULL,
    prefix varchar(16) NOT NULL DEFAULT '',
    from_number bigint NOT NULL,
    to_number bigint NOT NULL,
    number_of_leaves integer NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_cheque_book PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_cheque_book_id UNIQUE (id),
    CONSTRAINT fk_mc_cheque_book_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cheque_book_bank FOREIGN KEY (company_id, bank_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_cheque_book_range CHECK (
        from_number >= 1
        AND to_number >= from_number
        AND number_of_leaves = (to_number - from_number + 1)
        AND number_of_leaves <= 10000
    ),
    CONSTRAINT chk_mc_cheque_book_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_cheque_book_bank ON mc_cheque_book (company_id, bank_ledger_id, active);

ALTER TABLE mc_cheque_book ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_cheque_book FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_cheque_book_tenant_isolation ON mc_cheque_book
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_cheque_leaf (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    cheque_book_id uuid NOT NULL,
    bank_ledger_id uuid NOT NULL,
    cheque_number varchar(24) NOT NULL,
    leaf_seq bigint NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'AVAILABLE',
    journal_id uuid,
    payee_name varchar(200),
    cheque_date date,
    amount numeric,
    remarks varchar(255),
    version_no bigint NOT NULL DEFAULT 1,
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_cheque_leaf PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_cheque_leaf_id UNIQUE (id),
    CONSTRAINT uq_mc_cheque_leaf_number UNIQUE (company_id, bank_ledger_id, cheque_number),
    CONSTRAINT fk_mc_cheque_leaf_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cheque_leaf_book FOREIGN KEY (company_id, cheque_book_id)
        REFERENCES mc_cheque_book (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cheque_leaf_bank FOREIGN KEY (company_id, bank_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_cheque_leaf_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_cheque_leaf_status CHECK (
        status IN ('AVAILABLE', 'ISSUED', 'BLANK_CANCELLED', 'SPOILED', 'POST_DATED')
    ),
    CONSTRAINT chk_mc_cheque_leaf_amount CHECK (
        amount IS NULL
        OR (amount >= 0 AND amount < 1000000000000000000 AND amount = trunc(amount, 6))
    ),
    CONSTRAINT chk_mc_cheque_leaf_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_cheque_leaf_status ON mc_cheque_leaf (company_id, cheque_book_id, status, leaf_seq);

ALTER TABLE mc_cheque_leaf ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_cheque_leaf FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_cheque_leaf_tenant_isolation ON mc_cheque_leaf
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 2. Voucher Bank Allocations (Instrument No, Instrument Date, Bankers Date for BRS)
CREATE TABLE mc_bank_allocation (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    journal_line_id uuid NOT NULL,
    bank_ledger_id uuid NOT NULL,
    transaction_type varchar(24) NOT NULL DEFAULT 'CHEQUE',
    instrument_no varchar(64),
    instrument_date date,
    bankers_date date,
    favouring_name varchar(200),
    transfer_mode_ref varchar(80),
    side char(2) NOT NULL,
    amount numeric NOT NULL,
    reconciliation_status varchar(24) NOT NULL DEFAULT 'UNRECONCILED',
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_bank_allocation PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_bank_allocation_id UNIQUE (id),
    CONSTRAINT fk_mc_bank_alloc_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bank_alloc_journal FOREIGN KEY (company_id, journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bank_alloc_line FOREIGN KEY (company_id, journal_line_id)
        REFERENCES mc_journal_line (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bank_alloc_bank FOREIGN KEY (company_id, bank_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_bank_alloc_txn_type CHECK (
        transaction_type IN (
            'CHEQUE',
            'NEFT',
            'RTGS',
            'IMPS',
            'UPI',
            'CASH_DEPOSIT',
            'CARD',
            'ECS_NACH',
            'BANK_CHARGES',
            'OTHERS'
        )
    ),
    CONSTRAINT chk_mc_bank_alloc_side CHECK (side IN ('DR', 'CR')),
    CONSTRAINT chk_mc_bank_alloc_amount CHECK (
        amount > 0
        AND amount < 1000000000000000000
        AND amount = trunc(amount, 6)
    ),
    CONSTRAINT chk_mc_bank_alloc_recon_status CHECK (
        reconciliation_status IN ('UNRECONCILED', 'AUTO_MATCHED', 'MANUALLY_RECONCILED')
    ),
    CONSTRAINT chk_mc_bank_alloc_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_bank_alloc_brs
    ON mc_bank_allocation (company_id, bank_ledger_id, reconciliation_status, instrument_date, amount);
CREATE INDEX idx_mc_bank_alloc_trgm_instrument
    ON mc_bank_allocation USING gin (instrument_no gin_trgm_ops)
    WHERE instrument_no IS NOT NULL;
CREATE INDEX idx_mc_bank_alloc_trgm_favouring
    ON mc_bank_allocation USING gin (favouring_name gin_trgm_ops)
    WHERE favouring_name IS NOT NULL;

ALTER TABLE mc_bank_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_bank_allocation FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_bank_allocation_tenant_isolation ON mc_bank_allocation
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 3. Bank Statement Imports (CSV, MT940, CAMT.053) & Auto-BRS Match Engine
CREATE TABLE mc_bank_statement_import (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    bank_ledger_id uuid NOT NULL,
    file_name varchar(255) NOT NULL,
    source_format varchar(20) NOT NULL,
    file_sha256 bytea NOT NULL,
    statement_from_date date NOT NULL,
    statement_to_date date NOT NULL,
    opening_balance numeric NOT NULL DEFAULT 0,
    closing_balance numeric NOT NULL DEFAULT 0,
    total_lines integer NOT NULL DEFAULT 0,
    matched_lines integer NOT NULL DEFAULT 0,
    imported_by uuid,
    imported_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_bank_statement_import PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_bank_statement_import_id UNIQUE (id),
    CONSTRAINT fk_mc_bank_stmt_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bank_stmt_bank FOREIGN KEY (company_id, bank_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bank_stmt_imported_by FOREIGN KEY (imported_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_bank_stmt_format CHECK (
        source_format IN ('CSV', 'MT940', 'CAMT_053', 'EXCEL')
    ),
    CONSTRAINT chk_mc_bank_stmt_sha256 CHECK (octet_length(file_sha256) = 32),
    CONSTRAINT chk_mc_bank_stmt_dates CHECK (statement_to_date >= statement_from_date),
    CONSTRAINT chk_mc_bank_stmt_balances CHECK (
        abs(opening_balance) < 1000000000000000000 AND opening_balance = trunc(opening_balance, 6)
        AND abs(closing_balance) < 1000000000000000000 AND closing_balance = trunc(closing_balance, 6)
    ),
    CONSTRAINT chk_mc_bank_stmt_counts CHECK (
        total_lines >= 0 AND matched_lines >= 0 AND matched_lines <= total_lines
    )
);

ALTER TABLE mc_bank_statement_import ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_bank_statement_import FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_bank_statement_import_tenant_isolation ON mc_bank_statement_import
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_bank_statement_line (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    statement_import_id uuid NOT NULL,
    bank_ledger_id uuid NOT NULL,
    line_no integer NOT NULL,
    txn_date date NOT NULL,
    value_date date,
    instrument_no varchar(64),
    utr_reference varchar(80),
    description text NOT NULL DEFAULT '',
    withdrawal_amount numeric NOT NULL DEFAULT 0,
    deposit_amount numeric NOT NULL DEFAULT 0,
    running_balance numeric,
    match_status varchar(24) NOT NULL DEFAULT 'UNMATCHED',
    CONSTRAINT pk_mc_bank_statement_line PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_bank_statement_line_id UNIQUE (id),
    CONSTRAINT uq_mc_bank_statement_line_seq UNIQUE (company_id, statement_import_id, line_no),
    CONSTRAINT fk_mc_bank_stmt_line_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bank_stmt_line_import FOREIGN KEY (company_id, statement_import_id)
        REFERENCES mc_bank_statement_import (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_bank_stmt_line_bank FOREIGN KEY (company_id, bank_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_bank_stmt_line_amounts CHECK (
        withdrawal_amount >= 0 AND withdrawal_amount < 1000000000000000000 AND withdrawal_amount = trunc(withdrawal_amount, 6)
        AND deposit_amount >= 0 AND deposit_amount < 1000000000000000000 AND deposit_amount = trunc(deposit_amount, 6)
        AND (withdrawal_amount > 0 OR deposit_amount > 0)
        AND (running_balance IS NULL OR (abs(running_balance) < 1000000000000000000 AND running_balance = trunc(running_balance, 6)))
    ),
    CONSTRAINT chk_mc_bank_stmt_line_status CHECK (
        match_status IN ('UNMATCHED', 'SUGGESTED', 'RECONCILED', 'VOUCHER_CREATED', 'IGNORED')
    )
);

CREATE INDEX idx_mc_bank_stmt_line_unmatched
    ON mc_bank_statement_line (company_id, bank_ledger_id, match_status, txn_date);
CREATE INDEX idx_mc_bank_stmt_line_trgm_desc
    ON mc_bank_statement_line USING gin (description gin_trgm_ops);
CREATE INDEX idx_mc_bank_stmt_line_trgm_utr
    ON mc_bank_statement_line USING gin (utr_reference gin_trgm_ops)
    WHERE utr_reference IS NOT NULL;

ALTER TABLE mc_bank_statement_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_bank_statement_line FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_bank_statement_line_tenant_isolation ON mc_bank_statement_line
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_brs_match (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    statement_line_id uuid NOT NULL,
    bank_allocation_id uuid NOT NULL,
    match_method varchar(24) NOT NULL,
    confidence_score numeric NOT NULL DEFAULT 100,
    matched_amount numeric NOT NULL,
    bankers_date date NOT NULL,
    reconciled_by uuid,
    reconciled_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_brs_match PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_brs_match_id UNIQUE (id),
    CONSTRAINT uq_mc_brs_match_pair UNIQUE (company_id, statement_line_id, bank_allocation_id),
    CONSTRAINT fk_mc_brs_match_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_brs_match_stmt_line FOREIGN KEY (company_id, statement_line_id)
        REFERENCES mc_bank_statement_line (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_brs_match_bank_alloc FOREIGN KEY (company_id, bank_allocation_id)
        REFERENCES mc_bank_allocation (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_brs_match_reconciled_by FOREIGN KEY (reconciled_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_brs_match_method CHECK (
        match_method IN ('AUTO_EXACT', 'AUTO_INSTRUMENT_WINDOW', 'MANUAL')
    ),
    CONSTRAINT chk_mc_brs_match_confidence CHECK (
        confidence_score >= 0 AND confidence_score <= 100 AND confidence_score = trunc(confidence_score, 6)
    ),
    CONSTRAINT chk_mc_brs_match_amount CHECK (
        matched_amount > 0 AND matched_amount < 1000000000000000000 AND matched_amount = trunc(matched_amount, 6)
    )
);

ALTER TABLE mc_brs_match ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_brs_match FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_brs_match_tenant_isolation ON mc_brs_match
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 4. Optional Payroll Masters (Pay Heads & Employees)
CREATE TABLE mc_payroll_head (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    pay_head_type varchar(40) NOT NULL,
    calculation_type varchar(24) NOT NULL,
    ledger_id uuid NOT NULL,
    affect_net_salary boolean NOT NULL DEFAULT true,
    payslip_display_name varchar(160),
    active boolean NOT NULL DEFAULT true,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_payroll_head PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_payroll_head_id UNIQUE (id),
    CONSTRAINT fk_mc_payroll_head_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_payroll_head_ledger FOREIGN KEY (company_id, ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_payroll_head_type CHECK (
        pay_head_type IN (
            'EARNINGS_FOR_EMPLOYEES',
            'DEDUCTIONS_FROM_EMPLOYEES',
            'EMPLOYEES_STATUTORY_DEDUCTIONS',
            'EMPLOYER_STATUTORY_CONTRIBUTIONS',
            'EMPLOYER_OTHER_CHARGES',
            'BONUS',
            'GRATUITY',
            'LOANS_AND_ADVANCES',
            'REIMBURSEMENTS'
        )
    ),
    CONSTRAINT chk_mc_payroll_head_calc CHECK (
        calculation_type IN (
            'ON_ATTENDANCE',
            'ON_PRODUCTION',
            'COMPUTED_VALUE',
            'FLAT_RATE',
            'USER_DEFINED'
        )
    ),
    CONSTRAINT chk_mc_payroll_head_version_no CHECK (version_no >= 1)
);

CREATE UNIQUE INDEX uq_mc_payroll_head_company_lower_name
    ON mc_payroll_head (company_id, lower(name));

ALTER TABLE mc_payroll_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_payroll_head FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_payroll_head_tenant_isolation ON mc_payroll_head
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

CREATE TABLE mc_employee (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    employee_code varchar(32) NOT NULL,
    name varchar(160) NOT NULL,
    department varchar(120),
    designation varchar(120),
    cost_centre_id uuid,
    date_of_joining date NOT NULL,
    date_of_resignation date,
    pan_siv bytea,
    pan_plain_masked varchar(16),
    uan_no varchar(24),
    esi_no varchar(24),
    bank_details_gcm bytea,
    active boolean NOT NULL DEFAULT true,
    version_no bigint NOT NULL DEFAULT 1,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_employee PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_employee_id UNIQUE (id),
    CONSTRAINT uq_mc_employee_code UNIQUE (company_id, employee_code),
    CONSTRAINT fk_mc_employee_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_employee_cost_centre FOREIGN KEY (company_id, cost_centre_id)
        REFERENCES mc_cost_centre (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_employee_dates CHECK (
        date_of_resignation IS NULL OR date_of_resignation >= date_of_joining
    ),
    CONSTRAINT chk_mc_employee_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_employee_trgm_name ON mc_employee USING gin (name gin_trgm_ops);
CREATE INDEX idx_mc_employee_trgm_code ON mc_employee USING gin (employee_code gin_trgm_ops);

ALTER TABLE mc_employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_employee FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_employee_tenant_isolation ON mc_employee
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );

-- 5. Docs-by-Ira Style OCR Document Review Staging Queue (Mandatory Human Verification)
CREATE TABLE mc_imported_doc_staging (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    source_file_name varchar(255) NOT NULL,
    content_type varchar(100) NOT NULL,
    file_sha256 bytea NOT NULL,
    encrypted_blob_gcm bytea,
    ocr_provider varchar(64) NOT NULL DEFAULT 'LOCAL_MANUAL',
    review_status varchar(32) NOT NULL DEFAULT 'EXTRACTED_PENDING_REVIEW',
    target_voucher_category varchar(24) NOT NULL DEFAULT 'PURCHASE',
    extracted_supplier_name varchar(200),
    extracted_supplier_gstin varchar(15),
    extracted_invoice_no varchar(64),
    extracted_invoice_date date,
    extracted_total_amount numeric,
    extracted_tax_amount numeric,
    extracted_text text NOT NULL DEFAULT '',
    extracted_payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    mapped_party_ledger_id uuid,
    promoted_journal_id uuid,
    reviewed_by uuid,
    reviewed_at timestamptz(6),
    version_no bigint NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_mc_imported_doc_staging PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_imported_doc_staging_id UNIQUE (id),
    CONSTRAINT fk_mc_imported_doc_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_imported_doc_party FOREIGN KEY (company_id, mapped_party_ledger_id)
        REFERENCES mc_ledger (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_imported_doc_journal FOREIGN KEY (company_id, promoted_journal_id)
        REFERENCES mc_journal (company_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_imported_doc_reviewed_by FOREIGN KEY (reviewed_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_imported_doc_created_by FOREIGN KEY (created_by)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_imported_doc_sha256 CHECK (octet_length(file_sha256) = 32),
    CONSTRAINT chk_mc_imported_doc_status CHECK (
        review_status IN (
            'EXTRACTED_PENDING_REVIEW',
            'IN_REVIEW',
            'APPROVED_CONVERTED',
            'REJECTED_DUPLICATE',
            'FAILED'
        )
    ),
    CONSTRAINT chk_mc_imported_doc_human_review_required CHECK (
        review_status <> 'APPROVED_CONVERTED'
        OR (reviewed_by IS NOT NULL AND promoted_journal_id IS NOT NULL)
    ),
    CONSTRAINT chk_mc_imported_doc_target_cat CHECK (
        target_voucher_category IN ('PURCHASE', 'SALES', 'EXPENSE_PAYMENT', 'CREDIT_NOTE', 'DEBIT_NOTE')
    ),
    CONSTRAINT chk_mc_imported_doc_amounts CHECK (
        (extracted_total_amount IS NULL OR (extracted_total_amount >= 0 AND extracted_total_amount < 1000000000000000000 AND extracted_total_amount = trunc(extracted_total_amount, 6)))
        AND (extracted_tax_amount IS NULL OR (extracted_tax_amount >= 0 AND extracted_tax_amount < 1000000000000000000 AND extracted_tax_amount = trunc(extracted_tax_amount, 6)))
    ),
    CONSTRAINT chk_mc_imported_doc_version_no CHECK (version_no >= 1)
);

CREATE INDEX idx_mc_imported_doc_queue
    ON mc_imported_doc_staging (company_id, review_status, created_at DESC);
CREATE INDEX idx_mc_imported_doc_trgm_supplier
    ON mc_imported_doc_staging USING gin (extracted_supplier_name gin_trgm_ops)
    WHERE extracted_supplier_name IS NOT NULL;
CREATE INDEX idx_mc_imported_doc_trgm_inv_no
    ON mc_imported_doc_staging USING gin (extracted_invoice_no gin_trgm_ops)
    WHERE extracted_invoice_no IS NOT NULL;
CREATE INDEX idx_mc_imported_doc_trgm_text
    ON mc_imported_doc_staging USING gin (extracted_text gin_trgm_ops);

ALTER TABLE mc_imported_doc_staging ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_imported_doc_staging FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_imported_doc_staging_tenant_isolation ON mc_imported_doc_staging
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR company_id = NULLIF(current_setting('app.current_company_id', true), '')::uuid
    );
