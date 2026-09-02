"use client";

import { FractaApiError } from "@fracta/api-client";
import { ToastProvider, TooltipProvider, errorMessage } from "@fracta/ui";
import { QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import * as React from "react";

import { SessionProvider } from "./session";
import { apiErrorStore } from "./api-error-store";
import { sessionStore } from "./token-store";

/**
 * 앱 전역 프로바이더.
 *
 * <p>QueryClient는 컴포넌트 안에서 만든다. 모듈 최상단에서 만들면 SSR 시
 * 모든 요청이 같은 캐시를 공유해 사용자 데이터가 섞인다.
 *
 * <p><b>조회 실패는 반드시 화면에 드러낸다.</b> TanStack Query는 에러를 잡아 상태로
 * 바꾸므로 `unhandledrejection`으로는 절대 잡히지 않는다. 그 사실을 놓쳐서 모든 요청이
 * 401로 떨어지는데도 화면에는 "데이터 없음"으로만 보인 적이 있다 —
 * 인증 만료와 빈 목록은 완전히 다른 상태이고, 사용자에게도 다르게 보여야 한다.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = React.useState(
    () =>
      new QueryClient({
        queryCache: new QueryCache({
          onError(error) {
            if (error instanceof FractaApiError && error.status === 401) {
              // 401은 조회·변경 어디서 나든 세션 종료로 처리한다
              sessionStore.signOut();
              apiErrorStore.set("로그인이 만료되었습니다. 다시 로그인해 주세요.");
              return;
            }
            apiErrorStore.set(
              error instanceof FractaApiError
                ? errorMessage(error.code)
                : "서버와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.",
            );
          },
        }),
        defaultOptions: {
          queries: {
            // 시세·호가는 짧게, 나머지는 30초. 화면별로 필요하면 개별 지정한다
            staleTime: 30_000,
            retry(failureCount, error) {
              // 인증·검증 실패를 재시도하면 사용자만 기다린다
              if (error instanceof FractaApiError && error.status < 500) return false;
              return failureCount < 2;
            },
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <TooltipProvider>
          <ToastProvider>{children}</ToastProvider>
        </TooltipProvider>
      </SessionProvider>
    </QueryClientProvider>
  );
}
