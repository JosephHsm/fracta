"use client";

let message: string | null = null;
const listeners = new Set<() => void>();

export const portalApiErrorStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
  getSnapshot: () => message,
  getServerSnapshot: () => null,
  set(next: string | null) {
    message = next;
    listeners.forEach((listener) => listener());
  },
};
