"""종목명 의미 검색.

사람은 "코덱스"라고 치는데 마스터에는 "KODEX 200"으로 들어 있다. 문자 일치로는 안 잡힌다.
그렇다고 LLM에 "코덱스가 뭐야"라고 물으면 **없는 종목코드를 지어낼 수 있다** —
069500을 069510으로 한 글자만 틀려도 엉뚱한 종목 시세가 정상처럼 표시된다.

그래서 LLM을 부르지 않는다. 로컬 bge-m3 임베딩으로 마스터 안에서만 고른다.
존재하는 코드만 나오고, 호출 비용은 0이다(임베딩은 컨테이너 안에서 돈다).
"""
from __future__ import annotations

from dataclasses import dataclass

from collections.abc import Callable
from contextlib import AbstractContextManager

from app.ports.embedding import EmbeddingPort


@dataclass(frozen=True)
class InstrumentHit:
    code: str
    name: str
    kind: str
    similarity: float


class InstrumentSemanticIndex:
    """종목명 임베딩 인덱스. 스키마 소유는 Core(Flyway)이고 여기서는 읽고 쓴다."""

    # 임베딩 배치 크기. bge-m3는 한 번에 많이 넣을수록 빠르지만 메모리를 쓴다.
    BATCH = 256

    def __init__(
        self,
        connect: Callable[[], AbstractContextManager],
        embedding: EmbeddingPort,
    ) -> None:
        self._connect = connect
        self._embedding = embedding

    def reindex(self, kinds: tuple[str, ...] = ("ETF", "REIT")) -> int:
        """마스터에서 대상 종목을 읽어 임베딩을 다시 만든다.

        전체 4천 종목을 다 넣지 않는다 — 조각 기초자산이 될 수 있는 ETF·리츠만 넣는다.
        일반 주식까지 넣으면 인덱싱이 길어지고 검색 결과도 흐려진다.
        """
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT code, kor_name FROM instrument_master "
                "WHERE asset_kind = ANY(%s) ORDER BY code",
                (list(kinds),),
            ).fetchall()

            if not rows:
                return 0

            conn.execute("DELETE FROM instrument_embedding")
            total = 0
            for start in range(0, len(rows), self.BATCH):
                batch = rows[start : start + self.BATCH]
                vectors = self._embedding.embed([name for _, name in batch])
                for (code, _), vector in zip(batch, vectors):
                    conn.execute(
                        "INSERT INTO instrument_embedding (code, embedding) "
                        "VALUES (%s, %s::vector)",
                        (code, str(vector)),
                    )
                total += len(batch)
            conn.commit()
            return total

    def search(self, query: str, limit: int = 10, min_similarity: float = 0.35) -> list[InstrumentHit]:
        """의미가 가까운 종목을 고른다.

        임계값이 있는 이유 — 벡터 검색은 **항상** 가장 가까운 것을 돌려준다.
        "asdf"를 쳐도 뭔가 나온다. 문자 검색이 0건일 때만 호출되는 폴백이므로,
        관련 없는 결과를 내느니 빈 목록이 낫다.
        """
        text = (query or "").strip()
        if not text:
            return []

        vector = self._embedding.embed([text])[0]
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT m.code, m.kor_name, m.asset_kind, "
                "       1 - (e.embedding <=> %s::vector) AS similarity "
                "  FROM instrument_embedding e "
                "  JOIN instrument_master m ON m.code = e.code "
                " ORDER BY e.embedding <=> %s::vector "
                " LIMIT %s",
                (str(vector), str(vector), limit),
            ).fetchall()

        return [
            InstrumentHit(code=code, name=name, kind=kind, similarity=float(similarity))
            for code, name, kind, similarity in rows
            if float(similarity) >= min_similarity
        ]
