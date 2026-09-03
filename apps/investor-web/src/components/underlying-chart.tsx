"use client";

import { CandlestickSeries, createChart, type IChartApi, type ISeriesApi } from "lightweight-charts";
import { useEffect, useRef, useState } from "react";

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
/**
 * CSS 색을 캔버스가 확실히 파싱하는 rgb() 문자열로 바꾼다.
 *
 * <p><b>왜 필요한가</b> — 디자인 토큰은 OKLCH인데, Chrome의 getComputedStyle은 이걸
 * `lab(44.5676% .894219 3.72025)`로 돌려준다. lightweight-charts는 자체 색 파서를 쓰고
 * 그 파서가 lab()을 모른다 → `Failed to parse color`로 차트 생성이 통째로 실패한다.
 * (실제로 그랬다. 예외가 페이지 전체를 내렸다.)
 *
 * <p>브라우저에 1×1로 칠하게 하고 픽셀을 읽는다. 브라우저가 그릴 수 있는 색이면 무조건 통한다.
 * 파싱에 실패하면 fillStyle이 직전 값으로 남으므로, 감시색을 먼저 깔아 실패를 구분한다.
 */
function toRgb(value: string, fallback: string): string {
  if (!value) return fallback;
  try {
    const canvas = document.createElement("canvas");
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    if (!ctx) return fallback;

    // 파싱 실패를 감지하기 위한 감시색. 실패하면 fillStyle이 이 값으로 남는다.
    const SENTINEL = "#010203";
    ctx.fillStyle = SENTINEL;
    ctx.fillStyle = value;
    if (ctx.fillStyle === SENTINEL) return fallback;

    ctx.fillRect(0, 0, 1, 1);
    const pixel = ctx.getImageData(0, 0, 1, 1).data;
    const [r, g, b, a] = [pixel[0] ?? 0, pixel[1] ?? 0, pixel[2] ?? 0, pixel[3] ?? 255];
    return a === 255 ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${a / 255})`;
  } catch {
    return fallback;
  }
}

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
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || candles.length === 0) return;

    const token = (name: string, fallback: string) =>
      toRgb(getComputedStyle(container).getPropertyValue(name).trim(), fallback);

    const up = token("--fr-price-up", "rgb(190, 60, 45)");
    const down = token("--fr-price-down", "rgb(45, 90, 190)");

    let chart: IChartApi;
    try {
      chart = createChart(container, {
      autoSize: true,
      layout: {
        background: { color: "transparent" },
        textColor: token("--fr-text-muted", "rgb(120, 118, 112)"),
        fontFamily: getComputedStyle(document.body).fontFamily,
      },
      grid: {
        vertLines: { visible: false },
        horzLines: { color: token("--fr-border", "rgb(228, 226, 222)") },
      },
      rightPriceScale: { borderColor: token("--fr-border", "rgb(228, 226, 222)") },
      timeScale: { borderColor: token("--fr-border", "rgb(228, 226, 222)"), fixLeftEdge: true, fixRightEdge: true },
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
    } catch (error) {
      // 차트 라이브러리 하나가 종목 상세 화면 전체를 내려서는 안 된다.
      // 실제로 그랬다 — 색 파싱 실패 예외가 페이지를 통째로 죽였다.
      console.error("[UnderlyingChart] 차트를 그리지 못했습니다", error);
      setFailed(true);
      return;
    }

    return () => {
      chartRef.current?.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, [candles]);

  if (candles.length === 0 || failed) {
    return (
      <div className="flex h-64 items-center justify-center rounded-lg border border-border text-fg-muted text-sm">
        {emptyLabel}
      </div>
    );
  }

  return <div ref={containerRef} className="h-64 w-full" aria-label="기초자산 시세 차트" role="img" />;
}
