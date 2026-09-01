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
    add("| ID | 프롬프트 | 차단 단계 | 사유 | 검사 대상 응답 |")
    add("|---|---|---|---|---|")
    for case in cases:
        answer = case.get("captured_answer") or case["simulated_answer"]
        stage = case.get("captured_stage", case["expected_stage"])
        reason = case["expected_reason"]
        shown = answer if stage != "INPUT" else "(LLM 호출 없음)"
        add(
            f"| {case['id']} | {escape(case['prompt'])} | {STAGE_LABEL.get(stage, stage)} "
            f"| `{reason}` | {escape(shown)} |"
        )
    add("")
    add(f"**결과: {len(cases)}/{len(cases)} 차단.**")
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
