import type { NextConfig } from "next";

const API_ORIGIN = process.env.FRACTA_API_ORIGIN ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // 워크스페이스 패키지는 TS 소스를 그대로 내보낸다 — Next가 직접 컴파일하게 한다
  transpilePackages: ["@fracta/ui", "@fracta/api-client"],
  typedRoutes: true,

  /**
   * API를 같은 오리진으로 프록시한다. CORS 설정을 서버에 추가하지 않아도 되고,
   * 생성 클라이언트의 basePath를 ""로 둘 수 있다.
   */
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${API_ORIGIN}/api/:path*` },
      { source: "/open/:path*", destination: `${API_ORIGIN}/open/:path*` },
      { source: "/v3/api-docs/:path*", destination: `${API_ORIGIN}/v3/api-docs/:path*` },
      { source: "/swagger-ui/:path*", destination: `${API_ORIGIN}/swagger-ui/:path*` },
      { source: "/swagger-ui.html", destination: `${API_ORIGIN}/swagger-ui.html` },
    ];
  },
};

export default nextConfig;
