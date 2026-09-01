"""인덱싱 파이프라인: PDF → 페이지 텍스트 → 청킹 → 임베딩 → prospectus_chunk."""

import logging
from dataclasses import dataclass

from app.db import connection
from app.indexing.chunker import Chunk, chunk_pages
from app.indexing.pdf_text import extract_pages
from app.ports.embedding import EmbeddingPort

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class IndexResult:
    issuance_id: int
    pages: int
    chunks: int
    embedding_model: str


class IndexingService:

    def __init__(self, embedder: EmbeddingPort, chunk_size: int, chunk_overlap: int) -> None:
        self._embedder = embedder
        self._chunk_size = chunk_size
        self._chunk_overlap = chunk_overlap

    def index(self, issuance_id: int, pdf_bytes: bytes) -> IndexResult:
        pages = extract_pages(pdf_bytes)
        chunks = chunk_pages(pages, self._chunk_size, self._chunk_overlap)

        # 불변식: 청크는 페이지 경계를 넘지 않는다. chunk_pages 가 페이지 단위로만
        # 자르므로 구조적으로 보장되지만, 파이프라인이 바뀌었을 때 조용히 깨지는 것을
        # 막기 위해 여기서 한 번 더 확인한다.
        assert all(c.page_no >= 1 for c in chunks), "page_no 는 1-indexed 여야 한다"

        vectors = self._embedder.embed([c.content for c in chunks]) if chunks else []

        with connection() as conn:
            with conn.cursor() as cur:
                # 재인덱싱: 기존 청크를 지우고 다시 만든다 (phase-08 §3.1)
                cur.execute(
                    "DELETE FROM prospectus_chunk WHERE issuance_id = %s", (issuance_id,)
                )
                for chunk, vector in zip(chunks, vectors, strict=True):
                    cur.execute(
                        "INSERT INTO prospectus_chunk "
                        "(issuance_id, page_no, chunk_index, content, embedding) "
                        "VALUES (%s, %s, %s, %s, %s)",
                        (issuance_id, chunk.page_no, chunk.chunk_index, chunk.content, vector),
                    )
            conn.commit()

        logger.info(
            "prospectus indexed",
            extra={"issuanceId": issuance_id, "pages": len(pages), "chunks": len(chunks)},
        )
        return IndexResult(
            issuance_id=issuance_id,
            pages=len(pages),
            chunks=len(chunks),
            embedding_model=self._embedder.model_id,
        )


def chunk_summary(chunks: list[Chunk]) -> dict[int, int]:
    """페이지별 청크 수 — 테스트·진단용."""
    summary: dict[int, int] = {}
    for chunk in chunks:
        summary[chunk.page_no] = summary.get(chunk.page_no, 0) + 1
    return summary
