package att.ossama.billingservice.kafka;

import att.ossama.billingservice.entities.ArchivedTransaction;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();

    public TransactionEventConsumer(ArchivedTransactionRepository repository) {
        this.repository = repository;
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
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process ledger event; will retry or route to DLT", e);
            throw new RuntimeException("Failed to process ledger event", e);
        }
    }
}
