# 청약 동시성 3방식 실측 비교

> Phase 4 산출물 (FSD §8.3, phase-04 §문서화). **모든 수치는 실제 측정값이다** — 추정치 없음.
> 측정일 2026-08-31. 재현 절차와 스크립트는 `scripts/bench/`.

## 1. 측정 환경

| 항목 | 값 |
|---|---|
| CPU | AMD Ryzen 5 5625U (6코어 / 12스레드) |
| RAM | 13.8 GB |
| OS | Windows 11 Home 10.0.26200 |
| JDK | Temurin 21.0.12.1 |
| DB | PostgreSQL 16.15 (Docker, `pgvector/pgvector:pg16`) |
| Redis | 7-alpine (Docker) |
| 앱 | 호스트 JVM에서 `bootJar` 실행 (컨테이너 아님) |
| DB 커넥션 풀 | HikariCP `maximum-pool-size=50`, `minimum-idle=10` |
| 웹 | Tomcat `threads.max=200`, `accept-count=1000`, `max-connections=2000` |
| 부하 도구 | k6 v2.2.0 (동일 호스트) |

앱·DB·부하 도구가 한 대에서 돌기 때문에 **절대 수치보다 세 방식의 상대 비교에 의미**가 있다.
세 방식 모두 위 설정을 동일하게 적용했다.

## 2. 시나리오

```
총 발행량      1,000 조각 (단가 100원, FCFS)
동시 사용자    500 VU × 1회 요청, 각 5조각  →  총 신청 2,500조각 (경쟁률 2.5:1)
도착 지터      0~2초 균등 분포
반복           방식당 3회, 중앙값 채택. 매 회 전 DB 초기화(reset.sql)
```

**도착 지터를 넣은 이유**: 500개 소켓을 같은 순간에 열면 Windows 수신 백로그가 넘쳐
`connection refused`가 대량 발생한다(무지터 실측: 500건 중 170건 전송 실패). 그 상태로는
락 전략이 아니라 OS TCP 백로그를 측정하게 되므로, 세 방식에 동일한 지터를 적용했다.

**실패율 집계 원칙**: 수량 부족으로 인한 정상 거절(`FUND_INSUFFICIENT_UNITS`)은
실패에 포함하지 않고 별도 카운터로 셌다.

## 3. 결과 (3회 중앙값)

| 방식 | TPS | p95(ms) | 정합성 | 실패율 | 비고 |
|---|---|---|---|---|---|
| A 비관적 락 (`pessimistic`) | 144.8 | 1,969 | ✅ 1,000/1,000 | 0% | 행 잠금 대기 동안 DB 커넥션 점유 |
| B Redis 분산락 (`redis`) | 105.8 | 2,942 | ✅ 1,000/1,000 | 0% | 외부 의존 추가, 3방식 중 최저 |
| C DB 원자적 감소 (`atomic`) | **153.9** | **1,767** | ✅ 1,000/1,000 | 0% | **기본값** |

**정합성**: 9회 전 실행에서 배정 총합이 정확히 1,000, 잔여 0, 초과 배정 0건.
성공 200건 × 5조각 = 1,000조각, 정상 거절 300건으로 매 회 동일했다.

### 회차별 원시값

| 방식 | 1회 TPS / p95 | 2회 TPS / p95 | 3회 TPS / p95 |
|---|---|---|---|
| A 비관적 락 | 120.2 / 2,752ms | 151.8 / 1,831ms | 144.8 / 1,969ms |
| B Redis 분산락 | 79.9 / 4,241ms | 105.8 / 2,942ms | 124.6 / 2,305ms |
| C 원자적 감소 | 131.5 / 2,888ms | 188.8 / 1,767ms | 153.9 / 1,749ms |

1회차가 일관되게 느린 것은 JIT 워밍업·커넥션 풀 채우기 때문이다. 중앙값을 쓴 이유가 이것이다.

## 4. 왜 C(DB 원자적 감소)를 기본값으로 골랐나

1. **정합성은 셋 다 같다.** 초과 배정 0건이 세 방식 모두에서 재현됐다. 그러면 남는 기준은
   처리량·지연·운영 복잡도인데 C가 TPS 최고(153.9), p95 최저(1,767ms)다.
2. **락 획득 단계 자체가 없다.** `UPDATE ... WHERE status='SUBSCRIBING' AND remaining_units >= :req`
   한 문장이 검증과 감소를 원자적으로 수행한다. 대기·타임아웃·해제 실패 같은 실패 모드가 존재하지 않는다.
3. **외부 의존이 늘지 않는다.** B는 Redis가 죽으면 청약 전체가 멈춘다(§5.3 실측).
4. **커넥션 풀에 덜 민감하다.** A는 행 잠금을 기다리는 동안 DB 커넥션을 쥐고 있어 풀 크기에 직접 영향받는다(§5.2 실측).

C의 대가는 **원자적 UPDATE 한 문장으로 표현 가능한 로직만 가능**하다는 점이다. 배정 규칙이
복잡해지면(예: 투자자별 한도, 등급별 우선 배정) A나 B가 필요해진다. 전략 패턴으로 분리해 둔 이유다
(`AllotmentStrategy` + `subscription.concurrency` 설정 스위치).

## 5. 각 방식의 실패 모드 (실측)

### 5.1 락 타임아웃 (B)

- FSD §8.3 기본값 `tryLock(3초 대기, 10초 임대)`로 위 시나리오(500 VU, 지터 2초) 측정 시
  **타임아웃 0건**. 3초 대기로 충분했다.
