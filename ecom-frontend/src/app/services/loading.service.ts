import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, debounceTime, distinctUntilChanged } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class LoadingService {
  private pending = 0;
  private readonly loading = new BehaviorSubject<boolean>(false);

  /**
   * `start()` is driven by the HTTP interceptor, so it can fire synchronously
   * inside a change-detection pass (a routed component issuing its first
   * request from ngOnInit). Emitting `true` right there changed the topbar
   * overlay's `*ngIf` value mid-check and produced
   * NG0100 "Expression has changed after it was checked" on AppComponent.
   * The debounce moves the emission out of that synchronous window and, as a
   * bonus, stops the spinner from flashing on sub-150 ms requests.
   */
  readonly loading$: Observable<boolean> = this.loading.pipe(
    debounceTime(150),
    distinctUntilChanged()
  );

  start(): void {
    this.pending++;
    this.loading.next(true);
  }

  stop(): void {
    this.pending = Math.max(0, this.pending - 1);
    if (this.pending === 0) {
      this.loading.next(false);
    }
  }
}
