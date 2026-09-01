"use client";

import * as React from "react";

/**
 * 파생 지표(진행률·경쟁률) 전용 카운트업. **금액에는 쓰지 않는다** — `<Money/>` 주석 참조.
 *
 * <p>`prefers-reduced-motion: reduce`면 즉시 목표값을 반환한다. 모션 축소는 CSS만으로는
 * 부족하다 — JS로 값을 굴리는 애니메이션은 코드에서 직접 꺼야 한다.
 */
export function useCountUp(target: number, durationMs = 600): number {
  const [value, setValue] = React.useState(target);
  const previous = React.useRef(target);

  React.useEffect(() => {
    const reduced =
      typeof window !== "undefined" &&
      window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

    if (reduced || durationMs <= 0) {
      previous.current = target;
      setValue(target);
      return;
    }

    const from = previous.current;
    const delta = target - from;
    if (delta === 0) return;

    let frame = 0;
    const start = performance.now();

    const tick = (now: number) => {
      const progress = Math.min((now - start) / durationMs, 1);
      // ease-out — 끝에서 부드럽게 멈춘다. 마지막 프레임은 항상 정확히 target이다.
      const eased = 1 - Math.pow(1 - progress, 3);
      setValue(progress === 1 ? target : from + delta * eased);
      if (progress < 1) frame = requestAnimationFrame(tick);
      else previous.current = target;
    };

    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [target, durationMs]);

  return value;
}
