package att.ossama.ledgerservice.seed;

import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.projection.AccountProjection;
import att.ossama.ledgerservice.saga.SagaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds historical data on startup, so a checkout has a Command Center with a
 * populated dashboard rather than an empty one.
 *
 * <h2>Opting in twice</h2>
 * This needs both the {@code seed} profile and {@code ledger.seed.enabled=true}.
 * The profile alone would be enough to run it, but the flag makes the intent
 * explicit at the call site and keeps an accidental
 * {@code --spring.profiles.active=seed} from writing to whatever database happens
 * to be configured.
 *
 * <pre>
 *   # a small dataset, for a working dashboard
 *   java -jar ledger-service.jar \
 *        --spring.profiles.active=seed --ledger.seed.enabled=true
 *
 *   # the portfolio dataset: 500 customers, 24 months, ~40k transactions
 *   java -jar ledger-service.jar \
 *        --spring.profiles.active=seed --ledger.seed.enabled=true --ledger.seed.mode=portfolio
 * </pre>
 *
 * <h2>Idempotency</h2>
 * The guard is a size check: a store that already holds a real ledger is left
 * alone. It is deliberately a threshold rather than an exact count — the point is
 * "this database has been used", not "this database has exactly N events". The
 * portfolio seed is expected to be run against a wiped database, which is the
 * only way its date range and volumes come out as designed.
 */
@Component
@Profile("seed")
@ConditionalOnProperty(name = "ledger.seed.enabled", havingValue = "true")
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    /** Above this, the store looks like a real ledger rather than a seed target. */
    static final long EVENT_THRESHOLD = 1_000;

    static final String MODE_SMALL = "small";
    static final String MODE_PORTFOLIO = "portfolio";

    private final LedgerSeeder seedService;
    private final EventStore eventStore;
    private final AccountProjection accountProjection;
    private final SagaRepository sagaRepository;
    private final String mode;
    private final int customers;
    private final int transactions;

    public SeedRunner(LedgerSeeder seedService,
                      EventStore eventStore,
                      AccountProjection accountProjection,
                      SagaRepository sagaRepository,
                      @Value("${ledger.seed.mode:small}") String mode,
                      @Value("${ledger.seed.customers:500}") int customers,
                      @Value("${ledger.seed.transactions:40000}") int transactions) {
        this.seedService = seedService;
        this.eventStore = eventStore;
        this.accountProjection = accountProjection;
        this.sagaRepository = sagaRepository;
        this.mode = mode;
        this.customers = customers;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        long existing = eventStore.count();
        if (existing > EVENT_THRESHOLD) {
            log.warn("Skipping seed — {} events already in store (threshold {})", existing, EVENT_THRESHOLD);
            return;
        }

        log.info("Seeding in {} mode", mode);
        long startedAt = System.nanoTime();
        LedgerSeeder.SeedSummary summary = switch (mode.toLowerCase()) {
            case MODE_PORTFOLIO -> seedService.seedPortfolio(customers, transactions);
            case MODE_SMALL -> seedService.seedSmall();
            default -> throw new IllegalArgumentException(
                    "Unknown ledger.seed.mode: '" + mode + "' (expected '" + MODE_SMALL + "' or '"
                            + MODE_PORTFOLIO + "')");
        };

        log.info("Seed complete: {} events, {} accounts, {} sagas ({} customers seeded, {} transactions in {})",
                eventStore.count(), accountProjection.count(), sagaRepository.count(),
                summary.customers(), summary.transactions(), elapsed(System.nanoTime() - startedAt));
    }

    /** Seconds to one decimal: a small seed takes 3s and a portfolio seed takes minutes. */
    private static String elapsed(long nanos) {
        return String.format("%.1fs", nanos / 1_000_000_000.0);
    }
}
