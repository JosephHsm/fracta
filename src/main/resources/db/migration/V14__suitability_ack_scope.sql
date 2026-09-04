-- 부적합 확인 서명에 범위와 만료를 부여한다.
--
-- 기존 구조는 (투자자, 등급) 한 쌍이었고 조회가 "서명한 등급 이상 존재 여부"였다.
-- 그래서 1등급 서명 한 번이면 그 이하 모든 상품을, 모든 발행 건에 대해, 영원히 커버했다.
-- 성향 진단에는 만료를 걸어 두고(risk_grade_expires_at) 정작 그 가드를 무력화하는 쪽에는
-- 아무 제한이 없었다. 실제 부적합 확인은 거래 건별로 받는다.
--
-- suitability_ack 은 append-only 다 — V3 에서 UPDATE 권한을 회수했다. 그래서 기존 행 백필을
-- UPDATE 로 하지 않고 컬럼 DEFAULT 로 채운 뒤 DEFAULT 를 걷어낸다. 신규 INSERT 는 값을
-- 반드시 넘겨야 한다.
--
-- 같은 이유로 재진단 무효화도 UPDATE 로 하지 않는다. "현재 성향 진단보다 먼저 서명된 것은
-- 무효"라는 규칙으로 푼다 — 재진단하면 risk_grade_issued_at 이 갱신되고 이전 서명이
-- 한꺼번에 효력을 잃는다.

ALTER TABLE investor
    ADD COLUMN risk_grade_issued_at TIMESTAMPTZ;

-- 기존 진단분은 만료 1년 전에 발급된 것으로 본다 (AC-03 유효기간 365일)
UPDATE investor
   SET risk_grade_issued_at = risk_grade_expires_at - INTERVAL '365 days'
 WHERE risk_grade_expires_at IS NOT NULL;

-- 기존 서명은 어떤 상품에 대한 것인지 알 수 없다. 포괄 서명을 남겨 두면 구멍이 그대로
-- 남으므로 이미 만료된 것으로 채운다(expires_at = now()) — 조회가 expires_at > now() 라
-- 곧바로 효력을 잃는다. 필요하면 투자자가 상품별로 다시 서명한다.
ALTER TABLE suitability_ack
    ADD COLUMN scope_type VARCHAR(10) NOT NULL DEFAULT 'ISSUANCE',
    ADD COLUMN scope_id   VARCHAR(50) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- 신규 서명은 범위와 만료를 반드시 넘겨야 한다
ALTER TABLE suitability_ack
    ALTER COLUMN scope_type DROP DEFAULT,
    ALTER COLUMN scope_id   DROP DEFAULT,
    ALTER COLUMN expires_at DROP DEFAULT,
    ADD CONSTRAINT ck_suitability_ack_scope
        CHECK (scope_type IN ('ISSUANCE', 'TOKEN'));

DROP INDEX IF EXISTS idx_suitability_ack;

-- 조회: (투자자, 범위) 로 찾아 만료·서명시각을 본다
CREATE INDEX idx_suitability_ack_scope
    ON suitability_ack (investor_id, scope_type, scope_id, expires_at DESC);
