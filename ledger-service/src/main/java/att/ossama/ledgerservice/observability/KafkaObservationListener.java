package att.ossama.ledgerservice.observability;

import att.ossama.ledgerservice.domain.TransactionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Mirrors what this service's own record looks like <em>coming back off</em> the
 * {@code ledger-events} topic onto the live stream.
 *
 * <p>This closes the loop the other four sources leave open. A
 * {@code KAFKA_PUBLISHED} event says a send succeeded; only a consumption proves
 * the record was actually readable from the topic, which is the half of the
 * pipeline that fails when a serializer or a key changes.
 *
 * <h2>Separate consumer group</h2>
 * {@value #GROUP_ID}, not billing-service's {@code billing-service} group. Kafka
 * delivers each partition to exactly one member <em>per group</em>, so sharing a
 * group would split the topic between this listener and the archiver and each
 * would see roughly half the transactions — an observation stream that quietly
 * reported half the traffic would be worse than none. A distinct group means both
 * see everything.
 *
 * <h2>Gated, like everything else behind the flag</h2>
 * With {@code ledger.live-stream.enabled} off this bean is never created, so no
 * listener container is started and no consumer group is registered against the
 * topic. That is the difference between the flag meaning "off" and it meaning
 * "subscribed but not displayed".
 *
 * <h2>Nothing here may throw</h2>
 * A listener that throws makes the container redeliver — for the default error
 * handler, indefinitely — so a malformed record would become a hot loop against
 * {@code ledger-events} for as long as the record stays at the head of the
 * partition. Nothing about observing the topic justifies that, so a payload that
 * cannot be parsed is reported as a {@code KAFKA_CONSUMED} event with its raw
 * body attached and then dropped.
 */
@Component
@ConditionalOnProperty(name = "ledger.live-stream.enabled", havingValue = "true")
public class KafkaObservationListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaObservationListener.class);

    /** Published by {@link att.ossama.ledgerservice.publisher.KafkaTransactionEventPublisher}. */
    public static final String TOPIC = "ledger-events";

    public static final String GROUP_ID = "ledger-observability";

    private final LiveEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KafkaObservationListener(LiveEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * A record deserializes to a {@link TransactionRecord} — the same shape
     * {@code KafkaTransactionEventPublisher} wrote, so a JSON field rename on one
     * side shows up here as a failure rather than as a stream of empty events.
     *
     * <p>The partition and offset travel as headers, marked not-required so a
     * change in how the container is configured degrades to an event without
     * position rather than to an exception.
     */
    @KafkaListener(topics = TOPIC, groupId = GROUP_ID)
    public void onEvent(String message,
                        @Header(value = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
                        @Header(value = KafkaHeaders.OFFSET, required = false) Long offset) {
        Instant observedAt = Instant.now();
        LiveEvent event;
        try {
            TransactionRecord record = objectMapper.readValue(message, TransactionRecord.class);
            event = new LiveEvent(
                    observedAt,
                    LiveEvent.SOURCE_KAFKA_CONSUMER,
                    LiveEvent.TYPE_KAFKA_CONSUMED,
                    "Consumed transaction " + record.transactionId() + " from " + TOPIC,
                    record.transactionId(),
                    LiveEvent.metadataOf(
                            "topic", TOPIC,
                            "partition", partition,
                            "offset", offset,
                            "type", record.type(),
                            "fromAccountId", record.fromAccountId(),
                            "toAccountId", record.toAccountId(),
                            "amount", record.amount()));
        } catch (Exception e) {
            log.warn("Could not read a {} record for observation ({}); the raw body is streamed instead",
                    TOPIC, e.getMessage());
            event = new LiveEvent(
                    observedAt,
                    LiveEvent.SOURCE_KAFKA_CONSUMER,
                    LiveEvent.TYPE_KAFKA_CONSUMED,
                    "Consumed an unreadable record from " + TOPIC,
                    null,
                    LiveEvent.metadataOf(
                            "topic", TOPIC,
                            "partition", partition,
                            "offset", offset,
                            "parseError", e.getMessage(),
                            "raw", message));
        }
        broadcaster.publish(event);
    }
}
