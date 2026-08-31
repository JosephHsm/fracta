# Phase 3 — 계좌·발행

> 기간 1주 · 선행 Phase 2 · FSD 참조 §6.1, §6.2, §5.1, §5.2

## 목표

투자자와 발행인을 만들고, 기초자산 → 발행 → 상장까지의 상태 머신을 완성한다.

## 범위

**포함**
- 투자자 등록 / KYC Mock / 투자성향 진단 / 적합성 판정
- 예치금 입출금 (가상)
- JWT 인증 (웹앱용 `/api/v1`)
- 기초자산·발행 계획 CRUD
- 토큰 심볼 발급
- 투자설명서 PDF 업로드 → MinIO + 인덱싱 이벤트 발행
- Issuance 상태 머신 전이
- INV-6(예치금 보존) 검증 추가

**제외**
- 청약 로직 일체 (Phase 4)
- AI 인덱싱 실제 처리 (Phase 8) — 여기서는 **이벤트 발행까지만**
- OAuth2 / API Key (Phase 7)
- 실제 시세 기반 괴리율 경고 (Phase 5 연동 후) — 여기서는 `MockMarketDataAdapter` 사용

## 핵심 사양

### 투자성향 진단 (AC-03)

- 8문항 설문 → 점수 합산 → 5단계 등급
- 등급: `안정형(1)` `안정추구형(2)` `위험중립형(3)` `적극투자형(4)` `공격투자형(5)`
- 설문 문항·배점표는 `docs/appendix/risk-profile-questions.md`로 분리 작성
- 진단 결과는 유효기간 1년 (만료 시 재진단 유도)

### 적합성 판정 (AC-04) ★

```
상품 위험등급 > 투자자 성향등급  →  차단
차단 응답: 403 + { "code": "SUIT_PROFILE_MISMATCH", ... }
단, 투자자가 "확인 서명"(부적합 확인) 제출 시 허용 + 감사 로그 필수
```

- 이 판정은 **Phase 4 청약과 Phase 6 매수 양쪽에서 호출**된다. `account/api`에 공개 인터페이스로 노출한다
  ```java
  public interface SuitabilityPort {
      SuitabilityResult check(InvestorId investor, RiskGrade productGrade);
  }
  ```
- 확인 서명 이력은 별도 테이블에 보관 (금소법 시뮬레이션의 핵심 증거)

### 예치금 (AC-05)

- `investor.cash_balance`는 `Money`
- 입금/출금은 `cash_transaction` 테이블에 append (INV-6 검증용)
- **증거금 차감은 Phase 4에서 구현.** 여기서는 잔액 증감 API만

### 토큰 심볼 (IS-05)

```
FR-{자산코드}-{연번}      예: FR-ESRK-001
자산코드: 영문 대문자 4자, 기초자산 등록 시 입력 또는 자동 생성
연번: 같은 자산코드 내 3자리 zero-pad
```
- `issuance.token_symbol` UNIQUE
- 동시 발행 시 연번 충돌 → **UNIQUE 제약 + 재시도** 또는 시퀀스 사용

### 발행 검증 (IS-02)

- `total_units` ≥ 100, ≤ 1,000,000
- `unit_price` ≥ 100원
- `total_units × unit_price` ≤ 100억원 → **`Money.multiply` 사용해 오버플로우 동시 방어**
- 티커 지정 시 발행가 vs 시세 환산가 괴리율 ±30% 초과하면 **경고**(차단 아님)

### 상태 머신 (FSD §5.2)

```
DRAFT → PENDING_APPROVAL → APPROVED → SUBSCRIBING → ALLOTTING
      → LISTED → (SUSPENDED) → DELISTED
                ↘ REJECTED
```

- 전이 규칙을 **enum 내부에 선언**하고, 허용되지 않은 전이는 `STATE_` 예외
- `APPROVED` 전이(IS-04)는 ADMIN만, 감사 로그 필수
- `SUBSCRIBING` 전이(IS-06)는 스케줄러가 `subscription_start_at` 도달 시 수행

## 구현 순서

