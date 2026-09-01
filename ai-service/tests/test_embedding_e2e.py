"""실제 bge-m3 + 실제 pgvector 종단 검증.

나머지 테스트는 전부 대역으로 돈다(그게 EmbeddingPort 를 분리한 이유다). 이 파일만
실물을 쓰며, 둘 중 하나라도 없으면 **건너뛴다** — CI와 GPU 없는 개발 노트북에서
2.3GB 가중치를 강제하지 않기 위해서다.

여기서만 확인할 수 있는 것이 세 가지 있다.
  1. bge-m3 의 실제 차원이 1024이고 V8__ai.sql 의 VECTOR(1024) 와 맞는가
  2. pgvector 의 코사인 거리(<=>)가 한국어 문장에서 의도대로 동작하는가
  3. 유사도 임계값 0.6 이 실제 모델 기준으로 타당한가 (관련 질문은 넘고 무관한 질문은 못 넘는가)

실행:
    docker compose up -d postgres
    pip install ".[embeddings]"
    pytest tests/test_embedding_e2e.py -v -s
"""

import os

import pytest

pytestmark = pytest.mark.e2e

DSN = os.getenv("E2E_DB_DSN", "postgresql://fracta:fracta@localhost:5432/fracta")

CHUNKS = [
    (3, "본 상품의 기초자산은 서울특별시 강남구 소재 오피스 빌딩이며, 연면적 12,400제곱미터, "
        "준공 2015년이다. 임차인은 5개사이며 가중평균 잔여 임대차 기간은 3.2년이다."),
    (7, "주요 위험요인. 공실 발생 시 임대수익이 감소할 수 있다. 금리 상승 시 차입금 이자비용이 "
        "증가한다. 부동산 시장 침체 시 기초자산 평가액이 하락할 수 있으며 원금 손실이 발생할 수 있다."),
    (12, "청약 단위는 1조각이며 조각당 공모가는 10,000원이다. 배당은 반기 1회 지급하며 "
         "목표 배당수익률은 연 5.5%다. 목표 수익률은 확정된 수치가 아니며 보장되지 않는다."),
]

RELEVANT = [
    ("공실이 생기면 어떻게 되나요?", 7),
    ("기초자산 건물은 어디에 있나요?", 3),
    ("배당은 얼마나 자주 주나요?", 12),
    ("주요 위험요인이 무엇인가요?", 7),
    ("금리가 오르면 어떤 영향이 있나요?", 7),
    ("원금 손실 가능성이 있나요?", 7),
    ("건물 연면적이 얼마인가요?", 3),
    ("임차인은 몇 개사인가요?", 3),
    ("조각당 공모가는 얼마인가요?", 12),
    ("목표 배당수익률이 몇 퍼센트인가요?", 12),
]

# 이 투자설명서와 무관한 질문들. 임계값 미달로 LLM 호출이 없어야 한다.
# 문서와 주제 자체가 무관한 질문. 임계값 미달로 LLM 호출이 없어야 한다.
IRRELEVANT = [
    "오늘 서울 날씨가 어떤가요?",
    "파이썬으로 리스트를 정렬하는 방법을 알려주세요",
    "이 회사 대표이사의 취미가 무엇인가요?",
    "점심 메뉴 추천해 주세요",
    "이 상품의 담당 PM 연락처를 알려주세요",
    "작년 코스피 지수는 얼마였나요?",
    "환불 규정이 어떻게 되나요?",
]

# 주제는 문서와 맞지만 답이 문서에 없는 질문.
# 유사도 임계값으로는 거를 수 없다 — 문서와 단어를 공유하기 때문이다.
# 임계값의 일이 아니라 LLM 의 found_in_document=false 와
# 출력 가드레일의 인용 검증이 받아내야 하는 경우다.
ON_TOPIC_BUT_ABSENT = [
    "청약 경쟁률이 어떻게 되나요?",
]


@pytest.fixture(scope="module")
def embedder():
    from app.config import Settings

    try:
        # sentence_transformers 는 최상위에서 torch 를 import 한다. 미설치(ImportError)와
        # 설치는 됐지만 로드 실패(OSError — 예: Windows MSVC 런타임 구버전)를 모두 건너뛴다.
        from app.adapters.embedding_adapters import LocalBgeM3Adapter

        return LocalBgeM3Adapter(Settings().embedding_model, Settings().embedding_dim)
    except (ImportError, OSError) as exc:
        pytest.skip(f"임베딩 런타임을 로드할 수 없다: {type(exc).__name__}: {exc}")


@pytest.fixture(scope="module")
def conn():
    psycopg = pytest.importorskip("psycopg")
    from pgvector.psycopg import register_vector

    try:
        connection = psycopg.connect(DSN, connect_timeout=3)
    except psycopg.Error as exc:
        pytest.skip(f"PostgreSQL 에 연결할 수 없다 ({DSN}): {exc}")

    connection.execute("CREATE EXTENSION IF NOT EXISTS vector")
    connection.commit()
    register_vector(connection)
    yield connection
    connection.close()


