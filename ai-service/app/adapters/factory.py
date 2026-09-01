"""프로바이더 선택. 전환은 AI_PROVIDER 환경변수 한 줄이다 (FSD §10.4)."""

from app.config import Settings
from app.ports.embedding import EmbeddingPort
from app.ports.llm import LlmPort


def build_llm(settings: Settings) -> LlmPort:
    provider = settings.ai_provider.lower()
    if provider == "claude":
        from app.adapters.claude_adapter import ClaudeAdapter

        return ClaudeAdapter(
            model=settings.ai_model,
            effort=settings.ai_effort,
            timeout_seconds=settings.ai_timeout_seconds,
        )
    if provider == "ollama":
        from app.adapters.ollama_adapter import OllamaAdapter

        return OllamaAdapter(
            host=settings.ollama_host,
            model=settings.ollama_model,
            timeout_seconds=settings.ai_timeout_seconds,
        )
    if provider == "fake":
        from app.adapters.fake_llm import FakeLlmAdapter

        return FakeLlmAdapter()
    raise ValueError(f"알 수 없는 AI_PROVIDER: {settings.ai_provider}")


def build_embedding(settings: Settings) -> EmbeddingPort:
    """임베딩은 AI_PROVIDER 를 따르지 않는다 — 항상 로컬이다.

    외부 임베딩 API를 쓰면 질문 텍스트가 밖으로 나가므로, LLM만 로컬로 돌려도
    폐쇄망이 성립하지 않는다. 대역은 테스트에서만 명시적으로 주입한다.
    """
    if settings.embedding_model == "fake":
        from app.adapters.embedding_adapters import FakeEmbeddingAdapter

        return FakeEmbeddingAdapter(dim=settings.embedding_dim)

    from app.adapters.embedding_adapters import LocalBgeM3Adapter

    return LocalBgeM3Adapter(model_id=settings.embedding_model, dim=settings.embedding_dim)
