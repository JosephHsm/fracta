"""가드레일 메트릭.

FSD §13.3 의 fracta.ai.guardrail.blocked 는 Core(Micrometer)에 집계된다.
AI 서비스는 자기 카운터를 노출하고, Core의 AiClient 가 응답을 보고 Micrometer
카운터를 올린다 — 지표의 정본은 Core 쪽이고 여기는 자기 진단용이다.
"""

from collections import Counter
from threading import Lock


class GuardrailMetrics:

    def __init__(self) -> None:
        self._lock = Lock()
        self._blocked: Counter[str] = Counter()
        self._total = 0
        self._llm_calls = 0

    def observe(self, blocked: bool, reason: str | None, llm_called: bool) -> None:
        with self._lock:
            self._total += 1
            if llm_called:
                self._llm_calls += 1
            if blocked:
                self._blocked[reason or "UNKNOWN"] += 1

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "queries_total": self._total,
                "llm_calls_total": self._llm_calls,
                "guardrail_blocked_total": sum(self._blocked.values()),
                "guardrail_blocked_by_reason": dict(self._blocked),
            }

    def reset(self) -> None:
        with self._lock:
            self._blocked.clear()
            self._total = 0
            self._llm_calls = 0


METRICS = GuardrailMetrics()
