-- 계좌·투자자 (FSD §6.1, Phase 3)

CREATE TABLE investor (
    id                    BIGSERIAL    PRIMARY KEY,
    name                  VARCHAR(100) NOT NULL,
    email                 VARCHAR(255) NOT NULL UNIQUE,
    password_hash         VARCHAR(100) NOT NULL,
    ci_hash               CHAR(64)     NOT NULL,
    role                  VARCHAR(20)  NOT NULL DEFAULT 'INVESTOR' CHECK (role IN ('INVESTOR', 'ADMIN')),
    kyc_status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (kyc_status IN ('PENDING', 'VERIFIED')),
    risk_grade            INT          CHECK (risk_grade BETWEEN 1 AND 5),
    risk_grade_expires_at TIMESTAMPTZ,
    cash_balance          BIGINT       NOT NULL DEFAULT 0 CHECK (cash_balance >= 0),  -- INV-3
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 성향 진단 이력 (AC-03). 유효기간 1년
CREATE TABLE risk_profile_result (
    id          BIGSERIAL   PRIMARY KEY,
    investor_id BIGINT      NOT NULL REFERENCES investor (id),
    answers     JSONB       NOT NULL,
    score       INT         NOT NULL,
    grade       INT         NOT NULL CHECK (grade BETWEEN 1 AND 5),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_risk_profile_investor ON risk_profile_result (investor_id, created_at DESC);

-- 예치금 입출금 원장 (AC-05, INV-6 검증용) — append-only
CREATE TABLE cash_transaction (
    id            BIGSERIAL   PRIMARY KEY,
    investor_id   BIGINT      NOT NULL REFERENCES investor (id),
    tx_type       VARCHAR(10) NOT NULL CHECK (tx_type IN ('DEPOSIT', 'WITHDRAW')),
    amount        BIGINT      NOT NULL CHECK (amount > 0),
    balance_after BIGINT      NOT NULL CHECK (balance_after >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cash_tx_investor ON cash_transaction (investor_id, created_at DESC);

REVOKE UPDATE, DELETE, TRUNCATE ON cash_transaction FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON cash_transaction FROM fracta;

-- 부적합 확인 서명 이력 (AC-04) — 금소법 시뮬레이션 핵심 증거, append-only
CREATE TABLE suitability_ack (
    id            BIGSERIAL   PRIMARY KEY,
    investor_id   BIGINT      NOT NULL REFERENCES investor (id),
    product_grade INT         NOT NULL CHECK (product_grade BETWEEN 1 AND 5),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_suitability_ack ON suitability_ack (investor_id, product_grade);

REVOKE UPDATE, DELETE, TRUNCATE ON suitability_ack FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON suitability_ack FROM fracta;
