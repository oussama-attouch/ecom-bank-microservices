package att.ossama.ledgerservice.persistence;

import att.ossama.ledgerservice.dashboard.AnomalyBucket;
import att.ossama.ledgerservice.dashboard.BalanceConcentration;
import att.ossama.ledgerservice.dashboard.EventBucket;
import att.ossama.ledgerservice.dashboard.JournalBucket;
import att.ossama.ledgerservice.dashboard.LedgerAggregates;
import att.ossama.ledgerservice.dashboard.MeasureSpan;
import att.ossama.ledgerservice.dashboard.SagaBucket;
import att.ossama.ledgerservice.persistence.repository.AccountBalanceRow;
import att.ossama.ledgerservice.persistence.repository.EventJpaRepository;
import att.ossama.ledgerservice.persistence.repository.JournalEntryJpaRepository;
import att.ossama.ledgerservice.persistence.repository.SagaStateJpaRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Answers the KPI aggregate questions in SQL.
 *
 * <p>The alternative — and what this replaced — was to load every event, journal
 * entry and saga and sum them in Java. On the 50,000-event portfolio ledger that
 * meant hydrating 50,000 rows and JSON-decoding 50,000 payloads on every
 * dashboard refresh, for numbers a single aggregate returns.
 *
 * <p>Worth knowing when reading the queries: the amount of a money event lives in
 * the {@code payload} JSON, not in a column, so the sum has to extract it —
 * {@code (payload::json ->> 'amount')::numeric}. And {@code event_type} holds the
 * event class's simple name ({@code MoneyCreditedEvent}), not the discriminator
 * inside the payload ({@code MONEY_CREDITED}); filtering on the latter silently
 * matches nothing.
 */
@Component
@Profile("!inmem")
public class JpaLedgerAggregates implements LedgerAggregates {

    /**
     * Stands in for "since the beginning of the log".
     *
     * <p>Sent rather than SQL null: an untyped null parameter leaves Postgres
     * unable to infer the comparison's type, and the alternative — a second query
     * per aggregate with the lower bound omitted — doubles the method count for a
     * bound no real ledger reaches. Every stored event is decades after this.
     */
    private static final Instant BEGINNING_OF_LOG = Instant.EPOCH;

    private final EventJpaRepository events;
    private final JournalEntryJpaRepository journal;
    private final SagaStateJpaRepository sagas;

    public JpaLedgerAggregates(EventJpaRepository events,
                               JournalEntryJpaRepository journal,
                               SagaStateJpaRepository sagas) {
        this.events = events;
        this.journal = journal;
        this.sagas = sagas;
    }

