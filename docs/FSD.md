# FRACTA — 기능 사양서 (Functional Specification Document)

> **토큰증권 기반 조각투자 발행·유통 플랫폼 & 오픈 API**
>
> | 항목 | 내용 |
> |---|---|
> | 문서 버전 | v1.1.3 |
> | 작성일 | 2026-08-20 (v1.1.3 개정: 2026-09-01) |
> | 문서 목적 | AI 개발 에이전트가 이 문서만으로 전체 구현이 가능하도록 하는 단일 진실 공급원(SSOT) |
> | 프로젝트 성격 | 1인 개발 포트폴리오 / 금융IT 직무 지원용 |

---

## 0. 개발 에이전트를 위한 지침

이 문서를 읽는 에이전트는 아래 원칙을 **모든 구현 판단의 우선순위 기준**으로 삼는다.

1. **정합성 > 성능 > 기능 수**
   금액과 수량이 틀리느니 느린 게 낫다. 불변식(§8.1)을 깨는 최적화는 금지한다.
2. **부동소수점 금지**
   금액·수량 계산에 `double`, `float`을 절대 사용하지 않는다. 내부는 `long`(최소단위 정수), 외부 표현은 `BigDecimal`.
3. **포트-어댑터 일관성**
   외부 의존(원장, LLM, 증권사 API)은 반드시 인터페이스(Port)로 추상화하고 구현체(Adapter)를 분리한다.
4. **모듈러 모놀리스**
   MSA로 쪼개지 않는다. 단일 Spring Boot 애플리케이션 안에서 패키지로 경계를 만든다. 모듈 간 호출은 **공개 인터페이스(`api` 패키지)를 통해서만** 한다.
5. **모든 상태 변경은 감사 로그를 남긴다**
   누가, 언제, 무엇을, 어떤 경로(웹/API/배치)로 바꿨는지 기록한다.
6. **구현 순서는 §14 로드맵을 따른다**
   Phase를 건너뛰지 않는다. 각 Phase 종료 시 해당 테스트가 통과해야 다음으로 넘어간다.

---

## 1. 프로젝트 개요

### 1.1 한 줄 정의

부동산·리츠 등 실물 기반 자산을 조각 단위 **토큰증권**으로 발행하고, 투자자가 청약·매매하며, 그 전 기능을 외부 개발자에게 **Open API**로 개방하는 플랫폼.

### 1.2 배경 (설계 근거)

| 근거 | 내용 | 설계 반영 |
|---|---|---|
| 전자증권법·자본시장법 개정 (2026.01.15 국회 통과) | 분산원장이 법적 효력을 갖는 증권 계좌부로 인정. 발행인 계좌관리기관 제도 도입. 투자계약증권의 증권사 유통 허용 | `ledger` 모듈을 독립 도메인으로 분리. 발행/유통 주체를 분리 모델링 |
| 증권업계 B2C Open API 확산 (2026) | KB증권 개인 대상 오픈베타(2026.07.20), 토스증권 진입, 키움 REST API 확대 | `openapi` 모듈 — 개발자 포털·샌드박스·쿼터 |
| 생성형 AI + 금융 결합 | AI가 정보를 분석하고 API가 주문까지 연결하는 구조 확산 | `ai` 모듈 — 단, 금소법 준수 가드레일 필수 |

### 1.3 사용자 (Actor)

| Actor | 설명 | 주요 행위 |
|---|---|---|
| **ISSUER** (발행인) | 기초자산 보유자 | 자산 등록, 투자설명서 업로드, 발행 신청 |
| **INVESTOR** (투자자) | 개인 투자자 | 계좌 개설, 투자성향 진단, 청약, 매매, 잔고 조회 |
| **DEVELOPER** (외부 개발자) | 서드파티 앱 개발자 | API 키 발급, 샌드박스 테스트, 실서비스 연동 |
| **ADMIN** (운영자) | 플랫폼 관리자 | 발행 승인, 대사 결과 확인, 거래 중단 |
| **SYSTEM** | 배치·스케줄러 | 일일 대사, 정산, 시세 동기화 |

### 1.4 범위 (Scope)

**포함**
- 조각증권 발행 → 청약 → 유통 → 결제 → 대사 전 과정
- 자체 Open API 및 개발자 포털
- NH투자증권 namuh PLUG Open API 연동 (실시간 시세 조회 전용)
- AI 투자설명서 Q&A (가드레일 포함), 개발자 어시스턴트

**제외 (명시적 비목표)**
- 실제 블록체인 노드 운영 (해시체인 원장으로 대체, §8.2)
- 실제 자금 이체 (예치금은 시뮬레이션 원장)
- 실제 KYC 심사 (Mock 처리)
- 증권사 API를 통한 주문 (시세 조회 전용. FRACTA 매매는 자체 오더북에서 체결)
- 실전투자 계좌 연동 (모의 도메인 + 계좌구분 `03`만 사용)
- 다국어, 모바일 네이티브 앱

---

## 2. 용어집 (Glossary)

에이전트는 코드 내 네이밍에 아래 용어를 **일관되게** 사용한다.

| 용어 | 코드 표기 | 정의 |
|---|---|---|
| 기초자산 | `UnderlyingAsset` | 조각의 대상이 되는 실물/증권 자산 |
| 발행 | `Issuance` | 기초자산을 토큰으로 만드는 행위 |
| 조각증권 / 토큰 | `Token` | 발행 결과물. 최소 단위로 분할된 증권 |
| 조각 수량 | `units` (long) | 토큰 개수. **정수만 사용** |
| 청약 | `Subscription` | 발행 시점의 최초 배정 절차 |
| 배정 | `Allotment` | 청약 결과 확정된 수량 |
| 유통 | `Trading` | 발행 이후 투자자 간 매매 |
| 오더북 | `OrderBook` | 종목별 미체결 주문 집합 |
| 체결 | `Execution` | 매수·매도 주문이 매칭된 결과 |
| 원장 | `Ledger` | 잔고와 이전 기록의 단일 진실 공급원 |
| DvP | `DeliveryVersusPayment` | 증권과 대금의 동시 결제 |
| 대사 | `Reconciliation` | 원장 정합성 검증 절차 |
| 괴리율 | `PremiumRate` | (플랫폼 체결가 − 원자산 환산가) / 원자산 환산가 |
| 멱등성 키 | `IdempotencyKey` | 중복 요청 방지용 클라이언트 제공 UUID |

---

## 3. 시스템 아키텍처

### 3.1 전체 구성도

```
┌──────────────────────────────────────────────────────────┐
│  [상류] namuh PLUG Open API (NH투자증권)                   │
│   · 국내주식 현재가 / 기간별시세 (REST)                     │
│   · 실시간 체결·호가 (WebSocket)                           │
│   · 모의 도메인 moapi.nhplug.com (시세 조회 전용)          │
└───────────────────────┬──────────────────────────────────┘
                        │ MarketDataPort (소비)
                        ▼
┌──────────────────────────────────────────────────────────┐
│  FRACTA Core — Spring Boot 3.x (모듈러 모놀리스)           │
│                                                          │
│  ┌──────────┐ ┌──────────────┐ ┌──────────┐             │
│  │ issuance │ │ subscription │ │ trading  │             │
│  │  발행     │ │  청약·배정    │ │ 오더북    │             │
│  └────┬─────┘ └──────┬───────┘ └────┬─────┘             │
│       └──────────────┼──────────────┘                    │
│                      ▼                                   │
│            ┌──────────────────┐                          │
│            │  ledger          │ ◄── LedgerPort           │
│            │  해시체인 원장     │                          │
│            └────────┬─────────┘                          │
│                     │                                    │
│  ┌──────────┐ ┌─────▼──────┐ ┌──────────┐ ┌──────────┐  │
│  │settlement│ │  account   │ │ openapi  │ │  audit   │  │
│  │ DvP 결제  │ │ KYC·성향   │ │ 게이트웨이 │ │ 감사추적  │  │
│  └──────────┘ └────────────┘ └────┬─────┘ └──────────┘  │
└───────────────────────────────────┼──────────────────────┘
         │                          │
         │ 이벤트                    │ 제공
         ▼                          ▼
┌─────────────────┐      ┌──────────────────────────────┐
│ batch           │      │ [하류] 외부 개발자             │
│ · 야간 잔고대사   │      │  · OAuth2 Client Credentials │
│ · 해시체인 검증   │      │  · Scope 기반 권한           │
│ · 정산 리포트     │      │  · Rate Limit / 멱등성       │
└─────────────────┘      │  · Webhook (HMAC 서명)       │
                         └──────────────────────────────┘
         │
         ▼ LlmPort
┌──────────────────────────────────────────┐
│ ai-service (Python FastAPI, 별도 컨테이너) │
│  · 투자설명서 RAG (pgvector)               │
│  · 금소법 가드레일 / 인용 강제              │
│  · Claude API ↔ Ollama 어댑터 스위치       │
└──────────────────────────────────────────┘

[Infra] PostgreSQL 16 + pgvector · Redis 7 · MinIO · Docker Compose
```

