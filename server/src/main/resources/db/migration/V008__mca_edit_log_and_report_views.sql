SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 1.0.0 — Immutable MCA Edit Log (Rule 3(1) Proviso) &
-- Security-Invoker Financial / Statutory Report Views
-- Stream: db/migration/V008__mca_edit_log_and_report_views.sql
-- ============================================================================

-- 1. Append-Only MCA Edit Log Version Snapshots & Redline Diffs (Alt+Q)
CREATE TABLE mc_edit_log_version (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    entity_type varchar(32) NOT NULL,
    entity_id uuid NOT NULL,
    master_id bigint NOT NULL,
    alter_id bigint NOT NULL DEFAULT 1,
    version_no bigint NOT NULL,
    operation varchar(16) NOT NULL,
    actor_principal_id uuid,
    actor_username varchar(100) NOT NULL,
    occurred_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
    voucher_number_snapshot varchar(32),
    voucher_date_snapshot date,
    canonical_snapshot jsonb NOT NULL,
    changed_fields jsonb NOT NULL DEFAULT '[]'::jsonb,
    snapshot_sha256 bytea,
    CONSTRAINT pk_mc_edit_log_version PRIMARY KEY (company_id, id),
    CONSTRAINT uq_mc_edit_log_version_id UNIQUE (id),
    CONSTRAINT uq_mc_edit_log_entity_version UNIQUE (company_id, entity_type, entity_id, version_no),
    CONSTRAINT fk_mc_edit_log_company FOREIGN KEY (company_id)
        REFERENCES mc_company (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_mc_edit_log_actor FOREIGN KEY (actor_principal_id)
        REFERENCES mc_principal_projection (principal_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_mc_edit_log_entity_type CHECK (
        entity_type IN (
            'VOUCHER',
            'LEDGER',
            'ACCOUNT_GROUP',
            'STOCK_ITEM',
            'STOCK_GROUP',
            'STOCK_CATEGORY',
            'GODOWN',
            'UOM',
            'VOUCHER_TYPE',
            'COST_CENTRE',
            'GST_REGISTRATION',
            'COMPANY'
        )
    ),
    CONSTRAINT chk_mc_edit_log_master_id CHECK (master_id >= 1),
    CONSTRAINT chk_mc_edit_log_alter_id CHECK (alter_id >= 1),
    CONSTRAINT chk_mc_edit_log_version_no CHECK (version_no >= 1),
    CONSTRAINT chk_mc_edit_log_operation CHECK (
        operation IN ('CREATE', 'ALTER', 'CANCEL', 'DELETE', 'MIGRATE_IN')
    ),
    CONSTRAINT chk_mc_edit_log_actor_username CHECK (length(trim(actor_username)) > 0),
    CONSTRAINT chk_mc_edit_log_sha256 CHECK (
        snapshot_sha256 IS NULL OR octet_length(snapshot_sha256) = 32
    )
);

CREATE INDEX idx_mc_edit_log_lookup
    ON mc_edit_log_version (company_id, entity_type, entity_id, version_no DESC);
CREATE INDEX idx_mc_edit_log_timeline
    ON mc_edit_log_version (company_id, occurred_at DESC, id);
CREATE INDEX idx_mc_edit_log_master_id
    ON mc_edit_log_version (company_id, entity_type, master_id, version_no);

CREATE TRIGGER trg_mc_edit_log_immutable
BEFORE UPDATE OR DELETE ON mc_edit_log_version
FOR EACH ROW
EXECUTE FUNCTION fn_mc_forbid_append_only_mutation();

ALTER TABLE mc_edit_log_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE mc_edit_log_version FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_mc_edit_log_version_tenant_isolation ON mc_edit_log_version
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
        REVOKE UPDATE, DELETE, TRUNCATE ON mc_edit_log_version FROM mc_app_runtime;
        GRANT SELECT, INSERT ON mc_edit_log_version TO mc_app_runtime;
    END IF;
END;
$$;

-- 2. Security-Invoker Report Views (Preserving Tenant RLS Enforcement)

-- 2A. Period-Scoped Trial Balance Rollup View
CREATE VIEW vw_mc_trial_balance_by_period WITH (security_invoker = true) AS
SELECT
    l.company_id,
    p.id AS period_id,
    p.code AS period_code,
    p.start_date AS period_start_date,
    p.end_date AS period_end_date,
    l.id AS ledger_id,
    l.name AS ledger_name,
    l.group_id,
    g.name AS group_name,
    COALESCE(g.nature, 'LIABILITIES') AS nature,
    COALESCE(g.affects_gross_profit, false) AS affects_gross_profit,
    g.schedule_iii_line_code,
    CASE WHEN l.opening_side = 'DR' THEN l.opening_balance ELSE 0::numeric END AS ledger_opening_dr,
    CASE WHEN l.opening_side = 'CR' THEN l.opening_balance ELSE 0::numeric END AS ledger_opening_cr,
    COALESCE(pb.dr_total, 0::numeric) AS period_dr_total,
    COALESCE(pb.cr_total, 0::numeric) AS period_cr_total,
    COALESCE(pb.entry_count, 0::bigint) AS period_entry_count
FROM mc_ledger l
JOIN mc_period p
  ON p.company_id = l.company_id
LEFT JOIN mc_account_group g
  ON g.company_id = l.company_id
 AND g.id = l.group_id
LEFT JOIN mc_ledger_period_bal pb
  ON pb.company_id = l.company_id
 AND pb.period_id = p.id
 AND pb.ledger_id = l.id;

-- 2B. Cumulative Company Trial Balance Closing View
CREATE VIEW vw_mc_ledger_closing_balance WITH (security_invoker = true) AS
WITH agg_bal AS (
    SELECT
        pb.company_id,
        pb.ledger_id,
        COALESCE(SUM(pb.dr_total), 0::numeric) AS total_dr_movements,
        COALESCE(SUM(pb.cr_total), 0::numeric) AS total_cr_movements
    FROM mc_ledger_period_bal pb
    GROUP BY pb.company_id, pb.ledger_id
),
net_calc AS (
    SELECT
        l.company_id,
        l.id AS ledger_id,
        l.name AS ledger_name,
        l.group_id,
        g.name AS group_name,
        COALESCE(g.nature, 'LIABILITIES') AS nature,
        COALESCE(g.affects_gross_profit, false) AS affects_gross_profit,
        g.schedule_iii_line_code,
        l.opening_balance,
        l.opening_side,
        COALESCE(ab.total_dr_movements, 0::numeric) AS total_dr_movements,
        COALESCE(ab.total_cr_movements, 0::numeric) AS total_cr_movements,
        (
            CASE WHEN l.opening_side = 'DR' THEN l.opening_balance ELSE -l.opening_balance END
            + COALESCE(ab.total_dr_movements, 0::numeric)
            - COALESCE(ab.total_cr_movements, 0::numeric)
        ) AS signed_closing_balance
    FROM mc_ledger l
    LEFT JOIN mc_account_group g
      ON g.company_id = l.company_id
     AND g.id = l.group_id
    LEFT JOIN agg_bal ab
      ON ab.company_id = l.company_id
     AND ab.ledger_id = l.id
)
SELECT
    company_id,
    ledger_id,
    ledger_name,
    group_id,
    group_name,
    nature,
    affects_gross_profit,
    schedule_iii_line_code,
    opening_balance,
    opening_side,
    total_dr_movements,
    total_cr_movements,
    CASE WHEN signed_closing_balance > 0 THEN signed_closing_balance ELSE 0::numeric END AS closing_dr,
    CASE WHEN signed_closing_balance < 0 THEN -signed_closing_balance ELSE 0::numeric END AS closing_cr
FROM net_calc;

-- 2C. Open Bill-Wise Receivables & Payables Ageing View (NEW_REF / ADVANCE / AGST_REF)
CREATE VIEW vw_mc_open_bill_balances WITH (security_invoker = true) AS
SELECT
    ba.company_id,
    ba.ledger_id,
    l.name AS party_ledger_name,
    ba.bill_name,
    MIN(j.voucher_date) FILTER (WHERE ba.allocation_type IN ('NEW_REF', 'ADVANCE')) AS bill_date,
    MAX(ba.due_date) FILTER (WHERE ba.allocation_type IN ('NEW_REF', 'ADVANCE')) AS due_date,
    COALESCE(SUM(CASE WHEN ba.side = 'DR' THEN ba.amount ELSE 0::numeric END), 0::numeric) AS total_dr,
    COALESCE(SUM(CASE WHEN ba.side = 'CR' THEN ba.amount ELSE 0::numeric END), 0::numeric) AS total_cr,
    ABS(
        COALESCE(SUM(CASE WHEN ba.side = 'DR' THEN ba.amount ELSE -ba.amount END), 0::numeric)
    ) AS pending_amount,
    CASE
        WHEN SUM(CASE WHEN ba.side = 'DR' THEN ba.amount ELSE -ba.amount END) >= 0 THEN 'DR'
        ELSE 'CR'
    END AS pending_side,
    COALESCE(SUM(ba.unadjusted_forex_delta) FILTER (WHERE ba.forex_swept_journal_id IS NULL), 0::numeric) AS unadjusted_forex_delta
FROM mc_bill_allocation ba
JOIN mc_journal j
  ON j.company_id = ba.company_id
 AND j.id = ba.journal_id
JOIN mc_ledger l
  ON l.company_id = ba.company_id
 AND l.id = ba.ledger_id
WHERE j.status = 'POSTED'
  AND j.posting_nature = 'NORMAL'
  AND ba.allocation_type IN ('NEW_REF', 'AGST_REF', 'ADVANCE')
GROUP BY ba.company_id, ba.ledger_id, l.name, ba.bill_name
HAVING SUM(CASE WHEN ba.side = 'DR' THEN ba.amount ELSE -ba.amount END) <> 0;

-- 2D. GSTR-1 Table 13 Document Issued & Cancelled Summary View
CREATE VIEW vw_mc_gstr1_document_summary WITH (security_invoker = true) AS
SELECT
    j.company_id,
    j.period_id,
    j.company_gstin_id,
    j.voucher_type_id,
    vt.name AS voucher_type_name,
    vt.base_category,
    MIN(j.voucher_number) FILTER (WHERE j.voucher_number IS NOT NULL) AS from_serial_no,
    MAX(j.voucher_number) FILTER (WHERE j.voucher_number IS NOT NULL) AS to_serial_no,
    COUNT(*) FILTER (WHERE j.status IN ('POSTED', 'REVERSED', 'CANCELLED') AND j.posting_nature = 'NORMAL') AS total_number,
    COUNT(*) FILTER (WHERE j.status = 'CANCELLED' AND j.posting_nature = 'NORMAL') AS cancelled_count,
    COUNT(*) FILTER (WHERE j.status IN ('POSTED', 'REVERSED') AND j.posting_nature = 'NORMAL') AS net_issued_count
FROM mc_journal j
JOIN mc_voucher_type vt
  ON vt.company_id = j.company_id
 AND vt.id = j.voucher_type_id
WHERE j.voucher_number IS NOT NULL
GROUP BY j.company_id, j.period_id, j.company_gstin_id, j.voucher_type_id, vt.name, vt.base_category;

-- 2E. GSTR-1 / GSTR-3B Uncertain & Conflict Transactions Workbench View (Ctrl+J)
CREATE VIEW vw_mc_gstr_uncertain_vouchers WITH (security_invoker = true) AS
SELECT DISTINCT
    j.company_id,
    j.id AS journal_id,
    j.period_id,
    j.company_gstin_id,
    j.voucher_type_id,
    j.voucher_number,
    j.voucher_date,
    j.party_ledger_id,
    j.party_gstin,
    CASE
        WHEN EXISTS (
            SELECT 1
              FROM mc_journal_line jl
             WHERE jl.company_id = j.company_id
               AND jl.journal_id = j.id
               AND jl.is_gst_overridden = true
        ) THEN 'MANUAL_TAX_OVERRIDE_MISMATCH'
        WHEN EXISTS (
            SELECT 1
              FROM mc_tax_allocation ta
             WHERE ta.company_id = j.company_id
               AND ta.journal_id = j.id
               AND ta.is_uncertain_transaction = true
        ) THEN 'STATUTORY_VALIDATION_CONFLICT'
        ELSE 'UNCLASSIFIED_DUTY_LEDGER_POSTING'
    END AS uncertain_category
FROM mc_journal j
WHERE j.status = 'POSTED'
  AND j.posting_nature = 'NORMAL'
  AND (
      EXISTS (
          SELECT 1
            FROM mc_journal_line jl
           WHERE jl.company_id = j.company_id
             AND jl.journal_id = j.id
             AND jl.is_gst_overridden = true
      )
      OR EXISTS (
          SELECT 1
            FROM mc_tax_allocation ta
           WHERE ta.company_id = j.company_id
             AND ta.journal_id = j.id
             AND ta.is_uncertain_transaction = true
             AND ta.conflict_accepted_by IS NULL
      )
      OR EXISTS (
          SELECT 1
            FROM mc_entry e
            JOIN mc_ledger l
              ON l.company_id = e.company_id
             AND l.id = e.ledger_id
            JOIN mc_account_group g
              ON g.company_id = l.company_id
             AND g.id = l.group_id
           WHERE e.company_id = j.company_id
             AND e.journal_id = j.id
             AND lower(g.name) = 'duties & taxes'
             AND l.tax_type IN ('NONE', 'OTHERS')
      )
  );

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mc_app_runtime') THEN
        GRANT USAGE ON SCHEMA public TO mc_app_runtime;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mc_app_runtime;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mc_app_runtime;
        REVOKE UPDATE, DELETE, TRUNCATE ON mc_audit_event FROM mc_app_runtime;
        REVOKE UPDATE, DELETE, TRUNCATE ON mc_entry FROM mc_app_runtime;
        REVOKE UPDATE, DELETE, TRUNCATE ON mc_edit_log_version FROM mc_app_runtime;
        GRANT SELECT, INSERT ON mc_audit_event, mc_entry, mc_edit_log_version TO mc_app_runtime;
        GRANT SELECT ON
            vw_mc_trial_balance_by_period,
            vw_mc_ledger_closing_balance,
            vw_mc_open_bill_balances,
            vw_mc_gstr1_document_summary,
            vw_mc_gstr_uncertain_vouchers
        TO mc_app_runtime;
    END IF;
END;
$$;

