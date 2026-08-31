package com.fracta.trading.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fracta.trading.domain.OrderBook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;

/**
 * 종목별 단일 스레드 매칭 실행기 (FSD §8.4).
 *
 * <p>종목 심볼 해시로 스레드를 고정하므로 <b>같은 종목의 주문은 항상 같은 스레드에서 순차 처리</b>된다.
 * 덕분에 {@link OrderBook} 자체에는 락이 없다.
 *
 * <p>{@code hashCode()}는 음수일 수 있으므로 {@code Math.floorMod}를 쓴다 —
 * {@code %}는 음수를 돌려줘 배열 인덱스가 깨진다.
 *
 * <p>스레드 풀에서 예외를 삼키면 오더북이 조용히 멈춘다. 모든 실패를 로그와
 * {@code fracta.order.matching.error} 메트릭으로 드러낸다.
 */
@Component
public class OrderBookExecutor {

    private static final Logger log = LoggerFactory.getLogger(OrderBookExecutor.class);

    private final ExecutorService[] partitions;
    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final Counter matchingErrors;

    public OrderBookExecutor(@Value("${trading.partitions:4}") int partitionCount,
                             MeterRegistry meterRegistry) {
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("파티션 수는 1 이상이어야 한다: " + partitionCount);
        }
        this.partitions = new ExecutorService[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            int index = i;
            this.partitions[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "orderbook-" + index);
                t.setUncaughtExceptionHandler((thread, e) ->
                        log.error("매칭 스레드에서 처리되지 않은 예외", e));
                return t;
            });
        }
        this.matchingErrors = Counter.builder("fracta.order.matching.error")
                .description("매칭 처리 중 발생한 오류 수")
                .register(meterRegistry);
        log.info("오더북 실행기 시작 — 파티션 {}개", partitionCount);
    }

    public int partitionCount() {
        return partitions.length;
    }

    /** 음수 해시로 인덱스가 깨지지 않도록 floorMod 를 쓴다. */
    public int partitionOf(String tokenSymbol) {
        return Math.floorMod(tokenSymbol.hashCode(), partitions.length);
    }

    public OrderBook book(String tokenSymbol) {
        return books.computeIfAbsent(tokenSymbol, OrderBook::new);
    }

    public List<String> knownSymbols() {
        return List.copyOf(books.keySet());
    }

    /**
     * 해당 종목 전용 스레드에서 작업을 실행하고 결과를 기다린다.
     * 반환값이 없는 작업은 {@link #runOnPartition}을 쓴다 — 람다에서 오버로드가 모호해지지 않도록
     * 이름을 나눠 두었다.
     */
    public <T> T callOnPartition(String tokenSymbol, Function<OrderBook, T> work) {
        return submit(tokenSymbol, () -> work.apply(book(tokenSymbol)));
    }

    public void runOnPartition(String tokenSymbol, java.util.function.Consumer<OrderBook> work) {
        submit(tokenSymbol, () -> {
            work.accept(book(tokenSymbol));
            return null;
        });
    }

    private <T> T submit(String tokenSymbol, Supplier<T> work) {
        ExecutorService executor = partitions[partitionOf(tokenSymbol)];
        CompletableFuture<T> future = CompletableFuture.supplyAsync(work, executor);
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            matchingErrors.increment();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("매칭 처리 실패: " + tokenSymbol, cause);
        }
    }

    @PreDestroy
    void shutdown() {
        for (ExecutorService executor : partitions) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }
}
