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

-- Refactor: replace flat user->TLD mapping with a three-level model:
--   RoRegistry (named registry group)
--   RoRegistryTld (registry -> TLD assignments)
--   RoRegistryUser (registry -> user assignments)
--
-- This allows adding a TLD to a registry once rather than updating every user.

CREATE TABLE "RoRegistry" (
    id bigserial NOT NULL,
    name text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    UNIQUE (name)
);

CREATE TABLE "RoRegistryTld" (
    id bigserial NOT NULL,
    registry_id bigint NOT NULL REFERENCES "RoRegistry"(id) ON DELETE CASCADE,
    tld text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    UNIQUE (registry_id, tld)
);

CREATE INDEX idx_ro_registry_tld_registry ON "RoRegistryTld" (registry_id);

CREATE TABLE "RoRegistryUser" (
    id bigserial NOT NULL,
    registry_id bigint NOT NULL REFERENCES "RoRegistry"(id) ON DELETE CASCADE,
    user_email text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    UNIQUE (registry_id, user_email)
);

CREATE INDEX idx_ro_registry_user_email ON "RoRegistryUser" (user_email);

-- Drop old flat mapping tables
DROP TABLE IF EXISTS "RegistryDashboardRoTldMapping";
DROP TABLE IF EXISTS "RegistryDashboardRoRegistrarMapping";
