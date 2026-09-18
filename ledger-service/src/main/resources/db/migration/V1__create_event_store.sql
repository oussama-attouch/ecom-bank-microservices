CREATE TABLE event_store (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_id    VARCHAR(255) NOT NULL,
    sequence_number BIGINT       NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    payload         TEXT         NOT NULL,
    occurred_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uq_event_store_aggregate_sequence UNIQUE (aggregate_id, sequence_number)
);
CREATE INDEX idx_event_store_aggregate_id ON event_store (aggregate_id);
CREATE INDEX idx_event_store_event_type ON event_store (event_type);
