# Plan A: Extend Nomulus Console for Registry Dashboard

## Overview

Add registry-operator-centric features to the existing Angular 21 + Java console within the nomulus codebase, while carefully isolating changes to minimize merge conflicts with upstream.

---

## Architecture

```
                    Existing Nomulus Console
                    ┌─────────────────────────────────────────────┐
                    │                                             │
                    │  Angular 21 Frontend                        │
                    │  ┌───────────────────────────────────────┐  │
                    │  │  Existing Registrar UI (unchanged)    │  │
                    │  ├───────────────────────────────────────┤  │
                    │  │  NEW: Registry Dashboard Module       │  │
                    │  │  ├── Dashboard Overview               │  │
                    │  │  ├── Registrar Portfolio               │  │
                    │  │  ├── Domain Analytics                  │  │
                    │  │  ├── Pricing & Promotions              │  │
                    │  │  ├── Billing Overview                  │  │
                    │  │  └── AI Analytics Chat                 │  │
                    │  └───────────────────────────────────────┘  │
                    │                                             │
                    │  Java Backend (Dagger + Action-based)       │
                    │  ┌───────────────────────────────────────┐  │
                    │  │  Existing /console-api/* (unchanged)  │  │
                    │  ├───────────────────────────────────────┤  │
                    │  │  NEW: /console-api/registry-dash/*    │  │
                    │  │  ├── /overview                        │  │
                    │  │  ├── /registrar-portfolio              │  │
                    │  │  ├── /domain-analytics                 │  │
                    │  │  ├── /pricing                          │  │
                    │  │  ├── /promotions                       │  │
                    │  │  ├── /billing                          │  │
                    │  │  └── /ai-query                         │  │
                    │  └───────────────────────────────────────┘  │
                    │                                             │
                    │  Database: Existing Nomulus PostgreSQL      │
                    │  + NEW: registry_dashboard_* tables         │
                    └─────────────────────────────────────────────┘
```

---

## Strategy for Minimizing Upstream Merge Conflicts

This is the critical challenge for Plan A. The approach:

### 1. Isolate Frontend Changes
- **All new UI code** lives under `console-webapp/src/app/registry-dashboard/` — a completely new directory that upstream will never touch.
- **Minimal touches to existing files:**
  - `app-routing.module.ts` — add one lazy-loaded route group (single line addition)
  - `app.module.ts` — import the new module (single line addition)
  - `navigation/` — add a conditional nav section for RO users (small addition to template + component)
  - `shared/services/` — extend `UserDataService` to include RO role info (additive, low-conflict)

### 2. Isolate Backend Changes
- **All new API actions** in a new directory: `core/src/main/java/google/registry/ui/server/console/registrydashboard/`
- **New endpoint prefix:** `/console-api/registry-dash/*` — no overlap with existing `/console-api/*` endpoints
- **New Dagger module:** `RegistryDashboardModule.java` — wired into the console service component with minimal touch to existing Dagger config
- **New DB tables** prefixed `registry_dashboard_*` — no schema conflicts

### 3. New Permission Layer
- Add new `ConsolePermission` entries (additive to the existing enum — low-conflict)
- Add new role: `REGISTRY_OPERATOR` to the global role enum
- RO users see registry dashboard nav; registrar users see existing nav

### 4. Git Strategy
- Keep all registry-dashboard changes in feature branches
- Before merging upstream, use `git diff --name-only upstream/master..HEAD` to audit touched files
- Maintain a manifest of "shared files we modified" (aim for < 10 shared files)

---

## Features — Detailed Breakdown

### Feature 1: Dashboard Overview
**What:** Aggregate metrics across all registrars managed by the RO — total domains, active registrars, domain growth trends.

**Frontend:**
- `registry-dashboard/overview/` component
- Angular Material cards + chart library (e.g., ngx-charts or Chart.js via wrapper)
- Auto-refreshing data via RxJS interval polling

**Backend:**
- `RegistryDashboardOverviewAction` — `/console-api/registry-dash/overview`
- Queries: `SELECT COUNT(*) FROM "Domain" WHERE "deletionTime" > NOW()` (active domains), grouped by TLD, by registrar
- Aggregation done in SQL for performance (runs against read replica in prod)

