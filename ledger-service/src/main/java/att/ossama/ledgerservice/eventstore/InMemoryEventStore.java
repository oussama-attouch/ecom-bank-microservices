package att.ossama.ledgerservice.eventstore;

import att.ossama.ledgerservice.domain.Event;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, append-only event store (fallback).
 *
 * Events are never updated or removed — only appended. The log is the single
 * source of truth; account state is always derived by replaying it.
 *
 * Only active with the "inmem" profile; otherwise {@link
 * att.ossama.ledgerservice.persistence.JpaEventStore} provides the durable store.
 */
@Component
@Profile("inmem")
public class InMemoryEventStore implements EventStore {

    private final List<Event> log = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final Clock clock;

    public InMemoryEventStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<Event> append(List<Event> events) {
        return append(events, clock.instant());
    }

    @Override
    public synchronized List<Event> append(List<Event> events, Instant occurredAt) {
        List<Event> appended = new ArrayList<>(events.size());
        for (Event event : events) {
            event.setOccurredAt(occurredAt);
            event.setOffset(sequence.incrementAndGet());
            log.add(event);
            appended.add(event);
        }
        return List.copyOf(appended);
    }

    @Override
    public List<Event> allEvents() {
        return List.copyOf(log);
    }

    @Override
    public List<Event> eventsForAccount(String accountId) {
        return log.stream()
                .filter(e -> accountId.equals(e.aggregateId()))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The log is already in memory, so this is the interface's default made
     * direct: filtering the live list rather than copying it first.
     */
    @Override
    public List<Event> allEventsBefore(Instant cutoff) {
        return log.stream()
                .filter(e -> e.getOccurredAt() != null && !e.getOccurredAt().isAfter(cutoff))
                .toList();
    }

    @Override
    public long count() {
        return log.size();
    }

    @Override
    public long nextOffset() {
        return sequence.incrementAndGet();
    }
}
