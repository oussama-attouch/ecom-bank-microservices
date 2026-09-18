package att.ossama.ledgerservice.dashboard;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Derives the Command Center KPI trends from the ledger's own history.
 *
 * <p>The UI's other Command Center numbers are computed client-side from the
 * last 200 journal rows, which is fine for "what is it now" but useless for
 * "what was it a month ago": the browser never sees the older rows. This service
 * answers the historical half from the authoritative stores — the append-only
 * event log, the journal and the saga store — so the trend does not depend on
 * what happens to be in the client's page window.
 *
 * <h2>The window</h2>
 * The period selector's range ({@link KpiRange}) defines two windows of equal
 * length: the selected one, {@code (now - lookback, now]}, and the one
 * immediately before it. Every KPI compares those two, so a delta is always a
 * like-for-like comparison rather than a partial period against a whole one.
 * {@code all} has no earlier window to compare against, and its cards report no
 * percentage rather than a fabricated one.
 *
 * <p>Anchored on {@link Clock#instant()} so tests can pin "now".
 *
 * <h2>Three shapes of KPI, and what "previous" means for each</h2>
 * <ul>
 *   <li><b>Flow</b> (volume, net cash flow, large transfers, problem sagas) — a
 *       period: both ends bounded, so the previous value is what happened in the
 *       window <em>before</em> the selected one. Leaving the start unbounded
 *       would silently accumulate all history.</li>
 *   <li><b>Rate and mean</b> (saga success, compensation, anomaly, audit
 *       completeness, reconciliation, average amount, average and p95 saga
 *       duration) — the same two windows, read as one number divided by another
 *       rather than as a total. The two halves are summed over the window and
 *       divided once, which is what makes a mean a mean: averaging daily means
 *       would weight a quiet day like a busy one.</li>
 *   <li><b>Level</b> (assets under management, active accounts) — a snapshot:
 *       the value now, against the value as it stood a range-length ago. Both are
 *       cumulative positions, so the comparison is not a window of activity but
 *       two readings of the same total.</li>
 * </ul>
 *
 * <h2>Four cards keep their own window, deliberately</h2>
 * <ul>
 *   <li><b>Balance concentration</b> — a ratio, and a level at that, so its past
 *       points cannot be recovered by subtracting flows from the value now; it
 *       keeps the seven-day comparison it has always had, whatever the selector
 *       says. Nothing about it becomes more meaningful over a year, and reading
 *       its history at a longer range would cost a full pass of the log per
 *       point.</li>
 *   <li><b>Dashboard SLA</b> — read from {@link SlaMetricsService} rather than
 *       from SQL, over 24 hours rather than a range: API latency is an
 *       operational signal that a yearly average would smear into
 *       meaninglessness.</li>
 *   <li><b>Assets under management and active accounts</b> — the range moves
 *       their <em>comparison</em>, not their value: "now" is now, whatever window
 *       the operator is looking at.</li>
 * </ul>
 *
 * <h2>How the numbers are read, and why it is not one query per card per day</h2>
 * Through {@link LedgerAggregates}, one grouped call per source table: the
 * journal, the event log, the saga store. The per-KPI questions are then
 * arithmetic over those rows.
 *
 * <p>The original implementation asked one aggregate per KPI per day of
 * sparkline — seven points on twelve cards, about 107 queries for the week the
 * selector defaulted to, and it would have been 4,300 for a year. It also loaded
 * the whole event log, journal and saga store into memory before that, which on
 * the 50,000-event portfolio ledger meant hydrating 50,000 rows and JSON-decoding
 * 50,000 payloads per refresh.
 *
 * <p>Grouping by bucket instead makes the cost of a refresh depend on the range
 * and not on the number of points: a year costs what a week costs, plus the rows
 * the wider window happens to touch. A refresh is sixteen queries at most, and
 * the slower half of them are bounded index ranges rather than scans.
 *
 * <p>Amounts are computed with {@link BigDecimal} because this is money: a
 * binary floating-point sum of credits and debits can drift off the cent.
 */
@Service
public class KpiTrendsService {

    /** Money is exact to the cent; percentages get two decimals for display. */
    private static final int MONEY_SCALE = 2;
    private static final int PERCENT_SCALE = 2;

    /** A posting above this counts as a large transfer on the row-2 card. */
    private static final double LARGE_TRANSFER_THRESHOLD = 10_000;

    /** How many accounts the concentration card holds against the whole ledger. */
    private static final int CONCENTRATION_TOP_ACCOUNTS = 10;

    /**
     * The concentration card's own window, in days, and the number of its
     * sparkline points. Seven, whatever the selector says — see the class notes.
     */
    private static final int CONCENTRATION_WINDOW_DAYS = 7;

    /**
     * How far back the anomaly card looks for the distribution it judges a
     * posting against. Thirty days is the conventional "what is normal here"
     * baseline, and it is long enough that a single outlier cannot drag the mean
     * far enough to hide itself.
     */
    private static final int ANOMALY_BASELINE_DAYS = 30;

    /**
     * The dashboard SLA card's own window, in days, and the length of its
     * sparkline.
     *
     * <p>One day rather than the seven every other card used: the freshness of an
     * API is an operational signal, and averaging it over a week would hide a bad
     * afternoon until the following week. The card's trend row therefore compares
     * 24 hours against the 24 before it, which is why it overrides the period
     * label the other cards share.
     */
    private static final int SLA_WINDOW_DAYS = 1;
    private static final int SLA_POINTS = 7;

    /**
     * Where the comparison readings sit in the concentration call's answer: the
     * cutoff first, because it is the oldest instant the call is given and so the
     * one the rest are derived from, then now, then the seven daily points.
     */
    private static final int CONCENTRATION_CUTOFF_READING = 0;
    private static final int CONCENTRATION_NOW_READING = 1;
    private static final int CONCENTRATION_HISTORY_FROM = 2;

    private final LedgerAggregates aggregates;
    private final SlaMetricsService sla;
    private final Clock clock;

    public KpiTrendsService(LedgerAggregates aggregates, SlaMetricsService sla, Clock clock) {
        this.aggregates = aggregates;
        this.sla = sla;
        this.clock = clock;
    }

    /** The sixteen Command Center KPI trends for the default range. */
    public KpiTrendsResponse trends() {
        return trends(KpiRange.SEVEN_DAYS);
    }

    /**
     * The sixteen KPI trends for the range a request asked for.
     *
     * @throws IllegalArgumentException for a range the selector does not offer
     */
    public KpiTrendsResponse trends(String requested) {
        return trends(KpiRange.parse(requested));
    }

    /** The sixteen Command Center KPI trends, in the order the cards are rendered. */
    public KpiTrendsResponse trends(KpiRange range) {
        Instant now = clock.instant();
        Instant from = range.from(now);
        Instant previousFrom = range.previousFrom(now);
        // A sparkline point is a calendar bucket, so the axis is built in the
        // calendar the database groups by — UTC, which is what the ledger stores
        // its wall-clock timestamps in. See KpiRange for why.
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<LocalDate> axis = range.axis(today);

        // One read per source table. The journal and saga spans cover both
        // comparison windows; the event log is read whole, because the two level
        // KPIs are cumulative positions rather than totals over a window.
        MeasureSpan span = new MeasureSpan(previousFrom, from, now, range.bucket());
        List<JournalBucket> journal = aggregates.journalBuckets(span, LARGE_TRANSFER_THRESHOLD);
        List<SagaBucket> sagas = aggregates.sagaBuckets(span);
        List<AnomalyBucket> anomalies = aggregates.anomalyBuckets(span, ANOMALY_BASELINE_DAYS);
        List<EventBucket> events = aggregates.eventBuckets(new MeasureSpan(null, from, now, range.bucket()));

        // Levels are read over the whole log, so their "total" is every movement
        // ever recorded; the split still marks which of them fall inside the
        // selected window, which is what the comparison reading is derived from.
        Slice assets = slice(events, axis, EventBucket::start, EventBucket::current, EventBucket::netFlow);
        Slice accounts = slice(events, axis, EventBucket::start, EventBucket::current, EventBucket::accountsCreated);

        Slice volume = slice(journal, axis, JournalBucket::start, JournalBucket::current, JournalBucket::volume);
        Slice cashFlow = slice(journal, axis, JournalBucket::start, JournalBucket::current,
                JournalBucket::customerCashFlow);
        Slice transactionSize = slice(journal, axis, JournalBucket::start, JournalBucket::current,
                JournalBucket::volume, JournalBucket::postings);
        Slice largeTransfers = slice(journal, axis, JournalBucket::start, JournalBucket::current,
                JournalBucket::largeTransfers);
        Slice reconciliation = slice(journal, axis, JournalBucket::start, JournalBucket::current,
                JournalBucket::reconciledTransfers, JournalBucket::transferPostings);
        Slice audit = slice(journal, axis, JournalBucket::start, JournalBucket::current,
                JournalBucket::auditedPostings, JournalBucket::postings);

        Slice problemSagas = slice(sagas, axis, SagaBucket::start, SagaBucket::current, SagaBucket::problem);
        Slice successRate = slice(sagas, axis, SagaBucket::start, SagaBucket::current,
                SagaBucket::completed, SagaBucket::started);
        Slice compensationRate = slice(sagas, axis, SagaBucket::start, SagaBucket::current,
                SagaBucket::compensating, SagaBucket::started);
        Slice sagaDuration = slice(sagas, axis, SagaBucket::start, SagaBucket::current,
                SagaBucket::durationMillisSum, SagaBucket::durationCount);

        Slice anomaly = slice(anomalies, axis, AnomalyBucket::start, AnomalyBucket::current,
                AnomalyBucket::outliers, AnomalyBucket::postings);

        // The percentile is the one measure that cannot be summed into a window
        // total: a p95 is not a function of the population's sum and count, so the
        // card's two readings are their own (cheap) queries on the saga store, and
        // its sparkline points come from the buckets, which are whole by
        // construction — the axis always starts inside the selected window, so no
        // point of it is ever split across the two.
        double p95Now = aggregates.p95SagaDurationMillisBetween(from, now);
        Double p95Before = range.hasPrevious()
                ? aggregates.p95SagaDurationMillisBetween(previousFrom, from)
                : null;
        // The concentration card keeps its own seven-day window and its own
        // reading, whatever range is selected.
        Instant concentrationCutoff = now.minus(Duration.ofDays(CONCENTRATION_WINDOW_DAYS));
        List<Double> concentration = aggregates.topBalanceShareAt(
                concentrationReadings(concentrationCutoff, now), CONCENTRATION_TOP_ACCOUNTS);

        boolean hasPrevious = range.hasPrevious();

        return new KpiTrendsResponse(
                // Row 1 — cumulative. This is "now", so only the comparison moves
                // with the range: the standing total against the standing total a
                // range-length ago.
                level(assets, hasPrevious),
                level(accounts, hasPrevious),
                // "Volume (30d)" on the card: the journal's turnover over the
                // selected window, which for the default range is the last seven
                // days rather than the day so far the card used to show.
                flow(volume, hasPrevious),
                flow(problemSagas, hasPrevious),
                // Row 2 — financial.
                flow(cashFlow, hasPrevious),
                mean(transactionSize, hasPrevious),
                // Concentration is its own shape: a level, and a ratio at that.
                trend(concentration.get(CONCENTRATION_NOW_READING),
                      concentration.get(CONCENTRATION_CUTOFF_READING),
                      moneySeries(concentration.subList(CONCENTRATION_HISTORY_FROM, concentration.size()))),
                flow(largeTransfers, hasPrevious),
                // Row 3 — operational. All attributed to the window a saga
                // started in, so a rate and the durations behind it describe the
                // same population rather than two different ones.
                percent(successRate, hasPrevious),
                mean(sagaDuration, hasPrevious),
                trend(p95Now, p95Before, sagaP95Series(sagas, axis)),
                percent(compensationRate, hasPrevious),
                // Row 4 — governance. These describe the pipeline that produces
                // every number above them, not the bank's business.
                percent(reconciliation, hasPrevious),
                percent(anomaly, hasPrevious),
                percent(audit, hasPrevious),
                // The SLA is measured in the JVM rather than in the database, so
                // it is read from its own service and on its own 24-hour window.
                trend(sla.compliancePercentBetween(slaWindowStart(now), now),
                      sla.compliancePercentBetween(slaWindowStart(now).minus(Duration.ofDays(SLA_WINDOW_DAYS)),
                              slaWindowStart(now)),
                      slaSeries(now)));
    }

    // -----------------------------------------------------------------------
    // The three KPI shapes
    // -----------------------------------------------------------------------

    /** A flow: both windows and every point are sums of the measure. */
    private static KpiTrend flow(Slice slice, boolean hasPrevious) {
        return trend(slice.currentSum(), hasPrevious ? slice.previousSum() : null,
                moneySeries(slice.bucketSums()));
    }

    /**
     * A mean: a ratio in the measure's own unit — money per posting, milliseconds
     * per saga. Zero for a window with nothing to average, which the card shows
     * as a level with no baseline rather than as a collapse to nothing.
     */
    private static KpiTrend mean(Slice slice, boolean hasPrevious) {
        return ratioTrend(slice, hasPrevious, 1);
    }

    /** A rate: the same arithmetic, reported in percent. */
    private static KpiTrend percent(Slice slice, boolean hasPrevious) {
        return ratioTrend(slice, hasPrevious, 100);
    }

    private static KpiTrend ratioTrend(Slice slice, boolean hasPrevious, double scale) {
        return trend(ratio(slice.currentSum(), slice.currentCount(), scale),
                hasPrevious ? ratio(slice.previousSum(), slice.previousCount(), scale) : null,
                moneySeries(ratios(slice.bucketSums(), slice.bucketCounts(), scale)));
    }

    /**
     * A level: the standing total now, and where it stood a range-length ago.
     *
     * <p>The comparison is not a window of activity but two readings of the same
     * total, so it is derived from the movement between them: everything that
     * happened before the cutoff, which is the total less the part inside the
     * selected window.
     */
    private static KpiTrend level(Slice slice, boolean hasPrevious) {
        double total = slice.currentSum() + slice.previousSum();
        // The point of the series that is today is the value now, and each earlier
        // point is the total less the movements that followed it. The buckets
        // partition the window, so this is arithmetic rather than an estimate.
        double[] points = new double[slice.points()];
        double after = 0;
        for (int i = points.length - 1; i >= 0; i--) {
            points[i] = total - after;
            after += slice.bucketSum(i);
        }
        return trend(total, hasPrevious ? total - slice.currentSum() : null, moneySeries(points));
    }

    // -----------------------------------------------------------------------
    // Slicing the grouped rows
    // -----------------------------------------------------------------------

    /**
     * One measure cut for the payload: its totals either side of the window
     * split, and its numerator and denominator per point of the sparkline.
     *
     * <p>A bucket's numbers are the sum over both sides, which matters for the
     * one bucket that straddles the split: summed again, it is a whole period
     * rather than a fragment, so a point of the series is the period it claims to
     * be while the two window totals stay exact.
     */
    private static final class Slice {

        private final double currentSum;
        private final double currentCount;
        private final double previousSum;
        private final double previousCount;
        private final double[] bucketSums;
        private final double[] bucketCounts;

        private Slice(double currentSum, double currentCount, double previousSum, double previousCount,
                      double[] bucketSums, double[] bucketCounts) {
            this.currentSum = currentSum;
            this.currentCount = currentCount;
            this.previousSum = previousSum;
            this.previousCount = previousCount;
            this.bucketSums = bucketSums;
            this.bucketCounts = bucketCounts;
        }

        double currentSum() {
            return currentSum;
        }

        double currentCount() {
            return currentCount;
        }

        double previousSum() {
            return previousSum;
        }

        double previousCount() {
            return previousCount;
        }

        int points() {
            return bucketSums.length;
        }

        double[] bucketSums() {
            return bucketSums;
        }

        double[] bucketCounts() {
            return bucketCounts;
        }

        double bucketSum(int index) {
            return bucketSums[index];
        }
    }

    /** Cuts one measure out of a table's grouped rows, for a KPI that is a total. */
    private static <T> Slice slice(List<T> rows, List<LocalDate> axis, Function<T, LocalDate> start,
                                   Predicate<T> current, ToDoubleFunction<T> numerator) {
        return slice(rows, axis, start, current, numerator, row -> 0);
    }

    /**
     * Cuts one measure out of a table's grouped rows, accumulating its numerator
     * and denominator separately.
     *
     * <p>Keeping the two apart is what lets a ratio be computed over a window
     * rather than averaged from the buckets: a rate is one division of the
     * window's two totals, and so is a mean.
     */
    private static <T> Slice slice(List<T> rows, List<LocalDate> axis, Function<T, LocalDate> start,
                                   Predicate<T> current, ToDoubleFunction<T> numerator,
                                   ToDoubleFunction<T> denominator) {
        Map<LocalDate, Integer> slots = slots(axis);
        double[] sums = new double[axis.size()];
        double[] counts = new double[axis.size()];
        double currentSum = 0;
        double currentCount = 0;
        double previousSum = 0;
        double previousCount = 0;

        for (T row : rows) {
            double num = numerator.applyAsDouble(row);
            double den = denominator.applyAsDouble(row);
            if (current.test(row)) {
                currentSum += num;
                currentCount += den;
            } else {
                previousSum += num;
                previousCount += den;
            }
            Integer slot = slots.get(start.apply(row));
            if (slot != null) {
                sums[slot] += num;
                counts[slot] += den;
            }
        }
        return new Slice(currentSum, currentCount, previousSum, previousCount, sums, counts);
    }

    /**
     * One value per point of the axis, for a measure that is measured over a
     * bucket rather than summed across it.
     *
     * <p>Each point is the whole of its bucket, and no point of the axis is ever
     * split across the two windows: the axis starts inside the selected window
     * for every range, so the row behind a point is the bucket's only row. The
     * assignment rather than an accumulation is what makes that explicit.
     */
    private static List<BigDecimal> measuredSeries(List<SagaBucket> rows, List<LocalDate> axis,
                                                   ToDoubleFunction<SagaBucket> measure) {
        Map<LocalDate, Integer> slots = slots(axis);
        double[] values = new double[axis.size()];
        for (SagaBucket row : rows) {
            Integer slot = slots.get(row.start());
            if (slot != null) {
                values[slot] = measure.applyAsDouble(row);
            }
        }
        return moneySeries(values);
    }

    /** The p95 saga duration per point of the axis. */
    private static List<BigDecimal> sagaP95Series(List<SagaBucket> rows, List<LocalDate> axis) {
        return measuredSeries(rows, axis, SagaBucket::p95Millis);
    }

    /** Axis bucket start → its position in the series. */
    private static Map<LocalDate, Integer> slots(List<LocalDate> axis) {
        Map<LocalDate, Integer> slots = new HashMap<>(axis.size() * 2);
        for (int i = 0; i < axis.size(); i++) {
            slots.put(axis.get(i), i);
        }
        return slots;
    }

    // -----------------------------------------------------------------------
    // Series arithmetic
    // -----------------------------------------------------------------------

    /**
     * Seven daily values for the dashboard SLA, oldest first, the last being the
     * day so far — or {@code null} for a day the service was not running.
     *
     * <p>The one series in this payload that can hold a gap, and it has to: the
     * SLA samples live in memory, so a process that started this morning has no
     * reading for yesterday. A zero-fill would draw a sparkline claiming the
     * dashboard answered nothing within its SLA for a week — a fabricated outage
     * rather than a missing measurement. A null serializes as JSON {@code null},
     * which the card's sparkline drops as a gap; with too few points left it
     * renders a dash.
     */
    private List<BigDecimal> slaSeries(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<BigDecimal> series = new ArrayList<>(SLA_POINTS);
        for (int i = SLA_POINTS - 1; i >= 0; i--) {
            Instant end = snapshotPoint(today.minusDays(i), now);
            Instant start = end.minus(Duration.ofDays(SLA_WINDOW_DAYS));
            series.add(sla.sampleCountBetween(start, end) == 0
                    ? null
                    : money(sla.compliancePercentBetween(start, end)));
        }
        // Not List.copyOf: the gaps above are nulls, which it rejects.
        return Collections.unmodifiableList(series);
    }

    /**
     * The instants the concentration card reads, in the order its answer comes
     * back: the cutoff, which is the oldest of them and so the one the rest are
     * derived from, then now, then the end of each of the last seven days, oldest
     * first.
     */
    private static List<Instant> concentrationReadings(Instant cutoff, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<Instant> readings = new ArrayList<>(CONCENTRATION_WINDOW_DAYS + 2);
        readings.add(cutoff);
        readings.add(now);
        for (int i = CONCENTRATION_WINDOW_DAYS - 1; i >= 0; i--) {
            readings.add(snapshotPoint(today.minusDays(i), now));
        }
        return List.copyOf(readings);
    }

    /** The start of the dashboard SLA card's trailing window. */
    private static Instant slaWindowStart(Instant now) {
        return now.minus(Duration.ofDays(SLA_WINDOW_DAYS));
    }

    /**
     * The moment a whole-day series point for {@code day} is taken from.
     *
     * <p>For a past day that is its next midnight — "the value as that day
     * ended". For today it is {@code now}, because the rest of today has not
     * happened: a point taken from tonight's midnight would report a future state
     * and, on the cumulative KPIs, would make the last point of the series
     * disagree with the value printed next to it.
     */
    private static Instant snapshotPoint(LocalDate day, Instant now) {
        Instant end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return end.isAfter(now) ? now : end;
    }

    /** A series of shares, rates or durations, scaled for display. */
    private static List<BigDecimal> moneySeries(List<Double> values) {
        List<BigDecimal> series = new ArrayList<>(values.size());
        for (double value : values) {
            series.add(money(value));
        }
        return List.copyOf(series);
    }

    private static List<BigDecimal> moneySeries(double[] values) {
        List<BigDecimal> series = new ArrayList<>(values.length);
        for (double value : values) {
            series.add(money(value));
        }
        return List.copyOf(series);
    }

    private static double[] ratios(double[] numerators, double[] denominators, double scale) {
        double[] ratios = new double[numerators.length];
        for (int i = 0; i < numerators.length; i++) {
            ratios[i] = ratio(numerators[i], denominators[i], scale);
        }
        return ratios;
    }

    /**
     * {@code numerator / denominator * scale}, or zero for an empty denominator.
     *
     * <p>Zero rather than a division error, and the same answer the aggregates
     * gave before: a window with nothing in it has no rate, and the trend logic
     * withholds the percentage for a zero baseline anyway.
     */
    private static double ratio(double numerator, double denominator, double scale) {
        return denominator == 0 ? 0 : scale * numerator / denominator;
    }

    /**
     * {@code (current - previous) / previous * 100}, scaled for display.
     *
     * <p>A zero (or absent) baseline returns a {@code null} delta instead of
     * dividing by zero — the client shows "—" for "no baseline", which is a
     * different statement from "no change". The absent case is the {@code all}
     * range, which has no earlier window: it reports the same dash, which is why
     * this takes a nullable previous rather than a sentinel.
     */
    private static KpiTrend trend(double current, Double previousOrNull, List<BigDecimal> history) {
        BigDecimal now = money(current);
        BigDecimal before = money(previousOrNull == null ? 0 : previousOrNull);
        BigDecimal delta = previousOrNull == null || before.signum() == 0
                ? null
                : now.subtract(before)
                        .divide(before, PERCENT_SCALE + 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        return new KpiTrend(now, before, delta, history);
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
