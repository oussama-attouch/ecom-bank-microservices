package att.ossama.ledgerservice.admin;

import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.projection.AccountProjection;
import att.ossama.ledgerservice.projection.AccountSummaries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Replays the event log and checks the read models against it.
 *
 * <h2>Why this does not truncate anything</h2>
 * A "rebuild the projections" operation normally means: empty the materialized
 * read-model tables, fold the event store back into them, report the counts. This
 * service has no such tables. The whole read side is computed on demand —
 * {@link AccountSummaries} answers the account list with one {@code GROUP BY}
 * over {@code event_store}, and {@code JpaLedgerAggregates} answers every KPI the
 * same way. There is nothing to empty, and the four tables that do exist are
 * either the source of truth ({@code event_store}, append-only) or state that the
 * log cannot regenerate at all: {@code journal_entries} and
 * {@code saga_states}/{@code saga_steps} are written by the write path, and the
 * event log holds only {@code ACCOUNT_CREATED}, {@code MONEY_CREDITED} and
 * {@code MONEY_DEBITED} — no saga transitions, no step names, no
 * {@code posted_by}, no {@code COMPENSATION} descriptions. Truncating those would
 * destroy data, not rebuild it.
 *
 * <h2>What it does instead</h2>
 * It performs the half of the operation that is real and useful here: an
 * independent replay of the log, folded by the same
 * {@link AccountProjection#rebuildAllFrom(List)} the snapshot path uses, checked
 * against the read models the dashboard is actually serving. On a service whose
 * read side is always derived, "are the projections correct?" is the only
 * question a rebuild can answer — and it is the question worth asking, because
 * the two sides do genuinely different arithmetic: the replay sums {@code double}
 * in Java, the aggregate sums Postgres {@code numeric} over extracted JSON. A
 * disagreement means the dashboard's numbers are wrong.
 *
 * <p>Read-only, in the strong sense: it truncates nothing, writes nothing, and
 * cannot affect the money path or any invariant.
 */
@Component
@Profile("!inmem")
public class ProjectionRebuilder {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRebuilder.class);

    /**
     * Balances are compared with a tolerance rather than exactly.
     *
     * <p>The two sides are not the same arithmetic. The replay accumulates
     * {@code double} in {@link AccountProjection}; the aggregate sums Postgres
     * {@code numeric} and the result crosses into Java through
     * {@code BigDecimal.doubleValue()} in {@code JpaAccountSummaries}. Demanding
     * bit equality of those two would report differences that are artefacts of
     * the comparison rather than disagreements about the ledger. A millionth of a
     * unit is far below any real posting and far above the drift this can
     * accumulate over 51,000 events.
     */
    private static final double BALANCE_TOLERANCE = 1e-6;

    /**
     * How many disagreements are returned in the body.
     *
     * <p>{@code mismatchCount} carries the real total, so capping the list cannot
     * make a divergent ledger look merely untidy — but a body holding one string
     * per account per field is not something an operator can read, and the first
     * twenty are enough to recognise the shape of the problem.
     */
    private static final int MAX_REPORTED_MISMATCHES = 20;

    private final EventStore eventStore;
    private final AccountProjection projection;
    private final AccountSummaries summaries;
    private final Clock clock;

    public ProjectionRebuilder(EventStore eventStore,
                               AccountProjection projection,
                               AccountSummaries summaries,
                               Clock clock) {
        this.eventStore = eventStore;
        this.projection = projection;
        this.summaries = summaries;
        this.clock = clock;
    }

    /**
     * Fold the whole log, compare the result against the live read models, and
     * report what happened.
     *
     * <p>{@code REPEATABLE_READ} is not decoration. Postgres defaults to
     * {@code READ COMMITTED}, where a multi-second replay running alongside a
     * live append can fold a log that already includes events the comparison
     * query has not seen yet — and report a divergence that never existed. Under
     * {@code REPEATABLE_READ} Postgres takes a snapshot, so the replay and the
     * aggregate both read one instant of the log and the verdict is about the
     * ledger rather than about the timing. Every read below joins this
     * transaction (REQUIRED propagation), so they share that one snapshot.
     *
     * <p>Read-only, so the stricter isolation costs nothing: no write conflicts
     * are possible, and a serialization failure needs one.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectionRebuildReport rebuild() {
        long startedAt = System.nanoTime();
        // Truncated to milliseconds: the platform clock reports finer than that,
        // and a timestamp with nine decimal places is noise in an API field whose
        // whole job is to say roughly when the replay ran.
        Instant rebuiltAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        List<Event> events = eventStore.allEvents();
        List<AccountState> replayed = projection.rebuildAllFrom(events);

        // The live read models, read directly rather than through
        // AccountProjection.allAccounts(). That method answers from AccountSummaries
        // when they are wired in and falls back to replaying when they are not, so
        // verifying through it would compare a replay against itself whenever the
        // summaries are absent — and report agreement unconditionally, which is
        // exactly the failure this check exists to catch. Injecting the aggregate
        // makes the comparison what it claims to be.
        List<AccountState> live = summaries.allAccounts();

        List<String> mismatches = compare(replayed, live);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        boolean consistent = mismatches.isEmpty();

        if (consistent) {
            log.info("Projection replay verified: {} events folded into {} accounts, matching the live "
                     + "read models in {}ms", events.size(), replayed.size(), elapsedMs);
        } else {
            log.warn("Projection replay diverged from the live read models: {} disagreement(s) over {} "
                     + "replayed accounts in {}ms — first: {}",
                    mismatches.size(), replayed.size(), elapsedMs, mismatches.get(0));
        }

        return new ProjectionRebuildReport(
                events.size(),
                elapsedMs,
                rebuiltAt,
                replayed.size(),
                live.size(),
                consistent,
                mismatches.size(),
                List.copyOf(mismatches.subList(0, Math.min(MAX_REPORTED_MISMATCHES, mismatches.size()))));
    }

    /**
     * Every way the replay and the aggregate disagree about an account.
     *
     * <p>Both sides are keyed by account id and compared as sets as well as
     * field by field: an account the replay derives but the aggregate does not
     * return is as much a divergence as a balance that differs, and it is the
     * kind a field-by-field walk over one side would silently miss. Both reads
     * group the same log by {@code aggregate_id}, so the two sets are expected to
     * be identical — including the aggregates with no creation event, which
     * {@code accountSummaries()} documents as deliberately present with null
     * identity fields.
     */
    private static List<String> compare(List<AccountState> replayed, List<AccountState> live) {
        Map<String, AccountState> byAccountId = new LinkedHashMap<>(live.size());
        for (AccountState state : live) {
            byAccountId.put(state.accountId(), state);
        }

        List<String> mismatches = new ArrayList<>();
        Set<String> seenInReplay = new LinkedHashSet<>();

        for (AccountState derived : replayed) {
            seenInReplay.add(derived.accountId());
            AccountState served = byAccountId.get(derived.accountId());
            if (served == null) {
                mismatches.add(derived.accountId() + ": derived by the replay, absent from the read model");
                continue;
            }
            if (Math.abs(derived.balance() - served.balance()) > BALANCE_TOLERANCE) {
                mismatches.add(derived.accountId() + ": balance " + derived.balance()
                               + " by replay, " + served.balance() + " by the read model");
            }
            if (!Objects.equals(derived.customerId(), served.customerId())) {
                mismatches.add(derived.accountId() + ": customerId " + derived.customerId()
                               + " by replay, " + served.customerId() + " by the read model");
            }
            if (!Objects.equals(derived.holderName(), served.holderName())) {
                mismatches.add(derived.accountId() + ": holderName " + derived.holderName()
                               + " by replay, " + served.holderName() + " by the read model");
            }
        }

        for (AccountState served : live) {
            if (!seenInReplay.contains(served.accountId())) {
                mismatches.add(served.accountId() + ": served by the read model, absent from the replay");
            }
        }

        return mismatches;
    }
}
