"use client";

import * as React from "react";

import { cn } from "../lib/cn";
import { formatPercent } from "../lib/format";
import { useCountUp } from "../lib/use-count-up";

/**
 * 모집·청약 진행률. 파생 지표라 카운트업을 허용한다 (금액은 금지 — `<Money/>` 참조).
 *
 * <p>`<progress>`가 아니라 role=progressbar를 쓴다 — 네이티브 요소는 브라우저별 스타일
 * 재정의가 지저분하고, 여기서는 값이 중요한 게 아니라 진행 상태를 보여주는 게 목적이다.
 */
export interface ProgressBarProps {
  /** 0~100. 서버가 준 값을 그대로 넣는다. */
  value: number;
  label?: string;
  /** 목표 대비 초과 모집을 표시해야 하면 100을 넘겨도 막대만 100%에서 멈춘다. */
  showValue?: boolean;
  tone?: "accent" | "success" | "warning";
  className?: string;
}

const TONE = {
  accent: "bg-accent",
  success: "bg-success",
  warning: "bg-warning",
} as const;

export function ProgressBar({
  value,
  label,
  showValue = true,
  tone = "accent",
  className,
}: ProgressBarProps) {
  const animated = useCountUp(value);
  const clamped = Math.max(0, Math.min(100, animated));

  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      {(label || showValue) && (
        <div className="flex items-baseline justify-between gap-2 text-xs">
          {label && <span className="text-fg-muted">{label}</span>}
          {showValue && <span className="fr-numeric font-medium">{formatPercent(animated)}</span>}
        </div>
      )}
      <div
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        // 보조기술에는 애니메이션 중간값이 아니라 실제 값을 준다
        aria-valuenow={Math.round(value)}
        aria-label={label}
        className="bg-surface-sunken border-border h-2 w-full overflow-hidden rounded-full border"
      >
        <div
          className={cn("h-full rounded-full transition-[width] duration-(--fr-motion-panel) ease-(--fr-ease-out)", TONE[tone])}
          style={{ width: `${clamped}%` }}
        />
      </div>
    </div>
  );
}
