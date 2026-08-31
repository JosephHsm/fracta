package com.fracta.ledger.infrastructure;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fracta.common.config.AdvisoryLockIds;
import com.fracta.common.money.InsufficientUnitsException;
import com.fracta.common.money.Units;
import com.fracta.ledger.api.Balance;
import com.fracta.ledger.api.ChainVerifyResult;
import com.fracta.ledger.api.InvalidUnitsRangeException;
import com.fracta.ledger.api.InvariantResult;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.LedgerTxId;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.ledger.api.TxType;

/**
 * 해시체인 원장 구현 (FSD §8.2). 쓰기는 전역 advisory lock으로 직렬화한다.
 *
 * <p>seq는 락을 쥔 상태에서 (마지막 seq + 1)로 직접 채번한다 — 시퀀스 gap 없이
 * 체인 순서와 seq가 항상 일치하도록. 트랜잭션 경계는 application 레이어
 * ({@code LedgerService})가 잡는다.
 */
@Component
public class HashChainLedgerAdapter implements LedgerPort {

    private static final int VERIFY_FETCH_SIZE = 1_000;

    private final JdbcTemplate jdbc;
    private final LedgerBalanceRepository balances;

    public HashChainLedgerAdapter(JdbcTemplate jdbc, LedgerBalanceRepository balances) {
        this.jdbc = jdbc;
        this.balances = balances;
    }

    // ── 쓰기 연산 ────────────────────────────────────────────────

    @Override
    public LedgerTxId issue(String tokenSymbol, OwnerId to, Units units, TxRef ref) {
        validateWrite(tokenSymbol, units, ref);
        Objects.requireNonNull(to, "to는 필수다");

        long seq = append(TxType.ISSUE, tokenSymbol, null, to.value(), units.value(), ref);
        LedgerBalanceEntity toBal = balanceEntity(to, tokenSymbol);
        toBal.setUnits(Units.of(toBal.units()).plus(units).value());
        balances.save(toBal);
        return new LedgerTxId(seq);
    }

    @Override
    public LedgerTxId transfer(String tokenSymbol, OwnerId from, OwnerId to, Units units, TxRef ref) {
        validateWrite(tokenSymbol, units, ref);
        Objects.requireNonNull(from, "from은 필수다");
        Objects.requireNonNull(to, "to는 필수다");
        if (from.equals(to)) {
            throw new IllegalArgumentException("from과 to가 같은 transfer는 허용하지 않는다: " + from);
        }

        long seq = append(TxType.TRANSFER, tokenSymbol, from.value(), to.value(), units.value(), ref);

        // 가용 수량 검증은 advisory lock을 쥔 뒤(append 이후)에 수행 — 레이스 프리
        LedgerBalanceEntity fromBal = balanceEntity(from, tokenSymbol);
        long available = fromBal.units() - fromBal.lockedUnits();
        if (available < units.value()) {
            throw new InsufficientUnitsException(available, units.value());
        }
        LedgerBalanceEntity toBal = balanceEntity(to, tokenSymbol);
        fromBal.setUnits(Units.of(fromBal.units()).minus(units).value());
        toBal.setUnits(Units.of(toBal.units()).plus(units).value());

        // 행 락 획득 순서 고정: 항상 owner_id 오름차순 flush (DvP 데드락 규율, CLAUDE.md #9)
        LedgerBalanceEntity first = from.value() < to.value() ? fromBal : toBal;
        LedgerBalanceEntity second = first == fromBal ? toBal : fromBal;
        balances.saveAndFlush(first);
        balances.saveAndFlush(second);
        return new LedgerTxId(seq);
    }

    @Override
    public LedgerTxId lock(String tokenSymbol, OwnerId owner, Units units, TxRef ref) {
        validateWrite(tokenSymbol, units, ref);
        Objects.requireNonNull(owner, "owner는 필수다");

        long seq = append(TxType.LOCK, tokenSymbol, owner.value(), owner.value(), units.value(), ref);
        LedgerBalanceEntity bal = balanceEntity(owner, tokenSymbol);
        long available = bal.units() - bal.lockedUnits();
        if (available < units.value()) {
            throw new InsufficientUnitsException(available, units.value());
        }
        bal.setLockedUnits(Units.of(bal.lockedUnits()).plus(units).value());
        balances.save(bal);
        return new LedgerTxId(seq);
    }

