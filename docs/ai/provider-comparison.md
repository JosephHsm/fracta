# 프로바이더 비교 — Claude vs 폐쇄망(Ollama)

> Phase 8 산출물 · README 필수 항목 7번
> 측정 스크립트: [`scripts/ai/measure_provider.py`](../../scripts/ai/measure_provider.py)

**이 문서의 표는 아직 완성되지 않았다.** 추정치로 채우지 않고 비워 둔다 —
이 프로젝트는 실측값만 기록한다(`docs/benchmarks/` 참조). 각 칸이 언제 어떻게
채워지는지를 아래에 명시한다.

---

## 0. 현재 상태

| 열 | 상태 | 이유 |
|---|---|---|
| Claude (`claude-opus-5`) | **측정 대기** | 크레딧 충전 후 `capture_attacks.py` + `measure_provider.py` 1회 실행이면 채워진다 |
| Ollama (로컬) | **측정 대기 — 데스크탑 환경** | 개발 노트북(Ryzen 5 5625U, GPU 없음)에서는 7B 이상 모델이 CPU 추론 3~6 tok/s 라 품질·지연 비교의 의미가 없다 |

측정 환경이 갈리는 이유를 분명히 해 둔다.

| | 개발 노트북 | 데스크탑 |
|---|---|---|
| CPU / GPU | Ryzen 5 5625U (6C/12T) / iGPU 공유 2GB | — / **RTX 4080 16GB** |
| RAM | 13.8 GB | — |
| 임베딩(bge-m3, 568M) | **가능** — CPU 추론으로 청크당 0.1~0.3초 | 가능 (GPU) |
| LLM 7B~20B 양자화 | 사실상 불가 (GPU 오프로드 없음) | **가능** — FSD §10.4의 "VRAM 16GB 기준" 과 일치 |

임베딩과 LLM을 분리한 설계(`EmbeddingPort` ≠ `LlmPort`)가 여기서 실익을 낸다.
노트북에서도 검색·인덱싱·가드레일은 전부 돌아가고, LLM 품질 비교만 데스크탑으로 미룰 수 있다.

---

## 1. 비교표 (채울 대상)

| 항목 | Claude (`claude-opus-5`) | Ollama (로컬) |
|---|---|---|
| 응답 품질 (정성 평가) | 측정 대기 | 측정 대기 (데스크탑) |
| p50 지연시간 | 측정 대기 | 측정 대기 (데스크탑) |
| p95 지연시간 | 측정 대기 | 측정 대기 (데스크탑) |
| 인용 정확도 (`citation_exact_rate`) | 측정 대기 | 측정 대기 (데스크탑) |
| 가드레일 통과율 (`guardrail_pass_rate`) | 측정 대기 | 측정 대기 (데스크탑) |
| JSON 스키마 준수 (파싱 실패 건수) | 측정 대기 | 측정 대기 (데스크탑) |
| 1,000회 질의 비용 | 측정 대기 (단가 $5/$25 per 1M) | 전력·하드웨어 (환산 안 함) |
| 폐쇄망 동작 | 불가 (외부 API) | 가능 |

측정 조건은 두 열이 동일하다. `measure_provider.py` 가 같은 컨텍스트(3개 청크,
페이지 3/7/12)와 같은 10문항을 쓰고, 시스템 프롬프트·스키마·`max_tokens` 도 같다.
`temperature` 는 양쪽 다 설정하지 않는다 — 한쪽만 조정하면 같은 조건 비교가 아니다.

---

## 2. 비용 산출 방식

1,000회를 실제로 돌리지 않는다. **실측 단가 × 1,000** 으로 환산한다.
근거 토큰 수는 `ai_conversation_log.input_tokens` / `output_tokens` 에 전량 기록된다.

```
질의당 비용 = (입력토큰 / 1M × $5.00) + (출력토큰 / 1M × $25.00)
```

Opus 5는 adaptive thinking이 기본 ON이라 사고 토큰이 출력에 포함된다.
`output_config.effort` 를 `medium` 으로 둔 것도 이 때문이다 — 문서 인용 QA에
`high` 이상은 과하고, 비용은 출력 토큰에 비례한다.

Ollama 열은 달러로 환산하지 않는다. 전력과 하드웨어 상각을 임의 가정으로 환산하면
그건 실측이 아니라 추정이고, 이 문서의 전제를 깬다.

---

## 3. 채우는 방법

### 3.1 Claude (노트북에서 가능, 크레딧 필요)

