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

-- Refactor RegistryDashboardCostBasis to store RSP retained fee (margin)
-- instead of net-to-registry amount.
--
-- Previously cost_amount stored "what RSP pays registry" (net-to-registry).
-- Now it stores "what RSP retains per operation" (rsp_retained_fee_amount).
-- Net to Registry becomes a calculated field: registrar_billed_amount - rsp_retained_fee_amount.

-- Rename column to reflect new semantics
ALTER TABLE "RegistryDashboardCostBasis"
  RENAME COLUMN cost_amount TO rsp_retained_fee_amount;

-- Drop registrar_id — new model is per-TLD/operation only (no per-registrar overrides)
ALTER TABLE "RegistryDashboardCostBasis"
  DROP COLUMN registrar_id;

-- Clear stale data — old values had different semantics and are no longer valid
DELETE FROM "RegistryDashboardCostBasis";

-- Drop old unique constraint that included registrar_id
ALTER TABLE "RegistryDashboardCostBasis"
  DROP CONSTRAINT IF EXISTS "RegistryDashboardCostBasis_tld_operation_registrar_id_effectiv_key";

-- Add clean unique constraint without registrar_id
ALTER TABLE "RegistryDashboardCostBasis"
  ADD CONSTRAINT "RegistryDashboardCostBasis_tld_operation_effective_date_key"
  UNIQUE (tld, operation, effective_date);
