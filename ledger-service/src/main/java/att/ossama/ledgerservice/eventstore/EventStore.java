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

    /**
     * Every event that had happened at or before {@code cutoff}, in append order.
     *
     * <p>The time axis of the log, where {@link #allEvents()} is its position
     * axis. Inclusive at the boundary: a snapshot "as of" an instant has to
     * contain the events that happened <em>at</em> it, so this matches the
     * {@code occurred_at <= :asOf} convention {@code accountBalancesAsOf}
     * already uses rather than the {@code (from, to]} the range aggregates use.
     *
     * <p>Ordered by log position, not by timestamp, and deliberately: the seed
     * backdates history, so an event's {@code occurredAt} and its insertion order
     * are different things. The projection replays in log order, so a snapshot
     * that replayed in timestamp order could fold a debit before the credit that
     * funded it.
     *
     * <p>The default filters {@link #allEvents()}, which is correct but loads the
     * whole log to answer a question about part of it. Every implementation that
     * has somewhere cheaper to ask overrides it; the fallback is here so
     * implementers of this interface are not forced to care about a read they may
     * never serve.
     */
    default List<Event> allEventsBefore(Instant cutoff) {
        return allEvents().stream()
                .filter(event -> {
                    Instant at = event.getOccurredAt();
                    // An event with no timestamp cannot be placed on the time
                    // axis at all, so it is not "before" any cutoff.
                    return at != null && !at.isAfter(cutoff);
                })
                .toList();
    }

    /** The ordered event stream for a single account aggregate. */
    List<Event> eventsForAccount(String accountId);

    /** Total number of events in the log. */
    long count();

    /** Next monotonically increasing offset for the append-only log. */
    long nextOffset();
}
