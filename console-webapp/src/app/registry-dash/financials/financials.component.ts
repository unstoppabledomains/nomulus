import { AfterViewInit, Component, OnInit, ViewChild, computed, effect, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MaterialModule } from '../../material.module';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { RegistryDashService, CostBasisEntry } from '../registry-dash.service';

const OPERATION_COLORS: Record<string, string> = {
  CREATE: '#1a73e8',
  RENEW: '#34a853',
  TRANSFER: '#fbbc04',
  RESTORE: '#ea4335',
};

@Component({
  selector: 'app-registry-dash-financials',
  standalone: true,
  imports: [CommonModule, MaterialModule, MatSortModule],
  templateUrl: './financials.component.html',
  styleUrls: ['./financials.component.scss'],
})
export class FinancialsComponent implements OnInit, AfterViewInit {
  selectedTab = signal(0);

  @ViewChild('costBasisSort') costBasisSort!: MatSort;
  dataSource = new MatTableDataSource<CostBasisEntry>([]);

  costBasisEntries = computed(() => this.dashService.costBasis());

  // Filters
  filterTld = signal<string>('all');
  filterOperation = signal<string>('all');
  filterRegistrar = signal<string>('all');

  uniqueTlds = computed(() => {
    const entries = this.costBasisEntries();
    return [...new Set(entries.map(e => e.tld))].sort();
  });

  uniqueOperations = computed(() => {
    const entries = this.costBasisEntries();
    return [...new Set(entries.map(e => e.operation))].sort();
  });

  uniqueRegistrars = computed(() => {
    const entries = this.costBasisEntries();
    const ids = entries.map(e => e.registrarId).filter((id): id is string => !!id);
    return [...new Set(ids)].sort();
  });

  filteredEntries = computed(() => {
    let entries = this.costBasisEntries();
    const tld = this.filterTld();
    const op = this.filterOperation();
    const reg = this.filterRegistrar();
    if (tld !== 'all') entries = entries.filter(e => e.tld === tld);
    if (op !== 'all') entries = entries.filter(e => e.operation === op);
    if (reg !== 'all') entries = entries.filter(e => (e.registrarId || '') === reg);
    return entries;
  });

  hasActiveFilters = computed(() =>
    this.filterTld() !== 'all' || this.filterOperation() !== 'all' || this.filterRegistrar() !== 'all'
  );

  // Group cost basis by TLD for the summary view
  costBasisByTld = computed(() => {
    const entries = this.costBasisEntries();
    const grouped = new Map<string, CostBasisEntry[]>();
    for (const entry of entries) {
      const key = entry.tld;
      if (!grouped.has(key)) grouped.set(key, []);
      grouped.get(key)!.push(entry);
    }
    return Array.from(grouped.entries()).map(([tld, items]) => ({
      tld,
      entries: items,
      totalCost: items.reduce((sum, e) => sum + e.costAmount, 0),
    }));
  });

  // Summary metrics
  totalTlds = computed(() => this.uniqueTlds().length);
  totalOperations = computed(() => this.uniqueOperations().length);
  averageCost = computed(() => {
    const entries = this.costBasisEntries();
    if (entries.length === 0) return 0;
    return entries.reduce((sum, e) => sum + e.costAmount, 0) / entries.length;
  });

  // Chart data: stacked bar chart grouped by TLD, segments by operation
  chartData = computed(() => {
    const byTld = this.costBasisByTld();
    if (byTld.length === 0) return [];
    const maxTotal = Math.max(...byTld.map(g => g.totalCost), 1);
    return byTld.map(group => {
      const segments = group.entries.map(e => ({
        operation: e.operation,
        amount: e.costAmount,
        widthPercent: (e.costAmount / maxTotal) * 100,
        color: OPERATION_COLORS[e.operation] || '#9e9e9e',
      }));
      return {
        tld: group.tld,
        total: group.totalCost,
        totalWidthPercent: (group.totalCost / maxTotal) * 100,
        segments,
      };
    });
  });

  costBasisColumns = ['tld', 'operation', 'registrarId', 'costAmount', 'costCurrency', 'effectiveDate', 'notes'];

  constructor(public dashService: RegistryDashService) {
    // Sync filtered entries into dataSource
    effect(() => {
      this.dataSource.data = this.filteredEntries();
    });
  }

  ngOnInit() {
    this.dashService.getCostBasis().subscribe();
  }

  ngAfterViewInit() {
    this.dataSource.sort = this.costBasisSort;
  }

  onTabChange(index: number) {
    this.selectedTab.set(index);
  }

  clearFilters() {
    this.filterTld.set('all');
    this.filterOperation.set('all');
    this.filterRegistrar.set('all');
  }

  getRegistrarLabel(registrarId: string | null | undefined): string {
    return registrarId || 'All (default)';
  }

  getOperationColor(operation: string): string {
    return OPERATION_COLORS[operation] || '#9e9e9e';
  }
}
