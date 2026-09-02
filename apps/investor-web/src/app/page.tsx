"use client";

import type { IssuanceSummaryResponse } from "@fracta/api-client";
import {
  Badge,
  BentoGrid,
  BentoItem,
  Card,
  CardBody,
  Money,
  ProgressBar,
  Skeleton,
  StatTile,
  Units,
  formatDDay,
  formatWon,
  viewTransitionName,
} from "@fracta/ui";
import { Coins, TrendingUp, Wallet } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import * as React from "react";

import { AppShell } from "@/components/app-shell";
import { TokenPremium } from "@/components/token-premium";
import { useIssuances, useMe } from "@/lib/queries";

export const RISK_LABEL: Record<number, string> = {
  1: "안정형",
  2: "안정추구형",
  3: "위험중립형",
  4: "적극투자형",
  5: "공격투자형",
};

/** 홈 — 청약 중 / 상장 종목 (FSD §11.2). Bento로 지표와 목록을 함께 배치한다. */
export default function HomePage() {
  const { data: me } = useMe();
  const { data: issuances, isLoading } = useIssuances();

  const subscribing = (issuances ?? []).filter((item) => item.status === "SUBSCRIBING");
  const listed = (issuances ?? []).filter(
    (item) => item.status === "LISTED" || item.status === "SUSPENDED",
  );

  return (
    <AppShell>
      <div className="flex flex-col gap-8">
        <section>
          <h1 className="mb-4 text-xl font-semibold tracking-tight">대시보드</h1>
          <BentoGrid>
            <BentoItem span={4}>
              <StatTile
                label="예수금"
                value={<Money amount={me?.cashBalance as number | undefined} size="xl" />}
                hint="청약·주문에 사용할 수 있는 금액입니다"
                icon={<Wallet aria-hidden className="size-3.5" />}
              />
            </BentoItem>
            <BentoItem span={4}>
              <StatTile
                label="투자성향"
                value={
                  <span className="text-2xl">
                    {me?.riskGrade ? (RISK_LABEL[me.riskGrade] ?? "미진단") : "미진단"}
                  </span>
                }
                hint={me?.riskGrade ? `${me.riskGrade}등급` : "진단 후 청약할 수 있습니다"}
                icon={<TrendingUp aria-hidden className="size-3.5" />}
              />
            </BentoItem>
            <BentoItem span={4}>
              <StatTile
                label="청약 중인 종목"
                value={<span className="text-3xl">{subscribing.length}</span>}
                hint={`상장 종목 ${listed.length}개`}
                icon={<Coins aria-hidden className="size-3.5" />}
              />
            </BentoItem>
          </BentoGrid>
        </section>

        <Section
          title="청약 중"
          description="마감 전까지 신청할 수 있습니다"
          loading={isLoading}
          empty="현재 청약 중인 종목이 없습니다"
          items={subscribing}
          render={(issuance) => <SubscriptionCard key={issuance.issuanceId} issuance={issuance} />}
        />

        <Section
          title="상장 종목"
          description="호가창에서 지정가·시장가로 거래할 수 있습니다"
          loading={isLoading}
          empty="상장된 종목이 없습니다"
          items={listed}
          render={(issuance) => <ListedCard key={issuance.issuanceId} issuance={issuance} />}
        />
      </div>
    </AppShell>
  );
}

function Section({
  title,
  description,
  items,
  loading,
  empty,
  render,
}: {
  title: string;
  description: string;
  items: IssuanceSummaryResponse[];
  loading: boolean;
  empty: string;
  render: (issuance: IssuanceSummaryResponse) => React.ReactNode;
}) {
  return (
    <section>
      <div className="mb-4 flex items-baseline gap-3">
        <h2 className="text-lg font-semibold tracking-tight">{title}</h2>
        <p className="text-fg-muted text-sm">{description}</p>
      </div>

      {loading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {[0, 1, 2].map((index) => (
            <Card key={index}>
              <CardBody className="flex flex-col gap-3">
                <Skeleton className="h-4 w-2/3" />
                <Skeleton className="h-7 w-1/2" />
                <Skeleton className="h-2 w-full" />
              </CardBody>
            </Card>
          ))}
        </div>
      ) : items.length === 0 ? (
        <Card>
          <CardBody className="text-fg-muted py-10 text-center text-sm">{empty}</CardBody>
        </Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{items.map(render)}</div>
      )}
    </section>
  );
}

