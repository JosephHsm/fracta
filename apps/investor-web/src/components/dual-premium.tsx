"use client";

import { Card, CardBody, CardHeader, CardTitle, PriceText } from "@fracta/ui";
import type * as React from "react";

import type { InstrumentEtfReference } from "@fracta/api-client";

/**
 * 이중 괴리율 — 증권사가 계산한 ETF 괴리율과 우리 조각 괴리율을 나란히 놓는다.
 *
 * <p>이 화면의 요점은 두 숫자의 <b>차이</b>다. ETF는 유동성공급자(LP)가 양방향 의무호가를
 * 대주기 때문에 괴리가 좁게 유지된다. 조각투자 플랫폼에는 그 제도가 없다 —
 * 그래서 같은 기초자산인데도 조각 쪽 괴리가 훨씬 크게 벌어진다.
 *
 * <p>"유동성이 얕으면 가격이 벌어진다"를 말로 설명하는 대신 숫자로 보여준다.
 * LP 의무호가 잔량을 함께 적는 이유도 그것이다 — 좁혀주는 주체가 실재한다는 증거다.
 */
export function DualPremium({
  etf,
  fractionPremium,
}: {
  etf: InstrumentEtfReference;
  fractionPremium: number | null | undefined;
}) {
  const lpTotal = (etf.lpAskUnits ?? 0) + (etf.lpBidUnits ?? 0);

  return (
    <Card>
      <CardHeader className="flex-row items-baseline justify-between">
        <CardTitle>괴리율 비교</CardTitle>
        <span className="text-fg-subtle text-xs">같은 기초자산, 다른 시장</span>
      </CardHeader>
      <CardBody className="flex flex-col gap-4 pt-3">
        <div className="grid grid-cols-2 gap-3">
          <Figure
            label="기초자산 ETF"
            note="유동성공급자 있음"
            value={etf.premiumRate}
          />
          <Figure
            label="FRACTA 조각"
            note="유동성공급자 없음"
            value={fractionPremium}
          />
        </div>

        <dl className="text-fg-muted flex flex-col gap-1.5 text-xs">
          <Row label="NAV (순자산가치)">
            {etf.nav != null ? `${Math.round(etf.nav).toLocaleString("ko-KR")}원` : "—"}
          </Row>
          <Row label="추적오차율">{etf.trackingError != null ? `${etf.trackingError}%` : "—"}</Row>
          <Row label="LP 의무호가 잔량">
            {lpTotal > 0 ? `${lpTotal.toLocaleString("ko-KR")}주` : "—"}
          </Row>
        </dl>

        <p className="text-fg-subtle text-xs leading-relaxed">
          ETF는 유동성공급자가 양방향 호가를 대주기 때문에 괴리가 좁게 유지됩니다.
          조각투자에는 그 제도가 없어 괴리를 좁힐 주체가 없습니다 — FRACTA가 ±20% 초과 시
          거래를 중단하는 이유입니다.
        </p>
      </CardBody>
    </Card>
  );
}

function Figure({
  label,
  note,
  value,
}: {
  label: string;
  note: string;
  value: number | null | undefined;
}) {
  return (
    <div className="border-border rounded-lg border p-3">
      <div className="text-fg-muted text-xs">{label}</div>
      <div className="mt-1 text-lg font-semibold">
        {value == null ? (
          <span className="text-fg-subtle">—</span>
        ) : (
          <PriceText change={value}>{`${value > 0 ? "+" : ""}${value}%`}</PriceText>
        )}
      </div>
      <div className="text-fg-subtle mt-0.5 text-[11px]">{note}</div>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between">
      <dt>{label}</dt>
      <dd className="fr-numeric text-fg">{children}</dd>
    </div>
  );
}
