"use client";

import { Dialog as BaseDialog } from "@base-ui-components/react/dialog";
import { X } from "lucide-react";
import * as React from "react";

import { cn } from "../lib/cn";

/**
 * 모달. **Subtle Glass를 쓰는 몇 안 되는 자리 중 하나다** (§11.4/§11.5) —
 * 뒤 레이어가 살짝 비쳐야 "위에 떠 있다"가 읽힌다. 카드에는 쓰지 않는다.
 */
export interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  /** 청약 확인처럼 되돌릴 수 없는 흐름은 바깥 클릭으로 닫히면 안 된다. */
  dismissible?: boolean;
  className?: string;
}

export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  dismissible = true,
  className,
}: DialogProps) {
  return (
    <BaseDialog.Root open={open} onOpenChange={onOpenChange} disablePointerDismissal={!dismissible}>
      <BaseDialog.Portal>
        <BaseDialog.Backdrop
          className={cn(
            "fixed inset-0 z-40 bg-black/40",
            "transition-opacity duration-(--fr-motion-panel) ease-(--fr-ease-out)",
            "data-[starting-style]:opacity-0 data-[ending-style]:opacity-0",
          )}
        />
        <BaseDialog.Popup
          className={cn(
            "fixed top-1/2 left-1/2 z-50 -translate-x-1/2 -translate-y-1/2",
            "w-[min(32rem,calc(100vw-2rem))] max-h-[calc(100vh-4rem)] overflow-y-auto",
            "fr-glass border-border shadow-lg rounded-xl border p-6",
            "transition-[opacity,transform] duration-(--fr-motion-panel) ease-(--fr-ease-out)",
            "data-[starting-style]:scale-[0.98] data-[starting-style]:opacity-0",
            "data-[ending-style]:scale-[0.98] data-[ending-style]:opacity-0",
            className,
          )}
        >
          <div className="mb-4 flex items-start justify-between gap-4">
            <div className="flex flex-col gap-1">
              <BaseDialog.Title className="text-base font-semibold tracking-tight">
                {title}
              </BaseDialog.Title>
              {description && (
                <BaseDialog.Description className="text-fg-muted text-sm">
                  {description}
                </BaseDialog.Description>
              )}
            </div>
            {dismissible && (
              <BaseDialog.Close
                aria-label="닫기"
                className="text-fg-subtle hover:text-fg hover:bg-surface-sunken -mt-1 -mr-1 rounded-md p-1.5"
              >
                <X aria-hidden className="size-4" />
              </BaseDialog.Close>
            )}
          </div>

          <div className="text-sm">{children}</div>

          {footer && <div className="mt-6 flex justify-end gap-2">{footer}</div>}
        </BaseDialog.Popup>
      </BaseDialog.Portal>
    </BaseDialog.Root>
  );
}

export const DialogClose = BaseDialog.Close;
