package att.ossama.ledgerservice.projection;

import att.ossama.ledgerservice.domain.AccountCreatedEvent;
import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.domain.MoneyCreditedEvent;
import att.ossama.ledgerservice.domain.MoneyDebitedEvent;
import att.ossama.ledgerservice.eventstore.EventStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CQRS read model. There is no account table holding a mutable balance:
 * balances and histories are derived from the event stream.
 *
 * <p>Deriving them is fine one account at a time — that is what the statement,
 * history and balance endpoints do, and what the transfer saga's funds check
 * does. It is not fine for the whole ledger at once: {@link #allAccounts()}
 * answered the account list by loading every event to collect the ids and then
 * replaying each account's stream, which on the portfolio ledger was 1,115
 * queries and ~102,000 JSON decodes per request. That is why the list is now
 * delegated to {@link AccountSummaries}, which asks the same question as one
 * aggregate.
 */
@Component
public class AccountProjection {

    private final EventStore eventStore;

    /**
     * The aggregate-backed list, where one is available.
     *
     * <p>Optional on purpose: the in-memory profile has no database to aggregate
     * in, and unit tests build this projection around a hand-written store. When
     * it is absent the list falls back to replay, which is correct, just slow —
     * so the fallback is a safety net rather than a path production takes.
     */
    @Autowired(required = false)
    private AccountSummaries summaries;

    public AccountProjection(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    /** Rebuild a single account's state from its full event history. */
    public AccountState rebuild(String accountId) {
        return fold(accountId, eventStore.eventsForAccount(accountId));
    }

    /**
     * All accounts, ordered by account id.
     *
     * <p>Answered by one aggregate where the SQL summaries are wired in, and by
     * replay otherwise. Either way the rows carry no event history: the list
     * response does not need it, and attaching it is what turned the response
     * body into a copy of the whole log.
     */
    public List<AccountState> allAccounts() {
        if (summaries != null) {
            return summaries.allAccounts();
        }
        Set<String> ids = new LinkedHashSet<>();
        eventStore.allEvents().forEach(e -> ids.add(e.aggregateId()));
        return ids.stream()
                .map(this::rebuild)
                .sorted(Comparator.comparing(AccountState::accountId))
                .toList();
    }

    /**
     * How many accounts exist, without rebuilding any of them.
     *
     * <p>Counts aggregates that were actually opened, so an aggregate whose
     * stream somehow lacks its {@code ACCOUNT_CREATED} event is not counted as an
     * account. Used by the seed runner to report what it wrote.
     */
    public long count() {
        return eventStore.allEvents().stream()
                .filter(e -> e instanceof AccountCreatedEvent)
                .map(Event::aggregateId)
                .distinct()
                .count();
    }

    public boolean exists(String accountId) {
        return eventStore.eventsForAccount(accountId).stream()
                .anyMatch(e -> e instanceof AccountCreatedEvent);
    }

    /** The current balance of an account, derived by replay. */
    public double balance(String accountId) {
        return fold(accountId, eventStore.eventsForAccount(accountId)).balance();
    }

    private AccountState fold(String accountId, List<Event> events) {
        double balance = 0;
        Long customerId = null;
        String holderName = null;

        for (Event event : events) {
            if (event instanceof AccountCreatedEvent created) {
                customerId = created.getCustomerId();
                holderName = created.getHolderName();
            } else if (event instanceof MoneyCreditedEvent credited) {
                balance += credited.getAmount();
            } else if (event instanceof MoneyDebitedEvent debited) {
                balance -= debited.getAmount();
            }
        }
        return new AccountState(accountId, customerId, holderName, balance, events);
    }
}
