# Phase 10 인수인계 — investor-web 완료 시점

> 작성 2026-09-02 · 기준 커밋 `9a53c7f` · 다음 담당자용

이 문서는 **Phase 10을 이어받는 사람**이 읽는다. 무엇이 끝났고, 무엇이 남았고,
어디서 넘어질지를 적는다. 사양은 `docs/FSD.md` §11과 `phase-10-frontend.md`가 그대로 유효하다.

---

## 1. 지금 어디까지 왔나

`phase-10-frontend.md`의 구현 순서 기준이다.

| # | 단계 | 상태 |
|---|---|---|
| 1 | 모노레포 스캐폴딩 | ✅ |
| 2 | `packages/api-client` 자동 생성 파이프라인 | ✅ |
| 3 | `packages/ui` 토큰 + 공용 컴포넌트 | ✅ |
| 4 | **investor-web 7개 화면** | ✅ |
| 5 | **dev-portal 6개 화면** | ⬜ **다음 작업** |
| 6 | View Transition + 마이크로 인터랙션 마감 | 🟡 부분 |
| 7 | 반응형·접근성 점검 | ⬜ |
| 8 | 에러 코드 → 문구 매핑 일관화 | 🟡 매핑표는 완성, 전 화면 적용 점검 필요 |

**완료된 화면** (`apps/investor-web/src/app`)

```
/                          홈 — Bento 대시보드 + 청약 중 / 상장 종목
/login                     로그인
/onboarding                투자성향 진단 8문항
/tokens/[symbol]           종목 상세 — 호가창·주문·체결·괴리율 배지
/issuances/[id]            청약 — 적합성 차단 UX 포함
/issuances/[id]/prospectus 투자설명서 PDF 뷰어 + AI 사이드패널
/portfolio                 내 자산
/orders                    주문 내역
```

**검증 상태** — Java 테스트 275건 통과, 프론트 4개 패키지 `typecheck`·`lint`·`build` 통과,
실서버(`bootRun` + `next start`)에서 로그인·목록·호가·체결·청약·AI 질의 실동작 확인.

---

## 2. 다음 작업 — dev-portal 6개 화면

`FSD.md` §11.3 기준. 순서는 `phase-10-frontend.md` 구현 순서 5를 따른다.

| 화면 | 필요한 API | 상태 |
|---|---|---|
| 앱 관리 | 클라이언트 등록·키 발급·Scope | ⚠ **웹앱용 API 없음** |
| 대시보드 | 호출량·쿼터·에러율 | ⚠ **집계 API 없음** |
| API 문서 | Swagger UI 임베드 + AI 챗 | `/swagger-ui.html`, `POST /api/v1/ai/devportal/ask` 존재 |
| 샌드박스 | `/open/sandbox/**` 호출 콘솔 | 오픈 API 존재 |
| 웹훅 | 등록·이력·재발송 | 등록만 존재. **이력·재발송 API 없음** |
| 로그 | 호출 로그 검색 | ⚠ **검색 API 없음** |

**시작 전에 반드시 할 일** — 위 ⚠ 항목은 서버에 엔드포인트가 아예 없다.
investor-web에서도 같은 일이 있었다(홈 화면용 목록 API 부재). **화면 코드를 쓰기 전에
필요한 엔드포인트를 먼저 확인하고, 없으면 추가 + 테스트부터 작성하라.**
없는 API를 전제로 화면을 짜다가 중간에 멈추면 되돌리는 비용이 크다.

확인 방법:

```bash
python -c "
import json; s=json.load(open('docs/openapi/fracta-openapi.json',encoding='utf-8'))
print('\n'.join(sorted(p for p in s['paths'])))
"
```

---

## 3. 이 프로젝트에서 반드시 지켜야 하는 것

`CLAUDE.md`와 `FSD.md` §11.4가 원본이다. 실제로 사고가 났던 것만 추린다.

1. **금액을 프론트에서 계산하지 않는다.** `단가 × 수량`도 안 된다. `Money` 컴포넌트에
   카운트업이 없는 것도 같은 이유다(중간 프레임이 서버가 준 적 없는 금액이 된다).
   내 자산 화면에 평가금액이 없는 것도 서버에 산출 API가 없어서다 — 곱해서 만들지 마라.
