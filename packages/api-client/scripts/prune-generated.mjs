import { readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * 생성 직후 정리 단계.
 *
 * <p>openapi-generator는 배포 가능한 npm 패키지 한 벌을 통째로 만든다. 우리는 워크스페이스
 * 안에서 TS 소스를 그대로 쓰기 때문에 패키징 파일과 마크다운 문서는 레포 노이즈다.
 * (API 문서는 Swagger UI가 담당한다 — FSD §11.3)
 */
const GENERATED = join(dirname(fileURLToPath(import.meta.url)), "..", "src", "generated");

const NOISE = ["docs", "README.md", "package.json", "tsconfig.json", "tsconfig.esm.json", ".npmignore"];

for (const entry of NOISE) {
  rmSync(join(GENERATED, entry), { recursive: true, force: true });
}

/**
 * 생성기 결함 보정 — `FetchError`가 `Error.cause`를 `override` 표시 없이 덮어쓴다.
 *
 * <p>이 프로젝트는 `noImplicitOverride`를 켜고 쓰기 때문에 앱에서 이 파일을 컴파일하면
 * TS4115로 깨진다. 생성물만 예외 규칙을 두면 앱 전체 tsconfig가 느슨해지므로,
 * 여기서 한 줄만 정확히 고친다. 이미 고쳐져 있으면 아무 일도 하지 않는다.
 */
const RUNTIME = join(GENERATED, "src", "runtime.ts");
const BEFORE = "constructor(public cause: Error, msg?: string)";
const AFTER = "constructor(public override cause: Error, msg?: string)";

const runtime = readFileSync(RUNTIME, "utf8");
if (runtime.includes(BEFORE)) {
  writeFileSync(RUNTIME, runtime.replace(BEFORE, AFTER));
  console.log("patched runtime.ts: FetchError.cause에 override 추가");
} else if (!runtime.includes(AFTER)) {
  // 생성기가 바뀌어 패턴이 사라졌다면 조용히 넘어가면 안 된다 — 앱 빌드가 나중에 깨진다
  throw new Error("runtime.ts에서 FetchError 생성자 패턴을 찾지 못했다. 보정 로직을 갱신할 것");
}

console.log(`pruned ${NOISE.length} generated packaging artifacts`);
