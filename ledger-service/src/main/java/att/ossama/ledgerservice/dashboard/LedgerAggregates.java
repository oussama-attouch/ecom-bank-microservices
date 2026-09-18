package att.ossama.ledgerservice.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * The aggregate questions the KPI trends ask of the ledger.
 *
 * <p>Every one is answerable with a single SQL aggregate, which is the point:
 * the Command Center runs on a 50,000-event ledger, and answering these by
 * loading the log into memory meant hydrating every row and JSON-decoding every
 * event payload (the payload column, not a set of indexed columns, holds the
 * amount). That cost roughly eight seconds per dashboard refresh and grew with
 * the ledger; the same questions as aggregates are milliseconds.
 *
 * <p>Windows are half-open — {@code (from, to]} — matching how the KPI logic
 * partitions adjacent periods: a movement exactly on a boundary belongs to
 * exactly one side. A {@code null} {@code from} means "since the beginning of the
 * log", which is how a cumulative level is taken as of an instant.
 *
 * <p>Two implementations exist: the SQL one the application runs, and a replay
 * one for the in-memory profile and for tests, so the KPI rules can be exercised
 * without a database.
 */
public interface LedgerAggregates {

    // -----------------------------------------------------------------------
    // Bucketed reads — what a range-aware refresh runs
    //
    // One call per source table, grouped by the range's bucket, with the two
    // comparison windows split in SQL. The per-KPI questions above are then
    // arithmetic over these rows rather than queries of their own: the Command
    // Center now asks for any of five ranges, and the implementation it replaced
    // asked one aggregate per KPI per day of sparkline — about 107 queries for a
    // week, and 30 to 365 times that for the longer ranges.
    //
    // The rows are the same aggregates the methods above return, only grouped
    // and widened: each bucket carries every measure its table can answer, so
    // one scan serves six cards instead of six scans serving one each.
    // -----------------------------------------------------------------------

    /**
     * The journal's measures per bucket, for the postings inside {@code span}.
     *
     * <p>One grouped pass of the largest table in the ledger. Grouped rows whose
     * bucket straddles the window boundary are returned once per side, so the
     * window totals stay exact and a history point can still be made whole.
     *
     * @param largeTransferThreshold the amount above which a posting counts as a
     *        large transfer, which is a KPI rule rather than a property of the
     *        table and so is owned by the caller
     */
    List<JournalBucket> journalBuckets(MeasureSpan span, double largeTransferThreshold);

    /**
     * The event log's measures per bucket.
     *
     * <p>Read over whatever {@code span} covers, which for the level KPIs is the
     * whole log: a cumulative position cannot be rebuilt from a window of its own
     * movements.
     */
    List<EventBucket> eventBuckets(MeasureSpan span);

    /** The saga store's measures per bucket, the percentile included. */
    List<SagaBucket> sagaBuckets(MeasureSpan span);

    /**
     * The anomaly card's postings per bucket, judged against the trailing
     * {@code baselineDays} ending at the span's end.
     *
     * @param baselineDays how far back the "what is normal here" baseline reaches
     */
    List<AnomalyBucket> anomalyBuckets(MeasureSpan span, int baselineDays);

    /**
     * Net assets moved over {@code (from, to]}: credits minus debits.
     *
     * <p>Called with a {@code null} lower bound this is the cumulative balance as
     * of {@code to}.
     */
    double netFlowBetween(Instant from, Instant to);

    /** Distinct accounts opened within {@code (from, to]}. */
    long accountsCreatedBetween(Instant from, Instant to);

    /** Total journal amount posted within {@code (from, to]}. */
    double journalVolumeBetween(Instant from, Instant to);

    /** Sagas that started within {@code (from, to]} and are still in a problem state. */
    long problemSagasStartedBetween(Instant from, Instant to);

    // -----------------------------------------------------------------------
    // Financial KPIs — Command Center row 2
    // -----------------------------------------------------------------------

    /**
     * Net cash flow over {@code (from, to]}: money credited to customer accounts
     * minus money debited from them.
     *
     * <p>Positive means the customer ledger took money in over the window. Only
     * the customer legs count, for the same reason the cash-flow chart counts
     * only those: the cash and clearing legs are the other side of the same
     * postings, and a transfer between two customers contributes its two legs
     * with opposite signs, so it nets to zero rather than reading as movement.
     */
    double netCashFlowBetween(Instant from, Instant to);

    /**
     * Mean journal amount posted within {@code (from, to]}.
     *
     * <p>Zero for a window with nothing in it, which the card shows as a level
     * with no baseline rather than as a collapse to nothing.
     */
    double averageTransactionValueBetween(Instant from, Instant to);

