/**
 * 데모 데이터 시드 — 실제 REST API를 그대로 호출한다.
 *
 * <p>SQL로 행을 꽂아넣지 않는다. 발행 승인 → 청약 → 배정 → 상장 → 주문 → 체결을
 * 진짜 업무 흐름으로 태워야 원장 불변식·괴리율·감사 로그가 실제 값으로 채워진다.
 * 화면이 붙을 데이터를 만드는 동시에 백엔드 전 구간을 한 번 훑는 스모크 테스트이기도 하다.
 *
 * 전제: docker compose up -d && ./gradlew bootRun
 *
 *   node scripts/seed/demo-data.mjs
 *
 * 예외 — ADMIN 승격만 SQL을 쓴다. 역할을 바꾸는 API가 없고(있으면 안 되고),
 * 관리자 승인 단계 없이는 발행이 상장까지 갈 수 없다.
 *
 * <p><b>재실행하면 종목이 누적된다.</b> 데모 계정(demo@fracta.demo 등)은 고정이라 그대로
 * 재사용되지만 발행·종목은 매번 새로 생긴다. 지우는 기능은 만들지 않았다 —
 * `ledger_transaction`은 UPDATE/DELETE 금지(FSD §8)라 부분 삭제가 불변식을 깨기 때문이다.
 * 깨끗한 상태가 필요하면 DB를 통째로 다시 만든다:
 *   docker compose down -v && docker compose up -d && ./gradlew bootRun
 */
import { execFileSync } from "node:child_process";

import { DEMO_PROSPECTUS_PAGES, buildPdf } from "./prospectus.mjs";

const BASE = process.env.FRACTA_API ?? "http://localhost:8080";
const PASSWORD = "demo-password-1!";

/** 재실행해도 이메일이 겹치지 않게 접미사를 붙인다. 같은 분에 두 번 돌려도 안 겹치게 초·난수까지 넣는다. */
const RUN = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;

let step = 0;
const log = (message) => console.log(`  ${String(++step).padStart(2, " ")}. ${message}`);
const section = (title) => console.log(`\n── ${title} ${"─".repeat(Math.max(0, 46 - title.length))}`);

async function api(path, { method = "GET", token, body, idempotencyKey } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;

  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  const text = await response.text();
  const json = text ? JSON.parse(text) : {};

  if (!response.ok) {
    const error = json.error ?? {};
    throw new Error(
      `${method} ${path} → ${response.status} ${error.code ?? ""} ${error.message ?? text}`,
    );
  }
  return json.data;
}

// ── 계정 ────────────────────────────────────────────────

async function login(email, password = PASSWORD) {
  const token = await api("/api/v1/auth/login", { method: "POST", body: { email, password } });
  return token.accessToken;
}

/**
 * 이미 있는 계정이면 로그인만 한다.
 *
 * <p>데모용 고정 계정(demo@fracta.demo 등)은 재실행 때마다 이메일이 바뀌면 안 된다 —
 * 로그인 정보를 매번 다시 알려줘야 하면 데모 대본이 성립하지 않는다.
 * 이미 존재하면 성향·예수금 설정만 다시 태운다.
 */
async function signupOrLogin(name, email, password = PASSWORD) {
  let fresh = true;
  try {
    await api("/api/v1/auth/signup", { method: "POST", body: { name, email, password } });
  } catch (error) {
    if (!String(error.message).includes("VALID_DUPLICATE_EMAIL")) throw error;
    fresh = false;
  }
  return { name, email, password, token: await login(email, password), fresh };
}

/** 매 실행마다 새로 만들어야 하는 계정 (청약·호가에 참여하는 군중). */
async function signup(name, email, password = PASSWORD) {
  await api("/api/v1/auth/signup", { method: "POST", body: { name, email, password } });
  return { name, email, password, token: await login(email, password), fresh: true };
}

/**
 * 투자성향 진단. 8문항 전부 같은 답을 내면 그 등급이 나온다 —
 * 1(안정형)이면 위험등급 3 상품 청약이 SUIT_PROFILE_MISMATCH로 막힌다.
 */
async function setRiskProfile(user, answerLevel) {
  const result = await api("/api/v1/investors/me/risk-profile", {
    method: "POST",
    token: user.token,
    body: { answers: Array(8).fill(answerLevel) },
  });
  return result;
}

async function deposit(user, amount) {
  await api("/api/v1/investors/me/cash/deposit", {
    method: "POST",
    token: user.token,
    body: { amount },
  });
}

