// 청약 동시성 부하 테스트 (FSD §8.3): 500 VU × 1회 × 5조각 = 총 신청 2,500 / 발행량 1,000
// 실행: k6 run --summary-export result.json -e ISSUANCE_ID=<id> subscription-load.js
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const tokens = JSON.parse(open('./tokens.json'));
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ISSUANCE = __ENV.ISSUANCE_ID;

export const options = {
    scenarios: {
        subscription: {
            executor: 'per-vu-iterations',
            vus: 500,
            iterations: 1,
            maxDuration: '180s',
        },
    },
};

// 수량 부족 정상 거절은 실패로 집계하지 않는다 — 별도 카운트 (phase-04 문서)
const successfulOrders = new Counter('successful_orders');
const soldOutRejections = new Counter('sold_out_rejections');
const realFailures = new Counter('real_failures');
const transportErrors = new Counter('transport_errors');   // 연결 실패 (status 0)

// 도착 지터: 500개 소켓이 같은 순간에 열리면 Windows 수신 백로그가 넘쳐
// 락 전략이 아니라 TCP 백로그를 측정하게 된다(connection refused). 세 전략 모두 동일 조건.
const ARRIVAL_JITTER_SECONDS = 2;

export default function () {
    sleep(Math.random() * ARRIVAL_JITTER_SECONDS);
    const token = tokens[(__VU - 1) % tokens.length];
    const res = http.post(
        `${BASE}/api/v1/issuances/${ISSUANCE}/subscriptions`,
        JSON.stringify({ units: 5, idempotencyKey: `k6-${__VU}-${Date.now()}-${Math.random()}` }),
        {
            headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
            timeout: '60s',
        },
    );
    if (res.status === 201) {
        successfulOrders.add(1);
        return;
    }
    if (res.status === 0) {
        transportErrors.add(1);
        console.error(`transport error: ${res.error_code} ${res.error}`);
        return;
    }
    let code = '';
    try {
        code = JSON.parse(res.body).error.code;
    } catch (e) { /* body 파싱 불가 → 실제 실패로 집계 */ }
    if (code === 'FUND_INSUFFICIENT_UNITS') {
        soldOutRejections.add(1);
    } else {
        realFailures.add(1);
        console.error(`unexpected ${res.status}: ${String(res.body).slice(0, 200)}`);
    }
}
