package att.ossama.ledgerservice.journal;

import att.ossama.ledgerservice.domain.JournalEntry;

import java.time.Instant;
import java.util.List;

/** Persistence seam for the journal; swap the in-memory impl for a DB later. */
public interface JournalEntryRepository {

    void save(JournalEntry entry);

    void saveAll(List<JournalEntry> entries);

    List<JournalEntry> findAll();

    List<JournalEntry> findByTransactionId(String transactionId);

    List<JournalEntry> findByAccount(String accountId);

    /**
     * Every entry posted at or before {@code cutoff}, newest first — the journal
     * as it stood at that instant.
     *
     * <p>The journal has no {@code occurred_at}: its time axis is
     * {@code createdAt}, which is the instant the posting was stamped with. For a
     * backfilled entry that is the reconstructed instant rather than the moment
     * the seeder ran, so filtering on it is what makes a historical snapshot read
     * correctly.
     *
     * <p>The default filters {@link #findAll()}, which is correct but reads the
     * whole table to answer a question about its prefix. The durable repository
     * overrides it with an indexed range; the in-memory one is already holding
     * every row, so for it the two are the same work.
     *
     * <p>{@code <=} inclusive, so a posting exactly at the instant asked about is
     * part of the state at that instant.
     */
    default List<JournalEntry> findAllBefore(Instant cutoff) {
        return findAll().stream()
                .filter(entry -> {
                    Instant at = entry.getCreatedAt();
                    return at != null && !at.isAfter(cutoff);
                })
                .toList();
    }
}
