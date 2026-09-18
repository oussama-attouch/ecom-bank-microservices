package att.ossama.ledgerservice.web.dto;

public record TransferRequest(
        String sourceAccountId,
        String destinationAccountId,
        double amount,
        String transactionId
) {
}
