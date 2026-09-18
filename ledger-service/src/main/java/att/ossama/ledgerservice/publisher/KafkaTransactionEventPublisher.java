package att.ossama.ledgerservice.publisher;

import att.ossama.ledgerservice.domain.TransactionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KafkaTransactionEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing to Kafka", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish transaction to Kafka: " + e.getMessage(), e);
        }
    }
}