**Data Model:** No new tables — queries existing Domain + Registrar tables.

---

### Feature 2: Registrar Portfolio
**What:** List all registrars associated with this RO, their status, allowed TLDs, domain counts, and health metrics.

**Frontend:**
- `registry-dashboard/registrar-portfolio/` component
- Angular Material table with sorting, filtering, search
- Click-through to per-registrar detail view

**Backend:**
- `RegistryDashboardRegistrarPortfolioAction` — `/console-api/registry-dash/registrar-portfolio`
- Joins Registrar table with domain counts
- Filtered to only registrars mapped to the authenticated RO

**Data Model:**
- **NEW TABLE:** `registry_dashboard_ro_registrar_mapping`
  ```sql
  CREATE TABLE registry_dashboard_ro_registrar_mapping (
    id BIGSERIAL PRIMARY KEY,
    ro_user_email TEXT NOT NULL,
    registrar_id TEXT NOT NULL REFERENCES "Registrar"("registrar_id"),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(ro_user_email, registrar_id)
  );
  ```
- This maps RO users to which registrars they can manage — lives in the nomulus DB but in a new table.

---

### Feature 3: Domain Analytics
**What:** Domains per registrar, registration/deletion trends, TLD breakdown, expiration forecasts.

**Frontend:**
- `registry-dashboard/domain-analytics/` component
- Interactive charts: line charts for trends, pie/bar for distribution
- Date range picker for historical analysis
- Export to CSV

**Backend:**
- `RegistryDashboardDomainAnalyticsAction` — `/console-api/registry-dash/domain-analytics`
- Time-series queries against Domain table (grouped by day/week/month)
- Materialized view or scheduled aggregation for performance on large datasets

**Data Model:**
- **NEW TABLE:** `registry_dashboard_domain_stats` (pre-aggregated daily stats)
  ```sql
  CREATE TABLE registry_dashboard_domain_stats (
    stat_date DATE NOT NULL,
    registrar_id TEXT NOT NULL,
    tld TEXT NOT NULL,
    total_domains INT,
    new_registrations INT,
    deletions INT,
    renewals INT,
    transfers_in INT,
    transfers_out INT,
    PRIMARY KEY (stat_date, registrar_id, tld)
  );
  ```
- Populated by a scheduled Flyway/cron job aggregating from BillingEvent and Domain tables.

---

### Feature 4: Pricing Management (Two-Tier Model)
**What:** Manage both sides of the pricing equation — what registrars pay the RSP, and what the RSP pays upstream to the RO — with the RSP keeping the delta. Supports per-registrar pricing.

#### How Nomulus Pricing Works (Context)
- **TLD base prices** are stored on the `Tld` entity as time-based transitions (`createBillingCostTransitions`, `renewBillingCostTransitions`, default $8 USD)
- **Premium prices** via `PremiumList` entities (per-domain-label overrides)
- **No built-in per-registrar pricing** on the Registrar entity itself
- Nomulus has **no concept of upstream/cost-basis pricing** — it only knows the registrar-facing price

#### Per-Registrar Pricing — Two Approaches Available

Nomulus provides **two** mechanisms for implementing per-registrar pricing:

**Approach 1: AllocationTokens (explicit, registrar-initiated)**
- Create `AllocationToken` with `allowedClientIds` scoped to a specific registrar
- Supports `discountFraction`, `discountPrice`, and `renewalPrice` overrides
- Registrar must include the token in EPP commands (create, renew, etc.)
- Well-tested, standard mechanism; good for promotions where registrar opts in
- Managed via `GenerateAllocationTokensCommand` CLI (no console API exists today)

