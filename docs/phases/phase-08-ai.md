# Phase 8 — AI

> 기간 1주 · 선행 Phase 3 (Phase 4~7과 병렬 가능) · FSD 참조 §10, §11.3
>
> **v1.1 변경**: 모델 ID 및 가드레일 구현 방식 갱신 (아래 §0)

## 0. v1.1 변경 사항

FSD §10.4의 `model = "claude-sonnet-4-6"` 기준으로 설계하면 두 군데가 어긋난다.

| 항목 | FSD v1.0 | v1.1 |
|---|---|---|
| 기본 모델 | `claude-sonnet-4-6` | **`claude-opus-5`** |
| 어시스턴트 프리필로 인용 형식 강제 | 암묵적 가정 | **불가.** 현행 모델군에서 프리필은 400 오류 |
| 인용 검증 | 정규식으로 자유 텍스트 파싱 | **구조화 출력(`output_config.format`)으로 1차 강제** + 정규식 2차 방어 |

> `claude-sonnet-4-6`은 존재하는 유효한 ID지만 현행 기본값이 아니다. 기본값은 `claude-opus-5`로 둔다.

### v1.1.1 정정 (Phase 8 구현 중 SDK 대조 결과)

아래 3건은 이 문서 작성 시점의 서술이 실제 SDK와 어긋났던 부분이다. 구현은 정정된 쪽을 따랐다.

| 위치 | 문서 | 실제 | 반영 |
|---|---|---|---|
| §0 모델 표 | Sonnet 5 $3/$15 | **$2/$10** (Sonnet **4.6**이 $3/$15) | 위 표 수정 |
| §3.3 코드 | `client.messages.create(..., betas=, fallbacks=)` | `betas`/`fallbacks` 는 **`client.beta.messages.create()`** 경로 | `ClaudeAdapter` 가 beta 경로 사용 |
| §3.3 `max_tokens=4096` | — | Opus 5는 **adaptive thinking이 기본 ON**이라 사고 토큰이 출력에서 나간다. 4096은 잘릴 수 있다 | 기본값 `AI_MAX_TOKENS=16000` |

나머지(프리필 금지, `budget_tokens`/`temperature`/`top_p` 400, `output_config.format`,
`effort`, `stop_reason == "refusal"` 선확인, `fallbacks="default"` + `server-side-fallback-2026-07-01`)는
문서 서술이 정확했다.


### 모델 선택

| 모델 | ID | 입력 $/1M | 출력 $/1M |
|---|---|---|---|
| **Claude Opus 5 (기본)** | `claude-opus-5` | $5.00 | $25.00 |
| Claude Sonnet 5 | `claude-sonnet-5` | $2.00 | $10.00 |
| Claude Haiku 4.5 | `claude-haiku-4-5` | $1.00 | $5.00 |

- 기본값은 `claude-opus-5`. 비용을 이유로 낮추는 것은 사용자의 결정이며 임의로 하향하지 않는다
- 모델 ID는 **설정값**(`AI_MODEL`)으로 분리. 하드코딩 금지
- ID에 날짜 접미사를 붙이지 않는다 (`claude-opus-5-20260101` 같은 형태는 존재하지 않음)

### 프리필 제거의 영향

"모든 답변은 `[p.12]` 형식으로 시작"을 어시스턴트 턴 프리필로 강제하는 방식은 **현행 모델에서 400 오류**다. 대신:

1. **구조화 출력**으로 응답 스키마 자체를 고정 (§3.3)
2. 시스템 프롬프트로 형식 지시
3. 그래도 **출력 단계 코드 검증은 유지** (FSD §10.3 — LLM을 신뢰하지 않는다)

---

## 1. 범위

**포함**
- FastAPI 서비스 (별도 컨테이너)
- 투자설명서 인덱싱 (PDF → 청킹 → 임베딩 → pgvector)
- RAG 질의응답
- 가드레일 3단계 (입력 / 시스템 프롬프트 / 출력 코드 검증)
- `LlmPort` + Claude·Ollama 2어댑터
- 개발자 어시스턴트
- `ai_conversation_log` 전량 기록

