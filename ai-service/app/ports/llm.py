"""LlmPort — 폐쇄망 대응 (FSD §10.4).

Claude의 refusal과 Ollama의 정상 응답을 하나의 인터페이스로 다루기 위해
LlmResult에 refused 플래그를 둔다. 어댑터는 JSON 파싱 실패를 흡수하되,
실패를 성공으로 위장하지 않는다 — parsed=None 이면 호출부가 차단한다.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


class LlmUnavailableError(RuntimeError):
    """전송·인프라 장애. 모델의 판단(refusal·파싱 실패)과 구분한다.

    이걸 LlmResult로 흡수해버리면 네트워크 장애가 가드레일 차단처럼 보이고,
    Core의 서킷브레이커가 열릴 근거를 잃는다.
    """


@dataclass(frozen=True)
class LlmResult:
    """LLM 1회 호출의 결과."""

    raw_text: str
    # 구조화 출력 파싱 결과. 파싱 실패 시 None — 호출부는 이를 차단 사유로 다룬다
    parsed: dict[str, Any] | None
    # Claude: stop_reason == "refusal". Ollama: 항상 False (거부 개념이 없다)
    refused: bool = False
    model_id: str = ""
    provider: str = ""
    input_tokens: int = 0
    output_tokens: int = 0
    # 파싱 실패 사유 등 진단용. 로그에만 쓰고 사용자에게 노출하지 않는다
    diagnostics: dict[str, Any] = field(default_factory=dict)


class LlmPort(ABC):
    """LLM 어댑터 계약. 구현체는 ClaudeAdapter / OllamaAdapter / FakeLlmAdapter."""

    provider: str

    @abstractmethod
    def complete_json(
        self,
        system: str,
        user_prompt: str,
        schema: dict[str, Any],
        max_tokens: int,
    ) -> LlmResult:
        """스키마를 강제한 1회 호출. 예외를 던지지 않고 LlmResult로 수렴시킨다."""

    @abstractmethod
    def health(self) -> dict[str, Any]:
        """/ai/health 용 상태. 프로바이더 도달 가능성을 반환한다."""
