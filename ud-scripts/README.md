# ud-scripts

UD-specific utility scripts for operating and testing the Nomulus registry.

## Registry Dashboard Demo Data

Scripts for loading demo data into the registry dashboard tables in a deployed
environment (e.g., alpha-gke). These populate the `RoRegistry`, `RoRegistryTld`,
`RoRegistryUser`, `RegistryDashboardCostBasis`, and
`RegistryDashboardRegistrarPricing` tables.

### Prerequisites

- IAP credentials for the target environment. Generate with:
  ```bash
  cd console-webapp && npm run auth:alpha
  ```
  Credentials are stored at `~/.config/nomulus-local-dev/credentials.json`.

- The target environment must have the registry dashboard schema migrations
  applied (V221-V224) and demo TLDs/registrars already created.

### Scripts

| Script | Purpose |
|--------|---------|
| `alpha-load-demo-data.sh` | Main loader. Authenticates via IAP, fetches XSRF token, and creates demo data through the console API. |
| `alpha-load-demo-data.sql` | SQL fallback. Same data, but for direct execution via `psql` against Cloud SQL. |
| `alpha-reset-demo-data.sql` | Deletes all dashboard demo data (registries, pricing, cost basis). Does not touch TLDs or registrars. |

### Usage

```bash
# Load demo data via API (idempotent — skips existing entries)
./ud-scripts/alpha-load-demo-data.sh

# Wipe dashboard data and reload from scratch
./ud-scripts/alpha-load-demo-data.sh --reset

# Preview what would be done
./ud-scripts/alpha-load-demo-data.sh --dry-run
```

### What gets created

- **3 registries**: RetroTech Registry (modem, floppy), Vintage Digital (pixel, dialup), NeonWave Networks (cassette)
- **20 cost basis entries**: 5 TLDs x 4 operations (CREATE, RENEW, RESTORE, TRANSFER) with null registrar (global rates)
- **35 per-registrar pricing rules**: Custom prices for all 7 demo registrars across their allowed TLDs
- **User mapping**: `torrey@unstoppabledomains.com` mapped to all 3 registries

To add more users, edit the "Step 4" section of `alpha-load-demo-data.sh`.

### How the API auth works

1. Refresh token from stored credentials -> ID token (targeting IAP audience)
2. GET `/console-api/userdata` -> `X-CSRF-Token` cookie
3. POST with both `Authorization: Bearer <id_token>` header and `X-CSRF-Token` cookie
