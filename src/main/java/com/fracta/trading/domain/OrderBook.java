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

    /** 매칭 없이 체결만 시도할 때 쓰는 기본 결제자 — 항상 성공으로 본다 (단위 테스트용). */
    public List<Match> submit(BookOrder incoming) {
        return submit(incoming, match -> true);
    }

    /**
     * 신규 주문을 받아 교차 가능한 만큼 체결한다.
     * 잔량이 남으면 LIMIT은 큐에 넣고 MARKET은 버린다(취소).
     *
     * <p>{@code settle}이 false를 돌려주면 그 체결을 <b>되돌리고</b> 매칭을 중단한다.
     * 되돌리지 않으면 결제가 롤백된 뒤에도 오더북 잔량만 줄어들어 DB와 어긋난다.
     *
     * @param settle 체결 1건을 결제한다. 성공하면 true
     * @return 실제로 성사된 체결 목록 (순서대로)
     */
    public List<Match> submit(BookOrder incoming, java.util.function.Predicate<Match> settle) {
        List<Match> matches = new ArrayList<>();
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

            if (!settle.test(match)) {
                // 결제 실패 — 양쪽 체결을 되돌리고 큐에서 빠진 주문은 되돌려 놓는다
                incoming.revertFill(executionUnits);
                resting.revertFill(executionUnits);
                if (restingRemoved) {
                    opposite.add(resting);
                }
                return matches;
            }
            matches.add(match);
        }

        if (!incoming.isFilled() && incoming.orderType() == OrderType.LIMIT) {
            add(incoming);
        }
        return matches;
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
