"""FakeLlmAdapter — 테스트 대역.

실호출 없이 가드레일 전 경로를 검증하기 위한 어댑터다. 크레딧을 쓰지 않고,
'모델이 반드시 투자권유 표현을 뱉는' 상황처럼 실물로는 재현하기 어려운 입력을
주입할 수 있어 오히려 가드레일 검증이 강해진다.

캡처한 실제 Claude 응답(tests/fixtures/claude/*.json)을 재생할 때도 이걸 쓴다.
"""

import json
from collections import deque
from typing import Any

from app.ports.llm import LlmPort, LlmResult


class FakeLlmAdapter(LlmPort):

    provider = "fake"

    def __init__(self, model: str = "fake-model") -> None:
        self._model = model
        self._queue: deque[LlmResult] = deque()
        self._default: LlmResult | None = None
        self.calls: list[dict[str, Any]] = []

    # --- 대역 설정 ---

    def push(self, result: LlmResult) -> "FakeLlmAdapter":
        self._queue.append(result)
        return self

    def push_answer(
        self,
        answer: str,
        cited_pages: list[int],
        found_in_document: bool = True,
    ) -> "FakeLlmAdapter":
        payload = {
            "answer": answer,
            "cited_pages": cited_pages,
            "found_in_document": found_in_document,
        }
        return self.push(
            LlmResult(
                raw_text=json.dumps(payload, ensure_ascii=False),
                parsed=payload,
                model_id=self._model,
                provider=self.provider,
                input_tokens=100,
                output_tokens=50,
            )
        )

    def push_raw(self, raw_text: str) -> "FakeLlmAdapter":
        """스키마를 어긴 원문을 그대로 흘린다 — Ollama 파싱 실패 재현용."""
        parsed = None
        diagnostics: dict[str, Any] = {"parse_error": "JSON_DECODE"}
        try:
            value = json.loads(raw_text)
            if isinstance(value, dict):
                parsed, diagnostics = value, {}
        except json.JSONDecodeError:
            pass
        return self.push(
            LlmResult(
                raw_text=raw_text,
                parsed=parsed,
                model_id=self._model,
                provider=self.provider,
                diagnostics=diagnostics,
            )
        )

    def push_refusal(self, category: str = "reasoning_extraction") -> "FakeLlmAdapter":
        return self.push(
            LlmResult(
                raw_text="",
                parsed=None,
                refused=True,
                model_id=self._model,
                provider=self.provider,
                diagnostics={"refusal_category": category},
            )
        )

    # --- LlmPort ---

    def complete_json(
        self,
        system: str,
        user_prompt: str,
        schema: dict[str, Any],
        max_tokens: int,
    ) -> LlmResult:
        self.calls.append({"system": system, "user_prompt": user_prompt})
        if self._queue:
            return self._queue.popleft()
        if self._default is not None:
            return self._default
        raise AssertionError(
            "FakeLlmAdapter 에 준비된 응답이 없는데 호출됐다. "
            "LLM을 호출하지 않아야 하는 경로라면 이 실패가 정답이다."
        )

    def health(self) -> dict[str, Any]:
        return {"provider": self.provider, "model": self._model, "reachable": True}

    @property
    def call_count(self) -> int:
        return len(self.calls)
