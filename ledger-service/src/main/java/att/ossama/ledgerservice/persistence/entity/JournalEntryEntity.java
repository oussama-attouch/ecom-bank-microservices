package att.ossama.ledgerservice.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

/** Durable row for a double-entry journal entry. */
@Entity
@Table(
        name = "journal_entries",
        indexes = {
                @Index(name = "idx_journal_entries_transaction_id", columnList = "transaction_id"),
                @Index(name = "idx_journal_entries_debit_credit", columnList = "debit_account_id, credit_account_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class JournalEntryEntity implements Persistable<String> {

    /**
     * Application-assigned UUID, mirroring the domain {@code JournalEntry.id}, so
     * ids keep the same shape on the way back out of the database.
     */
    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(name = "debit_account_id", nullable = false)
    private String debitAccountId;

    @Column(name = "credit_account_id", nullable = false)
    private String creditAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "posted_by")
    private String postedBy;

    /**
     * Not persisted. Because the id is assigned by the application rather than
     * generated, Spring Data would otherwise treat a non-null id as an existing
     * row and issue a SELECT + merge. Implementing {@link Persistable} lets a
     * freshly built entity keep insert (persist) semantics.
     */
    @Transient
    private boolean isNewEntity = true;

    @Override
    public boolean isNew() {
        return isNewEntity;
    }

    /** Called by JPA once the row has been written or loaded. */
    @PostLoad
    @PostPersist
    public void markNotNew() {
        this.isNewEntity = false;
    }
}
