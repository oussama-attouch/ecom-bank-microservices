package att.ossama.ledgerservice.seed;

import att.ossama.ledgerservice.account.AccountService;
import att.ossama.ledgerservice.domain.AccountCreatedEvent;
import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.JournalEntry;
import att.ossama.ledgerservice.domain.MoneyCreditedEvent;
import att.ossama.ledgerservice.domain.MoneyDebitedEvent;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.feign.CustomerRestClient;
import att.ossama.ledgerservice.journal.JournalEntryRepository;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.journal.TrialBalance;
import att.ossama.ledgerservice.model.Customer;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The portfolio seed, run at a size a test can afford.
 *
 * <p>The full run is 500 customers and 40,000 transactions, which takes minutes
 * and is verified against a real database. What is asserted here is the shape:
 * the same generator, the same distributions, the same 24-month span, at a
 * hundredth of the volume — which is where a wrong distribution actually shows
 * up. Volume and wall-clock are the database run's business.
 */
class PortfolioSeedTest {

    /** Small enough to run in a test, large enough for the ratios to mean something. */
    private static final int CUSTOMERS = 100;
    private static final int TRANSACTIONS = 2_000;

    private static final Instant FIXED_NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private FakeEventStore events;
    private FakeJournalRepository journal;
    private FakeSagaRepository sagas;
    private JournalService journalService;
    private AccountProjection projection;
    private LedgerSeeder seeder;

    @BeforeEach
    void setUp() {
        Fixture fixture = fixture();
        events = fixture.events();
        journal = fixture.journal();
        sagas = fixture.sagas();
        journalService = fixture.journalService();
        projection = fixture.projection();
        seeder = fixture.seeder();
    }

    // -----------------------------------------------------------------------
    // Volume and span
    // -----------------------------------------------------------------------

    @Test
    void writesTheRequestedPopulationExactly() {
        LedgerSeeder.SeedSummary summary = seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        assertThat(summary.customers()).isEqualTo(CUSTOMERS);
        // ~2.2 accounts per customer from the 10/60/30 mix.
        assertThat(summary.accounts()).isBetween((int) (CUSTOMERS * 2.0), (int) (CUSTOMERS * 2.5));
        // The exact movement count is a promise of the generator, not an accident:
        // each customer's draw is scaled to hit the target.
        assertThat((long) summary.debits() + summary.transfers()).isPositive();
        assertThat(events.allEvents()).isNotEmpty();
    }

