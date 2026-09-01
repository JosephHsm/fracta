/**
 * 서버 에러 코드 (FSD §7.1). 접두사 체계는 서버 `common/error/ErrorCode`와 1:1이다.
 *
 * <p>이 목록이 UI 문구 매핑표의 키가 된다. 원시 코드는 화면에 노출하지 않는다 —
 * Phase 10 완료 조건 "모든 API 에러 코드가 사용자 문구로 매핑됨(원시 코드 노출 0건)".
 */
export const ERROR_CODES = [
  "AUTH_UNAUTHORIZED",
  "AUTH_INVALID_CREDENTIALS",
  "AUTH_FORBIDDEN",
  "AUTH_INVALID_CLIENT",
  "AUTH_INVALID_TOKEN",
  "AUTH_SCOPE_DENIED",
  "AUTH_ENV_MISMATCH",
  "VALID_INVALID_INPUT",
  "VALID_AMOUNT_OVERFLOW",
  "VALID_UNITS_RANGE",
  "VALID_DUPLICATE_EMAIL",
  "STATE_INVALID_TRANSITION",
  "STATE_NOT_SUBSCRIBING",
  "STATE_NOT_TRADABLE",
  "STATE_NO_LIQUIDITY",
  "STATE_LOCK_TIMEOUT",
  "FUND_INSUFFICIENT_UNITS",
  "FUND_INSUFFICIENT_CASH",
  "SUIT_PROFILE_MISMATCH",
  "SUIT_PROFILE_REQUIRED",
  "RATE_LIMIT_EXCEEDED",
  "IDEM_KEY_CONFLICT",
  "IDEM_KEY_REQUIRED",
  "IDEM_IN_PROGRESS",
  "BROKER_AUTH_FAILED",
  "BROKER_CALL_FAILED",
  "AI_UNAVAILABLE",
  "NOT_FOUND",
  "INTERNAL_ERROR",
] as const;

export type FractaErrorCode = (typeof ERROR_CODES)[number];

/** 서버가 모르는 코드를 새로 내보내도 UI가 죽지 않게 문자열도 허용한다. */
export type ErrorCodeLike = FractaErrorCode | (string & {});

export interface FractaErrorBody {
  code: ErrorCodeLike;
  message: string;
  details?: Record<string, unknown>;
}

/**
 * 실패 응답을 코드까지 살려 던진다. 생성 클라이언트의 `ResponseError`는 본문을 파싱하지 않아
 * 화면이 상태코드밖에 못 본다 — 적합성 차단(403 SUIT_PROFILE_MISMATCH)처럼 전용 UI가 필요한
 * 경우를 일반 토스트로 뭉개지 않으려면 코드가 필요하다.
 */
export class FractaApiError extends Error {
  override readonly name = "FractaApiError";

  constructor(
    readonly status: number,
    readonly code: ErrorCodeLike,
    override readonly message: string,
    readonly details: Record<string, unknown> = {},
    readonly requestId?: string,
  ) {
    super(message);
    Object.setPrototypeOf(this, FractaApiError.prototype);
  }

  is(...codes: ErrorCodeLike[]): boolean {
    return codes.includes(this.code);
  }

  /** 적합성 차단 — 사유 표시 + 확인 서명 동선으로 보내야 하는 경우 (FSD §11.2) */
  get isSuitabilityBlock(): boolean {
    return this.is("SUIT_PROFILE_MISMATCH", "SUIT_PROFILE_REQUIRED");
  }
}

/** 응답 본문에서 에러 코드를 뽑는다. 본문이 없거나 형식이 다르면 상태코드로 대충 채운다. */
export async function toFractaApiError(response: Response): Promise<FractaApiError> {
  const requestId = response.headers.get("X-Request-Id") ?? undefined;

  let body: { error?: Partial<FractaErrorBody> } | undefined;
  try {
    body = (await response.clone().json()) as { error?: Partial<FractaErrorBody> };
  } catch {
    body = undefined;
  }

  const error = body?.error;
  return new FractaApiError(
    response.status,
    error?.code ?? (response.status === 404 ? "NOT_FOUND" : "INTERNAL_ERROR"),
    error?.message ?? `요청이 실패했다 (HTTP ${response.status})`,
    error?.details ?? {},
    requestId,
  );
}