### 3.2 모듈 의존 규칙

```
issuance ──┐
           ├──▶ ledger ──▶ audit
subscription ─┤
           │
trading ───┘──▶ settlement ──▶ ledger

account  ◀── (모든 모듈이 조회만)
openapi  ──▶ (모든 도메인 모듈의 public API 호출)
batch    ──▶ ledger, settlement
ai       ──▶ issuance (문서 조회만)
```

**금지 사항**
- 역방향 의존 금지 (`ledger`는 `trading`을 모르면 안 된다)
- 모듈 간 엔티티 직접 참조 금지 → ID 참조 + DTO 변환
- 순환 의존 발생 시 이벤트(ApplicationEvent)로 분리

### 3.3 패키지 구조

```
com.fracta
├── common/              # 공통 (에러, 응답 포맷, Money, 유틸)
│   ├── error/
│   ├── money/           # Money, Units 값객체
│   ├── ratelimit/       # Redis 원자적 슬라이딩 윈도우 (Phase 5·7 공용)
│   └── logging/
├── issuance/
│   ├── api/             # 다른 모듈에 노출하는 인터페이스 + DTO
│   ├── domain/          # 엔티티, 값객체, 도메인 서비스
│   ├── application/     # 유스케이스 (트랜잭션 경계)
│   ├── infrastructure/  # JPA 리포지토리, 외부 연동
│   └── presentation/    # REST 컨트롤러
├── subscription/        # (동일 구조)
├── trading/
├── ledger/
│   ├── api/LedgerPort.java
│   └── infrastructure/HashChainLedgerAdapter.java
├── settlement/
├── account/
├── openapi/
│   ├── auth/            # OAuth2, API Key
│   ├── gateway/         # 인증·Scope·Rate Limit 게이트웨이
│   ├── idempotency/
│   ├── log/
│   ├── sandbox/
│   ├── webhook/
│   └── presentation/
├── audit/
├── external/
│   └── broker/          # 증권사 API 어댑터
│       ├── MarketDataPort.java
│       ├── MockMarketDataAdapter.java
│       └── plug/NamuhPlugMarketDataAdapter.java
├── ai/
│   └── LlmPort.java
└── batch/
```

---

## 4. 기술 스택

### 4.1 확정 스택

| 영역 | 기술 | 버전 | 선정 사유 |
|---|---|---|---|
| 언어 | Java | 21 (LTS) | Virtual Thread, Record, Pattern Matching |
| 프레임워크 | Spring Boot | 3.3.x | |
| ORM | Spring Data JPA | | 복잡 조회도 JPA로 충분했습니다. QueryDSL은 도입했다가 실사용처가 없어 제거했습니다 |
| 배치 | Spring Batch | 5.x | 대사·정산 |
| DB | PostgreSQL | 16 | + pgvector 확장 |
| 캐시/락 | Redis | 7.x | 분산락, 쿼터, 호가 캐시 |
| 스토리지 | MinIO | latest | 투자설명서 PDF (S3 호환) |
| 인증 | Spring Security + JWT | | |
| API 문서 | springdoc-openapi | 2.x | Swagger UI |
| 프론트 | Next.js (App Router) + TypeScript | 16.x | 모노레포 2앱. View Transitions 내장(플래그 불필요) |
| UI | Tailwind CSS v4 + shadcn/ui | | 프리미티브는 **Base UI** (shadcn 2026-07 기본 전환). 아이콘 Lucide |
| 프론트 상태/데이터 | TanStack Query | v5 | 서버 상태 캐싱·재검증. 표는 목록 규모가 작아 기본 마크업으로 충분했고, TanStack Table은 실사용처가 없어 제거했습니다 |
| 차트 | lightweight-charts + Recharts | | 기초자산 시세/캔들은 lightweight-charts, 대시보드 지표는 Recharts |
| AI 서비스 | Python + FastAPI | 3.11 | |
| 임베딩 | `BAAI/bge-m3` 또는 유사 다국어 모델 | | 한국어 성능 |
| LLM | Claude API (기본) / Ollama (폐쇄망) | | §11.3 |
| 테스트 | JUnit5, Testcontainers, k6 | | |
| 인프라 | Docker Compose | | 단일 명령 기동 |

### 4.2 이벤트 처리

**Kafka를 사용하지 않는다.** 1인 프로젝트 규모에서 운영 비용 대비 이득이 없다.
→ Spring `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)` 사용.
→ 비동기 처리가 필요한 지점만 Redis Stream 사용 (웹훅 발송 큐).

---

## 5. 도메인 모델

### 5.1 ERD (핵심 테이블)

```
┌─────────────────────┐       ┌──────────────────────┐
│ underlying_asset    │       │ issuance             │
│─────────────────────│       │──────────────────────│
│ id (PK)             │◄──────│ asset_id (FK)        │
│ name                │  1:N  │ id (PK)              │
│ asset_type          │       │ token_symbol (UK)    │
│ broker_ticker       │       │ total_units          │
│ description         │       │ unit_price           │
│ issuer_id (FK)      │       │ status               │
└─────────────────────┘       │ subscription_start_at│
                              │ subscription_end_at  │
                              │ prospectus_file_key  │
                              └──────────┬───────────┘
                                         │ 1:N
                              ┌──────────▼───────────┐
                              │ subscription_order   │
                              │──────────────────────│
                              │ id (PK)              │
                              │ issuance_id (FK)     │
                              │ investor_id (FK)     │
                              │ requested_units      │
                              │ allotted_units       │
                              │ deposit_amount       │
                              │ status               │
                              │ idempotency_key (UK) │
                              └──────────────────────┘

┌─────────────────────┐       ┌──────────────────────┐
│ investor            │       │ ledger_balance       │
│─────────────────────│       │──────────────────────│
│ id (PK)             │◄──────│ owner_id (FK)        │
│ name                │  1:N  │ token_symbol         │
│ ci_hash             │       │ units                │
│ kyc_status          │       │ locked_units         │
│ risk_profile        │       │ version (낙관락)      │
│ cash_balance        │       │ UK(owner, symbol)    │
└─────────────────────┘       └──────────────────────┘

┌──────────────────────────────────────────────────┐
│ ledger_transaction  ★ append-only, 절대 UPDATE 금지│
│──────────────────────────────────────────────────│
│ seq (PK, BIGSERIAL)                              │
│ tx_type       (ISSUE|TRANSFER|BURN|LOCK|UNLOCK)  │
│ token_symbol                                     │
│ from_owner_id (nullable, ISSUE 시 null)          │
│ to_owner_id   (nullable, BURN 시 null)           │
│ units                                            │
│ ref_type      (SUBSCRIPTION|EXECUTION|ADMIN)     │
│ ref_id                                           │
│ created_at                                       │
│ prev_hash     (CHAR(64))                         │
│ curr_hash     (CHAR(64))  ★ UNIQUE               │
└──────────────────────────────────────────────────┘

┌─────────────────────┐       ┌──────────────────────┐
│ trade_order         │       │ trade_execution      │
│─────────────────────│       │──────────────────────│
│ id (PK)             │◄──────│ buy_order_id (FK)    │
│ token_symbol        │       │ sell_order_id (FK)   │
│ investor_id (FK)    │       │ id (PK)              │
│ side (BUY|SELL)     │       │ price                │
│ order_type(LIMIT|MKT)│      │ units                │
│ price               │       │ executed_at          │
│ units               │       │ premium_rate         │
│ filled_units        │       └──────────────────────┘
│ status              │
│ idempotency_key (UK)│
│ created_at          │
└─────────────────────┘

┌─────────────────────┐       ┌──────────────────────┐
│ api_client          │       │ api_call_log         │
│─────────────────────│       │──────────────────────│
│ id (PK)             │       │ id (PK)              │
│ client_id (UK)      │       │ client_id            │
│ client_secret_hash  │       │ endpoint             │
│ owner_investor_id   │       │ status_code          │
│ scopes (JSONB)      │       │ latency_ms           │
│ env (SANDBOX|LIVE)  │       │ idempotency_key      │
│ rate_limit_per_sec  │       │ called_at            │
│ webhook_url         │       └──────────────────────┘
│ webhook_secret      │
└─────────────────────┘

┌─────────────────────┐       ┌──────────────────────┐
│ prospectus_chunk    │       │ audit_log            │
│─────────────────────│       │──────────────────────│
│ id (PK)             │       │ id (PK)              │
│ issuance_id (FK)    │       │ actor_type           │
│ page_no             │       │ actor_id             │
│ content (TEXT)      │       │ action               │
│ embedding VECTOR    │       │ target_type          │
└─────────────────────┘       │ target_id            │
                              │ before_json          │
                              │ after_json           │
                              │ channel (WEB|API|BATCH)│
                              │ created_at           │
                              └──────────────────────┘
```

