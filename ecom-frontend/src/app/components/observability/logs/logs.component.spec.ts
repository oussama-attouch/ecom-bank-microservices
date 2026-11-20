import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { LogLine, LogService } from '../../../services/log.service';
import { LogsComponent } from './logs.component';

function line(overrides: Partial<LogLine> = {}): LogLine {
  return {
    id: 'line-1',
    timestampMs: 1_700_000_000_000,
    time: '22:32:55.997',
    fullStamp: '2026-11-19 22:32:55.997',
    level: 'INFO',
    service: 'ledger-service',
    thread: 'http-nio-8085-exec-3',
    logger: 'a.o.l.p.KafkaTransactionEventPublisher',
    message: 'Published transaction',
    traceId: null,
    spanId: null,
    raw: false,
    ...overrides
  };
}

describe('LogsComponent', () => {

  let api: jasmine.SpyObj<LogService>;
  let fixture: ComponentFixture<LogsComponent> | undefined;

  beforeEach(() => {
    api = jasmine.createSpyObj<LogService>('LogService', ['query']);
    TestBed.configureTestingModule({
      imports: [LogsComponent],
      providers: [{ provide: LogService, useValue: api }]
    });
  });

  afterEach(() => {
    // The component owns a 5s interval; destroying the fixture clears it, or
    // every later test inherits a poll against a mock that is no longer set up.
    fixture?.destroy();
    fixture = undefined;
  });

  it('renders a parsed line and links its traceId to Zipkin', () => {
    const traceId = 'dfc4ced33118a7e3d7b35e6228eabf53';
    api.query.and.returnValue(of([line({ traceId, spanId: 'ec69d53e3a960109' })]));

    fixture = TestBed.createComponent(LogsComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('.log-row').length).toBe(1);
    const trace = fixture.nativeElement.querySelector('.trace') as HTMLAnchorElement;
    expect(trace).not.toBeNull();
    expect(trace.getAttribute('href')).toBe(`http://localhost:9411/zipkin/traces/${traceId}`);
  });

  it('keeps RAW lines when the level filter is ERROR', () => {
    api.query.and.returnValue(of([
      line({ id: 'error', level: 'ERROR', message: 'boom' }),
      line({ id: 'raw', level: 'RAW', raw: true, message: '\tat java.base/Thread.run' }),
      line({ id: 'info', level: 'INFO', message: 'fine' })
    ]));

    fixture = TestBed.createComponent(LogsComponent);
    // Set the filter before the first render. The page is OnPush; mutating a
    // plain field after the initial check does not schedule another one, and
    // this test is about the filter's result, not about change detection.
    fixture.componentInstance.level = 'ERROR';
    fixture.detectChanges();

    expect(fixture.componentInstance.level).toBe('ERROR');
    expect(fixture.componentInstance.levelMatched.length).toBe(2);
    expect(fixture.nativeElement.querySelectorAll('.log-row').length).toBe(1);
    expect(fixture.nativeElement.querySelectorAll('.log-raw').length).toBe(1);
  });

  it('shows only traced request lines when Traced only is ticked', () => {
    api.query.and.returnValue(of([
      line({ id: 'traced', traceId: 'a'.repeat(32), spanId: 'b'.repeat(16) }),
      line({ id: 'background', logger: 'Eureka', message: 'heartbeat' })
    ]));

    fixture = TestBed.createComponent(LogsComponent);
    fixture.componentInstance.tracedOnly = true;
    fixture.detectChanges();

    expect(fixture.componentInstance.tracedCount()).toBe(1);
    expect(fixture.nativeElement.querySelectorAll('.log-row').length).toBe(1);
    expect(fixture.nativeElement.querySelector('.trace')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.copy')).not.toBeNull();
  });

  it('shows the MANAGER-only message when the gateway answers 403', () => {
    api.query.and.returnValue(throwError(() => ({ status: 403 })));

    fixture = TestBed.createComponent(LogsComponent);
    fixture.detectChanges();

    const banner = fixture.nativeElement.querySelector('.global-error') as HTMLElement;
    expect(banner).not.toBeNull();
    expect(banner.textContent).toContain('MANAGER-only');
  });
});
