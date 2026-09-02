"use client";

import { Button } from "@fracta/ui";
import { AlertTriangle, X } from "lucide-react";
import * as React from "react";

import { apiErrorStore } from "@/lib/api-error-store";

/**
 * 조회 실패 배너.
 *
 * <p>빈 목록과 통신 실패는 다른 상태다. 실패를 조용히 빈 화면으로 보여주면
 * 사용자는 "데이터가 없구나"로 읽고, 개발자는 버그를 못 본다.
 */
export function ApiErrorBanner() {
  const { message } = React.useSyncExternalStore(
    apiErrorStore.subscribe,
    apiErrorStore.getSnapshot,
    apiErrorStore.getServerSnapshot,
  );

  if (!message) return null;

  return (
    <div
      role="alert"
      className="border-danger/30 bg-danger-soft text-danger mb-6 flex items-start gap-3 rounded-lg border p-3 text-sm"
    >
      <AlertTriangle aria-hidden className="mt-0.5 size-4 shrink-0" />
      <p className="flex-1">{message}</p>
      <Button
        variant="ghost"
        size="sm"
        onClick={() => apiErrorStore.clear()}
        aria-label="알림 닫기"
        className="text-danger -my-1"
      >
        <X aria-hidden className="size-4" />
      </Button>
    </div>
  );
}
