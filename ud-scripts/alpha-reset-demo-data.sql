-- Copyright 2026 The Nomulus Authors. All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

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
