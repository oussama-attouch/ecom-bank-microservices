package att.ossama.ledgerservice.dashboard;

import att.ossama.ledgerservice.domain.AccountCreatedEvent;
import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.JournalEntry;
import att.ossama.ledgerservice.domain.MoneyCreditedEvent;
import att.ossama.ledgerservice.domain.MoneyDebitedEvent;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.saga.SagaRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Answers the KPI aggregate questions by replaying the stores in memory.
 *
 * <p>This is the original implementation of the aggregates, kept for the
 * {@code inmem} profile — which has no database to aggregate in — and used by the
 * KPI tests, so the windowing rules can be exercised without Postgres. The
 * application's default profile uses {@link att.ossama.ledgerservice.persistence.JpaLedgerAggregates},
 * which answers the same questions with SQL.
 *
 * <p>The window predicate here and the one in the SQL version are deliberately
 * identical — {@code (from, to]}, with a null {@code from} meaning the beginning
 * of the log — because the two must agree: the tests assert the trend rules
 * against this implementation, and the dashboard reads the other.
 */
@Component
@Profile("inmem")
public class ReplayLedgerAggregates implements LedgerAggregates {

    private final EventStore eventStore;
    private final JournalService journalService;
    private final SagaRepository sagaRepository;

    public ReplayLedgerAggregates(EventStore eventStore,
                                  JournalService journalService,
                                  SagaRepository sagaRepository) {
        this.eventStore = eventStore;
        this.journalService = journalService;
        this.sagaRepository = sagaRepository;
    }

    @Override
    public double netFlowBetween(Instant from, Instant to) {
        double total = 0;
        for (Event event : eventStore.allEvents()) {
            if (!within(event.getOccurredAt(), from, to)) {
                continue;
            }
            if (event instanceof MoneyCreditedEvent credited) {
                total += credited.getAmount();
            } else if (event instanceof MoneyDebitedEvent debited) {
                total -= debited.getAmount();
            }
        }
        return total;
    }

    @Override
    public long accountsCreatedBetween(Instant from, Instant to) {
        return eventStore.allEvents().stream()
                .filter(event -> event instanceof AccountCreatedEvent)
                .filter(event -> within(event.getOccurredAt(), from, to))
                .map(Event::aggregateId)
                .distinct()
                .count();
    }

    @Override
    public double journalVolumeBetween(Instant from, Instant to) {
        List<JournalEntry> entries = journalService.entries();
        double total = 0;
        for (JournalEntry entry : entries) {
            if (within(entry.getCreatedAt(), from, to)) {
                total += entry.getAmount();
            }
        }
        return total;
    }

    @Override
    public long problemSagasStartedBetween(Instant from, Instant to) {
        return sagaRepository.findAll().stream()
                .filter(saga -> isProblem(saga.getStatus()))
                .filter(saga -> within(saga.getStartedAt(), from, to))
                .count();
    }

    @Override
    public double netCashFlowBetween(Instant from, Instant to) {
        double net = 0;
        for (JournalEntry entry : journalService.entries()) {
            if (!within(entry.getCreatedAt(), from, to)) {
                continue;
            }
            if (isCustomerAccount(entry.getCreditAccountId())) {
                net += entry.getAmount();
            }
            if (isCustomerAccount(entry.getDebitAccountId())) {
                net -= entry.getAmount();
            }
        }
        return net;
    }

    @Override
    public double averageTransactionValueBetween(Instant from, Instant to) {
        double total = 0;
        long count = 0;
        for (JournalEntry entry : journalService.entries()) {
            if (within(entry.getCreatedAt(), from, to)) {
                total += entry.getAmount();
                count++;
            }
        }
        return count == 0 ? 0 : total / count;
    }

    @Override
    public List<Double> topBalanceShareAt(List<Instant> instants, int topAccounts) {
        List<Double> shares = new ArrayList<>(instants.size());
        for (Instant instant : instants) {
            shares.add(BalanceConcentration.shareOfTop(balancesAsOf(instant).values(), topAccounts));
        }
        return List.copyOf(shares);
    }

