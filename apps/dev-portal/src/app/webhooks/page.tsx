"use client";

import type { IssuedWebhookView, WebhookDeliveryView, WebhookView } from "@fracta/api-client";
import { Badge, Button, Card, CardBody, Field, Table, TableContainer, TableEmpty, Tbody, Td, TextInput, Th, Thead, Tr, formatDateTime } from "@fracta/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw, Webhook as WebhookIcon } from "lucide-react";
import * as React from "react";

import { NoClient } from "@/components/no-client";
import { PageHeading } from "@/components/page-heading";
import { PortalShell } from "@/components/portal-shell";
import { SecretReveal } from "@/components/secret-reveal";
import { useSelectedClient } from "@/lib/selected-client";
import { usePortalSession } from "@/lib/session";

const EVENTS = ["order.filled", "order.partially_filled", "order.cancelled", "subscription.allotted", "token.listed", "token.suspended"] as const;
type DeliveryStatus = NonNullable<WebhookDeliveryView["status"]>;
const STATUS: Record<DeliveryStatus, { label: string; tone: "neutral" | "success" | "danger" | "warning" }> = {
  PENDING: { label: "발송 대기", tone: "warning" },
  DELIVERED: { label: "발송 완료", tone: "success" },
  DEAD: { label: "최종 실패", tone: "danger" },
};