/** 역할 변경 API는 없다(있으면 안 된다). 데모 편의를 위해 DB에서만 승격한다. */
function promoteToAdmin(email) {
  try {
    execFileSync(
      "docker",
      [
        "exec", "fracta-postgres",
        "psql", "-U", "postgres", "-d", "fracta", "-c",
        `UPDATE investor SET role = 'ADMIN' WHERE email = '${email}'`,
      ],
      { stdio: "pipe" },
    );
  } catch (error) {
    throw new Error(
      `ADMIN 승격 실패 — fracta-postgres 컨테이너가 떠 있어야 합니다.\n${error.message}`,
    );
  }
}

// ── 발행 ────────────────────────────────────────────────

async function createIssuance(issuer, { assetName, assetType, brokerTicker, splitRatio, totalUnits, unitPrice, subscriptionDays }) {
  const asset = await api("/api/v1/assets", {
    method: "POST",
    token: issuer.token,
    body: {
      name: assetName,
      assetType,
      assetCode: null,
      brokerTicker,
      splitRatio,
      description: `${assetName} 조각투자 기초자산`,
    },
  });

  const now = Date.now();
  const issuance = await api("/api/v1/issuances", {
    method: "POST",
    token: issuer.token,
    body: {
      assetId: asset.assetId,
      totalUnits,
      unitPrice,
      subscriptionStartAt: new Date(now - 60_000).toISOString(),
      subscriptionEndAt: new Date(now + subscriptionDays * 86_400_000).toISOString(),
    },
  });

  await api(`/api/v1/issuances/${issuance.issuanceId}/submit`, {
    method: "POST",
    token: issuer.token,
  });
  return { ...issuance, assetId: asset.assetId };
}

/**
 * 투자설명서 업로드 + AI 인덱싱.
 *
 * <p>둘 다 해야 투자설명서 화면의 AI 사이드패널이 동작한다 — 업로드만 하면 PDF는 보이지만
 * 질의에 답할 근거(pgvector 청크)가 없다.
 *
 * <p>AI 서비스가 꺼져 있으면 인덱싱만 건너뛴다. 뷰어는 그래도 뜬다.
 */
async function attachProspectus(issuer, admin, issuanceId) {
  const pdf = buildPdf(DEMO_PROSPECTUS_PAGES);
  const form = new FormData();
  form.append("file", new Blob([pdf], { type: "application/pdf" }), "prospectus.pdf");

  const response = await fetch(`${BASE}/api/v1/issuances/${issuanceId}/prospectus`, {
    method: "POST",
    headers: { Authorization: `Bearer ${issuer.token}` },
    body: form,
  });
  if (!response.ok) {
    throw new Error(`투자설명서 업로드 실패 ${response.status}: ${await response.text()}`);
  }

  try {
    await api(`/api/v1/admin/ai/issuances/${issuanceId}/index`, {
      method: "POST",
      token: admin.token,
    });
    return "인덱싱 완료";
  } catch (error) {
    return `인덱싱 건너뜀 (${String(error.message).split("→")[1]?.trim() ?? error.message})`;
  }
}

async function approveAndOpen(admin, issuanceId) {
  await api(`/api/v1/admin/issuances/${issuanceId}/approve`, { method: "POST", token: admin.token });
  try {
    await api(`/api/v1/admin/issuances/${issuanceId}/open-subscription`, {
      method: "POST",
      token: admin.token,
    });
  } catch (error) {
    // IS-06 스케줄러가 먼저 SUBSCRIBING으로 넘겼을 수 있다
    if (!String(error.message).includes("STATE_INVALID_TRANSITION")) throw error;
  }
}

async function subscribe(user, issuanceId, units) {
  return api(`/api/v1/issuances/${issuanceId}/subscriptions`, {
    method: "POST",
    token: user.token,
    idempotencyKey: `seed-${issuanceId}-${user.email}-${units}`,
    body: { units },
  });
}

/**
 * 배정 마감(SU-05)이 배정과 상장을 함께 처리한다. `/list`는 청약 없이 상장하는
 * Phase 3의 수동 경로라, 이미 LISTED면 호출하면 안 된다.
 */
async function listToken(admin, issuanceId, issuerToken) {
  await api(`/api/v1/admin/issuances/${issuanceId}/start-allotment`, {
    method: "POST",
    token: admin.token,
  });
  await api(`/api/v1/admin/issuances/${issuanceId}/finalize-allotment`, {
    method: "POST",
    token: admin.token,
  }).catch((error) => {
    if (!String(error.message).includes("STATE_")) throw error;
  });

  const detail = await api(`/api/v1/issuances/${issuanceId}`, { token: issuerToken });
  if (detail.status !== "LISTED") {
    await api(`/api/v1/admin/issuances/${issuanceId}/list`, { method: "POST", token: admin.token });
  }
  return detail.status;
}

// ── 유통 ────────────────────────────────────────────────

