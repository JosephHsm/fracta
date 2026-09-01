package com.fracta.external.broker.api;

/** 배치가 증권사 구현 세부사항을 모르고 토큰 갱신을 요청하는 공개 포트. */
public interface BrokerTokenRefreshPort {

    void refreshIfDueOrThrow();
}
