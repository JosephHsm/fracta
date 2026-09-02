"use client";

/**
 * 세션 토큰 저장소.
 *
 * <p>React 밖에 두는 이유가 두 가지다.
 * ① localStorage는 외부 저장소라 `useSyncExternalStore`로 읽어야 하이드레이션이 어긋나지 않는다.
 *    effect 안에서 setState로 끌어오면 렌더가 한 번 더 돌고, React 컴파일러도 이를 지적한다.
 * ② API 클라이언트가 매 요청 직전에 최신 토큰을 읽어야 하는데, ref를 렌더 중에 만지지 않고
 *    여기서 바로 읽으면 된다.
 *
 * <p>토큰을 localStorage에 두는 것은 데모용 SPA의 선택이다. 실서비스라면 httpOnly 쿠키 +
 * 회전이 맞다 — FSD의 범위를 넘으므로 한계로 남긴다.
 */
const TOKEN_KEY = "fracta-token";
const NAME_KEY = "fracta-name";

export interface SessionState {
  token: string | null;
  name: string | null;
  /** localStorage를 한 번이라도 읽었는지. 읽기 전에는 로그인 여부를 판단하지 않는다. */
  ready: boolean;
}

/** 서버 렌더에서는 항상 같은 객체를 돌려줘야 무한 루프가 나지 않는다. */
const SERVER_STATE: SessionState = { token: null, name: null, ready: false };

let state: SessionState = SERVER_STATE;
const listeners = new Set<() => void>();

function set(next: SessionState) {
  state = next;
  for (const listener of listeners) listener();
}

export const sessionStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },

  getSnapshot(): SessionState {
    return state;
  },

  getServerSnapshot(): SessionState {
    return SERVER_STATE;
  },

  /** 하이드레이션 후 한 번 호출한다. 저장소 접근이 막혀 있어도 ready는 세운다. */
  hydrate() {
    if (state.ready) return;
    try {
      set({
        token: localStorage.getItem(TOKEN_KEY),
        name: localStorage.getItem(NAME_KEY),
        ready: true,
      });
    } catch {
      set({ token: null, name: null, ready: true });
    }
  },

  signIn(token: string, name: string) {
    try {
      localStorage.setItem(TOKEN_KEY, token);
      localStorage.setItem(NAME_KEY, name);
    } catch {
      /* 저장 실패해도 이번 세션은 메모리 토큰으로 동작한다 */
    }
    set({ token, name, ready: true });
  },

  signOut() {
    try {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(NAME_KEY);
    } catch {
      /* 저장소 접근 실패는 무시한다 — 메모리 상태만 비워도 로그아웃은 성립한다 */
    }
    set({ token: null, name: null, ready: true });
  },

  /** API 클라이언트가 요청 직전에 부른다. */
  currentToken(): string | undefined {
    return state.token ?? undefined;
  },
};
