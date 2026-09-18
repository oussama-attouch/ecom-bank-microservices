package att.ossama.ledgerservice.journal;

public record TrialBalance(double totalDebits, double totalCredits, boolean balanced) {
}
