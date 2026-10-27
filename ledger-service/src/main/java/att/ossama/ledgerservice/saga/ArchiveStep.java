package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.TransactionRecord;

import java.time.Instant;

/**
 * The saga's {@code ARCHIVE} step, as a seam rather than a call.
 *
 * <p>Exists so the step can be recorded in a different transaction from the rest
 * of the saga without annotating {@link TransferSagaService} itself: the saga runs
 * one committed transaction per step on purpose, because its failure handling
 * writes after catching (the {@code FAILED} step, the compensations, the
 * {@code COMPENSATING} status), and a saga-wide transaction would roll those
 * writes back instead of recording them.
 *
 * <p>Two implementations, selected by {@code ledger.outbox.enabled} and never by
 * the caller: {@link LegacyArchiveStep} publishes inline as the saga always has,
 * and {@code OutboxArchiveStep} records the step and an outbox row atomically.
 * The saga's flow is identical either way, so its compensation behaviour cannot
 * drift between configurations.
 */
public interface ArchiveStep {

    /**
     * Records the {@code ARCHIVE} step and publishes or enqueues {@code record}.
     *
     * <p>Publishes or enqueues exactly when {@code publish} is true — a run that
     * is reconstructing history must record the step without announcing it.
     * Propagates every failure as an exception and never swallows one, so the
     * saga's compensation branch still sees an archive that did not commit.
     *
     * <p>Returns the saga the caller must continue with. It may be a copy: the
     * outbox writer deliberately leaves the argument untouched, so a rolled-back
     * transaction cannot leave a saga claiming a step that never committed.
     * Callers must therefore assign the result rather than assume mutation.
     */
    SagaState archive(SagaState saga, TransactionRecord record, Instant archiveAt, boolean publish);
}
