import * as React from "react";

import { cn } from "../lib/cn";

/**
 * Bento 그리드 — 지표 종류가 많은 금융 화면의 정보 구조 (FSD §11.4).
 * 12칸 기준. 자식은 `<BentoItem span={...}>`으로 폭을 잡는다.
 */
export function BentoGrid({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("grid grid-cols-2 gap-4 md:grid-cols-12", className)} {...props} />;
}

const SPAN: Record<number, string> = {
  3: "md:col-span-3",
  4: "md:col-span-4",
  5: "md:col-span-5",
  6: "md:col-span-6",
  8: "md:col-span-8",
  9: "md:col-span-9",
  12: "col-span-2 md:col-span-12",
};

export interface BentoItemProps extends React.HTMLAttributes<HTMLDivElement> {
  /** 12칸 기준 가로 폭 */
  span?: 3 | 4 | 5 | 6 | 8 | 9 | 12;
}

export function BentoItem({ span = 4, className, ...props }: BentoItemProps) {
  return <div className={cn(SPAN[span], className)} {...props} />;
}

/**
 * 카드. **컨테이너 쿼리 기준점**이다 (§11.4) — 내부 레이아웃은 뷰포트가 아니라
 * 이 카드의 폭(`@container`)에 반응한다. 같은 카드가 넓은 본문에서는 가로로,
 * 사이드바에서는 세로로 접힌다.
 */
export interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  elevated?: boolean;
}

export function Card({ elevated = false, className, ...props }: CardProps) {
  return (
    <div
      className={cn(
        "@container border-border rounded-lg border",
        elevated ? "bg-surface-elevated shadow-md" : "bg-surface shadow-sm",
        className,
      )}
      {...props}
    />
  );
}

export function CardHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("flex flex-col gap-1 px-5 pt-5", className)} {...props} />;
}

export function CardTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  return <h3 className={cn("text-sm font-semibold tracking-tight", className)} {...props} />;
}

export function CardDescription({ className, ...props }: React.HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn("text-fg-muted text-xs", className)} {...props} />;
}

export function CardBody({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("p-5", className)} {...props} />;
}

export function CardFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("border-border flex items-center gap-2 border-t px-5 py-3", className)} {...props} />;
}