    @Override
    @Transactional(readOnly = true)
    public double netFlowBetween(Instant from, Instant to) {
        return events.netFlowBetween(lower(from), to).doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public long accountsCreatedBetween(Instant from, Instant to) {
        return events.countAccountsCreatedBetween(lower(from), to);
    }

    @Override
    @Transactional(readOnly = true)
    public double journalVolumeBetween(Instant from, Instant to) {
        return journal.sumAmountBetween(lower(from), to).doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public long problemSagasStartedBetween(Instant from, Instant to) {
        return sagas.countProblemStartedBetween(lower(from), to);
    }

    @Override
    @Transactional(readOnly = true)
    public double netCashFlowBetween(Instant from, Instant to) {
        return journal.netCustomerCashFlowBetween(lower(from), to).doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public double averageTransactionValueBetween(Instant from, Instant to) {
        return journal.averageAmountBetween(lower(from), to).doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Double> topBalanceShareAt(List<Instant> instants, int topAccounts) {
        if (instants.isEmpty()) {
            return List.of();
        }

        // The oldest instant is the base: one grouped pass of the log, and every
        // later reading is that plus the movements since. The movements were one
        // indexed range per reading — nine of them, about 280ms on the seeded
        // ledger — and are now one grouped pass replayed forward a day at a time,
        // which is the same arithmetic for a tenth of the reads.
        List<Instant> ordered = instants.stream().sorted().toList();
        Instant base = ordered.get(0);
        Instant newest = ordered.get(ordered.size() - 1);

        Map<String, Double> balances = balancesOf(events.accountBalancesAsOf(base));
        Map<LocalDate, Map<String, Double>> movements = movementsByDay(base, newest);
        List<LocalDate> movementDays = new ArrayList<>(movements.keySet());

        // The readings are handed back in the order they were asked for: the
        // caller picks the cutoff, the current value and the sparkline out of the
        // answer by position, and only the walk needs them sorted.
        Map<Instant, Double> shareByInstant = new HashMap<>();
        int applied = 0;
        for (Instant instant : ordered) {
            LocalDate day = LocalDate.ofInstant(instant, ZoneOffset.UTC);
            while (applied < movementDays.size()) {
                LocalDate movementDay = movementDays.get(applied);
                // A day's movements belong to a reading once the day has ended by
                // it. The exception is the newest reading, which is taken mid-day:
                // it includes the part of today that has already happened, and the
                // grouped read was bounded at that same instant.
                boolean ended = movementDay.isBefore(day);
                boolean todaySoFar = instant.equals(newest) && movementDay.equals(day);
                if (!ended && !todaySoFar) {
                    break;
                }
                for (Map.Entry<String, Double> movement : movements.get(movementDay).entrySet()) {
                    balances.merge(movement.getKey(), movement.getValue(), Double::sum);
                }
                applied++;
            }
            shareByInstant.put(instant, BalanceConcentration.shareOfTop(balances.values(), topAccounts));
        }

        List<Double> shares = new ArrayList<>(instants.size());
        for (Instant instant : instants) {
            shares.add(shareByInstant.get(instant));
        }
        return List.copyOf(shares);
    }

    /** How much each account's balance moved on each day of {@code (from, to]}. */
    private Map<LocalDate, Map<String, Double>> movementsByDay(Instant from, Instant until) {
        Map<LocalDate, Map<String, Double>> movements = new HashMap<>();
        for (Object[] row : events.accountMovementDays(from, until)) {
            Map<String, Double> day = movements.computeIfAbsent(
                    LocalDate.parse((String) row[1]), key -> new HashMap<>());
            day.merge((String) row[0], ((BigDecimal) row[2]).doubleValue(), Double::sum);
        }
        // A plain HashMap has no order to walk, and the walk is the whole point.
        return new TreeMap<>(movements);
    }

    private static Map<String, Double> balancesOf(List<AccountBalanceRow> rows) {
        Map<String, Double> balances = new HashMap<>(rows.size());
        for (AccountBalanceRow row : rows) {
            balances.put(row.getAccountId(), row.getBalance().doubleValue());
        }
        return balances;
    }

    @Override
    @Transactional(readOnly = true)
    public long largeTransfersBetween(Instant from, Instant to, double threshold) {
        return journal.countAboveAmountBetween(lower(from), to, BigDecimal.valueOf(threshold));
    }

    @Override
    @Transactional(readOnly = true)
    public double sagaSuccessRateBetween(Instant from, Instant to) {
        return sagas.successRateStartedBetween(lower(from), to).doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public double averageSagaDurationMillisBetween(Instant from, Instant to) {
        return sagas.averageDurationMillisStartedBetween(lower(from), to).doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public double p95SagaDurationMillisBetween(Instant from, Instant to) {
        return sagas.p95DurationMillisStartedBetween(lower(from), to).doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public double compensationRateBetween(Instant from, Instant to) {
        return sagas.compensationRateStartedBetween(lower(from), to).doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public double sagaJournalReconciliationBetween(Instant from, Instant to) {
        return journal.sagaJournalReconciliationBetween(lower(from), to).doubleValue();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The baseline start is computed here rather than as SQL interval
     * arithmetic on {@code to}, so the trailing window is one subtraction in
     * Java and the query stays a pair of plain timestamp bounds the planner can
     * use the index for.
     */
    @Override
    @Transactional(readOnly = true)
    public double anomalyRateBetween(Instant from, Instant to, int baselineDays) {
        Instant baselineFrom = to.minus(Duration.ofDays(baselineDays));
        return journal.anomalyRateBetween(baselineFrom, lower(from), to).doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public double auditTrailCompletenessBetween(Instant from, Instant to) {
        return journal.auditTrailCompletenessBetween(lower(from), to).doubleValue();
    }

    // -----------------------------------------------------------------------
    // Bucketed reads — one pass per table, whatever the range
    // -----------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<JournalBucket> journalBuckets(MeasureSpan span, double largeTransferThreshold) {
        List<Object[]> rows = journal.journalBuckets(lower(span.spanFrom()), span.splitAt(), span.to(),
                span.bucket().unit(), BigDecimal.valueOf(largeTransferThreshold));

        List<JournalBucket> buckets = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            buckets.add(new JournalBucket(
                    bucketStart(row[0]),
                    (Boolean) row[1],
                    number(row[2]),
                    count(row[3]),
                    number(row[4]),
                    count(row[5]),
                    count(row[6]),
                    count(row[7]),
                    count(row[8])));
        }
        return buckets;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventBucket> eventBuckets(MeasureSpan span) {
        List<Object[]> rows = events.eventBuckets(lower(span.spanFrom()), span.splitAt(), span.to(),
                span.bucket().unit());

        List<EventBucket> buckets = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            buckets.add(new EventBucket(
                    bucketStart(row[0]),
                    (Boolean) row[1],
                    number(row[2]),
                    count(row[3])));
        }
        return buckets;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SagaBucket> sagaBuckets(MeasureSpan span) {
        List<Object[]> rows = sagas.sagaBuckets(lower(span.spanFrom()), span.splitAt(), span.to(),
                span.bucket().unit());

        List<SagaBucket> buckets = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            buckets.add(new SagaBucket(
                    bucketStart(row[0]),
                    (Boolean) row[1],
                    count(row[2]),
                    count(row[3]),
                    count(row[4]),
                    count(row[5]),
                    number(row[6]),
                    count(row[7]),
                    number(row[8])));
        }
        return buckets;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnomalyBucket> anomalyBuckets(MeasureSpan span, int baselineDays) {
        // The baseline trails the oldest row read, so the first bucket has a
        // month of "what is normal" behind it too — the same rule the
        // single-window aggregate applies at the other end of the span.
        Instant baselineFrom = lower(span.spanFrom()).minus(Duration.ofDays(baselineDays));

        List<Object[]> rows = journal.anomalyBuckets(baselineFrom, lower(span.spanFrom()), span.splitAt(),
                span.to(), span.bucket().unit());

        List<AnomalyBucket> buckets = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            buckets.add(new AnomalyBucket(
                    bucketStart(row[0]),
                    (Boolean) row[1],
                    count(row[2]),
                    count(row[3])));
        }
        return buckets;
    }

    /**
     * The bucket's first day, from the {@code YYYY-MM-DD} label the grouped
     * queries produce.
     *
     * <p>A string rather than a date for the same reason the chart series use
     * one: the grouping has already been done in the database's own calendar, and
     * reading the label back as an instant would put the point through a second
     * time zone conversion on the way out.
     */
    private static LocalDate bucketStart(Object value) {
        return LocalDate.parse((String) value);
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    private static long count(Object value) {
        return ((Number) value).longValue();
    }

    private static Instant lower(Instant from) {
        return from == null ? BEGINNING_OF_LOG : from;
    }
}
