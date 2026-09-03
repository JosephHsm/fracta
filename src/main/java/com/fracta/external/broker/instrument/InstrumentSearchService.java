package com.fracta.external.broker.instrument;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.external.broker.MarketDataPort;
import com.fracta.external.broker.PriceConverter;
import com.fracta.common.money.Money;

/**
 * 기초자산 탐색 — 이름·코드로 상장 종목을 찾고, 조각으로 나눴을 때의 참조가를 함께 준다.
 *
 * <p><b>검색은 로컬, 시세는 API.</b> 마스터 4천여 종목을 매번 증권사에 물을 수 없다
 * (실효 한도 1건/초). 목록은 DB에서 만들고, 시세는 <b>선택한 종목 하나만</b> 조회한다.
 */
@Service
public class InstrumentSearchService {

    /**
     * 검색 결과 한 줄. 시세는 담지 않는다 — 목록에서 N건을 조회하면 쿼터가 바로 마른다.
     *
     * <p>{@code matchedBy}는 문자 검색인지 의미 검색인지 알려준다. 사용자가 친 글자가
     * 결과에 안 보이면("코덱스" → "KODEX 200") 왜 나왔는지 화면이 설명할 수 있어야 한다.
     */
    public record Hit(String code, String name, String market, AssetKind kind, Long prevClose,
                      MatchType matchedBy) {
    }

    /** 어떻게 찾았는가. */
    public enum MatchType {
        /** 이름·코드 문자 일치 */
        TEXT,
        /** 로컬 임베딩 의미 검색 (문자 검색이 0건일 때만) */
        SEMANTIC
    }

    /** 종목 하나의 상세. 시세는 이때만 조회한다. */
    public record Detail(String code, String name, AssetKind kind, long underlyingPrice,
                         long splitRatio, long referencePrice) {
    }

    private static final int MAX_HITS = 30;

    private final InstrumentMasterRepository repository;
    private final MarketDataPort marketData;
    private final InstrumentSemanticPort semantic;

    public InstrumentSearchService(InstrumentMasterRepository repository,
                                   MarketDataPort marketData,
                                   InstrumentSemanticPort semantic) {
        this.repository = repository;
        this.marketData = marketData;
        this.semantic = semantic;
    }

    @Transactional(readOnly = true)
    public List<Hit> search(String query, AssetKind kind, int limit) {
        if (query == null || query.strip().length() < 1) {
            return List.of();
        }
        int size = Math.clamp(limit, 1, MAX_HITS);
        String text = query.strip();

        List<Hit> byText = repository.search(text, kind, PageRequest.of(0, size)).stream()
                .map(m -> new Hit(m.code(), m.korName(), m.market(), m.assetKind(), m.prevClose(),
                        MatchType.TEXT))
                .toList();
        if (!byText.isEmpty()) {
            return byText;
        }

        // 문자로 못 찾았을 때만 의미 검색으로 넘어간다. 순서가 중요하다 —
        // "삼성"처럼 정확히 맞는 질의에까지 임베딩을 태우면 느려지고 결과도 흐려진다.
        return semantic.search(text, size).stream()
                .map(hit -> repository.findById(hit.code()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(m -> kind == null || m.assetKind() == kind)
                .map(m -> new Hit(m.code(), m.korName(), m.market(), m.assetKind(), m.prevClose(),
                        MatchType.SEMANTIC))
                .toList();
    }

    /**
     * 종목 하나를 조회하고 조각 참조가를 계산한다.
     *
     * <p>환산은 서버가 한다 — 화면에서 분할비율로 나누면 반올림이 갈려 기준선이 어긋난다.
     */
    @Transactional(readOnly = true)
    public Optional<Detail> detail(String code, long splitRatio) {
        return repository.findById(code).map(master -> {
            Money underlying = marketData.getCurrentPrice(code).price();
            Money reference = PriceConverter.referencePrice(underlying, splitRatio);
            return new Detail(master.code(), master.korName(), master.assetKind(),
                    underlying.amount(), splitRatio, reference.amount());
        });
    }
}
