"use client";

import { CommandHint, CommandPalette, ThemeToggle, cn, startViewTransition } from "@fracta/ui";
import { AppWindow, BookOpen, Boxes, Code2, LayoutDashboard, LogOut, ScrollText, Webhook } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import * as React from "react";

import { PortalApiErrorBanner } from "./api-error-banner";
import { PortalAuthGuard } from "./auth-guard";
import { useSelectedClient } from "@/lib/selected-client";
import { usePortalSession } from "@/lib/session";

const NAV = [
  { href: "/", label: "대시보드", icon: LayoutDashboard },
  { href: "/apps", label: "앱 관리", icon: AppWindow },
  { href: "/sandbox", label: "샌드박스", icon: Code2 },
  { href: "/webhooks", label: "웹훅", icon: Webhook },
  { href: "/logs", label: "로그", icon: ScrollText },
  { href: "/docs", label: "API 문서", icon: BookOpen },
] as const;

export function PortalShell({ children }: { children: React.ReactNode }) {
  return <PortalAuthGuard><Shell>{children}</Shell></PortalAuthGuard>;
}

function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { name, signOut } = usePortalSession();
  const { clients, selectedId, select } = useSelectedClient();
  const commands = React.useMemo(() => NAV.map((item) => ({
    id: `nav-${item.href}`,
    label: item.label,
    group: "이동",
    onSelect: () => router.push(item.href),
  })), [router]);

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[15rem_1fr]">
      <aside className="border-border bg-surface hidden border-r lg:flex lg:flex-col">
        <Link href="/" className="border-border flex h-16 items-center gap-2 border-b px-5 font-semibold tracking-tight">
          <Boxes aria-hidden className="text-accent size-5" />
          FRACTA Developers
        </Link>
        <nav className="flex flex-1 flex-col gap-1 p-3" aria-label="개발자 포털 메뉴">
          {NAV.map((item) => {
            const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
            return (
              <Link key={item.href} href={item.href} aria-current={active ? "page" : undefined}
                className={cn("flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors duration-(--fr-motion-hover)",
                  active ? "bg-surface-sunken text-fg font-medium" : "text-fg-muted hover:bg-surface-sunken/60 hover:text-fg")}
              >
                <item.icon aria-hidden className="size-4" />{item.label}
              </Link>
            );
          })}
        </nav>
        <div className="border-border border-t p-4 text-xs">
          <p className="text-fg-muted">로그인 계정</p>
          <p className="mt-1 truncate font-medium">{name}</p>
        </div>
      </aside>

      <div className="min-w-0">
        <header className="fr-glass border-border sticky top-0 z-30 border-b">
          <div className="flex min-h-16 flex-wrap items-center gap-2 px-4 py-2 sm:px-6 lg:flex-nowrap lg:gap-3 lg:py-0">
            <Link href="/" className="flex items-center gap-2 font-semibold lg:hidden">
              <Boxes aria-hidden className="text-accent size-5" /> FRACTA
            </Link>
            <nav className="order-last grid w-full grid-cols-3 gap-1 lg:hidden" aria-label="모바일 메뉴">
              {NAV.map((item) => {
                const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
                return <Link key={item.href} href={item.href} aria-current={active ? "page" : undefined}
                  className={cn("rounded-md px-1.5 py-1.5 text-center text-xs whitespace-nowrap",
                    active ? "bg-surface-sunken text-fg font-medium" : "text-fg-muted hover:text-fg")}>{item.label}</Link>;
              })}
            </nav>
            <div className="ml-auto flex items-center gap-2">
              {clients.length > 0 && (
                <label className="text-fg-muted hidden items-center gap-2 text-xs sm:flex">
                  앱
                  <select aria-label="현재 앱" value={selectedId ?? ""} onChange={(event) => select(event.target.value)}
                    className="bg-surface border-border text-fg h-9 max-w-56 rounded-md border px-2 text-sm">
                    {clients.map((client) => <option key={client.clientId} value={client.clientId}>{client.name} · {client.env}</option>)}
                  </select>
                </label>
              )}
              <CommandHint />
              <ThemeToggle />
              <button type="button" aria-label="로그아웃" onClick={() => startViewTransition(() => signOut())}
                className="text-fg-subtle hover:bg-surface-sunken hover:text-fg rounded-md p-2">
                <LogOut aria-hidden className="size-4" />
              </button>
            </div>
          </div>
        </header>
        <main className="mx-auto max-w-[100rem] px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
          <PortalApiErrorBanner />
          {children}
        </main>
      </div>
      <CommandPalette items={commands} />
    </div>
  );
}
