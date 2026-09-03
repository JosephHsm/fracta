/**
 * 데모 — 원장 훼손 → 대사 배치 검출 시연 (FSD §14 명시 조건).
 *
 * <p>불변식 INV-1(원장 잔고 합 = 발행 수량)을 <b>의도적으로 깨고</b>, 대사 배치가
 * 그것을 찾아내 종목을 거래 중단시키는 것까지 보여준다. 자동 복구는 하지 않는다 —
 * 검출 → 중단 → 수동 조사가 이 프로젝트의 원칙이다.
 *
 * <p>훼손은 애플리케이션 경로로 할 수 없다(그게 정상이다). superuser psql로 직접 UPDATE 한다.
 *
 * <p><b>준비물 없음.</b> 예전에는 대사 크론을 20초로 줄여 재기동해야 했는데 두 가지가 문제였다.
 * (1) 환경변수를 잊거나 다른 셸에서 재기동하면 조용히 원복된다. (2) 일일 대사는 `runDate`
 * 하나로 식별돼 <b>같은 날 두 번째 실행이 거부</b>된다 — 90초를 기다렸는데 아무 일도
 * 일어나지 않는다. 실제로 촬영 중에 그랬다.
 * 지금은 ADMIN 수동 실행 API를 부른다. 기다리지 않고, 몇 번이든 다시 시연할 수 있다.
 *
 *   node scripts/demo/tamper-and-detect.mjs
 */
import { execFileSync } from "node:child_process";

const CONTAINER = process.env.PG_CONTAINER ?? "fracta-postgres";
const BASE = process.env.FRACTA_API ?? "http://localhost:8080";
const ADMIN_EMAIL = process.env.DEMO_ADMIN_EMAIL ?? "admin@fracta.demo";
const ADMIN_PASSWORD = process.env.DEMO_PASSWORD ?? "demo-password-1!";

const bold = (text) => `[1m${text}[0m`;
const red = (text) => `[31m${text}[0m`;
const green = (text) => `[32m${text}[0m`;

function sql(query) {
  return execFileSync(
    "docker",
    ["exec", CONTAINER, "psql", "-U", "postgres", "-d", "fracta", "-tAF", "|", "-c", query],
    { encoding: "utf8" },
  ).trim();
}

const rows = (query) =>
  sql(query)
    .split("\n")
    .filter(Boolean)
    .map((line) => line.split("|"));

/** 대사 즉시 실행은 ADMIN 전용이다. */
async function adminToken() {
  const response = await fetch(`${BASE}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(`관리자 로그인 실패 ${response.status}: ${JSON.stringify(body.error ?? body)}`);
  }
  return body.data.accessToken;
}

async function runReconciliation(token) {
  const response = await fetch(`${BASE}/api/v1/admin/batch/reconciliation`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(`대사 실행 실패 ${response.status}: ${JSON.stringify(body.error ?? body)}`);
  }
  return body.data;
}

async function main() {
  console.log(bold("\nFRACTA 원장 훼손 → 대사 배치 검출 시연\n"));

  // ── 1. 대상 고르기 ────────────────────────────────────
  const [target] = rows(`
    SELECT b.token_symbol, b.owner_id, b.units
      FROM ledger_balance b
      JOIN issuance i ON i.token_symbol = b.token_symbol
     WHERE i.status = 'LISTED' AND b.units > 0
     ORDER BY b.units DESC LIMIT 1`);

  if (!target) {
    console.log(red("상장 종목의 잔고가 없다. 먼저 시드를 실행해라: node scripts/seed/demo-data.mjs\n"));
    process.exitCode = 1;
    return;
  }

  const [symbol, ownerId, units] = target;
  console.log(`대상 종목  ${symbol}`);
  console.log(`대상 잔고  owner ${ownerId} — ${Number(units).toLocaleString()}조각`);

  const before = sql(`SELECT status FROM issuance WHERE token_symbol = '${symbol}'`);
  console.log(`현재 상태  ${green(before)}\n`);

  // ── 2. 훼손 ──────────────────────────────────────────
  const tampered = Number(units) + 999;
  sql(`UPDATE ledger_balance SET units = ${tampered}
        WHERE token_symbol = '${symbol}' AND owner_id = ${ownerId}`);
  console.log(red(`훼손      ledger_balance.units ${units} → ${tampered} (직접 UPDATE)`));
  console.log("          애플리케이션 경로로는 불가능하다. superuser로 DB를 직접 건드렸다.\n");

  // ── 3. 대사를 지금 돌린다 ─────────────────────────────
  console.log("대사 배치 실행 (ADMIN 수동 실행)…");
  const run = await runReconciliation(await adminToken());
  console.log(`          job=${run.jobStatus} exit=${run.exitCode}\n`);

  const violations = rows(`
    SELECT invariant_code, token_symbol,
           COALESCE(expected_value::text, '-'), COALESCE(actual_value::text, '-'),
           COALESCE(difference_value::text, '-'), COALESCE(detail, '')
      FROM reconciliation_result
     WHERE token_symbol = '${symbol}' AND valid = false
     ORDER BY id DESC LIMIT 3`);

  if (violations.length === 0) {
    console.log(red("위반이 검출되지 않았다. 대사 로직이나 훼손 대상을 확인해라.\n"));
    process.exitCode = 1;
    return;
  }

  console.log(bold(green("검출됨\n")));
  for (const [code, token, expected, actual, difference, detail] of violations) {
    console.log(`  ${red(code)}  ${token}`);
    console.log(`    기대 ${expected} / 실제 ${actual} / 차이 ${difference}`);
    if (detail) console.log(`    ${detail}`);
    console.log("");
  }

  const after = sql(`SELECT status FROM issuance WHERE token_symbol = '${symbol}'`);
  console.log(`종목 상태  ${before} → ${red(after)}`);
  console.log("          위반이 검출된 종목은 거래가 중단된다. 자동 복구는 하지 않는다.\n");

  const openOrders = sql(
    `SELECT count(*) FROM trade_order WHERE token_symbol = '${symbol}' AND status = 'OPEN'`);
  console.log(`미체결 주문 ${openOrders}건 (중단 시 전량 정리된다)\n`);
}

main().catch((error) => {
  console.error(red(`\n실패: ${error.message}\n`));
  process.exitCode = 1;
});
