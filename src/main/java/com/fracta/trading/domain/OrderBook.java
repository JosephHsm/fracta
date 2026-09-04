package com.fracta.trading.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 단일 종목 오더북 (FSD §8.4) — 순수 자료구조. DB·트랜잭션을 모른다.
 *
 * <ul>
 *   <li>매수: 가격 내림차순, 동가면 시각 오름차순 (먼저 온 주문이 우선)</li>
 *   <li>매도: 가격 오름차순, 동가면 시각 오름차순</li>
 * </ul>
 *
 * <p>종목별 단일 스레드에서만 접근하므로 락이 없다. 여러 스레드가 같은 종목을 만지면 깨진다.
 */
public class OrderBook {

    /** 동가일 때 먼저 들어온 주문이 앞. 시각까지 같으면 주문ID 오름차순으로 결정론을 보장한다. */
    private static final Comparator<BookOrder> TIME_PRIORITY =
            Comparator.comparing(BookOrder::createdAt).thenComparingLong(BookOrder::orderId);

    private final String tokenSymbol;
    private final PriorityQueue<BookOrder> buys = new PriorityQueue<>(
            Comparator.comparingLong((BookOrder o) -> o.price()).reversed().thenComparing(TIME_PRIORITY));
    private final PriorityQueue<BookOrder> sells = new PriorityQueue<>(
            Comparator.comparingLong((BookOrder o) -> o.price()).thenComparing(TIME_PRIORITY));

    public OrderBook(String tokenSymbol) {
        this.tokenSymbol = tokenSymbol;
    }

    public String tokenSymbol() {
        return tokenSymbol;
    }

    /**
     * 결제 시도 결과. 실패했을 때 <b>어느 쪽 주문을 오더북에서 빼야 하는지</b>까지 알려준다.
     *
     * <p>오더북은 실패 원인을 알 수 없다 — 잔고가 없는 쪽이 누구인지는 결제자만 안다.
     * 예전에는 실패하면 무조건 매칭을 멈추고 걸려 있던 주문을 큐에 되살렸는데, 그러면
     * 결제 불가능한 주문이 최우선 호가에 영구히 박혀 이후 모든 반대 주문을 막았다.
     */
    public enum SettleOutcome {
        /** 결제 성공. */
        OK,
        /** 오더북에 걸려 있던 쪽이 원인 — 그 주문을 빼고 다음 호가로 계속 매칭한다. */
        REJECT_RESTING,
        /** 새로 들어온 쪽이 원인 — 매칭을 멈추고 이 주문은 오더북에 넣지 않는다. */
        REJECT_INCOMING
    }

    /** 매칭 결과. 결제 실패로 빠진 주문까지 돌려줘 호출자가 DB 상태를 맞출 수 있게 한다. */
    public record MatchResult(List<Match> matches, List<Long> evictedRestingOrderIds,
                              boolean incomingRejected) {

        static MatchResult of(List<Match> matches, List<Long> evicted, boolean incomingRejected) {
            return new MatchResult(List.copyOf(matches), List.copyOf(evicted), incomingRejected);
        }
    }

    /** 매칭 없이 체결만 시도할 때 쓰는 기본 결제자 — 항상 성공으로 본다 (단위 테스트용). */
    public List<Match> submit(BookOrder incoming) {
        return submit(incoming, match -> SettleOutcome.OK).matches();
    }

