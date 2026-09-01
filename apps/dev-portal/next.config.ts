import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // 워크스페이스 패키지는 TS 소스를 그대로 내보낸다 — Next가 직접 컴파일하게 한다
  transpilePackages: ["@fracta/ui", "@fracta/api-client"],
  typedRoutes: true,
};

export default nextConfig;
