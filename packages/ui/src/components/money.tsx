import { formatUnits, formatWon } from "../lib/format";
import { cn } from "../lib/cn";

/**
 * 금액 표시.
 *
 * <p><b>카운트업 애니메이션을 넣지 않는다.</b> 중간 프레임이 서버가 준 적 없는 금액이 되고,
 * 스크린리더는 그 중간값을 읽는다. 진행률·경쟁률 같은 파생 지표에만 카운트업을 쓴다
 * (FSD §11.4, phase-10 흔한 실수 11).
 */
export interface MoneyProps extends React.HTMLAttributes<HTMLSpanElement> {
  /** 서버가 준 원 단위 정수. 프론트에서 만들어낸 값이면 안 된다. */
  amount: number | null | undefined;
  size?: "sm" | "md" | "lg" | "xl";
  /**
   * 부호 있는 금액(평가손익 등)으로 표시한다. 부호를 붙이고 등락 색을 입힌다.
   *
   * <p>색은 국내 관행을 따른다 — 이익은 적색, 손실은 청색. 0은 무채색이다.
   * 색만으로 구분되게 두지 않고 부호(+/−)를 함께 쓴다(WCAG 1.4.1).
   */
  signed?: boolean;
}

const SIZE = {
  sm: "text-xs",
  md: "text-sm",
  lg: "text-lg font-semibold",
  xl: "text-3xl font-semibold tracking-tight",
} as const;

export function Money({ amount, size = "md", signed = false, className, ...props }: MoneyProps) {
  const tone =
    !signed || amount == null || amount === 0
      ? undefined
      : amount > 0
        ? "text-price-up"
        : "text-price-down";
  const text =
    signed && amount != null && amount > 0 ? `+${formatWon(amount)}` : formatWon(amount);
  return (
    <span
      className={cn("fr-numeric whitespace-nowrap", SIZE[size], tone, className)}
      {...props}
    >
      {text}
    </span>
  );
}

export interface UnitsProps extends React.HTMLAttributes<HTMLSpanElement> {
  units: number | null | undefined;
}

export function Units({ units, className, ...props }: UnitsProps) {
  return (
    <span className={cn("fr-numeric", className)} {...props}>
      {formatUnits(units)}
    </span>
  );
}
