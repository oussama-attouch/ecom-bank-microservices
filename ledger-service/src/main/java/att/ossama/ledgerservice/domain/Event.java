package att.ossama.ledgerservice.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * Base type for all immutable, append-only events in the ledger.
 *
 * In the future each append maps 1:1 to a record on a Kafka "ledger-events"
 * topic (partitioned by aggregateId to preserve per-account ordering).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AccountCreatedEvent.class, name = "ACCOUNT_CREATED"),
        @JsonSubTypes.Type(value = MoneyCreditedEvent.class, name = "MONEY_CREDITED"),
        @JsonSubTypes.Type(value = MoneyDebitedEvent.class, name = "MONEY_DEBITED")
})
public abstract class Event {

    private final String eventId;
    private Instant occurredAt;
    private long offset;

    protected Event(String eventId, Instant occurredAt) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
    }

    public abstract EventType type();

    public abstract String aggregateId();

    public String getEventId() {
        return eventId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * Restamps when this event happened. Only the {@link
     * att.ossama.ledgerservice.eventstore.EventStore} calls this, from its
     * {@code append(events, occurredAt)} overload, so a backfill can place a
     * batch at the instant it is reconstructing rather than at wall-clock time.
     *
     * <p>Like {@link #setOffset}, the event is not immutable in the store's
     * hands: the log owns the time axis and the position, and both are settled at
     * append. Anything already persisted is unaffected — the JPA store writes the
     * timestamp into its own column as well as into the payload, so the two can
     * never disagree.
     */
    public void setOccurredAt(Instant occurredAt) {
        if (occurredAt != null) {
            this.occurredAt = occurredAt;
        }
    }

    /** Position in the append-only log; assigned by the {@link att.ossama.ledgerservice.eventstore.EventStore}. */
    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }
}