/** 카드 내부 레이아웃은 뷰포트가 아니라 카드 폭(@container)에 반응한다 — §11.4 Container Query. */
function CardShell({
  href,
  name,
  symbol,
  riskGrade,
  transitionKey,
  children,
}: {
  href: Route;
  name: string;
  symbol: string;
  riskGrade: number;
  transitionKey: string;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      className="group focus-visible:outline-accent block rounded-lg focus-visible:outline-2 focus-visible:outline-offset-2"
    >
      <Card className="hover:border-border-strong hover:shadow-md h-full transition-[border-color,box-shadow,transform] duration-(--fr-motion-hover) group-hover:-translate-y-0.5">
        <CardBody className="flex h-full flex-col gap-3">
          <div className="flex items-start justify-between gap-2">
            <div className="flex flex-col gap-0.5">
              <span
                className="font-medium tracking-tight"
                style={{ viewTransitionName: transitionKey }}
              >
                {name}
              </span>
              <span className="fr-numeric text-fg-subtle text-xs">{symbol}</span>
            </div>
            <Badge tone="neutral">{RISK_LABEL[riskGrade] ?? `${riskGrade}등급`}</Badge>
          </div>
          {children}
        </CardBody>
      </Card>
    </Link>
  );
}

function SubscriptionCard({ issuance }: { issuance: IssuanceSummaryResponse }) {
  const total = issuance.totalUnits ?? 0;
  const remaining = issuance.remainingUnits ?? 0;
  // 수량 비율일 뿐 금액 계산이 아니다 — 진행률은 파생 지표라 표시 계산이 허용된다
  const progress = total > 0 ? ((total - remaining) / total) * 100 : 0;

  return (
    <CardShell
      href={`/issuances/${issuance.issuanceId}` as Route}
      name={issuance.assetName ?? issuance.tokenSymbol ?? ""}
      symbol={issuance.tokenSymbol ?? ""}
      riskGrade={issuance.riskGrade ?? 3}
      transitionKey={viewTransitionName("issuance", issuance.issuanceId ?? 0)}
    >
      <div className="flex items-baseline justify-between">
        <span className="text-fg-muted text-xs">1조각</span>
        <Money amount={issuance.unitPrice} size="lg" />
      </div>

      <ProgressBar value={progress} label="청약 진행률" />

      <div className="mt-auto flex items-center justify-between pt-1">
        <Badge tone="warning">{formatDDay(issuance.subscriptionEndAt)}</Badge>
        <span className="text-fg-muted text-xs">
          잔여 <Units units={remaining} />
        </span>
      </div>
    </CardShell>
  );
}

function ListedCard({ issuance }: { issuance: IssuanceSummaryResponse }) {
  return (
    <CardShell
      href={`/tokens/${issuance.tokenSymbol}` as Route}
      name={issuance.assetName ?? issuance.tokenSymbol ?? ""}
      symbol={issuance.tokenSymbol ?? ""}
      riskGrade={issuance.riskGrade ?? 3}
      transitionKey={viewTransitionName("token", issuance.tokenSymbol ?? "")}
    >
      <div className="flex items-baseline justify-between">
        <span className="text-fg-muted text-xs">발행가</span>
        <span className="fr-numeric text-sm">{formatWon(issuance.unitPrice)}</span>
      </div>

      <div className="mt-auto flex items-center justify-between gap-2 pt-1">
        <TokenPremium
          tokenSymbol={issuance.tokenSymbol ?? ""}
          suspended={issuance.status === "SUSPENDED"}
        />
      </div>
    </CardShell>
  );
}
