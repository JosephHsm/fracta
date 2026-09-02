"use client";

import { Badge } from "@fracta/ui";
import { ArrowLeft } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { useParams } from "next/navigation";
import * as React from "react";

import { AppShell } from "@/components/app-shell";
import { ProspectusAiPanel } from "@/components/prospectus-ai-panel";
import { ProspectusViewer } from "@/components/prospectus-viewer";
import { useIssuances } from "@/lib/queries";

/**
 * 투자설명서 화면 (FSD §11.2) — PDF 뷰어 + AI 질의 사이드패널.
 *
 * <p>인용(`p.N`)을 클릭하면 뷰어가 해당 페이지로 이동한다. 페이지 번호는 화면이
 * 만들어내는 값이 아니라 서버 응답의 `citedPages`를 그대로 쓴다 —
 * 답변 본문을 파싱해 추측하면 인용과 이동이 어긋난다.
 */
export default function ProspectusPage() {
  const params = useParams<{ id: string }>();
  const issuanceId = Number(params.id);

  const { data: issuances } = useIssuances();
  const issuance = (issuances ?? []).find((item) => item.issuanceId === issuanceId);

  const [page, setPage] = React.useState(1);

  return (
    <AppShell>
      <div className="flex flex-col gap-6">
        <Link
          href={`/issuances/${issuanceId}` as Route}
          className="text-fg-muted hover:text-fg inline-flex w-fit items-center gap-1.5 text-sm"
        >
          <ArrowLeft aria-hidden className="size-4" />
          청약 화면으로
        </Link>

        <header className="flex flex-wrap items-baseline gap-3">
          <h1 className="text-xl font-semibold tracking-tight">투자설명서</h1>
          <span className="text-fg-muted text-sm">
            {issuance?.assetName ?? ""} <span className="fr-numeric">{issuance?.tokenSymbol}</span>
          </span>
          <Badge tone="neutral" className="ml-auto">
            {page}쪽 보는 중
          </Badge>
        </header>

        <div className="grid gap-4 lg:grid-cols-3">
          <div className="lg:col-span-2">
            <ProspectusViewer issuanceId={issuanceId} page={page} />
          </div>
          <div className="lg:col-span-1">
            <ProspectusAiPanel issuanceId={issuanceId} onCitationClick={setPage} />
          </div>
        </div>
      </div>
    </AppShell>
  );
}
