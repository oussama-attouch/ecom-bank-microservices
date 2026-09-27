import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, OnDestroy, OnInit, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';

/**
 * How long a drag settles before it is published, in milliseconds.
 *
 * The scrubber's output drives six HTTP reads, so emitting per `input` event
 * would fire six requests for every pixel of a drag. 300ms is long enough that a
 * continuous drag issues nothing until the operator pauses or lets go, and short
 * enough that the dashboard feels like it is following the handle.
 */
const DRAG_DEBOUNCE_MS = 300;

/**
 * One step of the slider, in milliseconds.
 *
 * A minute, because a minute is the precision the banner and this label display
 * (`MMM d, y HH:mm`). Finer steps would let the handle sit on an instant the UI
 * then rounds, so the number shown would not be the number being queried —
 * coarser would make the last day of a two-year ledger unreachable.
 */
const STEP_MS = 60_000;

/**
 * Timeline scrubber: drag back through the ledger's history to put the Command
 * Center into snapshot mode.
 *
 * <h2>Why a native range input rather than {@code p-slider}</h2>
 * Three reasons, in order of weight:
 *
 * <ol>
 *   <li>The control's value <em>is</em> a time, not an abstract position, so
 *       either way this component has to map handle position ↔ instant itself
 *       (min/max/step in epoch millis, formatted label below). PrimeNG's extra
 *       slider API buys nothing for that mapping, so the choice comes down to
 *       which control is better at being a slider.</li>
 *   <li>A native {@code <input type="range">} is a form control, so keyboard
 *       support (arrows, Home/End, PageUp/Down), focus handling and the
 *       {@code slider} ARIA role with its value semantics come from the browser
 *       and are correct by default. Reproducing that on a styled div is exactly
 *       the work PrimeNG does, and relying on it here would mean trusting a
 *       third-party implementation of the one control an operator with a
 *       keyboard depends on most.</li>
 *   <li>Styling surface. PrimeNG renders a slider's track, range and handle as
 *       its own DOM, and this codebase already documents that painting a
 *       PrimeNG control with our tokens means piercing view encapsulation
 *       ({@code .range-picker} in the dashboard). That is a reasonable trade for
 *       a toggle button and a poor one for a slider, whose handle, track, filled
 *       range and thumb all need theming.</li>
 * </ol>
 *
 * <p>{@code p-slider} remains a drop-in if visual uniformity with the rest of
 * the header ever outweighs those — the public surface here is three inputs and
 * one output, none of which mention the DOM.
 *
 * <h2>What it does not decide</h2>
 * Whether a snapshot is active. It reports the instant the operator scrubbed to
 * and {@code null} for live; the dashboard owns what that means, because the
 * dashboard is what has to stop polling and refetch.
 */
@Component({
  selector: 'app-timeline-scrubber',
  standalone: true,
  imports: [CommonModule, FormsModule, ButtonModule],
  templateUrl: './timeline-scrubber.component.html',
  styleUrl: './timeline-scrubber.component.scss'
})
export class TimelineScrubberComponent implements OnInit, OnDestroy {

  /**
   * Oldest instant the ledger has data for — the left end of the slider.
   *
   * Null until the dashboard has discovered it (it costs one chart-series read),
   * during which the control renders disabled rather than pretending a range it
   * does not have.
   */
  @Input()
  set earliest(value: Date | null) {
    this.earliestMs = value ? value.getTime() : null;
    this.reposition();
  }

  /** Right end of the slider, normally "now". */
  @Input()
  set latest(value: Date | null) {
    this.latestMs = value ? value.getTime() : null;
    this.reposition();
  }

  /**
   * The instant currently being viewed, or null for live. Setting it from
   * outside moves the handle — this is what keeps the control in step when the
   * dashboard clears the snapshot from its own banner button.
   */
  @Input()
  set value(value: Date | null) {
    this.live = value === null;
    this.selectedMs = value ? value.getTime() : this.latestMs;
    this.reposition();
  }

