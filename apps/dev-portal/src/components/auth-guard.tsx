"use client";

import { useRouter } from "next/navigation";
import * as React from "react";

import { usePortalSession } from "@/lib/session";

export function PortalAuthGuard({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { ready, token } = usePortalSession();
  React.useEffect(() => {
    if (ready && !token) router.replace("/login");
  }, [ready, token, router]);
  if (!ready || !token) return <div className="text-fg-muted p-10 text-sm">포털을 준비하고 있습니다.</div>;
  return children;
}
