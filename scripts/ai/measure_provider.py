"""프로바이더 비교 실측 — docs/ai/provider-comparison.md 의 표를 채운다.

같은 질문 세트를 같은 컨텍스트로 두 프로바이더에 던져 지연·토큰·인용 정확도·
가드레일 통과율을 잰다. Claude 열은 노트북에서, Ollama 열은 데스크탑(RTX 4080)에서
돌린다. 조건을 맞추기 위해 컨텍스트와 질문은 코드에 고정한다.

사용:
    # Claude (크레딧 소모)
    AI_PROVIDER=claude ai-service/.venv/Scripts/python.exe scripts/ai/measure_provider.py

    # Ollama (데스크탑)
    AI_PROVIDER=ollama OLLAMA_MODEL=qwen3:14b \
        ai-service/.venv/Scripts/python.exe scripts/ai/measure_provider.py

    # 모델 3종 비교 (데스크탑)
    for m in qwen3:14b gpt-oss:20b exaone3.5:7.8b; do
        AI_PROVIDER=ollama OLLAMA_MODEL=$m python scripts/ai/measure_provider.py
    done
"""

from __future__ import annotations

import argparse
import json
import pathlib
import statistics
import sys
import time

# Windows 콘솔이 cp949 라도 한글 출력이 깨지지 않게 한다
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parents[2]
AI_SERVICE = ROOT / "ai-service"
sys.path.insert(0, str(AI_SERVICE))

from app.adapters.factory import build_llm  # noqa: E402
from app.config import Settings  # noqa: E402
from app.guardrail.output_guard import guard_output  # noqa: E402
from app.guardrail.prompts import (  # noqa: E402
    ANSWER_SCHEMA,
    PROSPECTUS_SYSTEM_PROMPT,
    build_prospectus_prompt,
)
from app.ports.llm import LlmUnavailableError  # noqa: E402

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from capture_attacks import (  # noqa: E402
    PRICE_INPUT_PER_M,
    PRICE_OUTPUT_PER_M,
    SAMPLE_CHUNKS,
)

OUT_DIR = ROOT / "scripts" / "ai" / "results"

# 정상 질의 세트. 각 항목의 expected_pages 는 '이 페이지를 근거로 삼아야 정확한 인용'이다.
QUESTIONS = [
    {"q": "이 상품의 기초자산은 무엇인가요?", "expected_pages": [3]},
    {"q": "기초자산의 연면적과 준공연도를 알려주세요", "expected_pages": [3]},
    {"q": "임차인은 몇 개사인가요?", "expected_pages": [3]},
    {"q": "주요 위험요인이 무엇인가요?", "expected_pages": [7]},
    {"q": "원금 손실이 발생할 수 있는 경우는 언제인가요?", "expected_pages": [7]},
    {"q": "금리가 오르면 어떤 영향이 있나요?", "expected_pages": [7]},
    {"q": "청약 단위와 공모가를 알려주세요", "expected_pages": [12]},
    {"q": "배당은 얼마나 자주 지급되나요?", "expected_pages": [12]},
    {"q": "목표 배당수익률이 얼마인가요?", "expected_pages": [12]},
    {"q": "가중평균 잔여 임대차 기간은?", "expected_pages": [3]},
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repeat", type=int, default=1,
                        help="질문 세트를 N회 반복한다 (지연 분포를 보려면 3 이상)")
    args = parser.parse_args()

    settings = Settings()
    llm = build_llm(settings)
    model = (
        settings.ollama_model if settings.ai_provider == "ollama" else settings.ai_model
    )
    valid_pages = {c.page_no for c in SAMPLE_CHUNKS}

    print(f"프로바이더 {settings.ai_provider} / 모델 {model} / {len(QUESTIONS)}문항 x {args.repeat}회")

    latencies: list[float] = []
    total_in = total_out = 0
    passed = citation_exact = parse_failures = refusals = 0
    records = []

    for _ in range(args.repeat):
        for item in QUESTIONS:
            prompt = build_prospectus_prompt(item["q"], SAMPLE_CHUNKS)
            started = time.monotonic()
            try:
                result = llm.complete_json(
                    system=PROSPECTUS_SYSTEM_PROMPT,
                    user_prompt=prompt,
                    schema=ANSWER_SCHEMA,
                    max_tokens=settings.ai_max_tokens,
                )
            except LlmUnavailableError as exc:
                print(f"  ! 호출 실패: {exc}")
                return 1
            elapsed = time.monotonic() - started
            latencies.append(elapsed)
            total_in += result.input_tokens
            total_out += result.output_tokens

            if result.refused:
                refusals += 1
                records.append({"q": item["q"], "outcome": "REFUSAL"})
                continue
            if result.parsed is None:
                parse_failures += 1
                records.append({"q": item["q"], "outcome": "PARSE_FAILURE",
                                "raw": result.raw_text[:200]})
                continue

            answer = str(result.parsed.get("answer", ""))
            cited = [int(p) for p in result.parsed.get("cited_pages", []) if str(p).isdigit()]
            guard = guard_output(answer, cited, valid_pages)

            if not guard.blocked:
                passed += 1
                if set(cited) == set(item["expected_pages"]):
                    citation_exact += 1

            records.append({
                "q": item["q"],
                "outcome": "BLOCKED" if guard.blocked else "OK",
                "reason": guard.reason,
                "cited_pages": cited,
                "expected_pages": item["expected_pages"],
                "answer": answer,
                "latency_s": round(elapsed, 3),
            })
            print(f"  {'차단' if guard.blocked else '통과'} {elapsed:6.2f}s  "
                  f"인용{cited} 기대{item['expected_pages']}  {answer[:45]}")

    total = len(latencies)
    latencies.sort()
    p95 = latencies[max(int(total * 0.95) - 1, 0)]
    cost = total_in / 1e6 * PRICE_INPUT_PER_M + total_out / 1e6 * PRICE_OUTPUT_PER_M

    summary = {
        "provider": settings.ai_provider,
        "model": model,
        "queries": total,
        "latency_p50_s": round(statistics.median(latencies), 3),
        "latency_p95_s": round(p95, 3),
        "input_tokens": total_in,
        "output_tokens": total_out,
        # Ollama 는 전력·하드웨어 비용이라 이 단가로 환산하지 않는다
        "cost_usd": round(cost, 4) if settings.ai_provider == "claude" else None,
        "cost_per_1000_usd": round(cost / total * 1000, 2)
        if settings.ai_provider == "claude" and total else None,
        "guardrail_pass_rate": round(passed / total, 3) if total else 0,
        "citation_exact_rate": round(citation_exact / total, 3) if total else 0,
        "parse_failures": parse_failures,
        "refusals": refusals,
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    slug = f"{settings.ai_provider}-{model.replace(':', '-').replace('/', '-')}"
    path = OUT_DIR / f"provider-{slug}.json"
    path.write_text(
        json.dumps({"summary": summary, "records": records}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print()
    print("=== 요약 ===")
    for key, value in summary.items():
        print(f"{key:24}: {value}")
    print(f"\n기록: {path.relative_to(ROOT)}")
    print("docs/ai/provider-comparison.md 의 해당 열에 옮긴다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
