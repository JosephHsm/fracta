-- 종목마스터 (NH투자증권 배포 m_new_stock.mst).
--
-- PLUG에는 이름으로 종목을 찾는 API가 없다 — 모든 시세 API가 종목코드(iem_cd)를 받는다.
-- 그래서 코드↔이름 목록을 로컬에 둔다. 검색은 여기서, 시세는 API에서.
--
-- 원본은 인증 없이 공개 배포된다(https://www.nhplug.com/instruments/m_new_stock.mst).
-- 매일 갱신되므로 파일을 저장소에 넣지 않고 기동·배치에서 받아 적재한다.
CREATE TABLE instrument_master (
    code           VARCHAR(12)  PRIMARY KEY,
    market         VARCHAR(4)   NOT NULL,
    kor_name       VARCHAR(100) NOT NULL,
    eng_name       VARCHAR(100),
    -- ETF / REIT / STOCK. 마스터에 상품유형 필드가 없어 이름 규칙으로 분류한다.
    asset_kind     VARCHAR(10)  NOT NULL,
    -- 상장주식수·전일종가 등은 시세 API가 주므로 마스터에는 검색에 필요한 것만 둔다.
    prev_close     BIGINT,
    market_cap     BIGINT,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 이름 부분검색용. pg_trgm 없이도 접두 검색은 이 인덱스를 탄다.
CREATE INDEX idx_instrument_master_kind_name ON instrument_master (asset_kind, kor_name);
CREATE INDEX idx_instrument_master_name      ON instrument_master (kor_name);

-- 종목명 임베딩. 이름 그대로 치지 않는 검색을 위해서다 —
-- 마스터에는 "KODEX 200"으로 저장돼 있는데 사람은 "코덱스"라고 친다.
--
-- LLM을 부르지 않는다. 로컬 bge-m3 임베딩만 쓴다(투자설명서 RAG와 같은 모델).
-- LLM에 종목코드를 물으면 없는 코드를 지어낼 수 있고, 한 글자만 틀려도
-- 엉뚱한 종목 시세가 정상처럼 표시된다. 마스터는 존재하는 코드만 돌려준다.
CREATE TABLE instrument_embedding (
    code       VARCHAR(12)  PRIMARY KEY REFERENCES instrument_master (code) ON DELETE CASCADE,
    embedding  vector(1024) NOT NULL,
    indexed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_instrument_embedding_vec
    ON instrument_embedding USING hnsw (embedding vector_cosine_ops);
