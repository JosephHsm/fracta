/**
 * 표시 포맷 전용. **여기서 금액을 만들지 않는다** — 서버가 준 값을 읽기 좋게 바꾸기만 한다.
 * `단가 × 수량` 같은 계산은 전부 서버 몫이다 (FSD §11.2, 부동소수점 오차).
 */

const KRW = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const UNITS = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const PERCENT = new Intl.NumberFormat("ko-KR", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** 원 단위 정수를 그대로 천 단위 구분해 보여준다. */
export function formatWon(amount: number | null | undefined): string {
  if (amount == null || Number.isNaN(amount)) return "—";
  return `${KRW.format(amount)}원`;
}

/** 통화 기호 없이 숫자만 (표 안에서 단위를 헤더로 뺄 때). */
export function formatNumber(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return "—";
  return KRW.format(value);
}

export function formatUnits(units: number | null | undefined): string {
  if (units == null || Number.isNaN(units)) return "—";
  return `${UNITS.format(units)}조각`;
}

/** 부호를 항상 붙인다 — 색 없이도 방향이 읽혀야 한다 (WCAG 1.4.1). */
export function formatSignedPercent(rate: number | null | undefined): string {
  if (rate == null || Number.isNaN(rate)) return "—";
  return `${rate > 0 ? "+" : ""}${PERCENT.format(rate)}%`;
}

export function formatPercent(rate: number | null | undefined): string {
  if (rate == null || Number.isNaN(rate)) return "—";
  return `${PERCENT.format(rate)}%`;
}

const DATE_TIME = new Intl.DateTimeFormat("ko-KR", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "Asia/Seoul",
});

const TIME = new Intl.DateTimeFormat("ko-KR", { timeStyle: "medium", timeZone: "Asia/Seoul" });

export function formatDateTime(value: Date | string | null | undefined): string {
  if (!value) return "—";
  const date = typeof value === "string" ? new Date(value) : value;
  return Number.isNaN(date.getTime()) ? "—" : DATE_TIME.format(date);
}

export function formatTime(value: Date | string | null | undefined): string {
  if (!value) return "—";
  const date = typeof value === "string" ? new Date(value) : value;
  return Number.isNaN(date.getTime()) ? "—" : TIME.format(date);
}

/** 청약 마감까지 남은 일수 (D-3 표기). 서버 시각 기준으로만 쓴다. */
export function formatDDay(endAt: Date | string | null | undefined, now: Date = new Date()): string {
  if (!endAt) return "—";
  const end = typeof endAt === "string" ? new Date(endAt) : endAt;
  if (Number.isNaN(end.getTime())) return "—";
  const days = Math.ceil((end.getTime() - now.getTime()) / 86_400_000);
  if (days < 0) return "마감";
  return days === 0 ? "D-DAY" : `D-${days}`;
}
