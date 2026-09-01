"use client";

/**
 * View Transitions API 얇은 래퍼 (§11.4).
 *
 * <p>미지원 브라우저에서는 콜백을 그냥 실행한다 — 전환이 없을 뿐 동작은 같다.
 * `prefers-reduced-motion`에서도 애니메이션을 건너뛴다. CSS의 `::view-transition`
 * animation:none만으로는 전환 자체가 시작되는 것을 막지 못해서 여기서도 확인한다.
 */
type ViewTransitionCapableDocument = Document & {
  startViewTransition?: (callback: () => void | Promise<void>) => { finished: Promise<void> };
};

export function startViewTransition(update: () => void | Promise<void>): void {
  if (typeof document === "undefined") {
    void update();
    return;
  }

  const doc = document as ViewTransitionCapableDocument;
  const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

  if (reduced || typeof doc.startViewTransition !== "function") {
    void update();
    return;
  }

  doc.startViewTransition(update);
}

/**
 * 리스트 카드와 상세 화면의 같은 요소를 잇는 이름. 목록/상세 양쪽에서 같은 값을 써야
 * 브라우저가 두 요소를 동일한 것으로 보고 형태를 이어준다.
 */
export function viewTransitionName(kind: string, id: string | number): string {
  return `fr-${kind}-${id}`;
}
