package att.ossama.ledgerservice.persistence.repository;

import java.math.BigDecimal;

/**
 * One row of the account summary aggregate: the account's identity and its
 * balance, computed in the database.
 */
public interface AccountSummaryRow {

    String getAccountId();

    Long getCustomerId();

    String getHolderName();

    BigDecimal getBalance();
}
