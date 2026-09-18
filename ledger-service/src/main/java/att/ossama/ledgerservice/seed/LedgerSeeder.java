package att.ossama.ledgerservice.seed;

import att.ossama.ledgerservice.account.AccountService;
import att.ossama.ledgerservice.domain.AccountCreatedEvent;
import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.MoneyCreditedEvent;
import att.ossama.ledgerservice.domain.MoneyDebitedEvent;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.saga.SagaRepository;
import att.ossama.ledgerservice.saga.SagaTimeline;
import att.ossama.ledgerservice.security.CallerContext;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Writes realistic slices of ledger history.
 *
 * <p>Writes go through the domain services rather than through SQL, so seeded
 * data satisfies the same invariants as live data: funds are checked, the journal
 * is double-entry, transfers are sagas. The only thing that differs is the clock
 * — every write is stamped with the instant being reconstructed rather than with
 * wall-clock time.
 *
 * <h2>Two modes</h2>
 * {@link #seedSmall()} writes a few customers over 90 days: enough to prove the
 * chain end to end, and what a fresh checkout gets. {@link #seedPortfolio()}
 * writes {@value #PORTFOLIO_CUSTOMERS} customers, their accounts and
 * {@value #PORTFOLIO_TRANSACTIONS} transactions across two years, with the
 * acquisition, seasonality, amount and wealth distributions a real ledger shows.
 * Both are reproducible: the same command produces the same ledger.
 *
 * <h2>Provenance</h2>
 * Everything a run creates belongs to customers in the seed's own id range
 * ({@value #PORTFOLIO_CUSTOMER_ID_BASE}+), which is what makes the data
 * identifiable and removable.
 */
@Service
public class LedgerSeeder {

    private static final Logger log = LoggerFactory.getLogger(LedgerSeeder.class);

    // -----------------------------------------------------------------------
    // Small mode
    // -----------------------------------------------------------------------

    static final int SMALL_CUSTOMERS = 10;
    static final int SMALL_TRANSACTIONS = 30;

    /** How far back the small seed reaches. */
    static final Duration HISTORY = Duration.ofDays(90);

    /** Fixed so the small seed is reproducible, as it was before portfolio mode. */
    private static final long SMALL_RANDOM_SEED = 20_260_916L;

    /** Small-mode opening deposit, as a multiple of the largest movement it writes. */
    private static final double OPENING_BALANCE_MULTIPLE = 4;

    /** Largest movement the small seed writes. */
    private static final double MAX_MOVEMENT = 5_000;

    // -----------------------------------------------------------------------
    // Portfolio mode
    // -----------------------------------------------------------------------

    static final int PORTFOLIO_CUSTOMERS = 500;
    static final int PORTFOLIO_TRANSACTIONS = 40_000;
    static final int PORTFOLIO_MONTHS = SeedPacing.PORTFOLIO_MONTHS;

    /** Days per month in the reconstruction: the brief's 24 months, in 30-day steps. */
    private static final long DAYS_PER_MONTH = 30L;

    /** Customers stop transacting from this month, at the brief's monthly rate. */
    private static final int CHURN_FROM_MONTH = 7;
    private static final double CHURN_RATE = 0.02;

    /** Per-customer monthly transaction rate, before scaling to the target volume. */
    private static final double MONTHLY_TRANSACTION_MEAN = 6.0;

    /** The brief's mix: deposits/withdrawals, transfers, then large transfers. */
    private static final double SHARE_DEPOSIT_OR_WITHDRAWAL = 0.70;
    private static final double SHARE_TRANSFER = 0.25;

    /** Of the transfers, the share that run as sagas rather than plain postings. */
    private static final double SHARE_TRANSFER_VIA_SAGA = 0.30;

    /** Of the saga transfers, the share that end up compensated. */
    private static final double SHARE_SAGA_COMPENSATING = 0.05;

    /** Account id format, matching what AccountService issues. */
    private static final String ACCOUNT_ID_PREFIX = "ACC-";

    /** Customer id range owned by the seed, so seeded data is identifiable. */
    static final long PORTFOLIO_CUSTOMER_ID_BASE = 900_000L;

    /**
     * Largest single funding entry. A seven-figure account is funded by several
     * deposits rather than one implausible transfer, and each is a journal entry
     * like any other.
     */
    private static final double MAX_OPENING_DEPOSIT = 100_000;

    /**
     * Accounts open across the first third of the history; every money movement
     * happens in the remaining two thirds.
     *
     * <p>Overlapping the two spans would let a payment land before the account it
     * draws on was opened — the seeder would be reconstructing an overdraft that
     * never happened. Disjoint spans are the cheap guarantee; a finer-grained
     * version would let each account's activity start from its own opening date,
     * at the cost of tracking every opening.
     */
    private static final double ACCOUNT_OPENING_SPAN = 1.0 / 3.0;

    /** How often the portfolio run reports progress. */
    private static final int PROGRESS_EVERY = 50;

    /**
     * Transactions between persistence-context resets.
     *
     * <p>The whole backfill is one transaction, so without this the persistence
     * context grows to every entity the run has touched. Flushing and clearing
     * it periodically keeps each flush proportional to the batch instead of to
     * the run: the 40k seed decayed from 32 to 13 transactions/second as the
     * context grew, because every flush, dirty check and query planned over an
     * ever-larger set of managed entities.
     */
    private static final int PERSISTENCE_FLUSH_BATCH = 500;

    private static final String[] CUSTOMER_NAMES = {
            "Alice Moreau", "Bob Ferreira", "Chloé Dubois", "Daniel Okafor", "Elena Rossi",
            "Farid Benali", "Grace Lindqvist", "Hiroshi Tanaka", "Ines Almeida", "Jonas Weber"
    };

    private static final String[] CREDIT_DESCRIPTIONS = {
            "salary", "invoice payment", "refund", "transfer received", "interest"
    };

    private static final String[] DEBIT_DESCRIPTIONS = {
            "utilities", "card payment", "supplier invoice", "rent", "subscription"
    };

    private final AccountService accountService;
    private final JournalService journalService;
    private final EventStore eventStore;
    private final SagaRepository sagaRepository;
    /**
     * Resolved, not injected: {@link CallerContext} is request-scoped, so the
     * instance only exists inside the scope {@link #inSyntheticRequestScope}
     * opens. A provider lets the seeder ask for it there rather than holding a
     * proxy it cannot use.
     */
    private final ObjectProvider<CallerContext> callerContext;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    /**
     * Used only to bound the persistence context during a bulk run. Null in unit
     * tests, which drive in-memory repositories and have no context to bound.
     */
    private final EntityManager entityManager;

    public LedgerSeeder(AccountService accountService,
                        JournalService journalService,
                        EventStore eventStore,
                        SagaRepository sagaRepository,
                        ObjectProvider<CallerContext> callerContext,
                        Clock clock,
                        PlatformTransactionManager transactionManager,
                        EntityManager entityManager) {
        this.accountService = accountService;
        this.journalService = journalService;
        this.eventStore = eventStore;
        this.sagaRepository = sagaRepository;
        this.callerContext = callerContext;
        this.clock = clock;
        this.transactionManager = transactionManager;
        this.entityManager = entityManager;
    }

    /** What one run produced, for the runner's summary line. */
    public record SeedSummary(int customers, int accounts, int credits, int debits, int transfers) {
        public int transactions() {
            return credits + debits + transfers;
        }
    }

    public SeedSummary seedSmall() {
        return seed(SMALL_CUSTOMERS, SMALL_TRANSACTIONS);
    }

    /** The opening deposit every small-mode account starts with. */
    static double openingBalance() {
        return MAX_MOVEMENT * OPENING_BALANCE_MULTIPLE;
    }

    // -----------------------------------------------------------------------
    // Small mode
    // -----------------------------------------------------------------------

    /**
     * Seeds {@code customerCount} customers and {@code transactionCount} money
     * movements over {@link #HISTORY}, ending at the current instant.
     *
     * <p>Accounts open over the first third of the window and everything else
     * happens in the remaining two thirds, so a payment can never land before the
     * account it draws on was opened.
     */
    public SeedSummary seed(int customerCount, int transactionCount) {
        Random random = new Random(SMALL_RANDOM_SEED);
        Instant now = clock.instant();
        Instant historyStart = now.minus(HISTORY);
        Instant openingDeadline = historyStart.plusMillis((long) (HISTORY.toMillis() * ACCOUNT_OPENING_SPAN));

        SeedLedger ledger = new SeedLedger(eventStore);
        return inSyntheticRequestScope(() -> {
            List<AccountState> accounts = openSmallAccounts(ledger, customerCount, historyStart, openingDeadline, random);
            Movements movements = postSmallMovements(ledger, accounts, transactionCount,
                    openingDeadline.plusMillis(1), now, random);
            SeedSummary summary = new SeedSummary(customerCount, accounts.size(),
                    (int) movements.credits(), (int) movements.debits(), (int) movements.transfers());
            log.info("Seeded {} customers, {} accounts, {} transactions "
                            + "({} credits, {} debits, {} transfers) between {} and {}",
                    summary.customers(), summary.accounts(), summary.transactions(),
                    summary.credits(), summary.debits(), summary.transfers(), historyStart, now);
            return summary;
        });
    }

    private List<AccountState> openSmallAccounts(SeedLedger ledger, int customerCount,
                                                 Instant from, Instant to, Random random) {
        List<AccountState> accounts = new ArrayList<>(customerCount);
        long window = Math.max(1, Duration.between(from, to).toMillis());

        for (int i = 0; i < customerCount; i++) {
            long customerId = PORTFOLIO_CUSTOMER_ID_BASE + i;
            Instant openedAt = from.plusMillis(random.nextLong(window));
            AccountState account = accountService.openAccount(
                    customerId, CUSTOMER_NAMES[i % CUSTOMER_NAMES.length], openedAt);
            credit(ledger, account.accountId(), openingBalance(), "opening deposit", openedAt);
            accounts.add(account);
        }
        return accounts;
    }

    private Movements postSmallMovements(SeedLedger ledger, List<AccountState> accounts, int transactionCount,
                                         Instant from, Instant to, Random random) {
        long window = Math.max(1, Duration.between(from, to).toMillis());
        int credits = 0;
        int debits = 0;
        int transfers = 0;

        for (int i = 0; i < transactionCount; i++) {
            Instant occurredAt = from.plusMillis(random.nextLong(window));
            String transactionId = UUID.randomUUID().toString();
            AccountState account = accounts.get(random.nextInt(accounts.size()));
            double amount = round(50 + random.nextDouble() * (MAX_MOVEMENT - 50));

            switch (i % 3) {
                case 0 -> {
                    credit(ledger, account.accountId(), amount,
                            CREDIT_DESCRIPTIONS[random.nextInt(CREDIT_DESCRIPTIONS.length)], occurredAt);
                    credits++;
                }
                case 1 -> {
                    // Skip when the account cannot cover it: letting it through
                    // would drive a seeded balance negative.
                    if (ledger.balance(account.accountId()) < amount) {
                        continue;
                    }
                    debit(ledger, account.accountId(), amount,
                            DEBIT_DESCRIPTIONS[random.nextInt(DEBIT_DESCRIPTIONS.length)], occurredAt, transactionId);
                    debits++;
                }
                default -> {
                    AccountState destination = otherAccount(accounts, account, random);
                    if (destination == null || ledger.balance(account.accountId()) < amount) {
                        continue;
                    }
                    writeTransfer(ledger, account.accountId(), destination.accountId(), amount, occurredAt,
                            transactionId, true, false, random);
                    transfers++;
                }
            }
        }
        return new Movements(credits, debits, transfers);
    }

    // -----------------------------------------------------------------------
    // Portfolio mode
    // -----------------------------------------------------------------------

    /**
     * Seeds the portfolio dataset: {@value #PORTFOLIO_CUSTOMERS} customers, their
     * accounts, {@value #PORTFOLIO_TRANSACTIONS} transactions, laid out over
     * {@value #PORTFOLIO_MONTHS} months.
     *
     * <p>The whole run is one transaction. Tens of thousands of separate commits
     * take minutes on their own — each is a round trip and an fsync — and a
     * backfill that fails two thirds of the way through should leave nothing
     * behind rather than a ledger nobody can reason about.
     */
    public SeedSummary seedPortfolio() {
        return seedPortfolio(PORTFOLIO_CUSTOMERS, PORTFOLIO_TRANSACTIONS);
    }

    /**
     * The portfolio shape at an arbitrary size.
     *
     * <p>Same distributions over the same 24 months, with fewer customers and
     * transactions. The full-size run takes minutes, which is more than a test can
     * spend, so the invariants are asserted at a smaller scale and the full run is
     * verified against the database. Output is exactly {@code transactionCount}
     * movements: each customer's draw is scaled to hit the target rather than the
     * generator stopping when a counter runs out.
     */
    public SeedSummary seedPortfolio(int customerCount, int transactionCount) {
        return runInOneTransaction(() -> {
            Random random = new Random(SeedPacing.RANDOM_SEED);
            Instant now = clock.instant();
            Instant historyStart = now.minus(Duration.ofDays(PORTFOLIO_MONTHS * DAYS_PER_MONTH));
            Instant openingsEnd = historyStart.plusMillis(
                    (long) (Duration.between(historyStart, now).toMillis() * ACCOUNT_OPENING_SPAN));

            SeedLedger ledger = new SeedLedger(eventStore);
            List<SeedCustomer> customers = planCustomers(random, historyStart, now, customerCount);
            Progress progress = new Progress(customerCount, ledger);

            SeedSummary summary = inSyntheticRequestScope(() -> {
                int openingDeposits = openPortfolioAccounts(ledger, customers, random);
                Movements movements = postPortfolioTransactions(ledger, customers, now, random,
                        transactionCount, progress);
                progress.report(customers.size(), movements, true);
                return new SeedSummary(customers.size(), ledger.accountCount(),
                        (int) movements.credits() + openingDeposits,
                        (int) movements.debits(), (int) movements.transfers());
            });

            log.info("Portfolio seed complete: {} customers, {} accounts, {} transactions "
                            + "({} credits, {} debits, {} transfers) over {} months",
                    summary.customers(), summary.accounts(), summary.transactions(),
                    summary.credits(), summary.debits(), summary.transfers(), PORTFOLIO_MONTHS);
            return summary;
        });
    }

    /**
     * Decides who arrives when, what accounts they get and when those open.
     *
     * <p>Planned up front so the population is known before anything is written:
     * the transaction phase needs each customer's churn month and every account's
     * opening date, and discovering those mid-write would mean either re-reading
     * the ledger or paying into accounts that do not exist yet.
     */
    private List<SeedCustomer> planCustomers(Random random, Instant historyStart, Instant now,
                                            int customerCount) {
        int[] arrivals = SeedPacing.acquisitionCurve(PORTFOLIO_MONTHS, customerCount);
        List<SeedCustomer> customers = new ArrayList<>(customerCount);
        int index = 0;

        for (int month = 0; month < PORTFOLIO_MONTHS; month++) {
            Instant monthStart = historyStart.plus(Duration.ofDays(month * DAYS_PER_MONTH));
            long monthMillis = Duration.ofDays(DAYS_PER_MONTH).toMillis();
            for (int i = 0; i < arrivals[month]; i++) {
                // Spread arrivals through their month: stacking them on day one
                // would show up as spikes in the charts.
                Instant appearedAt = monthStart.plusMillis((long) (random.nextDouble() * monthMillis));

                int accountCount = SeedPacing.accountCountFor(index, random);
                List<SeedAccount> accounts = new ArrayList<>(accountCount);
                for (int a = 0; a < accountCount; a++) {
                    Instant openedAt = appearedAt.plus(Duration.ofDays(SeedPacing.accountOpeningDelayDays(random)));
                    // A customer arriving at the very end of the window would otherwise
                    // open an account after now, and every event stamped from it would
                    // land in the future.
                    accounts.add(new SeedAccount(openedAt.isAfter(now) ? now.minusSeconds(60) : openedAt));
                }
                customers.add(new SeedCustomer(index, appearedAt, accounts, churnMonth(random)));
                index++;
            }
        }

        if (customers.size() != customerCount) {
            // The curve is scaled to the target, so this can only mean the scaling
            // broke; failing loudly beats seeding a different population quietly.
            throw new IllegalStateException("Acquisition curve produced " + customers.size()
                    + " customers, expected " + customerCount);
        }
        return customers;
    }

    /**
     * The month a customer goes quiet, or {@code -1} for one that stays active.
     *
     * <p>Churn starts in month {@value #CHURN_FROM_MONTH} at {@value #CHURN_RATE}
     * per month and is terminal: a churned customer keeps their accounts and
     * history — the log is append-only — but stops transacting.
     */
    private int churnMonth(Random random) {
        for (int month = CHURN_FROM_MONTH; month < PORTFOLIO_MONTHS; month++) {
            if (random.nextDouble() < CHURN_RATE) {
                return month;
            }
        }
        return -1;
    }

    /**
     * Opens and funds every portfolio account.
     *
     * @return the number of funding entries written, which count as transactions
     */
    private int openPortfolioAccounts(SeedLedger ledger, List<SeedCustomer> customers, Random random) {
        int deposits = 0;
        for (SeedCustomer customer : customers) {
            long customerId = PORTFOLIO_CUSTOMER_ID_BASE + customer.index();
            double budget = SeedPacing.openingBalanceFor(customer.index(), random);
            double perAccount = budget / customer.accounts().size();

            List<SeedAccount> accounts = customer.accounts();
            for (int a = 0; a < accounts.size(); a++) {
                SeedAccount account = accounts.get(a);
                String accountId = openAccount(ledger, customerId, holderName(customer.index(), a),
                        account.openedAt());
                account.setAccountId(accountId);
                deposits += fund(ledger, accountId, perAccount, account.openedAt());
            }
        }
        return deposits;
    }

    /**
     * Opens one account by appending its ACCOUNT_CREATED event.
     *
     * <p>Written through the seeder's own ledger view rather than through
     * AccountService, and that is not a shortcut: the service holds the raw store,
     * so an account created that way never reaches the in-memory index the
     * generator reads balances from — every account would read as non-existent
     * with a balance of zero, and every withdrawal and transfer would be skipped
     * as unfunded. Account ids keep the service's format.
     */
    private String openAccount(SeedLedger ledger, long customerId, String holderName, Instant openedAt) {
        String accountId = ACCOUNT_ID_PREFIX + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ledger.append(List.of(new AccountCreatedEvent(
                UUID.randomUUID().toString(), openedAt, accountId, customerId, holderName)), openedAt);
        return accountId;
    }

    /**
     * Funds one account, splitting anything above {@link #MAX_OPENING_DEPOSIT}
     * into several entries so no single deposit is implausible.
     *
     * <p>The remainder is compared in whole cents. Subtracting a double from a
     * double leaves a residue — 300,000 minus 100,000 three times is not zero —
     * and a naive {@code while (remaining > 0)} turns that residue into hundreds
     * of one-cent deposits.
     *
     * @return how many deposit entries were written
     */
    private int fund(SeedLedger ledger, String accountId, double budget, Instant openedAt) {
        int entries = 0;
        long remainingCents = Math.round(budget * 100);
        while (remainingCents > 0) {
            long sliceCents = Math.min(remainingCents, Math.round(MAX_OPENING_DEPOSIT * 100));
            credit(ledger, accountId, sliceCents / 100.0, "opening deposit", openedAt);
            remainingCents -= sliceCents;
            entries++;
        }
        return entries;
    }

    /**
     * Writes the transaction population.
     *
     * <p>The brief sets both a rate (Poisson, mean 6 per customer-month) and an
     * exact total (40,000), which do not agree on their own — the rate over the
     * active months gives roughly 27,000. Rather than drop the rate or miss the
     * target, each customer's draw is scaled by the ratio needed to reach the
     * target: the shape is preserved (busy customers stay busy, churned ones stay
     * quiet) and the volume lands exactly where the brief asks.
     */
    private Movements postPortfolioTransactions(SeedLedger ledger, List<SeedCustomer> customers,
                                                Instant to, Random random,
                                                int transactionCount, Progress progress) {
        Counters counters = new Counters();

        // One raw draw per customer fixes the relative shape; everything after is
        // that shape scaled and rounded to the exact total.
        double[] draws = new double[customers.size()];
        double totalDraw = 0;
        for (int i = 0; i < customers.size(); i++) {
            draws[i] = SeedPacing.poisson(random, MONTHLY_TRANSACTION_MEAN) * activeMonths(customers.get(i));
            totalDraw += draws[i];
        }
        double scale = totalDraw == 0 ? 0 : (double) transactionCount / totalDraw;

        // A running remainder keeps the scaled counts summing to the target
        // exactly, instead of each customer's rounding drifting.
        double carry = 0;
        long assignedSoFar = 0;

        for (int i = 0; i < customers.size(); i++) {
            SeedCustomer customer = customers.get(i);
            carry += draws[i] * scale;
            long target = (long) Math.floor(carry);
            long assigned = target - assignedSoFar;
            assignedSoFar = target;

            for (long t = 0; t < assigned; t++) {
                transact(ledger, customer, to, random, counters);
                counters.written++;
                if (counters.written % PERSISTENCE_FLUSH_BATCH == 0) {
                    boundPersistenceContext();
                }
            }
            if ((i + 1) % PROGRESS_EVERY == 0 || i == customers.size() - 1) {
                // Flush before reporting: the log line quotes the store's event
                // count, and anything still pending in the context would not be
                // in it yet.
                boundPersistenceContext();
                progress.report(i + 1, new Movements(counters.credits, counters.debits, counters.transfers), false);
            }
        }
        return new Movements(counters.credits, counters.debits, counters.transfers);
    }

    /**
     * Months a customer transacts for, given their churn month.
     *
     * <p>Note this is the same for every customer regardless of when they joined,
     * so a late arrival receives as many transactions as an early one, packed into
     * the shorter window their account has been open for. That is deliberate: the
     * per-account window in {@link #transact} is what enforces the ordering
     * invariant, and this only decides how the volume is shared out.
     */
    private int activeMonths(SeedCustomer customer) {
        int churned = customer.churnMonth();
        int until = churned < 0 ? PORTFOLIO_MONTHS : churned;
        return Math.max(1, until);
    }

    /** Issues one transaction for a customer, choosing its type by the brief's mix. */
    private void transact(SeedLedger ledger, SeedCustomer customer, Instant to,
                          Random random, Counters counters) {
        List<SeedAccount> accounts = customer.accounts();
        SeedAccount account = accounts.get(random.nextInt(accounts.size()));
        String transactionId = UUID.randomUUID().toString();
        double roll = random.nextDouble();

        if (roll < SHARE_DEPOSIT_OR_WITHDRAWAL) {
            double amount = SeedPacing.logNormalAmountWithin(random, 20, 500);
            // Never before the account exists: the window is the account's own life.
            Instant occurredAt = SeedPacing.weightedInstant(random, account.openedAt(), to);
            // Deposits and withdrawals share one population, split by whether the
            // account can cover it: an account cannot withdraw what it lacks.
            if (random.nextBoolean() || ledger.balance(account.accountId()) < amount) {
                credit(ledger, account.accountId(), amount,
                        CREDIT_DESCRIPTIONS[random.nextInt(CREDIT_DESCRIPTIONS.length)], occurredAt);
                counters.credits++;
            } else {
                debit(ledger, account.accountId(), amount,
                        DEBIT_DESCRIPTIONS[random.nextInt(DEBIT_DESCRIPTIONS.length)], occurredAt, transactionId);
                counters.debits++;
            }
            return;
        }

        boolean large = roll >= SHARE_DEPOSIT_OR_WITHDRAWAL + SHARE_TRANSFER;
        double amount = large
                ? SeedPacing.uniformAmount(random, 5_000, 50_000)
                : SeedPacing.logNormalAmountWithin(random, 50, 2_000);

        // A five-figure transfer is only possible from an account that holds one.
        // Picking at random would have most large transfers refused by the funds
        // check and quietly rebooked as a small credit, which is how a seed ends
        // up with no large movements at all in a population that is mostly small
        // accounts. The brief still shapes who is wealthy; this only decides which
        // of a customer's accounts the big movement comes out of.
        if (large) {
            account = richestAccount(accounts, ledger, amount);
        }
        // Stamped after the account is settled: a large transfer moves to a
        // different, possibly later-opened account, and the movement must still
        // not precede that account's opening.
        Instant occurredAt = SeedPacing.weightedInstant(random, account.openedAt(), to);
        SeedAccount destination = otherAccount(accounts, account, random, occurredAt);
        if (destination == null || ledger.balance(account.accountId()) < amount) {
            // Not enough to move: book an incoming payment instead of writing a
            // transfer the ledger would refuse.
            credit(ledger, account.accountId(), SeedPacing.logNormalAmountWithin(random, 20, 500),
                    CREDIT_DESCRIPTIONS[random.nextInt(CREDIT_DESCRIPTIONS.length)], occurredAt);
            counters.credits++;
            return;
        }

        // The brief: 30% of transfers run as sagas, and the large ones always do —
        // a five-figure movement is exactly what a saga exists to protect.
        boolean viaSaga = large || random.nextDouble() < SHARE_TRANSFER_VIA_SAGA;
        boolean compensating = viaSaga && random.nextDouble() < SHARE_SAGA_COMPENSATING;
        writeTransfer(ledger, account.accountId(), destination.accountId(), amount, occurredAt,
                transactionId, viaSaga, compensating, random);
        counters.transfers++;
    }

    // -----------------------------------------------------------------------
    // Write helpers
    // -----------------------------------------------------------------------

    /**
     * Writes a transfer: its two events, its journal legs, and its saga if it has
     * one.
     *
     * @param viaSaga      whether the transfer is recorded as an orchestrated saga
     * @param compensating whether that saga's archive failed and it was unwound
     */
    private void writeTransfer(SeedLedger ledger, String source, String destination, double amount,
                               Instant occurredAt, String transactionId, boolean viaSaga,
                               boolean compensating, Random random) {
        if (!viaSaga) {
            // The plain posting path: two events, one journal entry, no saga —
            // which is what a direct TRANSFER through /api/transactions leaves.
            appendTransferEvents(ledger, source, destination, amount, occurredAt, transactionId);
            journalService.postTransfer(transactionId, source, destination, amount, occurredAt);
            return;
        }
        appendTransferEvents(ledger, source, destination, amount, occurredAt, transactionId);
        journalService.postTransfer(transactionId, source, destination, amount, occurredAt);
        sagaRepository.save(sagaOf(ledger, transactionId, source, destination, amount, occurredAt,
                compensating, random));
    }

    /**
     * The finished shape of a saga, as the orchestrator would have left it.
     *
     * <p>Reusing {@link SagaTimeline} — the orchestrator's own clock — is what
     * keeps reconstructed sagas indistinguishable from live ones: the same steps,
     * in the same order, spaced the same way. A compensated saga also books the
     * two reversal legs the orchestrator would have posted.
     */
    private SagaState sagaOf(SeedLedger ledger, String transactionId, String source, String destination,
                             double amount, Instant startedAt, boolean compensating, Random random) {
        SagaState saga = new SagaState(transactionId, source, destination, amount, startedAt);
        SagaTimeline timeline = new SagaTimeline(startedAt);

        Instant validate = timeline.validate();
        Instant debit = timeline.debitSource();
        Instant credit = timeline.creditDestination(debit);
        Instant archive = timeline.archive(credit);

        saga.addStep("VALIDATE", validate, null, "EXECUTED");
        saga.addStep("DEBIT_SOURCE", debit, null, "EXECUTED");
        saga.addStep("CREDIT_DESTINATION", credit, null, "EXECUTED");

        if (!compensating) {
            saga.addStep("ARCHIVE", archive, null, "EXECUTED");
            saga.finish(SagaStatus.COMPLETED, null, timeline.completedAt(archive));
            return saga;
        }

        saga.addStep("ARCHIVE", archive, null, "FAILED");
        Instant reverseCredit = timeline.compensateDebit(archive);
        Instant reverseDebit = timeline.compensateCredit(reverseCredit);
        saga.addStep("COMPENSATE_DEBIT_" + destination, reverseCredit, null, "COMPENSATED");
        saga.addStep("COMPENSATE_CREDIT_" + source, reverseDebit, null, "COMPENSATED");
        // The reversal legs are real journal entries and real events, exactly as
        // the orchestrator's compensation would leave behind.
        journalService.reverseTransferCredit(transactionId, destination, amount, reverseCredit);
        journalService.reverseTransferDebit(transactionId, source, amount, reverseDebit);
        ledger.append(List.of(
                new MoneyDebitedEvent(UUID.randomUUID().toString(), reverseCredit, transactionId, destination,
                        amount, "SAGA COMPENSATION (reverse credit)"),
                new MoneyCreditedEvent(UUID.randomUUID().toString(), reverseDebit, transactionId, source,
                        amount, "SAGA COMPENSATION (reverse debit)")), reverseDebit);
        saga.finish(SagaStatus.COMPENSATING, "Archive failed; transfer reversed.",
                timeline.completedAt(reverseDebit));
        return saga;
    }

    private void appendTransferEvents(SeedLedger ledger, String source, String destination, double amount,
                                      Instant occurredAt, String transactionId) {
        ledger.append(List.of(
                new MoneyDebitedEvent(UUID.randomUUID().toString(), occurredAt, transactionId, source, amount,
                        "TRANSFER to " + destination),
                new MoneyCreditedEvent(UUID.randomUUID().toString(), occurredAt, transactionId, destination, amount,
                        "TRANSFER from " + source)), occurredAt);
    }

    /**
     * Appends a credit event and posts its journal entry together.
     *
     * <p>Both halves are required and they are not interchangeable. Balances are
     * derived by replaying the event log, so an entry that only reached the
     * journal would leave the account reading zero and every withdrawal against
     * it refused; the trial balance is derived from the journal, so an event that
     * only reached the log would leave the ledger unbalanced.
     */
    private void credit(SeedLedger ledger, String accountId, double amount, String description, Instant occurredAt) {
        String transactionId = UUID.randomUUID().toString();
        ledger.append(List.of(new MoneyCreditedEvent(UUID.randomUUID().toString(), occurredAt, transactionId,
                accountId, amount, description)), occurredAt);
        journalService.postCredit(transactionId, accountId, amount, description, occurredAt);
    }

    private void debit(SeedLedger ledger, String accountId, double amount, String description,
                       Instant occurredAt, String transactionId) {
        ledger.append(List.of(new MoneyDebitedEvent(UUID.randomUUID().toString(), occurredAt, transactionId,
                accountId, amount, description)), occurredAt);
        journalService.postDebit(transactionId, accountId, amount, description, occurredAt);
    }

    private String holderName(int customerIndex, int accountIndex) {
        String base = CUSTOMER_NAMES[customerIndex % CUSTOMER_NAMES.length];
        return accountIndex == 0 ? base : base + " (" + (accountIndex + 1) + ")";
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static AccountState otherAccount(List<AccountState> accounts, AccountState exclude, Random random) {
        if (accounts.size() < 2) {
            return null;
        }
        AccountState candidate;
        do {
            candidate = accounts.get(random.nextInt(accounts.size()));
        } while (candidate.accountId().equals(exclude.accountId()));
        return candidate;
    }

    /**
     * The account among {@code accounts} holding at least {@code amount}, richest
     * first, or the richest of them when none can cover it.
     *
     * <p>Deliberately not random: choosing uniformly among a customer's accounts
     * would make a large transfer fail its funds check most of the time, and the
     * fallback would rebook it as a small credit — leaving the seeded ledger with
     * none of the large movements the mix asks for.
     */
    private static SeedAccount richestAccount(List<SeedAccount> accounts, SeedLedger ledger, double amount) {
        SeedAccount richest = accounts.get(0);
        double best = -1;
        for (SeedAccount candidate : accounts) {
            double balance = ledger.balance(candidate.accountId());
            if (balance >= amount) {
                return candidate;
            }
            if (balance > best) {
                best = balance;
                richest = candidate;
            }
        }
        return richest;
    }

    /**
     * Another account of the same customer that is already open at
     * {@code occurredAt}, or null when this customer has nowhere to send money.
     *
     * <p>Checking the opening date matters for the destination as much as the
     * source: a transfer credits an account that must already exist, or the
     * reconstruction claims money arrived somewhere before it was opened.
     */
    private static SeedAccount otherAccount(List<SeedAccount> accounts, SeedAccount exclude,
                                            Random random, Instant occurredAt) {
        List<SeedAccount> open = accounts.stream()
                .filter(candidate -> candidate != exclude)
                .filter(candidate -> !candidate.openedAt().isAfter(occurredAt))
                .toList();
        if (open.isEmpty()) {
            return null;
        }
        return open.get(random.nextInt(open.size()));
    }

    /** How the requested movements were actually distributed. */
    private record Movements(long credits, long debits, long transfers) {
        long total() {
            return credits + debits + transfers;
        }
    }

    /** Mutable running totals, since the transaction loop is not a stream. */
    private static final class Counters {
        private long credits;
        private long debits;
        private long transfers;
        /** Movements written so far, used to trigger the periodic flush. */
        private long written;
    }

    /** One customer in the planned population, with the accounts they will hold. */
    private static final class SeedCustomer {
        private final int index;
        private final Instant appearedAt;
        private final List<SeedAccount> accounts;
        private final int churnMonth;

        private SeedCustomer(int index, Instant appearedAt, List<SeedAccount> accounts, int churnMonth) {
            this.index = index;
            this.appearedAt = appearedAt;
            this.accounts = accounts;
            this.churnMonth = churnMonth;
        }

        int index() {
            return index;
        }

        @SuppressWarnings("unused") // kept: which month a customer joined explains the acquisition curve
        Instant appearedAt() {
            return appearedAt;
        }

        List<SeedAccount> accounts() {
            return accounts;
        }

        int churnMonth() {
            return churnMonth;
        }
    }

    /** One planned account: when it opens, and its id once written. */
    private static final class SeedAccount {
        private final Instant openedAt;
        private String accountId;

        private SeedAccount(Instant openedAt) {
            this.openedAt = openedAt;
        }

        Instant openedAt() {
            return openedAt;
        }

        String accountId() {
            return accountId;
        }

        void setAccountId(String accountId) {
            this.accountId = accountId;
        }
    }

    // -----------------------------------------------------------------------
    // Progress
    // -----------------------------------------------------------------------

    /** Logs a line every 50 customers: what is done, how fast, and how far in. */
    private static final class Progress {
        private final Logger log = LoggerFactory.getLogger(LedgerSeeder.class);
        private final int totalCustomers;
        private final SeedLedger ledger;
        private final long startedAt = System.nanoTime();

        private Progress(int totalCustomers, SeedLedger ledger) {
            this.totalCustomers = totalCustomers;
            this.ledger = ledger;
        }

        void report(int customersDone, Movements movements, boolean finished) {
            double seconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1_000_000_000.0);
            log.info("Seeded {}/{} customers, {} accounts, {} transactions, {} events "
                            + "({}s elapsed, ~{} txn/s){}",
                    customersDone, totalCustomers, ledger.accountCount(), movements.total(), ledger.count(),
                    String.format("%.1f", seconds), Math.round(movements.total() / seconds),
                    finished ? " — done" : "");
        }
    }

    // -----------------------------------------------------------------------
    // Infrastructure
    // -----------------------------------------------------------------------

    /**
     * Writes pending changes and detaches them, so the persistence context does
     * not grow with the length of the run.
     *
     * <p>Safe for everything the seeder holds in memory: the balance index is
     * plain maps of account id to amount, the planned customers and accounts hold
     * account-id strings, and every domain object written so far (events, journal
     * entries, saga state) is copied into a fresh entity by its repository before
     * being saved. Nothing the generator reads back after this point is a managed
     * entity or a lazy proxy, so clearing cannot detach something still in use.
     *
     * <p>The flush is not a commit. The run is still one transaction, so it either
     * lands whole or not at all — this only bounds memory and the cost of the next
     * flush.
     */
    private void boundPersistenceContext() {
        if (entityManager == null) {
            // Unit tests drive in-memory repositories; there is no context to bound.
            return;
        }
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Wraps the whole backfill in a single transaction.
     *
     * <p>Programmatic rather than {@code @Transactional}: the work happens inside
     * the synthetic request scope and is handed in as a {@link Supplier}, so an
     * annotation would either not apply or would wrap the scope instead of
     * sitting inside it.
     */
    private <T> T runInOneTransaction(Supplier<T> body) {
        TransactionStatus status = transactionManager.getTransaction(
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED));
        try {
            T result = body.get();
            transactionManager.commit(status);
            return result;
        } catch (RuntimeException e) {
            // Roll back before rethrowing the original failure: a plain
            // TransactionTemplate.execute would surface UnexpectedRollbackException
            // and bury what actually went wrong.
            status.setRollbackOnly();
            transactionManager.rollback(status);
            throw e;
        }
    }

    /**
     * Runs the body inside a synthetic request scope, acting as a MANAGER.
     *
     * <p>The teller transfer limit reads the caller's roles from the
     * request-scoped {@link CallerContext}, and a seed run is not an HTTP
     * request, so without a bound request that context cannot resolve at all.
     * Rather than weaken or bypass the check for the seeder, the seeder satisfies
     * it the way a real call does — the authorisation rule stays where it is.
     */
    private <T> T inSyntheticRequestScope(Supplier<T> body) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(SyntheticRequest.create()));
        try {
            CallerContext caller = callerContext.getObject();
            caller.setUserId("seed-runner");
            caller.setRoles(new LinkedHashSet<>(Set.of("TELLER", "MANAGER")));
            return body.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /**
     * A request object that exists only to satisfy the request scope.
     *
     * <p>A dynamic proxy rather than an implementation of the interface: the
     * scope machinery never reads anything off the request, and the seeder has no
     * business answering questions about a request that never happened.
     */
    private static final class SyntheticRequest implements InvocationHandler {

        static HttpServletRequest create() {
            return (HttpServletRequest) Proxy.newProxyInstance(
                    SyntheticRequest.class.getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class},
                    new SyntheticRequest());
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "toString" -> "synthetic-seed-request";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> null;
            };
        }
    }
}
