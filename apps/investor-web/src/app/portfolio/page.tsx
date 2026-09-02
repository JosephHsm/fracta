"use client";

import {
  Badge,
  BentoGrid,
  BentoItem,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  Money,
  StatTile,
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
} from "@fracta/ui";
import { Landmark, Wallet } from "lucide-react";
import Link from "next/link";
import * as React from "react";

import type { MySubscriptionResponseStatusEnum } from "@fracta/api-client";

import { AppShell } from "@/components/app-shell";
import { RISK_LABEL } from "@/app/page";
import { useIssuances, useMe, useMySubscriptions } from "@/lib/queries";

/**
 * 청약 상태 문구.
 *
 * <p><b>`Record<Enum, ...>`이라 값이 하나라도 빠지면 컴파일이 깨진다.</b> 이전에는
 * `Record<string, ...>`이라 서버에 있는 DEPOSITED·PENDING·SETTLED가 매핑에 없었고,
 * 화면에 원시 코드 "DEPOSITED"가 그대로 노출됐다 —
 * Phase 10 완료 조건 "원시 코드 노출 0건" 위반이었다.
 */
type StatusStyle = { label: string; tone: "neutral" | "success" | "warning" | "danger" };

const SUBSCRIPTION_STATUS: Record<MySubscriptionResponseStatusEnum, StatusStyle> = {
  PENDING: { label: "접수 중", tone: "warning" },
  DEPOSITED: { label: "증거금 납입", tone: "warning" },
  ALLOTTED: { label: "배정 완료", tone: "success" },
  PARTIALLY_ALLOTTED: { label: "부분 배정", tone: "success" },
  SETTLED: { label: "정산 완료", tone: "success" },
  REJECTED: { label: "미배정", tone: "neutral" },
  CANCELLED: { label: "취소", tone: "neutral" },
};

/**
 * 내 자산 (FSD §11.2) — 예수금·청약 내역.
 *
 * <p>평가금액은 <b>표시하지 않는다.</b> 보유 수량 × 현재가는 금액 계산이고, 서버가
 * 그 값을 내려주는 엔드포인트가 없다. 프론트에서 곱해 보여주면 §11.2 금액 규칙 위반이다.
 * 서버에 평가금액 API가 생기면 그때 붙인다.
 */
export default function PortfolioPage() {
  const { data: me } = useMe();
  const { data: subscriptions, isLoading } = useMySubscriptions();
  const { data: issuances } = useIssuances();

  const byId = new Map((issuances ?? []).map((item) => [item.issuanceId, item]));
  const allotted = (subscriptions ?? []).filter((item) => (item.allottedUnits ?? 0) > 0);

  return (
    <AppShell>
      <div className="flex flex-col gap-8">
        <h1 className="text-xl font-semibold tracking-tight">내 자산</h1>

        <BentoGrid>
          <BentoItem span={4}>
            <StatTile
              label="예수금"
              value={<Money amount={me?.cashBalance as number | undefined} size="xl" />}
              hint="출금 가능 금액"
              icon={<Wallet aria-hidden className="size-3.5" />}
            />
          </BentoItem>
          <BentoItem span={4}>
            <StatTile
              label="배정받은 종목"
              value={<span className="text-3xl">{allotted.length}</span>}
              hint={`청약 ${subscriptions?.length ?? 0}건`}
              icon={<Landmark aria-hidden className="size-3.5" />}
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
              hint={me?.kycStatus === "VERIFIED" ? "KYC 완료" : "KYC 미완료"}
            />
          </BentoItem>
        </BentoGrid>

        <Card>
          <CardHeader>
            <CardTitle>청약 내역</CardTitle>
          </CardHeader>
          <CardBody className="pt-3">
            <TableContainer className="border-0">
              <Table>
                <Thead>
                  <Tr>
                    <Th>종목</Th>
                    <Th>신청 수량</Th>
                    <Th>배정 수량</Th>
                    <Th>증거금</Th>
                    <Th>상태</Th>
                  </Tr>
                </Thead>
                <Tbody>
                  {(subscriptions ?? []).map((subscription) => {
                    const issuance = byId.get(subscription.issuanceId);
                    // 폴백에서도 원시 코드를 쓰지 않는다
                    const status = subscription.status
                      ? SUBSCRIPTION_STATUS[subscription.status]
                      : { label: "확인 중", tone: "neutral" as const };
                    return (
                      <Tr key={subscription.orderId}>
                        <Td>
                          {issuance ? (
                            <Link
                              href={`/issuances/${issuance.issuanceId}`}
                              className="hover:text-accent"
                            >
                              {issuance.assetName ?? issuance.tokenSymbol}
                            </Link>
                          ) : (
                            <span className="text-fg-muted">발행 #{subscription.issuanceId}</span>
                          )}
                        </Td>
                        <Td className="fr-numeric">{formatNumber(subscription.requestedUnits)}</Td>
                        <Td className="fr-numeric">
                          {subscription.allottedUnits == null ? (
                            <span className="text-fg-subtle">배정 전</span>
                          ) : (
                            <Units units={subscription.allottedUnits} />
                          )}
                        </Td>
                        <Td>
                          <Money amount={subscription.depositAmount} />
                        </Td>
                        <Td>
                          <Badge tone={status.tone}>{status.label}</Badge>
                        </Td>
                      </Tr>
                    );
                  })}
                </Tbody>
              </Table>
              {!isLoading && (subscriptions ?? []).length === 0 && (
                <TableEmpty>아직 청약 내역이 없습니다</TableEmpty>
              )}
            </TableContainer>
          </CardBody>
        </Card>

        <p className="text-fg-subtle text-xs">
          평가금액과 손익은 서버가 산출하는 값이 필요해 아직 표시하지 않습니다. 화면에서
          보유 수량과 현재가를 곱해 만들지 않습니다.
        </p>
      </div>
    </AppShell>
  );
}
