package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.InsufficientFundsException;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The saga path must reject a transfer the source account cannot cover, at the
 * VALIDATE step, before anything is written.
 */
@ExtendWith(MockitoExtension.class)
class TransferSagaServiceFundsCheckTest {

    private static final String SOURCE = "ACC-SOURCE";
    private static final String DEST = "ACC-DEST";
    private static final double BALANCE = 5_000;

    @Mock private EventStore eventStore;
    @Mock private AccountProjection projection;
    @Mock private SagaRepository sagaRepository;
    @Mock private ArchiveStep archiveStep;
    @Mock private JournalService journalService;

    private TransferSagaService service;

    @BeforeEach
    void setUp() {
        // A MANAGER is used throughout so the teller limit never interferes with
        // what these tests are actually about: the funds check.
        //
        // The broadcaster is a no-op lambda: this test is about the funds check,
        // and the live stream is not what it asserts on. A lambda is what the
        // interface is for — one implementation publishes, the other does nothing,
        // and neither is the caller's business.
        service = new TransferSagaService(eventStore, projection, sagaRepository, archiveStep,
                journalService, new TransferLimitPolicy(caller("MANAGER")), event -> { }, Clock.systemUTC());
    }

    private static CallerContext caller(String... roles) {
        CallerContext context = new CallerContext();
        context.setRoles(new LinkedHashSet<>(Set.of(roles)));
        return context;
    }

    private TransferRequest request(double amount) {
        return new TransferRequest(SOURCE, DEST, amount, "tx-funds-check");
    }

    /**
     * Let the ARCHIVE step succeed so these tests can assert what happened before
     * it. The step is added to a copy, exactly as the outbox writer does — the
     * fake has to honour the seam's contract or the saga would stream a step it
     * does not hold.
     */
    private void archiveSucceeds() {
        when(archiveStep.archive(any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            SagaState saga = invocation.getArgument(0);
            Instant archiveAt = invocation.getArgument(2);
            return saga.copyWithStep("ARCHIVE", archiveAt, null, "EXECUTED");
        });
    }

    @Test
    void rejectsTransferThatExceedsTheSourceBalance() {
        when(projection.exists(SOURCE)).thenReturn(true);
        when(projection.exists(DEST)).thenReturn(true);
        when(projection.balance(SOURCE)).thenReturn(BALANCE);

        assertThatThrownBy(() -> service.execute(request(1_000_000), false))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");
    }

    /**
     * A rejected transfer must leave no trace: no events appended, no archive
     * step attempted, and no saga saved. The idempotency lookup
     * ({@code sagaRepository.find}) is a read that happens before validation and
     * is expected; what must not happen is a write.
     */
    @Test
    void rejectedTransferWritesNothing() {
        when(projection.exists(SOURCE)).thenReturn(true);
        when(projection.exists(DEST)).thenReturn(true);
        when(projection.balance(SOURCE)).thenReturn(BALANCE);

        assertThatThrownBy(() -> service.execute(request(1_000_000), false))
                .isInstanceOf(InsufficientFundsException.class);

        verifyNoInteractions(eventStore);
        verifyNoInteractions(archiveStep);
        verify(sagaRepository, never()).save(any());
    }

    @Test
    void allowsTransferUpToTheFullBalance() {
        when(projection.exists(SOURCE)).thenReturn(true);
        when(projection.exists(DEST)).thenReturn(true);
        when(projection.balance(SOURCE)).thenReturn(BALANCE);
        when(eventStore.append(anyList(), any())).thenAnswer(invocation -> {
            List<Event> appended = invocation.getArgument(0);
            appended.forEach(event -> event.setOffset(1L));
            return appended;
        });
        archiveSucceeds();

        SagaState saga = service.execute(request(BALANCE), false);

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
        verify(eventStore, times(2)).append(anyList(), any());
        verify(archiveStep).archive(any(), any(), any(), eq(true));
    }

    @Test
    void allowsTransferWellUnderTheBalance() {
        when(projection.exists(SOURCE)).thenReturn(true);
        when(projection.exists(DEST)).thenReturn(true);
        when(projection.balance(SOURCE)).thenReturn(BALANCE);
        when(eventStore.append(anyList(), any())).thenAnswer(invocation -> {
            List<Event> appended = invocation.getArgument(0);
            appended.forEach(event -> event.setOffset(1L));
            return appended;
        });
        archiveSucceeds();

        SagaState saga = service.execute(request(100), false);

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
    }

    /** The funds check must not fire for a missing account: that is a 404 case. */
    @Test
    void missingSourceAccountIsNotReportedAsInsufficientFunds() {
        when(projection.exists(SOURCE)).thenReturn(false);

        assertThatThrownBy(() -> service.execute(request(100), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Source account not found");

        verify(projection, never()).balance(SOURCE);
    }
}
