"use client";

import { FractaApiError } from "@fracta/api-client";
import { ToastProvider, TooltipProvider, errorMessage } from "@fracta/ui";
import { QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import * as React from "react";

import { portalApiErrorStore } from "./api-error-store";
import { SelectedClientProvider } from "./selected-client";
import { PortalSessionProvider } from "./session";
import { portalSessionStore } from "./token-store";

export function PortalProviders({ children }: { children: React.ReactNode }) {
  const [queryClient] = React.useState(
    () => new QueryClient({
      queryCache: new QueryCache({
        // 성공한 요청이 하나라도 들어오면 지난 오류는 유효하지 않다.
        // 이게 없으면 로그인 전 401 배너가 로그인 성공 후에도 남는다 (실제로 그랬다).
        onSuccess() {
          portalApiErrorStore.set(null);
        },
        onError(error) {
          if (error instanceof FractaApiError && error.status === 401) {
            portalSessionStore.signOut();
            portalApiErrorStore.set("로그인이 만료되었습니다. 다시 로그인해 주세요.");
            return;
          }
          portalApiErrorStore.set(
            error instanceof FractaApiError
              ? errorMessage(error.code)
              : "서버와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.",
          );
        },
      }),
      defaultOptions: {
        queries: {
          staleTime: 15_000,
          retry: (count, error) =>
            error instanceof FractaApiError && error.status < 500 ? false : count < 2,
        },
      },
    }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      <PortalSessionProvider>
        <SelectedClientProvider>
          <TooltipProvider>
            <ToastProvider>{children}</ToastProvider>
          </TooltipProvider>
        </SelectedClientProvider>
      </PortalSessionProvider>
    </QueryClientProvider>
  );
}
