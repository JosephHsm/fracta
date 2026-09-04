# Phase 5 — 증권사 연동 (namuh PLUG)

> 기간 1주 · 선행 Phase 1 (Phase 2~4와 병렬 가능) · FSD 참조 §9, 부록 A
>
> **v1.1 변경**: 기본 증권사를 한국투자증권(KIS) → **NH투자증권 namuh PLUG**로 전환

## 0-0. 이 연동으로 무엇을 얻는가

투입 대비 산출을 먼저 못박는다. 이 Phase가 두꺼워서 목적이 흐려지기 쉽다.

| | 내용 |
|---|---|
| **1차 목적** | **괴리율(TR-07/08) 산출용 기준가.** 플랫폼 체결가를 원자산 환산가와 비교하려면 원자산 시세가 필요하다. 이게 없으면 이 프로젝트의 차별 포인트가 성립하지 않는다 |
| **2차 목적** | 종목마스터(`m_new_stock.mst`) — 발행 시 기초자산에 실제 티커를 붙이기 위한 검색 |
| **확장 대비** | WebSocket 실시간 체결·호가. **현재 화면은 쓰지 않는다**(3초 폴링). 실시간 표시로 넘어갈 때를 대비해 연결·재접속·장 시간 게이트까지 만들어 둔 것이며, 지금 화면 밀도에서 붙여 얻는 이득이 크지 않다고 판단했다 |
| **하지 않는 것** | 주문·잔고·계좌 조회. 조회 경로 화이트리스트(`PlugPathPolicy`)가 코드 레벨에서 막는다 |

괴리율 기준가는 **체결 경로 안에서 동기로 호출된다.** 그래서 어댑터에 장중 단기 캐시(`broker.quote-cache-ttl`, 기본 3초)를 둔다 — 캐시가 없으면 연속 체결이 그대로 증권사 왕복 횟수가 되고 매칭 스레드가 그만큼 멈춘다.

## 0. 증권사 선택 근거 (README 소재)

### 왜 KIS에서 PLUG로 바꿨는가

| 근거 | 내용 |
|---|---|
| **FSD §1.2 배경과의 정합성** | 프로젝트 설계 근거가 "증권업계 B2C Open API 확산(2026)"이다. PLUG는 2026-08-10 출시로 이 트렌드의 최신 사례다. KIS는 이 서사에 속하지 않는다 |
| **차별화** | KIS 연동 포트폴리오는 포화 상태다. PLUG 기반 사례는 사실상 없다 |
| **아키텍처 무손상** | 24시간 토큰, 모의/실전 도메인 분리, 초당 호출 제한, 에러코드 기반 대응 — FSD §9.2의 요구사항이 1:1로 대응된다. `MarketDataPort` 추상화 덕분에 어댑터 교체 수준의 변경이다 |

### 리스크와 대응

| 리스크 | 대응 |
|---|---|
| 출시 3주차. 커뮤니티·레퍼런스 전무 | Phase 1~4를 `MockMarketDataAdapter`로 진행하여 **이 Phase가 전체를 블로킹하지 않게** 한다 |
| 초당 호출 제한 수치가 공식 문서에 미공개 | **오히려 README 서사 소재.** 토큰버킷으로 시작 → `IGW429xx` 실측 → 슬라이딩 윈도우 전환 (§9.2에 원래 요구된 서사가 진짜가 된다) |
| API 신청 승인 지연 가능 | Phase 5는 Phase 1 직후 **병렬 착수**. 승인 대기 중 Phase 2~4 진행 |
| 문서 미성숙 → 스펙 오해 | 모든 응답을 그대로 로깅하고, WireMock 스텁은 **실제 응답을 캡처해서** 만든다. 추측으로 스텁 작성 금지 |

### 어댑터 전략

```
MarketDataPort  (FSD §9.1 — 시그니처 변경 없음)
├── MockMarketDataAdapter        기본 Component                  ← 로컬·테스트·CI 기본값
└── NamuhPlugMarketDataAdapter   @Profile("plug") + @Primary    ← 주력
```

KIS는 v1.1에서 교체한 과거 후보이며 다시 추가하지 않는다. 설정 프로파일만으로 Mock ↔ PLUG를
전환하는 현재 구현이 포트-어댑터 경계를 검증한다.

---

## 1. 확인된 PLUG 사양

> ⚠️ **아래는 공개 문서·공식 SDK 기준이다. 구현 착수 시 반드시 재확인하고, 실제 응답으로 검증한다.**
> 출시 직후라 스펙 변경 가능성이 높다. 문서와 실제가 다르면 **실제를 따르고 이 문서를 갱신**한다.