1. Flyway `V3__account.sql` — `investor`, `risk_profile_result`, `cash_transaction`, `suitability_ack`
2. Flyway `V4__issuance.sql` — `underlying_asset`, `issuance`
3. `account` 모듈: 등록 → KYC Mock(비동기 3초 후 `VERIFIED`) → 성향 진단 → 적합성 판정 → 예치금
4. JWT 인증 (Spring Security). 웹앱 전용. `/api/v1/**` 보호
5. `account/api/SuitabilityPort`, `AccountQueryPort` 공개 인터페이스 노출
6. `issuance` 모듈: 기초자산 CRUD → 발행 계획 → 심볼 발급 → 상태 머신
7. 투자설명서 업로드 → MinIO presigned PUT 또는 서버 경유 업로드 → `prospectus_file_key` 저장 → `ProspectusUploadedEvent` 발행 (`@TransactionalEventListener(AFTER_COMMIT)`)
8. `LISTED` 전이 시 `LedgerPort.issue()` 호출하여 발행인 계좌에 전량 발행
   - **주의**: 실제로는 Phase 4 배정 완료 후 상장이다. Phase 3에서는 ADMIN 수동 상장 경로만 열어두고 테스트한다
9. INV-6 검증 로직 추가 → `verifyInvariant`에 편입

## 완료 조건 체크리스트

- [x] **발행 → 상장까지 상태 전이 통합 테스트 통과** (FSD §14 명시 조건)
- [x] 허용되지 않은 상태 전이 시도 → `STATE_` 에러 코드 반환 테스트
- [x] 투자자 등록 후 3초 내 KYC `VERIFIED` 전환 확인
- [x] 성향 진단 8문항 → 5등급 매핑 경계값 테스트 (각 등급 경계 점수)
- [x] 적합성 차단 테스트 — 위험등급 4 상품 + 성향등급 2 투자자 → `403 SUIT_PROFILE_MISMATCH`
- [x] 확인 서명 후 동일 요청 → 통과 + `suitability_ack` 기록 + 감사 로그 존재
- [x] `total_units × unit_price` 오버플로우 유발 입력 → `VALID_` 에러 (앱 크래시 아님)
- [x] 발행 검증 경계값 테스트: `total_units` = 99/100/1000000/1000001
- [x] 토큰 심볼 형식 테스트 + 동시 생성 100건에서 중복 0건
- [x] PDF 업로드 → MinIO 객체 존재 확인 + `ProspectusUploadedEvent` 발행 확인
- [x] 발행 승인 시 `audit_log`에 actor/before/after 기록 확인
- [x] INV-6 테스트 — 입금·출금 반복 후 `Σ cash_balance == 총입금 - 총출금`
- [x] 상장 후 `LedgerPort.totalIssued(symbol) == issuance.total_units` (INV-1 유지)
- [x] `IssuanceStateInvariantTest` 통과

> **완료 근거** (2026-08-31, 커밋 `41b91a3`): 계좌·발행 테스트 21건 포함 전체 71건 통과.
> 성향 진단 배점표는 `docs/appendix/risk-profile-questions.md`.
> FSD §6.1의 `SUITABILITY_MISMATCH` 대신 §7.1 접두사 체계를 따라 `SUIT_PROFILE_MISMATCH`를 사용한다.

## 흔한 실수

1. 상태 전이 검증을 서비스 곳곳에 `if`로 흩뿌림 → enum에 모으고 단일 지점에서 검증
2. KYC Mock을 `Thread.sleep(3000)`으로 동기 처리 → 요청 스레드 점유. `@Async` 또는 스케줄러 사용
3. 적합성 판정을 `subscription` 모듈이 `account` 엔티티를 직접 참조해 구현 → **모듈 경계 위반.** `SuitabilityPort` 경유
4. MinIO 업로드를 트랜잭션 안에서 수행 → 롤백돼도 파일은 남는다. 커밋 후 업로드하거나 고아 파일 정리 배치를 문서화
5. `ProspectusUploadedEvent`를 커밋 전에 발행 → Phase 8이 아직 없는 데이터를 읽는다. `AFTER_COMMIT` 필수
6. 예치금을 `double`로 계산 → **금지**

## 다음 Phase 진입 전 확인

- [x] `SuitabilityPort` 가 확정되었는가? Phase 4·6이 호출한다
- [x] `issuance.remaining_units` 컬럼이 있는가? **Phase 4의 방식 C(원자적 감소)가 이 컬럼을 요구한다.** 여기서 미리 추가하고 `total_units`로 초기화해 둔다
