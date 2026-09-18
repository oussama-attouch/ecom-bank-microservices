package att.ossama.ledgerservice.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Durable row in the append-only event log.
 *
 * One row per domain event. {@code sequenceNumber} is the position of the event
 * within its own aggregate (unique per {@code aggregateId}); the surrogate
 * {@code id} is the global position in the log.
 */
@Entity
@Table(
        name = "event_store",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_event_store_aggregate_sequence",
                columnNames = {"aggregate_id", "sequence_number"}
        ),
        indexes = @Index(
                name = "idx_event_store_aggregate_id",
                columnList = "aggregate_id"
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
