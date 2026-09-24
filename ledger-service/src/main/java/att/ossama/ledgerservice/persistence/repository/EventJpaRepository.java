package att.ossama.ledgerservice.persistence.repository;

import att.ossama.ledgerservice.persistence.entity.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Spring Data repository for the durable event log. */
public interface EventJpaRepository extends JpaRepository<EventEntity, Long> {

    /** All events of one aggregate, in the order they were appended. */
    List<EventEntity> findByAggregateIdOrderBySequenceNumber(String aggregateId);

    /**
     * Every event at or before {@code cutoff}, in log order.
     *
     * <p>The one read here that returns events rather than a summary of them, and
     * the base of the point-in-time snapshot: the projection folds this list to
     * answer "what did every account look like at that instant".
     *
     * <p>{@code <=} is inclusive, following {@link #accountBalancesAsOf(Instant)}
     * rather than the {@code (from, to]} the range aggregates use — see {@code
     * EventStore.allEventsBefore}.
     *
     * <p>Ordered by {@code id}, the surrogate global log position, and not by
     * {@code occurredAt}: the seed backdates events, so the two orders differ, and
     * the projection replays in log order.
     *
     * <p>Served by {@code idx_event_store_occurred_at} (V3).
     */
    List<EventEntity> findByOccurredAtLessThanEqualOrderByIdAsc(Instant cutoff);

    /** Highest sequence number already stored for an aggregate (0 when empty). */
    @Query("SELECT COALESCE(MAX(e.sequenceNumber), 0) FROM EventEntity e WHERE e.aggregateId = :aggregateId")
    Long findMaxSequenceNumberByAggregateId(@Param("aggregateId") String aggregateId);

    /** Highest sequence number across the whole log (0 when empty). */
    @Query("SELECT COALESCE(MAX(e.sequenceNumber), 0) FROM EventEntity e")
    Long findMaxSequenceNumber();

