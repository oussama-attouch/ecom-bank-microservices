import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { InputNumberModule } from 'primeng/inputnumber';
import { TooltipModule } from 'primeng/tooltip';
import { ConfirmationService, MessageService } from 'primeng/api';
import { AuthService } from '../../services/auth.service';
import { LedgerService } from '../../services/ledger.service';
import { CustomerService } from '../../services/customer.service';
import { Account, Customer } from '../../models';
import { EmptyStateComponent } from '../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../shared/table-skeleton/table-skeleton.component';
import { catchError, forkJoin, of } from 'rxjs';

@Component({
  selector: 'app-banking',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TableModule, ButtonModule, TagModule, InputNumberModule, TooltipModule, EmptyStateComponent, TableSkeletonComponent],
  template: `
    <div class="page-head">
      <h2>Banking</h2>
      @if (!authService.hasRole('AUDITOR')) {
        <p-button label="New Transfer" icon="pi pi-exchange" severity="warn" routerLink="/banking/transfer-wizard"></p-button>
      }
    </div>

    @if (!authService.hasRole('AUDITOR')) {
      <div class="form-row">
        <select name="customer" [(ngModel)]="newCustomerId">
          <option [ngValue]="null" disabled>Select customer</option>
          <option *ngFor="let c of customers" [ngValue]="c.id">{{ c.name }}</option>
        </select>
        <p-button label="Create Account" icon="pi pi-plus" (onClick)="createAccount()"></p-button>
      </div>
    }

    @if (loading) {
      <app-table-skeleton [rows]="5" [cols]="4"></app-table-skeleton>
    } @else {
    <p-table #dt [value]="accounts" [paginator]="true" [rows]="10" responsiveLayout="scroll">
      <ng-template pTemplate="header">
        <tr>
          <th pSortableColumn="accountId">Account <p-sortIcon field="accountId"></p-sortIcon></th>
          <th>Holder</th>
          <th pSortableColumn="balance">Balance <p-sortIcon field="balance"></p-sortIcon></th>
          <th>Actions</th>
        </tr>
      </ng-template>
      <ng-template pTemplate="emptymessage">
        <tr>
          <td [attr.colspan]="4">
            <app-empty-state
              icon="pi pi-wallet"
              title="No accounts yet"
              hint="Pick a customer above and use Create Account to open the first ledger account."></app-empty-state>
          </td>
        </tr>
      </ng-template>
      <ng-template pTemplate="body" let-a>
        <tr>
          <td class="mono-id">{{ a.accountId }}</td>
          <td>{{ a.holderName ?? a.customerId }}</td>
          <td>{{ a.balance }}</td>
          <td>
            <p-button icon="pi pi-eye" [text]="true" [rounded]="true" pTooltip="History" tooltipPosition="top" (onClick)="selectAccount(a.accountId)"></p-button>
            <p-button icon="pi pi-file" [text]="true" [rounded]="true" pTooltip="Statement" tooltipPosition="top" [routerLink]="'/accounts/' + a.accountId + '/statement'"></p-button>
          </td>
        </tr>
      </ng-template>
    </p-table>
    }

    <div *ngIf="detail" class="mt-2">
      <div class="page-head"><h3>Account {{ detail.accountId }}</h3></div>
      <p><strong>Holder:</strong> {{ detail.holderName }} — <strong>Balance:</strong> {{ detail.balance }}</p>
      <div class="form-row">
        <p-inputNumber name="txAmount" [(ngModel)]="txAmount" placeholder="Amount" mode="currency" currency="USD"></p-inputNumber>
        <p-button label="Credit" icon="pi pi-plus" severity="success" (onClick)="applyTransaction('CREDIT')"></p-button>
        <p-button label="Debit" icon="pi pi-minus" severity="warn" (onClick)="applyTransaction('DEBIT')"></p-button>
      </div>
      <p-table [value]="detail.history || []" responsiveLayout="scroll">
        <ng-template pTemplate="header">
          <tr><th>Offset</th><th>When</th><th>Type</th><th>Amount</th><th>Description</th></tr>
        </ng-template>
        <ng-template pTemplate="body" let-e>
          <tr>
            <td>{{ e.offset }}</td>
            <td>{{ e.occurredAt | date:'MMM d, HH:mm:ss' }}</td>
            <td><p-tag [value]="e.type" [severity]="eventSeverity(e.type)"></p-tag></td>
            <td>{{ e.amount }}</td>
            <td>{{ e.description }}</td>
          </tr>
        </ng-template>
      </p-table>
    </div>
  `
})
export class BankingComponent implements OnInit {
  accounts: Account[] = [];
  customers: Customer[] = [];
  newCustomerId: number | null = null;
  fromAccountId: string | null = null;
  toAccountId: string | null = null;
  amount = 0;
  detail: Account | null = null;
  txAmount = 0;
  lastSaga: any = null;

  /** True until the first accounts response lands; drives the table skeleton (brief 5.9). */
  loading = true;

  constructor(
    private ledger: LedgerService,
    private cs: CustomerService,
    private msg: MessageService,
    private confirm: ConfirmationService,
    public authService: AuthService
  ) {}

