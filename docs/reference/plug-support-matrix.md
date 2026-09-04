# namuh PLUG — 도메인별 지원 범위 실측

> 측정일 **2026-09-03** · 재현 스크립트 `scripts/plug/support-matrix.ps1`
> **모든 값은 실제 호출 결과다.** 공식 문서에는 이 표가 없다.

## 요약

| | 모의 `moapi` | 실전 `api` | 비고 |
|---|---|---|---|
| 토큰 발급 (`/oauth2/token`) | — (발급은 실전 전용, 발급분은 양쪽 사용) | OK |
| 계좌 조회 (`/n2/acctinfo`) | **OK** | OK | *(FRACTA는 쓰지 않는다 — 아래 참고)* |
| **REST** 시세 — 국내 11종 | **전부 차단 `IGW40023`** | 전부 OK |
| **REST** 시세 — 해외 `gbstock` | **차단 `IGW40023`** | OK |
| **WebSocket** 시세 | **연결됨** `wss://moapi:17070/websocket` | 연결됨 `:7070`(국내)·`:7080`(해외) |

> **계좌 조회는 지원되지만 부르지 않는다.** 화이트리스트(`PlugPathPolicy`)에서 뺐다 —
> 이 표는 증권사가 무엇을 지원하는지의 기록이고, 우리가 무엇을 부르는지와는 별개다.
> 쓰지 않는 경로를 방어선에 열어 두면 "시세만 조회한다"는 보증이 그만큼 약해진다.

> **정정** — 처음에 "모의는 시세 전면 차단"이라고 적었는데 과장이었다.
> 정확히는 **REST 시세만 차단**이고 WebSocket 실시간은 살아 있다.
> 다만 WS는 핸드셰이크 성립까지만 확인했고, 구독 후 데이터 수신은 측정하지 않았다.

```
IGW40023  "모의투자에서는 제공하지 않는 API입니다. 실전투자 환경을 이용해주세요."
```

## 시세 엔드포인트 상세

| 엔드포인트 | 용도 | 모의 | 실전 |
|---|---|---|---|
| `/krstock/quote/v1/currentPrice` | 현재가 + 10호가 | ✗ IGW40023 | OK |
| `/krstock/quote/v1/currentDaily` | 일별 시세 | ✗ IGW40023 | OK |
| `/krstock/quote/v1/period` | 기간 시세 (차트) | ✗ IGW40023 | OK |
| `/krstock/quote/v1/currentExecution` | 체결 내역 | ✗ IGW40023 | OK |
| `/krstock/quote/v1/currentInvestor` | 투자자별 매매 | ✗ IGW40023 | OK |
| `/krstock/quote/v1/etfCurrent` | **ETF 현재가 + NAV·괴리율** | ✗ IGW40023 | OK |
| `/krstock/quote/v1/etfComponents` | ETF 구성종목 | ✗ IGW40023 | OK |
| `/krstock/quote/v1/afterHoursCurrent` | 시간외 현재가 | ✗ IGW40023 | OK |
| `/krstock/quote/v1/currentAfterHoursDaily` | 시간외 일별 | ✗ IGW40023 | OK |
| `/krstock/quote/v1/currentAfterHoursExecution` | 시간외 체결 | ✗ IGW40023 | OK |
| `/krstock/quote/v1/afterHoursExpected` | 시간외 예상체결 | ✗ IGW40023 | OK |

해외주식(`/gbstock/quote/v1/current`)도 같은 방식으로 막힌다 — 모의 `IGW40023` / 실전 OK.
즉 차단은 국내·해외를 가리지 않고 **REST 시세 계열 전반**이다.

주문(`/krstock/order/**`)과 잔고(`/krstock/inquiry/**`) 계열은 **측정하지 않았다.**
이 프로젝트는 증권사에 주문을 보내지 않고, 잔고는 자체 원장이 단일 진실이다.

## 8월 31일에는 됐다

`scripts/plug/captured/` 의 `currentPrice`·`currentDaily`·`period` 응답 캡처가 증거다.
파일 타임스탬프는 2026-08-31이고, 당시 `capture.ps1` 기본값은 `-Env mock`이었다.
같은 날 유량 실측(`docs/benchmarks/broker-quota.md`)도 모의 도메인에서 수행했다.

즉 **8월 31일 → 9월 3일 사이에 NH가 모의 도메인의 시세 API를 닫았다.**
공식 SDK README(`github.com/PLUG-OpenAPI/nhplug-sdk`)에도 이 제약은 적혀 있지 않다.
문서가 말하는 건 "개발·교육·시뮬레이션은 moapi로 전환하세요"까지다.

## 그래서 왜 실전 REST를 쓰는가

모의 WS가 살아 있다면 모의만으로 갈 수도 있다. 그런데 이 프로젝트가 필요한 것 중
**WS로 대체되지 않는 게 둘** 있다.

