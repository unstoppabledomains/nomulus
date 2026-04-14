# Dashboard Chart Improvements — Design Spec

## Summary

Two improvements to the Registry Financials "Revenue & Billing" tab:

1. **Fix the Revenue by TLD chart legend** — currently overflows when many TLDs are present
2. **Add finer time granularity** — expand from 4 month-only ranges to 9 ranges spanning 6 hours to 24 months, with automatic granularity selection

## 1. Legend Fix

### Problem

The ECharts legend config is `legend: { data: tlds }` with no overflow handling. When the registry has many TLDs, legend items pile up below the chart, overlapping and compressing the chart area.

### Solution

Change the legend to ECharts' built-in scrollable type:

```typescript
legend: {
  type: 'scroll',
  bottom: 0,
  data: tlds,
}
```

This adds left/right scroll arrows when legend items overflow the available width. No other changes needed.

### File

- `console-webapp/src/app/registry-dash/financials/revenue-billing/revenue-billing.component.ts` — `revenueLineOptions` computed signal, legend object (line ~113)

## 2. Time Granularity

### Problem

The dashboard only offers monthly granularity (3m, 6m, 12m, 24m). Registry operators need finer views — down to 15-minute intervals — to monitor sales during marketing pushes and adjust in real time.

### Range-to-Granularity Mapping

| Button | Lookback | SQL Granularity | X-axis Format | Max Points/TLD |
|--------|----------|-----------------|---------------|----------------|
| 6h | 6 hours | 15-min buckets | `HH:mm` | 24 |
| 12h | 12 hours | hour | `HH:00` | 12 |
| 1d | 1 day | hour | `Mar 27 HH:00` | 24 |
| 7d | 7 days | day | `Mar 21` | 7 |
| 30d | 30 days | day | `Mar 01` | 30 |
| 3m | ~90 days | month | `2026-01` | ~3 |
| 6m | ~180 days | month | `2025-10` | ~6 |
| 12m | ~365 days | month | `2025-04` | ~12 |
| 24m | ~730 days | month | `2024-04` | ~24 |

Default selection: **12m** (preserves current behavior).

### Frontend Changes

#### revenue-billing.component.html

Replace the 4 month-only toggles with 9 range buttons:

```html
<mat-button-toggle-group [value]="selectedRange()" (change)="onRangeChange($event.value)">
  <mat-button-toggle value="6h">6h</mat-button-toggle>
  <mat-button-toggle value="12h">12h</mat-button-toggle>
  <mat-button-toggle value="1d">1d</mat-button-toggle>
  <mat-button-toggle value="7d">7d</mat-button-toggle>
  <mat-button-toggle value="30d">30d</mat-button-toggle>
  <mat-button-toggle value="3m">3m</mat-button-toggle>
  <mat-button-toggle value="6m">6m</mat-button-toggle>
  <mat-button-toggle value="12m">12m</mat-button-toggle>
  <mat-button-toggle value="24m">24m</mat-button-toggle>
</mat-button-toggle-group>
```

#### revenue-billing.component.ts

- Replace `selectedMonths` signal with `selectedRange` signal (default: `'12m'`)
- Add a `RANGE_CONFIG` lookup mapping each range key to `{ lookbackHours, granularity, xAxisFormat }`:
  - `'6h'` → `{ lookbackHours: 6, granularity: '15min', xAxisFormat: 'HH:mm' }`
  - `'12h'` → `{ lookbackHours: 12, granularity: 'hour', xAxisFormat: 'HH:00' }`
  - `'1d'` → `{ lookbackHours: 24, granularity: 'hour', xAxisFormat: 'MMM DD HH:00' }`
  - `'7d'` → `{ lookbackHours: 168, granularity: 'day', xAxisFormat: 'MMM DD' }`
  - `'30d'` → `{ lookbackHours: 720, granularity: 'day', xAxisFormat: 'MMM DD' }`
  - `'3m'` → `{ lookbackHours: 2160, granularity: 'month', xAxisFormat: 'YYYY-MM' }`
  - `'6m'` → `{ lookbackHours: 4380, granularity: 'month', xAxisFormat: 'YYYY-MM' }`
  - `'12m'` → `{ lookbackHours: 8760, granularity: 'month', xAxisFormat: 'YYYY-MM' }`
  - `'24m'` → `{ lookbackHours: 17520, granularity: 'month', xAxisFormat: 'YYYY-MM' }`
