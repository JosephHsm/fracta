import * as React from "react";

import { Card } from "./card";
import { cn } from "../lib/cn";

/**
 * Bento 대시보드의 기본 단위 — 라벨 + 큰 숫자 + 보조 정보.
 *
 * <p>컨테이너 쿼리를 쓴다: 카드가 좁으면(<18rem) 라벨과 값이 세로로 쌓이고,
 * 넓으면 보조 정보가 같은 줄로 붙는다. 뷰포트가 아니라 **자기 폭**을 본다 (§11.4).
 */
export interface StatTileProps {
  label: string;
  /** 이미 포맷된 값. 숫자 포맷은 호출자가 `formatWon` 등으로 만든다. */
  value: React.ReactNode;
  /** 값 아래 보조 설명 또는 등락 표시 */
  hint?: React.ReactNode;
  icon?: React.ReactNode;
  className?: string;
}

export function StatTile({ label, value, hint, icon, className }: StatTileProps) {
  return (
    <Card className={cn("h-full", className)}>
      <div className="flex flex-col gap-2 p-5">
        <div className="text-fg-muted flex items-center gap-2 text-xs font-medium">
          {icon}
          <span>{label}</span>
        </div>
        <div className="fr-numeric @[18rem]:text-3xl text-2xl font-semibold tracking-tight">{value}</div>
        {hint && <div className="text-fg-muted text-xs">{hint}</div>}
      </div>
    </Card>
  );
}
