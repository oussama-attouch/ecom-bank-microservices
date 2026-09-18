import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { InputNumberModule } from 'primeng/inputnumber';
import { TooltipModule } from 'primeng/tooltip';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ProductService } from '../../services/product.service';
import { Product } from '../../models';

import { EmptyStateComponent } from '../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../shared/table-skeleton/table-skeleton.component';

@Component({
  selector: 'app-products',
  standalone: true,
  imports: [CommonModule, FormsModule, TableModule, ButtonModule, InputTextModule, InputNumberModule, TooltipModule, EmptyStateComponent, TableSkeletonComponent],
  template: `
    <div class="page-head"><h2>Products</h2></div>

    <div class="form-row">
      <input pInputText name="name" [(ngModel)]="form.name" placeholder="Name">
      <p-inputNumber name="price" [(ngModel)]="form.price" placeholder="Price" mode="currency" currency="USD"></p-inputNumber>
      <p-inputNumber name="quantity" [(ngModel)]="form.quantity" placeholder="Quantity"></p-inputNumber>
      <p-button [label]="editingId != null ? 'Update' : 'Add Product'" icon="pi pi-check" (onClick)="save()"></p-button>
      <p-button *ngIf="editingId != null" label="Cancel" severity="secondary" icon="pi pi-times" (onClick)="reset()"></p-button>
    </div>

    @if (loading) {
      <app-table-skeleton [rows]="5" [cols]="5"></app-table-skeleton>
    } @else {
    <p-table #dt [value]="products" [paginator]="true" [rows]="10" [globalFilterFields]="['name']" responsiveLayout="scroll">
      <ng-template pTemplate="caption">
        <div class="flex justify-end">
          <input pInputText type="text" (input)="dt.filterGlobal($any($event.target).value, 'contains')" placeholder="Search">
        </div>
      </ng-template>
      <ng-template pTemplate="header">
        <tr>
          <th pSortableColumn="id">ID <p-sortIcon field="id"></p-sortIcon></th>
          <th pSortableColumn="name">Name <p-sortIcon field="name"></p-sortIcon></th>
          <th pSortableColumn="price">Price <p-sortIcon field="price"></p-sortIcon></th>
          <th pSortableColumn="quantity">Quantity <p-sortIcon field="quantity"></p-sortIcon></th>
          <th>Actions</th>
        </tr>
      </ng-template>
      <ng-template pTemplate="emptymessage">
        <tr>
          <td [attr.colspan]="5">
            <app-empty-state
              icon="pi pi-box"
              title="No products yet"
              hint="Add a product with the form above so it can be ordered and billed."></app-empty-state>
          </td>
        </tr>
      </ng-template>
      <ng-template pTemplate="body" let-p>
        <tr>
          <td class="mono-id">{{ p.id }}</td>
          <td>{{ p.name }}</td>
          <td>{{ p.price }}</td>
          <td>{{ p.quantity }}</td>
          <td>
            <p-button icon="pi pi-pencil" [text]="true" [rounded]="true" pTooltip="Edit" (onClick)="edit(p)"></p-button>
            <p-button icon="pi pi-trash" severity="danger" [text]="true" [rounded]="true" pTooltip="Delete" (onClick)="confirmDelete(p.id!)"></p-button>
          </td>
        </tr>
      </ng-template>
    </p-table>
    }
  `,
  styles: [`.flex { display: flex; } .justify-end { justify-content: flex-end; }`]
})
export class ProductsComponent implements OnInit {
  products: Product[] = [];
  form: Product = { name: '', price: 0, quantity: 0 };
  editingId: number | null = null;

  /** True until the first list response lands; drives the table skeleton (brief 5.9). */
  loading = true;

  constructor(
    private svc: ProductService,
    private msg: MessageService,
    private confirm: ConfirmationService
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.svc.list().subscribe({
      next: (p) => { this.products = p; this.loading = false; },
      error: (e) => { this.loading = false; this.err(e); }
    });
  }

  save(): void {
    const req = this.editingId != null ? this.svc.update(this.editingId, this.form) : this.svc.create(this.form);
    req.subscribe({
      next: () => {
        this.msg.add({ severity: 'success', summary: 'Saved', detail: 'Product saved' });
        this.reset();
      },
      error: (e) => this.err(e)
    });
  }

  edit(p: Product): void {
    this.editingId = p.id ?? null;
    this.form = { name: p.name, price: p.price, quantity: p.quantity };
  }

  confirmDelete(id: number): void {
    this.confirm.confirm({
      message: 'Delete this product?',
      header: 'Confirm Delete',
      icon: 'pi pi-exclamation-triangle',
      accept: () => {
        this.svc.delete(id).subscribe({
          next: () => {
            this.msg.add({ severity: 'success', summary: 'Deleted', detail: 'Product deleted' });
            this.load();
          },
          error: (e) => this.err(e)
        });
      }
    });
  }

  reset(): void {
    this.form = { name: '', price: 0, quantity: 0 };
    this.editingId = null;
    this.load();
  }

  /** HTTP errors are surfaced once, globally, by error.interceptor.ts. */
  private err(e: unknown): void {
    console.error('[Products] request failed', e);
  }
}
