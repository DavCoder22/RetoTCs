CREATE TABLE IF NOT EXISTS customers (
    id         UUID PRIMARY KEY,
    full_name  VARCHAR(150) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    segment    VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS accounts (
    id                  UUID PRIMARY KEY,
    customer_id         UUID NOT NULL REFERENCES customers (id),
    account_number      VARCHAR(34) NOT NULL UNIQUE,
    currency            VARCHAR(3)  NOT NULL,
    balance             NUMERIC(19, 2) NOT NULL DEFAULT 0,
    daily_transfer_limit NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_accounts_customer_id ON accounts (customer_id);

CREATE TABLE IF NOT EXISTS transactions (
    id                UUID PRIMARY KEY,
    type              VARCHAR(20) NOT NULL,
    amount            NUMERIC(19, 2) NOT NULL,
    currency          VARCHAR(3)  NOT NULL,
    debit_account_id  UUID REFERENCES accounts (id),
    credit_account_id UUID REFERENCES accounts (id),
    status            VARCHAR(20) NOT NULL,
    idempotency_key   VARCHAR(255),
    reference         VARCHAR(100),
    failure_reason    VARCHAR(500),
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_transactions_debit_account_id ON transactions (debit_account_id);
CREATE INDEX IF NOT EXISTS idx_transactions_credit_account_id ON transactions (credit_account_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_idempotency ON transactions (idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS ledger_entries (
    id             UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES transactions (id),
    account_id     UUID NOT NULL REFERENCES accounts (id),
    entry_type     VARCHAR(10) NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_id ON ledger_entries (account_id);

CREATE TABLE IF NOT EXISTS recommendations (
    id          UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers (id),
    type        VARCHAR(50) NOT NULL,
    payload     TEXT,
    status      VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_recommendations_customer_id ON recommendations (customer_id);

CREATE TABLE IF NOT EXISTS outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   VARCHAR(50) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT,
    status         VARCHAR(20) NOT NULL,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL,
    processed_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_status ON outbox_events (status);