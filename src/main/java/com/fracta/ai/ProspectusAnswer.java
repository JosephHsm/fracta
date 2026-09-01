package com.fracta.ai;

import java.util.List;

/**
 * 투자설명서 질의 결과.
 *
 * @param citedPages    인용 페이지. Phase 10의 "인용 클릭 → 해당 페이지 이동"이 이걸 쓴다
 * @param blocked       가드레일 차단 여부
 * @param blockedReason 차단 사유 코드. 메트릭·감사용이며 <b>사용자에게 노출하지 않는다</b>
 * @param llmCalled     LLM을 실제로 호출했는가. 유사도 임계값 미달이면 false
 */
public record ProspectusAnswer(
        String answer,
        List<Integer> citedPages,
        boolean blocked,
        String blockedReason,
        boolean llmCalled,
        double topSimilarity,
        String modelId,
        String provider
) {

    public ProspectusAnswer {
        citedPages = citedPages == null ? List.of() : List.copyOf(citedPages);
    }
}
