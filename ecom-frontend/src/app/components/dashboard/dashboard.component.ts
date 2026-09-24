import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CardModule } from 'primeng/card';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ButtonModule } from 'primeng/button';
import { ChartModule } from 'primeng/chart';
import { SelectButtonModule } from 'primeng/selectbutton';
import { MessageService } from 'primeng/api';
import { LedgerService } from '../../services/ledger.service';
import { JournalService } from '../../services/journal.service';
import { NotificationService } from '../../services/notification.service';
import { CountUpDirective } from '../../directives/count-up.directive';
import { KpiSparklineComponent } from '../shared/kpi-sparkline/kpi-sparkline.component';
import { TimelineScrubberComponent } from './timeline-scrubber/timeline-scrubber.component';
import {
  deltaText,
  hasTrendPercentage,
  sparklineSeries,
  thresholdState,
  thresholdTooltip,
  trendDirection,
  trendTone,
  trendTooltip,
  ThresholdState,
  TrendDirection,
  TrendTone,
  TrendSource
} from './kpi-trends';
import {
  bucketLabel,
  bucketedSeries,
  bucketSizeOf,
  BucketSize,
  ChartBuckets,
  ChartRange,
  hasChartData,
  isPollRange,
  sum,
  tickStride
} from './chart-series';
import { Account, ChartSeries, JournalEntry, KpiTrends, KpiTrend, TrialBalance } from '../../models';
import { catchError, forkJoin, interval, of, OperatorFunction, Subscription } from 'rxjs';

/** Which of the four trend entries a KPI card reads. */
type KpiKey = keyof KpiTrends;

/** Periods offered by the header selector. */
export type DashboardRange = '7D' | '30D' | '90D' | '1Y' | 'ALL';

/**
 * Selector spelling → the server's range token. The endpoint's accepted values
 * are lower case (`7d`, `30d`, `90d`, `1y`, `all`) and it rejects anything else
 * with a 400, while the buttons read better in caps.
 */
const RANGE_TOKEN: Record<DashboardRange, ChartRange> = {
  '7D': '7d', '30D': '30d', '90D': '90d', '1Y': '1y', ALL: 'all'
};

/**
 * How the selected range reads on the cards, which is not the same at every
 * level of detail: a label wants the short token beside the KPI's name
 * ("Volume (30d)"), the subtitle wants prose ("Over 30 days"), the trend row
 * names the window it compares against, and a tooltip reads as a sentence.
 *
 * `previous` is empty for ALL, which has no earlier period to compare with: the
 * trend row then shows its dash and nothing else, rather than a label for a
 * comparison that did not happen.
 */
interface RangeWords {
  /** The token that goes in a card's label. */
  token: string;
  /** The card's subtitle line. */
  over: string;
  /** What the trend row compares against. */
  previous: string;
  /** The same, as it reads inside a sentence, with its leading space. */
  ago: string;
  /** How the window reads mid-sentence. */
  prose: string;
  /** Why there is no percentage, when there cannot be one. */
  noBaseline?: string;
}

const RANGE_WORDS: Record<DashboardRange, RangeWords> = {
  '7D': { token: '7d', over: 'Over 7 days', previous: 'From previous 7d',
          ago: ' 7 days ago', prose: 'the last 7 days' },
  '30D': { token: '30d', over: 'Over 30 days', previous: 'From previous 30d',
           ago: ' 30 days ago', prose: 'the last 30 days' },
  '90D': { token: '90d', over: 'Over 90 days', previous: 'From previous 90d',
           ago: ' 90 days ago', prose: 'the last 90 days' },
  '1Y': { token: '1y', over: 'Over 1 year', previous: 'From previous 1y',
          ago: ' a year ago', prose: 'the last year' },
  ALL: { token: 'all', over: 'Over all time', previous: '',
         ago: '', prose: 'the whole log',
         noBaseline: 'The whole log is the window, so there is no earlier period to compare against' }
};

/** Where the header selector remembers the operator's choice. */
const RANGE_STORAGE_KEY = 'dashboard.range';
const DEFAULT_RANGE: DashboardRange = '30D';

/**
 * Why a refresh is running, which is what decides the chart-series fetch.
 *
 * The series is the expensive source — 91-708 grouped rows — so "is it worth
 * re-reading?" has a different answer at each of the four moments the dashboard
 * refreshes, and collapsing them into one expression is what broke the range
 * selector: `initialLoad || isPollRange(token)` is false for 90d/1y/all once the
 * first load has landed, so those ranges fetched neither on the click
 * (initialLoad is spent) nor on the poll (the poll skips them) and sat on
 * "No data" forever.
 *
 * 'snapshot' is the scrubber arriving or leaving: like a range change it must
 * always fetch, because the operator has explicitly asked to look at a different
 * instant and the range-dependent payloads are about to describe the wrong one.
 */
type RefreshReason = 'initial' | 'range-change' | 'poll' | 'snapshot';

export interface KpiCard extends TrendSource {
  label: string;
  icon: string;
  /**
   * Picks this card's trend out of the `/api/dashboard/kpi-trends` payload.
   * Named rather than positional so reordering cards cannot silently pair a KPI
   * with another KPI's history.
   */
  key: KpiKey;
  /** null = the source failed, so the card shows "No data" instead of a number. */
  value: number | null;
  prefix: string;
  /** Unit trail: `%` on the rate cards, ` ms` on the duration cards. */
  suffix?: string;
  decimals: number;
  /** Tooltip on the card's trend line, for the KPIs whose displayed value is a window. */
  trendTitle?: string;
  /**
   * The muted line under the label naming the window the value covers, for the
   * cards whose number moves with the selector. Absent on the snapshot cards,
   * whose value is "now" whatever range is selected.
   */
  subtitle?: string;
  /**
   * Whether the sparkline's last point may be re-anchored to the card's value.
   *
   * The history is one point per bucket — a day, a week or a month — while a
   * flow card's value is the whole window's total. They are different
   * quantities, and pinning the line's end to the headline would draw a spike
   * the width of the range: a 30-day volume of 6M over daily points of 200K.
   * Rates, means and levels share the point's own scale, so they keep the
   * anchor, which is what makes the line end on the number printed beside it.
   */
  anchorHistory?: boolean;
}

