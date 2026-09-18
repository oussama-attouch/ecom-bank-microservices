package att.ossama.ledgerservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Immutable-identity, mutable-progress state of a transfer saga. */
public class SagaState {

    private final String transactionId;
    private volatile SagaStatus status;
    private final String sourceAccountId;
    private final String destinationAccountId;
    private final double amount;
    private final List<SagaStep> steps = new CopyOnWriteArrayList<>();
    private volatile String errorMessage;
    private final Instant startedAt;
    private volatile Instant completedAt;

    public SagaState(String transactionId, String sourceAccountId, String destinationAccountId, double amount) {
        this(transactionId, sourceAccountId, destinationAccountId, amount, null);
    }

    /**
     * Rehydrate a persisted saga, preserving its original start time. Used by the
     * durable saga store; {@code null} falls back to "now" for a brand-new saga.
     */
    public SagaState(String transactionId, String sourceAccountId, String destinationAccountId,
                     double amount, Instant startedAt) {
        this.transactionId = transactionId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.startedAt = startedAt != null ? startedAt : Instant.now();
        this.status = SagaStatus.COMPLETED;
    }

    public void addStep(String name, Long offset, String status) {
        this.steps.add(new SagaStep(name, offset, status));
    }

    /**
     * Record a step that happened at {@code occurredAt} rather than now.
     *
     * <p>The orchestrator passes the instant its timeline assigns to the step, so
     * a backfilled saga reads as a sequence of events spread over the few hundred
     * milliseconds a real one takes, instead of every step landing on the instant
     * the seeder ran.
     */
    public void addStep(String name, Instant occurredAt, Long offset, String status) {
        this.steps.add(new SagaStep(name, occurredAt, offset, status));
    }

    /** Rehydrate a previously persisted step, preserving its original timestamp. */
    public void addStep(SagaStep step) {
        this.steps.add(step);
    }

    public void finish(SagaStatus status, String errorMessage) {
        finish(status, errorMessage, Instant.now());
    }

    /**
     * Terminal transition stamped with an explicit instant.
     *
     * <p>The orchestrator passes the end of the run's timeline rather than the
     * wall clock, so a reconstructed saga's duration is the sum of its step
     * delays instead of "however long ago the seeder happened to run".
     */
    public void finish(SagaStatus status, String errorMessage, Instant completedAt) {
        this.status = status;
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
    }

    /**
     * Restore persisted terminal state without resetting {@code completedAt} to
     * "now" — unlike {@link #finish}, which stamps the moment of completion.
     */
    public void restoreTerminalState(SagaStatus status, String errorMessage, Instant completedAt) {
        this.status = status;
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public SagaStatus getStatus() {
        return status;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getDestinationAccountId() {
        return destinationAccountId;
    }

    public double getAmount() {
        return amount;
    }

    public List<SagaStep> getSteps() {
        return steps;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
