# FRACTA — 프로젝트 규칙

토큰증권 기반 조각투자 발행·유통 플랫폼 + 오픈 API.
전체 사양은 `docs/FSD.md` (v1.1, SSOT). 현재 작업 범위는 지정된 `docs/phases/phase-XX-*.md`를 따른다.
Phase 목록과 의존 관계는 `docs/phases/README.md` 참조.
증권사는 **NH투자증권 namuh PLUG** (v1.1에서 KIS로부터 전환). 근거는 `docs/phases/phase-05-broker-integration.md` §0.

## 절대 원칙 (모든 판단의 우선순위)

1. **정합성 > 성능 > 기능 수.** 불변식을 깨는 최적화는 금지. 성능이 아쉬우면 한계를 문서에 적고 넘어간다.
2. **부동소수점 금지.** 금액·수량에 `double`/`float` 사용 금지. 내부는 `long`(최소단위 정수), 표현은 `BigDecimal`. `Money`/`Units` 값 객체를 반드시 경유한다.
3. **외부 의존은 포트로 추상화.** 원장·LLM·증권사 API는 인터페이스(Port) + 구현체(Adapter) 분리.
4. **모듈러 모놀리스.** MSA로 쪼개지 않는다. 모듈 간 호출은 `api` 패키지의 공개 인터페이스로만. 엔티티 직접 참조 금지.
5. **모든 상태 변경은 감사 로그를 남긴다.** 행위자·시각·대상·채널(WEB/API/BATCH).
6. **지정된 Phase 범위를 벗어나지 않는다.** 다음 Phase 기능을 미리 구현하지 말 것.

## 명령어

```bash
docker compose up -d          # PG / Redis / MinIO 기동
./gradlew build               # 빌드
./gradlew test                # 전체 테스트
./gradlew test --tests "*LedgerTest"
```

## 기술 스택 (변경 금지)

Java 21 · Spring Boot 3.3 · JPA + QueryDSL · Spring Batch 5
PostgreSQL 16 + pgvector · Redis 7 · MinIO
테스트: JUnit5 + **Testcontainers** (H2 금지) + WireMock + k6

## 코딩 규칙

- 트랜잭션 경계는 `application` 레이어에만. 도메인·인프라에 `@Transactional` 금지
- 네이티브 쿼리는 바인딩 파라미터만. 문자열 연결 금지
- 예외는 `common/error`의 도메인 예외 사용. 에러 코드 접두사 체계(`AUTH_`/`VALID_`/`STATE_`/`FUND_`/`RATE_`/`IDEM_`/`SUIT_`) 준수
- 로그는 구조화(JSON), 모든 로그에 `requestId` 포함
- 토큰·시크릿·주민번호는 로깅 전 마스킹
- AI 모델 ID는 설정값(`AI_MODEL`, 기본 `claude-opus-5`)으로. 하드코딩 금지. 어시스턴트 프리필·`budget_tokens`·`temperature` 사용 금지(400 오류)
- 용어는 `docs/FSD.md` §2 용어집을 따른다. 임의 네이밍 금지

## 자주 나는 사고 (작업 전 확인)

1. `unit_price × units` long 오버플로우 → 곱셈 전 범위 검증
2. 비례배분 후 총합 불일치 → 잔여 분배 단계까지 구현하고 assert
3. 매도 주문 시 수량 잠금 누락 → 이중 매도 발생
4. 오더북 인메모리 상태 재시작 복원 누락 → `@PostConstruct`로 DB에서 복원
5. 해시체인 동시 INSERT → `pg_advisory_xact_lock` 필수
6. 증권사 도메인과 계좌구분을 잘못 짝지음(모의 도메인 + 계좌구분 `01` 등) → 전부 실패. 토큰 발급은 실전 도메인, 조회는 모의 도메인이라는 점도 주의
7. AI 가드레일을 프롬프트에만 의존 → 반드시 코드 후처리 검증
8. audit_log에 민감정보 평문 저장 → 마스킹 필터
9. DvP 락 획득 순서 미고정 → 데드락. 항상 `owner_id` 오름차순
10. 테스트에서 H2 사용 → advisory lock·pgvector 동작 안 함

## 검증

- 코드 변경 후 `./gradlew test` 실행
- 원장·청약·결제 관련 변경 시 불변식 테스트(`*InvariantTest`) 반드시 통과 확인
- Phase 완료 조건 체크리스트를 모두 만족하기 전에 완료 보고하지 말 것

## 하지 말 것

- 성능을 이유로 advisory lock 제거
- `ledger_transaction` 테이블에 UPDATE/DELETE
- 실전 증권사 연동 코드 작성. `BROKER_ACCOUNT_PRODUCT_CODE`는 `03`(모의), 도메인은 `moapi.nhplug.com` 고정
- `BrokerSafetyValidator`(실전 계좌 차단) 우회·비활성화
- 증권사 API로 **주문** 전송 (시세 조회 전용이다. 매매는 자체 오더북에서 체결)
- 사양에 없는 라이브러리 추가 (필요하면 먼저 제안하고 승인받을 것)
- 테스트를 건너뛰거나 `@Disabled` 처리
