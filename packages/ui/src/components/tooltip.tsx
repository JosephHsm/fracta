"use client";

import { Tooltip as BaseTooltip } from "@base-ui-components/react/tooltip";
import * as React from "react";

import { cn } from "../lib/cn";

/**
 * 툴팁.
 *
 * <p>배치는 Base UI가 JS로 계산한다. CSS Anchor Positioning은 Baseline 2026
 * *newly available*이라 지원 편차가 있어서 **기본 경로로 쓰지 않는다** (§11.4).
 * 얹더라도 `@supports` 안에서 점진적 향상으로만 얹는다.
 *
 * <p>툴팁은 마우스에서만 열린다 — 키보드·터치 사용자에게 필수 정보를 툴팁에만 두면 안 된다.
 */
export interface TooltipProps {
  content: React.ReactNode;
  children: React.ReactNode;
  side?: "top" | "bottom" | "left" | "right";
}

export function Tooltip({ content, children, side = "top" }: TooltipProps) {
  return (
    <BaseTooltip.Root>
      <BaseTooltip.Trigger render={children as React.ReactElement<Record<string, unknown>>} />
      <BaseTooltip.Portal>
        <BaseTooltip.Positioner side={side} sideOffset={6}>
          <BaseTooltip.Popup
            className={cn(
              "bg-fg text-fg-inverse shadow-md max-w-64 rounded-md px-2.5 py-1.5 text-xs",
              "transition-[opacity,transform] duration-(--fr-motion-hover) ease-(--fr-ease-out)",
              "data-[starting-style]:scale-95 data-[starting-style]:opacity-0",
              "data-[ending-style]:scale-95 data-[ending-style]:opacity-0",
            )}
          >
            {content}
          </BaseTooltip.Popup>
        </BaseTooltip.Positioner>
      </BaseTooltip.Portal>
    </BaseTooltip.Root>
  );
}

export const TooltipProvider = BaseTooltip.Provider;