    /**
     * How concentrated the ledger's balances are: for each of {@code instants},
     * in the order given, the percentage of all positive account balances held
     * by the {@code topAccounts} largest ones.
     *
     * <p>A series in one call rather than a reading per instant, because the KPI
     * wants eight of them — the cutoff for its baseline, now, and one per day of
     * the sparkline — and each reading on its own is a full grouped pass of the
     * log. The readings nest, so one pass is enough: the balance at an instant is
     * the balance at the oldest of them plus the movements since, which is
     * arithmetic rather than an approximation.
     *
     * <p>Unlike the cumulative levels, a share cannot be recovered from the value
     * now by subtracting what happened after it, which is why this KPI gets its
     * own question. A rising share means fewer accounts hold more of the ledger,
     * which is the risk the card exists to surface.
     */
    List<Double> topBalanceShareAt(List<Instant> instants, int topAccounts);

    /** Journal entries above {@code threshold} posted within {@code (from, to]}. */
    long largeTransfersBetween(Instant from, Instant to, double threshold);

    // -----------------------------------------------------------------------
    // Operational KPIs — Command Center row 3
    // -----------------------------------------------------------------------

    /**
     * Sagas started within {@code (from, to]} that reached COMPLETED, as a
     * percentage of every saga started in that window.
     *
     * <p>Zero when nothing started: an empty window has no rate, and the trend
     * logic withholds the percentage for a zero baseline anyway.
     */
    double sagaSuccessRateBetween(Instant from, Instant to);

    /**
     * Mean duration, in milliseconds, of the sagas started within
     * {@code (from, to]} that have finished. Zero when none has.
     *
     * <p>Measured from the start rather than from the completion so all four
     * saga KPIs read the same window — a saga is attributed to the window it
     * began in, whatever it did afterwards.
     */
    double averageSagaDurationMillisBetween(Instant from, Instant to);

    /** The 95th percentile of the same population, in milliseconds; zero when it is empty. */
    double p95SagaDurationMillisBetween(Instant from, Instant to);

    /**
     * Sagas started within {@code (from, to]} that are COMPENSATING, as a
     * percentage of every saga started in that window.
     *
     * <p>The saga store keeps only each saga's current status, so this is the
     * honest reading of "how much of what started in the window had to be
     * unwound" — a saga whose compensation later completed is no longer
     * COMPENSATING and does not count.
     */
    double compensationRateBetween(Instant from, Instant to);

    // -----------------------------------------------------------------------
    // Governance KPIs — Command Center row 4
    //
    // These are meta-metrics about the pipeline that produces every KPI above
    // them, not about the bank's business. They exist to make the analytics
    // product's own integrity visible: whether the ledger log and the saga
    // store agree, whether the numbers are statistically ordinary, whether
    // every posting is attributable, and whether the dashboard answers fast
    // enough to be usable.
    // -----------------------------------------------------------------------

    /**
     * Transfer postings within {@code (from, to]} that have a matching saga
     * record, as a percentage of every transfer posting in that window.
     *
     * <p>A transfer posting is one whose debit or credit leg is the transfer
     * clearing account — the structural marker {@code JournalService} writes
     * for all four of its transfer legs, including the two compensation
     * reversals. Matching on the prose description instead would silently miss
     * the compensation legs, which are the ones most worth reconciling.
     *
     * <p>This is the referential-integrity half of the audit story, and it is
     * deliberately the opposite direction to {@link #auditTrailCompleteness}:
     * that one asks whether a posting carries its own identifiers, this one
     * asks whether the orchestrator's record of the same transaction survived
     * alongside it. A ledger whose postings are all well-formed and whose saga
     * store is missing two thirds of them passes the first and fails this one.
     *
     * <p>Zero when the window holds no transfer posting: an empty window has no
     * rate, and the trend logic withholds the percentage for a zero baseline.
     */
    double sagaJournalReconciliationBetween(Instant from, Instant to);

    /**
     * Postings within {@code (from, to]} whose amount is more than three
     * standard deviations from the mean of the trailing
     * {@code baselineDays} window ending at {@code to}, as a percentage.
     *
     * <p>The baseline is stated relative to {@code to} rather than to
     * {@code from} so the question is self-contained: "against everything the
     * month before this window looked like, how odd is this window?". That also
     * makes the answer meaningful for the one-day windows behind a sparkline
     * point, not just for the seven-day one on the card.
     *
     * <p>Zero — not a division error — when the baseline has no spread (every
     * amount identical, or nothing posted): with a standard deviation of zero
     * nothing can be an outlier, and reporting a rate there would invent one.
     */
    double anomalyRateBetween(Instant from, Instant to, int baselineDays);

    /**
     * Postings within {@code (from, to]} that carry a complete audit trail, as
     * a percentage: every entry must have a posting identity, a transaction
     * identity and both of its legs named.
     *
     * <p>Reported as measured. On a ledger whose history was written by a
     * backfill rather than by an operator this is exactly the number that
     * should be low, and rounding it up or excluding the offending rows would
     * destroy the only signal the card carries.
     */
    double auditTrailCompletenessBetween(Instant from, Instant to);
}
