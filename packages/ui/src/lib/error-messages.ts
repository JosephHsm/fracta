/**
 * 에러 코드 → 사용자 문구 매핑표 (FSD §7.1, phase-10 완료 조건).
 *
 * <p>원시 코드는 화면에 절대 노출하지 않는다. 매핑이 없는 코드가 오면 일반 문구로 떨어뜨리되,
 * 개발자가 추적할 수 있게 `requestId`만 함께 보여준다.
 *
 * <p>문구는 **다음 행동**을 알려주는 쪽으로 쓴다. "잔액이 부족합니다"보다
 * "예수금이 부족하다. 입금 후 다시 시도해라"가 낫다.
 */
export const ERROR_MESSAGES: Record<string, string> = {
  // 인증 — 웹앱
  AUTH_UNAUTHORIZED: "로그인이 필요하다.",
  AUTH_INVALID_CREDENTIALS: "이메일 또는 비밀번호가 맞지 않는다.",
  AUTH_FORBIDDEN: "이 작업을 수행할 권한이 없다.",
  // 인증 — 오픈 API (dev-portal)
  AUTH_INVALID_CLIENT: "클라이언트 인증에 실패했다. client_id와 client_secret을 확인해라.",
  AUTH_INVALID_TOKEN: "액세스 토큰이 만료됐거나 유효하지 않다. 토큰을 다시 발급해라.",
  AUTH_SCOPE_DENIED: "이 API에 필요한 Scope가 앱에 없다. 앱 설정에서 Scope를 추가해라.",
  AUTH_ENV_MISMATCH: "샌드박스 콘솔에서는 LIVE 키를 쓸 수 없다. 샌드박스 키로 바꿔라.",

  // 입력 검증
  VALID_INVALID_INPUT: "입력값을 다시 확인해라.",
  VALID_AMOUNT_OVERFLOW: "금액이 처리 가능한 범위를 넘었다. 수량을 줄여라.",
  VALID_UNITS_RANGE: "신청 가능한 수량 범위를 벗어났다.",
  VALID_DUPLICATE_EMAIL: "이미 가입된 이메일이다.",

  // 상태
  STATE_INVALID_TRANSITION: "지금 상태에서는 할 수 없는 작업이다.",
  STATE_NOT_SUBSCRIBING: "청약 기간이 아니다.",
  STATE_NOT_TRADABLE: "거래가 중단된 종목이다. 괴리율이 정상 범위로 돌아오면 재개된다.",
  STATE_NO_LIQUIDITY: "체결 가능한 반대 호가가 없다. 지정가로 주문해라.",
  STATE_LOCK_TIMEOUT: "다른 처리가 진행 중이다. 잠시 후 다시 시도해라.",

  // 잔고
  FUND_INSUFFICIENT_UNITS: "보유 수량이 부족하다. 매도 주문에 이미 잠긴 수량이 있는지 확인해라.",
  FUND_INSUFFICIENT_CASH: "예수금이 부족하다. 입금 후 다시 시도해라.",

  // 적합성 — 전용 UI로 보낸다. 아래 문구는 폴백용이다 (phase-10 흔한 실수 3)
  SUIT_PROFILE_MISMATCH: "투자성향에 비해 위험등급이 높은 상품이다.",
  SUIT_PROFILE_REQUIRED: "투자성향 진단을 먼저 완료해야 한다.",

  // 쿼터·멱등성
  RATE_LIMIT_EXCEEDED: "요청이 한도를 초과했다. 잠시 후 다시 시도해라.",
  IDEM_KEY_REQUIRED: "Idempotency-Key 헤더가 필요하다.",
  IDEM_KEY_CONFLICT: "같은 키로 다른 내용의 요청이 이미 처리됐다.",
  IDEM_IN_PROGRESS: "같은 요청이 처리 중이다. 결과를 기다려라.",

  // 외부 연동
  BROKER_AUTH_FAILED: "증권사 시세 연동에 실패했다. 시세 없이도 주문은 가능하다.",
  BROKER_CALL_FAILED: "증권사 시세를 가져오지 못했다. 잠시 후 다시 시도해라.",
  AI_UNAVAILABLE: "AI 응답을 생성하지 못했다. 잠시 후 다시 시도해라.",

  NOT_FOUND: "요청한 정보를 찾을 수 없다.",
  INTERNAL_ERROR: "일시적인 오류가 발생했다. 잠시 후 다시 시도해라.",
};

const FALLBACK = "요청을 처리하지 못했다. 잠시 후 다시 시도해라.";

/** 코드에 대응하는 사용자 문구. 매핑이 없으면 폴백 문구를 준다 — 코드를 그대로 내보내지 않는다. */
export function errorMessage(code: string | undefined): string {
  return (code && ERROR_MESSAGES[code]) || FALLBACK;
}

/** 매핑표에 없는 코드인지. 개발 중 콘솔 경고나 테스트에서 쓴다. */
export function isMappedErrorCode(code: string): boolean {
  return code in ERROR_MESSAGES;
}
