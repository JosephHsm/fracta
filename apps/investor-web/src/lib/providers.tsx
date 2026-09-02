"use client";

import { FractaApiError } from "@fracta/api-client";
import { ToastProvider, TooltipProvider } from "@fracta/ui";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import * as React from "react";

import { SessionProvider } from "./session";

/**
 * 앱 전역 프로바이더.
 *
 * <p>QueryClient는 컴포넌트 안에서 만든다. 모듈 최상단에서 만들면 SSR 시
 * 모든 요청이 같은 캐시를 공유해 사용자 데이터가 섞인다.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = React.useState(
    () =>
      new QueryClient({
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