interface ChartTheme {
  grid: string;
  tick: string;
  sans: string;
  mono: string;
  tooltipBg: string;
  tooltipFg: string;
  brand: string;
  accent: string;
  success: string;
  warning: string;
  danger: string;
  info: string;
  tertiary: string;
  surface: string;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, CardModule, TableModule, TagModule, ButtonModule, ChartModule, SelectButtonModule, CountUpDirective, KpiSparklineComponent, TimelineScrubberComponent],
  template: `
    <!-- Glassy Command Center header (brief 8.3) -->
    <div class="page-head">
      <h2>Command Center</h2>
      <div class="head-tags">
        @if (failedSources.length) {
          <span class="degraded" [title]="'Failed to load: ' + failedSources.join(', ')">
            <i class="pi pi-exclamation-triangle"></i> Partial data
          </span>
        }
        @if (trial) {
          <p-tag [value]="trial.balanced ? 'LEDGER BALANCED' : 'LEDGER UNBALANCED'"
                 [severity]="trial.balanced ? 'success' : 'danger'"
                 [icon]="trial.balanced ? 'pi pi-check-circle' : 'pi pi-exclamation-triangle'"></p-tag>
        } @else {
          <p-tag value="LEDGER STATUS UNKNOWN" severity="warn" icon="pi pi-question-circle"></p-tag>
        }

        <!-- Period selector. Records the operator's window and drives the chart
             series: the four charts below re-query chart-series for it. Under a
             snapshot it still applies — it picks the window measured back from
             the scrubbed instant, and the equal window every delta compares to. -->
        <div class="range-picker">
          <p-selectbutton
            [options]="rangeOptions"
            [(ngModel)]="range"
            (onChange)="onRangeChange()"
            [allowEmpty]="false"
            size="small"></p-selectbutton>
        </div>

        <!-- Timeline scrubber. Chooses *when* the dashboard is describing, where
             the selector above chooses how wide a window to measure there. -->
        <app-timeline-scrubber
          [earliest]="historyStart"
          [latest]="now"
          [value]="asOf()"
          (change)="onScrub($event)"></app-timeline-scrubber>

        <!-- Freshness of the 5s poll, so a stalled feed is visible at a glance.
             Hidden under a snapshot: nothing is polling, so "Updated Xs ago"
             would be reporting the age of a feed that is deliberately frozen. -->
        @if (!asOf()) {
          <span class="updated-ago" [class.pulse]="justRefreshed">Updated {{secondsSinceUpdate}}s ago</span>
        }
      </div>
    </div>

    <!-- Snapshot banner. Above the KPIs, and role="status" so a screen reader
         announces the mode change rather than leaving a keyboard operator to
         discover it from the numbers. -->
    @if (asOf(); as snapshotDate) {
      <div class="snapshot-banner" role="status">
        <span class="pulse-dot"></span>
        <strong>Viewing snapshot:</strong> {{ snapshotDate | date:'MMM d, y HH:mm' }}
        <button pButton label="Return to Live" (click)="onScrub(null)"></button>
      </div>
    }

    <!-- KPI cards (brief 5.3) -->
    <div class="kpi-grid">
      @for (k of kpis; track k.label) {
        <div class="kpi" [style.--i]="$index">
          <div class="kpi-top">
            <i [class]="k.icon"></i>
            <span class="kpi-label">{{ k.label }}</span>
            <!-- Threshold dot: whether the value is on the right side of the
                 card's target. Rendered only for the cards that have one, so a
                 KPI with no pass/fail gets no indicator rather than a green one. -->
            @if (!initialLoad && kpiTargetState(k); as state) {
              <span class="kpi-dot" [class]="state" [title]="kpiTargetTooltip(k)"
                    [attr.aria-label]="kpiTargetTooltip(k)"></span>
            }
          </div>
          <!-- The window the value covers, for the cards that follow the period
               selector. A snapshot card has no window to name, so it has no
               subtitle rather than one that says "now" on all five ranges. -->
          @if (k.subtitle) {
            <div class="kpi-sub">{{ k.subtitle }}</div>
          }
          <div class="kpi-body">
            @if (initialLoad) {
              <div class="skeleton sk-value"></div>
            } @else if (k.value === null) {
              <div class="kpi-value na">No data</div>
            } @else {
              <div class="kpi-value"
                   [class]="kpiValueSize(k)"
                   [appCountUp]="k.value"
                   [countUpPrefix]="k.prefix"
                   [countUpSuffix]="k.suffix ?? ''"
                   [countUpDecimals]="k.decimals"></div>
            }
            <!-- The sparkline is its own trend, so it renders even when the value's source failed. -->
            @if (!initialLoad) {
              <app-kpi-sparkline [class]="kpiTone(k)" [values]="kpiSparkline(k)"></app-kpi-sparkline>
            }
          </div>
          @if (initialLoad) {
            <div class="skeleton sk-trend"></div>
          } @else {
            <div class="kpi-trend" [class]="kpiTone(k)" [title]="kpiTooltip(k)">
              @if (k.value !== null && kpiHasTrend(k)) {
                <i class="pi" [class.pi-arrow-up-right]="kpiDirection(k) === 'up'"
                   [class.pi-arrow-down-right]="kpiDirection(k) === 'down'"
                   [class.pi-minus]="kpiDirection(k) === 'flat'"></i>
                <span class="trend-delta tabular-nums">{{ kpiDelta(k) }}</span>
              } @else {
                <!-- No baseline a week ago: "—" rather than a fabricated 0%. -->
                <span class="trend-none">—</span>
              }
              <span class="trend-period">{{ k.trendPeriod ?? 'From last week' }}</span>
            </div>
          }
        </div>
      }
    </div>

    <!-- Charts (brief 5.8). Every series comes from /dashboard/chart-series for
         the selected range; the titles name the range and the bucket width so a
         13-bar 90D chart is not mistaken for 13 days. -->
    <div class="chart-grid">
      <p-card [header]="'Transaction Volume (' + range + ' · ' + bucketWord + ')'">
        @if (initialLoad) { <div class="skeleton sk-chart"></div> }
        @else if (volumeChart) { <p-chart [type]="volumeChartType" [data]="volumeChart" [options]="barOptions" height="220px"></p-chart> }
        @else { <div class="na-block"><i class="pi pi-chart-bar"></i><span>No data</span></div> }
      </p-card>
      <p-card [header]="'Cash Flow — In vs Out (' + range + ' · ' + bucketWord + ')'">
        @if (initialLoad) { <div class="skeleton sk-chart"></div> }
        @else if (cashFlowChart) { <p-chart type="line" [data]="cashFlowChart" [options]="lineOptions" height="220px"></p-chart> }
        @else { <div class="na-block"><i class="pi pi-chart-line"></i><span>No data</span></div> }
      </p-card>
      <p-card header="Balance Distribution (top 10)">
        @if (initialLoad) { <div class="skeleton sk-chart"></div> }
        @else if (distributionChart) { <p-chart type="doughnut" [data]="distributionChart" [options]="doughnutOptions" height="220px"></p-chart> }
        @else { <div class="na-block"><i class="pi pi-chart-pie"></i><span>No data</span></div> }
      </p-card>
      <p-card header="Saga Status Breakdown">
        @if (initialLoad) { <div class="skeleton sk-chart"></div> }
        @else if (sagaChart) { <p-chart type="bar" [data]="sagaChart" [options]="horizontalBarOptions" height="220px"></p-chart> }
        @else { <div class="na-block"><i class="pi pi-chart-bar"></i><span>No data</span></div> }
      </p-card>
    </div>

    <!-- Trial balance -->
    <p-card class="mt-2">
      @if (initialLoad) {
        <div class="skeleton sk-row"></div>
      } @else if (trial) {
        <div class="trial">
          <div class="trial-col">
            <div class="trial-num tabular-nums">{{ trial.totalDebits | number:'1.0-2' }}</div>
            <div class="trial-label">Total Debits</div>
          </div>
          <div class="trial-col">
            <div class="trial-num tabular-nums">{{ trial.totalCredits | number:'1.0-2' }}</div>
            <div class="trial-label">Total Credits</div>
          </div>
          <div class="trial-col">
            <div class="trial-status" [class.bad]="!trial.balanced">
              <i [class]="trial.balanced ? 'pi pi-check-circle' : 'pi pi-exclamation-triangle'"></i>
              {{ trial.balanced ? 'BALANCED' : 'UNBALANCED' }}
            </div>
            <div class="trial-label">Accounting Heartbeat</div>
          </div>
        </div>
      } @else {
        <div class="na-block"><i class="pi pi-calculator"></i><span>No data — trial balance unavailable</span></div>
      }
    </p-card>

    <!-- Live feed -->
    <p-card class="mt-2">
      <ng-template pTemplate="title">
        <div class="feed-head">
          <span>Transaction Feed</span>
          <div class="feed-actions">
            <span class="live-dot" [class.paused]="!live" [title]="live ? 'Polling every 5s' : 'Paused'"></span>
            <p-button [label]="live ? 'Live' : 'Paused'"
                      [icon]="live ? 'pi pi-circle-fill' : 'pi pi-pause'"
                      [severity]="live ? 'success' : 'secondary'"
                      size="small"
                      (onClick)="toggleLive()"></p-button>
          </div>
        </div>
      </ng-template>

      @if (initialLoad) {
        <div class="sk-rows">
          @for (i of skeletonRows; track i) {
            <div class="sk-row-wrapper">
              <div class="skeleton sk-row-cell w-15"></div>
              <div class="skeleton sk-row-cell w-10"></div>
              <div class="skeleton sk-row-cell w-20"></div>
              <div class="skeleton sk-row-cell w-20"></div>
              <div class="skeleton sk-row-cell w-10"></div>
            </div>
          }
        </div>
      } @else if (feed) {
        <p-table [value]="feed" responsiveLayout="scroll">
          <ng-template pTemplate="header">
            <tr>
              <th>Timestamp</th>
              <th>Status</th>
              <th>Transaction</th>
              <th>Debit</th>
              <th>Credit</th>
              <th class="num">Amount</th>
              <th>Description</th>
            </tr>
          </ng-template>
          <ng-template pTemplate="body" let-e>
            <tr [class.flash-row]="isFlash(e)">
              <td>{{ e.createdAt | date:'MMM d, HH:mm:ss' }}</td>
              <td>
                @if (sagaStatus(e.transactionId); as st) {
                  <p-tag [value]="st" [severity]="sagaSeverity(st)"></p-tag>
                } @else {
                  <span class="muted">—</span>
                }
              </td>
              <td class="mono-id">{{ short(e.transactionId) }}</td>
              <td class="mono-id">{{ e.debitAccountId }}</td>
              <td class="mono-id">{{ e.creditAccountId }}</td>
              <td class="num">{{ e.amount | number:'1.2-2' }}</td>
              <td>{{ e.description }}</td>
            </tr>
          </ng-template>
        </p-table>
      } @else {
        <div class="na-block"><i class="pi pi-inbox"></i><span>No data — transaction feed unavailable</span></div>
      }
    </p-card>
  `,
  styles: [`
    /* ---- Glassy header (brief 8.3) ---- */
    .page-head {
      position: relative;
      overflow: hidden;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-4);
      padding: var(--space-5);
      margin-bottom: var(--space-5);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-xl);
      background: var(--glass-bg);
      backdrop-filter: blur(var(--glass-blur));
      -webkit-backdrop-filter: blur(var(--glass-blur));
    }
    .page-head::after {
      content: '';
      position: absolute;
      left: 0; right: 0; bottom: 0;
      height: 2px;
      background: var(--gradient-brand);
    }
    .page-head h2 {
      margin: 0;
      font-size: var(--text-xl);
      font-weight: var(--weight-semibold);
      letter-spacing: var(--tracking-tight);
      color: var(--text-primary);
    }
    .head-tags { display: flex; align-items: center; gap: var(--space-3); }
    .degraded {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 4px 10px; border-radius: var(--radius-pill);
      font-size: var(--text-xs); font-weight: var(--weight-semibold);
      color: var(--warning-text);
      background: var(--warning-soft);
      border: 1px solid var(--warning-soft);
    }

    /* ---- Period selector ----
       The active option wears the brand fill. PrimeNG renders its buttons
       outside this view, so the override has to pierce encapsulation — the
       same technique as .flash-row below. Aura also paints a white pill
       (the togglebutton "content") inside the checked button, which would
       otherwise cover the brand fill and the white label. */
    .range-picker { display: inline-flex; align-items: center; }
    :host ::ng-deep .range-picker .p-togglebutton.p-togglebutton-checked {
      background: var(--brand-primary);
      border-color: var(--brand-primary);
      color: var(--on-brand);
    }
    :host ::ng-deep .range-picker .p-togglebutton.p-togglebutton-checked .p-togglebutton-content {
      background: transparent;
      box-shadow: none;
    }

    /* ---- "Updated Xs ago" ---- */
    .updated-ago { font-size: 12px; color: var(--text-tertiary); margin-right: 12px; transition: color 300ms; }
    .updated-ago.pulse { color: var(--success); }

    /* ---- Snapshot banner ----
       Sits above the KPIs and below the header, so the mode is stated before any
       of the numbers it qualifies are read. Amber rather than red: a snapshot is
       a deliberate state, not an error. */
    .snapshot-banner {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      margin-bottom: var(--space-5);
      padding: var(--space-3) var(--space-5);
      border: 1px solid var(--warning);
      border-radius: var(--radius-xl);
      background: var(--warning-soft);
      color: var(--warning-text);
      font-size: var(--text-md);
    }
    .snapshot-banner strong { font-weight: var(--weight-semibold); }
    /* Pushes the button to the far end of the banner. */
    .snapshot-banner button { margin-left: auto; }
    .pulse-dot {
      flex: none;
      width: 8px;
      height: 8px;
      border-radius: var(--radius-full);
      background: var(--warning);
      animation: pulse 2s infinite;
    }

    /* ---- KPI cards (brief 5.3) ----
       Four to a row; the sixteen cards wrap into four rows of four on their own,
       and into two and then one as the viewport narrows (below). */
    .kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-5); margin-bottom: var(--space-5); }
    .kpi {
      position: relative;
      overflow: hidden;
      min-height: 110px;
      padding: var(--space-5);
      background: var(--surface-0);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-xl);
      box-shadow: var(--shadow-sm);
      transition: transform var(--duration-base) var(--ease-out),
                  box-shadow var(--duration-base) var(--ease-out);
      animation: kpi-in var(--duration-entrance) var(--ease-out) backwards;
      animation-delay: calc(var(--i, 0) * var(--stagger));
    }
    /* 3px gradient edge, same technique as the sidebar's active bar. */
    .kpi::before {
      content: '';
      position: absolute;
      left: 0; top: 0; bottom: 0;
      width: 3px;
      background: linear-gradient(180deg, var(--brand-primary) 0%, var(--brand-accent) 100%);
    }
    .kpi:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
    @keyframes kpi-in {
      from { opacity: 0; transform: translateY(8px); }
      to   { opacity: 1; transform: translateY(0); }
    }

    .kpi-top { display: flex; align-items: center; gap: var(--space-2); }
    .kpi-top i { font-size: 20px; color: var(--text-tertiary); }
    .kpi-label {
      font-size: var(--text-sm);
      font-weight: var(--weight-medium);
      letter-spacing: var(--tracking-wide);
      color: var(--text-secondary);
    }
    /* The window a range-dependent card's value covers. Muted, and on its own
       line so a label that grew a "(30d)" suffix cannot push the threshold dot
       off the row. */
    .kpi-sub {
      margin-top: 2px;
      font-size: var(--text-xs);
      color: var(--text-tertiary);
    }
    /* ---- Threshold dot (brief 5.3) ----
       Whether the value is on the right side of the card's target. Pushed to the
       far end of the header row so the dots of a row of cards line up, and sized
       in px rather than in a text token: it is an indicator, not a glyph. */
    .kpi-dot {
      width: 8px;
      height: 8px;
      margin-left: auto;
      border-radius: var(--radius-full);
      flex: none;
    }
    .kpi-dot.met {
      background: var(--success);
      box-shadow: 0 0 0 3px var(--success-soft);
    }
    .kpi-dot.missed {
      background: var(--danger);
      box-shadow: 0 0 0 3px var(--danger-soft);
    }
    /* Value on the left, sparkline parked on the right of the same row (brief 5.3). */
    .kpi-body {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--space-3);
      margin-top: var(--space-2);
      min-width: 0;
    }
    /* The chart keeps its 72px whatever the number beside it does: a shrunk
       sparkline is a trend nobody can read, and the number has a step of the
       type scale to give instead (see .kpi-value.long). */
    .kpi-body app-kpi-sparkline { flex: none; }
    .kpi-value {
      font-size: var(--text-3xl);
      font-weight: var(--weight-bold);
      letter-spacing: var(--tracking-kpi);
      font-variant-numeric: tabular-nums;
      color: var(--text-primary);
      line-height: 1.15;
    }
    /* Eight figures and a currency mark do not fit at the headline size on a
       quarter-width card; a nine-figure total needs two steps down. */
    .kpi-value.long { font-size: var(--text-2xl); }
    .kpi-value.compact { font-size: var(--text-xl); }
    .kpi-value.small { font-size: var(--text-lg); }
    .kpi-value.na { font-size: var(--text-lg); color: var(--text-secondary); font-weight: var(--weight-semibold); }

    /* ---- Trend line (brief 5.3: "trend (small, colored)") ---- */
    .kpi-trend {
      display: flex;
      align-items: center;
      gap: var(--space-1);
      margin-top: var(--space-3);
      min-height: 18px;
      font-size: var(--text-xs);
    }
    .kpi-trend i { font-size: 12px; }
    .trend-delta { font-weight: var(--weight-semibold); }
    .trend-period { color: var(--text-tertiary); margin-left: 2px; }
    .trend-none { color: var(--text-tertiary); font-weight: var(--weight-semibold); }

    /*
     * Tone is semantic direction, not raw sign. The card sets it and the
     * sparkline inherits it through currentColor, so both always agree. Neutral
     * is the quieter secondary text: a flat trend, a KPI with no baseline yet,
     * and — per brief 5.3's direction table — the KPIs whose movement is not a
     * judgement either way.
     */
    .good { color: var(--success-text); }
    .bad { color: var(--danger-text); }
    .neutral { color: var(--text-secondary); }

    /* ---- Skeletons (brief 5.9) ---- */
    .skeleton {
      background: linear-gradient(90deg, var(--surface-2) 0%, var(--surface-1) 50%, var(--surface-2) 100%);
      background-size: 200% 100%;
      animation: sk 1.5s linear infinite;
      border-radius: var(--radius-sm);
    }
    @keyframes sk {
      from { background-position: 0% 0; }
      to   { background-position: 200% 0; }
    }
    .sk-value { height: 34px; width: 60%; margin-top: var(--space-3); }
    .sk-trend { height: 12px; width: 45%; margin-top: var(--space-3); }
    .sk-chart { height: 220px; width: 100%; }
    .sk-row { height: 16px; width: 100%; }
    .sk-rows { display: flex; flex-direction: column; gap: var(--space-5); padding: var(--space-3) 0; }
    .sk-row-wrapper { display: flex; gap: var(--space-5); align-items: center; }
    .sk-row-cell { height: 14px; }
    .w-10 { width: 10%; } .w-15 { width: 15%; } .w-20 { width: 20%; }

    /* ---- Charts / misc ---- */
    .chart-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--space-5); }
    .na-block {
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      gap: var(--space-2); min-height: 120px; color: var(--text-secondary);
    }
    .na-block i { font-size: 1.6rem; opacity: .6; }
    .muted { color: var(--text-tertiary); }

    .trial { display: flex; gap: var(--space-7); flex-wrap: wrap; align-items: center; }
    .trial-num { font-size: var(--text-2xl); font-weight: var(--weight-bold); color: var(--text-primary); }
    .trial-label { color: var(--text-secondary); font-size: var(--text-sm); }
    .trial-status {
      display: flex; align-items: center; gap: 6px;
      font-size: var(--text-lg); font-weight: var(--weight-bold);
      color: var(--success-text);
    }
    .trial-status.bad { color: var(--danger-text); }

    .feed-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
    .feed-actions { display: flex; align-items: center; gap: var(--space-3); }
    .live-dot {
      width: 8px; height: 8px; border-radius: var(--radius-full);
      background: var(--success);
      animation: pulse 2s infinite;
    }
    .live-dot.paused { background: var(--text-tertiary); animation: none; }
    @keyframes pulse {
      0%, 100% { opacity: 1; transform: scale(1); }
      50%      { opacity: .45; transform: scale(.8); }
    }

    :host ::ng-deep .flash-row { animation: flash 1.5s ease-out; }
    @keyframes flash { from { background: var(--brand-primary-soft); } to { background: transparent; } }

    @media (max-width: 1100px) { .kpi-grid { grid-template-columns: repeat(2, 1fr); } .chart-grid { grid-template-columns: 1fr; } }
    @media (max-width: 600px) { .kpi-grid { grid-template-columns: 1fr; } }
  `]
})
export class DashboardComponent implements OnInit, OnDestroy {
  /** The two level KPIs the browser computes from the account list it holds. */
  totalAum = 0;
  activeAccounts = 0;