**제외**
- 프론트 사이드패널 UI (Phase 10)
- 파인튜닝, 자체 모델 학습

---

## 2. 구성

```
POST /ai/prospectus/index      # 투자설명서 인덱싱
POST /ai/prospectus/ask        # 질의응답
POST /ai/devportal/ask         # 개발자 어시스턴트
GET  /ai/health
```

- Core(Java)는 HTTP로 호출. Core 측 인터페이스는 `ai/LlmPort.java`
- Core → AI 호출은 **타임아웃 + 서킷브레이커** 필수. AI 장애가 본 서비스를 막으면 안 된다
- 인덱싱은 Phase 3의 `ProspectusUploadedEvent` 수신으로 트리거

---

## 3. 핵심 사양

### 3.1 인덱싱 파이프라인 (FSD §10.2)

```
PDF → 페이지별 텍스트 추출 (pdfplumber)
    → 청킹 (500자, 100자 오버랩, 페이지 경계 유지)
    → 임베딩 (bge-m3)
    → prospectus_chunk 저장 (pgvector)
```

- **페이지 경계 유지가 핵심.** 청크가 페이지를 넘나들면 인용 페이지 번호가 부정확해진다
- `page_no`는 1-indexed (사용자에게 보이는 번호와 일치)
- 임베딩은 `BAAI/bge-m3` — 한국어 성능. **Anthropic은 임베딩 API를 제공하지 않으므로 이 부분은 로컬/HF 모델을 쓴다**
- 재인덱싱 시 기존 청크 삭제 후 재생성

### 3.2 질의 파이프라인

```
질문 → 임베딩 → 코사인 유사도 top-5
     → 최고 유사도 < 0.6 이면 LLM 호출 없이 즉시 "문서에서 찾을 수 없습니다"
     → 컨텍스트 + 질문 → LLM
     → 가드레일 검사
     → 응답 + 인용 페이지 번호
```

- **임계값 미달 시 LLM을 호출하지 않는다.** 비용·환각 양쪽에서 이득
- 임계값 `0.6`은 설정값. 실측 후 조정하고 근거를 문서화

### 3.3 LLM 호출 (Python SDK)

```python
from anthropic import Anthropic

client = Anthropic()   # ANTHROPIC_API_KEY 또는 ant auth 프로필 자동 인식

resp = client.messages.create(
    model=settings.ai_model,               # 기본 "claude-opus-5"
    max_tokens=4096,
    system=PROSPECTUS_SYSTEM_PROMPT,
    messages=[{"role": "user", "content": prompt}],
    output_config={
        "effort": "medium",                # 문서 인용 QA는 medium이면 충분. 실측 후 조정
        "format": {                        # 인용을 스키마로 강제
            "type": "json_schema",
            "schema": {
                "type": "object",
                "properties": {
                    "answer": {"type": "string"},
                    "cited_pages": {"type": "array", "items": {"type": "integer"}},
                    "found_in_document": {"type": "boolean"},
                },
                "required": ["answer", "cited_pages", "found_in_document"],
                "additionalProperties": False,
            },
        },
    },
    betas=["server-side-fallback-2026-07-01"],
    fallbacks="default",                   # refusal 시 서버측 폴백
)

# content 를 읽기 전에 반드시 stop_reason 확인
if resp.stop_reason == "refusal":
    return BLOCKED_RESPONSE
```

**주의사항**
- **어시스턴트 프리필 금지** — 400 오류
- `budget_tokens` 사용 금지 — 현행 모델에서 400. 깊이 조절은 `output_config.effort`
- `temperature` / `top_p` 사용 금지 — 현행 모델에서 제거됨(400)
- `stop_reason == "refusal"` 을 반드시 먼저 확인. `content`를 먼저 읽으면 안 된다
- 긴 응답이 예상되면 `client.messages.stream()` 사용