    /**
     * Net assets moved over {@code (from, to]}: credits minus debits.
     *
     * <p>Native, and it has to be: the amount lives inside the {@code payload}
     * JSON rather than in a column, so the sum extracts it. Note also that
     * {@code event_type} holds the event class's simple name — filtering on the
     * discriminator inside the payload ({@code MONEY_CREDITED}) matches nothing.
     *
     * <p>Served by {@code idx_event_store_occurred_at} (V3).
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE
                       WHEN event_type = 'MoneyCreditedEvent' THEN (payload::json ->> 'amount')::numeric
                       WHEN event_type = 'MoneyDebitedEvent' THEN -((payload::json ->> 'amount')::numeric)
                       ELSE 0 END), 0)
            FROM event_store
            WHERE occurred_at > :from AND occurred_at <= :until
            """, nativeQuery = true)
    BigDecimal netFlowBetween(@Param("from") Instant from, @Param("until") Instant until);

    /** Distinct accounts opened within {@code (from, to]}. */
    @Query(value = """
            SELECT COUNT(DISTINCT aggregate_id)
            FROM event_store
            WHERE event_type = 'AccountCreatedEvent'
              AND occurred_at > :from AND occurred_at <= :until
            """, nativeQuery = true)
    long countAccountsCreatedBetween(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * The earliest instant the ledger holds, or null for an empty log.
     *
     * <p>The timeline scrubber's left end. Carried on the chart-series payload
     * rather than fetched by the browser in its own right: a scrubber that had to
     * ask for the ledger's extent separately would double the dashboard's most
     * expensive read (the balance distribution is a full grouped pass and is
     * computed for every range anyway) purely to learn one date.
     *
     * <p>{@code MIN} on an indexed column is a single index probe in Postgres, not
     * a scan, so riding along on a call the dashboard already makes is close to
     * free. The earliest event never changes — the log is append-only and nothing
     * is ever backdated before the first event — so this needs no as-of variant.
     */
    @Query(value = "SELECT MIN(occurred_at) FROM event_store", nativeQuery = true)
    Instant findEarliestOccurredAt();

    /**
     * Every account's identity and balance, in one pass over the log.
     *
     * <p>This is what {@code GET /api/accounts} needs and what replaying the log
     * for it cost: the identity fields come from the account's creation event and
     * the balance from the money events, both extracted from the payload JSON,
     * grouped per aggregate in the database rather than by hydrating 51,000 rows
     * and decoding them in Java.
     *
     * <p>Aggregates with no creation event are still included, with null identity
     * fields, because the replay it replaces listed every aggregate in the log.
     */
    @Query(value = """
            SELECT e.aggregate_id AS accountId,
                   MAX(CASE WHEN e.event_type = 'AccountCreatedEvent'
                            THEN (e.payload::json ->> 'customerId')::bigint END) AS customerId,
                   MAX(CASE WHEN e.event_type = 'AccountCreatedEvent'
                            THEN e.payload::json ->> 'holderName' END) AS holderName,
                   COALESCE(SUM(CASE
                       WHEN e.event_type = 'MoneyCreditedEvent' THEN (e.payload::json ->> 'amount')::numeric
                       WHEN e.event_type = 'MoneyDebitedEvent' THEN -((e.payload::json ->> 'amount')::numeric)
                       ELSE 0 END), 0) AS balance
            FROM event_store e
            GROUP BY e.aggregate_id
            ORDER BY e.aggregate_id
            """, nativeQuery = true)
    List<AccountSummaryRow> accountSummaries();

    /**
     * The same per-account summary as {@link #accountSummaries()}, as of an
     * instant: identity fields and balance from the events up to {@code asOf}.
     *
     * <p>The doughnut chart's source under the timeline scrubber. A separate query
     * rather than a nullable parameter on {@link #accountSummaries()} because the
     * live path must keep the plan it has — a time bound it never uses would make
     * every 5s dashboard poll pay for a predicate it does not need.
     *
     * <p>{@code <=} inclusive, following {@link #accountBalancesAsOf(Instant)}: an
     * account opened exactly at the instant asked about did exist at it.
     *
     * <p>Served by {@code idx_event_store_occurred_at} (V3).
     */
    @Query(value = """
            SELECT e.aggregate_id AS accountId,
                   MAX(CASE WHEN e.event_type = 'AccountCreatedEvent'
                            THEN (e.payload::json ->> 'customerId')::bigint END) AS customerId,
                   MAX(CASE WHEN e.event_type = 'AccountCreatedEvent'
                            THEN e.payload::json ->> 'holderName' END) AS holderName,
                   COALESCE(SUM(CASE
                       WHEN e.event_type = 'MoneyCreditedEvent' THEN (e.payload::json ->> 'amount')::numeric
                       WHEN e.event_type = 'MoneyDebitedEvent' THEN -((e.payload::json ->> 'amount')::numeric)
                       ELSE 0 END), 0) AS balance
            FROM event_store e
            WHERE e.occurred_at <= :asOf
            GROUP BY e.aggregate_id
            ORDER BY e.aggregate_id
            """, nativeQuery = true)
    List<AccountSummaryRow> accountSummariesAsOf(@Param("asOf") Instant asOf);

    /**
     * Every account's balance as of {@code asOf}, one row per account.
     *
     * <p>The same per-aggregate sum {@link #accountSummaries()} computes, without
     * the identity fields: this is the base the concentration KPI reads once, and
     * it is a full grouped pass of the log, so it is deliberately read once per
     * refresh rather than once per day of the sparkline.
     */
    @Query(value = """
            SELECT e.aggregate_id AS accountId,
                   COALESCE(SUM(CASE
                       WHEN e.event_type = 'MoneyCreditedEvent' THEN (e.payload::json ->> 'amount')::numeric
                       WHEN e.event_type = 'MoneyDebitedEvent' THEN -((e.payload::json ->> 'amount')::numeric)
                       ELSE 0 END), 0) AS balance
            FROM event_store e
            WHERE e.occurred_at <= :asOf
            GROUP BY e.aggregate_id
            """, nativeQuery = true)
    List<AccountBalanceRow> accountBalancesAsOf(@Param("asOf") Instant asOf);

    /**
     * The same per-aggregate sum, grouped by day as well, within {@code (from, to]}.
     *
     * <p>The concentration card reads the same balance at nine instants a day
     * apart or less, and each reading nests inside the next. Grouping by day lets
     * all of them be replayed from one pass of the movements instead of one
     * indexed range per reading: on the seeded ledger the nine reads cost about
     * 280ms together, and the single grouped pass about 25ms.
     *
     * <p>The day labels are the same {@code YYYY-MM-DD} the other grouped reads
     * use, so a caller can line the movements up with the instants it asked
     * about without a second time zone conversion.
     */
    @Query(value = """
            SELECT e.aggregate_id AS accountId,
                   to_char(date_trunc('day', e.occurred_at), 'YYYY-MM-DD') AS day,
                   COALESCE(SUM(CASE
                       WHEN e.event_type = 'MoneyCreditedEvent' THEN (e.payload::json ->> 'amount')::numeric
                       WHEN e.event_type = 'MoneyDebitedEvent' THEN -((e.payload::json ->> 'amount')::numeric)
                       ELSE 0 END), 0) AS balance
            FROM event_store e
            WHERE e.occurred_at > :from AND e.occurred_at <= :until
            GROUP BY 1, 2
            """, nativeQuery = true)
    List<Object[]> accountMovementDays(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * The two level KPIs' measures, per bucket, over whatever the span covers —
     * the whole log for a cumulative position.
     *
     * <p>One pass answers both cards and every point of both sparklines. The
     * movement in each bucket is what turns the level <em>now</em> into the level
     * as each point ended, by subtracting the movements that followed it: the
     * buckets partition the log, so this is arithmetic rather than an
     * approximation, and it costs one grouped scan instead of a cumulative read
     * per point.
     *
     * <p>{@code COUNT(*)} rather than {@code COUNT(DISTINCT aggregate_id)} for
     * the openings: an account is opened exactly once, so the two are the same
     * number — and the distinct version makes the planner sort the whole log to
     * de-duplicate it, which on the seeded ledger spilled 16MB to disk and cost
     * more than twice as much as the scan it was aggregating.
     */
    @Query(value = """
            SELECT to_char(date_trunc(CAST(:bucket AS text), occurred_at), 'YYYY-MM-DD') AS bucket,
                   (occurred_at > :splitAt) AS current,
                   COALESCE(SUM(CASE
                       WHEN event_type = 'MoneyCreditedEvent' THEN (payload::json ->> 'amount')::numeric
                       WHEN event_type = 'MoneyDebitedEvent' THEN -((payload::json ->> 'amount')::numeric)
                       ELSE 0 END), 0) AS net_flow,
                   COUNT(*) FILTER (WHERE event_type = 'AccountCreatedEvent') AS accounts_created
            FROM event_store
            WHERE occurred_at > :spanFrom AND occurred_at <= :until
            GROUP BY 1, 2
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> eventBuckets(@Param("spanFrom") Instant spanFrom,
                                @Param("splitAt") Instant splitAt,
                                @Param("until") Instant until,
                                @Param("bucket") String bucket);
}
