# FRACTA 불변식 운영 가이드

> 기준: `docs/FSD.md` §8.1 · 구현: Phase 9 Spring Batch

FRACTA의 배치는 장부를 자동으로 고치는 도구가 아니다. 서비스 로직과 DB 제약을 통과한 뒤에도
남을 수 있는 운영 실수·DBA 변조·결함을 찾아 거래를 멈추고, 원인 조사에 필요한 증거를
남기는 마지막 방어선이다.

## 불변식 6종

| 코드 | 정의 | 필요한 이유 | 깨졌을 때의 위험 |
|---|---|---|---|
| INV-1 수량 보존 | 심볼별 `총 ISSUE 수량 == Σ ledger_balance.units` | 발행된 권리와 투자자 보유량은 정확히 같아야 한다 | 토큰이 장부에서 사라지거나 근거 없이 늘어남 |
| INV-2 잠금 정합 | 모든 잔고에서 `locked_units <= units` | 매도 주문으로 잠근 수량은 실제 보유 수량을 넘을 수 없다 | 같은 토큰의 이중 매도 또는 결제 실패 |
| INV-3 음수 금지 | 토큰 수량·잠금 수량·투자자 예치금이 모두 0 이상 | 음수 자산은 정상 상태 전이로 만들 수 없는 상태다 | 존재하지 않는 토큰·현금을 사용한 거래 |
| INV-4 체인 무결성 | seq 연속성, `prev_hash` 연결, canonical 데이터의 SHA-256 재계산이 모두 일치 | append-only 원장의 사후 변조 여부를 증명한다 | 과거 거래 변조·삭제·삽입을 신뢰할 수 없음 |
| INV-5 배정 총량 | 배정 완료 발행에서 `Σ allotted_units == min(total_units, Σ 취소되지 않은 requested_units)` | 초과 청약 때는 발행량을 넘지 않고, 미달 청약 때는 신청하지 않은 물량을 임의 배정하지 않아야 한다 | 과다 발행, 신청량 초과 배정, 일부 배정 유실 |
| INV-6 예치금 보존 | `Σ cash_balance + Σ 미결제 증거금 == 총 입금 - 총 출금` | 투자자 현금과 청약 중 묶인 현금을 외부 입출금 순액과 대조한다 | 현금의 중복 생성·유실 |

INV-5는 완판·초과 청약이면 `total_units`와 같고, 미달 청약이면 실제 신청 총량과 같다.
미달 청약까지 무조건 `total_units`를 배정하면 신청하지 않은 권리를 생성하므로 안 된다.

## 자동 검증

| 실행 | 시각 | 검증 방식 |
|---|---|---|
| `DailyReconciliationJob` | 매일 23:00 KST | 심볼을 JDBC 커서로 읽어 100개 단위 Chunk로 INV-1·2·3·5를 검사하고, 전역 INV-3·6과 전체 INV-4를 이어서 검사 |
| `ChainVerificationJob` | 매일 23:30 KST | `ledger_transaction`을 seq 순서와 fetch size 1,000의 커서로 스트리밍하며 전체 체인을 재해싱 |

`DailyReconciliationJob`은 `NeverSkipItemSkipPolicy`와 `NeverRetryPolicy`를 명시한다. 금융 데이터
한 건을 조용히 건너뛰거나 같은 실행에서 임의 재처리하지 않는다. 실행 일자는 식별
`JobParameter`이고 Redis 락이 같은 스케줄 슬롯의 동시 기동을 한 번 더 막는다.

각 검사 결과는 성공 여부와 관계없이 `reconciliation_result`에 저장한다. 실행 ID, 일자, 심볼,
불변식 코드, 기대값·실제값·차이, 최초 불일치 seq, 검사 건수와 상세 사유를 남기므로
“검사하지 않음”과 “검사 후 정상”을 구분할 수 있다.

## 위반 대응 절차

1. 심볼 범위 위반은 해당 종목을, INV-4·INV-6 같은 전역 위반은 거래 가능한 모든 종목을 즉시 `SUSPENDED`로 전환한다.
2. `TokenSuspendedEvent`를 통해 Phase 6의 규칙대로 미체결 주문을 취소하고 잠금 수량을 해제한다.
3. `BatchAlertEvent`를 발행하고 ADMIN 구조화 로그를 남긴다. 실제 이메일·메신저 연동은 Phase 9 범위가 아니다.
4. `fracta.ledger.invariant.violation{source="batch"}` 카운터를 증가시킨다.
5. 원본 데이터는 수정하지 않는다. 운영자가 `reconciliation_result`, 배치 실행 이력과 감사 로그를 조사한 뒤 수동으로 판단한다.

모든 Job은 성공·실패 모두 `audit_log`에 행위자 `system`, 채널 `BATCH`, 액션
`BATCH_JOB_EXECUTE`로 실행 결과를 남긴다.

## 의도적 훼손 검출 시연

2026-09-01에 Testcontainers PostgreSQL 16의 DBA 연결로 애플리케이션 보호 장치를 의도적으로
우회해 아래를 훼손했다. 테스트는 원본 값을 `finally`에서 복원하며 운영 코드에는 이 경로가 없다.

| 훼손 | 검출 결과 |
|---|---|
| `ledger_balance.units += 1` | Daily Job이 INV-1 위반을 기록하고 해당 심볼을 중단. 훼손값은 자동 복구하지 않음 |
| `locked_units > units` | INV-2 위반 개별 기록 |
| 토큰 잔고를 음수로 변경하되 합계 유지 | INV-1은 정상, INV-3만 위반으로 기록 |
| `ledger_transaction.units += 1` | Chain Job이 INV-4 위반과 최초 변조 seq를 정확히 기록하고 전체 거래 중단 |
| `subscription_order.allotted_units` 변경 | INV-5 위반 개별 기록 |
| `investor.cash_balance += 1` | INV-6 위반 전역 기록 |

자동화 근거는 `BatchInvariantIntegrationTest`이며 위 여섯 검출 시나리오, 중단, 무복구,
메트릭과 BATCH 감사 로그를 함께 단언한다.

## 10만 건 실측

`BatchPerformanceIntegrationTest`가 같은 PostgreSQL 환경에서 실제 행을 구성해 Job을 실행한다.

```text
BATCH-PERFORMANCE chain100kMillis=598 balance100kMillis=880
```

- `ChainVerificationJob`: 100,000건 이상을 전체 로드 없이 완료, 최초부터 끝까지 재해싱
- `DailyReconciliationJob`: 100,000개 잔고의 INV-1 대사와 100,000건 체인 단계를 포함해 880ms
- 목표 5분(300,000ms) 이내를 충족했다. 값은 2026-09-01 개발 PC의 단일 실행 결과이며 운영 성능 보장은 아니다.

재현 명령:

```bash
./gradlew test --tests "com.fracta.batch.BatchPerformanceIntegrationTest"
```
