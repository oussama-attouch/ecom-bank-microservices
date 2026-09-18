package att.ossama.ledgerservice.seed;

import att.ossama.ledgerservice.domain.AccountCreatedEvent;
import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.MoneyCreditedEvent;
import att.ossama.ledgerservice.domain.MoneyDebitedEvent;
import att.ossama.ledgerservice.eventstore.EventStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An {@link EventStore} that keeps a live in-memory view of balances and account
 * existence alongside the durable log.
 *
 * <p>Why this exists: {@link att.ossama.ledgerservice.projection.AccountProjection}
 * answers "does this account exist" and "what is its balance" by replaying events
 * from the store, which is the right call for a read model of a ledger whose
 * entries are spread across a handful of requests. A backfill is the opposite
 * shape of workload — tens of thousands of sequential writes, each one asking
 * those same two questions about accounts that were all created moments earlier —
 * and replaying the whole log per question turns a linear job into a quadratic
 * one. On the portfolio seed that is the difference between minutes and hours.
 *
 * <p>So the index is maintained as events go in: one pass over whatever the log
 * already held when the seed started (an additive seed must not lose sight of
 * existing accounts), then O(1) updates per appended event. The durable store
 * still receives every event exactly as before — this changes how reads are
 * answered during the seed, not what is written.
 *
 * <p>Everything here is derived from the log in the same way the projection
 * derives it, so the answers are identical; only the timing differs.
 */
final class SeedLedger implements EventStore {

    private final EventStore delegate;
    private final Map<String, Double> balances = new HashMap<>();
    private final Set<String> accounts = new java.util.HashSet<>();

    SeedLedger(EventStore delegate) {
        this.delegate = delegate;
        // Start from whatever is already there: the seed is additive, so an
        // account created before this run must still be visible (and spendable).
        delegate.allEvents().forEach(this::index);
    }

    @Override
    public List<Event> append(List<Event> events) {
        return delegate.append(events);
    }

    @Override
    public List<Event> append(List<Event> events, Instant occurredAt) {
        List<Event> appended = delegate.append(events, occurredAt);
        appended.forEach(this::index);
        return appended;
    }

    @Override
    public List<Event> allEvents() {
        return delegate.allEvents();
    }

    @Override
    public List<Event> eventsForAccount(String accountId) {
        return delegate.eventsForAccount(accountId);
    }

    @Override
    public long count() {
        return delegate.count();
    }

    @Override
    public long nextOffset() {
        return delegate.nextOffset();
    }

    /** True when the account has been opened, by this run or before it. */
    boolean exists(String accountId) {
        return accounts.contains(accountId);
    }

    /** Current balance, or zero for an account that does not exist. */
    double balance(String accountId) {
        return balances.getOrDefault(accountId, 0.0);
    }

    /** How many accounts the index knows about. */
    int accountCount() {
        return accounts.size();
    }

    /**
     * Folds one event into the index, mirroring the projection's own rules: an
     * account exists once it is created, credits add, debits subtract, and
     * anything else is not this index's business.
     */
    private void index(Event event) {
        if (event instanceof AccountCreatedEvent created) {
            accounts.add(created.aggregateId());
            balances.putIfAbsent(created.aggregateId(), 0.0);
        } else if (event instanceof MoneyCreditedEvent credited) {
            balances.merge(credited.getAccountId(), credited.getAmount(), Double::sum);
        } else if (event instanceof MoneyDebitedEvent debited) {
            balances.merge(debited.getAccountId(), -debited.getAmount(), Double::sum);
        }
    }

    /** Account ids known to the index, for callers that need to iterate them. */
    List<String> accountIds() {
        return new ArrayList<>(accounts);
    }
}
