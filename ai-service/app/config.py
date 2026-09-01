"""AI 서비스 설정. 모델 ID는 전부 설정값이다 — 하드코딩 금지 (phase-08 §0)."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # --- LLM (자리 ②: 작문 담당) ---
    # claude | ollama | fake — 전환은 이 한 줄이다 (FSD §10.4)
    ai_provider: str = "claude"
    # 날짜 접미사를 붙이지 않는다. claude-opus-5-20260101 같은 ID는 존재하지 않는다
    ai_model: str = "claude-opus-5"
    # 문서 인용 QA는 medium이면 충분. low|medium|high|xhigh|max
    ai_effort: str = "medium"
    # Opus 5는 adaptive thinking이 기본 ON이라 사고 토큰이 여기서 나간다.
    # phase-08 §3.3의 4096은 잘릴 수 있어 상향했다 (SDK 권장 비스트리밍 기본값)
    ai_max_tokens: int = 16000
    ai_timeout_seconds: float = 120.0

    ollama_host: str = "http://localhost:11434"
    # 데스크탑(RTX 4080/16GB) 실측 후 확정한다. 후보: qwen3:14b, gpt-oss:20b
    ollama_model: str = "qwen3:14b"

    # --- 임베딩 (자리 ①: 검색 담당) ---
    # LLM 프로바이더와 무관하게 항상 로컬이다. 외부로 나가면 폐쇄망이 성립하지 않는다.
    embedding_model: str = "BAAI/bge-m3"
    # V8__ai.sql의 VECTOR(1024)와 반드시 일치해야 한다
    embedding_dim: int = 1024

    # --- 검색 ---
    # FSD §10.2 기준값. 실측 후 조정하고 근거를 docs/ai/에 남긴다
    similarity_threshold: float = 0.6
    top_k: int = 5

    # --- 청킹 (FSD §10.2) ---
    chunk_size: int = 500
    chunk_overlap: int = 100

    # --- 인프라 ---
    db_dsn: str = "postgresql://fracta:fracta@localhost:5432/fracta"
    minio_endpoint: str = "localhost:9000"
    minio_access_key: str = "minioadmin"
    minio_secret_key: str = "minioadmin"
    minio_bucket: str = "prospectus"
    minio_secure: bool = False

    # 개발자 어시스턴트가 컨텍스트로 쓰는 Phase 7 산출물
    openapi_spec_url: str = "http://localhost:8080/v3/api-docs"


@lru_cache
def get_settings() -> Settings:
    return Settings()
