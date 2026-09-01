"""권유 표현 유도 프롬프트 20종 전부 차단 (FSD §14 명시 조건, phase-08 §5).

이 테스트가 증명하는 것과 증명하지 않는 것을 구분해 둔다.

증명한다: 권유 표현이 담긴 응답은 **어떤 경로로도 사용자에게 도달하지 못한다.**
          프롬프트가 아니라 코드가 막는다.
증명하지 않는다: 실제 Claude가 이 20개 질문에 무엇이라고 답하는지.
          그건 scripts/ai/capture_attacks.py 로 1회 캡처해 fixture 에 채우고,
          같은 테스트가 그 실제 응답을 재생하면서 증명된다.

캡처 전에는 simulated_answer(가드레일이 없었다면 모델이 이렇게 답했을 것)를 쓴다.
"""

import pytest

from app.ask_service import ProspectusAskService
from app.guardrail.prompts import BLOCKED_RESPONSE
from tests.conftest import StubSearch


def _service(fake_llm, conversation_log, metrics, chunks):
    return ProspectusAskService(
        fake_llm, StubSearch(chunks), conversation_log, metrics, 16000
    )


def _answer_for(case: dict) -> str:
    """캡처된 실제 응답이 있으면 그것을, 없으면 시뮬레이션 응답을 쓴다."""
    return case.get("captured_answer") or case["simulated_answer"]


def test_공격_프롬프트가_20종이다(attack_prompts):
    assert len(attack_prompts) == 20
    assert len({p["id"] for p in attack_prompts}) == 20


def test_권유_유도_20종_전부_차단된다(
    attack_prompts, fake_llm, conversation_log, metrics, default_chunks
):
    service = _service(fake_llm, conversation_log, metrics, default_chunks)
    survived: list[str] = []

    for case in attack_prompts:
        answer = _answer_for(case)
        # 출력 단계 케이스는 재시도까지 같은 응답을 주도록 2번 넣는다
        fake_llm.push_answer(answer, [3])
        fake_llm.push_answer(answer, [3])

        response = service.ask(1, case["prompt"])

        if not response.blocked:
            survived.append(f"{case['id']}: {case['prompt']}")
        else:
            assert response.answer == BLOCKED_RESPONSE, case["id"]

    assert not survived, "차단되지 않고 통과한 공격 프롬프트:\n" + "\n".join(survived)


def test_차단_단계와_사유가_고정된_기대와_일치한다(
    attack_prompts, fake_llm, conversation_log, metrics, default_chunks
):
    """어느 단계에서 막히는지가 바뀌면 설계 문서의 결과표도 바뀌어야 한다."""
    service = _service(fake_llm, conversation_log, metrics, default_chunks)

    for case in attack_prompts:
        answer = _answer_for(case)
        fake_llm.push_answer(answer, [3])
        fake_llm.push_answer(answer, [3])

        response = service.ask(1, case["prompt"])
        entry = conversation_log.entries[-1]

        assert entry.guardrail_stage == case["expected_stage"], case["id"]
        assert response.blocked_reason == case["expected_reason"], case["id"]


def test_차단된_질의도_전부_로그에_남는다(
    attack_prompts, fake_llm, conversation_log, metrics, default_chunks
):
    """남기지 않으면 가드레일이 동작했다는 것을 증명할 수 없다 (phase-08 §6-7)."""
    service = _service(fake_llm, conversation_log, metrics, default_chunks)

    for case in attack_prompts:
        fake_llm.push_answer(_answer_for(case), [3])
        fake_llm.push_answer(_answer_for(case), [3])
        service.ask(1, case["prompt"])

    assert len(conversation_log.entries) == len(attack_prompts)
    assert all(e.guardrail_blocked for e in conversation_log.entries)
    assert all(e.guardrail_reason for e in conversation_log.entries)


def test_입력_단계_차단은_LLM_호출_없이_이뤄진다(
    attack_prompts, fake_llm, conversation_log, metrics, default_chunks
):
    input_stage = [c for c in attack_prompts if c["expected_stage"] == "INPUT"]
    assert input_stage, "입력 단계에서 막히는 케이스가 최소 1개는 있어야 한다"

    service = _service(fake_llm, conversation_log, metrics, default_chunks)
    for case in input_stage:
        # 응답을 넣지 않는다 — 호출되면 FakeLlmAdapter 가 터진다
        service.ask(1, case["prompt"])

    assert fake_llm.call_count == 0


@pytest.mark.parametrize("field", ["id", "prompt", "simulated_answer", "expected_stage", "expected_reason"])
def test_fixture_가_필수_필드를_갖춘다(attack_prompts, field):
    for case in attack_prompts:
        assert case.get(field), f"{case.get('id')} 에 {field} 가 없다"
