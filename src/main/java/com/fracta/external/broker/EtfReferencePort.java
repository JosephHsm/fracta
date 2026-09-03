package com.fracta.external.broker;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * ETF 기준 지표 — NAV·괴리율·추적오차·LP 의무호가.
 *
 * <p><b>이 프로젝트에서 괴리율 엔진의 정답지 역할을 한다.</b> 조각증권에는 "맞는 괴리율"이
 * 무엇인지 알려주는 기준이 없다. ETF에는 있다 — 증권사가 {@code dprt}를 직접 계산해 준다.
 * 분할비율 1·체결가를 시장가로 두면 우리 계산이 이 값과 일치해야 한다.
 *
 * <p>또 하나 중요한 것은 <b>LP 의무호가 잔량</b>이다. ETF의 괴리가 좁게 유지되는 건
 * 유동성공급자가 양방향 호가를 대주기 때문이다. 조각투자 플랫폼에는 그 제도가 없다 —
 * 그래서 FRACTA는 괴리를 좁히는 대신 임계치를 넘으면 거래를 멈춘다(TR-08).
 * 화면에서 두 값을 나란히 보여주면 그 차이가 숫자로 드러난다.
 */
public interface EtfReferencePort {

    /**
     * @param marketPrice   시장가 (원)
     * @param nav           순자산가치. 원 단위이나 소수가 있어 BigDecimal이다
     * @param premiumRate   증권사가 계산한 괴리율 (%)
     * @param trackingError 추적오차율 (%)
     * @param lpAskUnits    LP 매도 의무호가 잔량 합
     * @param lpBidUnits    LP 매수 의무호가 잔량 합
     */
    record EtfReference(String ticker, long marketPrice, BigDecimal nav, BigDecimal premiumRate,
                        BigDecimal trackingError, long lpAskUnits, long lpBidUnits) {
    }

    /** ETF가 아니거나 조회가 불가능하면 비어 있다. 오류가 아니다. */
    Optional<EtfReference> reference(String ticker);
}
