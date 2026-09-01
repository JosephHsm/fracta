# Phase 9 — 배치

> 기간 0.5주 · 선행 Phase 6 · FSD 참조 §12, §8.1

## 목표

불변식을 **매일 자동으로 검증**하고, 위반 시 즉시 거래를 중단시킨다. 금융 시스템의 마지막 방어선이다.

## 범위

**포함**
- Spring Batch Job 6종 (FSD §12)
- 스케줄링
- 위반 시 자동 `SUSPENDED` + 알림
- 의도적 데이터 훼손 검출 테스트

**제외**
- 알림 채널 실제 연동 (이메일/슬랙) — 이벤트 발행 + 로그까지. 실제 발송은 선택
- 배치 모니터링 UI (Phase 10)

## Job 목록 (FSD §12)

| Job | 스케줄 | 내용 | 실패 시 |
|---|---|---|---|
| `DailyReconciliationJob` | 매일 23:00 | INV-1~6 전체 검증 | 위반 토큰 `SUSPENDED` + 알림 |
| `ChainVerificationJob` | 매일 23:30 | 전체 해시체인 재계산 검증 | **즉시 전체 거래 중단** + 알림 |
| `SettlementReportJob` | 매일 18:00 | 일별 체결·수수료 집계 | 재시도 3회 |
| `BrokerTokenRefreshJob` | 30분마다 | 증권사 토큰 만료 확인·갱신 | 즉시 재시도 |
| `SubscriptionAllotmentJob` | 청약 종료 시각 | 배정 계산 실행 | 롤백 후 수동 개입 |
| `ApiLogArchiveJob` | 매주 일 02:00 | 90일 경과 로그 아카이브 | 재시도 |

> `TokenRefreshJob`은 증권사 전환(Phase 5)에 맞춰 `BrokerTokenRefreshJob`으로 명명한다. 대상은 namuh PLUG 액세스 토큰.

## 핵심 사양

### DailyReconciliationJob (Chunk 지향)

```
Reader:    토큰 심볼 목록 조회
Processor: 심볼별로 totalIssued 조회 → 잔고 합산 → 차이 계산
Writer:    reconciliation_result 저장
           차이 != 0 이면 SUSPENDED 처리 + 알림 이벤트

Chunk size:  100
Skip policy: 없음        ← 금융 데이터는 스킵하지 않는다
Retry:       없음        ← 재실행은 수동
```

- **Skip/Retry를 넣지 않는다.** Spring Batch 기본값을 그대로 두지 말고 **명시적으로 비활성화**한다
- INV-1 ~ INV-6 전부 검증. 각 불변식의 결과를 개별 기록 (어느 것이 깨졌는지 알아야 한다)
- 성능 목표: **10만 잔고 기준 5분 이내** (FSD §13.1)

### ChainVerificationJob

- 전체 `ledger_transaction`을 seq 순으로 재해싱하여 비교
- **스트리밍 처리.** 전체 로드 금지 (Phase 2와 동일 원칙)
- 위반 발견 시 → **전체 거래 중단** (모든 `LISTED` 토큰을 `SUSPENDED`) + 최초 불일치 seq 기록
- 이건 가장 심각한 상황이다. 자동 복구를 **시도하지 않는다** (FSD §8.1)

### 위반 시 조치 (FSD §8.1)

```
즉시 해당 토큰 SUSPENDED 전환
+ ADMIN 알림 (이벤트 발행)
+ 상세 로그 (어떤 불변식이, 어떤 심볼에서, 얼마나 차이나는지)
자동 복구를 시도하지 않는다
```

- `SUSPENDED` 전환 시 Phase 6에서 정한 미체결 주문 처리 규칙을 그대로 적용
- 메트릭 `fracta.ledger.invariant.violation` 증가 (FSD §13.3)

### 멱등성·중복 실행 방지

- 배치는 재실행될 수 있다. `JobParameters`에 실행 일자를 포함하여 동일 일자 중복 실행을 막는다
- 단일 인스턴스 전제이나, 향후를 위해 **Redis 락으로 중복 기동 방어**를 넣어둔다

## 구현 순서

