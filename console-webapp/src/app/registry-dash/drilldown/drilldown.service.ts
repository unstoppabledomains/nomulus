// Copyright 2024 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import { Injectable } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { RegistryDashService } from '../registry-dash.service';
import { DrillDownDialogComponent } from './drilldown-dialog.component';
import { DrillDownDialogData } from './drilldown.models';

@Injectable({ providedIn: 'root' })
export class DrillDownService {
  constructor(
    private dialog: MatDialog,
    private dashService: RegistryDashService
  ) {}

  // --- Click-to-filter ---

  applyTldFilter(tld: string) {
    const cleaned = tld.replace(/^\./, '');
    this.dashService.selectedTlds.set([cleaned]);
  }

  applyRegistrarFilter(registrarId: string) {
    this.dashService.selectedRegistrarIds.set([registrarId]);
  }

  // --- Drill-down dialogs ---

  drillDownRenewalByTld(tld: string) {
    const cleaned = tld.replace(/^\./, '');
    const d = this.dashService.forecasting();
    if (!d) return;
    const entry = d.renewalRates.find(r => r.tld === cleaned);
    if (!entry) return;
    this.openDialog({
      title: `Renewal Rate: .${cleaned}`,
      columns: [
        { key: 'metric', label: 'Metric' },
        { key: 'value', label: 'Value', format: 'number' },
      ],
      rows: [
        { metric: 'Renewals', value: entry.renewals },
        { metric: 'Deletions', value: entry.deletions },
        { metric: 'Renewal Rate', value: entry.renewalRate },
      ],
    });
  }

  drillDownActivityByPeriod(period: string) {
    const d = this.dashService.domainActivity();
    if (!d) return;
    const points = d.activity.filter(pt => pt.period === period);
    if (points.length === 0) return;

    const byTldType = new Map<string, Record<string, number>>();
    for (const pt of points) {
      if (!byTldType.has(pt.tld)) byTldType.set(pt.tld, {});
      byTldType.get(pt.tld)![pt.type] = (byTldType.get(pt.tld)![pt.type] ?? 0) + pt.count;
    }

    const types = [...new Set(points.map(p => p.type))].sort();
    const rows = [...byTldType.entries()].map(([tld, counts]) => ({
      tld: `.${tld}`,
      ...Object.fromEntries(types.map(t => [t, counts[t] ?? 0])),
    }));

    this.openDialog({
      title: `Activity Breakdown: ${period}`,
      columns: [
        { key: 'tld', label: 'TLD' },
        ...types.map(t => ({ key: t, label: t, format: 'number' as const })),
      ],
      rows,
    });
  }

  drillDownActivityByTld(tld: string) {
    const cleaned = tld.replace(/^\./, '');
    const d = this.dashService.domainActivity();
    if (!d) return;
    const points = d.activity.filter(pt => pt.tld === cleaned);
    if (points.length === 0) return;

    const periodSet = new Set<string>();
    const typeSet = new Set<string>();
    for (const pt of points) {
      periodSet.add(pt.period);
      typeSet.add(pt.type);
    }

    const periods = [...periodSet].sort();
    const types = [...typeSet].sort();
    const rows = periods.map(period => {
      const row: Record<string, any> = { period };
      for (const type of types) {
        row[type] = points.find(pt => pt.period === period && pt.type === type)?.count ?? 0;
      }
      return row;
    });

    this.openDialog({
      title: `Activity: .${cleaned}`,
      subtitle: `${periods[0]} — ${periods[periods.length - 1]}`,
      columns: [
        { key: 'period', label: 'Period' },
        ...types.map(t => ({ key: t, label: t, format: 'number' as const })),
      ],
      rows,
    });
  }

  drillDownDomainCountsByTld(tld: string) {
    const cleaned = tld.replace(/^\./, '');
    const portfolio = this.dashService.portfolio();
    const registrars = portfolio.filter(p => p.allowedTlds.includes(cleaned));
    if (registrars.length === 0) {
      this.openDialog({
        title: `Domain Counts: .${cleaned}`,
        subtitle: 'Registrar-level breakdown not available (portfolio data not loaded)',
        columns: [{ key: 'info', label: 'Info' }],
        rows: [{ info: 'Load the Portfolio tab first to see registrar breakdown.' }],
      });
      return;
    }

    this.openDialog({
      title: `Domain Counts: .${cleaned}`,
      columns: [
        { key: 'registrar', label: 'Registrar' },
        { key: 'domainCount', label: 'Domains', format: 'number' },
        { key: 'state', label: 'State' },
      ],
      rows: registrars.map(r => ({
        registrar: r.registrarName || r.registrarId,
        domainCount: r.domainCount,
        state: r.state,
      })),
    });
  }

