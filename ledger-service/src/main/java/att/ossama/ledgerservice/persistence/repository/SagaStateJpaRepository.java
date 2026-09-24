package att.ossama.ledgerservice.persistence.repository;

import att.ossama.ledgerservice.persistence.entity.SagaStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data repository for durable saga state (transaction id is the key). */
public interface SagaStateJpaRepository extends JpaRepository<SagaStateEntity, String> {

    /**
     * Saga headers, without their steps.
     *
     * <p>Deliberately a projection rather than {@code findAll()}: the steps are a
     * collection on the entity, so loading sagas loaded their steps too — 2,765
     * sagas meant 11,334 extra rows per call, which is most of why the saga list
     * took seconds. Nothing in the list view reads a step; the detail view asks
     * for them by id.
     */
    @Query("""
            SELECT s.transactionId AS transactionId,
                   s.status AS status,
                   s.sourceAccountId AS sourceAccountId,
                   s.destinationAccountId AS destinationAccountId,
                   s.amount AS amount,
                   s.startedAt AS startedAt,
                   s.completedAt AS completedAt,
                   s.errorMessage AS errorMessage
            FROM SagaStateEntity s
            """)
    List<SagaHeader> findAllHeaders();

    /**
     * One saga with its steps, for the detail view and for the orchestrator, which
     * needs them initialised to rewrite the collection when it saves.
     */
    @Query("SELECT s FROM SagaStateEntity s LEFT JOIN FETCH s.steps WHERE s.transactionId = :transactionId")
    Optional<SagaStateEntity> findWithSteps(@Param("transactionId") String transactionId);

    /**
     * Sagas that started within {@code (from, to]} and are still in a problem
     * state. A saga store keeps only each saga's current status, so this counts
     * what started in the window and is still failing — the honest reading of
     * "problem sagas over the last 7 days". Served by
     * {@code idx_saga_states_status_started} (V3).
     */
    @Query(value = """
            SELECT COUNT(*) FROM saga_states
            WHERE status IN ('COMPENSATING', 'FAILED')
              AND started_at > :from AND started_at <= :until
            """, nativeQuery = true)
    long countProblemStartedBetween(@Param("from") Instant from, @Param("until") Instant until);

    /** Saga counts by status, for the breakdown chart. */
    @Query("SELECT s.status, COUNT(s) FROM SagaStateEntity s GROUP BY s.status")
    List<Object[]> countByStatus();

    /**
     * Saga counts by status, for the sagas that had started by {@code asOf}.
     *
     * <p>The breakdown chart under the timeline scrubber. Note what this can and
     * cannot say: the store keeps only each saga's <em>current</em> status, so a
     * saga that started before the cutoff and has since completed is counted as
     * COMPLETED even though it was in flight at the instant asked about. That is
     * the same honest reading {@link #countProblemStartedBetween} documents —
     * "started in the window, in the state it is in now" — and reconstructing the
     * status a saga held mid-flight would need the step log, which this query
     * deliberately does not join.
     *
     * <p>{@code started_at} is nullable, and a saga with no start belongs to no
     * instant rather than to the beginning of time, so those rows are excluded.
     */
    @Query(value = """
            SELECT status, COUNT(*) FROM saga_states
            WHERE started_at IS NOT NULL AND started_at <= :asOf
            GROUP BY status
            """, nativeQuery = true)
    List<Object[]> countByStatusStartedBefore(@Param("asOf") Instant asOf);

    /**
     * Saga headers for the sagas that had started by {@code asOf}, in the same
     * shape as {@link #findAllHeaders()} and carrying the same caveat: the status
     * is the saga's current one, not the one it held at the cutoff.
     *
     * <p>Steps are omitted for the same reason as the live list — the list view
     * renders none, and selecting them drags every step row along.
     */
    @Query("""
            SELECT s.transactionId AS transactionId,
                   s.status AS status,
                   s.sourceAccountId AS sourceAccountId,
                   s.destinationAccountId AS destinationAccountId,
                   s.amount AS amount,
                   s.startedAt AS startedAt,
                   s.completedAt AS completedAt,
                   s.errorMessage AS errorMessage
            FROM SagaStateEntity s
            WHERE s.startedAt IS NOT NULL AND s.startedAt <= :asOf
            """)
    List<SagaHeader> findAllHeadersStartedBefore(@Param("asOf") Instant asOf);

