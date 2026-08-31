# Phase 1 — 기반

> 기간 1주 · 선행 없음 · FSD 참조 §3.3, §4, §5.3, §7.1, §6.8, 부록 A

## 목표

이후 모든 Phase가 올라탈 골격을 만든다. **비즈니스 로직은 한 줄도 쓰지 않는다.**

## 범위

**포함**
- Gradle 멀티모듈 아님 — 단일 모듈 + 패키지 경계 (모듈러 모놀리스)
- `docker-compose.yml` — PostgreSQL 16(pgvector), Redis 7, MinIO
- 패키지 스켈레톤 (FSD §3.3 구조 그대로)
- `Money`, `Units` 값 객체 + 단위 테스트
- 공통 응답 포맷 / 에러 코드 체계 / 전역 예외 핸들러
- 감사 로그 AOP (`@Auditable`) — 뼈대만, 실제 기록 대상은 Phase 3부터
- 구조화 로깅(JSON) + `requestId` MDC 필터
- Testcontainers 기동 확인
- **증권사 환경 안전장치 (아래 §안전장치)**

**제외**
- 도메인 엔티티 일체 (Phase 2~)
- 인증/인가 (Phase 3에서 JWT, Phase 7에서 OAuth2)
- Flyway 마이그레이션은 뼈대만, 테이블 DDL은 각 Phase에서 추가

## 구현 순서

1. **프로젝트 스캐폴딩**
   - Spring Boot 3.3.x, Java 21, Gradle Wrapper 포함
   - 의존성: web, data-jpa, validation, actuator, redis, batch(선언만), querydsl, flyway, springdoc
   - `application.yml` + `application-local.yml` + `application-test.yml`

2. **docker-compose.yml**
   ```
   postgres:  pgvector/pgvector:pg16   5432   (DB: fracta)
   redis:     redis:7-alpine           6379
   minio:     minio/minio              9000/9001
   ```
   - PG 초기화 스크립트에 `CREATE EXTENSION IF NOT EXISTS vector;`
   - 세 컨테이너 모두 healthcheck 정의 (Phase 1 완료 조건)

3. **패키지 스켈레톤** — FSD §3.3 트리를 그대로 생성. 각 모듈에 `package-info.java`로 책임 1줄 명시.

4. **`common/money`**
   ```java
   public record Money(long amount) implements Comparable<Money>
   public record Units(long value) implements Comparable<Units>
   ```
   - `Money.multiply(long units)`는 `Math.multiplyExact` 사용 → 오버플로우 시 `ArithmeticException`을 `AmountOverflowException`으로 래핑
   - `Units.minus`는 음수 결과 시 `InsufficientUnitsException`
   - 생성자에서 음수 거부
   - `toDisplay()`는 `BigDecimal` 반환

5. **`common/error`**
   - `DomainException` 추상 클래스 + `ErrorCode` enum
   - 접두사 체계: `AUTH_` `VALID_` `STATE_` `FUND_` `RATE_` `IDEM_` `SUIT_` (FSD §7.1)
   - `@RestControllerAdvice` 전역 핸들러 → FSD §7.1 공통 응답 포맷으로 변환

6. **`common/response`**
   - `ApiResponse<T>` — `data` + `meta{requestId, timestamp}`
   - `ErrorResponse` — `error{code, message, details}` + `meta`

7. **`requestId` 필터** — `OncePerRequestFilter`에서 UUID 생성 후 MDC 주입. 응답 헤더 `X-Request-Id`에도 반영.

8. **감사 로그 AOP**
   - `@Auditable(action, targetType)` 애노테이션
   - `@Around` 어드바이스가 before/after 상태를 JSON 직렬화하여 `audit_log` INSERT
   - `channel`은 MDC에서 판별 (WEB/API/BATCH/ADMIN)
   - **마스킹 필터**: 토큰·시크릿·주민번호·`ci_hash` 필드명 패턴은 `***`로 치환 후 저장
   - `audit_log` 테이블 DDL + 애플리케이션 DB 유저에서 UPDATE/DELETE 권한 회수 (FSD AU-04)

9. **구조화 로깅** — logback JSON 인코더. 모든 로그에 `requestId` 포함.

10. **Testcontainers 기반 클래스**
    - `@Testcontainers` 추상 `IntegrationTestBase` — PG(pgvector 이미지) + Redis 컨테이너 재사용
    - **H2 금지.** 의존성에서 아예 제외한다.

## 안전장치 — 실전 계좌 차단 (필수)

