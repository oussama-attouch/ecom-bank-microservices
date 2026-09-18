package att.ossama.ledgerservice.journal;

import java.util.List;

public record AccountStatement(String accountId, List<StatementLine> lines, double finalBalance) {
}
