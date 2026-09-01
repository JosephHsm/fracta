import type { Metadata } from "next";

import "./globals.css";

export const metadata: Metadata = {
  title: "FRACTA 개발자 포털",
  description: "FRACTA 오픈 API 개발자 포털",
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
    <html lang="ko" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT }} />
      </head>
      <body>{children}</body>
    </html>
  );
}
