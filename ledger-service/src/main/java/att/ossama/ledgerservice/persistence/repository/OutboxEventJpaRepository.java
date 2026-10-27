package att.ossama.ledgerservice.persistence.repository;

import att.ossama.ledgerservice.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the outbox table.
 *
 * <p>Bare on purpose: the claim query and the status counts are the relay's
 * contract and land with it. In the schema phase the interface exists so the
 * entity is mapped and validated, nothing more.
 */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, String> {
}
