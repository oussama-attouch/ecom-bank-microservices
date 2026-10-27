-- Transactional outbox (specs/003-outbox.md §2).
--
-- One row per event the ledger intends to publish. The ARCHIVE step commits its
-- row in the same transaction as the outbox insert, and a separate relay
-- publishes the row afterwards, so a broker outage can no longer lose an event
-- the ledger has already posted.
--
-- V4 was free when this was written: the tree's maximum was V3
-- (V1__create_event_store, V2__create_journal_and_saga_tables,
-- V3__add_seed_indexes) — listed, not assumed.
--
-- payload is TEXT, not jsonb, on purpose. AC-2 compares the bytes on the topic
-- against the exact output of KafkaTransactionEventPublisher's ObjectMapper;
-- jsonb reorders keys and normalises whitespace, so at least some records would
-- publish differently from what the old publisher sent. The column stores the
-- message body verbatim and the relay sends it unchanged, which is what makes
-- byte identity a property of the storage form rather than of two serializers
-- agreeing.

CREATE TABLE outbox_events (
    id              VARCHAR(36)  PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    published_at    TIMESTAMP,
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT,
    parked_at       TIMESTAMP,
    next_attempt_at TIMESTAMP,
    CONSTRAINT ck_outbox_attempt_count_non_negative
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_not_both_published_and_parked
        CHECK (published_at IS NULL OR parked_at IS NULL)
);

CREATE INDEX idx_outbox_pending
    ON outbox_events (created_at)
    WHERE published_at IS NULL AND parked_at IS NULL;

CREATE INDEX idx_outbox_aggregate_created
    ON outbox_events (aggregate_id, created_at);

CREATE INDEX idx_outbox_created_at
    ON outbox_events (created_at DESC);
