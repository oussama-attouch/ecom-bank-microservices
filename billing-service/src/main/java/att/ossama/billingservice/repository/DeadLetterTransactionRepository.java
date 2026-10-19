package att.ossama.billingservice.repository;

import att.ossama.billingservice.entities.DeadLetterTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

/**
 * Persistence for poison messages. Deliberately not exported over Spring Data
 * REST: a dead-letter payload is raw failure evidence and must not be readable
 * through the generic /api surface.
 */
@RepositoryRestResource(exported = false)
public interface DeadLetterTransactionRepository extends JpaRepository<DeadLetterTransaction, Long> {
}
