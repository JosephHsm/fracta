import coreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

/**
 * ESLint는 9.x로 고정한다 — eslint-config-next가 끌고 오는 eslint-plugin-react 7.37이
 * ESLint 10의 컨텍스트 API에서 깨진다(rule 'react/display-name' 로딩 실패).
 */
const config = [
  ...[coreWebVitals].flat(),
  ...[nextTypescript].flat(),
  { ignores: [".next/**", "node_modules/**", "next-env.d.ts"] },
];

export default config;
