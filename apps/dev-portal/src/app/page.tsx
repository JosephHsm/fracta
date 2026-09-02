"use client";

import { BentoGrid, BentoItem, Card, CardBody, CardHeader, CardTitle, ProgressBar, StatTile, formatNumber, formatPercent } from "@fracta/ui";
import { useQuery } from "@tanstack/react-query";
import { Activity, Clock3, Gauge, ShieldAlert } from "lucide-react";
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

import { NoClient } from "@/components/no-client";
import { PageHeading } from "@/components/page-heading";
import { PortalShell } from "@/components/portal-shell";
import { useLatestQuota } from "@/lib/quota-store";
import { useSelectedClient } from "@/lib/selected-client";
import { usePortalSession } from "@/lib/session";

export default function DashboardPage() {
  const { client } = usePortalSession();
  const { selected, loading } = useSelectedClient();
  const quotaHeader = useLatestQuota();
  const query = useQuery({
    queryKey: ["developer-dashboard", selected?.clientId],
    enabled: Boolean(selected?.clientId),
    queryFn: async () => (await client.developer.dashboard({ clientId: selected?.clientId ?? "" })).data,
  });
  const data = query.data;
  const dailyQuota = data?.quota;
  const dailyPercent = dailyQuota?.perDayLimit ? ((dailyQuota.usedToday ?? 0) / dailyQuota.perDayLimit) * 100 : 0;
  const liveQuota = quotaHeader.clientId === selected?.clientId && quotaHeader.limit != null && quotaHeader.remaining != null ? quotaHeader : null;
  const livePercent = liveQuota?.limit ? ((liveQuota.limit - (liveQuota.remaining ?? 0)) / liveQuota.limit) * 100 : 0;

  return (
    <PortalShell>
      <PageHeading title="API 대시보드" description={selected ? `${selected.name}의 최근 7일 호출 상태입니다.` : "호출량과 쿼터 상태를 확인합니다."} />
      {!loading && !selected ? <NoClient /> : (
        <BentoGrid>
          <BentoItem span={3}><StatTile label="오늘 호출" value={formatNumber(data?.callsToday)} hint="자정부터 현재까지" icon={<Activity aria-hidden className="size-4" />} /></BentoItem>
          <BentoItem span={3}><StatTile label="최근 7일" value={formatNumber(data?.callsLastSevenDays)} hint="전체 API 요청" icon={<Gauge aria-hidden className="size-4" />} /></BentoItem>
          <BentoItem span={3}><StatTile label="에러율" value={formatPercent(data?.errorRatePercent)} hint="HTTP 4xx·5xx" icon={<ShieldAlert aria-hidden className="size-4" />} /></BentoItem>
          <BentoItem span={3}><StatTile label="평균 지연" value={`${formatNumber(data?.averageLatencyMs)}ms`} hint="최근 7일" icon={<Clock3 aria-hidden className="size-4" />} /></BentoItem>
          <BentoItem span={8}>
            <Card className="h-full"><CardHeader><CardTitle>일별 호출량</CardTitle></CardHeader><CardBody className="h-72">
              <ResponsiveContainer width="100%" height="100%"><AreaChart data={data?.dailyUsage ?? []} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}><defs><linearGradient id="calls" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="var(--fr-chart-1)" stopOpacity={0.35} /><stop offset="100%" stopColor="var(--fr-chart-1)" stopOpacity={0.02} /></linearGradient></defs><CartesianGrid stroke="var(--fr-border)" vertical={false} /><XAxis dataKey="date" tick={{ fill: "var(--fr-text-muted)", fontSize: 11 }} tickFormatter={(value) => String(value).slice(5)} /><YAxis allowDecimals={false} tick={{ fill: "var(--fr-text-muted)", fontSize: 11 }} /><Tooltip contentStyle={{ background: "var(--fr-surface-elevated)", border: "1px solid var(--fr-border)", borderRadius: "var(--fr-radius-md)" }} /><Area type="monotone" dataKey="calls" name="호출" stroke="var(--fr-chart-1)" fill="url(#calls)" strokeWidth={2} /></AreaChart></ResponsiveContainer>
            </CardBody></Card>
          </BentoItem>
          <BentoItem span={4}>
            <Card className="h-full"><CardHeader><CardTitle>쿼터 사용률</CardTitle></CardHeader><CardBody className="flex flex-col gap-6">
              <ProgressBar value={dailyPercent} label="일일 호출" tone={dailyPercent >= 80 ? "warning" : "accent"} /><p className="text-fg-muted -mt-4 text-xs">{formatNumber(dailyQuota?.usedToday)} / {formatNumber(dailyQuota?.perDayLimit)}건</p>
              <div className="border-border border-t pt-5"><ProgressBar value={livePercent} label="최근 응답의 초당 쿼터" tone={livePercent >= 80 ? "warning" : "success"} /><p className="text-fg-muted mt-2 text-xs">{liveQuota ? `X-RateLimit-Remaining: ${liveQuota.remaining} / ${liveQuota.limit}` : "샌드박스에서 호출하면 X-RateLimit 헤더를 실시간으로 표시합니다."}</p></div>
            </CardBody></Card>
          </BentoItem>
        </BentoGrid>
      )}
    </PortalShell>
  );
}