2. **색은 데이터에만.** 버튼·헤더·보더는 무채색. 색상 리터럴 금지, `packages/ui` 토큰 경유.
   토큰을 바꿨으면 `pnpm design:contrast`로 대비비를 확인하라(19쌍 × 라이트/다크).
3. **화면 문구는 합니다체.** 코드 주석·설계 문서의 해라체를 UI에 쓰지 마라.
4. **원시 코드를 화면에 노출하지 않는다.** 에러 코드는 `errorMessage()`,
   상태값은 `Record<Enum, ...>` 매핑을 쓴다(§5 참조).
5. **모션은 page ≤ 400ms.** `prefers-reduced-motion`에서 전부 꺼져야 한다.

---

## 4. 개발 환경 — 띄우는 순서와 함정

```bash
docker compose up -d postgres redis minio    # 인프라
docker compose up -d --build ai-service      # AI (--build 중요, §5 참조)
./gradlew bootRun                            # 백엔드 :8080
node scripts/seed/demo-data.mjs              # 데모 데이터 (서버가 뜬 뒤에)
pnpm dev:investor                            # 프론트 :3000
```

**데모 계정** (시드가 만든다. 재실행해도 고정)

```
demo@fracta.demo         / demo-password-1!   공격투자형, 예수금 1억
conservative@fracta.demo / demo-password-1!   안정형 (적합성 차단 시연용)
admin@fracta.demo        / demo-password-1!
```

**시드가 만드는 상태** — 상장 3종목의 괴리율을 체결가로 의도적으로 만든다:
정상(≈-1%) / 경고(≈+13%) / **거래중단(≈+25%, TR-08이 자동 SUSPENDED 전환)**.
청약 중 1종목에는 5쪽짜리 투자설명서가 업로드·인덱싱되어 AI 사이드패널이 동작한다.

**깨끗한 상태로 되돌리기** — 시드를 다시 돌리면 종목이 누적된다.
`ledger_transaction`은 UPDATE/DELETE 금지라 부분 삭제가 불변식을 깬다. 통째로 다시 만든다:

```bash
docker compose down -v && docker compose up -d postgres redis minio
docker compose up -d --build ai-service
./gradlew bootRun    # Flyway가 스키마를 다시 만든다
node scripts/seed/demo-data.mjs
```

---

## 5. 넘어졌던 자리 (같은 실수 반복 금지)

이번 Phase에서 실제로 시간을 잃은 지점이다. 전부 재발 가능하다.

### 5.1 스펙에 보안 스킴이 없으면 생성 클라이언트가 인증 헤더를 안 붙인다
화면은 멀쩡한데 모든 데이터가 비어 보인다. `OpenApiConfig`가 `bearerAuth`를 선언한다.
**회귀 테스트 있음** — `OpenApiSpecExportTest.declaresBearerSecurityScheme`.

### 5.2 TanStack Query 에러는 `unhandledrejection`으로 안 잡힌다
Query가 에러를 잡아 상태로 바꾼다. 401 처리와 오류 배너는 `QueryCache.onError`
(`lib/providers.tsx`)에 있다. **조회 실패를 빈 목록으로 보여주면 안 된다** —
통신 실패와 데이터 없음은 다른 상태다.

### 5.3 `Record<string, ...>` 매핑은 빠진 값을 못 잡는다
상태값을 실제 enum을 보지 않고 지어냈다가 화면에 `DEPOSITED`가 그대로 노출됐다.
서버 응답 필드를 도메인 enum 타입으로 두면 스펙에 enum이 실리고, 프론트에서
`Record<Enum, ...>`로 받으면 빠진 값이 컴파일 오류가 된다. **JSON은 바뀌지 않는다.**
이 전환으로 `"PRO_RATA"` → 실제 `"PRORATA"` 오타도 잡혔다(모든 발행이 "선착순"으로
잘못 표시되고 있었다).

### 5.4 서로 다른 record가 같은 스키마 이름으로 합쳐진다
컨트롤러 간 중첩 record 이름이 겹치면 springdoc이 하나로 병합한다. 실제로
`ExecutionResponse`에서 `buyFee`/`sellFee`가 사라지고, 오픈 API `BalanceResponse`가
`{balance}` 하나로 잘못 문서화됐다. 오픈 API 쪽은 `OpenApi*` 접두사를 쓴다.
**중복 검사:**

