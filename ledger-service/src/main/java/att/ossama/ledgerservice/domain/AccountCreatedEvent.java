package att.ossama.ledgerservice.domain;

import java.time.Instant;

public class AccountCreatedEvent extends Event {

    private final String accountId;
    private final Long customerId;
    private final String holderName;

    public AccountCreatedEvent(String eventId, Instant occurredAt,
                               String accountId, Long customerId, String holderName) {
        super(eventId, occurredAt);
        this.accountId = accountId;
        this.customerId = customerId;
        this.holderName = holderName;
    }

    @Override
    public EventType type() {
        return EventType.ACCOUNT_CREATED;
    }

    @Override
    public String aggregateId() {
        return accountId;
    }

    public String getAccountId() {
        return accountId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getHolderName() {
        return holderName;
    }
}
