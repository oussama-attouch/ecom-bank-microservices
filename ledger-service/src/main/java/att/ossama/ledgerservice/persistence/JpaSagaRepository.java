package att.ossama.ledgerservice.persistence;

import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.domain.SagaStep;
import att.ossama.ledgerservice.persistence.entity.SagaStateEntity;
import att.ossama.ledgerservice.persistence.entity.SagaStepEntity;
import att.ossama.ledgerservice.persistence.repository.SagaHeader;
import att.ossama.ledgerservice.persistence.repository.SagaStateJpaRepository;
import att.ossama.ledgerservice.saga.SagaRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable saga repository backed by Postgres.
 *
 * Saving replaces the step collection (orphanRemoval deletes the old rows), so a
 * saga's steps and terminal status are written whenever the orchestrator saves
 * the saga again after mutating it.
 */
@Component
@Profile("!inmem")
public class JpaSagaRepository implements SagaRepository {

    private final SagaStateJpaRepository repository;

    public JpaSagaRepository(SagaStateJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public SagaState find(String transactionId) {
        return repository.findWithSteps(transactionId).map(this::toDomain).orElse(null);
    }

    @Override
    @Transactional
    public void save(SagaState saga) {
        // findWithSteps, not findById: the steps are lazy now, and this rewrites
        // the collection, so they have to be initialised to clear them.
        SagaStateEntity entity = repository.findWithSteps(saga.getTransactionId())
                .orElseGet(() -> new SagaStateEntity(
                        saga.getTransactionId(), null, null, null, null, null, null, null, new ArrayList<>()));

        entity.setStatus(saga.getStatus() == null ? null : saga.getStatus().name());
        entity.setSourceAccountId(saga.getSourceAccountId());
        entity.setDestinationAccountId(saga.getDestinationAccountId());
        entity.setAmount(BigDecimal.valueOf(saga.getAmount()));
        entity.setErrorMessage(saga.getErrorMessage());
        entity.setStartedAt(saga.getStartedAt());
        entity.setCompletedAt(saga.getCompletedAt());

        entity.getSteps().clear();
        for (SagaStep step : saga.getSteps()) {
            SagaStepEntity stepEntity = new SagaStepEntity();
            stepEntity.setName(step.getName());
            stepEntity.setStatus(step.getStatus());
            stepEntity.setOffset(step.getOffset());
            stepEntity.setTimestamp(step.getTimestamp());
            stepEntity.setSaga(entity);
            entity.getSteps().add(stepEntity);
        }

        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SagaState> findAll() {
        // Headers only: the list view renders no steps, and selecting them would
        // drag every step row along with every saga.
        return repository.findAllHeaders().stream().map(this::toDomain).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overridden rather than left to the seam's filter-{@code findAll()}
     * default: the bounded form is a predicate the database applies, so the
     * scrubber does not pull all 2,765 saga headers into the service to discard
     * most of them.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SagaState> findAllStartedBefore(Instant cutoff) {
        return repository.findAllHeadersStartedBefore(cutoff).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    /**
     * Map a saga header — the list view's shape, with no steps attached.
     *
     * <p>An empty step list is the honest representation here: the header query
     * never selected any, and the detail query is what loads them. The list does
     * not render steps, so nothing downstream can tell.
     */
    private SagaState toDomain(SagaHeader header) {
        SagaState saga = new SagaState(
                header.getTransactionId(),
                header.getSourceAccountId(),
                header.getDestinationAccountId(),
                header.getAmount() == null ? 0d : header.getAmount().doubleValue(),
                header.getStartedAt());

        if (header.getCompletedAt() != null || header.getErrorMessage() != null) {
            SagaStatus status = header.getStatus() == null
                    ? SagaStatus.COMPLETED
                    : SagaStatus.valueOf(header.getStatus());
            saga.restoreTerminalState(status, header.getErrorMessage(), header.getCompletedAt());
        }
        return saga;
    }

    private SagaState toDomain(SagaStateEntity entity) {
        SagaState saga = new SagaState(
                entity.getTransactionId(),
                entity.getSourceAccountId(),
                entity.getDestinationAccountId(),
                entity.getAmount() == null ? 0d : entity.getAmount().doubleValue(),
                entity.getStartedAt());

        for (SagaStepEntity step : entity.getSteps()) {
            Instant timestamp = step.getTimestamp();
            saga.addStep(new SagaStep(step.getName(), timestamp, step.getOffset(), step.getStatus()));
        }

        if (entity.getCompletedAt() != null || entity.getErrorMessage() != null) {
            SagaStatus status = entity.getStatus() == null
                    ? SagaStatus.COMPLETED
                    : SagaStatus.valueOf(entity.getStatus());
            // restoreTerminalState (not finish) so the persisted completion time survives.
            saga.restoreTerminalState(status, entity.getErrorMessage(), entity.getCompletedAt());
        }
        return saga;
    }
}