| 항목 | 값 | 확신도 |
|---|---|---|
| 개발자 포털 | `https://www.nhplug.com` (API가이드 `/apiservice`, 테스트베드 `/testbed-console`) | 확인 |
| 공식 GitHub | `https://github.com/PLUG-OpenAPI` (Python SDK `nhplug-sdk`, MCP 샘플 `nhplug-mcp`, MIT) | 확인 |
| 실전 도메인 | `https://api.nhplug.com:8443` | 확인 |
| **모의 도메인** | `https://moapi.nhplug.com:8443` | 확인 — **2026-09-03부터 REST 시세 차단** (아래 참조) |
| 인증 | AppKey + AppSecret → OAuth 토큰 | 확인 |
| **토큰 유효기간** | **24시간** | 확인 |
| 토큰 발급 엔드포인트 | **실전 도메인에서만 발급.** 모의 도메인은 토큰 발급을 제공하지 않음 | 확인 — **설계에 반영 필수** |
| 계좌구분 코드 | `01`/`02` = 실전, **`03` = 모의** | 확인 |
| 호출 제한 | 공식 SDK가 **4건/초로 스로틀**, 하드 리밋 5건/초로 표기 | ⚠️ **실측과 다름** — 아래 참조 |
| **호출 제한 (실측)** | **모의 도메인 실효 약 1건/초.** 2건/초부터 `IGW42903` 발생 | 2026-08-31 실측 · `docs/benchmarks/broker-quota.md` |
| 제한 초과 에러 | `IGW42901`, `IGW42902`, `IGW42903` (거래건수/유량 초과) | 확인 — 실제 발생 코드는 `IGW42903` |
| 기타 에러 | `IGW40011`(검증), `IGW40031`(잘못된 AppKey), `IGW40301`(권한 없음), `IGW50025`(일시적 서버 오류) | 확인 |
| 실시간 | WebSocket 지원 (국내주식 호가·체결·예상체결) | 확인 |
| 제공 범위 | 국내/해외 주식, 파생, 채권, 금현물 + 차트 | 확인 |

**착수 전 반드시 실측할 항목**
- [x] 모의 도메인의 정확한 초당 호출 제한값 → **약 1건/초** (`docs/benchmarks/broker-quota.md` §2)
- [x] 모의 도메인에서 **미지원인 API 목록** → 사용 중인 3개 엔드포인트는 전부 지원됨.
      미지원 시 `IGW40401`이 신호이며 `broker.unsupported-on-mock` 설정 + 코드 감지 양쪽으로 폴백한다
- [x] WebSocket 접속 URL·인증 방식·구독 메시지 포맷 → 모의 `wss://moapi.nhplug.com:17070/websocket`,
      `{"header":{"token":...,"tr_type":"1"},"body":{"tr_cd":"oc","tr_key":"005930"}}` (공식 `docs/realtime_channels.md`)
- [x] 토큰 재발급 시 기존 토큰 무효화 여부 → **무효화되지 않는다.** 18회 연속 재발급 중
      진행 중이던 호출이 1건도 실패하지 않았다 (`broker-quota.md` §6)
- [x] 국내주식 현재가/일봉 조회의 정확한 요청·응답 스키마 → 실제 캡처 완료.
      스텁은 캡처본으로 생성 (`src/test/resources/wiremock/plug/README.md`)

---

## 2. 범위

**포함**
- `MarketDataPort` 구현체 `NamuhPlugMarketDataAdapter`
- OAuth 토큰 관리 + 만료 30분 전 선제 갱신
- 현재가 / 기간별 시세(일봉) 조회
- WebSocket 실시간 체결·호가 + 재연결
- 쿼터 관리 (토큰버킷 + 슬라이딩 윈도우, 설정 스위치)
- `MockMarketDataAdapter` (랜덤워크)
- 시세 → 조각 환산 (§9.4)
- WireMock 기반 통합 테스트

**제외**
- 주문 API 연동 — **이 프로젝트는 외부 증권사에 주문을 내지 않는다.** FRACTA의 매매는 자체 오더북(Phase 6)에서 체결된다. 증권사 API는 **시세 조회 전용**이다
- 괴리율 경보 (Phase 6)
- 해외주식·파생·채권 (범위 외)

> **중요**: FSD §1.4에 "모의투자 주문"이 포함되어 있으나, 자체 오더북을 갖는 구조에서 외부 주문은 불필요하고 위험만 늘린다. **시세 조회 전용으로 축소**한다. 이 결정을 FSD에 반영했다.

---

## 2-1. 전제가 뒤집힌 건 (2026-09-03 추가)

