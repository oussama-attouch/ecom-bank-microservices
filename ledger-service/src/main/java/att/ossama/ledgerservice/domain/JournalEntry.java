package att.ossama.ledgerservice.domain;

import java.time.Instant;

/**
 * A single double-entry journal entry. Debits {@code amount} from
 * {@code debitAccountId} and credits {@code amount} to {@code creditAccountId},
 * so a single entry is inherently balanced (debit == credit == amount).
 */
public class JournalEntry {

    private final String id;
    private final String transactionId;
    private final String debitAccountId;
    private final String creditAccountId;
    private final double amount;
    private final String currency;
    private final String description;
    private final Instant createdAt;
    private final String postedBy;

    public JournalEntry(String id, String transactionId, String debitAccountId, String creditAccountId,
                        double amount, String currency, String description, Instant createdAt, String postedBy) {
        this.id = id;
        this.transactionId = transactionId;
        this.debitAccountId = debitAccountId;
        this.creditAccountId = creditAccountId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.createdAt = createdAt;
        this.postedBy = postedBy;
    }

    public boolean isValid() {
        return debitAccountId != null && creditAccountId != null
                && !debitAccountId.equals(creditAccountId)
                && amount > 0;
    }

    public String getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getDebitAccountId() {
        return debitAccountId;
    }

    public String getCreditAccountId() {
        return creditAccountId;
    }

    public double getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPostedBy() {
        return postedBy;
    }
}
