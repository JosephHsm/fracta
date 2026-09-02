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
}

const SIZE = {
  sm: "text-xs",
  md: "text-sm",
  lg: "text-lg font-semibold",
  xl: "text-3xl font-semibold tracking-tight",
} as const;

export function Money({ amount, size = "md", className, ...props }: MoneyProps) {
  return (
    <span className={cn("fr-numeric whitespace-nowrap", SIZE[size], className)} {...props}>
      {formatWon(amount)}
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
