"use client";

import type {
  CandlesResponse,
  InstrumentDetail,
  ExecutionResponse,
  IssuanceSummaryResponse,
  ListIssuancesStatusEnum,
  MeResponse,
  OrderBookResponse,
} from "@fracta/api-client";
import { useQuery } from "@tanstack/react-query";

import { useSession } from "./session";

/**
 * 조회 훅 모음.
 *
 * <p>모든 응답은 생성된 타입을 그대로 쓴다 — 화면에서 손으로 타입을 적지 않는다.
 * 서버 필드가 바뀌면 `pnpm spec:export && pnpm api:generate` 후 여기서 컴파일이 깨진다.
 */

/** 발행 목록. status를 주지 않으면 전체를 받는다. */
export function useIssuances(statuses?: ListIssuancesStatusEnum[]) {
  const { client, token } = useSession();
  return useQuery<IssuanceSummaryResponse[]>({
    queryKey: ["issuances", statuses ?? "all"],
    enabled: Boolean(token),
    queryFn: async () => {
      const response = await client.issuance.listIssuances({ status: statuses });
      return response.data ?? [];
    },
  });
}

export function useMe() {
  const { client, token } = useSession();
  return useQuery<MeResponse>({
    queryKey: ["me"],
    enabled: Boolean(token),
    queryFn: async () => (await client.investor.me()).data ?? {},
  });
}

export function useIssuance(issuanceId: number) {
  const { client, token } = useSession();
  return useQuery({
    queryKey: ["issuance", issuanceId],
    enabled: Boolean(token) && Number.isFinite(issuanceId),
    queryFn: async () => (await client.issuance.getIssuance({ id: issuanceId })).data ?? {},
  });
}

/**
 * 호가창. 실시간처럼 보이게 하되 폴링 간격을 명시한다 —
 * phase-10 흔한 실수 6: 폴링이면 간격을 문서에 남길 것.
 */
export const ORDERBOOK_POLL_MS = 3_000;

export function useOrderBook(tokenSymbol: string) {
  const { client, token } = useSession();
  return useQuery<OrderBookResponse>({
    queryKey: ["orderbook", tokenSymbol],
    enabled: Boolean(token) && Boolean(tokenSymbol),
    refetchInterval: ORDERBOOK_POLL_MS,
    staleTime: 0,
    queryFn: async () =>
      (await client.trading.orderBook({ tokenSymbol, levels: 10 })).data ?? {},
  });
}

export function useExecutions(tokenSymbol: string, size = 20) {
  const { client, token } = useSession();
  return useQuery<ExecutionResponse[]>({
    queryKey: ["executions", tokenSymbol, size],
    enabled: Boolean(token) && Boolean(tokenSymbol),
    refetchInterval: ORDERBOOK_POLL_MS,
    staleTime: 0,
    queryFn: async () =>
      (await client.trading.listExecutions({ tokenSymbol, page: 0, size })).data ?? [],
  });
}

/**
 * 기초자산 시세 차트용 일봉. 값은 서버가 이미 조각 참조가로 환산해서 준다 —
 * 여기서 분할비율로 나누지 않는다(금액 재계산 금지).
 *
 * <p>호가처럼 자주 바뀌지 않으므로 폴링하지 않는다. 일봉은 하루 단위다.
 */
export function useCandles(tokenSymbol: string, days = 90) {
  const { client, token } = useSession();
  return useQuery<CandlesResponse>({
    queryKey: ["candles", tokenSymbol, days],
    enabled: Boolean(token) && Boolean(tokenSymbol),
    staleTime: 5 * 60 * 1000,
    queryFn: async () =>
      (await client.trading.listCandles({ tokenSymbol, days })).data ?? {
        tokenSymbol,
        candles: [],
      },
  });
}

/**
 * 기초자산 상세 — ETF면 증권사가 계산한 NAV·괴리율이 함께 온다.
 *
 * <p>이게 있어야 **이중 괴리율**을 보여줄 수 있다. 증권사 괴리율은 유동성공급자(LP)가
 * 좁혀준 결과고, 우리 조각 괴리율은 LP가 없는 시장의 결과다. 두 숫자를 나란히 놓으면
 * 유동성이 얕다는 게 말이 아니라 데이터로 보인다.
 */
export function useInstrument(code: string | null | undefined, splitRatio: number) {
  const { client, token } = useSession();
  return useQuery<InstrumentDetail | null>({
    queryKey: ["instrument", code, splitRatio],
    enabled: Boolean(token) && Boolean(code),
    staleTime: 60 * 1000,
    queryFn: async () =>
      (await client.instrument.getInstrument({ code: code as string, splitRatio })).data ?? null,
  });
}

export function useMyOrders() {
  const { client, token } = useSession();
  return useQuery({
    queryKey: ["my-orders"],
    enabled: Boolean(token),
    queryFn: async () => (await client.trading.listMyOrders()).data ?? [],
  });
}

/**
 * 내 보유 포지션 — 평가금액·손익 포함.
 *
 * <p>금액은 전부 서버가 산출한 값이다. 화면에서 보유 수량 × 현재가를 곱하지 않는다
 * (FSD §11.2 금액 재계산 금지). 기준가 출처는 `priceSource`로 함께 내려온다.
 */
/**
 * 거래소 장 상태. 화면이 "지금 어느 시장이 도는지"를 항상 말할 수 있어야 한다.
 *
 * <p>장중에는 자주, 마감이면 드물게 갱신한다 — 닫힌 시장을 계속 물어볼 이유가 없다.
 */
export function useMarketSessions() {
  const { client, token } = useSession();
  return useQuery({
    queryKey: ["market-sessions"],
    enabled: Boolean(token),
    queryFn: async () => (await client.instrument.getMarketSessions()).data ?? [],
    refetchInterval: (query) =>
      (query.state.data ?? []).some((s) => s.state === "OPEN") ? 10_000 : 60_000,
  });
}

export function useMyPositions() {
  const { client, token } = useSession();
  return useQuery({
    queryKey: ["my-positions"],
    enabled: Boolean(token),
    queryFn: async () => (await client.trading.listMyPositions()).data ?? [],
  });
}

export function useMySubscriptions() {
  const { client, token } = useSession();
  return useQuery({
    queryKey: ["my-subscriptions"],
    enabled: Boolean(token),
    queryFn: async () => (await client.subscription.listMySubscriptions()).data ?? [],
  });
}
