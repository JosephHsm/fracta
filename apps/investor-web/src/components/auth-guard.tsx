"use client";

import { Skeleton } from "@fracta/ui";
import { Building2 } from "lucide-react";
import * as React from "react";

import { useRequireSession } from "@/lib/session";

/**
 * 로그인이 필요한 화면의 공통 껍데기.
 *
 * <p>세션 복원(localStorage 읽기)은 하이드레이션 이후에야 끝난다. 그때까지 화면이
 * <b>아무것도 렌더하지 않으면 다크 모드에서 통째로 검은 화면</b>이 되고, JS가 죽으면
 * 영원히 검은 채로 남는다. 실제로 그 사고를 냈다 — 그래서 항상 무언가를 그린다.
 *
 * <p>세 상태를 구분한다.
 * ① 복원 중 → 스켈레톤 (레이아웃이 흔들리지 않게 실제 화면과 같은 골격)
 * ② 로그아웃 확정 → 이동 안내 (useRequireSession이 /login으로 보낸다)
 * ③ 로그인됨 → children
 */
export function AuthGuard({ children }: { children: React.ReactNode }) {
  const session = useRequireSession();

  if (!session.ready) return <RestoringSession />;
  if (!session.token) return <RedirectingToLogin />;

  return <>{children}</>;
}

function RestoringSession() {
  return (
    <div className="min-h-dvh" aria-busy="true" aria-live="polite">
      <span className="sr-only">로그인 정보를 확인하고 있습니다</span>

      <div className="border-border bg-surface border-b">
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-6 px-6">
          <Building2 aria-hidden className="text-accent size-5" />
          <Skeleton className="h-4 w-24" />
          <Skeleton className="ml-auto h-8 w-28" />
        </div>
      </div>

      <div className="mx-auto grid max-w-7xl gap-4 px-6 py-8 md:grid-cols-3">
        {[0, 1, 2].map((index) => (
          <Skeleton key={index} className="h-28 w-full rounded-lg" />
        ))}
        <Skeleton className="h-64 w-full rounded-lg md:col-span-3" />
      </div>
    </div>
  );
}

function RedirectingToLogin() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-3" aria-live="polite">
      <Building2 aria-hidden className="text-accent size-6" />
      <p className="text-fg-muted text-sm">로그인 화면으로 이동합니다…</p>
    </div>
  );
}
