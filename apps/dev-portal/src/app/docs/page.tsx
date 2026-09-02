"use client";

import type { DevPortalAskResponse } from "@fracta/api-client";
import { Badge, Button, Card, CardBody, Field, TextInput } from "@fracta/ui";
import { BookOpen, Bot, Send } from "lucide-react";
import * as React from "react";

import { PageHeading } from "@/components/page-heading";
import { PortalShell } from "@/components/portal-shell";
import { usePortalSession } from "@/lib/session";

export default function DocsPage() {
  const { client } = usePortalSession();
  const [question, setQuestion] = React.useState("샌드박스에서 종목 목록을 조회하는 방법을 알려주세요.");
  const [answer, setAnswer] = React.useState<DevPortalAskResponse | null>(null);
  const [pending, setPending] = React.useState(false);

  const ask = async (event: React.FormEvent) => {
    event.preventDefault();
    setPending(true);
    try {
      setAnswer((await client.ai.askDevPortal({ askRequest: { question } })).data ?? null);
    } finally {
      setPending(false);
    }
  };

  return (
    <PortalShell>
      <PageHeading title="API 문서" description="실제 OpenAPI 계약을 탐색하고 AI 어시스턴트에게 구현 방법을 묻습니다." />
      <div className="grid gap-6 2xl:grid-cols-[minmax(0,1fr)_25rem]">
        <Card className="min-h-[45rem] overflow-hidden"><div className="border-border bg-surface-sunken flex items-center gap-2 border-b px-4 py-3 text-sm font-medium"><BookOpen aria-hidden className="size-4" />Swagger UI</div><iframe title="FRACTA OpenAPI Swagger UI" src="/swagger-ui.html" className="h-[calc(100dvh-13rem)] min-h-[41rem] w-full bg-surface" /></Card>
        <Card className="h-fit 2xl:sticky 2xl:top-24"><CardBody className="flex flex-col gap-5">
          <div className="flex items-start gap-3"><div className="bg-accent-soft text-accent rounded-md p-2"><Bot aria-hidden className="size-5" /></div><div><h2 className="font-semibold">API 어시스턴트</h2><p className="text-fg-muted mt-1 text-xs">스펙에 존재하는 엔드포인트만 근거로 답합니다.</p></div></div>
          <form onSubmit={ask} className="flex flex-col gap-3"><Field label="질문"><TextInput value={question} onChange={(event) => setQuestion(event.target.value)} maxLength={2000} /></Field><Button type="submit" state={pending ? "pending" : "idle"} disabled={!question.trim()}><Send aria-hidden className="size-4" />질문하기</Button></form>
          {answer && <section aria-live="polite" className="border-border flex flex-col gap-4 border-t pt-5">{answer.blocked ? <div className="bg-warning-soft border-warning/30 text-warning rounded-md border p-4 text-sm">안전 정책상 이 질문에는 답할 수 없습니다. API 사용 방법이나 오류 해결 방법으로 바꾸어 질문해 주세요.</div> : <p className="text-sm leading-6 whitespace-pre-wrap">{answer.answer}</p>}<div>{(answer.citedEndpoints ?? []).length > 0 && <><h3 className="text-fg-muted mb-2 text-xs font-medium">근거 엔드포인트</h3><div className="flex flex-col gap-2">{answer.citedEndpoints?.map((endpoint) => <div key={endpoint} className="bg-surface-sunken border-border rounded-md border p-3"><Badge tone="accent">API</Badge><code className="mt-2 block overflow-x-auto text-xs">{endpoint}</code></div>)}</div></>}</div>{!answer.llmCalled && <p className="text-fg-muted text-xs">가드레일 또는 폴백 응답입니다.</p>}</section>}
        </CardBody></Card>
      </div>
    </PortalShell>
  );
}
