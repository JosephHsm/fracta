"""가드레일 3단계 — 입력·출력 단위 검증."""

import pytest

from app.guardrail.input_guard import guard_input
from app.guardrail.output_guard import (
    FSD_BLOCKED_PATTERNS,
    SUPPLEMENTARY_PATTERNS,
    find_solicitation,
    guard_output,
)
from app.guardrail.result import GuardReason

VALID_PAGES = {3, 7, 12}


# --- ① 입력 단계 ---


def test_인젝션_20종_입력_차단_비율(injection_prompts):
    """phase-08 §5: 차단 비율을 측정·기록한다."""
    blocked = [p for p in injection_prompts if guard_input(p["prompt"]).blocked]
    ratio = len(blocked) / len(injection_prompts)

    # 고정한 기대값과 어긋나면 패턴이 퇴화한 것이다
    for prompt in injection_prompts:
        result = guard_input(prompt["prompt"])
        assert result.blocked == prompt["expected_input_block"], (
            f"{prompt['id']} 기대={prompt['expected_input_block']} 실제={result.blocked}: "
            f"{prompt['prompt']}"
        )

    assert ratio == 1.0, f"입력 단계 차단 비율 {ratio:.0%} — docs/ai/guardrail-design.md 갱신 필요"


def test_인젝션_차단은_PROMPT_INJECTION_사유를_남긴다(injection_prompts):
    for prompt in injection_prompts:
        result = guard_input(prompt["prompt"])
        if result.blocked:
            assert result.reason == GuardReason.PROMPT_INJECTION
            assert result.matched, "어떤 패턴에 걸렸는지 기록되어야 설계 문서를 쓸 수 있다"


def test_개인정보_포함_질문_차단(personal_info_prompts):
    for prompt in personal_info_prompts:
        result = guard_input(prompt["prompt"])
        assert result.blocked, prompt["prompt"]
        assert result.reason == GuardReason.PERSONAL_INFO


def test_정상_질문은_통과한다():
    for question in [
        "이 상품의 주요 위험요인이 무엇인가요?",
        "기초자산의 소재지는 어디인가요?",
        "청약 단위와 공모가를 알려주세요",
        "임대차 계약 기간이 얼마나 되나요?",
    ]:
        assert not guard_input(question).blocked, question


def test_빈_질문은_차단된다():
    assert guard_input("").blocked
    assert guard_input("   ").blocked


# --- ③ 출력 단계 ---


def test_FSD_원문_패턴이_보존되어_있다():
    """FSD §10.3 의 4개 패턴은 정본이다. 삭제·변형되면 실패한다."""
    assert FSD_BLOCKED_PATTERNS == [
        r"(사|매수|투자)(하세요|하시길|추천)",
        r"(유망|기대|전망)(합니다|됩니다)",
        r"수익률.{0,10}(예상|전망|보장)",
        r"(오를|상승할).{0,5}(것|겁니다)",
    ]


@pytest.mark.parametrize(
    "answer",
    [
        "지금 투자하세요 [p.3].",
        "매수추천 의견입니다 [p.3].",
        "매우 유망합니다 [p.7].",
        "수익률은 연 6%로 예상됩니다 [p.12].",
        "앞으로 오를 것으로 보입니다 [p.3].",
    ],
)
def test_FSD_패턴에_걸리는_표현은_차단된다(answer):
    result = guard_output(answer, [3], VALID_PAGES)
    assert result.blocked
    assert result.reason == GuardReason.INVESTMENT_SOLICITATION
    assert result.matched.startswith("FSD:")


@pytest.mark.parametrize(
    "answer",
    [
        "적극 추천드립니다 [p.3].",
        # FSD 패턴 (사|매수|투자)(하세요|하시길|추천) 은 띄어쓰기 하나로 빠져나간다.
        # '매수추천'은 잡히지만 '매수 추천'은 안 잡힌다 — 보강 패턴이 필요한 실제 근거.
        "매수 추천드립니다 [p.3].",
        "매력적인 투자 기회입니다 [p.7].",
        "원금은 보장됩니다 [p.7].",
        "상승 가능성이 높습니다 [p.3].",
        "포트폴리오 편입을 권합니다 [p.12].",
        "안전한 상품입니다 [p.3].",
        "지금 매수하기 좋은 타이밍입니다 [p.7].",
        "사도 됩니다 [p.3].",
    ],
)
def test_보강_패턴이_어미_변형을_잡는다(answer):
    """FSD 4개만으로는 어미를 바꾼 변형이 그대로 통과한다."""
    result = guard_output(answer, [3], VALID_PAGES)
    assert result.blocked
    assert result.reason == GuardReason.INVESTMENT_SOLICITATION
    assert result.matched.startswith("SUPP:")


def test_인용이_없으면_차단된다():
    result = guard_output("기초자산은 오피스 빌딩이다.", [], VALID_PAGES)
    assert result.blocked
    assert result.reason == GuardReason.NO_CITATION


def test_검색결과에_없는_페이지_인용은_환각으로_차단된다():
    """FSD §14 명시 조건 — 환각 인용 검출."""
    result = guard_output("기초자산은 오피스 빌딩이다 [p.3].", [3, 99], VALID_PAGES)
    assert result.blocked
    assert result.reason == GuardReason.HALLUCINATED_CITATION
    assert "99" in result.matched


def test_본문_인용도_2차로_검증된다():
    """cited_pages 는 맞는데 본문에서 엉뚱한 페이지를 부르는 경우."""
    result = guard_output("위험요인은 42쪽에 있다 [p.42].", [3], VALID_PAGES)
    assert result.blocked
    assert result.reason == GuardReason.HALLUCINATED_CITATION


def test_정상_응답은_통과한다():
    result = guard_output(
        "주요 위험요인은 공실 발생 시 임대수익 감소다 [p.7].", [7], VALID_PAGES
    )
    assert not result.blocked


def test_보강_패턴은_FSD_패턴과_중복되지_않는다():
    """같은 문자열을 두 목록이 함께 잡으면 결과표에서 출처가 흐려진다."""
    assert not set(FSD_BLOCKED_PATTERNS) & set(SUPPLEMENTARY_PATTERNS)


def test_위험요인_원문_인용은_차단되지_않아야_한다():
    """가드레일이 문서 원문 요약까지 막으면 기능 자체가 무의미해진다."""
    answer = (
        "투자설명서는 공실률 상승, 금리 변동, 임차인 신용도 하락을 주요 위험으로 "
        "적시하고 있다 [p.7]."
    )
    assert not guard_output(answer, [7], VALID_PAGES).blocked


def test_find_solicitation_은_출처를_구분해_돌려준다():
    assert find_solicitation("투자하세요") == ("FSD", r"(사|매수|투자)(하세요|하시길|추천)")
    assert find_solicitation("기초자산은 오피스다") is None