  /** Nullable on purpose: `null` means "section failed to load" → template renders a placeholder. */
  accounts: Account[] | null = null;
  entries: JournalEntry[] | null = null;
  sagas: any[] | null = null;
  trial: TrialBalance | null = null;
  feed: JournalEntry[] | null = null;
  /**
   * Historical half of the KPIs: the 7-day-ago value, the delta and a 7-point
   * daily series. Null when that endpoint failed - the cards then show a dash
   * for the trend while their current values keep rendering.
   */
  trends: KpiTrends | null = null;

  volumeChart: any = null;
  cashFlowChart: any = null;
  distributionChart: any = null;
  sagaChart: any = null;
  /** Bars for the daily ranges, a line once the points are rolled up (90d and up). */
  volumeChartType: 'bar' | 'line' = 'bar';

  /**
   * Latest `/dashboard/chart-series` payload, or null while it has not answered
   * or its last fetch failed. Null on a failed fetch is what clears the charts
   * to "No data" rather than leaving the previous range's series on screen.
   */
  chartSeries: ChartSeries | null = null;

  live = true;

  /**
   * The instant the dashboard is describing, or null for live.
   *
   * A signal rather than a plain field because the template reads it from four
   * places — the banner, the scrubber binding, the poll gate and the freshness
   * indicator — and they must all flip in the same render pass. It is also what
   * {@link refreshAll} reads to decide whether it is describing "now" or "then",
   * so a stale value here would mean a snapshot fetching live data.
   */
  readonly asOf = signal<Date | null>(null);

