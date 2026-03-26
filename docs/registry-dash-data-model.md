# Registry Dashboard - Data Model & Relationships

## Overview

The Registry Dashboard extends the Nomulus domain registry with operator-facing analytics, per-registrar pricing, financial tracking, and configuration. It introduces a **three-level scoping model** (Registry → TLDs → Users) that overlays the existing Nomulus data model to provide multi-tenant access control.

---

## Entity Relationship Diagram

```mermaid
erDiagram
    RoRegistry ||--o{ RoRegistryTld : "contains"
    RoRegistry ||--o{ RoRegistryUser : "grants access to"
    RoRegistryUser }o--|| User : "references"
    RoRegistryTld }o--|| Tld : "maps to"
    Registrar }o--o{ Tld : "allowedTlds"
    Domain }o--|| Registrar : "currentSponsorRegistrarId"
    Domain }o--|| Tld : "tld"
    RegistrarPricing }o--|| Registrar : "registrarId"
    RegistrarPricing }o--|| Tld : "tld"
    CostBasis }o--|| Tld : "tld"
    CostBasis }o--o| Registrar : "registrarId (nullable)"

    RoRegistry {
        bigint id PK
        string name UK
        jsonb settings "feature flags, default '{}'"
    }

    RoRegistryTld {
        bigint id PK
        bigint registryId FK
        string tld FK
    }

    RoRegistryUser {
        bigint id PK
        bigint registryId FK
        string userEmailAddress FK
    }

    User {
        string emailAddress PK
        UserRoles userRoles
        string registryLockEmailAddress
    }

    Registrar {
        string registrarId PK
        string registrarName
        enum type "REAL|OTE|TEST|..."
        enum state "ACTIVE|SUSPENDED|..."
        set allowedTlds "set of TLD strings"
        long ianaIdentifier
    }

    Tld {
        string tldStr PK "column: tld_name"
        string tldUnicode
        string roidSuffix
        boolean invoicingEnabled
        boolean escrowEnabled
    }

    Domain {
        string repoId PK
        string domainName
        string tld FK
        string currentSponsorRegistrarId FK
        datetime registrationExpirationTime
        datetime creationTime
        datetime deletionTime
    }

    RegistrarPricing {
        bigint id PK
        string registrarId FK
        string tld FK
        string operation "CREATE|RENEW|TRANSFER|RESTORE"
        decimal priceAmount "numeric(19,2)"
        string priceCurrency
        timestamptz effectiveDate
        timestamptz expiryDate
        boolean isActive
    }

    CostBasis {
        bigint id PK
        string tld FK
        string operation "CREATE|RENEW|TRANSFER|RESTORE"
        string registrarId FK "nullable - NULL means all registrars"
        decimal costAmount "numeric(19,2)"
        string costCurrency
        timestamptz effectiveDate
        string notes
    }
```

---

## Data Flow Diagram

```mermaid
flowchart TB
    subgraph Auth ["Authentication & Access Control (Three-Level Scoping)"]
        U[User<br/>emailAddress + globalRole]
        REG[RoRegistry<br/>named group + settings]
        RTL[RoRegistryTld<br/>registry → TLD mapping]
        RU[RoRegistryUser<br/>registry → user mapping]
        U -->|"REGISTRY_OPERATOR role"| RU
        RU -->|"belongs to"| REG
        REG -->|"contains"| RTL
    end

    subgraph Core ["Core Nomulus Entities (read-only)"]
        R[Registrar<br/>registrarId, name, state]
        T[Tld<br/>tldStr]
        D[Domain<br/>domainName]
        R -->|"allowedTlds (Set&lt;String&gt;)"| T
        D -->|"currentSponsorRegistrarId"| R
        D -->|"tld"| T
    end

    subgraph Dash ["Registry Dashboard Tables (CRUD)"]
        P[RegistrarPricing<br/>per registrar+tld+operation]
        C[CostBasis<br/>per tld+operation<br/>optionally per registrar]
    end

    RTL -->|"scopes access to"| T
    T -->|"allowedTlds derives"| R
    P -->|"registrarId"| R
    P -->|"tld"| T
    C -->|"tld"| T
    C -.->|"registrarId (optional)"| R

    style Auth fill:#e8f4fd,stroke:#1a73e8
    style Core fill:#fef7e0,stroke:#f9ab00
    style Dash fill:#e8f5e9,stroke:#34a853
```

