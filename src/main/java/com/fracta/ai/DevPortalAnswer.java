package com.fracta.ai;

import java.util.List;

/** 개발자 어시스턴트 응답. citedEndpoints는 실제 스펙에 있는 경로만 담긴다. */
public record DevPortalAnswer(
        String answer,
        List<String> citedEndpoints,
        boolean blocked,
        String blockedReason,
        boolean llmCalled,
        String modelId,
        String provider
) {

    public DevPortalAnswer {
        citedEndpoints = citedEndpoints == null ? List.of() : List.copyOf(citedEndpoints);
    }
}
