package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.InsufficientFundsException;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.projection.AccountProjection;
import att.ossama.ledgerservice.publisher.TransactionEventPublisher;
import att.ossama.ledgerservice.security.CallerContext;
import att.ossama.ledgerservice.security.TransferLimitPolicy;
import att.ossama.ledgerservice.web.dto.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock private TransactionEventPublisher publisher;
    @Mock private JournalService journalService;

    private TransferSagaService service;

    @BeforeEach
    void setUp() {
        // A MANAGER is used throughout so the teller limit never interferes with
        // what these tests are actually about: the funds check.
        service = new TransferSagaService(eventStore, projection, sagaRepository, publisher,
                journalService, new TransferLimitPolicy(caller("MANAGER")), Clock.systemUTC());
    }

    private static CallerContext caller(String... roles) {
        CallerContext context = new CallerContext();
        context.setRoles(new LinkedHashSet<>(Set.of(roles)));
        return context;
    }

    private TransferRequest request(double amount) {
        return new TransferRequest(SOURCE, DEST, amount, "tx-funds-check");
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
     * A rejected transfer must leave no trace: no events appended, nothing
     * published, and no saga saved. The idempotency lookup
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
        verifyNoInteractions(publisher);
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

        SagaState saga = service.execute(request(BALANCE), false);

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
        verify(eventStore, times(2)).append(anyList(), any());
        verify(publisher).publishStrict(any());
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
