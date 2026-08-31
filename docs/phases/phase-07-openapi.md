# Phase 7 — 오픈 API ★차별 포인트

> 기간 1.5주 · 선행 Phase 6 · FSD 참조 §6.7, §7, §8.5

## 목표

Phase 1~6에서 만든 도메인을 **외부 개발자가 쓸 수 있는 제품**으로 포장한다. 이 Phase가 프로젝트의 정체성이다.

## 범위

**포함**
- API 클라이언트 등록, `client_id`/`client_secret` 발급
- OAuth2 Client Credentials Grant
- Scope 기반 권한
- Rate Limit + 응답 헤더
- 멱등성 미들웨어 (Redis)
- 웹훅 등록·발송·재시도·HMAC 서명
- 샌드박스 환경
- 호출 로그
- Swagger(springdoc) 문서화

**제외**
- 개발자 포털 UI (Phase 10)
- AI 개발자 어시스턴트 (Phase 8)

## 핵심 사양

### 인증 (OA-01, OA-02)

```
POST /open/v1/oauth/token
grant_type=client_credentials&client_id=...&client_secret=...
→ { access_token, token_type: "Bearer", expires_in: 3600, scope: "..." }
```
- `client_secret`은 **SHA-256 해시 저장**, 평문은 발급 시 1회만 노출 (FSD §13.2)
- 재발급 시 기존 secret 즉시 무효화
- access_token 유효 1시간

### Scope (OA-03)

| Scope | 허용 엔드포인트 |
|---|---|
| `market:read` | 종목 목록/상세/호가/체결/괴리율 |
| `account:read` | 잔고, 주문 내역 |
| `order:write` | 주문 생성/취소 |
| `subscription:write` | 청약 |

- Scope 부족 → `403 AUTH_SCOPE_DENIED`
- 메서드 레벨 검증 (`@PreAuthorize` 또는 커스텀 애노테이션)

### Rate Limit (OA-04, OA-05)

```
기본: 초당 10건 / 일 10,000건 (클라이언트별 설정 가능)
```
- **Phase 5의 슬라이딩 윈도우 구현을 재사용한다.** 새로 만들지 않는다
- 모든 응답(성공 포함)에 헤더 부착:
```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 7
X-RateLimit-Reset: 1755676800
```
- 초과 시:
```
HTTP/1.1 429
Retry-After: 1
{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"초당 호출 한도를 초과했습니다."}}
```

### 멱등성 (OA-06, §8.5) ★

```
1. Idempotency-Key 헤더 추출 (없으면 400)
2. Redis SETNX "idem:{clientId}:{key}" = "PROCESSING" (TTL 24h)
   - 성공 → 4번
   - 실패(이미 존재) → 3번
3. 기존 값 조회
   - "PROCESSING" → 409 IDEM_IN_PROGRESS
   - 저장된 응답 → 그대로 반환 (200 + X-Idempotent-Replay: true)
4. 실제 처리
5. 응답을 Redis 저장 (TTL 24h) + api_call_log 기록

[본문 해시 검증] 같은 키인데 본문이 다르면 → 422 IDEM_KEY_CONFLICT
```

**구현 주의**
- 처리 중 예외 발생 시 `PROCESSING` 키를 **반드시 삭제**한다. 안 그러면 24시간 동안 재시도가 막힌다
- 본문 해시는 정규화 후 SHA-256 (공백·키 순서 차이로 오탐 방지)
- 필터/인터셉터로 구현하여 `order:write`·`subscription:write` 엔드포인트에 일괄 적용

### 웹훅 (OA-07, OA-08)

```
X-Fracta-Signature: t=1755676800,v1=5257a869...

v1 = HMAC_SHA256(secret, "{t}.{raw_body}")
수신 측은 t가 현재 시각 ±5분 이내인지 확인 후 타이밍 세이프 비교
```

- 이벤트: `order.filled`, `order.partially_filled`, `order.cancelled`, `subscription.allotted`, `token.listed`, `token.suspended`
- 발송 큐: **Redis Stream** (FSD §4.2 — 비동기가 필요한 유일한 지점)
- 재시도: 지수 백오프 **5회**. 최종 실패 시 DLQ 기록 + 포털에서 수동 재발송
- `raw_body`는 **직렬화된 그대로** 서명한다. 재직렬화하면 서명이 깨진다

### 샌드박스 (OA-09)

```
/open/sandbox/v1/...   ← env=SANDBOX 클라이언트 전용
```
- **별도 스키마**의 가상 데이터 사용 (FSD 명시)
- 구현 방식 결정 필요 → **권장: PostgreSQL 스키마 분리 + 요청 컨텍스트 기반 라우팅**
  - 대안(별도 DB, 별도 인스턴스)보다 운영 단순
- 샌드박스는 항상 `MockMarketDataAdapter` 시세 사용
- LIVE 클라이언트가 샌드박스 경로 호출 → `403`, 반대도 `403`

### 호출 로그 (OA-10)

- 모든 호출: `client_id`, `endpoint`, `status_code`, `latency_ms`, `idempotency_key`, `called_at`
- **비동기 기록** (요청 지연에 영향 주지 않게)
- 요청/응답 본문은 저장하지 않는다 (개인정보·용량). 필요 시 해시만

