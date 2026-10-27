package att.ossama.ledgerservice.outbox;

import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.observability.LiveEvent;
import att.ossama.ledgerservice.observability.LiveEventBroadcaster;
import att.ossama.ledgerservice.persistence.entity.OutboxEventEntity;
import att.ossama.ledgerservice.persistence.repository.OutboxEventJpaRepository;
import att.ossama.ledgerservice.publisher.KafkaTransactionEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.errors.BrokerNotAvailableException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.NetworkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The relay: publishes committed outbox rows to Kafka and records what happened.
 *
 * <p>One poll claims a batch with {@code FOR UPDATE SKIP LOCKED}, sends each row's
 * stored payload verbatim under its transaction id, and writes exactly one update
 * per row. Success marks the row published; failure bookkeeps the attempt and, on
 * the attempt that exhausts the budget, parks the row in that same update.
 *
 * <h2>Why the transaction is here and not on {@code poll()}</h2>
 * {@code @Transactional} on {@link #poll()} would be dead code: {@code @Scheduled}
 * calls it through {@code this}, which never crosses the proxy. That is the same
 * self-invocation that makes an annotation on the saga useless. The template makes
 * the boundary explicit and, just as importantly, keeps the claim lock held for the
 * batch: the locks are the reason a second poller cannot double-publish a row this
 * one is already working on.
 *
 * <h2>Aborting a batch</h2>
 * A broker-level failure (timeout, network, not connected) is treated as "the
 * broker is down for everyone", so the loop stops immediately and the remaining
 * claimed rows are left untouched. Each attempt can cost the send timeout while the
 * claim locks are held, so carrying on would multiply that cost by the batch size.
 * A record-level failure (too large, unserializable) is that row's own problem and
 * does not stop the batch.
 *
 * <h2>Fail fast</h2>
 * No Kafka producer, no bean: with {@code ledger.outbox.enabled=true} but
 * {@code ledger.publisher=http} there is no {@code KafkaTemplate} to inject, so the
 * context refuses to start rather than running a relay that answers "fine" while
 * publishing nothing. Enable the demo with
 * {@code --ledger.outbox.enabled=true --ledger.publisher=kafka}.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** How long one send may take before the attempt counts as failed. */
    private static final int SEND_TIMEOUT_SECONDS = 5;

    /** Ceiling of the exponential backoff; beyond it the wait stops growing. */
    private static final long MAX_BACKOFF_SECONDS = 300;

    /** {@code last_error} is a summary for an operator, not a log file. */
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEventJpaRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final LiveEventBroadcaster broadcaster;
    private final int batchSize;
    private final int maxAttempts;
    private final Counter parkedCounter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Single-flight guard. A manual poll and the scheduled tick must never run a
     * batch concurrently, and the loser returns without work rather than queueing a
     * duplicate one.
     */
    private final ReentrantLock singleFlight = new ReentrantLock();

    public OutboxPublisher(OutboxEventJpaRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           Clock clock,
                           MeterRegistry meterRegistry,
                           LiveEventBroadcaster broadcaster,
                           TransactionTemplate transactionTemplate,
                           @Value("${ledger.outbox.batch-size:50}") int batchSize,
                           @Value("${ledger.outbox.max-attempts:10}") int maxAttempts) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.broadcaster = broadcaster;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.parkedCounter = meterRegistry.counter("outbox.parked.count");
    }

    /**
     * Publishes one batch, synchronously, and returns how many events reached the
     * broker. Also what the admin endpoint calls, so the demo does not have to wait
     * for a tick.
     */
    public int poll() {
        if (!singleFlight.tryLock()) {
            log.debug("Outbox poll already in flight - this caller returns without work");
            return 0;
        }
        try {
            Integer published = transactionTemplate.execute(status -> publishBatch());
            return published == null ? 0 : published;
        } finally {
            singleFlight.unlock();
        }
    }

    /**
     * The scheduled trigger, and nothing else. {@code fixedDelay} so a slow broker
     * cannot stack runs behind each other.
     */
    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval:5000}")
    void scheduledPoll() {
        poll();
    }

    private int publishBatch() {
        Instant now = clock.instant();
        List<OutboxEventEntity> claimed = outboxRepository.claimPending(maxAttempts, now, batchSize);
        int published = 0;

        for (OutboxEventEntity row : claimed) {
            try {
                kafkaTemplate.send(KafkaTransactionEventPublisher.TOPIC, row.getAggregateId(), row.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(row, e);
                log.warn("Outbox poll interrupted on {} - aborting the rest of the batch", row.getAggregateId());
                break;
            } catch (Exception e) {
                Throwable failure = rootCause(e);
                recordFailure(row, failure);
                if (isBrokerLevel(failure)) {
                    log.warn("Broker-level failure on {} ({}) - aborting the rest of the batch",
                            row.getAggregateId(), failure.toString());
                    break;
                }
                log.warn("Record-level failure on {} ({}) - that row's problem, continuing",
                        row.getAggregateId(), failure.toString());
                continue;
            }
            recordPublished(row);
            published++;
        }
        return published;
    }

    /**
     * Success: the broker acknowledged, so the row is published and its old error is
     * cleared. {@code attempt_count} is deliberately untouched - a clean publish
     * reads as zero attempts, and a retried one keeps its history.
     */
    private void recordPublished(OutboxEventEntity row) {
        row.setPublishedAt(clock.instant());
        row.setLastError(null);
        outboxRepository.save(row);
        mirrorPublished(row);
    }

    /**
     * Failure: one update carries the attempt count, the error, and either the next
     * backoff instant or the parking stamp. Parking on the same update is not an
     * optimisation - a second statement could leave the row at {@code maxAttempts}
     * with no {@code parked_at}, which the claim query would never select again and
     * the status rule would report as a permanent {@code FAILED}.
     */
    private void recordFailure(OutboxEventEntity row, Throwable failure) {
        Instant now = clock.instant();
        int attempt = row.getAttemptCount() + 1;
        row.setAttemptCount(attempt);
        row.setLastError(truncate(failure));

        if (attempt >= maxAttempts) {
            row.setParkedAt(now);
            row.setNextAttemptAt(null);
            parkedCounter.increment();
            log.error("Outbox event {} for transaction {} parked after {} attempt(s): {}",
                    row.getId(), row.getAggregateId(), attempt, failure.toString());
        } else {
            row.setNextAttemptAt(now.plusSeconds(backoffSeconds(attempt)));
        }
        outboxRepository.save(row);
    }

    /**
     * The live-stream mirror, in the shape the Kafka publisher has always used, so
     * Observability -> Live keeps showing publishes once the relay owns them.
     *
     * <p>Best-effort by construction: the message is already on the topic by the
     * time this runs, and a display failure must never turn that into a failed
     * attempt that republishes it. The metadata comes from the stored payload, so
     * the stream reports the same fields the old publisher did.
     */
    private void mirrorPublished(OutboxEventEntity row) {
        try {
            TransactionRecord record = readPayload(row.getPayload());
            broadcaster.publish(new LiveEvent(
                    clock.instant(),
                    LiveEvent.SOURCE_KAFKA_PUBLISHER,
                    LiveEvent.TYPE_KAFKA_PUBLISHED,
                    "Published transaction " + row.getAggregateId() + " to "
                            + KafkaTransactionEventPublisher.TOPIC,
                    row.getAggregateId(),
                    LiveEvent.metadataOf(
                            "topic", KafkaTransactionEventPublisher.TOPIC,
                            "type", row.getEventType(),
                            "attempts", row.getAttemptCount(),
                            "fromAccountId", record == null ? null : record.fromAccountId(),
                            "toAccountId", record == null ? null : record.toAccountId(),
                            "amount", record == null ? null : record.amount())));
        } catch (RuntimeException e) {
            log.debug("Could not mirror outbox publish {} onto the live stream", row.getId(), e);
        }
    }

    private TransactionRecord readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, TransactionRecord.class);
        } catch (JsonProcessingException e) {
            log.debug("Outbox payload is not a transaction record; mirroring without its fields", e);
            return null;
        }
    }

    /**
     * Backoff for the attempt just recorded: 1s, 2s, 4s, 8s ... capped at 300s. The
     * cap matters because {@code max-attempts} is configurable - an uncapped
     * exponent would overflow a long before it reached the ceiling.
     */
    private static long backoffSeconds(int attempt) {
        long seconds = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        return Math.min(seconds, MAX_BACKOFF_SECONDS);
    }

    /**
     * Whether the failure says "the broker is down for everyone" rather than "this
     * record is bad". Only the broker-level signatures abort a batch; anything
     * unrecognised is treated as that row's problem so an unexpected record-level
     * exception cannot stall every event behind it.
     *
     * <p>The list is checked against kafka-clients 3.7.1 rather than assumed: there
     * is no {@code NotConnectedException} in that jar (the spec named one from
     * memory), so "not connected" surfaces as {@link NetworkException} or
     * {@link DisconnectException}, and a broker that is unreachable or still
     * electing a leader surfaces as {@link BrokerNotAvailableException}.
     *
     * <p>The whole cause chain is inspected, not just the thrown type, because
     * Spring Kafka wraps producer failures: a broker that is simply not there
     * arrives as {@code org.springframework.kafka.KafkaException: Send failed}
     * around a Kafka {@code TimeoutException}. Classifying on the outer type alone
     * silently turned every real outage into a "record-level" failure that marched
     * through the batch — found by running the relay against a dead broker, not by
     * the unit test that threw the raw exception.
     */
    private static boolean isBrokerLevel(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof org.apache.kafka.common.errors.TimeoutException
                    || current instanceof NetworkException
                    || current instanceof DisconnectException
                    || current instanceof BrokerNotAvailableException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException) {
                return true;
            }
            Throwable next = current.getCause();
            current = (next == current) ? null : next;
        }
        return false;
    }

    /**
     * The deepest cause: what actually went wrong, rather than the transport's
     * wrapper around it. Stored as {@code last_error}, where "Send failed" tells an
     * operator nothing and "Topic ledger-events not present in metadata after
     * 10000 ms" tells them everything.
     */
    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String truncate(Throwable failure) {
        String message = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
