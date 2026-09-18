import { ChartSeries } from '../../models';
import {
  bucketSizeOf,
  bucketedSeries,
  bucketLabel,
  hasChartData,
  isPollRange,
  sum,
  tickStride
} from './chart-series';

/**
 * The per-range aggregation rules from brief 5.8.
 *
 * `bucketedSeries` takes `today` as an argument, so these run against a frozen
 * date instead of drifting with the calendar: the counts (7, 30, 13, 12, 24)
 * are the acceptance criteria, and they would otherwise only hold on some days
 * of the month.
 */

/** A payload as the endpoint returns it, spanning `count` days back from `last`. */
function series(range: string, last: string, count: number): ChartSeries {
  const end = Date.parse(`${last}T00:00:00Z`) / 86_400_000;
  const dailyVolume = [];
  const dailyFlow = [];
  for (let i = count - 1; i >= 0; i--) {
    const date = new Date((end - i) * 86_400_000).toISOString().slice(0, 10);
    dailyVolume.push({ date, count: 2 });
    dailyFlow.push({ date, in: 100, out: 40 });
  }
  return {
    range,
    dailyVolume,
    dailyFlow,
    balanceDistribution: [],
    sagaBreakdown: { completed: 0, compensating: 0, failed: 0 }
  };
}

/** Today as the server spells it: `YYYY-MM-DD`. */
const TODAY = '2026-09-17';

/**
 * One point in each calendar month from `firstMonth` to `TODAY`, dated the 1st.
 *
 * The real ALL payload looks like this — the ledger's first month back to now —
 * so the bucket count it produces is the month count, not a function of how
 * many days the synthetic series happens to span.
 */
function monthly(range: string, firstMonth: string): ChartSeries {
  const dailyVolume = [];
  const dailyFlow = [];
  const [startYear, startMonth] = firstMonth.split('-').map(Number);
  const [endYear, endMonth] = TODAY.split('-').map(Number);
  for (let m = startYear * 12 + startMonth - 1; m <= endYear * 12 + endMonth - 1; m++) {
    const date = `${Math.floor(m / 12)}-${String((m % 12) + 1).padStart(2, '0')}-01`;
    dailyVolume.push({ date, count: 3 });
    dailyFlow.push({ date, in: 50, out: 20 });
  }
  return {
    range,
    dailyVolume,
    dailyFlow,
    balanceDistribution: [],
    sagaBreakdown: { completed: 0, compensating: 0, failed: 0 }
  };
}

