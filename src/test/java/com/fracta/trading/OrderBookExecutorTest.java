package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.trading.application.OrderBookExecutor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OrderBookExecutorTest {

    private OrderBookExecutor executor(int partitions) {
        return new OrderBookExecutor(partitions, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("파티션 인덱스는 항상 음수가 아니다 — hashCode 가 음수인 심볼도 안전하다")
    void partitionIndexNeverNegative() {
        var executor = executor(4);
        // hashCode()가 음수인 문자열을 포함해 많이 넣어본다 ('%' 였다면 음수 인덱스가 나온다)
        IntStream.range(0, 5_000).forEach(i -> {
            String symbol = "FR-SYM-" + i;
            int partition = executor.partitionOf(symbol);
            assertThat(partition).as(symbol).isBetween(0, 3);
        });

        // 실제로 hashCode 가 음수인 사례를 하나 확인한다
        String negative = IntStream.range(0, 100_000).mapToObj(i -> "S" + i)
                .filter(s -> s.hashCode() < 0).findFirst().orElseThrow();
        assertThat(executor.partitionOf(negative)).isBetween(0, 3);
    }

    @Test
    @DisplayName("같은 종목은 항상 같은 파티션으로 간다 — 단일 스레드 순차 처리 보장")
    void sameSymbolSamePartition() {
        var executor = executor(8);
        int first = executor.partitionOf("FR-ABCD-001");
        for (int i = 0; i < 100; i++) {
            assertThat(executor.partitionOf("FR-ABCD-001")).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("종목별 오더북은 재사용된다")
    void bookIsCachedPerSymbol() {
        var executor = executor(2);
        var book = executor.book("FR-ABCD-001");
        assertThat(executor.book("FR-ABCD-001")).isSameAs(book);
        assertThat(executor.book("FR-ABCD-002")).isNotSameAs(book);
    }

    @Test
    @DisplayName("파티션 스레드의 예외는 삼키지 않고 호출자에게 전달된다 — 오더북이 조용히 멈추면 안 된다")
    void exceptionsPropagate() {
        var executor = executor(2);
        assertThatThrownBy(() -> executor.callOnPartition("FR-ABCD-001", book -> {
            throw new IllegalStateException("의도적 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("의도적 실패");

        // 예외 후에도 파티션은 살아 있다
        String symbol = executor.callOnPartition("FR-ABCD-001",
                com.fracta.trading.domain.OrderBook::tokenSymbol);
        assertThat(symbol).isEqualTo("FR-ABCD-001");
    }

    @Test
    @DisplayName("파티션 수는 1 이상이어야 한다")
    void rejectsInvalidPartitionCount() {
        assertThatThrownBy(() -> executor(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
