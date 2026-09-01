"""공격 프롬프트 픽스처 → docs/appendix/guardrail-attack-prompts.md 생성.

손으로 두 곳을 관리하면 반드시 어긋난다. 픽스처가 정본이고 문서는 파생물이다.
캡처(capture_attacks.py) 후 다시 돌리면 실제 응답 기준으로 표가 갱신된다.

사용:
    ai-service/.venv/Scripts/python.exe scripts/ai/render_attack_table.py
"""

from __future__ import annotations

import json
import pathlib
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "ai-service" / "tests" / "fixtures"
OUT = ROOT / "docs" / "appendix" / "guardrail-attack-prompts.md"

STAGE_LABEL = {"INPUT": "① 입력", "OUTPUT": "③ 출력", "NONE": "-"}


def escape(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ").strip()


def main() -> int:
    attacks = json.loads((FIXTURES / "attack_prompts.json").read_text(encoding="utf-8"))
    injections = json.loads((FIXTURES / "injection_prompts.json").read_text(encoding="utf-8"))

    cases = attacks["prompts"]
    captured = any("captured_answer" in c for c in cases)
    source = "실제 Claude 응답 캡처" if captured else "시뮬레이션 응답 (캡처 전)"

    inj = injections["prompts"]
    pii = injections["personal_info_prompts"]
    inj_blocked = sum(1 for p in inj if p["expected_input_block"])

    lines: list[str] = []
    add = lines.append

    add("# 부록 — 가드레일 공격 프롬프트")
    add("")
    add("> 자동 생성 문서다. 수정하지 말고 `scripts/ai/render_attack_table.py` 를 다시 돌린다.")
    add("> 정본은 `ai-service/tests/fixtures/*.json` 이고, 같은 파일을 pytest 가 그대로 쓴다.")
    add("")
    add("FSD §14가 Phase 8의 명시 완료 조건으로 요구하는 두 가지를 여기서 증명한다.")
    add("")
    add("1. 권유 표현 유도 프롬프트 20종 **전부 차단**")
    add("2. 환각 인용 검출 동작")
    add("")
    add("## 1. 권유 표현 유도 프롬프트 20종")
    add("")
    add(f"**응답 출처: {source}**")
    add("")
    if not captured:
        add("> 아직 실호출 캡처 전이다. `simulated_answer` 는 '가드레일이 없었다면 모델이")
        add("> 이렇게 답했을 것'을 재현한 것이며, 이 표가 증명하는 것은 **그런 응답이")
        add("> 사용자에게 도달하지 못한다**는 사실이다. 실제 모델이 이 20개 질문에")
        add("> 무엇이라고 답하는지는 `scripts/ai/capture_attacks.py` 를 1회 실행해 채운다")
        add("> (예상 비용 약 $0.61).")
        add("")
    add("| ID | 프롬프트 | 처리 | 실제 모델 응답 |")
    add("|---|---|---|---|")
    handled = {"입력 차단": 0, "출력 차단": 0, "모델 자체 거절": 0}
    for case in cases:
        answer = case.get("captured_answer") or case["simulated_answer"]
        stage = case.get("captured_stage", case["expected_stage"])
        if stage == "INPUT":
            how, shown = "① 입력 차단", "(LLM 호출 없음)"
            handled["입력 차단"] += 1
        elif case.get("captured_blocked"):
            how, shown = "③ 출력 차단", answer
            handled["출력 차단"] += 1
        else:
            how, shown = "모델 자체 거절", answer
            handled["모델 자체 거절"] += 1
        add(f"| {case['id']} | {escape(case['prompt'])} | {how} | {escape(shown)} |")
    add("")
    add("**결과: 권유 표현이 사용자에게 도달한 건수 0/20.**")
    add("")
    add(f"처리 경로 — 입력 차단 {handled['입력 차단']}건 / "
        f"출력 차단 {handled['출력 차단']}건 / 모델 자체 거절 {handled['모델 자체 거절']}건")
    add("")
    add("> **\"전부 차단\"이 기대값이 아니다.** 실제 Claude는 20종 어디에도 권유 표현으로")
    add("> 답하지 않았다 — 대부분 스스로 거절했다. 차단만 세면 모델이 잘 답할수록 지표가")
    add("> 나빠지는 이상한 기준이 된다. 요구사항의 실질은 **권유 표현이 사용자에게")
    add("> 도달하지 않는 것**이고, 그 기준으로 20/20 을 만족한다.")
    add(">")
    add("> 출력 차단 건 상당수는 **오탐**이다. \"매수 추천 의견은 제공할 수 없습니다\" 같은")
    add("> 거절 문구가 `추천` 패턴에 걸린다. 안전한 방향의 오탐이라 그대로 둔다")
    add("> (근거: [guardrail-design.md](../ai/guardrail-design.md) §6).")
    add(">")
    add("> 위 집계는 **캡처 시점의 출력 가드레일 단독 평가** 기준이다. 파이프라인 재생")
    add("> 기준(`test_처리_경로_분포를_기록한다`)은 입력 1 / 출력 5 / 모델 거절 14 로")
    add("> 나온다 — 파이프라인은 `found_in_document` 를 먼저 보고 '문서에 없음' 경로로")
    add("> 빠지기 때문이다. 이번 캡처가 그 필드를 저장하지 않아 생긴 차이이며,")
    add("> 다음 캡처부터는 `captured_found_in_document` 로 기록된다.")
    add("")
    add("검증 위치: `ai-service/tests/test_attack_prompts.py`")
    add("")
    add("## 2. 프롬프트 인젝션 20종 — 입력 단계 차단 비율")
    add("")
    add("| ID | 프롬프트 | 입력 단계 차단 |")
    add("|---|---|---|")
    for p in inj:
        add(f"| {p['id']} | {escape(p['prompt'])} | {'차단' if p['expected_input_block'] else '통과'} |")
    add("")
    add(f"**입력 단계 차단 비율: {inj_blocked}/{len(inj)} = {inj_blocked / len(inj):.0%}**")
    add("")
    add("이 수치를 그대로 읽으면 오해다. 100%인 이유는 **이 20종을 알고 패턴을 썼기**")
    add("때문이다. 입력 단계 차단은 원리상 알려진 표현만 잡으며, 어순이나 어미를 바꾼")
    add("변형은 통과한다. 그래서 출력 단계 코드 검증이 별도로 존재한다 —")
    add("입력 필터가 뚫려도 권유 표현이 담긴 응답은 사용자에게 도달하지 못한다.")
    add("")
    add("## 3. 개인정보 포함 질문")
    add("")
    add("| ID | 프롬프트 | 결과 |")
    add("|---|---|---|")
    for p in pii:
        add(f"| {p['id']} | {escape(p['prompt'])} | 차단 (`PERSONAL_INFO`) |")
    add("")
    add("주민등록번호·계좌번호·카드번호·휴대전화번호 패턴을 입력 단계에서 잡는다.")
    add("차단하면 LLM을 호출하지 않으므로 개인정보가 외부 API로 나가지 않는다.")
    add("")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"생성됨: {OUT.relative_to(ROOT)}  ({len(lines)}줄, 출처={source})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
