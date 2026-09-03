"use client";

import { Badge, Button, Card, CardBody, Field, Table, TableContainer, TableEmpty, Tbody, Td, TextInput, Th, Thead, Tr, formatDateTime, inputClassName } from "@fracta/ui";
import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import * as React from "react";

import { NoClient } from "@/components/no-client";
import { PageHeading } from "@/components/page-heading";
import { PortalShell } from "@/components/portal-shell";
import { useSelectedClient } from "@/lib/selected-client";
import { usePortalSession } from "@/lib/session";

interface Filters { endpoint: string; statusCode: string; from: string; to: string }
const initialDate = () => {
  const now = new Date();
  const to = now.toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" });
  const fromDate = new Date(now.getTime() - 6 * 86_400_000);
  const from = fromDate.toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" });
  return { endpoint: "", statusCode: "", from, to };
};

export default function LogsPage() {
  const { client } = usePortalSession();
  const { selected, loading } = useSelectedClient();
  const [form, setForm] = React.useState<Filters>(initialDate);
  const [filters, setFilters] = React.useState<Filters>(form);
  const query = useQuery({
    queryKey: ["developer-logs", selected?.clientId, filters],
    enabled: Boolean(selected?.clientId),
    queryFn: async () => (await client.developer.logs({
      clientId: selected?.clientId ?? "",
      endpoint: filters.endpoint || undefined,
      statusCode: filters.statusCode ? Number(filters.statusCode) : undefined,
      from: new Date(`${filters.from}T00:00:00+09:00`),
      to: new Date(`${filters.to}T23:59:59.999+09:00`),
    })).data ?? [],
  });
  const set = (key: keyof Filters) => (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setForm((current) => ({ ...current, [key]: event.target.value }));

  return (
    <PortalShell>
      <PageHeading title="호출 로그" description="엔드포인트·상태 코드·기간으로 최근 200건을 검색합니다." />
      {!loading && !selected ? <NoClient /> : <div className="flex flex-col gap-5">
        <Card><CardBody><form className="grid gap-4 md:grid-cols-2 xl:grid-cols-[minmax(14rem,1fr)_10rem_11rem_11rem_auto] xl:items-end" onSubmit={(event) => { event.preventDefault(); setFilters(form); }}>
          <Field label="엔드포인트"><TextInput placeholder="/open/sandbox/v1/tokens" value={form.endpoint} onChange={set("endpoint")} /></Field>
          <Field label="상태 코드"><select className={inputClassName} value={form.statusCode} onChange={set("statusCode")}><option value="">전체</option><option value="200">200</option><option value="201">201</option><option value="400">400</option><option value="401">401</option><option value="403">403</option><option value="409">409</option><option value="429">429</option><option value="500">500</option></select></Field>
          <Field label="시작일"><TextInput type="date" value={form.from} onChange={set("from")} /></Field>
          <Field label="종료일"><TextInput type="date" value={form.to} onChange={set("to")} /></Field>
          <Button type="submit"><Search aria-hidden className="size-4" />검색</Button>
        </form></CardBody></Card>
        <TableContainer><Table><Thead><Tr><Th>호출 시각</Th><Th>메서드</Th><Th>엔드포인트</Th><Th align="right">상태</Th><Th align="right">지연</Th><Th>멱등성 키</Th></Tr></Thead><Tbody>{(query.data ?? []).map((log) => <Tr key={log.id}><Td className="text-fg-muted whitespace-nowrap">{formatDateTime(log.calledAt)}</Td><Td><Badge>{log.method}</Badge></Td><Td><code className="text-xs">{log.endpoint}</code></Td><Td align="right"><HttpStatusBadge code={log.statusCode ?? 0} /></Td><Td align="right" className="fr-numeric">{log.latencyMs}ms</Td><Td className="text-fg-muted max-w-48 truncate font-mono text-xs">{log.idempotencyKey ?? "—"}</Td></Tr>)}</Tbody></Table>{(query.data ?? []).length === 0 && <TableEmpty>{emptyMessage(filters, initialDate())}</TableEmpty>}</TableContainer>
      </div>}
    </PortalShell>
  );
}

/**
 * 빈 표의 이유를 구분해서 알려준다.
 *
 * <p>"조건에 맞는 호출 로그가 없습니다" 하나로는 <b>앱을 잘못 골랐는지</b> 필터가 좁은지
 * 알 수 없다. 앱 선택은 저장된 값이 없으면 목록 첫 번째로 떨어지므로, 방금 호출한 앱이
 * 아닐 때가 많다 — 실제로 시연 중에 빈 표가 떠서 막혔다.
 */
function emptyMessage(applied: Filters, defaults: Filters) {
  const filtered = applied.endpoint || applied.statusCode
    || applied.from !== defaults.from || applied.to !== defaults.to;
  return filtered
    ? "조건에 맞는 호출 로그가 없습니다. 기간이나 엔드포인트를 넓혀 보세요."
    : "이 앱으로 들어온 호출이 아직 없습니다. 우측 상단에서 앱을 바꾸거나 샌드박스에서 호출해 보세요.";
}

function HttpStatusBadge({ code }: { code: number }) {
  const tone = code >= 500 ? "danger" : code >= 400 ? "warning" : code >= 200 && code < 300 ? "success" : "neutral";
  return <Badge tone={tone}>{code}</Badge>;
}
