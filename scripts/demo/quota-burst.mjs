/**
 * 데모 — 오픈 API 쿼터 초과(429) 시연.
 *
 * <p>개발자 포털 샌드박스 콘솔은 한 번에 한 건씩 호출한다. 초당 한도를 넘기려면 짧은 시간에
 * 여러 건을 던져야 해서 화면 클릭으로는 재현하기 어렵다. 이 스크립트가 그 역할을 한다.
 *
 * <p>흐름 — 웹앱 JWT로 개발자 API에 앱을 하나 만들고(시크릿은 이때 한 번만 내려온다),
 * 그 자격증명으로 OAuth2 client_credentials 토큰을 받아 샌드박스 엔드포인트를 연속 호출한다.
 * 응답의 `X-RateLimit-*` 헤더와 429가 그대로 보이도록 매 건을 출력한다.
 *
 *   node scripts/demo/quota-burst.mjs [호출수]
 */
const BASE = process.env.FRACTA_API ?? "http://localhost:8080";
const EMAIL = process.env.DEMO_EMAIL ?? "demo@fracta.demo";
const PASSWORD = process.env.DEMO_PASSWORD ?? "demo-password-1!";
const COUNT = Number(process.argv[2] ?? 25);

const bold = (text) => `[1m${text}[0m`;
const red = (text) => `[31m${text}[0m`;
const green = (text) => `[32m${text}[0m`;

async function json(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, options);
  const body = await response.json();
  if (!response.ok) {
    throw new Error(`${path} → ${response.status} ${JSON.stringify(body.error ?? body)}`);
  }
  return body.data;
}

const login = async () =>
  (await json("/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: EMAIL, password: PASSWORD }),
  })).accessToken;

async function createSandboxClient(jwt) {
  return json("/api/v1/developer/clients", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${jwt}` },
    body: JSON.stringify({
      name: `Quota Demo ${new Date().toISOString().slice(11, 19)}`,
      env: "SANDBOX",
      scopes: ["MARKET_READ"],
    }),
  });
}

async function clientCredentialsToken(clientId, clientSecret) {
  // OAuth2 표준대로 폼 파라미터(snake_case)다 — JSON 본문이 아니다
  const form = new URLSearchParams({
    grant_type: "client_credentials",
    client_id: clientId,
    client_secret: clientSecret,
  });
  const response = await fetch(`${BASE}/open/sandbox/v1/oauth/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form,
  });
  const body = await response.json();
  if (!response.ok) throw new Error(`토큰 발급 실패 ${response.status}: ${JSON.stringify(body)}`);
  return body.data.accessToken;
}

async function main() {
  console.log(bold(`\nFRACTA 오픈 API 쿼터 시연 — ${COUNT}건 연속 호출\n`));

  const jwt = await login();
  const client = await createSandboxClient(jwt);
  // 생성 응답은 시크릿 전용 뷰(IssuedClientView)라 쿼터가 없다 — 목록에서 가져온다
  const listed = (
    await json("/api/v1/developer/clients", { headers: { Authorization: `Bearer ${jwt}` } })
  ).find((entry) => entry.clientId === client.clientId);

  console.log(`앱 생성   ${client.clientId}`);
  console.log(
    `쿼터      초당 ${listed?.rateLimitPerSec ?? "?"}건 · 일 ${(listed?.rateLimitPerDay ?? 0).toLocaleString()}건`,
  );
  console.log(`시크릿    ${String(client.clientSecret).slice(0, 12)}… (발급 직후 1회만 내려온다)\n`);

  const token = await clientCredentialsToken(client.clientId, client.clientSecret);

  let ok = 0;
  let limited = 0;

  // 순차가 아니라 동시에 던진다 — 순차로는 초당 한도에 걸리지 않는다
  const calls = Array.from({ length: COUNT }, (_, index) =>
    fetch(`${BASE}/open/sandbox/v1/tokens`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then(async (response) => ({
      index: index + 1,
      status: response.status,
      remaining: response.headers.get("X-RateLimit-Remaining"),
      limit: response.headers.get("X-RateLimit-Limit"),
      retryAfter: response.headers.get("Retry-After"),
      code: response.ok ? null : (await response.json()).error?.code,
    })),
  );

  for (const result of await Promise.all(calls)) {
    if (result.status === 429) {
      limited += 1;
      console.log(
        `  ${String(result.index).padStart(2)} ${red(`429 ${result.code}`)}` +
          `  남은 호출 ${result.remaining ?? "-"}/${result.limit ?? "-"}` +
          (result.retryAfter ? `  Retry-After ${result.retryAfter}s` : ""),
      );
    } else {
      ok += 1;
      console.log(
        `  ${String(result.index).padStart(2)} ${green(String(result.status))}` +
          `       남은 호출 ${result.remaining ?? "-"}/${result.limit ?? "-"}`,
      );
    }
  }

  console.log(bold(`\n성공 ${ok}건 · 쿼터 차단 ${limited}건\n`));
  if (limited === 0) {
    console.log("한도에 걸리지 않았다. 호출 수를 늘려서 다시 실행해라: node scripts/demo/quota-burst.mjs 50\n");
  }
}

main().catch((error) => {
  console.error(`\n실패: ${error.message}\n`);
  process.exitCode = 1;
});
