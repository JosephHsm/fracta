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

    public InstrumentController(InstrumentSearchService search, InstrumentMasterLoader loader) {
        this.search = search;
        this.loader = loader;
    }

    @Operation(operationId = "searchInstruments", summary = "기초자산 검색 (이름·코드)")
    @GetMapping("/api/v1/instruments")
    public ApiResponse<List<InstrumentSearchService.Hit>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "kind", required = false) AssetKind kind,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.of(search.search(query, kind, limit));
    }

    /**
     * 종목 상세 + 조각 참조가. 분할비율은 화면이 정한다 — 1주를 몇 조각으로 볼 것인가.
     *
     * <p>환산은 서버가 한다. 화면에서 나누면 반올림이 갈려 호가창 기준선과 어긋난다.
     */
    @Operation(operationId = "getInstrument", summary = "기초자산 상세 + 조각 참조가")
    @GetMapping("/api/v1/instruments/{code}")
    public ApiResponse<InstrumentSearchService.Detail> detail(
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
