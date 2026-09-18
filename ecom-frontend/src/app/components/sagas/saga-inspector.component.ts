import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CardModule } from 'primeng/card';
import { TimelineModule } from 'primeng/timeline';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ButtonModule } from 'primeng/button';
import { PanelModule } from 'primeng/panel';
import { AuthService } from '../../services/auth.service';
import { LedgerService } from '../../services/ledger.service';
import { JournalService } from '../../services/journal.service';
import { SagaState, SagaStep, JournalEntry } from '../../models';

@Component({
  selector: 'app-saga-inspector',
  standalone: true,
  imports: [CommonModule, RouterLink, CardModule, TimelineModule, TableModule, TagModule, ButtonModule, PanelModule],
  template: `
    <div class="page-head">
      <h2>Saga Inspector</h2>
      <p-button label="Back to Sagas" icon="pi pi-arrow-left" severity="secondary" [routerLink]="'/transactions/sagas'"></p-button>
    </div>

    <p-card *ngIf="saga" class="mb">
      <div class="saga-head">
        <div>
          <div class="mono big">{{ saga.transactionId }}</div>
          <p-tag [value]="saga.status" [severity]="severity(saga.status)" styleClass="mt-1"></p-tag>
        </div>
        <div class="amt">{{ saga.amount | number:'1.2-2' }}</div>
      </div>
      <div class="kv-grid">
        <div><label>Source</label><span class="mono">{{ saga.sourceAccountId }}</span></div>
        <div><label>Destination</label><span class="mono">{{ saga.destinationAccountId }}</span></div>
        <div><label>Started</label><span>{{ saga.startedAt | date:'MMM d, y HH:mm:ss.SSS' }}</span></div>
        <div><label>Completed</label><span>{{ saga.completedAt | date:'MMM d, y HH:mm:ss.SSS' }}</span></div>
        <div><label>Duration</label><span>{{ duration() }}</span></div>
      </div>
      <div class="mt">
        @if (!authService.hasRole('AUDITOR')) {
          <p-button label="Replay this transfer" icon="pi pi-replay" (onClick)="replay()"></p-button>
        }
      </div>
    </p-card>

    <p-card *ngIf="saga && saga.errorMessage" class="mb error-card">
      <h3 class="err-title">Compensation Triggered</h3>
      <p class="mono">{{ saga.errorMessage }}</p>
    </p-card>

    <div class="two-col">
      <p-card header="Execution Steps">
        <p-timeline [value]="saga?.steps" align="left">
          <ng-template pTemplate="content" let-step>
            <div class="step-row">
              <span class="step-name">{{ step.name }}</span>
              <p-tag [value]="step.status" [severity]="stepSeverity(step.status)"></p-tag>
              <span class="step-ts" *ngIf="step.timestamp">{{ step.timestamp | date:'HH:mm:ss.SSS' }}</span>
            </div>
          </ng-template>
        </p-timeline>
      </p-card>

      <p-card header="Journal Entries">
        <p-table [value]="entries" responsiveLayout="scroll">
          <ng-template pTemplate="header">
            <tr>
              <th>Time</th><th>Debit</th><th>Credit</th><th>Amount</th><th>Description</th>
            </tr>
          </ng-template>
          <ng-template pTemplate="body" let-e>
            <tr>
              <td>{{ e.createdAt | date:'HH:mm:ss' }}</td>
              <td class="mono">{{ e.debitAccountId }}</td>
              <td class="mono">{{ e.creditAccountId }}</td>
              <td>{{ e.amount | number:'1.2-2' }}</td>
              <td class="small">{{ e.description }}</td>
            </tr>
          </ng-template>
        </p-table>
      </p-card>
    </div>

    <p-panel header="Raw JSON" [toggleable]="true" [collapsed]="true">
      <pre class="json">{{ rawJson }}</pre>
    </p-panel>
  `,
  styles: [`
    .mb { margin-bottom: 1rem; } .mt { margin-top: 1rem; } .mt-1 { margin-top: .25rem; }
    .mono { font-family: ui-monospace, monospace; } .big { font-size: 1.05rem; }
    .saga-head { display: flex; justify-content: space-between; align-items: flex-start; }
    .amt { font-size: 1.8rem; font-weight: 700; color: var(--accent); }
    .kv-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 1rem; margin-top: 1rem; }
    .kv-grid label { display: block; font-size: .72rem; text-transform: uppercase; opacity: .7; margin-bottom: .25rem; }
    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin-bottom: 1rem; }
    @media (max-width: 900px) { .two-col { grid-template-columns: 1fr; } }
    .step-row { display: flex; align-items: center; gap: .6rem; padding: .35rem 0; }
    .step-name { font-weight: 600; }
    .step-ts { font-size: .75rem; opacity: .6; font-family: ui-monospace, monospace; margin-left: auto; }
    .small { font-size: .78rem; opacity: .85; }
    .json { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow: auto; font-size: .78rem; }
    .error-card { border: 1px solid #f87171; }
    .err-title { color: #ef4444; margin: 0 0 .5rem; }
  `]
})
export class SagaInspectorComponent implements OnInit {
  saga: SagaState | undefined;
  entries: JournalEntry[] = [];
  rawJson = '';

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private ledger: LedgerService,
    private journal: JournalService,
    public authService: AuthService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.router.navigate(['/transactions/sagas']); return; }
    this.ledger.getSaga(id).subscribe({
      next: (s) => {
        this.saga = s;
        this.rawJson = JSON.stringify(s, null, 2);
      }
    });
    this.journal.entries(id).subscribe({ next: (e) => (this.entries = e) });
  }

  duration(): string {
    const s = this.saga;
    if (!s?.startedAt || !s?.completedAt) return '—';
    const ms = new Date(s.completedAt).getTime() - new Date(s.startedAt).getTime();
    return ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's';
  }

  replay(): void {
    const s = this.saga;
    if (!s) return;
    this.router.navigate(['/banking/transfer-wizard'], {
      queryParams: { source: s.sourceAccountId, dest: s.destinationAccountId, amount: s.amount }
    });
  }

  severity(s: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (s) {
      case 'COMPLETED': return 'success';
      case 'COMPENSATING': return 'warn';
      case 'FAILED': return 'danger';
      default: return 'secondary';
    }
  }

  stepSeverity(s: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (s) {
      case 'EXECUTED': return 'success';
      case 'COMPENSATED': return 'info';
      case 'FAILED': return 'danger';
      default: return 'secondary';
    }
  }
}
