"""③ 출력 단계 가드레일 — 코드 검증. LLM을 신뢰하지 않는다 (FSD §10.3).

두 층으로 본다:
  1. 구조화 출력(cited_pages)으로 인용을 1차 강제 — 자유 텍스트 정규식 파싱보다 안정적
  2. answer 본문의 [p.N] 표기와 금지 표현은 정규식으로 2차 방어

FSD_BLOCKED_PATTERNS 는 FSD §10.3 원문이다. 임의로 고치지 않는다.
SUPPLEMENTARY_PATTERNS 는 공격 프롬프트 20종 대응으로 보강한 것이며,
어느 쪽에 걸렸는지 구분해 기록한다 — 그래야 설계 문서에서 근거를 나눌 수 있다.
"""

import re

from app.guardrail.result import GuardResult, GuardReason

# --- FSD §10.3 원문 ---
FSD_BLOCKED_PATTERNS: list[str] = [
    r"(사|매수|투자)(하세요|하시길|추천)",
    r"(유망|기대|전망)(합니다|됩니다)",
    r"수익률.{0,10}(예상|전망|보장)",
    r"(오를|상승할).{0,5}(것|겁니다)",
]

# --- 보강 (phase-08 §5 "권유 표현 유도 프롬프트 20종 전부 차단" 대응) ---
# FSD의 4개는 대표 표현만 잡는다. 20종을 실제로 돌려보면 어미를 살짝 바꾼 변형이
# 그대로 통과하므로, 우회 사례를 근거로 아래를 추가했다.
# 상세 근거: docs/ai/guardrail-design.md
SUPPLEMENTARY_PATTERNS: list[str] = [
    r"추천(합니다|드립니다|해요|해\s*드립니다|입니다)",
    r"(강력|적극)(히|적으로)?\s*(추천|권장|권유)",
    r"(투자|매수|매도)(를|할|하는\s*것)?\s*(권유|권장|권해)",
    r"(사|팔|매수|매도|투자)(시면|는\s*게\s*좋|시는\s*것을|하시는\s*것을)",
    r"(수익|이익|원금|손실)[^\n]{0,12}(보장|확실|틀림없|염려\s*없)",
    r"(급등|폭등|상승세|하락세|반등)[^\n]{0,12}(예상|전망|기대)",
    r"(지금|현재|이번)[^\n]{0,8}(사|매수|투자)(하기|할)[^\n]{0,6}(좋|적기|타이밍)",
    r"(오를|내릴|상승|하락)[^\n]{0,8}(가능성|확률)[^\n]{0,6}(높|큽|큼)",
    r"(좋은|괜찮은|안전한|매력적인|우수한)[^\n]{0,10}(투자|상품|기회|종목)[^\n]{0,4}(입니다|이에요|네요)",
    r"(적합|알맞|어울리)[^\n]{0,10}(투자처|상품입니다|종목입니다)",
    r"(사도|투자해도|매수해도)\s*(됩니다|좋습니다|괜찮)",
    r"(포트폴리오|자산)[^\n]{0,10}(편입|담)(을\s*권|는\s*것을\s*권|하세요)",
]

_COMPILED_FSD = [(p, re.compile(p)) for p in FSD_BLOCKED_PATTERNS]
_COMPILED_SUPP = [(p, re.compile(p)) for p in SUPPLEMENTARY_PATTERNS]

_INLINE_CITATION = re.compile(r"\[p\.(\d+)\]")


def find_solicitation(answer: str) -> tuple[str, str] | None:
    """금지 표현을 찾는다. (출처, 패턴) 또는 None."""
    for pattern, compiled in _COMPILED_FSD:
        if compiled.search(answer):
            return "FSD", pattern
    for pattern, compiled in _COMPILED_SUPP:
        if compiled.search(answer):
            return "SUPP", pattern
    return None


def guard_output(
    answer: str,
    cited_pages: list[int],
    valid_pages: set[int],
) -> GuardResult:
    """LLM 응답을 검증한다.

    valid_pages 는 이번 질의로 실제 검색된 청크의 페이지 집합이다.
    여기에 없는 페이지를 인용하면 환각이다.
    """
    hit = find_solicitation(answer)
    if hit is not None:
        source, pattern = hit
        return GuardResult.block(
            GuardReason.INVESTMENT_SOLICITATION, f"{source}:{pattern}"
        )

    if not cited_pages:
        return GuardResult.block(GuardReason.NO_CITATION, "cited_pages 비어 있음")

    if not set(cited_pages).issubset(valid_pages):
        outside = sorted(set(cited_pages) - valid_pages)
        return GuardResult.block(
            GuardReason.HALLUCINATED_CITATION, f"검색결과 밖 페이지 {outside}"
        )

    # 2차 방어: 본문 [p.N] 표기도 검색 결과 안이어야 한다.
    # 구조화 출력의 cited_pages 는 맞는데 본문에서 엉뚱한 페이지를 부르는 경우를 잡는다.
    inline = {int(m) for m in _INLINE_CITATION.findall(answer)}
    if inline and not inline.issubset(valid_pages):
        outside = sorted(inline - valid_pages)
        return GuardResult.block(
            GuardReason.HALLUCINATED_CITATION, f"본문 인용이 검색결과 밖 {outside}"
        )

    return GuardResult.ok()
