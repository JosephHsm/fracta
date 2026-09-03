package com.fracta.external.broker.instrument;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * 종목마스터 적재.
 *
 * <p>PLUG에는 이름으로 종목을 찾는 API가 없다. 모든 시세 API가 종목코드를 받는다.
 * 그래서 코드↔이름 목록을 로컬에 둔다 — <b>검색은 DB에서, 시세는 API에서</b>.
 * 실제 증권 앱이 쓰는 방식이기도 하다.
 *
 * <p>원본은 <b>인증 없이</b> 공개 배포된다. 토큰도 {@code x-client-*} 헤더도 필요 없다.
 * 매일 갱신되므로 저장소에 파일을 넣지 않고 받아서 적재한다.
 *
 * <p>실패해도 애플리케이션은 뜬다. 검색이 안 될 뿐 발행·청약·유통은 마스터와 무관하다.
 */
@Service
public class InstrumentMasterLoader {

    private static final Logger log = LoggerFactory.getLogger(InstrumentMasterLoader.class);

    /** 적재 결과. 호출자가 로그·응답으로 쓴다. */
    public record LoadResult(int total, long etf, long reit, long stock) {
    }

    private final InstrumentMasterRepository repository;
    private final RestClient restClient;
    private final String masterUrl;

    public InstrumentMasterLoader(InstrumentMasterRepository repository,
                                  @Value("${broker.instruments.master-url}") String masterUrl,
                                  @Value("${broker.instruments.timeout-millis:20000}") long timeoutMillis) {
        this.repository = repository;
        this.masterUrl = masterUrl;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(Duration.ofMillis(timeoutMillis)))
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(Duration timeout) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }

    @Transactional
    public LoadResult refresh() {
        byte[] raw = restClient.get()
                .uri(URI.create(masterUrl))
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .body(byte[].class);

        List<InstrumentMasterParser.Row> rows = InstrumentMasterParser.parse(raw);

        // 전량 교체. 상장폐지 종목이 남으면 검색에 유령이 뜬다.
        repository.deleteAllInBatch();
        repository.saveAll(rows.stream().map(InstrumentMaster::new).toList());
        repository.flush();

        var result = new LoadResult(rows.size(),
                repository.countByAssetKind(AssetKind.ETF),
                repository.countByAssetKind(AssetKind.REIT),
                repository.countByAssetKind(AssetKind.STOCK));
        log.info("종목마스터 적재 완료: 전체={} ETF={} 리츠={} 주식={}",
                result.total(), result.etf(), result.reit(), result.stock());
        return result;
    }
}
