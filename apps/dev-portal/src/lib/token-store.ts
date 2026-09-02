"use client";

const STORAGE_KEY = "fracta-portal-session";

interface SessionSnapshot {
  token: string | null;
  name: string | null;
  ready: boolean;
}

let snapshot: SessionSnapshot = { token: null, name: null, ready: false };
const SERVER_SNAPSHOT: SessionSnapshot = { token: null, name: null, ready: false };
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((listener) => listener());
}

export const portalSessionStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
  getSnapshot: () => snapshot,
  getServerSnapshot: () => SERVER_SNAPSHOT,
  currentToken: () => snapshot.token ?? undefined,
  hydrate() {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "null") as {
        token?: string;
        name?: string;
      } | null;
      snapshot = { token: saved?.token ?? null, name: saved?.name ?? null, ready: true };
    } catch {
      snapshot = { token: null, name: null, ready: true };
    }
    emit();
  },
  signIn(token: string, name: string) {
    snapshot = { token, name, ready: true };
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ token, name }));
    emit();
  },
  signOut() {
    snapshot = { token: null, name: null, ready: true };
    localStorage.removeItem(STORAGE_KEY);
    emit();
  },
};
