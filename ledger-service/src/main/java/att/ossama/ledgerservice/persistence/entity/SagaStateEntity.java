package att.ossama.ledgerservice.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Durable row for a transfer saga; the transaction id is the business key. */
@Entity
@Table(name = "saga_states")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SagaStateEntity {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    /** Needed to rehydrate the domain saga; the API exposes both endpoints. */
    @Column(name = "source_account_id", nullable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", nullable = false)
    private String destinationAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Steps are loaded on demand, not with every saga.
     *
     * <p>The saga list reads a few scalar fields per saga and none of the steps;
     * fetching them eagerly meant the list pulled 11,334 extra rows to render
     * 2,765 summaries. The detail query joins them explicitly, and the
     * orchestrator's own lookups use it too, so nothing that needs steps goes
     * without.
     */
    @OneToMany(
            mappedBy = "saga",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<SagaStepEntity> steps = new ArrayList<>();
}
