"""ClaudeAdapter — 기본 프로바이더 (phase-08 §3.3).

금지 사항 (현행 모델에서 400):
  - 어시스턴트 프리필로 형식 강제  → output_config.format 사용
  - budget_tokens                 → output_config.effort 사용
  - temperature / top_p           → 제거됨

stop_reason == "refusal" 을 content보다 **먼저** 확인한다.
"""

import json
import logging
from typing import Any

import anthropic

from app.ports.llm import LlmPort, LlmResult, LlmUnavailableError

logger = logging.getLogger(__name__)

# fallbacks="default" 의 짝. 배열 형식(-2026-06-01)과 섞으면 400이다.
_FALLBACK_BETA = "server-side-fallback-2026-07-01"


class ClaudeAdapter(LlmPort):

    provider = "claude"

    def __init__(self, model: str, effort: str, timeout_seconds: float) -> None:
        # 키는 환경에서 해석된다(ANTHROPIC_API_KEY 또는 ant auth 프로필).
        # 코드에 키를 넣지 않으며, 로그·에러 응답에도 싣지 않는다.
        self._client = anthropic.Anthropic(timeout=timeout_seconds)
        self._model = model
        self._effort = effort

    def complete_json(
        self,
        system: str,
        user_prompt: str,
        schema: dict[str, Any],
        max_tokens: int,
    ) -> LlmResult:
        try:
            response = self._client.beta.messages.create(
                model=self._model,
                max_tokens=max_tokens,
                system=system,
                messages=[{"role": "user", "content": user_prompt}],
                output_config={
                    "effort": self._effort,
                    "format": {"type": "json_schema", "schema": schema},
                },
                # 정책 거부 시 서버측에서 대체 모델로 재실행한다. 거부 사유 범주에 따라
                # Anthropic이 대상을 고르므로 모델을 직접 지정하지 않는다.
                betas=[_FALLBACK_BETA],
                fallbacks="default",
            )
        except anthropic.APIStatusError as exc:
            # 상태코드만 남긴다. 응답 본문에는 요청 에코가 들어갈 수 있다
            raise LlmUnavailableError(f"claude api status {exc.status_code}") from None
        except anthropic.APIConnectionError:
            raise LlmUnavailableError("claude api unreachable") from None

        usage_in = getattr(response.usage, "input_tokens", 0) or 0
        usage_out = getattr(response.usage, "output_tokens", 0) or 0

        # content보다 먼저 본다. refusal 시 content는 비어 있거나 부분 응답이다.
        if response.stop_reason == "refusal":
            details = response.stop_details
            category = getattr(details, "category", None) if details else None
            return LlmResult(
                raw_text="",
                parsed=None,
                refused=True,
                model_id=self._model,
                provider=self.provider,
                input_tokens=usage_in,
                output_tokens=usage_out,
                diagnostics={"refusal_category": category},
            )

        text = next((b.text for b in response.content if b.type == "text"), "")
        parsed, diagnostics = parse_json_object(text)

        return LlmResult(
            raw_text=text,
            parsed=parsed,
            refused=False,
            model_id=self._model,
            provider=self.provider,
            input_tokens=usage_in,
            output_tokens=usage_out,
            diagnostics=diagnostics,
        )

    def health(self) -> dict[str, Any]:
        # 상태 확인에 실제 추론 호출을 쓰지 않는다 (헬스체크마다 과금될 이유가 없다).
        # 자격증명 해석 가능 여부만 본다.
        try:
            configured = bool(self._client.api_key or self._client.auth_token)
        except Exception:  # noqa: BLE001 - SDK 내부 속성 변화에 헬스체크가 죽지 않게 한다
            configured = False
        return {
            "provider": self.provider,
            "model": self._model,
            "effort": self._effort,
            "credentials_configured": configured,
        }


def parse_json_object(text: str) -> tuple[dict[str, Any] | None, dict[str, Any]]:
    """구조화 출력을 파싱한다. 실패는 감추지 않고 None으로 드러낸다."""
    if not text or not text.strip():
        return None, {"parse_error": "EMPTY_RESPONSE"}
    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        return None, {"parse_error": f"JSON_DECODE: {exc.msg}"}
    if not isinstance(value, dict):
        return None, {"parse_error": "NOT_AN_OBJECT"}
    return value, {}
