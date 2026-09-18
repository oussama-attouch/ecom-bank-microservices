import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { ConfirmationService, MessageService } from 'primeng/api';
import { OrderService } from '../../services/order.service';
import { CustomerService } from '../../services/customer.service';
import { Customer, Order } from '../../models';
import { EmptyStateComponent } from '../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../shared/table-skeleton/table-skeleton.component';

@Component({
  selector: 'app-orders',
  standalone: true,
  imports: [CommonModule, FormsModule, TableModule, ButtonModule, TagModule, TooltipModule, EmptyStateComponent, TableSkeletonComponent],
  template: `
    <div class="page-head"><h2>Orders</h2></div>

    <div class="form-row">
      <select name="customer" [(ngModel)]="newCustomerId">
        <option [ngValue]="null" disabled>Select customer</option>
        <option *ngFor="let c of customers" [ngValue]="c.id">{{ c.name }}</option>
      </select>
      <select name="status" [(ngModel)]="newStatus">
        <option *ngFor="let s of statuses">{{ s }}</option>
      </select>
      <p-button label="Create Order" icon="pi pi-plus" (onClick)="createOrder()"></p-button>
    </div>

    @if (loading) {
      <app-table-skeleton [rows]="5" [cols]="5"></app-table-skeleton>
    } @else {
    <p-table #dt [value]="orders" [paginator]="true" [rows]="10" [globalFilterFields]="['status','customerId']" responsiveLayout="scroll">
      <ng-template pTemplate="caption">
        <div class="flex justify-end">
          <input type="text" (input)="dt.filterGlobal($any($event.target).value, 'contains')" placeholder="Search">
        </div>
      </ng-template>
      <ng-template pTemplate="header">
        <tr>
          <th pSortableColumn="id">ID <p-sortIcon field="id"></p-sortIcon></th>
          <th>Created</th>
          <th pSortableColumn="status">Status <p-sortIcon field="status"></p-sortIcon></th>
          <th>Customer</th>
          <th>Actions</th>
        </tr>
      </ng-template>
      <ng-template pTemplate="emptymessage">
        <tr>
          <td [attr.colspan]="5">
            <app-empty-state
              icon="pi pi-shopping-cart"
              title="No orders yet"
              hint="Create an order and it will show up here with its status."></app-empty-state>
          </td>
        </tr>
      </ng-template>
      <ng-template pTemplate="body" let-o>
        <tr>
          <td class="mono-id">{{ o.id }}</td>
          <td>{{ o.createdAt | date:'MMM d, y HH:mm' }}</td>
          <td><p-tag [value]="o.status" [severity]="severity(o.status)"></p-tag></td>
          <td>{{ o.customerId }}</td>
          <td><p-button icon="pi pi-eye" [text]="true" [rounded]="true" pTooltip="View" (onClick)="view(o.id!)"></p-button></td>
        </tr>
      </ng-template>
    </p-table>
    }

    <div *ngIf="fullOrder" class="mt-2">
      <p-table [value]="fullOrder.productItems || []" responsiveLayout="scroll">
        <ng-template pTemplate="caption">
          <div class="flex" style="justify-content:space-between;align-items:center">
            <strong>Order #{{ fullOrder.id }} — {{ fullOrder.status }} (customer {{ fullOrder.customer?.name }})</strong>
            <p-button icon="pi pi-times" [text]="true" [rounded]="true" (onClick)="fullOrder = null"></p-button>
          </div>
        </ng-template>
        <ng-template pTemplate="header">
          <tr><th>Product</th><th>Qty</th><th>Price</th><th>Discount</th></tr>
        </ng-template>
        <ng-template pTemplate="body" let-it>
          <tr>
            <td>{{ it.product?.name ?? it.productId }}</td>
            <td>{{ it.quantity }}</td>
            <td>{{ it.price }}</td>
            <td>{{ it.discount }}</td>
          </tr>
        </ng-template>
      </p-table>
    </div>
  `,
  styles: [`.flex { display: flex; } .justify-end { justify-content: flex-end; }`]
})
export class OrdersComponent implements OnInit {
  orders: Order[] = [];
  customers: Customer[] = [];
  fullOrder: Order | null = null;
  newCustomerId: number | null = null;
  newStatus = 'PENDING';
  statuses = ['PENDING', 'CREATED', 'DELIVERED', 'CANCELED'];

  /** True until the first orders response lands; drives the table skeleton (brief 5.9). */
  loading = true;

  constructor(
    private os: OrderService,
    private cs: CustomerService,
    private msg: MessageService,
    private confirm: ConfirmationService
  ) {}

  ngOnInit(): void {
    this.load();
    this.cs.list().subscribe({ next: (c) => (this.customers = c), error: (e) => this.err(e) });
  }

  load(): void {
    this.os.list().subscribe({
      next: (o) => { this.orders = o; this.loading = false; },
      error: (e) => { this.loading = false; this.err(e); }
    });
  }

  createOrder(): void {
    if (!this.newCustomerId) {
      this.msg.add({ severity: 'warn', summary: 'Warning', detail: 'Select a customer' });
      return;
    }
    this.confirm.confirm({
      message: `Create a ${this.newStatus} order for this customer?`,
      header: 'Confirm Order',
      icon: 'pi pi-shopping-cart',
      accept: () => {
        this.os.create({ customerId: this.newCustomerId!, status: this.newStatus, createdAt: new Date().toISOString() }).subscribe({
          next: () => {
            this.msg.add({ severity: 'success', summary: 'Created', detail: 'Order created' });
            this.newCustomerId = null;
            this.load();
          },
          error: (e) => this.err(e)
        });
      }
    });
  }

  view(id: number): void {
    this.os.fullOrder(id).subscribe({ next: (o) => (this.fullOrder = o), error: (e) => this.err(e) });
  }

  severity(s: string | undefined): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (s?.toUpperCase()) {
      case 'COMPLETED':
      case 'DELIVERED':
        return 'success';
      case 'PENDING':
      case 'CREATED':
        return 'info';
      case 'COMPENSATING':
        return 'warn';
      case 'FAILED':
      case 'CANCELED':
        return 'danger';
      default:
        return 'secondary';
    }
  }

  /** HTTP errors are surfaced once, globally, by error.interceptor.ts. */
  private err(e: unknown): void {
    console.error('[Orders] request failed', e);
  }
}
