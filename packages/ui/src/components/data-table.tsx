import * as React from "react";

import { cn } from "../lib/cn";

/**
 * 표 프리미티브. 정렬·페이징 같은 로직은 화면에서 TanStack Table이 맡고,
 * 여기서는 보이는 껍데기만 책임진다.
 *
 * <p>표는 가로로 넘칠 수 있으므로 **자기 컨테이너 안에서만** 스크롤한다 —
 * 페이지 본문이 가로로 밀리면 모바일에서 레이아웃이 깨진다.
 */
export function TableContainer({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("border-border bg-surface w-full overflow-x-auto rounded-lg border", className)}
      {...props}
    />
  );
}

export function Table({ className, ...props }: React.TableHTMLAttributes<HTMLTableElement>) {
  return <table className={cn("w-full border-collapse text-sm", className)} {...props} />;
}

export function Thead({ className, ...props }: React.HTMLAttributes<HTMLTableSectionElement>) {
  return <thead className={cn("border-border border-b", className)} {...props} />;
}

export function Th({ className, ...props }: React.ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th
      className={cn(
        "text-fg-muted px-4 py-2.5 text-left text-xs font-medium whitespace-nowrap",
        className,
      )}
      {...props}
    />
  );
}

export function Tbody({ className, ...props }: React.HTMLAttributes<HTMLTableSectionElement>) {
  return <tbody className={cn("divide-border divide-y", className)} {...props} />;
}

export interface TrProps extends React.HTMLAttributes<HTMLTableRowElement> {
  /** 새로 들어온 행에 진입 애니메이션을 준다 (체결 내역처럼 실시간으로 붙는 표). */
  entering?: boolean;
}

export function Tr({ entering = false, className, ...props }: TrProps) {
  return (
    <tr
      className={cn(
        "hover:bg-surface-sunken transition-colors duration-(--fr-motion-hover)",
        entering && "fr-row-enter",
        className,
      )}
      {...props}
    />
  );
}

export function Td({ className, ...props }: React.TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cn("px-4 py-2.5 whitespace-nowrap", className)} {...props} />;
}

/** 값이 없을 때. 빈 표를 그냥 두면 로딩 실패와 구분이 안 된다. */
export function TableEmpty({ children = "표시할 내역이 없습니다" }: { children?: React.ReactNode }) {
  return <div className="text-fg-muted px-4 py-10 text-center text-sm">{children}</div>;
}