---

## Access Control Flow

```mermaid
sequenceDiagram
    participant Browser as Angular Frontend
    participant API as Console API Action
    participant Util as RegistryDashAccessUtil
    participant DB as PostgreSQL

    Browser->>API: GET /console-api/registry-dash/overview
    API->>API: Check user.globalRole permissions<br/>(VIEW_DASHBOARD_OVERVIEW)
    API->>Util: getAccessibleTlds(user.email)
    Util->>DB: SELECT tld FROM RoRegistryTld rt<br/>JOIN RoRegistryUser ru<br/>ON rt.registryId = ru.registryId<br/>WHERE ru.userEmailAddress = ?
    DB-->>Util: ["example", "crypto", "nft"]
    Util->>DB: SELECT registrarId FROM Registrar<br/>WHERE allowedTlds && accessible_tlds
    DB-->>Util: Derived registrar IDs
    Util-->>API: ImmutableSet of registrar IDs + TLDs
    API->>DB: SELECT domain counts<br/>WHERE tld IN (...) AND currentSponsorRegistrarId IN (...)
    DB-->>API: Aggregated results
    API-->>Browser: JSON response (scoped to registry's TLDs)
```

---

## Three-Level Scoping Model

The scoping model replaces the old flat user-to-registrar mapping with a more intuitive registry-centric approach:

```
RoRegistry (named group, e.g., "registryXYZ")
├── RoRegistryTld → which TLDs belong to this registry
│   ├── "example"
│   ├── "crypto"
│   └── "nft"
└── RoRegistryUser → which users can see this registry's data
    ├── "alice@example.com"
    └── "bob@example.com"
```

**Access chain:** `User → RoRegistryUser → RoRegistry → RoRegistryTld → Tld → Registrar` (registrars derived via `allowedTlds`)

**Key benefit:** Adding a TLD to a registry instantly grants visibility to all registry users. No need to manage individual registrar mappings.

---

## Tables in Detail

### Existing Nomulus Tables (Read-Only)

| Table | Primary Key | Dashboard-Relevant Fields | Notes |
|-------|-------------|--------------------------|-------|
| `Tld` | `tld_name` (String) | `tld_name`, `tld_unicode`, `invoicing_enabled` | No registry operator field exists. Nomulus assumes single operator. |
| `Registrar` | `registrar_id` (String) | `registrar_id`, `registrar_name`, `type`, `state`, `allowed_tlds`, `iana_identifier` | `allowed_tlds` is the key relationship: maps registrar → TLDs |
| `Domain` | `repo_id` (String) | `domain_name`, `tld`, `current_sponsor_registrar_id`, `creation_time`, `deletion_time` | Links domain to both its TLD and sponsoring registrar |
| `User` | `email_address` (String) | `email_address`, `user_roles` (contains `globalRole` + `registrarRoles`) | `globalRole = REGISTRY_OPERATOR` for dashboard users |

### New Dashboard Tables (CRUD via API)

| Table | Primary Key | Unique Constraint | Purpose |
|-------|-------------|-------------------|---------|
| `RoRegistry` | `id` (bigserial) | `(name)` | Named registry group with JSONB settings |
| `RoRegistryTld` | `id` (bigserial) | `(registry_id, tld)` | Maps a registry to its TLDs |
| `RoRegistryUser` | `id` (bigserial) | `(registry_id, user_email_address)` | Maps a registry to its users |
| `RegistryDashboardRegistrarPricing` | `id` (bigserial) | `(registrar_id, tld, operation, effective_date)` | Per-registrar pricing overrides |
| `RegistryDashboardCostBasis` | `id` (bigserial) | `COALESCE(registrar_id, '') + tld + operation + effective_date` | Registry cost basis, optionally per-registrar |

### Schema Migrations

