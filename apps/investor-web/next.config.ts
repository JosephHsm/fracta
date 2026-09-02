import type { NextConfig } from "next";

const API_ORIGIN = process.env.FRACTA_API_ORIGIN ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // 워크스페이스 패키지는 TS 소스를 그대로 내보낸다 — Next가 직접 컴파일하게 한다
  transpilePackages: ["@fracta/ui", "@fracta/api-client"],
  typedRoutes: true,

  // Docker 이미지를 슬림하게 만든다 — 런타임에 필요한 파일만 .next/standalone 으로 모은다
  output: "standalone",

  /**
   * API를 같은 오리진으로 프록시한다. CORS 설정을 서버에 추가하지 않아도 되고,
   * 생성 클라이언트의 basePath를 ""로 둘 수 있다.
   */
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${API_ORIGIN}/api/:path*` }];
  },
};

export default nextConfig;
