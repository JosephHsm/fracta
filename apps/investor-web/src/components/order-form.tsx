"use client";

import { FractaApiError } from "@fracta/api-client";
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  Field,
  TextInput,
  cn,
  errorMessage,
  formatWon,
  useToast,
} from "@fracta/ui";
import { useQueryClient } from "@tanstack/react-query";
import { Ban } from "lucide-react";
import * as React from "react";

import { useSession } from "@/lib/session";

type Side = "BUY" | "SELL";
type OrderType = "LIMIT" | "MARKET";

/**
 * 주문 폼 (TR-01). 지정가/시장가, 호가 클릭 시 가격 자동 입력.
 *
 * <p><b>예상 금액을 프론트에서 계산하지 않는다.</b> 단가 × 수량은 서버가 원장에 기록하는 값이고,
 * 화면에서 곱해 보여주면 반올림·오버플로우 규칙이 서버와 갈라진다(FSD §11.2).
 * 대신 "체결가 기준으로 서버가 정산합니다"라고 안내한다.
 *
 * <p>거래 중단(SUSPENDED) 종목은 주문 버튼이 비활성이다 — phase-10 완료 조건.
 */
export function OrderForm({
  tokenSymbol,
  suspended,
  price,
  onPriceChange,
}: {
  tokenSymbol: string;
  suspended: boolean;
  price: string;
  onPriceChange: (price: string) => void;
}) {
  const { client } = useSession();
  const toast = useToast();
  const queryClient = useQueryClient();

  const [side, setSide] = React.useState<Side>("BUY");
  const [orderType, setOrderType] = React.useState<OrderType>("LIMIT");
  const [units, setUnits] = React.useState("10");
  const [error, setError] = React.useState<string | null>(null);
  const [state, setState] = React.useState<"idle" | "pending" | "done">("idle");

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setState("pending");

    try {
      const result = await client.trading.place({
        tokenSymbol,
        idempotencyKey: crypto.randomUUID(),
        placeOrderRequest: {
          side,
          orderType,
          price: orderType === "LIMIT" ? Number(price) : undefined,
          units: Number(units),
        },
      });

      const data = result.data;
      setState("done");
      toast.show({
        title: side === "BUY" ? "매수 주문이 접수되었습니다" : "매도 주문이 접수되었습니다",
        description:
          data?.filledUnits && data.filledUnits > 0
            ? `${data.filledUnits}조각이 즉시 체결되었습니다.`
            : "체결되면 알림으로 안내해 드립니다.",
        tone: "success",
      });

      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["orderbook", tokenSymbol] }),
        queryClient.invalidateQueries({ queryKey: ["executions", tokenSymbol] }),
        queryClient.invalidateQueries({ queryKey: ["my-orders"] }),
        queryClient.invalidateQueries({ queryKey: ["me"] }),
      ]);
      window.setTimeout(() => setState("idle"), 1_200);
    } catch (caught) {
      setState("idle");
      setError(
        caught instanceof FractaApiError
          ? errorMessage(caught.code)
          : "주문을 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      );
    }
  };

  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>주문</CardTitle>
      </CardHeader>
      <CardBody className="pt-3">
        {suspended && (
          <div className="border-danger/30 bg-danger-soft text-danger mb-4 flex items-start gap-2 rounded-md border p-3 text-xs">
            <Ban aria-hidden className="mt-px size-4 shrink-0" />
            <p>
              괴리율이 ±20%를 초과해 거래가 중단된 종목입니다. 정상 범위로 돌아오면 거래가
              재개됩니다.
            </p>
          </div>
        )}

        <form onSubmit={submit} className="flex flex-col gap-4">
          <div role="radiogroup" aria-label="매매 구분" className="grid grid-cols-2 gap-2">
            {(["BUY", "SELL"] as const).map((value) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={side === value}
                onClick={() => setSide(value)}
                className={cn(
                  "rounded-md border py-2 text-sm font-medium transition-colors duration-(--fr-motion-hover)",
                  side === value
                    ? value === "BUY"
                      ? "border-price-up/40 bg-price-up-soft text-price-up"
                      : "border-price-down/40 bg-price-down-soft text-price-down"
                    : "border-border text-fg-muted hover:bg-surface-sunken",
                )}
              >
                {value === "BUY" ? "매수" : "매도"}
              </button>
            ))}
          </div>

          <div role="radiogroup" aria-label="주문 유형" className="flex gap-2">
            {(["LIMIT", "MARKET"] as const).map((value) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={orderType === value}
                onClick={() => setOrderType(value)}
                className={cn(
                  "rounded-md px-3 py-1 text-xs transition-colors duration-(--fr-motion-hover)",
                  orderType === value
                    ? "bg-surface-sunken text-fg font-medium"
                    : "text-fg-muted hover:text-fg",
                )}
              >
                {value === "LIMIT" ? "지정가" : "시장가"}
              </button>
            ))}
          </div>

          {orderType === "LIMIT" && (
            <Field label="주문 가격" description="호가를 클릭하면 자동으로 입력됩니다">
              <TextInput
                type="number"
                inputMode="numeric"
                min={1}
                value={price}
                onChange={(event) => onPriceChange(event.target.value)}
                required
              />
            </Field>
          )}

          <Field label="주문 수량" description="1조각 단위로 주문할 수 있습니다">
            <TextInput
              type="number"
              inputMode="numeric"
              min={1}
              value={units}
              onChange={(event) => setUnits(event.target.value)}
              required
            />
          </Field>

          {error && (
            <p role="alert" className="text-danger text-xs">
              {error}
            </p>
          )}

          <div className="text-fg-muted flex items-baseline justify-between text-xs">
            <span>주문 단가</span>
            <span className="fr-numeric">
              {orderType === "LIMIT" ? formatWon(Number(price) || 0) : "시장가"}
            </span>
          </div>
          <p className="text-fg-subtle text-xs">
            체결 금액과 수수료는 체결가 기준으로 서버가 정산합니다.
          </p>

          <Button
            type="submit"
            state={state}
            disabled={suspended}
            variant={side === "BUY" ? "primary" : "secondary"}
            pendingLabel="주문 전송 중..."
            doneLabel="주문 접수됨"
            block
          >
            {side === "BUY" ? "매수 주문" : "매도 주문"}
          </Button>

          {suspended && (
            <Badge tone="danger" className="justify-center">
              거래 중단 종목은 주문할 수 없습니다
            </Badge>
          )}
        </form>
      </CardBody>
    </Card>
  );
}
