-- ==========================================================================
-- Alpha-GKE Registry Dashboard Demo Data
-- ==========================================================================
-- Populates registry dashboard tables with demo data for the 5 demo TLDs
-- and 7 demo registrars that already exist in the alpha environment.
--
-- PREREQUISITES:
--   - TLDs (modem, floppy, pixel, dialup, cassette) already exist
--   - Registrars (arsenic, mercury, thallium, radium, polonium, cadmium, antimony) already exist
--   - Registry dashboard schema (V221-V224 migrations) already applied
--
-- SAFE TO RE-RUN: All inserts use ON CONFLICT DO NOTHING.
--
-- HOW TO RUN (from your Mac):
--   Option A: Cloud SQL Proxy
--     cloud-sql-proxy ud-registry-alpha-gke:us-central1:alpha-nomulus-db &
--     PGPASSWORD=<password> psql -h 127.0.0.1 -p 5432 -U nomulus -d postgres -f alpha-load-demo-data.sql
--
--   Option B: gcloud sql connect
--     gcloud sql connect alpha-nomulus-db --project=ud-registry-alpha-gke --database=postgres --user=nomulus
--     \i alpha-load-demo-data.sql
--
--   Option C: From CICD pod (if psql is available)
--     kubectl exec -ti <pod> -- psql -h <cloud-sql-ip> -U nomulus -d postgres -f /tmp/alpha-load-demo-data.sql
-- ==========================================================================

BEGIN;

-- ==========================================================================
-- Step 1: Discovery — show what TLDs and registrars already exist
-- ==========================================================================

DO $$
DECLARE
  tld_count INTEGER;
  reg_count INTEGER;
BEGIN
  SELECT count(*) INTO tld_count FROM "Tld" WHERE tld_name IN ('modem','floppy','pixel','dialup','cassette');
  SELECT count(*) INTO reg_count FROM "Registrar" WHERE registrar_id IN ('arsenic','mercury','thallium','radium','polonium','cadmium','antimony');
  RAISE NOTICE '=== Discovery ===';
  RAISE NOTICE 'Demo TLDs found: % / 5 expected', tld_count;
  RAISE NOTICE 'Demo registrars found: % / 7 expected', reg_count;
  IF tld_count < 5 THEN
    RAISE WARNING 'Some demo TLDs are missing! Script will still run but data may be incomplete.';
  END IF;
  IF reg_count < 7 THEN
    RAISE WARNING 'Some demo registrars are missing! Script will still run but data may be incomplete.';
  END IF;
END $$;

-- ==========================================================================
-- Step 2: Create 3 RoRegistries (registry operator groups)
-- ==========================================================================
-- Each registry "owns" a set of TLDs. A TLD belongs to exactly one registry.
-- A registry can have multiple TLDs.

INSERT INTO "RoRegistry" (name, settings, created_at) VALUES
  ('RetroTech Registry', '{"showPricingSpread": true}', NOW()),
  ('Vintage Digital',    '{"showPricingSpread": true}', NOW()),
  ('NeonWave Networks',  '{"showPricingSpread": true}', NOW())
ON CONFLICT (name) DO NOTHING;

-- ==========================================================================
-- Step 3: Map TLDs to registries (each TLD in exactly one registry)
-- ==========================================================================
-- RetroTech Registry: modem, floppy  (2 TLDs — the volume players)
-- Vintage Digital:    pixel, dialup  (2 TLDs — mid-tier)
-- NeonWave Networks:  cassette       (1 TLD  — boutique)

INSERT INTO "RoRegistryTld" (registry_id, tld, created_at)
SELECT r.id, t.tld, NOW()
FROM "RoRegistry" r
CROSS JOIN (VALUES ('modem'), ('floppy')) AS t(tld)
WHERE r.name = 'RetroTech Registry'
ON CONFLICT (registry_id, tld) DO NOTHING;

INSERT INTO "RoRegistryTld" (registry_id, tld, created_at)
SELECT r.id, t.tld, NOW()
FROM "RoRegistry" r
CROSS JOIN (VALUES ('pixel'), ('dialup')) AS t(tld)
WHERE r.name = 'Vintage Digital'
ON CONFLICT (registry_id, tld) DO NOTHING;