**Approach 2: DomainPricingCustomLogic (transparent, server-side)**
- Nomulus has a built-in extension point: `DomainPricingCustomLogic` (`core/src/main/java/google/registry/flows/custom/DomainPricingCustomLogic.java`)
- It's a no-op base class with hooks for every pricing operation: `customizeCreatePrice`, `customizeRenewPrice`, `customizeRestorePrice`, `customizeTransferPrice`, `customizeUpdatePrice`
- Each hook receives `FeesAndCredits` + context (TLD, domain name, date) and can return modified pricing
- The `CustomLogicFactory` provides access to `SessionMetadata` (which contains the registrar `clientId`) — its own javadoc says: *"A common use case might be switching based on the registrar clientId in sessionMetadata"*
- **How it would work:** Subclass `DomainPricingCustomLogic`, look up the registrar's custom pricing rules from the dashboard DB tables, return modified `FeesAndCredits`. Pricing is applied transparently — registrar doesn't need to do anything special.
- **Wiring:** Configure your custom factory class in `ConfigModule.provideCustomLogicFactoryClass` — one config change, all custom logic in new files
- **Merge conflict impact:** Only one existing file touched (`ConfigModule`) to register the factory class; the custom logic subclass and factory subclass are entirely new files

**Recommendation:** Use **Approach 2 (DomainPricingCustomLogic)** for ongoing per-registrar base pricing (transparent, no registrar action needed) and **Approach 1 (AllocationTokens)** for time-limited promotions and marketing deals (registrar opts in with a promo code). Both can coexist.

#### Pricing Architecture
```
                    Registrar-Facing Price (stored in nomulus)
                    ┌─────────────────────────────────────────┐
                    │  TLD base price (Tld entity)            │
                    │  + Premium overrides (PremiumList)       │
                    │  - Token discounts (AllocationToken)     │
                    │  = What registrar pays RSP               │
                    └─────────────────┬───────────────────────┘
                                      │
                    RSP Margin = Registrar Price - RO Cost
                                      │
                    ┌─────────────────┴───────────────────────┐
                    │  RO Cost Basis (stored in dashboard DB)  │
                    │  Per TLD: create cost, renew cost, etc.  │
                    │  = What RSP pays upstream to RO           │
                    └─────────────────────────────────────────┘
```

**Frontend:**
- `registry-dashboard/pricing/` component with three sub-views:
  - **Registrar Prices:** Current TLD base prices from nomulus + active per-registrar tokens
    - Editable: modify TLD base prices (calls `configure_tld` equivalent)
    - Editable: create/modify per-registrar `AllocationToken` for custom pricing
    - Table: registrar | TLD | create price | renew price | restore price | source (base/token)
  - **RO Cost Basis:** What the RSP pays upstream to the RO per TLD
    - Editable: set/update per-TLD cost basis
    - Table: TLD | create cost to RO | renew cost to RO | restore cost to RO
  - **Margin Analysis:** Calculated view showing delta
    - Table: registrar | TLD | registrar price | RO cost | margin | margin %
    - Filterable, sortable, exportable
    - Highlights negative margins in red
- `registry-dashboard/promotions/` component — CRUD for promotional offers
  - Form: select registrar(s), set discount type (% off, fixed price, free), set date range, set domain count limit
  - Impact preview: shows projected margin impact before activating
  - Table of active/expired promotions with usage metrics

**Backend:**
- `RegistryDashboardPricingAction` — `/console-api/registry-dash/pricing` (GET/POST)
  - **GET:** Reads TLD pricing from `Tld` entity + `PremiumList` data + active `AllocationToken` per-registrar overrides
  - **POST (modify TLD base price):** Updates `Tld` entity `createBillingCostTransitions` / `renewBillingCostTransitions` via timed transitions (same mechanism as `ConfigureTldCommand`)
  - **POST (per-registrar price):** Creates/updates `AllocationToken` with:
    - `allowedClientIds` = target registrar ID
    - `discountPrice` or `discountFraction` for create pricing
    - `renewalPrice` + `renewalPriceBehavior=SPECIFIED` for renewal pricing
    - `tokenType=UNLIMITED_USE` for ongoing pricing (not one-time promos)
    - `allowedTlds` scoped to specific TLDs
- `RegistryDashboardCostBasisAction` — `/console-api/registry-dash/cost-basis` (GET/POST/PUT)
  - CRUD for RO cost basis (dashboard-only data, not in nomulus)
