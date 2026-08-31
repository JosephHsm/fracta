-- 원장 (FSD §5.1, §6.4, §8.2). ledger_transaction은 append-only — INSERT만 허용한다.
-- owner_id의 investor FK는 TODO(phase-3)에서 추가한다 (investor 테이블이 아직 없다).

CREATE TABLE ledger_transaction (
    seq           BIGSERIAL    PRIMARY KEY,
    tx_type       VARCHAR(10)  NOT NULL CHECK (tx_type IN ('ISSUE', 'TRANSFER', 'BURN', 'LOCK', 'UNLOCK')),
    token_symbol  VARCHAR(50)  NOT NULL,
    from_owner_id BIGINT,
    to_owner_id   BIGINT,
    units         BIGINT       NOT NULL CHECK (units > 0),
    ref_type      VARCHAR(20)  NOT NULL CHECK (ref_type IN ('SUBSCRIPTION', 'EXECUTION', 'ADMIN')),
    ref_id        VARCHAR(100),
    created_at    TIMESTAMPTZ  NOT NULL,
    prev_hash     CHAR(64)     NOT NULL,
    curr_hash     CHAR(64)     NOT NULL UNIQUE
);

CREATE INDEX idx_ledger_tx_symbol ON ledger_transaction (token_symbol);
CREATE INDEX idx_ledger_tx_symbol_type ON ledger_transaction (token_symbol, tx_type);

-- append-only 강제: 애플리케이션 계정(소유자 포함)에서 UPDATE/DELETE 권한 회수
REVOKE UPDATE, DELETE, TRUNCATE ON ledger_transaction FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON ledger_transaction FROM fracta;

CREATE TABLE ledger_balance (
    id           BIGSERIAL   PRIMARY KEY,
    owner_id     BIGINT      NOT NULL,
    token_symbol VARCHAR(50) NOT NULL,
    units        BIGINT      NOT NULL DEFAULT 0,
    locked_units BIGINT      NOT NULL DEFAULT 0,
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_ledger_balance UNIQUE (owner_id, token_symbol),
    -- DB 레벨 INV-2 / INV-3 방어선
    CONSTRAINT ck_ledger_balance_invariant
        CHECK (units >= 0 AND locked_units >= 0 AND locked_units <= units)
);

CREATE INDEX idx_ledger_balance_symbol ON ledger_balance (token_symbol);
