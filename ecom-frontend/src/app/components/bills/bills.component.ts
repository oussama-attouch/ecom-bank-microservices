import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { ConfirmationService, MessageService } from 'primeng/api';
import { BillingService } from '../../services/billing.service';
import { OrderService } from '../../services/order.service';
import { Bill, Order } from '../../models';
import { EmptyStateComponent } from '../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../shared/table-skeleton/table-skeleton.component';

@Component({
  selector: 'app-bills',
  standalone: true,
  imports: [CommonModule, FormsModule, TableModule, ButtonModule, TagModule, EmptyStateComponent, TableSkeletonComponent],
  template: `
    <div class="page-head"><h2>Bills</h2></div>

    <div class="form-row">
      <select name="order" [(ngModel)]="selectedOrderId">
        <option [ngValue]="null" disabled>Select order</option>
        <option *ngFor="let o of orders" [ngValue]="o.id">Order #{{ o.id }} (customer {{ o.customerId }})</option>
      </select>
      <p-button label="Generate Bill" icon="pi pi-file" severity="secondary" [outlined]="true" (onClick)="generate()"></p-button>
    </div>

    @if (loading) {
      <app-table-skeleton [rows]="5" [cols]="4"></app-table-skeleton>
    } @else {
    <p-table #dt [value]="bills" [paginator]="true" [rows]="10" [globalFilterFields]="['id', 'customerId']" responsiveLayout="scroll">
      <ng-template pTemplate="caption">
        <div class="flex justify-end">
          <input type="text" (input)="dt.filterGlobal($any($event.target).value, 'contains')" placeholder="Search">
        </div>
      </ng-template>
      <ng-template pTemplate="header">
        <tr>
          <th pSortableColumn="id">ID <p-sortIcon field="id"></p-sortIcon></th>
          <th>Date</th>
          <th>Customer</th>
          <th>Items</th>
        </tr>
      </ng-template>
      <ng-template pTemplate="emptymessage">
        <tr>
          <td [attr.colspan]="4">
            <app-empty-state
              icon="pi pi-receipt"
              title="No bills yet"
              hint="Generate a bill from one of your orders to see it here."
              ctaLabel="Generate Bill"
              (ctaClick)="generate()"></app-empty-state>
          </td>
        </tr>
      </ng-template>
      <ng-template pTemplate="body" let-b>
        <tr>
          <td class="mono-id">{{ b.id }}</td>
          <td>{{ b.billingDate | date:'MMM d, y HH:mm' }}</td>
          <td>{{ b.customer?.name ?? b.customerId }}</td>
          <td><p-tag [value]="(b.productItems?.length ?? 0).toString()" severity="info"></p-tag></td>
        </tr>
      </ng-template>
    </p-table>
    }
  `,
  styles: [`.flex { display: flex; } .justify-end { justify-content: flex-end; }`]
})
export class BillsComponent implements OnInit {
  bills: Bill[] = [];
  orders: Order[] = [];
  selectedOrderId: number | null = null;

  /** True until the first bills response lands; drives the table skeleton (brief 5.9). */
  loading = true;

  constructor(
    private bs: BillingService,
    private os: OrderService,
    private msg: MessageService,
    private confirm: ConfirmationService
  ) {}

  ngOnInit(): void {
    this.load();
    this.os.list().subscribe({ next: (o) => (this.orders = o), error: (e) => this.err(e) });
  }

  load(): void {
    this.bs.list().subscribe({
      next: (b) => { this.bills = b; this.loading = false; },
      error: (e) => { this.loading = false; this.err(e); }
    });
  }

  generate(): void {
    if (this.selectedOrderId == null) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Select an order' });
      return;
    }
    const order = this.orders.find((o) => o.id === Number(this.selectedOrderId));
    if (!order || order.customerId == null) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Could not determine customer' });
      return;
    }
    this.confirm.confirm({
      message: `Generate a bill for order #${order.id}?`,
      header: 'Generate Bill',
      icon: 'pi pi-file',
      accept: () => {
        this.bs.generate(order.customerId!).subscribe({
          next: () => {
            this.msg.add({ severity: 'success', summary: 'Generated', detail: 'Bill generated' });
            this.load();
          },
          error: (e) => this.err(e)
        });
      }
    });
  }

  /** HTTP errors are surfaced once, globally, by error.interceptor.ts. */
  private err(e: unknown): void {
    console.error('[Bills] request failed', e);
  }
}
