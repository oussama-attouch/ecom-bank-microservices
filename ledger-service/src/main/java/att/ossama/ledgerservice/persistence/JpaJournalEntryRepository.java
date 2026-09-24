package att.ossama.ledgerservice.persistence;

import att.ossama.ledgerservice.domain.JournalEntry;
import att.ossama.ledgerservice.journal.JournalEntryRepository;
import att.ossama.ledgerservice.persistence.entity.JournalEntryEntity;
import att.ossama.ledgerservice.persistence.repository.JournalEntryJpaRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Durable journal repository backed by Postgres.
 *
 * The table uses the domain's UUID as its primary key, so ids round-trip
 * unchanged between the API and the database.
 */
@Component
@Profile("!inmem")
public class JpaJournalEntryRepository implements JournalEntryRepository {

    private final JournalEntryJpaRepository repository;

    public JpaJournalEntryRepository(JournalEntryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(JournalEntry entry) {
        repository.save(toEntity(entry));
    }

    @Override
    @Transactional
    public void saveAll(List<JournalEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        repository.saveAll(entries.stream().map(this::toEntity).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalEntry> findAll() {
        // Newest first. The endpoint that serves the Command Center's feed pages
        // this list, so the order decides what the feed and the 7-day charts see:
        // ascending returned the *oldest* 200 of 49,911 rows, which put the whole
        // visible window back in 2024 and left the last seven days empty.
        //
        // Nothing depends on the ascending order: the trial balance sums the rows
        // (order-independent), the account statement reads a different query, and
        // the Journal Explorer sorts client-side.
        return repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt", "id")).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalEntry> findByTransactionId(String transactionId) {
        return repository.findByTransactionId(transactionId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalEntry> findByAccount(String accountId) {
        return repository.findByDebitAccountIdOrCreditAccountId(accountId, accountId).stream()
                .sorted(Comparator.comparing(JournalEntryEntity::getCreatedAt))
                .map(this::toDomain)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overridden rather than left to the seam's filter-{@code findAll()} default:
     * this is an index range on {@code created_at} that never reads past the
     * cutoff, where the default would load all 49,911 postings to answer a
     * question about their prefix — on every step of a scrubber drag.
     */
    @Override
    @Transactional(readOnly = true)
    public List<JournalEntry> findAllBefore(Instant cutoff) {
        return repository.findByCreatedAtLessThanEqualOrderByCreatedAtDescIdDesc(cutoff).stream()
                .map(this::toDomain)
                .toList();
    }


    /**
     * Builds a brand-new entity (isNewEntity left at its default {@code true}) so
     * Spring Data issues an INSERT rather than a SELECT + merge.
     */
    private JournalEntryEntity toEntity(JournalEntry entry) {
        JournalEntryEntity entity = new JournalEntryEntity();
        entity.setId(entry.getId());
        entity.setTransactionId(entry.getTransactionId());
        entity.setDebitAccountId(entry.getDebitAccountId());
        entity.setCreditAccountId(entry.getCreditAccountId());
        entity.setAmount(BigDecimal.valueOf(entry.getAmount()));
        entity.setCurrency(entry.getCurrency());
        entity.setDescription(entry.getDescription());
        entity.setCreatedAt(entry.getCreatedAt());
        entity.setPostedBy(entry.getPostedBy());
        return entity;
    }

    private JournalEntry toDomain(JournalEntryEntity entity) {
        return new JournalEntry(
                entity.getId(),
                entity.getTransactionId(),
                entity.getDebitAccountId(),
                entity.getCreditAccountId(),
                entity.getAmount() == null ? 0d : entity.getAmount().doubleValue(),
                entity.getCurrency(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getPostedBy()
        );
    }
}