  /**
   * The instant the operator scrubbed to, or null to return to live.
   *
   * Debounced: see {@link DRAG_DEBOUNCE_MS}.
   *
   * <h2>Why this is `scrub` and not `change`</h2>
   * It was `change`, as the original brief specified, and that name was a bug.
   * `change` is a <em>native DOM event</em>, and this component wraps an
   * `<input type="range">`: when the input fires its own `change`, the event
   * bubbles up to this component's host element, where Angular delivers it to
   * the same `(change)` binding as the output. The dashboard therefore received a
   * DOM `Event` where it expected a `Date` on every interaction — including ones
   * that never went through the debounce, because the native path bypasses
   * {@link sliderValue} and its `ready` guard entirely.
   *
   * <p>What that looked like downstream was two unrelated-looking bugs: the
   * instant was junk, so the URL builder threw and <em>no</em> request was issued,
   * leaving the browser-computed AUM and Active Accounts cards on their previous
   * live values while the server-driven cards, whose payload had just been
   * cleared, read "No data".
   *
   * <p>`stopPropagation()` on the inner input would also have worked, and is a
   * landmine: it makes the component's correctness depend on a listener in its
   * own template that a later editor has no reason to preserve. An output name
   * that is not also a DOM event name cannot regress that way.
   */
  @Output() readonly scrub = new EventEmitter<Date | null>();

  /** Slider position in epoch milliseconds; the DOM value is derived from it. */
  selectedMs: number | null = null;

  /** True when no snapshot is active, so the label reads "Live". */
  live = true;

  /** False until both ends of the range are known. */
  ready = false;

  private earliestMs: number | null = null;
  private latestMs: number | null = null;

  /**
   * Drag events, debounced. `distinctUntilChanged` matters because a drag that
   * returns to where it started should not re-issue six reads for an instant the
   * dashboard is already showing.
   */
  private readonly drags = new Subject<number>();
  private dragSub: Subscription | null = null;

  ngOnInit(): void {
    this.dragSub = this.drags
      .pipe(debounceTime(DRAG_DEBOUNCE_MS), distinctUntilChanged())
      .subscribe((ms) => this.scrub.emit(new Date(ms)));
    this.reposition();
  }

  ngOnDestroy(): void {
    this.dragSub?.unsubscribe();
    this.drags.complete();
  }

  /** The slider's own value: rounded to a whole step, and never out of range. */
  get sliderValue(): number {
    if (this.selectedMs === null || !this.ready) return 0;
    return Math.round((this.selectedMs - this.earliestMs!) / STEP_MS);
  }

  set sliderValue(step: number) {
    if (!this.ready) return;
    this.selectedMs = this.earliestMs! + step * STEP_MS;
    this.live = false;
    this.drags.next(this.selectedMs);
  }

  /** Number of whole minutes across the range, for the input's `max`. */
  get steps(): number {
    if (!this.ready) return 0;
    return Math.floor((this.latestMs! - this.earliestMs!) / STEP_MS);
  }

  /** The instant under the handle, or null while the range is unknown. */
  get selected(): Date | null {
    return this.selectedMs === null ? null : new Date(this.selectedMs);
  }

  /** Clears the snapshot and puts the handle back at the right-hand end. */
  returnToLive(): void {
    this.live = true;
    this.selectedMs = this.latestMs;
    // Emitted directly rather than through the debounce: this is a discrete
    // action, and waiting 300ms to stop showing a stale instant is a pause the
    // operator would read as the button not working.
    this.scrub.emit(null);
  }

  /** Re-clamps the handle and recomputes whether the control can be used. */
  private reposition(): void {
    this.ready = this.earliestMs !== null && this.latestMs !== null && this.latestMs > this.earliestMs;
    if (this.ready && this.selectedMs === null) {
      this.selectedMs = this.latestMs;
    }
    if (this.ready && this.selectedMs !== null) {
      this.selectedMs = Math.min(Math.max(this.selectedMs, this.earliestMs!), this.latestMs!);
    }
  }
}
