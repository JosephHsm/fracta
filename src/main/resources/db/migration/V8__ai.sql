-- AI 서비스 (FSD §10, Phase 8)
-- 스키마 소유는 Core(Flyway)다. Python AI 서비스는 읽기·쓰기만 하고 마이그레이션하지 않는다.

-- 투자설명서 청크. 임베딩 차원 1024 = BAAI/bge-m3 고정값.
-- 차원을 바꾸면 컬럼 타입 변경 + 전체 재인덱싱이 필요하므로 EMBEDDING_MODEL 교체는 마이그레이션 대상이다.
CREATE TABLE prospectus_chunk (
    id          BIGSERIAL   PRIMARY KEY,
    issuance_id BIGINT      NOT NULL REFERENCES issuance (id),
    -- 1-indexed. 사용자에게 보이는 PDF 페이지 번호와 일치해야 한다 (인용 [p.12]의 근거)
    page_no     INT         NOT NULL CHECK (page_no >= 1),
    -- 같은 페이지 안에서의 순번. 청크는 페이지 경계를 넘지 않는다 (FSD §10.2)
    chunk_index INT         NOT NULL CHECK (chunk_index >= 0),
    content     TEXT        NOT NULL,
    embedding   VECTOR(1024) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_prospectus_chunk UNIQUE (issuance_id, page_no, chunk_index)
);

CREATE INDEX idx_prospectus_chunk_issuance ON prospectus_chunk (issuance_id);

-- 코사인 유사도 검색용. hnsw는 ivfflat과 달리 사전 학습(훈련 데이터) 없이 빈 테이블에도 만들 수 있다.
CREATE INDEX idx_prospectus_chunk_embedding
    ON prospectus_chunk USING hnsw (embedding vector_cosine_ops);

-- 모든 AI 질의를 전량 기록한다 (FSD §10.3). 차단된 질의도 반드시 남긴다 —
-- 남기지 않으면 가드레일이 실제로 동작했다는 것을 증명할 수 없다.
CREATE TABLE ai_conversation_log (
    id                 BIGSERIAL    PRIMARY KEY,
    conversation_type  VARCHAR(20)  NOT NULL
        CHECK (conversation_type IN ('PROSPECTUS', 'DEVPORTAL')),
    issuance_id        BIGINT       REFERENCES issuance (id),
    request_id         VARCHAR(64),
    question           TEXT         NOT NULL,
    -- 검색 컨텍스트: 사용한 청크 ID 목록. 환각 인용 판정의 근거가 된다
    retrieved_chunk_ids BIGINT[]    NOT NULL DEFAULT '{}',
    top_similarity     REAL,
    -- LLM을 실제로 호출했는가. 임계값 미달·입력 차단은 false 여야 한다
    llm_called         BOOLEAN      NOT NULL,
    raw_answer         TEXT,
    guardrail_stage    VARCHAR(10)  NOT NULL
        CHECK (guardrail_stage IN ('INPUT', 'OUTPUT', 'NONE')),
    guardrail_blocked  BOOLEAN      NOT NULL,
    guardrail_reason   VARCHAR(40),
    final_answer       TEXT         NOT NULL,
    cited_pages        INT[]        NOT NULL DEFAULT '{}',
    provider           VARCHAR(20)  NOT NULL,
    model_id           VARCHAR(60)  NOT NULL,
    input_tokens       INT          NOT NULL DEFAULT 0,
    output_tokens      INT          NOT NULL DEFAULT 0,
    latency_ms         BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_conversation_log_created ON ai_conversation_log (created_at DESC);
CREATE INDEX idx_ai_conversation_log_blocked
    ON ai_conversation_log (guardrail_blocked, created_at DESC);
