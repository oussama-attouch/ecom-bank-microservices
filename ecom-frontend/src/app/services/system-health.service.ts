import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { silentWhen } from './http-context.tokens';

/** One row of the top row of cards, as /api/admin/system-health reports it. */
export interface ServiceHealth {
  name: string;
  host: string;
  /** The port Eureka registered — business traffic. */
  appPort: number;
  /** The port the probe actually hit — `appPort + offset`, or the published metadata. */
  managementPort: number;
  status: string;
  latencyMs: number;
  /** Present only when the service is configured to publish health details. */
  details: unknown | null;
  /** Set instead of `details` when the probe itself failed. */
  error: string | null;
}

export interface SystemHealthReport {
  timestamp: string;
  overallStatus: string;
  services: ServiceHealth[];
}

/**
 * One row of the middle table. `failureRate` is null until the breaker has
 * recorded a call — Resilience4j publishes -1.0% for "no data", which the gateway
 * normalises because 0% and "not measured" are different claims.
 */
export interface CircuitBreakerView {
  service: string;
  name: string;
  state: string;
  failureRate: number | null;
  slowCallRate: number | null;
  bufferedCalls: number;
  failedCalls: number;
  notPermittedCalls: number;
}

export interface CircuitBreakersReport {
  timestamp: string;
  circuitBreakers: CircuitBreakerView[];
}

/**
 * The gateway's system-health API. Manager-only at the edge, so a non-manager
 * gets 403 before either controller is reached.
 *
 * <p>Both calls are `silent`: this page renders its own failure state, and the
 * global toast ("an unexpected error occurred") is the wrong words for "the
 * gateway you are looking at is down" — which the page can say far more
 * precisely, and which it must say rather than showing empty panels that read as
 * "everything is fine".
 */
@Injectable({ providedIn: 'root' })
export class SystemHealthService {
  private readonly base = '/api/admin';

  constructor(private http: HttpClient) {}

  systemHealth(): Observable<SystemHealthReport> {
    return this.http.get<SystemHealthReport>(`${this.base}/system-health`, silentWhen(true));
  }

  circuitBreakers(): Observable<CircuitBreakersReport> {
    return this.http.get<CircuitBreakersReport>(`${this.base}/circuit-breakers`, silentWhen(true));
  }
}