  /**
   * Oldest instant the ledger has history for — the scrubber's left end.
   *
   * Discovered rather than assumed. Null until the one `range=all` chart-series
   * read answers, which is the only existing endpoint that reports how far back
   * the ledger actually goes; the scrubber renders disabled until then rather
   * than offering a range it cannot honour. Day resolution, because that payload
   * is grouped by day: the handle cannot reach the opening hours of the first
   * day, which is a bound on precision rather than a correctness problem.
   */
  historyStart: Date | null = null;

  /**
   * "Now" for the scrubber's right end, captured once.
   *
   * Not re-read on every poll: a moving right-hand end would make the handle
   * drift under the operator's cursor while they are dragging it, and would mean
   * the same handle position named a different instant from one second to the
   * next.
   */
  readonly now = new Date();

  /** Periods in the header selector, in display order. */
  readonly rangeOptions: DashboardRange[] = ['7D', '30D', '90D', '1Y', 'ALL'];
  /** Selected period, restored from localStorage in ngOnInit. */
  range: DashboardRange = DEFAULT_RANGE;

  /** When the last poll answered, for the "Updated Xs ago" indicator. */
  private lastLoadedAt = new Date();
  secondsSinceUpdate = 0;
  /** True for 300ms after a poll lands, so the indicator flashes green. */
  justRefreshed = false;
  /** Ticks the "Updated Xs ago" counter once a second. */
  private ticker: ReturnType<typeof setInterval> | null = null;

  /**
   * True until the first response lands. Drives the skeleton placeholders and
   * the entrance animations: everything below keys off it, so a 5s poll can
   * never replay them.
   */
  initialLoad = true;

  /** Sources that failed on the last refresh; drives the "Partial data" badge. */
  failedSources: string[] = [];

  readonly skeletonRows = [0, 1, 2, 3, 4];

  barOptions: any = {};
  lineOptions: any = {};
  doughnutOptions: any = {};
  horizontalBarOptions: any = {};

  /**
   * X-axis label thinning for the volume and flow charts: every nth tick is
   * labelled. Rebuilt with the options, because a stride baked into a Chart.js
   * options object does not re-evaluate on its own.
   */
  chartTickStride = 1;

  /** transactionId → saga status, so the feed can show a badge (journal entries have no status). */
  private sagaStatuses = new Map<string, string>();
  /** `transactionId:status` pairs already announced, so the 5s poll does not repeat alerts. */
  private notifiedSagas = new Set<string>();
  private flashIds = new Set<string>();
  private prevIds = new Set<string>();
  private timer: Subscription | null = null;

  private theme!: ChartTheme;
  private chartAnimationMs = 600;
  /** Signature of the last chart rebuild — polling must not re-render identical charts. */
  private chartSignature = '';
  /** Signature of the rendered feed rows — polling must not rebuild identical rows. */
  private feedSignature = '';
  /**
   * Which range-dependent response may still be applied.
   *
   * A 90d query can easily outlive the operator's next click, and its reply must
   * not overwrite the 7D payload that replaced it — for either the chart series
   * or the KPI trends, which are fetched for the same range and can finish in
   * either order. Every range change bumps the generation; a response from an
   * older one is dropped.
   */
  private seriesGeneration = 0;
  private readonly themeObserver = new MutationObserver(() => this.onThemeChanged());

  constructor(
    private ledger: LedgerService,
    private journal: JournalService,
    private notifications: NotificationService,
    private msg: MessageService
  ) {}

  ngOnInit(): void {
    // The range is read first: the chart options carry the per-range tick
    // stride, so building them before the stored range is known would label
    // every first paint with the default range's stride.
    this.range = this.readStoredRange();
    this.theme = this.readTheme();
    this.chartAnimationMs = this.tokenMs('--duration-chart', 600);
    this.buildOptions();
    // Recolour the charts when the shell flips .app-dark on <html> (brief 5.8).
    this.themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    this.lastLoadedAt = new Date();
    this.ticker = setInterval(
      () => (this.secondsSinceUpdate = Math.floor((Date.now() - this.lastLoadedAt.getTime()) / 1000)),
      1000
    );
    this.refreshAll('initial');
    this.startTimer();
  }

  ngOnDestroy(): void {
    this.stopTimer();
    if (this.ticker !== null) {
      clearInterval(this.ticker);
      this.ticker = null;
    }
    this.themeObserver.disconnect();
  }

  /**
   * The operator picked a new period.
   *
   * Both range-dependent payloads are dropped first and their fetches always
   * fire, whatever the range costs: the selector is explicit intent, and for
   * 90d/1y/all this is the only moment they are ever read — the poll skips those
   * ranges, so a fetch that is skipped here is skipped for the rest of the
   * session. The cards and the charts read "No data" for the one round trip
   * rather than keeping the old range's numbers on screen under the new range's
   * labels, which is the one thing they must never do.
   */
  onRangeChange(): void {
    localStorage.setItem(RANGE_STORAGE_KEY, this.range);
    this.seriesGeneration++;
    this.chartSeries = null;
    this.trends = null;
    // The axis stride is per range, and a stride baked into a Chart.js options
    // object does not re-evaluate on its own.
    this.buildOptions();
    this.refreshAll('range-change');
  }

  /** The server's token for the selected range. */
  private get rangeToken(): ChartRange {
    return RANGE_TOKEN[this.range];
  }

  /**
   * The operator scrubbed to an instant, or asked to return to live.
   *
   * Mirrors {@link onRangeChange} deliberately, because it is the same kind of
   * event: the operator has changed what the dashboard is describing, so the
   * range-dependent payloads are dropped and re-fetched rather than left to
   * describe the previous subject. Dropping them to null is what puts the cards
   * and charts on "No data" for the one round trip instead of showing live
   * numbers under a snapshot banner.
   *
   * <p>The poll is stopped rather than skipped. A snapshot is a frozen instant,
   * so re-reading it every 5s would return the same bytes forever; leaving the
   * timer running would also mean a slow response could land after the operator
   * had scrubbed again, which is the race {@link seriesGeneration} exists to
   * lose safely. Stopping it removes the race instead of arbitrating it.
   *
   * @param at the instant to view, or null for live
   */
  onScrub(at: Date | null): void {
    // Re-selecting the instant already shown is a no-op: the scrubber debounces
    // a drag, and a drag that ends where it started should not cost six reads.
    const current = this.asOf();
    const same = current === null ? at === null : at !== null && at.getTime() === current.getTime();
    if (same) return;

    this.asOf.set(at);
    this.seriesGeneration++;
    this.chartSeries = null;
    this.trends = null;
    // A snapshot has no "now" to be fresh relative to, so the clock is reset
    // behind it; on the way back to live this restarts the count from the
    // refresh that is about to run.
    this.lastLoadedAt = new Date();
    this.secondsSinceUpdate = 0;

    if (at === null) {
      this.startTimer();
    } else {
      this.stopTimer();
    }

    this.refreshAll('snapshot');
  }

