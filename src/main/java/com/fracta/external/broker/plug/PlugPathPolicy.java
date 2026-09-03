package com.fracta.external.broker.plug;

import java.util.List;

/**
 * 증권사 API 경로 정책 — <b>조회만 허용한다.</b>
 *
 * <p>예전에는 안전이 <i>도메인</i>에 걸려 있었다(moapi만 허용). 하지만 위험을 결정하는 건
 * 도메인이 아니라 <b>경로</b>다. 모의 도메인이라도 주문 경로를 부르면 모의 주문이 나가고,
 * 실전 도메인이라도 시세 경로만 부르면 아무것도 체결되지 않는다.
 *
 * <p>2026-09-03 모의 도메인이 시세를 전면 차단하면서(IGW40023) 시세 조회를 실전 도메인으로
 * 옮겨야 했다. 그때 안전 근거를 도메인에서 경로로 옮겼다. 지금이 더 정확한 규칙이다.
 *
 * <p>이 클래스는 두 곳에서 쓰인다.
 * <ul>
 *   <li>부팅 시 — 설정된 엔드포인트가 전부 조회 경로인지 검증({@code BrokerSafetyValidator})</li>
 *   <li>호출 직전 — 런타임 차단({@code PlugApiClient})</li>
 * </ul>
 * 설정을 통과해도 코드가 임의 경로를 만들어 부를 수 있으므로 두 겹 모두 필요하다.
 */
public final class PlugPathPolicy {

    /** 조회 전용 경로. 여기 없는 경로는 부를 수 없다. */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/krstock/quote/",
            "/n2/acctinfo");

    private PlugPathPolicy() {
    }

    public static boolean isAllowed(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return ALLOWED_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    /**
     * 허용되지 않은 경로면 던진다.
     *
     * <p>이 프로젝트는 증권사에 <b>주문을 보내지 않는다.</b> 매매는 자체 오더북에서 체결된다.
     * 주문 경로 호출은 버그가 아니라 사고이므로, 예외 메시지에 그 사실을 남긴다.
     */
    public static void assertReadOnly(String path) {
        if (!isAllowed(path)) {
            throw new IllegalStateException(
                    "조회 전용 정책 위반 — 이 경로는 호출할 수 없습니다: " + path
                            + " (허용: " + String.join(", ", ALLOWED_PREFIXES) + ")");
        }
    }

    public static List<String> allowedPrefixes() {
        return ALLOWED_PREFIXES;
    }
}