```bash
# 0. 예상 비용 확인 (실호출 없음)
ai-service/.venv/Scripts/python.exe scripts/ai/capture_attacks.py --dry-run

# 1. 공격 프롬프트 20종 실제 응답 캡처 (약 $0.61)
#    → 픽스처에 저장되어 이후 pytest 가 무료로 재생한다
ai-service/.venv/Scripts/python.exe scripts/ai/capture_attacks.py
ai-service/.venv/Scripts/python.exe scripts/ai/render_attack_table.py

# 2. 지연·인용 정확도·비용 실측 (10문항 x 3회 = 30건, 약 $0.96)
AI_PROVIDER=claude ai-service/.venv/Scripts/python.exe \
    scripts/ai/measure_provider.py --repeat 3
```

**예상 총액 약 $1.6.** $5 크레딧 안에서 시행착오 여유가 남는다.

### 3.2 Ollama (데스크탑, RTX 4080)

```bash
docker compose --profile offline up -d ollama
docker compose --profile offline exec ollama ollama pull qwen3:14b

AI_PROVIDER=ollama OLLAMA_MODEL=qwen3:14b \
    ai-service/.venv/Scripts/python.exe scripts/ai/measure_provider.py --repeat 3
```

결과는 `scripts/ai/results/provider-*.json` 에 남고, 그 값을 위 표에 옮긴다.

---

## 4. 폐쇄망 모델 선정 — 아직 고르지 않았다

`OLLAMA_MODEL` 은 설정값이므로 코드 변경 없이 바꾼다. 데스크탑에서 아래 후보를
같은 스크립트로 돌려 **인용 정확도와 JSON 스키마 준수율**로 고른다.
추정으로 고르지 않고 재서 고른다.

| 후보 | 4080(16GB)에서 | 이 워크로드 관점 |
|---|---|---|
| **Qwen3 14B** (1순위) | Q4_K_M 약 9GB, 약 35 tok/s | 119개 언어 커버, 한국어 우세 평가. 9GB라 컨텍스트 여유 7GB |
| gpt-oss-20b | 약 12~14GB, 42+ tok/s | 추론·메모리 효율 우수하나 다국어 학습이 얕아 한국어 상대적 약세 |
| EXAONE 3.5 7.8B | 약 5GB | 한국어 특화. 파라미터가 작아 스키마 준수도 확인 필요 |

선정 기준이 코딩 능력이나 벤치마크 종합 점수가 **아니라는** 점이 중요하다.
이 서비스가 시키는 일은 한국어 문서 이해 + JSON 스키마 준수 두 가지고,
그 두 축은 `measure_provider.py` 의 `citation_exact_rate` 와 `parse_failures` 로 바로 측정된다.

---

## 5. 왜 vLLM이 아니라 Ollama인가

RTX 4080은 NVIDIA라 vLLM도 기술적으로 가능하다. 그럼에도 Ollama를 쓴다.

| | 판단 |
|---|---|
| vLLM의 우위 구간 | **동시 10명 이상**에서 3~5배 처리량. 배치 1에서는 Ollama와 20% 이내 |
| 이 서비스의 부하 | 폐쇄망 시연 = 배치 1. vLLM의 우위가 발현되지 않는다 |
| 스키마 강제 | Ollama는 `/api/chat` 의 `format` 에 JSON 스키마를 그대로 받는다 — Claude의 `output_config.format` 과 1:1 대응 |
| 모델 교체 | `ollama pull` 한 줄. 위 §4의 3종 비교가 쉬워진다 |
| 사양 | FSD §10.4와 phase-08 §3.5가 `OllamaAdapter` 를 명시한다 |

바꾸더라도 `LlmPort` 뒤라 어댑터 한 개 추가로 끝난다. Phase 5에서 KIS 어댑터를
포트 교체 가능성 증명용으로 남겨 둔 것과 같은 구조다.

---

## 6. 전환 방법

```bash
AI_PROVIDER=ollama   # 이 한 줄
```

임베딩은 이 스위치를 따르지 않는다. `EmbeddingPort` 는 항상 로컬 bge-m3다 —
LLM만 로컬로 돌리고 임베딩을 외부 API로 보내면 질문 텍스트가 밖으로 나가므로
폐쇄망이 성립하지 않는다. `test_adapters.py::test_임베딩은_AI_PROVIDER를_따르지_않는다`
가 이걸 고정한다.

---

## 7. 폐쇄망 시연 캡처

**미완 — 데스크탑 환경에서 촬영한다.** 네트워크를 끊은 상태에서
`/ai/prospectus/ask` 가 정상 응답하는 화면과 `/ai/health` 의 `provider: ollama`
표시를 함께 담는다.
