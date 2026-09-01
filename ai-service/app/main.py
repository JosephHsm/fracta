"""FastAPI 진입점 (FSD §10.1).

Core(Java)는 HTTP로만 호출한다. 이 서비스가 죽어도 본 서비스는 계속 동작해야 하므로
Core 쪽에 타임아웃 + 서킷브레이커가 걸려 있다 (com.fracta.ai.AiCircuitBreaker).
"""

import logging
from contextlib import asynccontextmanager
from dataclasses import dataclass

from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field

from app import db
from app.adapters.factory import build_embedding, build_llm
from app.ask_service import ProspectusAskService
from app.config import Settings, get_settings
from app.conversation import ConversationLog
from app.devportal import DevPortalAskService, SpecLoader
from app.indexing.service import IndexingService
from app.metrics import METRICS, GuardrailMetrics
from app.ports.embedding import EmbeddingPort
from app.ports.llm import LlmPort, LlmUnavailableError
from app.retrieval.search import SearchService
from app.storage import MinioProspectusReader, ProspectusReader

logger = logging.getLogger(__name__)


@dataclass
class AppContext:
    settings: Settings
    llm: LlmPort
    embedder: EmbeddingPort
    search: SearchService
    indexing: IndexingService
    reader: ProspectusReader
    prospectus: ProspectusAskService
    devportal: DevPortalAskService
    metrics: GuardrailMetrics


def build_context(
    settings: Settings,
    *,
    llm: LlmPort | None = None,
    embedder: EmbeddingPort | None = None,
    reader: ProspectusReader | None = None,
    conversation_log: ConversationLog | None = None,
    metrics: GuardrailMetrics | None = None,
) -> AppContext:
    """의존성 조립. 테스트는 대역을 주입해 같은 경로를 그대로 검증한다."""
    llm = llm or build_llm(settings)
    embedder = embedder or build_embedding(settings)
    conversation_log = conversation_log or ConversationLog()
    metrics = metrics or METRICS
    reader = reader or MinioProspectusReader(
        endpoint=settings.minio_endpoint,
        access_key=settings.minio_access_key,
        secret_key=settings.minio_secret_key,
        bucket=settings.minio_bucket,
        secure=settings.minio_secure,
    )

    search = SearchService(embedder, settings.top_k, settings.similarity_threshold)
    indexing = IndexingService(embedder, settings.chunk_size, settings.chunk_overlap)

    return AppContext(
        settings=settings,
        llm=llm,
        embedder=embedder,
        search=search,
        indexing=indexing,
        reader=reader,
        prospectus=ProspectusAskService(
            llm, search, conversation_log, metrics, settings.ai_max_tokens
        ),
        devportal=DevPortalAskService(
            llm,
            SpecLoader(settings.openapi_spec_url),
            conversation_log,
            metrics,
            settings.ai_max_tokens,
        ),
        metrics=metrics,
    )


_context: AppContext | None = None


def get_context() -> AppContext:
    if _context is None:
        raise HTTPException(status_code=503, detail="AI 서비스가 아직 준비되지 않았다")
    return _context


# --- 요청/응답 모델 ---


class IndexRequest(BaseModel):
    issuance_id: int = Field(gt=0)
    file_key: str = Field(min_length=1)


class IndexResponse(BaseModel):
    issuance_id: int
    pages: int
    chunks: int
    embedding_model: str


class ProspectusAskRequest(BaseModel):
    issuance_id: int = Field(gt=0)
    question: str = Field(min_length=1, max_length=2000)
    request_id: str | None = None


class ProspectusAskResponse(BaseModel):
    answer: str
    cited_pages: list[int]
    blocked: bool
    blocked_reason: str | None
    llm_called: bool
    top_similarity: float
    model_id: str
    provider: str


class DevPortalAskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    request_id: str | None = None


class DevPortalAskResponse(BaseModel):
    answer: str
    cited_endpoints: list[str]
    blocked: bool
    blocked_reason: str | None
    llm_called: bool
    model_id: str
    provider: str


# --- 앱 ---


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _context
    settings = get_settings()
    db.init_pool(settings.db_dsn)
    _context = build_context(settings)
    logger.info(
        "ai service started",
        extra={"provider": settings.ai_provider, "model": settings.ai_model},
    )
    yield
    db.close_pool()
    _context = None


def create_app(context: AppContext | None = None) -> FastAPI:
    """context 를 주면 lifespan(DB 연결) 없이 뜬다 — 테스트용."""
    if context is not None:
        global _context
        _context = context
        app = FastAPI(title="FRACTA AI Service", version="0.1.0")
    else:
        app = FastAPI(title="FRACTA AI Service", version="0.1.0", lifespan=lifespan)

    @app.post("/ai/prospectus/index", response_model=IndexResponse)
    def index_prospectus(
        request: IndexRequest, ctx: AppContext = Depends(get_context)
    ) -> IndexResponse:
        try:
            pdf_bytes = ctx.reader.read(request.file_key)
        except FileNotFoundError:
            raise HTTPException(status_code=404, detail="투자설명서 파일을 찾을 수 없다")
        result = ctx.indexing.index(request.issuance_id, pdf_bytes)
        return IndexResponse(
            issuance_id=result.issuance_id,
            pages=result.pages,
            chunks=result.chunks,
            embedding_model=result.embedding_model,
        )

    @app.post("/ai/prospectus/ask", response_model=ProspectusAskResponse)
    def ask_prospectus(
        request: ProspectusAskRequest, ctx: AppContext = Depends(get_context)
    ) -> ProspectusAskResponse:
        try:
            result = ctx.prospectus.ask(
                request.issuance_id, request.question, request.request_id
            )
        except LlmUnavailableError as exc:
            # 가드레일 차단으로 위장하지 않는다. Core의 서킷브레이커가 이걸 보고 열린다.
            raise HTTPException(status_code=503, detail=str(exc))
        return ProspectusAskResponse(**vars(result))

    @app.post("/ai/devportal/ask", response_model=DevPortalAskResponse)
    def ask_devportal(
        request: DevPortalAskRequest, ctx: AppContext = Depends(get_context)
    ) -> DevPortalAskResponse:
        try:
            result = ctx.devportal.ask(request.question, request.request_id)
        except LlmUnavailableError as exc:
            raise HTTPException(status_code=503, detail=str(exc))
        return DevPortalAskResponse(**vars(result))

    @app.get("/ai/health")
    def health(ctx: AppContext = Depends(get_context)) -> dict:
        # 임베딩 모델·DB·LLM 프로바이더 상태를 모두 반영한다 (phase-08 §5)
        components = {
            "database": db.healthy(),
            "embedding": ctx.embedder.health(),
            "llm": ctx.llm.health(),
        }
        up = components["database"].get("reachable", False)
        return {"status": "UP" if up else "DOWN", "components": components}

    @app.get("/ai/metrics")
    def metrics(ctx: AppContext = Depends(get_context)) -> dict:
        return ctx.metrics.snapshot()

    return app


app = create_app()
