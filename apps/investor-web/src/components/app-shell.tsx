"use client";

import { CommandHint, CommandPalette, ThemeToggle, cn, startViewTransition } from "@fracta/ui";
import { Building2, LayoutDashboard, LogOut, Receipt, Wallet } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import * as React from "react";

import { ApiErrorBanner } from "@/components/api-error-banner";
import { AuthGuard } from "@/components/auth-guard";
import { useSession } from "@/lib/session";
import { useIssuances } from "@/lib/queries";

const NAV = [
  { href: "/", label: "홈", icon: LayoutDashboard },
  { href: "/portfolio", label: "내 자산", icon: Wallet },
  { href: "/orders", label: "주문 내역", icon: Receipt },
] as const;

/**
 * 공통 셸 — 인증 가드 + 스티키 헤더(Subtle Glass) + 내비 + 커맨드 팔레트.
 *
 * <p>가드를 셸 안에 둔다. 화면마다 `if (!session.token) return null`을 반복하면
 * 하나라도 빠뜨리는 순간 빈 화면이 나온다 — 실제로 그렇게 검은 화면이 났다.
 *
 * <p>팔레트 항목은 상장·청약 종목을 실시간으로 물어와서 만든다. 하드코딩하면
 * 종목이 늘어날 때마다 검색에서 빠진다.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <AuthGuard>
      <Shell>{children}</Shell>
    </AuthGuard>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { name, signOut } = useSession();
  const { data: issuances } = useIssuances();

  const commands = React.useMemo(() => {
    const navItems = NAV.map((item) => ({
      id: `nav-${item.href}`,
      label: item.label,
      group: "이동",
      onSelect: () => router.push(item.href),
    }));

    const tokenItems = (issuances ?? []).map((issuance) => ({
      id: `token-${issuance.issuanceId}`,
      label: `${issuance.assetName ?? issuance.tokenSymbol}`,
      keywords: [issuance.tokenSymbol ?? "", issuance.status ?? ""],
      group: issuance.status === "SUBSCRIBING" ? "청약 중" : "상장 종목",
      hint: issuance.tokenSymbol ?? undefined,
      onSelect: () =>
        router.push(
          issuance.status === "SUBSCRIBING"
            ? `/issuances/${issuance.issuanceId}`
            : `/tokens/${issuance.tokenSymbol}`,
        ),
    }));

    return [...navItems, ...tokenItems];
  }, [issuances, router]);

  return (
    <div className="min-h-dvh">
      <header className="fr-glass border-border sticky top-0 z-30 border-b">
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-6 px-6">
          <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight">
            <Building2 aria-hidden className="text-accent size-5" />
            FRACTA
          </Link>

          <nav className="flex items-center gap-1" aria-label="주요 메뉴">
            {NAV.map((item) => {
              const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  aria-current={active ? "page" : undefined}
                  className={cn(
                    "rounded-md px-3 py-1.5 text-sm transition-colors duration-(--fr-motion-hover)",
                    active
                      ? "bg-surface-sunken text-fg font-medium"
                      : "text-fg-muted hover:text-fg hover:bg-surface-sunken/60",
                  )}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>

          <div className="ml-auto flex items-center gap-3">
            <CommandHint />
            <ThemeToggle />
            {name && (
              <div className="flex items-center gap-2">
                <span className="text-fg-muted hidden text-sm sm:inline">{name}님</span>
                <button
                  type="button"
                  onClick={() => startViewTransition(() => signOut())}
                  aria-label="로그아웃"
                  className="text-fg-subtle hover:text-fg hover:bg-surface-sunken rounded-md p-1.5"
                >
                  <LogOut aria-hidden className="size-4" />
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-6 py-8">
        <ApiErrorBanner />
        {children}
      </main>

      <CommandPalette items={commands} />
    </div>
  );
}
