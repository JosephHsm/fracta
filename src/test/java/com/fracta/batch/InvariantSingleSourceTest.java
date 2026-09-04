package com.fracta.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.InvestorId;
import com.fracta.batch.application.ReconciliationReader;
import com.fracta.common.invariant.ReconciliationCheck;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.subscription.application.SubscriptionInvariantService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;

/**
 * 불변식 정의는 한 곳에만 있다.
 *
 * <p>예전에는 애플리케이션(원장 어댑터·청약 검증 서비스)과 야간 배치가 같은 불변식을 각자
 * SQL로 구현해 <b>두 벌</b>이 존재했다. 한쪽만 고치면 검증기가 서로 다른 답을 냈고,
 * INV-6에 매수 대금 홀드 항을 추가할 때 실제로 그 일이 일어나 배치가 정상 상태를 위반으로
 * 판정했다. 이제 배치는 소유 모듈의 포트를 부르기만 한다.
 */
class InvariantSingleSourceTest extends IntegrationTestBase {

    @Autowired
    ReconciliationReader reader;

    @Autowired
    LedgerPort ledger;

    @Autowired
    SubscriptionInvariantService invariants;

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Autowired
    JdbcTemplate jdbc;

    private static ReconciliationCheck find(List<ReconciliationCheck> checks, String code) {
        return checks.stream()
                .filter(c -> code.equals(c.invariantCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(code + " 검증 결과가 없다"));
    }

    @Test
    @DisplayName("배치가 읽는 토큰 불변식은 원장 포트가 판정한 그 결과다")
    void tokenChecksComeFromLedgerPort() {
        var market = support.listedMarket(null, 100);

        List<ReconciliationCheck> batch = reader.readToken(market.tokenSymbol());
        List<ReconciliationCheck> port = ledger.checkTokenInvariants(market.tokenSymbol());

        // 배치 결과 = 원장 포트 결과 + INV-5. 원장 항목은 값까지 같아야 한다.
        assertThat(batch).hasSize(port.size() + 1);
        for (String code : List.of("INV-1", "INV-2", "INV-3")) {
            assertThat(find(batch, code))
                    .as("%s 는 원장 포트가 낸 결과 그대로여야 한다", code)
                    .isEqualTo(find(port, code));
        }
        assertThat(find(batch, "INV-5")).isNotNull();
    }

    @Test
    @DisplayName("훼손 상황에서도 verifyInvariant 와 배치 판정이 어긋나지 않는다")
    void bothPathsAgreeOnViolation() {
        var market = support.listedMarket(null, 100);
        long holder = support.investor(0);
        support.giveUnits(market, holder, 10);

        // 잔고를 직접 훼손한다 — INV-1(발행량 == 잔고 합) 위반
        jdbc.update("UPDATE ledger_balance SET units = units + 5 WHERE token_symbol = ? AND owner_id = ?",
                market.tokenSymbol(), holder);
        try {
            boolean portSaysInvalid = !ledger.verifyInvariant(market.tokenSymbol()).valid();
            boolean batchSaysInvalid = !find(reader.readToken(market.tokenSymbol()), "INV-1").valid();

            assertThat(portSaysInvalid).isTrue();
            assertThat(batchSaysInvalid)
                    .as("애플리케이션과 배치가 같은 훼손을 다르게 판정하면 안 된다")
                    .isEqualTo(portSaysInvalid);
        } finally {
            jdbc.update("UPDATE ledger_balance SET units = units - 5 WHERE token_symbol = ? AND owner_id = ?",
                    market.tokenSymbol(), holder);
        }
    }

    @Test
    @DisplayName("배치가 읽는 INV-6은 청약 검증 서비스가 낸 값과 같다 — 매수 홀드가 있어도")
    void globalCheckMatchesApplicationService() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(1_000_000);

        // 매수 홀드를 만들어 둔다. 예전에는 이 항이 배치 쪽에만 빠져 있어 오탐이 났다.
        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, support.newKey());

        var appResult = invariants.verifyInv6();
        var batchCheck = find(reader.readGlobal(), "INV-6");

        assertThat(appResult.valid())
                .as("애플리케이션 판정: %s", appResult)
                .isTrue();
        assertThat(batchCheck.valid())
                .as("배치 판정이 애플리케이션과 달라졌다: %s", batchCheck.detail())
                .isEqualTo(appResult.valid());
        assertThat(batchCheck.expected().longValueExact()).isEqualTo(appResult.externalNet());
        assertThat(batchCheck.actual().longValueExact()).isEqualTo(appResult.heldTotal());
    }

    @Test
    @DisplayName("현금 INV-3도 전역 검증에 함께 나온다")
    void globalCheckIncludesCashInv3() {
        var inv3 = find(reader.readGlobal(), "INV-3");

        assertThat(inv3.tokenSymbol()).isEqualTo(ReconciliationCheck.GLOBAL_SCOPE);
        assertThat(inv3.valid()).as("음수 현금 잔고가 있으면 안 된다: %s", inv3.detail()).isTrue();
    }
}
