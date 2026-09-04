"use client";

import {
  FractaApiError,
  type IssuanceSummaryResponseAllotmentMethodEnum,
} from "@fracta/api-client";
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  Dialog,
  Field,
  Money,
  ProgressBar,
  TextInput,
  Units,
  errorMessage,
  formatDDay,
  formatDateTime,
  useToast,
  viewTransitionName,
} from "@fracta/ui";
import { useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, FileText, ShieldAlert } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { useParams } from "next/navigation";
import * as React from "react";

import { AppShell } from "@/components/app-shell";
import { RISK_LABEL } from "@/app/page";
import { useIssuances, useMe } from "@/lib/queries";
import { useSession } from "@/lib/session";

/** 서버 enum을 전부 덮는다 — 값이 늘면 컴파일이 깨져 원시 코드가 화면에 새지 않는다. */
const ALLOTMENT_METHOD: Record<IssuanceSummaryResponseAllotmentMethodEnum, string> = {
  FCFS: "선착순",
  PRORATA: "비례배분",
};

/**
 * 청약 화면 (FSD §11.2) — 수량 입력 → 증거금 확인 → 적합성 경고 → 신청.
 *
 * <p>핵심은 <b>적합성 차단 UX</b>다. 403 SUIT_PROFILE_MISMATCH를 일반 토스트로 흘리면
 * 금소법 시연 포인트가 사라진다(phase-10 흔한 실수 3). 사유를 명확히 보여주고
 * 확인 서명 동선으로 연결한다.
 */
export default function SubscriptionPage() {
  const params = useParams<{ id: string }>();
  const issuanceId = Number(params.id);

  const { client } = useSession();
  const toast = useToast();
  const queryClient = useQueryClient();

  const { data: issuances } = useIssuances();
  const { data: me } = useMe();
  const issuance = (issuances ?? []).find((item) => item.issuanceId === issuanceId);

  const [units, setUnits] = React.useState("10");
  const [state, setState] = React.useState<"idle" | "pending" | "done">("idle");
  const [error, setError] = React.useState<string | null>(null);
  const [blocked, setBlocked] = React.useState<{ message: string; productGrade: number } | null>(
    null,
  );
  const [confirmOpen, setConfirmOpen] = React.useState(false);

  const total = issuance?.totalUnits ?? 0;
  const remaining = issuance?.remainingUnits ?? 0;
  const progress = total > 0 ? ((total - remaining) / total) * 100 : 0;
  const subscribing = issuance?.status === "SUBSCRIBING";

  const apply = async () => {
    setError(null);
    setBlocked(null);
    setState("pending");
    try {
      await client.subscription.apply({
        issuanceId,
        idempotencyKey: crypto.randomUUID(),
        applyRequest: { units: Number(units) },
      });
      setState("done");
      setConfirmOpen(false);
      toast.show({
        title: "청약이 접수되었습니다",
        description: "배정 결과는 청약 마감 후 내 자산에서 확인할 수 있습니다.",
        tone: "success",
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["issuances"] }),
        queryClient.invalidateQueries({ queryKey: ["my-subscriptions"] }),
        queryClient.invalidateQueries({ queryKey: ["me"] }),
      ]);
    } catch (caught) {
      setState("idle");
      setConfirmOpen(false);
      if (caught instanceof FractaApiError && caught.isSuitabilityBlock) {
        // 전용 UI로 보낸다 — 토스트로 흘리지 않는다
        setBlocked({
          message: caught.message,
          productGrade: issuance?.riskGrade ?? 3,
        });
        return;
      }
      setError(
        caught instanceof FractaApiError
          ? errorMessage(caught.code)
          : "청약을 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      );
    }
  };

  const acknowledgeAndRetry = async () => {
    if (!blocked) return;
    try {
      // 서명은 이 발행 건에만 적용된다. 다른 상품에는 다시 서명해야 한다
      await client.investor.acknowledge({
        suitabilityAckRequest: {
          productGrade: blocked.productGrade,
          scopeType: "ISSUANCE",
          scopeId: String(issuanceId),
        },
      });
      toast.show({
        title: "부적합 확인 서명이 등록되었습니다",
        description: "이 발행 건에 한해 청약할 수 있습니다. 다른 상품은 다시 확인이 필요합니다.",
        tone: "info",
      });
      setBlocked(null);
      await apply();
    } catch (caught) {
      setError(
        caught instanceof FractaApiError
          ? errorMessage(caught.code)
          : "확인 서명을 등록하지 못했습니다.",
      );
    }
  };

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
              style={{ viewTransitionName: viewTransitionName("issuance", issuanceId) }}
            >
              {issuance?.assetName ?? "발행 정보"}
            </h1>
            <div className="flex items-center gap-2">
              <span className="fr-numeric text-fg-muted text-sm">{issuance?.tokenSymbol}</span>
              <Badge tone="neutral">
                {RISK_LABEL[issuance?.riskGrade ?? 3] ?? "위험등급 미상"}
              </Badge>
              {subscribing ? (
                <Badge tone="warning">{formatDDay(issuance?.subscriptionEndAt)}</Badge>
              ) : (
                <Badge tone="neutral">청약 종료</Badge>
              )}
            </div>
          </div>
        </header>

        <div className="grid gap-4 lg:grid-cols-3">
          <Card className="lg:col-span-2">
            <CardHeader>
              <CardTitle>청약 현황</CardTitle>
            </CardHeader>
            <CardBody className="flex flex-col gap-5 pt-3">
              <ProgressBar value={progress} label="청약 진행률" />

              <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
                <Stat label="1조각 가격" value={<Money amount={issuance?.unitPrice} />} />
                <Stat label="총 발행량" value={<Units units={total} />} />
                <Stat label="잔여 수량" value={<Units units={remaining} />} />
                <Stat
                  label="배정 방식"
                  value={issuance?.allotmentMethod ? ALLOTMENT_METHOD[issuance.allotmentMethod] : "—"}
                />
              </dl>

              <div className="flex flex-wrap items-center justify-between gap-3">
                <p className="text-fg-subtle text-xs">
                  청약 기간 {formatDateTime(issuance?.subscriptionStartAt)} ~{" "}
                  {formatDateTime(issuance?.subscriptionEndAt)}
                </p>
                <Link
                  href={`/issuances/${issuanceId}/prospectus` as Route}
                  className="text-accent inline-flex items-center gap-1.5 text-sm hover:underline"
                >
                  <FileText aria-hidden className="size-4" />
                  투자설명서 · AI 질의
                </Link>
              </div>
            </CardBody>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>청약 신청</CardTitle>
            </CardHeader>
            <CardBody className="flex flex-col gap-4 pt-3">
              <Field label="신청 수량" description="1조각 단위로 신청할 수 있습니다" required>
                <TextInput
                  type="number"
                  inputMode="numeric"
                  min={1}
                  value={units}
                  onChange={(event) => setUnits(event.target.value)}
                />
              </Field>

              <div className="text-fg-muted flex items-baseline justify-between text-xs">
                <span>내 예수금</span>
                <Money amount={me?.cashBalance as number | undefined} />
              </div>
              <p className="text-fg-subtle text-xs">
                증거금은 서버가 계산해 예수금에서 차감합니다.
              </p>

              {error && (
                <p role="alert" className="text-danger text-xs">
                  {error}
                </p>
              )}

              <Button
                state={state}
                disabled={!subscribing}
                onClick={() => setConfirmOpen(true)}
                pendingLabel="처리 중..."
                doneLabel="청약 완료"
                block
              >
                청약하기
              </Button>
              {!subscribing && (
                <p className="text-fg-subtle text-center text-xs">
                  청약 기간이 아닙니다
                </p>
              )}
            </CardBody>
          </Card>
        </div>

        {blocked && (
          <SuitabilityBlock
            message={blocked.message}
            productGrade={blocked.productGrade}
            investorGrade={me?.riskGrade}
            onAcknowledge={acknowledgeAndRetry}
            onDismiss={() => setBlocked(null)}
          />
        )}
      </div>

      <Dialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="청약 신청을 확인해 주세요"
        description="확인 후에는 취소가 제한될 수 있습니다."
        dismissible={false}
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirmOpen(false)}>
              취소
            </Button>
            <Button state={state} onClick={apply} pendingLabel="처리 중...">
              청약 확정
            </Button>
          </>
        }
      >
        <dl className="flex flex-col gap-2">
          <div className="flex justify-between">
            <dt className="text-fg-muted">종목</dt>
            <dd>{issuance?.assetName}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-fg-muted">신청 수량</dt>
            <dd>
              <Units units={Number(units) || 0} />
            </dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-fg-muted">1조각 가격</dt>
            <dd>
              <Money amount={issuance?.unitPrice} />
            </dd>
          </div>
        </dl>
      </Dialog>
    </AppShell>
  );
}

