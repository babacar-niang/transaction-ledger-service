-- V1__create_ledger_schema.sql

CREATE TABLE accounts (
    id          UUID PRIMARY KEY,
    owner_id    VARCHAR(255) NOT NULL,
    currency    VARCHAR(3) NOT NULL,
    type        VARCHAR(50) NOT NULL,
    status      VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_owner ON accounts(owner_id);
CREATE INDEX idx_accounts_status ON accounts(status);

CREATE TABLE transfers (
    id                    UUID PRIMARY KEY,
    debit_account_id      UUID NOT NULL REFERENCES accounts(id),
    credit_account_id     UUID NOT NULL REFERENCES accounts(id),
    amount                NUMERIC(19, 4) NOT NULL,
    currency              VARCHAR(3) NOT NULL,
    reference             VARCHAR(255),
    description           TEXT,
    status                VARCHAR(50) NOT NULL,
    reversed_transfer_id  UUID REFERENCES transfers(id),
    reversal_reason       TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transfers_debit  ON transfers(debit_account_id);
CREATE INDEX idx_transfers_credit ON transfers(credit_account_id);
CREATE INDEX idx_transfers_status ON transfers(status);

-- Immutable ledger entries — never updated or deleted
CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY,
    account_id      UUID NOT NULL REFERENCES accounts(id),
    transfer_id     UUID NOT NULL REFERENCES transfers(id),
    entry_type      VARCHAR(10) NOT NULL,   -- DEBIT or CREDIT
    amount          NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    balance_after   NUMERIC(19, 4) NOT NULL,
    reference       VARCHAR(255),
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- No UPDATE/DELETE on ledger_entries — immutability enforced at DB level
CREATE INDEX idx_entries_account    ON ledger_entries(account_id);
CREATE INDEX idx_entries_transfer   ON ledger_entries(transfer_id);
CREATE INDEX idx_entries_created_at ON ledger_entries(created_at);
CREATE INDEX idx_entries_type       ON ledger_entries(account_id, entry_type);
