package att.ossama.ledgerservice.journal;

import att.ossama.ledgerservice.domain.JournalEntry;

public record StatementLine(JournalEntry entry, double runningBalance) {
}
