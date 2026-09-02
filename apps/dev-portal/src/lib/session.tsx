"use client";

import { createFractaClient, type FractaClient } from "@fracta/api-client";
import * as React from "react";

import { portalSessionStore } from "./token-store";

interface PortalSession {
  token: string | null;
  name: string | null;
  ready: boolean;
  client: FractaClient;
  signIn: (token: string, name: string) => void;
  signOut: () => void;
}

const SessionContext = React.createContext<PortalSession | null>(null);
const client = createFractaClient({
  basePath: "",
  getAccessToken: () => portalSessionStore.currentToken(),
});

export function PortalSessionProvider({ children }: { children: React.ReactNode }) {
  const state = React.useSyncExternalStore(
    portalSessionStore.subscribe,
    portalSessionStore.getSnapshot,
    portalSessionStore.getServerSnapshot,
  );

  React.useEffect(() => portalSessionStore.hydrate(), []);

  const value = React.useMemo<PortalSession>(
    () => ({ ...state, client, signIn: portalSessionStore.signIn, signOut: portalSessionStore.signOut }),
    [state],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function usePortalSession() {
  const value = React.useContext(SessionContext);
  if (!value) throw new Error("usePortalSession은 PortalSessionProvider 안에서 사용해야 합니다");
  return value;
}