    /** Every account's balance as the log stood at {@code asOf}. */
    private Map<String, Double> balancesAsOf(Instant asOf) {
        Map<String, Double> balances = new HashMap<>();
        for (Event event : eventStore.allEvents()) {
            if (!within(event.getOccurredAt(), null, asOf)) {
                continue;
            }
            if (event instanceof MoneyCreditedEvent credited) {
                balances.merge(credited.getAccountId(), credited.getAmount(), Double::sum);
            } else if (event instanceof MoneyDebitedEvent debited) {
                balances.merge(debited.getAccountId(), -debited.getAmount(), Double::sum);
            }
        }
        return balances;
    }

    @Override
    public long largeTransfersBetween(Instant from, Instant to, double threshold) {
        return journalService.entries().stream()
                .filter(entry -> within(entry.getCreatedAt(), from, to))
                .filter(entry -> entry.getAmount() > threshold)
                .count();
    }

    @Override
    public double sagaSuccessRateBetween(Instant from, Instant to) {
        List<SagaState> started = sagasStartedBetween(from, to);
        if (started.isEmpty()) {
            return 0;
        }
        long completed = started.stream().filter(saga -> saga.getStatus() == SagaStatus.COMPLETED).count();
        return 100.0 * completed / started.size();
    }

    @Override
    public double averageSagaDurationMillisBetween(Instant from, Instant to) {
        List<Double> durations = finishedDurationsBetween(from, to);
        if (durations.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (double duration : durations) {
            total += duration;
        }
        return total / durations.size();
    }

    @Override
    public double p95SagaDurationMillisBetween(Instant from, Instant to) {
        List<Double> durations = finishedDurationsBetween(from, to);
        if (durations.isEmpty()) {
            return 0;
        }
        durations.sort(Comparator.naturalOrder());
        return percentile(durations, 0.95);
    }

    @Override
    public double compensationRateBetween(Instant from, Instant to) {
        List<SagaState> started = sagasStartedBetween(from, to);
        if (started.isEmpty()) {
            return 0;
        }
        long compensating = started.stream().filter(saga -> saga.getStatus() == SagaStatus.COMPENSATING).count();
        return 100.0 * compensating / started.size();
    }

    @Override
    public double sagaJournalReconciliationBetween(Instant from, Instant to) {
        // The ids are materialised once rather than probed per entry: the saga
        // store is a list here, so a `contains` per posting would be a scan per
        // posting, and both sides are read once either way.
        Set<String> knownTransactions = sagaRepository.findAll().stream()
                .map(SagaState::getTransactionId)
                .collect(Collectors.toSet());

        long transfers = 0;
        long matched = 0;
        for (JournalEntry entry : journalService.entries()) {
            if (!within(entry.getCreatedAt(), from, to) || !isTransfer(entry)) {
                continue;
            }
            transfers++;
            if (knownTransactions.contains(entry.getTransactionId())) {
                matched++;
            }
        }
        return transfers == 0 ? 0 : 100.0 * matched / transfers;
    }

    @Override
    public double anomalyRateBetween(Instant from, Instant to, int baselineDays) {
        Instant baselineFrom = to.minus(Duration.ofDays(baselineDays));

        // Two passes over the same list, each with the window it needs: the
        // baseline's mean and spread, then the window judged against them. The
        // SQL version joins the two into one query; the arithmetic is identical.
        double sum = 0;
        long baselineCount = 0;
        for (JournalEntry entry : journalService.entries()) {
            if (within(entry.getCreatedAt(), baselineFrom, to)) {
                sum += entry.getAmount();
                baselineCount++;
            }
        }
        if (baselineCount == 0) {
            return 0;
        }
        double mean = sum / baselineCount;

        double squaredDeviations = 0;
        for (JournalEntry entry : journalService.entries()) {
            if (within(entry.getCreatedAt(), baselineFrom, to)) {
                double deviation = entry.getAmount() - mean;
                squaredDeviations += deviation * deviation;
            }
        }
        // Sample standard deviation, matching Postgres's STDDEV (and not
        // STDDEV_POP): the two implementations have to agree, because the tests
        // pin the rule against this one and the dashboard reads the other.
        if (baselineCount < 2) {
            return 0;
        }
        double sd = Math.sqrt(squaredDeviations / (baselineCount - 1));
        if (sd == 0) {
            return 0;
        }

        long windowCount = 0;
        long outliers = 0;
        for (JournalEntry entry : journalService.entries()) {
            if (!within(entry.getCreatedAt(), from, to)) {
                continue;
            }
            windowCount++;
            if (Math.abs(entry.getAmount() - mean) > 3 * sd) {
                outliers++;
            }
        }
        return windowCount == 0 ? 0 : 100.0 * outliers / windowCount;
    }

    @Override
    public double auditTrailCompletenessBetween(Instant from, Instant to) {
        long total = 0;
        long complete = 0;
        for (JournalEntry entry : journalService.entries()) {
            if (!within(entry.getCreatedAt(), from, to)) {
                continue;
            }
            total++;
            if (hasCompleteAuditTrail(entry)) {
                complete++;
            }
        }
        return total == 0 ? 0 : 100.0 * complete / total;
    }

    // -----------------------------------------------------------------------
    // Bucketed reads — the range-aware path
    //
    // The same four questions the SQL implementation asks in one grouped pass
    // per table, answered by grouping the replayed stores in memory. The bucket
    // boundaries come from KpiRange.Bucket, so the two implementations agree on
    // what a "week" is as well as on what a window is.
    // -----------------------------------------------------------------------

    @Override
    public List<JournalBucket> journalBuckets(MeasureSpan span, double largeTransferThreshold) {
        // The ids are materialised once: the saga store is a list here, so a
        // lookup per transfer posting would be a scan per posting.
        Set<String> knownTransactions = sagaRepository.findAll().stream()
                .map(SagaState::getTransactionId)
                .collect(Collectors.toSet());

        Map<Slot, JournalTotals> buckets = new HashMap<>();
        for (JournalEntry entry : journalService.entries()) {
            if (!within(entry.getCreatedAt(), span.spanFrom(), span.to())) {
                continue;
            }
            JournalTotals totals = buckets.computeIfAbsent(
                    slotOf(entry.getCreatedAt(), span), key -> new JournalTotals());
            totals.volume += entry.getAmount();
            totals.postings++;
            if (isCustomerAccount(entry.getCreditAccountId())) {
                totals.customerCashFlow += entry.getAmount();
            }
            if (isCustomerAccount(entry.getDebitAccountId())) {
                totals.customerCashFlow -= entry.getAmount();
            }
            if (entry.getAmount() > largeTransferThreshold) {
                totals.largeTransfers++;
            }
            if (isTransfer(entry)) {
                totals.transferPostings++;
                if (knownTransactions.contains(entry.getTransactionId())) {
                    totals.reconciledTransfers++;
                }
            }
            if (hasCompleteAuditTrail(entry)) {
                totals.auditedPostings++;
            }
        }

        List<JournalBucket> rows = new ArrayList<>(buckets.size());
        for (Map.Entry<Slot, JournalTotals> bucket : ordered(buckets).entrySet()) {
            JournalTotals totals = bucket.getValue();
            rows.add(new JournalBucket(bucket.getKey().start(), bucket.getKey().current(),
                    totals.volume, totals.postings, totals.customerCashFlow, totals.largeTransfers,
                    totals.transferPostings, totals.reconciledTransfers, totals.auditedPostings));
        }
        return List.copyOf(rows);
    }

    @Override
    public List<EventBucket> eventBuckets(MeasureSpan span) {
        Map<Slot, EventTotals> buckets = new HashMap<>();
        for (Event event : eventStore.allEvents()) {
            if (!within(event.getOccurredAt(), span.spanFrom(), span.to())) {
                continue;
            }
            EventTotals totals = buckets.computeIfAbsent(
                    slotOf(event.getOccurredAt(), span), key -> new EventTotals());
            if (event instanceof MoneyCreditedEvent credited) {
                totals.netFlow += credited.getAmount();
            } else if (event instanceof MoneyDebitedEvent debited) {
                totals.netFlow -= debited.getAmount();
            } else if (event instanceof AccountCreatedEvent) {
                totals.accounts.add(event.aggregateId());
            }
        }

        List<EventBucket> rows = new ArrayList<>(buckets.size());
        for (Map.Entry<Slot, EventTotals> bucket : ordered(buckets).entrySet()) {
            EventTotals totals = bucket.getValue();
            rows.add(new EventBucket(bucket.getKey().start(), bucket.getKey().current(),
                    totals.netFlow, totals.accounts.size()));
        }
        return List.copyOf(rows);
    }

    @Override
    public List<SagaBucket> sagaBuckets(MeasureSpan span) {
        Map<Slot, SagaTotals> buckets = new HashMap<>();
        for (SagaState saga : sagaRepository.findAll()) {
            if (!within(saga.getStartedAt(), span.spanFrom(), span.to())) {
                continue;
            }
            SagaTotals totals = buckets.computeIfAbsent(
                    slotOf(saga.getStartedAt(), span), key -> new SagaTotals());
            totals.started++;
            if (saga.getStatus() == SagaStatus.COMPLETED) {
                totals.completed++;
            }
            if (saga.getStatus() == SagaStatus.COMPENSATING) {
                totals.compensating++;
            }
            if (isProblem(saga.getStatus())) {
                totals.problem++;
            }
            if (saga.getCompletedAt() != null) {
                totals.durations.add(Duration.between(saga.getStartedAt(), saga.getCompletedAt()).toNanos() / 1_000_000.0);
            }
        }

        List<SagaBucket> rows = new ArrayList<>(buckets.size());
        for (Map.Entry<Slot, SagaTotals> bucket : ordered(buckets).entrySet()) {
            SagaTotals totals = bucket.getValue();
            List<Double> durations = totals.durations;
            double sum = 0;
            for (double duration : durations) {
                sum += duration;
            }
            rows.add(new SagaBucket(bucket.getKey().start(), bucket.getKey().current(),
                    totals.started, totals.completed, totals.compensating, totals.problem,
                    sum, durations.size(), percentileOf(durations)));
        }
        return List.copyOf(rows);
    }

    @Override
    public List<AnomalyBucket> anomalyBuckets(MeasureSpan span, int baselineDays) {
        Instant baselineFrom = (span.spanFrom() == null ? Instant.EPOCH : span.spanFrom())
                .minus(Duration.ofDays(baselineDays));
        List<JournalEntry> entries = journalService.entries();

        // One baseline for the whole series — the trailing month ending where the
        // span ends — matching the SQL's single CROSS JOINed aggregate.
        double sum = 0;
        long count = 0;
        for (JournalEntry entry : entries) {
            if (within(entry.getCreatedAt(), baselineFrom, span.to())) {
                sum += entry.getAmount();
                count++;
            }
        }
        if (count < 2) {
            return List.of();
        }
        double mean = sum / count;

        double squaredDeviations = 0;
        for (JournalEntry entry : entries) {
            if (within(entry.getCreatedAt(), baselineFrom, span.to())) {
                double deviation = entry.getAmount() - mean;
                squaredDeviations += deviation * deviation;
            }
        }
        double sd = Math.sqrt(squaredDeviations / (count - 1));
        if (sd == 0) {
            return List.of();
        }

        Map<Slot, AnomalyTotals> buckets = new HashMap<>();
        for (JournalEntry entry : entries) {
            if (!within(entry.getCreatedAt(), span.spanFrom(), span.to())) {
                continue;
            }
            AnomalyTotals totals = buckets.computeIfAbsent(
                    slotOf(entry.getCreatedAt(), span), key -> new AnomalyTotals());
            totals.postings++;
            if (Math.abs(entry.getAmount() - mean) > 3 * sd) {
                totals.outliers++;
            }
        }

        List<AnomalyBucket> rows = new ArrayList<>(buckets.size());
        for (Map.Entry<Slot, AnomalyTotals> bucket : ordered(buckets).entrySet()) {
            AnomalyTotals totals = bucket.getValue();
            rows.add(new AnomalyBucket(bucket.getKey().start(), bucket.getKey().current(),
                    totals.outliers, totals.postings));
        }
        return List.copyOf(rows);
    }

    /** The p95 of a bucket's finished sagas, zero when it has none. */
    private static double percentileOf(List<Double> durations) {
        if (durations.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(durations);
        sorted.sort(Comparator.naturalOrder());
        return percentile(sorted, 0.95);
    }

    /** Which bucket a row belongs to, and which side of the window split it falls on. */
    private record Slot(LocalDate start, boolean current) {
    }

    private static Slot slotOf(Instant at, MeasureSpan span) {
        return new Slot(span.bucket().align(utcDay(at)), at.isAfter(span.splitAt()));
    }

    private static LocalDate utcDay(Instant at) {
        return LocalDate.ofInstant(at, ZoneOffset.UTC);
    }

    /** The buckets in the order the grouped SQL returns them: oldest first, previous side first. */
    private static <T> Map<Slot, T> ordered(Map<Slot, T> buckets) {
        Map<Slot, T> sorted = new TreeMap<>(Comparator.comparing(Slot::start).thenComparing(Slot::current));
        sorted.putAll(buckets);
        return sorted;
    }

    private static final class JournalTotals {
        private double volume;
        private long postings;
        private double customerCashFlow;
        private long largeTransfers;
        private long transferPostings;
        private long reconciledTransfers;
        private long auditedPostings;
    }

    private static final class EventTotals {
        private double netFlow;
        private final Set<String> accounts = new HashSet<>();
    }

    private static final class SagaTotals {
        private long started;
        private long completed;
        private long compensating;
        private long problem;
        private final List<Double> durations = new ArrayList<>();
    }

    private static final class AnomalyTotals {
        private long outliers;
        private long postings;
    }

    /**
     * The four fields that make a posting attributable, mirroring the SQL's
     * {@code IS NOT NULL} conjunction.
     */
    private static boolean hasCompleteAuditTrail(JournalEntry entry) {
        return entry.getPostedBy() != null
                && entry.getTransactionId() != null
                && entry.getDebitAccountId() != null
                && entry.getCreditAccountId() != null;
    }

    /**
     * The account-id rule the SQL spells as an equality against
     * {@code JournalService.TRANSFER_CLEARING}: a leg against the clearing
     * account is a transfer, whichever of the four legs it is.
     */
    private static boolean isTransfer(JournalEntry entry) {
        return JournalService.TRANSFER_CLEARING.equals(entry.getDebitAccountId())
                || JournalService.TRANSFER_CLEARING.equals(entry.getCreditAccountId());
    }

    /** The sagas that started inside the window, whatever they have done since. */
    private List<SagaState> sagasStartedBetween(Instant from, Instant to) {
        return sagaRepository.findAll().stream()
                .filter(saga -> within(saga.getStartedAt(), from, to))
                .toList();
    }

    /** Durations in milliseconds of those of them that have finished, in store order. */
    private List<Double> finishedDurationsBetween(Instant from, Instant to) {
        List<Double> durations = new ArrayList<>();
        for (SagaState saga : sagasStartedBetween(from, to)) {
            Instant completedAt = saga.getCompletedAt();
            if (completedAt != null) {
                durations.add(Duration.between(saga.getStartedAt(), completedAt).toNanos() / 1_000_000.0);
            }
        }
        return durations;
    }

    /**
     * The interpolated percentile Postgres's {@code percentile_cont} computes, so
     * that the in-memory and the SQL reading of p95 agree: the fractional position
     * is taken over the sorted durations and its two neighbours are mixed. On the
     * handful of sagas a 7-day window holds, a discrete percentile would jump
     * between whichever sagas happened to be slowest.
     *
     * @param sorted durations in ascending order
     */
    private static double percentile(List<Double> sorted, double fraction) {
        double position = fraction * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double weight = position - lower;
        return sorted.get(lower) * (1 - weight) + sorted.get(upper) * weight;
    }

    /** The account-id rule the SQL aggregates spell as {@code LIKE 'ACC-%'}. */
    private static boolean isCustomerAccount(String accountId) {
        return accountId != null && accountId.startsWith("ACC-");
    }

    /**
     * The window is half-open: {@code from} is exclusive so the two adjacent
     * windows a comparison uses never both count the event that lands exactly on
     * the cutoff, and {@code to} is inclusive so an event stamped "now" is counted
     * in the current period rather than dropped by clock skew.
     */
    private static boolean within(Instant at, Instant from, Instant to) {
        if (at == null || at.isAfter(to)) {
            return false;
        }
        return from == null || at.isAfter(from);
    }

    private static boolean isProblem(SagaStatus status) {
        return status == SagaStatus.COMPENSATING || status == SagaStatus.FAILED;
    }
}
