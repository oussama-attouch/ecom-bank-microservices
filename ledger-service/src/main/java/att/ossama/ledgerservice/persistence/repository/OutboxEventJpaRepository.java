package att.ossama.ledgerservice.persistence.repository;

import att.ossama.ledgerservice.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data repository for the outbox table.
 *
 * <p>The claim query is native SQL rather than JPQL with {@code @Lock} and a lock
 * timeout hint. The task's sketch used {@code javax.persistence.lock.timeout},
 * which is the pre-Jakarta name and is ignored by Hibernate 6 under Jakarta EE 10:
 * the query would still run, but as a blocking {@code FOR UPDATE} instead of
 * {@code SKIP LOCKED} — a failure that only shows up as contention between
 * pollers. Writing the statement out keeps the shape the relay depends on (spec
 * §5.2) visible and exact.
 *
 * <p>Status counts and the admin list land with the API phase; this is the only
 * query the relay needs.
 */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, String> {

    /**
     * Claims up to {@code batchSize} rows for one poll, oldest first, and locks
     * them for the rest of the caller's transaction.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes two pollers safe: a row
     * already claimed by another transaction is skipped rather than waited for, so
     * a slow batch cannot stall another instance. Rows this poll did not claim stay
     * untouched — which is also what the abort-on-broker-failure path relies on.
     *
     * <p>{@code next_attempt_at} is the backoff gate: a row that failed recently is
     * invisible until its next attempt is due, which is what gives the backoff any
     * effect at all. {@code attempt_count < :maxAttempts} keeps exhausted rows out
     * even before {@code parked_at} is considered, and the explicit
     * {@code parked_at IS NULL} says so.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE published_at IS NULL
               AND parked_at IS NULL
               AND attempt_count < :maxAttempts
               AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
             ORDER BY created_at ASC
             LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> claimPending(@Param("maxAttempts") int maxAttempts,
                                         @Param("now") Instant now,
                                         @Param("batchSize") int batchSize);

    /**
     * The admin list: newest first, ties broken by id.
     *
     * <p>Fifty because the dashboard renders a page, not a table anyone pages
     * through, and the counters cover the whole table independently of it.
     */
    List<OutboxEventEntity> findTop50ByOrderByCreatedAtDescIdDesc();

    /** Rows the relay has published. */
    long countByPublishedAtIsNotNull();

    /** Rows that exhausted their attempts and were parked in place. */
    long countByPublishedAtIsNullAndParkedAtIsNotNull();

    /** Rows that have been attempted and are waiting for their next attempt. */
    long countByPublishedAtIsNullAndParkedAtIsNullAndAttemptCountGreaterThan(int attempts);

    /** Rows never attempted. */
    long countByPublishedAtIsNullAndParkedAtIsNullAndAttemptCount(int attempts);
}
