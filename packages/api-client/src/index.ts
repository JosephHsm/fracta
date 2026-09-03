/**
 * FRACTA 타입 클라이언트.
 *
 * <p>`src/generated`는 `docs/openapi/fracta-openapi.json`에서 **자동 생성**한다. 손대지 않는다.
 * 재생성: `pnpm spec:export` (Spring이 스펙을 다시 씀) → `pnpm api:generate`.
 * 이 파일은 생성 결과 위에 인증·에러 변환만 얇게 얹는다.
 */
import {
  AdminAiControllerApi,
  AdminIssuanceControllerApi,
  AdminSubscriptionControllerApi,
  AiControllerApi,
  AssetControllerApi,
  AuthControllerApi,
  CashControllerApi,
  DefaultApi,
  InstrumentControllerApi,
  InvestorControllerApi,
  IssuanceControllerApi,
  SubscriptionControllerApi,
  TradingControllerApi,
} from "./generated/src/apis/index";
import { Configuration, type Middleware } from "./generated/src/runtime";
import { toFractaApiError } from "./errors";

export * from "./generated/src/models/index";
/**
 * 쿼리 파라미터 enum은 models가 아니라 apis 쪽에 생성된다. `export *`로 통째로 내보내면
 * 요청 파라미터 인터페이스가 모델과 이름이 겹치므로(PlaceOrderRequest 등) 필요한 것만 짚어 내보낸다.
 */
export { ListIssuancesStatusEnum } from "./generated/src/apis/IssuanceControllerApi";
export * from "./errors";
export { Configuration } from "./generated/src/runtime";

export interface FractaClientOptions {
  /** 서버 주소. 브라우저에서는 보통 프록시 경유 상대 경로("")를 쓴다. */
  basePath?: string;
  /** 매 요청 직전에 호출된다 — 토큰 갱신 후에도 같은 클라이언트를 계속 쓸 수 있다. */
  getAccessToken?: () => string | undefined | Promise<string | undefined>;
  fetchApi?: typeof fetch;
}

/** 실패 응답을 FractaApiError로 바꾼다. 성공 응답은 그대로 통과시킨다. */
const errorMiddleware: Middleware = {
  async post({ response }) {
    if (!response.ok) {
      throw await toFractaApiError(response);
    }
    return response;
  },
};

export function createFractaClient(options: FractaClientOptions = {}) {
  const configuration = new Configuration({
    basePath: options.basePath ?? "",
    fetchApi: options.fetchApi,
    middleware: [errorMiddleware],
    accessToken: options.getAccessToken
      ? async () => (await options.getAccessToken?.()) ?? ""
      : undefined,
  });

  return {
    configuration,
    auth: new AuthControllerApi(configuration),
    investor: new InvestorControllerApi(configuration),
    cash: new CashControllerApi(configuration),
    issuance: new IssuanceControllerApi(configuration),
    asset: new AssetControllerApi(configuration),
    subscription: new SubscriptionControllerApi(configuration),
    trading: new TradingControllerApi(configuration),
    /** 기초자산 탐색 — 이름·코드 검색과 조각 참조가. 시세는 상세 조회에서만 부른다 */
    instrument: new InstrumentControllerApi(configuration),
    ai: new AiControllerApi(configuration),
    adminIssuance: new AdminIssuanceControllerApi(configuration),
    adminSubscription: new AdminSubscriptionControllerApi(configuration),
    adminAi: new AdminAiControllerApi(configuration),
    /** 웹앱 JWT로 접근하는 개발자 포털 관리 API. */
    developer: new DefaultApi(configuration),
    /** 태그가 없는 공개 API(/open/**) — dev-portal 샌드박스가 쓴다 */
    open: new DefaultApi(configuration),
  };
}

export type FractaClient = ReturnType<typeof createFractaClient>;
