package att.ossama.ledgerservice.domain;

import java.time.Instant;

public class MoneyCreditedEvent extends Event {

    private final String transactionId;
    private final String accountId;
    private final double amount;
    private final String description;

    public MoneyCreditedEvent(String eventId, Instant occurredAt,
                              String transactionId, String accountId,
                              double amount, String description) {
        super(eventId, occurredAt);
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
        this.description = description;
    }

    @Override
    public EventType type() {
        return EventType.MONEY_CREDITED;
    }

    @Override
    public String aggregateId() {
        return accountId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getAccountId() {
        return accountId;
    }

    public double getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }
}