async function placeOrder(user, tokenSymbol, side, orderType, price, units) {
  return api(`/api/v1/tokens/${tokenSymbol}/orders`, {
    method: "POST",
    token: user.token,
    idempotencyKey: `seed-${tokenSymbol}-${user.email}-${side}-${price}-${units}-${Math.random()}`,
    body: { side, orderType, price, units },
  });
}

/**
 * 호가창과 체결 내역을 함께 만든다.
 *
 * <p>배정 마감(SU-05)이 청약자에게 물량을 배정하므로 <b>매도측은 청약자</b>다.
 * 발행인은 상장 물량을 갖지 않는다 — IS-07 수동 상장 경로에서만 발행인에게 발행된다.
 *
 * <p>청약자가 매도호가를 깔고, 나머지 투자자가 아래쪽에 매수호가를 쌓은 뒤,
 * 일부가 매도호가를 때려 체결을 만든다. 체결가로 괴리율 단계(정상/경고/중단)를 만든다.
 */
async function buildMarket(sellers, buyers, tokenSymbol, { askLevels, bidLevels, takePrice, takeUnits }) {
  for (const [price, units, sellerIndex] of askLevels) {
    await placeOrder(sellers[sellerIndex], tokenSymbol, "SELL", "LIMIT", price, units);
  }
  for (const [index, [price, units]] of bidLevels.entries()) {
    await placeOrder(buyers[index % buyers.length], tokenSymbol, "BUY", "LIMIT", price, units);
  }
  const results = [];
  for (const [index, units] of takeUnits.entries()) {
    results.push(await placeOrder(buyers[index % buyers.length], tokenSymbol, "BUY", "LIMIT", takePrice, units));
  }
  return results;
}

// ── 메인 ────────────────────────────────────────────────