INSERT INTO "RoRegistryTld" (registry_id, tld, created_at)
SELECT r.id, t.tld, NOW()
FROM "RoRegistry" r
CROSS JOIN (VALUES ('cassette')) AS t(tld)
WHERE r.name = 'NeonWave Networks'
ON CONFLICT (registry_id, tld) DO NOTHING;

-- ==========================================================================
-- Step 4: Map demo users to registries
-- ==========================================================================
-- IMPORTANT: Replace these emails with real user emails that will access the
-- dashboard. The user must also have REGISTRY_OPERATOR global role.
--
-- To create/update a user with REGISTRY_OPERATOR role, use the nomulus CLI:
--   nomulus -e alpha create_user --email=<email> --global_role=REGISTRY_OPERATOR
-- Or update existing:
--   nomulus -e alpha update_user --email=<email> --global_role=REGISTRY_OPERATOR

-- Map demo user to ALL 3 registries (sees everything)
INSERT INTO "RoRegistryUser" (registry_id, user_email, created_at)
SELECT r.id, u.email, NOW()
FROM "RoRegistry" r
CROSS JOIN (VALUES
  ('torrey@unstoppabledomains.com')
  -- Add more user emails here as needed:
  -- ,('another.user@unstoppabledomains.com')
) AS u(email)
WHERE r.name IN ('RetroTech Registry', 'Vintage Digital', 'NeonWave Networks')
ON CONFLICT (registry_id, user_email) DO NOTHING;

-- ==========================================================================
-- Step 5: Fee Schedules (Cost Basis) — global rates per TLD/Operation
-- ==========================================================================
-- These represent what the registry operator pays upstream per operation.
-- registrar_id is NULL = global rate (applies to all registrars).
-- Operations: CREATE, RENEW, RESTORE, TRANSFER
--
-- Pricing strategy: cost basis is roughly 55-65% of TLD retail price,
-- leaving room for per-registrar markup.

INSERT INTO "RegistryDashboardCostBasis"
  (tld, operation, cost_amount, cost_currency, effective_date, registrar_id, notes, created_at, updated_at)
VALUES
  -- modem: Retail Create $15, Renew $12, Restore $25
  ('modem',    'CREATE',   9.00,  'USD', '2026-01-01', NULL, 'Wholesale create — modem',   NOW(), NOW()),
  ('modem',    'RENEW',    7.50,  'USD', '2026-01-01', NULL, 'Wholesale renew — modem',    NOW(), NOW()),
  ('modem',    'RESTORE', 15.00,  'USD', '2026-01-01', NULL, 'Wholesale restore — modem',  NOW(), NOW()),
  ('modem',    'TRANSFER', 7.50,  'USD', '2026-01-01', NULL, 'Same as renew — modem',      NOW(), NOW()),

  -- floppy: Retail Create $10, Renew $8, Restore $18
  ('floppy',   'CREATE',   6.00,  'USD', '2026-01-01', NULL, 'Wholesale create — floppy',  NOW(), NOW()),
  ('floppy',   'RENEW',    5.00,  'USD', '2026-01-01', NULL, 'Wholesale renew — floppy',   NOW(), NOW()),
  ('floppy',   'RESTORE', 10.00,  'USD', '2026-01-01', NULL, 'Wholesale restore — floppy', NOW(), NOW()),
  ('floppy',   'TRANSFER', 5.00,  'USD', '2026-01-01', NULL, 'Same as renew — floppy',     NOW(), NOW()),

  -- pixel: Retail Create $20, Renew $16, Restore $35
  ('pixel',    'CREATE',  12.00,  'USD', '2026-01-01', NULL, 'Wholesale create — pixel',   NOW(), NOW()),
  ('pixel',    'RENEW',   10.00,  'USD', '2026-01-01', NULL, 'Wholesale renew — pixel',    NOW(), NOW()),
  ('pixel',    'RESTORE', 20.00,  'USD', '2026-01-01', NULL, 'Wholesale restore — pixel',  NOW(), NOW()),
  ('pixel',    'TRANSFER',10.00,  'USD', '2026-01-01', NULL, 'Same as renew — pixel',      NOW(), NOW()),

  -- dialup: Retail Create $12, Renew $10, Restore $20
  ('dialup',   'CREATE',   7.00,  'USD', '2026-01-01', NULL, 'Wholesale create — dialup',  NOW(), NOW()),
  ('dialup',   'RENEW',    6.00,  'USD', '2026-01-01', NULL, 'Wholesale renew — dialup',   NOW(), NOW()),
  ('dialup',   'RESTORE', 12.00,  'USD', '2026-01-01', NULL, 'Wholesale restore — dialup', NOW(), NOW()),
  ('dialup',   'TRANSFER', 6.00,  'USD', '2026-01-01', NULL, 'Same as renew — dialup',     NOW(), NOW()),

  -- cassette: Retail Create $18, Renew $14, Restore $30
  ('cassette', 'CREATE',  11.00,  'USD', '2026-01-01', NULL, 'Wholesale create — cassette',  NOW(), NOW()),
  ('cassette', 'RENEW',    9.00,  'USD', '2026-01-01', NULL, 'Wholesale renew — cassette',   NOW(), NOW()),
  ('cassette', 'RESTORE', 18.00,  'USD', '2026-01-01', NULL, 'Wholesale restore — cassette', NOW(), NOW()),
  ('cassette', 'TRANSFER', 9.00,  'USD', '2026-01-01', NULL, 'Same as renew — cassette',     NOW(), NOW())

