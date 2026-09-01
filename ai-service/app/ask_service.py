"""질의 파이프라인 — 가드레일 3단계를 순서대로 강제한다.

순서가 사양이다 (FSD §10.2, phase-08 §3.2):
  ① 입력 가드레일 → 차단이면 LLM 호출 없음
  ② 임베딩 → top-k 검색 → 임계값 미달이면 LLM 호출 없음
  ③ LLM 호출 → refusal 확인 → 파싱 → 출력 가드레일
어떤 경로로 끝나든 ai_conversation_log 에 한 건이 남는다.
"""

import logging
import time
from dataclasses import dataclass, field

from app.conversation import ConversationEntry, ConversationLog
from app.guardrail.input_guard import guard_input
from app.guardrail.output_guard import find_solicitation, guard_output
from app.guardrail.prompts import (
    ANSWER_SCHEMA,
    BLOCKED_RESPONSE,
    NOT_FOUND_RESPONSE,
    PROSPECTUS_SYSTEM_PROMPT,
    build_prospectus_prompt,
)
from app.guardrail.result import GuardReason
from app.metrics import GuardrailMetrics
from app.ports.llm import LlmPort, LlmResult
from app.retrieval.search import SearchService

logger = logging.getLogger(__name__)

# 재시도는 1회만 (FSD §10.3). 총 호출 2회를 넘기지 않는다.
_MAX_LLM_CALLS = 2


@dataclass
class AskResponse:
    answer: str
    cited_pages: list[int] = field(default_factory=list)
    blocked: bool = False
    # 사유는 응답에도 싣는다 — Core가 Micrometer 카운터를 올리는 근거다.
    # 프론트에는 노출하지 않는다 (어떤 패턴에 걸렸는지 알려주면 우회를 돕는 꼴이다)
    blocked_reason: str | None = None
    llm_called: bool = False
    top_similarity: float = 0.0
    model_id: str = ""
    provider: str = ""


