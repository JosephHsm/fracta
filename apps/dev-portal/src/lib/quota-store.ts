"use client";

import * as React from "react";

interface QuotaSnapshot {
  clientId: string | null;
  limit: number | null;
  remaining: number | null;
  reset: number | null;
}

const EMPTY: QuotaSnapshot = { clientId: null, limit: null, remaining: null, reset: null };
let snapshot = EMPTY;
const listeners = new Set<() => void>();

export const quotaStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
  getSnapshot: () => snapshot,
  getServerSnapshot: () => EMPTY,
  capture(clientId: string, headers: Headers) {
    const number = (name: string) => {
      const value = headers.get(name);
      return value == null ? null : Number(value);
    };
    snapshot = {
      clientId,
      limit: number("X-RateLimit-Limit"),
      remaining: number("X-RateLimit-Remaining"),
      reset: number("X-RateLimit-Reset"),
    };
    listeners.forEach((listener) => listener());
  },
};

export function useLatestQuota() {
  return React.useSyncExternalStore(quotaStore.subscribe, quotaStore.getSnapshot, quotaStore.getServerSnapshot);
}
