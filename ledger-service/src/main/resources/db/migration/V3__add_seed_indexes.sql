-- Performance indexes for KPI Trends, dashboard charts, and feed queries
-- Added after the portfolio seed (50K+ journal rows) proved sequential scans were the bottleneck.

CREATE INDEX IF NOT EXISTS idx_event_store_occurred_at ON event_store (occurred_at);
CREATE INDEX IF NOT EXISTS idx_journal_entries_created_at ON journal_entries (created_at);
CREATE INDEX IF NOT EXISTS idx_saga_states_status_started ON saga_states (status, started_at);
CREATE INDEX IF NOT EXISTS idx_journal_entries_debit_created ON journal_entries (debit_account_id, created_at);
CREATE INDEX IF NOT EXISTS idx_journal_entries_credit_created ON journal_entries (credit_account_id, created_at);