/**
 * 적합성 차단 전용 UI (금소법 시연 포인트).
 * 사유 + 등급 비교 + 확인 서명 동선을 한 화면에 둔다.
 */
function SuitabilityBlock({
  message,
  productGrade,
  investorGrade,
  onAcknowledge,
  onDismiss,
}: {
  message: string;
  productGrade: number;
  investorGrade?: number;
  onAcknowledge: () => void;
  onDismiss: () => void;
}) {
  return (
    <Card className="border-warning/40" elevated>
      <CardBody className="flex flex-col gap-4">
        <div className="flex items-start gap-3">
          <ShieldAlert aria-hidden className="text-warning mt-0.5 size-5 shrink-0" />
          <div className="flex flex-col gap-1">
            <h2 className="font-semibold tracking-tight">투자자 적합성 확인이 필요합니다</h2>
            <p className="text-fg-muted text-sm">{message}</p>
          </div>
        </div>

        <dl className="border-border grid grid-cols-2 gap-3 rounded-md border p-3 text-sm">
          <div className="flex flex-col gap-0.5">
            <dt className="text-fg-muted text-xs">상품 위험등급</dt>
            <dd className="font-medium">{RISK_LABEL[productGrade] ?? productGrade}</dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-fg-muted text-xs">내 투자성향</dt>
            <dd className="font-medium">
              {investorGrade ? (RISK_LABEL[investorGrade] ?? investorGrade) : "미진단"}
            </dd>
          </div>
        </dl>

        <p className="text-fg-subtle text-xs">
          금융소비자보호법에 따라, 투자성향보다 위험등급이 높은 상품은 부적합 사실을 확인하고
          서명해야 청약할 수 있습니다.
        </p>

        <div className="flex flex-wrap gap-2">
          <Button onClick={onAcknowledge}>부적합 확인 후 청약</Button>
          <Button variant="secondary" onClick={onDismiss}>
            청약 취소
          </Button>
          <Link
            href="/onboarding"
            className="text-accent inline-flex items-center px-3 text-sm hover:underline"
          >
            투자성향 다시 진단하기
          </Link>
        </div>
      </CardBody>
    </Card>
  );
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-fg-muted text-xs">{label}</dt>
      <dd className="font-medium">{value}</dd>
    </div>
  );
}

