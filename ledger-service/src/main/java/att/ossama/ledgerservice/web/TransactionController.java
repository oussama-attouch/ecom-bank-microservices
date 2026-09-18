package att.ossama.ledgerservice.web;

import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.InsufficientFundsException;
import att.ossama.ledgerservice.domain.MoneyCreditedEvent;
import att.ossama.ledgerservice.domain.MoneyDebitedEvent;
import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.domain.TransactionType;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.projection.AccountProjection;
import att.ossama.ledgerservice.publisher.TransactionEventPublisher;
import att.ossama.ledgerservice.security.TransferLimitPolicy;
import att.ossama.ledgerservice.web.dto.TransactionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final EventStore eventStore;
    private final AccountProjection projection;
    private final TransactionEventPublisher publisher;
    private final JournalService journalService;
    private final TransferLimitPolicy transferLimitPolicy;

    public TransactionController(EventStore eventStore,
                                 AccountProjection projection,
                                 TransactionEventPublisher publisher,
                                 JournalService journalService,
                                 TransferLimitPolicy transferLimitPolicy) {
        this.eventStore = eventStore;
        this.projection = projection;
        this.publisher = publisher;
        this.journalService = journalService;
        this.transferLimitPolicy = transferLimitPolicy;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody TransactionRequest request) {
        TransactionType type;
        try {
            type = TransactionType.valueOf(request.type().trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown transaction type: " + request.type()));
        }

        try {
            // Authorization before validation, and before any event is appended.
            // Any transaction that moves money OUT of an account is subject to
            // the teller limit: DEBIT takes it out directly, TRANSFER takes it
            // out of the source. CREDIT only moves money in.
            if (type == TransactionType.DEBIT || type == TransactionType.TRANSFER) {
                transferLimitPolicy.assertMayMoveFunds(request.amount());
            }

            String transactionId = UUID.randomUUID().toString();
            List<Event> events = switch (type) {
                case CREDIT -> credit(transactionId, request);
                case DEBIT -> debit(transactionId, request);
                case TRANSFER -> transfer(transactionId, request);
            };

            List<Event> appended = eventStore.append(events);
            postJournal(type, transactionId, request);
            publisher.publish(toRecord(type, transactionId, request, appended.get(0).getOccurredAt()));

            return ResponseEntity.status(HttpStatus.CREATED).body(appended);
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private List<Event> credit(String transactionId, TransactionRequest r) {
        requireAccount(r.accountId());
        requireAmount(r.amount());
        return List.of(new MoneyCreditedEvent(
                UUID.randomUUID().toString(), Instant.now(), transactionId,
                r.accountId(), r.amount(), r.description()));
    }

    private List<Event> debit(String transactionId, TransactionRequest r) {
        requireAccount(r.accountId());
        requireAmount(r.amount());
        requireFunds(r.accountId(), r.amount());
        return List.of(new MoneyDebitedEvent(
                UUID.randomUUID().toString(), Instant.now(), transactionId,
                r.accountId(), r.amount(), r.description()));
    }

    private List<Event> transfer(String transactionId, TransactionRequest r) {
        requireAccount(r.fromAccountId());
        requireAccount(r.toAccountId());
        requireAmount(r.amount());
        requireFunds(r.fromAccountId(), r.amount());

        Instant now = Instant.now();
        return List.of(
                new MoneyDebitedEvent(UUID.randomUUID().toString(), now, transactionId,
                        r.fromAccountId(), r.amount(), "TRANSFER to " + r.toAccountId()),
                new MoneyCreditedEvent(UUID.randomUUID().toString(), now, transactionId,
                        r.toAccountId(), r.amount(), "TRANSFER from " + r.fromAccountId()));
    }

    private void requireAccount(String accountId) {
        if (accountId == null || accountId.isBlank() || !projection.exists(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + accountId);
        }
    }

    private void requireAmount(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    private void requireFunds(String accountId, double amount) {
        if (projection.balance(accountId) < amount) {
            // Same domain exception and wording as the saga path
            // (TransferSagaService), so "insufficient funds" reads identically
            // whichever endpoint the caller used.
            throw new InsufficientFundsException("Insufficient funds in source account");
        }
    }

    private void postJournal(TransactionType type, String transactionId, TransactionRequest r) {
        switch (type) {
            case CREDIT -> journalService.postCredit(transactionId, r.accountId(), r.amount(), r.description());
            case DEBIT -> journalService.postDebit(transactionId, r.accountId(), r.amount(), r.description());
            case TRANSFER -> journalService.postTransfer(transactionId, r.fromAccountId(), r.toAccountId(), r.amount());
        }
        journalService.verifyTransactionBalanced(transactionId);
    }

    private TransactionRecord toRecord(TransactionType type, String transactionId,
                                       TransactionRequest r, Instant occurredAt) {
        return switch (type) {
            case CREDIT -> new TransactionRecord(transactionId, "CREDIT", r.accountId(), null, null, r.amount(), occurredAt.toString());
            case DEBIT -> new TransactionRecord(transactionId, "DEBIT", r.accountId(), null, null, r.amount(), occurredAt.toString());
            case TRANSFER -> new TransactionRecord(transactionId, "TRANSFER", null, r.fromAccountId(), r.toAccountId(), r.amount(), occurredAt.toString());
        };
    }
}
