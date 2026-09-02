"use client";

/**
 * 최근 API 오류 한 건.
 *
 * <p>조회 실패를 화면에 띄우기 위한 최소 저장소다. React 밖에 두는 이유는 QueryCache의
 * `onError`가 컴포넌트 바깥에서 불리기 때문이다 — 훅을 쓸 수 없다.
 *
 * <p>"가장 최근 오류 하나"만 들고 있다. 여러 조회가 동시에 실패해도 원인은 대개 같고
 * (서버 다운, 세션 만료), 배너를 여러 줄 쌓아 봐야 읽지 않는다.
 */
export interface ApiErrorState {
  message: string | null;
  /** 같은 문구가 다시 와도 배너를 다시 띄우기 위한 일련번호. */
  seq: number;
}

const EMPTY: ApiErrorState = { message: null, seq: 0 };

let state: ApiErrorState = EMPTY;
const listeners = new Set<() => void>();

function emit(next: ApiErrorState) {
  state = next;
  for (const listener of listeners) listener();
}

export const apiErrorStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },

  getSnapshot(): ApiErrorState {
    return state;
  },

  getServerSnapshot(): ApiErrorState {
    return EMPTY;
  },

  set(message: string) {
    emit({ message, seq: state.seq + 1 });
  },

  clear() {
    if (state.message === null) return;
    emit({ message: null, seq: state.seq });
  },
};