### 5.2 상태 머신

**Issuance**
```
DRAFT → PENDING_APPROVAL → APPROVED → SUBSCRIBING → ALLOTTING
      → LISTED → (SUSPENDED) → DELISTED
                ↘ REJECTED
```

**SubscriptionOrder**
```
PENDING → DEPOSITED → ALLOTTED → SETTLED
                    ↘ PARTIALLY_ALLOTTED → SETTLED (잔여 환불)
                    ↘ REJECTED (환불)
```

**TradeOrder**
```
OPEN → PARTIALLY_FILLED → FILLED
     ↘ CANCELLED
     ↘ REJECTED
```

### 5.3 값 객체 (Value Object)

에이전트는 아래를 **반드시 값 객체로 구현**한다. 원시 타입 사용 금지.

```java
// 금액 — 내부는 원 단위 long
public record Money(long amount) {
    public static Money of(long won) { ... }
    public Money plus(Money other) { ... }
    public Money minus(Money other) { ... }
    public Money multiply(long units) { ... }   // 오버플로우 체크
    public BigDecimal toDisplay() { ... }
}

// 수량 — 조각 개수, 정수
public record Units(long value) {
    public static final Units ZERO = new Units(0);
    public Units plus(Units other) { ... }
    public Units minus(Units other) {  // 음수 시 예외
        if (value < other.value) throw new InsufficientUnitsException();
        ...
    }
}
```

---

## 6. 모듈별 기능 사양

### 6.1 account — 계좌·투자자

| ID | 기능 | 설명 |
|---|---|---|
| AC-01 | 투자자 등록 | 이름, 이메일, 비밀번호. CI는 Mock 해시 생성 |
| AC-02 | KYC 처리 | Mock. 등록 후 3초 뒤 자동 `VERIFIED` 전환 (비동기 시뮬레이션) |
| AC-03 | 투자성향 진단 | 8문항 설문 → 점수 합산 → 5단계 등급 (안정형~공격투자형) |
| AC-04 | 적합성 판정 | 상품 위험등급 > 투자자 성향등급 이면 청약/매수 **차단**. 단, 확인 서명 시 허용 |
| AC-05 | 예치금 관리 | 가상 입금/출금. 잔액은 `Money`로 관리 |
| AC-06 | 잔고 조회 | `LedgerPort.balanceOf()` 위임 |

**AC-04 상세**: 금융소비자보호법상 적합성 원칙 시뮬레이션. 차단 시 응답은 `403 SUITABILITY_MISMATCH` + 사유 명시.

### 6.2 issuance — 발행

| ID | 기능 | 설명 |
|---|---|---|
| IS-01 | 기초자산 등록 | 자산명, 유형(REIT/ETF/REAL_ESTATE), 증권사 티커(선택), 분할비율 |
| IS-02 | 발행 계획 생성 | 총 조각 수(`total_units`), 조각당 단가(`unit_price`), 청약 기간 |
| IS-03 | 투자설명서 업로드 | PDF → MinIO 저장. 업로드 완료 시 AI 인덱싱 이벤트 발행 |
| IS-04 | 발행 승인 | ADMIN이 승인 → `APPROVED`. 승인 시 감사 로그 필수 |
| IS-05 | 토큰 심볼 발급 | `FR-{자산코드}-{연번}` 형식. 예: `FR-ESRK-001` |
| IS-06 | 청약 개시 | 스케줄러가 `subscription_start_at` 도달 시 `SUBSCRIBING` 전환 |
| IS-07 | 상장 | 배정 완료 후 `LISTED`. 이때부터 유통 가능 |

**IS-02 검증 규칙**
- `total_units` ≥ 100, ≤ 1,000,000
- `unit_price` ≥ 100원
- `total_units × unit_price` ≤ 100억원 (오버플로우 방지)
- 증권사 티커 지정 시 → 발행가와 실제 시세 환산가 괴리율 ±30% 초과하면 경고

### 6.3 subscription — 청약 ★핵심 난이도

| ID | 기능 | 설명 |
|---|---|---|
| SB-01 | 청약 신청 | 수량 지정 → 증거금(수량×단가) 예치금에서 차감 |
| SB-02 | **선착순 배정** | 잔여 수량 내에서 즉시 확정. **동시성 처리 필수** (§8.3) |
| SB-03 | **비례 배분** | 경쟁률 초과 시 안분비례. 단수주 처리 규칙 필수 |
| SB-04 | 청약 취소 | 청약 기간 내에만 가능. 증거금 즉시 환불 |
| SB-05 | 배정 확정 | 청약 종료 → 배정 계산 → 원장 기록 → 잔여 증거금 환불 |
| SB-06 | 멱등성 처리 | 동일 `Idempotency-Key` 재요청 시 최초 결과 반환 |

**SB-03 비례배분 알고리즘**

```
총 신청량 T, 총 발행량 N, 개별 신청량 rᵢ 일 때

1단계: 기본 배정 aᵢ = floor(rᵢ × N / T)
2단계: 잔여 R = N - Σaᵢ
3단계: 잔여 R개를 소수부 큰 순서로 1개씩 배분
       (동률이면 신청 시각 빠른 순 → 그래도 동률이면 청약ID 오름차순)

★ 불변식: Σaᵢ == N 이 반드시 성립해야 함
★ 결정론적이어야 함 — 같은 입력이면 항상 같은 결과
```

### 6.4 ledger — 원장 ★핵심

| ID | 기능 | 설명 |
|---|---|---|
| LG-01 | 발행 기록 | `ISSUE` 트랜잭션. from=null |
| LG-02 | 이전 | `TRANSFER`. from → to |
| LG-03 | 잠금/해제 | `LOCK`/`UNLOCK`. 매도 주문 시 수량 잠금 |
| LG-04 | 잔고 조회 | `units`(총) / `locked_units`(잠김) / 가용 = 총 − 잠김 |
| LG-05 | 해시체인 생성 | §8.2 |
| LG-06 | 체인 검증 | 전체 또는 구간 재계산 후 비교 |
| LG-07 | 불변식 검증 | §8.1 |

**LedgerPort 인터페이스 (필수 시그니처)**

```java
public interface LedgerPort {
    LedgerTxId issue(String tokenSymbol, OwnerId to, Units units, TxRef ref);
    LedgerTxId transfer(String tokenSymbol, OwnerId from, OwnerId to, Units units, TxRef ref);
    LedgerTxId lock(String tokenSymbol, OwnerId owner, Units units, TxRef ref);
    LedgerTxId unlock(String tokenSymbol, OwnerId owner, Units units, TxRef ref);

    Balance balanceOf(String tokenSymbol, OwnerId owner);
    Units totalIssued(String tokenSymbol);

    ChainVerifyResult verifyChain(long fromSeq, long toSeq);
    InvariantResult verifyInvariant(String tokenSymbol);
}
```

### 6.5 trading — 유통

| ID | 기능 | 설명 |
|---|---|---|
| TR-01 | 지정가 주문 | 가격·수량 지정. 매도 시 수량 잠금 선행 |
| TR-02 | 시장가 주문 | 반대편 최우선 호가부터 소진. 미체결 잔량은 취소 |
| TR-03 | 주문 취소 | 미체결 잔량만 취소. 잠금 해제 |
| TR-04 | 매칭 엔진 | 가격 우선 → 시간 우선. §8.4 |
| TR-05 | 호가창 조회 | 매수/매도 각 10호가 |
| TR-06 | 체결 내역 | 최근 N건 + 페이징 |
| TR-07 | **괴리율 계산** | 체결 시점 증권사 시세 대비 괴리율 산출·저장 |
| TR-08 | 괴리율 경보 | ±10% 초과 시 경고, ±20% 초과 시 해당 종목 자동 `SUSPENDED` |

