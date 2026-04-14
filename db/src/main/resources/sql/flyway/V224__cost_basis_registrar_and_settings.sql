-- Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
