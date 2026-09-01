"use client";

import { Toast } from "@base-ui-components/react/toast";
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from "lucide-react";
import * as React from "react";

import { cn } from "../lib/cn";

/**
 * 토스트. 성공·정보성 알림용이다.
 *
 * <p><b>적합성 차단(SUIT_*)은 여기로 보내지 않는다.</b> 금소법 시연 포인트라 사유 표시와
 * 확인 서명 동선이 있는 전용 UI가 필요하다 (phase-10 흔한 실수 3).
 */
export type ToastTone = "info" | "success" | "warning" | "danger";

const TONE_STYLE: Record<ToastTone, { className: string; icon: React.ReactNode }> = {
  info: { className: "border-info/30", icon: <Info aria-hidden className="text-info size-4" /> },
  success: {
    className: "border-success/30",
    icon: <CheckCircle2 aria-hidden className="text-success size-4" />,
  },
  warning: {
    className: "border-warning/40",
    icon: <AlertTriangle aria-hidden className="text-warning size-4" />,
  },
  danger: {
    className: "border-danger/40",
    icon: <XCircle aria-hidden className="text-danger size-4" />,
  },
};

export function ToastProvider({ children }: { children: React.ReactNode }) {
  return (
    <Toast.Provider>
      {children}
      <ToastViewport />
    </Toast.Provider>
  );
}

function ToastViewport() {
  const { toasts } = Toast.useToastManager();

  return (
    <Toast.Portal>
      <Toast.Viewport className="fixed right-4 bottom-4 z-50 flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2">
        {toasts.map((toast) => {
          const tone = (toast.type as ToastTone | undefined) ?? "info";
          const style = TONE_STYLE[tone] ?? TONE_STYLE.info;

          return (
            <Toast.Root
              key={toast.id}
              toast={toast}
              className={cn(
                "fr-glass border-border shadow-lg flex items-start gap-3 rounded-lg border p-4",
                "transition-[opacity,transform] duration-(--fr-motion-panel) ease-(--fr-ease-out)",
                "data-[starting-style]:translate-y-2 data-[starting-style]:opacity-0",
                "data-[ending-style]:translate-y-1 data-[ending-style]:opacity-0",
                style.className,
              )}
            >
              <span className="mt-0.5 shrink-0">{style.icon}</span>
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <Toast.Title className="text-fg text-sm font-medium" />
                <Toast.Description className="text-fg-muted text-xs" />
              </div>
              <Toast.Close
                aria-label="알림 닫기"
                className="text-fg-subtle hover:text-fg -mt-1 -mr-1 rounded p-1"
              >
                <X aria-hidden className="size-4" />
              </Toast.Close>
            </Toast.Root>
          );
        })}
      </Toast.Viewport>
    </Toast.Portal>
  );
}

export interface ShowToastOptions {
  title: string;
  description?: string;
  tone?: ToastTone;
  timeout?: number;
}

/** 화면에서는 이 훅만 쓴다 — Base UI의 매니저 API를 직접 만지지 않게 감싼다. */
export function useToast() {
  const manager = Toast.useToastManager();

  return React.useMemo(
    () => ({
      show({ title, description, tone = "info", timeout }: ShowToastOptions) {
        return manager.add({
          title,
          description,
          type: tone,
          timeout,
          // 오류는 즉시 읽어야 한다. 성공 알림은 진행 중인 낭독을 끊지 않는다.
          priority: tone === "danger" ? "high" : "low",
        });
      },
      close: manager.close,
    }),
    [manager],
  );
}
