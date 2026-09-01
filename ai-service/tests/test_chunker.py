"""청킹 — 페이지 경계 유지가 핵심 불변식이다 (FSD §10.2)."""

import pytest

from app.indexing.chunker import Chunk, PageText, chunk_page_text, chunk_pages
from app.indexing.service import chunk_summary


def test_페이지를_넘는_청크가_생기지_않는다():
    pages = [
        PageText(page_no=1, text="가" * 1200),
        PageText(page_no=2, text="나" * 1200),
        PageText(page_no=3, text="다" * 300),
    ]

    chunks = chunk_pages(pages, size=500, overlap=100)

    # 모든 청크의 page_no 가 단일값이라는 것은 자료구조상 자명하지만,
    # 각 페이지의 내용이 섞이지 않았는지가 실질적인 검증이다.
    for c in chunks:
        assert len(set(c.content)) == 1, "청크 안에 두 페이지의 문자가 섞였다"

    by_page = {c.page_no: c.content[0] for c in chunks}
    assert by_page == {1: "가", 2: "나", 3: "다"}


def test_페이지별_청크_인덱스가_0부터_매겨진다():
    pages = [PageText(page_no=5, text="가" * 1000), PageText(page_no=6, text="나" * 1000)]

    chunks = chunk_pages(pages, size=500, overlap=100)

    for page_no in (5, 6):
        indexes = [c.chunk_index for c in chunks if c.page_no == page_no]
        assert indexes == list(range(len(indexes)))


def test_오버랩이_실제로_겹친다():
    text = "".join(str(i % 10) for i in range(1000))

    pieces = chunk_page_text(text, size=500, overlap=100)

    assert len(pieces) >= 2
    # 첫 청크의 끝 100자 == 둘째 청크의 앞 100자
    assert pieces[0][-100:] == pieces[1][:100]


def test_페이지가_청크_크기보다_짧으면_한_덩어리():
    pieces = chunk_page_text("짧은 문단이다.", size=500, overlap=100)
    assert pieces == ["짧은 문단이다."]


def test_공백만_있는_페이지는_청크를_만들지_않는다():
    assert chunk_page_text("   \n\t  ", size=500, overlap=100) == []
    assert chunk_pages([PageText(1, "   ")], size=500, overlap=100) == []


def test_연속_공백은_하나로_정규화된다():
    pieces = chunk_page_text("가나  다\n\n라\t마", size=500, overlap=100)
    assert pieces == ["가나 다 라 마"]


def test_오버랩이_크기_이상이면_설정_오류로_죽는다():
    # step 이 0 이하가 되면 무한 루프다. 조용히 도는 것보다 여기서 죽는 게 낫다.
    with pytest.raises(ValueError):
        chunk_page_text("가" * 1000, size=500, overlap=500)
    with pytest.raises(ValueError):
        chunk_page_text("가" * 1000, size=500, overlap=600)


def test_긴_페이지도_끝까지_담긴다():
    text = "".join(str(i % 10) for i in range(2345))

    pieces = chunk_page_text(text, size=500, overlap=100)

    assert pieces[-1].endswith(text[-10:])
    assert "".join(dict.fromkeys(pieces))  # 청크가 비어 있지 않다


def test_페이지별_청크수_요약():
    chunks = [Chunk(1, 0, "a"), Chunk(1, 1, "b"), Chunk(4, 0, "c")]
    assert chunk_summary(chunks) == {1: 2, 4: 1}