1. Flyway `V9__batch.sql` — `reconciliation_result`, Spring Batch 메타테이블
2. `DailyReconciliationJob` (Chunk 지향) + Skip/Retry 명시적 비활성화
3. `ChainVerificationJob` (스트리밍)
4. 위반 처리 공통 컴포넌트 (`SUSPENDED` 전환 + 알림 이벤트 + 메트릭)
5. `SettlementReportJob` (Phase 6의 집계 로직 재사용)
6. `BrokerTokenRefreshJob` (Phase 5의 `PlugTokenManager` 재사용)
7. `SubscriptionAllotmentJob` (Phase 4의 배정 서비스 재사용)
8. `ApiLogArchiveJob`
9. 스케줄러 설정 + 중복 실행 방지
10. **의도적 데이터 훼손 검출 테스트**

## 완료 조건 체크리스트

- [x] **의도적 데이터 훼손 후 배치가 검출** (FSD §14 명시 조건)
  - [x] `ledger_balance.units` 직접 UPDATE → `DailyReconciliationJob`이 INV-1 위반 검출
  - [x] `ledger_transaction.units` 직접 UPDATE → `ChainVerificationJob`이 검출 + 최초 불일치 seq 정확
  - [x] `investor.cash_balance` 직접 UPDATE → INV-6 위반 검출
- [x] 6개 Job 전부 구현 및 스케줄 등록 확인
- [x] INV-1 ~ INV-6 **각각의 위반 상황을 개별 검출** (FSD §15.1 — 통합 테스트)
- [x] 위반 검출 시 해당 토큰이 `SUSPENDED`로 전환됨
- [x] 체인 위반 검출 시 **전체** 토큰이 `SUSPENDED`로 전환됨
- [x] 위반 시 자동 복구를 **시도하지 않음** (코드 확인)
- [x] `reconciliation_result`에 어떤 불변식이 깨졌는지 식별 가능하게 기록됨
- [x] Skip policy가 명시적으로 비활성화됨 (설정 확인)
- [x] Retry가 명시적으로 비활성화됨 (`DailyReconciliationJob`)
- [x] **10만 잔고 기준 대사 배치 5분 이내** (FSD §13.1) — 880ms (2026-09-01 전체 빌드 실측)
- [x] `ChainVerificationJob`이 10만 건에서 OOM 없이 완료 (스트리밍 확인) — 598ms
- [x] 동일 일자 중복 실행 시도 → 두 번째 실행이 거부됨
- [x] `SettlementReportJob` 실패 시 3회 재시도 동작
- [x] `BrokerTokenRefreshJob`이 만료 30분 전 갱신 (Phase 5 로직 재사용 확인)
- [x] `SubscriptionAllotmentJob` 실패 시 완전 롤백 (부분 배정 없음) + 자동 재시작 금지
- [x] `ApiLogArchiveJob` — 90일 경과 로그만 아카이브, 미경과 로그 보존
- [x] 메트릭 `fracta.ledger.invariant.violation` 동작
- [x] 모든 배치 실행이 `audit_log`에 `channel=BATCH`로 기록됨

## 흔한 실수

1. **Spring Batch 기본 Skip/Retry를 그대로 둠** → 금융 데이터가 조용히 스킵된다. 명시적 비활성화
2. `ChainVerificationJob`에서 전체 로드 → OOM. 스트리밍/커서 사용
3. 위반 검출 후 자동 복구 시도 → **금지.** 원인 파악 전 복구는 상태를 더 망친다
4. 배치가 `@Transactional`을 domain 레이어에 걸침 → 트랜잭션 경계는 application 레이어
5. 훼손 검출 테스트를 애플리케이션 API로 수행 → 애플리케이션은 훼손을 못 만든다. **DBA 권한으로 직접 UPDATE**해야 진짜 테스트다
6. 배치 실패 시 알림 없이 조용히 종료 → 실패 자체를 모른다. `JobExecutionListener`로 실패 알림
7. `JobParameters`가 매번 동일 → Spring Batch가 재실행을 거부한다. 실행 일자 포함
8. 대사 결과를 로그로만 남김 → 이력 추적 불가. `reconciliation_result` 테이블 필수

## 문서화 (README 필수 항목 5번)

`docs/invariants.md`:
- INV-1 ~ INV-6 정의
- 각 불변식이 **왜** 필요한가 (깨지면 무슨 일이 생기는가)
- 검증 방법 (어느 Job이 언제 검증하는가)
- 위반 시 조치 절차
- 의도적 훼손 → 검출 시연 결과 (스크린샷 또는 로그)

## 다음 Phase 진입 전 확인

- [x] 배치 실행 이력을 Phase 10의 운영 화면에서 조회 가능한 형태로 저장하는가
      → Spring Batch 메타테이블 + `reconciliation_result` + `settlement_report`에 실행 ID를 보존한다.
