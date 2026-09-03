package com.fracta.external.broker.plug;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fracta.common.config.BrokerProperties;
import com.fracta.external.broker.EtfReferencePort;

/**
 * ETF 현재가에서 NAV·괴리율을 읽는다.
 *
 * <p><b>NAV는 {@code Output_3}에 있다.</b> 응답이 5개 블록으로 나뉘는데 {@code Output_0}에는
 * 현재가·호가만 있고 NAV·괴리율·LP 잔량은 별도 블록이다. 처음에 Output_0만 보고
 * "NAV 필드가 비어 있다"고 잘못 읽었다(2026-09-03).
 *
 * <pre>
 *   Output_0  현재가·10호가·거래원 (131개 항목)
 *   Output_1  최근 체결
 *   Output_2  예상체결
 *   Output_3  NAV·괴리율·추적오차·LP 의무호가 ← 여기
 *   Output_4  기초지수
 * </pre>
 */
@Component
@Profile("plug")
@Primary
public class NamuhPlugEtfReferenceAdapter implements EtfReferencePort {

    private static final Logger log = LoggerFactory.getLogger(NamuhPlugEtfReferenceAdapter.class);

    private static final String EP_ETF_CURRENT = "etfCurrent";
    private static final String DEFAULT_MARKET = "KRX";

    private final PlugApiClient client;
    private final BrokerProperties properties;

    public NamuhPlugEtfReferenceAdapter(PlugApiClient client, BrokerProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Optional<EtfReference> reference(String ticker) {
        if (!properties.supportedOnCurrentEnv(EP_ETF_CURRENT)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = client.call(properties.endpoint(EP_ETF_CURRENT),
                    Map.of("iem_cd", ticker, "market_cd", DEFAULT_MARKET));

            Map<String, Object> price = asMap(body.get("Output_0"));
            Map<String, Object> nav = asMap(body.get("Output_3"));
            // ETF가 아니면 NAV 블록이 아예 없거나 비어 온다 — 오류가 아니다
            if (nav.isEmpty() || nav.get("itmt_last_nav") == null) {
                return Optional.empty();
            }

            return Optional.of(new EtfReference(
                    ticker,
                    asLong(price.get("stck_prpr")),
                    asDecimal(nav.get("itmt_last_nav")),
                    asDecimal(nav.get("dprt")),
                    asDecimal(nav.get("trc_errt")),
                    sumLpQuantities(nav, "lp_askp_rsqn"),
                    sumLpQuantities(nav, "lp_bidp_rsqn")));
        } catch (RuntimeException e) {
            // ETF 지표는 부가 정보다. 실패해도 조각 괴리율은 계산된다.
            log.warn("ETF 기준 지표 조회 실패: ticker={} 사유={}", ticker, e.getMessage());
            return Optional.empty();
        }
    }

    /** LP 의무호가는 10단계로 나뉘어 온다. 합계만 쓴다. */
    private static long sumLpQuantities(Map<String, Object> nav, String prefix) {
        long total = 0;
        for (int level = 1; level <= 10; level++) {
            total += asLong(nav.get(prefix + level));
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 같은 값이 엔드포인트에 따라 숫자로도 문자열로도 온다 — 관대하게 읽는다. */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static BigDecimal asDecimal(Object value) {
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