이 Phase는 "시세는 모의 도메인에서 받는다"를 전제로 설계했다. **그 전제가 깨졌다.**

```
moapi  REST 시세 11종 + 해외(gbstock)   →  IGW40023 "모의투자에서는 제공하지 않는 API입니다"
moapi  /n2/acctinfo (대조군)            →  정상
moapi  WebSocket 17070                 →  연결됨
api    REST 시세                        →  정상
```

8월 31일에는 모의에서 됐다(`scripts/plug/captured/` 캡처와 유량 실측이 증거).
공식 SDK README에도 이 제약은 없다. 전수 실측표는
[`docs/reference/plug-support-matrix.md`](../reference/plug-support-matrix.md).

**대응** — 시세를 실전 도메인에서 받되, **안전 근거를 도메인에서 경로로 옮겼다.**
위험을 정하는 건 어느 서버냐가 아니라 무엇을 부르냐다. 모의 도메인에 주문을 보내도
주문은 나간다. 지금 규칙이 더 정확하다.

| 층 | 무엇을 막나 |
|---|---|
| 시세 요청 본문에 계좌번호가 없다 | `{iem_cd, market_cd}`뿐 — 계좌를 특정할 방법이 없다 |
| `PlugPathPolicy` | 런타임 화이트리스트. `/krstock/quote/` 외 거부 |
| `NoBrokerOrderPathTest` | **주문 경로가 소스에 나타나기만 해도 빌드 실패** |
| `BrokerSafetyValidator` | 설정된 엔드포인트가 전부 조회인지 부팅 시 검증 |

이 과정에서 버그도 하나 잡았다 — 폴백 판정이 `IGW40401`만 보고 있어서
**준비해 둔 Mock 폴백이 발동하지 않았다.** 실제로 오는 코드는 `IGW40023`이다.

## 3. 핵심 사양

### 3.1 포트 (FSD §9.1 — 변경 금지)

```java
public interface MarketDataPort {
    Quote getCurrentPrice(String ticker);
    List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to);
    void subscribeRealtime(String ticker, Consumer<Tick> handler);
    void unsubscribe(String ticker);
}
```

### 3.2 토큰 관리 ★ PLUG 고유 주의점

```
토큰 발급: 실전 도메인(api.nhplug.com)에서만 가능
데이터 조회: 모의 도메인(moapi.nhplug.com)으로 호출
→ 두 도메인을 쓰는 클라이언트를 분리 구성해야 한다
```

- 발급 시각 저장 → 만료 30분 전 스케줄러가 선제 갱신
- **갱신 중 요청은 대기** (`ReadWriteLock` 또는 `Mono.cache` 패턴)
- 토큰은 로그에 절대 남기지 않는다 (마스킹 필터 대상)
- 로컬 캐시 파일 사용 금지 — Redis에 저장하여 재기동 시 재사용 (24시간 한도 내 불필요한 재발급 방지)

### 3.3 쿼터 관리 (FSD §9.3) ★ README 소재

**두 구현을 모두 유지하고 설정으로 스위치한다.**

```
broker.rate-limit.strategy = bucket | sliding   (기본: sliding)
broker.rate-limit.per-sec  = 1                  (모의 도메인 실측값)
```

슬라이딩 윈도우 (Redis Sorted Set):
```java
public boolean tryAcquire(String key, int limit, Duration window) {
    long now = System.currentTimeMillis();
    redis.zRemRangeByScore(key, 0, now - window.toMillis());
    if (redis.zCard(key) >= limit) return false;
    redis.zAdd(key, now, UUID.randomUUID().toString());
    redis.expire(key, window);
    return true;
}
```

- **선택 이유를 코드 주석과 README에 명시**: 토큰버킷은 윈도우 경계에서 버스트가 발생해 서버 측 제한에 걸릴 수 있다
- `IGW42901~42903` 수신 시 → 카운터 증가 + WARN 로그 + 백오프. **메트릭 `fracta.broker.quota.rejected` 필수**

### 3.4 WebSocket

- 재연결: 지수 백오프 `1s → 2s → 4s → ... → 60s` (상한 고정)
- 재연결 시 **구독 목록 자동 복원**
- 장 시간(09:00~15:30) 외에는 폴링·구독 중단, 마지막 종가 캐시 사용
- 연결 상태를 Actuator health indicator로 노출

### 3.5 미지원 API 폴백 (FSD §9.2)

```
호출 전 지원 여부 체크 → 미지원이면 MockMarketDataAdapter 로 폴백 + WARN 로그
```
- 지원 목록을 `application-{profile}.yml`에 **설정 테이블로 분리.** 하드코딩 금지
- 폴백 발생 시 응답에 `source: MOCK` 표시 (디버깅용)

