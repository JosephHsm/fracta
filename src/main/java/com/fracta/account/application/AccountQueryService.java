package com.fracta.account.application;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InvestorId;
import com.fracta.account.api.KycStatus;
import com.fracta.account.infrastructure.InvestorRepository;
import com.fracta.common.money.Money;

/** 다른 모듈의 투자자 조회 창구 (AccountQueryPort 구현). */
@Service
public class AccountQueryService implements AccountQueryPort {

    private final InvestorRepository investors;

    public AccountQueryService(InvestorRepository investors) {
        this.investors = investors;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvestorSummary> findInvestor(InvestorId id) {
        return investors.findById(id.value())
                .map(i -> new InvestorSummary(InvestorId.of(i.id()), i.name(), i.kycStatus(),
                        i.validRiskGrade(Instant.now())));
    }

    @Override
    @Transactional(readOnly = true)
    public Money cashBalanceOf(InvestorId id) {
        return investors.findById(id.value())
                .map(i -> Money.of(i.cashBalance()))
                .orElse(Money.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isKycVerified(InvestorId id) {
        return investors.findById(id.value())
                .map(i -> i.kycStatus() == KycStatus.VERIFIED)
                .orElse(false);
    }
}
