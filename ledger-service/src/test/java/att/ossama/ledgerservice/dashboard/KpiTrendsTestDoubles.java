package att.ossama.ledgerservice.dashboard;

import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.JournalEntry;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.journal.JournalEntryRepository;
import att.ossama.ledgerservice.saga.SagaRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written in-memory doubles for {@link KpiTrendsServiceTest}.
 *
 * <p>Deliberately not Mockito: the behaviour under test is "what does the
 * service read from its stores", and a real list-backed store answers that more
 * legibly than three stubbed mocks. It also keeps the test independent of the
 * bytecode manipulation Mockito needs, which cannot always attach its agent.
 */
final class KpiTrendsTestDoubles {

    private KpiTrendsTestDoubles() {
    }

    /** Append-only log; appends are ordered, exactly like the real store. */
    static final class FakeEventStore implements EventStore {
        private final List<Event> log = new ArrayList<>();

        void append(Event event) {
            log.add(event);
        }

        @Override
        public List<Event> append(List<Event> events) {
            log.addAll(events);
            return List.copyOf(events);
        }

        @Override
        public List<Event> append(List<Event> events, java.time.Instant occurredAt) {
            events.forEach(event -> event.setOccurredAt(occurredAt));
            return append(events);
        }

        @Override
        public List<Event> allEvents() {
            return List.copyOf(log);
        }

        @Override
        public List<Event> eventsForAccount(String accountId) {
            return log.stream().filter(e -> accountId.equals(e.aggregateId())).toList();
        }

        @Override
        public long count() {
            return log.size();
        }

        @Override
        public long nextOffset() {
            return log.size() + 1L;
        }
    }

    /**
     * Journal seam backed by a list. {@link KpiTrendsService} reads the journal
     * through {@code JournalService}, so this sits underneath a real
     * {@code JournalService} rather than being mocked.
     */
    static final class FakeJournalRepository implements JournalEntryRepository {
        private final List<JournalEntry> entries = new ArrayList<>();

        void add(JournalEntry entry) {
            entries.add(entry);
        }

        @Override
        public void save(JournalEntry entry) {
            entries.add(entry);
        }

        @Override
        public void saveAll(List<JournalEntry> toSave) {
            entries.addAll(toSave);
        }

        @Override
        public List<JournalEntry> findAll() {
            return List.copyOf(entries);
        }

        @Override
        public List<JournalEntry> findByTransactionId(String transactionId) {
            return entries.stream().filter(e -> transactionId.equals(e.getTransactionId())).toList();
        }

        @Override
        public List<JournalEntry> findByAccount(String accountId) {
            return entries.stream()
                    .filter(e -> accountId.equals(e.getDebitAccountId()) || accountId.equals(e.getCreditAccountId()))
                    .toList();
        }
    }

    /** Saga seam backed by a list. */
    static final class FakeSagaRepository implements SagaRepository {
        private final List<SagaState> sagas = new ArrayList<>();

        void add(SagaState saga) {
            sagas.add(saga);
        }

        @Override
        public SagaState find(String transactionId) {
            return sagas.stream().filter(s -> transactionId.equals(s.getTransactionId())).findFirst().orElse(null);
        }

        @Override
        public void save(SagaState saga) {
            sagas.add(saga);
        }

        @Override
        public List<SagaState> findAll() {
            return List.copyOf(sagas);
        }

        @Override
        public long count() {
            return sagas.size();
        }
    }
}
