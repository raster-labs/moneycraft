SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '60s';

-- ============================================================================
-- MoneyCraft 2.0.0 — Full TallyPrime Release 7.2 F11 Company Features &
-- Context-Sensitive F12 Configuration Engine (CHG-DB-001)
-- Stream: db/migration/V010__f11_f12_dynamic_feature_and_config_engine.sql
-- Pattern: Zero-Downtime Expand-and-Contract Additive Migration (DDL-001..006)
-- ============================================================================

-- 1. Additive F11 Company Feature Columns & F12 Configuration JSONB Store on mc_company
ALTER TABLE mc_company
    ADD COLUMN IF NOT EXISTS maintain_accounts boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS interest_calculation_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS multiple_price_levels_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS batches_enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS expiry_date_for_batches_enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS job_order_processing_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS cost_tracking_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS job_costing_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS use_discount_column_in_invoices boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS use_separate_actual_and_billed_qty boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS vat_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS excise_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS service_tax_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS payroll_statutory_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS multiple_addresses_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS mark_modified_vouchers boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS einvoice_applicable boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS eway_bill_applicable boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS eway_bill_threshold_limit numeric NOT NULL DEFAULT 50000.00,
    ADD COLUMN IF NOT EXISTS f12_config jsonb NOT NULL DEFAULT '{}'::jsonb;

-- 2. Expand-Safe NOT VALID + VALIDATE CONSTRAINT Fixed-Point & JSONB Object Checks (MCJ1-DATA-01)
ALTER TABLE mc_company DROP CONSTRAINT IF EXISTS chk_mc_company_eway_threshold;
ALTER TABLE mc_company
    ADD CONSTRAINT chk_mc_company_eway_threshold CHECK (
        eway_bill_threshold_limit >= 0
        AND eway_bill_threshold_limit < 1000000000000000000
        AND eway_bill_threshold_limit = trunc(eway_bill_threshold_limit, 6)
    ) NOT VALID;
ALTER TABLE mc_company VALIDATE CONSTRAINT chk_mc_company_eway_threshold;

ALTER TABLE mc_company DROP CONSTRAINT IF EXISTS chk_mc_company_f12_config_object;
ALTER TABLE mc_company
    ADD CONSTRAINT chk_mc_company_f12_config_object CHECK (
        jsonb_typeof(f12_config) = 'object'
    ) NOT VALID;
ALTER TABLE mc_company VALIDATE CONSTRAINT chk_mc_company_f12_config_object;