### 3.6 시세 → 조각 환산 (FSD §9.4)

```
조각 참조가 = round(원자산 현재가 / 분할비율)
괴리율     = (플랫폼 체결가 - 조각 참조가) / 조각 참조가 × 100

예) 원자산 4,200원, 분할비율 1주 = 100조각 → 조각 참조가 42원
```
- 반올림 규칙을 **명시적으로 고정** (`RoundingMode.HALF_UP`)
- `분할비율`은 `underlying_asset`에 컬럼 추가
- 원자산 가격이 0 또는 미조회 시 괴리율 계산을 **건너뛴다** (0으로 나누기 방지)

---

## 4. 구현 순서

1. `MarketDataPort` + `Quote`/`Candle`/`Tick` 값 객체 정의
2. **`MockMarketDataAdapter` 먼저 구현** (랜덤워크). 이게 있어야 Phase 2~4·6이 안 막힌다
3. PLUG 개발자 포털에서 API 신청 → AppKey/Secret 발급 → **테스트베드에서 수동 호출로 실제 응답 캡처**
4. 캡처한 응답으로 WireMock 스텁 작성 (`src/test/resources/wiremock/plug/*.json`)
5. `PlugTokenManager` — 발급·캐싱(Redis)·선제 갱신·갱신 중 대기
6. `PlugRateLimiter` — 토큰버킷 + 슬라이딩 윈도우 2구현 + 설정 스위치
7. `NamuhPlugMarketDataAdapter` — 현재가·일봉 (REST)
8. WebSocket 클라이언트 — 구독·재연결·복원
9. 미지원 API 폴백 + 장 시간 외 처리
10. `PriceConverter` — 조각 환산 + 괴리율 계산 (순수 함수)
11. 24시간 무중단 폴링 검증 실행

---

## 5. 완료 조건 체크리스트

- [x] **무중단 폴링 성공** (FSD §14 명시 조건) — 토큰 자동 갱신 최소 1회 포함
      → ⚠️ **24시간이 아니라 18분 압축 검증이다** (사용자 합의, B안). 토큰 수명은 서버가 정하므로
        갱신 여유값을 86,340초로 두어 갱신 주기를 60초로 압축했다. 결과: **1,090초 무중단,
        902회 전부 성공, 토큰 자동 갱신 18회.** 24시간 연속 가동에서만 드러나는 문제(커넥션 누수,
        자정 경계 등)는 검증되지 않았다 — `docs/benchmarks/broker-quota.md` §5·§6
- [x] **쿼터 초과 0건** (FSD §14 명시 조건) — `IGW429xx` 수신 횟수 0
      → 902회 중 0건. 단, 문서값 4건/초로는 70% 이상이 거절됐다 (`broker-quota.md` §2)
- [x] `MockMarketDataAdapter` 로 전체 테스트 스위트 통과 (외부 의존 없이 CI 가능)
      → 전체 143건이 자격증명 없이 통과한다 (WireMock + Mock)
- [x] 토큰 만료 30분 전 선제 갱신 동작 확인 (시간 조작 테스트)
      → `PlugTokenManagerTest.preemptiveRefreshWhenNearExpiry` + 내구 실행 중 18회 실제 갱신
- [x] 토큰 갱신 중 동시 요청 10건 → 발급 호출은 **1회만** 발생
      → `concurrentRequestsIssueTokenOnce` (WireMock이 발급 요청 수를 셈)
- [x] 토큰이 로그·`audit_log`에 평문 노출되지 않음 → `tokenNotLeaked`
- [x] 토큰 발급은 실전 도메인, 데이터 조회는 모의 도메인으로 분리 호출됨을 WireMock으로 검증
      → `tokenAndDataUseSeparateDomains` (두 WireMock 서버로 분리 확인)
- [x] 쿼터 초과 시 요청이 대기/거절되고 `fracta.broker.quota.rejected` 메트릭 증가
      → `rejectionIncrementsMetric`, `waitsInsteadOfRejectingWhenBudgetAllows`
- [x] 토큰버킷 ↔ 슬라이딩 윈도우 설정 스위치 동작 확인 (양쪽 다 테스트 통과)
      → `RateLimiterSelectorTest` + `RateLimiterTest`
- [x] 슬라이딩 윈도우 **경계 시점 버스트 차단** 테스트 (FSD §15.1)
      → `slidingBlocksBoundaryBurst` + 실서버 실측(균등 12/12 성공 vs 버스트 4/12)
