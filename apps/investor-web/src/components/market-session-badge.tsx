"use client";

import * as React from "react";

import { useMarketSessions } from "@/lib/queries";

/**
 * 거래소 장 상태 배지.
 *
 * <p>실제 주식 앱처럼 <b>지금 어느 시장이 도는지</b>를 헤더에 항상 띄운다. 값이 멈춰 있을 때
 * 그게 장애인지 장 마감인지 화면이 말해 주지 않으면, 멈춘 숫자를 실시간이라고 믿게 된다.
 *
 * <p>문구는 서버가 만든 것을 그대로 쓴다({@code label}). 개장 판정은 거래소 응답으로 하는데,
 * 화면이 자기 시간표로 문구를 다시 만들면 서버와 어긋난다.
 */
export function MarketSessionBadge() {
  const { data: sessions } = useMarketSessions();
  if (!sessions?.length) return null;

  const open = sessions.filter((s) => s.state === "OPEN");
  // 열린 시장이 있으면 그것만, 없으면 국내장 상태를 대표로 보여준다
  const shown = open.length > 0 ? open : sessions.filter((s) => s.venue === "KRX");

  return (
    <div className="hidden items-center gap-2 md:flex" aria-live="polite">
      {shown.map((session) => (
        <span
          key={session.venue}
          className="border-border text-fg-muted inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs"
          title={session.tradeDate ? `체결일자 ${session.tradeDate}` : undefined}
        >
          <Dot state={session.state} />
          {session.label}
        </span>
      ))}
    </div>
  );
}

/**
 * 상태 점. 색만으로 구분되지 않게 문구가 항상 함께 있다(WCAG 1.4.1).
 * 장중에만 맥동시킨다 — 멈춘 시장에 움직이는 표시를 두면 그게 "실시간인 척"이다.
 */
function Dot({ state }: { state?: string }) {
  const tone =
    state === "OPEN"
      ? "bg-price-up"
      : state === "CLOSED"
        ? "bg-fg-subtle"
        : "bg-warning";
  return (
    <span className="relative flex size-1.5">
      {state === "OPEN" && (
        <span className="bg-price-up absolute inline-flex size-full animate-ping rounded-full opacity-60 motion-reduce:animate-none" />
      )}
      <span className={`relative inline-flex size-1.5 rounded-full ${tone}`} />
    </span>
  );
}
