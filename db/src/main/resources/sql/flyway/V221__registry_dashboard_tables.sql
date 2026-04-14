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

-- Maps Registry Operator users to the registrars they can view/manage
CREATE TABLE "RegistryDashboardRoRegistrarMapping" (
    id bigserial NOT NULL,
    user_email_address text NOT NULL,
    registrar_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    UNIQUE (user_email_address, registrar_id)
);

CREATE INDEX idx_ro_mapping_user
    ON "RegistryDashboardRoRegistrarMapping" (user_email_address);

-- Per-registrar pricing rules (read by DomainPricingCustomLogic at EPP time)
CREATE TABLE "RegistryDashboardRegistrarPricing" (
    id bigserial NOT NULL,
    registrar_id text NOT NULL,
    tld text NOT NULL,
    operation text NOT NULL,
    price_amount numeric(19,2) NOT NULL,
    price_currency text NOT NULL,
    effective_date timestamptz NOT NULL DEFAULT NOW(),
    expiry_date timestamptz,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    UNIQUE (registrar_id, tld, operation, effective_date)
);

CREATE INDEX idx_pricing_lookup
    ON "RegistryDashboardRegistrarPricing" (registrar_id, tld, is_active);

-- Registry Operator cost basis (what the RSP pays upstream per TLD/operation)
CREATE TABLE "RegistryDashboardCostBasis" (
    id bigserial NOT NULL,
    tld text NOT NULL,
    operation text NOT NULL,
    cost_amount numeric(19,2) NOT NULL,
    cost_currency text NOT NULL,
    effective_date timestamptz NOT NULL DEFAULT NOW(),
    notes text,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    UNIQUE (tld, operation, effective_date)
);
