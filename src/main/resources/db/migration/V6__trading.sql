-- 유통·결제 (FSD §5.1, §6.5, §6.6, Phase 6)

CREATE TABLE trade_order (
    id              BIGSERIAL    PRIMARY KEY,
    token_symbol    VARCHAR(20)  NOT NULL,
    investor_id     BIGINT       NOT NULL REFERENCES investor (id),
    side            VARCHAR(4)   NOT NULL CHECK (side IN ('BUY', 'SELL')),
    order_type      VARCHAR(6)   NOT NULL CHECK (order_type IN ('LIMIT', 'MARKET')),
    -- 시장가는 가격이 없다
    price           BIGINT       CHECK (price IS NULL OR price > 0),
    units           BIGINT       NOT NULL CHECK (units > 0),
    filled_units    BIGINT       NOT NULL DEFAULT 0 CHECK (filled_units >= 0),
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED')),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,   -- Phase 7 멱등성이 이 컬럼을 쓴다
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CHECK (filled_units <= units),
    CHECK (order_type = 'MARKET' OR price IS NOT NULL)
);

-- 오더북 복원: status IN ('OPEN','PARTIALLY_FILLED') 를 created_at 오름차순으로 읽는다
CREATE INDEX idx_trade_order_book ON trade_order (token_symbol, status, created_at);
CREATE INDEX idx_trade_order_investor ON trade_order (investor_id, created_at DESC);

CREATE TABLE trade_execution (
    id            BIGSERIAL    PRIMARY KEY,
    token_symbol  VARCHAR(20)  NOT NULL,
    buy_order_id  BIGINT       NOT NULL REFERENCES trade_order (id),
    sell_order_id BIGINT       NOT NULL REFERENCES trade_order (id),
    price         BIGINT       NOT NULL CHECK (price > 0),
    units         BIGINT       NOT NULL CHECK (units > 0),
    buy_fee       BIGINT       NOT NULL DEFAULT 0 CHECK (buy_fee >= 0),
    sell_fee      BIGINT       NOT NULL DEFAULT 0 CHECK (sell_fee >= 0),
    -- 원자산 시세를 못 구하면 NULL (0으로 나누기 방지 — 계산 자체를 건너뛴다)
    premium_rate  NUMERIC(12, 4),
    executed_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_trade_exec_symbol ON trade_execution (token_symbol, executed_at DESC);
CREATE INDEX idx_trade_exec_daily ON trade_execution (executed_at);

-- 체결은 사후 변경 대상이 아니다 (원장·감사와 동일 원칙)
REVOKE UPDATE, DELETE, TRUNCATE ON trade_execution FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON trade_execution FROM fracta;

-- 매매 대금 이동 유형 추가. DEPOSIT/WITHDRAW 만 외부 입출금이고 나머지는 내부 이동이다 (INV-6)
ALTER TABLE cash_transaction DROP CONSTRAINT cash_transaction_tx_type_check;
ALTER TABLE cash_transaction
    ADD CONSTRAINT cash_transaction_tx_type_check
        CHECK (tx_type IN ('DEPOSIT', 'WITHDRAW', 'MARGIN_HOLD', 'MARGIN_REFUND',
                           'SETTLEMENT_CREDIT', 'TRADE_DEBIT', 'TRADE_CREDIT', 'FEE_INCOME'));

-- 플랫폼 수수료 계정.
-- 수수료가 투자자 잔액에서 빠져나가는데 받는 곳이 없으면 INV-6(예치금 보존)이 수수료만큼 깨진다.
-- 이 계정이 수수료를 받아 Σ cash_balance 가 보존된다.
INSERT INTO investor (name, email, password_hash, ci_hash, role, kyc_status, cash_balance)
VALUES ('플랫폼 수수료 계정', 'platform-fee@fracta.internal', '-', repeat('0', 64), 'ADMIN', 'VERIFIED', 0)
ON CONFLICT (email) DO NOTHING;
