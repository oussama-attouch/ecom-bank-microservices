import { Injectable } from '@angular/core';
import { HttpClient, HttpResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Order } from '../models';

@Injectable({ providedIn: 'root' })
export class OrderService {
  private readonly base = '/order-service/orders';
  private readonly itemsBase = '/order-service/productItems';

  constructor(private http: HttpClient) {}

  list(): Observable<Order[]> {
    return this.http.get<any>(this.base).pipe(
      map((r: any) => (r?._embedded?.orders as Order[]) ?? [])
    );
  }

  count(): Observable<number> {
    return this.http.get<any>(this.base).pipe(
      map((r: any) => (r?.page?.totalElements as number) ?? (r?._embedded?.orders?.length ?? 0))
    );
  }

  fullOrder(id: number): Observable<Order> {
    return this.http.get<Order>(`/order-service/fullOrder/${id}`);
  }

  create(order: { customerId: number; status: string; createdAt: string }): Observable<HttpResponse<any>> {
    return this.http.post<any>(this.base, order, { observe: 'response' });
  }

  addItem(productId: number, price: number, quantity: number, orderRef: string): Observable<any> {
    return this.http.post(this.itemsBase, { productId, price, quantity, discount: 0, order: orderRef });
  }
}
