"use client";

import { cva, type VariantProps } from "class-variance-authority";
import { Check, Loader2 } from "lucide-react";
import * as React from "react";

import { cn } from "../lib/cn";

const button = cva(
  [
    "inline-flex items-center justify-center gap-2 rounded-md font-medium whitespace-nowrap",
    "transition-[background-color,color,border-color,box-shadow,transform]",
    "duration-(--fr-motion-button) ease-(--fr-ease-out)",
    "disabled:pointer-events-none disabled:opacity-50",
    "active:scale-[0.985]",
  ],
  {
    variants: {
      variant: {
        primary: "bg-primary text-primary-foreground hover:bg-primary-hover shadow-sm",
        accent: "bg-accent text-accent-foreground hover:bg-accent-hover shadow-sm",
        secondary: "bg-surface text-fg border-border hover:bg-surface-sunken border",
        ghost: "text-fg-muted hover:bg-surface-sunken hover:text-fg",
        danger: "bg-danger text-fg-inverse hover:brightness-110 shadow-sm",
      },
      size: {
        sm: "h-8 px-3 text-xs",
        md: "h-10 px-4 text-sm",
        lg: "h-12 px-6 text-base",
      },
      block: { true: "w-full", false: "" },
    },
    defaultVariants: { variant: "primary", size: "md", block: false },
  },
);

/**
 * 상태 전이를 버튼이 직접 표현한다 — 대기 → 처리 중 → 완료.
 * 청약·주문처럼 되돌릴 수 없는 행동에서 "눌렸는지" 모호하면 사용자가 두 번 누른다.
 */
export type ActionState = "idle" | "pending" | "done";

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof button> {
  state?: ActionState;
  /** state가 pending/done일 때 대신 보여줄 문구 */
  pendingLabel?: string;
  doneLabel?: string;
}

export function Button({
  className,
  variant,
  size,
  block,
  state = "idle",
  pendingLabel = "처리 중...",
  doneLabel = "완료",
  disabled,
  children,
  ...props
}: ButtonProps) {
  const busy = state === "pending";

  return (
    <button
      type="button"
      // 처리 중에는 비활성화 대신 aria-busy + 클릭 차단 — 포커스를 잃지 않아야 스크린리더가 상태 변화를 읽는다
      aria-busy={busy || undefined}
      disabled={disabled || busy}
      className={cn(button({ variant, size, block }), className)}
      {...props}
    >
      {state === "pending" && <Loader2 aria-hidden className="size-4 animate-spin" />}
      {state === "done" && <Check aria-hidden className="size-4" />}
      <span>{state === "pending" ? pendingLabel : state === "done" ? doneLabel : children}</span>
    </button>
  );
}
