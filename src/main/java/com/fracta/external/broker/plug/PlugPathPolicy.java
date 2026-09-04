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

    /**
     * 조회 전용 경로. 여기 없는 경로는 부를 수 없다.
     *
     * <p><b>실제로 부르는 것만 남긴다.</b> 예전에는 계좌 조회(`/n2/acctinfo`)도 열려 있었는데
     * 설정에만 있고 코드에서 한 번도 호출하지 않았다. 방어선에 쓰지 않는 구멍을 열어 두면
     * "시세만 조회한다"는 설명이 그만큼 약해진다.
     *
     * <p>{@code /gbstock/quote/} 는 해외주식 시세다. 국내장이 닫힌 시간에도 시세가 살아 있어야
     * 화면이 실제 주식 앱처럼 동작한다. 해외 주문 경로는 여전히 막힌다 — 화이트리스트에
     * 조회 접두사만 있고, 주문 접두사는 여기 없다.
     */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/krstock/quote/",
            "/gbstock/quote/");

    private PlugPathPolicy() {
    }

    /**
     * 화이트리스트 통과 여부.
     *
     * <p><b>정규화 후에 비교한다.</b> 접두사만 문자열로 보면
     * {@code /krstock/quote/../../order} 같은 경로가 통과한다 — 접두사는 맞지만 서버가
     * {@code ..}를 풀면 주문 경로가 된다. 이 화이트리스트는 이 프로젝트에서 실주문을 막는
     * 마지막 방어선이라 문자열 비교로 둘 수 없다.
     */
    public static boolean isAllowed(String path) {
        String normalized = normalize(path);
        return normalized != null && ALLOWED_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    /**
     * 경로를 비교 가능한 형태로 만든다. 판단할 수 없으면 null — 모르는 건 막는다.
     *
     * <p>쿼리·프래그먼트를 떼고, {@code .}/{@code ..}를 해소하고, 퍼센트 인코딩된
     * {@code %2e%2e}로 정규화를 피해 가는 것도 막는다.
     */
    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String candidate = path;
        // 인코딩으로 숨긴 구분자·상위 경로를 먼저 드러낸다
        if (candidate.contains("%")) {
            try {
                candidate = java.net.URLDecoder.decode(candidate, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;   // 깨진 인코딩 — 무엇을 부르는지 알 수 없다
            }
        }
        // 역슬래시를 슬래시로 취급하는 서버가 있다
        candidate = candidate.replace('\\', '/');
        int cut = candidate.indexOf('?');
        if (cut >= 0) {
            candidate = candidate.substring(0, cut);
        }
        cut = candidate.indexOf('#');
        if (cut >= 0) {
            candidate = candidate.substring(0, cut);
        }
        if (candidate.isBlank()) {
            return null;
        }
        if (!candidate.startsWith("/")) {
            candidate = "/" + candidate;
        }
        String normalized;
        try {
            // 상대 경로 해소는 URI 에 맡긴다 — 직접 구현하면 반드시 구멍이 생긴다
            normalized = java.net.URI.create(candidate).normalize().getPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
        // normalize() 가 다 풀지 못한 잔여 ".." 는 통과시키지 않는다
        if (normalized == null || normalized.contains("..")) {
            return null;
        }
        return normalized;
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
