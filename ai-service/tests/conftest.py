"""테스트 공통 대역.

DB·임베딩 모델·LLM 없이 전 경로를 돌린다. 이게 EmbeddingPort 를 LlmPort 와
분리한 실질적인 이유다 — bge-m3 로드(10~30초)와 2.3GB 가중치 없이 CI가 돈다.
"""

import json
import pathlib

import pytest

from app.adapters.embedding_adapters import FakeEmbeddingAdapter
from app.adapters.fake_llm import FakeLlmAdapter
from app.ask_service import ProspectusAskService
from app.conversation import InMemoryConversationLog
from app.metrics import GuardrailMetrics
from app.retrieval.search import RetrievedChunk, SearchResult

FIXTURES = pathlib.Path(__file__).parent / "fixtures"


class StubSearch:
    """SearchService 대역. DB 없이 검색 결과를 고정한다."""

    def __init__(self, chunks: list[RetrievedChunk], threshold: float = 0.6) -> None:
        self._chunks = chunks
        self._threshold = threshold
        self.queries: list[str] = []

    @property
    def threshold(self) -> float:
        return self._threshold

    def search(self, issuance_id: int, question: str) -> SearchResult:
        self.queries.append(question)
        top = max((c.similarity for c in self._chunks), default=0.0)
        return SearchResult(chunks=list(self._chunks), top_similarity=top)

    def below_threshold(self, result: SearchResult) -> bool:
        return result.top_similarity < self._threshold


def chunk(id: int, page_no: int, content: str, similarity: float = 0.82) -> RetrievedChunk:
    return RetrievedChunk(id=id, page_no=page_no, content=content, similarity=similarity)


@pytest.fixture
def fake_llm() -> FakeLlmAdapter:
    return FakeLlmAdapter()


@pytest.fixture
def conversation_log() -> InMemoryConversationLog:
    return InMemoryConversationLog()


@pytest.fixture
def metrics() -> GuardrailMetrics:
    return GuardrailMetrics()


@pytest.fixture
def default_chunks() -> list[RetrievedChunk]:
    return [
        chunk(1, 3, "본 상품의 기초자산은 서울 강남구 소재 오피스 빌딩이다.", 0.88),
        chunk(2, 7, "주요 위험요인: 공실 발생 시 임대수익이 감소할 수 있다.", 0.81),
        chunk(3, 12, "청약 단위는 1조각이며 조각당 공모가는 10,000원이다.", 0.74),
    ]


@pytest.fixture
def ask_service(fake_llm, conversation_log, metrics, default_chunks):
    """정상 검색 결과(임계값 통과)를 전제로 한 파이프라인."""
    return ProspectusAskService(
        llm=fake_llm,
        search=StubSearch(default_chunks),
        conversation_log=conversation_log,
        metrics=metrics,
        max_tokens=16000,
    )


def load_fixture(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


@pytest.fixture
def attack_prompts() -> list[dict]:
    return load_fixture("attack_prompts.json")["prompts"]


@pytest.fixture
def injection_prompts() -> list[dict]:
    return load_fixture("injection_prompts.json")["prompts"]


@pytest.fixture
def personal_info_prompts() -> list[dict]:
    return load_fixture("injection_prompts.json")["personal_info_prompts"]
