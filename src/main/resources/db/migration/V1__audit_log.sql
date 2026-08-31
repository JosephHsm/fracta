-- 감사 로그 (FSD §6.8). append-only — UPDATE/DELETE는 권한 레벨에서 차단한다.
CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    actor        VARCHAR(100) NOT NULL DEFAULT 'system',
    action       VARCHAR(100) NOT NULL,
    target_type  VARCHAR(100) NOT NULL,
    target_id    VARCHAR(100),
    channel      VARCHAR(20)  NOT NULL,
    before_state JSONB,
    after_state  JSONB,
    request_id   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_target ON audit_log (target_type, target_id);
CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);

-- AU-04: 애플리케이션 계정(fracta, 소유자 포함)에서 UPDATE/DELETE/TRUNCATE 권한 회수.
-- 소유자는 스스로 권한을 회수할 수 있고, 회수 후에는 permission denied가 난다.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM fracta;
