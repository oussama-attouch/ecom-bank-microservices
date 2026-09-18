package att.ossama.ledgerservice.persistence.repository;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The header of a saga, without its steps.
 *
 * <p>A projection rather than the entity: the saga list needs a few scalar fields
 * per saga, and loading the entities pulled every step with them — 2,765 sagas
 * meant 11,334 extra rows on each call, which is most of what made that endpoint
 * take seconds. Selecting the columns instead keeps the steps out of the query
 * entirely; the detail view asks for them explicitly, by id.
 */
public interface SagaHeader {

    String getTransactionId();

    String getStatus();

    String getSourceAccountId();

    String getDestinationAccountId();

    BigDecimal getAmount();

    Instant getStartedAt();

    Instant getCompletedAt();

    String getErrorMessage();
}
