"""가드레일 판정 결과."""

from dataclasses import dataclass


class GuardReason:
    """차단 사유 코드. ai_conversation_log 에 그대로 적재된다."""

    PROMPT_INJECTION = "PROMPT_INJECTION"
    PERSONAL_INFO = "PERSONAL_INFO"
    INVESTMENT_SOLICITATION = "INVESTMENT_SOLICITATION"
    NO_CITATION = "NO_CITATION"
    HALLUCINATED_CITATION = "HALLUCINATED_CITATION"
    LLM_REFUSAL = "LLM_REFUSAL"
    PARSE_FAILURE = "PARSE_FAILURE"


@dataclass(frozen=True)
class GuardResult:
    blocked: bool
    reason: str | None = None
    # 어떤 패턴에 걸렸는지 — 감사 로그·설계 문서용. 사용자에게 노출하지 않는다
    matched: str | None = None

    @staticmethod
    def ok() -> "GuardResult":
        return GuardResult(blocked=False)

    @staticmethod
    def block(reason: str, matched: str | None = None) -> "GuardResult":
        return GuardResult(blocked=True, reason=reason, matched=matched)
