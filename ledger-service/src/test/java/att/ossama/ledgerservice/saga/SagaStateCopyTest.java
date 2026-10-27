package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.domain.SagaStep;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ARCHIVE step is written inside a transaction that can roll back, so the saga
 * it is added to has to be a copy. A rollback cannot undo an in-memory mutation,
 * and the compensation branch would then append {@code ARCHIVE ... FAILED} to a
 * saga already holding {@code ARCHIVE ... EXECUTED} — two contradictory archive
 * steps in the one path whose audit trail matters most.
 */
class SagaStateCopyTest {

    private static final Instant START = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant VALIDATE_AT = START.plusMillis(120);
    private static final Instant DEBIT_AT = START.plusMillis(300);
    private static final Instant ARCHIVE_AT = START.plusMillis(900);

    @Test
    void addsTheStepToACopyAndLeavesTheOriginalUntouched() {
        SagaState original = saga();
        List<SagaStep> stepsBefore = List.copyOf(original.getSteps());

        SagaState copy = original.copyWithStep("ARCHIVE", ARCHIVE_AT, null, "EXECUTED");

        assertThat(copy).isNotSameAs(original);
        assertThat(copy.getSteps()).extracting(SagaStep::getName)
                .containsExactly("VALIDATE", "DEBIT_SOURCE", "ARCHIVE");
        SagaStep added = copy.getSteps().get(2);
        assertThat(added.getTimestamp()).isEqualTo(ARCHIVE_AT);
        assertThat(added.getOffset()).isNull();
        assertThat(added.getStatus()).isEqualTo("EXECUTED");

        assertThat(original.getSteps()).hasSize(2);
        assertThat(original.getSteps()).containsExactlyElementsOf(stepsBefore);
        assertThat(original.getSteps()).extracting(SagaStep::getName)
                .containsExactly("VALIDATE", "DEBIT_SOURCE");
    }

    @Test
    void copiesIdentityAndStartInstantSoTheCopyIsTheSameSaga() {
        SagaState original = saga();

        SagaState copy = original.copyWithStep("ARCHIVE", ARCHIVE_AT, null, "EXECUTED");

        assertThat(copy.getTransactionId()).isEqualTo(original.getTransactionId());
        assertThat(copy.getSourceAccountId()).isEqualTo(original.getSourceAccountId());
        assertThat(copy.getDestinationAccountId()).isEqualTo(original.getDestinationAccountId());
        assertThat(copy.getAmount()).isEqualTo(original.getAmount());
        assertThat(copy.getStartedAt()).isEqualTo(original.getStartedAt());
    }

    @Test
    void copiesTerminalStateInsteadOfResettingItToTheDefault() {
        SagaState original = saga();
        original.finish(SagaStatus.COMPENSATING, "archive failed", ARCHIVE_AT);

        SagaState copy = original.copyWithStep("ARCHIVE", ARCHIVE_AT, null, "FAILED");

        assertThat(copy.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(copy.getErrorMessage()).isEqualTo("archive failed");
        assertThat(copy.getCompletedAt()).isEqualTo(ARCHIVE_AT);
    }

    private static SagaState saga() {
        SagaState saga = new SagaState("tx-copy", "ACC-A", "ACC-B", 42.0, START);
        saga.addStep("VALIDATE", VALIDATE_AT, null, "EXECUTED");
        saga.addStep("DEBIT_SOURCE", DEBIT_AT, 7L, "EXECUTED");
        return saga;
    }
}
