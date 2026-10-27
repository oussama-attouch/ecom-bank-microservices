import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Observable, of, throwError } from 'rxjs';
import { OutboxComponent } from './outbox.component';
import { LedgerService, OutboxStatusResponse } from '../../../services/ledger.service';

/**
 * The states that mean different things to an operator, plus one happy path so the
 * table is known to render.
 *
 * <p>404 and 403 are not interchangeable: one is a configuration (the outbox gate is
 * off on the ledger service) and the other is an identity (this session is not a
 * manager). The page's job is to say which, so each is asserted separately.
 *
 * <p>The module is configured once, in `beforeEach` — `TestBed` refuses a second
 * `configureTestingModule` after the module has been instantiated, so per-test
 * configuration only works if the module is reset first. One module plus a fake
 * whose response the test sets is simpler and keeps each spec to one line of setup.
 */
describe('OutboxComponent', () => {
  const EMPTY: OutboxStatusResponse = {
    total: 0, pending: 0, published: 0, failed: 0, parked: 0, events: []
  };

  const ROW = {
    id: 'b0000000-0000-0000-0000-000000000001',
    aggregateType: 'TRANSACTION',
    aggregateId: 'tx-abc',
    eventType: 'TRANSFER',
    status: 'PUBLISHED' as const,
    attempts: 0,
    lastError: null,
    createdAt: '2026-10-27T13:47:15Z',
    publishedAt: '2026-10-27T13:47:16Z'
  };

  class FakeLedgerService {
    response: Observable<OutboxStatusResponse> = of(EMPTY);

    getOutboxStatus(): Observable<OutboxStatusResponse> {
      return this.response;
    }

    triggerOutboxPoll() {
      return of({ triggeredAt: '2026-10-27T13:47:15Z', processed: 0 });
    }
  }

  let fake: FakeLedgerService;
  let fixture: ComponentFixture<OutboxComponent>;

  beforeEach(async () => {
    fake = new FakeLedgerService();

    await TestBed.configureTestingModule({
      imports: [OutboxComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        MessageService,
        { provide: LedgerService, useValue: fake }
      ]
    }).compileComponents();
  });

  /**
   * Renders the component with `response` as its first read and returns the text.
   * The fixture is destroyed after the text is captured: the page polls on an
   * interval, and a spec that renders twice must not leave the first copy's timer
   * running into the second's.
   */
  function render(response: Observable<OutboxStatusResponse>): string {
    fake.response = response;
    fixture = TestBed.createComponent(OutboxComponent);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    fixture.destroy();
    return text;
  }

  it('says the outbox is disabled when the API answers 404', () => {
    const text = render(throwError(() => new HttpErrorResponse({ status: 404 })));

    expect(text).toContain('Outbox is disabled.');
    expect(text).not.toContain('No outbox events yet.');
  });

  it('says the caller is not a manager when the API answers 403', () => {
    const text = render(throwError(() => new HttpErrorResponse({ status: 403 })));

    expect(text).toContain('You do not hold MANAGER role.');
    expect(text).not.toContain('Outbox is disabled.');
  });

  it('shows the empty state when the table has no rows', () => {
    const text = render(of(EMPTY));

    expect(text).toContain('No outbox events yet.');
    expect(text).not.toContain('Outbox is disabled.');
  });

  it('renders the counters and one row per event', () => {
    const text = render(of({
      total: 1,
      pending: 0,
      published: 1,
      failed: 0,
      parked: 0,
      events: [ROW]
    }));

    expect(text).toContain('PUBLISHED');
    expect(text).toContain('TRANSFER');
    expect(text).toContain('tx-abc');
    // 32-char ids are truncated to eight characters plus an ellipsis.
    expect(text).toContain('b0000000…');
    expect(text).not.toContain('No outbox events yet.');
  });

  it('shows no Parked counter when nothing is parked', () => {
    const text = render(of(EMPTY));

    expect(text).not.toContain('Parked');
  });

  it('shows the Parked counter when a row is parked', () => {
    const text = render(of({
      total: 1, pending: 0, published: 0, failed: 0, parked: 1, events: []
    }));

    expect(text).toContain('Parked');
    expect(text).toContain('attempts exhausted, never retried');
  });
});
