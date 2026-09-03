/**
 * 데모 — 원장 훼손 → 대사 배치 검출 시연 (FSD §14 명시 조건).
 *
 * <p>불변식 INV-1(원장 잔고 합 = 발행 수량)을 <b>의도적으로 깨고</b>, 일일 대사 배치가
 * 그것을 찾아내 종목을 거래 중단시키는 것까지 보여준다. 자동 복구는 하지 않는다 —
 * 검출 → 중단 → 수동 조사가 이 프로젝트의 원칙이다.
 *
 * <p>훼손은 애플리케이션 경로로 할 수 없다(그게 정상이다). superuser psql로 직접 UPDATE 한다.
 *
 * <p><b>촬영 전 준비</b> — 대사 배치는 기본 스케줄이 매일 23시다. 데모에서는 주기를 줄인다:
 * <pre>
 *   docker compose stop app
 *   BATCH_SCHEDULING_ENABLED=true BATCH_RECONCILIATION_CRON="0/20 * * * * *" docker compose up -d app
 * </pre>
 *
 *   node scripts/demo/tamper-and-detect.mjs
 */
import { execFileSync } from "node:child_process";

const CONTAINER = process.env.PG_CONTAINER ?? "fracta-postgres";
const WAIT_SECONDS = Number(process.env.DEMO_WAIT_SECONDS ?? 90);

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

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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

  const before = sql(
    `SELECT status FROM issuance WHERE token_symbol = '${symbol}'`);
  console.log(`현재 상태  ${green(before)}\n`);

  // ── 2. 훼손 ──────────────────────────────────────────
  const tampered = Number(units) + 999;
  sql(`UPDATE ledger_balance SET units = ${tampered}
        WHERE token_symbol = '${symbol}' AND owner_id = ${ownerId}`);
  console.log(red(`훼손      ledger_balance.units ${units} → ${tampered} (직접 UPDATE)`));
  console.log("          애플리케이션 경로로는 불가능하다. superuser로 DB를 직접 건드렸다.\n");

  // ── 3. 배치가 잡을 때까지 기다린다 ──────────────────────
  console.log(`대사 배치를 기다린다 (최대 ${WAIT_SECONDS}초)…\n`);
  const deadline = Date.now() + WAIT_SECONDS * 1000;

  while (Date.now() < deadline) {
    const violations = rows(`
      SELECT invariant_code, token_symbol,
             COALESCE(expected_value::text, '-'), COALESCE(actual_value::text, '-'),
             COALESCE(difference_value::text, '-'), COALESCE(detail, '')
        FROM reconciliation_result
       WHERE token_symbol = '${symbol}' AND valid = false
       ORDER BY id DESC LIMIT 3`);

    if (violations.length > 0) {
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
      return;
    }
    process.stdout.write(".");
    await sleep(3000);
  }

  console.log(red(`\n\n${WAIT_SECONDS}초 안에 검출되지 않았다.`));
  console.log("배치 스케줄러가 켜져 있는지 확인해라 — 파일 상단 '촬영 전 준비' 참조.\n");
  process.exitCode = 1;
}

main().catch((error) => {
  console.error(red(`\n실패: ${error.message}\n`));
  process.exitCode = 1;
});
