"""EmbeddingPort — 검색 담당 (자리 ①).

LlmPort와 분리한 이유는 교체 유연성이 아니라 **테스트 대역**이다.
bge-m3 로드에 10~30초가 걸리므로, 포트가 없으면 청킹·검색·가드레일 테스트가
전부 모델 로드를 기다려야 하고 CI가 2.3GB 가중치를 받아야 한다.

임베딩은 AI_PROVIDER와 무관하게 항상 로컬이다. 외부 임베딩 API를 쓰면
질문 텍스트가 밖으로 나가므로 폐쇄망 시연이 성립하지 않는다.
"""

from abc import ABC, abstractmethod
from typing import Any


class EmbeddingPort(ABC):

    model_id: str
    dim: int

    @abstractmethod
    def embed(self, texts: list[str]) -> list[list[float]]:
        """여러 텍스트를 한 번에 임베딩한다. 반환 벡터는 L2 정규화되어 있어야 한다.

        정규화를 어댑터 책임으로 두는 이유: pgvector의 코사인 거리는 정규화 여부와
        무관하지만, 정규화되어 있으면 1 - cosine_distance 를 그대로 유사도로 쓸 수 있다.
        """

    @abstractmethod
    def health(self) -> dict[str, Any]:
        ...