```bash
grep -rhoE "public record [A-Za-z]+" src/main/java --include=*Controller.java \
  | sed 's/public record //' | sort | uniq -c | awk '$1>1'
```

### 5.5 `docker compose up -d`는 이미지를 재빌드하지 않는다
`ai-service` 컨테이너가 pgvector 수정 이전 코드로 돌아 AI 질의가 전부 실패했다.
Python을 고쳤으면 **`--build`를 붙여라.**

### 5.6 실행 중인 서버의 `.next`를 다시 빌드하지 마라
`next start`가 도는 중에 `next build`를 돌리면 청크 해시가 어긋나 화면이 검게 뜬다.
서버를 먼저 끄고 빌드하라.

### 5.7 프로세스가 고아로 남는다
`TaskStop`(또는 Ctrl+C)이 Gradle/pnpm 래퍼만 죽이고 Java·Node 자식이 포트를 잡고 있다.
`EADDRINUSE`가 나면 포트 점유 프로세스를 확인하고 정리하라.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen |
  ForEach-Object { Get-CimInstance Win32_Process -Filter "ProcessId=$($_.OwningProcess)" } |
  Select-Object ProcessId, CommandLine
```

### 5.8 일괄 편집 스크립트는 결과를 확인하라
정규식 역참조가 깨져 컨트롤러 5개의 import가 손상된 적이 있고,
앵커 문자열이 안 맞아 메서드 추가가 **조용히 실패**했는데 컴파일은 통과한 적이 있다.
치환 후 반드시 `grep`으로 결과를 확인하라.

---

## 6. 알려진 한계 (의도적으로 남긴 것)

- **평가금액·손익 미표시** — 서버에 산출 API가 없다. 프론트에서 곱하지 않는다는 원칙이
  우선이라 비워뒀다. 서버에 API가 생기면 `/portfolio`에 붙인다.
- **토큰을 localStorage에 저장** — 데모용 SPA의 선택이다. 실서비스라면 httpOnly 쿠키 +
  회전이 맞다. `lib/token-store.ts` 주석에 적어뒀다.
- **호가·체결은 3초 폴링** — WebSocket이 아니다. 간격은 `ORDERBOOK_POLL_MS` 상수이고
  화면에도 표시한다.
- **데모 투자설명서가 영문** — `scripts/seed/prospectus.mjs`가 라이브러리 없이 PDF를
  만드는데 한글은 CID 폰트 임베딩이 필요하다. 한글 PDF를 쓰려면
  `POST /api/v1/issuances/{id}/prospectus`로 진짜 파일을 올리고 재인덱싱하면 된다.
- **AI 가드레일 오탐** — "risk factors"를 묻는 질문이 `INVESTMENT_SOLICITATION`으로
  차단된 사례가 있다. Phase 8 가드레일 튜닝 대상이지 프론트 문제가 아니다.
  화면은 차단 사유를 그대로 보여주도록 만들어져 있다.
- **오픈 API 생성 클라이언트 이름이 지저분** — `/open/**`이 실전·샌드박스 두 경로에
  매핑돼 `token`/`token1`/`token2`처럼 중복 생성된다. dev-portal 작업 시 정리 대상.
- **TypeScript 5.9.3 / ESLint 9.39.5 고정** — TS 7과 ESLint 10이 나와 있으나
  Next 16 + `eslint-config-next` 툴체인에서 검증되지 않았다. 화면이 안정된 뒤 시도하라.

---

## 7. 자주 쓰는 명령

```bash
# 스펙 재생성 → 타입 클라이언트 재생성 (서버 코드를 고쳤으면 항상)
pnpm spec:export && pnpm api:generate

# 전체 검증
./gradlew test && pnpm -r typecheck && pnpm -r lint && pnpm --filter investor-web build

# 디자인 토큰 대비비 (토큰을 바꿨으면)
pnpm design:contrast
```

`pnpm api:generate`는 생성물의 `FetchError.cause`에 `override`를 붙이는 보정 단계를
포함한다(`packages/api-client/scripts/prune-generated.mjs`). 생성기가 바뀌어 패턴을 못 찾으면
스크립트가 예외를 던진다 — 조용히 넘어가지 않는다.
