package att.ossama.ledgerservice.dashboard;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * How fast the Command Center's own endpoints answer — the analytics product
 * measuring itself (brief 5.3, row 4).
 *
 * <h2>Why not Micrometer's timer</h2>
 * A Micrometer {@code http.server.requests} timer is the obvious source, and
 * this service configures its SLO histogram too (see {@code application.properties}),
 * but a Micrometer counter is cumulative since JVM start: it can answer "how many
 * requests since boot were under 500ms" and not "how many in the last 24 hours".
 * The card asks the second question, so the window has to be kept here.
 *
 * <h2>Why hourly buckets rather than a list of timings</h2>
 * The KPI is a count ratio — the share of requests under the threshold — not a
 * percentile, so the timings never need to be kept individually. Bucketing them
 * by the hour they landed in makes the answer exact for any window and makes
 * memory depend on the retention period rather than on the traffic: eight days
 * is at most 193 buckets, whatever the request rate. A ring buffer of individual
 * timings would need a cap, and a cap would silently shrink the window it claims
 * to cover — at the dashboard's own 5s poll of six endpoints that is roughly
 * 100,000 requests a day, so a 10,000-entry buffer would hold about two hours
 * while still being labelled "last 24 hours".
 *
 * <h2>What it does not cover</h2>
 * The buckets live in the JVM, so the window starts when the service started. A
 * freshly restarted process has no history, and {@link #sampleCountBetween}
 * exists so callers can tell "0% compliant" apart from "no requests recorded"
 * rather than reporting the first when they mean the second.
 */
@Service
public class SlaMetricsService {

    /** The response time the dashboard is expected to answer within. */
    public static final long SLA_THRESHOLD_MS = 500;

    private static final long SLA_THRESHOLD_NANOS = Duration.ofMillis(SLA_THRESHOLD_MS).toNanos();

    /**
     * How long samples are kept. Eight days, not one: the card compares the last
     * 24 hours against the 24 before it and draws a seven-point sparkline, so the
     * oldest instant it ever asks about is eight days back.
     */
    private static final Duration RETENTION = Duration.ofDays(8);

    private static final long HOUR_MILLIS = Duration.ofHours(1).toMillis();

    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    /**
     * The oldest hour still guaranteed to be present. Lets {@link #prune} run
     * only when the clock has actually moved on, rather than on every request.
     */
    private final AtomicLong retainedFromHour = new AtomicLong(Long.MIN_VALUE);

    public SlaMetricsService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records one answered request.
     *
     * @param endpoint     the request path, kept for diagnostics and for a
     *                     future per-endpoint breakdown; the SLA itself is
     *                     computed across all of them
     * @param responseTime how long the service took to handle it
     */
    public void record(String endpoint, Duration responseTime) {
        if (responseTime == null || responseTime.isNegative()) {
            return;
        }
        long hour = hourOf(clock.instant());
        Bucket bucket = buckets.computeIfAbsent(hour, h -> new Bucket());
        bucket.requests.increment();
        if (responseTime.toNanos() < SLA_THRESHOLD_NANOS) {
            bucket.withinSla.increment();
        }
        prune(hour);
    }

    /**
     * Share of the requests answered inside the SLA between {@code from}
     * (exclusive) and {@code to} (inclusive), as a percentage.
     *
     * <p>Zero when nothing was recorded in the window. The caller is expected to
     * check {@link #sampleCountBetween} before presenting that as a measurement —
     * the trend service uses it to leave a gap in the sparkline instead of
     * plotting a compliance of zero for a period the process was not running.
     */
    public double compliancePercentBetween(Instant from, Instant to) {
        long requests = 0;
        long withinSla = 0;
        for (Bucket bucket : bucketsBetween(from, to)) {
            requests += bucket.requests.sum();
            withinSla += bucket.withinSla.sum();
        }
        return requests == 0 ? 0 : 100.0 * withinSla / requests;
    }

    /** How many requests were recorded in the window, whether they met the SLA or not. */
    public long sampleCountBetween(Instant from, Instant to) {
        long requests = 0;
        for (Bucket bucket : bucketsBetween(from, to)) {
            requests += bucket.requests.sum();
        }
        return requests;
    }

    /**
     * The buckets overlapping {@code (from, to]}.
     *
     * <p>Hourly granularity means a boundary bucket can hold samples from either
     * side of it. That is accepted rather than corrected: the windows this is
     * asked about are 24 hours wide, so the worst case is one hour of bleed at
     * each end, and the alternative — keeping per-request timestamps — is the
     * design this class exists to avoid.
     */
    private List<Bucket> bucketsBetween(Instant from, Instant to) {
        long firstHour = hourOf(from);
        long lastHour = hourOf(to);
        List<Bucket> found = new ArrayList<>();
        for (long hour = firstHour; hour <= lastHour; hour++) {
            Bucket bucket = buckets.get(hour);
            if (bucket != null) {
                found.add(bucket);
            }
        }
        return found;
    }

    /** Drops whole hours older than the retention period. */
    private void prune(long currentHour) {
        long cutoff = currentHour - RETENTION.toHours();
        long retained = retainedFromHour.get();
        if (retained != Long.MIN_VALUE && retained >= cutoff) {
            return;
        }
        if (!retainedFromHour.compareAndSet(retained, cutoff)) {
            return; // another thread is already pruning to at least this hour
        }
        buckets.keySet().removeIf(hour -> hour < cutoff);
    }

    private static long hourOf(Instant instant) {
        return Math.floorDiv(instant.toEpochMilli(), HOUR_MILLIS);
    }

    /** One hour's counters. Adders, because every request lands in one of these. */
    private static final class Bucket {
        private final LongAdder requests = new LongAdder();
        private final LongAdder withinSla = new LongAdder();
    }
}
