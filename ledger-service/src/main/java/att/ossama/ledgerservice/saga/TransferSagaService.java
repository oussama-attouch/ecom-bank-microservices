package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.InsufficientFundsException;
import att.ossama.ledgerservice.domain.MoneyCreditedEvent;
import att.ossama.ledgerservice.domain.MoneyDebitedEvent;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.projection.AccountProjection;
import att.ossama.ledgerservice.publisher.TransactionEventPublisher;
import att.ossama.ledgerservice.security.TransferLimitPolicy;
import att.ossama.ledgerservice.web.dto.TransferRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates an atomic transfer as a saga:
 *   VALIDATE -> DEBIT_SOURCE -> CREDIT_DESTINATION -> ARCHIVE
 * with compensating events on failure so the transfer is all-or-nothing.
 *
 * <h2>Timestamps</h2>
 * {@link #execute(TransferRequest, boolean)} runs the saga on the wall clock.
 * The explicit-instant overload runs it on a timeline derived from a given
 * moment, which is how the seeder reconstructs history: every event, journal
 * entry and saga step in one run is stamped from that single start instant, laid
 * out by {@link SagaTimeline} with the realistic spacing between steps. Nothing
 * downstream of the start ever reads the clock, so a backfilled saga cannot
 * develop a torn timeline.
 */
@Service
public class TransferSagaService {

    private static final Logger log = LoggerFactory.getLogger(TransferSagaService.class);

    private final EventStore eventStore;
    private final AccountProjection projection;
    private final SagaRepository sagaRepository;
    private final TransactionEventPublisher publisher;
    private final JournalService journalService;
    private final TransferLimitPolicy transferLimitPolicy;
    private final Clock clock;

    public TransferSagaService(EventStore eventStore,
                               AccountProjection projection,
                               SagaRepository sagaRepository,
                               TransactionEventPublisher publisher,
                               JournalService journalService,
                               TransferLimitPolicy transferLimitPolicy,
                               Clock clock) {
        this.eventStore = eventStore;
        this.projection = projection;
        this.sagaRepository = sagaRepository;
        this.publisher = publisher;
        this.journalService = journalService;
        this.transferLimitPolicy = transferLimitPolicy;
        this.clock = clock;
    }

    public SagaState execute(TransferRequest request, boolean simulateFailure) {
        return execute(request, simulateFailure, clock.instant(), true);
    }

    /** Runs the saga as if it started at {@code occurredAt}, publishing the result. */
    public SagaState execute(TransferRequest request, boolean simulateFailure, Instant occurredAt) {
        return execute(request, simulateFailure, occurredAt, true);
    }

    /**
     * Runs the saga as if it started at {@code occurredAt}.
     *
     * @param publish whether to notify downstream processors on the ARCHIVE step.
     *                The seeder passes {@code false}: reconstructing history must
     *                not look like a fresh transaction to billing-service, which
     *                would archive two years of backdated activity all over again.
     *                The step itself is still recorded, so the saga's shape is the
     *                same as a live one.
     */
    public SagaState execute(TransferRequest request, boolean simulateFailure, Instant occurredAt,
                             boolean publish) {
        // Authorisation first: a rejected transfer must not create a saga, append
        // an event, or post a journal entry. Throws ForbiddenException -> 403.
        transferLimitPolicy.assertMayMoveFunds(request.amount());

        // Idempotency: same transactionId => return the existing saga.
        SagaState existing = sagaRepository.find(request.transactionId());
        if (existing != null) {
            return existing;
        }

        String source = request.sourceAccountId();
        String dest = request.destinationAccountId();
        double amount = request.amount();
        String txId = request.transactionId();

        // Step 1: validate
        if (!projection.exists(source)) {
            throw new IllegalArgumentException("Source account not found: " + source);
        }
        if (!projection.exists(dest)) {
            throw new IllegalArgumentException("Destination account not found: " + dest);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (source.equals(dest)) {
            throw new IllegalArgumentException("Source and destination must differ");
        }
        // The direct posting path has always checked funds; without the same
        // check here a transfer could drive the source balance negative. This
        // runs before the saga is created, so a rejected transfer leaves no
        // trace (no saga row, no events, no journal entries).
        if (amount > projection.balance(source)) {
            throw new InsufficientFundsException("Insufficient funds in source account");
        }

        SagaTimeline timeline = new SagaTimeline(occurredAt);

        SagaState saga = new SagaState(txId, source, dest, amount, timeline.startedAt());
        sagaRepository.save(saga);
        saga.addStep("VALIDATE", timeline.validate(), null, "EXECUTED");

        // Step 2: debit source
        Instant debitAt = timeline.debitSource();
        try {
            Event debit = new MoneyDebitedEvent(uuid(), debitAt, txId, source, amount, "SAGA TRANSFER to " + dest);
            List<Event> appended = eventStore.append(List.of(debit), debitAt);
            journalService.postTransferDebit(txId, source, amount, debitAt);
            saga.addStep("DEBIT_SOURCE", debitAt, appended.get(0).getOffset(), "EXECUTED");
        } catch (Exception e) {
            log.error("SAGA {} step DEBIT_SOURCE failed", txId, e);
            saga.finish(SagaStatus.FAILED, "Debit source failed: " + e.getMessage(), timeline.completedAt(debitAt));
            return persist(saga);
        }

        // Step 3: credit destination
        Instant creditAt = timeline.creditDestination(debitAt);
        try {
            Event credit = new MoneyCreditedEvent(uuid(), creditAt, txId, dest, amount, "SAGA TRANSFER from " + source);
            List<Event> appended = eventStore.append(List.of(credit), creditAt);
            journalService.postTransferCredit(txId, dest, amount, creditAt);
            journalService.verifyTransactionBalanced(txId);
            saga.addStep("CREDIT_DESTINATION", creditAt, appended.get(0).getOffset(), "EXECUTED");
        } catch (Exception e) {
            log.error("SAGA {} step CREDIT_DESTINATION failed", txId, e);
            saga.addStep("CREDIT_DESTINATION", creditAt, null, "FAILED");
            Instant reversalAt = timeline.compensateCredit(creditAt);
            if (!reverseDebit(saga, source, amount, txId, reversalAt)) {
                saga.finish(SagaStatus.FAILED, "Credit destination failed and compensation failed: " + e.getMessage(),
                        timeline.completedAt(reversalAt));
                log.error("SAGA {} compensation failed - CRITICAL", txId);
                return persist(saga);
            }
            saga.finish(SagaStatus.COMPENSATING, "Credit destination failed; debit reversed. " + e.getMessage(),
                    timeline.completedAt(reversalAt));
            return persist(saga);
        }

        // Step 4: archive
        Instant archiveAt = timeline.archive(creditAt);
        try {
            if (simulateFailure) {
                throw new RuntimeException("Simulated archive failure (demo mode)");
            }
            if (publish) {
                publisher.publishStrict(
                        new TransactionRecord(txId, "TRANSFER", null, source, dest, amount, archiveAt.toString()));
            }
            saga.addStep("ARCHIVE", archiveAt, null, "EXECUTED");
        } catch (Exception e) {
            log.warn("SAGA {} step ARCHIVE failed: {}", txId, e.getMessage());
            saga.addStep("ARCHIVE", archiveAt, null, "FAILED");
            // Undo the credit first, then the debit: the credit only existed
            // because the debit did, so unwinding it first leaves the last
            // compensation step as the one that restores the source. Both run even
            // if the first fails, so a half-unwound transfer is recorded rather
            // than silently left mid-flight.
            Instant reverseCreditAt = timeline.compensateDebit(archiveAt);
            Instant reverseDebitAt = timeline.compensateCredit(reverseCreditAt);
            boolean reversedCredit = reverseCredit(saga, dest, amount, txId, reverseCreditAt);
            boolean reversedDebit = reverseDebit(saga, source, amount, txId, reverseDebitAt);
            // The second reversal is the later step by construction, so the saga
            // ends when it does whichever way it went.
            Instant endsAt = timeline.completedAt(reverseDebitAt);
            if (!reversedCredit || !reversedDebit) {
                saga.finish(SagaStatus.FAILED, "Archive failed and compensation failed: " + e.getMessage(), endsAt);
                log.error("SAGA {} compensation failed - CRITICAL", txId);
                return persist(saga);
            }
            saga.finish(SagaStatus.COMPENSATING, "Archive failed; transfer reversed. " + e.getMessage(), endsAt);
            return persist(saga);
        }

        saga.finish(SagaStatus.COMPLETED, null, timeline.completedAt(archiveAt));
        log.info("SAGA {} COMPLETED", txId);
        return persist(saga);
    }

    /** Append a MoneyCreditedEvent to reverse a prior debit on the account. */
    private boolean reverseDebit(SagaState saga, String accountId, double amount, String txId, Instant occurredAt) {
        try {
            Event credit = new MoneyCreditedEvent(uuid(), occurredAt, txId, accountId, amount,
                    "SAGA COMPENSATION (reverse debit)");
            List<Event> appended = eventStore.append(List.of(credit), occurredAt);
            journalService.reverseTransferDebit(txId, accountId, amount, occurredAt);
            saga.addStep("COMPENSATE_CREDIT_" + accountId, occurredAt, appended.get(0).getOffset(), "COMPENSATED");
            return true;
        } catch (Exception e) {
            saga.addStep("COMPENSATE_CREDIT_" + accountId, occurredAt, null, "FAILED");
            log.error("SAGA {} reverseDebit failed for {}", txId, accountId, e);
            return false;
        }
    }

    /** Append a MoneyDebitedEvent to reverse a prior credit on the account. */
    private boolean reverseCredit(SagaState saga, String accountId, double amount, String txId, Instant occurredAt) {
        try {
            Event debit = new MoneyDebitedEvent(uuid(), occurredAt, txId, accountId, amount,
                    "SAGA COMPENSATION (reverse credit)");
            List<Event> appended = eventStore.append(List.of(debit), occurredAt);
            journalService.reverseTransferCredit(txId, accountId, amount, occurredAt);
            saga.addStep("COMPENSATE_DEBIT_" + accountId, occurredAt, appended.get(0).getOffset(), "COMPENSATED");
            return true;
        } catch (Exception e) {
            saga.addStep("COMPENSATE_DEBIT_" + accountId, occurredAt, null, "FAILED");
            log.error("SAGA {} reverseCredit failed for {}", txId, accountId, e);
            return false;
        }
    }

    /**
     * Persist the terminal state of the saga. The in-memory store observed later
     * mutations by reference; a durable store has to be written explicitly once
     * the steps and the final status have been recorded.
     */
    private SagaState persist(SagaState saga) {
        sagaRepository.save(saga);
        return saga;
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }
}
