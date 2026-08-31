package com.fracta.external.broker.plug;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fracta.common.config.BrokerProperties;

/** broker.rate-limit.strategy 설정으로 활성 쿼터 전략을 고른다 (sliding | bucket). */
@Component
public class RateLimiterSelector {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterSelector.class);

    private final BrokerRateLimiter active;

    public RateLimiterSelector(List<BrokerRateLimiter> limiters, BrokerProperties properties) {
        String strategy = properties.rateLimit().strategy();
        this.active = limiters.stream()
                .filter(l -> l.name().equals(strategy))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "지원하지 않는 broker.rate-limit.strategy 값: " + strategy));
        log.info("증권사 쿼터 전략 = {} (초당 {}건)", active.name(), properties.rateLimit().perSec());
    }

    public BrokerRateLimiter active() {
        return active;
    }
}
