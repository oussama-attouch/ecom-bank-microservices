import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { InputTextModule } from 'primeng/inputtext';
import { ButtonModule } from 'primeng/button';
import { LedgerService } from '../../services/ledger.service';
import { SagaState } from '../../models';
import { EmptyStateComponent } from '../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../shared/table-skeleton/table-skeleton.component';

@Component({
  selector: 'app-saga-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TableModule, TagModule, InputTextModule, ButtonModule, EmptyStateComponent, TableSkeletonComponent],
  template: `
    <div class="page-head"><h2>Sagas</h2></div>

    <div class="form-row">
      <select name="status" [(ngModel)]="statusFilter">
        <option value="">All statuses</option>
        <option>COMPLETED</option>
        <option>COMPENSATING</option>
        <option>FAILED</option>
      </select>
      <input type="date" name="from" [(ngModel)]="dateFrom">
      <input type="date" name="to" [(ngModel)]="dateTo">
      <input type="number" name="minAmt" [(ngModel)]="minAmount" placeholder="Min amount">
      <input type="number" name="maxAmt" [(ngModel)]="maxAmount" placeholder="Max amount">
      <p-button label="Clear" severity="secondary" icon="pi pi-filter-slash" (onClick)="clearFilters()"></p-button>
    </div>

    @if (loading) {
      <app-table-skeleton [rows]="5" [cols]="7"></app-table-skeleton>
    } @else {
    <p-table #dt [value]="filteredSagas" [paginator]="true" [rows]="15"
             [globalFilterFields]="['transactionId','sourceAccountId','destinationAccountId']" responsiveLayout="scroll">
      <ng-template pTemplate="caption">
        <div class="flex justify-end">
          <input pInputText type="text" (input)="dt.filterGlobal($any($event.target).value, 'contains')" placeholder="Search">
        </div>
      </ng-template>
      <ng-template pTemplate="header">
        <tr>
          <th pSortableColumn="transactionId">Txn ID</th>
          <th pSortableColumn="status">Status</th>
          <th pSortableColumn="amount">Amount</th>
          <th>Source</th>
          <th>Destination</th>
          <th pSortableColumn="startedAt">Started</th>
          <th>Duration</th>
        </tr>
      </ng-template>
      <ng-template pTemplate="body" let-s>
        <tr>
          <td><a [routerLink]="'/transactions/sagas/' + s.transactionId" class="mono">{{ short(s.transactionId) }}</a></td>
          <td><p-tag [value]="s.status" [severity]="severity(s.status)"></p-tag></td>
          <td>{{ s.amount | number:'1.2-2' }}</td>
          <td class="mono">{{ s.sourceAccountId }}</td>
          <td class="mono">{{ s.destinationAccountId }}</td>
          <td>{{ s.startedAt | date:'MMM d HH:mm:ss' }}</td>
          <td>{{ duration(s) }}</td>
        </tr>
      </ng-template>
      <ng-template pTemplate="emptymessage">
        <tr>
          <td [attr.colspan]="7">
            <app-empty-state
              icon="pi pi-sitemap"
              title="No sagas found"
              hint="Sagas appear here once a transfer is run through the wizard."></app-empty-state>
          </td>
        </tr>
      </ng-template>
    </p-table>
    }
  `,
  styles: [`
    .flex { display: flex; } .justify-end { justify-content: flex-end; }
    .mono { font-family: ui-monospace, monospace; font-size: 0.8rem; }
    a.mono { color: var(--accent); text-decoration: none; }
  `]
})
export class SagaListComponent implements OnInit {
  sagas: SagaState[] = [];
  statusFilter = '';
  dateFrom = '';
  dateTo = '';
  minAmount: number | null = null;
  maxAmount: number | null = null;

  /** True until the first sagas response lands; drives the table skeleton (brief 5.9). */
  loading = true;

  constructor(private ledger: LedgerService) {}

  ngOnInit(): void {
    this.ledger.listSagas().subscribe({
      next: (s) => { this.sagas = s; this.loading = false; },
      error: (e) => { this.loading = false; this.err(e); }
    });
  }

  /** HTTP errors are surfaced once, globally, by error.interceptor.ts. */
  private err(e: unknown): void {
    console.error('[Sagas] request failed', e);
  }

  get filteredSagas(): SagaState[] {
    return this.sagas.filter((s) => {
      if (this.statusFilter && s.status !== this.statusFilter) return false;
      if (this.dateFrom && s.startedAt && s.startedAt < this.dateFrom) return false;
      if (this.dateTo && s.startedAt && s.startedAt > this.dateTo + 'T23:59:59') return false;
      if (this.minAmount != null && s.amount < this.minAmount) return false;
      if (this.maxAmount != null && s.amount > this.maxAmount) return false;
      return true;
    });
  }

  clearFilters(): void {
    this.statusFilter = '';
    this.dateFrom = '';
    this.dateTo = '';
    this.minAmount = null;
    this.maxAmount = null;
  }

  short(id: string): string {
    return id.length > 12 ? id.substring(0, 12) + '…' : id;
  }

  duration(s: SagaState): string {
    if (!s.startedAt || !s.completedAt) return '—';
    const ms = new Date(s.completedAt).getTime() - new Date(s.startedAt).getTime();
    return ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's';
  }

  severity(s: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (s) {
      case 'COMPLETED': return 'success';
      case 'COMPENSATING': return 'warn';
      case 'FAILED': return 'danger';
      default: return 'secondary';
    }
  }
}
