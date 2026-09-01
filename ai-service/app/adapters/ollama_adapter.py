"""OllamaAdapter — 폐쇄망 시연용 (FSD §10.4).

Ollama는 /api/chat 의 format 필드에 JSON 스키마를 그대로 받는다. Claude의
output_config.format 과 1:1로 대응되므로 두 어댑터가 같은 계약을 지킬 수 있다.

핵심 규칙: **파싱 실패를 통과 처리하지 않는다** (phase-08 §6-8).
로컬 모델은 스키마 준수도가 Claude보다 낮으므로, parsed=None 을 그대로 올려보내
호출부가 NO_CITATION 으로 차단하게 한다. 여기서 봐주면 가드레일이 우회된다.
"""

import logging
from typing import Any

import httpx2 as httpx

from app.adapters.claude_adapter import parse_json_object
from app.ports.llm import LlmPort, LlmResult, LlmUnavailableError

logger = logging.getLogger(__name__)


class OllamaAdapter(LlmPort):

    provider = "ollama"

    def __init__(self, host: str, model: str, timeout_seconds: float) -> None:
        self._host = host.rstrip("/")
        self._model = model
        self._timeout = timeout_seconds

    def complete_json(
        self,
        system: str,
        user_prompt: str,
        schema: dict[str, Any],
        max_tokens: int,
    ) -> LlmResult:
        payload = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user_prompt},
            ],
            # 스키마 강제. Claude의 output_config.format 과 같은 역할이다
            "format": schema,
            "stream": False,
            # temperature / top_p 는 쓰지 않는다 — 두 프로바이더의 호출 형태를
            # 어긋나게 두면 비교표가 같은 조건을 비교하지 못한다
            "options": {"num_predict": max_tokens},
        }
        try:
            with httpx.Client(timeout=self._timeout) as client:
                response = client.post(f"{self._host}/api/chat", json=payload)
                response.raise_for_status()
                body = response.json()
        except httpx.HTTPStatusError as exc:
            raise LlmUnavailableError(
                f"ollama status {exc.response.status_code}"
            ) from None
        except httpx.HTTPError:
            raise LlmUnavailableError("ollama unreachable") from None

        text = (body.get("message") or {}).get("content", "") or ""
        parsed, diagnostics = parse_json_object(text)

        return LlmResult(
            raw_text=text,
            parsed=parsed,
            # Ollama에는 정책 거부 개념이 없다. refusal 경로는 Claude 전용이다
            refused=False,
            model_id=self._model,
            provider=self.provider,
            input_tokens=int(body.get("prompt_eval_count") or 0),
            output_tokens=int(body.get("eval_count") or 0),
            diagnostics=diagnostics,
        )

    def health(self) -> dict[str, Any]:
        state: dict[str, Any] = {"provider": self.provider, "model": self._model}
        try:
            with httpx.Client(timeout=3.0) as client:
                tags = client.get(f"{self._host}/api/tags")
                tags.raise_for_status()
                names = [m.get("name", "") for m in tags.json().get("models", [])]
            state["reachable"] = True
            # ollama는 태그를 'qwen3:14b' 형태로 돌려준다. latest 생략 표기를 감안한다
            state["model_pulled"] = any(
                n == self._model or n.split(":")[0] == self._model.split(":")[0]
                for n in names
            )
        except httpx.HTTPError:
            state["reachable"] = False
            state["model_pulled"] = False
        return state
