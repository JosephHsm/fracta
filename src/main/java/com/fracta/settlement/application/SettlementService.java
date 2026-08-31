package com.fracta.settlement.application;

import java.math.BigDecimal;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.CashPort;
import com.fracta.account.api.InvestorId;
import com.fracta.common.money.Money;
import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.settlement.domain.FeePolicy;

/**
 * DvP 결제 (ST-01) — <b>증권만 넘어가고 대금이 안 넘어가는 상태가 단 한 순간도 존재하면 안 된다.</b>
 *
 * <p>전 과정을 하나의 트랜잭션에서 처리한다. 어느 하나라도 실패하면 전부 롤백된다.
 *
 * <p><b>락 획득 순서를 owner_id 오름차순으로 고정한다</b> (FSD 부록 B-9).
 * A→B와 B→A 매매가 동시에 일어날 때 각자 자기 쪽부터 잠그면 서로를 기다려 데드락이 난다.
 * 두 트랜잭션이 항상 낮은 id부터 잠그면 순환 대기가 성립하지 않는다.
 *
 * <p>잠금분 이전 규칙: 같은 트랜잭션에서 {@code unlock} 후 {@code transfer}
 * (Phase 2에서 확정, {@code LedgerPort} javadoc 참조).
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    public record SettleCommand(String tokenSymbol, long buyOrderId, long sellOrderId,
                                long buyerId, long sellerId, long price, long units,
                                BigDecimal premiumRate) {
    }

    public record SettleResult(Money executionAmount, Money buyFee, Money sellFee) {
    }

    private final LedgerPort ledger;
    private final CashPort cash;

    public SettlementService(LedgerPort ledger, CashPort cash) {
        this.ledger = ledger;
        this.cash = cash;
    }

    /**
     * 체결 1건을 결제한다. 호출자가 연 트랜잭션에 참여한다({@code REQUIRED}) —
     * 체결 기록까지 한 트랜잭션에 묶어야 "증권만 넘어간" 중간 상태가 생기지 않고,
     * 주문 1건당 트랜잭션 수도 줄어든다. 체결 간 격리는 호출자가 체결마다
     * {@code REQUIRES_NEW} 로 감싸 보장한다.
     */
    @Transactional
    public SettleResult settle(SettleCommand command) {
        Money price = Money.of(command.price());
        Money amount = FeePolicy.executionAmount(price, command.units());
        Money buyFee = FeePolicy.fee(amount);
        Money sellFee = FeePolicy.fee(amount);
        Units units = Units.of(command.units());

        TxRef ref = TxRef.of(RefType.EXECUTION, command.buyOrderId() + ":" + command.sellOrderId());
        OwnerId seller = OwnerId.of(command.sellerId());
        OwnerId buyer = OwnerId.of(command.buyerId());

        // 1. 대금 이동. 락 획득 순서는 항상 owner_id 오름차순 — 바꾸면 데드락이 난다.
        moveCashInOwnerOrder(command, amount, buyFee, sellFee);

        // 2. 매도자 잠금 해제 후 이전 (같은 트랜잭션)
        ledger.unlock(command.tokenSymbol(), seller, units, ref);
        ledger.transfer(command.tokenSymbol(), seller, buyer, units, ref);

        return new SettleResult(amount, buyFee, sellFee);
    }

    /**
     * 대금 이동. 매수자에게서 (체결금액 + 매수수수료)를 빼고 매도자에게 (체결금액 − 매도수수료)를 준다.
     * 두 수수료 합계는 플랫폼 계정으로 적립한다 — 받는 곳이 없으면 그만큼 INV-6이 깨진다.
     *
     * <p>차감·지급 순서는 {@code owner_id} 오름차순으로 고정한다. 상호 매매가 동시에 일어날 때
     * 각자 자기 쪽부터 잠그면 서로를 기다려 데드락이 난다.
     */
    private void moveCashInOwnerOrder(SettleCommand command, Money amount, Money buyFee, Money sellFee) {
        InvestorId buyer = InvestorId.of(command.buyerId());
        InvestorId seller = InvestorId.of(command.sellerId());
        Money buyerDebit = amount.plus(buyFee);
        Money sellerCredit = amount.minus(sellFee);

        Runnable debitBuyer = () -> cash.tradeDebit(buyer, buyerDebit);
        Runnable creditSeller = () -> cash.tradeCredit(seller, sellerCredit);

        if (command.buyerId() < command.sellerId()) {
            debitBuyer.run();
            creditSeller.run();
        } else {
            creditSeller.run();
            debitBuyer.run();
        }
        cash.feeIncome(buyFee.plus(sellFee));
    }

    /** 결제 실패 시 매도 잠금을 되돌리기 위한 보상은 트랜잭션 롤백이 담당한다 (ST-02). */
    public Optional<String> describeFailure(RuntimeException e) {
        log.warn("DvP 결제 실패 — 트랜잭션 롤백으로 증권·대금·잠금이 원복된다: {}", e.getMessage());
        return Optional.ofNullable(e.getMessage());
    }
}