### 3.4 가드레일 (차별 포인트)

**① 입력 단계**
- 프롬프트 인젝션 패턴 탐지 (지시 무시 요청, 역할 전환 유도 등)
- 개인정보 포함 질문 차단 (주민번호·계좌번호 패턴)
- 차단 시 LLM 호출하지 않음

**② 시스템 프롬프트 (FSD §10.3 문구 그대로 사용)**
```
당신은 투자설명서 내용을 있는 그대로 전달하는 도우미입니다.

절대 금지:
- 투자 권유, 추천, 전망 제시 ("사세요", "유망합니다", "오를 것입니다")
- 제공된 문서에 없는 내용 답변
- 수익률 예측이나 보장

반드시 준수:
- 모든 답변에 근거 페이지 번호를 [p.12] 형식으로 표기
- 문서에 없으면 "제공된 투자설명서에서 확인할 수 없습니다"라고만 답변
- 위험 관련 질문에는 해당 위험요인 원문 요약을 우선 제시
```

**③ 출력 단계 — 코드 검증 (LLM 신뢰 금지)**
```python
BLOCKED_PATTERNS = [
    r"(사|매수|투자)(하세요|하시길|추천)",
    r"(유망|기대|전망)(합니다|됩니다)",
    r"수익률.{0,10}(예상|전망|보장)",
    r"(오를|상승할).{0,5}(것|겁니다)",
]

def guard_output(answer: str, cited_pages: list[int], chunks: list) -> GuardResult:
    for p in BLOCKED_PATTERNS:
        if re.search(p, answer):
            return GuardResult(blocked=True, reason="INVESTMENT_SOLICITATION")

    if not cited_pages:
        return GuardResult(blocked=True, reason="NO_CITATION")

    valid = {c.page_no for c in chunks}
    if not set(cited_pages).issubset(valid):
        return GuardResult(blocked=True, reason="HALLUCINATED_CITATION")

    return GuardResult(blocked=False)
```

- 구조화 출력으로 `cited_pages`를 받으므로 정규식 파싱보다 안정적이다
- 단, `answer` 본문의 금지 표현 검사는 그대로 유지
- **차단 시**: 고정 문구 반환 + 사유를 감사 로그에 기록. **LLM 재시도는 1회만**

### 3.5 LlmPort — 폐쇄망 대응 (FSD §10.4)

```python
class LlmPort(ABC):
    @abstractmethod
    def complete(self, system: str, messages: list, max_tokens: int) -> LlmResult: ...

class ClaudeAdapter(LlmPort):     # 기본값
    # model = settings.ai_model   (기본 "claude-opus-5")

class OllamaAdapter(LlmPort):     # 폐쇄망 시연용
    # 로컬 GPU. AI_PROVIDER=ollama
    # VRAM 16GB 기준 양자화 모델
```

- 전환은 환경변수 `AI_PROVIDER=claude|ollama` 한 줄
- **`LlmResult`에 `refused: bool` 필드를 둔다.** Claude의 refusal과 Ollama의 정상 응답을 동일 인터페이스로 다루기 위함
- 구조화 출력은 Ollama에서 지원 수준이 다르다. **어댑터가 JSON 파싱 실패를 흡수**하고, 파싱 실패 시 `NO_CITATION`으로 **차단** 처리한다 (통과시키지 않는다)

### 3.6 대화 로그

`ai_conversation_log`에 **전부** 저장 (FSD §10.3):
- 질문 / 검색 컨텍스트(청크 ID 목록) / 원본 응답 / 가드레일 결과 / 최종 응답 / 모델 ID / 토큰 사용량

토큰 사용량 기록은 비용 분석 및 README 비교표의 근거가 된다.

---

## 4. 구현 순서

