package att.ossama.ledgerservice.web.dto;

public record TransactionRequest(
        String type,
        String accountId,
        String fromAccountId,
        String toAccountId,
        double amount,
        String description
) {
}
