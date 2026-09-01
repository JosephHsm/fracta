-- 오픈 API (FSD §6.7, §7, Phase 7)

CREATE TABLE api_client (
    id                 BIGSERIAL    PRIMARY KEY,
    client_id          VARCHAR(64)  NOT NULL UNIQUE,
    -- 평문은 발급 시 1회만 노출한다. DB에는 SHA-256 해시만 남는다 (FSD §13.2)
    client_secret_hash CHAR(64)     NOT NULL,
    name               VARCHAR(100) NOT NULL,
    owner_investor_id  BIGINT       NOT NULL REFERENCES investor (id),
    scopes             VARCHAR(255) NOT NULL,
    env                VARCHAR(10)  NOT NULL CHECK (env IN ('LIVE', 'SANDBOX')),
    rate_limit_per_sec INT          NOT NULL DEFAULT 10 CHECK (rate_limit_per_sec > 0),
    rate_limit_per_day INT          NOT NULL DEFAULT 10000 CHECK (rate_limit_per_day > 0),
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_client_owner ON api_client (owner_investor_id);

CREATE TABLE api_call_log (
    id              BIGSERIAL    PRIMARY KEY,
    client_id       VARCHAR(64)  NOT NULL,
    endpoint        VARCHAR(255) NOT NULL,
    method          VARCHAR(10)  NOT NULL,
    status_code     INT          NOT NULL,
    latency_ms      BIGINT       NOT NULL,
    idempotency_key VARCHAR(100),
    -- 요청/응답 본문은 저장하지 않는다 (개인정보·용량). 필요하면 해시만 남긴다
    body_hash       CHAR(64),
    called_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_call_log_client ON api_call_log (client_id, called_at DESC);

CREATE TABLE webhook_endpoint (
    id         BIGSERIAL    PRIMARY KEY,
    client_id  VARCHAR(64)  NOT NULL,
    owner_investor_id BIGINT NOT NULL REFERENCES investor (id),
    url        VARCHAR(500) NOT NULL,
    -- HMAC 서명에 원문이 필요하므로 해시가 아니라 값을 보관한다. 조회 API로 노출하지 않는다
    secret     VARCHAR(100) NOT NULL,
    events     VARCHAR(500) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_endpoint_client ON webhook_endpoint (client_id, active);

CREATE TABLE webhook_delivery (
    id           BIGSERIAL    PRIMARY KEY,
    endpoint_id  BIGINT       NOT NULL REFERENCES webhook_endpoint (id),
    event_type   VARCHAR(50)  NOT NULL,
    payload      TEXT         NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    last_error   VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);

CREATE INDEX idx_webhook_delivery_status ON webhook_delivery (status, created_at);
