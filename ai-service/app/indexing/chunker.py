"""청킹 — 500자, 100자 오버랩, **페이지 경계 유지** (FSD §10.2).

페이지 경계 유지가 이 파이프라인에서 가장 중요한 제약이다. 청크가 페이지를
넘나들면 인용 페이지 번호가 부정확해지고, 그 순간 인용 기반 신뢰가 무너진다.
따라서 청킹은 페이지 단위로만 수행하고, 청크 하나는 항상 단일 page_no 를 갖는다.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class PageText:
    page_no: int  # 1-indexed. 사용자에게 보이는 번호와 일치
    text: str


@dataclass(frozen=True)
class Chunk:
    page_no: int
    chunk_index: int  # 해당 페이지 내 순번
    content: str


def chunk_page_text(text: str, size: int, overlap: int) -> list[str]:
    """한 페이지의 텍스트를 겹치는 창으로 자른다."""
    if size <= 0:
        raise ValueError("chunk_size 는 1 이상이어야 한다")
    if overlap < 0 or overlap >= size:
        # step 이 0 이하가 되면 무한 루프다. 설정 실수를 여기서 잡는다
        raise ValueError(f"chunk_overlap({overlap}) 은 0 이상 chunk_size({size}) 미만이어야 한다")

    normalized = " ".join(text.split())
    if not normalized:
        return []
    if len(normalized) <= size:
        return [normalized]

    step = size - overlap
    pieces: list[str] = []
    start = 0
    while start < len(normalized):
        piece = normalized[start : start + size]
        if piece:
            pieces.append(piece)
        if start + size >= len(normalized):
            break
        start += step
    return pieces


def chunk_pages(pages: list[PageText], size: int, overlap: int) -> list[Chunk]:
    """페이지 목록을 청크 목록으로 변환한다. 청크는 페이지를 넘지 않는다."""
    chunks: list[Chunk] = []
    for page in pages:
        for index, content in enumerate(chunk_page_text(page.text, size, overlap)):
            chunks.append(Chunk(page_no=page.page_no, chunk_index=index, content=content))
    return chunks
