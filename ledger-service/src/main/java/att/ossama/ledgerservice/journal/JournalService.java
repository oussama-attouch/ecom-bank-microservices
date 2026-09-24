package att.ossama.ledgerservice.journal;

import att.ossama.ledgerservice.domain.JournalEntry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Posts double-entry journal entries for every money movement and enforces the
 * accounting invariants:
 *  - each entry: debitAccount != creditAccount and amount > 0
 *  - each transaction: total debits == total credits
 *
 * <h2>Timestamps</h2>
 * Every posting method comes in two forms: one that stamps the entry "now" from
 * the injected {@link Clock}, and one that takes the instant explicitly. The
 * no-argument form delegates to the explicit one, so both share a single code
 * path and the seeder can reconstruct history without touching the global clock
 * (which the live KPI deltas depend on).
 */
@Service
public class JournalService {

    public static final String CASH_ACCOUNT = "CASH_ACCOUNT";
    public static final String TRANSFER_CLEARING = "TRANSFER_CLEARING";

    private final JournalEntryRepository repository;
    private final Clock clock;

    public JournalService(JournalEntryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** MONEY_CREDITED to account A -> debit CASH_ACCOUNT, credit A. */
    public JournalEntry postCredit(String transactionId, String accountId, double amount, String description) {
        return postCredit(transactionId, accountId, amount, description, clock.instant());
    }

    /** @see #postCredit(String, String, double, String) */
    public JournalEntry postCredit(String transactionId, String accountId, double amount, String description,
                                   Instant occurredAt) {
        return post(transactionId, CASH_ACCOUNT, accountId, amount, description, occurredAt);
    }

    /** MONEY_DEBITED from account A -> debit A, credit CASH_ACCOUNT. */
    public JournalEntry postDebit(String transactionId, String accountId, double amount, String description) {
        return postDebit(transactionId, accountId, amount, description, clock.instant());
    }

    /** @see #postDebit(String, String, double, String) */
    public JournalEntry postDebit(String transactionId, String accountId, double amount, String description,
                                  Instant occurredAt) {
        return post(transactionId, accountId, CASH_ACCOUNT, amount, description, occurredAt);
    }

    /** TRANSFER A -> B produces two entries through the clearing account. */
    public void postTransfer(String transactionId, String fromAccountId, String toAccountId, double amount) {
        postTransfer(transactionId, fromAccountId, toAccountId, amount, clock.instant());
    }

    /**
     * Both legs are stamped with the same instant: they are one transaction, and
     * a backfilled transfer must not have its two halves straddling a boundary.
     *
     * @see #postTransfer(String, String, String, double)
     */
    public void postTransfer(String transactionId, String fromAccountId, String toAccountId, double amount,
                             Instant occurredAt) {
        postTransferDebit(transactionId, fromAccountId, amount, occurredAt);
        postTransferCredit(transactionId, toAccountId, amount, occurredAt);
    }

    public JournalEntry postTransferDebit(String transactionId, String accountId, double amount) {
        return postTransferDebit(transactionId, accountId, amount, clock.instant());
    }

    /** @see #postTransferDebit(String, String, double) */
    public JournalEntry postTransferDebit(String transactionId, String accountId, double amount, Instant occurredAt) {
        return post(transactionId, accountId, TRANSFER_CLEARING, amount, "TRANSFER debit", occurredAt);
    }

    public JournalEntry postTransferCredit(String transactionId, String accountId, double amount) {
        return postTransferCredit(transactionId, accountId, amount, clock.instant());
    }

    /** @see #postTransferCredit(String, String, double) */
    public JournalEntry postTransferCredit(String transactionId, String accountId, double amount, Instant occurredAt) {
        return post(transactionId, TRANSFER_CLEARING, accountId, amount, "TRANSFER credit", occurredAt);
    }

    /** Compensation: reverse a transfer debit on accountId (credit it back). */
    public JournalEntry reverseTransferDebit(String transactionId, String accountId, double amount) {
        return reverseTransferDebit(transactionId, accountId, amount, clock.instant());
    }

    /** @see #reverseTransferDebit(String, String, double) */
    public JournalEntry reverseTransferDebit(String transactionId, String accountId, double amount,
                                             Instant occurredAt) {
        return post(transactionId, TRANSFER_CLEARING, accountId, amount,
                "COMPENSATION (reverse debit)", occurredAt);
    }

    /** Compensation: reverse a transfer credit on accountId (debit it back). */
    public JournalEntry reverseTransferCredit(String transactionId, String accountId, double amount) {
        return reverseTransferCredit(transactionId, accountId, amount, clock.instant());
    }

    /** @see #reverseTransferCredit(String, String, double) */
    public JournalEntry reverseTransferCredit(String transactionId, String accountId, double amount,
                                              Instant occurredAt) {
        return post(transactionId, accountId, TRANSFER_CLEARING, amount,
                "COMPENSATION (reverse credit)", occurredAt);
    }

    private JournalEntry post(String transactionId, String debitAccountId, String creditAccountId,
                              double amount, String description, Instant occurredAt) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Journal amount must be positive: " + amount);
        }
        JournalEntry entry = new JournalEntry(
                UUID.randomUUID().toString(),
                transactionId,
                debitAccountId,
                creditAccountId,
                amount,
                "USD",
                description,
                occurredAt,
                "SYSTEM");
        if (!entry.isValid()) {
            throw new IllegalArgumentException("Invalid journal entry: debit=" + debitAccountId + " credit=" + creditAccountId);
        }
        repository.save(entry);
        return entry;
    }

    /** Verifies a transaction's total debits equal its total credits. */
    public void verifyTransactionBalanced(String transactionId) {
        List<JournalEntry> entries = repository.findByTransactionId(transactionId);
        double debits = 0;
        double credits = 0;
        for (JournalEntry e : entries) {
            debits += e.getAmount();
            credits += e.getAmount();
        }
        if (Math.abs(debits - credits) > 1e-9) {
            throw new IllegalStateException("Unbalanced transaction " + transactionId
                    + ": debits=" + debits + " credits=" + credits);
        }
    }

    public TrialBalance trialBalance() {
        return total(repository.findAll());
    }

    /**
     * The trial balance as it stood at {@code asOf}: the same sum over the
     * postings up to that instant.
     *
     * <p>Delegates to {@link #total(List)} rather than repeating the arithmetic,
     * so a snapshot's totals are the live totals' own code path. What changes with
     * the time bound is only which rows are in it, which is the whole of what
     * "the state at that instant" means for a pair of running totals.
     */
    public TrialBalance trialBalanceAt(Instant asOf) {
        return total(repository.findAllBefore(asOf));
    }

    /** The one place a trial balance is summed, live or as of an instant. */
    private TrialBalance total(List<JournalEntry> entries) {
        double debits = 0;
        double credits = 0;
        boolean allValid = true;
        for (JournalEntry e : entries) {
            debits += e.getAmount();
            credits += e.getAmount();
            if (!e.isValid()) {
                allValid = false;
            }
        }
        boolean balanced = allValid && Math.abs(debits - credits) < 1e-9;
        return new TrialBalance(debits, credits, balanced);
    }

    public AccountStatement statement(String accountId) {
        List<JournalEntry> entries = repository.findByAccount(accountId);
        double running = 0;
        List<StatementLine> lines = new ArrayList<>();
        for (JournalEntry e : entries) {
            if (accountId.equals(e.getCreditAccountId())) {
                running += e.getAmount();
            } else if (accountId.equals(e.getDebitAccountId())) {
                running -= e.getAmount();
            }
            lines.add(new StatementLine(e, running));
        }
        return new AccountStatement(accountId, lines, running);
    }

    /** Test helper: inject a deliberately invalid (unbalanced) entry. */
    public void injectUnbalanced() {
        injectUnbalanced(clock.instant());
    }

    /** @see #injectUnbalanced() */
    public void injectUnbalanced(Instant occurredAt) {
        JournalEntry entry = new JournalEntry(
                UUID.randomUUID().toString(),
                "unbalanced-test-" + UUID.randomUUID().toString().substring(0, 8),
                "CORRUPT_ACCOUNT",
                "CORRUPT_ACCOUNT",
                1.0,
                "USD",
                "UNBALANCED TEST INJECTION",
                occurredAt,
                "TEST");
        repository.save(entry);
    }

    public List<JournalEntry> entries() {
        return repository.findAll();
    }

    /**
     * The postings up to {@code asOf}, newest first — the journal explorer's feed
     * under the timeline scrubber.
     *
     * <p>Ordered the same way as {@link #entries()}, because the controller pages
     * this list by taking a {@code subList} off the front: a snapshot that came
     * back oldest-first would serve the operator the ledger's opening week instead
     * of the days before the instant they dragged to.
     */
    public List<JournalEntry> entriesBefore(Instant asOf) {
        return repository.findAllBefore(asOf);
    }

    public List<JournalEntry> entriesForTransaction(String transactionId) {
        return repository.findByTransactionId(transactionId);
    }
}