| 필요한 것 | WS로 되나 | 비고 |
|---|---|---|
| 현재가 (괴리율 계산) | **된다** | 실시간 체결 스트림 |
| 일봉 (기초자산 차트) | **안 된다** | `period`는 REST 전용 |
| ETF NAV·괴리율(`dprt`) | **안 된다** | `etfCurrent` REST 전용 |

ETF의 NAV·`dprt`는 이 프로젝트에서 **괴리율 엔진의 정답지** 역할을 한다.
그게 없으면 우리 계산이 맞는지 증명할 방법이 없다. 그래서 실전 REST를 쓴다.
대신 나가는 것은 조회뿐이며, 그 보장은 도메인이 아니라 경로 정책이 한다.

## 이 발견이 잡아낸 버그

폴백 판정이 `IGW40401`(제공하지 않는 API URI)만 미지원 신호로 보고 있었다.
실제로 오는 코드는 `IGW40023`이라 **Mock 폴백이 발동하지 않고 조회가 예외로 떨어졌다.**
두 코드가 같은 상황을 다르게 말한다. `PlugErrorCodes`에서 둘 다 인식하도록 고쳤고,
실제 응답을 그대로 재현한 WireMock 스텁으로 회귀 테스트를 걸었다.

## ETF 응답의 NAV 블록 — `Output_3`

스펙에는 NAV·괴리율이 있는데 `Output_0`만 보면 안 보인다. 블록이 5개로 나뉜다.

| 블록 | 내용 |
|---|---|
| `Output_0` | 현재가·10호가·거래원 등 131개 항목 |
| `Output_1` | 최근 체결 30건 |
| `Output_2` | 예상체결 |
| **`Output_3`** | **NAV·괴리율·추적오차·LP 의무호가 잔량** |
| `Output_4` | 기초지수 (코스피 200 등) |

`Output_3` 실측값 (069500 KODEX 200, 2026-09-03 13:02):

```
itmt_last_nav  105,291.01   장중 NAV
prdy_last_nav  103,712.16   전일 NAV
dprt                  0.2   괴리율 (%)
trc_errt             0.38   추적오차율 (%)
totvalue          241,027   순자산총액 (억원)
cnfg_cnt              202   구성종목 수
lp_askp_rsqn1..10           LP(유동성공급자) 매도 의무호가 잔량
lp_bidp_rsqn1..10           LP 매수 의무호가 잔량
clon_cls_code   실물(패시브)
```

**이 프로젝트의 괴리율(TR-07/TR-08)이 임의 개념이 아니라는 근거다.**
증권사 API가 `dprt`를 표준 필드로 내려주고, 괴리를 좁히는 장치인 LP 의무호가까지 함께 준다.
FRACTA는 같은 지표를 조각증권에 적용한다 — 유동성이 얕아 괴리가 더 크게 벌어지는 자산이다.

## 검증된 상장 리츠 종목코드

시드에 쓸 후보. **기억이 아니라 실전 도메인 조회로 확인한 값이다** (2026-09-03 13:02 기준가).

| 코드 | 종목명 | 당시가 | 비고 |
|---|---|---|---|
| 365550 | ESR켄달스퀘어리츠 | 3,100원 | 물류 전문. PBR 0.68 |
| 293940 | 신한알파리츠 | 5,390원 | |
| 330590 | 롯데리츠 | 3,915원 | |
| 357120 | 코람코라이프인프라리츠 | 4,165원 | |
| 448730 | 삼성FN리츠 | 5,180원 | |
| 088260 | 이리츠코크렙 | 3,180원 | |
| 350520 | 이지스레지던스리츠 | 3,395원 | |
| 400760 | NH올원리츠 | 2,780원 | |

`currentPrice` 응답에 `scrt_grp_isnm="리츠"`, `bstp_kor_isnm="코스피 부동산"`이 실려 온다.

## 공식 자료

| 자료 | 위치 |
|---|---|
| 포털 | https://www.nhplug.com |
| 공식 GitHub | https://github.com/PLUG-OpenAPI |
| 파이썬 SDK | `PLUG-OpenAPI/nhplug-sdk` — 샘플·종목마스터 파서 |
| MCP 서버 | `PLUG-OpenAPI/nhplug-mcp` — 주문 도구는 기본 비활성 |
| OpenAPI 스펙 | `scripts/plug/spec/` (krstock 31개 · common 2개) |

**조각증권·토큰증권 API는 없다.** 스펙 전체에서 `조각증권`·`토큰증권` 언급 0건,
`STO` 매치는 전부 `STOP`(주문유형)이었다. 소수점 주식도 없다 —
`소수점` 언급 3건은 해외주식 *주문단가의 소수 자릿수*다.
제공 도메인은 `krstock`·`krbond`·`krfuture`·`krgold`·`gbstock`·`gbfuture` 6개로,
전부 전통 상장상품이다.
