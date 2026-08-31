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
| 6 | [유통·결제](phase-06-trading-settlement.md) | 1.5주 | ⬜ | 4, 5 |
| 7 | [오픈 API](phase-07-openapi.md) ★차별 포인트 | 1.5주 | ⬜ | 6 |
| 8 | [AI](phase-08-ai.md) | 1주 | ⬜ | 3 |
| 9 | [배치](phase-09-batch.md) | 0.5주 | ⬜ | 6 |
| 10 | [프론트](phase-10-frontend.md) | 2주 | ⬜ | 7 |
| 11 | [마감](phase-11-release.md) | 1주 | ⬜ | 10 |

**총 예상 13주.**

## 의존성 주의

- **Phase 5는 Phase 1 직후 언제든 병렬 착수 가능하다.** 증권사 API 신청 승인이 지연될 수 있으므로, 승인 대기 중에는 Phase 2~4를 `MockMarketDataAdapter`로 계속 진행한다. Phase 5가 전체를 블로킹해서는 안 된다.
- Phase 6은 Phase 5의 시세 조회(괴리율 계산)에 의존한다. 단 `MockMarketDataAdapter`로도 완료 조건 충족이 가능하도록 설계한다.
- Phase 8(AI)은 Phase 3의 투자설명서 업로드(IS-03)에만 의존한다. Phase 4~7과 병렬 가능.

## 증권사 어댑터 전략 (v1.1 변경)

기본 증권사를 **KIS → NH투자증권 namuh PLUG**로 변경했다. 근거와 상세는 [phase-05](phase-05-broker-integration.md) §0 참조.

```
MarketDataPort / BrokerOrderPort
├── MockMarketDataAdapter        ← 기본값. Phase 1~4 및 CI 전 구간
├── NamuhPlugMarketDataAdapter   ← 주력 (Phase 5)
└── KisMarketDataAdapter         ← 선택. 포트 교체 가능성 증명용 (Phase 11)
```

## 문서 변경 이력

| 버전 | 일자 | 내용 |
|---|---|---|
| v1.0 | 2026-08-20 | FSD 최초 작성 |
| v1.1 | 2026-08-31 | Phase 분할. 증권사 KIS → namuh PLUG 전환 |
