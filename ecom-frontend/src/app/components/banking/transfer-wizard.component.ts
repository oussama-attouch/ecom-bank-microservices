import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { StepsModule } from 'primeng/steps';
import { CardModule } from 'primeng/card';
import { ButtonModule } from 'primeng/button';
import { DropdownModule } from 'primeng/dropdown';
import { InputNumberModule } from 'primeng/inputnumber';
import { TimelineModule } from 'primeng/timeline';
import { DialogModule } from 'primeng/dialog';
import { CheckboxModule } from 'primeng/checkbox';
import { TagModule } from 'primeng/tag';
import { TableModule } from 'primeng/table';
import { AuthService } from '../../services/auth.service';
import { LedgerService } from '../../services/ledger.service';
import { NotificationService } from '../../services/notification.service';
import { Account } from '../../models';
import { catchError, interval, of, Subscription, switchMap, takeWhile } from 'rxjs';

@Component({
  selector: 'app-transfer-wizard',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    StepsModule, CardModule, ButtonModule, DropdownModule, InputNumberModule,
    TimelineModule, DialogModule, CheckboxModule, TagModule, TableModule
  ],
  template: `
    <div class="page-head">
      <h2>Transfer Wizard</h2>
      <p-button label="Back to Banking" icon="pi pi-arrow-left" [text]="true" routerLink="/banking"></p-button>
    </div>

    <p-steps [model]="wizardItems" [activeIndex]="step" [readonly]="true" class="mb-2"></p-steps>

    <!-- Step 1: select -->
    <p-card *ngIf="step === 0" class="mt-2">
      <div class="form-col">
        <label>Source Account</label>
        <p-dropdown [options]="accountOptions" [(ngModel)]="sourceAccountId" optionLabel="label" optionValue="value" placeholder="Select source account" [style]="{ width: '100%' }"></p-dropdown>

        <label>Destination Account</label>
        <p-dropdown [options]="accountOptions" [(ngModel)]="destinationAccountId" optionLabel="label" optionValue="value" placeholder="Select destination account" [style]="{ width: '100%' }"></p-dropdown>

        <label>Amount</label>
        <p-inputNumber [(ngModel)]="amount" mode="currency" currency="USD" placeholder="Amount" [style]="{ width: '100%' }"></p-inputNumber>

        <div class="validation">
          <div *ngIf="sourceAccountId && sourceAccountId === destinationAccountId" class="err">Source and destination must differ.</div>
          <div *ngIf="amount > (sourceAccount?.balance ?? 0)" class="err">Insufficient funds in source account.</div>
          @if (isOverTellerLimit) {
            <div class="err" style="font-weight: bold; color: #dc2626; display: flex; align-items: center; gap: 4px;">
              <i class="pi pi-exclamation-triangle"></i> Teller limit exceeded. Transfers over $10,000 require a MANAGER role.
            </div>
          }
        </div>

        <p-button label="Next: Review" icon="pi pi-arrow-right" [disabled]="!valid" (onClick)="next()"></p-button>
      </div>
    </p-card>

    <!-- Step 2: review -->
    <p-card *ngIf="step === 1" class="mt-2">
      <div class="summary">
        <div class="sum-row"><span>Source</span><strong>{{ sourceAccount?.accountId }}</strong><span>{{ sourceAccount?.balance | number:'1.2-2' }} → {{ (sourceAccount?.balance ?? 0) - amount | number:'1.2-2' }}</span></div>
        <div class="sum-row"><span>Destination</span><strong>{{ destAccount?.accountId }}</strong><span>{{ destAccount?.balance | number:'1.2-2' }} → {{ (destAccount?.balance ?? 0) + amount | number:'1.2-2' }}</span></div>
        <div class="sum-row"><span>Amount</span><strong>{{ amount | number:'1.2-2' }}</strong></div>
        <div class="sum-row"><span>Transaction ID</span><strong class="mono">{{ transactionId }}</strong></div>
        <div class="sum-row"><span>Timestamp</span><strong>{{ now }}</strong></div>
      </div>

      <h4 class="mt-2">Journal Preview (double-entry)</h4>
      <div class="journal-preview">
        <div class="jp-row"><span class="jp-debit">Debit</span> {{ sourceAccountId }} <span class="jp-arrow">→</span> <span class="jp-credit">Credit</span> TRANSFER_CLEARING <span class="jp-amt">{{ amount | number:'1.2-2' }}</span></div>
        <div class="jp-row"><span class="jp-debit">Debit</span> TRANSFER_CLEARING <span class="jp-arrow">→</span> <span class="jp-credit">Credit</span> {{ destinationAccountId }} <span class="jp-amt">{{ amount | number:'1.2-2' }}</span></div>
      </div>

      <div class="chk mt-2">
        <p-checkbox [(ngModel)]="simulateFailure" [binary]="true" inputId="sim"></p-checkbox>
        <label for="sim">Simulate Failure (demo — force ARCHIVE step to fail)</label>
      </div>

      <div class="btn-row mt-2">
        <p-button label="Back" severity="secondary" icon="pi pi-arrow-left" (onClick)="back()"></p-button>
        <p-button label="Confirm Transfer" severity="warn" icon="pi pi-check" (onClick)="confirm()"></p-button>
      </div>
    </p-card>

    <!-- Step 3: execution -->
    <p-card *ngIf="step === 2" class="mt-2">
      <div *ngIf="saga?.status === 'COMPLETED'" class="banner success">
        <i class="pi pi-check-circle"></i> Transfer Completed — {{ saga.amount | number:'1.2-2' }} transferred
      </div>
      <div *ngIf="saga?.status === 'COMPENSATING'" class="banner warn">
        <i class="pi pi-undo"></i> Transfer Reversed — {{ saga.errorMessage }}
      </div>
      <div *ngIf="saga?.status === 'FAILED'" class="banner danger">
        <i class="pi pi-times-circle"></i> Transfer Failed — {{ saga.errorMessage }}
      </div>

      <div *ngIf="isStalled" class="banner warn">
        <i class="pi pi-exclamation-triangle"></i> Waiting for backend response...
        <p-button label="Stop" severity="secondary" (onClick)="stopPolling()"></p-button>
      </div>

      <h4>Saga Execution</h4>
      <p-timeline [value]="visibleTimeline">
        <ng-template pTemplate="content" let-s>
          <p-tag [value]="s.label" [severity]="s.severity" [icon]="icon(s.severity)"></p-tag>
          <small *ngIf="s.sub" class="ml">{{ s.sub }}</small>
        </ng-template>
      </p-timeline>

      <p-button label="View full saga state" [text]="true" icon="pi pi-info-circle" (onClick)="showRaw = true"></p-button>
      <p-button label="Done" icon="pi pi-check" class="ml" routerLink="/banking"></p-button>
    </p-card>

    <p-dialog header="Full Saga State" [(visible)]="showRaw" [modal]="true" [style]="{ width: '620px' }">
      <p-table [value]="sagaKeyValues" responsiveLayout="scroll">
        <ng-template pTemplate="header"><tr><th>Field</th><th>Value</th></tr></ng-template>
        <ng-template pTemplate="body" let-kv>
          <tr><td>{{ kv.key }}</td><td class="mono">{{ kv.value }}</td></tr>
        </ng-template>
      </p-table>
    </p-dialog>
  `,
  styles: [`
    .form-col { display: flex; flex-direction: column; gap: 12px; }
    .form-col label { font-weight: 600; }
    .validation .err { color: #dc2626; font-size: 0.85rem; }
    .summary { display: flex; flex-direction: column; gap: 8px; }
    .sum-row { display: flex; gap: 16px; align-items: center; }
    .sum-row span:first-child { width: 120px; color: var(--text-muted); }
    .mono { font-family: ui-monospace, monospace; font-size: 0.8rem; word-break: break-all; }
    .journal-preview { border: 1px solid var(--border); border-radius: 8px; padding: 12px; }
    .jp-row { padding: 4px 0; }
    .jp-debit { color: #dc2626; font-weight: 600; }
    .jp-credit { color: #16a34a; font-weight: 600; }
    .jp-arrow { color: var(--text-muted); margin: 0 8px; }
    .jp-amt { float: right; font-weight: 600; }
    .chk { display: flex; align-items: center; gap: 8px; }
    .btn-row { display: flex; gap: 8px; }
    .banner { padding: 14px; border-radius: 8px; display: flex; align-items: center; gap: 8px; font-weight: 600; margin-bottom: 16px; }
    .banner.success { background: #dcfce7; color: #166534; }
    .banner.warn { background: #fef3c7; color: #92400e; }
    .banner.danger { background: #fee2e2; color: #991b1b; }
    .ml { margin-left: 8px; }
  `]
})
export class TransferWizardComponent implements OnInit, OnDestroy {
  step = 0;
  wizardItems = [{ label: 'Select' }, { label: 'Review' }, { label: 'Execute' }];