1. FastAPI 스캐폴딩 + `docker-compose.yml`에 `ai-service` 추가
2. Flyway `V8__ai.sql` — `prospectus_chunk`(VECTOR), `ai_conversation_log`
3. pgvector 인덱스(`hnsw` 권장) + 차원 수 확정
4. 인덱싱 파이프라인 (pdfplumber → 청킹 → bge-m3 → 저장)
5. 검색 (코사인 유사도 top-5 + 임계값)
6. `LlmPort` + `ClaudeAdapter` (구조화 출력 + refusal 처리)
7. 가드레일 3단계
8. `/ai/prospectus/ask` 완성
9. `OllamaAdapter`
10. `/ai/devportal/ask` — OpenAPI 스펙(Phase 7 산출물)을 컨텍스트로
11. Core 측 HTTP 클라이언트 + 서킷브레이커
12. **권유 유도 프롬프트 20종 작성 및 차단 검증**

---

## 5. 완료 조건 체크리스트

- [x] **권유 표현 유도 프롬프트 20종 전부 차단** (FSD §14 명시 조건)
  - 목록을 `docs/appendix/guardrail-attack-prompts.md`에 문서화 (픽스처에서 자동 생성)
- [x] **환각 인용 검출 동작** (FSD §14 명시 조건) — 존재하지 않는 페이지 인용 시 차단
- [x] 인덱싱 — PDF 업로드 → 청크 생성 → 임베딩 저장 확인
  - 실제 PDF(pdfplumber) → 페이지 추출 → 청킹 → 임베딩 → `DELETE`+`INSERT` 까지 검증
  - 실제 bge-m3(1024차원) + 실제 pgvector 종단 검증 완료 — `test_embedding_e2e.py` 21건
- [x] 청킹이 페이지 경계를 넘지 않음 (모든 청크의 `page_no`가 단일값)
- [x] 유사도 임계값 미달 질문 → **LLM 호출 없이** 고정 문구 반환 (호출 0건 확인)
  - 임계값을 실측으로 조정: **0.6 → 0.48**. 근거 `docs/ai/similarity-threshold.md`
  - FSD 기본값 0.6은 답할 수 있는 질문 10건 중 4건을 차단했다
- [x] 인용 없는 응답 → `NO_CITATION` 차단
- [x] 프롬프트 인젝션 시도 20종 중 입력 단계 차단 비율 측정·기록 (20/20 = 100%)
- [x] 개인정보 포함 질문 차단
- [x] `stop_reason == "refusal"` 처리 경로 테스트 (모킹)
- [x] 어시스턴트 프리필 미사용 확인 (코드 검색 → 테스트로 고정)
- [x] `budget_tokens` / `temperature` / `top_p` 미사용 확인 (코드 검색 → 테스트로 고정)
- [x] 모델 ID가 설정값으로 분리됨 (하드코딩 0건, 날짜 접미사 0건)
- [x] Ollama에서 JSON 파싱 실패 시 안전하게 **차단**됨 (통과되지 않음)
- [x] 모든 질의가 `ai_conversation_log`에 기록 (차단된 것 포함)
- [x] 로그에 API 키가 남지 않음
- [x] AI 서비스 다운 시 Core가 정상 동작 (서킷브레이커 확인)
- [x] 메트릭 `fracta.ai.guardrail.blocked` 동작 (FSD §13.3)
- [x] `/ai/health` 가 임베딩 모델·DB·LLM 프로바이더 상태 반영
- [ ] `AI_PROVIDER=ollama` 전환 후 동일 테스트 스위트 통과 — **측정 대기 (데스크탑)**

