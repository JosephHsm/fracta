package com.fracta.trading.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.trading.infrastructure.TradeExecutionRepository;

/**
 * 종목의 기준가 — <b>이 프로젝트에서 기준가의 유일한 정의다.</b>
 *
 * <p>평가금액(내 자산)과 가격제한폭(주문 접수)이 같은 값을 써야 한다. 두 곳에서 따로 정의하면
 * 화면에 보이는 기준가와 주문이 거부되는 기준이 어긋나고, 사용자는 왜 막혔는지 알 수 없다.
 * 불변식을 두 벌로 두었다가 겪은 것과 같은 문제다.
 *
 * <pre>
 * 1순위: 마지막 체결가   — 시장이 마지막으로 합의한 가격
 * 2순위: 발행 단가       — 체결이 한 번도 없으면 청약가가 가장 정직한 기준이다
 * </pre>
 *
 * <p>둘 다 <b>실제로 거래된 가격</b>이다. 조각 참조가(원자산 ÷ 분할비율)는 파생값이라 쓰지
 * 않는다 — 원자산 시세가 흔들리면 우리 시장의 가격 제한이 따라 흔들려 버린다.
 */
@Service
public class ReferencePriceService {

    /** 기준가와 그 출처. 화면이 "무엇을 기준으로 매긴 값인지" 밝힐 수 있어야 한다. */
    public record ReferencePrice(long price, Source source) {

        public enum Source { LAST_EXECUTION, ISSUE_PRICE }
    }

    private final TradeExecutionRepository executions;
    private final ListedTokenPort listedTokens;

    public ReferencePriceService(TradeExecutionRepository executions,
                                 ListedTokenPort listedTokens) {
        this.executions = executions;
        this.listedTokens = listedTokens;
    }

    @Transactional(readOnly = true)
    public Optional<ReferencePrice> of(String tokenSymbol) {
        return listedTokens.findByTokenSymbol(tokenSymbol).flatMap(this::of);
    }

    /** 종목 정보를 이미 들고 있으면 이 쪽을 쓴다 — 재조회하지 않는다. */
    public Optional<ReferencePrice> of(ListedTokenPort.ListedToken token) {
        Optional<Long> lastPrice = executions.findLastPrice(token.tokenSymbol());
        if (lastPrice.isPresent() && lastPrice.get() > 0) {
            return Optional.of(
                    new ReferencePrice(lastPrice.get(), ReferencePrice.Source.LAST_EXECUTION));
        }
        if (token.unitPrice() > 0) {
            return Optional.of(
                    new ReferencePrice(token.unitPrice(), ReferencePrice.Source.ISSUE_PRICE));
        }
        return Optional.empty();
    }
}
