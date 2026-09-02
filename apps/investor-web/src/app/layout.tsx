import type { Metadata } from "next";
import localFont from "next/font/local";

import { Providers } from "@/lib/providers";

import "./globals.css";

/**
 * Pretendard Variable 한 파일로 45~920 굵기를 모두 덮는다. 자체 호스팅이라
 * 외부 CDN에 의존하지 않는다 — 폐쇄망 모드(FSD §10.4)에서도 그대로 뜬다.
 */
const pretendard = localFont({
  src: "../../../../packages/ui/src/fonts/PretendardVariable.woff2",
  variable: "--font-pretendard",
  weight: "45 920",
  display: "swap",
  preload: true,
});

export const metadata: Metadata = {
  title: "FRACTA",
  description: "토큰증권 기반 조각투자 발행·유통 플랫폼",
};

/**
 * 다크 모드 깜빡임 방지. 하이드레이션 전에 클래스를 붙여야 첫 페인트가 흰 화면으로 튀지 않는다.
 * 저장된 선택이 없으면 시스템 설정을 따른다.
 */
const THEME_INIT = `
try {
  var saved = localStorage.getItem("fracta-theme");
  var dark = saved ? saved === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
  if (dark) document.documentElement.classList.add("dark");
} catch (_) {}
`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className={pretendard.variable} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT }} />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
