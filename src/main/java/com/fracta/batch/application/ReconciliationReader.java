package com.fracta.batch.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.batch.infrastructure.BatchReconciliationQuery;

/**
 * 대사 검증의 트랜잭션 경계.
 *
 * <p>왜 필요한가 — 전역 보존식(INV-6)은 예치금 잔액, 청약 증거금, 매수 대금 홀드를
 * <b>각각 다른 테이블에서</b> 더한다. 그냥 부르면 세 쿼리가 각자 다른 스냅샷을 보므로,
 * 청약 신청 한 건이 쿼리 사이에 커밋되면 <b>잔액은 차감 전, 증거금은 차감 후</b>를 읽어
 * 정상인데도 위반으로 판정된다. 자금이 한 곳에서 다른 곳으로 옮겨가는 모든 연산이 같은
 * 오탐을 만든다.
 *
 * <p>그래서 {@code REPEATABLE_READ}로 한 스냅샷에 묶는다. 배치 스텝이 이미 트랜잭션을
 * 열고 있어 {@code REQUIRES_NEW}가 필요하다 — 기존 트랜잭션에 참여하면 격리수준 지정이
 * 조용히 무시된다.
 *
 * <p>인프라({@code BatchReconciliationQuery})에 {@code @Transactional}을 달지 않는 이유는
 * 트랜잭션 경계를 application 레이어에만 두는 규칙 때문이다.
 */
@Service
public class ReconciliationReader {

    private final BatchReconciliationQuery query;

    public ReconciliationReader(BatchReconciliationQuery query) {
        this.query = query;
    }

    /** 전역 불변식 — 세 합계를 한 스냅샷에서 읽는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public List<ReconciliationCheck> readGlobal() {
        return query.checkGlobal();
    }

    /**
     * 토큰 단위 불변식. 한 종목의 발행량·잔고·배정을 함께 보므로 역시 한 스냅샷에서 읽는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public List<ReconciliationCheck> readToken(String tokenSymbol) {
        return query.checkToken(tokenSymbol);
    }
}
