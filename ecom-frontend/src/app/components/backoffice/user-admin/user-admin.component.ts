import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { DialogModule } from 'primeng/dialog';
import { CheckboxModule } from 'primeng/checkbox';
import { TagModule } from 'primeng/tag';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService } from 'primeng/api';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { BackofficeService, BackofficeUser } from '../../../services/backoffice.service';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../../shared/table-skeleton/table-skeleton.component';

/**
 * Backoffice access administration: list users, edit their realm roles, and
 * enable or disable an account.
 *
 * <p>Manager-only twice over — the route carries `roleGuard(['MANAGER'])`, and
 * the gateway answers 403 to anyone else — so this page never renders for a
 * teller. The 403 branch below exists because a stale token can outlive a role
 * change (the same 15-minute window the save dialog warns about), and "you do
 * not hold MANAGER" is a better explanation than a generic error.
 *
 * <p>The list request is marked silent: "the gate is disabled on the gateway"
 * (404) and "you are not a manager" (403) are page states, not incidents, and a
 * global error toast would say neither.
 */
@Component({
  selector: 'app-user-admin',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    TableModule,
    ButtonModule,
    InputTextModule,
    DialogModule,
    CheckboxModule,
    TagModule,
    ToggleSwitchModule,
    TooltipModule,
    EmptyStateComponent,
    TableSkeletonComponent
  ],
  templateUrl: './user-admin.component.html',
  styleUrl: './user-admin.component.scss'
})
export class UserAdminComponent implements OnInit, OnDestroy {
  /**
   * The roles this screen may grant, in the order the API returns them. The
   * server rejects anything else, and never touches the roles it does not
   * manage (`default-roles-ecom-bank`, `offline_access`, …).
   */
  readonly roles = ['TELLER', 'MANAGER', 'AUDITOR'];

  users: BackofficeUser[] = [];
  total = 0;
  loading = true;

  /** Non-null when the whole page is unavailable (gate off, not a manager, …). */
  gateError: string | null = null;

  /** Debounced at 300 ms by the pipe below, per the spec. */
  readonly search = new FormControl('', { nonNullable: true });

  // Dialog state
  editing: BackofficeUser | null = null;
  dialogVisible = false;
  draftRoles = new Set<string>();
  saving = false;

  /** Id of the user whose switch is mid-flight, so it can be disabled meanwhile. */
  togglingId: string | null = null;

  private readonly destroy$ = new Subject<void>();
  private readonly svc = inject(BackofficeService);
  private readonly msg = inject(MessageService);

  ngOnInit(): void {
    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.load());
    this.load();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.loading = true;
    this.gateError = null;
    this.svc.list(this.search.value).subscribe({
      next: (page) => {
        this.users = page.users;
        this.total = page.total;
        this.loading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.users = [];
        this.total = 0;
        this.loading = false;
        this.gateError = this.describeFailure(err);
      }
    });
  }

  // ------------------------------------------------------------------
  // Row rendering
  // ------------------------------------------------------------------

  fullName(user: BackofficeUser): string {
    const name = [user.firstName, user.lastName].filter((part) => !!part?.trim()).join(' ');
    return name || '—';
  }

  roleSeverity(role: string): 'success' | 'info' | 'warn' | 'secondary' {
    switch (role?.toUpperCase()) {
      case 'MANAGER': return 'success';
      case 'TELLER': return 'info';
      case 'AUDITOR': return 'warn';
      default: return 'secondary';
    }
  }

  // ------------------------------------------------------------------
  // Role editor
  // ------------------------------------------------------------------

  openRoles(user: BackofficeUser): void {
    this.editing = user;
    this.draftRoles = new Set(user.roles);
    this.dialogVisible = true;
  }

  closeRoles(): void {
    this.dialogVisible = false;
    this.editing = null;
    this.saving = false;
  }

  hasDraft(role: string): boolean {
    return this.draftRoles.has(role);
  }

  setDraft(role: string, checked: boolean): void {
    if (checked) {
      this.draftRoles.add(role);
    } else {
      this.draftRoles.delete(role);
    }
  }

  saveRoles(): void {
    const target = this.editing;
    if (!target || this.saving) {
      return;
    }
    this.saving = true;
    // Emitted in canonical order so the request is stable and the response
    // ordering is not the only thing keeping the chips tidy.
    const selected = this.roles.filter((role) => this.draftRoles.has(role));

    this.svc.replaceRoles(target.id, selected).subscribe({
      next: (updated) => {
        this.replaceRow(updated);
        // MessageService is the single owner of toasts: the success path adds
        // one; failures are toasted by error.interceptor.ts.
        this.msg.add({
          severity: 'success',
          summary: 'Roles updated',
          detail: `${updated.username}: ${updated.roles.length ? updated.roles.join(', ') : 'no roles'}`,
          life: 4000
        });
        this.closeRoles();
      },
      error: () => {
        // Keep the dialog open and the draft intact so the operator can adjust —
        // the interceptor has already shown the server's reason (409 for the
        // self-demotion and last-manager guards).
        this.saving = false;
      }
    });
  }

  // ------------------------------------------------------------------
  // Enabled switch
  // ------------------------------------------------------------------

  toggleEnabled(user: BackofficeUser, enabled: boolean): void {
    if (this.togglingId) {
      return;
    }
    this.togglingId = user.id;
    this.svc.setEnabled(user.id, enabled).subscribe({
      next: (updated) => {
        this.replaceRow(updated);
        this.togglingId = null;
        this.msg.add({
          severity: 'success',
          summary: enabled ? 'Account enabled' : 'Account disabled',
          detail: `${updated.username} ${enabled ? 'can' : 'cannot'} sign in`,
          life: 4000
        });
      },
      error: () => {
        this.togglingId = null;
        // The switch is bound one-way from the row, so re-rendering the array is
        // what puts the handle back where it was.
        this.users = [...this.users];
      }
    });
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private replaceRow(updated: BackofficeUser): void {
    this.users = this.users.map((user) => (user.id === updated.id ? updated : user));
  }

  /**
   * Turns a failed list call into the one sentence that explains it.
   *
   * 404 is the important one: with `backoffice.users.enabled=false` the
   * controller bean does not exist, so the gateway has no handler and answers
   * 404. That is a configuration, not an outage, and the operator needs to be
   * told which.
   */
  private describeFailure(err: HttpErrorResponse): string {
    if (err.status === 404) {
      return 'Backoffice gate is disabled on the gateway.';
    }
    if (err.status === 403) {
      return 'You do not hold MANAGER role.';
    }
    if (err.status === 0) {
      return 'Cannot reach the gateway.';
    }
    const backendMessage =
      err.error && typeof err.error === 'object' && typeof err.error.error === 'string'
        ? err.error.error.trim()
        : '';
    return backendMessage || 'The user list could not be loaded.';
  }
}
