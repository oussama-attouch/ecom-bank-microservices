import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TooltipModule } from 'primeng/tooltip';
import { ConfirmationService, MessageService } from 'primeng/api';
import { map } from 'rxjs';
import { CustomerService } from '../../services/customer.service';
import { AuthService } from '../../services/auth.service';
import { Customer } from '../../models';
import { EmptyStateComponent } from '../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../shared/table-skeleton/table-skeleton.component';

@Component({
  selector: 'app-customers',
  standalone: true,
  imports: [CommonModule, FormsModule, TableModule, ButtonModule, InputTextModule, TooltipModule, EmptyStateComponent, TableSkeletonComponent],
  template: `
    <div class="page-head"><h2>Customers</h2></div>

    <!-- The gateway answers 403 to POST/PUT/DELETE on customer-service for
         anyone without MANAGER, so the write form is rendered only for a
         manager. Reads stay open: every signed-in operator sees the list. -->
    @if (canWrite$ | async) {
      <div class="form-row">
        <input pInputText name="name" [(ngModel)]="form.name" placeholder="Name">
        <input pInputText name="email" [(ngModel)]="form.email" placeholder="Email">
        <p-button [label]="editingId != null ? 'Update' : 'Add Customer'" icon="pi pi-check" (onClick)="save()"></p-button>
        <p-button *ngIf="editingId != null" label="Cancel" severity="secondary" icon="pi pi-times" (onClick)="reset()"></p-button>
      </div>
    }

    @if (loading) {
      <app-table-skeleton [rows]="5" [cols]="4"></app-table-skeleton>
    } @else {
    <p-table #dt [value]="customers" [paginator]="true" [rows]="10" [globalFilterFields]="['name','email']" responsiveLayout="scroll">
      <ng-template pTemplate="caption">
        <div class="flex justify-end">
          <input pInputText type="text" (input)="dt.filterGlobal($any($event.target).value, 'contains')" placeholder="Search">
        </div>
      </ng-template>
      <ng-template pTemplate="header">
        <tr>
          <th pSortableColumn="id">ID <p-sortIcon field="id"></p-sortIcon></th>
          <th pSortableColumn="name">Name <p-sortIcon field="name"></p-sortIcon></th>
          <th pSortableColumn="email">Email <p-sortIcon field="email"></p-sortIcon></th>
          <th *ngIf="canWrite$ | async">Actions</th>
        </tr>
      </ng-template>
      <ng-template pTemplate="body" let-c>
        <tr>
          <td class="mono-id">{{ c.id }}</td>
          <td>{{ c.name }}</td>
          <td>{{ c.email }}</td>
          <td *ngIf="canWrite$ | async">
            <p-button icon="pi pi-pencil" [text]="true" [rounded]="true" pTooltip="Edit" (onClick)="edit(c)"></p-button>
            <p-button icon="pi pi-trash" severity="danger" [text]="true" [rounded]="true" pTooltip="Delete" (onClick)="confirmDelete(c.id!)"></p-button>
          </td>
        </tr>
      </ng-template>
      <ng-template pTemplate="emptymessage">
        <tr>
          <td [attr.colspan]="(canWrite$ | async) ? 4 : 3">
            <app-empty-state
              icon="pi pi-users"
              title="No customers yet"
              hint="Add your first customer with the form above to start taking orders."></app-empty-state>
          </td>
        </tr>
      </ng-template>
    </p-table>
    }
  `,
  styles: [`.flex { display: flex; } .justify-end { justify-content: flex-end; }`]
})
export class CustomersComponent implements OnInit {
  private readonly auth = inject(AuthService);

  /**
   * True only for a MANAGER. Driven by the roles observable rather than a
   * one-off `hasRole()` read so the buttons appear when the roles arrive on a
   * hard refresh, instead of depending on a later change-detection pass.
   */
  readonly canWrite$ = this.auth.userRoles$.pipe(map((roles) => roles.includes('MANAGER')));

  customers: Customer[] = [];
  form: Customer = { name: '', email: '' };
  editingId: number | null = null;

  /** True until the first list response lands; drives the table skeleton (brief 5.9). */
  loading = true;

  constructor(
    private svc: CustomerService,
    private msg: MessageService,
    private confirm: ConfirmationService
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.svc.list().subscribe({
      next: (c) => { this.customers = c; this.loading = false; },
      error: (e) => { this.loading = false; this.err(e); }
    });
  }

  save(): void {
    const req = this.editingId != null ? this.svc.update(this.editingId, this.form) : this.svc.create(this.form);
    req.subscribe({
      next: () => {
        this.msg.add({ severity: 'success', summary: 'Saved', detail: 'Customer saved' });
        this.reset();
      },
      error: (e) => this.err(e)
    });
  }

  edit(c: Customer): void {
    this.editingId = c.id ?? null;
    this.form = { name: c.name, email: c.email };
  }

  confirmDelete(id: number): void {
    this.confirm.confirm({
      message: 'Delete this customer?',
      header: 'Confirm Delete',
      icon: 'pi pi-exclamation-triangle',
      accept: () => {
        this.svc.delete(id).subscribe({
          next: () => {
            this.msg.add({ severity: 'success', summary: 'Deleted', detail: 'Customer deleted' });
            this.load();
          },
          error: (e) => this.err(e)
        });
      }
    });
  }

  reset(): void {
    this.form = { name: '', email: '' };
    this.editingId = null;
    this.load();
  }

  /** HTTP errors are surfaced once, globally, by error.interceptor.ts. */
  private err(e: unknown): void {
    console.error('[Customers] request failed', e);
  }
}
