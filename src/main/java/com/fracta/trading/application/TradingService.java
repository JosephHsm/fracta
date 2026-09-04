package com.fracta.trading.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.CashPort;
import com.fracta.account.api.InsufficientCashException;
import com.fracta.account.api.InvestorId;
import com.fracta.account.api.RiskGrade;
import com.fracta.account.api.RiskProfileRequiredException;
import com.fracta.account.api.SuitabilityMismatchException;
import com.fracta.account.api.SuitabilityPort;
import com.fracta.account.api.SuitabilityResult;
import com.fracta.account.api.SuitabilityScope;
import com.fracta.audit.api.Auditable;
import com.fracta.common.money.InsufficientUnitsException;
import com.fracta.common.money.Money;
import com.fracta.common.money.Units;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.settlement.application.SettlementService;
import com.fracta.settlement.domain.FeePolicy;
import com.fracta.trading.api.TradeEvents;
import com.fracta.trading.domain.BookOrder;
import com.fracta.trading.domain.Match;
import com.fracta.trading.domain.OrderBook;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderStatus;
import com.fracta.trading.domain.OrderType;
import com.fracta.trading.domain.PriceRules;
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
 * <p><b>양쪽 모두 접수 시점에 잠근다.</b> 매도는 원장 수량을 선잠금하고(FSD 부록 B-3),
 * 매수는 예상 체결금액 + 수수료를 예치금에서 홀드한다. 어느 한쪽만 잠그면 잠기지 않은 쪽이
 * 결제 시점에 펑크를 내고, 그 주문이 호가창에 남아 반대편 주문을 계속 실패시킨다.
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
    private final CashPort cash;
    private final SuitabilityPort suitability;
    private final ListedTokenPort listedTokens;
    private final SettlementService settlement;
    private final OrderBookExecutor executor;
    private final PremiumRateMonitor premiumMonitor;
    private final ReferencePriceService referencePrices;
    private final TradingHours tradingHours;
    private final int sweepLevels;
    private final int dailyLimitPercent;
    private final Counter matchedCounter;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final org.springframework.beans.factory.ObjectProvider<TradingService> selfProvider;

    public TradingService(TradeOrderRepository orders, TradeExecutionRepository executions,
                          LedgerPort ledger, CashPort cash, SuitabilityPort suitability,
                          ListedTokenPort listedTokens, SettlementService settlement,
                          OrderBookExecutor executor, PremiumRateMonitor premiumMonitor,
                          ReferencePriceService referencePrices, TradingHours tradingHours,
                          @Value("${trading.market-buy-sweep-levels:20}") int sweepLevels,
                          @Value("${trading.daily-price-limit-percent:30}") int dailyLimitPercent,
                          MeterRegistry meterRegistry,
                          org.springframework.context.ApplicationEventPublisher events,
                          org.springframework.beans.factory.ObjectProvider<TradingService> selfProvider) {
        this.orders = orders;
        this.executions = executions;
        this.ledger = ledger;
        this.cash = cash;
        this.suitability = suitability;
        this.listedTokens = listedTokens;
        this.settlement = settlement;
        this.executor = executor;
        this.premiumMonitor = premiumMonitor;
        this.referencePrices = referencePrices;
        this.tradingHours = tradingHours;
        this.sweepLevels = sweepLevels;
        this.dailyLimitPercent = dailyLimitPercent;
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
            return replay(existing.get(), tokenSymbol, investorId, side, orderType, price, units,
                    idempotencyKey);
        }

        // 매수 대금 홀드액은 트랜잭션 밖에서 미리 정한다 — 시장가는 호가창을 훑어야 하는데,
        // 그건 파티션 스레드를 잡는 일이라 DB 트랜잭션을 연 채로 기다리면 안 된다.
        long holdAmount = side == OrderSide.BUY
                ? buyHoldAmount(tokenSymbol, orderType, price, units) : 0L;

        AcceptedOrder acceptedOrder;
        try {
            acceptedOrder = selfProvider.getObject().acceptOrder(tokenSymbol, investorId, side,
                    orderType, price, units, idempotencyKey, holdAmount);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청과의 UNIQUE 충돌 — 이 트랜잭션(잠금·홀드 포함)은 전부 롤백됐다
            return orders.findByIdempotencyKey(idempotencyKey)
                    .map(o -> replay(o, tokenSymbol, investorId, side, orderType, price, units,
                            idempotencyKey))
                    .orElseThrow(() -> e);
        }
        BookOrder accepted = acceptedOrder.bookOrder();
        long orderId = accepted.orderId();

        // 매칭과 결제를 같은 파티션 스레드에서 처리한다. 결제가 실패하면 오더북이 그 체결을
        // 되돌리므로 인메모리 잔량과 DB가 어긋나지 않는다.
        List<Long> executionIds = new ArrayList<>();
        List<BigDecimal> premiums = new ArrayList<>();

        // 종목 정보는 접수 때 이미 읽었다. 체결마다 재조회하지 않는다.
        var token = acceptedOrder.token();
        OrderBook.MatchResult result = executor.callOnPartition(tokenSymbol,
                book -> book.submit(accepted, match -> {
                    BigDecimal premium = premiumMonitor.calculate(token, Money.of(match.price()))
                            .orElse(null);
                    try {
                        long executionId = selfProvider.getObject()
                                .settleAndRecord(tokenSymbol, match, premium);
                        matchedCounter.increment();
                        executionIds.add(executionId);
                        if (premium != null) {
                            premiums.add(premium);
                        }
                        return OrderBook.SettleOutcome.OK;
                    } catch (RuntimeException e) {
                        return blame(e, match, orderId);
                    }
                }));

        // 결제 실패로 오더북에서 빠진 주문들을 DB에서도 정리한다. 이걸 빼먹으면 DB에는 미체결로
        // 남고 오더북에는 없는 유령 주문이 되고, 원장 잠금·대금 홀드가 영원히 풀리지 않는다.
        if (!result.evictedRestingOrderIds().isEmpty()) {
            selfProvider.getObject().rejectOrders(result.evictedRestingOrderIds(), "결제 실패");
        }

        // 괴리율 판정은 파티션 스레드 밖에서 한다 — 거래 중단은 같은 파티션을 다시 잡아야 해서
        // 안에서 호출하면 스레드가 자기 자신을 기다려 교착한다.
        handlePremiumBreaches(tokenSymbol, premiums);

        if (result.incomingRejected()) {
            selfProvider.getObject().rejectOrders(List.of(orderId), "결제 실패");
            return selfProvider.getObject().describe(orderId, executionIds);
        }

        // 정리할 게 있을 때만 트랜잭션을 하나 더 연다 — 호가창에 그대로 남는 지정가 주문이
        // 대부분인데 매번 확정 트랜잭션을 열면 접수 경로가 그만큼 느려진다.
        long filled = accepted.filledUnits();
        boolean marketRemainder = orderType == OrderType.MARKET && !accepted.isFilled();
        boolean buyHoldLeftover = side == OrderSide.BUY && accepted.isFilled();
        if (marketRemainder || buyHoldLeftover) {
            return selfProvider.getObject().finalizeOrder(orderId, executionIds);
        }
        String status = filled == 0 ? OrderStatus.OPEN.name()
                : filled == units ? OrderStatus.FILLED.name() : OrderStatus.PARTIALLY_FILLED.name();
        return new PlaceResult(orderId, status, filled, executionIds, false);
    }

    /**
     * 최초 결과를 그대로 돌려준다 — <b>같은 사람이 같은 주문을 다시 보낸 경우에만</b>.
     *
     * <p>확인 없이 돌려주면 두 가지가 샌다. 남의 멱등성 키를 그대로 보내면 그 사람 주문의
     * ID·상태·체결수량이 넘어오고, 같은 키에 다른 종목·수량을 실어 보내면 넣지도 않은 주문이
     * 접수된 것처럼 보인다. 청약(SB-06)은 이미 소유자를 확인하고 있었는데 유통만 빠져 있었다.
     *
     * <p>주문 내용은 저장된 컬럼이 곧 지문이라 따로 보관하지 않는다.
     */
    private PlaceResult replay(TradeOrder order, String tokenSymbol, InvestorId investorId,
                               OrderSide side, OrderType orderType, Long price, long units,
                               String idempotencyKey) {
        boolean sameRequest = order.investorId() == investorId.value()
                && order.tokenSymbol().equals(tokenSymbol)
                && order.side() == side
                && order.orderType() == orderType
                && java.util.Objects.equals(order.price(), price)
                && order.units() == units;
        if (!sameRequest) {
            throw new TradingExceptions.IdempotencyConflictException(idempotencyKey);
        }
        return new PlaceResult(order.id(), order.status().name(), order.filledUnits(),
                List.of(), true);
    }

    /**
     * 접수 시점에 홀드할 매수 대금 = 예상 체결금액 + 매수 수수료.
     *
     * <p>지정가는 지정가 × 수량이 상한이다 — 체결가는 지정가보다 높을 수 없으므로 항상 충분하다.
     * 시장가는 상한이 없어서 반대편 호가를 실제로 훑어 추정한다({@code OrderBook.sweepCost}).
     * 호가가 모자라면 채울 수 있는 만큼만 홀드한다 — 시장가는 어차피 잔량을 버린다.
     *
     * <p>시장가 추정은 접수 시점 스냅샷이라 매칭 전에 호가가 밀리면 모자랄 수 있다. 그때는
     * 결제가 실패하고 주문이 거절된다 — 유령으로 남지 않는다.
     */
    private long buyHoldAmount(String tokenSymbol, OrderType orderType, Long price, long units) {
        if (orderType == OrderType.LIMIT) {
            if (price == null || price <= 0) {
                throw new IllegalArgumentException("지정가 주문에는 가격이 필요하다");
            }
            return withFee(Money.of(price).multiply(units));
        }
        OrderBook.SweepEstimate estimate = executor.callOnPartition(tokenSymbol,
                book -> book.sweepCost(OrderSide.BUY, units, sweepLevels));
        return withFee(Money.of(estimate.amount()));
    }

    private long withFee(Money amount) {
        return amount.plus(FeePolicy.fee(amount)).amount();
    }

    /**
     * 결제 실패 원인이 어느 쪽 주문인지 가린다.
     *
     * <p>예전에는 원인과 무관하게 항상 매수 주문을 거절했다. 그러면 매도자 사정으로 실패해도
     * 멀쩡한 매수자가 거절당하고, 잔고가 빈 매수 주문은 호가창에 그대로 남아 이후 매도를
     * 전부 막았다. 원인을 못 가리는 실패(락 타임아웃 등)는 오더북을 건드리지 않고
     * 들어온 주문만 거절한다 — 걸려 있던 주문에 죄를 씌우지 않는다.
     */
    private OrderBook.SettleOutcome blame(RuntimeException e, Match match, long incomingOrderId) {
        settlement.describeFailure(e);
        Long faulty = null;
        if (hasCause(e, InsufficientCashException.class)) {
            faulty = match.buyOrderId();      // 매수자가 대금을 못 낸다
        } else if (hasCause(e, InsufficientUnitsException.class)) {
            faulty = match.sellOrderId();     // 매도자가 수량을 못 넘긴다
        }
        if (faulty == null || faulty == incomingOrderId) {
            return OrderBook.SettleOutcome.REJECT_INCOMING;
        }
        return OrderBook.SettleOutcome.REJECT_RESTING;
    }

    private boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * 주문 접수 트랜잭션. 매도는 수량 잠금이, 매수는 대금 홀드가 선행하고,
     * 어느 쪽이든 실패하면 주문 레코드가 생기지 않는다.
     */
    @Transactional
    @Auditable(action = "ORDER_ACCEPT", targetType = "TRADE_ORDER",
            targetId = "#result.bookOrder().orderId()")
    public AcceptedOrder acceptOrder(String tokenSymbol, InvestorId investorId, OrderSide side,
                                     OrderType orderType, Long price, long units,
                                     String idempotencyKey, long holdAmount) {
        if (units <= 0) {
            throw new com.fracta.ledger.api.InvalidUnitsRangeException(units);
        }
        var token = listedTokens.findByTokenSymbol(tokenSymbol)
                .orElseThrow(() -> new IllegalArgumentException("종목이 없다: " + tokenSymbol));
        if (!token.tradable()) {
            throw new TradingExceptions.NotTradableException(tokenSymbol, token.status());
        }

        if (!tradingHours.isOpen()) {
            throw new TradingExceptions.MarketClosedException(tradingHours.describe());
        }
        requirePriceRules(token, orderType, price);

        // 매수는 적합성 판정을 거친다 (매도는 보유분 처분이라 대상이 아니다)
        if (side == OrderSide.BUY) {
            requireSuitable(investorId, tokenSymbol, token.riskGrade());
        }

        TradeOrder order = new TradeOrder(tokenSymbol, investorId.value(), side, orderType,
                price, units, idempotencyKey);

        if (side == OrderSide.SELL) {
            // 잠금 선행 — 실패하면 예외가 나가 주문이 저장되지 않는다
            ledger.lock(tokenSymbol, OwnerId.of(investorId.value()), Units.of(units),
                    TxRef.of(RefType.EXECUTION, "order-" + idempotencyKey));
        } else if (holdAmount > 0) {
            // 대금 홀드 선행 — 잔고가 모자라면 여기서 끊긴다. 홀드 없이 오더북에 올라간
            // 매수 주문은 결제 시점에 반드시 펑크가 난다
            cash.holdMargin(investorId, Money.of(holdAmount));
            order.holdFunds(holdAmount);
        }
        return new AcceptedOrder(orders.saveAndFlush(order).toBookOrder(), token);
    }

    /**
     * 지정가의 호가단위·가격제한폭 검증 (TR-01).
     *
     * <p>시장가는 가격을 직접 정하지 않으므로 이 검증의 대상이 아니다 — 대신 호가창을 훑어
     * 담기 때문에 상대 호가가 제한폭 안에 있다는 사실로 간접 보호된다.
     *
     * <p>기준가를 구할 수 없으면(체결도 없고 발행가도 0) 제한을 걸 근거가 없어 통과시킨다.
     */
    private void requirePriceRules(ListedTokenPort.ListedToken token, OrderType orderType,
                                   Long price) {
        if (orderType != OrderType.LIMIT || price == null) {
            return;
        }
        if (!PriceRules.isOnTick(price)) {
            throw new TradingExceptions.InvalidTickException(price, PriceRules.tickSizeOf(price));
        }
        referencePrices.of(token).ifPresent(reference -> {
            if (!PriceRules.isWithinDailyLimit(price, reference.price(), dailyLimitPercent)) {
                throw new TradingExceptions.PriceOutOfLimitException(price,
                        PriceRules.lowerLimit(reference.price(), dailyLimitPercent),
                        PriceRules.upperLimit(reference.price(), dailyLimitPercent),
                        reference.source().name());
            }
        });
    }

    /** 상품 위험등급은 발행 정보(ListedToken)에서 온다. Phase 4 청약과 같은 규칙이다. */
    private void requireSuitable(InvestorId investorId, String tokenSymbol, int productRiskGrade) {
        RiskGrade grade = RiskGrade.fromLevel(productRiskGrade);
        SuitabilityResult result = suitability.check(investorId, grade,
                SuitabilityScope.token(tokenSymbol));
        switch (result.decision()) {
            case BLOCKED_NO_PROFILE -> throw new RiskProfileRequiredException(investorId);
            case BLOCKED_MISMATCH -> throw new SuitabilityMismatchException(grade, result.investorGrade());
            default -> {
            }
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

    // ── 체결 결제 ────────────────────────────────────────────

    /**
     * DvP 결제 + 체결 기록 + 주문 체결량 반영을 <b>하나의 트랜잭션</b>으로 처리한다.
     * 체결마다 새 트랜잭션({@code REQUIRES_NEW})이라 한 건이 실패해도 앞선 체결은 유지된다.
     *
     * <p>결제 전에 매수 홀드에서 이번 체결분을 먼저 환급한다. 홀드는 예치금에서 이미 빠져
     * 있으므로, 환급 없이 결제하면 매수자가 같은 돈을 두 번 낸다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Auditable(action = "EXECUTION_SETTLE", targetType = "TRADE_EXECUTION", targetId = "#result")
    public long settleAndRecord(String tokenSymbol, Match match, BigDecimal premium) {
        releaseHoldForFill(match.buyOrderId(), match.price(), match.units());

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

    /**
     * 이번 체결에 실제로 드는 돈만큼만 홀드에서 환급한다 — 곧바로 같은 금액이 결제로 빠진다.
     *
     * <p>잔량에 비례해 나누지 않는다. 호가마다 가격이 다르면 수량 비례와 실제 원가가 어긋나서,
     * 시장가 매수가 중간 체결에서 필요 이상으로 묶이는 일이 생긴다.
     *
     * <p>지정가는 체결가가 지정가 이하라 홀드가 항상 충분하고, 남는 몫은 주문이 끝날 때
     * {@link #refundLeftoverHold} 가 돌려준다. 시장가는 접수 시점 추정이라 모자랄 수 있는데,
     * 그때는 남은 홀드를 다 털고 부족분이 잔액에서 빠진다 — 잔액도 모자라면 결제가 실패하고
     * 주문이 거절된다.
     */
    private void releaseHoldForFill(long buyOrderId, long price, long fillUnits) {
        TradeOrder buyOrder = orders.findById(buyOrderId)
                .orElseThrow(() -> new IllegalStateException("매수 주문이 없다: " + buyOrderId));
        if (buyOrder.heldAmount() == 0) {
            return;
        }
        long released = buyOrder.releaseHold(withFee(Money.of(price).multiply(fillUnits)));
        if (released > 0) {
            cash.refundMargin(InvestorId.of(buyOrder.investorId()), Money.of(released));
        }
    }

    /**
     * 주문이 끝났는데 홀드가 남았으면 돌려준다.
     *
     * <p>지정가 매수는 지정가 기준으로 홀드하므로, 더 싼 호가에 체결되면 그 차액이 홀드에 남는다.
     * 전량 체결이라 취소 경로를 타지 않으니 여기서 정리하지 않으면 차액이 영영 묶인다.
     */
    private void refundLeftoverHold(TradeOrder order) {
        long leftover = order.releaseAllHold();
        if (leftover > 0) {
            cash.refundMargin(InvestorId.of(order.investorId()), Money.of(leftover));
        }
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
     * 결제 실패로 오더북에서 빠진 주문을 거절 처리한다. 잠근 것은 전부 되돌린다 —
     * 매도는 미체결 잔량의 원장 잠금을, 매수는 남은 대금 홀드를 푼다.
     */
    @Transactional
    @Auditable(action = "ORDER_REJECT", targetType = "TRADE_ORDER", targetId = "#p0")
    public void rejectOrders(List<Long> orderIds, String reason) {
        for (long orderId : orderIds) {
            orders.findById(orderId).ifPresent(order -> {
                if (!order.status().isOpenOnBook()) {
                    return;   // 이미 종료된 주문 — 중복 정리는 무시한다
                }
                releaseAllHoldsOf(order);
                order.reject();
                log.warn("주문 거절: orderId={} side={} 사유={}", orderId, order.side(), reason);
            });
        }
    }

    /** 주문이 오더북을 떠날 때 걸어 둔 것을 전부 되돌린다. 취소·거절 공통. */
    private void releaseAllHoldsOf(TradeOrder order) {
        long remaining = order.remaining();
        if (order.side() == OrderSide.SELL && remaining > 0) {
            releaseLock(order, remaining);
        }
        refundLeftoverHold(order);
    }

    /**
     * 최종 상태 확정. 시장가 미체결 잔량은 취소하고, 오더북을 떠난 주문에 남은 홀드는 돌려준다.
     */
    @Transactional
    @Auditable(action = "ORDER_FINALIZE", targetType = "TRADE_ORDER", targetId = "#p0")
    public PlaceResult finalizeOrder(long orderId, List<Long> executionIds) {
        TradeOrder order = orders.findById(orderId).orElseThrow();
        if (order.status().isOpenOnBook()) {
            // 시장가는 잔량을 남기지 않는다 — 못 채운 만큼 취소하고 잠금·홀드를 푼다
            if (order.orderType() == OrderType.MARKET && order.remaining() > 0) {
                releaseAllHoldsOf(order);
                order.cancel();
            }
            // 지정가 잔량은 계속 호가창에 있다. 홀드도 그 잔량 몫이라 그대로 둔다.
        } else {
            // 전량 체결 — 지정가보다 싸게 체결돼 남은 홀드가 있으면 돌려준다
            refundLeftoverHold(order);
        }
        return new PlaceResult(orderId, order.status().name(), order.filledUnits(),
                executionIds, false);
    }

    /** DB에 확정된 상태를 그대로 읽어 돌려준다. */
    @Transactional(readOnly = true)
    public PlaceResult describe(long orderId, List<Long> executionIds) {
        TradeOrder order = orders.findById(orderId).orElseThrow();
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
        releaseAllHoldsOf(order);   // 미체결 잔량의 잠금 + 남은 대금 홀드
        order.cancel();
        events.publishEvent(new TradeEvents.OrderCancelled(orderId, order.tokenSymbol(),
                order.investorId(), remaining));
        return new PlaceResult(orderId, order.status().name(), order.filledUnits(), List.of(), false);
    }

    /**
     * 유효기간이 지난 주문을 취소한다 (TR-03 확장).
     *
     * <p>투자자 본인 취소와 같은 경로다 — 오더북에서 빼고 잠금·홀드를 되돌린다. 다만
     * 소유자 확인을 하지 않는다. 시스템이 정책으로 거두는 것이지 누가 요청한 게 아니다.
     * 이미 끝난 주문은 조용히 넘어간다(동시에 사용자가 취소했을 수 있다).
     */
    @Transactional
    @Auditable(action = "ORDER_EXPIRE", targetType = "TRADE_ORDER", targetId = "#p0")
    public void cancelExpired(long orderId, java.time.Duration ttl) {
        TradeOrder order = orders.findById(orderId).orElse(null);
        if (order == null || !order.status().isOpenOnBook()) {
            return;
        }
        long remaining = order.remaining();
        executor.runOnPartition(order.tokenSymbol(), book -> book.remove(orderId));
        releaseAllHoldsOf(order);
        order.cancel();
        events.publishEvent(new TradeEvents.OrderCancelled(orderId, order.tokenSymbol(),
                order.investorId(), remaining));
        log.info("유효기간({}) 만료로 주문 취소: orderId={} 잔량={}", ttl, orderId, remaining);
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

    /**
     * 이 종목의 주문 가격 규칙 (TR-09/10).
     *
     * <p>서버가 판정하는 값을 그대로 내려준다. 화면이나 시드 스크립트가 호가단위·제한폭을
     * 각자 계산하면 서버가 거부하는 기준과 어긋나 "왜 막혔는지 알 수 없는" 주문이 생긴다.
     */
    @Transactional(readOnly = true)
    public PriceRuleView priceRulesOf(String tokenSymbol) {
        var token = listedTokens.findByTokenSymbol(tokenSymbol)
                .orElseThrow(() -> new IllegalArgumentException("종목이 없다: " + tokenSymbol));
        var reference = referencePrices.of(token).orElse(null);
        if (reference == null) {
            // 체결도 없고 발행가도 없다 — 제한을 걸 근거가 없다
            return new PriceRuleView(tokenSymbol, null, null, null, null, null);
        }
        long price = reference.price();
        return new PriceRuleView(tokenSymbol, price, reference.source().name(),
                PriceRules.tickSizeOf(price),
                PriceRules.lowerLimit(price, dailyLimitPercent),
                PriceRules.upperLimit(price, dailyLimitPercent));
    }

    /**
     * 주문 가격 규칙. 기준가를 구할 수 없으면 모든 값이 null 이고 제한도 걸리지 않는다.
     *
     * @param tickSize 이 가격대의 호가단위. 지정가는 이 배수여야 한다
     */
    public record PriceRuleView(String tokenSymbol, Long referencePrice, String priceSource,
                                Long tickSize, Long lowerLimit, Long upperLimit) {
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

    /** 거래 중단 시 미체결 주문 전량 취소 + 잠금·홀드 해제 (TR-08 정책). */
    @Transactional
    @Auditable(action = "ORDERS_CANCEL_ALL", targetType = "TOKEN", targetId = "#p0")
    public int cancelAllOpenOrders(String tokenSymbol, String reason) {
        List<TradeOrder> open = orders.findOpenOrdersOfSymbol(tokenSymbol,
                List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED));
        executor.runOnPartition(tokenSymbol, OrderBook::drainAll);
        for (TradeOrder order : open) {
            releaseAllHoldsOf(order);
            order.cancel();
        }
        log.warn("거래 중단으로 미체결 주문 {}건 취소: symbol={} 사유={}", open.size(), tokenSymbol, reason);
        return open.size();
    }
}
