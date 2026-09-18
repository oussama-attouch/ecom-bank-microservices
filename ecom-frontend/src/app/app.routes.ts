import { Routes } from '@angular/router';
import { Component } from '@angular/core';
import { DashboardComponent } from './components/dashboard/dashboard.component';
import { CustomersComponent } from './components/customers/customers.component';
import { ProductsComponent } from './components/products/products.component';
import { OrdersComponent } from './components/orders/orders.component';
import { BillsComponent } from './components/bills/bills.component';
import { BankingComponent } from './components/banking/banking.component';
import { TransferWizardComponent } from './components/banking/transfer-wizard.component';
import { TransactionsComponent } from './components/transactions/transactions.component';
import { SagaListComponent } from './components/sagas/saga-list.component';
import { SagaInspectorComponent } from './components/sagas/saga-inspector.component';
import { JournalExplorerComponent } from './components/journal/journal-explorer.component';
import { StatementComponent } from './components/statement/statement.component';
import { JournalComponent } from './components/journal/journal.component';
import { LoginComponent } from './components/login/login.component';
import { authGuard } from './guards/auth.guard';
import { roleGuard } from './guards/role.guard';

@Component({
  selector: 'app-callback',
  standalone: true,
  template: `
    <div style="display:flex;align-items:center;justify-content:center;height:80vh;flex-direction:column;gap:1rem;">
      <i class="pi pi-spinner pi-spin" style="font-size:2rem;"></i>
      <p>Signing you in...</p>
    </div>
  `
})
export class CallbackComponent {}

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'callback', component: CallbackComponent },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      { path: 'dashboard', component: DashboardComponent },
      { path: 'customers', component: CustomersComponent },
      { path: 'products', component: ProductsComponent },
      { path: 'orders', component: OrdersComponent },
      { path: 'bills', component: BillsComponent },
      { path: 'banking', component: BankingComponent },
      { path: 'banking/transfer-wizard', component: TransferWizardComponent, canActivate: [roleGuard(['TELLER', 'MANAGER'])] },
      { path: 'transactions', component: TransactionsComponent },
      { path: 'transactions/sagas', component: SagaListComponent },
      { path: 'transactions/sagas/:id', component: SagaInspectorComponent },
      { path: 'transactions/journal', component: JournalExplorerComponent },
      { path: 'accounts/:id/statement', component: StatementComponent },
      { path: 'journal', component: JournalComponent },
    ]
  },
  { path: '**', redirectTo: 'dashboard' }
];
