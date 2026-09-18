import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { InputTextModule } from 'primeng/inputtext';
import { ButtonModule } from 'primeng/button';
import { JournalService } from '../../services/journal.service';
import { JournalEntry, TrialBalance } from '../../models';
import { EmptyStateComponent } from '../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../shared/table-skeleton/table-skeleton.component';
import { KpiSkeletonComponent } from '../shared/kpi-skeleton/kpi-skeleton.component';

@Component({
  selector: 'app-journal-explorer',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TableModule, TagModule, InputTextModule, ButtonModule, EmptyStateComponent, TableSkeletonComponent, KpiSkeletonComponent],
  template: `
    <div class="page-head">
      <h2>Journal Explorer</h2>
      <p-button label="Export CSV" icon="pi pi-download" severity="secondary" [outlined]="true" (onClick)="exportCsv()"></p-button>
    </div>

    @if (loading) {
      <app-kpi-skeleton [count]="4"></app-kpi-skeleton>
    } @else {
    <div class="summary-bar">
      <div class="sum"><label>Entries</label><span>{{ entries.length }}</span></div>
      <div class="sum"><label>Debits</label><span>{{ totalDebits | number:'1.2-2' }}</span></div>
      <div class="sum"><label>Credits</label><span>{{ totalCredits | number:'1.2-2' }}</span></div>
      <div class="sum"><label>Balanced</label>
        <p-tag [value]="balanced ? 'YES ✅' : 'NO ❌'" [severity]="balanced ? 'success' : 'danger'"></p-tag>
      </div>
    </div>
    }

    <div class="form-row">
      <input type="text" [(ngModel)]="accountFilter" placeholder="Filter by account">
      <input type="date" [(ngModel)]="dateFrom">
      <input type="date" [(ngModel)]="dateTo">
      <input type="number" [(ngModel)]="minAmount" placeholder="Min amount">
      <input type="number" [(ngModel)]="maxAmount" placeholder="Max amount">
      <p-button label="Clear" severity="secondary" icon="pi pi-filter-slash" (onClick)="clearFilters()"></p-button>
    </div>

    @if (loading) {
      <app-table-skeleton [rows]="5" [cols]="7"></app-table-skeleton>
    } @else {
    <p-table [value]="filteredEntries" [paginator]="true" [rows]="15" [sortField]="'createdAt'" [sortOrder]="-1" responsiveLayout="scroll">
      <ng-template pTemplate="header">
        <tr>
          <th pSortableColumn="createdAt">Time</th>
          <th>Transaction</th>
          <th pSortableColumn="debitAccountId">Debit</th>
          <th pSortableColumn="creditAccountId">Credit</th>
          <th pSortableColumn="amount">Amount</th>
          <th>Currency</th>
          <th>Description</th>
        </tr>
      </ng-template>
      <ng-template pTemplate="body" let-e>
        <tr>
          <td>{{ e.createdAt | date:'MMM d, y HH:mm:ss' }}</td>
          <td><a *ngIf="e.transactionId" [routerLink]="'/transactions/sagas/' + e.transactionId" class="mono">{{ short(e.transactionId) }}</a></td>
          <td class="mono">{{ e.debitAccountId }}</td>
          <td class="mono">{{ e.creditAccountId }}</td>
          <td class="amt">{{ e.amount | number:'1.2-2' }}</td>
          <td>{{ e.currency }}</td>
          <td class="small">{{ e.description }}</td>
        </tr>
      </ng-template>
      <ng-template pTemplate="emptymessage">
        <tr>
          <td [attr.colspan]="7">
            <app-empty-state
              icon="pi pi-filter-slash"
              title="No entries match these filters"
              hint="Clear the filters to see the full journal again."></app-empty-state>
          </td>
        </tr>
      </ng-template>
    </p-table>
    }
  `,
  styles: [`
    .summary-bar { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; margin-bottom: 1rem; }
    .sum { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 1rem; text-align: center; }
    .sum label { display: block; font-size: .72rem; text-transform: uppercase; opacity: .7; margin-bottom: .25rem; }
    .sum span { font-size: 1.4rem; font-weight: 700; }
    .mono { font-family: ui-monospace, monospace; font-size: .8rem; }
    a.mono { color: var(--accent); text-decoration: none; }
    .amt { font-weight: 600; }
    .small { font-size: .78rem; opacity: .85; }
    @media (max-width: 700px) { .summary-bar { grid-template-columns: 1fr 1fr; } }
  `]
})
export class JournalExplorerComponent implements OnInit {
  entries: JournalEntry[] = [];
  trial: TrialBalance | null = null;
  accountFilter = '';
  dateFrom = '';
  dateTo = '';
  minAmount: number | null = null;
  maxAmount: number | null = null;

  /** True until the first journal response lands; drives the KPI and table skeletons (brief 5.9). */
  loading = true;

  constructor(private journal: JournalService) {}

  ngOnInit(): void {
    this.journal.entries().subscribe({
      next: (e) => { this.entries = e; this.loading = false; },
      error: (e) => { this.loading = false; this.err(e); }
    });
    this.journal.trialBalance().subscribe({
      next: (t) => { this.trial = t; this.loading = false; },
      error: (e) => { this.loading = false; this.err(e); }
    });
  }

  /** HTTP errors are surfaced once, globally, by error.interceptor.ts. */
  private err(e: unknown): void {
    console.error('[JournalExplorer] request failed', e);
  }

  get totalDebits(): number {
    return this.trial?.totalDebits ?? this.entries.reduce((s, e) => s + (e.amount ?? 0), 0);
  }

  get totalCredits(): number {
    return this.trial?.totalCredits ?? this.entries.reduce((s, e) => s + (e.amount ?? 0), 0);
  }

  get balanced(): boolean {
    return this.trial?.balanced ?? (Math.abs(this.totalDebits - this.totalCredits) < 1e-6);
  }

  get filteredEntries(): JournalEntry[] {
    const acct = this.accountFilter.trim().toLowerCase();
    return this.entries.filter((e) => {
      if (acct && !(e.debitAccountId ?? '').toLowerCase().includes(acct) && !(e.creditAccountId ?? '').toLowerCase().includes(acct)) return false;
      const t = e.createdAt ? new Date(e.createdAt).toISOString().slice(0, 10) : '';
      if (this.dateFrom && t < this.dateFrom) return false;
      if (this.dateTo && t > this.dateTo) return false;
      if (this.minAmount != null && (e.amount ?? 0) < this.minAmount) return false;
      if (this.maxAmount != null && (e.amount ?? 0) > this.maxAmount) return false;
      return true;
    });
  }

  clearFilters(): void {
    this.accountFilter = '';
    this.dateFrom = '';
    this.dateTo = '';
    this.minAmount = null;
    this.maxAmount = null;
  }

  short(id: string): string {
    return id.length > 12 ? id.substring(0, 12) + '…' : id;
  }

  exportCsv(): void {
    const rows = this.filteredEntries;
    const header = ['Time', 'TransactionId', 'DebitAccount', 'CreditAccount', 'Amount', 'Currency', 'Description'];
    const lines = rows.map((e) =>
      [e.createdAt, e.transactionId, e.debitAccountId, e.creditAccountId, e.amount, e.currency, e.description]
        .map((v) => (v == null ? '' : `"${String(v).replace(/"/g, '""')}"`))
        .join(',')
    );
    const csv = [header.join(','), ...lines].join('\n');
    const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `journal-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }
}
