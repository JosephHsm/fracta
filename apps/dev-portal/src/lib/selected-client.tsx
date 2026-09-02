"use client";

import type { ClientView } from "@fracta/api-client";
import { useQuery } from "@tanstack/react-query";
import * as React from "react";

import { usePortalSession } from "./session";

const STORAGE_KEY = "fracta-portal-client";
const selectionListeners = new Set<() => void>();

const selectionStore = {
  subscribe(listener: () => void) {
    selectionListeners.add(listener);
    return () => selectionListeners.delete(listener);
  },
  getSnapshot: () => localStorage.getItem(STORAGE_KEY),
  getServerSnapshot: () => null,
  set(clientId: string) {
    localStorage.setItem(STORAGE_KEY, clientId);
    selectionListeners.forEach((listener) => listener());
  },
};

interface SelectedClientValue {
  clients: ClientView[];
  selected: ClientView | null;
  selectedId: string | null;
  select: (clientId: string) => void;
  loading: boolean;
}

const SelectedClientContext = React.createContext<SelectedClientValue | null>(null);

export function SelectedClientProvider({ children }: { children: React.ReactNode }) {
  const { client, token } = usePortalSession();
  const preferredId = React.useSyncExternalStore(
    selectionStore.subscribe,
    selectionStore.getSnapshot,
    selectionStore.getServerSnapshot,
  );
  const query = useQuery({
    queryKey: ["developer-clients"],
    enabled: Boolean(token),
    queryFn: async () => (await client.developer.clients()).data ?? [],
  });
  const clients = React.useMemo(() => query.data ?? [], [query.data]);
  const selectedId = clients.some((item) => item.clientId === preferredId)
    ? preferredId
    : clients[0]?.clientId ?? null;

  const select = React.useCallback((clientId: string) => {
    selectionStore.set(clientId);
  }, []);
  const selected = clients.find((item) => item.clientId === selectedId) ?? null;
  const value = React.useMemo(
    () => ({ clients, selected, selectedId, select, loading: query.isLoading }),
    [clients, selected, selectedId, select, query.isLoading],
  );
  return <SelectedClientContext.Provider value={value}>{children}</SelectedClientContext.Provider>;
}

export function useSelectedClient() {
  const value = React.useContext(SelectedClientContext);
  if (!value) throw new Error("useSelectedClient는 SelectedClientProvider 안에서 사용해야 합니다");
  return value;
}
