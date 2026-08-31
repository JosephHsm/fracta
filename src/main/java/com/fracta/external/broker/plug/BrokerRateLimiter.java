package com.fracta.external.broker.plug;

/**
 * 증권사 호출 쿼터 (FSD §9.3). 두 구현을 모두 유지하고 설정으로 스위치한다.
 * 계약: {@link #tryAcquire()}는 즉시 판정, {@link #acquire()}는 허용될 때까지 대기하되
 * 최대 대기 시간을 넘기면 {@link BrokerApiException#rateLimited} 로 거절한다.
 */
public interface BrokerRateLimiter {

    /** sliding | bucket */
    String name();

    boolean tryAcquire();

    void acquire(String path);
}