## 구현 순서

1. Flyway `V7__openapi.sql` — `api_client`, `api_call_log`, `webhook_endpoint`, `webhook_delivery`
2. 클라이언트 등록 + secret 발급/해시/재발급
3. OAuth2 토큰 엔드포인트 + JWT 발급 (Phase 3의 웹앱 JWT와 **별도 발급자**로 분리)
4. Scope 검증
5. Rate Limit 인터셉터 + 응답 헤더 (Phase 5 구현 재사용)
6. 멱등성 인터셉터
7. 오픈 API 컨트롤러 — FSD §7.2의 12개 엔드포인트
8. 웹훅 등록 + Redis Stream 발송 워커 + 서명 + 재시도
9. 샌드박스 스키마 라우팅
10. 호출 로그 (비동기)
11. springdoc 문서화 + Swagger UI

## 완료 조건 체크리스트

- [ ] **멱등성 테스트: 동시 중복 요청 100건 → 실제 처리 1건** (FSD §14 명시 조건)
- [ ] **웹훅 서명 검증 통과** (FSD §14 명시 조건)
- [ ] FSD §7.2의 **12개 엔드포인트 전부** 구현 및 동작
- [ ] `client_secret` 이 DB에 평문으로 저장되지 않음 (해시만)
- [ ] secret 재발급 시 기존 secret 즉시 무효화
- [ ] Scope 없는 토큰으로 호출 → `403 AUTH_SCOPE_DENIED`
- [ ] 4개 Scope 각각의 허용/거부 매트릭스 테스트
- [ ] 만료된 access_token → `401 AUTH_INVALID_TOKEN`
- [ ] Rate Limit — 초당 한도 초과 시 `429` + 3개 헤더 + `Retry-After`
- [ ] Rate Limit — **경계 시점 버스트 차단** (FSD §15.1)
- [ ] 일 한도(10,000건) 초과 테스트
- [ ] 성공 응답에도 `X-RateLimit-*` 헤더 존재
- [ ] 멱등성 — 동일 키 재요청 시 `X-Idempotent-Replay: true` + 동일 응답
- [ ] 멱등성 — `PROCESSING` 중 재요청 → `409 IDEM_IN_PROGRESS`
- [ ] 멱등성 — 같은 키 + 다른 본문 → `422 IDEM_KEY_CONFLICT`
- [ ] 멱등성 — **처리 중 예외 발생 시 PROCESSING 키가 삭제됨** (재시도 가능)
- [ ] `Idempotency-Key` 없이 `order:write` 호출 → `400`
- [ ] 웹훅 HMAC 서명 생성·검증 단위 테스트
- [ ] 웹훅 타임스탬프 ±5분 초과 → 검증 실패 (리플레이 방지)
- [ ] 웹훅 수신 실패 → 지수 백오프 5회 재시도 → DLQ 기록
- [ ] 6개 이벤트 전부 발송 확인
- [ ] 샌드박스 — LIVE 클라이언트가 샌드박스 경로 호출 시 `403` (반대도)
- [ ] 샌드박스 데이터가 실서비스 데이터와 완전히 격리됨
- [ ] 모든 호출이 `api_call_log`에 기록됨
- [ ] 호출 로그 기록이 응답 지연에 영향 없음 (비동기 확인)
- [ ] Swagger UI에서 12개 엔드포인트 전부 문서화 + 예제 포함
- [ ] 조회 API p95 < 200ms (FSD §13.1)

## 흔한 실수

1. **멱등성 처리 중 예외 시 PROCESSING 키를 안 지움** → 24시간 재시도 불가. 가장 흔한 버그
2. 웹훅 서명을 재직렬화한 본문으로 생성 → 수신 측 검증 실패. **raw body 그대로**
3. 서명 비교에 `String.equals` 사용 → 타이밍 공격. `MessageDigest.isEqual` 사용
4. Rate Limit 헤더를 429 응답에만 부착 → 성공 응답에도 필요 (OA-05)
5. `client_secret`을 로그·`audit_log`에 기록 → 마스킹 필터 대상
6. 오픈 API JWT와 웹앱 JWT를 같은 발급자/시크릿으로 처리 → 권한 경계 붕괴. 분리
7. 샌드박스를 `if (sandbox)` 분기로 도메인 코드에 흩뿌림 → 스키마 라우팅으로 격리
8. 호출 로그를 동기 INSERT → p95 목표 초과
9. Rate Limit을 Phase 5와 별개로 새로 구현 → 중복. 재사용
10. 웹훅 발송을 요청 스레드에서 동기 처리 → 수신 측 지연이 주문 API를 막는다

## 문서화 (README 필수 항목)

- 멱등성 상태 전이 다이어그램
- 웹훅 서명 검증 예제 코드 (수신 측 관점, 언어 2종)
- Rate Limit 정책과 헤더 규격
- 샌드박스와 LIVE의 차이 표

## 다음 Phase 진입 전 확인

- [ ] OpenAPI 스펙(JSON)이 생성되는가? Phase 10의 `api-client` 타입 생성이 이걸 쓴다
- [ ] 개발자 포털이 보여줄 데이터(호출량·쿼터·에러율)가 조회 가능한가
