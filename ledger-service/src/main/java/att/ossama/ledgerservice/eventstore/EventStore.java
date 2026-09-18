package att.ossama.ledgerservice.eventstore;

import att.ossama.ledgerservice.domain.Event;

import java.time.Instant;
import java.util.List;

/**
 * Append-only event store. The write side of the CQRS split.
 *
 * The in-memory implementation below is deliberately interchangeable with a
 * Kafka-backed implementation: append() would publish each event to a
 * partitioned "ledger-events" topic and allEvents()/eventsForAccount() would
 * consume it to rebuild state.
 */
public interface EventStore {

    /**
     * Atomically append a batch of events (e.g. a transfer's debit + credit),
     * stamped with the current time.
     *
     * <p>Every write path stamps its events explicitly and calls {@link
     * #append(List, Instant)}; this convenience form is here for callers that
     * genuinely mean "now" — a REPL, an administrative fix-up. It delegates the
     * other way round on purpose, so an implementation only has to get the
     * timestamped append right.
     *
     * @return the appended events, in order, with their log offsets assigned
     */
    List<Event> append(List<Event> events);

    /**
     * Atomically append a batch of events that happened at {@code occurredAt}.
     *
     * <p>Every event in the batch is restamped with {@code occurredAt}, so a
     * backfill can write history in the order it happened while the events carry
     * the date they are reconstructing. The log's own ordering — the surrogate id
     * — still reflects the order of insertion, which is what the projection
     * replays; only the timestamp on the events moves.
     *
     * @return the appended events, in order, with their log offsets assigned
     */
    List<Event> append(List<Event> events, Instant occurredAt);

    /** All events across all accounts, in append order. */
    List<Event> allEvents();

    /** The ordered event stream for a single account aggregate. */
    List<Event> eventsForAccount(String accountId);

    /** Total number of events in the log. */
    long count();

    /** Next monotonically increasing offset for the append-only log. */
    long nextOffset();
}