    @Override
    public LedgerTxId unlock(String tokenSymbol, OwnerId owner, Units units, TxRef ref) {
        validateWrite(tokenSymbol, units, ref);
        Objects.requireNonNull(owner, "owner는 필수다");

        long seq = append(TxType.UNLOCK, tokenSymbol, owner.value(), owner.value(), units.value(), ref);
        LedgerBalanceEntity bal = balanceEntity(owner, tokenSymbol);
        // 잠금량보다 큰 해제는 거부 — Units.minus가 InsufficientUnitsException을 던진다
        bal.setLockedUnits(Units.of(bal.lockedUnits()).minus(units).value());
        balances.save(bal);
        return new LedgerTxId(seq);
    }

    // ── 조회 ────────────────────────────────────────────────────

    @Override
    public Balance balanceOf(String tokenSymbol, OwnerId owner) {
        return balances.findByOwnerIdAndTokenSymbol(owner.value(), tokenSymbol)
                .map(e -> new Balance(owner, tokenSymbol, Units.of(e.units()), Units.of(e.lockedUnits())))
                .orElseGet(() -> Balance.zero(owner, tokenSymbol));
    }

    @Override
    public Units totalIssued(String tokenSymbol) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(units), 0) FROM ledger_transaction WHERE tx_type = ? AND token_symbol = ?",
                Long.class, TxType.ISSUE.name(), tokenSymbol);
        return Units.of(sum == null ? 0 : sum);
    }

    // ── 검증 ────────────────────────────────────────────────────

    @Override
    public ChainVerifyResult verifyChain(long fromSeq, long toSeq) {
        if (fromSeq < 1 || toSeq < fromSeq) {
            throw new IllegalArgumentException("잘못된 검증 구간: [%d, %d]".formatted(fromSeq, toSeq));
        }

        String startPrevHash;
        if (fromSeq == 1) {
            startPrevHash = LedgerHasher.GENESIS_PREV_HASH;
        } else {
            List<String> prev = jdbc.queryForList(
                    "SELECT curr_hash FROM ledger_transaction WHERE seq = ?", String.class, fromSeq - 1);
            if (prev.isEmpty()) {
                return ChainVerifyResult.mismatch(fromSeq, toSeq, 0, fromSeq,
                        "직전 레코드(seq=%d)가 존재하지 않는다".formatted(fromSeq - 1));
            }
            startPrevHash = prev.getFirst();
        }

        var state = new Object() {
            String expectedPrevHash = startPrevHash;
            long expectedSeq = fromSeq;
            long checked = 0;
            Long mismatchSeq = null;
            String detail = null;
        };

        // 스트리밍 검증 — 전체 로드 금지 (10만 건 이상 대비)
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT seq, tx_type, token_symbol, from_owner_id, to_owner_id, units,
                           ref_type, ref_id, created_at, prev_hash, curr_hash
                    FROM ledger_transaction
                    WHERE seq BETWEEN ? AND ?
                    ORDER BY seq
                    """);
            ps.setFetchSize(VERIFY_FETCH_SIZE);
            ps.setLong(1, fromSeq);
            ps.setLong(2, toSeq);
            return ps;
        }, rs -> {
            if (state.mismatchSeq != null) {
                return;
            }
            long seq = rs.getLong("seq");
            state.checked++;

            if (seq != state.expectedSeq) {
                state.mismatchSeq = state.expectedSeq;
                state.detail = "seq 불연속: %d 기대, %d 발견".formatted(state.expectedSeq, seq);
                return;
            }

            String storedPrev = rs.getString("prev_hash");
            String storedCurr = rs.getString("curr_hash");
            if (!state.expectedPrevHash.equals(storedPrev)) {
                state.mismatchSeq = seq;
                state.detail = "prev_hash 불일치";
                return;
            }

            long fromOwner = rs.getLong("from_owner_id");
            Long fromOwnerId = rs.wasNull() ? null : fromOwner;
            long toOwner = rs.getLong("to_owner_id");
            Long toOwnerId = rs.wasNull() ? null : toOwner;
            Instant createdAt = rs.getObject("created_at", OffsetDateTime.class).toInstant();

            String recomputed = LedgerHasher.hash(seq,
                    TxType.valueOf(rs.getString("tx_type")),
                    rs.getString("token_symbol"),
                    fromOwnerId, toOwnerId,
                    rs.getLong("units"),
                    RefType.valueOf(rs.getString("ref_type")),
                    rs.getString("ref_id"),
                    createdAt, storedPrev);
            if (!recomputed.equals(storedCurr)) {
                state.mismatchSeq = seq;
                state.detail = "curr_hash 재계산 불일치";
                return;
            }

            state.expectedPrevHash = storedCurr;
            state.expectedSeq = seq + 1;
        });

        if (state.mismatchSeq != null) {
            return ChainVerifyResult.mismatch(fromSeq, toSeq, state.checked, state.mismatchSeq, state.detail);
        }
        long expectedCount = toSeq - fromSeq + 1;
        if (state.checked != expectedCount) {
            return ChainVerifyResult.mismatch(fromSeq, toSeq, state.checked, state.expectedSeq,
                    "레코드 누락: %d건 기대, %d건 검증".formatted(expectedCount, state.checked));
        }
        return ChainVerifyResult.ok(fromSeq, toSeq, state.checked);
    }

    @Override
    public InvariantResult verifyInvariant(String tokenSymbol) {
        List<String> violations = new ArrayList<>();

        long issued = totalIssued(tokenSymbol).value();
        Long balanceSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(units), 0) FROM ledger_balance WHERE token_symbol = ?",
                Long.class, tokenSymbol);
        long sum = balanceSum == null ? 0 : balanceSum;
        if (issued != sum) {
            violations.add("INV-1 위반: totalIssued=%d, 잔고 합계=%d".formatted(issued, sum));
        }

        Long lockViolations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_balance WHERE token_symbol = ? AND locked_units > units",
                Long.class, tokenSymbol);
        if (lockViolations != null && lockViolations > 0) {
            violations.add("INV-2 위반: locked_units > units 잔고 %d건".formatted(lockViolations));
        }

        Long negatives = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_balance WHERE token_symbol = ? AND (units < 0 OR locked_units < 0)",
                Long.class, tokenSymbol);
        if (negatives != null && negatives > 0) {
            violations.add("INV-3 위반: 음수 잔고 %d건".formatted(negatives));
        }

        return InvariantResult.of(tokenSymbol, violations);
    }

    // ── 내부 ────────────────────────────────────────────────────

    /**
     * 체인에 트랜잭션 1건을 직렬화 추가한다.
     * 락 획득 → prevHash 조회 순서를 절대 뒤집지 않는다 (뒤집으면 체인 분기).
     */
    private long append(TxType txType, String tokenSymbol, Long fromOwnerId, Long toOwnerId,
                        long units, TxRef ref) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "원장 쓰기는 트랜잭션 안에서만 가능하다 (pg_advisory_xact_lock 전제)");
        }

        jdbc.query("SELECT pg_advisory_xact_lock(?)",
                ps -> ps.setLong(1, AdvisoryLockIds.LEDGER_CHAIN),
                rs -> null);

        record Last(long seq, String hash) {
        }
        Last last = jdbc.query(
                "SELECT seq, curr_hash FROM ledger_transaction ORDER BY seq DESC LIMIT 1",
                rs -> rs.next() ? new Last(rs.getLong(1), rs.getString(2)) : null);

        long seq = last == null ? 1 : last.seq() + 1;
        String prevHash = last == null ? LedgerHasher.GENESIS_PREV_HASH : last.hash();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String currHash = LedgerHasher.hash(seq, txType, tokenSymbol, fromOwnerId, toOwnerId,
                units, ref.refType(), ref.refId(), createdAt, prevHash);

        jdbc.update("""
                        INSERT INTO ledger_transaction
                            (seq, tx_type, token_symbol, from_owner_id, to_owner_id, units,
                             ref_type, ref_id, created_at, prev_hash, curr_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                seq, txType.name(), tokenSymbol, fromOwnerId, toOwnerId, units,
                ref.refType().name(), ref.refId(), OffsetDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC),
                prevHash, currHash);
        return seq;
    }

    private LedgerBalanceEntity balanceEntity(OwnerId owner, String tokenSymbol) {
        return balances.findByOwnerIdAndTokenSymbol(owner.value(), tokenSymbol)
                .orElseGet(() -> new LedgerBalanceEntity(owner.value(), tokenSymbol));
    }

    private void validateWrite(String tokenSymbol, Units units, TxRef ref) {
        if (tokenSymbol == null || tokenSymbol.isBlank()) {
            throw new IllegalArgumentException("tokenSymbol은 필수다");
        }
        Objects.requireNonNull(units, "units는 필수다");
        Objects.requireNonNull(ref, "ref는 필수다");
        if (units.value() <= 0) {
            throw new InvalidUnitsRangeException(units.value());
        }
    }
}
