"""인덱싱 파이프라인 — 실제 PDF로 페이지 경계 유지를 검증한다.

test_chunker.py 는 텍스트 단계만 본다. 여기서는 pdfplumber 가 실제 PDF에서 뽑은
페이지 경계가 청크까지 살아남는지를 본다 — 파이프라인 앞단이 페이지를 뭉개면
청커가 아무리 정확해도 인용이 어긋난다.
"""

import pytest

from app.adapters.embedding_adapters import FakeEmbeddingAdapter
from app.indexing.chunker import chunk_pages
from app.indexing.pdf_text import extract_pages
from app.indexing.service import IndexingService, chunk_summary
from tests.pdf_builder import build_pdf


@pytest.fixture
def three_page_pdf() -> bytes:
    return build_pdf([
        ["ALPHA page one line one", "ALPHA page one line two"],
        ["BRAVO page two line one", "BRAVO page two line two"],
        ["CHARLIE page three only line"],
    ])


def test_페이지별로_텍스트를_뽑고_1_indexed_로_매긴다(three_page_pdf):
    pages = extract_pages(three_page_pdf)

    assert [p.page_no for p in pages] == [1, 2, 3]
    assert "ALPHA" in pages[0].text
    assert "BRAVO" in pages[1].text
    assert "CHARLIE" in pages[2].text


def test_페이지_내용이_서로_섞이지_않는다(three_page_pdf):
    pages = extract_pages(three_page_pdf)

    assert "BRAVO" not in pages[0].text
    assert "ALPHA" not in pages[1].text
    assert "CHARLIE" not in pages[1].text


def test_실제_PDF에서도_청크가_페이지를_넘지_않는다(three_page_pdf):
    """모든 청크의 page_no 가 단일값 — FSD §10.2 의 핵심 제약."""
    chunks = chunk_pages(extract_pages(three_page_pdf), size=500, overlap=100)

    markers = {"ALPHA": 1, "BRAVO": 2, "CHARLIE": 3}
    assert chunks
    for chunk in chunks:
        present = [m for m in markers if m in chunk.content]
        assert len(present) == 1, f"청크가 여러 페이지를 담고 있다: {chunk.content[:80]}"
        assert chunk.page_no == markers[present[0]]


def test_텍스트가_없는_페이지는_건너뛰되_페이지_번호는_유지된다():
    """스캔본 등 텍스트 레이어가 없는 페이지가 섞여도 인용 번호가 밀리면 안 된다."""
    pdf = build_pdf([
        ["FIRST page content"],
        [],                      # 빈 페이지
        ["THIRD page content"],
    ])

    pages = extract_pages(pdf)

    assert [p.page_no for p in pages] == [1, 3], "빈 페이지를 건너뛰며 번호를 당기면 안 된다"


def test_인덱싱은_기존_청크를_지우고_다시_만든다(three_page_pdf):
    recorder = _SqlRecorder()
    service = IndexingService(FakeEmbeddingAdapter(dim=1024), chunk_size=500, chunk_overlap=100)

    with recorder.patch():
        result = service.index(issuance_id=42, pdf_bytes=three_page_pdf)

    assert result.pages == 3
    assert result.chunks == 3
    assert result.embedding_model == "fake-3gram"

    # 재인덱싱: DELETE 가 먼저, 그 다음 INSERT
    assert recorder.statements[0][0].startswith("DELETE FROM prospectus_chunk")
    assert recorder.statements[0][1] == (42,)
    assert all(s[0].startswith("INSERT INTO prospectus_chunk") for s in recorder.statements[1:])
    assert len(recorder.statements) == 1 + result.chunks
    assert recorder.committed


def test_저장되는_임베딩_차원이_스키마와_일치한다(three_page_pdf):
    recorder = _SqlRecorder()
    service = IndexingService(FakeEmbeddingAdapter(dim=1024), chunk_size=500, chunk_overlap=100)

    with recorder.patch():
        service.index(issuance_id=7, pdf_bytes=three_page_pdf)

    for statement, params in recorder.statements[1:]:
        issuance_id, page_no, chunk_index, content, embedding = params
        assert issuance_id == 7
        assert page_no >= 1
        assert chunk_index >= 0
        assert content
        assert len(embedding) == 1024


def test_페이지별_청크수_요약(three_page_pdf):
    chunks = chunk_pages(extract_pages(three_page_pdf), size=500, overlap=100)
    assert chunk_summary(chunks) == {1: 1, 2: 1, 3: 1}


def test_긴_페이지는_여러_청크로_나뉘되_같은_페이지에_머문다():
    pdf = build_pdf([[f"DELTA line {i:03d} with some padding text" for i in range(40)]])

    chunks = chunk_pages(extract_pages(pdf), size=500, overlap=100)

    assert len(chunks) > 1
    assert {c.page_no for c in chunks} == {1}
    assert [c.chunk_index for c in chunks] == list(range(len(chunks)))


class _SqlRecorder:
    """app.db.connection 을 대체해 실행된 SQL을 기록한다. DB 없이 파이프라인을 본다."""

    def __init__(self) -> None:
        self.statements: list[tuple[str, tuple]] = []
        self.committed = False

    def patch(self):
        import contextlib

        from app import db as db_module

        recorder = self

        class Cursor:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def execute(self, sql, params=()):
                recorder.statements.append((" ".join(sql.split()), tuple(params)))

        class Connection:
            def cursor(self):
                return Cursor()

            def commit(self):
                recorder.committed = True

        @contextlib.contextmanager
        def fake_connection():
            yield Connection()

        original = db_module.connection

        @contextlib.contextmanager
        def swap():
            import app.indexing.service as service_module

            service_module.connection = fake_connection
            try:
                yield
            finally:
                service_module.connection = original

        return swap()
