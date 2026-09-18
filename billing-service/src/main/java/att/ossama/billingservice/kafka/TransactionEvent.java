package att.ossama.billingservice.kafka;

public record TransactionEvent(
        String transactionId,
        String type,
        String accountId,
        String fromAccountId,
        String toAccountId,
        double amount,
        String timestamp
) {
}
