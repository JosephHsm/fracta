package com.fracta.ai.presentation;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.ai.DevPortalAnswer;
import com.fracta.ai.LlmPort;
import com.fracta.ai.ProspectusAnswer;
import com.fracta.common.response.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;

/**
 * AI 질의 API (Phase 8).
 *
 * <p>프론트가 AI 서비스를 직접 부르지 않고 Core를 거치게 한다. 그래야 인증·감사·
 * 서킷브레이커·{@code fracta.ai.guardrail.blocked} 메트릭이 한 곳을 지난다.
 *
 * <p>차단 사유({@code blockedReason})는 응답에 싣지 않는다 — 어떤 패턴에 걸렸는지
 * 알려주면 우회를 돕는 꼴이다. 사유는 {@code ai_conversation_log} 와 서버 로그에만 남는다.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    public record AskRequest(
            @NotBlank @Size(max = 2000) String question) {
    }

    public record ProspectusAskResponse(
            String answer,
            java.util.List<Integer> citedPages,
            boolean blocked,
            boolean llmCalled) {
    }

    public record DevPortalAskResponse(
            String answer,
            java.util.List<String> citedEndpoints,
            boolean blocked,
            boolean llmCalled) {
    }

    private final LlmPort llmPort;

    public AiController(LlmPort llmPort) {
        this.llmPort = llmPort;
    }

    @PostMapping("/issuances/{id}/ask")
    public ApiResponse<ProspectusAskResponse> askProspectus(
            @PathVariable("id") long issuanceId,
            @Valid @RequestBody AskRequest request) {

        ProspectusAnswer answer =
                llmPort.askProspectus(issuanceId, request.question(), MDC.get("requestId"));

        return ApiResponse.of(new ProspectusAskResponse(
                answer.answer(), answer.citedPages(), answer.blocked(), answer.llmCalled()));
    }

    @PostMapping("/devportal/ask")
    public ApiResponse<DevPortalAskResponse> askDevPortal(@Valid @RequestBody AskRequest request) {
        DevPortalAnswer answer = llmPort.askDevPortal(request.question(), MDC.get("requestId"));

        return ApiResponse.of(new DevPortalAskResponse(
                answer.answer(), answer.citedEndpoints(), answer.blocked(), answer.llmCalled()));
    }
}
