CREATE TABLE journal_entries (
    id                VARCHAR(36) PRIMARY KEY,
    transaction_id    VARCHAR(255)   NOT NULL,
    debit_account_id  VARCHAR(255)   NOT NULL,
    credit_account_id VARCHAR(255)   NOT NULL,
    amount            NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    description       VARCHAR(500),
    created_at        TIMESTAMP      NOT NULL,
    posted_by         VARCHAR(255)
);

CREATE INDEX idx_journal_entries_transaction_id ON journal_entries (transaction_id);
CREATE INDEX idx_journal_entries_debit_credit   ON journal_entries (debit_account_id, credit_account_id);

CREATE TABLE saga_states (
    transaction_id         VARCHAR(255)   PRIMARY KEY,
    status                 VARCHAR(50)    NOT NULL,
    source_account_id      VARCHAR(255)   NOT NULL,
    destination_account_id VARCHAR(255)   NOT NULL,
    amount                 NUMERIC(19, 4) NOT NULL,
    error_message          VARCHAR(1000),
    started_at             TIMESTAMP,
    completed_at           TIMESTAMP
);

CREATE TABLE saga_steps (
    id          BIGSERIAL PRIMARY KEY,
    saga_id     VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(50),
    step_offset BIGINT,
    timestamp   TIMESTAMP,
    CONSTRAINT fk_saga_steps_saga FOREIGN KEY (saga_id) REFERENCES saga_states (transaction_id) ON DELETE CASCADE
);

CREATE INDEX idx_saga_steps_saga_id ON saga_steps (saga_id);