- [x] WebSocket 강제 종료 → 지수 백오프 재연결 → **구독 목록 복원** 확인
      → `PlugWebSocketClientTest` (백오프 1→2→4→…→60초 상한, 복원 전송 검증)
- [x] 장 시간 외 폴링 중단 + 마지막 종가 캐시 반환 확인 → `MarketClosedCacheTest`
- [x] 미지원 API 호출 → Mock 폴백 + WARN 로그 확인 → `unsupportedUriFallsBackToMock` (`IGW40401`)
- [x] `PriceConverter` 단위 테스트 — 분할비율 경계값, 반올림, 원자산가 0 처리 → 7건
- [x] WireMock 통합 테스트 — 정상/`IGW40031`/`IGW42901`/`IGW50025` 응답 각각 처리
      → 스텁은 **실제 캡처 응답**으로 만들었다 (`src/test/resources/wiremock/plug/README.md`)
- [x] `BrokerSafetyValidator`(Phase 1)가 여전히 동작 — 실전 계좌구분 설정 시 부팅 실패
      → 6건 전부 통과. 루프백(WireMock)만 예외로 허용하고 실전 도메인은 그대로 차단한다
- [x] tr_id/엔드포인트 매핑이 **설정으로 분리**됨 (하드코딩 0건)
      → ⚠️ 위치가 다르다: `application-plug.yml`은 `.gitignore` 대상(시크릿 보관 용도)이라
        커밋되지 않으므로, 비밀이 아닌 엔드포인트 매핑은 `application.yml`의 `broker.endpoints`에 뒀다.
        시크릿은 `.env`(gitignore) → 환경변수로만 주입된다

> **완료 근거** (2026-08-31, 커밋 `8750e0d`): 증권사 테스트 42건 포함 전체 143건 통과.
> 유량 실측·버스트 비교·지속 폴링 결과는 `docs/benchmarks/broker-quota.md`.
> 오류코드 표는 `docs/reference/plug-error-codes.md`.

---

## 6. 흔한 실수

1. **토큰 발급을 모의 도메인으로 시도** → PLUG는 실전 도메인에서만 발급한다. 두 클라이언트 분리 필수
2. **계좌구분 `03`을 실전 도메인에 보냄** (또는 반대) → 무조건 실패. 도메인-계좌구분 쌍을 설정에서 검증
3. 엔드포인트·거래ID를 코드에 하드코딩 → 프로파일별 설정 테이블로 분리 (FSD §9.2)
4. 토큰을 인메모리에만 보관 → 재기동마다 재발급. 24시간 한도를 낭비한다. Redis 저장
5. 갱신 중 요청을 대기시키지 않아 **동시 갱신 폭주** → 락 또는 single-flight 패턴
6. WebSocket 재연결 시 구독 복원 누락 → 조용히 시세가 멈춘다
7. 문서를 추측해서 WireMock 스텁 작성 → 실제와 다름. **반드시 실제 응답을 캡처**
8. 원자산 가격 미조회 상태에서 괴리율 계산 → 0으로 나누기 또는 무의미한 값
9. 백오프 상한 없이 무한 증가 → 60초 상한 고정
10. **실전 도메인으로 주문 API를 호출** → 진짜 돈이 나간다. 이 Phase는 **시세 조회 전용**이다

---

## 7. 문서화 (README 필수 항목 4번)

`docs/benchmarks/broker-quota.md` 에 서사를 기록한다:

1. PLUG의 초당 제한이 **공식 문서에 없다**는 사실
2. 토큰버킷으로 시작 → 어느 시점에 `IGW429xx`가 발생했는가 (실측값)
3. 슬라이딩 윈도우로 전환 → 발생 0건 달성
4. 두 방식의 경계 시점 동작 차이를 그림 또는 표로
5. 최종 설정값과 그 근거

이 서사는 **면접에서 그대로 쓰는 소재**다. 추측이 아니라 실측 기록으로 남긴다.

---

## 8. 다음 Phase 진입 전 확인

- [x] `MarketDataPort` 가 Phase 6의 괴리율 계산에 필요한 정보를 전부 제공하는가
      → `getCurrentPrice`(원자산 현재가) + `PriceConverter`(조각 참조가·괴리율)로 충족
- [x] `MockMarketDataAdapter` 만으로 Phase 6 완료 조건을 충족할 수 있는가
      → 충족한다. Plug 어댑터는 `@Profile("plug")`이고 기본값이 Mock이라 증권사 장애가 개발·CI를 막지 않는다
- [x] 분할비율 컬럼이 `underlying_asset` 에 추가되었는가 → Phase 3의 `V4__issuance.sql`에서 `split_ratio`로 추가됨
