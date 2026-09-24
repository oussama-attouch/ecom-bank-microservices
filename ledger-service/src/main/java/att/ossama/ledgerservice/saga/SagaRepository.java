package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.SagaState;

import java.time.Instant;
import java.util.List;

/** Persistence seam for saga state; swap the in-memory impl for a DB later. */
public interface SagaRepository {

    SagaState find(String transactionId);

    void save(SagaState saga);

    List<SagaState> findAll();

    /** How many sagas are stored, for seed summaries and health reporting. */
    long count();

    /**
     * The sagas that had started by {@code cutoff} — the saga list as it stood at
     * that instant.
     *
     * <p>Bounded on {@code startedAt}: a saga has no {@code occurred_at}, and
     * "which sagas existed then" is a question about when they began.
     *
     * <p>Carries the caveat the store's own shape forces: only each saga's
     * <em>current</em> status is kept, so one that started before the cutoff and
     * has since finished reads as COMPLETED even though it was in flight at the
     * instant asked about. {@code countProblemStartedBetween} has always read the
     * store the same way, and the alternative — replaying each saga's steps to
     * recover the status it held mid-flight — is a different feature.
     *
     * <p>The default filters {@link #findAll()}; the durable repository overrides
     * it with an index range.
     */
    default List<SagaState> findAllStartedBefore(Instant cutoff) {
        return findAll().stream()
                .filter(saga -> {
                    Instant at = saga.getStartedAt();
                    return at != null && !at.isAfter(cutoff);
                })
                .toList();
    }
}
