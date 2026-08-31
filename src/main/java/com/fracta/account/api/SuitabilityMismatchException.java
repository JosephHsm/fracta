package com.fracta.account.api;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 적합성 원칙 위반 — 상품 위험등급이 투자자 성향등급을 초과 (확인 서명 없음). */
public class SuitabilityMismatchException extends DomainException {

    public SuitabilityMismatchException(RiskGrade productGrade, RiskGrade investorGrade) {
        super(ErrorCode.SUIT_PROFILE_MISMATCH,
                "상품 위험등급(%s)이 투자자 성향등급(%s)보다 높다. 부적합 확인 서명 시 진행 가능하다."
                        .formatted(productGrade.koreanName(), investorGrade.koreanName()),
                Map.of("productGrade", productGrade.level(), "investorGrade", investorGrade.level()));
    }
}
