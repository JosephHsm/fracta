import { rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * openapi-generator는 배포 가능한 npm 패키지 한 벌을 통째로 만든다. 우리는 워크스페이스 안에서
 * TS 소스를 그대로 쓰기 때문에 패키징 파일과 마크다운 문서는 레포 노이즈다.
 * (API 문서는 Swagger UI가 담당한다 — FSD §11.3)
 */
const GENERATED = join(dirname(fileURLToPath(import.meta.url)), "..", "src", "generated");

const NOISE = ["docs", "README.md", "package.json", "tsconfig.json", "tsconfig.esm.json", ".npmignore"];

for (const entry of NOISE) {
  rmSync(join(GENERATED, entry), { recursive: true, force: true });
}

console.log(`pruned ${NOISE.length} generated packaging artifacts`);