- `RegistryDashboardPromotionsAction` — `/console-api/registry-dash/promotions` (GET/POST/PUT/DELETE)
  - CRUD for promotional offers
  - On creation, generates `AllocationToken` entries with time-based `tokenStatusTransitions` (NOT_STARTED → VALID → ENDED)
  - Tracks usage against `max_domains` cap

**Data Model:**
- **NEW TABLE:** `registry_dashboard_ro_cost_basis` (what RSP pays the RO)
  ```sql
  CREATE TABLE registry_dashboard_ro_cost_basis (
    id BIGSERIAL PRIMARY KEY,
    tld TEXT NOT NULL,
    create_cost NUMERIC NOT NULL,           -- per-year create cost RSP pays RO
    renew_cost NUMERIC NOT NULL,            -- per-year renew cost RSP pays RO
    restore_cost NUMERIC NOT NULL DEFAULT 0,
    transfer_cost NUMERIC NOT NULL DEFAULT 0,
    currency TEXT NOT NULL DEFAULT 'USD',
    effective_date DATE NOT NULL,           -- when this cost basis takes effect
    notes TEXT,                              -- e.g. "per RO agreement v2"
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tld, effective_date)
  );
  ```
- **NEW TABLE:** `registry_dashboard_registrar_pricing` (per-registrar pricing rules — read by `DomainPricingCustomLogic` subclass at EPP time, and by dashboard for pricing views)
  ```sql
  CREATE TABLE registry_dashboard_registrar_pricing (
    id BIGSERIAL PRIMARY KEY,
    registrar_id TEXT NOT NULL,
    tld TEXT NOT NULL,
    pricing_mechanism TEXT NOT NULL DEFAULT 'CUSTOM_LOGIC',  -- 'CUSTOM_LOGIC' or 'ALLOCATION_TOKEN'
    allocation_token_key TEXT,              -- reference to nomulus AllocationToken (if token-based)
    create_price NUMERIC,                   -- registrar's create price
    renew_price NUMERIC,                    -- registrar's renew price
    restore_price NUMERIC,                  -- registrar's restore price
    transfer_price NUMERIC,                 -- registrar's transfer price
    discount_type TEXT,                     -- 'FIXED_PRICE', 'PERCENTAGE', 'FREE'
    notes TEXT,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(registrar_id, tld)
  );
  ```
- **NEW TABLE:** `registry_dashboard_promotion`
  ```sql
  CREATE TABLE registry_dashboard_promotion (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    created_by TEXT NOT NULL,
    registrar_ids TEXT[] NOT NULL,          -- which registrars; empty = all
    tlds TEXT[] NOT NULL,                   -- which TLDs
    discount_type TEXT NOT NULL,            -- 'PERCENTAGE', 'FIXED_PRICE', 'FREE'
    discount_value NUMERIC,                -- percentage or fixed price amount
    max_domains INT,                        -- cap on promotional registrations
    domains_used INT DEFAULT 0,
    start_date TIMESTAMPTZ NOT NULL,
    end_date TIMESTAMPTZ NOT NULL,
    allocation_token_prefix TEXT,           -- links to generated AllocationTokens
    status TEXT DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
  );
  ```
