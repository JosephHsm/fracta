"""검색 — 코사인 유사도 top-k + 임계값 (FSD §10.2).

임계값 검사는 **LLM 호출 이전에** 한다. 이후에 하면 비용만 쓰고 버리는 꼴이다
(phase-08 §6-6).
"""

from dataclasses import dataclass

from app.db import connection
from app.ports.embedding import EmbeddingPort


@dataclass(frozen=True)
class RetrievedChunk:
    id: int
    page_no: int
    content: str
    similarity: float


@dataclass(frozen=True)
class SearchResult:
    chunks: list[RetrievedChunk]
    top_similarity: float

    @property
    def valid_pages(self) -> set[int]:
        return {c.page_no for c in self.chunks}

    @property
    def chunk_ids(self) -> list[int]:
        return [c.id for c in self.chunks]


class SearchService:

    def __init__(self, embedder: EmbeddingPort, top_k: int, threshold: float) -> None:
        self._embedder = embedder
        self._top_k = top_k
        self._threshold = threshold

    @property
    def threshold(self) -> float:
        return self._threshold

    def search(self, issuance_id: int, question: str) -> SearchResult:
        vector = self._embedder.embed([question])[0]

        with connection() as conn:
            with conn.cursor() as cur:
                # <=> 는 pgvector 의 코사인 거리다. 유사도 = 1 - 거리.
                # 바인딩 파라미터만 쓴다 (문자열 연결 금지)
                cur.execute(
                    "SELECT id, page_no, content, 1 - (embedding <=> %s) AS similarity "
                    "FROM prospectus_chunk "
                    "WHERE issuance_id = %s "
                    "ORDER BY embedding <=> %s "
                    "LIMIT %s",
                    (vector, issuance_id, vector, self._top_k),
                )
                rows = cur.fetchall()

        chunks = [
            RetrievedChunk(id=r[0], page_no=r[1], content=r[2], similarity=float(r[3]))
            for r in rows
        ]
        top = max((c.similarity for c in chunks), default=0.0)
        return SearchResult(chunks=chunks, top_similarity=top)

    def below_threshold(self, result: SearchResult) -> bool:
        return result.top_similarity < self._threshold