**TR-08이 이 프로젝트의 차별 포인트**다. 반드시 구현한다.

### 6.6 settlement — 결제

| ID | 기능 | 설명 |
|---|---|---|
| ST-01 | DvP 결제 | 증권 이전 + 대금 이전을 **하나의 트랜잭션**으로 |
| ST-02 | 결제 실패 처리 | 롤백 후 주문 `REJECTED`, 잠금 해제 |
| ST-03 | 수수료 | 체결금액 × 0.015% (매수·매도 각각), 원 단위 절사 |
| ST-04 | 정산 리포트 | 일별 체결 건수·금액·수수료 집계 |

**ST-01 필수 조건**: 증권만 넘어가고 대금이 안 넘어가는 상태가 **단 한 순간도 존재하면 안 된다.** 동일 DB 트랜잭션 내에서 처리하고, 락 획득 순서를 고정하여 데드락을 방지한다(항상 `owner_id` 오름차순).

### 6.7 openapi — 오픈 API ★차별 포인트

| ID | 기능 | 설명 |
|---|---|---|
| OA-01 | API 클라이언트 등록 | `client_id`/`client_secret` 발급. secret은 해시 저장, 최초 1회만 평문 노출 |
| OA-02 | OAuth2 토큰 발급 | Client Credentials Grant. access_token 유효 1시간 |
| OA-03 | Scope 권한 | `market:read`, `account:read`, `order:write`, `subscription:write` |
| OA-04 | Rate Limit | Redis 원자적 슬라이딩 윈도우. 기본 초당 10건 / 일 10,000건 |
| OA-05 | 쿼터 응답 헤더 | `X-RateLimit-Limit`, `-Remaining`, `-Reset` |
| OA-06 | **멱등성** | `Idempotency-Key` 헤더. §8.5 |
| OA-07 | 웹훅 등록 | URL + 시크릿. 이벤트 구독 선택 |
| OA-08 | 웹훅 발송 | HMAC-SHA256 서명. 실패 시 지수 백오프 5회 재시도 |
| OA-09 | 샌드박스 | `env=SANDBOX` 클라이언트는 별도 스키마의 가상 데이터 사용 |
| OA-10 | 호출 로그 | 모든 호출 기록. 개발자 포털에서 조회 |

**OA-05 응답 예시**
```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1755676800
Retry-After: 1

{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"초당 호출 한도를 초과했습니다.","details":{}},
 "meta":{"requestId":"uuid","timestamp":"..."}}
```

### 6.8 audit — 감사

| ID | 기능 | 설명 |
|---|---|---|
| AU-01 | 자동 기록 | AOP로 `@Auditable` 메서드 가로채 대상 ID와 before/after JSON 저장 |
| AU-02 | 채널 구분 | WEB / API / BATCH / ADMIN |
| AU-03 | 조회 API | 대상·기간·행위자별 필터 |
| AU-04 | 불변성 | audit_log는 UPDATE/DELETE 권한 자체를 DB 레벨에서 제거 |

---

## 7. API 명세

### 7.1 공통 규약

**베이스 URL**
```
내부 (웹앱용):   /api/v1/...
오픈 API:        /open/v1/...
샌드박스:        /open/sandbox/v1/...
```

**공통 응답 포맷**
```json
// 성공
{ "data": { ... }, "meta": { "requestId": "uuid", "timestamp": "..." } }

// 실패
{ "error": { "code": "FUND_INSUFFICIENT_UNITS", "message": "...", "details": {...} },
  "meta": { "requestId": "uuid", "timestamp": "..." } }
```

**에러 코드 체계**
| 접두 | 범위 | 예시 |
|---|---|---|
| `AUTH_` | 인증/인가 | `AUTH_INVALID_TOKEN`, `AUTH_SCOPE_DENIED` |
| `VALID_` | 입력 검증 | `VALID_UNITS_RANGE` |
| `STATE_` | 상태 위반 | `STATE_NOT_SUBSCRIBING` |
| `FUND_` | 잔고/자금 | `FUND_INSUFFICIENT_CASH`, `FUND_INSUFFICIENT_UNITS` |
| `RATE_` | 쿼터 | `RATE_LIMIT_EXCEEDED` |
| `IDEM_` | 멱등성 | `IDEM_KEY_CONFLICT` |
| `SUIT_` | 적합성 | `SUIT_PROFILE_MISMATCH` |

### 7.2 오픈 API 엔드포인트

| Method | Path | Scope | 설명 |
|---|---|---|---|
| POST | `/open/v1/oauth/token` | — | 토큰 발급 |
| GET | `/open/v1/tokens` | `market:read` | 상장 종목 목록 |
| GET | `/open/v1/tokens/{symbol}` | `market:read` | 종목 상세 (발행정보·기초자산) |
| GET | `/open/v1/tokens/{symbol}/orderbook` | `market:read` | 10호가 |
| GET | `/open/v1/tokens/{symbol}/executions` | `market:read` | 체결 내역 |
| GET | `/open/v1/tokens/{symbol}/premium` | `market:read` | 괴리율 |
| GET | `/open/v1/accounts/balance` | `account:read` | 잔고 |
| GET | `/open/v1/accounts/orders` | `account:read` | 주문 내역 |
| POST | `/open/v1/orders` | `order:write` | 주문 (멱등성 필수) |
| DELETE | `/open/v1/orders/{id}` | `order:write` | 주문 취소 |
| POST | `/open/v1/subscriptions` | `subscription:write` | 청약 (멱등성 필수) |
| POST | `/open/v1/webhooks` | — | 웹훅 등록 |

### 7.3 주문 API 상세 (대표 예시)

```http
POST /open/v1/orders
Authorization: Bearer {access_token}
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "tokenSymbol": "FR-ESRK-001",
  "side": "BUY",
  "orderType": "LIMIT",
  "price": 4200,
  "units": 100
}
```

```json
// 201 Created
{
  "data": {
    "orderId": "ord_01HZ...",
    "status": "PARTIALLY_FILLED",
    "filledUnits": 30,
    "remainingUnits": 70,
    "executions": [
      { "price": 4200, "units": 30, "executedAt": "2026-08-20T10:15:32Z" }
    ]
  },
  "meta": { "requestId": "...", "timestamp": "..." }
}
```

### 7.4 웹훅 이벤트

| 이벤트 | 트리거 |
|---|---|
| `order.filled` | 주문 전량 체결 |
| `order.partially_filled` | 부분 체결 |
| `order.cancelled` | 취소 |
| `subscription.allotted` | 배정 확정 |
| `token.listed` | 신규 상장 |
| `token.suspended` | 거래 중단 (괴리율 경보 등) |

**서명 검증 규격**
```
X-Fracta-Signature: t=1755676800,v1=5257a869e7ecebeda32affa62cdca3fa...

v1 = HMAC_SHA256(secret, "{t}.{raw_body}")
수신 측은 t가 현재 시각 ±5분 이내인지 확인 후 서명 비교 (타이밍 세이프)
```

---

## 8. 핵심 알고리즘 ★

### 8.1 불변식 (Invariant)

시스템은 아래를 **항상** 만족해야 한다. 배치(§12)가 매일 검증한다.

```
[INV-1] 수량 보존
        모든 토큰 심볼 s에 대하여
        totalIssued(s) == Σ balance(owner, s).units

[INV-2] 잠금 정합
        balance.locked_units <= balance.units   (모든 잔고)

[INV-3] 음수 금지
        balance.units >= 0 AND balance.locked_units >= 0
        investor.cash_balance >= 0

[INV-4] 체인 무결성
        모든 seq n > 1 에 대하여
        tx[n].prev_hash == tx[n-1].curr_hash
        AND tx[n].curr_hash == SHA256(canonical(tx[n]))

[INV-5] 배정 총량
        Σ subscription.allotted_units
            == min(issuance.total_units, Σ 취소되지 않은 subscription.requested_units)
        (배정 완료된 발행 건에 한함)

[INV-6] 예치금 보존
        Σ investor.cash_balance + Σ 미결제 증거금 == 총 입금액 - 총 출금액
```

**위반 시 조치**: 즉시 해당 토큰 `SUSPENDED` 전환 + ADMIN 알림 + 상세 로그. 자동 복구를 시도하지 않는다.

### 8.2 해시체인 원장

