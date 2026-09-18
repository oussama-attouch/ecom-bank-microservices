package att.ossama.ledgerservice.persistence.repository;

import java.math.BigDecimal;

/**
 * One row of a per-account money aggregate: which account, and how much its
 * balance was or moved by.
 *
 * <p>Used twice, with the same shape both times — the balance a group of events
 * adds up to as of an instant, and the movement a window of them adds up to —
 * because the concentration KPI needs the second to reconstruct the first at
 * instants it did not read directly.
 */
public interface AccountBalanceRow {

    String getAccountId();

    BigDecimal getBalance();
}
