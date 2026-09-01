package com.fracta.batch.infrastructure;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fracta.batch.application.ReconciliationCheck;

/** 여러 모듈의 원장을 조인하는 배치 전용 읽기 모델. 상태 변경은 하지 않는다. */
@Component
public class BatchReconciliationQuery {

    private final JdbcTemplate jdbc;

    public BatchReconciliationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReconciliationCheck> checkToken(String tokenSymbol) {
        List<ReconciliationCheck> results = new ArrayList<>();

        BigInteger issued = number("""
                SELECT COALESCE(SUM(units), 0)
                FROM ledger_transaction
                WHERE tx_type = 'ISSUE' AND token_symbol = ?
                """, tokenSymbol);
        BigInteger balances = number("""
                SELECT COALESCE(SUM(units), 0)
                FROM ledger_balance
                WHERE token_symbol = ?
                """, tokenSymbol);
        results.add(ReconciliationCheck.amounts("INV-1", tokenSymbol, issued, balances,
                "totalIssued=%s, balanceSum=%s".formatted(issued, balances)));

        long lockViolations = count("""
                SELECT COUNT(*) FROM ledger_balance
                WHERE token_symbol = ? AND locked_units > units
                """, tokenSymbol);
        results.add(ReconciliationCheck.count("INV-2", tokenSymbol, lockViolations,
                "locked_units > units 잔고 %d건".formatted(lockViolations)));

        long negativeBalances = count("""
                SELECT COUNT(*) FROM ledger_balance
                WHERE token_symbol = ? AND (units < 0 OR locked_units < 0)
                """, tokenSymbol);
        results.add(ReconciliationCheck.count("INV-3", tokenSymbol, negativeBalances,
                "음수 토큰 잔고 %d건".formatted(negativeBalances)));

        results.add(checkInv5(tokenSymbol));
        return List.copyOf(results);
    }

    public List<ReconciliationCheck> checkGlobal() {
        long negativeCash = count("SELECT COUNT(*) FROM investor WHERE cash_balance < 0");
        ReconciliationCheck inv3 = ReconciliationCheck.count(
                "INV-3", ReconciliationCheck.GLOBAL_SCOPE, negativeCash,
                "음수 현금 잔고 %d건".formatted(negativeCash));

        BigInteger externalNet = number("""
                SELECT COALESCE(SUM(CASE
                    WHEN tx_type = 'DEPOSIT' THEN amount
                    WHEN tx_type = 'WITHDRAW' THEN -amount
                    ELSE 0 END), 0)
                FROM cash_transaction
                """);
        BigInteger cashSum = number("SELECT COALESCE(SUM(cash_balance), 0) FROM investor");
        BigInteger outstandingMargin = number("""
                SELECT COALESCE(SUM(deposit_amount), 0)
                FROM subscription_order WHERE status = 'DEPOSITED'
                """);
        BigInteger accounted = cashSum.add(outstandingMargin);
        ReconciliationCheck inv6 = ReconciliationCheck.amounts(
                "INV-6", ReconciliationCheck.GLOBAL_SCOPE, externalNet, accounted,
                "externalNet=%s, cashBalanceSum=%s, outstandingMargin=%s"
                        .formatted(externalNet, cashSum, outstandingMargin));
        return List.of(inv3, inv6);
    }

    public long maxLedgerSeq() {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM ledger_transaction", Long.class);
        return value == null ? 0 : value;
    }

    private ReconciliationCheck checkInv5(String tokenSymbol) {
        List<Inv5Row> rows = jdbc.query("""
                SELECT i.total_units, i.status,
                       COALESCE(SUM(CASE WHEN so.status <> 'CANCELLED'
                                         THEN so.requested_units ELSE 0 END), 0) AS requested,
                       COALESCE(SUM(so.allotted_units), 0) AS allotted,
                       COUNT(so.id) AS order_count
                FROM issuance i
                LEFT JOIN subscription_order so ON so.issuance_id = i.id
                WHERE i.token_symbol = ?
                GROUP BY i.id, i.total_units, i.status
                """, (rs, rowNum) -> new Inv5Row(
                rs.getLong("total_units"), rs.getString("status"),
                decimal(rs.getBigDecimal("requested")), decimal(rs.getBigDecimal("allotted")),
                rs.getLong("order_count")), tokenSymbol);

        if (rows.isEmpty()) {
            return ReconciliationCheck.informational("INV-5", tokenSymbol,
                    "발행 정보가 없어 배정 검증 대상이 아님");
        }
        Inv5Row row = rows.getFirst();
        if (row.orderCount() == 0 || !("LISTED".equals(row.status()) || "SUSPENDED".equals(row.status()))) {
            return ReconciliationCheck.informational("INV-5", tokenSymbol,
                    "배정 완료 발행이 아니어서 검증 대상이 아님: status=" + row.status());
        }
        BigInteger expected = row.requested().min(BigInteger.valueOf(row.totalUnits()));
        return ReconciliationCheck.amounts("INV-5", tokenSymbol, expected, row.allotted(),
                "expectedAllotted=%s, sumAllotted=%s".formatted(expected, row.allotted()));
    }

    private BigInteger number(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return decimal(value);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static BigInteger decimal(BigDecimal value) {
        return value == null ? BigInteger.ZERO : value.toBigIntegerExact();
    }

    private record Inv5Row(long totalUnits, String status, BigInteger requested,
                           BigInteger allotted, long orderCount) {
    }
}
