package att.ossama.ledgerservice.outbox;

import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStep;
import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.persistence.entity.OutboxEventEntity;
import att.ossama.ledgerservice.persistence.repository.OutboxEventJpaRepository;
import att.ossama.ledgerservice.saga.SagaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What the two writes of the archive transaction actually contain. The transaction
 * itself is Spring's contract (and is guarded structurally), but the row's shape is
 * this class's job — including the payload being the exact stored string the relay
 * will later send, and the receiver of the copy never being mutated.
 */
@ExtendWith(MockitoExtension.class)
class OutboxArchiveStepTest {

    private static final Instant START = Instant.parse("2026-05-06T07:08:09Z");
    private static final TransactionRecord RECORD =
            new TransactionRecord("tx-outbox", "TRANSFER", null, "ACC-A", "ACC-B", 12.5, START.toString());

    @Mock private SagaRepository sagaRepository;
    @Mock private OutboxEventJpaRepository outboxRepository;

    private OutboxArchiveStep step;

    @BeforeEach
    void setUp() {
        step = new OutboxArchiveStep(sagaRepository, outboxRepository);
    }

    @Test
    void savesTheArchiveStepAndOnePendingRow() throws Exception {
        SagaState saga = sagaWithOneStep();

        SagaState archived = step.archive(saga, RECORD, START, true);

        ArgumentCaptor<SagaState> savedSaga = ArgumentCaptor.forClass(SagaState.class);
        verify(sagaRepository).save(savedSaga.capture());
        assertThat(savedSaga.getValue()).isNotSameAs(saga);
        assertThat(savedSaga.getValue().getSteps()).extracting(SagaStep::getName)
                .containsExactly("VALIDATE", "ARCHIVE");
        assertThat(savedSaga.getValue().getSteps().get(1).getStatus()).isEqualTo("EXECUTED");

        ArgumentCaptor<OutboxEventEntity> savedRow = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxRepository).save(savedRow.capture());
        OutboxEventEntity row = savedRow.getValue();

        assertThat(row.getId()).hasSize(36).isNotBlank();
        assertThat(row.getAggregateType()).isEqualTo("TRANSACTION");
        assertThat(row.getAggregateId()).isEqualTo("tx-outbox");
        assertThat(row.getEventType()).isEqualTo("TRANSFER");
        assertThat(row.getCreatedAt()).isEqualTo(START);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getParkedAt()).isNull();
        assertThat(row.getNextAttemptAt()).isNull();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getPayload()).isEqualTo(new ObjectMapper().writeValueAsString(RECORD));

        assertThat(archived).isNotSameAs(saga);
        assertThat(saga.getSteps()).extracting(SagaStep::getName).containsExactly("VALIDATE");
    }

    @Test
    void recordsTheStepWithoutAnOutboxRowWhenPublishingIsNotWanted() {
        SagaState saga = sagaWithOneStep();

        SagaState archived = step.archive(saga, RECORD, START, false);

        verify(sagaRepository).save(archived);
        verifyNoInteractions(outboxRepository);
        assertThat(archived.getSteps()).extracting(SagaStep::getName)
                .containsExactly("VALIDATE", "ARCHIVE");
        assertThat(saga.getSteps()).extracting(SagaStep::getName).containsExactly("VALIDATE");
    }

    private static SagaState sagaWithOneStep() {
        SagaState saga = new SagaState("tx-outbox", "ACC-A", "ACC-B", 12.5, START);
        saga.addStep("VALIDATE", START, null, "EXECUTED");
        return saga;
    }
}
