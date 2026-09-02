"use client";

import type { ClientView, IssuedClientView } from "@fracta/api-client";
import { Badge, Button, Card, CardBody, Field, TextInput, inputClassName, useToast } from "@fracta/ui";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Plus } from "lucide-react";
import * as React from "react";

import { PageHeading } from "@/components/page-heading";
import { PortalShell } from "@/components/portal-shell";
import { SecretReveal } from "@/components/secret-reveal";
import { useSelectedClient } from "@/lib/selected-client";
import { usePortalSession } from "@/lib/session";

const SCOPES = [
  { value: "MARKET_READ", label: "market:read", description: "종목·호가·체결·괴리율 조회" },
  { value: "ACCOUNT_READ", label: "account:read", description: "잔고·주문 내역 조회" },
  { value: "ORDER_WRITE", label: "order:write", description: "주문 생성·취소" },
  { value: "SUBSCRIPTION_WRITE", label: "subscription:write", description: "청약 신청" },
] as const;
type ScopeValue = (typeof SCOPES)[number]["value"];

export default function AppsPage() {
  const { client } = usePortalSession();
  const { clients, selectedId, select } = useSelectedClient();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [name, setName] = React.useState("결제 연동 샌드박스");
  const [env, setEnv] = React.useState<"SANDBOX" | "LIVE">("SANDBOX");
  const [scopes, setScopes] = React.useState<Set<ScopeValue>>(new Set(["MARKET_READ"]));
  const [issued, setIssued] = React.useState<IssuedClientView | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["developer-clients"] });
  const register = useMutation({
    mutationFn: async () => (await client.developer.registerClient({ developerCreateClientRequest: { name, env, scopes } })).data,
    onSuccess: async (result) => {
      if (result?.clientSecret) setIssued(result);
      await refresh();
      if (result?.clientId) select(result.clientId);
    },
  });
  const rotate = useMutation({
    mutationFn: async (clientId: string) => (await client.developer.rotateSecret({ clientId })).data,
    onSuccess: (result) => { if (result?.clientSecret) setIssued(result); },
  });

  const toggle = (value: ScopeValue) => setScopes((current) => {
    const next = new Set(current);
    if (next.has(value)) next.delete(value); else next.add(value);
    return next;
  });

  return (
    <PortalShell>
      <PageHeading title="앱 관리" description="클라이언트를 등록하고 환경과 Scope를 관리합니다." />
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_23rem]">
        <section className="flex flex-col gap-4" aria-label="등록된 앱">
          {issued?.clientSecret && <SecretReveal label="client_secret" value={issued.clientSecret} onDone={() => setIssued(null)} />}
          {clients.length === 0 ? <Card><CardBody className="text-fg-muted py-10 text-center text-sm">아직 등록된 앱이 없습니다.</CardBody></Card> : clients.map((app) => (
            <AppCard key={app.clientId} app={app} selected={app.clientId === selectedId}
              onSelect={() => app.clientId && select(app.clientId)}
              onRotate={() => app.clientId && rotate.mutate(app.clientId)}
              onUpdated={() => { refresh(); toast.show({ title: "Scope를 저장했습니다", tone: "success" }); }} />
          ))}
        </section>

        <Card className="h-fit xl:sticky xl:top-24"><CardBody>
          <form className="flex flex-col gap-5" onSubmit={(event) => { event.preventDefault(); if (scopes.size > 0) register.mutate(); }}>
            <div><h2 className="font-semibold">새 앱 등록</h2><p className="text-fg-muted mt-1 text-xs">처음에는 SANDBOX 환경을 권장합니다.</p></div>
            <Field label="앱 이름" required><TextInput value={name} onChange={(event) => setName(event.target.value)} maxLength={100} required /></Field>
            <Field label="환경" description={env === "LIVE" ? "LIVE 키는 샌드박스 콘솔에서 사용할 수 없습니다." : "가상 데이터와 격리된 계정을 사용합니다."}>
              <select className={inputClassName} value={env} onChange={(event) => setEnv(event.target.value as "SANDBOX" | "LIVE")}><option value="SANDBOX">SANDBOX</option><option value="LIVE">LIVE</option></select>
            </Field>
            <ScopeChecks selected={scopes} onToggle={toggle} />
            <Button type="submit" block state={register.isPending ? "pending" : "idle"} disabled={scopes.size === 0}><Plus aria-hidden className="size-4" />앱 등록</Button>
          </form>
        </CardBody></Card>
      </div>
    </PortalShell>
  );
}

function AppCard({ app, selected, onSelect, onRotate, onUpdated }: { app: ClientView; selected: boolean; onSelect: () => void; onRotate: () => void; onUpdated: () => void }) {
  const { client } = usePortalSession();
  const initial = React.useMemo(() => new Set(Array.from(app.scopes ?? [])) as Set<ScopeValue>, [app.scopes]);
  const [scopes, setScopes] = React.useState(initial);
  const update = useMutation({ mutationFn: () => client.developer.updateScopes({ clientId: app.clientId ?? "", developerUpdateScopesRequest: { scopes } }), onSuccess: onUpdated });
  const changed = SCOPES.some(({ value }) => scopes.has(value) !== initial.has(value));
  return (
    <Card elevated={selected} className={selected ? "border-border-strong" : undefined}><CardBody className="flex flex-col gap-5">
      <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-start"><div><div className="flex items-center gap-2"><h2 className="font-semibold">{app.name}</h2><Badge tone={app.env === "SANDBOX" ? "info" : "warning"}>{app.env}</Badge>{selected && <Badge tone="accent">현재 앱</Badge>}</div><code className="text-fg-muted mt-1 block text-xs">{app.clientId}</code></div><div className="flex gap-2"><Button size="sm" variant="secondary" onClick={onSelect}>선택</Button><Button size="sm" variant="secondary" onClick={onRotate}><KeyRound aria-hidden className="size-3.5" />시크릿 재발급</Button></div></div>
      <div className="bg-surface-sunken border-border rounded-md border p-3"><p className="text-fg-muted text-xs">client_secret</p><code className="mt-1 block text-sm">sec_••••••••••••••••••••</code></div>
      <ScopeChecks selected={scopes} onToggle={(value) => setScopes((current) => { const next = new Set(current); if (next.has(value)) next.delete(value); else next.add(value); return next; })} />
      <div className="flex items-center justify-between gap-3"><p className="text-fg-muted text-xs">초당 {app.rateLimitPerSec}건 · 일 {app.rateLimitPerDay?.toLocaleString("ko-KR")}건</p><Button size="sm" variant="secondary" disabled={!changed || scopes.size === 0} state={update.isPending ? "pending" : "idle"} onClick={() => update.mutate()}>Scope 저장</Button></div>
    </CardBody></Card>
  );
}

function ScopeChecks({ selected, onToggle }: { selected: Set<ScopeValue>; onToggle: (value: ScopeValue) => void }) {
  return <fieldset className="flex flex-col gap-2"><legend className="mb-2 text-sm font-medium">Scope</legend>{SCOPES.map((scope) => <label key={scope.value} className="border-border hover:bg-surface-sunken flex cursor-pointer items-start gap-3 rounded-md border p-3"><input type="checkbox" className="accent-accent mt-0.5 size-4" checked={selected.has(scope.value)} onChange={() => onToggle(scope.value)} /><span><span className="block font-mono text-xs font-medium">{scope.label}</span><span className="text-fg-muted text-xs">{scope.description}</span></span></label>)}</fieldset>;
}
