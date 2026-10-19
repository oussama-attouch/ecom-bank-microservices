import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Observable, of, throwError } from 'rxjs';
import { UserAdminComponent } from './user-admin.component';
import { BackofficeService, BackofficeUserPage } from '../../../services/backoffice.service';

/**
 * The three states that mean different things to an operator, plus one happy
 * path so the table is known to render. Kept to the failure contract rather than
 * the styling: the page's job is to explain *why* it is empty, and the three
 * failures have three different answers.
 */
describe('UserAdminComponent', () => {
  const EMPTY_PAGE: BackofficeUserPage = { total: 0, first: 0, max: 50, users: [] };

  class FakeBackofficeService {
    response: Observable<BackofficeUserPage> = of(EMPTY_PAGE);

    list(): Observable<BackofficeUserPage> {
      return this.response;
    }

    replaceRoles() {
      return throwError(() => new Error('not used in this spec'));
    }

    setEnabled() {
      return throwError(() => new Error('not used in this spec'));
    }
  }

  let fixture: ComponentFixture<UserAdminComponent>;
  let fake: FakeBackofficeService;

  /** Creates the component; `ngOnInit` (and the list call) runs on detectChanges. */
  function create(response: Observable<BackofficeUserPage>): string {
    fake = new FakeBackofficeService();
    fake.response = response;

    TestBed.configureTestingModule({
      imports: [UserAdminComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        MessageService,
        { provide: BackofficeService, useValue: fake }
      ]
    });

    fixture = TestBed.createComponent(UserAdminComponent);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('says the gate is disabled when the API answers 404', () => {
    const text = create(throwError(() => new HttpErrorResponse({ status: 404 })));

    expect(text).toContain('Backoffice gate is disabled on the gateway.');
    expect(text).not.toContain('No users match.');
  });

  it('says the caller is not a manager when the API answers 403', () => {
    const text = create(throwError(() => new HttpErrorResponse({ status: 403 })));

    expect(text).toContain('You do not hold MANAGER role.');
    expect(text).not.toContain('Backoffice gate is disabled');
  });

  it('shows the empty state when the API returns a page with no users', () => {
    const text = create(of(EMPTY_PAGE));

    expect(text).toContain('No users match.');
    expect(text).not.toContain('Backoffice gate is disabled');
  });

  it('renders a row per user with its role chips', () => {
    const text = create(of({
      total: 1,
      first: 0,
      max: 50,
      users: [{
        id: 'u-1',
        username: 'teller',
        email: 'teller@ecom-bank.local',
        firstName: 'Terry',
        lastName: 'Teller',
        enabled: true,
        roles: ['TELLER']
      }]
    }));

    expect(text).toContain('teller');
    expect(text).toContain('Terry Teller');
    expect(text).toContain('TELLER');
    expect(text).not.toContain('No users match.');
  });
});
