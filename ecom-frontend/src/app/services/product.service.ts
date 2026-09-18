import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Product } from '../models';

@Injectable({ providedIn: 'root' })
export class ProductService {
  private readonly base = '/inventory-service/api/products';

  constructor(private http: HttpClient) {}

  list(): Observable<Product[]> {
    return this.http.get<any>(this.base).pipe(
      map((r: any) => (r?._embedded?.products as Product[]) ?? [])
    );
  }

  count(): Observable<number> {
    return this.http.get<any>(this.base).pipe(
      map((r: any) => (r?.page?.totalElements as number) ?? (r?._embedded?.products?.length ?? 0))
    );
  }

  create(product: Product): Observable<any> {
    return this.http.post(this.base, product);
  }

  update(id: number, product: Product): Observable<any> {
    return this.http.put(`${this.base}/${id}`, product);
  }

  delete(id: number): Observable<any> {
    return this.http.delete(`${this.base}/${id}`);
  }
}
