package att.ossama.ledgerservice.domain;

/**
 * Normalised view of a committed transaction, published to downstream
 * processors (currently billing-service over HTTP; later a Kafka topic).
 *
 * timestamp is an ISO-8601 string so the record serialises cleanly over the
 * wire regardless of the consumer's date/time handling.
 */
public record TransactionRecord(
        String transactionId,
        String type,
        String accountId,
        String fromAccountId,
        String toAccountId,
        double amount,
        String timestamp
) {
}
