import { DataSourceSchema, DataSourceType } from './explore.models';

export const DATA_SOURCE_SCHEMAS: Record<DataSourceType, DataSourceSchema> = {
  DOMAIN_ACTIVITY: {
    label: 'Domain Activity',
    description: 'Registration activity: creates, renews, transfers, deletes, restores',
    metrics: [{ field: 'count', label: 'Count' }],
    dimensions: [
      { field: 'tld', label: 'TLD' },
      { field: 'activity_type', label: 'Activity Type' },
      { field: 'period', label: 'Time Period' },
      { field: 'registrar', label: 'Registrar' },
    ],
    filters: ['tlds', 'activityTypes', 'dateRange'],
    supportsGranularity: true,
  },
  REVENUE: {
    label: 'Revenue',
    description: 'Billing revenue by TLD, operation, and time period',
    metrics: [
      { field: 'amount', label: 'Gross Amount' },
      { field: 'netAmountToRegistry', label: 'Net to Registry' },
    ],
    dimensions: [
      { field: 'tld', label: 'TLD' },
      { field: 'operation', label: 'Operation' },
      { field: 'period', label: 'Time Period' },
    ],
    filters: ['tlds', 'operations', 'dateRange'],
    supportsGranularity: true,
  },
  DOMAIN_COUNTS: {
    label: 'Domain Counts',
    description: 'Current active domain counts by TLD and registrar',
    metrics: [{ field: 'count', label: 'Count' }],
    dimensions: [
      { field: 'tld', label: 'TLD' },
      { field: 'registrar', label: 'Registrar' },
    ],
    filters: ['tlds', 'registrarIds'],
    supportsGranularity: false,
  },
  RENEWAL_RATES: {
    label: 'Renewal Rates',
    description: 'Renewal and deletion counts by TLD',
    metrics: [
      { field: 'renewals', label: 'Renewals' },
      { field: 'deletions', label: 'Deletions' },
      { field: 'renewalRate', label: 'Renewal Rate' },
    ],
    dimensions: [{ field: 'tld', label: 'TLD' }],
    filters: ['tlds', 'dateRange'],
    supportsGranularity: false,
  },
  EXPIRATION_CURVE: {
    label: 'Expiration Curve',
    description: 'Domain expiration distribution over time',
    metrics: [{ field: 'count', label: 'Count' }],
    dimensions: [
      { field: 'tld', label: 'TLD' },
      { field: 'month', label: 'Month' },
    ],
    filters: ['tlds', 'dateRange'],
    supportsGranularity: false,
  },
  PRICING_RULES: {
    label: 'Pricing Rules',
    description: 'Active pricing rules by registrar, TLD, and operation',
    metrics: [{ field: 'priceAmount', label: 'Price Amount' }],
    dimensions: [
      { field: 'registrar', label: 'Registrar' },
      { field: 'tld', label: 'TLD' },
      { field: 'operation', label: 'Operation' },
    ],
    filters: ['tlds', 'registrarIds', 'operations'],
    supportsGranularity: false,
  },
};
