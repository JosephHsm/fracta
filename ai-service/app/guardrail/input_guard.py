"""① 입력 단계 가드레일.

여기서 차단되면 **LLM을 호출하지 않는다.** 비용과 위험 양쪽에서 이득이고,
프롬프트 인젝션은 애초에 모델에게 도달하지 않는 것이 가장 확실한 방어다.
"""

import re

from app.guardrail.result import GuardResult, GuardReason

# 프롬프트 인젝션 — 지시 무시 / 역할 전환 / 프롬프트 추출 / 제약 해제
INJECTION_PATTERNS: list[tuple[str, str]] = [
    ("지시 무시", r"(이전|위의|앞의|모든)\s*(지시|명령|규칙|프롬프트)[^\n]{0,10}(무시|잊)"),
    ("지시 무시(영문)", r"(?i)ignore\s+(all\s+|the\s+)?(previous|above|prior)\s+instructions?"),
    ("규칙 해제", r"(규칙|제약|제한|가드레일|필터)[^\n]{0,10}(무시|해제|끄|없이|우회)"),
    ("규칙 해제(영문)", r"(?i)(disregard|bypass|override)\s+(your\s+)?(rules?|instructions?|guidelines?)"),
    ("역할 전환", r"(너|당신|넌)(는|은)?\s*(이제|지금부터)[^\n]{0,20}(이다|야|해라|입니다|처럼)"),
    ("역할 전환(영문)", r"(?i)(you\s+are\s+now|act\s+as|pretend\s+to\s+be|roleplay\s+as)"),
    ("프롬프트 추출", r"(시스템\s*프롬프트|초기\s*지시|system\s*prompt)[^\n]{0,15}(알려|보여|출력|공개|말해)"),
    ("프롬프트 추출(영문)", r"(?i)(reveal|show|print|repeat)\s+(me\s+)?(your\s+)?(system\s+)?(prompt|instructions)"),
    ("개발자 모드", r"(개발자\s*모드|관리자\s*권한|디버그\s*모드)"),
    ("개발자 모드(영문)", r"(?i)(developer\s+mode|admin\s+mode|jailbreak|\bDAN\b)"),
    ("가정 우회", r"(가정|상상|만약)[^\n]{0,15}(제한|규칙|가드레일)[^\n]{0,10}없"),
    ("역할 부여 우회", r"(투자|금융)\s*(전문가|자문가?|상담사|애널리스트|플래너)\s*(로서|처럼|인\s*척|입장에서)"),
]

# 개인정보 — 질문에 실려 들어오면 그대로 LLM과 로그로 흘러간다
PERSONAL_INFO_PATTERNS: list[tuple[str, str]] = [
    ("주민등록번호", r"\d{6}\s*-\s*[1-4]\d{6}"),
    ("주민등록번호(붙임)", r"\b\d{6}[1-4]\d{6}\b"),
    ("계좌번호", r"\b\d{3,6}-\d{2,6}-\d{4,8}\b"),
    ("카드번호", r"\b\d{4}[-\s]\d{4}[-\s]\d{4}[-\s]\d{4}\b"),
    ("휴대전화번호", r"\b01[016789][-\s]?\d{3,4}[-\s]?\d{4}\b"),
]

_COMPILED_INJECTION = [(name, re.compile(p)) for name, p in INJECTION_PATTERNS]
_COMPILED_PII = [(name, re.compile(p)) for name, p in PERSONAL_INFO_PATTERNS]


def guard_input(question: str) -> GuardResult:
    """질문을 검사한다. 차단이면 LLM 호출 없이 즉시 고정 문구를 반환해야 한다."""
    if not question or not question.strip():
        return GuardResult.block(GuardReason.PROMPT_INJECTION, "빈 질문")

    for name, pattern in _COMPILED_PII:
        if pattern.search(question):
            return GuardResult.block(GuardReason.PERSONAL_INFO, name)

    for name, pattern in _COMPILED_INJECTION:
        if pattern.search(question):
            return GuardResult.block(GuardReason.PROMPT_INJECTION, name)

    return GuardResult.ok()
