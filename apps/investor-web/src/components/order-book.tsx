"use client";

import type { OrderBookLevel } from "@fracta/api-client";
import { Card, CardBody, CardHeader, CardTitle, Skeleton, cn, formatNumber } from "@fracta/ui";
import * as React from "react";

import { ORDERBOOK_POLL_MS, useOrderBook } from "@/lib/queries";

/**
 * 호가창 10호가 (TR-05).
 *
 * <p>호가를 클릭하면 주문 폼 가격이 채워진다 (FSD §11.2 필수 UX).
 * 매도(위)는 하락 청색, 매수(아래)는 상승 적색 — 국내 HTS 관행 그대로다.
 * 색만으로 구분되지 않게 "매도/매수" 헤더를 함께 둔다.
 *
 * <p>WebSocket이 아니라 폴링이다. 간격은 {@link ORDERBOOK_POLL_MS}에 상수로 두고
 * 화면에도 표시한다 — phase-10 흔한 실수 6.
 */
export function OrderBook({
  tokenSymbol,
  onPickPrice,
}: {
  tokenSymbol: string;
  onPickPrice?: (price: number) => void;
}) {
  const { data, isLoading } = useOrderBook(tokenSymbol);

  const asks = React.useMemo(() => [...(data?.asks ?? [])].reverse(), [data?.asks]);
  const bids = React.useMemo(() => data?.bids ?? [], [data?.bids]);
  const maxUnits = React.useMemo(
    () => Math.max(1, ...[...asks, ...bids].map((level) => level.units ?? 0)),
    [asks, bids],
  );

  return (
    <Card className="h-full">
      <CardHeader className="flex-row items-baseline justify-between">
        <CardTitle>호가</CardTitle>
        <span className="text-fg-subtle text-xs">{ORDERBOOK_POLL_MS / 1000}초마다 갱신</span>
      </CardHeader>
      <CardBody className="pt-3">
        {isLoading ? (
          <div className="flex flex-col gap-1.5">
            {Array.from({ length: 8 }, (_, index) => (
              <Skeleton key={index} className="h-6 w-full" />
            ))}
          </div>
        ) : asks.length === 0 && bids.length === 0 ? (
          <p className="text-fg-muted py-8 text-center text-sm">등록된 호가가 없습니다</p>
        ) : (
          <div className="flex flex-col gap-0.5">
            <Side
              label="매도"
              levels={asks}
              maxUnits={maxUnits}
              tone="down"
              onPickPrice={onPickPrice}
            />
            <div className="border-border my-1.5 border-t" />
            <Side
              label="매수"
              levels={bids}
              maxUnits={maxUnits}
              tone="up"
              onPickPrice={onPickPrice}
            />
          </div>
        )}
      </CardBody>
    </Card>
  );
}

function Side({
  label,
  levels,
  maxUnits,
  tone,
  onPickPrice,
}: {
  label: string;
  levels: OrderBookLevel[];
  maxUnits: number;
  tone: "up" | "down";
  onPickPrice?: (price: number) => void;
}) {
  if (levels.length === 0) {
    return <p className="text-fg-subtle px-2 py-3 text-center text-xs">{label} 호가 없음</p>;
  }

  return (
    <div role="list" aria-label={`${label} 호가`} className="flex flex-col gap-0.5">
      {levels.map((level) => {
        const price = level.price ?? 0;
        const units = level.units ?? 0;
        const width = Math.max(2, (units / maxUnits) * 100);

        return (
          <button
            key={`${label}-${price}`}
            type="button"
            role="listitem"
            onClick={() => onPickPrice?.(price)}
            aria-label={`${label} ${formatNumber(price)}원 ${formatNumber(units)}조각, 클릭하면 주문 가격에 입력됩니다`}
            className="hover:bg-surface-sunken relative flex items-center justify-between rounded px-2 py-1 text-sm transition-colors duration-(--fr-motion-hover)"
          >
            {/* 잔량 막대는 배경으로 깔린다 — 숫자를 가리지 않는다 */}
            <span
              aria-hidden
              className={cn(
                "absolute inset-y-0 right-0 rounded",
                tone === "up" ? "bg-price-up-soft" : "bg-price-down-soft",
              )}
              style={{ width: `${width}%` }}
            />
            <span
              className={cn(
                "fr-numeric relative font-medium",
                tone === "up" ? "text-price-up" : "text-price-down",
              )}
            >
              {formatNumber(price)}
            </span>
            <span className="fr-numeric text-fg-muted relative text-xs">
              {formatNumber(units)}
            </span>
          </button>
        );
      })}
    </div>
  );
}