ON CONFLICT DO NOTHING;

-- ==========================================================================
-- Step 6: Per-Registrar Pricing Overrides
-- ==========================================================================
-- These show custom pricing that differs from TLD retail defaults.
-- The dashboard displays these alongside default TLD prices to show the spread.
-- Each registrar only gets rules for TLDs in their allowed_tlds.
--
-- Registrar portfolios (from alpha setup):
--   arsenic:  modem, floppy, pixel, dialup, cassette (5 — broad portfolio)
--   mercury:  modem, floppy, dialup                   (3 — mid-tier)
--   thallium: modem, pixel, dialup                    (3 — mid-tier)
--   radium:   modem, floppy, cassette                 (3 — mid-tier)
--   polonium: modem, pixel                            (2 — small)
--   cadmium:  floppy                                  (1 — specialist)
--   antimony: modem                                   (1 — specialist)

-- arsenic: broad portfolio, mix of discounts and premiums
INSERT INTO "RegistryDashboardRegistrarPricing"
  (registrar_id, tld, operation, price_amount, price_currency, effective_date, is_active, created_at, updated_at)
VALUES
  ('arsenic', 'modem',    'CREATE',  14.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('arsenic', 'modem',    'RENEW',   11.50, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('arsenic', 'modem',    'RESTORE', 28.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('arsenic', 'floppy',   'CREATE',   9.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('arsenic', 'floppy',   'RENEW',    7.50, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('arsenic', 'pixel',    'CREATE',  22.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('arsenic', 'pixel',    'RENEW',   15.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('arsenic', 'dialup',   'CREATE',  11.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('arsenic', 'cassette', 'CREATE',  19.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('arsenic', 'cassette', 'RENEW',   13.00, 'USD', '2026-01-01', true, NOW(), NOW())
ON CONFLICT (registrar_id, tld, operation, effective_date) DO NOTHING;

-- mercury: competitive discounts
INSERT INTO "RegistryDashboardRegistrarPricing"
  (registrar_id, tld, operation, price_amount, price_currency, effective_date, is_active, created_at, updated_at)
VALUES
  ('mercury', 'modem',  'CREATE',  13.50, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('mercury', 'modem',  'RENEW',   11.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('mercury', 'floppy', 'CREATE',   8.50, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('mercury', 'floppy', 'RENEW',    7.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('mercury', 'dialup', 'CREATE',  10.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('mercury', 'dialup', 'RENEW',    9.00, 'USD', '2026-01-01', true, NOW(), NOW())
ON CONFLICT (registrar_id, tld, operation, effective_date) DO NOTHING;

-- thallium: premium pricing
INSERT INTO "RegistryDashboardRegistrarPricing"
  (registrar_id, tld, operation, price_amount, price_currency, effective_date, is_active, created_at, updated_at)
VALUES
  ('thallium', 'modem',  'CREATE',  16.50, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('thallium', 'modem',  'RENEW',   13.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('thallium', 'pixel',  'CREATE',  23.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('thallium', 'pixel',  'RENEW',   18.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('thallium', 'dialup', 'CREATE',  14.00, 'USD', '2026-01-01', true, NOW(), NOW())
ON CONFLICT (registrar_id, tld, operation, effective_date) DO NOTHING;

-- radium: at-cost or slight discounts
INSERT INTO "RegistryDashboardRegistrarPricing"
  (registrar_id, tld, operation, price_amount, price_currency, effective_date, is_active, created_at, updated_at)
VALUES
  ('radium', 'modem',    'CREATE',  15.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('radium', 'modem',    'RENEW',   12.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('radium', 'floppy',   'CREATE',  10.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('radium', 'cassette', 'CREATE',  17.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('radium', 'cassette', 'RENEW',   13.50, 'USD', '2026-01-01', true, NOW(), NOW())
ON CONFLICT (registrar_id, tld, operation, effective_date) DO NOTHING;

-- polonium: small portfolio
INSERT INTO "RegistryDashboardRegistrarPricing"
  (registrar_id, tld, operation, price_amount, price_currency, effective_date, is_active, created_at, updated_at)
VALUES
  ('polonium', 'modem', 'CREATE',  14.50, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('polonium', 'modem', 'RENEW',   11.75, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('polonium', 'pixel', 'CREATE',  19.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('polonium', 'pixel', 'RENEW',   15.50, 'USD', '2026-01-01', true, NOW(), NOW())
ON CONFLICT (registrar_id, tld, operation, effective_date) DO NOTHING;

-- cadmium: single TLD specialist (deep discounts for volume)
INSERT INTO "RegistryDashboardRegistrarPricing"
  (registrar_id, tld, operation, price_amount, price_currency, effective_date, is_active, created_at, updated_at)
VALUES
  ('cadmium', 'floppy', 'CREATE',   8.00, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('cadmium', 'floppy', 'RENEW',    6.50, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('cadmium', 'floppy', 'RESTORE', 15.00, 'USD', '2026-01-01', true, NOW(), NOW())
ON CONFLICT (registrar_id, tld, operation, effective_date) DO NOTHING;

-- antimony: single TLD, slight premium
INSERT INTO "RegistryDashboardRegistrarPricing"
  (registrar_id, tld, operation, price_amount, price_currency, effective_date, is_active, created_at, updated_at)
VALUES
  ('antimony', 'modem', 'CREATE',  15.50, 'USD', '2026-01-01', true, NOW(), NOW()),
  ('antimony', 'modem', 'RENEW',   12.50, 'USD', '2026-01-01', true, NOW(), NOW())
ON CONFLICT (registrar_id, tld, operation, effective_date) DO NOTHING;

COMMIT;

-- ==========================================================================
-- Step 7: Verification
-- ==========================================================================

SELECT '=== RoRegistries ===' AS section;
SELECT r.name, r.settings,
       array_agg(DISTINCT rt.tld ORDER BY rt.tld) AS tlds,
       array_agg(DISTINCT ru.user_email) AS users
FROM "RoRegistry" r
LEFT JOIN "RoRegistryTld" rt ON rt.registry_id = r.id
LEFT JOIN "RoRegistryUser" ru ON ru.registry_id = r.id
GROUP BY r.id, r.name, r.settings
ORDER BY r.name;

SELECT '=== Cost Basis (global fee schedules) ===' AS section;
SELECT tld, operation, cost_amount, cost_currency, registrar_id
FROM "RegistryDashboardCostBasis"
WHERE registrar_id IS NULL
ORDER BY tld, operation;

SELECT '=== Per-Registrar Pricing Rules ===' AS section;
SELECT registrar_id, tld, operation, price_amount, is_active
FROM "RegistryDashboardRegistrarPricing"
ORDER BY registrar_id, tld, operation;

SELECT '=== Summary ===' AS section;
SELECT 'Registries' AS entity, count(*) FROM "RoRegistry"
UNION ALL
SELECT 'TLD mappings', count(*) FROM "RoRegistryTld"
UNION ALL
SELECT 'User mappings', count(*) FROM "RoRegistryUser"
UNION ALL
SELECT 'Cost basis entries', count(*) FROM "RegistryDashboardCostBasis"
UNION ALL
SELECT 'Pricing rules', count(*) FROM "RegistryDashboardRegistrarPricing";