    /**
     * 신규 주문을 받아 교차 가능한 만큼 체결한다.
     * 잔량이 남으면 LIMIT은 큐에 넣고 MARKET은 버린다(취소).
     *
     * <p>{@code settle}이 {@link SettleOutcome#OK}가 아닌 값을 돌려주면 그 체결을 <b>되돌린다.</b>
     * 되돌리지 않으면 결제가 롤백된 뒤에도 오더북 잔량만 줄어들어 DB와 어긋난다.
     * 되돌린 뒤 처리는 원인에 따라 갈린다.
     *
     * <ul>
     *   <li>{@code REJECT_RESTING} — 그 주문을 큐에서 빼고 <b>다음 호가로 계속</b> 매칭한다.
     *       들어온 주문에는 잘못이 없으므로 여기서 멈추면 멀쩡한 주문이 손해를 본다</li>
     *   <li>{@code REJECT_INCOMING} — 걸려 있던 주문은 제자리에 두고 매칭을 멈춘다.
     *       들어온 주문은 오더북에 넣지 않는다 (호출자가 DB에서 거절 처리한다)</li>
     * </ul>
     *
     * <p>{@code REJECT_RESTING}은 매번 큐에서 한 건을 제거하므로 루프는 반드시 끝난다.
     *
     * @param settle 체결 1건을 결제한다
     * @return 성사된 체결과, 결제 실패로 오더북에서 빠진 주문들
     */
    public MatchResult submit(BookOrder incoming,
                              java.util.function.Function<Match, SettleOutcome> settle) {
        List<Match> matches = new ArrayList<>();
        List<Long> evicted = new ArrayList<>();
        PriorityQueue<BookOrder> opposite = incoming.side() == OrderSide.BUY ? sells : buys;

        while (!incoming.isFilled() && !opposite.isEmpty()) {
            BookOrder resting = opposite.peek();
            if (!crosses(incoming, resting)) {
                break;
            }

            // 체결가는 먼저 들어온 주문(=오더북에 놓여 있던 쪽)의 가격이다
            long executionPrice = resting.price();
            long executionUnits = Math.min(incoming.remaining(), resting.remaining());

            incoming.fill(executionUnits);
            resting.fill(executionUnits);
            boolean restingRemoved = false;
            if (resting.isFilled()) {
                opposite.poll();
                restingRemoved = true;
            }

            BookOrder buy = incoming.side() == OrderSide.BUY ? incoming : resting;
            BookOrder sell = incoming.side() == OrderSide.BUY ? resting : incoming;
            Match match = new Match(buy.orderId(), sell.orderId(), buy.investorId(),
                    sell.investorId(), executionPrice, executionUnits);

            SettleOutcome outcome = settle.apply(match);
            if (outcome == SettleOutcome.OK) {
                matches.add(match);
                continue;
            }

            // 결제 실패 — 양쪽 체결을 먼저 되돌린다
            incoming.revertFill(executionUnits);
            resting.revertFill(executionUnits);

            if (outcome == SettleOutcome.REJECT_RESTING) {
                // 원인 주문을 큐에서 뺀다. 잔량이 남아 아직 안 빠졌다면 지금 뺀다 — 되돌린 뒤에도
                // 정렬 키(가격·시각·ID)는 그대로라 resting 은 여전히 머리다.
                if (!restingRemoved) {
                    opposite.poll();
                }
                evicted.add(resting.orderId());
                continue;
            }

            // REJECT_INCOMING — 걸려 있던 주문은 원래 자리로 되돌리고 매칭을 멈춘다
            if (restingRemoved) {
                opposite.add(resting);
            }
            return MatchResult.of(matches, evicted, true);
        }

        if (!incoming.isFilled() && incoming.orderType() == OrderType.LIMIT) {
            add(incoming);
        }
        return MatchResult.of(matches, evicted, false);
    }

