# FRACTA

> 토큰증권 기반 조각투자 발행·유통 플랫폼 + 오픈 API

부동산·리츠 등 실물 기반 자산을 조각 단위 **토큰증권**으로 발행하고, 투자자가 청약·매매하며, 그 전 기능을 외부 개발자에게 **Open API**로 개방하는 플랫폼.

> ⚠️ **현재 상태: 설계 완료 / 구현 착수 전 (Phase 0)**
> 이 README는 착수 시점의 골격입니다. FSD §16이 요구하는 9개 항목(성능 실측표, 트러블슈팅 로그, 데모 영상 등)은 각 Phase 진행에 따라 채워집니다.

---

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/FSD.md`](docs/FSD.md) | 기능 사양서 (v1.1) — **단일 진실 공급원(SSOT)** |
| [`docs/phases/README.md`](docs/phases/README.md) | Phase 11개 인덱스 및 의존 관계 |
| [`CLAUDE.md`](CLAUDE.md) | AI 개발 에이전트용 프로젝트 규칙 |

---

## 기술 스택

**Backend** Java 21 · Spring Boot 3.3 · JPA + QueryDSL · Spring Batch 5
**Infra** PostgreSQL 16 (pgvector) · Redis 7 · MinIO · Docker Compose
**Frontend** Next.js 14 (App Router) · TypeScript · Tailwind + shadcn/ui
**AI** Python FastAPI · bge-m3 임베딩 · Claude API ↔ Ollama 어댑터 스위치
**외부 연동** NH투자증권 namuh PLUG Open API (모의 도메인, 시세 조회 전용)
**테스트** JUnit5 · Testcontainers · WireMock · k6

---

## 설계 원칙

1. **정합성 > 성능 > 기능 수.** 불변식을 깨는 최적화는 금지
2. **부동소수점 금지.** 금액·수량은 내부 `long`, 표현은 `BigDecimal`. `Money`/`Units` 값 객체 경유
3. **포트-어댑터.** 원장·LLM·증권사 API는 인터페이스 + 구현체 분리
4. **모듈러 모놀리스.** MSA로 쪼개지 않음. 모듈 간 호출은 `api` 패키지 공개 인터페이스로만
5. **모든 상태 변경은 감사 로그를 남긴다**

---

## 차별 포인트

| # | 항목 | Phase |
|---|---|---|
| 1 | **해시체인 원장 + 불변식 6종 자동 검증** — 블록체인 없이 위변조 검출 | [2](docs/phases/phase-02-ledger.md), [9](docs/phases/phase-09-batch.md) |
| 2 | **청약 동시성 3방식 구현·실측 비교** — 비관적 락 / Redis 분산락 / DB 원자적 감소 | [4](docs/phases/phase-04-subscription.md) |
| 3 | **괴리율 기반 자동 거래 중단** — 원자산 시세 대비 ±20% 초과 시 자동 `SUSPENDED` | [6](docs/phases/phase-06-trading-settlement.md) |
| 4 | **오픈 API 게이트웨이** — OAuth2 · Scope · Rate Limit · 멱등성 · 웹훅 서명 · 샌드박스 | [7](docs/phases/phase-07-openapi.md) |
| 5 | **금소법 대응 AI 가드레일** — 프롬프트가 아닌 **코드로** 투자권유·환각 인용 차단 | [8](docs/phases/phase-08-ai.md) |

---

## 로컬 실행 (Phase 1 완료 후)

```bash
docker compose up -d          # PostgreSQL / Redis / MinIO
./gradlew build
./gradlew test
```

**사전 요구사항**: JDK 21 · Docker Desktop · Node.js 20+ · Python 3.11

---

## 진행 현황

| Phase | 내용 | 상태 |
|---|---|---|
| 1 | [기반](docs/phases/phase-01-foundation.md) | ✅ |
| 2 | [원장](docs/phases/phase-02-ledger.md) | ✅ |
| 3 | [계좌·발행](docs/phases/phase-03-account-issuance.md) | ✅ |
| 4 | [청약](docs/phases/phase-04-subscription.md) | ✅ |
| 5 | [증권사 연동](docs/phases/phase-05-broker-integration.md) | ⬜ |
| 6 | [유통·결제](docs/phases/phase-06-trading-settlement.md) | ⬜ |
| 7 | [오픈 API](docs/phases/phase-07-openapi.md) | ⬜ |
| 8 | [AI](docs/phases/phase-08-ai.md) | ⬜ |
| 9 | [배치](docs/phases/phase-09-batch.md) | ⬜ |
| 10 | [프론트](docs/phases/phase-10-frontend.md) | ⬜ |
| 11 | [마감](docs/phases/phase-11-release.md) | ⬜ |

---

## 범위 밖 (명시적 비목표)

- 실제 블록체인 노드 운영 → 해시체인 원장으로 대체
- 실제 자금 이체 → 예치금은 시뮬레이션 원장
- 실제 KYC 심사 → Mock 처리
- **증권사 실전 계좌 연동 및 주문 전송** → 모의 도메인 시세 조회 전용
- 다국어, 모바일 네이티브 앱

---

## 라이선스

포트폴리오 목적의 개인 프로젝트입니다.