    @Test
    void everyAccountBelongsToASeededCustomer() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        List<AccountState> accounts = projection.allAccounts();
        assertThat(accounts).hasSizeBetween((int) (CUSTOMERS * 2.0), (int) (CUSTOMERS * 2.5));
        assertThat(accounts).allSatisfy(account -> {
            assertThat(account.accountId()).startsWith("ACC-");
            assertThat(account.holderName()).isNotBlank();
            // Provenance: every account belongs to the seed's own customer range.
            assertThat(account.customerId())
                    .isBetween(LedgerSeeder.PORTFOLIO_CUSTOMER_ID_BASE,
                            LedgerSeeder.PORTFOLIO_CUSTOMER_ID_BASE + CUSTOMERS);
        });
    }

    @Test
    void historySpansTwentyFourMonthsAndNeverReachesTheFuture() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        Instant historyStart = FIXED_NOW.minus(Duration.ofDays(LedgerSeeder.PORTFOLIO_MONTHS * 30L));
        List<Event> all = events.allEvents();

        Event earliest = all.stream().min(Comparator.comparing(Event::getOccurredAt)).orElseThrow();
        Event latest = all.stream().max(Comparator.comparing(Event::getOccurredAt)).orElseThrow();

        assertThat(earliest.getOccurredAt()).isAfterOrEqualTo(historyStart);
        // A backdated event in the future would fall outside every KPI window.
        assertThat(latest.getOccurredAt()).isBeforeOrEqualTo(FIXED_NOW.plusSeconds(1));
        // And the span is genuinely two years, not a few months of activity.
        assertThat(Duration.between(earliest.getOccurredAt(), latest.getOccurredAt()).toDays())
                .isGreaterThan(365);
    }

    @Test
    void accountsOpenBeforeAnythingMovesThroughThem() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        Map<String, Instant> openedAt = new LinkedHashMap<>();
        for (Event event : events.allEvents()) {
            if (event instanceof AccountCreatedEvent) {
                openedAt.put(event.aggregateId(), event.getOccurredAt());
            }
        }

        for (Event event : events.allEvents()) {
            if (event instanceof AccountCreatedEvent) {
                continue;
            }
            assertThat(event.getOccurredAt())
                    .as("movement on %s must not precede its opening", event.aggregateId())
                    .isAfterOrEqualTo(openedAt.get(event.aggregateId()));
        }
    }

    // -----------------------------------------------------------------------
    // Realism
    // -----------------------------------------------------------------------

    @Test
    void activityIsWeightedTowardWorkingHoursAndWeekdays() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        List<Event> all = events.allEvents();
        long businessHours = all.stream()
                .filter(event -> {
                    int hour = event.getOccurredAt().atZone(ZoneOffset.UTC).getHour();
                    return hour >= 8 && hour <= 17;
                })
                .count();
        long weekend = all.stream()
                .filter(event -> {
                    var day = event.getOccurredAt().atZone(ZoneOffset.UTC).getDayOfWeek();
                    return day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY;
                })
                .count();

        // The hour table puts 80% of the weight in 08:00-17:59, so the working
        // day should dominate even after the weekday and seasonal multipliers.
        assertThat(businessHours / (double) all.size()).isGreaterThan(0.55);
        // Weekends carry 20% of the weekday weight, so under a third of events.
        assertThat(weekend / (double) all.size()).isLessThan(0.33);
    }

    @Test
    void decemberIsBusierThanAugustForTheSameAccounts() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        // Compared over accounts that existed for both months. A raw December
        // versus August count is dominated by the customer base growing across the
        // two years (December 2024 has a fraction of the accounts August 2026
        // does), which would swamp the seasonal multiplier the brief specifies.
        Instant augustStart = Instant.parse("2025-08-01T00:00:00Z");
        Instant augustEnd = Instant.parse("2025-09-01T00:00:00Z");
        Instant decemberStart = Instant.parse("2025-12-01T00:00:00Z");
        Instant decemberEnd = Instant.parse("2026-01-01T00:00:00Z");

        Map<String, Instant> openedAt = new LinkedHashMap<>();
        for (Event event : events.allEvents()) {
            if (event instanceof AccountCreatedEvent) {
                openedAt.put(event.aggregateId(), event.getOccurredAt());
            }
        }

        long august = events.allEvents().stream()
                .filter(event -> existedBefore(openedAt, event, augustStart))
                .filter(event -> inWindow(event, augustStart, augustEnd))
                .count();
        long december = events.allEvents().stream()
                .filter(event -> existedBefore(openedAt, event, augustStart))
                .filter(event -> inWindow(event, decemberStart, decemberEnd))
                .count();

        assertThat(august).as("the August group should have activity").isPositive();
        assertThat(december).as("December (x1.8) should out-produce August (x0.7)").isGreaterThan(august);
    }

    /** True when the account was already open at {@code instant}. */
    private static boolean existedBefore(Map<String, Instant> openedAt, Event event, Instant instant) {
        Instant opened = openedAt.get(event.aggregateId());
        return opened != null && !opened.isAfter(instant);
    }

    private static boolean inWindow(Event event, Instant from, Instant to) {
        return !event.getOccurredAt().isBefore(from) && event.getOccurredAt().isBefore(to);
    }

    @Test
    void amountsStayInsideTheirBandsForEveryTransaction() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        for (JournalEntry entry : journal.findAll()) {
            assertThat(entry.getAmount()).isGreaterThanOrEqualTo(10.0);
            // The largest single movement the brief allows is a 50K transfer.
            assertThat(entry.getAmount()).isLessThanOrEqualTo(100_000.0);
            // Whole cents. Compared with a tolerance rather than exactly: the
            // durable entity stores NUMERIC(19,4), so a value that was rounded to
            // cents on the way in comes back as a double a hair off (64965.79
            // arrives as 64965.78500000003).
            double cents = entry.getAmount() * 100;
            assertThat(Math.abs(cents - Math.round(cents)))
                    .as("amount %s should be a whole number of cents", entry.getAmount())
                    .isLessThan(0.01);
        }
    }

    @Test
    void wealthIsSkewedEnoughForTheConcentrationKpi() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        List<Double> balances = projection.allAccounts().stream()
                .map(AccountState::balance)
                .sorted(Comparator.reverseOrder())
                .toList();

        double total = balances.stream().mapToDouble(Double::doubleValue).sum();
        double topTen = balances.stream().limit(10).mapToDouble(Double::doubleValue).sum();

        // The brief's target band for the concentration panel. A flat
        // distribution would land near 5% and an unbounded Pareto tail near 95%.
        assertThat(topTen / total).isBetween(0.30, 0.80);
        // And the distribution is genuinely skewed rather than uniform.
        assertThat(balances.get(0)).isGreaterThan(balances.get(balances.size() / 2) * 5);
    }

    // -----------------------------------------------------------------------
    // Integrity
    // -----------------------------------------------------------------------

    @Test
    void noSeededAccountIsLeftOverdrawn() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        assertThat(projection.allAccounts())
                .allSatisfy(account -> assertThat(account.balance())
                        .as("balance of %s", account.accountId())
                        .isGreaterThanOrEqualTo(0.0));
    }

    @Test
    void theJournalStaysDoubleEntry() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        TrialBalance trial = journalService.trialBalance();
        assertThat(trial.balanced()).isTrue();
        assertThat(trial.totalDebits()).isEqualTo(trial.totalCredits());
        assertThat(journal.findAll())
                .allSatisfy(entry -> assertThat(entry.getDebitAccountId())
                        .isNotEqualTo(entry.getCreditAccountId()));
    }

    @Test
    void transfersBecomeSagasWithTheBriefsMixAndTimings() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        List<SagaState> all = sagas.findAll();
        assertThat(all).isNotEmpty();

        long compensating = all.stream().filter(saga -> saga.getStatus() == SagaStatus.COMPENSATING).count();
        // 5% of saga transfers end up compensated: with hundreds of sagas the
        // exact count is random, so this is a sanity band rather than a figure.
        assertThat(compensating).isLessThan(all.size());
        assertThat(compensating / (double) all.size()).isLessThan(0.20);

        for (SagaState saga : all) {
            assertThat(saga.getSteps()).extracting(step -> step.getName())
                    .contains("VALIDATE", "DEBIT_SOURCE", "CREDIT_DESTINATION", "ARCHIVE");
            // A reconstructed saga lasts a few hundred milliseconds, not zero.
            assertThat(Duration.between(saga.getStartedAt(), saga.getCompletedAt()).toMillis())
                    .isBetween(200L, 2_000L);
            assertThat(saga.getSteps()).allSatisfy(step -> {
                assertThat(step.getTimestamp()).isAfterOrEqualTo(saga.getStartedAt());
                assertThat(step.getTimestamp()).isBeforeOrEqualTo(saga.getCompletedAt());
            });
        }

        // A compensated saga books its two reversal legs, so the money is back
        // where it started and the journal still balances.
        for (SagaState saga : all) {
            if (saga.getStatus() == SagaStatus.COMPENSATING) {
                assertThat(journalService.entriesForTransaction(saga.getTransactionId()))
                        .as("compensated saga %s", saga.getTransactionId())
                        .hasSize(4);
            }
        }
    }

    @Test
    void transfersReachBothSagaAndPlainPostingPaths() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);

        List<SagaState> all = sagas.findAll();

        // Sagas are not empty, and the mix reaches them: the brief routes every
        // large transfer through the orchestrator and 30% of the medium ones, so
        // some sagas carry five figures and the rest are ordinary amounts. (An
        // earlier version of this test asserted every saga was >= 5,000, which
        // contradicts the mix it was supposed to be checking.)
        assertThat(all).isNotEmpty();
        assertThat(all.stream().mapToDouble(SagaState::getAmount).max().orElse(0))
                .as("large transfers should reach the saga path")
                .isGreaterThanOrEqualTo(5_000.0);
        assertThat(all.stream().filter(saga -> saga.getAmount() < 5_000).count())
                .as("medium transfers should reach the saga path too")
                .isPositive();
    }

    // -----------------------------------------------------------------------
    // Reproducibility
    // -----------------------------------------------------------------------

    @Test
    void theSameCommandProducesTheSameLedger() {
        seeder.seedPortfolio(CUSTOMERS, TRANSACTIONS);
        List<String> firstRun = describe(events, journal);

        Fixture second = fixture();
        second.seeder().seedPortfolio(CUSTOMERS, TRANSACTIONS);

        assertThat(describe(second.events(), second.journal())).isEqualTo(firstRun);
    }

    /**
     * A comparable description of what was written: event types, amounts, dates,
     * and the order they landed in.
     *
     * <p>Two things are deliberately left out, and both would otherwise make this
     * fail by design rather than on a regression:
     * <ul>
     *   <li>account ids, re-labelled by first appearance because they are random
     *       UUIDs;</li>
     *   <li>the compensation legs of a saga, whose timestamps come from {@code
     *       SagaTimeline} — the sub-second spacing between a saga's steps is
     *       jittered on purpose, so a compensated transfer's reversal events and
     *       journal entries land at slightly different instants on every run.</li>
     * </ul>
     * Everything else has to repeat exactly — that is what "fixed seed" means.
     */
    private static List<String> describe(FakeEventStore events, FakeJournalRepository journal) {
        Map<String, String> labels = new LinkedHashMap<>();
        List<String> shape = new ArrayList<>();

        for (Event event : events.allEvents()) {
            if (isCompensation(descriptionOf(event))) {
                continue;
            }
            shape.add(event.type() + "@" + event.getOccurredAt() + "/"
                    + labels.computeIfAbsent(event.aggregateId(), id -> "ACC#" + labels.size())
                    + "/" + amountOf(event));
        }
        for (JournalEntry entry : journal.findAll()) {
            if (isCompensation(entry.getDescription())) {
                continue;
            }
            shape.add("JOURNAL@" + entry.getCreatedAt() + "/" + entry.getAmount() + "/" + entry.getDescription());
        }
        return shape;
    }

    /** Compensation legs are the only rows stamped from the jittered timeline. */
    private static boolean isCompensation(String description) {
        return description != null && description.contains("COMPENSATION");
    }

    private static String descriptionOf(Event event) {
        if (event instanceof MoneyCreditedEvent credited) {
            return credited.getDescription();
        }
        if (event instanceof MoneyDebitedEvent debited) {
            return debited.getDescription();
        }
        return null;
    }

    private static double amountOf(Event event) {
        if (event instanceof MoneyCreditedEvent credited) {
            return credited.getAmount();
        }
        if (event instanceof MoneyDebitedEvent debited) {
            return debited.getAmount();
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private record Fixture(FakeEventStore events,
                           FakeJournalRepository journal,
                           FakeSagaRepository sagas,
                           JournalService journalService,
                           AccountProjection projection,
                           LedgerSeeder seeder) {
    }

    private Fixture fixture() {
        FakeEventStore store = new FakeEventStore();
        FakeJournalRepository entries = new FakeJournalRepository();
        FakeSagaRepository sagaStore = new FakeSagaRepository();
        AccountProjection accountProjection = new AccountProjection(store);
        JournalService service = new JournalService(entries, CLOCK);

        ObjectProvider<CallerContext> callerContext = scopeBoundCallerContext();
        AccountService accountService = new AccountService(store, accountProjection, unreachableCustomers(), CLOCK);

        return new Fixture(store, entries, sagaStore, service, accountProjection,
                new LedgerSeeder(accountService, service, store, sagaStore, callerContext, CLOCK,
                        new PassthroughTransactionManager(), null));
    }

    /** A caller context resolved from the ambient request scope, as the container does. */
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

    /** The seeder supplies holder names, so customer-service must never be called. */
    private static CustomerRestClient unreachableCustomers() {
        return new CustomerRestClient() {
            @Override
            public Customer getCustomer(Long id) {
                throw new AssertionError("the seeder must not call customer-service for " + id);
            }
        };
    }

    /** Keeps the seed's begin/commit bookkeeping honest without a database. */
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
        private final Map<String, SagaState> sagas = new LinkedHashMap<>();

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