Phase 5에서 namuh PLUG를 붙이지만, **설정 검증은 Phase 1에 넣는다.** 문서상의 금지 조항만으로는 실수를 막지 못한다.

```java
// common/config/BrokerSafetyValidator.java
@Component
class BrokerSafetyValidator {
    @PostConstruct
    void validate() {
        // 1. 계좌구분 03(모의) 이외 거부
        // 2. 도메인이 moapi.* 가 아니면 거부
        // 3. BROKER_ALLOW_LIVE=true 라는 명시적 플래그가 없으면 무조건 거부
        // → 위반 시 IllegalStateException 으로 애플리케이션 부팅 실패
    }
}
```

- 검증 대상 설정값: `broker.env`, `broker.base-url`, `broker.account-product-code`
- 허용: `broker.env=mock`, `base-url=https://moapi.nhplug.com:8443`, `account-product-code=03`
- **이 검증을 우회하거나 비활성화하는 코드를 작성하지 않는다.**

## 산출물

```
fracta/
├── build.gradle.kts, settings.gradle.kts, gradlew
├── docker-compose.yml
├── docker/postgres/init.sql
├── src/main/java/com/fracta/
│   ├── FractaApplication.java
│   ├── common/{money,error,response,event,config,logging}/
│   └── {issuance,subscription,trading,ledger,settlement,account,openapi,audit,external,ai,batch}/  (빈 패키지 + package-info)
├── src/main/resources/{application.yml,application-local.yml,logback-spring.xml}
├── src/main/resources/db/migration/V1__audit_log.sql
└── src/test/java/com/fracta/{common/money,support/IntegrationTestBase}/
```

## 완료 조건 체크리스트

- [x] `docker compose up -d` **한 번**으로 PG·Redis·MinIO 전부 healthy
- [x] `./gradlew build` 성공 (경고 0을 목표, 최소한 에러 0)
- [x] `GET /actuator/health` 가 `{"status":"UP"}` 반환, DB·Redis 컴포넌트 포함
- [x] `MoneyTest` / `UnitsTest` 통과 — 경계값 필수: `0`, `1`, `Long.MAX_VALUE`, 오버플로우, 음수 거부
- [x] `Money.multiply` 오버플로우 시 `AmountOverflowException` 발생 테스트 통과
- [x] 임의 API 호출 시 응답에 `meta.requestId` 존재, 로그에서 동일 `requestId` 검색 가능
- [x] 의도적 예외 발생 시 FSD §7.1 실패 포맷 그대로 반환
- [x] `@Auditable` 붙인 더미 메서드 호출 → `audit_log`에 before/after 기록 확인
- [x] 마스킹 테스트 — `password`/`secret`/`ci_hash` 필드가 `audit_log`에 평문으로 저장되지 않음
- [x] DB 유저로 `UPDATE audit_log` 시도 시 권한 오류 발생
- [x] `BrokerSafetyValidator` 테스트 — `account-product-code=01` 설정 시 컨텍스트 로딩 실패
- [x] Testcontainers로 PG 기동 후 `SELECT extversion FROM pg_extension WHERE extname='vector'` 성공
- [x] 프로젝트 의존성 트리에 **H2가 없음** (`./gradlew dependencies | grep -i h2` 결과 없음)

> **완료 근거** (2026-08-31, 커밋 `3e4c26b`): 테스트 30건 통과.
> `/actuator/health`·`X-Request-Id`·`audit_log` 권한 회수는 실제 기동 상태에서도 확인했다.
> H2는 `configurations.all { exclude }`로 전이 의존성까지 차단한다.

## 흔한 실수

1. `Money`를 `BigDecimal`로 내부 표현 → **금지.** 내부는 `long`, 표현만 `BigDecimal`
2. 오버플로우 검증을 곱셈 **후에** 함 → `Math.multiplyExact`로 곱셈 시점에 잡는다
3. `docker compose up` 후 앱이 DB보다 먼저 떠서 실패 → healthcheck + `depends_on: condition: service_healthy`
4. pgvector를 일반 `postgres:16` 이미지로 띄움 → 확장 설치 불가. `pgvector/pgvector:pg16` 사용
5. 테스트 편의로 H2 추가 → advisory lock·pgvector 동작 안 함. Phase 2에서 전부 깨진다

## 다음 Phase 진입 전 확인

- [x] `Money`/`Units` API가 확정되었는가? Phase 2 이후 전체가 이걸 쓴다. 나중에 바꾸면 광범위 수정이 발생한다.
- [x] 패키지 경계가 FSD §3.2 의존 규칙을 표현하는가? (ArchUnit 도입은 선택이지만 권장)
