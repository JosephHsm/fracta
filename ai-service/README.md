# FRACTA AI 서비스

투자설명서 RAG + 금소법 가드레일 (Phase 8). Core(Java)와 별도 컨테이너로 뜨고
HTTP로만 통신한다 (FSD §10.1).

## 두 개의 자리

혼동하기 쉬운 부분이라 먼저 짚는다. 이 서비스에는 **역할이 다른 모델이 두 개** 들어간다.

| | 정체 | 하는 일 | 포트 |
|---|---|---|---|
| **bge-m3** | 임베딩 모델 | 텍스트 → 숫자 1024개. 문장을 만들지 못한다 | `EmbeddingPort` |
| **Claude / Ollama** | LLM | 검색된 발췌문 + 질문 → 한국어 답변 | `LlmPort` |

`AI_PROVIDER` 스위치는 **LLM에만** 걸린다. 임베딩은 어느 경우에도 로컬에서 돈다 —
LLM만 로컬로 바꾸고 임베딩을 외부 API로 보내면 질문 텍스트가 밖으로 나가므로
폐쇄망이 성립하지 않는다.

포트를 분리한 실질적 이유는 교체 유연성이 아니라 **테스트 대역**이다.
bge-m3 로드에 10~30초가 걸리고 가중치가 2.3GB라, 포트가 없으면 청킹·검색·가드레일
테스트가 전부 모델 로드를 기다리고 CI가 매번 2.3GB를 받아야 한다.
(임베딩 차원을 바꾸려면 `V8__ai.sql` 의 `VECTOR(1024)` 도 함께 고치고 전체 재인덱싱이 필요하다.)

## 엔드포인트

```
POST /ai/prospectus/index      # 투자설명서 인덱싱 (ProspectusUploadedEvent 로 자동 트리거)
POST /ai/prospectus/ask        # 질의응답
POST /ai/devportal/ask         # 개발자 어시스턴트 (Phase 7 OpenAPI 스펙이 컨텍스트)
GET  /ai/health                # 임베딩 모델 · DB · LLM 프로바이더 상태
GET  /ai/metrics               # 자기 진단용 집계 (정본은 Core의 Micrometer)
```

## 구조

```
app/
├── ports/          LlmPort, EmbeddingPort
├── adapters/       ClaudeAdapter · OllamaAdapter · FakeLlmAdapter
│                   LocalBgeM3Adapter · FakeEmbeddingAdapter · factory
├── guardrail/      prompts(FSD §10.3 원문) · input_guard · output_guard · result
├── indexing/       pdf_text → chunker(페이지 경계 유지) → service
├── retrieval/      search (코사인 top-5 + 임계값)
├── ask_service.py  질의 파이프라인 (가드레일 3단계 순서 강제)
├── devportal.py    개발자 어시스턴트
├── conversation.py ai_conversation_log 전량 기록
└── main.py         FastAPI
```

## 로컬 실행

```bash
# 의존성 (임베딩 모델 제외 — 테스트는 대역으로 돈다)
python -m venv .venv
.venv/Scripts/python.exe -m pip install fastapi "uvicorn[standard]" pydantic \
    pydantic-settings "psycopg[binary,pool]" pgvector anthropic pdfplumber minio \
    pytest pytest-asyncio httpx

# 테스트 (DB·임베딩 모델·LLM 없이 전 경로가 돈다)
.venv/Scripts/python.exe -m pytest -q

# 컨테이너로 기동 (임베딩 모델 포함)
docker compose up -d ai-service
```

## 테스트가 무엇을 증명하는가

`pytest` 는 실호출 없이 돈다. 증명하는 것과 증명하지 않는 것을 구분해 둔다.

- **증명한다**: 권유 표현이 담긴 응답은 어떤 경로로도 사용자에게 도달하지 못한다.
  프롬프트가 아니라 코드가 막는다.
- **증명하지 않는다**: 실제 Claude가 그 20개 질문에 무엇이라 답하는지.
  그건 `scripts/ai/capture_attacks.py` 로 1회 캡처해 픽스처에 채우고,
  이후 같은 테스트가 **실제 응답을 무료로 재생**하면서 증명된다
  (Phase 5의 `scripts/plug/capture.ps1` 과 같은 방식).

## 설정

전부 환경변수다. 모델 ID 하드코딩은 `test_adapters.py` 가 막는다.

| 변수 | 기본값 | 비고 |
|---|---|---|
| `AI_PROVIDER` | `claude` | `claude` \| `ollama` \| `fake` — LLM만 바뀐다 |
| `AI_MODEL` | `claude-opus-5` | 날짜 접미사를 붙이지 않는다 |
| `AI_EFFORT` | `medium` | `output_config.effort`. `budget_tokens` 는 400이다 |
| `AI_MAX_TOKENS` | `16000` | Opus 5는 adaptive thinking이 기본 ON이라 4096은 잘릴 수 있다 |
| `OLLAMA_MODEL` | `qwen3:14b` | 데스크탑 실측 후 확정 |
| `EMBEDDING_MODEL` | `BAAI/bge-m3` | `fake` 로 두면 테스트 대역 |
| `EMBEDDING_DIM` | `1024` | `V8__ai.sql` 의 `VECTOR(n)` 과 반드시 일치 |
| `SIMILARITY_THRESHOLD` | `0.6` | 미달 시 LLM 호출 0건 |

## 알려진 환경 문제

**Windows에서 `torch` DLL 로드 실패** (`WinError 1114`, `c10.dll`)

```
OSError: [WinError 1114] DLL 초기화 루틴을 실행할 수 없습니다.
Error loading "<venv>/torch/lib/c10.dll" or one of its dependencies.
```

torch 2.x 는 MSVC 런타임 **14.38 이상**을 요구하는데 설치된 버전이 그보다 낮을 때
난다. `(Get-Item C:\Windows\System32\msvcp140.dll).VersionInfo.FileVersion` 으로 확인하고,
낮으면 최신 [Visual C++ 재배포 가능 패키지](https://aka.ms/vs/17/release/vc_redist.x64.exe)
를 설치한다.

**막히는 것은 없다.** 단위 테스트는 `FakeEmbeddingAdapter` 로 돌고(그래서 포트를 분리했다),
실제 임베딩은 Linux 컨테이너에서 돈다. 영향받는 것은 호스트에서 직접 돌리는
`tests/test_embedding_e2e.py` 뿐이며, 이 파일은 런타임이 없으면 자동으로 skip 된다.

## 문서

- [가드레일 설계](../docs/ai/guardrail-design.md) — 금소법 대응 매핑, 실제 우회 사례
- [프로바이더 비교](../docs/ai/provider-comparison.md) — 측정 방법과 현재 상태
- [공격 프롬프트 부록](../docs/appendix/guardrail-attack-prompts.md) — 자동 생성
