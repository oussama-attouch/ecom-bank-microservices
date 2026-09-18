package att.ossama.ledgerservice.persistence;

import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.persistence.repository.EventJpaRepository;
import att.ossama.ledgerservice.projection.AccountSummaries;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Answers the account list with one aggregate query.
 *
 * <p>Replaces a read that loaded every event to find the account ids and then
 * replayed each account's stream: 1,115 queries and ~102,000 JSON decodes per
 * request, about eleven seconds on the portfolio ledger, which blocked the whole
 * Command Center because its dashboard fetches this endpoint alongside the others.
 */
@Component
@Profile("!inmem")
public class JpaAccountSummaries implements AccountSummaries {

    private final EventJpaRepository events;

    public JpaAccountSummaries(EventJpaRepository events) {
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountState> allAccounts() {
        return events.accountSummaries().stream()
                .map(row -> new AccountState(
                        row.getAccountId(),
                        row.getCustomerId(),
                        row.getHolderName(),
                        row.getBalance() == null ? 0d : row.getBalance().doubleValue(),
                        // No history on a list row: see AccountSummaries.
                        List.of()))
                .toList();
    }
}
