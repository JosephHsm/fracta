import { cva, type VariantProps } from "class-variance-authority";
import * as React from "react";

import { cn } from "../lib/cn";

const badge = cva(
  "inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium whitespace-nowrap",
  {
    variants: {
      tone: {
        neutral: "bg-surface-sunken text-fg-muted border-border",
        info: "bg-info-soft text-info border-info/25",
        success: "bg-success-soft text-success border-success/25",
        warning: "bg-warning-soft text-warning border-warning/30",
        danger: "bg-danger-soft text-danger border-danger/30",
        accent: "bg-accent-soft text-accent border-accent/25",
      },
    },
    defaultVariants: { tone: "neutral" },
  },
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badge> {
  icon?: React.ReactNode;
}

/**
 * 상태 배지. 색은 보조 신호일 뿐이므로 **문구를 반드시 함께** 넣는다 (WCAG 1.4.1).
 * 아이콘만 있는 배지는 만들지 않는다.
 */
export function Badge({ tone, icon, className, children, ...props }: BadgeProps) {
  return (
    <span className={cn(badge({ tone }), className)} {...props}>
      {icon}
      {children}
    </span>
  );
}
