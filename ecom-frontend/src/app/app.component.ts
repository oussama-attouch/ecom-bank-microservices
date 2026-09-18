import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { ToastModule } from 'primeng/toast';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { TagModule } from 'primeng/tag';
import { LoadingService } from './services/loading.service';
import { AuthService } from './services/auth.service';
import { NotificationPanelComponent } from './components/notifications/notification-panel.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    ButtonModule,
    TooltipModule,
    ToastModule,
    ConfirmDialogModule,
    ProgressSpinnerModule,
    TagModule,
    NotificationPanelComponent
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit {
  collapsed = false;
  dark = false;
  /** True once the content area has scrolled past 20px — raises the topbar shadow (brief 5.2). */
  scrolled = false;
  /**
   * The auth routes render full-bleed: the sidebar and topbar belong to the
   * signed-in shell, and the login hero needs the whole viewport (brief 5.7).
   */
  isAuthRoute = false;
  private readonly router = inject(Router);
  loading$ = inject(LoadingService).loading$;
  authService = inject(AuthService);
  isAuthenticated$ = this.authService.isAuthenticated$;
  /**
   * Bound as an observable rather than read through `getUserRoles()`: the
   * method reads `BehaviorSubject.value`, so on a fresh load the template
   * rendered before the roles arrived (they appeared a beat late). The async
   * pipe re-renders when the subject emits.
   */
  userRoles$ = this.authService.userRoles$;

  menuGroups: MenuGroup[] = [
    {
      header: 'Overview',
      items: [
        { label: 'Dashboard', icon: 'pi pi-home', route: '/dashboard', exact: true }
      ]
    },
    {
      header: 'Operations',
      items: [
        { label: 'Customers', icon: 'pi pi-users', route: '/customers' },
        { label: 'Products', icon: 'pi pi-box', route: '/products' },
        { label: 'Orders', icon: 'pi pi-shopping-cart', route: '/orders' },
        { label: 'Bills', icon: 'pi pi-receipt', route: '/bills' }
      ]
    },
    {
      header: 'Banking',
      items: [
        { label: 'Accounts', icon: 'pi pi-wallet', route: '/banking' },
        { label: 'New Transfer', icon: 'pi pi-exchange', route: '/banking/transfer-wizard', roles: ['TELLER', 'MANAGER'] },
        { label: 'Sagas', icon: 'pi pi-sitemap', route: '/transactions/sagas' },
        { label: 'Journal', icon: 'pi pi-book', route: '/transactions/journal' }
      ]
    }
  ];

  hasRequiredRole(roles?: string[]): boolean {
    if (!roles || roles.length === 0) return true;
    const userRoles = this.authService.getUserRoles();
    return roles.some((r) => userRoles.includes(r));
  }

  getRoleSeverity(role: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (role?.toUpperCase()) {
      case 'TELLER': return 'info';
      case 'MANAGER': return 'success';
      case 'AUDITOR': return 'warn';
      default: return 'secondary';
    }
  }

  ngOnInit(): void {
    this.authService.checkAuth().subscribe();
    this.dark = localStorage.getItem('app-dark') === 'true';
    this.applyDark();
    this.router.events.subscribe((e) => {
      if (e instanceof NavigationEnd) {
        this.isAuthRoute = e.urlAfterRedirects.startsWith('/login') || e.urlAfterRedirects.startsWith('/callback');
      }
    });
  }

  toggleDark(): void {
    this.dark = !this.dark;
    localStorage.setItem('app-dark', String(this.dark));
    this.applyDark();
  }

  /** Sticky topbar shadow: .main is the scroll container (brief 5.2). */
  onScroll(event: Event): void {
    this.scrolled = (event.target as HTMLElement).scrollTop > 20;
  }

  login(): void {
    this.authService.login();
  }

  logout(): void {
    this.authService.logout();
  }

  private applyDark(): void {
    if (this.dark) {
      document.documentElement.classList.add('app-dark');
    } else {
      document.documentElement.classList.remove('app-dark');
    }
  }
}

interface MenuItem { label: string; icon: string; route: string; exact?: boolean; roles?: string[]; }
interface MenuGroup { header: string; items: MenuItem[]; }
