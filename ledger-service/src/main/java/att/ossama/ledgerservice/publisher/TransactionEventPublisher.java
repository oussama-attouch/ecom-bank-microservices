package att.ossama.ledgerservice.publisher;

import att.ossama.ledgerservice.domain.TransactionRecord;

/**
 * Publishes committed transactions to downstream processors.
 *
 * Two methods:
 *  - publish()       best-effort (used by the single credit/debit /api/transactions path)
 *  - publishStrict() throws on failure (used by the transfer saga so it can compensate)
 *
 * Implementations: HTTP (dev/fallback) and Kafka (production, exactly-once).
 */
public interface TransactionEventPublisher {

    void publish(TransactionRecord record);

    void publishStrict(TransactionRecord record);
}
