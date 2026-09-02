"use client";

import { FractaApiError, createFractaClient } from "@fracta/api-client";
import { Badge, Button, Card, CardBody, Field, TextInput, errorMessage, inputClassName } from "@fracta/ui";
import { AlertTriangle, Play, Terminal } from "lucide-react";
import Link from "next/link";
import * as React from "react";

import { NoClient } from "@/components/no-client";
import { PageHeading } from "@/components/page-heading";
import { PortalShell } from "@/components/portal-shell";
import { quotaStore } from "@/lib/quota-store";
import { useSelectedClient } from "@/lib/selected-client";
import { usePortalSession } from "@/lib/session";

type Endpoint = "tokens" | "balance" | "orders";
const ENDPOINTS: Record<Endpoint, { method: "GET"; path: string; label: string }> = {
  tokens: { method: "GET", path: "/open/sandbox/v1/tokens", label: "종목 목록" },
  balance: { method: "GET", path: "/open/sandbox/v1/accounts/balance", label: "계좌 잔고" },
  orders: { method: "GET", path: "/open/sandbox/v1/accounts/orders", label: "주문 내역" },
};

export default function SandboxPage() {
  const { client } = usePortalSession();
  const { selected, loading } = useSelectedClient();
  const [secret, setSecret] = React.useState("");
  const [endpoint, setEndpoint] = React.useState<Endpoint>("tokens");
  const [result, setResult] = React.useState<string>("호출 결과가 여기에 표시됩니다.");
  const [headers, setHeaders] = React.useState<Record<string, string>>({});
  const [error, setError] = React.useState<string | null>(null);
  const [pending, setPending] = React.useState(false);

  const call = async () => {
    if (!selected?.clientId || selected.env !== "SANDBOX") return;
    setPending(true);
    setError(null);
    try {
      const tokenResponse = await client.open.token({
        grantType: "client_credentials",
        clientId: selected.clientId,
        clientSecret: secret,
      });
      const accessToken = tokenResponse.data?.accessToken;
      if (!accessToken) throw new Error("액세스 토큰이 없습니다");
      const sandbox = createFractaClient({ basePath: "", getAccessToken: () => accessToken }).open;
      const raw = endpoint === "tokens" ? await sandbox.tokensRaw()
        : endpoint === "balance" ? await sandbox.balanceRaw()
          : await sandbox.ordersRaw();
      const value = await raw.value();
      quotaStore.capture(selected.clientId, raw.raw.headers);
      setHeaders({
        "X-RateLimit-Limit": raw.raw.headers.get("X-RateLimit-Limit") ?? "—",
        "X-RateLimit-Remaining": raw.raw.headers.get("X-RateLimit-Remaining") ?? "—",
        "X-RateLimit-Reset": raw.raw.headers.get("X-RateLimit-Reset") ?? "—",
      });
      setResult(JSON.stringify(value, null, 2));
    } catch (caught) {
      setError(caught instanceof FractaApiError ? errorMessage(caught.code) : "호출에 실패했습니다. 입력값을 확인해 주세요.");
    } finally {
      setPending(false);
    }
  };

  return (
    <PortalShell>
      <PageHeading title="샌드박스 콘솔" description="브라우저에서 SANDBOX API를 호출하고 쿼터 헤더를 확인합니다." />
      {!loading && !selected ? <NoClient /> : selected?.env === "LIVE" ? (
        <Card className="border-danger/30"><CardBody className="flex flex-col items-start gap-4"><div className="flex items-start gap-3"><AlertTriangle aria-hidden className="text-danger mt-0.5 size-5" /><div><h2 className="font-semibold">LIVE 키는 이 콘솔에서 사용할 수 없습니다</h2><p className="text-fg-muted mt-1 text-sm">실데이터 오호출을 막기 위해 샌드박스 앱만 선택할 수 있습니다.</p></div></div><Link href="/apps"><Button variant="secondary">SANDBOX 앱 선택</Button></Link></CardBody></Card>
      ) : (
        <div className="grid gap-6 xl:grid-cols-[25rem_minmax(0,1fr)]">
          <Card className="h-fit"><CardBody className="flex flex-col gap-5">
            <div className="flex items-center gap-2"><Badge tone="info">SANDBOX</Badge><code className="text-fg-muted truncate text-xs">{selected?.clientId}</code></div>
            <Field label="client_secret" description="브라우저 메모리에만 머물며 저장하지 않습니다." error={error ?? undefined} required><TextInput type="password" value={secret} onChange={(event) => setSecret(event.target.value)} placeholder="sec_..." autoComplete="off" /></Field>
            <Field label="엔드포인트"><select className={inputClassName} value={endpoint} onChange={(event) => setEndpoint(event.target.value as Endpoint)}>{Object.entries(ENDPOINTS).map(([key, value]) => <option key={key} value={key}>{value.label}</option>)}</select></Field>
            <div className="bg-surface-sunken border-border rounded-md border p-3 font-mono text-xs"><span className="text-success">{ENDPOINTS[endpoint].method}</span> {ENDPOINTS[endpoint].path}</div>
            <Button block state={pending ? "pending" : "idle"} disabled={!secret} onClick={call}><Play aria-hidden className="size-4" />요청 보내기</Button>
          </CardBody></Card>
          <div className="flex min-w-0 flex-col gap-4">
            <Card><CardBody className="flex flex-wrap gap-x-6 gap-y-2 text-xs">{Object.keys(headers).length === 0 ? <span className="text-fg-muted">응답 후 X-RateLimit-* 헤더가 표시됩니다.</span> : Object.entries(headers).map(([name, value]) => <span key={name}><span className="text-fg-muted">{name}</span> <strong className="fr-numeric ml-1">{value}</strong></span>)}</CardBody></Card>
            <Card className="min-h-96 overflow-hidden"><div className="border-border bg-surface-sunken flex items-center gap-2 border-b px-4 py-3 text-xs font-medium"><Terminal aria-hidden className="size-4" />응답 본문</div><pre className="max-h-[40rem] overflow-auto p-5 text-xs leading-6"><code>{result}</code></pre></Card>
          </div>
        </div>
      )}
    </PortalShell>
  );
}