    /**
     * Sagas started within {@code (from, to]} whose current status is COMPLETED,
     * as a percentage of every saga started in that window.
     *
     * <p>{@code NULLIF} guards the empty window: no sagas started is not a 0%
     * success rate, and the trend logic withholds the percentage for a zero
     * baseline anyway.
     */
    @Query(value = """
            SELECT COALESCE(100.0 * COUNT(*) FILTER (WHERE status = 'COMPLETED') / NULLIF(COUNT(*), 0), 0)
            FROM saga_states
            WHERE started_at > :from AND started_at <= :until
            """, nativeQuery = true)
    BigDecimal successRateStartedBetween(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * Mean duration in milliseconds of the sagas started within {@code (from, to]}
     * that have finished; zero when none of them has.
     *
     * <p>Attributed to the window the saga <em>started</em> in, so all four saga
     * KPIs read the same window. {@code EXTRACT(EPOCH ...) * 1000} keeps the
     * sub-second precision a saga timeline actually has — these run in hundreds
     * of milliseconds, and whole seconds would round every one of them to zero.
     */
    @Query(value = """
            SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000), 0)
            FROM saga_states
            WHERE started_at > :from AND started_at <= :until
              AND completed_at IS NOT NULL
            """, nativeQuery = true)
    BigDecimal averageDurationMillisStartedBetween(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * The 95th percentile of the same population, in milliseconds.
     *
     * <p>{@code percentile_cont} interpolates between the two nearest durations,
     * which is what makes a p95 meaningful on the handful of sagas a 7-day window
     * holds: the discrete alternative would jump between whichever sagas happened
     * to be slowest.
     */
    @Query(value = """
            SELECT COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000), 0)
            FROM saga_states
            WHERE started_at > :from AND started_at <= :until
              AND completed_at IS NOT NULL
            """, nativeQuery = true)
    BigDecimal p95DurationMillisStartedBetween(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * Sagas started within {@code (from, to]} that are still COMPENSATING, as a
     * percentage of every saga started in that window.
     *
     * <p>The store keeps only each saga's current status, so this is what started
     * in the window and had to be unwound; one whose compensation has since
     * completed no longer counts.
     */
    @Query(value = """
            SELECT COALESCE(100.0 * COUNT(*) FILTER (WHERE status = 'COMPENSATING') / NULLIF(COUNT(*), 0), 0)
            FROM saga_states
            WHERE started_at > :from AND started_at <= :until
            """, nativeQuery = true)
    BigDecimal compensationRateStartedBetween(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * The four operational cards' measures, per bucket, for both comparison
     * windows in one pass.
     *
     * <p>The percentile is taken per bucket in SQL rather than derived from the
     * sums beside it: a p95 is not a function of a window's total and count, so a
     * sparkline point has to be measured over its own rows. It costs nothing
     * here — the saga store is three orders of magnitude smaller than the journal
     * — and it keeps the point honest, which averaging per-day percentiles would
     * not.
     *
     * <p>{@code started_at} is nullable in the schema, and the window predicate
     * excludes those rows rather than dating them to the epoch. A saga with no
     * start belongs to no window; it is not a saga that started at the beginning
     * of time.
     */
    @Query(value = """
            SELECT to_char(date_trunc(CAST(:bucket AS text), started_at), 'YYYY-MM-DD') AS bucket,
                   (started_at > :splitAt) AS current,
                   COUNT(*) AS started,
                   COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                   COUNT(*) FILTER (WHERE status = 'COMPENSATING') AS compensating,
                   COUNT(*) FILTER (WHERE status IN ('COMPENSATING', 'FAILED')) AS problem,
                   COALESCE(SUM(EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000), 0) AS duration_sum,
                   COUNT(completed_at) AS duration_count,
                   COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000), 0) AS p95_millis
            FROM saga_states
            WHERE started_at > :spanFrom AND started_at <= :until
            GROUP BY 1, 2
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> sagaBuckets(@Param("spanFrom") Instant spanFrom,
                               @Param("splitAt") Instant splitAt,
                               @Param("until") Instant until,
                               @Param("bucket") String bucket);
}