  ngOnInit(): void {
    this.load();
    this.cs.list().subscribe({ next: (c) => (this.customers = c), error: (e) => this.err(e) });
  }

  load(): void {
    this.ledger.listAccounts().subscribe({
      next: (a) => { this.accounts = a; this.loading = false; },
      error: (e) => { this.loading = false; this.err(e); }
    });
  }

  createAccount(): void {
    if (!this.newCustomerId) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Select a customer' });
      return;
    }
    this.ledger.createAccount(this.newCustomerId).subscribe({
      next: (a) => {
        this.msg.add({ severity: 'success', summary: 'Created', detail: 'Account ' + a.accountId });
        this.newCustomerId = null;
        this.load();
      },
      error: (e) => this.err(e)
    });
  }

  transfer(): void {
    if (!this.fromAccountId || !this.toAccountId) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Select both accounts' });
      return;
    }
    if (this.amount <= 0) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Amount must be positive' });
      return;
    }
    this.confirm.confirm({
      message: `Transfer ${this.amount} from ${this.fromAccountId} to ${this.toAccountId}?`,
      header: 'Confirm Transfer',
      icon: 'pi pi-exchange',
      accept: () => {
        const txId = crypto.randomUUID();
        this.ledger.transferSaga(this.fromAccountId!, this.toAccountId!, this.amount, txId).subscribe({
          next: (saga) => {
            this.lastSaga = saga;
            if (saga.status === 'COMPLETED') this.msg.add({ severity: 'success', summary: 'Transfer Complete', detail: 'Transfer completed' });
            else if (saga.status === 'COMPENSATING') this.msg.add({ severity: 'warn', summary: 'Transfer Compensated', detail: saga.errorMessage || 'Rolled back' });
            else this.msg.add({ severity: 'error', summary: 'Transfer Failed', detail: saga.errorMessage });
            this.load();
          },
          error: (e) => this.err(e)
        });
      }
    });
  }

  /**
   * Loads account detail. Each call is isolated so one failure does not wipe the
   * panel; if BOTH fail the account is almost certainly stale, so we tell the
   * user once and refresh the list instead of leaving the page unchanged.
   * (Note: /balance and /history return the same document server-side.)
   */
  selectAccount(id: string): void {
    let balanceFailed = false;
    let historyFailed = false;
    let firstError: any = null;

    forkJoin({
      // Silent: this method owns the recovery UX, so the global interceptor
      // must not add its own generic toast on top of ours.
      bal: this.ledger.balance(id, true).pipe(
        catchError((e) => {
          console.error('[Banking] account balance failed to load', e);
          balanceFailed = true;
          firstError = firstError ?? e;
          return of(null);
        })
      ),
      hist: this.ledger.history(id, true).pipe(
        catchError((e) => {
          console.error('[Banking] account history failed to load', e);
          historyFailed = true;
          firstError = firstError ?? e;
          return of(null);
        })
      )
    }).subscribe({
      next: ({ bal, hist }) => {
        if (balanceFailed && historyFailed) {
          this.detail = null;
          const status = firstError?.status ?? 0;
          this.msg.add({
            severity: 'warn',
            summary: 'Account unavailable',
            detail: status === 404
              ? 'Account not found — it may have been deleted. Refreshing list.'
              : 'Could not load this account. Refreshing list.'
          });
          this.load();
          return;
        }
        const base = hist ?? bal;
        this.detail = {
          accountId: base?.accountId ?? id,
          customerId: base?.customerId,
          holderName: base?.holderName,
          balance: bal?.balance ?? base?.balance ?? 0,
          history: hist?.history ?? []
        };
      },
      error: (e) => this.err(e)
    });
  }

  applyTransaction(type: 'CREDIT' | 'DEBIT'): void {
    if (!this.detail) return;
    if (this.txAmount <= 0) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Amount must be positive' });
      return;
    }
    const call = type === 'CREDIT' ? this.ledger.credit(this.detail.accountId, this.txAmount, type) : this.ledger.debit(this.detail.accountId, this.txAmount, type);
    call.subscribe({
      next: () => {
        this.msg.add({ severity: 'success', summary: type, detail: type + ' completed' });
        this.txAmount = 0;
        this.selectAccount(this.detail!.accountId);
        this.load();
      },
      error: (e) => this.err(e)
    });
  }

  severity(s: string | undefined): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (s?.toUpperCase()) {
      case 'COMPLETED': return 'success';
      case 'COMPENSATING': return 'warn';
      case 'FAILED': return 'danger';
      default: return 'secondary';
    }
  }

  eventSeverity(t: string | undefined): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (t) {
      case 'MONEY_CREDITED': return 'success';
      case 'MONEY_DEBITED': return 'danger';
      case 'ACCOUNT_CREATED': return 'info';
      default: return 'secondary';
    }
  }

  /** HTTP errors are surfaced once, globally, by error.interceptor.ts. */
  private err(e: unknown): void {
    console.error('[Banking] request failed', e);
  }
}
