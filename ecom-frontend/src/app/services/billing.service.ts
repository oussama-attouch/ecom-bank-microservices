import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Bill, ArchivedTransaction } from '../models';

@Injectable({ providedIn: 'root' })
export class BillingService {
  // Plain JSON array endpoint (BillRestController)
  private readonly listBase = '/billing-service/bills';
  // Spring Data REST endpoint (for creating a bill)
  private readonly restBase = '/billing-service/api/bills';
  // Plain JSON array endpoint (TransactionProcessor)
  private readonly archiveBase = '/billing-service/api/archived-transactions';

  constructor(private http: HttpClient) {}

  list(): Observable<Bill[]> {
    return this.http.get<Bill[]>(this.listBase);
  }

  count(): Observable<number> {
    return this.list().pipe(map((bills) => bills.length));
  }

  /**
   * Every transaction billing-service has archived off the `ledger-events`
   * topic, in the order the archive stored them.
   *
   * Unpaged, unfiltered and unsorted server-side — the controller returns
   * `repository.findAll()` — so the caller sorts and filters the array it gets
   * back. Fine at the current volume; it is the whole reason the page does its
   * work client-side.
   */
  listArchivedTransactions(): Observable<ArchivedTransaction[]> {
    return this.http.get<ArchivedTransaction[]>(this.archiveBase);
  }

  generate(customerId: number): Observable<any> {
    return this.http.post(this.restBase, {
      customerId,
      billingDate: new Date().toISOString()
    });
  }
}