| Migration | Description |
|-----------|-------------|
| V221 | Initial pricing + cost basis + registrar mapping tables |
| V222 | TLD mapping table |
| V223 | Three-level refactor (RoRegistry, RoRegistryTld, RoRegistryUser) |
| V224 | Add `registrar_id` to CostBasis (nullable), add `settings` JSONB to RoRegistry |

---

## Key Relationships Explained

### 1. User → Registry → TLDs (Three-Level Scoping)

Access is controlled through the registry scoping chain:

```
User.emailAddress  →  RoRegistryUser.userEmailAddress
                       RoRegistryUser.registryId  →  RoRegistry.id
                                                      RoRegistry.id  →  RoRegistryTld.registryId
                                                                        RoRegistryTld.tld  →  Tld.tldStr
```

One user can belong to **multiple registries**. One registry can contain **many TLDs** and **many users**.

### 2. Registry → Registrars (Derived)

Registrars are **derived** from the TLD assignments, not directly mapped:

```
RoRegistryTld.tld = "example"
    → Registrar WHERE "example" IN (allowedTlds)
    → ["registrar1", "registrar2", "registrar3"]
```

### 3. Registrar → TLDs (via allowedTlds)

`Registrar.allowedTlds` is a `Set<String>` stored as a column (not a join table). It contains TLD strings that match `Tld.tldStr`.

### 4. Pricing: Registrar + TLD + Operation

Pricing rules are scoped to a specific registrar, TLD, and operation type:

```
RegistrarPricing.registrarId  →  Registrar.registrarId
RegistrarPricing.tld          →  Tld.tldStr
RegistrarPricing.operation    =  "CREATE" | "RENEW" | "TRANSFER" | "RESTORE"
```

### 5. Cost Basis: TLD + Operation + Optional Registrar

Cost basis can be scoped globally (NULL registrar_id = applies to all registrars) or per-registrar:

```
CostBasis.tld           →  Tld.tldStr
CostBasis.operation     =  "CREATE" | "RENEW" | "TRANSFER" | "RESTORE"
CostBasis.registrarId   →  Registrar.registrarId (nullable)
```

The unique constraint uses `COALESCE(registrar_id, '')` to ensure only one default rate per TLD/operation and one override per registrar/TLD/operation.

---

## Derived Relationships

```mermaid
flowchart LR
    subgraph input ["What Admin Configures"]
        REG["RoRegistry<br/>name + settings"]
        TLD_MAP["RoRegistryTld<br/>registry → TLDs"]
        USR_MAP["RoRegistryUser<br/>registry → users"]
        COST["CostBasis<br/>TLD + operation costs<br/>(optionally per-registrar)"]
    end

    subgraph derived ["What the System Can Derive"]
        REGS["Registrars<br/>(from Registrar.allowedTlds ∩ registry TLDs)"]
        DOMS["Domain counts<br/>(from Domain table)"]
        PRICE["Pricing rules<br/>(from RegistrarPricing)"]
        MARGIN["Pricing spread<br/>(price - cost)"]
    end

    TLD_MAP --> REGS
    REGS -->|"currentSponsorRegistrarId"| DOMS
    REGS --> PRICE
    PRICE --> MARGIN
    COST --> MARGIN

    style input fill:#e8f4fd,stroke:#1a73e8
    style derived fill:#f3e8fd,stroke:#7b1fa2
```

| From | Derive | Query Pattern |
|------|--------|---------------|
| User email | Registry IDs | `SELECT registryId FROM RoRegistryUser WHERE userEmailAddress = ?` |
| Registry IDs | Accessible TLDs | `SELECT tld FROM RoRegistryTld WHERE registryId IN (...)` |
| TLDs | Registrar IDs | `SELECT registrarId FROM Registrar WHERE allowedTlds && accessible_tlds` |
| Registrar IDs | Domain counts | `SELECT COUNT(*), currentSponsorRegistrarId FROM Domain WHERE currentSponsorRegistrarId IN (...) GROUP BY ...` |
| Registrar + TLD | Pricing | `SELECT * FROM RegistrarPricing WHERE registrarId = ? AND tld = ? AND isActive = true` |
| TLD (+ optional registrar) | Cost basis | `SELECT * FROM CostBasis WHERE tld = ? AND (registrarId = ? OR registrarId IS NULL)` |
| Pricing - Cost | **Margin** | `pricing.priceAmount - costBasis.costAmount` (computed in UI) |

