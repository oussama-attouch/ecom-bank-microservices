package att.ossama.ledgerservice.outbox;

import att.ossama.ledgerservice.observability.LiveEvent;
import att.ossama.ledgerservice.observability.LiveEventBroadcaster;
import att.ossama.ledgerservice.persistence.entity.OutboxEventEntity;
import att.ossama.ledgerservice.persistence.repository.OutboxEventJpaRepository;
import att.ossama.ledgerservice.publisher.KafkaTransactionEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The relay's contract, asserted against mocks: what a poll sends, what it writes
 * back, when it stops early, and the exact backoff sequence.
 *
 * <p>The clock is fixed, so every instant the poller writes is predictable — which
 * is the only way to assert {@code 1s, 2s, 4s, 8s} rather than "about a second".
 * The transaction template is stubbed to run its callback inline, so the batch's
 * writes are observable in the same order the real transaction would perform them.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-06-07T08:09:10Z");
    private static final String TOPIC = KafkaTransactionEventPublisher.TOPIC;
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 10;

    @Mock private OutboxEventJpaRepository outboxRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private LiveEventBroadcaster broadcaster;
    @Mock private TransactionTemplate transactionTemplate;

    private SimpleMeterRegistry meterRegistry;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new OutboxPublisher(
                outboxRepository,
                kafkaTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry,
                broadcaster,
                transactionTemplate,
                BATCH_SIZE,
                MAX_ATTEMPTS);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void publishesTheStoredPayloadAndLeavesTheAttemptCountAtZero() {
        OutboxEventEntity row = row("tx-1", 0);
        claim(row);
        sendSucceeds(row);

        int published = publisher.poll();

        assertThat(published).isEqualTo(1);
        assertThat(row.getPublishedAt()).isEqualTo(NOW);
        assertThat(row.getLastError()).isNull();
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getParkedAt()).isNull();
        assertThat(row.getNextAttemptAt()).isNull();
        verify(outboxRepository).save(row);

        ArgumentCaptor<LiveEvent> streamed = ArgumentCaptor.forClass(LiveEvent.class);
        verify(broadcaster).publish(streamed.capture());
        assertThat(streamed.getValue().source()).isEqualTo(LiveEvent.SOURCE_KAFKA_PUBLISHER);
        assertThat(streamed.getValue().type()).isEqualTo(LiveEvent.TYPE_KAFKA_PUBLISHED);
        assertThat(streamed.getValue().transactionId()).isEqualTo("tx-1");
        assertThat(streamed.getValue().metadata())
                .containsEntry("topic", TOPIC)
                .containsEntry("fromAccountId", "ACC-A")
                .containsEntry("toAccountId", "ACC-B");
        assertThat(meterRegistry.counter("outbox.parked.count").count()).isZero();
    }

    @ParameterizedTest(name = "attempt {0} -> backoff {1}s")
    @CsvSource({"0, 1", "1, 2", "2, 4", "3, 8"})
    void backsOffExponentiallyAfterAFailedPublish(int currentAttempts, long expectedBackoffSeconds) {
        OutboxEventEntity row = row("tx-backoff", currentAttempts);
        claim(row);
        sendFails(row, new org.apache.kafka.common.errors.TimeoutException("broker down"));

        int published = publisher.poll();

        assertThat(published).isZero();
        assertThat(row.getAttemptCount()).isEqualTo(currentAttempts + 1);
        assertThat(row.getLastError()).contains("TimeoutException").contains("broker down");
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(expectedBackoffSeconds));
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getParkedAt()).isNull();
        verify(outboxRepository).save(row);
        verifyNoInteractions(broadcaster);
    }

    @Test
    void parksOnTheLastAllowedAttemptAndCountsItOnce() {
        OutboxEventEntity row = row("tx-poison", MAX_ATTEMPTS - 1);
        claim(row);
        sendFails(row, new RecordTooLargeException("message too large"));

        int published = publisher.poll();

        assertThat(published).isZero();
        assertThat(row.getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(row.getParkedAt()).isEqualTo(NOW);
        assertThat(row.getNextAttemptAt()).isNull();
        assertThat(row.getLastError()).contains("RecordTooLargeException");
        assertThat(meterRegistry.counter("outbox.parked.count").count()).isEqualTo(1.0);
        verify(outboxRepository).save(row);
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());

        // The parked row is outside the claim predicate now: a later poll sends
        // nothing more for it, which is what "never retried" means in practice.
        claim();
        assertThat(publisher.poll()).isZero();
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        assertThat(meterRegistry.counter("outbox.parked.count").count()).isEqualTo(1.0);
    }

    @Test
    void abortsTheBatchOnABrokerLevelFailure() {
        OutboxEventEntity first = row("tx-1", 0);
        OutboxEventEntity second = row("tx-2", 0);
        claim(first, second);
        // The shape Spring Kafka actually produces when no broker answers: a
        // KafkaException wrapper around the producer's TimeoutException. The relay
        // has to classify on the cause chain, or a real outage looks like a
        // record-level failure and the batch marches through every row.
        sendFails(first, new KafkaException("Send failed",
                new org.apache.kafka.common.errors.TimeoutException("broker down")));

        int published = publisher.poll();

        assertThat(published).isZero();
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getLastError())
                .as("the stored summary is the underlying cause, not the transport wrapper")
                .contains("broker down")
                .doesNotContain("Send failed");
        assertThat(second.getAttemptCount()).isZero();
        assertThat(second.getLastError()).isNull();
        assertThat(second.getPublishedAt()).isNull();
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        verify(outboxRepository, times(1)).save(any());
    }

    @Test
    void keepsTheBatchGoingAfterARecordLevelFailure() {
        OutboxEventEntity bad = row("tx-bad", 0);
        OutboxEventEntity good = row("tx-good", 0);
        claim(bad, good);
        sendFails(bad, new RecordTooLargeException("message too large"));
        sendSucceeds(good);

        int published = publisher.poll();

        assertThat(published).isEqualTo(1);
        assertThat(bad.getAttemptCount()).isEqualTo(1);
        assertThat(bad.getLastError()).contains("RecordTooLargeException");
        assertThat(bad.getPublishedAt()).isNull();
        assertThat(good.getPublishedAt()).isEqualTo(NOW);
        assertThat(good.getAttemptCount()).isZero();
        verify(outboxRepository, times(2)).save(any());
    }

    @Test
    void doesNothingWhenNothingIsPending() {
        claim();

        assertThat(publisher.poll()).isZero();

        verifyNoInteractions(kafkaTemplate);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void claim(OutboxEventEntity... rows) {
        when(outboxRepository.claimPending(MAX_ATTEMPTS, NOW, BATCH_SIZE)).thenReturn(List.of(rows));
    }

    private void sendSucceeds(OutboxEventEntity row) {
        when(kafkaTemplate.send(TOPIC, row.getAggregateId(), row.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private void sendFails(OutboxEventEntity row, Throwable failure) {
        when(kafkaTemplate.send(TOPIC, row.getAggregateId(), row.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(failure));
    }

    private static OutboxEventEntity row(String aggregateId, int attemptCount) {
        OutboxEventEntity row = new OutboxEventEntity();
        row.setId(UUID.randomUUID().toString());
        row.setAggregateType("TRANSACTION");
        row.setAggregateId(aggregateId);
        row.setEventType("TRANSFER");
        row.setPayload("{\"transactionId\":\"" + aggregateId + "\",\"type\":\"TRANSFER\","
                + "\"accountId\":null,\"fromAccountId\":\"ACC-A\",\"toAccountId\":\"ACC-B\","
                + "\"amount\":10.0,\"timestamp\":\"" + NOW + "\"}");
        row.setCreatedAt(NOW);
        row.setAttemptCount(attemptCount);
        return row;
    }
}
