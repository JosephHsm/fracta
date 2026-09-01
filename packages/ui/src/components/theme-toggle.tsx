"use client";

import { Monitor, Moon, Sun } from "lucide-react";
import * as React from "react";

import { cn } from "../lib/cn";

export type ThemePreference = "light" | "dark" | "system";

const STORAGE_KEY = "fracta-theme";

function applyTheme(preference: ThemePreference) {
  const dark =
    preference === "dark" ||
    (preference === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
}

/**
 * 라이트/다크/시스템 3단 토글.
 *
 * <p>"시스템"이 기본이다. 사용자가 명시적으로 고르기 전까지 OS 설정을 따르는 게
 * 금융 대시보드에서 덜 놀랍다. 첫 페인트 깜빡임은 layout의 인라인 스크립트가 막는다.
 */
export function ThemeToggle({ className }: { className?: string }) {
  const [preference, setPreference] = React.useState<ThemePreference>("system");

  React.useEffect(() => {
    const stored = localStorage.getItem(STORAGE_KEY) as ThemePreference | null;
    if (stored) setPreference(stored);
  }, []);

  React.useEffect(() => {
    if (preference === "system") localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, preference);
    applyTheme(preference);
  }, [preference]);

  // 시스템 설정을 따르는 동안에는 OS 변경도 즉시 반영한다
  React.useEffect(() => {
    if (preference !== "system") return;
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => applyTheme("system");
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, [preference]);

  const options: { value: ThemePreference; icon: React.ReactNode; label: string }[] = [
    { value: "light", icon: <Sun aria-hidden className="size-3.5" />, label: "라이트" },
    { value: "dark", icon: <Moon aria-hidden className="size-3.5" />, label: "다크" },
    { value: "system", icon: <Monitor aria-hidden className="size-3.5" />, label: "시스템" },
  ];

  return (
    <div
      role="radiogroup"
      aria-label="화면 테마"
      className={cn("border-border bg-surface-sunken inline-flex gap-0.5 rounded-md border p-0.5", className)}
    >
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={preference === option.value}
          title={option.label}
          onClick={() => setPreference(option.value)}
          className={cn(
            "rounded-[5px] p-1.5 transition-colors duration-(--fr-motion-hover)",
            preference === option.value
              ? "bg-surface text-fg shadow-sm"
              : "text-fg-subtle hover:text-fg",
          )}
        >
          {option.icon}
          <span className="sr-only">{option.label}</span>
        </button>
      ))}
    </div>
  );
}
