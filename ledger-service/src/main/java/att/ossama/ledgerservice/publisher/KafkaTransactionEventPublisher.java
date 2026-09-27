package att.ossama.ledgerservice.publisher;

import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.observability.LiveEvent;
import att.ossama.ledgerservice.observability.LiveEventBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Kafka publisher. Sends committed transactions to the "ledger-events" topic
 * (keyed by transactionId so all events for a transaction share a partition).
 */
@Component
@ConditionalOnProperty(name = "ledger.publisher", havingValue = "kafka")
public class KafkaTransactionEventPublisher implements TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransactionEventPublisher.class);

    public static final String TOPIC = "ledger-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LiveEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KafkaTransactionEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                          LiveEventBroadcaster broadcaster) {
        this.kafkaTemplate = kafkaTemplate;
        this.broadcaster = broadcaster;
    }

    @Override
    public void publish(TransactionRecord record) {
        try {
            send(record);
        } catch (Exception e) {
            log.warn("Could not publish transaction {} to Kafka: {}", record.transactionId(), e.getMessage());
        }
    }

    @Override
    public void publishStrict(TransactionRecord record) {
        send(record); // throws on failure so the saga compensates
    }

    private void send(TransactionRecord record) {
        try {
            String json = objectMapper.writeValueAsString(record);
            kafkaTemplate.send(TOPIC, record.transactionId(), json).get(10, TimeUnit.SECONDS);
            log.info("Published transaction {} to Kafka topic {}", record.transactionId(), TOPIC);
            broadcastPublished(record);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing to Kafka", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish transaction to Kafka: " + e.getMessage(), e);
        }
    }

    /**
     * Mirrors a successful send onto the live stream — after the broker has
     * acknowledged it, never before: a {@code KAFKA_PUBLISHED} event means the
     * record is on the topic and readable, which is the only claim worth making
     * on a stream an operator is watching to decide whether the pipeline is
     * healthy.
     *
     * <p>Reached from both {@link #publish} and {@link #publishStrict}, because
     * both go through {@code send}. Instrumenting only the strict path would leave
     * the fire-and-forget path invisible while it was quietly publishing, which is
     * the path where a swallowed failure is hardest to notice.
     */
    private void broadcastPublished(TransactionRecord record) {
        broadcaster.publish(new LiveEvent(
                Instant.now(),
                LiveEvent.SOURCE_KAFKA_PUBLISHER,
                LiveEvent.TYPE_KAFKA_PUBLISHED,
                "Published transaction " + record.transactionId() + " to " + TOPIC,
                record.transactionId(),
                LiveEvent.metadataOf(
                        "topic", TOPIC,
                        "type", record.type(),
                        "fromAccountId", record.fromAccountId(),
                        "toAccountId", record.toAccountId(),
                        "amount", record.amount())));
    }
}
