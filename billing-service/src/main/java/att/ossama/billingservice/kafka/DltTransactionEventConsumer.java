package att.ossama.billingservice.kafka;

import att.ossama.billingservice.entities.DeadLetterTransaction;
import att.ossama.billingservice.repository.DeadLetterTransactionRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Consumes "ledger-events.DLT" — the parking topic the main listener's
 * {@code DeadLetterPublishingRecoverer} publishes to after three failed
 * attempts — and persists each poison message to
 * {@code dead_letter_transactions}.
 *
 * <p>This is the "what failed" half of the observability story, opposite
 * {@code archived_transactions} which answers "what succeeded". A row is an
 * occurrence, not an idempotent entity: publishing the same payload twice
 * yields two rows by design.
 *
 * <p>Receives the whole {@link ConsumerRecord} because the {@code kafka_dlt-*}
 * headers are the only place the original coordinates survive. Header names are
 * matched by suffix, since the prefix differs across
 * {@code DeadLetterPublishingRecoverer} versions. A record with missing or
 * unparseable headers is still persisted with null coordinates — data loss is
 * worse than an incomplete row.
 */
@Component
public class DltTransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DltTransactionEventConsumer.class);

    // Suffixes only: the live names are kafka_dlt-original-topic and friends.
    private static final String ORIGINAL_TOPIC = "original-topic";
    private static final String ORIGINAL_PARTITION = "original-partition";
    private static final String ORIGINAL_OFFSET = "original-offset";
    private static final String EXCEPTION_FQCN = "exception-fqcn";
    private static final String EXCEPTION_MESSAGE = "exception-message";

    /** Used when the original topic header is missing or blank. */
    private static final String FALLBACK_TOPIC = "ledger-events";

    private final DeadLetterTransactionRepository repository;

    public DltTransactionEventConsumer(DeadLetterTransactionRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "ledger-events.DLT", containerFactory = "dltListenerContainerFactory")
    public void onDeadLetter(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Headers headers = record.headers();
        String originalTopic = asText(find(headers, ORIGINAL_TOPIC));
        Integer originalPartition = asInt(find(headers, ORIGINAL_PARTITION));
        Long originalOffset = asLong(find(headers, ORIGINAL_OFFSET));
        String exceptionFqcn = asText(find(headers, EXCEPTION_FQCN));
        String exceptionMessage = asText(find(headers, EXCEPTION_MESSAGE));

        DeadLetterTransaction row = DeadLetterTransaction.builder()
                .topic(originalTopic == null || originalTopic.isBlank() ? FALLBACK_TOPIC : originalTopic)
                .partition(originalPartition)
                .offset(originalOffset)
                .recordKey(record.key())
                .payload(record.value())
                .exceptionFqcn(exceptionFqcn)
                .exceptionMessage(exceptionMessage)
                .failedAt(Instant.now().toString())
                .build();

        try {
            DeadLetterTransaction saved = repository.save(row);
            // ERROR, not WARN: a row here means the pipeline dropped a record.
            log.error("Poison message from {}:{}@{} persisted as dead_letter_transactions id={} ({})",
                    saved.getTopic(), originalPartition, originalOffset, saved.getId(), exceptionFqcn);
        } catch (RuntimeException e) {
            // One attempt, no recoverer (see DltKafkaConsumerConfig): throwing
            // here logs once and skips the record instead of republishing it, so
            // no DLT ladder forms. Nothing is acknowledged, because a failed
            // insert must not look consumed.
            log.error("Failed to persist poison message from {}:{}@{}; skipping (no republish)",
                    originalTopic, originalPartition, originalOffset, e);
            throw e;
        }

        ack.acknowledge();
    }

    /** First header whose key ends with the given suffix, or null. */
    private static Header find(Headers headers, String suffix) {
        for (Header header : headers) {
            if (header.key().endsWith(suffix)) {
                return header;
            }
        }
        return null;
    }

    private static String asText(Header header) {
        Object value = header == null ? null : header.value();
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    private static Integer asInt(Header header) {
        Object value = header == null ? null : header.value();
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof byte[] bytes) {
                if (bytes.length == Integer.BYTES) {
                    return ByteBuffer.wrap(bytes).getInt();
                }
                return Integer.valueOf(new String(bytes, StandardCharsets.UTF_8).trim());
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            return Integer.valueOf(value.toString().trim());
        } catch (RuntimeException e) {
            // Unparseable header: keep the row, lose the coordinate.
            return null;
        }
    }

    private static Long asLong(Header header) {
        Object value = header == null ? null : header.value();
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof byte[] bytes) {
                if (bytes.length == Long.BYTES) {
                    return ByteBuffer.wrap(bytes).getLong();
                }
                return Long.valueOf(new String(bytes, StandardCharsets.UTF_8).trim());
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
            return Long.valueOf(value.toString().trim());
        } catch (RuntimeException e) {
            // Unparseable header: keep the row, lose the coordinate.
            return null;
        }
    }
}
