"""개발자 어시스턴트 — Phase 7의 OpenAPI 스펙을 컨텍스트로 쓴다.

스펙 전문을 매 질의마다 넣으면 입력 토큰이 수만 개가 되고 비용이 질의당 몇 배가 된다.
경로·메서드·요약·스코프만 남긴 압축본을 만든 뒤, 질문과 겹치는 엔드포인트만 골라
넣는다. 투자설명서 RAG의 top-k 검색과 같은 발상이되 임베딩 없이 키워드로 한다 —
스펙은 수십 개 엔드포인트 규모라 벡터 검색을 붙일 이유가 없다.
"""

import logging
import re
import time
from dataclasses import dataclass, field

import httpx2 as httpx

from app.conversation import ConversationEntry, ConversationLog
from app.guardrail.input_guard import guard_input
from app.guardrail.output_guard import find_solicitation
from app.guardrail.prompts import (
    BLOCKED_RESPONSE,
    DEVPORTAL_NOT_FOUND_RESPONSE,
    DEVPORTAL_SCHEMA,
    DEVPORTAL_SYSTEM_PROMPT,
)
from app.guardrail.result import GuardReason
from app.metrics import GuardrailMetrics
from app.ports.llm import LlmPort

logger = logging.getLogger(__name__)

_MAX_LLM_CALLS = 2
_TOKEN = re.compile(r"[A-Za-z0-9가-힣_]+")


@dataclass(frozen=True)
class EndpointDoc:
    path: str
    method: str
    summary: str
    description: str
    scopes: list[str] = field(default_factory=list)

    def render(self) -> str:
        scope = f" (scope: {', '.join(self.scopes)})" if self.scopes else ""
        body = f"\n  {self.description}" if self.description else ""
        return f"{self.method.upper()} {self.path}{scope}\n  {self.summary}{body}"


def compact_spec(spec: dict) -> list[EndpointDoc]:
    """OpenAPI JSON을 엔드포인트 목록으로 줄인다."""
    docs: list[EndpointDoc] = []
    for path, operations in (spec.get("paths") or {}).items():
        if not isinstance(operations, dict):
            continue
        for method, operation in operations.items():
            if method.lower() not in {"get", "post", "put", "patch", "delete"}:
                continue
            if not isinstance(operation, dict):
                continue
            scopes: list[str] = []
            for requirement in operation.get("security") or []:
                for values in requirement.values():
                    scopes.extend(values or [])
            docs.append(
                EndpointDoc(
                    path=path,
                    method=method,
                    summary=(operation.get("summary") or "").strip(),
                    description=(operation.get("description") or "").strip(),
                    scopes=sorted(set(scopes)),
                )
            )
    return docs


def select_relevant(docs: list[EndpointDoc], question: str, limit: int = 8) -> list[EndpointDoc]:
    """질문 토큰과 겹치는 엔드포인트를 고른다. 겹치는 게 없으면 전부 넘기지 않고 빈 목록."""
    tokens = {t.lower() for t in _TOKEN.findall(question) if len(t) >= 2}
    if not tokens:
        return []

    scored: list[tuple[int, EndpointDoc]] = []
    for doc in docs:
        haystack = f"{doc.path} {doc.summary} {doc.description}".lower()
        score = sum(1 for t in tokens if t in haystack)
        if score:
            scored.append((score, doc))
    scored.sort(key=lambda pair: (-pair[0], pair[1].path))
    return [doc for _, doc in scored[:limit]]


class SpecLoader:
    """스펙을 가져와 캐시한다. 스펙은 배포 단위로 바뀌므로 TTL이면 충분하다."""

    def __init__(self, url: str, ttl_seconds: float = 300.0) -> None:
        self._url = url
        self._ttl = ttl_seconds
        self._cached: list[EndpointDoc] | None = None
        self._fetched_at = 0.0

    def load(self) -> list[EndpointDoc]:
        now = time.monotonic()
        if self._cached is not None and now - self._fetched_at < self._ttl:
            return self._cached
        try:
            with httpx.Client(timeout=5.0) as client:
                response = client.get(self._url)
                response.raise_for_status()
                docs = compact_spec(response.json())
        except (httpx.HTTPError, ValueError) as exc:
            logger.warning("openapi spec fetch failed: %s", type(exc).__name__)
            # 캐시가 있으면 낡은 것이라도 쓴다. Core가 잠깐 내려갔다고
            # 어시스턴트까지 죽을 이유가 없다.
            return self._cached or []
        self._cached = docs
        self._fetched_at = now
        return docs

    def seed(self, docs: list[EndpointDoc]) -> None:
        """테스트에서 스펙을 직접 주입한다."""
        self._cached = docs
        self._fetched_at = time.monotonic()


@dataclass
class DevPortalResponse:
    answer: str
    cited_endpoints: list[str] = field(default_factory=list)
    blocked: bool = False
    blocked_reason: str | None = None
    llm_called: bool = False
    model_id: str = ""
    provider: str = ""


