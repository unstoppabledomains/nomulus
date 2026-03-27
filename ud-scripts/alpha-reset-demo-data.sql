-- ==========================================================================
-- Alpha-GKE Registry Dashboard Demo Data — RESET
-- ==========================================================================
-- Clears all registry dashboard demo data so alpha-load-demo-data.sql
-- can be re-run cleanly. Does NOT touch TLDs, registrars, or domains.
--
-- SAFE: Only deletes from dashboard-specific tables.
-- ==========================================================================

BEGIN;

-- Cost basis and pricing (no FK dependencies)
DELETE FROM "RegistryDashboardCostBasis";
DELETE FROM "RegistryDashboardRegistrarPricing";

-- Registry scoping (FK cascade: deleting RoRegistry cascades to TLDs and users)
DELETE FROM "RoRegistryUser";
DELETE FROM "RoRegistryTld";
DELETE FROM "RoRegistry";

COMMIT;

SELECT 'Dashboard data cleared. Run alpha-load-demo-data.sql to reload.' AS status;
