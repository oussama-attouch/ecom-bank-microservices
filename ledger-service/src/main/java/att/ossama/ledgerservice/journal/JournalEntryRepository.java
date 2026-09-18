package att.ossama.ledgerservice.journal;

import att.ossama.ledgerservice.domain.JournalEntry;

import java.util.List;

/** Persistence seam for the journal; swap the in-memory impl for a DB later. */
public interface JournalEntryRepository {

    void save(JournalEntry entry);

    void saveAll(List<JournalEntry> entries);

    List<JournalEntry> findAll();

    List<JournalEntry> findByTransactionId(String transactionId);

    List<JournalEntry> findByAccount(String accountId);

}