class ProspectusAskService:

    def __init__(
        self,
        llm: LlmPort,
        search: SearchService,
        conversation_log: ConversationLog,
        metrics: GuardrailMetrics,
        max_tokens: int,
    ) -> None:
        self._llm = llm
        self._search = search
        self._log = conversation_log
        self._metrics = metrics
        self._max_tokens = max_tokens

    def ask(
        self, issuance_id: int, question: str, request_id: str | None = None
    ) -> AskResponse:
        started = time.monotonic()

        def elapsed_ms() -> int:
            return int((time.monotonic() - started) * 1000)

        # --- ① 입력 단계 ---
        input_guard = guard_input(question)
        if input_guard.blocked:
            response = AskResponse(
                answer=BLOCKED_RESPONSE,
                blocked=True,
                blocked_reason=input_guard.reason,
                llm_called=False,
                provider=self._llm.provider,
            )
            self._record(
                issuance_id, question, request_id, response,
                stage="INPUT", latency_ms=elapsed_ms(),
            )
            return response

        # --- ② 검색 + 임계값 ---
        search_result = self._search.search(issuance_id, question)
        if self._search.below_threshold(search_result):
            # LLM을 호출하지 않는다. 비용·환각 양쪽에서 이득이다.
            response = AskResponse(
                answer=NOT_FOUND_RESPONSE,
                blocked=False,
                llm_called=False,
                top_similarity=search_result.top_similarity,
                provider=self._llm.provider,
            )
            self._record(
                issuance_id, question, request_id, response,
                stage="NONE", latency_ms=elapsed_ms(),
                chunk_ids=search_result.chunk_ids,
            )
            return response

        # --- ③ LLM + 출력 단계 ---
        user_prompt = build_prospectus_prompt(question, search_result.chunks)
        last: LlmResult | None = None
        last_reason: str | None = None

        for attempt in range(_MAX_LLM_CALLS):
            last = self._llm.complete_json(
                system=PROSPECTUS_SYSTEM_PROMPT,
                user_prompt=user_prompt,
                schema=ANSWER_SCHEMA,
                max_tokens=self._max_tokens,
            )

            # content 보다 stop_reason 을 먼저 본다
            if last.refused:
                # 정책 거부는 재시도해도 같은 결과다. 크레딧만 쓴다.
                last_reason = GuardReason.LLM_REFUSAL
                break

            if last.parsed is None:
                # Ollama 등에서 스키마를 어긴 경우. 통과시키지 않는다.
                last_reason = GuardReason.PARSE_FAILURE
                continue

            answer = str(last.parsed.get("answer", ""))
            cited_pages = _as_int_list(last.parsed.get("cited_pages"))
            found = bool(last.parsed.get("found_in_document"))

            if not found:
                # 문서에 없다는 것은 정상 응답이지 차단이 아니다.
                # 단, 없다고 하면서 권유 표현을 덧붙이는 경우가 있어 본문은 검사한다.
                if find_solicitation(answer) is not None:
                    last_reason = GuardReason.INVESTMENT_SOLICITATION
                    continue
                response = AskResponse(
                    answer=NOT_FOUND_RESPONSE,
                    blocked=False,
                    llm_called=True,
                    top_similarity=search_result.top_similarity,
                    model_id=last.model_id,
                    provider=last.provider,
                )
                self._record(
                    issuance_id, question, request_id, response,
                    stage="NONE", latency_ms=elapsed_ms(),
                    chunk_ids=search_result.chunk_ids, llm_result=last,
                )
                return response

            output_guard = guard_output(answer, cited_pages, search_result.valid_pages)
            if not output_guard.blocked:
                response = AskResponse(
                    answer=answer,
                    cited_pages=cited_pages,
                    blocked=False,
                    llm_called=True,
                    top_similarity=search_result.top_similarity,
                    model_id=last.model_id,
                    provider=last.provider,
                )
                self._record(
                    issuance_id, question, request_id, response,
                    stage="OUTPUT", latency_ms=elapsed_ms(),
                    chunk_ids=search_result.chunk_ids, llm_result=last,
                )
                return response

            last_reason = output_guard.reason
            logger.warning(
                "guardrail blocked llm output",
                extra={"attempt": attempt + 1, "reason": last_reason},
            )

        # 재시도까지 실패 — 고정 문구를 반환한다
        response = AskResponse(
            answer=BLOCKED_RESPONSE,
            blocked=True,
            blocked_reason=last_reason,
            llm_called=True,
            top_similarity=search_result.top_similarity,
            model_id=last.model_id if last else "",
            provider=last.provider if last else self._llm.provider,
        )
        self._record(
            issuance_id, question, request_id, response,
            stage="OUTPUT", latency_ms=elapsed_ms(),
            chunk_ids=search_result.chunk_ids, llm_result=last,
        )
        return response

    def _record(
        self,
        issuance_id: int,
        question: str,
        request_id: str | None,
        response: AskResponse,
        *,
        stage: str,
        latency_ms: int,
        chunk_ids: list[int] | None = None,
        llm_result: LlmResult | None = None,
    ) -> None:
        self._metrics.observe(response.blocked, response.blocked_reason, response.llm_called)
        entry = ConversationEntry(
            conversation_type="PROSPECTUS",
            issuance_id=issuance_id,
            request_id=request_id,
            question=question,
            retrieved_chunk_ids=chunk_ids or [],
            top_similarity=response.top_similarity,
            llm_called=response.llm_called,
            raw_answer=llm_result.raw_text if llm_result else None,
            guardrail_stage=stage,
            guardrail_blocked=response.blocked,
            # 컬럼이 VARCHAR(40) 이다. 사유 코드만 남긴다
            guardrail_reason=(response.blocked_reason or None),
            final_answer=response.answer,
            cited_pages=response.cited_pages,
            provider=response.provider or self._llm.provider,
            model_id=response.model_id,
            input_tokens=llm_result.input_tokens if llm_result else 0,
            output_tokens=llm_result.output_tokens if llm_result else 0,
            latency_ms=latency_ms,
        )
        self._log.record(entry)


def _as_int_list(value: object) -> list[int]:
    """모델이 페이지 번호를 문자열로 줄 수 있다. 숫자로 수렴시키되 실패는 버린다."""
    if not isinstance(value, list):
        return []
    pages: list[int] = []
    for item in value:
        try:
            pages.append(int(item))
        except (TypeError, ValueError):
            continue
    return pages
