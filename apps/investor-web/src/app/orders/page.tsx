"use client";

import { FractaApiError } from "@fracta/api-client";
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  Table,
  TableContainer,
  TableEmpty,
  Tbody,
  Td,
  Th,
  Thead,
  Tr,
  errorMessage,
  formatNumber,
  useToast,
} from "@fracta/ui";
import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import * as React from "react";

import { AppShell } from "@/components/app-shell";
import { useMyOrders } from "@/lib/queries";
import { useRequireSession } from "@/lib/session";

const ORDER_STATUS: Record<string, { label: string; tone: "neutral" | "success" | "warning" }> = {
  OPEN: { label: "미체결", tone: "warning" },
  PARTIALLY_FILLED: { label: "부분 체결", tone: "warning" },
  FILLED: { label: "체결 완료", tone: "success" },
  CANCELLED: { label: "취소", tone: "neutral" },
  REJECTED: { label: "거부", tone: "neutral" },
};

/** 주문 내역 — 미체결 주문 취소까지 여기서 처리한다. */
export default function OrdersPage() {
  const session = useRequireSession();
  const { client } = session;
  const toast = useToast();
  const queryClient = useQueryClient();
  const { data: orders, isLoading } = useMyOrders();
  const [cancelling, setCancelling] = React.useState<number | null>(null);

  if (!session.ready || !session.token) return null;

  const cancel = async (orderId: number) => {
    setCancelling(orderId);
    try {
      await client.trading.cancelMyOrder({ orderId });
      toast.show({ title: "주문이 취소되었습니다", tone: "success" });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["my-orders"] }),
        queryClient.invalidateQueries({ queryKey: ["me"] }),
      ]);
    } catch (caught) {
      toast.show({
        title: "주문을 취소하지 못했습니다",
        description:
          caught instanceof FractaApiError ? errorMessage(caught.code) : undefined,
        tone: "danger",
      });
    } finally {
      setCancelling(null);
    }
  };

  return (
    <AppShell>
      <div className="flex flex-col gap-6">
        <h1 className="text-xl font-semibold tracking-tight">주문 내역</h1>

        <Card>
          <CardHeader>
            <CardTitle>전체 주문</CardTitle>
          </CardHeader>
          <CardBody className="pt-3">
            <TableContainer className="border-0">
              <Table>
                <Thead>
                  <Tr>
                    <Th>종목</Th>
                    <Th>구분</Th>
                    <Th>유형</Th>
                    <Th>주문가</Th>
                    <Th>수량</Th>
                    <Th>체결</Th>
                    <Th>상태</Th>
                    <Th />
                  </Tr>
                </Thead>
                <Tbody>
                  {(orders ?? []).map((order) => {
                    const status = ORDER_STATUS[order.status ?? ""] ?? {
                      label: order.status ?? "—",
                      tone: "neutral" as const,
                    };
                    const open = order.status === "OPEN" || order.status === "PARTIALLY_FILLED";
                    const buy = order.side === "BUY";

                    return (
                      <Tr key={order.orderId}>
                        <Td>
                          <Link
                            href={`/tokens/${order.tokenSymbol}`}
                            className="hover:text-accent fr-numeric"
                          >
                            {order.tokenSymbol}
                          </Link>
                        </Td>
                        <Td>
                          <span className={buy ? "text-price-up" : "text-price-down"}>
                            {buy ? "매수" : "매도"}
                          </span>
                        </Td>
                        <Td className="text-fg-muted text-xs">
                          {order.orderType === "LIMIT" ? "지정가" : "시장가"}
                        </Td>
                        <Td className="fr-numeric">
                          {order.price == null ? "시장가" : formatNumber(order.price)}
                        </Td>
                        <Td className="fr-numeric">{formatNumber(order.units)}</Td>
                        <Td className="fr-numeric text-fg-muted">
                          {formatNumber(order.filledUnits)}
                        </Td>
                        <Td>
                          <Badge tone={status.tone}>{status.label}</Badge>
                        </Td>
                        <Td>
                          {open && (
                            <Button
                              variant="ghost"
                              size="sm"
                              state={cancelling === order.orderId ? "pending" : "idle"}
                              onClick={() => order.orderId && cancel(order.orderId)}
                              pendingLabel="취소 중"
                            >
                              취소
                            </Button>
                          )}
                        </Td>
                      </Tr>
                    );
                  })}
                </Tbody>
              </Table>
              {!isLoading && (orders ?? []).length === 0 && (
                <TableEmpty>아직 주문 내역이 없습니다</TableEmpty>
              )}
            </TableContainer>
          </CardBody>
        </Card>
      </div>
    </AppShell>
  );
}
