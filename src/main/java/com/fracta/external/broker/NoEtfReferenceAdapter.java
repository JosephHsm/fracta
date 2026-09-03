package com.fracta.external.broker;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 기본 구현 — 항상 비어 있다.
 *
 * <p>NAV는 시뮬레이션할 수 없다. Mock이 그럴듯한 NAV를 지어내면 "증권사 값과 우리 계산을
 * 대조한다"는 검증이 <b>자기 자신과의 대조</b>가 되어 아무것도 증명하지 못한다.
 * 없으면 없다고 하는 편이 정직하다 — 화면도 그때는 조각 괴리율만 보여준다.
 */
@Component
public class NoEtfReferenceAdapter implements EtfReferencePort {

    @Override
    public Optional<EtfReference> reference(String ticker) {
        return Optional.empty();
    }
}
