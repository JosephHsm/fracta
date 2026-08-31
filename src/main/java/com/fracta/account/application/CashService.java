package com.fracta.account.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.InsufficientCashException;
import com.fracta.account.api.InvestorId;
import com.fracta.account.domain.CashTransaction;
import com.fracta.account.infrastructure.CashTransactionRepository;
import com.fracta.account.infrastructure.InvestorRepository;
import com.fracta.common.money.Money;

/** 예치금 가상 입출금 (AC-05). 증거금 차감은 Phase 4. */
@Service
public class CashService {

    private final InvestorRepository investors;
    private final CashTransactionRepository cashTransactions;

    public CashService(InvestorRepository investors, CashTransactionRepository cashTransactions) {
        this.investors = investors;
        this.cashTransactions = cashTransactions;
    }

    @Transactional
    public Money deposit(InvestorId investorId, Money amount) {
        requirePositive(amount);
        int updated = investors.deposit(investorId.value(), amount.amount());
        if (updated == 0) {
            throw new IllegalArgumentException("투자자가 없다: " + investorId.value());
        }
        long balanceAfter = currentBalance(investorId);
        cashTransactions.save(new CashTransaction(
                investorId.value(), CashTransaction.Type.DEPOSIT, amount.amount(), balanceAfter));
        return Money.of(balanceAfter);
    }

    @Transactional
    public Money withdraw(InvestorId investorId, Money amount) {
        requirePositive(amount);
        int updated = investors.withdraw(investorId.value(), amount.amount());
        if (updated == 0) {
            investors.findById(investorId.value())
                    .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + investorId.value()));
            throw new InsufficientCashException(amount.amount());
        }
        long balanceAfter = currentBalance(investorId);
        cashTransactions.save(new CashTransaction(
                investorId.value(), CashTransaction.Type.WITHDRAW, amount.amount(), balanceAfter));
        return Money.of(balanceAfter);
    }

    @Transactional(readOnly = true)
    public List<CashTransaction> transactionsOf(InvestorId investorId) {
        return cashTransactions.findByInvestorIdOrderByIdDesc(investorId.value());
    }

    private long currentBalance(InvestorId investorId) {
        return investors.findById(investorId.value())
                .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + investorId.value()))
                .cashBalance();
    }

    private void requirePositive(Money amount) {
        if (amount.isZero()) {
            throw new IllegalArgumentException("금액은 1원 이상이어야 한다");
        }
    }
}
