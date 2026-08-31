# Phase 2 — 원장 ★최우선

> 기간 1주 · 선행 Phase 1 · FSD 참조 §6.4, §8.1, §8.2, §5.1

## 목표

시스템 전체의 단일 진실 공급원을 만든다. **이 Phase가 틀리면 이후 전부가 틀린다.** 성능보다 정합성을 우선한다.

## 범위

**포함**
- `ledger_transaction` (append-only) / `ledger_balance` 테이블
- `LedgerPort` 인터페이스 + `HashChainLedgerAdapter` 구현
- 해시체인 생성·검증
- advisory lock 기반 체인 직렬화
- 불변식 INV-1 ~ INV-4 검증 로직
- 변조 검출 테스트

**제외**
- INV-5(배정 총량) → Phase 4에서 추가
- INV-6(예치금 보존) → Phase 3에서 예치금 도입 후 추가
- 배치 스케줄링 → Phase 9
- 토큰 심볼 발급 로직 → Phase 3 (여기서는 문자열을 그대로 받는다)

## 핵심 사양

### LedgerPort (시그니처 변경 금지)

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

### 해시 정규화 (FSD §8.2 — 필드 순서 절대 변경 금지)

```
canonical = seq | txType | tokenSymbol | fromOwnerId | toOwnerId
          | units | refType | refId | createdAt(ISO-8601 UTC, 밀리초) | prevHash
```

- 구분자 `|`, null은 빈 문자열
- `currHash = SHA-256(canonical)` 을 hex 소문자 64자
- Genesis(seq=1) 의 `prevHash` = `"0".repeat(64)`
- **`createdAt` 포맷이 흔들리면 재검증이 전부 실패한다.** 포맷터를 상수로 고정하고 테스트로 못 박는다.

### 체인 직렬화

```java
@Transactional
public LedgerTxId append(LedgerTx tx) {
    jdbc.execute("SELECT pg_advisory_xact_lock(?)", LEDGER_LOCK_ID);
    String prevHash = findLastHash();   // 락 획득 이후에 읽어야 한다
    ...
}
```

- `pg_advisory_xact_lock`은 **트랜잭션 종료 시 자동 해제**된다. 명시적 unlock 금지
- 락 획득 → `prevHash` 조회 순서를 **절대 뒤집지 않는다**
- `LEDGER_LOCK_ID`는 상수. 다른 용도의 advisory lock과 ID가 겹치지 않도록 상수 클래스에 모아 관리

### 잔고 갱신

- `ledger_balance`는 낙관적 락(`@Version`)
- `lock`/`unlock`은 `locked_units`만 변경, `units`는 불변
- `transfer` 시 from의 **가용 수량**(`units - locked_units`) 검증 → 부족 시 `InsufficientUnitsException`
- **주의**: 매도 잠금분은 transfer 시점에 이미 `locked_units`에 있다. Phase 6에서 DvP가 `unlock` 후 `transfer`할지, 잠금분 직접 이전할지 규칙을 여기서 정하고 문서화한다 → **권장: `unlock` 후 `transfer`를 같은 트랜잭션에서 수행**

## 구현 순서

1. Flyway `V2__ledger.sql` — 두 테이블 + 인덱스 + 제약
   - `ledger_transaction.curr_hash` UNIQUE
   - `ledger_balance` UNIQUE(owner_id, token_symbol)
   - `CHECK (units >= 0 AND locked_units >= 0 AND locked_units <= units)` — DB 레벨 INV-2/INV-3 방어선
2. DB 유저에서 `ledger_transaction`의 UPDATE/DELETE 권한 회수 (`REVOKE`)
3. 값 객체: `OwnerId`, `TxRef(refType, refId)`, `LedgerTxId`, `Balance`, `TxType`, `RefType`
4. `LedgerHasher` — canonical 생성 + SHA-256. **순수 함수로 분리** (테스트 용이성)
5. `HashChainLedgerAdapter` — 4개 쓰기 연산 + 조회
6. `verifyChain(from, to)` — 구간 재계산. 결과에 최초 불일치 seq 포함
7. `verifyInvariant(symbol)` — INV-1 ~ INV-3
8. 테스트 작성