  accounts: Account[] = [];
  sourceAccountId: string | null = null;
  destinationAccountId: string | null = null;
  amount = 0;
  transactionId = crypto.randomUUID();
  simulateFailure = false;

  saga: any = null;
  timeline: any[] = [];
  visibleCount = 0;
  polling: Subscription | null = null;
  pollingTimeout: any = null;
  pollStartedAt: number | null = null;
  lastPollOkAt: number | null = null;
  private pollingStopped = false;
  showRaw = false;

  constructor(
    private ledger: LedgerService,
    public authService: AuthService,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.ledger.listAccounts().subscribe({ next: (a) => (this.accounts = a) });
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  get accountOptions(): { label: string; value: string }[] {
    return this.accounts.map((a) => ({ label: `${a.accountId} (balance: $${(a.balance || 0).toFixed(2)})`, value: a.accountId }));
  }

  get sourceAccount(): Account | undefined {
    return this.accounts.find((a) => a.accountId === this.sourceAccountId);
  }

  get destAccount(): Account | undefined {
    return this.accounts.find((a) => a.accountId === this.destinationAccountId);
  }

  get isOverTellerLimit(): boolean {
    return this.authService.hasRole('TELLER') && this.amount > 10000;
  }

  get valid(): boolean {
    return !!this.sourceAccountId && !!this.destinationAccountId
      && this.sourceAccountId !== this.destinationAccountId
      && this.amount > 0
      && this.amount <= (this.sourceAccount?.balance ?? 0)
      && !this.isOverTellerLimit;
  }

  get visibleTimeline(): any[] {
    return this.timeline.slice(0, this.visibleCount);
  }

  get sagaKeyValues(): { key: string; value: string }[] {
    if (!this.saga) return [];
    const rows = [];
    for (const k of ['transactionId', 'status', 'sourceAccountId', 'destinationAccountId', 'amount', 'errorMessage', 'startedAt', 'completedAt']) {
      if (this.saga[k] != null) rows.push({ key: k, value: String(this.saga[k]) });
    }
    rows.push({ key: 'steps', value: JSON.stringify(this.saga.steps ?? []) });
    return rows;
  }

  get now(): string {
    return new Date().toLocaleString();
  }

  next(): void {
    if (this.valid) this.step = 1;
  }

  back(): void {
    this.step = 0;
  }

  confirm(): void {
    this.step = 2;
    this.timeline = [];
    this.visibleCount = 0;
    this.ledger.transferSaga(this.sourceAccountId!, this.destinationAccountId!, this.amount, this.transactionId, this.simulateFailure).subscribe({
      next: (saga) => {
        this.saga = saga;
        this.buildTimeline(saga);
        this.startPolling();
        this.notifySaga(saga);
      },
      error: (e) => {
        // HTTP errors are surfaced once, globally, by error.interceptor.ts.
        console.error('[TransferWizard] transfer request failed', e);
        this.step = 1;
      }
    });
  }

  /** Raise a notification-centre entry when a transfer did not complete cleanly. */
  private notifySaga(saga: any): void {
    const route = `${saga?.amount ?? '?'} from ${saga?.sourceAccountId ?? '?'} to ${saga?.destinationAccountId ?? '?'}`;
    if (saga?.status === 'COMPENSATING') {
      this.notifications.push({
        title: 'Transfer compensated',
        message: `${route} was reversed. ${saga.errorMessage ?? ''}`.trim(),
        severity: 'warn'
      });
    } else if (saga?.status === 'FAILED') {
      this.notifications.push({
        title: 'Transfer failed',
        message: `${route} failed and could not be reversed. ${saga.errorMessage ?? ''}`.trim(),
        severity: 'error'
      });
    }
  }

  private buildTimeline(saga: any): void {
    const list: any[] = [];
    for (const s of saga.steps ?? []) {
      const severity = s.status === 'EXECUTED' ? 'success' : s.status === 'COMPENSATED' ? 'warn' : s.status === 'FAILED' ? 'danger' : 'secondary';
      list.push({ label: s.name, severity, sub: s.offset != null ? `offset ${s.offset}` : undefined });
    }
    const finalSeverity = saga.status === 'COMPLETED' ? 'success' : saga.status === 'COMPENSATING' ? 'warn' : 'danger';
    list.push({ label: saga.status, severity: finalSeverity, sub: undefined });

    this.timeline = list;
    this.visibleCount = 0;
    const total = list.length;
    for (let i = 0; i < total; i++) {
      setTimeout(() => (this.visibleCount = i + 1), 300 * i);
    }
  }

  private startPolling(): void {
    this.stopPolling();
    // The POST already ran the saga to completion, so an already-terminal saga
    // needs no poll at all — without this the tracker churns on a settled saga.
    if (this.isTerminal(this.saga?.status)) return;

    this.pollStartedAt = Date.now();
    this.lastPollOkAt = null;
    this.pollingStopped = false;
    this.polling = interval(1000).pipe(
      switchMap(() => this.ledger.getSaga(this.transactionId, true).pipe(
        catchError(err => {
          console.error('[TransferWizard] saga poll failed', err);
          return of(null);
        })
      )),
      takeWhile(saga => {
        if (!saga) return true; // A failed poll is not a terminal state: keep trying.
        this.lastPollOkAt = Date.now();
        this.saga = saga;
        this.buildTimeline(saga);
        return !this.isTerminal(saga.status);
      }, true)
    ).subscribe({
      error: err => console.error('[TransferWizard] saga poll error', err)
    });

    this.pollingTimeout = setTimeout(() => {
      if (!this.isTerminal(this.saga?.status)) {
        this.notifications.push({
          title: 'Saga poll timed out',
          message: `No terminal state for ${this.transactionId} after 60s — check the Sagas page.`,
          severity: 'warn'
        });
        this.stopPolling();
      }
    }, 60_000);
  }

  /** Public because the step-3 "Stop" banner button calls it from the template. */
  stopPolling(): void {
    this.pollingStopped = true;
    this.polling?.unsubscribe();
    this.polling = null;
    if (this.pollingTimeout) {
      clearTimeout(this.pollingTimeout);
      this.pollingTimeout = null;
    }
  }

  /**
   * True once polling has run 5s without a single successful response — the
   * backend is down or unreachable. Keyed on the last good poll rather than on
   * `saga === null`, because `confirm()` already seeded `saga` from the POST
   * response, so `saga` is never null while step 3 is on screen.
   *
   * Deliberately does NOT test `polling`: while stalled the poll is still
   * running, so requiring it to be stopped would make the banner unreachable.
   * `pollingStopped` is what suppresses the banner after "Stop" or the 60s guard.
   */
  get isStalled(): boolean {
    return this.pollStartedAt !== null
      && !this.pollingStopped
      && this.lastPollOkAt === null
      && Date.now() - this.pollStartedAt > 5000
      && !this.isTerminal(this.saga?.status);
  }

  private isTerminal(status: string | undefined): boolean {
    return status === 'COMPLETED' || status === 'COMPENSATING' || status === 'FAILED';
  }

  icon(severity: string): string {
    switch (severity) {
      case 'success': return 'pi pi-check-circle';
      case 'warn': return 'pi pi-undo';
      case 'danger': return 'pi pi-times-circle';
      default: return 'pi pi-spinner';
    }
  }
}
