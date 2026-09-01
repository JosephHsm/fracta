package com.fracta.trading.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.InvestorId;
import com.fracta.account.api.RiskGrade;
import com.fracta.account.api.RiskProfileRequiredException;
import com.fracta.account.api.SuitabilityMismatchException;
import com.fracta.account.api.SuitabilityPort;
import com.fracta.account.api.SuitabilityResult;
import com.fracta.audit.api.Auditable;
import com.fracta.common.money.Money;
import com.fracta.common.money.Units;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.settlement.application.SettlementService;
import com.fracta.trading.api.TradeEvents;
import com.fracta.trading.domain.BookOrder;
import com.fracta.trading.domain.Match;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderStatus;
import com.fracta.trading.domain.OrderType;
import com.fracta.trading.domain.TradeExecution;
import com.fracta.trading.domain.TradeOrder;
import com.fracta.trading.domain.TradingExceptions;
import com.fracta.trading.infrastructure.TradeExecutionRepository;
import com.fracta.trading.infrastructure.TradeOrderRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 주문 접수·매칭·취소 (TR-01 ~ TR-04).
 *
 * <p><b>매도 주문은 잠금이 반드시 선행한다</b> — 잠금 성공 후에만 주문 레코드를 만든다.
 * 순서를 뒤집으면 이중 매도가 난다 (FSD 부록 B-3).
 */
