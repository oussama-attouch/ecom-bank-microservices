package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.publisher.TransactionEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The ARCHIVE step as the saga has always performed it: publish inline, then
 * record the step.
 *
 * <p>Active when {@code ledger.outbox.enabled} is absent or {@code false}, which is
 * the shipped default. The behaviour is the pre-outbox behaviour, including the
 * part that matters: a publisher failure throws out of
 * {@link TransactionEventPublisher#publishStrict} <em>before</em> the step is
 * recorded, so the saga compensates exactly as it did before the outbox existed.
 *
 * <p>Mutates the saga in place and returns it, which is legitimate here precisely
 * because there is no transaction to roll back. The outbox implementation cannot
 * do that — see {@link SagaState#copyWithStep}.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.enabled", havingValue = "false", matchIfMissing = true)
public class LegacyArchiveStep implements ArchiveStep {

    private final TransactionEventPublisher publisher;

    public LegacyArchiveStep(TransactionEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public SagaState archive(SagaState saga, TransactionRecord record, Instant archiveAt, boolean publish) {
        if (publish) {
            publisher.publishStrict(record);
        }
        saga.addStep("ARCHIVE", archiveAt, null, "EXECUTED");
        return saga;
    }
}
