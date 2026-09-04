package com.fracta.batch.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.common.invariant.ReconciliationCheck;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.subscription.api.InvariantCheckPort;

/**
 * 대사 검증의 트랜잭션 경계 — <b>검증하지 않고 모으기만 한다.</b>
 *
 * <p>불변식의 정의는 그것을 소유한 모듈에 있다. 원장이 INV-1·2·3을, 청약 검증 서비스가
 * INV-5·6과 현금 INV-3을 판정한다. 배치가 같은 검증을 자기 SQL로 갖고 있으면 정의가 두 벌이
 * 되고, 한쪽만 고치면 검증기가 서로 다른 답을 낸다 — INV-6에 매수 대금 홀드 항을 추가할 때
 * 실제로 그 일이 일어났다.
 *
 * <p>왜 트랜잭션 경계가 필요한가 — 전역 보존식(INV-6)은 예치금 잔액, 청약 증거금, 매수 대금
 * 홀드를 <b>각각 다른 테이블에서</b> 더한다. 그냥 부르면 세 쿼리가 각자 다른 스냅샷을 보므로,
 * 청약 신청 한 건이 쿼리 사이에 커밋되면 <b>잔액은 차감 전, 증거금은 차감 후</b>를 읽어
 * 정상인데도 위반으로 판정된다. 자금이 한 곳에서 다른 곳으로 옮겨가는 모든 연산이 같은
 * 오탐을 만든다.
 *
 * <p>그래서 {@code REPEATABLE_READ}로 한 스냅샷에 묶는다. 배치 스텝이 이미 트랜잭션을
 * 열고 있어 {@code REQUIRES_NEW}가 필요하다 — 기존 트랜잭션에 참여하면 격리수준 지정이
 * 조용히 무시된다.
 */
@Service
public class ReconciliationReader {

    private final LedgerPort ledger;
    private final InvariantCheckPort subscriptionInvariants;

    public ReconciliationReader(LedgerPort ledger, InvariantCheckPort subscriptionInvariants) {
        this.ledger = ledger;
        this.subscriptionInvariants = subscriptionInvariants;
    }

    /** 전역 불변식 — 현금 INV-3과 예치금 보존 INV-6을 한 스냅샷에서 읽는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public List<ReconciliationCheck> readGlobal() {
        return subscriptionInvariants.checkGlobal();
    }

    /**
     * 토큰 단위 불변식 — INV-1·2·3(원장)과 INV-5(배정)를 한 스냅샷에서 읽는다.
     * 한 종목의 발행량·잔고·배정을 함께 보므로 역시 스냅샷이 갈리면 안 된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public List<ReconciliationCheck> readToken(String tokenSymbol) {
        List<ReconciliationCheck> checks = new ArrayList<>(ledger.checkTokenInvariants(tokenSymbol));
        checks.add(subscriptionInvariants.checkInv5(tokenSymbol));
        return List.copyOf(checks);
    }
}
