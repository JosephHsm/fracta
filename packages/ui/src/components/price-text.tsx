import { cn } from "../lib/cn";

/**
 * 등락 표시. 국내 관행대로 **상승 = 적색, 하락 = 청색**이다 (FSD §11.4).
 *
 * <p>색만으로 방향을 전달하지 않는다 — ▲/▼ 기호와 +/- 부호를 항상 함께 낸다(WCAG 1.4.1).
 * 값은 서버가 준 문자열/숫자를 그대로 쓴다. 프론트에서 손익을 다시 계산하지 않는다.
 */
export type PriceDirection = "UP" | "DOWN" | "FLAT";

export function priceDirection(change: number | null | undefined): PriceDirection {
  if (change == null || Number.isNaN(change) || change === 0) return "FLAT";
  return change > 0 ? "UP" : "DOWN";
}

const DIRECTION_STYLE: Record<PriceDirection, { className: string; mark: string; label: string }> = {
  UP: { className: "text-price-up", mark: "▲", label: "상승" },
  DOWN: { className: "text-price-down", mark: "▼", label: "하락" },
  FLAT: { className: "text-price-flat", mark: "－", label: "보합" },
};

export interface PriceTextProps {
  /** 방향 판정용 숫자. 표시는 `children`이 있으면 그것을 그대로 쓴다. */
  change: number | null | undefined;
  /** 서버가 이미 포맷한 표시 문자열. 없으면 change를 그대로 찍는다. */
  children?: React.ReactNode;
  showMark?: boolean;
  className?: string;
}

export function PriceText({ change, children, showMark = true, className }: PriceTextProps) {
  const direction = priceDirection(change);
  const style = DIRECTION_STYLE[direction];

  return (
    <span className={cn("fr-numeric inline-flex items-center gap-1", style.className, className)}>
      {showMark && (
        <span aria-hidden className="text-[0.85em]">
          {style.mark}
        </span>
      )}
      <span>{children ?? change ?? "—"}</span>
      <span className="sr-only">{style.label}</span>
    </span>
  );
}
