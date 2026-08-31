package com.fracta.account.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.domain.CashTransaction;
import com.fracta.account.infrastructure.CashTransactionRepository;
import com.fracta.account.infrastructure.InvestorRepository;

/**
 * INV-6 예치금 보존 검증: Σ investor.cash_balance == 총입금 − 총출금.
 * (미결제 증거금 항은 Phase 4에서 추가된다)
 */
@Service
public class CashInvariantService {

    public record CashInvariantResult(boolean valid, long expectedNet, long actualBalanceSum) {
    }

    private final InvestorRepository investors;
    private final CashTransactionRepository cashTransactions;

    public CashInvariantService(InvestorRepository investors, CashTransactionRepository cashTransactions) {
        this.investors = investors;
        this.cashTransactions = cashTransactions;
    }

    @Transactional(readOnly = true)
    public CashInvariantResult verifyInv6() {
        long deposits = cashTransactions.sumByType(CashTransaction.Type.DEPOSIT);
        long withdrawals = cashTransactions.sumByType(CashTransaction.Type.WITHDRAW);
        long expectedNet = deposits - withdrawals;
        long actual = investors.sumCashBalance();
        return new CashInvariantResult(expectedNet == actual, expectedNet, actual);
    }
}
