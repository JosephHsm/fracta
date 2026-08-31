package com.fracta.subscription.infrastructure;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fracta.issuance.api.IssuanceAllotmentPort;
import com.fracta.subscription.application.AllotmentStrategy;
import com.fracta.subscription.domain.LockTimeoutException;

/**
 * 방식 B — Redis 분산락 (Redisson).
 *
 * <p>락은 <b>트랜잭션 커밋/롤백 후에</b> 해제한다. reserve 직후 풀어버리면 다음 스레드가
 * 아직 커밋되지 않은 감소를 보지 못해 이중 배정이 난다. tryLock 실패는 예외로 승격한다
 * — 조용히 삼키면 중복 배정이다.
 */
@Component
public class RedisLockAllotmentStrategy implements AllotmentStrategy {

    private final RedissonClient redisson;
    private final IssuanceAllotmentPort issuances;
    private final long waitSeconds;
    private final long leaseSeconds;

    public RedisLockAllotmentStrategy(
            RedissonClient redisson,
            IssuanceAllotmentPort issuances,
            @org.springframework.beans.factory.annotation.Value("${subscription.redis-lock.wait-seconds:3}")
            long waitSeconds,
            @org.springframework.beans.factory.annotation.Value("${subscription.redis-lock.lease-seconds:10}")
            long leaseSeconds) {
        this.redisson = redisson;
        this.issuances = issuances;
        this.waitSeconds = waitSeconds;
        this.leaseSeconds = leaseSeconds;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public void reserve(long issuanceId, long units) {
        underLock(issuanceId, () -> issuances.reserveGuardedExternally(issuanceId, units));
    }

    @Override
    public void release(long issuanceId, long units) {
        underLock(issuanceId, () -> issuances.release(issuanceId, units));
    }

    private void underLock(long issuanceId, Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("분산락 예약은 트랜잭션 안에서만 가능하다");
        }
        String lockKey = "subscription:" + issuanceId;
        RLock lock = redisson.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockTimeoutException(lockKey);
        }
        if (!acquired) {
            throw new LockTimeoutException(lockKey);
        }

        boolean unlockRegistered = false;
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });
            unlockRegistered = true;
            action.run();
        } finally {
            if (!unlockRegistered && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
