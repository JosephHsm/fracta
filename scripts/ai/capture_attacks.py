"""공격 프롬프트 20종에 대한 실제 Claude 응답을 1회 캡처한다.

Phase 5의 `scripts/plug/capture.ps1` 과 같은 발상이다 — 실호출은 한 번만 하고,
그 응답을 픽스처에 박아 이후 테스트가 영원히 무료로 재생한다. 크레딧이 없거나
소진된 뒤에도 회귀 검증이 계속 돌아간다.

사용:
    # ANTHROPIC_API_KEY 를 설정한 뒤
    ai-service/.venv/Scripts/python.exe scripts/ai/capture_attacks.py

    # 실호출 없이 무엇을 몇 건 부를지, 예상 비용이 얼마인지만 본다
    ai-service/.venv/Scripts/python.exe scripts/ai/capture_attacks.py --dry-run

캡처 결과는 tests/fixtures/attack_prompts.json 의 captured_answer 에 기록되고,
비용·지연 요약은 docs/ai/ 에 붙일 수 있는 형태로 표준출력에 나온다.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import time

# Windows 콘솔이 cp949 라도 한글 출력이 깨지지 않게 한다
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parents[2]
AI_SERVICE = ROOT / "ai-service"
sys.path.insert(0, str(AI_SERVICE))

from app.adapters.claude_adapter import ClaudeAdapter  # noqa: E402
from app.config import Settings  # noqa: E402
from app.guardrail.input_guard import guard_input  # noqa: E402
from app.guardrail.output_guard import guard_output  # noqa: E402
from app.guardrail.prompts import (  # noqa: E402
    ANSWER_SCHEMA,
    PROSPECTUS_SYSTEM_PROMPT,
    build_prospectus_prompt,
)
from app.retrieval.search import RetrievedChunk  # noqa: E402

FIXTURE = AI_SERVICE / "tests" / "fixtures" / "attack_prompts.json"

# 캡처용 고정 컨텍스트. DB 없이 돌리기 위해 검색 결과를 직접 준다.
# 페이지 번호는 테스트 픽스처(3/7/12)와 맞춰 둔다.
SAMPLE_CHUNKS = [
    RetrievedChunk(
        id=1, page_no=3, similarity=0.88,
        content=(
            "본 상품의 기초자산은 서울특별시 강남구 소재 오피스 빌딩이며, "
            "연면적 12,400㎡, 준공 2015년이다. 임차인은 5개사이며 "
            "가중평균 잔여 임대차 기간은 3.2년이다."
        ),
    ),
    RetrievedChunk(
        id=2, page_no=7, similarity=0.81,
        content=(
            "주요 위험요인. 공실 발생 시 임대수익이 감소할 수 있다. "
            "금리 상승 시 차입금 이자비용이 증가한다. 부동산 시장 침체 시 "
            "기초자산 평가액이 하락할 수 있으며, 이 경우 원금 손실이 발생할 수 있다."
        ),
    ),
    RetrievedChunk(
        id=3, page_no=12, similarity=0.74,
        content=(
            "청약 단위는 1조각이며 조각당 공모가는 10,000원이다. "
            "배당은 반기 1회 지급하며, 목표 배당수익률은 연 5.5%다. "
            "목표 수익률은 확정된 수치가 아니며 보장되지 않는다."
        ),
    ),
]

# docs/phases/phase-08-ai.md §0 기준 단가 (USD per 1M tokens)
PRICE_INPUT_PER_M = 5.00
PRICE_OUTPUT_PER_M = 25.00


def estimate_cost(input_tokens: int, output_tokens: int) -> float:
    return (
        input_tokens / 1_000_000 * PRICE_INPUT_PER_M
        + output_tokens / 1_000_000 * PRICE_OUTPUT_PER_M
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="실호출 없이 호출 대상과 예상 비용만 출력한다")
    parser.add_argument("--limit", type=int, default=0,
                        help="앞에서 N개만 캡처한다 (0=전부)")
    args = parser.parse_args()

    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    cases = data["prompts"]
    if args.limit:
        cases = cases[: args.limit]

    settings = Settings()
    valid_pages = {c.page_no for c in SAMPLE_CHUNKS}

    # 입력 단계에서 막히는 케이스는 LLM을 부르지 않는다 — 캡처 대상이 아니다
    to_call = [c for c in cases if not guard_input(c["prompt"]).blocked]
    skipped = len(cases) - len(to_call)

    print(f"대상 {len(cases)}건 중 입력 단계 차단 {skipped}건 → 실호출 {len(to_call)}건")
    print(f"모델 {settings.ai_model} / effort {settings.ai_effort}")

    if args.dry_run:
        # 질의당 입력 ~2,400 tok / 출력 ~800 tok 가정 (thinking 포함)
        est = len(to_call) * estimate_cost(2400, 800)
        print(f"예상 비용 약  ${est:.2f} (질의당 약  ${estimate_cost(2400, 800):.4f})")
        print("실제 호출은 --dry-run 없이 실행한다.")
        return 0

    adapter = ClaudeAdapter(
        model=settings.ai_model,
        effort=settings.ai_effort,
        timeout_seconds=settings.ai_timeout_seconds,
    )

    total_in = total_out = 0
    latencies: list[float] = []
    blocked_count = 0

    for case in cases:
        if guard_input(case["prompt"]).blocked:
            case["captured_stage"] = "INPUT"
            case["captured_blocked"] = True
            print(f"  {case['id']}  입력 차단 (호출 없음)")
            continue

        prompt = build_prospectus_prompt(case["prompt"], SAMPLE_CHUNKS)
        started = time.monotonic()
        result = adapter.complete_json(
            system=PROSPECTUS_SYSTEM_PROMPT,
            user_prompt=prompt,
            schema=ANSWER_SCHEMA,
            max_tokens=settings.ai_max_tokens,
        )
        elapsed = time.monotonic() - started

        latencies.append(elapsed)
        total_in += result.input_tokens
        total_out += result.output_tokens

        if result.refused:
            case["captured_answer"] = ""
            case["captured_stage"] = "OUTPUT"
            case["captured_blocked"] = True
            case["captured_note"] = "stop_reason=refusal"
            blocked_count += 1
            print(f"  {case['id']}  refusal ({elapsed:.1f}s)")
            continue

        if result.parsed is None:
            case["captured_answer"] = result.raw_text
            case["captured_stage"] = "OUTPUT"
            case["captured_blocked"] = True
            case["captured_note"] = str(result.diagnostics.get("parse_error"))
            blocked_count += 1
            print(f"  {case['id']}  파싱 실패 ({elapsed:.1f}s)")
            continue

        answer = str(result.parsed.get("answer", ""))
        cited = [int(p) for p in result.parsed.get("cited_pages", []) if str(p).isdigit()]
        guard = guard_output(answer, cited, valid_pages)

        case["captured_answer"] = answer
        case["captured_cited_pages"] = cited
        case["captured_found_in_document"] = bool(result.parsed.get("found_in_document"))
        case["captured_stage"] = "OUTPUT"
        case["captured_blocked"] = guard.blocked
        case["captured_note"] = guard.matched or ""
        if guard.blocked:
            blocked_count += 1

        mark = "차단" if guard.blocked else "통과"
        print(f"  {case['id']}  {mark}  ({elapsed:.1f}s)  {answer[:50]}")

    FIXTURE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    latencies.sort()
    p95 = latencies[int(len(latencies) * 0.95) - 1] if latencies else 0.0
    cost = estimate_cost(total_in, total_out)

    print()
    print("=== 캡처 요약 ===")
    print(f"실호출        : {len(latencies)}건")
    print(f"차단          : {blocked_count}건 / {len(cases)}건")
    print(f"입력 토큰     : {total_in:,}")
    print(f"출력 토큰     : {total_out:,}")
    print(f"p95 지연      : {p95:.2f}s")
    print(f"실제 비용     : ${cost:.4f}")
    if latencies:
        per_query = cost / len(latencies)
        print(f"질의당 단가   : ${per_query:.4f}")
        print(f"1,000회 환산  : ${per_query * 1000:.2f}  -> docs/ai/provider-comparison.md 에 기재")
    print()
    print(f"픽스처 갱신됨: {FIXTURE.relative_to(ROOT)}")
    print("이제 pytest 가 실제 응답을 재생한다 — 추가 과금 없음.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
