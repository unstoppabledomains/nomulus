# Plan B: Separate Registry Dashboard Application

## Overview

Build a standalone Registry Dashboard as an independent application with its own frontend, API layer, and mini database — communicating with nomulus via its existing API and a read replica of the nomulus DB.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  Registry Dashboard (Standalone Application)                         │
│                                                                      │
│  ┌────────────────────┐    ┌──────────────────────────────────┐      │
│  │  Next.js Frontend  │◄──►│  API Layer (Next.js API Routes)  │      │
│  │                    │    │  or NestJS Backend               │      │
│  │  - Dashboard       │    │                                  │      │
│  │  - Registrar Mgmt  │    │  ┌──────────────────────┐       │      │
│  │  - Domain Analytics│    │  │  Dashboard DB (PG)   │       │      │
│  │  - Pricing/Promos  │    │  │  - RO accounts       │       │      │
│  │  - Billing         │    │  │  - RO↔Registrar maps │       │      │
│  │  - AI Chat         │    │  │  - Promotions        │       │      │
│  │                    │    │  │  - Pre-agg stats     │       │      │
│  └────────────────────┘    │  │  - AI query logs     │       │      │
│                            │  └──────────────────────┘       │      │
│                            │                                  │      │
│                            │  ┌──────────────────────┐       │      │
│                            │  │  Nomulus API Client   │       │      │
│                            │  │  (HTTP calls to       │       │      │
│                            │  │   /console-api/*)     │       │      │
│                            │  └──────────┬───────────┘       │      │
│                            │             │                    │      │
│                            │  ┌──────────▼───────────┐       │      │
│                            │  │  Nomulus DB           │       │      │
│                            │  │  (Read Replica)       │       │      │
│                            │  │  - Domains            │       │      │
│                            │  │  - Registrars         │       │      │
│                            │  │  - Billing            │       │      │
│                            │  └──────────────────────┘       │      │
│                            └──────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────────────┘

Data Flow:
  READ operations  → Nomulus DB read replica (direct SQL for analytics)
  WRITE operations → Nomulus API (/console-api/*) for domain/registrar mutations
  Dashboard state  → Dashboard DB (RO accounts, mappings, promotions)
```

---

## Technology Stack

### Frontend: Next.js 15 (App Router)
- **Why Next.js:** Server components for fast initial loads, API routes built-in, excellent TypeScript support, huge ecosystem, AI SDK support (Vercel AI SDK)
- **UI:** shadcn/ui + Tailwind CSS (modern, accessible, highly customizable)
- **Charts:** Recharts or Tremor (React-native charting, good for dashboards)
- **State:** React Server Components for data fetching; Zustand or React Context for client state
- **Auth:** NextAuth.js (supports Google OAuth, OIDC, custom providers)

### Backend API: Next.js API Routes (or separate NestJS if more structure needed)
- **Option A (Recommended for MVP):** Next.js Route Handlers — keeps everything in one deployment
- **Option B (If API grows complex):** NestJS — separate backend service with decorators, DI, OpenAPI generation

### Databases
- **Dashboard DB:** PostgreSQL (small, dedicated instance) — stores RO accounts, RO↔registrar mappings, promotions, AI query logs, pre-aggregated stats
- **Nomulus DB:** Read replica connection (read-only) — for domain analytics, billing aggregation, registrar data

### AI Integration: Vercel AI SDK + Claude API
- **Why:** First-class streaming support, tool-use, structured output — purpose-built for conversational AI in Next.js apps
- **Alternative:** Direct Anthropic TypeScript SDK if not using Vercel hosting

### Deployment
- **Option 1:** Cloud Run (already in GCP ecosystem with nomulus)
- **Option 2:** Vercel (easiest for Next.js, but adds external dependency)
- **Option 3:** GKE alongside nomulus (same k8s cluster)

---

## Features — Detailed Breakdown

### Feature 1: Dashboard Overview
**What:** Aggregate metrics — total domains, active registrars, growth trends, health status.

**Frontend:**
- Next.js Server Component that fetches data at render time
- KPI cards (total domains, active registrars, 30-day growth, revenue)
- Trend sparklines using Recharts
- Auto-refresh via React Query or SWR with polling

**Backend:**
- `GET /api/dashboard/overview`
- Queries nomulus read replica:
  ```sql
  SELECT COUNT(*) as total_domains FROM "Domain" WHERE "deletionTime" > NOW();
  SELECT "currentSponsorRegistrarId", COUNT(*) as domain_count
    FROM "Domain" WHERE "deletionTime" > NOW()
    GROUP BY "currentSponsorRegistrarId";
  ```
- Results cached with TTL (e.g., 5 minutes) using Redis or in-memory cache

---

### Feature 2: Registrar Portfolio
**What:** All registrars under this RO's management — status, TLDs, domain counts, contact info.

**Frontend:**
- Data table with sorting, filtering, pagination (TanStack Table or shadcn DataTable)
- Click-through to registrar detail view
- Bulk actions (apply promotion, send notification)
- Status badges (ACTIVE, SUSPENDED, PENDING)

**Backend:**
- `GET /api/registrars` — reads from nomulus DB read replica, filtered by RO mapping
- `GET /api/registrars/:id` — detailed registrar info + domain stats
- RO↔registrar mappings stored in dashboard DB

**Dashboard DB Schema:**
```sql
CREATE TABLE ro_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  organization TEXT,
  auth_provider_id TEXT UNIQUE NOT NULL,  -- from OAuth
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE ro_registrar_mappings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  ro_account_id UUID NOT NULL REFERENCES ro_accounts(id),
  registrar_id TEXT NOT NULL,  -- maps to nomulus Registrar.registrarId
  access_level TEXT DEFAULT 'FULL',  -- FULL, READ_ONLY, PRICING_ONLY
  created_at TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(ro_account_id, registrar_id)
);
```

---

### Feature 3: Domain Analytics
**What:** Interactive analytics — registrations over time, TLD breakdown, expiration forecasts, churn analysis.

**Frontend:**
- Date range picker with presets (7d, 30d, 90d, YTD, custom)
- Multi-series line chart (registrations, deletions, net growth)
- Stacked bar chart (domains by TLD)
- Pie chart (market share by registrar)
- Filterable by registrar, TLD, date range
- Export to CSV/PDF

**Backend:**
- `GET /api/analytics/domains?from=&to=&registrarId=&tld=`
- Pre-aggregated daily stats in dashboard DB (populated by scheduled job)
- Real-time queries against read replica for ad-hoc filters

**Dashboard DB Schema:**
```sql
CREATE TABLE domain_daily_stats (
  stat_date DATE NOT NULL,
  registrar_id TEXT NOT NULL,
  tld TEXT NOT NULL,
  total_domains INT NOT NULL DEFAULT 0,
  new_registrations INT NOT NULL DEFAULT 0,
  deletions INT NOT NULL DEFAULT 0,
  renewals INT NOT NULL DEFAULT 0,
  transfers_in INT NOT NULL DEFAULT 0,
  transfers_out INT NOT NULL DEFAULT 0,
  PRIMARY KEY (stat_date, registrar_id, tld)
);

-- Populated by a cron job / Cloud Scheduler task that queries nomulus read replica
```

**Aggregation Job:**
- Runs daily (e.g., 2am UTC)
- Queries nomulus DB for previous day's activity
- Inserts/upserts into `domain_daily_stats`
- Can be a Cloud Run Job or Cloud Scheduler + Cloud Function

---

### Feature 4: Pricing Management (Two-Tier Model)
**What:** Manage both sides of the pricing equation — what registrars pay the RSP, and what the RSP pays upstream to the RO — with the RSP keeping the delta. Supports per-registrar pricing.

#### How Nomulus Pricing Works (Context)
- **TLD base prices** stored on `Tld` entity as time-based transitions (`createBillingCostTransitions`, `renewBillingCostTransitions`, default $8 USD)
- **Premium prices** via `PremiumList` entities (per-domain-label overrides)
- **No built-in per-registrar pricing** on the Registrar entity itself
- Nomulus has **no concept of upstream/cost-basis pricing**

#### Per-Registrar Pricing — Two Approaches Available

Nomulus provides **two** mechanisms for implementing per-registrar pricing:

**Approach 1: AllocationTokens (explicit, registrar-initiated)**
- Create `AllocationToken` with `allowedClientIds` scoped to a specific registrar
- Supports `discountFraction`, `discountPrice`, and `renewalPrice` overrides
- Registrar must include the token in EPP commands (create, renew, etc.)
- Well-tested, standard mechanism; good for promotions where registrar opts in
- Managed via `GenerateAllocationTokensCommand` CLI or direct DB writes

**Approach 2: DomainPricingCustomLogic (transparent, server-side)**
- Nomulus has a built-in extension point: `DomainPricingCustomLogic` (`core/.../flows/custom/DomainPricingCustomLogic.java`)
- No-op base class with hooks for every pricing operation: `customizeCreatePrice`, `customizeRenewPrice`, `customizeRestorePrice`, `customizeTransferPrice`, `customizeUpdatePrice`
- The `CustomLogicFactory` provides access to `SessionMetadata` (registrar `clientId`) — its own javadoc says: *"A common use case might be switching based on the registrar clientId in sessionMetadata"*
- **How it would work:** Subclass `DomainPricingCustomLogic` in the nomulus fork, look up per-registrar pricing rules from the dashboard DB (or a shared table in the nomulus DB), return modified `FeesAndCredits`. Pricing is applied transparently — registrar doesn't need to do anything special.
- **Wiring:** One config change to `ConfigModule.provideCustomLogicFactoryClass`; custom logic subclass and factory subclass are entirely new files in nomulus
- **Note for Plan B:** This still requires a small code change in the nomulus fork (new files + 1 config line), but it's the cleanest way to implement per-registrar base pricing at the EPP layer

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
- **Registrar Prices page:** Current TLD base prices from nomulus + active per-registrar tokens
  - Editable: modify TLD base prices (calls nomulus API or `configure_tld` CLI equivalent)
  - Editable: create/modify per-registrar `AllocationToken` for custom pricing
  - Table: registrar | TLD | create price | renew price | restore price | source (base/token)
- **RO Cost Basis page:** What the RSP pays upstream to the RO per TLD
  - Editable: set/update per-TLD cost basis
  - Table: TLD | create cost to RO | renew cost to RO | restore cost to RO
- **Margin Analysis page:** Calculated view showing delta
  - Table: registrar | TLD | registrar price | RO cost | margin | margin %
  - Filterable, sortable, exportable
  - Highlights negative margins in red
- **Promotions Manager:**
  - Create promotion form: name, registrar(s), TLD(s), discount type, date range, domain cap
  - Impact preview: shows projected margin impact before activating
  - Active promotions dashboard with usage metrics
  - Edit/deactivate promotions
  - Promotion templates for common deals

**Backend:**
- `GET /api/pricing` — reads TLD pricing from nomulus read replica (`Tld` entity) + `PremiumList` data + active `AllocationToken` per-registrar overrides
- `POST /api/pricing/tld` — updates TLD base prices
  - Calls nomulus API or executes `nomulus configure_tld` CLI to update `Tld.createBillingCostTransitions` / `renewBillingCostTransitions`
- `POST /api/pricing/registrar` — creates/updates per-registrar pricing
  - **For base per-registrar pricing (Approach 2 — DomainPricingCustomLogic):**
    - Writes pricing rules to a shared table (in nomulus DB or dashboard DB) that the custom logic subclass reads at EPP time
    - Requires a one-time setup: subclass `DomainPricingCustomLogic` + `CustomLogicFactory` in nomulus fork, register in `ConfigModule`
    - Pricing applied transparently — registrar doesn't need to know or do anything
  - **For promotional pricing (Approach 1 — AllocationTokens):**
    - Creates `AllocationToken` in nomulus with `allowedClientIds` = target registrar, `discountPrice`/`discountFraction`, `renewalPrice`, `tokenType=UNLIMITED_USE`, `allowedTlds` scoped
    - Must use nomulus CLI (`nomulus generate_allocation_tokens`) or write directly to nomulus DB — no console API endpoint for token creation exists today
    - Registrar opts in by using the token in EPP commands
- `GET/POST/PUT /api/pricing/cost-basis` — CRUD for RO cost basis (dashboard DB only)
- `GET/POST/PUT/DELETE /api/promotions` — CRUD against dashboard DB
  - On creation, generates `AllocationToken` entries with time-based `tokenStatusTransitions`
  - Tracks usage against `max_domains` cap

**Dashboard DB Schema:**
```sql
-- What the RSP pays upstream to the RO per TLD
CREATE TABLE ro_cost_basis (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tld TEXT NOT NULL,
  create_cost NUMERIC NOT NULL,           -- per-year create cost RSP pays RO
  renew_cost NUMERIC NOT NULL,            -- per-year renew cost RSP pays RO
  restore_cost NUMERIC NOT NULL DEFAULT 0,
  transfer_cost NUMERIC NOT NULL DEFAULT 0,
  currency TEXT NOT NULL DEFAULT 'USD',
  effective_date DATE NOT NULL,           -- when this cost basis takes effect
  notes TEXT,                              -- e.g. "per RO agreement v2"
  created_by UUID NOT NULL REFERENCES ro_accounts(id),
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(tld, effective_date)
);

-- Per-registrar pricing rules (read by DomainPricingCustomLogic subclass at EPP time)
-- Also used as source of truth for dashboard pricing views
CREATE TABLE registrar_pricing (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
  created_by UUID NOT NULL REFERENCES ro_accounts(id),
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(registrar_id, tld)
);

-- Promotional pricing campaigns
CREATE TABLE promotions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  description TEXT,
  created_by UUID NOT NULL REFERENCES ro_accounts(id),
  registrar_ids TEXT[] NOT NULL,        -- empty array = all registrars
  tlds TEXT[] NOT NULL,
  discount_type TEXT NOT NULL,          -- 'PERCENTAGE', 'FIXED_PRICE', 'FREE'
  discount_value NUMERIC,
  max_domains INT,
  domains_used INT DEFAULT 0,
  start_date TIMESTAMPTZ NOT NULL,
  end_date TIMESTAMPTZ NOT NULL,
  allocation_token_prefix TEXT,
  status TEXT DEFAULT 'DRAFT',          -- DRAFT, ACTIVE, PAUSED, EXPIRED, CANCELLED
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE promotion_usage (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  promotion_id UUID NOT NULL REFERENCES promotions(id),
  domain_name TEXT NOT NULL,
  registrar_id TEXT NOT NULL,
  used_at TIMESTAMPTZ DEFAULT NOW()
);
```

---

### Feature 5: Billing & Margin Overview
**What:** Two-sided billing view — revenue from registrars (from nomulus BillingEvent data) vs costs to the RO (from dashboard cost-basis data) — showing net RSP margin.

**Frontend:**
- **Revenue side:** KPI cards (MTD, QTD, YTD, all-time), revenue by registrar, by TLD
- **Cost side:** Total costs to RO by TLD, by time period
- **Margin view:** Revenue minus cost = RSP margin, broken down by registrar and TLD
  - Margin trend charts over time
  - Per-registrar profitability ranking
  - Alerts for registrars/TLDs with thin or negative margins
- Invoice-style view per registrar (what they owe)
- Export to CSV/PDF

**Backend:**
- `GET /api/billing/summary?from=&to=` — combined revenue + cost + margin
- `GET /api/billing/by-registrar?from=&to=` — per-registrar P&L
- `GET /api/billing/by-tld?from=&to=` — per-TLD P&L
- **Revenue:** Aggregates from nomulus read replica `BillingEvent` table
- **Costs:** Reads from `ro_cost_basis` table, multiplied by transaction volumes
- **Margin:** Calculated as revenue - (cost_basis x volume) per registrar/TLD/period
- Pre-aggregated for fast dashboard loads

**Dashboard DB Schema:**
```sql
CREATE TABLE billing_daily_stats (
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
**What:** Conversational AI interface for querying registry data using natural language.

**Frontend:**
- Chat panel (slide-out or dedicated page)
- Built with Vercel AI SDK's `useChat` hook — handles streaming, message history, tool results natively
- Renders inline data visualizations when AI returns chart data
- Suggested prompts sidebar
- Conversation history (persisted per session or per user)

**Backend:**
- `POST /api/ai/chat` — streaming endpoint
- **Architecture (using Vercel AI SDK + Claude):**

```typescript
// Simplified flow
import { anthropic } from '@ai-sdk/anthropic';
import { streamText, tool } from 'ai';

export async function POST(req: Request) {
  const { messages } = await req.json();
  const roScope = await getRoRegistrarIds(session.user); // security scoping

  const result = streamText({
    model: anthropic('claude-sonnet-4-6'),
    system: `You are a registry analytics assistant. You can query domain registration,
             billing, and registrar data. You MUST only query data for these registrar IDs:
             ${roScope.join(', ')}. Generate read-only SQL queries only.`,
    messages,
    tools: {
      queryDomainStats: tool({
        description: 'Query domain registration statistics',
        parameters: z.object({
          sql: z.string().describe('Read-only SQL query against domain_daily_stats'),
        }),
        execute: async ({ sql }) => {
          // Validate SQL is read-only and scoped
          const validated = validateAndScopeQuery(sql, roScope);
          return await dashboardDb.query(validated);
        },
      }),
      queryBillingStats: tool({
        description: 'Query billing/revenue statistics',
        parameters: z.object({
          sql: z.string().describe('Read-only SQL query against billing_daily_stats'),
        }),
        execute: async ({ sql }) => {
          const validated = validateAndScopeQuery(sql, roScope);
          return await dashboardDb.query(validated);
        },
      }),
      generateChart: tool({
        description: 'Generate a chart from data',
        parameters: z.object({
          type: z.enum(['line', 'bar', 'pie']),
          data: z.array(z.record(z.any())),
          xAxis: z.string(),
          yAxis: z.string(),
          title: z.string(),
        }),
        // Client-side rendering — tool result sent back to frontend
      }),
    },
  });

  return result.toDataStreamResponse();
}
```

**Safety Guardrails:**
- SQL validation: whitelist of allowed tables, only SELECT, no DDL/DML
- Registrar scoping: automatically inject WHERE clause limiting to RO's registrars
- Query result limits: max 10,000 rows
- Rate limiting: 50 queries/hour per user
- Audit logging: all queries + generated SQL logged

**Dashboard DB Schema:**
```sql
CREATE TABLE ai_conversations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  ro_account_id UUID NOT NULL REFERENCES ro_accounts(id),
  title TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE ai_messages (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id UUID NOT NULL REFERENCES ai_conversations(id),
  role TEXT NOT NULL,              -- 'user', 'assistant', 'tool'
  content TEXT NOT NULL,
  tool_calls JSONB,               -- tool invocations
  tool_results JSONB,             -- tool execution results
  generated_sql TEXT,             -- for audit
  created_at TIMESTAMPTZ DEFAULT NOW()
);
```

---

## Authentication & Authorization

### Auth Flow
```
User → Next.js App → NextAuth.js → Google OAuth 2.0 → Dashboard DB (ro_accounts)
                                                        ↓
                                          ro_registrar_mappings table
                                                        ↓
                                          Scoped access to nomulus data
```

### Auth Implementation
- **NextAuth.js** with Google provider (same Google Workspace as nomulus)
- On first login, user is created in `ro_accounts` table
- Admin assigns registrar mappings via admin UI or CLI
- JWT session tokens with registrar scope embedded

### Authorization Levels
```
SUPER_ADMIN  — Can manage all RO accounts and all registrar mappings
RO_ADMIN     — Can manage their own registrar portfolio (full CRUD)
RO_VIEWER    — Read-only access to their registrar portfolio
RO_PRICING   — Can manage pricing/promotions but not other settings
```

---

## Project Structure

```
registry-dashboard/
├── package.json
├── next.config.ts
├── tailwind.config.ts
├── tsconfig.json
├── drizzle.config.ts              # DB schema management (Drizzle ORM)
├── docker-compose.yml             # Local dev (dashboard DB + nomulus DB replica)
├── Dockerfile                     # Production container
├── cloudbuild.yaml                # GCP Cloud Build
│
├── src/
│   ├── app/                       # Next.js App Router
│   │   ├── layout.tsx             # Root layout (nav, auth provider)
│   │   ├── page.tsx               # Dashboard overview (home)
│   │   ├── registrars/
│   │   │   ├── page.tsx           # Registrar portfolio
│   │   │   └── [id]/page.tsx      # Registrar detail
│   │   ├── analytics/
│   │   │   ├── domains/page.tsx   # Domain analytics
│   │   │   └── billing/page.tsx   # Billing analytics
│   │   ├── pricing/
│   │   │   ├── page.tsx           # Pricing overview
│   │   │   └── promotions/page.tsx# Promotions manager
│   │   ├── ai/
│   │   │   └── page.tsx           # AI analytics chat
│   │   ├── settings/
│   │   │   └── page.tsx           # Dashboard settings
│   │   └── api/                   # API Route Handlers
│   │       ├── dashboard/
│   │       ├── registrars/
│   │       ├── analytics/
│   │       ├── billing/
│   │       ├── pricing/
│   │       ├── promotions/
│   │       ├── ai/
│   │       └── auth/[...nextauth]/
│   │
│   ├── components/                # Shared UI components
│   │   ├── ui/                    # shadcn/ui components
│   │   ├── charts/                # Chart wrappers
│   │   ├── data-table/            # Data table components
│   │   └── ai-chat/               # AI chat panel
│   │
│   ├── lib/                       # Shared utilities
│   │   ├── db/                    # Dashboard DB client + schema
│   │   ├── nomulus-db/            # Nomulus read replica client
│   │   ├── nomulus-api/           # HTTP client for nomulus API
│   │   ├── auth/                  # Auth utilities
│   │   ├── ai/                    # AI query validation + tools
│   │   └── utils/                 # General utilities
│   │
│   └── types/                     # TypeScript type definitions
│
├── drizzle/                       # DB migrations
│   └── migrations/
│
└── tests/                         # Test files
```

---

## Implementation Phases

### Phase 1: Foundation (2-3 weeks)
1. Initialize Next.js 15 project with TypeScript, Tailwind, shadcn/ui
2. Set up NextAuth.js with Google OAuth
3. Create dashboard PostgreSQL schema (ro_accounts, mappings)
4. Establish nomulus DB read replica connection
5. Build layout: sidebar nav, header, auth guards
6. Deploy skeleton to Cloud Run
7. Dashboard Overview page (aggregate domain/registrar counts)

### Phase 2: Core Views (3-4 weeks)
1. Registrar Portfolio (list + detail views)
2. Domain Analytics (charts, date range filtering)
3. Daily stats aggregation job (Cloud Scheduler)
4. Data export (CSV)

### Phase 3: Billing & Pricing (2-3 weeks)
1. Billing Overview (revenue charts, breakdowns)
2. Billing daily stats aggregation
3. Pricing view (read from nomulus data)
4. Promotions CRUD

### Phase 4: AI Analytics (2-3 weeks)
1. AI chat UI component with streaming
2. Vercel AI SDK + Claude integration
3. Tool definitions for domain/billing/registrar queries
4. SQL validation and security scoping
5. Conversation persistence
6. Audit logging

### Phase 5: Polish & Production (1-2 weeks)
1. Error handling, loading states, empty states
2. Responsive design
3. Performance optimization (caching, query optimization)
4. Production deployment pipeline
5. Monitoring and alerting

---

## Infrastructure Requirements

### New Services
| Service | Purpose | Estimated Cost |
|---------|---------|----------------|
| Cloud Run instance | Dashboard app hosting | ~$30-100/mo |
| Cloud SQL (PostgreSQL) | Dashboard DB | ~$30-50/mo (small instance) |
| Cloud SQL read replica connection | Nomulus data access | Already exists or ~$0 (connection only) |
| Cloud Scheduler | Daily stats aggregation | ~$0.10/mo |
| Anthropic API | AI analytics | ~$10-50/mo depending on usage |
| Secret Manager | API keys, DB credentials | ~$1/mo |

### Networking
- Dashboard Cloud Run → Nomulus DB read replica: VPC connector or Cloud SQL Auth Proxy
- Dashboard Cloud Run → Nomulus API: Internal HTTP (if in same GCP project) or external HTTPS
- Dashboard Cloud Run → Dashboard DB: Cloud SQL Auth Proxy

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Nomulus API doesn't support needed operations (e.g., AllocationToken creation) | High | Fall back to direct DB writes via nomulus CLI tool or add API endpoints to nomulus fork |
| Per-registrar pricing requires small nomulus fork change | Low | `DomainPricingCustomLogic` subclass + `CustomLogicFactory` subclass + 1 config line in `ConfigModule` — all new files except the config line; low merge conflict risk |
| Read replica lag causes stale analytics data | Low | Pre-aggregated stats are daily anyway; real-time queries show "as of" timestamp |
| Maintaining two separate DB connections adds complexity | Medium | Use Drizzle ORM with separate connection configs; clear separation in code |
| New infrastructure to manage and monitor | Medium | Cloud Run is low-ops; standard GCP monitoring |
| Auth mismatch between nomulus console and dashboard | Low | Both use Google OAuth; could share session if needed |
| AI query generating incorrect/harmful SQL | Critical | Multi-layer validation: SQL parsing, whitelist tables/operations, registrar scoping, result limits |
| Scope creep with a greenfield project | High | Strict phase gating; MVP mindset; resist adding features not in the plan |

---

## Pros & Cons

### Pros
- **Near-zero merge conflict risk** with upstream nomulus — separate codebase; only touch is `DomainPricingCustomLogic` subclass in nomulus fork (new files + 1 config line) for per-registrar pricing
- **Modern tech stack** — Next.js, TypeScript, Tailwind, shadcn/ui — faster development, better DX
- **Independent scaling** — dashboard can scale separately from nomulus
- **AI-first design** — Vercel AI SDK is purpose-built for conversational AI in Next.js; much easier than retrofitting into Java
- **Faster iteration** — can deploy independently without touching nomulus build/deploy pipeline
- **Clean data separation** — dashboard-specific state in its own DB, nomulus data accessed read-only
- **Flexible deployment** — Cloud Run, Vercel, GKE, wherever makes sense
- **Easier onboarding** — TypeScript/React is more common than Angular + custom Java frameworks

### Cons
- **New infrastructure** to set up, deploy, and monitor
- **Two codebases** to maintain
- **Nomulus API limitations** — may not expose all needed operations (especially write operations like AllocationToken creation)
- **Network latency** for nomulus API calls (mitigated by read replica for reads)
- **Auth duplication** — separate auth system (though both Google OAuth)
- **Higher initial setup cost** — new project, new CI/CD, new deployment pipeline
- **Data consistency** — must handle read replica lag and eventual consistency

---

## Comparison Notes (vs Plan A)

| Dimension | Plan A (Extend) | Plan B (Separate) |
|-----------|-----------------|-------------------|
| Merge conflict risk | Low-Medium (9 files) | Near-zero (1 config line in nomulus fork for per-registrar pricing) |
| Infrastructure cost | $0 incremental | ~$60-200/mo |
| Time to MVP | ~4-5 weeks | ~5-6 weeks |
| AI integration ease | Harder (Java + custom framework) | Easier (Vercel AI SDK) |
| Tech stack flexibility | Constrained (Angular + Java) | Full freedom |
| Operational overhead | None (same deploy) | New service to manage |
| Long-term maintainability | Risk grows with upstream divergence | Independent evolution |
| Team familiarity | Must know Angular + Dagger | Modern JS/TS (more common) |
