package com.fracta.account.application;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fracta.account.api.InvestorId;
import com.fracta.account.api.InvestorRegisteredEvent;
import com.fracta.account.infrastructure.InvestorRepository;
import com.fracta.audit.api.Auditable;

/**
 * KYC Mock (AC-02) — 등록 커밋 후 3초 뒤 자동 VERIFIED.
 * 요청 스레드를 점유하지 않도록 TaskScheduler로 지연 실행한다.
 */
@Component
public class KycMockProcessor {

    static final Duration KYC_DELAY = Duration.ofSeconds(3);

    private static final Logger log = LoggerFactory.getLogger(KycMockProcessor.class);

    private final TaskScheduler taskScheduler;
    private final KycVerifier verifier;

    public KycMockProcessor(TaskScheduler taskScheduler, KycVerifier verifier) {
        this.taskScheduler = taskScheduler;
        this.verifier = verifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistered(InvestorRegisteredEvent event) {
        taskScheduler.schedule(() -> {
            try {
                verifier.verify(event.investorId());
            } catch (Exception e) {
                log.error("KYC mock 처리 실패: investorId={}", event.investorId().value(), e);
            }
        }, Instant.now().plus(KYC_DELAY));
    }

    /** 지연 콜백에서 새 트랜잭션으로 실행되는 검증 확정. */
    @Component
    public static class KycVerifier {

        private final InvestorRepository investors;

        public KycVerifier(InvestorRepository investors) {
            this.investors = investors;
        }

        @Transactional
        @Auditable(action = "KYC_VERIFY", targetType = "INVESTOR", targetId = "#p0.value()")
        public long verify(InvestorId investorId) {
            investors.findById(investorId.value()).ifPresent(investor -> investor.verifyKyc());
            return investorId.value();
        }
    }
}