async function main() {
  console.log(`FRACTA 데모 데이터 시드 — ${BASE}\n실행 태그: ${RUN}`);

  const health = await fetch(`${BASE}/actuator/health`).catch(() => null);
  if (!health?.ok) {
    throw new Error(`서버에 연결할 수 없습니다: ${BASE}\n./gradlew bootRun 이 떠 있는지 확인하세요.`);
  }

  section("계정");
  const admin = await signupOrLogin("데모 관리자", "admin@fracta.demo");
  promoteToAdmin(admin.email);
  // 역할이 바뀌었으므로 토큰을 다시 받는다 — JWT에 role 클레임이 박혀 있다
  admin.token = await login(admin.email);
  log(`관리자 ${admin.email}`);

  const issuer = await signupOrLogin("프랙타 자산운용", "issuer@fracta.demo");
  await setRiskProfile(issuer, 5);
  log(`발행인 ${issuer.email}`);

  const demo = await signupOrLogin("김프랙타", "demo@fracta.demo");
  await setRiskProfile(demo, 5);
  await deposit(demo, 100_000_000);
  log(`데모 투자자 ${demo.email} (예수금 1억원, 공격투자형)`);

  const conservative = await signupOrLogin("이안정", "conservative@fracta.demo");
  await setRiskProfile(conservative, 1);
  await deposit(conservative, 10_000_000);
  log(`보수 투자자 ${conservative.email} (안정형 — 적합성 차단 시연용)`);

  const crowd = [];
  for (let i = 0; i < 8; i += 1) {
    const user = await signup(`투자자${i + 1}`, `seed-inv${i + 1}-${RUN}@fracta.demo`);
    await setRiskProfile(user, 5);
    await deposit(user, 60_000_000);
    crowd.push(user);
  }
  log(`일반 투자자 ${crowd.length}명 (각 예수금 6,000만원)`);

  // 앞 5명은 청약해서 물량을 받는 매도측, 나머지는 매수측이다
  const sellers = crowd.slice(0, 5);
  const buyers = [demo, ...crowd.slice(5)];

  // 기초자산 기준가 = 티커 숫자 / splitRatio.
  // MOCK-1000000 + splitRatio 100 → 조각당 기준가 10,000원.
  const markets = [
    {
      label: "한남동 상업시설",
      config: { assetName: "한남동 상업시설", assetType: "REAL_ESTATE", brokerTicker: "MOCK-1000000", splitRatio: 100, totalUnits: 100_000, unitPrice: 10_000, subscriptionDays: 7 },
      market: { askLevels: [[10_200, 200, 0], [10_300, 300, 1], [10_400, 400, 2], [10_500, 500, 3]], bidLevels: [[10_000, 500], [9_900, 800], [9_800, 1_200], [9_700, 900]], takePrice: 10_200, takeUnits: [120, 60] },
    },
    {
      label: "성수동 지식산업센터",
      config: { assetName: "성수동 지식산업센터", assetType: "REAL_ESTATE", brokerTicker: "MOCK-1000000", splitRatio: 100, totalUnits: 60_000, unitPrice: 10_000, subscriptionDays: 5 },
      // 체결가 11,500 → 기준가 10,000 대비 +15% → 괴리율 경고(주황)
      market: { askLevels: [[11_500, 250, 0], [11_700, 350, 1], [11_900, 450, 2]], bidLevels: [[11_000, 700], [10_800, 1_000], [10_600, 800]], takePrice: 11_500, takeUnits: [150, 90] },
    },
    {
      label: "제주 리조트 지분",
      config: { assetName: "제주 리조트 지분", assetType: "REAL_ESTATE", brokerTicker: "MOCK-1000000", splitRatio: 100, totalUnits: 40_000, unitPrice: 10_000, subscriptionDays: 3 },
      // 체결가 12,600 → +26% → TR-08 자동 거래중단(빨강). 미체결 주문도 함께 정리된다
      market: { askLevels: [[12_600, 250, 0], [12_800, 350, 1]], bidLevels: [[12_000, 600], [11_800, 900]], takePrice: 12_600, takeUnits: [150] },
    },
  ];

  const listed = [];
  for (const entry of markets) {
    section(`상장 종목 — ${entry.label}`);
    const issuance = await createIssuance(issuer, entry.config);
    log(`발행 생성 ${issuance.tokenSymbol} (id=${issuance.issuanceId})`);

    await approveAndOpen(admin, issuance.issuanceId);
    log("승인 → 청약 개시");

    if (listed.length === 0) {
      log(`투자설명서 첨부 — ${await attachProspectus(issuer, admin, issuance.issuanceId)}`);
    }

    // 청약 수량이 곧 상장 후 매도 가능 물량이 된다
    for (const [index, user] of sellers.entries()) {
      await subscribe(user, issuance.issuanceId, 300 + index * 100);
    }
    log(`청약 ${sellers.length}건 접수 (총 2,500조각)`);

    await listToken(admin, issuance.issuanceId, issuer.token);
    log("배정 → 상장 완료");

    await buildMarket(sellers, buyers, issuance.tokenSymbol, entry.market);
    log(`호가 ${entry.market.askLevels.length + entry.market.bidLevels.length}단계 + 체결 ${entry.market.takeUnits.length}건`);

    const premium = await api(`/api/v1/tokens/${issuance.tokenSymbol}/executions?size=1`, {
      token: demo.token,
    });
    const rate = premium[0]?.premiumRate;
    log(`최근 체결 괴리율 ${rate == null ? "산출 불가" : `${Number(rate).toFixed(2)}%`}`);

    listed.push({ ...issuance, label: entry.label });
  }

  section("청약 진행 중 발행 (화면용)");
  const subscribing = await createIssuance(issuer, {
    assetName: "여의도 오피스 타워",
    assetType: "REAL_ESTATE",
    brokerTicker: "MOCK-1000000",
    splitRatio: 100,
    totalUnits: 80_000,
    unitPrice: 10_000,
    subscriptionDays: 10,
  });
  await approveAndOpen(admin, subscribing.issuanceId);
  log(`투자설명서 ${DEMO_PROSPECTUS_PAGES.length}쪽 — ${await attachProspectus(issuer, admin, subscribing.issuanceId)}`);
  for (const [index, user] of crowd.entries()) {
    await subscribe(user, subscribing.issuanceId, 150 + index * 50);
  }
  log(`${subscribing.tokenSymbol} 청약 중 — ${crowd.length}건 접수`);

  section("적합성 차단 확인");
  try {
    await subscribe(conservative, subscribing.issuanceId, 10);
    console.log("  !! 차단되지 않았습니다 — 적합성 규칙을 확인하세요");
  } catch (error) {
    const blocked = String(error.message).includes("SUIT_");
    console.log(`  ${blocked ? "OK" : "!!"} ${error.message.split("→")[1]?.trim() ?? error.message}`);
  }

  section("요약");
  console.log(`  로그인 계정: ${demo.email} / ${PASSWORD}`);
  console.log(`  보수 투자자: ${conservative.email} / ${PASSWORD}  (적합성 차단 시연)`);
  console.log(`  관리자     : ${admin.email} / ${PASSWORD}`);
  console.log("  상장 종목  :");
  for (const token of listed) console.log(`    - ${token.tokenSymbol}  ${token.label}`);
  console.log(`  청약 중    : ${subscribing.tokenSymbol}  여의도 오피스 타워`);
  console.log("");
}

main().catch((error) => {
  console.error(`\n실패: ${error.message}\n`);
  process.exit(1);
});