## 완료 조건 체크리스트

- [x] **1만 건 트랜잭션 INSERT 후 `verifyChain(1, 10000)` 통과** (FSD §14 명시 조건)
- [x] **임의 레코드 변조 후 검출 테스트 통과** — `units` 직접 UPDATE(관리자 권한) 후 `verifyChain`이 해당 seq를 정확히 지목
- [x] `prev_hash` 변조 검출 테스트 통과
- [x] Genesis 트랜잭션 `prev_hash == "0"*64` 검증
- [x] **동시성 테스트**: 50 스레드가 동시에 `issue` 호출 → 체인 분기 0건, `verifyChain` 통과
  - `CountDownLatch`로 정확한 동시 시작 보장 (FSD §15.2)
- [x] INV-1 테스트 — `totalIssued(s) == Σ balance(owner, s).units`
- [x] INV-2 테스트 — `locked_units <= units` 위반 시도가 DB CHECK에서 거부됨
- [x] INV-3 테스트 — 잔고 초과 `transfer` 시 `InsufficientUnitsException`, 잔고 불변
- [x] INV-4 테스트 — `verifyChain` 이 전 구간 통과
- [x] `LedgerTransactionInvariantTest` 존재 및 통과 (CLAUDE.md 검증 규칙)
- [x] 애플리케이션 DB 유저로 `UPDATE ledger_transaction` / `DELETE` 시도 시 권한 오류
- [x] 모든 테스트가 Testcontainers PostgreSQL에서 실행됨 (H2 사용 0건)
- [x] `lock` 후 가용 수량이 정확히 감소하고, `transfer` 가 가용 수량 초과 시 실패

> **완료 근거** (2026-08-31, 커밋 `f88eded`): 원장 테스트 20건 통과.
> 1만 건 `verifyChain` 87ms, 단건 append 약 225 tps 실측 — `docs/notes/phase-02-ledger-notes.md`.

## 흔한 실수

1. **advisory lock 획득 전에 `prevHash`를 읽음** → 체인 분기. 순서 고정 필수
2. `createdAt`을 `LocalDateTime`으로 저장 → 타임존 유실로 재검증 실패. **`Instant`/`timestamptz` 사용**
3. 해시를 대문자 hex로 만들었다가 검증 시 소문자 비교 → 항상 실패. 포맷 상수화
4. `verifyChain`을 전체 로드 후 메모리에서 처리 → 1만 건은 괜찮지만 10만 건에서 OOM. **스트리밍/페이징으로 구현**
5. 잔고 갱신을 `SELECT` 후 `UPDATE`로 처리하며 낙관적 락 미적용 → lost update
6. `pg_advisory_lock`(세션 락)을 씀 → 커넥션 풀 반납 시 해제 안 됨. **반드시 `pg_advisory_xact_lock`**
7. 성능이 아쉬워 advisory lock 제거 → **CLAUDE.md 금지 사항.** 한계는 문서에 적고 넘어간다

## 문서화 (README 소재)

- advisory lock 방식의 처리량 한계를 **실측**해서 기록한다 (초당 몇 건?)
- FSD §8.2.1의 개선안(토큰 심볼별 체인 분리)을 왜 구현하지 않았는지 근거를 적는다
- "블록체인을 안 쓰고 해시체인으로 대체한 이유" — README 필수 항목 2번의 소재

## 다음 Phase 진입 전 확인

- [x] `LedgerPort` 시그니처가 확정되었는가? Phase 3·4·6이 전부 이걸 호출한다
- [x] `TxRef` 의 `RefType` enum에 `SUBSCRIPTION`/`EXECUTION`/`ADMIN` 이 정의되어 있는가