  drillDownRevenueByTld(tld: string) {
    const cleaned = tld.replace(/^\./, '');
    const d = this.dashService.revenueBilling();
    if (!d) return;
    const points = d.periodRevenue.filter(pt => pt.tld === cleaned);
    if (points.length === 0) return;

    const byOp = new Map<string, { amount: number; netAmount: number }>();
    for (const pt of points) {
      const prev = byOp.get(pt.operation) ?? { amount: 0, netAmount: 0 };
      byOp.set(pt.operation, {
        amount: prev.amount + pt.amount,
        netAmount: prev.netAmount + pt.netAmountToRegistry,
      });
    }

    this.openDialog({
      title: `Revenue: .${cleaned}`,
      subtitle: `${points[0].currency}`,
      columns: [
        { key: 'operation', label: 'Operation' },
        { key: 'amount', label: 'Gross Revenue', format: 'currency' },
        { key: 'netAmount', label: 'Net to Registry', format: 'currency' },
      ],
      rows: [...byOp.entries()].map(([op, vals]) => ({
        operation: op,
        amount: vals.amount,
        netAmount: vals.netAmount,
      })),
    });
  }

  drillDownRevenueByOperation(operation: string) {
    const d = this.dashService.revenueBilling();
    if (!d) return;
    const points = d.periodRevenue.filter(pt => pt.operation === operation);
    if (points.length === 0) return;

    const byTld = new Map<string, { amount: number; netAmount: number }>();
    for (const pt of points) {
      const prev = byTld.get(pt.tld) ?? { amount: 0, netAmount: 0 };
      byTld.set(pt.tld, {
        amount: prev.amount + pt.amount,
        netAmount: prev.netAmount + pt.netAmountToRegistry,
      });
    }

    this.openDialog({
      title: `Revenue by TLD: ${operation}`,
      columns: [
        { key: 'tld', label: 'TLD' },
        { key: 'amount', label: 'Gross Revenue', format: 'currency' },
        { key: 'netAmount', label: 'Net to Registry', format: 'currency' },
      ],
      rows: [...byTld.entries()]
        .sort((a, b) => b[1].netAmount - a[1].netAmount)
        .map(([tld, vals]) => ({
          tld: `.${tld}`,
          amount: vals.amount,
          netAmount: vals.netAmount,
        })),
    });
  }

  drillDownExpirationByTld(tld: string) {
    const cleaned = tld.replace(/^\./, '');
    const d = this.dashService.forecasting();
    if (!d) return;
    const points = d.expirationCurve.filter(pt => pt.tld === cleaned);
    if (points.length === 0) return;

    this.openDialog({
      title: `Expirations: .${cleaned}`,
      columns: [
        { key: 'month', label: 'Month' },
        { key: 'count', label: 'Expiring Domains', format: 'number' },
      ],
      rows: points.sort((a, b) => a.month.localeCompare(b.month)).map(pt => ({
        month: pt.month,
        count: pt.count,
      })),
    });
  }

  drillDownNetGrowthByPeriod(period: string) {
    const d = this.dashService.domainActivity();
    if (!d) return;
    const points = d.activity.filter(pt => pt.period === period);
    if (points.length === 0) return;

    const creates = points.filter(pt => pt.type === 'CREATES').reduce((s, pt) => s + pt.count, 0);
    const deletes = points.filter(pt => pt.type === 'DELETES').reduce((s, pt) => s + pt.count, 0);

    this.openDialog({
      title: `Net Growth: ${period}`,
      subtitle: `Net: ${creates - deletes >= 0 ? '+' : ''}${(creates - deletes).toLocaleString()}`,
      columns: [
        { key: 'metric', label: 'Metric' },
        { key: 'value', label: 'Count', format: 'number' },
      ],
      rows: [
        { metric: 'Creates', value: creates },
        { metric: 'Deletes', value: deletes },
        { metric: 'Net Growth', value: creates - deletes },
      ],
    });
  }

  drillDownFeesByTld(tld: string) {
    const cleaned = tld.replace(/^\./, '');
    const fees = this.dashService.tldFees().filter(f => f.tld === cleaned);
    if (fees.length === 0) return;

    this.openDialog({
      title: `Fees: .${cleaned}`,
      subtitle: fees[0].currency,
      columns: [
        { key: 'operation', label: 'Operation' },
        { key: 'defaultPrice', label: 'Default Fee', format: 'currency' },
      ],
      rows: fees.map(f => ({
        operation: f.operation,
        defaultPrice: f.defaultPrice,
      })),
    });
  }

  private openDialog(data: DrillDownDialogData) {
    this.dialog.open(DrillDownDialogComponent, {
      width: '720px',
      data,
    });
  }
}