    /**
     * 시장가 주문이 지금 오더북을 쓸어담을 때 드는 예상 체결금액. 접수 시점 대금 홀드에 쓴다.
     *
     * <p>반대편 호가를 우선순위대로 최대 {@code maxLevels} 단계까지만 훑는다 — 무제한으로 훑으면
     * 접수 1건이 오더북 전체를 순회한다. 훑은 범위로 수량을 다 못 채우면 채운 만큼만
     * 돌려준다({@code coveredUnits}).
     *
     * <p><b>추정치다.</b> 접수와 실제 매칭 사이에 호가가 바뀔 수 있다. 홀드가 모자라면 결제가
     * {@code REJECT_INCOMING}으로 실패하고, 그때는 주문이 유령으로 남지 않고 정상 거절된다.
     */
    public SweepEstimate sweepCost(OrderSide takerSide, long units, int maxLevels) {
        if (units <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 한다: " + units);
        }
        if (maxLevels <= 0) {
            throw new IllegalArgumentException("호가 단계는 1 이상이어야 한다: " + maxLevels);
        }

        OrderSide oppositeSide = takerSide == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        long remaining = units;
        long amount = 0;
        for (PriceLevel level : depth(oppositeSide, maxLevels)) {
            if (remaining == 0) {
                break;
            }
            long take = Math.min(remaining, level.units());
            amount = Math.addExact(amount, Math.multiplyExact(level.price(), take));
            remaining -= take;
        }
        return new SweepEstimate(amount, units - remaining);
    }

    /** 호가 스윕 추정 결과. {@code coveredUnits}가 요청 수량보다 적으면 호가가 모자란 것이다. */
    public record SweepEstimate(long amount, long coveredUnits) {
    }

    /** 매칭 없이 큐에만 넣는다 — 재시작 복원 전용. */
    public void restore(BookOrder order) {
        if (order.orderType() != OrderType.LIMIT) {
            throw new IllegalArgumentException("시장가 주문은 오더북에 남지 않는다: " + order.orderId());
        }
        add(order);
    }

    private void add(BookOrder order) {
        (order.side() == OrderSide.BUY ? buys : sells).add(order);
    }

    private boolean crosses(BookOrder incoming, BookOrder resting) {
        if (incoming.orderType() == OrderType.MARKET) {
            return true;   // 시장가는 반대편 최우선 호가를 그대로 받는다
        }
        return incoming.side() == OrderSide.BUY
                ? incoming.price() >= resting.price()
                : incoming.price() <= resting.price();
    }

    /** 미체결 주문 제거 (취소·거래정지). 없으면 null. */
    public BookOrder remove(long orderId) {
        for (PriorityQueue<BookOrder> queue : List.of(buys, sells)) {
            for (BookOrder order : queue) {
                if (order.orderId() == orderId) {
                    queue.remove(order);
                    return order;
                }
            }
        }
        return null;
    }

    /** 남아 있는 미체결 주문 전부를 꺼내 비운다 (SUSPENDED 시 전량 취소). */
    public List<BookOrder> drainAll() {
        List<BookOrder> all = new ArrayList<>(buys);
        all.addAll(sells);
        buys.clear();
        sells.clear();
        return all;
    }

    public List<BookOrder> openOrders() {
        List<BookOrder> all = new ArrayList<>(buys);
        all.addAll(sells);
        return all;
    }

    /** 잠금 정합성 검증용 — 매도 미체결 잔량 합. */
    public long lockedUnitsOf(long investorId) {
        return sells.stream()
                .filter(o -> o.investorId() == investorId)
                .mapToLong(BookOrder::remaining)
                .sum();
    }

    /**
     * 호가창 N호가. 같은 가격은 수량을 합산하고, 호가가 부족하면 있는 만큼만 돌려준다.
     */
    public List<PriceLevel> depth(OrderSide side, int levels) {
        PriorityQueue<BookOrder> source = side == OrderSide.BUY ? buys : sells;
        Map<Long, Long> aggregated = new LinkedHashMap<>();
        source.stream()
                .sorted(side == OrderSide.BUY
                        ? Comparator.comparingLong((BookOrder o) -> o.price()).reversed()
                        : Comparator.comparingLong(BookOrder::price))
                .forEach(o -> aggregated.merge(o.price(), o.remaining(), Long::sum));

        return aggregated.entrySet().stream()
                .limit(levels)
                .map(e -> new PriceLevel(e.getKey(), e.getValue()))
                .toList();
    }

    public record PriceLevel(long price, long units) {
    }
}
