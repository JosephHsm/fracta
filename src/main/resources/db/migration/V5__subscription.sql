-- 청약 (FSD §6.3, Phase 4)

CREATE TABLE subscription_order (
    id              BIGSERIAL    PRIMARY KEY,
    issuance_id     BIGINT       NOT NULL REFERENCES issuance (id),
    investor_id     BIGINT       NOT NULL REFERENCES investor (id),
    requested_units BIGINT       NOT NULL CHECK (requested_units > 0),
    allotted_units  BIGINT       CHECK (allotted_units >= 0),
    deposit_amount  BIGINT       NOT NULL CHECK (deposit_amount >= 0),
    status          VARCHAR(20)  NOT NULL DEFAULT 'DEPOSITED'
        CHECK (status IN ('PENDING', 'DEPOSITED', 'ALLOTTED', 'PARTIALLY_ALLOTTED', 'REJECTED', 'SETTLED', 'CANCELLED')),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    applied_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_sub_order_issuance ON subscription_order (issuance_id, status);
CREATE INDEX idx_sub_order_investor ON subscription_order (investor_id);

-- 배정 방식: FCFS(선착순, 동시성 전략으로 remaining_units 감소) / PRORATA(전량 접수 후 비례배분)
ALTER TABLE issuance
    ADD COLUMN allotment_method VARCHAR(10) NOT NULL DEFAULT 'FCFS'
        CHECK (allotment_method IN ('FCFS', 'PRORATA'));

-- 상품 위험등급 (적합성 판정 AC-04의 비교 대상)
ALTER TABLE issuance
    ADD COLUMN risk_grade INT NOT NULL DEFAULT 3 CHECK (risk_grade BETWEEN 1 AND 5);

-- 증거금 흐름은 내부 이동으로 별도 유형 기록 (INV-6: 외부 입출금과 구분)
ALTER TABLE cash_transaction ALTER COLUMN tx_type TYPE VARCHAR(20);
ALTER TABLE cash_transaction DROP CONSTRAINT cash_transaction_tx_type_check;
ALTER TABLE cash_transaction
    ADD CONSTRAINT cash_transaction_tx_type_check
        CHECK (tx_type IN ('DEPOSIT', 'WITHDRAW', 'MARGIN_HOLD', 'MARGIN_REFUND', 'SETTLEMENT_CREDIT'));
