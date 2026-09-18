package att.ossama.ledgerservice.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Durable row for one executed (or compensated) saga step. */
@Entity
@Table(
        name = "saga_steps",
        indexes = @Index(name = "idx_saga_steps_saga_id", columnList = "saga_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SagaStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", length = 50)
    private String status;

    /** Column renamed from "offset" (a PostgreSQL reserved word) to step_offset. */
    @Column(name = "step_offset")
    private Long offset;

    @Column(name = "timestamp")
    private Instant timestamp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "saga_id")
    private SagaStateEntity saga;
}