- Update the effect to call `getRevenueBilling(lookbackHours, granularity)` instead of `getRevenueBilling(months)`
- Update x-axis formatting in `revenueLineOptions` to use the format from `RANGE_CONFIG`
- Rename `monthlyRevenue` references to use the `period` field from the updated API response
- Update `avgMonthlyRevenue` metric to be period-aware: label changes to "Avg Monthly" / "Avg Daily" / "Avg Hourly" depending on granularity

#### registry-dash.service.ts

- Update `getRevenueBilling()` to accept `lookbackHours: number` and `granularity: string` params
- Pass as query params: `?lookbackHours=168&granularity=day`
- Update `RevenueDataPoint` interface: rename `month` field to `period`

### Backend Changes

#### RegistryDashRevenueBillingAction.java

**New parameters:**
- `lookbackHours` (integer, optional) — number of hours to look back. Values: 6, 12, 24, 168, 720, 2160, 4380, 8760, 17520.
- `granularity` (string, optional) — one of: `15min`, `hour`, `day`, `month`. Defaults to `month`.

**Backward compatibility:** If the old `months` param is provided (and `lookbackHours` is not), convert: `lookbackHours = months * 730`, `granularity = 'month'`. This keeps any existing API consumers working.

**SQL changes:**

For `hour`, `day`, `month` granularity, use `date_trunc(:granularity, b.event_time)`.

For `15min` granularity, use:
```sql
date_trunc('hour', b.event_time) +
  floor(extract(minute from b.event_time) / 15) * interval '15 minutes'
```

This requires two SQL template variants (one for standard `date_trunc` granularities, one for 15-min). The simplest approach: an `if` in Java that selects the appropriate SQL string based on the granularity parameter.

**Template SQL (standard granularity):**
```sql
SELECT date_trunc(:granularity, b.event_time) AS period,
       d.tld, b.reason,
       SUM(b.cost_amount) AS total_amount,
       b.cost_currency
FROM "BillingEvent" b
JOIN "Domain" d ON d.repo_id = b.domain_repo_id
WHERE b.event_time >= :startDate
  AND b.reason IN ('CREATE', 'RENEW', 'TRANSFER', 'RESTORE')
GROUP BY date_trunc(:granularity, b.event_time), d.tld, b.reason, b.cost_currency
ORDER BY period, d.tld
```

**Template SQL (15-min granularity):**
```sql
SELECT date_trunc('hour', b.event_time) +
         floor(extract(minute from b.event_time) / 15) * interval '15 minutes' AS period,
       d.tld, b.reason,
       SUM(b.cost_amount) AS total_amount,
       b.cost_currency
FROM "BillingEvent" b
JOIN "Domain" d ON d.repo_id = b.domain_repo_id
WHERE b.event_time >= :startDate
  AND b.reason IN ('CREATE', 'RENEW', 'TRANSFER', 'RESTORE')
GROUP BY period, d.tld, b.reason, b.cost_currency
ORDER BY period, d.tld
```

Both queries have `_ALL` and `_SCOPED` variants (same as today, adding `AND d.tld IN :tlds` for scoped).

**Response format:** Rename `month` → `period` in the JSON response. The `monthlyRevenue` array key also renames to `periodRevenue`. The `period` value format depends on granularity:
- 15min/hour: ISO-8601 datetime string (e.g., `2026-03-27T14:15:00Z`)
- day: date string (e.g., `2026-03-27`)
- month: year-month string (e.g., `2026-03`)

**Start date calculation:** `Instant.now().minus(lookbackHours, ChronoUnit.HOURS)`

### Security

- Validate `granularity` against an allowlist (`15min`, `hour`, `day`, `month`) — never interpolate raw user input into SQL
- Validate `lookbackHours` is a positive integer within a reasonable range (1–17520)
- Existing permission check (`VIEW_REVENUE_BILLING`) and TLD scoping remain unchanged

## Files Changed Summary

| File | Change |
|------|--------|
| `console-webapp/.../revenue-billing.component.ts` | Legend scroll config, range signal, granularity mapping, x-axis formatting |
| `console-webapp/.../revenue-billing.component.html` | 9 toggle buttons |
| `console-webapp/.../registry-dash.service.ts` | Updated API params and response type |
| `core/.../RegistryDashRevenueBillingAction.java` | New params, parameterized SQL, response field rename |

## Pricing Clarification (No Code Change)

The revenue charts show **actual charged amounts** from `BillingEvent.cost_amount`. When a registrar-specific pricing rule is active (via `RegistryDashboardRegistrarPricing`), the custom price is applied at EPP flow time by `RegistryDashboardPricingCustomLogic` before the `BillingEvent` is created. The dashboard sums what was actually billed — no change needed.
