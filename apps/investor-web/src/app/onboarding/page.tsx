"use client";

import { FractaApiError } from "@fracta/api-client";
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  cn,
  errorMessage,
  useToast,
} from "@fracta/ui";
import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import * as React from "react";

import { AppShell } from "@/components/app-shell";
import { RISK_LABEL } from "@/app/page";
import { useMe } from "@/lib/queries";
import { useSession } from "@/lib/session";

/**
 * 투자성향 진단 8문항 (FSD §11.2 온보딩).
 *
 * <p>문항은 `docs/appendix/risk-profile-questions.md`의 축약본이다. 점수 계산은
 * 서버가 한다 — 프론트가 등급을 미리 계산해 보여주면 서버 판정과 어긋날 수 있다.
 */
const QUESTIONS = [
  "투자 경험이 어느 정도이십니까?",
  "금융투자상품에 대한 이해도는 어느 정도이십니까?",
  "총 자산 중 금융투자상품 비중은 어느 정도입니까?",
  "연간 소득 대비 투자 가능 금액은 어느 정도입니까?",
  "예상 투자 기간은 어느 정도입니까?",
  "투자 원금 손실을 어느 정도까지 감수할 수 있습니까?",
  "기대하는 수익 수준은 어느 정도입니까?",
  "시장이 급락할 때 어떻게 대응하시겠습니까?",
];

const CHOICES = [
  { level: 1, label: "매우 보수적" },
  { level: 2, label: "보수적" },
  { level: 3, label: "중립적" },
  { level: 4, label: "적극적" },
  { level: 5, label: "매우 적극적" },
];

export default function OnboardingPage() {
  const { client } = useSession();
  const router = useRouter();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { data: me } = useMe();

  const [answers, setAnswers] = React.useState<number[]>(Array(QUESTIONS.length).fill(0));
  const [state, setState] = React.useState<"idle" | "pending" | "done">("idle");
  const [error, setError] = React.useState<string | null>(null);
  const [result, setResult] = React.useState<{ grade: number; gradeName: string } | null>(null);

  const complete = answers.every((answer) => answer > 0);

  const submit = async () => {
    setError(null);
    setState("pending");
    try {
      const response = await client.investor.submitRiskProfile({
        riskProfileRequest: { answers },
      });
      const data = response.data;
      setResult({ grade: data?.grade ?? 0, gradeName: data?.gradeName ?? "" });
      setState("done");
      toast.show({
        title: "투자성향 진단이 완료되었습니다",
        description: `${data?.gradeName ?? ""}으로 판정되었습니다.`,
        tone: "success",
      });
      await queryClient.invalidateQueries({ queryKey: ["me"] });
    } catch (caught) {
      setState("idle");
      setError(
        caught instanceof FractaApiError
          ? errorMessage(caught.code)
          : "진단 결과를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      );
    }
  };

  return (
    <AppShell>
      <div className="mx-auto flex max-w-3xl flex-col gap-6">
        <header className="flex flex-col gap-2">
          <h1 className="text-xl font-semibold tracking-tight">투자성향 진단</h1>
          <p className="text-fg-muted text-sm">
            금융소비자보호법에 따라 투자성향을 진단합니다. 8문항 모두 답변해 주세요.
          </p>
          {me?.riskGrade ? (
            <Badge tone="info" className="w-fit">
              현재 성향: {RISK_LABEL[me.riskGrade] ?? me.riskGrade}
            </Badge>
          ) : null}
        </header>

        {result ? (
          <Card elevated>
            <CardBody className="flex flex-col items-center gap-3 py-10 text-center">
              <span className="text-fg-muted text-sm">진단 결과</span>
              <strong className="text-3xl font-semibold tracking-tight">{result.gradeName}</strong>
              <p className="text-fg-muted text-sm">
                {result.grade}등급으로 판정되었습니다. 이보다 위험등급이 높은 상품은 부적합
                확인 서명 후 청약할 수 있습니다.
              </p>
              <Button onClick={() => router.push("/")}>홈으로</Button>
            </CardBody>
          </Card>
        ) : (
          <>
            {QUESTIONS.map((question, index) => (
              <Card key={question}>
                <CardHeader>
                  <CardTitle>
                    {index + 1}. {question}
                  </CardTitle>
                </CardHeader>
                <CardBody className="pt-3">
                  <div
                    role="radiogroup"
                    aria-label={question}
                    className="grid grid-cols-2 gap-2 sm:grid-cols-5"
                  >
                    {CHOICES.map((choice) => (
                      <button
                        key={choice.level}
                        type="button"
                        role="radio"
                        aria-checked={answers[index] === choice.level}
                        onClick={() =>
                          setAnswers((previous) =>
                            previous.map((value, i) => (i === index ? choice.level : value)),
                          )
                        }
                        className={cn(
                          "rounded-md border px-2 py-2 text-xs transition-colors duration-(--fr-motion-hover)",
                          answers[index] === choice.level
                            ? "border-accent bg-accent-soft text-accent font-medium"
                            : "border-border text-fg-muted hover:bg-surface-sunken",
                        )}
                      >
                        {choice.label}
                      </button>
                    ))}
                  </div>
                </CardBody>
              </Card>
            ))}

            {error && (
              <p role="alert" className="text-danger text-sm">
                {error}
              </p>
            )}

            <div className="flex items-center justify-between gap-4">
              <span className="text-fg-muted text-sm">
                {answers.filter((answer) => answer > 0).length} / {QUESTIONS.length} 답변
              </span>
              <Button
                state={state}
                disabled={!complete}
                onClick={submit}
                pendingLabel="진단 중..."
                doneLabel="진단 완료"
              >
                진단 결과 확인
              </Button>
            </div>
          </>
        )}
      </div>
    </AppShell>
  );
}
