# Registry Dashboard - Data Model & Relationships

## Overview

The Registry Dashboard extends the Nomulus domain registry with operator-facing analytics and configuration. It introduces 3 new tables that overlay the existing Nomulus data model to provide access control, per-registrar pricing, and cost tracking.

---

## Entity Relationship Diagram

```mermaid
erDiagram
    User ||--o{ RoRegistrarMapping : "scoped by"
    RoRegistrarMapping }o--|| Registrar : "grants access to"
    Registrar }o--o{ Tld : "allowedTlds"
    Domain }o--|| Registrar : "currentSponsorRegistrarId"
    Domain }o--|| Tld : "tld"
    RegistrarPricing }o--|| Registrar : "registrarId"
    RegistrarPricing }o--|| Tld : "tld"
    CostBasis }o--|| Tld : "tld"

    User {
        string emailAddress PK
        UserRoles userRoles
        string registryLockEmailAddress
    }

    RoRegistrarMapping {
        bigint id PK
        string userEmailAddress FK
        string registrarId FK
        timestamptz createdAt
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
    subgraph Auth ["Authentication & Access Control"]
        U[User<br/>emailAddress + globalRole]
        M[RoRegistrarMapping<br/>userEmail -> registrarId]
        U -->|"REGISTRY_OPERATOR role"| M
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
        C[CostBasis<br/>per tld+operation]
    end

    M -->|"scopes access to"| R
    P -->|"registrarId"| R
    P -->|"tld"| T
    C -->|"tld"| T

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
    API->>Util: getMappedRegistrarIds(user.email)
    Util->>DB: SELECT registrarId<br/>FROM RoRegistrarMapping<br/>WHERE userEmailAddress = ?
    DB-->>Util: ["NewRegistrar", "TheRegistrar"]
    Util-->>API: ImmutableSet of registrar IDs
    API->>DB: SELECT domain counts<br/>WHERE currentSponsorRegistrarId IN (...)
    DB-->>API: Aggregated results
    API-->>Browser: JSON response (scoped to mapped registrars)
```

---

## Tables in Detail

### Existing Nomulus Tables (Read-Only)

| Table | Primary Key | Dashboard-Relevant Fields | Notes |
|-------|-------------|--------------------------|-------|
| `Tld` | `tld_name` (String) | `tld_name`, `tld_unicode`, `invoicing_enabled` | No registry operator field exists. Nomulus assumes single operator. |
| `Registrar` | `registrar_id` (String) | `registrar_id`, `registrar_name`, `type`, `state`, `allowed_tlds`, `iana_identifier` | `allowed_tlds` is the key relationship: maps registrar -> TLDs |
| `Domain` | `repo_id` (String) | `domain_name`, `tld`, `current_sponsor_registrar_id`, `creation_time`, `deletion_time` | Links domain to both its TLD and sponsoring registrar |
| `User` | `email_address` (String) | `email_address`, `user_roles` (contains `globalRole` + `registrarRoles`) | `globalRole = REGISTRY_OPERATOR` for dashboard users |

### New Dashboard Tables (CRUD via API)

| Table | Primary Key | Unique Constraint | Purpose |
|-------|-------------|-------------------|---------|
| `RegistryDashboardRoRegistrarMapping` | `id` (bigserial) | `(user_email_address, registrar_id)` | Maps RO users to registrars they can view |
| `RegistryDashboardRegistrarPricing` | `id` (bigserial) | `(registrar_id, tld, operation, effective_date)` | Per-registrar pricing overrides |
| `RegistryDashboardCostBasis` | `id` (bigserial) | `(tld, operation, effective_date)` | Registry cost basis per TLD/operation |

---

## Key Relationships Explained

### 1. User -> Registrars (via RoRegistrarMapping)

There is **no direct FK** between `User` and `Registrar`. Access is controlled through `RegistryDashboardRoRegistrarMapping`:

```
User.emailAddress  --->  RoRegistrarMapping.userEmailAddress
                         RoRegistrarMapping.registrarId  --->  Registrar.registrarId
```

One user can be mapped to **many registrars**. One registrar can be viewed by **many users**.

### 2. Registrar -> TLDs (via allowedTlds)

`Registrar.allowedTlds` is a `Set<String>` stored as a column (not a join table). It contains TLD strings that match `Tld.tldStr`.

```
Registrar.allowedTlds = {"example", "tld", "xn--q9jyb4c"}
                                 |
                                 v
                         Tld.tld_name = "example"
                         Tld.tld_name = "tld"
                         Tld.tld_name = "xn--q9jyb4c"
```

### 3. Domain -> Registrar + TLD

