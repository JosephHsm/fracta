import { AlertTriangle, Ban, Minus } from "lucide-react";
import type { ReactElement } from "react";

import { cn } from "../lib/cn";

/**
 * 괴리율 배지 (TR-08) — 이 프로젝트의 얼굴.
 *
 * <p>세 단계는 색으로만 갈리지 않는다. 색맹·흑백 출력·다크 모드 어디서든 구분되도록
 * 아이콘 + 상태 문구를 항상 함께 낸다 (WCAG 1.4.1, FSD §11.4).
 *
 * <p>등락 색(상승 적색/하락 청색)과는 다른 축이다. 여기 빨강은 "상승"이 아니라 "거래 중단"이다.
 */
export type PremiumLevel = "NORMAL" | "WARN" | "HALT";

/** 임계치는 서버(TR-08)와 같은 값이다. 프론트에서 다른 기준을 쓰면 배지와 주문 차단이 어긋난다. */
const WARN_THRESHOLD = 10;
const HALT_THRESHOLD = 20;

/**
 * 괴리율(%)로 단계를 판정한다. 서버가 종목을 이미 SUSPENDED로 바꿨다면 `suspended`가 우선이다 —
 * 프론트 계산이 서버 상태를 뒤집지 않게 한다.
 */
export function premiumLevel(premiumRate: number | null | undefined, suspended = false): PremiumLevel {
  if (suspended) return "HALT";
  if (premiumRate == null || Number.isNaN(premiumRate)) return "NORMAL";
  const magnitude = Math.abs(premiumRate);
  if (magnitude > HALT_THRESHOLD) return "HALT";
  if (magnitude > WARN_THRESHOLD) return "WARN";
  return "NORMAL";
}

const LEVEL_STYLE: Record<PremiumLevel, { className: string; icon: ReactElement; label: string }> = {
  NORMAL: {
    className: "bg-premium-normal-soft text-premium-normal border-premium-normal/20",
    icon: <Minus aria-hidden className="size-3.5" />,
    label: "정상",
  },
  WARN: {
    className: "bg-premium-warn-soft text-premium-warn border-premium-warn/30",
    icon: <AlertTriangle aria-hidden className="size-3.5" />,
    label: "경고",
  },
  HALT: {
    className: "bg-premium-halt-soft text-premium-halt border-premium-halt/30",
    icon: <Ban aria-hidden className="size-3.5" />,
    label: "거래 중단",
  },
};

export interface PremiumBadgeProps {
  /** 서버가 준 괴리율(%). 시세 조회 실패 등으로 값이 없으면 null. */
  premiumRate: number | null | undefined;
  /** 종목이 SUSPENDED인지. 서버 상태가 항상 우선한다. */
  suspended?: boolean;
  className?: string;
}

export function PremiumBadge({ premiumRate, suspended = false, className }: PremiumBadgeProps) {
  const level = premiumLevel(premiumRate, suspended);
  const style = LEVEL_STYLE[level];

  // 서버가 준 값을 그대로 표시한다. 반올림 외의 재계산은 하지 않는다 (§11.2 금액 규칙).
  const rateText = premiumRate == null ? "—" : `${premiumRate > 0 ? "+" : ""}${premiumRate.toFixed(2)}%`;

  return (
    <span
      className={cn(
        "fr-numeric inline-flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs font-medium",
        style.className,
        className,
      )}
      data-level={level}
    >
      {style.icon}
      <span>괴리율 {rateText}</span>
      <span className="sr-only">— </span>
      <span className={cn(level === "NORMAL" && "sr-only")}>{style.label}</span>
    </span>
  );
}