  /**
   * Learn how far back the ledger goes, from the chart-series payload the
   * dashboard is already fetching — the scrubber's left end.
   *
   * <p>Deliberately not a request of its own. The obvious implementation was a
   * one-off `range=all` chart-series call on mount, and it is a bad trade: that
   * endpoint's cost is dominated by the balance-distribution aggregate, which is
   * range-independent and computed for every range anyway, so a second call
   * doubles the dashboard's most expensive read — measured at ~740ms — purely to
   * learn one date. Riding on the payload costs one indexed `MIN(occurred_at)`
   * server-side and no extra round trip.
   *
   * <p>Only ever set once. The earliest event in an append-only log cannot move,
   * and re-stamping the bound on every 5s poll would make the slider's left end
   * drift under a snapshot the operator is holding.
   */
  private adoptHistoryStart(series: ChartSeries | null): void {
    if (this.historyStart || !series?.historyStart) return;
    const oldest = new Date(series.historyStart);
    if (!Number.isNaN(oldest.getTime())) {
      this.historyStart = oldest;
    }
  }

  /** Stored period, or the 30D default when it is absent or no longer offered. */
  private readStoredRange(): DashboardRange {
    const stored = localStorage.getItem(RANGE_STORAGE_KEY);
    return this.rangeOptions.includes(stored as DashboardRange)
      ? (stored as DashboardRange)
      : DEFAULT_RANGE;
  }

