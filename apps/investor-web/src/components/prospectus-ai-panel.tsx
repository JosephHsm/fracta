"use client";

import { FractaApiError, type ProspectusAskResponse } from "@fracta/api-client";
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
} from "@fracta/ui";
import { Bot, ShieldAlert, Sparkles } from "lucide-react";
import * as React from "react";

import { useSession } from "@/lib/session";

/**
 * 투자설명서 AI 질의 사이드패널 (FSD §11.2, Phase 8 연동).
 *
 * <p>일반 챗봇처럼 말풍선만 쌓지 않는다. 서버가 주는 구조화 응답
 * (`answer` / `citedPages` / `blocked` / `llmCalled`)을 그대로 화면 구조로 옮긴다 —
 * 인용은 클릭 가능한 칩, 차단은 전용 표시, LLM 미호출은 배지로 드러낸다.
 *
 * <p><b>가드레일 차단을 조용히 넘기지 않는다.</b> 차단은 이 프로젝트의 차별 포인트라
 * 사용자에게 "왜 답하지 않았는지"가 보여야 한다(phase-10 완료 조건).
 */
interface Turn {
  id: string;
  question: string;
  response?: ProspectusAskResponse;
  error?: string;
}

const SUGGESTED = [
  "What are the main risk factors?",
  "What fees are charged when trading?",
  "How does allotment work?",
];

export function ProspectusAiPanel({
  issuanceId,
  onCitationClick,
}: {
  issuanceId: number;
  onCitationClick: (page: number) => void;
}) {
  const { client } = useSession();
  const [question, setQuestion] = React.useState("");
  const [turns, setTurns] = React.useState<Turn[]>([]);
  const [state, setState] = React.useState<"idle" | "pending">("idle");

  const ask = async (asked: string) => {
    const text = asked.trim();
    if (!text || state === "pending") return;

    const id = crypto.randomUUID();
    setTurns((previous) => [...previous, { id, question: text }]);
    setQuestion("");
    setState("pending");

    try {
      const result = await client.ai.askProspectus({
        id: issuanceId,
        askRequest: { question: text },
      });
      setTurns((previous) =>
        previous.map((turn) => (turn.id === id ? { ...turn, response: result.data } : turn)),
      );
    } catch (caught) {
      setTurns((previous) =>
        previous.map((turn) =>
          turn.id === id
            ? {
                ...turn,
                error:
                  caught instanceof FractaApiError
                    ? errorMessage(caught.code)
                    : "답변을 생성하지 못했습니다.",
              }
            : turn,
        ),
      );
    } finally {
      setState("idle");
    }
  };

  return (
    <Card className="flex h-full flex-col">
      <CardHeader className="flex-row items-center gap-2">
        <Sparkles aria-hidden className="text-accent size-4" />
        <CardTitle>AI 질의</CardTitle>
      </CardHeader>

      <CardBody className="flex min-h-0 flex-1 flex-col gap-4 pt-3">
        <p className="text-fg-muted text-xs">
          투자설명서 원문만 근거로 답변합니다. 투자 권유·수익 보장에 해당하는 답변은 차단됩니다.
        </p>

        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto" aria-live="polite">
          {turns.length === 0 && (
            <div className="flex flex-col gap-2">
              <span className="fr-eyebrow">예시 질문</span>
              {SUGGESTED.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => ask(suggestion)}
                  className="border-border hover:bg-surface-sunken rounded-md border px-3 py-2 text-left text-xs transition-colors duration-(--fr-motion-hover)"
                >
                  {suggestion}
                </button>
              ))}
            </div>
          )}

          {turns.map((turn) => (
            <TurnView key={turn.id} turn={turn} onCitationClick={onCitationClick} />
          ))}

          {state === "pending" && (
            <p className="text-fg-muted flex items-center gap-2 text-xs">
              <Bot aria-hidden className="size-4 animate-pulse" />
              투자설명서를 찾아보고 있습니다…
            </p>
          )}
        </div>

        <form
          onSubmit={(event) => {
            event.preventDefault();
            void ask(question);
          }}
          className="flex flex-col gap-2"
        >
          <Field label="질문">
            <TextInput
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              placeholder="투자설명서에 대해 물어보세요"
            />
          </Field>
          <Button type="submit" state={state} pendingLabel="답변 생성 중..." block>
            질문하기
          </Button>
        </form>
      </CardBody>
    </Card>
  );
}

function TurnView({
  turn,
  onCitationClick,
}: {
  turn: Turn;
  onCitationClick: (page: number) => void;
}) {
  const response = turn.response;

  return (
    <div className="flex flex-col gap-2">
      <p className="bg-surface-sunken text-fg ml-auto max-w-[85%] rounded-lg px-3 py-2 text-xs">
        {turn.question}
      </p>

      {turn.error && (
        <p role="alert" className="text-danger text-xs">
          {turn.error}
        </p>
      )}

      {response && (
        <div
          className={cn(
            "flex flex-col gap-2 rounded-lg border p-3 text-xs",
            response.blocked ? "border-warning/40 bg-warning-soft" : "border-border bg-surface",
          )}
        >
          {response.blocked && (
            <p className="text-warning flex items-center gap-1.5 font-medium">
              <ShieldAlert aria-hidden className="size-3.5" />
              가드레일이 답변을 차단했습니다
            </p>
          )}

          <p className={cn("whitespace-pre-wrap", response.blocked ? "text-warning" : "text-fg")}>
            {response.answer}
          </p>

          <div className="flex flex-wrap items-center gap-1.5">
            {(response.citedPages ?? []).map((page) => (
              <button
                key={page}
                type="button"
                onClick={() => onCitationClick(page)}
                aria-label={`투자설명서 ${page}쪽으로 이동`}
                className="border-accent/30 bg-accent-soft text-accent hover:bg-accent hover:text-accent-foreground rounded-md border px-2 py-0.5 text-[11px] font-medium transition-colors duration-(--fr-motion-hover)"
              >
                p.{page}
              </button>
            ))}
            {/* LLM을 부르지 않고 답한 경우를 드러낸다 — 검색 임계값 미달이나 입력 차단이다 */}
            {response.llmCalled === false && <Badge tone="neutral">LLM 미호출</Badge>}
          </div>
        </div>
      )}
    </div>
  );
}