```java
// 정규화(canonical) 문자열 — 필드 순서 고정, 구분자 '|'
String canonical = String.join("|",
    String.valueOf(seq),
    txType.name(),
    tokenSymbol,
    nullSafe(fromOwnerId),
    nullSafe(toOwnerId),
    String.valueOf(units),
    refType.name(),
    nullSafe(refId),
    createdAt.toInstant().toString(),   // ISO-8601 UTC, 밀리초까지
    prevHash
);

String currHash = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(UTF_8))
);
```

**제약**
- Genesis 트랜잭션(seq=1)의 `prev_hash`는 `"0".repeat(64)`
- `ledger_transaction` 테이블은 **INSERT만 허용**. DB 사용자 권한에서 UPDATE/DELETE 회수
- seq는 `BIGSERIAL`이지만, **동시 INSERT 시 체인이 꼬일 수 있으므로** 반드시 §8.2.1 직렬화 적용

**8.2.1 체인 직렬화**

해시체인은 순차 기록이 전제다. 동시에 두 트랜잭션이 같은 `prev_hash`를 읽으면 체인이 분기한다.

```java
// 방법: 전역 advisory lock (PostgreSQL)
@Transactional
public LedgerTxId append(LedgerTx tx) {
    jdbc.execute("SELECT pg_advisory_xact_lock(?)", LEDGER_LOCK_ID);
    // 이 시점부터 트랜잭션 커밋까지 단일 스레드 보장
    String prevHash = findLastHash();
    ...
}
```

성능 한계(초당 수백 건)가 있으나 프로젝트 규모에서 충분하다.
**개선안(문서에만 기재, 구현 선택)**: 토큰 심볼별로 체인을 분리하면 병렬성 확보 가능.

### 8.3 청약 동시성 ★자소서 핵심 소재

한정 수량 선착순 청약은 전형적인 경쟁 조건이다. **3가지 방식을 모두 구현하고 k6로 비교 측정한다.**

**방식 A — 비관적 락**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT i FROM Issuance i WHERE i.id = :id")
Issuance findByIdForUpdate(@Param("id") Long id);
```

**방식 B — Redis 분산락 (Redisson)**
```java
RLock lock = redisson.getLock("subscription:" + issuanceId);
if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) throw new LockTimeoutException();
try { ... } finally { lock.unlock(); }
```

**방식 C — DB 원자적 감소 (권장 기본값)**
```sql
UPDATE issuance
   SET remaining_units = remaining_units - :req
 WHERE id = :id
   AND remaining_units >= :req;
-- affected rows == 0 이면 수량 부족
```

**측정 시나리오 (k6)**
```
총 발행량 1,000
동시 사용자 500명이 각 5개씩 청약 (총 신청 2,500)
→ 검증: 배정 총합이 정확히 1,000인가?
→ 측정: p95 응답시간, TPS, 실패율
→ 3가지 방식 비교표를 README에 게재
```

### 8.4 매칭 엔진

```
[자료구조]
매수: PriorityQueue<Order>  — 가격 내림차순, 동가면 시각 오름차순
매도: PriorityQueue<Order>  — 가격 오름차순, 동가면 시각 오름차순

[알고리즘]
1. 신규 주문 도착
2. while (반대편 최우선 호가와 교차 가능 && 잔량 > 0):
     a. 체결가 = 먼저 들어온 주문의 가격 (Price-Time Priority)
     b. 체결량 = min(양측 잔량)
     c. 체결 기록 생성 → DvP 결제 호출
     d. 잔량 차감, 0이면 큐에서 제거
3. 잔량이 남고 LIMIT이면 큐에 삽입
   MARKET이면 잔량 취소

[동시성]
종목별 단일 스레드 처리. 종목 심볼 해시로 스레드 풀 파티셔닝.
→ 같은 종목의 주문은 항상 같은 스레드에서 순차 처리
→ 오더북 자체에는 락 불필요
```

**주의**: 오더북은 인메모리이므로 재시작 시 DB의 `OPEN`/`PARTIALLY_FILLED` 주문으로 복원하는 로직을 반드시 구현한다(`@PostConstruct`).

### 8.5 멱등성 처리

```
[요청 흐름]
1. Idempotency-Key 헤더 추출 (없으면 400)
2. Redis SETNX "idem:{clientId}:{key}" = "PROCESSING" (TTL 24h)
   - 실패(이미 존재) → 3번으로
   - 성공 → 4번으로
3. 기존 값 조회
   - "PROCESSING" → 409 IDEM_IN_PROGRESS (재시도 유도)
   - 저장된 응답 → 그대로 반환 (200, 헤더에 X-Idempotent-Replay: true)
4. 실제 처리 수행
5. 응답을 Redis에 저장 (TTL 24h) + DB api_call_log 기록

[요청 본문 해시 검증]
같은 키인데 본문이 다르면 → 422 IDEM_KEY_CONFLICT
(클라이언트 버그를 조기에 잡아줌)
```

---

## 9. 외부 연동 — namuh PLUG Open API (NH투자증권)

### 9.1 포트 정의

```java
public interface MarketDataPort {
    Quote getCurrentPrice(String ticker);
    List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to);
    void subscribeRealtime(String ticker, Consumer<Tick> handler);
    void unsubscribe(String ticker);
}
```

**구현 어댑터 2종**
- `NamuhPlugMarketDataAdapter` — `plug` 프로파일의 조회 전용 주력 어댑터
- `MockMarketDataAdapter` — 기본 랜덤워크 시뮬레이터(로컬·테스트·CI)

KIS는 v1.1에서 교체된 과거 후보이며 추가 구현 대상이 아니다. 포트 교체 가능성은 위 두
어댑터와 프로파일 전환으로 검증한다.

### 9.2 연동 요구사항

| 항목 | 처리 방법 |
|---|---|
| **접근토큰 24시간 만료** | 발급 시각 저장. 만료 30분 전 선제 갱신 스케줄러. 갱신 중 요청은 대기 |
| **초당 호출 제한** | 모의 도메인 실측 실효 한도 약 1건/초. 기본값 `1`; SDK 문서값 4~5와 다른 근거는 `docs/benchmarks/broker-quota.md` |
| **제한 방식 불확실** | 공식 문서에 수치 미공개. 토큰버킷으로 시작 → `IGW42901~42903` 발생 시 슬라이딩 윈도우로 전환. **두 구현 모두 유지하고 설정으로 스위치** |
| **토큰 발급 도메인 상이** | 토큰 발급은 **실전 도메인에서만** 가능. 데이터 조회는 모의 도메인. 두 클라이언트를 분리 구성 |
| **엔드포인트 매핑** | 비밀이 아닌 경로는 `application.yml`의 `broker.endpoints`에 설정. 시크릿은 환경변수로만 주입. 하드코딩 금지 |
| **도메인 분리** | 모의 `moapi.nhplug.com:8443` (계좌구분 `03`), 실전 `api.nhplug.com:8443` (계좌구분 `01`/`02`). 도메인-계좌구분 쌍이 어긋나면 전부 실패 |
| **모의투자 미지원 API** | 호출 전 지원 여부 체크. 미지원 시 Mock으로 폴백 + WARN 로그 |
| **WebSocket 재연결** | 지수 백오프(1s→2s→4s→...→60s). 재연결 시 구독 목록 자동 복원 |
| **장 시간 외** | 09:00~15:30 외에는 폴링 중단, 마지막 종가 캐시 사용 |

### 9.3 쿼터 관리 구현

```java
// 슬라이딩 윈도우 (Redis Sorted Set)
public boolean tryAcquire(String key, int limit, Duration window) {
    long now = System.currentTimeMillis();
    long windowStart = now - window.toMillis();

    redis.zRemRangeByScore(key, 0, windowStart);      // 만료 제거
    long count = redis.zCard(key);
    if (count >= limit) return false;

    redis.zAdd(key, now, UUID.randomUUID().toString());
    redis.expire(key, window);
    return true;
}
```

**중요**: 이 구현을 선택한 이유(토큰버킷은 윈도우 경계에서 버스트가 발생해 서버 측 슬라이딩 윈도우 제한에 걸릴 수 있음)를 코드 주석과 README에 명시한다. 면접 소재다.

### 9.4 시세 → 조각 환산

```
조각 참조가 = round(원자산 현재가 / 분할비율)

예) ESR켄달스퀘어리츠 4,200원, 분할비율 1주 = 100조각
    → 조각 참조가 = 42원

