# FRACTA — 에이전트 규칙

> **정본은 [`CLAUDE.md`](CLAUDE.md)다.** 작업 전에 그 파일을 읽는다.
>
> 이 파일은 예전에 `CLAUDE.md`를 통째로 복사해 두었다가 **두 파일이 서로 다른 말을 하게 됐다**
> (FSD 버전이 v1.1.1로 멈춰 있었고, 이미 제거한 QueryDSL을 여전히 스택으로 적고 있었다).
> 그래서 내용을 옮기지 않고 가리키기만 한다. 규칙이 바뀌면 `CLAUDE.md` 한 곳만 고친다.

## 문서 지도

| 무엇을 알고 싶은가 | 어디를 보나 |
|---|---|
| 코딩 규칙·금지 사항·자주 나는 사고 | [`CLAUDE.md`](CLAUDE.md) |
| 기능 사양 (SSOT) | [`docs/FSD.md`](docs/FSD.md) |
| 현재 작업 범위 | [`docs/phases/README.md`](docs/phases/README.md) → 해당 Phase 문서 |
| 프로젝트가 무엇이고 왜 이렇게 만들었나 | [`README.md`](README.md) |

## 그래도 이것만은 (읽지 않고 손대면 사고 나는 것)

`CLAUDE.md`를 아직 안 읽었더라도 아래는 지킨다.

1. **증권사에 주문을 보내지 않는다.** 시세 조회 전용이다. 주문 경로는 소스에 나타나기만 해도
   `NoBrokerOrderPathTest`가 빌드를 깬다. 그 테스트를 우회·비활성화하지 않는다.
2. **금액·수량에 `double`/`float`을 쓰지 않는다.** 내부는 `long`, 표현은 `BigDecimal`.
   `Money`/`Units` 값 객체를 경유한다.
3. **`ledger_transaction`에 UPDATE/DELETE를 하지 않는다.** append-only 해시체인이다.
4. **advisory lock을 성능을 이유로 제거하지 않는다.**
5. **테스트를 건너뛰거나 `@Disabled` 처리하지 않는다.** H2도 쓰지 않는다(Testcontainers).
6. **적용이 끝난 Flyway 마이그레이션을 수정하지 않는다.** 체크섬이 깨져 다음 기동이 실패한다.
   새 파일을 추가한다.
