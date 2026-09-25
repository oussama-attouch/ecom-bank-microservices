package att.ossama.billingservice.kafka;

import att.ossama.billingservice.entities.ArchivedTransaction;
import att.ossama.billingservice.observability.ArchiveObservationNotifier;
import att.ossama.billingservice.repository.ArchivedTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes committed ledger transactions from the "ledger-events" topic and
 * archives them. Idempotent: processed transaction IDs are tracked in-memory
 * (swap for Redis in production) so redelivered messages are skipped.
 */
@Component
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private final ArchivedTransactionRepository repository;
    private final ArchiveObservationNotifier notifier;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();

    public TransactionEventConsumer(ArchivedTransactionRepository repository,
                                    ArchiveObservationNotifier notifier) {
        this.repository = repository;
        this.notifier = notifier;
    }

    @KafkaListener(topics = "ledger-events")
    public void onEvent(String message, Acknowledgment ack) {
        try {
            TransactionEvent event = objectMapper.readValue(message, TransactionEvent.class);

            if (!processedIds.add(event.transactionId())) {
                log.info("Duplicate transaction event {} skipped", event.transactionId());
                ack.acknowledge();
                return;
            }

            ArchivedTransaction tx = ArchivedTransaction.builder()
                    .transactionId(event.transactionId())
                    .type(event.type())
                    .accountId(event.accountId())
                    .fromAccountId(event.fromAccountId())
                    .toAccountId(event.toAccountId())
                    .amount(event.amount())
                    .timestamp(event.timestamp())
                    .build();
            repository.save(tx);
            log.info("Archived transaction {} (from Kafka)", event.transactionId());
            // After the archive and before the ack, and safe to place here
            // because the notifier returns immediately when the live stream is
            // off and swallows everything when it is on: this line cannot fail
            // the consume, so it cannot cause a redelivery of a record that was
            // in fact archived. The duplicate branch above deliberately does not
            // report — a skipped duplicate is not an archive.
            notifier.archived(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process ledger event; will retry or route to DLT", e);
            throw new RuntimeException("Failed to process ledger event", e);
        }
    }
}
