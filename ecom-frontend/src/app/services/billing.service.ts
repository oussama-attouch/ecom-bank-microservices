import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Bill } from '../models';

@Injectable({ providedIn: 'root' })
export class BillingService {
  // Plain JSON array endpoint (BillRestController)
  private readonly listBase = '/billing-service/bills';
  // Spring Data REST endpoint (for creating a bill)
  private readonly restBase = '/billing-service/api/bills';

  constructor(private http: HttpClient) {}

  list(): Observable<Bill[]> {
    return this.http.get<Bill[]>(this.listBase);
  }

  count(): Observable<number> {
    return this.list().pipe(map((bills) => bills.length));
  }

  generate(customerId: number): Observable<any> {
    return this.http.post(this.restBase, {
      customerId,
      billingDate: new Date().toISOString()
    });
  }
}
