import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { InputTextModule } from 'primeng/inputtext';
import { CardModule } from 'primeng/card';
import { JournalService } from '../../services/journal.service';
import { AccountStatement, JournalEntry, TrialBalance } from '../../models';
import { EmptyStateComponent } from '../shared/empty-state/empty-state.component';

@Component({
  selector: 'app-journal',
  standalone: true,
  imports: [CommonModule, FormsModule, TableModule, ButtonModule, TagModule, InputTextModule, CardModule, EmptyStateComponent],
  template: `
    <div class="page-head"><h2>Journal</h2></div>

    <div class="grid mb-2">
      <p-card><div class="stat"><div class="num">{{ trial?.totalDebits ?? 0 }}</div><div>Total Debits</div></div></p-card>
      <p-card><div class="stat"><div class="num">{{ trial?.totalCredits ?? 0 }}</div><div>Total Credits</div></div></p-card>
      <p-card>
        <div class="stat">
          <p-tag [value]="trial?.balanced ? 'BALANCED' : 'UNBALANCED'" [severity]="trial?.balanced ? 'success' : 'danger'"></p-tag>
          <div>Trial Balance</div>
        </div>
      </p-card>
    </div>

    <div class="form-row">
      <input pInputText name="accountId" [(ngModel)]="statementAccountId" placeholder="Account ID">
      <p-button label="Statement" icon="pi pi-list" severity="secondary" [outlined]="true" (onClick)="statementFor()"></p-button>
    </div>

    <div *ngIf="statement" class="mb-2">
      <h3>Statement for {{ statement.accountId }} — final balance {{ statement.finalBalance }}</h3>
      <p-table [value]="statement.lines || []" responsiveLayout="scroll">
        <ng-template pTemplate="header">
          <tr><th>Debit</th><th>Credit</th><th>Amount</th><th>Description</th><th>Running</th></tr>
        </ng-template>
        <ng-template pTemplate="body" let-l>
          <tr>
            <td>{{ l.entry?.debitAccountId }}</td>
            <td>{{ l.entry?.creditAccountId }}</td>
            <td>{{ l.entry?.amount }}</td>
            <td>{{ l.entry?.description }}</td>
            <td>{{ l.runningBalance }}</td>
          </tr>
        </ng-template>
      </p-table>
    </div>

    <p-table #dt [value]="entries" [paginator]="true" [rows]="15" [globalFilterFields]="['transactionId','debitAccountId','creditAccountId','description']" responsiveLayout="scroll">
      <ng-template pTemplate="caption">
        <div class="flex justify-end">
          <input pInputText type="text" (input)="dt.filterGlobal($any($event.target).value, 'contains')" placeholder="Search">
        </div>
      </ng-template>
      <ng-template pTemplate="header">
        <tr>
          <th pSortableColumn="transactionId">Transaction <p-sortIcon field="transactionId"></p-sortIcon></th>
          <th>Debit</th>
          <th>Credit</th>
          <th pSortableColumn="amount">Amount <p-sortIcon field="amount"></p-sortIcon></th>
          <th>Description</th>
        </tr>
      </ng-template>
      <ng-template pTemplate="emptymessage">
        <tr>
          <td [attr.colspan]="5">
            <app-empty-state
              icon="pi pi-book"
              title="No journal entries"
              hint="Every money movement posts a double-entry pair here."></app-empty-state>
          </td>
        </tr>
      </ng-template>
      <ng-template pTemplate="body" let-e>
        <tr>
          <td class="mono-id">{{ e.transactionId }}</td>
          <td class="mono-id">{{ e.debitAccountId }}</td>
          <td class="mono-id">{{ e.creditAccountId }}</td>
          <td>{{ e.amount }}</td>
          <td>{{ e.description }}</td>
        </tr>
      </ng-template>
    </p-table>
  `,
  styles: [`
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 16px; }
    .stat { text-align: center; }
    .num { font-size: 1.5rem; font-weight: 700; color: var(--accent); }
    .flex { display: flex; } .justify-end { justify-content: flex-end; }
  `]
})
export class JournalComponent implements OnInit {
  entries: JournalEntry[] = [];
  trial: TrialBalance | null = null;
  statement: AccountStatement | null = null;
  statementAccountId = '';

  constructor(private journal: JournalService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.journal.entries().subscribe({ next: (e) => (this.entries = e) });
    this.journal.trialBalance().subscribe({ next: (t) => (this.trial = t) });
  }

  statementFor(): void {
    if (!this.statementAccountId.trim()) return;
    this.journal.statement(this.statementAccountId.trim()).subscribe({ next: (s) => (this.statement = s) });
  }
}
