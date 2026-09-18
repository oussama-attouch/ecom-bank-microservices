package att.ossama.ledgerservice.persistence;

import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.eventstore.EventStore;
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
    private final Clock clock;

    public JpaEventStore(EventJpaRepository repository, EventSerializer serializer, Clock clock) {
        this.repository = repository;
        this.serializer = serializer;
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
        }
        return List.copyOf(appended);
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
