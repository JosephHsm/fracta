import { cn } from "../lib/cn";

/**
 * 로딩 자리표시자. 스피너보다 레이아웃 이동(CLS)이 적다.
 *
 * <p>`aria-hidden`이다 — 스크린리더에는 회색 박스가 아니라 로딩 상태를 알리는
 * `aria-busy`/live region으로 전달해야 한다.
 */
export function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      aria-hidden
      className={cn("bg-surface-sunken animate-pulse rounded-md", className)}
      {...props}
    />
  );
}

export function SkeletonText({ lines = 3, className }: { lines?: number; className?: string }) {
  return (
    <div className={cn("flex flex-col gap-2", className)}>
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton
          key={index}
          className={cn("h-3.5", index === lines - 1 ? "w-2/3" : "w-full")}
        />
      ))}
    </div>
  );
}