Every domain links to exactly one registrar and one TLD:

```
Domain.currentSponsorRegistrarId  --->  Registrar.registrarId
Domain.tld                        --->  Tld.tldStr
```

### 4. Pricing: Registrar + TLD + Operation

Pricing rules are scoped to a specific registrar, TLD, and operation type:

```
RegistrarPricing.registrarId  --->  Registrar.registrarId
RegistrarPricing.tld          --->  Tld.tldStr
RegistrarPricing.operation    =     "CREATE" | "RENEW" | "TRANSFER" | "RESTORE"
```

### 5. Cost Basis: TLD + Operation

Cost basis is scoped to a TLD and operation (not per-registrar, since cost is the same regardless of registrar):

```
CostBasis.tld        --->  Tld.tldStr
CostBasis.operation  =     "CREATE" | "RENEW" | "TRANSFER" | "RESTORE"
```

---

## Derived Relationships

The dashboard can derive several useful views from the existing data:

```mermaid
flowchart LR
    subgraph input ["What Admin Configures"]
        MAP["RoRegistrarMapping<br/>user -> registrar"]
    end

    subgraph derived ["What the System Can Derive"]
        REG["Registrars<br/>(from mapping)"]
        TLDS["TLDs<br/>(from Registrar.allowedTlds)"]
        DOMS["Domain counts<br/>(from Domain table)"]
        PRICE["Pricing rules<br/>(from RegistrarPricing)"]
        COST["Cost basis<br/>(from CostBasis)"]
    end

    MAP --> REG
    REG -->|"allowedTlds"| TLDS
    REG -->|"currentSponsorRegistrarId"| DOMS
    REG --> PRICE
    TLDS --> COST

    style input fill:#e8f4fd,stroke:#1a73e8
    style derived fill:#f3e8fd,stroke:#7b1fa2
```

| From | Derive | Query Pattern |
|------|--------|---------------|
| User email | Mapped registrar IDs | `SELECT registrarId FROM RoRegistrarMapping WHERE userEmailAddress = ?` |
| Registrar IDs | Registrar details | `SELECT * FROM Registrar WHERE registrarId IN (...)` |
| Registrar IDs | Relevant TLDs | `SELECT DISTINCT allowedTlds FROM Registrar WHERE registrarId IN (...)` |
| Registrar IDs | Domain counts | `SELECT COUNT(*), currentSponsorRegistrarId FROM Domain WHERE currentSponsorRegistrarId IN (...) GROUP BY currentSponsorRegistrarId` |
| Registrar + TLD | Pricing | `SELECT * FROM RegistrarPricing WHERE registrarId = ? AND tld = ? AND isActive = true` |
| TLD | Cost basis | `SELECT * FROM CostBasis WHERE tld = ?` |
| Pricing - Cost | **Margin** | `pricing.priceAmount - costBasis.costAmount` (computed in UI) |

---

## Role & Permission Matrix

### No Dashboard Access (NONE, SUPPORT_AGENT, SUPPORT_LEAD)

These roles have **no visibility** into the Registry Dashboard. The nav item is hidden entirely.

```mermaid
flowchart LR
    subgraph roles ["Roles Without Dashboard Access"]
        NONE["NONE<br/>(registrar partner)"]
        SA["SUPPORT_AGENT"]
        SL["SUPPORT_LEAD"]
    end

    BLOCKED["Registry Dashboard<br/>HIDDEN"]

    NONE -.->|"no access"| BLOCKED
    SA -.->|"no access"| BLOCKED
    SL -.->|"no access"| BLOCKED

    style NONE fill:#ea4335,color:#fff
    style SA fill:#f9ab00,color:#000
    style SL fill:#f9ab00,color:#000
    style BLOCKED fill:#555,color:#fff,stroke-dasharray: 5 5
```

- `NONE` users only see registrar-scoped views (Domains, Settings, Billing, etc.)
- `SUPPORT_AGENT` and `SUPPORT_LEAD` have broad operational permissions but the dashboard is not part of their workflow
- The `REGISTRY_DASH` element is added to `DISABLED_ELEMENTS_PER_ROLE` for all three roles

---

### Registry Operator Access (REGISTRY_OPERATOR)

Dashboard users who view and manage data **scoped to their mapped registrars**.

