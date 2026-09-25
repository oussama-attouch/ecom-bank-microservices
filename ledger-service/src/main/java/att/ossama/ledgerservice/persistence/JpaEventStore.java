package att.ossama.ledgerservice.persistence;

import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.MoneyCreditedEvent;
import att.ossama.ledgerservice.domain.MoneyDebitedEvent;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.observability.LiveEvent;
import att.ossama.ledgerservice.observability.LiveEventBroadcaster;
import att.ossama.ledgerservice.persistence.entity.EventEntity;
import att.ossama.ledgerservice.persistence.repository.EventJpaRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable, append-only event store backed by Postgres.
 *
 * Active by default (any profile other than "inmem"); the in-memory
 * implementation is kept as a fallback for tests / quick local runs.
 *
 * Events are never updated or deleted — only inserted. The global log position
 * of an event is its surrogate {@code id}; {@code sequenceNumber} is the
 * position within its own aggregate.
 */
@Component
@Profile("!inmem")
public class JpaEventStore implements EventStore {

    private final EventJpaRepository repository;
    private final EventSerializer serializer;
    private final LiveEventBroadcaster broadcaster;
    private final Clock clock;

    public JpaEventStore(EventJpaRepository repository, EventSerializer serializer,
                         LiveEventBroadcaster broadcaster, Clock clock) {
        this.repository = repository;
        this.serializer = serializer;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Kept intentionally thin: the timestamped overload below is the one every
     * write path uses, and it does the work. Delegating the other way would make
     * production call a method that only exists for "stamp it now" convenience,
     * which is the sort of thing a test double then has to know about.
     */
    @Override
    @Transactional
    public List<Event> append(List<Event> events) {
        return append(events, clock.instant());
    }

    @Override
    @Transactional
    public List<Event> append(List<Event> events, Instant occurredAt) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        // Restamp before serializing: the timestamp appears both in its own
        // column and inside the payload, and the two are written from the same
        // value so a backdated batch cannot come back reading as "now".
        events.forEach(event -> event.setOccurredAt(occurredAt));

        // Track the next sequence per aggregate so a batch containing several
        // events for the same aggregate cannot collide on the
        // (aggregate_id, sequence_number) unique constraint.
        Map<String, Long> nextSequence = new HashMap<>();
        List<EventEntity> entities = new ArrayList<>(events.size());

        for (Event event : events) {
            String aggregateId = event.aggregateId();
            Long sequence = nextSequence.computeIfAbsent(
                    aggregateId,
                    id -> repository.findMaxSequenceNumberByAggregateId(id) + 1L);

            entities.add(new EventEntity(
                    null,
                    aggregateId,
                    sequence,
                    event.getClass().getSimpleName(),
                    serializer.serialize(event),
                    event.getOccurredAt()
            ));
            nextSequence.put(aggregateId, sequence + 1L);
        }

        List<EventEntity> saved = repository.saveAll(entities);

        // Hand the log position back to the caller, mirroring the in-memory store.
        List<Event> appended = new ArrayList<>(saved.size());
        for (int i = 0; i < saved.size(); i++) {
            Event event = events.get(i);
            Long id = saved.get(i).getId();
            if (id != null) {
                event.setOffset(id);
            }
            appended.add(event);
            broadcastAppend(event, saved.get(i).getSequenceNumber());
        }
        return List.copyOf(appended);
    }

    /**
     * Mirrors one appended event onto the live stream.
     *
     * <p>Called after the row is written and its log position assigned, so the
     * offset on the stream is the offset in the log and not a value that is about
     * to change. It is called from inside the append transaction, which means an
     * append that is later rolled back has already been streamed: the stream is a
     * live tail of what the write path did, not a transactional outbox, and
     * treating it as the latter would mean holding events until commit — a queue
     * and a failure mode the event store does not need in order to serve a
     * display. The log itself remains the authority on what was committed.
     */
    private void broadcastAppend(Event event, Long sequenceNumber) {
        broadcaster.publish(new LiveEvent(
                event.getOccurredAt(),
                LiveEvent.SOURCE_EVENT_STORE,
                LiveEvent.TYPE_EVENT_APPENDED,
                "Appended " + event.type() + " for " + event.aggregateId() + " at log offset " + event.getOffset(),
                transactionIdOf(event),
                LiveEvent.metadataOf(
                        "eventId", event.getEventId(),
                        "eventType", event.type().name(),
                        "eventClass", event.getClass().getSimpleName(),
                        "aggregateId", event.aggregateId(),
                        "offset", event.getOffset(),
                        "sequenceNumber", sequenceNumber)));
    }

    /**
     * The transaction a money event belongs to, when it has one.
     *
     * <p>Read by type rather than by adding a method to {@link Event}: an
     * account-creation event has no transaction to correlate with, and widening the
     * base type with an accessor that returns null for one of its three subtypes
     * would push that fact onto every reader of the domain model to serve one
     * observer.
     */
    private static String transactionIdOf(Event event) {
        if (event instanceof MoneyDebitedEvent debited) {
            return debited.getTransactionId();
        }
        if (event instanceof MoneyCreditedEvent credited) {
            return credited.getTransactionId();
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Event> allEvents() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(this::toEvent)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Event> eventsForAccount(String accountId) {
        return repository.findByAggregateIdOrderBySequenceNumber(accountId).stream()
                .map(this::toEvent)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overridden rather than left to the interface's filter-{@code allEvents()}
     * default: this is a real indexed range on {@code occurred_at} that never
     * reads past the cutoff, where the default would hydrate and decode the whole
     * log to answer a question about its prefix. On a cutoff in the distant past
     * the difference is the entire table.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Event> allEventsBefore(Instant cutoff) {
        return repository.findByOccurredAtLessThanEqualOrderByIdAsc(cutoff).stream()
                .map(this::toEvent)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    @Override
    @Transactional(readOnly = true)
    public long nextOffset() {
        return repository.findMaxSequenceNumber() + 1L;
    }

    /**
     * The payload carries Jackson's polymorphic type discriminator ("type"), so
     * it round-trips straight back to the concrete {@link Event} subtype.
     */
    private Event toEvent(EventEntity entity) {
        Event event = serializer.deserialize(entity.getPayload(), Event.class);
        if (entity.getId() != null) {
            event.setOffset(entity.getId());
        }
        return event;
    }
}
