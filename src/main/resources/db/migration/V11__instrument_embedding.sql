-- 종목명 의미 검색용 임베딩.
--
-- V10에 함께 넣었다가 분리했다. V10은 이미 적용된 뒤였고, 적용된 마이그레이션을
-- 수정하면 Flyway 체크섬이 깨져 다음 기동이 실패한다. 마이그레이션은 append-only다.
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
