package att.ossama.ledgerservice.persistence.repository;

import att.ossama.ledgerservice.persistence.entity.JournalEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Spring Data repository for durable journal entries (UUID primary key). */
public interface JournalEntryJpaRepository extends JpaRepository<JournalEntryEntity, String> {

    List<JournalEntryEntity> findByTransactionId(String transactionId);

    /**
     * Every entry posted at or before {@code cutoff}, newest first.
     *
     * <p>The journal's time-bounded read, for the timeline scrubber. Ordered to
     * match {@code JpaJournalEntryRepository.findAll()}'s
     * {@code createdAt DESC, id DESC} so a snapshot feed reads the same way as the
     * live one rather than silently inverting — the scrubber's feed, and the
     * {@code subList} page the controller takes off the front of it, both depend
     * on newest-first.
     *
     * <p>{@code <=} inclusive, so an entry posted exactly at the instant asked
     * about is part of the state at that instant. Served by
     * {@code idx_journal_entries_created_at} (V3).
     */
    List<JournalEntryEntity> findByCreatedAtLessThanEqualOrderByCreatedAtDescIdDesc(Instant cutoff);

    List<JournalEntryEntity> findByDebitAccountIdOrCreditAccountId(String debitAccountId, String creditAccountId);

    /**
     * Total amount posted within {@code (from, to]}.
     *
     * <p>Aggregated in the database rather than by loading every entry and adding
     * them up: the journal is the largest table in the ledger, and the dashboard
     * only ever wants this one number. Served by
     * {@code idx_journal_entries_created_at} (V3).
     */
    @Query(value = """
            SELECT COALESCE(SUM(amount), 0)
            FROM journal_entries
            WHERE created_at > :from AND created_at <= :until
            """, nativeQuery = true)
    BigDecimal sumAmountBetween(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * Journal entries per day within the window, oldest first.
     *
     * <p>Grouped in the database so the volume chart can cover a year without
     * shipping a year of rows to the browser.
     */
    @Query(value = """
            SELECT to_char(created_at, 'YYYY-MM-DD') AS day, COUNT(*) AS entries
            FROM journal_entries
            WHERE created_at > :from AND created_at <= :until
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> dailyCounts(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * Money in and out per day, grouped by which leg touches a customer account.
     *
     * <p>The {@code ACC-} test is the rule the browser used to apply when it
     * aggregated this itself: a leg against the clearing account is internal
     * movement, not customer flow.
     */
    @Query(value = """
            SELECT to_char(created_at, 'YYYY-MM-DD') AS day,
                   COALESCE(SUM(CASE WHEN credit_account_id LIKE 'ACC-%' THEN amount ELSE 0 END), 0) AS money_in,
                   COALESCE(SUM(CASE WHEN debit_account_id LIKE 'ACC-%' THEN amount ELSE 0 END), 0) AS money_out
            FROM journal_entries
            WHERE created_at > :from AND created_at <= :until
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> dailyFlow(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * Net customer cash flow within {@code (from, to]}: money credited to
     * customer accounts minus money debited from them.
     *
     * <p>Only the {@code ACC-} legs count, the same rule {@link #dailyFlow} uses.
     * The cash and clearing legs are the other side of these same postings, and a
     * transfer between two customers contributes one leg of each sign, so what is
     * left is what actually entered or left the customer ledger over the window.
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN credit_account_id LIKE 'ACC-%' THEN amount ELSE 0 END), 0)
                 - COALESCE(SUM(CASE WHEN debit_account_id LIKE 'ACC-%' THEN amount ELSE 0 END), 0)
            FROM journal_entries
            WHERE created_at > :from AND created_at <= :until
            """, nativeQuery = true)
    BigDecimal netCustomerCashFlowBetween(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * Mean amount posted within {@code (from, to]}, or zero for an empty window.
     *
     * <p>One row per journal entry rather than per transaction: the journal is
     * where an amount is recorded, and a transfer's two legs are two postings.
     */
    @Query(value = """
            SELECT COALESCE(AVG(amount), 0)
            FROM journal_entries
            WHERE created_at > :from AND created_at <= :until
            """, nativeQuery = true)
    BigDecimal averageAmountBetween(@Param("from") Instant from, @Param("until") Instant until);

    /** Postings above {@code threshold} within {@code (from, to]}. */
    @Query(value = """
            SELECT COUNT(*)
            FROM journal_entries
            WHERE amount > :threshold
              AND created_at > :from AND created_at <= :until
            """, nativeQuery = true)
    long countAboveAmountBetween(@Param("from") Instant from, @Param("until") Instant until,
                                 @Param("threshold") BigDecimal threshold);

    // -----------------------------------------------------------------------
    // Governance aggregates — Command Center row 4
    // -----------------------------------------------------------------------

    /**
     * Share of the window's transfer postings that have a matching saga row.
     *
     * <p>Reads {@code saga_states} directly even though this is the journal's
     * repository: the denominator is a set of journal rows, and splitting the
     * question across two repositories would mean shipping one of the two id
     * sets into the application to intersect them. Both tables live in this
     * schema and the migration that creates them is the same one.
     *
     * <p>{@code TRANSFER_CLEARING} is spelled out rather than bound as a
     * parameter because it is a schema-level constant of the ledger's chart of
     * accounts ({@code JournalService.TRANSFER_CLEARING}), not a knob.
     * {@code NULLIF} keeps an empty window from dividing by zero, and
     * {@code COALESCE} turns the resulting NULL into the 0 the card shows.
     */
    @Query(value = """
            SELECT COALESCE(
                100.0 * COUNT(*) FILTER (
                    WHERE EXISTS (SELECT 1 FROM saga_states s WHERE s.transaction_id = j.transaction_id)
                ) / NULLIF(COUNT(*), 0),
                0)
            FROM journal_entries j
            WHERE j.created_at > :from AND j.created_at <= :until
              AND (j.debit_account_id = 'TRANSFER_CLEARING' OR j.credit_account_id = 'TRANSFER_CLEARING')
            """, nativeQuery = true)
    BigDecimal sagaJournalReconciliationBetween(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * Share of the window's postings that are more than three standard
     * deviations from the trailing baseline mean.
     *
     * <p>Two reads of the same table, joined once: the baseline aggregate and
     * the window it is judging. A {@code CROSS JOIN} is right because the
     * baseline is always exactly one row, and it keeps the window's rows
     * visible when the baseline is empty.
     *
     * <p>The {@code b.sd > 0} predicate is what makes a zero-spread baseline
     * answer 0 rather than null: it filters every row out, so {@code COUNT(*)}
     * is 0, the division is NULL, and the outer {@code COALESCE} — the only
     * reason a single-row aggregate still exists — reports no outliers. Written
     * as a {@code CASE} inside the aggregate instead, Postgres would still have
     * to evaluate the division it is guarding.
     */
    @Query(value = """
            SELECT COALESCE(
                100.0 * COUNT(*) FILTER (WHERE ABS(w.amount - b.mean) > 3 * b.sd) / NULLIF(COUNT(*), 0),
                0)
            FROM (SELECT amount FROM journal_entries
                  WHERE created_at > :from AND created_at <= :until) w
            CROSS JOIN (SELECT AVG(amount) AS mean, COALESCE(STDDEV(amount), 0) AS sd
                        FROM journal_entries
                        WHERE created_at > :baselineFrom AND created_at <= :until) b
            WHERE b.sd > 0
            """, nativeQuery = true)
    BigDecimal anomalyRateBetween(@Param("baselineFrom") Instant baselineFrom,
                                  @Param("from") Instant from,
                                  @Param("until") Instant until);

    /**
     * Share of the window's postings carrying a complete audit trail.
     *
     * <p>The four columns are the whole of what makes a posting attributable:
     * who posted it, which transaction it belongs to, and both of its legs.
     * {@code debit_account_id} and {@code credit_account_id} are {@code NOT
     * NULL} in the schema, so today the check turns on {@code posted_by} — but
     * asserting all four is what makes the metric keep meaning something if
     * that constraint is ever relaxed, and it costs nothing to evaluate.
     */
    @Query(value = """
            SELECT COALESCE(
                100.0 * COUNT(*) FILTER (
                    WHERE posted_by IS NOT NULL
                      AND transaction_id IS NOT NULL
                      AND debit_account_id IS NOT NULL
                      AND credit_account_id IS NOT NULL
                ) / NULLIF(COUNT(*), 0),
                0)
            FROM journal_entries
            WHERE created_at > :from AND created_at <= :until
            """, nativeQuery = true)
    BigDecimal auditTrailCompletenessBetween(@Param("from") Instant from, @Param("until") Instant until);

    // -----------------------------------------------------------------------
    // Bucketed reads — the range-aware KPI path
    // -----------------------------------------------------------------------

    /**
     * Six of the Command Center's journal measures, per bucket, for both
     * comparison windows in one pass.
     *
     * <p>Grouped by {@code date_trunc(:bucket, created_at)} and by which side of
     * {@code splitAt} each row falls on, because the selected window starts
     * mid-bucket: the day it starts inside belongs to both windows, and only the
     * rows can say which side of the boundary they are on. Grouping by both at
     * once is what lets one query answer "the totals either side" and "the shape
     * of the series" without a second pass — and it is why the client's 30, 90
     * and 365-point sparklines cost no more than the seven-point one did.
     *
     * <p>The reconciliation column is a left join against the saga store's
     * transaction ids rather than an {@code EXISTS} probe per posting: the two
     * are the same question, and a hash join against 2,700 distinct ids is
     * cheaper than 50,000 index lookups. It reads {@code saga_states} from the
     * journal's repository for the same reason the single-window version does —
     * the denominator is a set of journal rows, and splitting the question across
     * two repositories would mean intersecting the two id sets in the
     * application.
     *
     * @param bucket the bucket width, spelled as {@code date_trunc} spells it
     */
    @Query(value = """
            SELECT to_char(date_trunc(CAST(:bucket AS text), j.created_at), 'YYYY-MM-DD') AS bucket,
                   (j.created_at > :splitAt) AS current,
                   COALESCE(SUM(j.amount), 0) AS volume,
                   COUNT(*) AS postings,
                   COALESCE(SUM(CASE WHEN j.credit_account_id LIKE 'ACC-%' THEN j.amount ELSE 0 END), 0)
                       - COALESCE(SUM(CASE WHEN j.debit_account_id LIKE 'ACC-%' THEN j.amount ELSE 0 END), 0)
                       AS customer_cash_flow,
                   COUNT(*) FILTER (WHERE j.amount > :largeThreshold) AS large_transfers,
                   COUNT(*) FILTER (WHERE j.debit_account_id = 'TRANSFER_CLEARING'
                                      OR j.credit_account_id = 'TRANSFER_CLEARING') AS transfer_postings,
                   COUNT(*) FILTER (WHERE (j.debit_account_id = 'TRANSFER_CLEARING'
                                           OR j.credit_account_id = 'TRANSFER_CLEARING')
                                      AND s.transaction_id IS NOT NULL) AS reconciled_transfers,
                   COUNT(*) FILTER (WHERE j.posted_by IS NOT NULL
                                      AND j.transaction_id IS NOT NULL
                                      AND j.debit_account_id IS NOT NULL
                                      AND j.credit_account_id IS NOT NULL) AS audited_postings
            FROM journal_entries j
            LEFT JOIN (SELECT DISTINCT transaction_id FROM saga_states) s
                   ON s.transaction_id = j.transaction_id
            WHERE j.created_at > :spanFrom AND j.created_at <= :until
            GROUP BY 1, 2
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> journalBuckets(@Param("spanFrom") Instant spanFrom,
                                  @Param("splitAt") Instant splitAt,
                                  @Param("until") Instant until,
                                  @Param("bucket") String bucket,
                                  @Param("largeThreshold") BigDecimal largeThreshold);

    /**
     * The anomaly card's postings per bucket, judged against the trailing
     * {@code baselineDays} ending at {@code until}.
     *
     * <p>One baseline for the whole series, which is the rule the single-window
     * aggregate has always applied and the one the card's own tooltip states:
     * "postings over three standard deviations from the trailing 30-day mean".
     * Judging each point against a baseline of its own would ask a different
     * question — how odd was that day against the month before <em>it</em> — and
     * it costs a grouped pass per day of sparkline to ask.
     *
     * <p>{@code CROSS JOIN} rather than a lateral aggregate because the baseline
     * is always exactly one row, and it keeps the window's rows visible when the
     * baseline is empty. The {@code b.sd > 0} predicate is what makes a
     * zero-spread baseline answer zero outliers rather than null: it filters
     * every row out, so {@code COUNT(*)} is 0 and the division that is not there
     * cannot be attempted.
     */
    @Query(value = """
            SELECT to_char(date_trunc(CAST(:bucket AS text), p.created_at), 'YYYY-MM-DD') AS bucket,
                   (p.created_at > :splitAt) AS current,
                   COUNT(*) FILTER (WHERE ABS(p.amount - b.mean) > 3 * b.sd) AS outliers,
                   COUNT(*) AS postings
            FROM journal_entries p
            CROSS JOIN (SELECT AVG(amount) AS mean, COALESCE(STDDEV(amount), 0) AS sd
                        FROM journal_entries
                        WHERE created_at > :baselineFrom AND created_at <= :until) b
            WHERE p.created_at > :spanFrom AND p.created_at <= :until
              AND b.sd > 0
            GROUP BY 1, 2
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> anomalyBuckets(@Param("baselineFrom") Instant baselineFrom,
                                  @Param("spanFrom") Instant spanFrom,
                                  @Param("splitAt") Instant splitAt,
                                  @Param("until") Instant until,
                                  @Param("bucket") String bucket);
}
