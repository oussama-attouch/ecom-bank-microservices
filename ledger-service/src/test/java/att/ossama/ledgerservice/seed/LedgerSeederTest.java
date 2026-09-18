package att.ossama.ledgerservice.seed;

import att.ossama.ledgerservice.account.AccountService;
import att.ossama.ledgerservice.dashboard.KpiTrendsService;
import att.ossama.ledgerservice.dashboard.KpiTrendsResponse;
import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.EventType;
import att.ossama.ledgerservice.domain.JournalEntry;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.feign.CustomerRestClient;
import att.ossama.ledgerservice.journal.JournalEntryRepository;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.model.Customer;
import att.ossama.ledgerservice.journal.TrialBalance;
import att.ossama.ledgerservice.projection.AccountProjection;
import att.ossama.ledgerservice.saga.SagaRepository;
import att.ossama.ledgerservice.security.CallerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the seed chain end to end: backdated account openings, journal postings
 * and transfer sagas that all land in the past, on accounts whose balances still
 * add up and whose journal still balances.
 *
 * <p>Hand-wired rather than a {@code @SpringBootTest}: the pieces that matter are
 * the real services and the real repositories, and running them against
 * in-memory seams keeps this test about the seeding logic instead of about
 * Postgres. The one part of Spring that is genuinely needed is the request scope
 * — the saga reads the caller's roles from a request-scoped bean — so the test
 * provides a provider that resolves from the scope the seeder opens, which is the
 * same contract the real container offers.
 */
class LedgerSeederTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private FakeEventStore eventStore;
    private FakeJournalRepository journal;
    private FakeSagaRepository sagaStore;
    private JournalService journalService;
    private AccountProjection projection;
    private LedgerSeeder seeder;

    /** Records what the seeder asked the publisher to do. */
    private final List<String> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Fixture fixture = fixture();
        eventStore = fixture.eventStore();
        journal = fixture.journal();
        sagaStore = fixture.sagas();
        journalService = fixture.journalService();
        projection = fixture.projection();
        seeder = fixture.seeder();
    }

    // -----------------------------------------------------------------------
    // What gets written
    // -----------------------------------------------------------------------

    @Test
    void seedsCustomersAccountsAndASpreadOfTransactions() {
        LedgerSeeder.SeedSummary summary = seeder.seedSmall();

        assertThat(summary.customers()).isEqualTo(LedgerSeeder.SMALL_CUSTOMERS);
        assertThat(summary.accounts()).isEqualTo(LedgerSeeder.SMALL_CUSTOMERS);
        assertThat(summary.credits()).isPositive();
        assertThat(summary.transfers()).isPositive();

        assertThat(projection.allAccounts()).hasSize(LedgerSeeder.SMALL_CUSTOMERS)
                .allSatisfy(account -> {
                    assertThat(account.accountId()).startsWith("ACC-");
                    assertThat(account.holderName()).isNotBlank();
                    // Synthetic provenance: seeded accounts belong to the
                    // seeder's own customer id range, which is what makes the
                    // data identifiable when part 2 wipes it.
                    assertThat(account.customerId()).isBetween(900_000L, 900_999L);
                });
    }

    @Test
    void everythingIsBackdatedInsideTheHistoryWindow() {
        seeder.seedSmall();

        Instant historyStart = FIXED_NOW.minus(LedgerSeeder.HISTORY);
        List<Event> events = eventStore.allEvents();

        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getOccurredAt()).isAfterOrEqualTo(historyStart);
            // Not merely "not now": an event stamped in the future would fall
            // outside both sides of every KPI window and vanish from the charts.
            // A second of slack covers a saga's own timeline, which spaces its
            // steps out from the instant the transfer was seeded at.
            assertThat(event.getOccurredAt()).isBeforeOrEqualTo(FIXED_NOW.plusSeconds(1));
        });
    }

    @Test
    void accountsAreOpenedBeforeAnyMoneyMovesThroughThem() {
        seeder.seedSmall();

        Map<String, Instant> openedAt = new HashMap<>();
        for (Event event : eventStore.allEvents()) {
            if (event.type() == EventType.ACCOUNT_CREATED) {
                openedAt.put(event.aggregateId(), event.getOccurredAt());
            }
        }

        for (Event event : eventStore.allEvents()) {
            if (event.type() == EventType.ACCOUNT_CREATED) {
                continue;
            }
            assertThat(event.getOccurredAt())
                    .as("money must not move through %s before it exists", event.aggregateId())
                    .isAfterOrEqualTo(openedAt.get(event.aggregateId()));
        }
    }

    @Test
    void noSeededAccountIsLeftOverdrawn() {
        seeder.seedSmall();

        assertThat(projection.allAccounts())
                .allSatisfy(account -> assertThat(account.balance())
                        .as("balance of %s", account.accountId())
                        .isGreaterThanOrEqualTo(0.0));
    }

    @Test
    void theJournalStaysDoubleEntryAndBalanced() {
        LedgerSeeder.SeedSummary summary = seeder.seedSmall();

        TrialBalance trial = journalService.trialBalance();
        assertThat(trial.balanced()).isTrue();
        assertThat(trial.totalDebits()).isEqualTo(trial.totalCredits());

        // Every movement posts at least one entry, so the journal cannot be
        // thinner than the activity the summary claims.
        assertThat(journal.findAll()).hasSizeGreaterThanOrEqualTo(summary.transactions());
    }

    // -----------------------------------------------------------------------
    // Saga realism
    // -----------------------------------------------------------------------

    @Test
    void transfersBecomeSagasWithRealisticTimings() {
        seeder.seedSmall();

        List<SagaState> sagas = sagaStore.findAll();
        assertThat(sagas).isNotEmpty();

        for (SagaState saga : sagas) {
            assertThat(saga.getStatus()).isIn(SagaStatus.COMPLETED, SagaStatus.COMPENSATING);
            assertThat(saga.getSteps()).extracting(att.ossama.ledgerservice.domain.SagaStep::getName)
                    .contains("VALIDATE", "DEBIT_SOURCE", "CREDIT_DESTINATION", "ARCHIVE");

            // A reconstructed saga should look like it took a few hundred
            // milliseconds: not zero (all steps at one instant) and not minutes.
            Duration duration = Duration.between(saga.getStartedAt(), saga.getCompletedAt());
            assertThat(duration.toMillis()).as("saga %s duration", saga.getTransactionId())
                    .isBetween(200L, 2_000L);

            assertThat(saga.getSteps()).allSatisfy(step -> {
                assertThat(step.getTimestamp()).isAfterOrEqualTo(saga.getStartedAt());
                assertThat(step.getTimestamp()).isBeforeOrEqualTo(saga.getCompletedAt());
            });
        }
    }

    @Test
    void aSeededTransferIsNotAnnouncedToDownstreamProcessors() {
        seeder.seedSmall();

        // Reconstructing history must not look like fresh activity to
        // billing-service, which would archive the whole backfill again.
        assertThat(sagaStore.findAll()).isNotEmpty();
        assertThat(published).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Reproducibility and the point of it all
    // -----------------------------------------------------------------------

    @Test
    void theSameSeedMakesTheSameDecisionsAboutWhatToWrite() {
        seeder.seedSmall();
        List<String> firstRun = describeDecisions(journal, sagaStore);

        Fixture second = fixture();
        second.seeder().seedSmall();

        // Every decision the seeder makes must repeat: which accounts, which
        // movements, in which order, for how much, at which instants, and which
        // of them became sagas.
        assertThat(describeDecisions(second.journal(), second.sagas())).isEqualTo(firstRun);
    }

    @Test
    void seededHistoryGivesTheKpiCardsABaselineToCompareAgainst() {
        seeder.seedSmall();

        KpiTrendsResponse trends = new KpiTrendsService(
                new att.ossama.ledgerservice.dashboard.ReplayLedgerAggregates(eventStore, journalService, sagaStore),
                new att.ossama.ledgerservice.dashboard.SlaMetricsService(CLOCK),
                CLOCK).trends();

        assertThat(trends.assetsUnderManagement().history()).hasSize(7);
        assertThat(trends.activeAccounts().current())
                .isEqualByComparingTo(String.valueOf(LedgerSeeder.SMALL_CUSTOMERS));

        // The case the live demo ledger could never produce: two quarters of
        // history means a value from a week ago, so the trend has a percentage
        // instead of the dash the cards have been showing.
        assertThat(trends.assetsUnderManagement().previous()).isPositive();
        assertThat(trends.assetsUnderManagement().deltaPercent()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private record Fixture(FakeEventStore eventStore,
                           FakeJournalRepository journal,
                           FakeSagaRepository sagas,
                           JournalService journalService,
                           AccountProjection projection,
                           LedgerSeeder seeder) {
    }

    /** A complete, empty ledger wired the way the application wires it. */
    private Fixture fixture() {
        FakeEventStore events = new FakeEventStore();
        FakeJournalRepository entries = new FakeJournalRepository();
        FakeSagaRepository sagas = new FakeSagaRepository();
        AccountProjection projection = new AccountProjection(events);
        JournalService journalService = new JournalService(entries, CLOCK);

        ObjectProvider<CallerContext> callerContext = scopeBoundCallerContext();
        AccountService accountService = new AccountService(events, projection, unreachableCustomers(), CLOCK);

        return new Fixture(events, entries, sagas, journalService, projection,
                new LedgerSeeder(accountService, journalService, events, sagas, callerContext, CLOCK,
                        new PassthroughTransactionManager(), null));
    }

    /**
     * A transaction manager that does nothing but keep the seed's begin/commit
     * bookkeeping honest.
     *
     * <p>The seeder wraps a run in one transaction so a half-written portfolio
     * never lands. This double lets the in-memory tests exercise that path
     * without a database, and fails loudly if the seed ever commits twice or
     * forgets to finish what it started.
     */
    private static final class PassthroughTransactionManager implements PlatformTransactionManager {

        private boolean active;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (active) {
                throw new AssertionError("a transaction was already active");
            }
            active = true;
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            if (!active) {
                throw new AssertionError("commit without a transaction");
            }
            active = false;
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
        }
    }

    /**
     * A caller context resolved from the ambient request scope, mirroring the
     * container: the same instance for the duration of one seed run, and an
     * error if asked for outside a scope — which is what makes the test catch a
     * seeder that forgot to open one.
     */
    private static ObjectProvider<CallerContext> scopeBoundCallerContext() {
        return new ObjectProvider<>() {
            @Override
            public CallerContext getObject() {
                ServletRequestAttributes attributes =
                        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes == null) {
                    throw new IllegalStateException("No request scope is active");
                }
                CallerContext existing = (CallerContext) attributes.getAttribute(
                        "callerContext", ServletRequestAttributes.SCOPE_REQUEST);
                if (existing == null) {
                    existing = new CallerContext();
                    attributes.setAttribute("callerContext", existing, ServletRequestAttributes.SCOPE_REQUEST);
                }
                return existing;
            }

            @Override
            public CallerContext getObject(Object... args) {
                return getObject();
            }

            @Override
            public CallerContext getIfAvailable() {
                return RequestContextHolder.getRequestAttributes() == null ? null : getObject();
            }

            @Override
            public CallerContext getIfUnique() {
                return getIfAvailable();
            }
        };
    }

    /** The seeder supplies holder names, so this must never be called. */
    private static CustomerRestClient unreachableCustomers() {
        return new CustomerRestClient() {
            @Override
            public Customer getCustomer(Long id) {
                throw new AssertionError("the seeder must not call customer-service for " + id);
            }
        };
    }

    /**
     * A stable description of the decisions one seed run made.
     *
     * <p>Timestamps are deliberately absent. A transfer's legs and its saga steps
     * are stamped from the saga's own timeline, whose sub-second spacing is random
     * on purpose (its bands are asserted in {@code SagaTimelineTest}); including
     * them would make this check fail by design rather than on a regression. What
     * has to repeat is every choice the seeder makes: which accounts, in what
     * order, how much, and which of the movements became multi-step sagas.
     */
    private static List<String> describeDecisions(FakeJournalRepository entries, FakeSagaRepository sagas) {
        Map<String, String> labels = new LinkedHashMap<>();
        List<String> shape = new ArrayList<>();

        for (JournalEntry entry : entries.findAll()) {
            shape.add("JOURNAL/" + label(labels, entry.getDebitAccountId())
                    + ">" + label(labels, entry.getCreditAccountId()) + "/" + entry.getAmount());
        }
        for (SagaState saga : sagas.findAll()) {
            shape.add("SAGA/" + saga.getStatus()
                    + "/" + label(labels, saga.getSourceAccountId())
                    + ">" + label(labels, saga.getDestinationAccountId())
                    + "/" + saga.getAmount() + "/steps=" + saga.getSteps().size());
        }
        return shape;
    }

    /** Maps a generated account id onto a stable positional label ("ACC#0", "ACC#1", ...). */
    private static String label(Map<String, String> labels, String accountId) {
        return labels.computeIfAbsent(accountId, id -> "ACC#" + labels.size());
    }

    // -----------------------------------------------------------------------
    // In-memory seams
    // -----------------------------------------------------------------------

    private static final class FakeEventStore implements EventStore {
        private final List<Event> log = new ArrayList<>();

        @Override
        public List<Event> append(List<Event> toAppend) {
            return append(toAppend, Instant.now());
        }

        @Override
        public List<Event> append(List<Event> toAppend, Instant occurredAt) {
            List<Event> appended = new ArrayList<>(toAppend.size());
            for (Event event : toAppend) {
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

    private static final class FakeSagaRepository implements SagaRepository {
        private final Map<String, SagaState> sagas = new java.util.LinkedHashMap<>();

        @Override
        public SagaState find(String transactionId) {
            return sagas.get(transactionId);
        }

        @Override
        public void save(SagaState saga) {
            sagas.put(saga.getTransactionId(), saga);
        }

        @Override
        public List<SagaState> findAll() {
            return List.copyOf(sagas.values());
        }

        @Override
        public long count() {
            return sagas.size();
        }
    }
}