괴리율 = (플랫폼 체결가 - 조각 참조가) / 조각 참조가 × 100
```

---

## 10. AI 서비스

### 10.1 구성

별도 컨테이너(FastAPI). Core는 HTTP로 호출한다.

```
POST /ai/prospectus/index      # 투자설명서 인덱싱
POST /ai/prospectus/ask        # 질의응답
POST /ai/devportal/ask         # 개발자 어시스턴트
GET  /ai/health
GET  /ai/metrics
```

### 10.2 RAG 파이프라인

```
[인덱싱]
PDF → 페이지별 텍스트 추출 (pdfplumber)
    → 청킹 (500자, 100자 오버랩, 페이지 경계 유지)
    → 임베딩 (bge-m3)
    → prospectus_chunk 테이블 저장 (pgvector)

[질의]
질문 → 임베딩 → 코사인 유사도 top-5 검색
     → 유사도 임계값 0.48 미만이면 즉시 "문서에서 찾을 수 없습니다" 반환 (LLM 호출 안 함)
     → 컨텍스트 + 질문 → LLM
     → 가드레일 검사
     → 응답 + 인용 페이지 번호
```

임계값 `0.48`은 bge-m3 실측 조정값이다. 초기값 `0.6`은 정상 질문 10건 중 4건을
LLM 호출 전에 잘못 차단했다. 측정 데이터와 조정 근거는 `docs/ai/similarity-threshold.md`에 둔다.

### 10.3 가드레일 ★차별 포인트

**입력 단계**
- 프롬프트 인젝션 패턴 탐지 (지시 무시 요청 등)
- 개인정보 포함 질문 차단

**시스템 프롬프트 (필수 포함 문구)**
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

**출력 단계 (구조화 출력 + 코드 검증, LLM 신뢰 금지)**
```python
BLOCKED_PATTERNS = [
    r"(사|매수|투자)(하세요|하시길|추천)",
    r"(유망|기대|전망)(합니다|됩니다)",
    r"수익률.{0,10}(예상|전망|보장)",
    r"(오를|상승할).{0,5}(것|겁니다)",
]

def guard_output(answer: str, cited_pages: list[int], chunks: list) -> GuardResult:
    # 1. 금지 표현 검사
    for p in BLOCKED_PATTERNS:
        if re.search(p, answer):
            return GuardResult(blocked=True, reason="INVESTMENT_SOLICITATION")

    # 2. 인용 존재 검사
    if not cited_pages:
        return GuardResult(blocked=True, reason="NO_CITATION")

    # 3. 인용 페이지가 실제 검색 결과에 포함되는지 검사
    valid = {c.page_no for c in chunks}
    if not set(cited_pages).issubset(valid):
        return GuardResult(blocked=True, reason="HALLUCINATED_CITATION")

    return GuardResult(blocked=False)
```

구조화된 `cited_pages` 검증 뒤에도 답변 본문의 `[p.N]`을 다시 추출해 검색 결과 밖 페이지가
없는지 2차 검사한다. 모델이 구조화 필드와 본문을 다르게 쓰는 경우도 통과시키지 않는다.

**차단 시 응답**: 고정 문구 반환 + 사유를 감사 로그에 기록. LLM 재시도는 1회만.

**모든 AI 응답은 `ai_conversation_log`에 질문·컨텍스트·원본응답·가드레일결과·최종응답을 저장한다.**

### 10.4 LlmPort — 폐쇄망 대응

```python
class LlmPort(ABC):
    @abstractmethod
    def complete_json(self, system: str, user_prompt: str,
                      schema: dict, max_tokens: int) -> LlmResult: ...

class ClaudeAdapter(LlmPort):    # 기본값, 개발용
    model = settings.ai_model   # 기본 "claude-opus-5"

class OllamaAdapter(LlmPort):    # 폐쇄망 시연용
    # 로컬 GPU. 설정: AI_PROVIDER=ollama
    # VRAM 16GB 기준 양자화 모델 사용
