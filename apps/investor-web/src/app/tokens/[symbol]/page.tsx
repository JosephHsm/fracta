"use client";

import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  Money,
  PremiumBadge,
  Skeleton,
  Table,
  TableContainer,
  TableEmpty,
  Tbody,
  Td,
  Th,
  Thead,
  Tr,
  Units,
  formatNumber,
  formatSignedPercent,
  formatTime,
  premiumLevel,
  viewTransitionName,
} from "@fracta/ui";
import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import * as React from "react";

import { AppShell } from "@/components/app-shell";
import { OrderBook } from "@/components/order-book";
import { OrderForm } from "@/components/order-form";
import { useExecutions, useIssuances } from "@/lib/queries";

/**
 * 종목 상세 — 이 프로젝트의 얼굴 화면 (FSD §11.2).
 * 발행 정보 + 괴리율 배지 + 호가창 + 체결 내역 + 주문 폼.
 */
export default function TokenDetailPage() {
  const params = useParams<{ symbol: string }>();
  const symbol = decodeURIComponent(params.symbol ?? "");

  const { data: issuances } = useIssuances();
  const { data: executions, isLoading: executionsLoading } = useExecutions(symbol, 20);

  // 사용자가 직접 입력하기 전에는 null — 그동안은 최근 체결가(없으면 발행가)를 보여준다.
  // effect에서 setState로 초기값을 넣으면 렌더가 한 번 더 돌고 값이 늦게 채워진다.
  const [priceInput, setPriceInput] = React.useState<string | null>(null);

  const issuance = (issuances ?? []).find((item) => item.tokenSymbol === symbol);
  const latest = executions?.[0];
  const suspended = issuance?.status === "SUSPENDED";
  const level = premiumLevel(latest?.premiumRate as number | undefined, suspended);

  const seedPrice = latest?.price ?? issuance?.unitPrice;
  const price = priceInput ?? (seedPrice == null ? "" : String(seedPrice));

  return (
    <AppShell>
      <div className="flex flex-col gap-6">
        <Link
          href="/"
          className="text-fg-muted hover:text-fg inline-flex w-fit items-center gap-1.5 text-sm"
        >
          <ArrowLeft aria-hidden className="size-4" />
          목록으로
        </Link>

        <header className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex flex-col gap-2">
            <h1
              className="text-2xl font-semibold tracking-tight"
              style={{ viewTransitionName: viewTransitionName("token", symbol) }}
            >
              {issuance?.assetName ?? symbol}
            </h1>
            <div className="flex flex-wrap items-center gap-2">
              <span className="fr-numeric text-fg-muted text-sm">{symbol}</span>
              <PremiumBadge
                premiumRate={latest?.premiumRate as number | undefined}
                suspended={suspended}
              />
              {level === "HALT" && (
                <Badge tone="danger">주문 불가</Badge>
              )}
            </div>
          </div>

          <div className="flex flex-col items-end gap-1">
            <span className="text-fg-muted text-xs">최근 체결가</span>
            {executionsLoading ? (
              <Skeleton className="h-8 w-32" />
            ) : (
              <Money amount={latest?.price} size="xl" />
            )}
          </div>
        </header>

        <div className="grid gap-4 lg:grid-cols-12">
          <div className="lg:col-span-4">
            <OrderBook tokenSymbol={symbol} onPickPrice={(picked) => setPriceInput(String(picked))} />
          </div>

          <div className="lg:col-span-4">
            <OrderForm
              tokenSymbol={symbol}
              suspended={suspended}
              price={price}
              onPriceChange={setPriceInput}
            />
          </div>

          <div className="flex flex-col gap-4 lg:col-span-4">
            <Card>
              <CardHeader>
                <CardTitle>발행 정보</CardTitle>
              </CardHeader>
              <CardBody className="flex flex-col gap-2.5 pt-3 text-sm">
                <Row label="발행가">
                  <Money amount={issuance?.unitPrice} />
                </Row>
                <Row label="총 발행량">
                  <Units units={issuance?.totalUnits} />
                </Row>
                <Row label="기초자산">
                  <span>{ASSET_TYPE[issuance?.assetType ?? ""] ?? "—"}</span>
                </Row>
                <Row label="상태">
                  <Badge tone={suspended ? "danger" : "success"}>
                    {suspended ? "거래 중단" : "거래 중"}
                  </Badge>
                </Row>
              </CardBody>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>투자설명서</CardTitle>
              </CardHeader>
              <CardBody className="pt-3">
                <Link
                  href={`/issuances/${issuance?.issuanceId ?? 0}`}
                  className="text-accent text-sm hover:underline"
                >
                  발행 상세와 AI 질의로 이동
                </Link>
              </CardBody>
            </Card>
          </div>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>체결 내역</CardTitle>
          </CardHeader>
          <CardBody className="pt-3">
            <TableContainer className="border-0">
              <Table>
                <Thead>
                  <Tr>
                    <Th>시각</Th>
                    <Th>체결가</Th>
                    <Th>수량</Th>
                    <Th>괴리율</Th>
                  </Tr>
                </Thead>
                <Tbody>
                  {(executions ?? []).map((execution, index) => (
                    <Tr key={execution.executionId} entering={index === 0}>
                      <Td className="text-fg-muted text-xs">{formatTime(execution.executedAt)}</Td>
                      <Td className="fr-numeric">{formatNumber(execution.price)}</Td>
                      <Td className="fr-numeric text-fg-muted">{formatNumber(execution.units)}</Td>
                      <Td className="fr-numeric text-xs">
                        {formatSignedPercent(execution.premiumRate as number | undefined)}
                      </Td>
                    </Tr>
                  ))}
                </Tbody>
              </Table>
              {!executionsLoading && (executions ?? []).length === 0 && (
                <TableEmpty>아직 체결된 거래가 없습니다</TableEmpty>
              )}
            </TableContainer>
          </CardBody>
        </Card>
      </div>
    </AppShell>
  );
}

const ASSET_TYPE: Record<string, string> = {
  REIT: "리츠",
  ETF: "ETF",
  REAL_ESTATE: "부동산",
};

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <span className="text-fg-muted text-xs">{label}</span>
      {children}
    </div>
  );
}
