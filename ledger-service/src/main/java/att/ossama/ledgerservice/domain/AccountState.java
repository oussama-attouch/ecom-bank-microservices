package att.ossama.ledgerservice.domain;

import java.util.List;

/**
 * Read-side projection of an account, rebuilt from its event stream.
 * Accounts are never mutated directly; this is purely derived state.
 */
public record AccountState(
        String accountId,
        Long customerId,
        String holderName,
        double balance,
        List<Event> history
) {
}
