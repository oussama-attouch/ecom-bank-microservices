package att.ossama.ledgerservice.account;

import att.ossama.ledgerservice.domain.AccountCreatedEvent;
import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.eventstore.EventStore;
import att.ossama.ledgerservice.feign.CustomerRestClient;
import att.ossama.ledgerservice.model.Customer;
import att.ossama.ledgerservice.projection.AccountProjection;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Opening accounts, which in this ledger means appending an
 * {@link AccountCreatedEvent} — there is no account table and no mutable row.
 *
 * <h2>Timestamps</h2>
 * The request path opens an account "now" from the injected {@link Clock}; the
 * explicit-instant overload opens it as of a past moment, which is what the
 * seeder needs to backfill two years of account openings. It also takes the
 * holder name directly, because a backfill must not call customer-service once
 * per account.
 */
@Service
public class AccountService {

    private final EventStore eventStore;
    private final AccountProjection projection;
    private final CustomerRestClient customerClient;
    private final Clock clock;

    public AccountService(EventStore eventStore,
                          AccountProjection projection,
                          CustomerRestClient customerClient,
                          Clock clock) {
        this.eventStore = eventStore;
        this.projection = projection;
        this.customerClient = customerClient;
        this.clock = clock;
    }

    /**
     * Opens an account for an existing e-commerce customer, resolving the holder
     * name from customer-service. A lookup failure is not fatal: the account is
     * still opened, under a placeholder name, exactly as before.
     */
    public AccountState openAccount(Long customerId) {
        return openAccount(customerId, resolveHolderName(customerId), clock.instant());
    }

    /** Opens an account as of {@code occurredAt}, under the given holder name. */
    public AccountState openAccount(Long customerId, String holderName, Instant occurredAt) {
        String accountId = "ACC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        AccountCreatedEvent event = new AccountCreatedEvent(
                UUID.randomUUID().toString(), occurredAt, accountId, customerId, holderName);
        eventStore.append(List.of(event), occurredAt);
        return projection.rebuild(accountId);
    }

    private String resolveHolderName(Long customerId) {
        if (customerId == null) {
            return null;
        }
        try {
            Customer customer = customerClient.getCustomer(customerId);
            return customer.getName();
        } catch (Exception e) {
            return "Customer " + customerId;
        }
    }
}