@pytest.fixture(scope="module")
def table(conn, embedder):
    """실제 스키마의 FK 체인(investor→asset→issuance)까지 끌어오지 않고
    prospectus_chunk 와 같은 형태의 임시 테이블만 만든다. 여기서 보려는 것은
    벡터 연산이지 참조 무결성이 아니다."""
    name = "e2e_prospectus_chunk"
    conn.execute(f"DROP TABLE IF EXISTS {name}")
    conn.execute(
        f"CREATE TABLE {name} ("
        " id BIGSERIAL PRIMARY KEY,"
        " page_no INT NOT NULL,"
        " content TEXT NOT NULL,"
        f" embedding VECTOR({embedder.dim}) NOT NULL)"
    )
    conn.execute(
        f"CREATE INDEX ON {name} USING hnsw (embedding vector_cosine_ops)"
    )
    vectors = embedder.embed([c[1] for c in CHUNKS])
    for (page_no, content), vector in zip(CHUNKS, vectors, strict=True):
        conn.execute(
            f"INSERT INTO {name} (page_no, content, embedding) VALUES (%s, %s, %s::vector)",
            (page_no, content, vector),
        )
    conn.commit()
    yield name
    conn.execute(f"DROP TABLE IF EXISTS {name}")
    conn.commit()


def _threshold() -> float:
    from app.config import Settings

    return Settings().similarity_threshold


def _search(conn, table, embedder, question, top_k=5):
    vector = embedder.embed([question])[0]
    rows = conn.execute(
        f"SELECT page_no, 1 - (embedding <=> %s::vector) AS similarity FROM {table} "
        f"ORDER BY embedding <=> %s::vector LIMIT %s",
        (vector, vector, top_k),
    ).fetchall()
    return [(r[0], float(r[1])) for r in rows]


def test_실제_모델_차원이_스키마와_일치한다(embedder):
    from app.config import Settings

    assert embedder.dim == 1024
    assert Settings().embedding_dim == embedder.dim


@pytest.mark.parametrize("question,expected_page", RELEVANT)
def test_관련_질문은_올바른_페이지를_최상위로_찾는다(conn, table, embedder, question, expected_page):
    results = _search(conn, table, embedder, question)

    top_page, top_similarity = results[0]
    print(f"\n  '{question}' → p.{top_page} (유사도 {top_similarity:.3f})")
    assert top_page == expected_page
    assert top_similarity >= _threshold(), (
        "관련 질문인데 임계값을 넘지 못했다 — SIMILARITY_THRESHOLD 재검토 필요"
    )


@pytest.mark.parametrize("question", IRRELEVANT)
def test_무관한_질문은_임계값을_넘지_못한다(conn, table, embedder, question):
    """임계값 0.6의 근거. 넘어버리면 무관한 질문에도 LLM을 호출하게 된다."""
    results = _search(conn, table, embedder, question)
    top_similarity = results[0][1]

    print(f"\n  '{question}' → 최고 유사도 {top_similarity:.3f}")
    assert top_similarity < _threshold(), (
        f"무관한 질문의 유사도가 {top_similarity:.3f} 로 임계값을 넘었다. "
        "SIMILARITY_THRESHOLD 를 올리고 근거를 docs/ai/ 에 기록해야 한다."
    )


def test_threshold_sits_between_distributions(conn, table, embedder):
    """관련/무관 분포가 겹치면 임계값 자체가 무의미하다.

    측정 근거는 docs/ai/similarity-threshold.md 에 기록한다.
    """
    threshold = _threshold()
    relevant_min = min(_search(conn, table, embedder, q)[0][1] for q, _ in RELEVANT)
    irrelevant_max = max(_search(conn, table, embedder, q)[0][1] for q in IRRELEVANT)

    print(f"\n  관련 최저 {relevant_min:.3f} / 무관 최고 {irrelevant_max:.3f} "
          f"/ 임계값 {threshold} (간격 {relevant_min - irrelevant_max:.3f})")
    assert irrelevant_max < threshold < relevant_min, (
        f"임계값 {threshold} 이 두 분포 사이에 있지 않다 "
        f"(무관 최고 {irrelevant_max:.3f}, 관련 최저 {relevant_min:.3f})"
    )


@pytest.mark.parametrize("question", ON_TOPIC_BUT_ABSENT)
def test_on_topic_question_passes_threshold(conn, table, embedder, question):
    """임계값이 거르는 것은 '주제적 무관함'이지 '답의 부재'가 아니다.

    답이 없는 on-topic 질문까지 임계값으로 막으려고 값을 올리면,
    답할 수 있는 질문들이 함께 잘려나간다 (FSD 기본값 0.6 에서 실제로 그러했다).
    이 경우는 LLM 의 found_in_document=false 와 출력 가드레일이 처리한다.
    """
    top_similarity = _search(conn, table, embedder, question)[0][1]

    print(f"\n  '{question}' → 최고 유사도 {top_similarity:.3f} (임계값 통과, LLM 판단으로 넘김)")
    assert top_similarity >= _threshold()


def test_임베딩이_L2_정규화되어_있다(embedder):
    """정규화되어 있어야 1 - cosine_distance 를 그대로 유사도로 쓸 수 있다."""
    vector = embedder.embed(["공실 발생 시 임대수익 감소"])[0]
    norm = sum(x * x for x in vector) ** 0.5
    assert abs(norm - 1.0) < 1e-4
