/**
 * 디자인 토큰 대비비 검증 (WCAG 2.2 AA — FSD §11.4).
 *
 * tokens.css의 OKLCH 값을 직접 파싱해서 계산한다. 눈으로 "괜찮아 보인다"로 넘기면
 * 다크 모드에서 조용히 깨진다 — 특히 괴리율 배지처럼 soft 배경 위에 얹는 색이 위험하다.
 *
 *   node scripts/design/contrast-check.mjs
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const TOKENS = join(ROOT, "packages", "ui", "src", "styles", "tokens.css");

/** WCAG 최소 기준. 본문 4.5, 큰 글자·UI 요소 3.0 */
const AA_TEXT = 4.5;
const AA_LARGE = 3.0;

// ── OKLCH → sRGB 상대 휘도 ──────────────────────────────
function oklchToLinearRgb(L, C, hDeg) {
  const h = (hDeg * Math.PI) / 180;
  const a = C * Math.cos(h);
  const b = C * Math.sin(h);

  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;

  const l = l_ ** 3;
  const m = m_ ** 3;
  const s = s_ ** 3;

  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ].map((v) => Math.min(1, Math.max(0, v)));
}

function luminance(oklch) {
  const [r, g, b] = oklchToLinearRgb(...oklch);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(fg, bg) {
  const a = luminance(fg);
  const b = luminance(bg);
  const [hi, lo] = a > b ? [a, b] : [b, a];
  return (hi + 0.05) / (lo + 0.05);
}

// ── tokens.css 파싱 ────────────────────────────────────
/** `:root { ... }` 와 `.dark { ... }` 블록에서 --fr-* 선언만 뽑는다. */
function parseBlock(css, selector) {
  const start = css.indexOf(selector);
  if (start === -1) throw new Error(`${selector} 블록을 찾지 못했다`);
  const open = css.indexOf("{", start);
  const end = css.indexOf("\n}", open);
  const body = css.slice(open, end);

  const tokens = {};
  const declaration = /--(fr-[a-z0-9-]+)\s*:\s*oklch\(([^)]+)\)/g;
  let match;
  while ((match = declaration.exec(body)) !== null) {
    const parts = match[2].trim().split("/")[0].trim().split(/\s+/).map(Number);
    // 알파가 붙은 토큰(glass 등)은 배경이 비쳐서 단독 계산이 무의미하다 — 건너뛴다
    if (parts.length >= 3 && parts.every((n) => !Number.isNaN(n))) {
      tokens[match[1]] = [parts[0], parts[1], parts[2]];
    }
  }
  return tokens;
}

/** [전경, 배경, 최소기준, 설명] */
const PAIRS = [
  ["fr-text", "fr-background", AA_TEXT, "본문 텍스트"],
  ["fr-text", "fr-surface", AA_TEXT, "카드 위 본문"],
  ["fr-text-muted", "fr-background", AA_TEXT, "보조 텍스트"],
  ["fr-text-muted", "fr-surface", AA_TEXT, "카드 위 보조 텍스트"],
  ["fr-text-subtle", "fr-surface", AA_LARGE, "약한 텍스트(라벨)"],
  ["fr-primary-foreground", "fr-primary", AA_TEXT, "주 버튼 라벨"],
  ["fr-accent-foreground", "fr-accent", AA_TEXT, "액센트 버튼 라벨"],
  ["fr-accent", "fr-background", AA_LARGE, "링크·포커스 링"],
  ["fr-price-up", "fr-surface", AA_TEXT, "상승(적색)"],
  ["fr-price-down", "fr-surface", AA_TEXT, "하락(청색)"],
  ["fr-price-flat", "fr-surface", AA_TEXT, "보합"],
  ["fr-premium-normal", "fr-premium-normal-soft", AA_TEXT, "괴리율 배지 — 정상"],
  ["fr-premium-warn", "fr-premium-warn-soft", AA_TEXT, "괴리율 배지 — 경고"],
  ["fr-premium-halt", "fr-premium-halt-soft", AA_TEXT, "괴리율 배지 — 중단"],
  ["fr-success", "fr-success-soft", AA_TEXT, "성공 배지"],
  ["fr-warning", "fr-warning-soft", AA_TEXT, "경고 배지"],
  ["fr-danger", "fr-danger-soft", AA_TEXT, "위험 배지"],
  ["fr-danger", "fr-surface", AA_TEXT, "폼 오류 문구"],
  ["fr-border", "fr-surface", 1.2, "카드 보더(장식)"],
];

const css = readFileSync(TOKENS, "utf8");
const themes = { 라이트: parseBlock(css, ":root {"), 다크: parseBlock(css, ".dark {") };

let failures = 0;
for (const [theme, tokens] of Object.entries(themes)) {
  console.log(`\n── ${theme} ──────────────────────────────`);
  for (const [fg, bg, min, label] of PAIRS) {
    if (!tokens[fg] || !tokens[bg]) {
      console.log(`  ?  ${label}: 토큰 없음 (${fg} / ${bg})`);
      failures += 1;
      continue;
    }
    const ratio = contrast(tokens[fg], tokens[bg]);
    const ok = ratio >= min;
    if (!ok) failures += 1;
    console.log(
      `  ${ok ? "OK" : "!!"} ${label.padEnd(20)} ${ratio.toFixed(2)}:1 (최소 ${min})`,
    );
  }
}

console.log(
  failures === 0
    ? "\n전부 기준 충족\n"
    : `\n기준 미달 ${failures}건 — 토큰을 조정할 것\n`,
);
process.exit(failures === 0 ? 0 : 1);
