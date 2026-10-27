package att.ossama.ledgerservice.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Durable row of the transactional outbox.
 *
 * <p>One row per event the write path intends to publish. {@code payload} is the
 * exact message body, stored verbatim so the relay can send it unchanged — the
 * column exists to hold bytes, not to be queried as JSON.
 *
 * <p>Append-only apart from the publish bookkeeping columns
 * ({@code publishedAt}, {@code attemptCount}, {@code lastError},
 * {@code parkedAt}, {@code nextAttemptAt}), which the relay writes as it
 * publishes. A published row is never deleted, and the {@code id} is supplied by
 * the caller as a UUID string before commit rather than generated here, so the
 * saga knows the row's identity before its transaction ends.
 *
 * <p>Not a dead-letter table: a row that exhausts its attempts stays in place
 * with {@code parkedAt} set, so parked events remain readable in the same list
 * and the same status query as everything else.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "aggregate_type", length = 64, nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 255, nullable = false)
    private String aggregateId;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "parked_at")
    private Instant parkedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
}
