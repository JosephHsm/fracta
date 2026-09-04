package com.fracta.trading.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.InvestorId;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.subscription.api.AllottedLotPort;
import com.fracta.trading.domain.PositionCalculator;
import com.fracta.trading.domain.PositionCalculator.Lot;
import com.fracta.trading.infrastructure.TradeExecutionRepository;

/**
 * 보유 포지션과 평가·손익 (FSD §11.2 "내 자산").
 *
 * <p><b>왜 서버가 계산하는가.</b> 화면에서 보유수량 × 현재가를 곱하면 금액 재계산 금지 원칙에
 * 걸린다. 그래서 화면이 오래 비어 있었는데, 원인은 API가 없어서가 아니라 <b>취득원가라는
 * 개념이 도메인에 없어서</b>였다. 원장은 수량만 갖고 있고, 조각을 얻는 경로가 청약과 매매로
 * 갈려 있어 "얼마에 샀는지"를 어느 테이블도 혼자 답하지 못한다.
 *
 * <p>평가 기준가는 {@link ReferencePriceService}가 정한다 — 주문의 가격제한폭도 같은 값을
 * 쓴다. 두 곳에서 따로 정의하면 화면에 보이는 기준가와 주문이 거부되는 기준이 어긋난다.
 *
 * <p>수량은 <b>원장 잔고를 그대로</b> 쓴다. 재생 결과와 다르면 재생 쪽이 아니라 원장이 옳다 —
 * 원장이 수량의 단일 진실 공급원이다. 재생은 원가를 구하는 용도로만 쓴다.
 */
@Service
public class PositionService {

    /**
     * 종목 하나의 보유 현황.
     *
     * @param units          원장 기준 보유 수량
     * @param lockedUnits    매도 주문으로 잠긴 수량
     * @param costBasis      취득원가 총액 (매수 수수료 포함)
     * @param referencePrice 평가 기준가. 산출할 수 없으면 null
     * @param priceSource    기준가 출처 — {@code LAST_EXECUTION} 또는 {@code ISSUE_PRICE}
     * @param marketValue    평가금액. 기준가가 없으면 null
     * @param profitLoss     평가손익(평가금액 − 취득원가). 기준가가 없으면 null
     */
    public record Position(String tokenSymbol, long units, long lockedUnits, long costBasis,
                           Long referencePrice, String priceSource,
                           Long marketValue, Long profitLoss) {
    }

    private final LedgerPort ledger;
    private final ListedTokenPort listedTokens;
    private final TradeExecutionRepository executions;
    private final AllottedLotPort allottedLots;
    private final ReferencePriceService referencePrices;

    public PositionService(LedgerPort ledger, ListedTokenPort listedTokens,
                           TradeExecutionRepository executions, AllottedLotPort allottedLots,
                           ReferencePriceService referencePrices) {
        this.ledger = ledger;
        this.listedTokens = listedTokens;
        this.executions = executions;
        this.allottedLots = allottedLots;
        this.referencePrices = referencePrices;
    }

    @Transactional(readOnly = true)
    public List<Position> positionsOf(InvestorId investorId) {
        Map<String, List<Lot>> lotsBySymbol = collectLots(investorId.value());

        List<Position> positions = new ArrayList<>();
        for (ListedTokenPort.ListedToken token : listedTokens.listAll()) {
            String symbol = token.tokenSymbol();
            var balance = ledger.balanceOf(symbol, OwnerId.of(investorId.value()));
            if (balance.units().isZero()) {
                continue;   // 보유하지 않은 종목은 내려보내지 않는다
            }
            positions.add(toPosition(token, balance.units().value(),
                    balance.lockedUnits().value(),
                    lotsBySymbol.getOrDefault(symbol, List.of())));
        }
        return positions;
    }

    private Position toPosition(ListedTokenPort.ListedToken token, long units, long lockedUnits,
                                List<Lot> lots) {
        long costBasis = PositionCalculator.replay(lots).costBasis();
        var reference = referencePrices.of(token);

        Long referencePrice = reference.map(ReferencePriceService.ReferencePrice::price).orElse(null);
        String priceSource = reference
                .map(r -> r.source().name())
                .orElse(null);

        Long marketValue = referencePrice == null ? null : Math.multiplyExact(referencePrice, units);
        Long profitLoss = marketValue == null ? null : marketValue - costBasis;
        return new Position(token.tokenSymbol(), units, lockedUnits, costBasis,
                referencePrice, priceSource, marketValue, profitLoss);
    }

    /** 청약 배정과 매매 체결을 한 바구니에 담는다. 정렬은 재생 쪽이 한다. */
    private Map<String, List<Lot>> collectLots(long investorId) {
        Map<String, List<Lot>> bySymbol = new LinkedHashMap<>();

        for (AllottedLotPort.AllottedLot lot : allottedLots.lotsOf(investorId)) {
            bySymbol.computeIfAbsent(lot.tokenSymbol(), key -> new ArrayList<>())
                    .add(Lot.acquire(lot.allottedAt(), lot.units(), lot.unitPrice(), 0));
        }

        for (Object[] row : executions.findLotsOfInvestor(investorId)) {
            String symbol = (String) row[0];
            long price = asLong(row[1]);
            long units = asLong(row[2]);
            long fee = asLong(row[3]);
            boolean buy = "BUY".equals(String.valueOf(row[4]));
            Instant at = asInstant(row[5]);
            bySymbol.computeIfAbsent(symbol, key -> new ArrayList<>())
                    .add(buy ? Lot.acquire(at, units, price, fee) : Lot.dispose(at, units));
        }
        return bySymbol;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Instant asInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case OffsetDateTime odt -> odt.toInstant();
            case java.sql.Timestamp ts -> ts.toInstant();
            default -> Instant.EPOCH;
        };
    }
}
