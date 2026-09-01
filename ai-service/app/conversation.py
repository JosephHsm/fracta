"""ai_conversation_log — 전량 기록 (FSD §10.3).

차단된 질의도 반드시 남긴다. 남기지 않으면 가드레일이 실제로 작동했다는 것을
증명할 수 없다 (phase-08 §6-7). 토큰 사용량은 비용 분석의 근거가 된다.
"""

import logging
from dataclasses import dataclass, field

from app.db import connection

logger = logging.getLogger(__name__)


@dataclass
class ConversationEntry:
    conversation_type: str  # PROSPECTUS | DEVPORTAL
    question: str
    final_answer: str
    llm_called: bool
    guardrail_stage: str  # INPUT | OUTPUT | NONE
    guardrail_blocked: bool
    provider: str
    model_id: str
    issuance_id: int | None = None
    request_id: str | None = None
    retrieved_chunk_ids: list[int] = field(default_factory=list)
    top_similarity: float | None = None
    raw_answer: str | None = None
    guardrail_reason: str | None = None
    cited_pages: list[int] = field(default_factory=list)
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: int = 0


class ConversationLog:
    """DB 적재."""

    def record(self, entry: ConversationEntry) -> None:
        with connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO ai_conversation_log ("
                    " conversation_type, issuance_id, request_id, question,"
                    " retrieved_chunk_ids, top_similarity, llm_called, raw_answer,"
                    " guardrail_stage, guardrail_blocked, guardrail_reason,"
                    " final_answer, cited_pages, provider, model_id,"
                    " input_tokens, output_tokens, latency_ms"
                    ") VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    (
                        entry.conversation_type,
                        entry.issuance_id,
                        entry.request_id,
                        entry.question,
                        entry.retrieved_chunk_ids,
                        entry.top_similarity,
                        entry.llm_called,
                        entry.raw_answer,
                        entry.guardrail_stage,
                        entry.guardrail_blocked,
                        entry.guardrail_reason,
                        entry.final_answer,
                        entry.cited_pages,
                        entry.provider,
                        entry.model_id,
                        entry.input_tokens,
                        entry.output_tokens,
                        entry.latency_ms,
                    ),
                )
            conn.commit()


class InMemoryConversationLog(ConversationLog):
    """테스트 대역. DB 없이 기록 여부를 검증한다."""

    def __init__(self) -> None:
        self.entries: list[ConversationEntry] = []

    def record(self, entry: ConversationEntry) -> None:
        self.entries.append(entry)