---

## Frontend Tabs

| Tab | Route | Component | Key Features |
|-----|-------|-----------|-------------|
| Overview | `/registry-dash/overview` | `OverviewComponent` | Summary cards, domain count by registrar |
| Portfolio | `/registry-dash/portfolio` | `PortfolioComponent` | Registrar list with details |
| Pricing | `/registry-dash/pricing` | `PricingComponent` | CRUD, sorting, filtering, default price comparison, diff column |
| Financials | `/registry-dash/financials` | `FinancialsComponent` | 3 sub-tabs: Cost Basis Rates (sorting + filtering), Summary by TLD, Pricing Spread (placeholder). Metric cards, stacked bar chart. |
| Admin | `/registry-dash/admin` | `AdminComponent` | Registry/TLD/user management, cost basis CRUD with registrar-scoped entries |

---

## Role & Permission Matrix

### No Dashboard Access (NONE, SUPPORT_AGENT, SUPPORT_LEAD)

These roles have **no visibility** into the Registry Dashboard. The nav item is hidden entirely.

- `NONE` users only see registrar-scoped views (Domains, Settings, Billing, etc.)
- `SUPPORT_AGENT` and `SUPPORT_LEAD` have broad operational permissions but the dashboard is not part of their workflow
- The `REGISTRY_DASH` element is added to `DISABLED_ELEMENTS_PER_ROLE` for all three roles

---

### Registry Operator Access (REGISTRY_OPERATOR)

Dashboard users who view and manage data **scoped to their registry's TLDs**.

```mermaid
flowchart TD
    RO["REGISTRY_OPERATOR<br/>(dashboard user)"]

    subgraph visible ["Dashboard Tabs Visible"]
        OV["Overview<br/>VIEW_DASHBOARD_OVERVIEW"]
        PF["Portfolio<br/>VIEW_REGISTRAR_PORTFOLIO"]
        PR["Pricing<br/>VIEW_PRICING + MANAGE_PRICING"]
        FIN["Financials<br/>MANAGE_COST_BASIS (read-only view)"]
    end

    subgraph hidden ["Hidden From This Role"]
        ADM["Admin Tab<br/>(FTE only)"]
        OTE["OTE Setup"]
        SUSP["Suspend Domain"]
        USERS["User Management"]
        REGS["Registrar Management"]
    end

    RO -->|"full access"| OV & PF & PR & FIN
    RO -.->|"hidden"| ADM & OTE & SUSP & USERS & REGS

    style RO fill:#1a73e8,color:#fff
    style visible fill:#e8f4fd,stroke:#1a73e8
    style hidden fill:#fce8e6,stroke:#ea4335
    style ADM fill:#ea4335,color:#fff
    style OTE fill:#ea4335,color:#fff
    style SUSP fill:#ea4335,color:#fff
    style USERS fill:#ea4335,color:#fff
    style REGS fill:#ea4335,color:#fff
```

**Data scoping:** All queries are filtered through `RegistryDashAccessUtil` which resolves user → registry → TLDs → registrars. An RO user only sees data within their registry's TLD assignments.

---

### Full Admin Access (FTE)

Full-time employees have **all dashboard permissions plus admin capabilities**.

```mermaid
flowchart TD
    FTE["FTE<br/>(full admin)"]

    subgraph dash ["All Dashboard Tabs"]
        OV["Overview<br/>VIEW_DASHBOARD_OVERVIEW"]
        PF["Portfolio<br/>VIEW_REGISTRAR_PORTFOLIO"]
        PR["Pricing<br/>VIEW_PRICING + MANAGE_PRICING"]
        FIN["Financials<br/>MANAGE_COST_BASIS"]
    end

    subgraph admin ["Admin Tab Capabilities"]
        ADM["Admin Tab"]
        MREG["Create/Delete Registries"]
        MTLD["Assign TLDs to Registries"]
        MUSR["Manage Registry Users"]
        MCB["Cost Basis CRUD<br/>(with registrar-scoped entries)"]
    end

    subgraph also ["Also Has Access To"]
        OTE["OTE Setup"]
        SUSP["Suspend Domain"]
        USERS["User Management"]
        REGS["Registrar Management"]
        EPP["EPP Commands"]
    end

    FTE -->|"full access"| OV & PF & PR & FIN
    FTE -->|"admin only"| ADM
    ADM --> MREG & MTLD & MUSR & MCB
    FTE -->|"plus all console features"| OTE & SUSP & USERS & REGS & EPP

    style FTE fill:#34a853,color:#fff
    style dash fill:#e8f4fd,stroke:#1a73e8
    style admin fill:#e8f5e9,stroke:#34a853
    style also fill:#fef7e0,stroke:#f9ab00
```

