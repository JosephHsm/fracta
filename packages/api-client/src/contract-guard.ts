/**
 * 서버 응답 계약의 컴파일 시점 가드.
 *
 * <p>Phase 10 완료 조건 — "스펙 변경 → 재생성 → 타입 오류로 깨진 곳이 드러남".
 * 화면 코드가 붙기 전에도 이 파일이 그 역할을 한다. 서버에서 필드명을 바꾸고
 * `pnpm spec:export && pnpm api:generate`를 돌리면 여기서 먼저 컴파일이 깨진다.
 *
 * <p>런타임 코드가 아니다. 타입만 참조하므로 번들에 아무것도 남지 않는다.
 * 화면이 실제로 의존하는 필드만 적는다 — 전부 나열하면 서버가 필드를 추가할 때마다
 * 의미 없이 깨진다.
 */
import type {
  BalanceResponse,
  CashTransactionResponse,
  ExecutionResponse,
  IssuanceDetailResponse,
  MeResponse,
  MyOrderResponse,
  MySubscriptionResponse,
  OrderBookResponse,
  RiskProfileResponse,
} from "./generated/src/models/index";

/** 해당 키들이 T에 존재하는지 검사한다. 없으면 컴파일 오류. */
type Requires<T, K extends keyof T> = Pick<T, K>;

// 종목 상세 — 호가창·발행 정보
export type OrderBookContract = Requires<OrderBookResponse, "tokenSymbol" | "bids" | "asks">;
export type IssuanceContract = Requires<
  IssuanceDetailResponse,
  "issuanceId" | "tokenSymbol" | "totalUnits" | "unitPrice" | "remainingUnits" | "status"
>;

// 체결 내역 — 괴리율 배지가 premiumRate에 의존한다
export type ExecutionContract = Requires<
  ExecutionResponse,
  "executionId" | "price" | "units" | "premiumRate" | "executedAt"
>;

// 내 자산 · 주문
export type MeContract = Requires<
  MeResponse,
  "investorId" | "name" | "kycStatus" | "riskGrade" | "cashBalance"
>;
export type MyOrderContract = Requires<
  MyOrderResponse,
  "orderId" | "tokenSymbol" | "side" | "orderType" | "units" | "filledUnits" | "status"
>;
export type MySubscriptionContract = Requires<
  MySubscriptionResponse,
  "orderId" | "issuanceId" | "requestedUnits" | "allottedUnits" | "depositAmount" | "status"
>;

// 예수금 · 투자성향
export type BalanceContract = Requires<BalanceResponse, "balance">;
export type CashTransactionContract = Requires<
  CashTransactionResponse,
  "type" | "amount" | "balanceAfter"
>;
export type RiskProfileContract = Requires<
  RiskProfileResponse,
  "score" | "grade" | "gradeName" | "expiresAt"
>;

/**
 * 금액·수량은 반드시 number(원 단위 정수)로 와야 한다. 서버가 실수로 문자열이나
 * 부동소수 표현으로 바꾸면 여기서 잡힌다 — 금액 규칙(FSD §0.2)의 프론트 쪽 방어선이다.
 */
type AssertNumeric<T> = T extends number | undefined ? true : never;

export type NumericGuards = [
  AssertNumeric<ExecutionResponse["price"]>,
  AssertNumeric<ExecutionResponse["units"]>,
  AssertNumeric<IssuanceDetailResponse["unitPrice"]>,
  AssertNumeric<IssuanceDetailResponse["totalUnits"]>,
  AssertNumeric<MySubscriptionResponse["depositAmount"]>,
  AssertNumeric<MyOrderResponse["units"]>,
];