```

**전환 방법**: 환경변수 `AI_PROVIDER=claude|ollama` 한 줄.
README에 두 모드의 응답 품질·지연시간 비교표를 게재한다.

---

## 11. 프론트엔드

### 11.1 구성 (모노레포)

```
apps/
├── investor-web/     # 투자자용
└── dev-portal/       # 개발자용
packages/
├── ui/               # 공용 컴포넌트
└── api-client/       # 타입 생성 클라이언트 (OpenAPI Generator)
```

### 11.2 investor-web 화면

| 화면 | 내용 |
|---|---|
| 홈 | 청약 중 / 상장 종목 리스트 |
| 종목 상세 | 발행 정보, 기초자산 시세 차트, **괴리율 배지**, 호가창, 체결 내역 |
| 투자설명서 | PDF 뷰어 + **AI 질의 사이드패널** (인용 클릭 시 해당 페이지로 이동) |
| 청약 | 수량 입력 → 증거금 확인 → 적합성 경고 → 신청 |
| 주문 | 지정가/시장가, 호가 클릭 시 자동 입력 |
| 내 자산 | 보유 조각, 평가금액, 손익, 거래 내역 |
| 온보딩 | 가입 → KYC → 투자성향 진단 |

### 11.3 dev-portal 화면

| 화면 | 내용 |
|---|---|
| 대시보드 | 호출량 그래프, 쿼터 사용률, 에러율 |
| 앱 관리 | 클라이언트 등록, 키 발급/재발급, Scope 설정 |
| API 문서 | Swagger UI 임베드 + **AI 어시스턴트 챗** |
| 샌드박스 | 브라우저에서 바로 호출해보는 콘솔 |
| 웹훅 | URL 등록, 시크릿 확인, 발송 이력 및 재발송 |
| 로그 | 호출 로그 검색 (엔드포인트·상태코드·기간) |

### 11.4 디자인 방향과 요소 기술

디자인 컨셉은 **Institutional Fintech + Modern SaaS** — 증권사 HTS만큼 무겁지 않고, 소비자 금융 앱만큼 가볍지도 않은 정보 밀도. 화려한 스타일이 아니라 **절제된 시각 + 강한 정보 구조 + 자연스러운 모션 + 컴포넌트 단위 반응형 + 접근성**을 채택 기준으로 삼는다.

채택하는 요소 기술은 다음 8개다. 전부 **"왜 이 기술을 썼는지 설명할 수 있는 것"** 만 남긴 것이고, 유행 자체가 목적인 항목은 §11.5에서 명시적으로 배제한다.

| # | 요소 기술 | 적용 지점 | 채택 사유 |
|---|---|---|---|
| 1 | **Bento 대시보드 레이아웃** | investor-web 홈·내 자산, dev-portal 대시보드 | 지표 종류가 많은 금융 화면에 맞는 정보 구조 |
| 2 | **마이크로 인터랙션** | 청약/주문 버튼 상태 전이, 테이블 행 진입, 진행률 | 완성도 체감이 가장 큰 요소. 모션 스케일은 아래 고정 |
| 3 | **View Transitions API** | 종목·상품 리스트 → 상세 전환 | 표준 Web Platform API 직접 사용. Next.js 16 App Router 내장 |
| 4 | **Container Queries** | `packages/ui` 카드·패널 | 부모 폭 기준 반응형 = 진짜 재사용 가능한 컴포넌트 |
| 5 | **Subtle Glass 서피스** | 스티키 헤더, 모달, 플로팅 요소 **한정** | 레이어 관계를 보여주는 수단으로만. 전면 투명 금지 |
| 6 | **OKLCH 디자인 토큰** | `packages/ui` 토큰 정의 | 인지 균등한 명도 단계. Tailwind v4·shadcn 기본 색공간 |
| 7 | **다크 모드** | 두 앱 전체 | 라이트 기본, 다크는 트레이딩·모니터링 화면용 |
| 8 | **커맨드 팔레트 (`Ctrl+K`)** | 두 앱 공통 | 종목·명령 검색. Keyboard-first UX |

선택 적용: CSS Anchor Positioning(툴팁·팝오버 — Baseline 2026 *newly available*이므로 `@supports` 폴백 필수), 스크롤 기반 애니메이션(투자설명서 진행 표시 정도).

**색 원칙 — 색은 데이터에만 쓴다**

인터페이스 크롬(버튼·헤더·보더·탭)은 **무채색**이다. 채도가 있는 색은 등락·손익·괴리율·상태처럼 **의미를 가진 값**에만 배정한다. 그래야 화면에 빨강이 보일 때 그게 진짜 신호가 된다. 파란 CTA 버튼과 파란 하락 숫자가 한 화면에 있으면 둘 다 의미를 잃는다.

```
--background/--surface/--surface-sunken   미세하게 따뜻한 중립 (hue 75~85, 낮은 채도)
--primary                                 무채색 잉크 (라이트=near-black, 다크=near-white)
--accent                                  바이올렛 hue 290 — 포커스·선택·링크·차트 1차 계열 전용
--success / --warning / --danger          Emerald / Amber / Red
--price-up / --price-down                 적색 hue 25 / 청색 hue 252
--chart-1..5                              액센트에서 출발해 색상환을 도는 5계열
```

- 중립색은 차가운 청회색이 아니라 **따뜻한 중립**이다(*elevated neutrals*). 순백·순회색보다 덜 삭막하고, 채도 있는 데이터 색이 더 또렷하게 얹힌다.
- 주 액션 버튼은 **무채색 잉크**다. 대비만으로 충분히 강하고, 데이터 색과 경쟁하지 않는다.
- 액센트 바이올렛(290)은 하락 청색(252)과 38° 떨어뜨려 나란히 놓여도 구분되게 했다.
- 등락·손익 색은 **국내 관행(상승 = 적색, 하락 = 청색)** 을 따른다. 서구식 반전 금지.
- 괴리율 배지의 경고(주황)·중단(빨강)은 등락 색과 **다른 축**이다. 색만으로 구분되게 두지 않고 아이콘 + 텍스트를 함께 쓴다(WCAG 1.4.1).
- 다크는 라이트의 반전이 아니다. near-black 서피스 + 낮은 대비 보더 + 채도를 올린 데이터 색으로 **다시 정의**한다. 그림자는 거의 쓰지 않고 서피스 단계로 깊이를 만든다.

**타이포그래피**

- 본문·숫자 모두 **Pretendard Variable**(OFL-1.1) 자체 호스팅. 한 파일로 45~920 굵기를 덮고 외부 CDN에 의존하지 않는다 — 폐쇄망 모드(§10.4)에서도 그대로 뜬다.
- 금액·수량은 `tabular-nums`로 고정한다. 값이 갱신될 때 폭이 흔들리면 표가 출렁인다.
- 데이터 밀도가 높은 화면이라 본문 자간을 -0.011em, 제목을 -0.022em으로 좁힌다.

**문체 — 화면 문구는 합니다체**

사용자에게 보이는 모든 문장은 **합니다체**이고, 요청은 "~해 주세요"다. 금융 서비스에서 반말·해라체는 성립하지 않는다. 코드 주석과 이 문서는 해라체를 쓰지만 그건 개발자용이고, 화면 문구에 섞이면 안 된다.

**모션 스케일** (초과 금지)

```
hover   100~150ms      button  150~200ms
panel   200~300ms      page    250~400ms
```

- `prefers-reduced-motion: reduce`에서 View Transition·카운트업·행 진입 애니메이션을 모두 끈다.
- 카운트업은 진행률·경쟁률 같은 파생 지표에만 쓴다. **금액은 서버 값을 그대로 즉시 표시**한다(§11.2 금액 규칙).

**접근성 기준**: WCAG 2.2 AA. 키보드만으로 온보딩~청약~주문 전 동선 도달, `:focus-visible` 상시 노출, 폼 오류는 색이 아니라 문구로 전달.

### 11.5 배제 항목

포트폴리오 가치보다 리스크가 큰 것들을 미리 못박는다.

| 배제 | 사유 |
|---|---|
| WebGL / 3D 메인 화면 | 개발 시간 대비 효과 없음. "증권 IT 백엔드/풀스택"이라는 초점이 흐려진다 |
| 전면 글래스모피즘 | 핀테크가 아니라 Web3 토큰 거래소 데모처럼 보인다 |
| 네오 브루탈리즘 | 금융 서비스 신뢰감과 상충 |
| 모든 요소에 애니메이션 | 오히려 완성도가 낮아 보인다. §11.4 모션 스케일 안에서만 |
| 운영자용 성능·동시성 벤치마크 화면 | 화면 범위(§11.2 7개 + §11.3 6개) 밖. 벤치마크 수치는 Phase 11 README에 싣는다 |

---

## 12. 배치 (Spring Batch)

| Job | 스케줄 | 내용 | 실패 시 |
|---|---|---|---|
| `DailyReconciliationJob` | 매일 23:00 | INV-1~6 전체 검증 | 위반 토큰 SUSPENDED + 알림 |
| `ChainVerificationJob` | 매일 23:30 | 전체 해시체인 재계산 검증 | 즉시 전체 거래 중단 + 알림 |
| `SettlementReportJob` | 매일 18:00 | 일별 체결·수수료 집계 | 재시도 3회 |
| `BrokerTokenRefreshJob` | 30분마다 | 증권사 토큰 만료 확인·갱신 | 즉시 재시도 |
| `SubscriptionAllotmentJob` | 청약 종료 시각 | 배정 계산 실행 | 롤백 후 수동 개입 |
| `ApiLogArchiveJob` | 매주 일 02:00 | 90일 경과 로그 아카이브 | 재시도 |

**`DailyReconciliationJob` 상세 (Chunk 지향)**
```
Reader:  토큰 심볼 목록 조회
Processor: 심볼별로
           - totalIssued 조회
           - 잔고 합산
           - 차이 계산
Writer:  reconciliation_result 저장
         차이 != 0 이면 SUSPENDED 처리 + 알림 이벤트

