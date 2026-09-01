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
  InvestorControllerApi,
  IssuanceControllerApi,
  SubscriptionControllerApi,
  TradingControllerApi,
} from "./generated/src/apis/index";
import { Configuration, type Middleware } from "./generated/src/runtime";
import { toFractaApiError } from "./errors";

export * from "./generated/src/models/index";
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
    ai: new AiControllerApi(configuration),
    adminIssuance: new AdminIssuanceControllerApi(configuration),
    adminSubscription: new AdminSubscriptionControllerApi(configuration),
    adminAi: new AdminAiControllerApi(configuration),
    /** 태그가 없는 공개 API(/open/**) — dev-portal 샌드박스가 쓴다 */
    open: new DefaultApi(configuration),
  };
}

export type FractaClient = ReturnType<typeof createFractaClient>;
