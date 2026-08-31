package com.fracta.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.support.IntegrationTestBase;

class LedgerConcurrencyTest extends IntegrationTestBase {

    private static final int THREADS = 50;

    @Autowired
    LedgerPort ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("50 스레드 동시 issue → 체인 분기 0건, verifyChain 통과, 수량 보존")
    void fiftyConcurrentIssuesDoNotForkTheChain() throws Exception {
        String symbol = "CONC-" + UUID.randomUUID().toString().substring(0, 8);
        TxRef ref = TxRef.of(RefType.ADMIN, "concurrency-test");

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger failures = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int i = 1; i <= THREADS; i++) {
                final long ownerId = 1000 + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        ledger.issue(symbol, OwnerId.of(ownerId), Units.of(1), ref);
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown(); // 정확한 동시 시작
            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).isZero();
        assertThat(ledger.totalIssued(symbol)).isEqualTo(Units.of(THREADS));

        // 체인 분기 0건: prev_hash 중복이 없어야 한다 (분기 시 같은 prev_hash를 공유)
        Long duplicatePrev = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT prev_hash FROM ledger_transaction GROUP BY prev_hash HAVING COUNT(*) > 1
                ) d
                """, Long.class);
        assertThat(duplicatePrev).isZero();

        long maxSeq = jdbc.queryForObject("SELECT MAX(seq) FROM ledger_transaction", Long.class);
        var result = ledger.verifyChain(1, maxSeq);
        assertThat(result.valid()).as(String.valueOf(result)).isTrue();
    }
}
