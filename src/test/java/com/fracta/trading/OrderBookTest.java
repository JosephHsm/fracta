package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.trading.domain.BookOrder;
import com.fracta.trading.domain.Match;
import com.fracta.trading.domain.OrderBook;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;

/** 매칭 엔진 순수 단위 테스트 (FSD §8.4). DB 없이 돈다. */
class OrderBookTest {

    private static final Instant T0 = Instant.parse("2026-08-31T09:00:00Z");

    private static BookOrder limit(long id, long investorId, OrderSide side, long price,
                                   long units, long secondsAfterT0) {
        return new BookOrder(id, investorId, side, OrderType.LIMIT, price, units, 0,
                T0.plusSeconds(secondsAfterT0));
    }

    private static BookOrder market(long id, long investorId, OrderSide side, long units) {
        return new BookOrder(id, investorId, side, OrderType.MARKET, null, units, 0, T0.plusSeconds(100));
    }

    @Test
    @DisplayName("가격 우선순위 — 매수는 높은 가격, 매도는 낮은 가격이 먼저 체결된다")
    void pricePriority() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_100, 10, 0));
        book.submit(limit(2, 12, OrderSide.SELL, 1_000, 10, 1));   // 더 싼 매도
        book.submit(limit(3, 13, OrderSide.SELL, 1_050, 10, 2));

        List<Match> matches = book.submit(limit(4, 20, OrderSide.BUY, 1_200, 10, 3));

        assertThat(matches).hasSize(1);
        // 가장 싼 매도(1,000)와 먼저 체결
        assertThat(matches.getFirst().sellOrderId()).isEqualTo(2);
        assertThat(matches.getFirst().price()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("동가 시 시간 우선순위 — 먼저 들어온 주문이 먼저 체결된다")
    void timePriorityOnEqualPrice() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 5));    // 나중
        book.submit(limit(2, 12, OrderSide.SELL, 1_000, 10, 1));    // 먼저
        book.submit(limit(3, 13, OrderSide.SELL, 1_000, 10, 3));

        List<Match> matches = book.submit(limit(4, 20, OrderSide.BUY, 1_000, 30, 10));

        assertThat(matches).hasSize(3);
        assertThat(matches.stream().map(Match::sellOrderId).toList())
                .containsExactly(2L, 3L, 1L);   // 시각 오름차순
    }

    @Test
    @DisplayName("체결가는 먼저 들어온 주문(메이커)의 가격이다 — 신규 주문 가격이 아니다")
    void executionPriceIsRestingOrderPrice() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));

        // 매수자가 1,500까지 낼 의사가 있어도 체결가는 먼저 있던 매도호가 1,000이다
        List<Match> matches = book.submit(limit(2, 20, OrderSide.BUY, 1_500, 10, 1));

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().price()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("반대 방향에서도 체결가는 메이커 가격 — 매수호가가 먼저면 그 가격으로")
    void executionPriceWhenBuyIsResting() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.BUY, 2_000, 10, 0));

        List<Match> matches = book.submit(limit(2, 20, OrderSide.SELL, 1_500, 10, 1));

        assertThat(matches.getFirst().price()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("부분 체결 — 잔량이 정확히 남고 큐에 유지된다")
    void partialFill() {
        OrderBook book = new OrderBook("FR-TEST-001");
        BookOrder sell = limit(1, 11, OrderSide.SELL, 1_000, 100, 0);
        book.submit(sell);

        BookOrder buy = limit(2, 20, OrderSide.BUY, 1_000, 30, 1);
        List<Match> matches = book.submit(buy);

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().units()).isEqualTo(30);
        assertThat(buy.isFilled()).isTrue();
        assertThat(sell.remaining()).isEqualTo(70);
        // 잔량이 남은 매도는 큐에 그대로 있다
        assertThat(book.depth(OrderSide.SELL, 10))
                .containsExactly(new OrderBook.PriceLevel(1_000, 70));
    }

    @Test
    @DisplayName("시장가 주문 — 미체결 잔량은 큐에 남지 않고 취소된다")
    void marketOrderCancelsRemainder() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));

        BookOrder marketBuy = market(2, 20, OrderSide.BUY, 50);
        List<Match> matches = book.submit(marketBuy);

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().units()).isEqualTo(10);
        assertThat(marketBuy.remaining()).isEqualTo(40);
        // 잔량 40은 큐에 들어가지 않는다
        assertThat(book.depth(OrderSide.BUY, 10)).isEmpty();
    }

    @Test
    @DisplayName("교차하지 않으면 체결 없이 큐에 쌓인다")
    void noCrossNoMatch() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_200, 10, 0));

        List<Match> matches = book.submit(limit(2, 20, OrderSide.BUY, 1_000, 10, 1));

        assertThat(matches).isEmpty();
        assertThat(book.depth(OrderSide.BUY, 10)).hasSize(1);
        assertThat(book.depth(OrderSide.SELL, 10)).hasSize(1);
    }

    @Test
    @DisplayName("호가창 — 같은 가격은 수량 합산, 요청 호가 수만큼만, 빈 쪽은 빈 목록")
    void depthAggregatesAndLimits() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));
        book.submit(limit(2, 12, OrderSide.SELL, 1_000, 15, 1));   // 동일 가격 → 합산
        book.submit(limit(3, 13, OrderSide.SELL, 1_100, 20, 2));
        book.submit(limit(4, 14, OrderSide.SELL, 1_200, 5, 3));

        assertThat(book.depth(OrderSide.SELL, 10)).containsExactly(
                new OrderBook.PriceLevel(1_000, 25),
                new OrderBook.PriceLevel(1_100, 20),
                new OrderBook.PriceLevel(1_200, 5));

        // 상위 2호가만
        assertThat(book.depth(OrderSide.SELL, 2)).hasSize(2);
        // 매수 호가는 비어 있다
        assertThat(book.depth(OrderSide.BUY, 10)).isEmpty();
    }

    @Test
    @DisplayName("호가창 정렬 — 매수는 높은 가격 순, 매도는 낮은 가격 순")
    void depthOrdering() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.BUY, 900, 10, 0));
        book.submit(limit(2, 12, OrderSide.BUY, 1_000, 10, 1));
        book.submit(limit(3, 13, OrderSide.BUY, 950, 10, 2));

        assertThat(book.depth(OrderSide.BUY, 10).stream().map(OrderBook.PriceLevel::price).toList())
                .containsExactly(1_000L, 950L, 900L);
    }

    @Test
    @DisplayName("복원 — 큐에 넣기만 하고 체결하지 않는다. 시간 우선순위 보존")
    void restorePreservesTimePriority() {
        OrderBook book = new OrderBook("FR-TEST-001");
        // 복원은 created_at 오름차순으로 들어온다
        book.restore(limit(10, 11, OrderSide.SELL, 1_000, 10, 1));
        book.restore(limit(20, 12, OrderSide.SELL, 1_000, 10, 5));

        List<Match> matches = book.submit(limit(30, 20, OrderSide.BUY, 1_000, 20, 10));
        assertThat(matches.stream().map(Match::sellOrderId).toList()).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("취소 — 미체결 주문을 큐에서 제거한다")
    void removeCancelsResting() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));
        assertThat(book.remove(1)).isNotNull();
        assertThat(book.depth(OrderSide.SELL, 10)).isEmpty();
        assertThat(book.remove(999)).isNull();
    }

    @Test
    @DisplayName("전량 취소 — drainAll 이 미체결 주문을 모두 꺼내고 큐를 비운다")
    void drainAll() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));
        book.submit(limit(2, 12, OrderSide.BUY, 900, 10, 1));

        assertThat(book.drainAll()).hasSize(2);
        assertThat(book.openOrders()).isEmpty();
    }

    @Test
    @DisplayName("연쇄 체결 — 하나의 큰 주문이 여러 반대 주문을 소진한다")
    void sweepsMultipleLevels() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));
        book.submit(limit(2, 12, OrderSide.SELL, 1_100, 10, 1));
        book.submit(limit(3, 13, OrderSide.SELL, 1_200, 10, 2));

        List<Match> matches = book.submit(limit(4, 20, OrderSide.BUY, 1_150, 30, 3));

        // 1,150 이하인 1,000·1,100 만 체결된다
        assertThat(matches).hasSize(2);
        assertThat(matches.stream().mapToLong(Match::units).sum()).isEqualTo(20);
        assertThat(book.depth(OrderSide.SELL, 10))
                .containsExactly(new OrderBook.PriceLevel(1_200, 10));
        // 남은 매수 잔량 10은 큐에 들어간다
        assertThat(book.depth(OrderSide.BUY, 10))
                .containsExactly(new OrderBook.PriceLevel(1_150, 10));
    }

    // -- 결제 실패 경로 (SettleOutcome) -----------------------

    /** 지정한 주문이 낀 체결만 실패시키는 결제자. 실패 사유는 호출자가 정한다. */
    private static Function<Match, OrderBook.SettleOutcome> failingOn(
            Set<Long> faultyOrderIds, OrderBook.SettleOutcome outcome, List<Match> attempted) {
        return match -> {
            attempted.add(match);
            boolean faulty = faultyOrderIds.contains(match.buyOrderId())
                    || faultyOrderIds.contains(match.sellOrderId());
            return faulty ? outcome : OrderBook.SettleOutcome.OK;
        };
    }

    @Test
    @DisplayName("REJECT_RESTING — 결제 못 하는 걸린 주문은 호가창에서 빠지고 다음 호가로 계속 체결된다")
    void rejectRestingEvictsAndContinues() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.BUY, 1_000, 10, 0));   // 최우선 매수 — 잔고 없음
        book.submit(limit(2, 12, OrderSide.BUY, 900, 10, 1));     // 그 다음 매수 — 정상

        List<Match> attempted = new ArrayList<>();
        OrderBook.MatchResult result = book.submit(limit(3, 20, OrderSide.SELL, 900, 20, 2),
                failingOn(Set.of(1L), OrderBook.SettleOutcome.REJECT_RESTING, attempted));

        // 1번에서 한 번 실패했지만 거기서 멈추지 않고 2번과 체결됐다
        assertThat(attempted).hasSize(2);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().buyOrderId()).isEqualTo(2);
        assertThat(result.evictedRestingOrderIds()).containsExactly(1L);
        assertThat(result.incomingRejected()).isFalse();

        // 결제 불가 주문은 호가창에서 사라졌다 — 남아 있으면 이후 매도를 계속 막는다
        assertThat(book.depth(OrderSide.BUY, 10)).isEmpty();
        // 매도 잔량 10은 정상적으로 호가창에 올라간다
        assertThat(book.depth(OrderSide.SELL, 10))
                .containsExactly(new OrderBook.PriceLevel(900, 10));
    }

    @Test
    @DisplayName("REJECT_RESTING — 되돌린 뒤 걸린 주문의 잔량이 원래대로 복구된다")
    void rejectRestingRevertsFill() {
        OrderBook book = new OrderBook("FR-TEST-001");
        BookOrder resting = limit(1, 11, OrderSide.BUY, 1_000, 10, 0);
        book.submit(resting);

        List<Match> attempted = new ArrayList<>();
        book.submit(limit(2, 20, OrderSide.SELL, 1_000, 4, 1),
                failingOn(Set.of(1L), OrderBook.SettleOutcome.REJECT_RESTING, attempted));

        // 체결이 롤백됐으므로 인메모리 잔량도 원복돼야 한다 (DB와 어긋나면 안 된다)
        assertThat(resting.filledUnits()).isZero();
        assertThat(resting.remaining()).isEqualTo(10);
    }

    @Test
    @DisplayName("REJECT_INCOMING — 걸린 주문은 제자리에 남고 들어온 주문은 호가창에 올라가지 않는다")
    void rejectIncomingKeepsRestingAndDropsIncoming() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));

        List<Match> attempted = new ArrayList<>();
        OrderBook.MatchResult result = book.submit(limit(2, 20, OrderSide.BUY, 1_000, 30, 1),
                failingOn(Set.of(2L), OrderBook.SettleOutcome.REJECT_INCOMING, attempted));

        assertThat(result.matches()).isEmpty();
        assertThat(result.evictedRestingOrderIds()).isEmpty();
        assertThat(result.incomingRejected()).isTrue();

        // 걸려 있던 매도는 잔량 그대로 제자리
        assertThat(book.depth(OrderSide.SELL, 10))
                .containsExactly(new OrderBook.PriceLevel(1_000, 10));
        // 잔량이 남았어도 들어온 주문은 큐에 넣지 않는다 — 호출자가 DB에서 거절 처리한다
        assertThat(book.depth(OrderSide.BUY, 10)).isEmpty();
    }

    @Test
    @DisplayName("REJECT_INCOMING — 앞서 성사된 체결은 유지된다")
    void rejectIncomingKeepsEarlierMatches() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));
        book.submit(limit(2, 12, OrderSide.SELL, 1_100, 10, 1));

        List<Match> attempted = new ArrayList<>();
        OrderBook.MatchResult result = book.submit(limit(3, 20, OrderSide.BUY, 1_200, 20, 2),
                failingOn(Set.of(2L), OrderBook.SettleOutcome.REJECT_INCOMING, attempted));

        // 1번과는 체결됐고 2번에서 멈췄다
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().sellOrderId()).isEqualTo(1);
        assertThat(result.incomingRejected()).isTrue();
        assertThat(book.depth(OrderSide.SELL, 10))
                .containsExactly(new OrderBook.PriceLevel(1_100, 10));
    }

    @Test
    @DisplayName("결제가 계속 실패해도 매칭 루프는 반드시 끝난다 — 호가를 하나씩 소진한다")
    void rejectRestingTerminates() {
        OrderBook book = new OrderBook("FR-TEST-001");
        for (int i = 1; i <= 5; i++) {
            book.submit(limit(i, 10 + i, OrderSide.BUY, 1_000, 10, i));
        }

        List<Match> attempted = new ArrayList<>();
        OrderBook.MatchResult result = book.submit(limit(99, 20, OrderSide.SELL, 1_000, 50, 10),
                failingOn(Set.of(1L, 2L, 3L, 4L, 5L), OrderBook.SettleOutcome.REJECT_RESTING,
                        attempted));

        assertThat(result.matches()).isEmpty();
        assertThat(result.evictedRestingOrderIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(book.depth(OrderSide.BUY, 10)).isEmpty();
        // 매수가 전부 빠졌으므로 매도 잔량 50이 호가창에 남는다
        assertThat(book.depth(OrderSide.SELL, 10))
                .containsExactly(new OrderBook.PriceLevel(1_000, 50));
    }

    // -- 시장가 홀드 추정 (sweepCost) -------------------------

    @Test
    @DisplayName("sweepCost — 여러 호가에 걸쳐 우선순위대로 예상 체결금액을 계산한다")
    void sweepCostWalksLevelsInPriority() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));
        book.submit(limit(2, 12, OrderSide.SELL, 1_200, 10, 1));
        book.submit(limit(3, 13, OrderSide.SELL, 1_100, 10, 2));

        // 25주 시장가 매수 = 1,000×10 + 1,100×10 + 1,200×5 = 27,000
        OrderBook.SweepEstimate estimate = book.sweepCost(OrderSide.BUY, 25, 10);

        assertThat(estimate.amount()).isEqualTo(27_000);
        assertThat(estimate.coveredUnits()).isEqualTo(25);
    }

    @Test
    @DisplayName("sweepCost — 호가가 모자라면 채울 수 있는 만큼만 돌려준다")
    void sweepCostStopsWhenBookIsThin() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));

        OrderBook.SweepEstimate estimate = book.sweepCost(OrderSide.BUY, 100, 10);

        assertThat(estimate.amount()).isEqualTo(10_000);
        assertThat(estimate.coveredUnits()).isEqualTo(10);
    }

    @Test
    @DisplayName("sweepCost — 훑는 호가 단계를 넘어가면 거기서 멈춘다")
    void sweepCostRespectsLevelCap() {
        OrderBook book = new OrderBook("FR-TEST-001");
        book.submit(limit(1, 11, OrderSide.SELL, 1_000, 10, 0));
        book.submit(limit(2, 12, OrderSide.SELL, 1_100, 10, 1));
        book.submit(limit(3, 13, OrderSide.SELL, 1_200, 10, 2));

        // 1호가만 본다 — 10주 × 1,000
        OrderBook.SweepEstimate estimate = book.sweepCost(OrderSide.BUY, 30, 1);

        assertThat(estimate.amount()).isEqualTo(10_000);
        assertThat(estimate.coveredUnits()).isEqualTo(10);
    }

    @Test
    @DisplayName("sweepCost — 빈 오더북이면 0원 0주")
    void sweepCostOnEmptyBook() {
        OrderBook book = new OrderBook("FR-TEST-001");

        OrderBook.SweepEstimate estimate = book.sweepCost(OrderSide.BUY, 10, 10);

        assertThat(estimate.amount()).isZero();
        assertThat(estimate.coveredUnits()).isZero();
    }
}
