# Phase 4 — 청약 ★난이도 최고

> 기간 1.5주 · 선행 Phase 3 · FSD 참조 §6.3, §8.3, §8.5, §8.1(INV-5)

## 목표

한정 수량 선착순 경쟁 조건을 **3가지 방식으로 구현하고 실측 비교**한다. 이 Phase의 산출물(비교표)이 자소서·면접의 핵심 소재다.

## 범위

**포함**
- 청약 신청 / 취소 / 증거금 처리
- 동시성 3방식 (비관적 락 / Redis 분산락 / DB 원자적 감소)
- 비례배분 알고리즘
- 배정 확정 → 원장 기록 → 잔여 환불
- 멱등성 처리 (내부 API 수준)
- k6 부하 테스트 + 비교표
- INV-5(배정 총량) 검증 추가

**제외**
- 오픈 API 멱등성 미들웨어 (Phase 7) — 여기서는 청약 서비스 내부에서만
- 배정 배치 잡 스케줄링 (Phase 9) — 여기서는 수동 트리거 가능한 서비스 메서드로

## 핵심 사양

### 증거금 (SB-01)

```
증거금 = requested_units × unit_price      ← Money.multiply, 오버플로우 검증
투자자 cash_balance 에서 즉시 차감 → subscription_order.deposit_amount 에 기록
```
- 차감과 청약 레코드 생성은 **동일 트랜잭션**
- 잔액 부족 시 `FUND_INSUFFICIENT_CASH`
- 적합성 판정(`SuitabilityPort`)을 차감 **이전에** 수행

### 동시성 3방식 (§8.3) — 셋 다 구현한다

| 방식 | 구현 | 스위치 |
|---|---|---|
| A. 비관적 락 | `@Lock(PESSIMISTIC_WRITE)` on `findByIdForUpdate` | `subscription.concurrency=pessimistic` |
| B. Redis 분산락 | Redisson `tryLock(3, 10, SECONDS)` | `subscription.concurrency=redis` |
| C. DB 원자적 감소 | `UPDATE ... WHERE remaining_units >= :req` | `subscription.concurrency=atomic` **(기본값)** |

```sql
-- 방식 C
UPDATE issuance
   SET remaining_units = remaining_units - :req
 WHERE id = :id
   AND status = 'SUBSCRIBING'
   AND remaining_units >= :req;
-- affected rows == 0 이면 수량 부족 → FUND_INSUFFICIENT_UNITS
```

- **전략 패턴으로 분리**: `AllotmentStrategy` 인터페이스 + 3구현. 설정으로 스위치
- 세 방식 모두 **동일한 통합 테스트를 통과**해야 한다 (파라미터라이즈드 테스트 권장)

### 비례배분 (SB-03) ★

```
총 신청량 T, 총 발행량 N, 개별 신청량 rᵢ

1단계: aᵢ = floor(rᵢ × N / T)
2단계: R = N - Σaᵢ
3단계: R개를 소수부 큰 순서로 1개씩 배분
       동률 → 신청 시각 빠른 순 → 그래도 동률 → 청약ID 오름차순

★ 불변식: Σaᵢ == N   (assert 로 못 박는다)
★ 결정론: 같은 입력 → 항상 같은 결과
```

**구현 주의**
- `rᵢ × N` 이 `long` 오버플로우 가능 → `Math.multiplyExact` 또는 `BigInteger` 경유
- 소수부 비교를 `double`로 하면 **결정론이 깨진다.** 소수부는 `rᵢ × N - aᵢ × T` (정수)로 비교한다
  ```
  소수부 크기 비교 ≡ (rᵢ × N) mod T 비교      ← 전부 정수 연산
  ```
- 3단계 정렬은 **안정 정렬 + 명시적 tie-breaker 3단계**를 모두 구현
- 순수 함수로 분리하여 DB 없이 단위 테스트 가능하게 한다
  ```java
  public record AllotmentInput(long id, long requested, Instant appliedAt) {}
  public static Map<Long, Long> allocate(List<AllotmentInput> inputs, long totalUnits)
  ```

### 배정 확정 (SB-05)

```
1. 청약 종료 (status: SUBSCRIBING → ALLOTTING)
2. 배정 계산 (선착순이면 이미 확정, 경쟁률 초과 시 비례배분)
3. 각 청약 → LedgerPort.issue(symbol, investorOwnerId, allottedUnits, TxRef(SUBSCRIPTION, orderId))
4. 잔여 증거금 환불: (requested - allotted) × unit_price → cash_balance
5. issuance → LISTED
6. INV-1, INV-5 검증 → 위반 시 전체 롤백
```
- 전 과정 **단일 트랜잭션**. 부분 배정 상태로 커밋되면 안 된다
- 원장 기록이 N건이면 advisory lock을 N번 잡는다 → 느리다. **한계를 실측하고 문서화**

### 멱등성 (SB-06)

- 동일 `Idempotency-Key` 재요청 → 최초 결과 반환
- Phase 4에서는 `subscription_order.idempotency_key` UNIQUE 제약 + 충돌 시 기존 레코드 조회로 충분
- 본격적인 Redis 기반 미들웨어는 Phase 7

## 구현 순서

