"""검색 — 코사인 유사도 쿼리와 임계값 판정."""

import contextlib
import pathlib

from app.adapters.embedding_adapters import FakeEmbeddingAdapter
from app.retrieval.search import RetrievedChunk, SearchResult, SearchService

SEARCH_SOURCE = (
    pathlib.Path(__file__).parent.parent / "app" / "retrieval" / "search.py"
)


def test_네이티브_쿼리에_문자열_연결이_없다():
    """CLAUDE.md 코딩 규칙 — 네이티브 쿼리는 바인딩 파라미터만."""
    source = SEARCH_SOURCE.read_text(encoding="utf-8")
    execute = source[source.index("cur.execute(") : source.index("rows = cur.fetchall()")]

    # SQL 문자열 안에 f-string 이나 % 포맷이 끼면 안 된다
    assert 'f"' not in execute
    assert "format(" not in execute
    assert "+ str(" not in execute
    assert execute.count("%s") == 4  # 벡터, issuance_id, 벡터, limit


def test_임계값_경계():
    service = SearchService(FakeEmbeddingAdapter(dim=8), top_k=5, threshold=0.6)

    assert service.below_threshold(_result(0.59))
    assert not service.below_threshold(_result(0.6))
    assert not service.below_threshold(_result(0.61))


def test_검색결과가_비면_유사도_0으로_취급된다():
    """검색 결과가 없으면 임계값 미달과 같은 경로 — LLM을 부르지 않는다."""
    service = SearchService(FakeEmbeddingAdapter(dim=8), top_k=5, threshold=0.6)
    empty = SearchResult(chunks=[], top_similarity=0.0)

    assert service.below_threshold(empty)
    assert empty.valid_pages == set()
    assert empty.chunk_ids == []


def test_검색결과에서_유효_페이지와_청크ID를_뽑는다():
    result = SearchResult(
        chunks=[
            RetrievedChunk(id=11, page_no=3, content="가", similarity=0.9),
            RetrievedChunk(id=12, page_no=3, content="나", similarity=0.8),
            RetrievedChunk(id=13, page_no=7, content="다", similarity=0.7),
        ],
        top_similarity=0.9,
    )

    # 같은 페이지의 청크가 여러 개면 페이지는 하나로 접힌다 (인용 검증 대상은 페이지다)
    assert result.valid_pages == {3, 7}
    assert result.chunk_ids == [11, 12, 13]


def test_질문_임베딩이_쿼리_파라미터로_넘어간다():
    recorder = []
    service = SearchService(FakeEmbeddingAdapter(dim=8), top_k=5, threshold=0.6)

    with _stub_connection(recorder, rows=[(1, 3, "내용", 0.83)]):
        result = service.search(issuance_id=9, question="주요 위험요인이 무엇인가요?")

    sql, params = recorder[0]
    vector, issuance_id, vector_again, limit = params
    assert issuance_id == 9
    assert limit == 5
    assert vector == vector_again, "정렬과 유사도 계산에 같은 벡터를 써야 한다"
    assert len(vector) == 8
    assert result.chunks[0].similarity == 0.83
    assert result.top_similarity == 0.83


def _result(similarity: float) -> SearchResult:
    return SearchResult(
        chunks=[RetrievedChunk(id=1, page_no=3, content="가", similarity=similarity)],
        top_similarity=similarity,
    )


@contextlib.contextmanager
def _stub_connection(recorder: list, rows: list):
    import app.retrieval.search as search_module

    class Cursor:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def execute(self, sql, params=()):
            recorder.append((sql, tuple(params)))

        def fetchall(self):
            return rows

    class Connection:
        def cursor(self):
            return Cursor()

    @contextlib.contextmanager
    def fake_connection():
        yield Connection()

    original = search_module.connection
    search_module.connection = fake_connection
    try:
        yield
    finally:
        search_module.connection = original
