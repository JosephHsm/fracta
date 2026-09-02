"use client";

import type {
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

export function useMyOrders() {
  const { client, token } = useSession();
  return useQuery({
    queryKey: ["my-orders"],
    enabled: Boolean(token),
    queryFn: async () => (await client.trading.listMyOrders()).data ?? [],
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
