import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CardModule } from 'primeng/card';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ButtonModule } from 'primeng/button';
import { ChartModule } from 'primeng/chart';
import { JournalService } from '../../services/journal.service';
import { LedgerService } from '../../services/ledger.service';
import { AccountStatement, StatementLine } from '../../models';

@Component({
  selector: 'app-statement',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, CardModule, TableModule, TagModule, ButtonModule, ChartModule],
  template: `
    <div class="page-head">
      <h2>Account Statement</h2>
      <div class="head-actions">
        <p-button label="Back to Banking" icon="pi pi-arrow-left" severity="secondary" [routerLink]="'/banking'"></p-button>
        <p-button label="Print" icon="pi pi-print" severity="secondary" [outlined]="true" (onClick)="print()"></p-button>
      </div>
    </div>

    <p-card class="mb">
      <div class="acct-head">
        <div>
          <div class="mono big">{{ accountId }}</div>
          <div class="holder">{{ holderName || 'Account' }}</div>
        </div>
        <div class="bal">
          <label>Final Balance</label>
          <span>{{ finalBalance | number:'1.2-2' }}</span>
        </div>
      </div>
    </p-card>

    <p-card header="Balance Over Time" class="mb">
      <p-chart type="line" [data]="chartData" [options]="chartOptions" height="260"></p-chart>
    </p-card>

    <div class="form-row mb">
      <input type="date" [(ngModel)]="dateFrom">
      <input type="date" [(ngModel)]="dateTo">
      <p-button label="Clear" severity="secondary" icon="pi pi-filter-slash" (onClick)="clearFilters()"></p-button>
    </div>

    <p-card header="Transactions">
      <p-table [value]="filteredLines" responsiveLayout="scroll">
        <ng-template pTemplate="header">
          <tr>
            <th>Date</th><th>Description</th><th>Debit</th><th>Credit</th><th>Running Balance</th>
          </tr>
        </ng-template>
        <ng-template pTemplate="body" let-line>
          <tr>
            <td>{{ line.entry?.createdAt | date:'MMM d, y HH:mm' }}</td>
            <td class="small">{{ line.entry?.description }}</td>
            <td>{{ debitOf(line) }}</td>
            <td>{{ creditOf(line) }}</td>
            <td class="amt">{{ line.runningBalance | number:'1.2-2' }}</td>
          </tr>
        </ng-template>
      </p-table>
    </p-card>
  `,
  styles: [`
    .mb { margin-bottom: 1rem; }
    .head-actions { display: flex; gap: .5rem; }
    .mono { font-family: ui-monospace, monospace; } .big { font-size: 1.1rem; }
    .holder { opacity: .75; margin-top: .2rem; }
    .acct-head { display: flex; justify-content: space-between; align-items: center; }
    .bal { text-align: right; } .bal label { display: block; font-size: .72rem; text-transform: uppercase; opacity: .7; }
    .bal span { font-size: 1.8rem; font-weight: 700; color: var(--accent); }
    .amt { font-weight: 600; } .small { font-size: .78rem; opacity: .85; }
    @media print {
      .page-head, .head-actions, .form-row, .sidebar, .topbar { display: none !important; }
    }
  `]
})
export class StatementComponent implements OnInit {
  accountId = '';
  holderName = '';
  finalBalance = 0;
  statement: AccountStatement | undefined;
  dateFrom = '';
  dateTo = '';

  chartData: any;
  chartOptions: any;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private journal: JournalService,
    private ledger: LedgerService
  ) {}

  ngOnInit(): void {
    this.accountId = this.route.snapshot.paramMap.get('id') ?? '';
    if (!this.accountId) { this.router.navigate(['/banking']); return; }

    this.ledger.balance(this.accountId).subscribe({
      next: (a) => { this.holderName = a.holderName ?? ''; }
    });

    this.journal.statement(this.accountId).subscribe({
      next: (s) => {
        this.statement = s;
        this.finalBalance = s.finalBalance ?? 0;
        this.buildChart(s.lines ?? []);
      }
    });
  }

  get lines(): StatementLine[] {
    return this.statement?.lines ?? [];
  }

  get filteredLines(): StatementLine[] {
    return this.lines.filter((l) => {
      const t = l.entry?.createdAt ? new Date(l.entry.createdAt).toISOString().slice(0, 10) : '';
      if (this.dateFrom && t < this.dateFrom) return false;
      if (this.dateTo && t > this.dateTo) return false;
      return true;
    });
  }

  clearFilters(): void {
    this.dateFrom = '';
    this.dateTo = '';
  }

  debitOf(l: StatementLine): string {
    return l.entry?.debitAccountId === this.accountId ? (l.entry.amount ?? 0).toFixed(2) : '—';
  }

  creditOf(l: StatementLine): string {
    return l.entry?.creditAccountId === this.accountId ? (l.entry.amount ?? 0).toFixed(2) : '—';
  }

  buildChart(lines: StatementLine[]): void {
    const labels = lines.map((l) => l.entry?.createdAt ? new Date(l.entry.createdAt).toLocaleString() : '');
    const balances = lines.map((l) => l.runningBalance ?? 0);
    const doc = document.documentElement;
    const textColor = getComputedStyle(doc).getPropertyValue('--text').trim() || '#e2e8f0';
    const gridColor = getComputedStyle(doc).getPropertyValue('--border').trim() || '#334155';

    this.chartData = {
      labels,
      datasets: [{
        label: 'Running Balance',
        data: balances,
        fill: true,
        borderColor: '#38bdf8',
        backgroundColor: 'rgba(56,189,248,0.15)',
        tension: 0.25
      }]
    };
    this.chartOptions = {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { labels: { color: textColor } }
      },
      scales: {
        x: { ticks: { color: textColor }, grid: { color: gridColor } },
        y: { ticks: { color: textColor }, grid: { color: gridColor } }
      }
    };
  }

  print(): void {
    window.print();
  }
}
