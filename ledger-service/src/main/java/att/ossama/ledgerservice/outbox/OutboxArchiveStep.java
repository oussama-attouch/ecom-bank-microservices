package att.ossama.ledgerservice.outbox;

import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.persistence.entity.OutboxEventEntity;
import att.ossama.ledgerservice.persistence.repository.OutboxEventJpaRepository;
import att.ossama.ledgerservice.saga.ArchiveStep;
import att.ossama.ledgerservice.saga.SagaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The ARCHIVE step on the outbox path: one transaction, two writes, no Kafka.
 *
 * <p>A separate bean from {@link att.ossama.ledgerservice.saga.TransferSagaService}
 * because that is the only way the annotation applies — the saga's own overloads
 * delegate to one another by self-invocation, which never crosses the proxy — and
 * because the transaction must cover this step alone. The saga's other writes stay
 * one committed transaction each: its failure handling writes <em>after</em>
 * catching, so a saga-wide transaction would roll back the record of the failure it
 * is trying to compensate.
 *
 * <p>Committing both writes together is the point of the feature. A committed
 * {@code ARCHIVE} step with no outbox row is a transfer that will never be
 * published; an outbox row with no committed step is a message about an archive the
 * saga says never happened.
 *
 * <p>Three things are deliberately absent from {@link #archive}: a {@code try}/
 * {@code catch} (a failure must reach the transaction interceptor and roll both
 * writes back), any Kafka call (the relay publishes later, and a broker round trip
 * has no business inside a database transaction), and any request-scoped work. The
 * only {@code try} is in {@link #serialize}, which converts a checked Jackson
 * failure into an unchecked one; it does not swallow it.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.enabled", havingValue = "true")
public class OutboxArchiveStep implements ArchiveStep {

    /** The only aggregate type this feature emits, matching the record's discriminator. */
    public static final String AGGREGATE_TYPE = "TRANSACTION";

    private final SagaRepository sagaRepository;
    private final OutboxEventJpaRepository outboxRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * {@code SagaRepository}, not the JPA class: the durable implementation is
     * {@code @Profile("!inmem")}, so depending on the concrete type would leave this
     * bean unbuildable under the in-memory profile.
     */
    public OutboxArchiveStep(SagaRepository sagaRepository, OutboxEventJpaRepository outboxRepository) {
        this.sagaRepository = sagaRepository;
        this.outboxRepository = outboxRepository;
    }

    @Override
    @Transactional
    public SagaState archive(SagaState saga, TransactionRecord record, Instant archiveAt, boolean publish) {
        SagaState archived = saga.copyWithStep("ARCHIVE", archiveAt, null, "EXECUTED");
        sagaRepository.save(archived);
        if (publish) {
            outboxRepository.save(pendingRow(archived, record, archiveAt));
        }
        return archived;
    }

    /**
     * The row as it must exist at commit: no publish bookkeeping set, because
     * nothing has been attempted yet. {@code created_at} is the saga's timeline
     * instant rather than the wall clock, so a backdated saga sorts where it
     * happened.
     */
    private OutboxEventEntity pendingRow(SagaState archived, TransactionRecord record, Instant archiveAt) {
        OutboxEventEntity row = new OutboxEventEntity();
        row.setId(UUID.randomUUID().toString());
        row.setAggregateType(AGGREGATE_TYPE);
        row.setAggregateId(archived.getTransactionId());
        row.setEventType(record.type());
        row.setPayload(serialize(record));
        row.setCreatedAt(archiveAt);
        row.setAttemptCount(0);
        return row;
    }

    /**
     * The message body, verbatim. A plain default {@link ObjectMapper}, created the
     * way the Kafka publisher creates one rather than injected from the context, so
     * the stored bytes cannot shift under a later {@code spring.jackson.*} setting:
     * the relay sends this string unchanged, and byte identity is then a property of
     * the storage form instead of two serializers agreeing.
     */
    private String serialize(TransactionRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize transaction record " + record.transactionId(), e);
        }
    }
}
