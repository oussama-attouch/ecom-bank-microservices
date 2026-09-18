import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Customer } from '../models';

@Injectable({ providedIn: 'root' })
export class CustomerService {
  private readonly base = '/customer-service/api/customers';

  constructor(private http: HttpClient) {}

  list(): Observable<Customer[]> {
    return this.http.get<any>(this.base).pipe(
      map((r: any) => (r?._embedded?.customers as Customer[]) ?? [])
    );
  }

  count(): Observable<number> {
    return this.http.get<any>(this.base).pipe(
      map((r: any) => (r?.page?.totalElements as number) ?? (r?._embedded?.customers?.length ?? 0))
    );
  }

  create(customer: Customer): Observable<any> {
    return this.http.post(this.base, customer);
  }

  update(id: number, customer: Customer): Observable<any> {
    return this.http.put(`${this.base}/${id}`, customer);
  }

  delete(id: number): Observable<any> {
    return this.http.delete(`${this.base}/${id}`);
  }
}
