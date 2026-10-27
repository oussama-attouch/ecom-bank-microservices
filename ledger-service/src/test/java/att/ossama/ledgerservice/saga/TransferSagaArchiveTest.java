package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.domain.SagaStep;
import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.projection.AccountProjection;
import att.ossama.ledgerservice.security.CallerContext;
import att.ossama.ledgerservice.security.TransferLimitPolicy;
import att.ossama.ledgerservice.web.dto.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * The saga's half of the Phase 2 invariant: it delegates the ARCHIVE step exactly
 * once, keeps no transaction of its own, and — when the delegate fails — records
 * {@code ARCHIVE ... FAILED} without a phantom {@code ARCHIVE ... EXECUTED} that a
 * rolled-back writer left in memory.
 *
 * <p>The double returns a copy, as the outbox writer does, so the assertion about
 * the failure path is about the seam's contract rather than about the concrete
 * implementation.
 */
@ExtendWith(MockitoExtension.class)
class TransferSagaArchiveTest {

    private static final String SOURCE = "ACC-SOURCE";
    private static final String DEST = "ACC-DEST";
    private static final double BALANCE = 5_000;
    private static final Instant START = Instant.parse("2026-03-04T05:06:07Z");

    @Mock private EventStore eventStore;
    @Mock private AccountProjection projection;
    @Mock private SagaRepository sagaRepository;
    @Mock private JournalService journalService;

    private RecordingArchiveStep archiveStep;
    private TransferSagaService service;

    @BeforeEach
    void setUp() {
        archiveStep = new RecordingArchiveStep();
        service = new TransferSagaService(eventStore, projection, sagaRepository, archiveStep,
                journalService, new TransferLimitPolicy(caller("MANAGER")), event -> { },
                Clock.fixed(START, ZoneOffset.UTC));
    }

    @Test
    void archivesExactlyOnceAndCompletes() {
        givenFundedAccounts();
        givenTheEventStoreAppends();

        SagaState saga = service.execute(request("tx-archive-once"), false);

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(archiveStep.calls).hasSize(1);

        ArchiveCall call = archiveStep.calls.get(0);
        assertThat(call.record().transactionId()).isEqualTo("tx-archive-once");
        assertThat(call.record().type()).isEqualTo("TRANSFER");
        assertThat(call.record().fromAccountId()).isEqualTo(SOURCE);
        assertThat(call.record().toAccountId()).isEqualTo(DEST);
        assertThat(call.record().amount()).isEqualTo(100);
        assertThat(call.publish()).isTrue();

        assertThat(saga.getSteps()).extracting(SagaStep::getName)
                .containsExactly("VALIDATE", "DEBIT_SOURCE", "CREDIT_DESTINATION", "ARCHIVE");
        assertThat(saga.getSteps()).filteredOn(step -> step.getName().equals("ARCHIVE"))
                .extracting(SagaStep::getStatus)
                .containsExactly("EXECUTED");
    }

    @Test
    void threadsThePublishFlagThroughToTheSeam() {
        givenFundedAccounts();
        givenTheEventStoreAppends();

        service.execute(request("tx-no-publish"), false, START, false);

        assertThat(archiveStep.calls).hasSize(1);
        assertThat(archiveStep.calls.get(0).publish()).isFalse();
    }

    @Test
    void failedArchiveCompensatesWithoutLeavingAPhantomArchiveStep() {
        givenFundedAccounts();
        givenTheEventStoreAppends();
        archiveStep.failWith(new IllegalStateException("outbox insert failed"));

        SagaState saga = service.execute(request("tx-archive-fails"), false);

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(saga.getErrorMessage()).contains("Archive failed");
        assertThat(saga.getSteps()).filteredOn(step -> step.getName().equals("ARCHIVE"))
                .extracting(SagaStep::getStatus)
                .containsExactly("FAILED");
        assertThat(saga.getSteps()).extracting(SagaStep::getName)
                .contains("COMPENSATE_DEBIT_" + DEST, "COMPENSATE_CREDIT_" + SOURCE);
    }

    private void givenFundedAccounts() {
        when(projection.exists(SOURCE)).thenReturn(true);
        when(projection.exists(DEST)).thenReturn(true);
        when(projection.balance(SOURCE)).thenReturn(BALANCE);
    }

    private void givenTheEventStoreAppends() {
        when(eventStore.append(anyList(), any())).thenAnswer(invocation -> {
            List<Event> appended = invocation.getArgument(0);
            appended.forEach(event -> event.setOffset(1L));
            return appended;
        });
    }

    private static TransferRequest request(String transactionId) {
        return new TransferRequest(SOURCE, DEST, 100, transactionId);
    }

    private static CallerContext caller(String... roles) {
        CallerContext context = new CallerContext();
        context.setRoles(new LinkedHashSet<>(Set.of(roles)));
        return context;
    }

    /** Records what the saga asked for; returns the saga plus the step, as a copy. */
    private static final class RecordingArchiveStep implements ArchiveStep {

        private final List<ArchiveCall> calls = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public SagaState archive(SagaState saga, TransactionRecord record, Instant archiveAt, boolean publish) {
            calls.add(new ArchiveCall(saga, record, archiveAt, publish));
            if (failure != null) {
                throw failure;
            }
            return saga.copyWithStep("ARCHIVE", archiveAt, null, "EXECUTED");
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }
    }

    private record ArchiveCall(SagaState saga, TransactionRecord record, Instant archiveAt, boolean publish) {
    }
}