**Key difference from REGISTRY_OPERATOR:** FTE users can see the Admin tab, which allows them to:
- **Create/delete** named registries (groups)
- **Assign TLDs** to registries (dropdown filtered by availability)
- **Manage users** within registries
- **CRUD cost basis** entries with optional registrar-scoped overrides

FTE users are **not scoped** by registry membership — they see all data across all registrars.

---

### Summary Table

| Permission | FTE | REGISTRY_OPERATOR | SUPPORT_LEAD | SUPPORT_AGENT | NONE |
|------------|:---:|:-----------------:|:------------:|:-------------:|:----:|
| VIEW_DASHBOARD_OVERVIEW | Y | Y | - | - | - |
| VIEW_REGISTRAR_PORTFOLIO | Y | Y | - | - | - |
| VIEW_PRICING | Y | Y | - | - | - |
| MANAGE_PRICING | Y | Y | - | - | - |
| MANAGE_COST_BASIS | Y | Y | - | - | - |
| Admin tab (manage registries/TLDs/users/cost basis) | Y | - | - | - | - |
| Dashboard nav visible | Y | Y | - | - | - |

---

## API Endpoints

| Method | Path | Action Class | Description |
|--------|------|-------------|-------------|
| GET | `/console-api/registry-dash/overview` | `RegistryDashOverviewAction` | Aggregate domain counts, registrar summary |
| GET | `/console-api/registry-dash/portfolio` | `RegistryDashPortfolioAction` | Registrar details, states, TLD assignments |
| GET, POST, PUT | `/console-api/registry-dash/pricing` | `RegistryDashPricingAction` | Pricing rules CRUD |
| GET, POST, PUT | `/console-api/registry-dash/cost-basis` | `RegistryDashCostBasisAction` | Cost basis CRUD (supports registrar-scoped entries) |
| GET, POST | `/console-api/registry-dash/admin` | `RegistryDashAdminAction` | Registry/TLD/user management |

---

## Important Design Notes

1. **Three-level scoping replaces flat mappings.** The old `RoRegistrarMapping` table was replaced in V223 with `RoRegistry` → `RoRegistryTld` + `RoRegistryUser`. This is more intuitive: assign TLDs to a registry, assign users to a registry, and registrars are derived automatically.

2. **No multi-operator support in Nomulus core.** The `Tld` entity has no `registryOperator` field. Nomulus assumes a single entity operates all TLDs. The `RoRegistry` scoping model bridges this gap.

3. **`allowedTlds` is not a join table.** It's a `Set<String>` stored directly on the `Registrar` entity. Registrars are derived from TLD assignments, not mapped directly.

4. **Soft references, not foreign keys.** The dashboard tables use `registrarId` and `tld` as string columns, not as database-level foreign keys to `Registrar` and `Tld` tables. This follows the Nomulus convention of loose coupling.

5. **Cost basis: global vs per-registrar.** A cost basis entry with `registrar_id = NULL` is the default rate for that TLD/operation. An entry with a specific `registrar_id` is an override for that registrar. The COALESCE-based unique index enforces at most one default and one override per registrar.

6. **RoRegistry.settings is JSONB.** The `settings` column stores feature flags and configuration as a JSON object (e.g., enabling/disabling pricing spread visibility). Default is `'{}'`.

7. **Upstream compatibility.** All dashboard-specific files are UD-only (prefixed or in UD-specific directories). Schema migrations V221-V224 are additive. Merge conflict risk with upstream `google/nomulus` is minimal.