```mermaid
flowchart TD
    RO["REGISTRY_OPERATOR<br/>(dashboard user)"]

    subgraph visible ["Dashboard Tabs Visible"]
        OV["Overview<br/>VIEW_DASHBOARD_OVERVIEW"]
        PF["Portfolio<br/>VIEW_REGISTRAR_PORTFOLIO"]
        PR["Pricing<br/>VIEW_PRICING + MANAGE_PRICING"]
        CB["Cost Basis<br/>MANAGE_COST_BASIS"]
    end

    subgraph hidden ["Hidden From This Role"]
        ADM["Admin Tab<br/>(FTE only)"]
        OTE["OTE Setup"]
        SUSP["Suspend Domain"]
        USERS["User Management"]
        REGS["Registrar Management"]
    end

    RO -->|"full access"| OV & PF & PR & CB
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

**Data scoping:** All queries are filtered through `RegistryDashAccessUtil.getMappedRegistrarIds(user.email)`. An RO user only sees registrars they've been mapped to via the Admin panel.

| Permission | Access | Used By |
|------------|--------|---------|
| VIEW_DASHBOARD_OVERVIEW | Read | Overview tab - aggregate domain counts, registrar summary |
| VIEW_REGISTRAR_PORTFOLIO | Read | Portfolio tab - registrar details, states, TLD assignments |
| VIEW_PRICING | Read | Pricing tab - view per-registrar pricing rules |
| MANAGE_PRICING | Write | Pricing tab - create/edit pricing rules |
| MANAGE_COST_BASIS | Write | Cost Basis tab - create/edit cost basis entries |

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
        CB["Cost Basis<br/>MANAGE_COST_BASIS"]
    end

    subgraph admin ["Admin-Only Capabilities"]
        ADM["Admin Tab"]
        MAP["Manage User-Registrar Mappings<br/>(CRUD on RoRegistrarMapping)"]
        SYS["View System Reference<br/>(all TLDs + all registrars)"]
    end

    subgraph also ["Also Has Access To"]
        OTE["OTE Setup"]
        SUSP["Suspend Domain"]
        USERS["User Management"]
        REGS["Registrar Management"]
        EPP["EPP Commands"]
    end

    FTE -->|"full access"| OV & PF & PR & CB
    FTE -->|"admin only"| ADM
    ADM --> MAP & SYS
    FTE -->|"plus all console features"| OTE & SUSP & USERS & REGS & EPP

    style FTE fill:#34a853,color:#fff
    style dash fill:#e8f4fd,stroke:#1a73e8
    style admin fill:#e8f5e9,stroke:#34a853
    style also fill:#fef7e0,stroke:#f9ab00
```

**Key difference from REGISTRY_OPERATOR:** FTE users can see the Admin tab, which allows them to:
- **Create** user-to-registrar mappings (grant RO users access)
- **Delete** existing mappings (revoke access)
- **View** all TLDs and registrars in the system as reference data

FTE users are **not scoped** by `RoRegistrarMapping` — they see all data across all registrars.

---

### Summary Table

| Permission | FTE | REGISTRY_OPERATOR | SUPPORT_LEAD | SUPPORT_AGENT | NONE |
|------------|:---:|:-----------------:|:------------:|:-------------:|:----:|
| VIEW_DASHBOARD_OVERVIEW | Y | Y | - | - | - |
| VIEW_REGISTRAR_PORTFOLIO | Y | Y | - | - | - |
| VIEW_PRICING | Y | Y | - | - | - |
| MANAGE_PRICING | Y | Y | - | - | - |
| MANAGE_COST_BASIS | Y | Y | - | - | - |
| Admin tab (manage mappings) | Y | - | - | - | - |
| Dashboard nav visible | Y | Y | - | - | - |

---

## Important Design Notes

1. **No multi-operator support in Nomulus core.** The `Tld` entity has no `registryOperator` field. Nomulus assumes a single entity operates all TLDs. The `RoRegistrarMapping` table bridges this gap by scoping dashboard access per user.

2. **`allowedTlds` is not a join table.** It's a `Set<String>` stored directly on the `Registrar` entity. This means there's no separate `Registrar_Tld` mapping table to query.

3. **Soft references, not foreign keys.** The dashboard tables use `registrarId` and `tld` as string columns, not as database-level foreign keys to `Registrar` and `Tld` tables. This follows the Nomulus convention of loose coupling.

4. **Pricing vs. Cost Basis scope.** Pricing is per-registrar (each registrar can have different prices). Cost basis is per-TLD (the registry's cost is the same regardless of which registrar originates the domain).

5. **Future consideration: TLD-based scoping.** Instead of mapping users to registrars directly, mapping users to TLDs would be more intuitive (`Registry -> TLDs -> Registrars`). From a TLD mapping, registrars can be derived via `Registrar.allowedTlds`. This would reduce admin overhead (5 TLD mappings vs 50+ registrar mappings).