1. Flyway `V5__subscription.sql` — `subscription_order` (+ `idempotency_key` UNIQUE)
2. `ProportionalAllocator` **순수 함수 먼저 구현 + 단위 테스트** (DB 없이)
3. `AllotmentStrategy` 인터페이스 + 방식 C 구현 (기본값)
4. 청약 신청 유스케이스: 적합성 → 증거금 차감 → 전략 호출 → 레코드 생성
5. 청약 취소 (SB-04) — 기간 내에만, 증거금 즉시 환불, `remaining_units` 복구
6. 방식 A, B 구현
7. 배정 확정 유스케이스
8. INV-5 검증 추가
9. **k6 시나리오 작성 및 3방식 실측**
10. 비교표 작성 → `docs/benchmarks/subscription-concurrency.md`

## k6 측정 시나리오 (FSD §8.3)

```
총 발행량:   1,000
동시 사용자: 500 VU
각 5개씩 청약 (총 신청 2,500)

검증: 배정 총합이 정확히 1,000 인가?
측정: p95 응답시간 / TPS / 실패율
출력: 3방식 비교표 → README 게재
```

- 매 실행 전 DB 초기화 (동일 조건 보장)
- 각 방식 3회 실행 후 중앙값 사용
- **실패율에 "수량 부족으로 인한 정상 거절"을 섞지 않는다.** 별도 카운트

## 완료 조건 체크리스트

- [ ] **500 VU 시나리오에서 배정 총합이 정확히 발행량과 일치** (3방식 전부) — FSD §14 명시 조건
- [ ] **3방식 측정 결과가 문서화됨** (`docs/benchmarks/subscription-concurrency.md`) — FSD §14 명시 조건
- [ ] 비례배분 단위 테스트 — 총합 일치 (`Σaᵢ == N`)
- [ ] 비례배분 결정론 테스트 — 동일 입력 100회 실행 → 100회 동일 결과
- [ ] 비례배분 단수주 테스트 — `T=3, N=2, rᵢ=[1,1,1]` 같은 극단 케이스
- [ ] 비례배분 오버플로우 테스트 — `rᵢ`, `N`이 큰 값일 때 정상 동작
- [ ] 동률 tie-breaker 3단계 전부 테스트 (소수부 동률 → 시각 동률 → ID)
- [ ] 초과 청약 불가 테스트 — `remaining_units` 초과 신청 시 `FUND_INSUFFICIENT_UNITS`
- [ ] 증거금 부족 테스트 — `FUND_INSUFFICIENT_CASH`, 청약 레코드 미생성
- [ ] 청약 취소 → 증거금 전액 환불 + `remaining_units` 복구 확인
- [ ] 청약 기간 외 취소 시도 → `STATE_` 에러
- [ ] 멱등성 테스트 — 동일 키 동시 100건 → 실제 처리 1건, 나머지는 동일 응답
- [ ] 배정 확정 후 INV-1 통과 (`totalIssued == Σ balance`)
- [ ] 배정 확정 후 INV-5 통과 (`Σ allotted_units == total_units`)
- [ ] 배정 확정 후 INV-6 통과 (예치금 보존)
- [ ] 배정 중 의도적 예외 발생 → **완전 롤백** (원장·잔고·예치금 전부 원복)
- [ ] `SubscriptionInvariantTest` 통과
- [ ] 3방식 전부 동일한 통합 테스트 스위트 통과

## 흔한 실수

1. **비례배분 후 총합 불일치** → 잔여 분배(3단계)를 빠뜨림. `assert Σaᵢ == N` 필수
2. **소수부를 `double`로 비교** → 부동소수점 금지 위반이자 결정론 파괴. 정수 나머지 연산으로
3. `rᵢ × N` 오버플로우 → `Math.multiplyExact`
4. 방식 C에서 `status = 'SUBSCRIBING'` 조건 누락 → 청약 종료 후에도 감소가 성공
5. 청약 취소 시 `remaining_units` 복구 누락 → 총량이 영구히 줄어든다
6. Redis 분산락 `tryLock` 실패를 예외 없이 삼킴 → 조용한 중복 배정
7. 배정 확정을 여러 트랜잭션으로 쪼갬 → 중간 실패 시 부분 배정 상태 잔존
8. k6 결과에서 정상 거절(수량 부족)을 실패로 집계 → 비교표가 무의미해짐
9. 적합성 판정을 증거금 차감 **이후에** 수행 → 차단 시 환불 로직이 추가로 필요해짐

## 문서화 (README 필수 항목 3번)

`docs/benchmarks/subscription-concurrency.md` 에 아래를 포함한다:

| 방식 | TPS | p95(ms) | 정합성 | 실패율 | 비고 |
|---|---|---|---|---|---|
| A 비관적 락 | | | ✅/❌ | | |
| B Redis 분산락 | | | ✅/❌ | | |
| C DB 원자적 감소 | | | ✅/❌ | | |

- 왜 C를 기본값으로 골랐는지 근거
- 각 방식의 실패 모드 (락 타임아웃, 커넥션 고갈, Redis 장애 시 동작)
- 측정 환경 명시 (CPU/메모리/커넥션 풀 크기)

## 다음 Phase 진입 전 확인

- [ ] `LedgerPort.issue` 를 배정 건수만큼 호출할 때의 처리량이 실측되었는가
- [ ] `remaining_units` 와 원장 잔고가 항상 일치하는가 (이중 진실 공급원 위험 — 문서에 명시)
