import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LogService, buildLogQuery, parseLokiResponse } from './log.service';

/**
 * The two pieces of this page that are pure logic: the LogQL it sends and the
 * line parser. The component around them is a list; if these are wrong, the
 * list is confidently wrong.
 */
describe('log query builder', () => {

  it('selects one service', () => {
    expect(buildLogQuery('billing-service', '')).toBe('{service="billing-service"}');
  });

  it('selects every application service for "all"', () => {
    expect(buildLogQuery('all', '')).toBe('{service=~".+-service"}');
  });

  it('appends a quoted line filter', () => {
    expect(buildLogQuery('ledger-service', 'dfc4ced3')).toBe(
      '{service="ledger-service"} |= "dfc4ced3"'
    );
  });

  it('escapes quotes and backslashes in the filter', () => {
    expect(buildLogQuery('ledger-service', 'say "hi" now')).toBe(
      '{service="ledger-service"} |= "say \\"hi\\" now"'
    );
    expect(buildLogQuery('ledger-service', 'C:\\logs')).toBe(
      '{service="ledger-service"} |= "C:\\\\logs"'
    );
  });

  it('trims the filter and treats whitespace as no filter', () => {
    expect(buildLogQuery('order-service', '   ')).toBe('{service="order-service"}');
    expect(buildLogQuery('order-service', '  timeout ')).toBe(
      '{service="order-service"} |= "timeout"'
    );
  });
});

describe('Loki response parser', () => {

  const traceId = 'dfc4ced33118a7e3d7b35e6228eabf53';
  const spanId = 'ec69d53e3a960109';
  const line =
    `2026-11-19 22:32:55.997 [http-nio-8085-exec-3] INFO  [${traceId},${spanId}] `
    + 'a.o.l.p.KafkaTransactionEventPublisher - Published transaction to Kafka';

  it('parses the logging pattern into the fields the page renders', () => {
    const [parsed] = parseLokiResponse({
      status: 'success',
      data: {
        resultType: 'streams',
        result: [{
          stream: { service: 'ledger-service' },
          values: [['1700000000000000000', line]]
        }]
      }
    });

    expect(parsed.level).toBe('INFO');
    expect(parsed.service).toBe('ledger-service');
    expect(parsed.time).toBe('22:32:55.997');
    expect(parsed.fullStamp).toBe('2026-11-19 22:32:55.997');
    expect(parsed.thread).toBe('http-nio-8085-exec-3');
    expect(parsed.logger).toBe('a.o.l.p.KafkaTransactionEventPublisher');
    expect(parsed.message).toBe('Published transaction to Kafka');
    expect(parsed.traceId).toBe(traceId);
    expect(parsed.spanId).toBe(spanId);
    expect(parsed.raw).toBeFalse();
  });

  it('keeps a stack-trace continuation as a RAW line instead of dropping it', () => {
    const [parsed] = parseLokiResponse({
      status: 'success',
      data: {
        resultType: 'streams',
        result: [{
          stream: { service: 'billing-service' },
          values: [['1700000000000000000', '\tat java.base/java.lang.Thread.run(Thread.java:1583)']]
        }]
      }
    });

    expect(parsed.raw).toBeTrue();
    expect(parsed.level).toBe('RAW');
    expect(parsed.message).toBe('\tat java.base/java.lang.Thread.run(Thread.java:1583)');
    expect(parsed.traceId).toBeNull();
  });

  it('treats an empty trace field as absent, not as an id', () => {
    const [parsed] = parseLokiResponse({
      status: 'success',
      data: {
        resultType: 'streams',
        result: [{
          stream: { service: 'gateway-service' },
          values: [['1700000000000000000',
            '2026-11-19 22:27:08.874 [main] INFO  [,] a.o.g.GatewayServiceApplication - Starting']]
        }]
      }
    });

    expect(parsed.traceId).toBeNull();
    expect(parsed.spanId).toBeNull();
  });

  it('orders lines newest first across streams', () => {
    const oldLine = '2026-11-19 22:00:00.000 [main] INFO  [,] a.Logger - old';
    const newLine = '2026-11-19 22:30:00.000 [main] INFO  [,] a.Logger - new';

    const lines = parseLokiResponse({
      status: 'success',
      data: {
        resultType: 'streams',
        result: [
          {
            stream: { service: 'ledger-service' },
            values: [['1700000000000000000', oldLine]]
          },
          {
            stream: { service: 'billing-service' },
            values: [['1700001800000000000', newLine]]
          }
        ]
      }
    });

    expect(lines.length).toBe(2);
    expect(lines[0].message).toBe('new');
    expect(lines[1].message).toBe('old');
  });

  it('returns an empty list for an empty or failed response', () => {
    expect(parseLokiResponse(null)).toEqual([]);
    expect(parseLokiResponse({ status: 'success', data: { result: [] } })).toEqual([]);
  });
});

/**
 * The transport regression. `HttpParams`' default codec leaves `=` unencoded,
 * which makes the URI partially encoded and makes Spring Cloud Gateway
 * double-encode the LogQL (`%7B` -> `%257B`). Loki then answers 400 with
 * `unexpected %!(NOVERB)`. The service must send the value fully encoded.
 */
describe('LogService request encoding', () => {

  let http: HttpTestingController;
  let service: LogService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(LogService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('encodes the LogQL fully, so no raw "=" reaches the gateway', () => {
    service.query({ service: 'all', text: '', minutes: 60, limit: 1000 }).subscribe();

    const request = http.expectOne((req) => req.url === '/loki/api/v1/query_range');

    // The value Angular stores is the raw LogQL; the serialized URL is encoded.
    expect(request.request.params.get('query')).toBe('{service=~".+-service"}');
    expect(request.request.urlWithParams).toContain(
      'query=%7Bservice%3D~%22.%2B-service%22%7D'
    );
    expect(request.request.urlWithParams).not.toContain('query=%7Bservice=~');

    const start = request.request.params.get('start') ?? '';
    const end = request.request.params.get('end') ?? '';
    expect(start).toMatch(/^\d+$/);
    expect(end).toMatch(/^\d+$/);
    expect(BigInt(end) > BigInt(start)).toBeTrue();

    request.flush({ status: 'success', data: { resultType: 'streams', result: [] } });
  });
});
