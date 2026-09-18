package att.ossama.billingservice.repository;

import att.ossama.billingservice.entities.ArchivedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

/**
 * Persistence for archived ledger transactions. Exposed only through the
 * TransactionProcessor controller, not Spring Data REST.
 */
@RepositoryRestResource(exported = false)
public interface ArchivedTransactionRepository extends JpaRepository<ArchivedTransaction, Long> {
}
