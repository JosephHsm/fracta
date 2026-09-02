"use client";

import { Button } from "@fracta/ui";
import { AlertCircle } from "lucide-react";
import * as React from "react";

import { portalApiErrorStore } from "@/lib/api-error-store";

export function PortalApiErrorBanner() {
  const message = React.useSyncExternalStore(
    portalApiErrorStore.subscribe,
    portalApiErrorStore.getSnapshot,
    portalApiErrorStore.getServerSnapshot,
  );
  if (!message) return null;
  return (
    <div role="alert" className="border-danger/30 bg-danger-soft text-danger mb-6 flex items-center gap-3 rounded-lg border p-4 text-sm">
      <AlertCircle aria-hidden className="size-4 shrink-0" />
      <span className="flex-1">{message}</span>
      <Button size="sm" variant="ghost" onClick={() => portalApiErrorStore.set(null)}>닫기</Button>
    </div>
  );
}