- 단, 지터 없이 50스레드가 동시에 몰리는 통합 테스트에서는 3초가 부족해 타임아웃이 발생했다.
  테스트 프로필에서만 30초로 늘렸다(`application-test.yml`). 운영 기본값은 FSD대로 3초를 유지한다.
- **도착이 몰릴수록 3초는 위험**하다는 뜻이다. B를 실제로 쓴다면 대기 시간을 부하 특성에 맞춰
  재측정해야 한다. `subscription.redis-lock.wait-seconds`로 조정한다.

### 5.2 커넥션 풀 고갈 (A)

동일 시나리오를 비관적 락 + 풀 크기만 바꿔 측정했다.

| 풀 크기 | TPS | p95(ms) | 정합성 |
|---|---|---|---|
| 50 | 144.8 | 1,969 | ✅ |
| 10 | 125.9 | 2,430 | ✅ |

풀을 1/5로 줄여도 **정합성은 그대로**이고 처리량이 13% 떨어지고 p95가 23% 늘었다.
A는 잠금 대기 시간만큼 커넥션을 점유하므로, 잠금 구간이 길어지면 풀이 먼저 마른다.
이 시나리오의 트랜잭션이 짧아 붕괴까지는 가지 않았다.

### 5.3 Redis 장애 (B)

| 시점 | 실측 동작 |
|---|---|
| 기동 시 Redis 불가 | **애플리케이션 부팅 실패.** `RedissonClient` 빈 생성이 `RedisConnectionException`으로 실패해 컨텍스트 초기화가 중단된다 |
| 운행 중 Redis 중지 | 요청이 **5,521ms 후 HTTP 500** (`INTERNAL_ERROR`). Redisson 재시도 소진 후 예외 |
| 데이터 영향 | **없음.** 주문 0건 생성, `remaining_units` 불변 — fail-closed로 초과 배정은 발생하지 않았다 |

정합성 측면에서는 안전(fail-closed)하지만 두 가지 개선점이 실측으로 드러났다.

1. 응답이 일반 `INTERNAL_ERROR`(500)다. `LockTimeoutException`처럼 `STATE_LOCK_TIMEOUT`으로 매핑해
   재시도 가능한 오류임을 알리는 편이 낫다.
2. 부팅 자체가 막히므로, Redis를 쓰지 않는 전략(A·C)으로 설정돼 있어도 `RedissonClient` 빈이
   항상 생성된다. 전략별 조건부 빈 생성이 필요하다. → **Phase 7 개선 후보로 기록**.

## 6. 배정 확정(SB-05) 처리량 실측

배정 확정은 주문 1건마다 `LedgerPort.issue`를 호출하고, 그때마다 해시체인 advisory lock을 잡는다.
"원장 기록이 N건이면 advisory lock을 N번 잡는다 → 느리다"는 한계를 실측했다.

| 주문 수 | 소요 시간 | 처리량 |
|---|---|---|
| 200건 (1,000조각) | 1,489ms | 134건/초 |
| 200건 (재측정) | 1,189ms | 168건/초 |

단일 트랜잭션 안에서 원장 200건 + 환불/정산 + 상태 전이까지 **1.2~1.5초**. Phase 2에서 측정한
단건 append 처리량(약 225 tps, `docs/notes/phase-02-ledger-notes.md`)과 같은 수준이다.

**한계**: 주문이 10,000건이면 산술적으로 60초를 넘고, 그동안 단일 트랜잭션이 유지된다.
현재 규모에서는 문제되지 않으나 대량 발행 시에는 배정 확정을 청크로 나누고
청크 간 정합성을 보장하는 설계가 필요하다. Phase 9(배치)에서 다룰 소재다.

확정 직후 불변식을 실제 데이터로 검증했다.

```
INV-1  issued=1000        balances=1000        ✅
INV-5  allotted=1000      total_units=1000     ✅
INV-6  cash+margin=5,000,000,000  extNet=5,000,000,000  ✅
issuance status=LISTED, remaining_units=0
```

## 7. 알려진 위험 — `remaining_units`와 원장 잔고의 이중 진실 공급원

FCFS에서 `issuance.remaining_units`는 **청약 신청 시점**에 줄고, 원장 잔고는 **배정 확정 시점**에
생긴다. 그 사이 구간에서 두 값은 의도적으로 불일치한다(잔여 0인데 원장 발행량은 0).

- 이 구간의 진실 공급원은 `subscription_order`의 증거금 기록이다.
- 배정 확정에서 `settleAndList(soldUnits)`로 `remaining_units`를 실제 판매량 기준으로 재확정하고,
  INV-1·INV-5로 원장과 대조한다. 위반 시 전체 롤백한다.
- 따라서 **정상 종료 후에는 항상 일치**하며, 진행 중 불일치는 설계된 상태다. 이 사실을 모르고
  `remaining_units`를 보유량 근거로 쓰면 안 된다.

## 8. 재현 방법

```powershell
docker compose up -d
./gradlew build

# 1회만: 벤치 데이터 시드 + 투자자 500명 토큰 생성
Get-Content scripts\bench\seed.sql -Raw | docker exec -i fracta-postgres psql -U postgres -d fracta
docker exec fracta-postgres psql -U postgres -d fracta -t -A -c `
  "SELECT id FROM investor WHERE email LIKE 'bench-%@bench.local' AND email <> 'bench-issuer@bench.local' ORDER BY id" `
  | python scripts\bench\gen_tokens.py > scripts\bench\tokens.json

# 3전략 × 3회 자동 측정 → summary.json
scripts\bench\run-all.ps1 -IssuanceId 1 -Runs 3
```
