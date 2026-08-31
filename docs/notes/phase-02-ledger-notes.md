# Phase 2 원장 — 실측·설계 기록 (README 소재)

> phase-02-ledger.md §문서화 요구사항에 따른 기록. 측정일 2026-08-31, 로컬 Docker PG 16.

## Advisory lock 처리량 실측

| 항목 | 값 |
|---|---|
| 단건 트랜잭션 append (issue, 트랜잭션당 1건) | **~225 tps** (300건 / 1.33s) |
| verifyChain(1, 10000) 전 구간 재계산 | **87ms** (스트리밍, fetchSize=1000) |

측정 코드: `LedgerChainBulkTest`. 전역 `pg_advisory_xact_lock` 직렬화 하에서의 수치로,
FSD §8.2.1의 "초당 수백 건" 예상 범위와 일치한다. 프로젝트 규모(단일 발행 건 청약·소규모 유통)에서 충분하다.

## 토큰 심볼별 체인 분리(FSD §8.2.1 개선안)를 구현하지 않은 이유

1. **정합성 > 성능** (CLAUDE.md 절대 원칙 1). 심볼별 분리는 심볼 간 전역 순서를 없애
   전체 원장 감사(단일 seq 스캔)를 복잡하게 만든다.
2. 실측 225 tps는 목표 부하(청약 피크 동시 500명, Phase 4 k6 시나리오)에서 병목이 아니다 —
   청약 자체는 원장 append가 아니라 배정 시점에 일괄 기록된다.
3. 분리 시 락 ID 관리·검증 로직·불변식 검증이 심볼 차원으로 늘어나 1인 프로젝트 유지비가 커진다.
   → 한계를 본 문서에 기록하고 단일 체인을 유지한다. 필요해지면 `AdvisoryLockIds`에
   심볼 해시 기반 락을 추가하는 방향으로 확장 가능하다.

## 블록체인 대신 해시체인을 쓴 이유 (README 필수 항목 2번 소재)

- 요구사항의 본질은 **변조 검출 가능한 append-only 기록**이지 탈중앙 합의가 아니다.
  운영 주체가 단일(플랫폼)이므로 합의 알고리즘·노드 운영 비용은 순수 오버헤드다.
- SHA-256 해시체인 + DB 권한 회수(UPDATE/DELETE REVOKE) + 전역 직렬화로
  "사후 변조 시 검출"(INV-4)을 동일하게 달성하면서 일반 RDB 트랜잭션·백업·조회를 그대로 쓴다.
- `LedgerPort` 추상화로 향후 블록체인 앵커링(`LEDGER_ADAPTER=blockchain`)으로 교체 가능한 구조를 유지한다.

## 구현 확정 사항 (Phase 3~6이 의존)

- **매도 잠금분 이전 규칙**: DvP는 같은 트랜잭션에서 `unlock` 후 `transfer` 수행 (Phase 6).
  잠금분 직접 이전 연산은 두지 않는다. (`LedgerPort` javadoc에도 명시)
- **seq 채번**: BIGSERIAL 기본값 대신 advisory lock 하에서 `마지막 seq + 1` 직접 채번 —
  롤백에 의한 시퀀스 gap을 원천 차단해 체인 seq가 항상 연속임을 보장한다.
- **createdAt 정규화 포맷**: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC, 밀리초 3자리 고정, `LedgerHasher.CREATED_AT_FORMAT`).
  `Instant.toString()`은 밀리초가 0이면 자릿수가 줄어 재검증이 깨지므로 쓰지 않는다.
  저장 시 `Instant`를 밀리초로 절단해 DB(timestamptz, µs 정밀도) 왕복 후에도 동일 문자열이 나온다.