Chunk size: 100
Skip policy: 없음 (금융 데이터는 스킵하지 않는다)
Retry: 없음 (재실행은 수동)
```

---

## 13. 비기능 요구사항

### 13.1 성능 목표

| 항목 | 목표 |
|---|---|
| 조회 API p95 | < 200ms |
| 주문 API p95 | < 500ms |
| 매칭 처리량 | 종목당 초당 100건 이상 |
| 청약 동시 처리 | 500 VU에서 정합성 100% |
| 대사 배치 | 10만 잔고 기준 5분 이내 |

### 13.2 보안

- 비밀번호: BCrypt (cost 12)
- `client_secret`: SHA-256 해시 저장, 평문은 발급 시 1회만
- 웹훅 서명: HMAC-SHA256 + 타임스탬프 (리플레이 방지)
- SQL Injection: JPA 사용, 네이티브 쿼리는 바인딩 파라미터만
- 민감 정보 로깅 금지: 토큰, 시크릿, 주민번호는 마스킹 필터 적용
- CORS: 화이트리스트 방식

### 13.3 관측성

- 구조화 로깅 (JSON), 모든 로그에 `requestId` 포함
- Spring Actuator + Micrometer
- 핵심 커스텀 메트릭: `fracta.order.matched`, `fracta.ledger.invariant.violation`, `fracta.broker.quota.rejected`, `fracta.ai.guardrail.blocked`

---

## 14. 개발 로드맵

각 Phase는 **테스트 통과 시에만** 종료한다.

### Phase 1 — 기반 (1주)
- 프로젝트 스캐폴딩, Docker Compose (PG/Redis/MinIO)
- `Money`, `Units` 값 객체 + 단위 테스트
- 공통 응답/에러 포맷, 감사 로그 AOP
- **완료 조건**: `docker compose up` 한 번으로 전체 기동, 헬스체크 통과

### Phase 2 — 원장 (1주) ★최우선
- `LedgerPort` + `HashChainLedgerAdapter`
- 해시체인 생성·검증, advisory lock 직렬화
- 불변식 검증 로직
- **완료 조건**: 1만 건 트랜잭션 후 체인 검증 통과. 임의 레코드 변조 시 검출 테스트 통과

### Phase 3 — 계좌·발행 (1주)
- 투자자 등록, KYC Mock, 투자성향 진단
- 기초자산·발행 계획 CRUD, 상태 머신
- **완료 조건**: 발행 → 상장까지 상태 전이 테스트 통과

### Phase 4 — 청약 (1.5주) ★난이도 최고
- 청약 신청, 증거금 처리
- 동시성 3방식 구현
- 비례배분 알고리즘
- k6 부하 테스트 + 비교표 작성
- **완료 조건**: 500 VU 시나리오에서 배정 총합 정확, 3방식 측정 결과 문서화

### Phase 5 — 증권사 연동 (1주)
- OAuth 토큰 관리, 자동 갱신
- 현재가/일봉 조회, WebSocket 실시간
- 쿼터 관리 (토큰버킷 + 슬라이딩 윈도우)
- Mock 어댑터
- **완료 조건**: 24시간 무중단 폴링 성공, 쿼터 초과 0건

### Phase 6 — 유통·결제 (1.5주)
- 오더북, 매칭 엔진, 재시작 복원
- DvP 결제, 수수료
- 괴리율 계산 및 경보
- **완료 조건**: 매칭 후 불변식 유지, 괴리율 20% 초과 시 자동 중단 동작

### Phase 7 — 오픈 API (1.5주) ★차별 포인트
- OAuth2 Client Credentials, Scope
- Rate Limit + 응답 헤더
- 멱등성 처리
- 웹훅 발송·재시도·서명
- 샌드박스 환경
- **완료 조건**: 멱등성 테스트(동시 중복 요청 100건 → 실제 처리 1건), 웹훅 서명 검증 통과

### Phase 8 — AI (1주)
- RAG 인덱싱·검색
- 가드레일 전 단계
- `LlmPort` 2어댑터
- 개발자 어시스턴트
- **완료 조건**: 권유 표현 유도 프롬프트 20종에서 권유 표현 사용자 도달 0건, 환각 인용 검출 동작

### Phase 9 — 배치 (0.5주)
- 대사·체인검증·정산 Job
- **완료 조건**: 의도적 데이터 훼손 후 배치가 검출

### Phase 10 — 프론트 (2주)
- investor-web, dev-portal

### Phase 11 — 마감 (1주)
- README (아키텍처 다이어그램, 기술 선택 근거, 성능 측정 결과)
- 데모 시나리오 + 영상
- 폐쇄망 모드 시연 캡처

**총 예상: 약 13주** (바이브 코딩 기준 대폭 단축 가능)

---

## 15. 테스트 전략

### 15.1 필수 테스트 목록

| 대상 | 유형 | 검증 내용 |
|---|---|---|
| 해시체인 | 단위 | 정상 생성, 변조 검출, Genesis 처리 |
| 불변식 | 통합 | INV-1~6 각각 위반 상황 검출 |
| 비례배분 | 단위 | 총합 일치, 결정론성, 단수주 처리 |
| 청약 동시성 | 부하(k6) | 500 VU, 배정 총합 정확 |
| 매칭 엔진 | 단위 | 가격/시간 우선, 부분체결, 시장가 잔량 취소 |
| DvP | 통합 | 중간 실패 시 완전 롤백 |
| 멱등성 | 통합 | 동시 중복 100건 → 1건 처리 |
| 웹훅 서명 | 단위 | 서명 생성·검증, 타임스탬프 만료 |
| 쿼터 | 통합 | 경계 시점 버스트 차단 |
| AI 가드레일 | 단위 | 권유 유도 프롬프트 20종 차단 |
| 증권사 어댑터 | 통합 | Testcontainers + WireMock으로 응답 스텁 |

### 15.2 테스트 원칙

- **DB 테스트는 Testcontainers 사용** (H2 금지 — PostgreSQL 고유 기능 사용)
- 외부 API는 WireMock으로 스텁
- 동시성 테스트는 `CountDownLatch`로 정확한 동시 시작 보장
- 금액·수량 관련 테스트는 경계값 필수 (0, 1, MAX, 오버플로우)

---

## 16. README 필수 게재 항목

포트폴리오 가치의 절반은 README다. 아래를 반드시 포함한다.

1. **아키텍처 다이어그램** (§3.1)
2. **기술 선택 근거표** — 왜 Kafka를 안 썼는지, 왜 블록체인을 안 붙였는지
3. **청약 동시성 3방식 비교** — TPS/p95/실패율 그래프
4. **증권사 쿼터 대응 과정** — 토큰버킷 → 실패 → 슬라이딩 윈도우 전환 서사
5. **불변식 목록과 검증 방법**
6. **AI 가드레일 설계** — 금소법 제약과 대응
7. **폐쇄망 모드 시연** — Claude API vs 로컬 모델 비교
8. **트러블슈팅 로그** — 겪은 문제와 해결 과정
9. **데모 영상 링크**

---

## 부록 A — 환경 변수

```env
# Core
SPRING_PROFILES_ACTIVE=local
DB_URL=jdbc:postgresql://localhost:5432/fracta
DB_USER=fracta
DB_PASSWORD=
REDIS_HOST=localhost
MINIO_ENDPOINT=http://localhost:9000

# Broker (NH투자증권 namuh PLUG)
BROKER_ENV=mock                               # mock 고정
BROKER_BASE_URL=https://moapi.nhplug.com:8443 # 데이터 조회 (모의)
BROKER_AUTH_URL=https://api.nhplug.com:8443   # 토큰 발급은 실전 도메인에서만 가능
BROKER_APP_KEY=
BROKER_APP_SECRET=
BROKER_ACCOUNT_NO=
BROKER_ACCOUNT_PRODUCT_CODE=03                # 03=모의. 01/02(실전)는 부팅 시 거부
BROKER_RATE_LIMIT_PER_SEC=1                   # 모의 도메인 실측값
BROKER_RATE_LIMIT_STRATEGY=sliding            # bucket | sliding
BROKER_ALLOW_LIVE=false                       # 실전 연동 안전장치. true 로 바꾸지 않는다

# AI
AI_SERVICE_URL=http://localhost:8000
AI_PROVIDER=claude               # claude | ollama
AI_MODEL=claude-opus-5           # 하드코딩 금지
AI_EFFORT=medium
AI_MAX_TOKENS=16000
AI_TIMEOUT_SECONDS=120
SIMILARITY_THRESHOLD=0.48        # bge-m3 실측 조정값
ANTHROPIC_API_KEY=
OLLAMA_HOST=http://localhost:11434
OLLAMA_MODEL=qwen3:14b           # RTX 4080 실측 전 후보 기본값

# Ledger
LEDGER_ADAPTER=hashchain         # hashchain | (future: blockchain)
```

## 부록 B — 구현 시 자주 실수하는 지점

에이전트는 아래를 특별히 주의한다.

1. **`unit_price × units`가 `long` 오버플로우** → 곱셈 전 `Math.multiplyHigh` 또는 범위 검증
2. **비례배분 후 총합이 안 맞음** → 반드시 잔여 분배 단계까지 구현하고 assert
3. **매도 주문 시 수량 잠금을 빠뜨림** → 이중 매도 발생
4. **오더북 인메모리 상태를 재시작 시 복원 안 함** → 미체결 주문 유실
5. **해시체인 동시 INSERT** → advisory lock 필수
6. **증권사 도메인과 계좌구분을 잘못 짝지음** (모의 도메인 + 계좌구분 `01` 등) → 전부 실패
7. **AI 가드레일을 프롬프트에만 의존** → 반드시 코드 후처리 검증
8. **audit_log에 민감정보 그대로 저장** → 마스킹 필터
9. **DvP에서 락 획득 순서 미고정** → 데드락
10. **테스트에서 H2 사용** → advisory lock, pgvector 동작 안 함

---

## 부록 C — 변경 이력

| 버전 | 일자 | 변경 내용 |
|---|---|---|
| v1.0 | 2026-08-20 | 최초 작성 |
| v1.1 | 2026-08-31 | ① 증권사 연동을 KIS → **NH투자증권 namuh PLUG**로 전환 (근거: `docs/phases/phase-05-broker-integration.md` §0)<br>② 증권사 API를 **시세 조회 전용**으로 축소 — 자체 오더북이 있으므로 외부 주문 불필요<br>③ AI 기본 모델을 `claude-opus-5`로 갱신. 어시스턴트 프리필 금지·구조화 출력 반영 (`docs/phases/phase-08-ai.md` §0)<br>④ §14 로드맵을 `docs/phases/*.md` 11개 문서로 분할 |
| v1.1.1 | 2026-09-01 | Phase 1~8 구현 대조 정정: Open API 슬라이딩 윈도우, PLUG 실측 기본 1건/초, RAG 임계값 0.48, 구조화 AI 출력·현행 `LlmPort`, 환경변수 기본값과 감사 채널을 실제 사양에 동기화 |
| v1.1.2 | 2026-09-01 | Phase 9 배치 구현 반영. 미달 청약에서 미신청 물량을 만들지 않도록 INV-5 기대값을 `min(total_units, Σ requested_units)`로 명확화 |
| v1.1.3 | 2026-09-01 | Phase 10 착수 전 프론트엔드 사양 구체화. §4.1 프론트 스택 확정(Next.js 16 · Tailwind v4 · shadcn/Base UI · TanStack · lightweight-charts), §11.4 디자인 방향·요소 기술 8종·색/모션 토큰·접근성 기준, §11.5 배제 항목 신설 |

*문서 끝. 변경 시 버전을 올리고 변경 이력을 남길 것.*
