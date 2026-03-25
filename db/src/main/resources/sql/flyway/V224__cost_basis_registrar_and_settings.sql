-- ===== start registry-dash-financials =====

-- Add nullable registrar_id to cost basis for per-registrar rates
ALTER TABLE "RegistryDashboardCostBasis"
  ADD COLUMN registrar_id text;

-- Drop old unique constraint and create new one including registrar_id
-- Old: (tld, operation, effective_date)
-- New: (tld, operation, registrar_id, effective_date) with COALESCE for NULL handling
ALTER TABLE "RegistryDashboardCostBasis"
  DROP CONSTRAINT "RegistryDashboardCostBasis_tld_operation_effective_date_key";

CREATE UNIQUE INDEX "idx_cost_basis_tld_op_reg_date"
  ON "RegistryDashboardCostBasis" (tld, operation, COALESCE(registrar_id, ''), effective_date);

-- Add JSONB settings column to RoRegistry for feature flags
ALTER TABLE "RoRegistry"
  ADD COLUMN settings jsonb NOT NULL DEFAULT '{}';

-- ===== end registry-dash-financials =====
