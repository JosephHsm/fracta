package com.fracta.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.issuance.application.AssetService;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.UnderlyingAsset;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

class TokenSymbolConcurrencyTest extends IntegrationTestBase {

    private static final int CONCURRENT_CREATES = 100;

    @Autowired
    AssetService assetService;

    @Autowired
    IssuanceService issuanceService;

    @Autowired
    AuthTestSupport auth;

    @Test
    @DisplayName("토큰 심볼 형식 FR-{코드}-{연번3} + 동시 생성 100건에서 중복 0건")
    void concurrentSymbolAllocationHasNoDuplicates() throws Exception {
        var issuer = auth.signupAndLogin("symbol-issuer");
        UnderlyingAsset asset = assetService.create(issuer.id(), "심볼 자산",
                UnderlyingAsset.AssetType.ETF, "SYMB", null, 10, null);

        Set<String> symbols = ConcurrentHashMap.newKeySet();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_CREATES);

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            for (int i = 0; i < CONCURRENT_CREATES; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        var created = issuanceService.create(asset.id(), 100, 100,
                                Instant.now().plusSeconds(86_400), Instant.now().plusSeconds(172_800));
                        symbols.add(created.tokenSymbol());
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).isZero();
        assertThat(symbols).hasSize(CONCURRENT_CREATES);   // 중복 0건
        assertThat(symbols).allMatch(s -> s.matches("FR-SYMB-\\d{3}"));
        assertThat(symbols).contains("FR-SYMB-001", "FR-SYMB-100");
    }
}
