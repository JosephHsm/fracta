# FRACTA — Phase 인덱스

`docs/FSD.md` §14 로드맵을 에이전트 작업 지시서 단위로 분할한 문서다.

## 사용 규칙

1. 한 번에 **하나의 Phase 문서만** 작업 범위로 지정한다.
2. Phase 문서의 **완료 조건 체크리스트를 전부 만족**하기 전에는 완료 보고하지 않는다.
3. 다음 Phase의 기능을 미리 구현하지 않는다. 필요해 보이면 인터페이스만 뚫고 `TODO(phase-N)` 주석을 남긴다.
4. 각 문서의 "FSD 참조"에 적힌 절이 **정본**이다. Phase 문서와 FSD가 충돌하면 FSD를 따르고, 충돌 사실을 보고한다.

## 진행 현황

| Phase | 문서 | 기간 | 상태 | 선행 |
|---|---|---|---|---|
| 1 | [기반](phase-01-foundation.md) | 1주 | ✅ | — |
| 2 | [원장](phase-02-ledger.md) ★최우선 | 1주 | ✅ | 1 |
| 3 | [계좌·발행](phase-03-account-issuance.md) | 1주 | ✅ | 2 |
| 4 | [청약](phase-04-subscription.md) ★난이도 최고 | 1.5주 | ✅ | 3 |
| 5 | [증권사 연동](phase-05-broker-integration.md) | 1주 | ✅ | 1 (병렬 가능) |
| 6 | [유통·결제](phase-06-trading-settlement.md) | 1.5주 | ✅ | 4, 5 |
| 7 | [오픈 API](phase-07-openapi.md) ★차별 포인트 | 1.5주 | ✅ | 6 |
| 8 | [AI](phase-08-ai.md) | 1주 | 🟡 | 3 |
| 9 | [배치](phase-09-batch.md) | 0.5주 | ✅ | 6 |
| 10 | [프론트](phase-10-frontend.md) | 2주 | ✅ | 7 |
| 11 | [마감](phase-11-release.md) | 1주 | 🟡 | 10 |

**총 예상 13주.**

🟡 = 코드·테스트 완료, 특정 환경에서만 가능한 실측이 남음.
Phase 8은 폐쇄망(Ollama) 품질·지연 비교가 데스크탑(RTX 4080) 환경을 필요로 한다.
상세는 [phase-08 §5](phase-08-ai.md#5-완료-조건-체크리스트) 참조.
Phase 10은 브라우저 시각 QA까지 완료됐다. 남은 한 항목(폐쇄망 전환 시 AI 사이드패널)은
Phase 8 실측에 묶여 있다.
Phase 11은 README 필수 항목 9개 중 8개와 `docker compose up` 단일 명령 기동이 끝났고,
데모 영상 녹화와 폐쇄망 시연 캡처가 남았다.

## 의존성 주의

- **Phase 5는 Phase 1 직후 언제든 병렬 착수 가능하다.** 증권사 API 신청 승인이 지연될 수 있으므로, 승인 대기 중에는 Phase 2~4를 `MockMarketDataAdapter`로 계속 진행한다. Phase 5가 전체를 블로킹해서는 안 된다.
- Phase 6은 Phase 5의 시세 조회(괴리율 계산)에 의존한다. 단 `MockMarketDataAdapter`로도 완료 조건 충족이 가능하도록 설계한다.
- Phase 8(AI)은 Phase 3의 투자설명서 업로드(IS-03)에만 의존한다. Phase 4~7과 병렬 가능.

## 증권사 어댑터 전략 (v1.1 변경)

기본 증권사를 **KIS → NH투자증권 namuh PLUG**로 변경했다. 근거와 상세는 [phase-05](phase-05-broker-integration.md) §0 참조.

```
MarketDataPort
├── MockMarketDataAdapter        ← 기본값. Phase 1~4 및 CI 전 구간
└── NamuhPlugMarketDataAdapter   ← `plug` 프로파일의 주력 (Phase 5)
```

KIS는 v1.1에서 교체한 과거 후보이며 재도입하지 않는다. 증권사 API는 주문이 아닌 시세 조회
전용이고, 포트 교체는 Mock ↔ PLUG 프로파일 전환으로 검증한다.

## 문서 변경 이력

| 버전 | 일자 | 내용 |
|---|---|---|
| v1.0 | 2026-08-20 | FSD 최초 작성 |
| v1.1 | 2026-08-31 | Phase 분할. 증권사 KIS → namuh PLUG 전환 |
| v1.1.1 | 2026-09-01 | Phase 7 완료 반영. Phase 8 구현 및 SDK 사양 정정 (phase-08 §0) |
| v1.1.2 | 2026-09-01 | Phase 9 완료 반영. INV-5 미달 청약 기대값을 실제 배정 규칙과 동기화 |
| v1.1.3 | 2026-09-01 | Phase 10 착수 전 프론트 사양 구체화. 스택 확정(Next.js 16 · Tailwind v4 · shadcn/Base UI)과 요소 기술 8종·배제 항목을 FSD §11.4~§11.5에 신설 |
| v1.1.4 | 2026-09-02 | Phase 10 진행 — investor-web 7개 화면 완료. dev-portal 구현용 인수인계 문서 추가 |
| v1.1.5 | 2026-09-02 | Phase 10 두 웹앱 코드·자동 검증 완료. dev-portal 6개 화면과 관리 API 추가, 인수인계 문서 제거 |
| v1.1.7 | 2026-09-02 | Phase 10 완료 — 브라우저 시각 QA에서 3건 검출·수정(오류 배너 잔존, 모바일 금액 줄바꿈, 차트 축 라벨 깨짐) |
| v1.1.6 | 2026-09-02 | Phase 11 진행 — README 필수 항목 8/9(아키텍처·근거표·트러블슈팅 8건·한계 10건), `docker compose up` 단일 명령 기동, 포트 교체 실증 테스트 |