describe('chart-series aggregation (brief 5.8)', () => {
  describe('bucket count per range', () => {
    it('plots 7d day by day', () => {
      const buckets = bucketedSeries(series('7d', TODAY, 8), TODAY);
      expect(buckets.bucket).toBe('day');
      expect(buckets.labels.length).toBe(7);
      expect(buckets.volume.length).toBe(7);
      expect(buckets.flowIn.length).toBe(7);
    });

    it('plots 30d day by day', () => {
      const buckets = bucketedSeries(series('30d', TODAY, 31), TODAY);
      expect(buckets.bucket).toBe('day');
      expect(buckets.labels.length).toBe(30);
    });

    it('plots 90d into 13 weekly points', () => {
      const buckets = bucketedSeries(series('90d', TODAY, 91), TODAY);
      expect(buckets.bucket).toBe('week');
      expect(buckets.labels.length).toBe(13);
    });

    it('rolls 1y into 12 calendar months', () => {
      const buckets = bucketedSeries(series('1y', TODAY, 366), TODAY);
      expect(buckets.bucket).toBe('month');
      expect(buckets.labels.length).toBe(12);
      expect(buckets.labels[0]).toContain('2025');
      expect(buckets.labels[11]).toContain('2026');
    });

    it('rolls ALL into one bucket per month of data', () => {
      // 2024-10 through 2026-09 is the 24 months the seeded ledger holds.
      const buckets = bucketedSeries(monthly('all', '2024-10'), TODAY);
      expect(buckets.bucket).toBe('month');
      expect(buckets.labels.length).toBe(24);
      expect(buckets.labels[0]).toContain('2024');
      expect(buckets.labels[23]).toContain('2026');
      expect(sum(buckets.volume)).toBe(72);
    });
  });

  describe('aggregation', () => {
    it('sums counts into the bucket and sums in/out separately', () => {
      const buckets = bucketedSeries(series('90d', TODAY, 91), TODAY);
      // 91 days × 2 transactions, wherever the week boundaries fall.
      expect(sum(buckets.volume)).toBe(182);
      expect(sum(buckets.flowIn)).toBe(91 * 100);
      expect(sum(buckets.flowOut)).toBe(91 * 40);
    });

    it('labels a bucket with its first day', () => {
      const buckets = bucketedSeries(series('90d', TODAY, 91), TODAY);
      // The first bucket starts 90 days before 2026-09-17.
      expect(buckets.labels[0]).toBe('Jun 19');
    });

    it('zero-fills days the sparse series has no row for', () => {
      const sparse: ChartSeries = {
        range: '7d',
        dailyVolume: [{ date: TODAY, count: 5 }],
        dailyFlow: [{ date: TODAY, in: 10, out: 3 }],
        balanceDistribution: [],
        sagaBreakdown: { completed: 0, compensating: 0, failed: 0 }
      };
      const buckets = bucketedSeries(sparse, TODAY);
      expect(buckets.volume).toEqual([0, 0, 0, 0, 0, 0, 5]);
      expect(buckets.flowIn).toEqual([0, 0, 0, 0, 0, 0, 10]);
      expect(buckets.flowOut).toEqual([0, 0, 0, 0, 0, 0, 3]);
    });

    it('ignores points that fall outside the window', () => {
      const stale: ChartSeries = {
        range: '7d',
        dailyVolume: [{ date: '2020-01-01', count: 99 }, { date: TODAY, count: 1 }],
        dailyFlow: [],
        balanceDistribution: [],
        sagaBreakdown: { completed: 0, compensating: 0, failed: 0 }
      };
      expect(sum(bucketedSeries(stale, TODAY).volume)).toBe(1);
    });
  });

  describe('the counts the charts actually show', () => {
    // The live endpoint returns one row per day with activity: 8 rows for 7d,
    // 31 for 30d, 91 for 90d, 366 for 1y. Each window plots a fixed number of
    // points regardless, which is what the browser check looks for.
    const live: { range: string; days: number; points: number; bucket: string }[] = [
      { range: '7d', days: 7, points: 7, bucket: 'day' },
      { range: '30d', days: 30, points: 30, bucket: 'day' },
      { range: '90d', days: 91, points: 13, bucket: 'week' },
      { range: '1y', days: 365, points: 12, bucket: 'month' }
    ];

    for (const c of live) {
      it(`${c.range}: ${c.days} daily rows plot as ${c.points} ${c.bucket} points`, () => {
        const buckets = bucketedSeries(series(c.range, TODAY, c.days), TODAY);
        expect(buckets.bucket).toBe(c.bucket);
        expect(buckets.labels.length).toBe(c.points);
        expect(buckets.volume.length).toBe(c.points);

        if (c.bucket !== 'month') {
          // Day and week buckets tile the window exactly, so every row lands in
          // one of them.
          expect(sum(buckets.volume)).toBe(c.days * 2);
        } else {
          // A month bucket is a calendar month, so the 1y axis is anchored to the
          // 1st and is a few days wider than the 365-day series the server
          // scopes itself to. Nothing is double counted and nothing outside the
          // axis is dropped, but the two are not the same length.
          const plotted = sum(buckets.volume);
          expect(plotted).toBeGreaterThan(c.days * 2 * 0.95);
          expect(plotted).toBeLessThanOrEqual(c.days * 2);
        }
      });
    }

    it('all: 708 daily rows over 24 months plot as 24 monthly points', () => {
      const buckets = bucketedSeries(series('all', TODAY, 708), TODAY);
      expect(buckets.bucket).toBe('month');
      // 708 days back from 2026-09-17 opens the window in October 2024.
      expect(buckets.labels.length).toBe(24);
      expect(buckets.labels[0]).toContain('2024');
      expect(buckets.labels[23]).toContain('2026');
      expect(sum(buckets.volume)).toBe(708 * 2);
    });
  });

  describe('chart shape per range', () => {
    it('labels every day for 7d, every third day for 30d, and thins the long ones', () => {
      expect(tickStride('7d')).toBe(1);    // every day
      expect(tickStride('30d')).toBe(3);   // every third day
      expect(tickStride('90d')).toBe(2);   // every second week
      expect(tickStride('1y')).toBe(3);    // every third month
      expect(tickStride('all')).toBe(3);
    });

    it('rolls up exactly the ranges whose tick stride is above one', () => {
      expect(bucketSizeOf('7d')).toBe('day');
      expect(bucketSizeOf('30d')).toBe('day');
      expect(bucketSizeOf('90d')).toBe('week');
      expect(bucketSizeOf('1y')).toBe('month');
      expect(bucketSizeOf('all')).toBe('month');
      expect(bucketSizeOf('nonsense')).toBe('day');
    });

    it('falls back to the daily bucket for a range it does not know', () => {
      const buckets = bucketedSeries(series('fortnight', TODAY, 10), TODAY);
      expect(buckets.bucket).toBe('day');
      expect(buckets.labels.length).toBe(7);
    });

    it('names the bucket width for the card headers', () => {
      expect(bucketLabel('day')).toBe('daily');
      expect(bucketLabel('week')).toBe('weekly');
      expect(bucketLabel('month')).toBe('monthly');
    });
  });

  describe('fetch strategy', () => {
    it('polls only the ranges that are cheap to re-query', () => {
      expect(isPollRange('7d')).toBe(true);
      expect(isPollRange('30d')).toBe(true);
      expect(isPollRange('90d')).toBe(false);
      expect(isPollRange('1y')).toBe(false);
      expect(isPollRange('all')).toBe(false);
    });

    it('treats an absent payload as nothing to draw', () => {
      expect(hasChartData(null)).toBe(false);
      expect(hasChartData({ range: '7d', dailyVolume: [], dailyFlow: [], balanceDistribution: [], sagaBreakdown: { completed: 0, compensating: 0, failed: 0 } })).toBe(false);
      expect(hasChartData(series('7d', TODAY, 1))).toBe(true);
    });
  });
});
