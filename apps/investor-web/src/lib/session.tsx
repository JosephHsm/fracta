"use client";

import { FractaApiError, createFractaClient, type FractaClient } from "@fracta/api-client";
import { useRouter } from "next/navigation";
import * as React from "react";

import { sessionStore } from "./token-store";

/**
 * 로그인 세션.
 *
 * <p>상태는 React 밖의 {@link sessionStore}에 있고 여기서는 구독만 한다 —
 * localStorage 같은 외부 저장소는 `useSyncExternalStore`로 읽어야 하이드레이션이 어긋나지 않는다.
 *
 * <p>401이 오면 세션을 비우고 로그인으로 보낸다. 화면마다 401을 따로 처리하면
 * 반드시 빠뜨리는 곳이 생긴다.
 */
interface SessionValue {
  token: string | null;
  name: string | null;
  ready: boolean;
  client: FractaClient;
  signIn: (token: string, name: string) => void;
  signOut: () => void;
}

const SessionContext = React.createContext<SessionValue | null>(null);

/** 토큰이 바뀌어도 클라이언트를 다시 만들 필요가 없다 — 요청 직전에 저장소에서 읽는다. */
const client = createFractaClient({
  basePath: "",
  getAccessToken: () => sessionStore.currentToken(),
});

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const state = React.useSyncExternalStore(
    sessionStore.subscribe,
    sessionStore.getSnapshot,
    sessionStore.getServerSnapshot,
  );

  React.useEffect(() => {
    sessionStore.hydrate();
  }, []);

  const value = React.useMemo<SessionValue>(
    () => ({
      token: state.token,
      name: state.name,
      ready: state.ready,
      client,
      signIn: sessionStore.signIn,
      signOut: sessionStore.signOut,
    }),
    [state],
  );

  // 401은 한 곳에서만 처리한다
  React.useEffect(() => {
    const onRejection = (event: PromiseRejectionEvent) => {
      const error = event.reason;
      if (error instanceof FractaApiError && error.status === 401) {
        sessionStore.signOut();
        router.push("/login");
      }
    };
    window.addEventListener("unhandledrejection", onRejection);
    return () => window.removeEventListener("unhandledrejection", onRejection);
  }, [router]);

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = React.useContext(SessionContext);
  if (!value) throw new Error("useSession은 SessionProvider 안에서만 쓸 수 있습니다");
  return value;
}

/** 로그인이 필요한 화면에서 쓴다. 세션 복원 전에는 리다이렉트하지 않는다. */
export function useRequireSession(): SessionValue {
  const session = useSession();
  const router = useRouter();

  React.useEffect(() => {
    if (session.ready && !session.token) router.replace("/login");
  }, [session.ready, session.token, router]);

  return session;
}
