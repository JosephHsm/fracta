package com.fracta.batch.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 배치 전용 읽기 — <b>불변식 검증은 여기 없다.</b>
 *
 * <p>예전에는 이 클래스가 INV-1·2·3·5·6을 자기 SQL로 다시 구현했다. 같은 불변식이
 * 애플리케이션(원장 어댑터·청약 검증 서비스)과 여기에 <b>두 벌</b>로 존재해, 정의가 바뀌면
 * 양쪽을 고쳐야 했다. 실제로 INV-6에 매수 대금 홀드 항을 추가할 때 한쪽만 고쳐서, 야간
 * 배치가 정상 상태를 위반으로 판정하고 전 종목을 정지시킬 뻔했다.
 *
 * <p>이제 검증은 그 불변식을 소유한 모듈이 한다 — {@code LedgerPort.checkTokenInvariants},
 * {@code InvariantCheckPort.checkInv5 / checkGlobal}. 여기 남은 것은 배치 진행에만
 * 필요한 조회다.
 */
@Component
public class BatchReconciliationQuery {

    private final JdbcTemplate jdbc;

    public BatchReconciliationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 체인 검증 구간의 끝. 원장이 비어 있으면 0. */
    public long maxLedgerSeq() {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM ledger_transaction", Long.class);
        return value == null ? 0 : value;
    }
}
