"""시스템 프롬프트와 고정 응답 문구.

FSD §10.3 이 지정한 프롬프트 문구는 **정본이다.** 아래 PROSPECTUS_SYSTEM_PROMPT 의
본문을 임의로 다듬지 않는다. 문구를 바꾸려면 FSD를 먼저 고친다.
"""

# FSD §10.3 필수 포함 문구 — 원문 그대로
PROSPECTUS_SYSTEM_PROMPT = """당신은 투자설명서 내용을 있는 그대로 전달하는 도우미입니다.

절대 금지:
- 투자 권유, 추천, 전망 제시 ("사세요", "유망합니다", "오를 것입니다")
- 제공된 문서에 없는 내용 답변
- 수익률 예측이나 보장

반드시 준수:
- 모든 답변에 근거 페이지 번호를 [p.12] 형식으로 표기
- 문서에 없으면 "제공된 투자설명서에서 확인할 수 없습니다"라고만 답변
- 위험 관련 질문에는 해당 위험요인 원문 요약을 우선 제시"""

DEVPORTAL_SYSTEM_PROMPT = """당신은 FRACTA 오픈 API 문서를 설명하는 개발자 어시스턴트입니다.

절대 금지:
- 제공된 OpenAPI 스펙에 없는 엔드포인트·파라미터·필드를 지어내는 것
- 투자 판단이나 상품에 대한 의견 제시
- 실제 client_secret·토큰 값을 예시로 만들어내는 것

반드시 준수:
- 제공된 스펙에 근거해서만 답변하고, 근거가 된 엔드포인트 경로를 함께 제시
- 스펙에서 확인되지 않으면 "제공된 API 스펙에서 확인할 수 없습니다"라고 답변"""

# 구조화 출력 스키마. 인용을 자유 텍스트 파싱이 아니라 스키마로 1차 강제한다.
ANSWER_SCHEMA = {
    "type": "object",
    "properties": {
        "answer": {"type": "string"},
        "cited_pages": {"type": "array", "items": {"type": "integer"}},
        "found_in_document": {"type": "boolean"},
    },
    "required": ["answer", "cited_pages", "found_in_document"],
    "additionalProperties": False,
}

DEVPORTAL_SCHEMA = {
    "type": "object",
    "properties": {
        "answer": {"type": "string"},
        "cited_endpoints": {"type": "array", "items": {"type": "string"}},
        "found_in_spec": {"type": "boolean"},
    },
    "required": ["answer", "cited_endpoints", "found_in_spec"],
    "additionalProperties": False,
}

# --- 고정 응답 ---
# 차단 사유를 사용자에게 그대로 노출하지 않는다. 어떤 패턴에 걸렸는지 알려주면
# 우회 시도를 도와주는 꼴이 된다. 사유는 ai_conversation_log 에만 남긴다.

NOT_FOUND_RESPONSE = "제공된 투자설명서에서 확인할 수 없습니다."
BLOCKED_RESPONSE = (
    "이 질문에는 답변할 수 없습니다. 투자설명서 원문을 직접 확인해 주세요."
)
DEVPORTAL_NOT_FOUND_RESPONSE = "제공된 API 스펙에서 확인할 수 없습니다."


def build_prospectus_prompt(question: str, chunks: list) -> str:
    """검색된 청크를 페이지 번호와 함께 붙인다.

    페이지 번호를 컨텍스트에 명시해야 모델이 인용할 페이지를 고를 수 있고,
    출력 단계에서 그 번호가 실제 검색 결과에 있는지 대조할 수 있다.
    """
    context = "\n\n".join(
        f"[p.{c.page_no}] {c.content}" for c in chunks
    )
    return (
        "다음은 투자설명서에서 검색된 발췌문입니다.\n"
        "----- 발췌문 시작 -----\n"
        f"{context}\n"
        "----- 발췌문 끝 -----\n\n"
        f"질문: {question}\n\n"
        "위 발췌문에 근거해서만 답하고, cited_pages 에는 실제로 근거로 삼은 "
        "발췌문의 페이지 번호만 넣으세요. 발췌문에서 확인할 수 없으면 "
        "found_in_document 를 false 로 두세요."
    )
