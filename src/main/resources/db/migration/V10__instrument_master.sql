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
