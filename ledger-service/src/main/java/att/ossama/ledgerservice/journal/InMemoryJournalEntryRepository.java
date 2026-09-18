package att.ossama.ledgerservice.journal;

import att.ossama.ledgerservice.domain.JournalEntry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory journal repository (fallback).
 *
 * Only active with the "inmem" profile; otherwise {@link
 * att.ossama.ledgerservice.persistence.JpaJournalEntryRepository} is used.
 */
@Component
@Profile("inmem")
public class InMemoryJournalEntryRepository implements JournalEntryRepository {

    private final List<JournalEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void save(JournalEntry entry) {
        entries.add(entry);
    }

    @Override
    public void saveAll(List<JournalEntry> list) {
        entries.addAll(list);
    }

    @Override
    public List<JournalEntry> findAll() {
        return List.copyOf(entries);
    }

    @Override
    public List<JournalEntry> findByTransactionId(String transactionId) {
        return entries.stream()
                .filter(e -> transactionId.equals(e.getTransactionId()))
                .toList();
    }

    @Override
    public List<JournalEntry> findByAccount(String accountId) {
        return entries.stream()
                .filter(e -> accountId.equals(e.getDebitAccountId()) || accountId.equals(e.getCreditAccountId()))
                .sorted(Comparator.comparing(JournalEntry::getCreatedAt))
                .toList();
    }

}
