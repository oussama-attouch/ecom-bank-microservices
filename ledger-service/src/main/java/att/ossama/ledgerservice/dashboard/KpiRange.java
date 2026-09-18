package att.ossama.ledgerservice.dashboard;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The period the Command Center is showing — the header selector's five ranges,
 * and everything the KPI payload needs to know about one of them.
 *
 * <h2>The two windows a range defines</h2>
 * Every KPI compares two periods of the same length: the selected range, from
 * {@code now - lookback} to {@code now}, and the range immediately before it.
 * The comparison is like-for-like by construction, which is why the previous
 * window is derived from the current one rather than being a range of its own.
 * {@link #ALL} has no previous window — nothing precedes the whole log — and its
 * KPIs report no percentage rather than a fabricated one.
 *
 * <h2>Why the bucket widths differ per range</h2>
 * A year drawn as 365 points in a 72&nbsp;px inline sparkline is a smear, so the
 * longer ranges are rolled up: daily for 7d and 30d, weekly for 90d, monthly for
 * 1y and all. The point counts are fixed rather than read off the data so that
 * every card's sparkline has the same shape whatever KPI it belongs to. The
 * figures are the ones the charts already use — thirteen whole weeks, twelve
 * months — so a card and the chart beside it are describing the same roll-up.
 *
 * <p>{@link #ALL} is the one range whose length is not known in advance, and it
 * is capped at the last twenty-four months rather than reaching back to the
 * ledger's first row: a sparkline is a trend, not an archive, and an unbounded
 * series would grow with the ledger for no added meaning.
 *
 * <h2>Buckets are calendar-aligned, and a day is a UTC day</h2>
 * Point boundaries are aligned to the calendar the database groups by —
 * {@code date_trunc('week')} weeks start on Monday, {@code date_trunc('month')}
 * months on the first — so a point can be matched to the rows behind it by its
 * start date alone. The ledger stores its timestamps as UTC wall-clock (verified
 * against the API's own rendering of the same rows), so a "day" here is a UTC
 * calendar day: the same day boundary the grouped SQL uses, and the same one the
 * chart-series endpoint already labels.
 */
public enum KpiRange {

    SEVEN_DAYS("7d", 7, Bucket.DAY, 7),
    THIRTY_DAYS("30d", 30, Bucket.DAY, 30),
    NINETY_DAYS("90d", 90, Bucket.WEEK, 13),
    ONE_YEAR("1y", 365, Bucket.MONTH, 12),
    ALL("all", null, Bucket.MONTH, 24);

    /** How wide one sparkline point is, and how the database groups the rows behind it. */
    public enum Bucket {

        DAY("day") {
            @Override
            public LocalDate align(LocalDate day) {
                return day;
            }

            @Override
            public LocalDate shift(LocalDate day, int buckets) {
                return day.minusDays(buckets);
            }
        },

        /**
         * Monday-based weeks, matching Postgres's {@code date_trunc('week')} —
         * ISO weeks, not the Sunday-first weeks some locales read a calendar in.
         */
        WEEK("week") {
            @Override
            public LocalDate align(LocalDate day) {
                return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            }

            @Override
            public LocalDate shift(LocalDate day, int buckets) {
                return day.minusWeeks(buckets);
            }
        },

        MONTH("month") {
            @Override
            public LocalDate align(LocalDate day) {
                return day.withDayOfMonth(1);
            }

            @Override
            public LocalDate shift(LocalDate day, int buckets) {
                return day.minusMonths(buckets);
            }
        };

        private final String unit;

        Bucket(String unit) {
            this.unit = unit;
        }

        /** The unit this bucket is grouped by, spelled as Postgres's {@code date_trunc} spells it. */
        public String unit() {
            return unit;
        }

        /** The start of the bucket that holds {@code day}. */
        public abstract LocalDate align(LocalDate day);

        /** The start of the bucket {@code buckets} whole buckets before {@code day}'s. */
        public abstract LocalDate shift(LocalDate day, int buckets);
    }

    private final String token;
    private final Integer lookbackDays;
    private final Bucket bucket;
    private final int points;

    KpiRange(String token, Integer lookbackDays, Bucket bucket, int points) {
        this.token = token;
        this.lookbackDays = lookbackDays;
        this.bucket = bucket;
        this.points = points;
    }

    /** The token the endpoint accepts and the client sends, e.g. {@code 30d}. */
    public String token() {
        return token;
    }

    /** How wide one sparkline point is for this range. */
    public Bucket bucket() {
        return bucket;
    }

    /** How many points the sparkline holds for this range. */
    public int points() {
        return points;
    }

    /** True when a period precedes this one to compare against. */
    public boolean hasPrevious() {
        return lookbackDays != null;
    }

    /** The start of the selected window: {@code null} for {@link #ALL}, which begins with the log. */
    public Instant from(Instant now) {
        return lookbackDays == null ? null : now.minus(Duration.ofDays(lookbackDays));
    }

    /**
     * The start of the comparison window — the same length, immediately before
     * the selected one — or {@code null} for {@link #ALL}.
     */
    public Instant previousFrom(Instant now) {
        if (lookbackDays == null) {
            return null;
        }
        return now.minus(Duration.ofDays(2L * lookbackDays));
    }

    /**
     * The start of each sparkline point, oldest first, the last being the bucket
     * that holds {@code today}.
     *
     * <p>Aligned to the bucket's own calendar and counted back from today rather
     * than forward from the window's start, so the last point is always the
     * period in progress and no point is a partial fragment of a calendar week or
     * month. The whole axis sits inside the selected window for every range: the
     * oldest point starts no earlier than {@code now - lookback}.
     */
    public List<LocalDate> axis(LocalDate today) {
        LocalDate last = bucket.align(today);
        List<LocalDate> starts = new ArrayList<>(points);
        for (int i = points - 1; i >= 0; i--) {
            starts.add(bucket.shift(last, i));
        }
        return List.copyOf(starts);
    }

    /**
     * The range a request asked for.
     *
     * <p>Case-insensitive, and blank means the default — the endpoint's own
     * {@code defaultValue} only covers a missing parameter, not an empty one.
     * An unknown token is a client error, so it is rejected rather than quietly
     * served as the default: a typo would otherwise show a week of data under a
     * year's heading.
     *
     * @throws IllegalArgumentException for a token the selector does not offer,
     *         which {@code ApiExceptionHandler} turns into a 400.
     */
    public static KpiRange parse(String requested) {
        if (requested == null || requested.isBlank()) {
            return SEVEN_DAYS;
        }
        String normalised = requested.trim().toLowerCase(Locale.ROOT);
        for (KpiRange range : values()) {
            if (range.token.equals(normalised)) {
                return range;
            }
        }
        throw new IllegalArgumentException("Unknown range: " + requested);
    }
}
