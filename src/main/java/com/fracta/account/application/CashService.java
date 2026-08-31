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

/** 예치금 가상 입출금 (AC-05) + 내부 이동(증거금·정산, CashPort). */
@Service
public class CashService implements com.fracta.account.api.CashPort {

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

    /** 증거금 홀드 — 출금과 동일한 원자적 차감, 유형만 MARGIN_HOLD (INV-6에서 내부 이동으로 취급). */
    @Override
    @Transactional
    public void holdMargin(InvestorId investorId, Money amount) {
        requirePositive(amount);
        int updated = investors.withdraw(investorId.value(), amount.amount());
        if (updated == 0) {
            investors.findById(investorId.value())
                    .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + investorId.value()));
            throw new InsufficientCashException(amount.amount());
        }
        cashTransactions.save(new CashTransaction(
                investorId.value(), CashTransaction.Type.MARGIN_HOLD, amount.amount(),
                currentBalance(investorId)));
    }

    @Override
    @Transactional
    public void refundMargin(InvestorId investorId, Money amount) {
        if (amount.isZero()) {
            return;
        }
        investors.deposit(investorId.value(), amount.amount());
        cashTransactions.save(new CashTransaction(
                investorId.value(), CashTransaction.Type.MARGIN_REFUND, amount.amount(),
                currentBalance(investorId)));
    }

    @Override
    @Transactional
    public void settlementCredit(InvestorId investorId, Money amount) {
        if (amount.isZero()) {
            return;
        }
        investors.deposit(investorId.value(), amount.amount());
        cashTransactions.save(new CashTransaction(
                investorId.value(), CashTransaction.Type.SETTLEMENT_CREDIT, amount.amount(),
                currentBalance(investorId)));
    }

    @Transactional(readOnly = true)
    public List<CashTransaction> transactionsOf(InvestorId investorId) {
        return cashTransactions.findByInvestorIdOrderByIdDesc(investorId.value());
    }

    private long currentBalance(InvestorId investorId) {
        return investors.findCashBalance(investorId.value())
                .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + investorId.value()));
    }

    private void requirePositive(Money amount) {
        if (amount.isZero()) {
            throw new IllegalArgumentException("금액은 1원 이상이어야 한다");
        }
    }
}
