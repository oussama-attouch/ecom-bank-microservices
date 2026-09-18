package att.ossama.ledgerservice.domain;

import java.time.Instant;

/** A single step executed (or compensated) as part of a transfer saga. */
public class SagaStep {

    private final String name;
    private final Instant timestamp;
    private final Long offset;
    private final String status;

    public SagaStep(String name, Long offset, String status) {
        this(name, Instant.now(), offset, status);
    }

    /**
     * Rehydrate a step that was persisted earlier, keeping its original
     * timestamp. Used by the durable saga store when rebuilding saga state.
     */
    public SagaStep(String name, Instant timestamp, Long offset, String status) {
        this.name = name;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.offset = offset;
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Long getOffset() {
        return offset;
    }

    public String getStatus() {
        return status;
    }
}