@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    public record PlaceResult(long orderId, String status, long filledUnits,
                              List<Long> executionIds, boolean replayed) {
    }

    /** 접수 결과. 종목 정보를 함께 돌려줘 체결마다 재조회하지 않게 한다. */
    public record AcceptedOrder(BookOrder bookOrder, ListedTokenPort.ListedToken token) {
    }

    private final TradeOrderRepository orders;
    private final TradeExecutionRepository executions;
    private final LedgerPort ledger;
    private final SuitabilityPort suitability;
    private final ListedTokenPort listedTokens;
    private final SettlementService settlement;
    private final OrderBookExecutor executor;
    private final PremiumRateMonitor premiumMonitor;
    private final Counter matchedCounter;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final org.springframework.beans.factory.ObjectProvider<TradingService> selfProvider;

    public TradingService(TradeOrderRepository orders, TradeExecutionRepository executions,
                          LedgerPort ledger, SuitabilityPort suitability,
                          ListedTokenPort listedTokens, SettlementService settlement,
                          OrderBookExecutor executor, PremiumRateMonitor premiumMonitor,
                          MeterRegistry meterRegistry,
                          org.springframework.context.ApplicationEventPublisher events,
                          org.springframework.beans.factory.ObjectProvider<TradingService> selfProvider) {
        this.orders = orders;
        this.executions = executions;
        this.ledger = ledger;
        this.suitability = suitability;
        this.listedTokens = listedTokens;
        this.settlement = settlement;
        this.executor = executor;
        this.premiumMonitor = premiumMonitor;
        this.matchedCounter = Counter.builder("fracta.order.matched")
                .description("체결된 주문 건수").register(meterRegistry);
        this.events = events;
        this.selfProvider = selfProvider;
    }

    // ── 주문 접수 ────────────────────────────────────────────

    /** 멱등: 동일 키 재요청은 최초 결과를 돌려준다. */
    public PlaceResult place(String tokenSymbol, InvestorId investorId, OrderSide side,
                             OrderType orderType, Long price, long units, String idempotencyKey) {
        Optional<TradeOrder> existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            TradeOrder order = existing.get();
            return new PlaceResult(order.id(), order.status().name(), order.filledUnits(),
                    List.of(), true);
        }

        AcceptedOrder acceptedOrder;
        try {
            acceptedOrder = selfProvider.getObject()
                    .acceptOrder(tokenSymbol, investorId, side, orderType, price, units, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            return orders.findByIdempotencyKey(idempotencyKey)
                    .map(o -> new PlaceResult(o.id(), o.status().name(), o.filledUnits(), List.of(), true))
                    .orElseThrow(() -> e);
        }
        BookOrder accepted = acceptedOrder.bookOrder();
        long orderId = accepted.orderId();

        // 매칭과 결제를 같은 파티션 스레드에서 처리한다. 결제가 실패하면 오더북이 그 체결을
        // 되돌리므로 인메모리 잔량과 DB가 어긋나지 않는다.
        List<Long> executionIds = new ArrayList<>();
        List<BigDecimal> premiums = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean settlementFailed =
                new java.util.concurrent.atomic.AtomicBoolean();

        // 종목 정보는 접수 때 이미 읽었다. 체결마다 재조회하지 않는다.
        var token = acceptedOrder.token();
        executor.runOnPartition(tokenSymbol, book -> book.submit(accepted, match -> {
            BigDecimal premium = premiumMonitor.calculate(token, Money.of(match.price()))
                    .orElse(null);
            Optional<Long> executionId = settleMatch(tokenSymbol, match, premium);
            if (executionId.isEmpty()) {
                settlementFailed.set(true);
                return false;
            }
            executionIds.add(executionId.get());
            if (premium != null) {
                premiums.add(premium);
            }
            return true;
        }));

        // 괴리율 판정은 파티션 스레드 밖에서 한다 — 거래 중단은 같은 파티션을 다시 잡아야 해서
        // 안에서 호출하면 스레드가 자기 자신을 기다려 교착한다.
        handlePremiumBreaches(tokenSymbol, premiums);

        // 결제 실패나 시장가 잔량 취소는 DB 상태를 다시 읽어 정확히 돌려준다
        if (settlementFailed.get() || (orderType == OrderType.MARKET && !accepted.isFilled())) {
            return selfProvider.getObject().finalizeOrder(orderId, executionIds);
        }
        long filled = accepted.filledUnits();
        String status = filled == 0 ? OrderStatus.OPEN.name()
                : filled == units ? OrderStatus.FILLED.name() : OrderStatus.PARTIALLY_FILLED.name();
        return new PlaceResult(orderId, status, filled, executionIds, false);
    }

    /**
     * 주문 접수 트랜잭션. 매도면 잠금이 선행하고, 잠금이 실패하면 주문 레코드가 생기지 않는다.
     */
    @Transactional
    @Auditable(action = "ORDER_ACCEPT", targetType = "TRADE_ORDER",
            targetId = "#result.bookOrder().orderId()")
    public AcceptedOrder acceptOrder(String tokenSymbol, InvestorId investorId, OrderSide side,
                                     OrderType orderType, Long price, long units,
                                     String idempotencyKey) {
        if (units <= 0) {
            throw new com.fracta.ledger.api.InvalidUnitsRangeException(units);
        }
        var token = listedTokens.findByTokenSymbol(tokenSymbol)
                .orElseThrow(() -> new IllegalArgumentException("종목이 없다: " + tokenSymbol));
        if (!token.tradable()) {
            throw new TradingExceptions.NotTradableException(tokenSymbol, token.status());
        }

        // 매수는 적합성 판정을 거친다 (매도는 보유분 처분이라 대상이 아니다)
        if (side == OrderSide.BUY) {
            requireSuitable(investorId, token.riskGrade());
        }

        TradeOrder order = new TradeOrder(tokenSymbol, investorId.value(), side, orderType,
                price, units, idempotencyKey);

        if (side == OrderSide.SELL) {
            // 잠금 선행 — 실패하면 예외가 나가 주문이 저장되지 않는다
            ledger.lock(tokenSymbol, OwnerId.of(investorId.value()), Units.of(units),
                    TxRef.of(RefType.EXECUTION, "order-pending"));
        }
        return new AcceptedOrder(orders.saveAndFlush(order).toBookOrder(), token);
    }

    /** 상품 위험등급은 발행 정보(ListedToken)에서 온다. Phase 4 청약과 같은 규칙이다. */
    private void requireSuitable(InvestorId investorId, int productRiskGrade) {
        RiskGrade grade = RiskGrade.fromLevel(productRiskGrade);
        SuitabilityResult result = suitability.check(investorId, grade);
        switch (result.decision()) {
            case BLOCKED_NO_PROFILE -> throw new RiskProfileRequiredException(investorId);
            case BLOCKED_MISMATCH -> throw new SuitabilityMismatchException(grade, result.investorGrade());
            default -> {
            }
        }
    }

    // ── 체결 결제 ────────────────────────────────────────────

    private Optional<Long> settleMatch(String tokenSymbol, Match match, BigDecimal premium) {
        try {
            // 결제와 체결 기록을 한 트랜잭션으로 묶는다 — 중간 상태가 생기지 않고 왕복도 줄어든다
            long executionId = selfProvider.getObject().settleAndRecord(tokenSymbol, match, premium);
            matchedCounter.increment();
            return Optional.of(executionId);
        } catch (RuntimeException e) {
            settlement.describeFailure(e);
            selfProvider.getObject().rejectAfterSettlementFailure(match);
            return Optional.empty();
        }
    }

    /**
     * 괴리율 경보·자동 거래 중단 (TR-08). 중단되면 미체결 주문을 전량 취소하고 잠금을 푼다 —
     * 그러지 않으면 잠금이 영구히 남는다. 반드시 파티션 스레드 밖에서 호출한다.
     */
    private void handlePremiumBreaches(String tokenSymbol, List<BigDecimal> premiums) {
        for (BigDecimal premium : premiums) {
            if (premiumMonitor.evaluate(tokenSymbol, premium)) {
                // ListedTokenService가 발행한 TokenSuspendedEvent를 공통 리스너가 받아
                // 커밋 후 미체결 주문을 정리한다. 배치 위반도 동일 경로를 사용한다.
                return;
            }
        }
    }

    /**
     * DvP 결제 + 체결 기록 + 주문 체결량 반영을 <b>하나의 트랜잭션</b>으로 처리한다.
     * 체결마다 새 트랜잭션({@code REQUIRES_NEW})이라 한 건이 실패해도 앞선 체결은 유지된다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Auditable(action = "EXECUTION_SETTLE", targetType = "TRADE_EXECUTION", targetId = "#result")
    public long settleAndRecord(String tokenSymbol, Match match, BigDecimal premium) {
        var result = settlement.settle(new SettlementService.SettleCommand(
                tokenSymbol, match.buyOrderId(), match.sellOrderId(),
                match.buyerId(), match.sellerId(), match.price(), match.units(), premium));

        if (orders.applyFill(match.buyOrderId(), match.units()) != 1
                || orders.applyFill(match.sellOrderId(), match.units()) != 1) {
            throw new IllegalStateException("체결량 반영 실패 — 주문 잔량과 어긋난다: " + match);
        }

        TradeExecution execution = executions.save(new TradeExecution(
                tokenSymbol, match.buyOrderId(), match.sellOrderId(), match.price(), match.units(),
                result.buyFee().amount(), result.sellFee().amount(), premium));

        publishFillEvents(tokenSymbol, match);
        return execution.id();
    }

    /** 체결 결과를 도메인 이벤트로 알린다. 오픈 API가 구독해 웹훅으로 내보낸다. */
    private void publishFillEvents(String tokenSymbol, Match match) {
        for (long orderId : new long[]{match.buyOrderId(), match.sellOrderId()}) {
            orders.findById(orderId).ifPresent(order -> {
                if (order.status() == OrderStatus.FILLED) {
                    events.publishEvent(new TradeEvents.OrderFilled(order.id(), tokenSymbol,
                            order.investorId(), order.filledUnits(), match.price()));
                } else if (order.status() == OrderStatus.PARTIALLY_FILLED) {
                    events.publishEvent(new TradeEvents.OrderPartiallyFilled(order.id(), tokenSymbol,
                            order.investorId(), order.filledUnits(), order.remaining(), match.price()));
                }
            });
        }
    }

    /**
     * 결제 실패 시 매수 주문을 거절한다. 매도 잠금은 유지된다 — 매도 주문은 살아있다 (ST-02).
     * 오더북 되돌리기는 {@code OrderBook.submit} 이 이미 처리했으므로 여기서는 DB 상태만 바꾼다.
     */
    @Transactional
    @Auditable(action = "ORDER_REJECT", targetType = "TRADE_ORDER", targetId = "#p0.buyOrderId()")
    public void rejectAfterSettlementFailure(Match match) {
        orders.findById(match.buyOrderId()).ifPresent(TradeOrder::reject);
        log.warn("결제 실패로 매수 주문 거절: buyOrderId={} sellOrderId={}",
                match.buyOrderId(), match.sellOrderId());
    }

    /**
     * 시장가 미체결 잔량 취소 / 결제 실패 후 최종 상태 확정.
     * 이미 REJECTED 로 표시된 주문은 그대로 둔다.
     */
    @Transactional
    @Auditable(action = "ORDER_FINALIZE", targetType = "TRADE_ORDER", targetId = "#p0")
    public PlaceResult finalizeOrder(long orderId, List<Long> executionIds) {
        TradeOrder order = orders.findById(orderId).orElseThrow();
        if (order.status().isOpenOnBook() && order.orderType() == OrderType.MARKET
                && order.remaining() > 0) {
            if (order.side() == OrderSide.SELL) {
                releaseLock(order, order.remaining());
            }
            order.cancel();
        }
        return new PlaceResult(orderId, order.status().name(), order.filledUnits(),
                executionIds, false);
    }

    // ── 주문 취소 (TR-03) ────────────────────────────────────

    @Transactional
    @Auditable(action = "ORDER_CANCEL", targetType = "TRADE_ORDER", targetId = "#p0")
    public PlaceResult cancel(long orderId, InvestorId investorId) {
        TradeOrder order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + orderId));
        if (order.investorId() != investorId.value()) {
            throw new TradingExceptions.ForbiddenOrderAccessException(orderId);
        }
        if (!order.status().isOpenOnBook()) {
            throw new TradingExceptions.OrderNotCancellableException(orderId, order.status());
        }

        long remaining = order.remaining();
        executor.runOnPartition(order.tokenSymbol(), book -> book.remove(orderId));
        if (order.side() == OrderSide.SELL && remaining > 0) {
            releaseLock(order, remaining);   // 미체결 잔량만 잠금 해제
        }
        order.cancel();
        events.publishEvent(new TradeEvents.OrderCancelled(orderId, order.tokenSymbol(),
                order.investorId(), remaining));
        return new PlaceResult(orderId, order.status().name(), order.filledUnits(), List.of(), false);
    }

    private void releaseLock(TradeOrder order, long units) {
        ledger.unlock(order.tokenSymbol(), OwnerId.of(order.investorId()), Units.of(units),
                TxRef.of(RefType.EXECUTION, "cancel-" + order.id()));
    }

    // ── 조회 ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TradeOrder> ordersOf(InvestorId investorId) {
        return orders.findByInvestorIdOrderByIdDesc(investorId.value());
    }

    /** 호가창 N호가 (TR-05). */
    public List<com.fracta.trading.domain.OrderBook.PriceLevel> depth(String tokenSymbol,
                                                                     OrderSide side, int levels) {
        return executor.callOnPartition(tokenSymbol, book -> book.depth(side, levels));
    }

    /** 체결 내역 (TR-06). */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<TradeExecution> executions(
            String tokenSymbol, org.springframework.data.domain.Pageable pageable) {
        return executions.findByTokenSymbolOrderByIdDesc(tokenSymbol, pageable);
    }

    /** 거래 중단 시 미체결 주문 전량 취소 + 잠금 해제 (TR-08 정책). */
    @Transactional
    @Auditable(action = "ORDERS_CANCEL_ALL", targetType = "TOKEN", targetId = "#p0")
    public int cancelAllOpenOrders(String tokenSymbol, String reason) {
        List<TradeOrder> open = orders.findOpenOrdersOfSymbol(tokenSymbol,
                List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED));
        executor.runOnPartition(tokenSymbol, book -> {
            for (BookOrder ignored : book.drainAll()) {
                // 큐를 비운다 — 개별 상태 변경은 아래 DB 루프에서 처리한다
            }
        });
        for (TradeOrder order : open) {
            long remaining = order.remaining();
            if (order.side() == OrderSide.SELL && remaining > 0) {
                releaseLock(order, remaining);
            }
            order.cancel();
        }
        log.warn("거래 중단으로 미체결 주문 {}건 취소: symbol={} 사유={}", open.size(), tokenSymbol, reason);
        return open.size();
    }
}