> **완료 근거** (2026-09-01)
>
> Python 139건 + Java 254건(Phase 8분 19건 포함) 통과.
> 검증 위치는 `docs/ai/guardrail-design.md` §10 표 참조.
>
> **e2e 종단 검증에서 잡은 것.** VC++ 재배포 패키지 갱신 후 실제 bge-m3 로 돌린 결과
> 두 가지가 나왔다. ① `psycopg` 가 파이썬 `list[float]` 를 `double precision[]` 로 넘겨
> `<=>` 연산자가 거부하는 버그 — **인덱싱은 성공하고 검색만 실패**하는 형태라 대역 기반
> 테스트로는 드러나지 않았다(`%s::vector` 캐스트로 수정). ② 임계값 0.6이 너무 높아
> 정상 질문 4/10을 차단 — 0.48로 조정하고 근거를 문서화했다.
>
> | 남은 것 | 필요 환경 | 실행 |
> |---|---|---|
> | Ollama 스위치 검증 + 품질·지연 비교 | 데스크탑 RTX 4080 | `scripts/ai/measure_provider.py` |
> | 폐쇄망 시연 캡처 | 데스크탑 | — |
> | 공격 프롬프트 20종 **실제 Claude 응답** 캡처 | ANTHROPIC_API_KEY | `scripts/ai/capture_attacks.py` (약 $0.61) |
> | Claude 지연·비용 실측 | ANTHROPIC_API_KEY | `scripts/ai/measure_provider.py --repeat 3` (약 $0.96) |
>
> 현재 20종 차단 검증은 **시뮬레이션 응답**(가드레일이 없었다면 모델이 이렇게 답했을 것)
> 으로 돈다. 증명하는 것은 "권유 표현이 담긴 응답은 어떤 경로로도 사용자에게 도달하지
> 못한다"이고, "실제 모델이 그 질문에 무엇이라 답하는가"는 캡처 후에 증명된다.
> 캡처하면 같은 테스트가 실제 응답을 재생하므로 이후 추가 과금이 없다
> (Phase 5의 `scripts/plug/capture.ps1` 과 같은 방식).

## 6. 흔한 실수

1. **가드레일을 프롬프트에만 의존** → 반드시 코드 후처리 검증 (부록 B-7)
2. 어시스턴트 프리필로 형식 강제 → **400 오류.** 구조화 출력 사용
3. `budget_tokens`로 사고 예산 설정 → 현행 모델에서 400. `output_config.effort` 사용
4. `stop_reason` 확인 없이 `content` 접근 → refusal 시 예외 또는 빈 응답
5. 청킹이 페이지를 넘나듦 → 인용 페이지 번호 부정확 → 신뢰도 붕괴
6. 유사도 임계값 검사를 LLM 호출 **이후에** 수행 → 비용 낭비
7. 차단된 응답을 로그에 남기지 않음 → 가드레일 효과를 증명할 수 없다
8. Ollama 어댑터의 파싱 실패를 **통과 처리** → 가드레일 우회
9. 모델 ID에 날짜 접미사를 붙임 → 존재하지 않는 ID
10. API 키를 로그·에러 응답에 노출

---

## 7. 문서화 (README 필수 항목 6·7번)

**`docs/ai/guardrail-design.md`**
- 금소법상 제약 → 설계 대응 매핑표
- 3단계 가드레일 구조도
- 공격 프롬프트 20종과 차단 결과표
- 왜 프롬프트만으로는 불충분한가 (실제 우회 사례 포함)

**`docs/ai/provider-comparison.md`** (README 필수 항목 7번)

| 항목 | Claude (`claude-opus-5`) | Ollama (로컬) |
|---|---|---|
| 응답 품질 (정성 평가) | | |
| p95 지연시간 | | |
| 인용 정확도 | | |
| 가드레일 통과율 | | |
| 1,000회 질의 비용 | | 전력/하드웨어 |

- Opus 5 기준 $5 / $25 per 1M tokens로 실제 질의 비용을 산출해 기재
- 폐쇄망 시연 캡처 포함

---

## 8. 다음 Phase 진입 전 확인

- [ ] `/ai/prospectus/ask` 응답에 인용 페이지가 포함되는가? Phase 10의 "인용 클릭 → 해당 페이지 이동" UI가 이걸 쓴다
- [ ] 개발자 어시스턴트가 Phase 7의 OpenAPI 스펙을 컨텍스트로 받는가
