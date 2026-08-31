# WireMock 스텁 출처

phase-05 §6-7: **"문서를 추측해서 WireMock 스텁 작성 금지 — 반드시 실제 응답을 캡처"**

| 파일 | 출처 |
|---|---|
| `currentPrice-200.json` | 실제 호출 캡처 (`POST https://moapi.nhplug.com:8443/krstock/quote/v1/currentPrice`, 005930, 2026-08-31) |
| `period-200.json` | 실제 호출 캡처 (`POST .../krstock/quote/v1/period`, 005930 일봉 5건, 2026-08-31) |
| `token-200.json` | 실제 발급 응답의 **형태만** 옮김 (필드·타입 동일, 토큰 값은 더미) |
| `error-IGW*.json` | 포털 오류코드 표(`docs/reference/plug-error-codes.md`)의 코드·메시지 그대로 |

캡처 도구: `scripts/plug/capture.ps1`. 원본 캡처는 `scripts/plug/captured/`(gitignore — 계좌번호 포함).