class DevPortalAskService:

    def __init__(
        self,
        llm: LlmPort,
        spec_loader: SpecLoader,
        conversation_log: ConversationLog,
        metrics: GuardrailMetrics,
        max_tokens: int,
    ) -> None:
        self._llm = llm
        self._spec = spec_loader
        self._log = conversation_log
        self._metrics = metrics
        self._max_tokens = max_tokens

    def ask(self, question: str, request_id: str | None = None) -> DevPortalResponse:
        started = time.monotonic()

        def elapsed_ms() -> int:
            return int((time.monotonic() - started) * 1000)

        input_guard = guard_input(question)
        if input_guard.blocked:
            response = DevPortalResponse(
                answer=BLOCKED_RESPONSE,
                blocked=True,
                blocked_reason=input_guard.reason,
                provider=self._llm.provider,
            )
            self._record(question, request_id, response, stage="INPUT", latency_ms=elapsed_ms())
            return response

        relevant = select_relevant(self._spec.load(), question)
        if not relevant:
            # 스펙에서 짚이는 게 없으면 LLM을 부르지 않는다. 투자설명서 쪽의
            # 유사도 임계값과 같은 역할이다.
            response = DevPortalResponse(
                answer=DEVPORTAL_NOT_FOUND_RESPONSE, provider=self._llm.provider
            )
            self._record(question, request_id, response, stage="NONE", latency_ms=elapsed_ms())
            return response

        valid_paths = {doc.path for doc in relevant}
        user_prompt = (
            "다음은 FRACTA 오픈 API 스펙에서 발췌한 엔드포인트입니다.\n"
            "----- 스펙 발췌 시작 -----\n"
            + "\n\n".join(doc.render() for doc in relevant)
            + "\n----- 스펙 발췌 끝 -----\n\n"
            f"질문: {question}\n\n"
            "위 발췌에 근거해서만 답하고, cited_endpoints 에는 실제로 근거로 삼은 "
            "경로만 넣으세요. 발췌에서 확인할 수 없으면 found_in_spec 을 false 로 두세요."
        )

        last = None
        last_reason: str | None = None
        for _ in range(_MAX_LLM_CALLS):
            last = self._llm.complete_json(
                system=DEVPORTAL_SYSTEM_PROMPT,
                user_prompt=user_prompt,
                schema=DEVPORTAL_SCHEMA,
                max_tokens=self._max_tokens,
            )
            if last.refused:
                last_reason = GuardReason.LLM_REFUSAL
                break
            if last.parsed is None:
                last_reason = GuardReason.PARSE_FAILURE
                continue

            answer = str(last.parsed.get("answer", ""))
            endpoints = [str(e) for e in (last.parsed.get("cited_endpoints") or [])]
            found = bool(last.parsed.get("found_in_spec"))

            # API 어시스턴트도 투자권유를 하면 안 된다. 같은 패턴을 적용한다.
            if find_solicitation(answer) is not None:
                last_reason = GuardReason.INVESTMENT_SOLICITATION
                continue

            # 스펙에 없는 엔드포인트를 지어내면 환각이다.
            if endpoints and not set(endpoints).issubset(valid_paths):
                last_reason = GuardReason.HALLUCINATED_CITATION
                continue

            response = DevPortalResponse(
                answer=answer if found else DEVPORTAL_NOT_FOUND_RESPONSE,
                cited_endpoints=endpoints if found else [],
                llm_called=True,
                model_id=last.model_id,
                provider=last.provider,
            )
            self._record(question, request_id, response, stage="OUTPUT",
                         latency_ms=elapsed_ms(), llm_result=last)
            return response

        response = DevPortalResponse(
            answer=BLOCKED_RESPONSE,
            blocked=True,
            blocked_reason=last_reason,
            llm_called=True,
            model_id=last.model_id if last else "",
            provider=last.provider if last else self._llm.provider,
        )
        self._record(question, request_id, response, stage="OUTPUT",
                     latency_ms=elapsed_ms(), llm_result=last)
        return response

    def _record(self, question, request_id, response, *, stage, latency_ms, llm_result=None):
        self._metrics.observe(response.blocked, response.blocked_reason, response.llm_called)
        self._log.record(
            ConversationEntry(
                conversation_type="DEVPORTAL",
                issuance_id=None,
                request_id=request_id,
                question=question,
                llm_called=response.llm_called,
                raw_answer=llm_result.raw_text if llm_result else None,
                guardrail_stage=stage,
                guardrail_blocked=response.blocked,
                guardrail_reason=response.blocked_reason,
                final_answer=response.answer,
                provider=response.provider or self._llm.provider,
                model_id=response.model_id,
                input_tokens=llm_result.input_tokens if llm_result else 0,
                output_tokens=llm_result.output_tokens if llm_result else 0,
                latency_ms=latency_ms,
            )
        )