- **Integration:** Two mechanisms work together:
  - **Base per-registrar pricing** via `DomainPricingCustomLogic` subclass — reads from `registry_dashboard_registrar_pricing` table, applies pricing transparently server-side. Requires one config change to `ConfigModule.provideCustomLogicFactoryClass` to register the custom factory.
  - **Promotional pricing** via `AllocationToken` system — time-limited deals where registrar opts in with a token code.
  - **Cost-basis data** is dashboard-only (nomulus doesn't need to know what the RSP pays the RO).

---

### Feature 5: Billing & Margin Overview
**What:** Two-sided billing view — revenue from registrars (from nomulus BillingEvent data) vs costs to the RO (from dashboard cost-basis data) — showing net margin.

**Frontend:**
- `registry-dashboard/billing/` component
- **Revenue side:** Summary cards (total revenue MTD/QTD/YTD), revenue by registrar, by TLD
- **Cost side:** Total costs to RO by TLD, by time period
- **Margin view:** Revenue minus cost = RSP margin, broken down by registrar and TLD
  - Margin trend charts over time
  - Per-registrar profitability ranking
  - Alerts for registrars/TLDs with thin or negative margins
- Filterable table of billing events with cost-basis overlay
- Export to CSV

**Backend:**
- `RegistryDashboardBillingAction` — `/console-api/registry-dash/billing`
- **Revenue:** Aggregates from existing `BillingEvent` table (what registrars paid)
- **Costs:** Reads from `registry_dashboard_ro_cost_basis` table (what RSP pays RO), multiplied by transaction volumes
- **Margin:** Calculated as revenue - (cost_basis × volume) per registrar/TLD/period
- Groups by registrar, TLD, time period
- Currency handling via existing `Money` type

**Data Model:**
- Queries existing `BillingEvent` + `BillingRecurrence` for revenue
- Queries `registry_dashboard_ro_cost_basis` for cost side
- Pre-aggregated in `registry_dashboard_billing_stats` for performance:
  ```sql
  CREATE TABLE registry_dashboard_billing_stats (
    stat_date DATE NOT NULL,
    registrar_id TEXT NOT NULL,
    tld TEXT NOT NULL,
    revenue_create NUMERIC DEFAULT 0,       -- registrar payments for creates
    revenue_renew NUMERIC DEFAULT 0,        -- registrar payments for renewals
    revenue_restore NUMERIC DEFAULT 0,
    revenue_transfer NUMERIC DEFAULT 0,
    cost_create NUMERIC DEFAULT 0,          -- RSP cost to RO for creates
    cost_renew NUMERIC DEFAULT 0,           -- RSP cost to RO for renewals
    cost_restore NUMERIC DEFAULT 0,
    cost_transfer NUMERIC DEFAULT 0,
    transaction_count_create INT DEFAULT 0,
    transaction_count_renew INT DEFAULT 0,
    transaction_count_restore INT DEFAULT 0,
    transaction_count_transfer INT DEFAULT 0,
    currency TEXT NOT NULL DEFAULT 'USD',
    PRIMARY KEY (stat_date, registrar_id, tld, currency)
  );
  ```

---

### Feature 6: AI-Powered Business Analytics
**What:** Interactive conversational interface where RO users can ask questions about their registry data in natural language.

**Frontend:**
- `registry-dashboard/ai-analytics/` component
- Chat-style UI: message input, conversation history, streaming responses
- Displays inline charts/tables when the AI returns structured data
- Suggested prompts: "Show me domain registration trends for last 30 days", "Which registrar has the most domains?", "What's our revenue breakdown by TLD?"

**Backend:**
- `RegistryDashboardAiQueryAction` — `/console-api/registry-dash/ai-query` (POST)
- **Architecture:**
  1. User sends natural language query
  2. Backend constructs a system prompt with:
     - Schema context (available tables, columns, relationships)
     - User's RO scope (which registrars they can access)
     - Security constraints (read-only, no PII exposure)
  3. Calls Claude API (or other LLM) with tool-use to generate SQL
  4. Validates generated SQL (read-only, scoped to allowed registrars)
  5. Executes query against read replica
  6. Returns results + natural language summary to frontend
- **Safety:**
  - SQL validation layer: only SELECT statements, no JOINs to sensitive tables
  - Row-level security: WHERE clause injection for registrar scoping
  - Rate limiting per user
  - Query result size limits
  - Conversation history stored in session (not persisted)

**Data Model:**
- **NEW TABLE:** `registry_dashboard_ai_query_log` (audit trail)
  ```sql
  CREATE TABLE registry_dashboard_ai_query_log (
    id BIGSERIAL PRIMARY KEY,
    user_email TEXT NOT NULL,
    query_text TEXT NOT NULL,
    generated_sql TEXT,
    result_row_count INT,
    error TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
  );
  ```

**LLM Integration Options:**
- **Option 1 (Recommended):** Claude API via Anthropic Java SDK — tool-use for SQL generation, streaming for response
- **Option 2:** Self-hosted model if data sovereignty is a concern
- **Option 3:** Google Vertex AI (already in GCP ecosystem)

---

## New Permission Model

```java
// Added to existing ConsolePermission enum
REGISTRY_DASHBOARD_VIEW,           // View dashboard overview
REGISTRY_DASHBOARD_ANALYTICS,      // Access domain/billing analytics
REGISTRY_DASHBOARD_PRICING,        // View pricing
REGISTRY_DASHBOARD_PROMOTIONS,     // Create/manage promotions
REGISTRY_DASHBOARD_AI_QUERY,       // Use AI analytics
REGISTRY_DASHBOARD_BILLING,        // View billing data
```

```java
// Added to existing GlobalRole enum (or new concept)
REGISTRY_OPERATOR   // Can access registry dashboard for mapped registrars
```

---

## Implementation Phases

### Phase 1: Foundation (2-3 weeks)
1. Add `REGISTRY_OPERATOR` role and new permissions to existing enums
2. Create `registry_dashboard_ro_registrar_mapping` table + Flyway migration
3. Create `RegistryDashboardModule` (Dagger) and base action class
4. Create `registry-dashboard` Angular module with lazy loading
5. Add nav entry for RO users
6. Implement Dashboard Overview (read-only aggregate queries)

### Phase 2: Core Features (3-4 weeks)
1. Registrar Portfolio view
2. Domain Analytics with pre-aggregated stats table
3. Billing Overview
4. Scheduled job for daily stats aggregation

### Phase 3: Pricing & Promotions (2-3 weeks)
1. Pricing view (read existing data)
2. Promotions CRUD
3. AllocationToken integration for promotional pricing
4. Free domain and marketing deal workflows

### Phase 4: AI Analytics (2-3 weeks)
1. Chat UI component
2. Backend AI query action with Claude API integration
3. SQL generation, validation, and execution pipeline
4. Query audit logging
5. Safety guardrails and testing

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Upstream merge conflicts | Medium | Isolate changes in new directories; touch < 10 existing files |
| Performance of aggregate queries on large datasets | High | Pre-aggregated stats table; read replica; query caching |
| AI-generated SQL injection/data leak | Critical | SQL validation layer; registrar-scoped WHERE clauses; read-only queries |
| Scope creep beyond initial features | Medium | Strict phase gating; MVP per feature |
| Angular version upgrades from upstream | Low | New module is self-contained; Angular lazy loading provides isolation |

---

## Files Modified in Existing Codebase (Merge Conflict Surface)

Estimated **9 existing files** touched:

1. `console-webapp/src/app/app-routing.module.ts` — add 1 lazy route
2. `console-webapp/src/app/app.module.ts` — import new module
3. `console-webapp/src/app/navigation/navigation.component.ts` — add RO nav items
4. `console-webapp/src/app/navigation/navigation.component.html` — add RO nav template
5. `console-webapp/src/app/shared/services/userData.service.ts` — extend with RO role
6. `core/src/main/java/google/registry/model/console/ConsolePermission.java` — add permissions
7. `core/src/main/java/google/registry/model/console/GlobalRole.java` — add REGISTRY_OPERATOR
8. `core/src/main/java/google/registry/ui/server/console/ConsoleDaggerModule.java` — wire new Dagger module
9. `core/src/main/java/google/registry/config/RegistryConfig.java` (ConfigModule) — register custom `CustomLogicFactory` subclass for per-registrar pricing

All other code is in **new files/directories** with zero conflict risk.

---

## Pros & Cons

### Pros
- Single deployment — no new infrastructure to manage
- Reuses existing auth, build pipeline, and deployment
- Direct access to nomulus DB — no API abstraction overhead
- Consistent UI/UX with existing console
- Lower operational complexity

### Cons
- **Merge conflict risk** (mitigated but not eliminated)
- Constrained to Angular + Java tech stack (Angular is fine, but Java backend is unusual custom framework)
- Must work within nomulus's Dagger DI and Action-based routing patterns
- Frontend and backend are coupled in the same monorepo build
- AI analytics requires adding LLM SDK dependency to nomulus core
- Harder to scale independently from the registrar console
