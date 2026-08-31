-- 발행 (FSD §6.2, Phase 3)

CREATE TABLE underlying_asset (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    asset_type    VARCHAR(20)  NOT NULL CHECK (asset_type IN ('REIT', 'ETF', 'REAL_ESTATE')),
    asset_code    CHAR(4)      NOT NULL UNIQUE CHECK (asset_code ~ '^[A-Z]{4}$'),
    broker_ticker VARCHAR(20),
    split_ratio   BIGINT       NOT NULL CHECK (split_ratio > 0),  -- 원자산 1주 = split_ratio 조각
    description   TEXT,
    issuer_id     BIGINT       NOT NULL REFERENCES investor (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE issuance (
    id                    BIGSERIAL   PRIMARY KEY,
    asset_id              BIGINT      NOT NULL REFERENCES underlying_asset (id),
    token_symbol          VARCHAR(20) NOT NULL UNIQUE,
    total_units           BIGINT      NOT NULL CHECK (total_units BETWEEN 100 AND 1000000),
    unit_price            BIGINT      NOT NULL CHECK (unit_price >= 100),
    -- Phase 4 방식 C(원자적 감소)가 요구하는 컬럼. total_units로 초기화한다
    remaining_units       BIGINT      NOT NULL CHECK (remaining_units >= 0),
    status                VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    subscription_start_at TIMESTAMPTZ NOT NULL,
    subscription_end_at   TIMESTAMPTZ NOT NULL,
    prospectus_file_key   VARCHAR(255),
    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (subscription_end_at > subscription_start_at)
);

CREATE INDEX idx_issuance_asset ON issuance (asset_id);
CREATE INDEX idx_issuance_status ON issuance (status);
