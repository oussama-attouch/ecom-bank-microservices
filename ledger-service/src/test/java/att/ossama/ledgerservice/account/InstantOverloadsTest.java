package att.ossama.ledgerservice.account;

import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.JournalEntry;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.feign.CustomerRestClient;
import att.ossama.ledgerservice.journal.JournalEntryRepository;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.model.Customer;
import att.ossama.ledgerservice.projection.AccountProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The explicit-instant overloads are what the whole backfill rests on: they are
 * the only way to write ledger history that is not stamped "now".
 *
 * <p>The requirement on each is the same and is asserted here per write path:
 * the no-argument form still works and still uses the clock, and the overload
 * uses the instant it was given — all the way down to the field the read side
 * will later replay.
 */
class InstantOverloadsTest {

    private static final Instant PAST = Instant.parse("2025-11-20T08:30:00Z");
    private static final Instant NOW = Instant.parse("2026-05-06T10:00:00Z");

    private FakeEventStore eventStore;
    private FakeJournalRepository journalRepository;
    private JournalService journalService;

    @BeforeEach
    void setUp() {
        eventStore = new FakeEventStore();
        journalRepository = new FakeJournalRepository();
        journalService = new JournalService(journalRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // -----------------------------------------------------------------------
    // Event store
    // -----------------------------------------------------------------------

    @Test
    void appendStampsEveryEventInTheBatchWithTheInstantItHappened() {
        Event first = creditEvent("ACC-1", 100);
        Event second = creditEvent("ACC-2", 200);

        List<Event> appended = eventStore.append(List.of(first, second), PAST);

        assertThat(appended).allSatisfy(event -> assertThat(event.getOccurredAt()).isEqualTo(PAST));
        assertThat(eventStore.allEvents()).allSatisfy(event -> assertThat(event.getOccurredAt()).isEqualTo(PAST));
    }

    @Test
    void theNoArgumentAppendStillStampsNow() {
        eventStore.append(List.of(creditEvent("ACC-1", 100)));

        assertThat(eventStore.allEvents()).singleElement()
                .satisfies(event -> assertThat(event.getOccurredAt()).isEqualTo(NOW));
    }

    @Test
    void countReportsTheSizeOfTheLog() {
        assertThat(eventStore.count()).isZero();

        eventStore.append(List.of(creditEvent("ACC-1", 100)), PAST);
        eventStore.append(List.of(creditEvent("ACC-2", 100)), PAST);

        assertThat(eventStore.count()).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Journal
    // -----------------------------------------------------------------------

    @Test
    void postCreditAndDebitHonourTheirInstant() {
        journalService.postCredit("tx-1", "ACC-1", 250, "backdated credit", PAST);
        journalService.postDebit("tx-2", "ACC-1", 100, "backdated debit", PAST);

        assertThat(journalRepository.findAll())
                .allSatisfy(entry -> assertThat(entry.getCreatedAt()).isEqualTo(PAST));
    }

    @Test
    void bothLegsOfATransferShareTheInstantTheyWereGiven() {
        journalService.postTransfer("tx-3", "ACC-1", "ACC-2", 500, PAST);

        List<JournalEntry> entries = journalRepository.findByTransactionId("tx-3");
        assertThat(entries).hasSize(2);
        // One transaction, one moment: a backfilled transfer must not have its
        // two halves straddling a boundary in the charts.
        assertThat(entries).allSatisfy(entry -> assertThat(entry.getCreatedAt()).isEqualTo(PAST));
        assertThat(entries).extracting(JournalEntry::getDescription)
                .containsExactlyInAnyOrder("TRANSFER debit", "TRANSFER credit");
    }

    @Test
    void compensationEntriesHonourTheirInstant() {
        journalService.reverseTransferDebit("tx-4", "ACC-1", 500, PAST);
        journalService.reverseTransferCredit("tx-4", "ACC-2", 500, PAST);

        assertThat(journalRepository.findByTransactionId("tx-4")).hasSize(2)
                .allSatisfy(entry -> assertThat(entry.getCreatedAt()).isEqualTo(PAST));
    }

    @Test
    void theNoArgumentPostingsStillStampNow() {
        journalService.postCredit("tx-5", "ACC-1", 250, "live credit");

        assertThat(journalRepository.findByTransactionId("tx-5")).singleElement()
                .satisfies(entry -> assertThat(entry.getCreatedAt()).isEqualTo(NOW));
    }

    // -----------------------------------------------------------------------
    // Account opening
    // -----------------------------------------------------------------------

    @Test
    void anAccountCanBeOpenedAsOfAPastInstantWithoutCallingCustomerService() {
        AccountService accountService = new AccountService(
                eventStore, new AccountProjection(eventStore), explodingCustomers(), Clock.fixed(NOW, ZoneOffset.UTC));

        AccountState account = accountService.openAccount(900_001L, "Backdated Holder", PAST);

        assertThat(account.accountId()).startsWith("ACC-");
        assertThat(account.balance()).isZero();
        assertThat(eventStore.allEvents()).singleElement().satisfies(event -> {
            assertThat(event.getOccurredAt()).isEqualTo(PAST);
            assertThat(event.aggregateId()).isEqualTo(account.accountId());
        });
    }

    @Test
    void theRequestPathStillOpensAccountsNowAndResolvesTheHolderName() {
        AccountService accountService = new AccountService(
                eventStore, new AccountProjection(eventStore), id -> customer(id, "Live Holder"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        AccountState account = accountService.openAccount(42L);

        assertThat(account.holderName()).isEqualTo("Live Holder");
        assertThat(eventStore.allEvents()).singleElement()
                .satisfies(event -> assertThat(event.getOccurredAt()).isEqualTo(NOW));
    }

    @Test
    void aFailedCustomerLookupStillOpensTheAccountRatherThanFailing() {
        AccountService accountService = new AccountService(
                eventStore, new AccountProjection(eventStore), explodingCustomers(), Clock.fixed(NOW, ZoneOffset.UTC));

        AccountState account = accountService.openAccount(7L);

        assertThat(account.holderName()).isEqualTo("Customer 7");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static Event creditEvent(String accountId, double amount) {
        return new att.ossama.ledgerservice.domain.MoneyCreditedEvent(
                "evt-" + accountId + amount, NOW, "tx", accountId, amount, "credit");
    }

    private static Customer customer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        customer.setEmail("holder@example.com");
        return customer;
    }

    /** Never resolves: the backfill supplies holder names itself. */
    private static CustomerRestClient explodingCustomers() {
        return new CustomerRestClient() {
            @Override
            public Customer getCustomer(Long id) {
                throw new IllegalStateException("customer-service is not available");
            }
        };
    }

    private static final class FakeEventStore implements EventStore {
        private final List<Event> log = new ArrayList<>();
        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        @Override
        public List<Event> append(List<Event> events) {
            return append(events, clock.instant());
        }

        @Override
        public List<Event> append(List<Event> events, Instant occurredAt) {
            List<Event> appended = new ArrayList<>(events.size());
            for (Event event : events) {
                event.setOccurredAt(occurredAt);
                event.setOffset(log.size() + 1L);
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
            return log.stream().filter(event -> accountId.equals(event.aggregateId())).toList();
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

    private static final class FakeJournalRepository implements JournalEntryRepository {
        private final List<JournalEntry> entries = new ArrayList<>();

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
            return entries.stream().filter(entry -> transactionId.equals(entry.getTransactionId())).toList();
        }

        @Override
        public List<JournalEntry> findByAccount(String accountId) {
            return entries.stream()
                    .filter(entry -> accountId.equals(entry.getDebitAccountId())
                            || accountId.equals(entry.getCreditAccountId()))
                    .toList();
        }
    }
}
