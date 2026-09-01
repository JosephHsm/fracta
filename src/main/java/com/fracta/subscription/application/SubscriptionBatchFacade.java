package com.fracta.subscription.application;

import org.springframework.stereotype.Service;

import com.fracta.subscription.api.SubscriptionBatchPort;

/** 배치 모듈이 application 구현 타입에 직접 의존하지 않도록 하는 공개 진입점. */
@Service
public class SubscriptionBatchFacade implements SubscriptionBatchPort {

    private final SubscriptionAllotmentService allotmentService;

    public SubscriptionBatchFacade(SubscriptionAllotmentService allotmentService) {
        this.allotmentService = allotmentService;
    }

    @Override
    public void finalizeAllotment(long issuanceId) {
        allotmentService.finalizeAllotment(issuanceId);
    }
}
