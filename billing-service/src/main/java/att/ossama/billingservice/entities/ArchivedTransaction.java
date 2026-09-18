package att.ossama.billingservice.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable archive of a committed ledger transaction, received by the
 * TransactionProcessor from ledger-service. Stored as-is; never mutated.
 */
@Entity
@Table(name = "archived_transactions")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class ArchivedTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String transactionId;
    private String type;          // CREDIT | DEBIT | TRANSFER
    private String accountId;
    private String fromAccountId;
    private String toAccountId;
    private double amount;
    private String timestamp;     // ISO-8601 string from the ledger
}
