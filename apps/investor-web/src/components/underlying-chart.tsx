"use client";

import { CandlestickSeries, createChart, type IChartApi, type ISeriesApi } from "lightweight-charts";
import { useEffect, useRef } from "react";

import type { CandleResponse } from "@fracta/api-client";

/**
 * 기초자산 시세 차트 (FSD §11 종목 상세 필수 요소).
 *
 * <p>값은 서버가 이미 조각 참조가로 환산해 준다. 여기서 분할비율로 나누지 않는다 —
 * 프론트 금액 재계산 금지(FSD §11.4). 반올림이 서버와 갈리면 차트와 호가창 기준선이 어긋난다.
 *
 * <p>색은 토큰에서 읽는다. 리터럴을 쓰면 다크모드에서 대비가 무너지고, 등락 색 규칙
 * (상승 적색 / 하락 청색 — 국내 관행)이 두 곳에 흩어진다.
 */
export function UnderlyingChart({
  candles,
  emptyLabel = "시세를 불러오지 못했습니다.",
}: {
  candles: CandleResponse[];
  emptyLabel?: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || candles.length === 0) return;

    const token = (name: string) =>
      getComputedStyle(container).getPropertyValue(name).trim();

    const up = token("--fr-price-up");
    const down = token("--fr-price-down");

    const chart = createChart(container, {
      autoSize: true,
      layout: {
        background: { color: "transparent" },
        textColor: token("--fr-text-muted"),
        fontFamily: getComputedStyle(document.body).fontFamily,
      },
      grid: {
        vertLines: { visible: false },
        horzLines: { color: token("--fr-border") },
      },
      rightPriceScale: { borderColor: token("--fr-border") },
      timeScale: { borderColor: token("--fr-border"), fixLeftEdge: true, fixRightEdge: true },
      crosshair: { horzLine: { labelBackgroundColor: down }, vertLine: { labelBackgroundColor: down } },
      localization: {
        locale: "ko-KR",
        priceFormatter: (value: number) => `${Math.round(value).toLocaleString("ko-KR")}원`,
      },
    });

    // 국내 관행 — 상승이 적색, 하락이 청색이다. 뒤집으면 반대로 읽힌다.
    const series = chart.addSeries(CandlestickSeries, {
      upColor: up,
      downColor: down,
      borderUpColor: up,
      borderDownColor: down,
      wickUpColor: up,
      wickDownColor: down,
    });

    series.setData(
      candles.map((candle) => ({
        time: candle.date as string,
        open: candle.open,
        high: candle.high,
        low: candle.low,
        close: candle.close,
      })),
    );
    chart.timeScale().fitContent();

    chartRef.current = chart;
    seriesRef.current = series;
    return () => {
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, [candles]);

  if (candles.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center rounded-lg border border-border text-fg-muted text-sm">
        {emptyLabel}
      </div>
    );
  }

  return <div ref={containerRef} className="h-64 w-full" aria-label="기초자산 시세 차트" role="img" />;
}
