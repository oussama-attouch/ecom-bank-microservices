package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStep;
import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.publisher.TransactionEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The gate-off path is the one an untouched checkout runs, so its contract is
 * asserted rather than assumed: publish inline, then record the step — and, on a
 * publisher failure, throw <em>before</em> recording anything, which is what keeps
 * the saga's compensation branch meaning what it meant before the outbox existed.
 */
@ExtendWith(MockitoExtension.class)
class LegacyArchiveStepTest {

    private static final Instant START = Instant.parse("2026-04-05T06:07:08Z");
    private static final TransactionRecord RECORD =
            new TransactionRecord("tx-legacy", "TRANSFER", null, "ACC-A", "ACC-B", 10.0, START.toString());

    @Mock private TransactionEventPublisher publisher;

    private LegacyArchiveStep step;
    private SagaState saga;

    @BeforeEach
    void setUp() {
        step = new LegacyArchiveStep(publisher);
        saga = new SagaState("tx-legacy", "ACC-A", "ACC-B", 10.0, START);
        saga.addStep("VALIDATE", START, null, "EXECUTED");
    }

    @Test
    void publishesInlineThenRecordsTheStep() {
        SagaState returned = step.archive(saga, RECORD, START.plusSeconds(1), true);

        verify(publisher).publishStrict(RECORD);
        assertThat(returned).isSameAs(saga);
        assertThat(returned.getSteps()).extracting(SagaStep::getName)
                .containsExactly("VALIDATE", "ARCHIVE");
        assertThat(returned.getSteps().get(1).getStatus()).isEqualTo("EXECUTED");
    }

    @Test
    void recordsTheStepWithoutPublishingWhenTheRunMustNotAnnounceItself() {
        SagaState returned = step.archive(saga, RECORD, START.plusSeconds(1), false);

        verifyNoInteractions(publisher);
        assertThat(returned.getSteps()).extracting(SagaStep::getName)
                .containsExactly("VALIDATE", "ARCHIVE");
    }

    @Test
    void aPublisherFailurePropagatesBeforeTheStepIsRecorded() {
        doThrow(new RuntimeException("kafka down")).when(publisher).publishStrict(any());

        assertThatThrownBy(() -> step.archive(saga, RECORD, START.plusSeconds(1), true))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("kafka down");

        assertThat(saga.getSteps()).extracting(SagaStep::getName).containsExactly("VALIDATE");
    }
}
