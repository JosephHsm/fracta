"use client";

import { PremiumBadge, Skeleton } from "@fracta/ui";

import { useExecutions } from "@/lib/queries";

/**
 * 괴리율 배지 (TR-08) — 이 프로젝트의 얼굴.
 *
 * <p>웹앱 API에는 괴리율 전용 엔드포인트가 없다. 대신 <b>최근 체결의 premiumRate</b>를 쓴다.
 * 체결 시점에 서버가 계산해 저장한 값이라, 지금 시세로 다시 계산하는 것보다 오히려 정확하다 —
 * TR-08의 거래중단 판정도 같은 값으로 내려졌다.
 *
 * <p>체결이 없으면 괴리율이 존재하지 않는다. 0%로 꾸미지 않고 "—"로 둔다.
 */
export function TokenPremium({
  tokenSymbol,
  suspended = false,
}: {
  tokenSymbol: string;
  suspended?: boolean;
}) {
  const { data, isLoading } = useExecutions(tokenSymbol, 1);

  if (isLoading) return <Skeleton className="h-6 w-28" />;

  const latest = data?.[0];
  return <PremiumBadge premiumRate={latest?.premiumRate as number | undefined} suspended={suspended} />;
}
