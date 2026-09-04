package com.fracta.external.broker.presentation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.common.response.ApiResponse;
import com.fracta.external.broker.instrument.AssetKind;
import com.fracta.external.broker.instrument.InstrumentMasterLoader;
import com.fracta.external.broker.instrument.InstrumentSearchService;

import io.swagger.v3.oas.annotations.Operation;

/**
 * 기초자산 탐색 API.
 *
 * <p>목록 검색은 시세를 부르지 않는다 — 30건을 조회하면 증권사 쿼터(실효 1건/초)가 바로 마른다.
 * 시세는 종목 하나를 펼칠 때만 부른다.
 */
@RestController
public class InstrumentController {

    private final InstrumentSearchService search;
    private final InstrumentMasterLoader loader;
    private final com.fracta.external.broker.MarketSessionTracker sessions;

    public InstrumentController(InstrumentSearchService search, InstrumentMasterLoader loader,
                                com.fracta.external.broker.MarketSessionTracker sessions) {
        this.search = search;
        this.loader = loader;
        this.sessions = sessions;
    }

    @Operation(operationId = "searchInstruments", summary = "기초자산 검색 (이름·코드)")
    @GetMapping("/api/v1/instruments")
    public ApiResponse<List<InstrumentSearchService.InstrumentHit>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "kind", required = false) AssetKind kind,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.of(search.search(query, kind, limit));
    }

    /**
     * 거래소별 장 상태 (국내·미국).
     *
     * <p>시간표를 코드에 박지 않고 <b>시세 응답의 체결일자·누적거래량</b>으로 판정한다.
     * 화면이 "국내장 마감 · 15:34 기준 / 미국장 장중"처럼 사실대로 보여줄 수 있게 한다.
     */
    @Operation(operationId = "getMarketSessions", summary = "거래소 장 상태")
    @GetMapping("/api/v1/market-sessions")
    public com.fracta.common.response.ApiResponse<java.util.List<MarketSessionResponse>> marketSessions() {
        var list = java.util.Arrays.stream(com.fracta.external.broker.MarketVenue.values())
                .map(sessions::sessionOf)
                .map(s -> new MarketSessionResponse(s.venue().name(), s.state().name(),
                        s.describe(), s.lastQuotedAt(), s.tradeDate()))
                .toList();
        return com.fracta.common.response.ApiResponse.of(list);
    }

    /** 한 거래소의 장 상태. {@code label} 은 화면에 그대로 쓸 수 있는 문구다. */
    public record MarketSessionResponse(String venue, String state, String label,
                                        String lastQuotedAt, String tradeDate) {
    }

    /**
     * 종목 상세 + 조각 참조가. 분할비율은 화면이 정한다 — 1주를 몇 조각으로 볼 것인가.
     *
     * <p>환산은 서버가 한다. 화면에서 나누면 반올림이 갈려 호가창 기준선과 어긋난다.
     */
    @Operation(operationId = "getInstrument", summary = "기초자산 상세 + 조각 참조가")
    @GetMapping("/api/v1/instruments/{code}")
    public ApiResponse<InstrumentSearchService.InstrumentDetail> detail(
            @PathVariable("code") String code,
            @RequestParam(value = "splitRatio", defaultValue = "1000") long splitRatio) {
        return ApiResponse.of(search.detail(code, splitRatio).orElse(null));
    }

    /** 종목마스터 재적재 (ADMIN). 매일 갱신되는 파일이라 주기적으로 다시 받는다. */
    @Operation(operationId = "refreshInstrumentMaster", summary = "종목마스터 재적재")
    @PostMapping("/api/v1/admin/instruments/refresh")
    public ApiResponse<InstrumentMasterLoader.LoadResult> refresh() {
        return ApiResponse.of(loader.refresh());
    }
}
