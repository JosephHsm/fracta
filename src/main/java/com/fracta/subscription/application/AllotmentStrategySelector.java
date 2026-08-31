package com.fracta.subscription.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** subscription.concurrency 설정으로 활성 전략을 고른다 (atomic | pessimistic | redis). */
@Component
public class AllotmentStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(AllotmentStrategySelector.class);

    private final AllotmentStrategy active;

    public AllotmentStrategySelector(List<AllotmentStrategy> strategies,
                                     @Value("${subscription.concurrency:atomic}") String mode) {
        this.active = strategies.stream()
                .filter(s -> s.name().equals(mode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "지원하지 않는 subscription.concurrency 값: " + mode));
        log.info("subscription concurrency strategy = {}", active.name());
    }

    public AllotmentStrategy active() {
        return active;
    }
}