export default function WebhooksPage() {
  const { client } = usePortalSession();
  const { selected, loading } = useSelectedClient();
  const queryClient = useQueryClient();
  const [url, setUrl] = React.useState("https://example.com/fracta-hook");
  const [events, setEvents] = React.useState<Set<string>>(new Set(["order.filled"]));
  const [issued, setIssued] = React.useState<IssuedWebhookView | null>(null);
  const [webhookId, setWebhookId] = React.useState<number | null>(null);
  const webhooks = useQuery({
    queryKey: ["developer-webhooks", selected?.clientId],
    enabled: Boolean(selected?.clientId),
    queryFn: async () => (await client.developer.webhooks({ clientId: selected?.clientId ?? "" })).data ?? [],
  });
  const activeWebhookId = webhooks.data?.some((item) => item.webhookId === webhookId)
    ? webhookId
    : webhooks.data?.[0]?.webhookId ?? null;
  const history = useQuery({
    queryKey: ["webhook-deliveries", activeWebhookId],
    enabled: activeWebhookId != null,
    queryFn: async () => (await client.developer.deliveries({ webhookId: activeWebhookId ?? 0 })).data ?? [],
  });
  const register = useMutation({
    mutationFn: async () => (await client.developer.registerDeveloperWebhook({ developerCreateWebhookRequest: { clientId: selected?.clientId ?? "", url, events } })).data,
    onSuccess: async (result) => {
      if (result?.webhookSecret) setIssued(result);
      await queryClient.invalidateQueries({ queryKey: ["developer-webhooks", selected?.clientId] });
      if (result?.webhookId) setWebhookId(result.webhookId);
    },
  });
  const redeliver = useMutation({
    mutationFn: (deliveryId: number) => client.developer.redeliver({ deliveryId }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["webhook-deliveries", activeWebhookId] }),
  });
  const toggle = (eventName: string) => setEvents((current) => { const next = new Set(current); if (next.has(eventName)) next.delete(eventName); else next.add(eventName); return next; });

  return (
    <PortalShell>
      <PageHeading title="웹훅" description="이벤트 수신 URL을 등록하고 발송 이력을 재처리합니다." />
      {!loading && !selected ? <NoClient /> : <div className="flex flex-col gap-6">
        {issued?.webhookSecret && <SecretReveal label="webhook_secret" value={issued.webhookSecret} onDone={() => setIssued(null)} />}
        <div className="grid gap-6 xl:grid-cols-[24rem_minmax(0,1fr)]">
          <Card className="h-fit"><CardBody><form className="flex flex-col gap-5" onSubmit={(event) => { event.preventDefault(); if (events.size > 0) register.mutate(); }}>
            <div><h2 className="font-semibold">웹훅 등록</h2><p className="text-fg-muted mt-1 text-xs">서명 시크릿은 등록 직후 한 번만 표시합니다.</p></div>
            <Field label="수신 URL" required><TextInput type="url" value={url} onChange={(event) => setUrl(event.target.value)} required /></Field>
            <fieldset className="flex flex-col gap-2"><legend className="mb-1 text-sm font-medium">이벤트</legend>{EVENTS.map((eventName) => <label key={eventName} className="text-fg-muted flex items-center gap-2 text-xs"><input type="checkbox" className="accent-accent size-4" checked={events.has(eventName)} onChange={() => toggle(eventName)} /><code>{eventName}</code></label>)}</fieldset>
            <Button type="submit" block disabled={events.size === 0} state={register.isPending ? "pending" : "idle"}><WebhookIcon aria-hidden className="size-4" />등록</Button>
          </form></CardBody></Card>
          <section className="flex min-w-0 flex-col gap-3" aria-label="등록된 웹훅">
            <h2 className="text-sm font-semibold">등록된 엔드포인트</h2>
            {(webhooks.data ?? []).length === 0 ? <Card><CardBody className="text-fg-muted py-10 text-center text-sm">등록된 웹훅이 없습니다.</CardBody></Card> : (webhooks.data ?? []).map((webhook) => <WebhookCard key={webhook.webhookId} webhook={webhook} selected={webhook.webhookId === activeWebhookId} onSelect={() => setWebhookId(webhook.webhookId ?? null)} />)}
          </section>
        </div>
        <section><h2 className="mb-3 text-sm font-semibold">발송 이력</h2><TableContainer><Table><Thead><Tr><Th>시각</Th><Th>이벤트</Th><Th>상태</Th><Th align="right">시도</Th><Th>오류</Th><Th align="right">작업</Th></Tr></Thead><Tbody>{(history.data ?? []).map((delivery) => { const status = STATUS[delivery.status ?? "PENDING"]; return <Tr key={delivery.deliveryId}><Td className="text-fg-muted whitespace-nowrap">{formatDateTime(delivery.createdAt)}</Td><Td><code className="text-xs">{delivery.eventType}</code></Td><Td><Badge tone={status.tone}>{status.label}</Badge></Td><Td align="right" className="fr-numeric">{delivery.attempts}</Td><Td className="text-fg-muted max-w-xs truncate">{delivery.lastError ?? "—"}</Td><Td align="right">{delivery.status === "DEAD" && <Button size="sm" variant="secondary" state={redeliver.isPending ? "pending" : "idle"} onClick={() => delivery.deliveryId && redeliver.mutate(delivery.deliveryId)}><RefreshCw aria-hidden className="size-3.5" />재발송</Button>}</Td></Tr>; })}</Tbody></Table>{(history.data ?? []).length === 0 && <TableEmpty>발송 이력이 없습니다.</TableEmpty>}</TableContainer></section>
      </div>}
    </PortalShell>
  );
}

function WebhookCard({ webhook, selected, onSelect }: { webhook: WebhookView; selected: boolean; onSelect: () => void }) {
  return <button type="button" onClick={onSelect} className={`border-border bg-surface hover:bg-surface-sunken w-full rounded-lg border p-4 text-left transition-colors duration-(--fr-motion-hover) ${selected ? "ring-focus ring-2" : ""}`}><div className="flex items-center justify-between gap-3"><span className="truncate text-sm font-medium">{webhook.url}</span><Badge tone={webhook.active ? "success" : "neutral"}>{webhook.active ? "활성" : "비활성"}</Badge></div><div className="mt-3 flex flex-wrap gap-1.5">{Array.from(webhook.events ?? []).map((eventName) => <Badge key={eventName}>{eventName}</Badge>)}</div></button>;
}
