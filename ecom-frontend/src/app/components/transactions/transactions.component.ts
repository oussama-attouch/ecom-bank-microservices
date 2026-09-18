import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { CardModule } from 'primeng/card';
import { ConfirmationService, MessageService } from 'primeng/api';
import { LedgerService } from '../../services/ledger.service';
import { Account } from '../../models';

@Component({
  selector: 'app-transactions',
  standalone: true,
  imports: [CommonModule, FormsModule, ButtonModule, InputNumberModule, InputTextModule, CardModule],
  template: `
    <div class="page-head"><h2>Transactions</h2></div>

    <p-card>
      <div class="form-row">
        <select name="type" [(ngModel)]="type">
          <option *ngFor="let t of types">{{ t }}</option>
        </select>

        <select *ngIf="type !== 'TRANSFER'" name="account" [(ngModel)]="accountId">
          <option [ngValue]="null" disabled>Select account</option>
          <option *ngFor="let a of accounts" [ngValue]="a.accountId">{{ a.accountId }}</option>
        </select>

        <select *ngIf="type === 'TRANSFER'" name="from" [(ngModel)]="fromAccountId">
          <option [ngValue]="null" disabled>From account</option>
          <option *ngFor="let a of accounts" [ngValue]="a.accountId">{{ a.accountId }}</option>
        </select>
        <select *ngIf="type === 'TRANSFER'" name="to" [(ngModel)]="toAccountId">
          <option [ngValue]="null" disabled>To account</option>
          <option *ngFor="let a of accounts" [ngValue]="a.accountId">{{ a.accountId }}</option>
        </select>

        <p-inputNumber name="amount" [(ngModel)]="amount" placeholder="Amount" mode="currency" currency="USD"></p-inputNumber>
        <input pInputText name="description" [(ngModel)]="description" placeholder="Description">
        <p-button label="Post Transaction" icon="pi pi-check" (onClick)="submit()"></p-button>
      </div>
    </p-card>

    <p class="mt-2" style="color: var(--text-muted)">
      CREDIT / DEBIT move money between a customer account and CASH_ACCOUNT. TRANSFER moves between two customer accounts.
    </p>
  `
})
export class TransactionsComponent implements OnInit {
  accounts: Account[] = [];
  type = 'CREDIT';
  accountId: string | null = null;
  fromAccountId: string | null = null;
  toAccountId: string | null = null;
  amount = 0;
  description = '';
  types = ['CREDIT', 'DEBIT', 'TRANSFER'];

  constructor(
    private ledger: LedgerService,
    private msg: MessageService,
    private confirm: ConfirmationService
  ) {}

  ngOnInit(): void {
    this.ledger.listAccounts().subscribe({ next: (a) => (this.accounts = a) });
  }

  submit(): void {
    if (this.type === 'TRANSFER' && (!this.fromAccountId || !this.toAccountId)) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Select both accounts' });
      return;
    }
    if (this.type !== 'TRANSFER' && !this.accountId) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Select an account' });
      return;
    }
    if (this.amount <= 0) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Amount must be positive' });
      return;
    }
    this.confirm.confirm({
      message: `Post ${this.type} transaction of ${this.amount}?`,
      header: 'Confirm Transaction',
      icon: 'pi pi-arrow-right-arrow-left',
      accept: () => this.doSubmit()
    });
  }

  private doSubmit(): void {
    const call = this.type === 'CREDIT'
      ? this.ledger.credit(this.accountId!, this.amount, this.description)
      : this.type === 'DEBIT'
        ? this.ledger.debit(this.accountId!, this.amount, this.description)
        : this.ledger.transfer(this.fromAccountId!, this.toAccountId!, this.amount);

    call.subscribe({
      next: () => {
        this.msg.add({ severity: 'success', summary: 'Posted', detail: 'Transaction posted' });
        this.amount = 0;
        this.description = '';
      },
      // HTTP errors are surfaced once, globally, by error.interceptor.ts.
      error: (e) => console.error('[Transactions] request failed', e)
    });
  }
}