  /**
   * KPI cards, in display order (brief 5.3): the four cumulative KPIs, the
   * financial four, the operational four, then the governance four — sixteen
   * cards, four rows of four in a grid that wraps on its own.
   *
   * <p>Fifteen of the sixteen follow the period selector: the server computes
   * every windowed card over the selected range and its equal-length
   * predecessor, so a card's label, subtitle, trend row and tooltip all have to
   * be spelled for that range too. The exception is the dashboard SLA, which is
   * an operations metric over 24 hours whatever the operator is looking at, and
   * says so.
   *
   * <p>upIsGood encodes the brief's semantic direction table: rising assets,
   * accounts and volume are wins; rising problem sagas, concentration and saga
   * durations are not. The two KPIs that carry no judgement at all — the size of
   * an average transaction and the count of large transfers — leave it out, and
   * their trend reads grey whichever way it moved. Tone is derived from this, so
   * up is never assumed to be green; the threshold dots read it too, which is
   * why a card with a target always has a direction.
   *
   * <p>Four cards are computed in the browser from rows the dashboard already
   * loads — the standing assets, the accounts that exist, and the two balanced
   * totals of the trial balance. The rest are windows over the whole ledger,
   * which the browser never holds all of: its 200-row page cannot compute them,
   * so their value is the server's `current` and they read "No data" until the
   * trend endpoint answers.
   */
  get kpis(): KpiCard[] {
    const words = this.rangeWords;
    // ALL has no earlier period, so its trend rows show a dash and no label.
    const previous = words.previous;
    const ago = words.ago;
    const noBaseline = words.noBaseline;

    return [
      // ---- Row 1: cumulative ----
      // Levels: their value is the standing total, which is "now" whatever range
      // is selected. Only the comparison moves with the selector — the total
      // against the total as it stood a range-length ago.
      { label: 'Assets Under Management', icon: 'pi pi-wallet', key: 'assetsUnderManagement',
        upIsGood: true, value: this.accounts ? this.totalAum : null, prefix: '$', decimals: 2,
        trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Everything held in customer accounts, versus ' + words.prose },
      { label: 'Active Accounts', icon: 'pi pi-users', key: 'activeAccounts',
        upIsGood: true, value: this.accounts ? this.activeAccounts : null, prefix: '', decimals: 0,
        trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Accounts that existed ' + words.ago.trim() + ', versus ' + words.prose },
      // The card is "Volume (30d)", not "Today's volume": the number is the
      // window's turnover, read from the server like the other windowed cards.
      // Its baseline is the previous window of the same length, not the same
      // time of day a week earlier.
      { label: 'Volume (' + words.token + ')', icon: 'pi pi-arrow-right-arrow-left', key: 'todayVolume',
        upIsGood: true, value: this.trendValue('todayVolume'), prefix: '$', decimals: 2,
        subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        anchorHistory: false,
        trendTitle: 'Journal turnover over ' + words.prose + ', versus the ' + words.token + ' before' },
      // The trend counts problem sagas *started* in the window, so the number
      // shown is that same window rather than the all-time count — pairing an
      // all-time count with a flow's percentage would compare two things.
      { label: 'Problem Sagas', icon: 'pi pi-exclamation-triangle', key: 'problemSagas',
        upIsGood: false, value: this.trendValue('problemSagas'), prefix: '', decimals: 0,
        target: 0, subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        anchorHistory: false,
        trendTitle: 'Problem sagas started in ' + words.prose + ', versus the ' + words.token + ' before' },

      // ---- Row 2: financial ----
      { label: 'Net Cash Flow (' + words.token + ')', icon: 'pi pi-money-bill', key: 'netCashFlow',
        upIsGood: true, value: this.trendValue('netCashFlow'), prefix: '$', decimals: 2,
        subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        anchorHistory: false,
        trendTitle: 'Money into customer accounts over ' + words.prose + ', less money out' },
      { label: 'Avg Transaction Value (' + words.token + ')', icon: 'pi pi-calculator', key: 'avgTransactionValue',
        value: this.trendValue('avgTransactionValue'), prefix: '$', decimals: 2,
        subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Mean posting over ' + words.prose + ', versus the ' + words.token + ' before' },
      // A share is a level, and a ratio at that: its past points cannot be
      // recovered by subtracting flows, so this one card keeps the seven-day
      // comparison it has always had whatever the selector says — and says so.
      { label: 'Balance Concentration (Top 10)', icon: 'pi pi-chart-pie', key: 'balanceConcentration',
        upIsGood: false, value: this.trendValue('balanceConcentration'), prefix: '', suffix: '%', decimals: 2,
        target: 80, trendPeriod: 'From last week', previousLabel: ' a week ago',
        trendTitle: 'Share of all balances held by the ten largest accounts, versus a week ago' },
      { label: 'Large Transfers (' + words.token + ', >$10K)', icon: 'pi pi-send', key: 'largeTransfers',
        value: this.trendValue('largeTransfers'), prefix: '', decimals: 0,
        subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        anchorHistory: false,
        trendTitle: 'Postings over $10,000 in ' + words.prose + ', versus the ' + words.token + ' before' },

      // ---- Row 3: operational ----
      // Rates and durations keep their labels: the rate is a share of the
      // window, and "the window" is what the subtitle says. Their trend row
      // names the period they are measured against.
      { label: 'Saga Success Rate', icon: 'pi pi-check-circle', key: 'sagaSuccessRate',
        upIsGood: true, value: this.trendValue('sagaSuccessRate'), prefix: '', suffix: '%', decimals: 2,
        target: 95, subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Sagas started in ' + words.prose + ' that completed, versus the ' + words.token + ' before' },
      { label: 'Avg Saga Duration', icon: 'pi pi-clock', key: 'avgSagaDuration',
        upIsGood: false, value: this.trendValue('avgSagaDuration'), prefix: '', suffix: ' ms', decimals: 0,
        subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Mean duration of the sagas started in ' + words.prose + ' that finished' },
      { label: 'P95 Saga Duration', icon: 'pi pi-stopwatch', key: 'p95SagaDuration',
        upIsGood: false, value: this.trendValue('p95SagaDuration'), prefix: '', suffix: ' ms', decimals: 0,
        target: 1000, subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Slowest 5% of the sagas started in ' + words.prose + ' that finished' },
      { label: 'Compensation Rate', icon: 'pi pi-replay', key: 'compensationRate',
        upIsGood: false, value: this.trendValue('compensationRate'), prefix: '', suffix: '%', decimals: 2,
        target: 5, subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Sagas started in ' + words.prose + ' that are being unwound' },

      // ---- Row 4: governance ----
      // Meta-metrics about the pipeline that produces the twelve cards above,
      // not about the bank. They are read from the server like the windowed
      // cards, and they are the four the browser could never compute: each is a
      // ratio over the whole ledger or over this service's own request log.
      { label: 'Saga–Journal Reconciliation', icon: 'pi pi-sync', key: 'sagaJournalReconciliation',
        upIsGood: true, value: this.trendValue('sagaJournalReconciliation'), prefix: '', suffix: '%', decimals: 2,
        target: 100, subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Transfer postings in ' + words.prose + ' with a matching saga record, versus the '
          + words.token + ' before' },
      { label: 'Anomaly Rate (3σ)', icon: 'pi pi-exclamation-triangle', key: 'anomalyRate',
        upIsGood: false, value: this.trendValue('anomalyRate'), prefix: '', suffix: '%', decimals: 2,
        target: 1, subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Postings over three standard deviations from the trailing 30-day mean, over '
          + words.prose },
      { label: 'Audit Trail Completeness', icon: 'pi pi-verified', key: 'auditTrailCompleteness',
        upIsGood: true, value: this.trendValue('auditTrailCompleteness'), prefix: '', suffix: '%', decimals: 2,
        target: 100, subtitle: words.over, trendPeriod: previous, previousLabel: ago, noBaselineNote: noBaseline,
        trendTitle: 'Postings in ' + words.prose + ' carrying every audit field, versus the '
          + words.token + ' before' },
      // The one card whose trend is not a range comparison: API latency is an
      // operational signal, so the window is a day and the row says so rather
      // than inheriting the selector's period and mislabelling the number.
      { label: 'Dashboard SLA (>95%)', icon: 'pi pi-chart-bar', key: 'dashboardSlaCompliance',
        upIsGood: true, value: this.trendValue('dashboardSlaCompliance'), prefix: '', suffix: '%', decimals: 1,
        target: 95, trendPeriod: 'From previous 24h', previousLabel: ' in the previous 24 hours',
        trendTitle: 'Command Center requests answered in under 500ms over the last 24 hours' }
    ];
  }

  /** How the selected range is spelled on the cards. */
  private get rangeWords(): RangeWords {
    return RANGE_WORDS[this.range];
  }

  /**
   * A card's value, taken from the trend payload's `current`.
   *
   * For the windowed KPIs the server is the only source that can answer: a 7-day
   * total over the whole ledger is not derivable from the page of rows the
   * Command Center holds. Null (the endpoint has not answered or failed) shows
   * "No data" rather than a plausible zero.
   */
  private trendValue(key: KpiKey): number | null {
    return this.trends?.[key]?.current ?? null;
  }

  /**
   * Each source is isolated with catchError so a single failing endpoint only
   * blanks its own section; the rest of the Command Center still renders.
   *
   * @param reason why this refresh is running. It decides whether the two
   *   range-dependent payloads are re-fetched: on a range change, always — that
   *   click is the operator asking for that window, and for 90d/1y/all it is the
   *   only moment they are ever read. On a poll tick, only for the ranges cheap
   *   enough to re-query every 5s; the long ones keep the payloads they already
   *   have. The KPI trends follow the same policy as the chart series, because
   *   they are the same window over the same ledger and cost the same to read.
   */
  refreshAll(reason: RefreshReason = 'poll'): void {
    const failed: string[] = [];
    const guard = <T>(label: string): OperatorFunction<T, T | null> =>
      catchError((err: unknown) => {
        console.error(`[CommandCenter] ${label} failed to load`, err);
        failed.push(label);
        return of<T | null>(null);
      });

    const token = this.rangeToken;
    // A range change and a scrub are explicit operator intent and always fetch;
    // a poll only re-reads the ranges cheap enough for a 5s cadence.
    const fetchRange = reason !== 'poll' || isPollRange(token);
    const generation = this.seriesGeneration;
    // The one instant every source is asked about, so a snapshot cannot end up
    // with four cards describing one moment and two describing another. Null
    // (live) drops the parameter from each URL entirely.
    const asOf = this.asOf();

    forkJoin({
      // `silent` keeps the global error interceptor out of the loop: this runs
      // on a 5s timer, so an outage must be announced once, not once per poll.
      //
      // Every source takes `asOf`: under a snapshot the whole dashboard has to
      // describe one instant, and a card left reading "now" beside fifteen
      // reading "then" is worse than not offering the feature.
      accounts: this.ledger.listAccounts(true, asOf).pipe(guard<Account[]>('accounts')),
      entries: this.journal.entries(undefined, 200, true, asOf).pipe(guard<JournalEntry[]>('transaction feed')),
      trial: this.journal.trialBalance(true, asOf).pipe(guard<TrialBalance>('trial balance')),
      sagas: this.ledger.listSagas(true, asOf).pipe(guard<any[]>('sagas')),
      // Every KPI card's history half (brief 5.3), for the selected range: the
      // server computes each windowed KPI over it, which is what makes clicking
      // a range recompute the sixteen cards and not only the four charts.
      // Isolated like every other source: if it fails the values still render
      // and the trends read as a dash.
      trends: fetchRange
        ? this.ledger.kpiTrends(token, true, asOf).pipe(guard<KpiTrends>('kpi trends'))
        : of<KpiTrends | null>(null),
      // Everything the four charts draw, for the selected range (brief 5.8).
      // `of(null)` keeps forkJoin's shape stable without issuing a request, and
      // marks the answer as "not fetched" so it cannot blank the series.
      series: fetchRange
        ? this.ledger.getChartSeries(token, true, asOf).pipe(guard<ChartSeries>('chart series'))
        : of<ChartSeries | null>(null)
    }).subscribe({
      next: ({ accounts, entries, trial, sagas, trends, series }) => {
        this.accounts = accounts;
        this.entries = entries;
        this.trial = trial;
        this.sagas = sagas;

        // A skipped fetch carries null; only a fetched answer may replace a
        // payload, and only if the range it was fetched for is still the
        // selected one. A 1y query easily outlives the operator's next click,
        // and its reply must not overwrite the 7D payload that replaced it.
        if (fetchRange && generation === this.seriesGeneration) {
          this.trends = trends;
          this.chartSeries = series;
        }

        this.sagaStatuses = new Map(
          (sagas ?? []).filter((s) => s?.transactionId).map((s) => [s.transactionId as string, s.status as string])
        );

        this.computeKpis();
        this.rebuildChartsIfChanged(this.chartSeries);
        this.adoptHistoryStart(this.chartSeries);
        this.updateFeed();
        this.raiseProblemSagaNotifications();
        this.reportFailures(failed);
        this.initialLoad = false;

        // Fresh data: restart the "Updated Xs ago" clock and flash it green.
        this.lastLoadedAt = new Date();
        this.secondsSinceUpdate = 0;
        this.justRefreshed = true;
        setTimeout(() => (this.justRefreshed = false), 300);
      },
      // Safety net: with per-source catchError this should not normally fire.
      error: (err) => {
        console.error('[CommandCenter] refresh failed', err);
        this.reportFailures(['dashboard']);
        this.initialLoad = false;
      }
    });
  }

  /**
   * Record which sources failed and announce the outage only on the transition
   * into a degraded state, so a persistent failure does not re-toast every 5s.
   * The template keeps a "Partial data" badge for as long as it lasts.
   */
  private reportFailures(failed: string[]): void {
    const enteringDegraded = this.failedSources.length === 0 && failed.length > 0;
    this.failedSources = failed;

    if (enteringDegraded) {
      this.msg.add({
        severity: 'warn',
        summary: 'Dashboard',
        detail: 'Some dashboard data failed to load',
        life: 5000
      });
    }
  }

  /**
   * Feed the notification centre (the topbar bell) with problem sagas, so the
   * Command Center KPI and the bell agree. Keyed by `transactionId:status` so
   * the 5s auto-refresh raises each alert exactly once, while a later status
   * change on the same transaction (e.g. COMPENSATING -> COMPLETED) still
   * notifies once more.
   */
  private raiseProblemSagaNotifications(): void {
    for (const saga of this.sagas ?? []) {
      const status = saga?.status;
      const txn = saga?.transactionId;
      if (!txn || (status !== 'COMPENSATING' && status !== 'FAILED')) continue;

      const key = `${txn}:${status}`;
      if (this.notifiedSagas.has(key)) continue;
      this.notifiedSagas.add(key);

      this.notifications.push({
        title: status === 'FAILED' ? 'Transfer failed' : 'Transfer compensating',
        message: `${this.short(txn)} is ${status.toLowerCase()} — open Sagas for the step trace.`,
        severity: status === 'FAILED' ? 'error' : 'warn'
      });
    }
  }

  // ---------------------------------------------------------------------------
  // KPI trends (brief 5.3)
  // ---------------------------------------------------------------------------

  /** This card's trend payload, or null when the trend endpoint has not answered. */
  private trend(k: KpiCard): KpiTrend | null {
    return this.trends?.[k.key] ?? null;
  }

  // The rules themselves live in ./kpi-trends as pure functions, so they can be
  // tested without standing up this component's HTTP polling and charts. These
  // wrappers only supply the card's own trend payload.

  /** Which way the value moved, or null when there is no measurement. */
  kpiDirection(k: KpiCard): TrendDirection | null {
    return trendDirection(this.trend(k));
  }

  /** True when the card should show a percentage rather than a dash. */
  kpiHasTrend(k: KpiCard): boolean {
    return hasTrendPercentage(this.trend(k));
  }

  /** Green when the movement is good news for this KPI, red when it is not. */
  kpiTone(k: KpiCard): TrendTone {
    return trendTone(k, this.trend(k));
  }

  /** Signed percentage for the trend line, e.g. +20.02% or -12.20%. */
  kpiDelta(k: KpiCard): string {
    return deltaText(this.trend(k));
  }

  /** Hover text: the exact comparison behind the percentage. */
  kpiTooltip(k: KpiCard): string {
    return trendTooltip(k, this.trend(k));
  }

  /** Daily series for the card's sparkline, oldest first. */
  kpiSparkline(k: KpiCard): number[] | null {
    // The line is drawn from the server's history, and only a card whose value
    // is on the same scale as one of its points may pin the last point to it.
    // A flow card's value is the whole window's total, so pinning would draw a
    // spike rather than a trend; see `anchorHistory` on KpiCard.
    const anchor = k.anchorHistory === false ? null : k.value;
    return sparklineSeries(this.trend(k), anchor);
  }

  /**
   * Whether the card's value clears its target, or null when it has no target
   * (or no value to judge). Read from the card's own displayed value rather than
   * from the payload, so the dot can never disagree with the number beside it.
   */
  kpiTargetState(k: KpiCard): ThresholdState | null {
    return thresholdState(k, k.value);
  }

  /** Hover text for the threshold dot: what the target is and which way it points. */
  kpiTargetTooltip(k: KpiCard): string {
    return thresholdTooltip(k);
  }

  /**
   * The type-scale step a card's value has to be set in to fit beside its chart.
   *
   * A 30-day or yearly total runs to eight or nine figures — "$37,112,346.95" —
   * where the card beside it used to hold a day's turnover in four. At the
   * headline size that string is wider than the room the card has beside the
   * sparkline, and the flex row clipped the chart rather than the number: a
   * shrunk sparkline is a trend nobody can read, while a number one step down
   * the scale still is one. The standing totals were already at the edge of this
   * before the cards became windows — the shipped capture of the sixteen has the
   * AUM chart cut off — so the step is chosen from the string's own length.
   *
   * The budget: a four-column grid on a 1440px viewport leaves about 178px for
   * the number beside a 72px chart, and a tabular digit is roughly two thirds of
   * its font size wide. That is 8 characters at the 32px headline size, 9 at
   * 28px, 11 at 24px and 14 at 18px, which is what the thresholds below encode.
   */
  kpiValueSize(k: KpiCard): string {
    if (k.value === null) return '';
    const digits = k.value.toLocaleString('en-US', {
      minimumFractionDigits: k.decimals,
      maximumFractionDigits: k.decimals
    });
    const length = (k.prefix + digits + (k.suffix ?? '')).length;
    if (length > 11) return 'small';
    if (length > 9) return 'compact';
    return length > 8 ? 'long' : '';
  }

  /**
   * The two numbers the browser can still work out for itself: how many accounts
   * exist and what they hold between them. They are levels — the standing total,
   * which is "now" whatever range is selected — so the /accounts page the
   * dashboard already loads answers them exactly, and the server's own reading of
   * the same total agrees with it.
   *
   * <p>The two KPIs that used to be computed here as well — today's volume and
   * the problem sagas of the last seven days — are now windows over the selected
   * range, and a window over the whole ledger is not derivable from the 200-row
   * page this component holds. They read the server's `current` instead, like
   * the other fourteen.
   */
  private computeKpis(): void {
    this.activeAccounts = this.accounts?.length ?? 0;
    this.totalAum = (this.accounts ?? []).reduce((s, a) => s + (a.balance || 0), 0);
  }

  /**
   * Cheap identity of the trend payload, for the chart-rebuild gate.
   *
   * A trend the payload does not carry is recorded as absent rather than assumed
   * to be there: the eight windowed cards arrived after the endpoint that feeds
   * them, and a client running ahead of its server has to render them as "No
   * data" instead of throwing on the way to the charts.
   */
  private trendSignature(): string {
    if (!this.trends) return 'none';
    return (Object.keys(this.trends) as KpiKey[])
      .map((key) => {
        const t = this.trends![key];
        if (!t) return key + ':absent';
        return key + ':' + t.current + ':' + t.previous + ':' + t.deltaPercent + ':' + (t.history ?? []).join(',');
      })
      .join('~');
  }

  /**
   * Cheap identity of the chart payload, for the chart-rebuild gate.
   *
   * The series lengths alone say whether the buckets could have moved: the four
   * charts plot the rolled-up series, and a poll that returns the same shape for
   * the same range draws the same chart. The 5s cadence means a volume change
   * that leaves the row count untouched would go unnoticed for one tick, which
   * is invisible at a day's granularity.
   */
  private seriesSignature(series: ChartSeries | null): string {
    if (!series) return 'none';
    return [
      series.range,
      series.dailyVolume?.length ?? 0,
      series.dailyFlow?.length ?? 0,
      series.balanceDistribution?.map((a) => a.accountId).join(',') ?? '',
      series.sagaBreakdown?.completed ?? -1,
      series.sagaBreakdown?.compensating ?? -1,
      series.sagaBreakdown?.failed ?? -1
    ].join(':');
  }

  /**
   * Rebuilding the datasets hands Chart.js brand new objects, which restarts
   * its animation. A 5s poll with unchanged data must not do that, so the
   * rebuild is gated on a signature of everything the charts actually read.
   *
   * `entries` no longer contributes beyond the feed it fills: the four charts
   * used to be aggregated from the entry page (hence the old
   * `entries.at(0)?.id` term, a stand-in for "the recent window moved"), and
   * now come from the chart-series payload. The KPI trend payload stays in,
   * because it feeds the trend lines and sparklines in the same render pass —
   * and it is the only term that moves when the operator changes the range,
   * which is what makes the cards and the charts rebuild together.
   */
  private rebuildChartsIfChanged(series: ChartSeries | null): void {
    const signature = [
      this.range,
      this.seriesSignature(series),
      this.accounts?.length ?? -1, this.totalAum, this.activeAccounts,
      this.entries?.length ?? -1,
      this.sagas?.length ?? -1, (this.sagas ?? []).map((s) => s.status).join('|'),
      this.trendSignature()
    ].join('~');

    if (signature === this.chartSignature) return;
    this.chartSignature = signature;
    this.buildCharts(series);
  }

  /**
   * The four charts, all drawn from the chart-series payload for the selected
   * range (brief 5.8).
   *
   * Volume and flow are bucketed per range — daily for 7d/30d, weekly for 90d,
   * monthly for 1y/all — so the x axis stays readable instead of smearing 708
   * points across a 500px card. The distribution and saga panels are range
   * independent: the server always returns the top ten balances and the
   * all-time saga counts.
   */
  private buildCharts(series: ChartSeries | null): void {
    const t = this.theme;
    // The chart follows the range the payload was built for, so a race between
    // two ranges cannot plot one window under another's labels.
    const buckets: ChartBuckets | null = hasChartData(series) ? bucketedSeries(series!) : null;

    if (buckets && sum(buckets.volume) > 0) {
      this.volumeChartType = buckets.bucket === 'day' ? 'bar' : 'line';
      this.volumeChart = buckets.bucket === 'day'
        ? {
            labels: buckets.labels,
            datasets: [{
              label: 'Transactions',
              data: buckets.volume,
              backgroundColor: t.brand,
              hoverBackgroundColor: t.brand,
              borderRadius: 6,
              maxBarThickness: 42
            }]
          }
        // Beyond 31 bars a bar chart is a picket fence, so the rolled-up ranges
        // are drawn as a line with the same brand colour and a gradient fill.
        : {
            labels: buckets.labels,
            datasets: [{
              label: 'Transactions',
              data: buckets.volume,
              borderColor: t.brand,
              pointBackgroundColor: t.brand,
              backgroundColor: this.verticalFade(t.brand, 0.18),
              fill: true, tension: 0.35, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4
            }]
          };
    } else {
      this.volumeChart = null;
      this.volumeChartType = 'bar';
    }

    if (buckets && (sum(buckets.flowIn) > 0 || sum(buckets.flowOut) > 0)) {
      this.cashFlowChart = {
        labels: buckets.labels,
        datasets: [
          {
            label: 'In', data: buckets.flowIn,
            borderColor: t.success, pointBackgroundColor: t.success,
            backgroundColor: this.verticalFade(t.success),
            fill: true, tension: 0.35, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4
          },
          {
            label: 'Out', data: buckets.flowOut,
            borderColor: t.danger, pointBackgroundColor: t.danger,
            backgroundColor: this.verticalFade(t.danger),
            fill: true, tension: 0.35, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4
          }
        ]
      };
    } else {
      this.cashFlowChart = null;
    }

    const distribution = series?.balanceDistribution;
    if (distribution?.length) {
      // The server already keeps the ten largest, in descending order; the
      // palette is applied here because colour is a presentation concern.
      const palette = [t.brand, t.accent, t.info, t.success, t.warning, t.danger, t.tertiary];
      this.distributionChart = {
        labels: distribution.map((a) => a.accountId),
        datasets: [{
          data: distribution.map((a) => Number(a.balance) || 0),
          backgroundColor: distribution.map((_, i) => palette[i % palette.length]),
          borderColor: t.surface,
          borderWidth: 2
        }]
      };
    } else {
      this.distributionChart = null;
    }

    const breakdown = series?.sagaBreakdown;
    if (breakdown) {
      this.sagaChart = {
        labels: ['COMPLETED', 'COMPENSATING', 'FAILED'],
        datasets: [{
          label: 'Sagas',
          data: [breakdown.completed ?? 0, breakdown.compensating ?? 0, breakdown.failed ?? 0],
          backgroundColor: [t.success, t.warning, t.danger],
          borderRadius: 6,
          maxBarThickness: 26
        }]
      };
    } else {
      this.sagaChart = null;
    }
  }

  private updateFeed(): void {
    if (!this.entries) {
      this.feed = null;
      this.feedSignature = '';
      return;
    }
    // The endpoint returns newest first, so the feed takes the head of the
    // list. It used to reverse, which assumed the oldest-first order the
    // server no longer produces.
    const newest = this.entries.slice(0, 20);

    // Handing PrimeNG a new array makes it rebuild every row. During a 5s poll
    // the rows are usually identical, so the reference is only replaced when the
    // visible set actually changes.
    const signature = newest.map((e) => e.id ?? '').join(',');
    if (signature === this.feedSignature) return;
    this.feedSignature = signature;

    this.flashIds = new Set(newest.filter((e) => e.id && !this.prevIds.has(e.id)).map((e) => e.id!));
    this.prevIds = new Set(this.entries.map((e) => e.id!).filter(Boolean));
    this.feed = newest;
    if (this.flashIds.size) {
      setTimeout(() => (this.flashIds = new Set()), 1600);
    }
  }

  /** Saga status for a journal entry's transaction, or null for plain credit/debit rows. */
  sagaStatus(transactionId: string | undefined): string | null {
    if (!transactionId) return null;
    return this.sagaStatuses.get(transactionId) ?? null;
  }

  sagaSeverity(status: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'COMPLETED': return 'success';
      case 'COMPENSATING': return 'warn';
      case 'FAILED': return 'danger';
      default: return 'secondary';
    }
  }

  isFlash(row: JournalEntry): boolean {
    return !!row.id && this.flashIds.has(row.id);
  }

  toggleLive(): void {
    this.live = !this.live;
    // Under a snapshot the feed's Live/Paused toggle is already moot — the poll
    // is stopped by onScrub — so resuming it here would quietly start polling
    // live data into a dashboard that is presenting a frozen instant. The
    // operator's route back is Return to Live, which restarts the timer.
    if (this.asOf()) {
      if (this.live) {
        this.msg.add({
          severity: 'info',
          summary: 'Snapshot mode',
          detail: 'Return to Live to resume the feed.',
          life: 4000
        });
      }
      return;
    }
    this.live ? this.startTimer() : this.stopTimer();
  }

  private startTimer(): void {
    this.stopTimer();
    this.timer = interval(5000).subscribe(() => this.refreshAll('poll'));
  }

  private stopTimer(): void {
    this.timer?.unsubscribe();
    this.timer = null;
  }

  short(id: string | undefined): string {
    return id ? (id.length > 12 ? id.substring(0, 12) + '…' : id) : '';
  }

  /**
   * How wide one plotted point is, for the chart card titles. Reads from the
   * series when there is one, so the title always describes what is on screen.
   */
  get bucketWord(): string {
    return bucketLabel(bucketSizeOf(this.chartSeries?.range ?? this.rangeToken));
  }

  // ---------------------------------------------------------------------------
  // Chart theming
  // ---------------------------------------------------------------------------

  /** Recolour on dark-mode toggle. Instant, so the charts do not replay their entrance. */
  private onThemeChanged(): void {
    this.theme = this.readTheme();
    this.chartAnimationMs = 0;
    this.chartSignature = '';
    this.rebuildChartsIfChanged(this.chartSeries);
    this.buildOptions();
    this.chartAnimationMs = this.tokenMs('--duration-chart', 600);
  }

  /**
   * Chart.js cannot read CSS variables, so the palette is resolved once from
   * the document and handed over as concrete values (brief 5.8).
   */
  private readTheme(): ChartTheme {
    return {
      grid: this.token('--border-subtle', 'rgba(15, 23, 42, 0.06)'),
      tick: this.token('--text-secondary', '#64748b'),
      sans: this.token('--font-sans', 'Inter, system-ui, sans-serif').replace(/\s+/g, ' '),
      mono: this.token('--font-mono', 'ui-monospace, monospace').replace(/\s+/g, ' '),
      tooltipBg: this.token('--chart-tooltip-bg', '#0f172a'),
      tooltipFg: this.token('--chart-tooltip-fg', '#ffffff'),
      brand: this.token('--brand-primary', '#4f46e5'),
      accent: this.token('--brand-accent', '#06b6d4'),
      success: this.token('--success', '#10b981'),
      warning: this.token('--warning', '#f59e0b'),
      danger: this.token('--danger', '#f43f5e'),
      info: this.token('--info', '#3b82f6'),
      tertiary: this.token('--text-tertiary', '#94a3b8'),
      surface: this.token('--surface-0', '#ffffff')
    };
  }

  private buildOptions(): void {
    this.chartTickStride = tickStride(this.rangeToken as ChartRange);
    this.barOptions = this.chartOptions();
    this.lineOptions = this.chartOptions();
    this.doughnutOptions = this.chartOptions({ scales: {} });
    this.horizontalBarOptions = this.chartOptions({ indexAxis: 'y' });
  }

  private chartOptions(extra: Record<string, unknown> = {}): any {
    const t = this.theme;
    const axis = {
      grid: { color: t.grid, drawTicks: false },
      border: { display: false },
      ticks: { color: t.tick, font: { family: t.sans, size: 12 } }
    };
    // Category axis: its points are labelled by the dates the chart-series
    // payload carries, thinned to the range's stride (7d: every day, 30d: every
    // third day, 90d: every second week, 1y/all: every third month). Chart.js
    // would otherwise drop labels on its own judgement, which reads as an axis
    // with no pattern.
    //
    // The label has to be read back through the scale rather than taken from
    // `value`. On a category scale a tick's value IS its index — buildTicks
    // emits `{ value: i }` and the default callback maps it through
    // `getLabelForValue` — so returning `value` is what put 0, 1, 2 … on the
    // volume and cash-flow axes instead of Sep 1, Sep 2, …. A plain function,
    // not an arrow: Chart.js invokes the callback with the scale as `this`.
    const stride = this.chartTickStride;
    const timeAxis = {
      ...axis,
      ticks: {
        ...axis.ticks,
        autoSkip: false,
        callback: function (this: any, value: string | number, index: number): string | number | null {
          if (index % stride !== 0) return null;
          return typeof this?.getLabelForValue === 'function' ? this.getLabelForValue(value) : value;
        }
      }
    };
    return {
      responsive: true,
      maintainAspectRatio: false,
      animation: { duration: this.chartAnimationMs, easing: 'easeOutQuart' },
      plugins: {
        legend: {
          position: 'top',
          align: 'end',
          labels: {
            color: t.tick,
            usePointStyle: true,
            pointStyle: 'circle',
            boxWidth: 8,
            boxHeight: 8,
            padding: 14,
            font: { family: t.sans, size: 12, weight: 500 }
          }
        },
        tooltip: {
          backgroundColor: t.tooltipBg,
          titleColor: t.tooltipFg,
          bodyColor: t.tooltipFg,
          cornerRadius: 8,
          padding: 10,
          usePointStyle: true,
          boxWidth: 8,
          boxHeight: 8,
          titleFont: { family: t.sans, size: 12, weight: 600 },
          bodyFont: { family: t.mono, size: 12 }
        }
      },
      scales: { x: timeAxis, y: { ...axis, beginAtZero: true } },
      ...extra
    };
  }

  /** Vertical colour→transparent fade, evaluated lazily so it survives resizes. */
  private verticalFade(hex: string, fromAlpha = 0.2): (ctx: any) => string | CanvasGradient {
    const rgb = this.toRgb(hex);
    return (ctx: any) => {
      const chart = ctx?.chart;
      const area = chart?.chartArea;
      const canvas = chart?.ctx;
      if (!area || !canvas) return `rgba(${rgb}, ${fromAlpha})`;
      const gradient = canvas.createLinearGradient(0, area.top, 0, area.bottom);
      gradient.addColorStop(0, `rgba(${rgb}, ${fromAlpha})`);
      gradient.addColorStop(1, `rgba(${rgb}, 0)`);
      return gradient;
    };
  }

  private toRgb(color: string): string {
    const value = (color || '').trim();
    if (value.startsWith('#')) {
      const hex = value.length === 4
        ? value.slice(1).split('').map((c) => c + c).join('')
        : value.slice(1);
      const n = parseInt(hex, 16);
      if (Number.isFinite(n)) return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`;
    }
    const m = value.match(/(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/);
    return m ? `${m[1]}, ${m[2]}, ${m[3]}` : '79, 70, 229';
  }

  private token(name: string, fallback: string): string {
    const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return v || fallback;
  }

  private tokenMs(name: string, fallback: number): number {
    const raw = this.token(name, '');
    const n = parseFloat(raw);
    if (!Number.isFinite(n)) return fallback;
    return raw.endsWith('ms') ? n : n * 1000;
  }
}
