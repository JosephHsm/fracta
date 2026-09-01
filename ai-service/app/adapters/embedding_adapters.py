"""EmbeddingPort 구현체 — 로컬 bge-m3 와 테스트 대역.

bge-m3(1024차원)는 AI_PROVIDER 와 무관하게 항상 로컬에서 돈다.
노트북(Ryzen 5 5625U, GPU 없음)에서도 CPU 추론으로 충분히 실용적이고,
데스크탑(RTX 4080)에서는 GPU를 자동으로 쓴다.
"""

import hashlib
import math
from typing import Any

from app.ports.embedding import EmbeddingPort


class LocalBgeM3Adapter(EmbeddingPort):
    """sentence-transformers 로 bge-m3 를 로컬 구동한다.

    import 를 __init__ 안에 두는 이유: torch 는 optional extra 라서 CI·단위 테스트
    환경에는 설치되지 않는다. 모듈 최상단에서 import 하면 대역을 쓰는 테스트까지
    ImportError 로 죽는다.
    """

    def __init__(self, model_id: str, dim: int) -> None:
        from sentence_transformers import SentenceTransformer

        self.model_id = model_id
        self.dim = dim
        self._model = SentenceTransformer(model_id)
        actual = self._model.get_sentence_embedding_dimension()
        if actual != dim:
            # 여기서 죽는 편이 낫다. 차원이 어긋나면 INSERT 시점에 정체불명의
            # PG 오류가 나고, 그때는 어느 설정이 틀렸는지 추적하기 어렵다.
            raise ValueError(
                f"임베딩 차원 불일치: {model_id}={actual}, 설정/스키마={dim}. "
                "EMBEDDING_DIM 과 V8__ai.sql 의 VECTOR(n) 을 함께 맞춰야 한다."
            )

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        vectors = self._model.encode(texts, normalize_embeddings=True)
        return [[float(x) for x in v] for v in vectors]

    def health(self) -> dict[str, Any]:
        return {"model": self.model_id, "dim": self.dim, "loaded": True}


class FakeEmbeddingAdapter(EmbeddingPort):
    """결정적 문자 3-gram 해싱 임베딩.

    난수나 단순 해시를 쓰면 모든 텍스트가 서로 직교해서 유사도가 항상 0이 되고,
    '임계값 미달' 테스트만 되고 '정상 검색' 테스트는 못 한다. 3-gram 을 버킷에
    누적하면 겹치는 문자열끼리 실제로 높은 코사인 유사도가 나오므로,
    임계값 상·하 양쪽 경로를 모두 검증할 수 있다.
    """

    def __init__(self, dim: int = 1024) -> None:
        self.model_id = "fake-3gram"
        self.dim = dim

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._embed_one(t) for t in texts]

    def _embed_one(self, text: str) -> list[float]:
        vector = [0.0] * self.dim
        normalized = " ".join(text.split()).lower()
        grams = [normalized[i : i + 3] for i in range(max(len(normalized) - 2, 1))]
        for gram in grams:
            digest = hashlib.sha256(gram.encode("utf-8")).digest()
            bucket = int.from_bytes(digest[:4], "big") % self.dim
            vector[bucket] += 1.0
        norm = math.sqrt(sum(x * x for x in vector))
        if norm == 0.0:
            # 빈 문자열. 0벡터는 pgvector 코사인 연산에서 NaN 을 만든다
            vector[0] = 1.0
            return vector
        return [x / norm for x in vector]

    def health(self) -> dict[str, Any]:
        return {"model": self.model_id, "dim": self.dim, "loaded": True}
