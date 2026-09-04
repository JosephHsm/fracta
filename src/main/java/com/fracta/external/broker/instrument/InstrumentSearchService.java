package com.fracta.external.broker.instrument;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.external.broker.MarketDataPort;
import com.fracta.external.broker.PriceConverter;
import java.math.BigDecimal;

import com.fracta.common.money.Money;
import com.fracta.external.broker.EtfReferencePort;

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
    public record InstrumentHit(String code, String name, String market, AssetKind kind, Long prevClose,
                      MatchType matchedBy) {
    }

    /** 어떻게 찾았는가. */
    public enum MatchType {
        /** 이름·코드 문자 일치 */
        TEXT,
        /** 로컬 임베딩 의미 검색 (문자 검색이 0건일 때만) */
        SEMANTIC
    }

    /**
     * 종목 하나의 상세. 시세는 이때만 조회한다.
     *
     * <p>{@code etf}가 있으면 <b>이중 괴리율</b>을 보여줄 수 있다 —
     * 증권사가 계산한 ETF 괴리율(LP가 좁혀준 결과)과 우리 조각 괴리율을 나란히.
     * 유동성공급자가 있는 시장과 없는 시장의 차이가 숫자로 드러난다.
     */
    public record InstrumentDetail(String code, String name, AssetKind kind, long underlyingPrice,
                         long splitRatio, long referencePrice, InstrumentEtfReference etf) {
    }

    /** 증권사가 내려준 ETF 기준 지표. ETF가 아니거나 조회 실패면 null이다. */
    public record InstrumentEtfReference(BigDecimal nav, BigDecimal premiumRate, BigDecimal trackingError,
                               long lpAskUnits, long lpBidUnits) {
    }

    private static final int MAX_HITS = 30;

    private final InstrumentMasterRepository repository;
    private final MarketDataPort marketData;
    private final InstrumentSemanticPort semantic;
    private final EtfReferencePort etfReference;

    public InstrumentSearchService(InstrumentMasterRepository repository,
                                   MarketDataPort marketData,
                                   InstrumentSemanticPort semantic,
                                   EtfReferencePort etfReference) {
        this.repository = repository;
        this.marketData = marketData;
        this.semantic = semantic;
        this.etfReference = etfReference;
    }

    @Transactional(readOnly = true)
    public List<InstrumentHit> search(String query, AssetKind kind, int limit) {
        if (query == null || query.strip().length() < 1) {
            return List.of();
        }
        int size = Math.clamp(limit, 1, MAX_HITS);
        String text = query.strip();

        List<InstrumentHit> byText = repository.search(text, kind, PageRequest.of(0, size)).stream()
                .map(m -> new InstrumentHit(m.code(), m.korName(), m.market(), m.assetKind(), m.prevClose(),
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
                .map(m -> new InstrumentHit(m.code(), m.korName(), m.market(), m.assetKind(), m.prevClose(),
                        MatchType.SEMANTIC))
                .toList();
    }

    /**
     * 종목 하나를 조회하고 조각 참조가를 계산한다.
     *
     * <p>환산은 서버가 한다 — 화면에서 분할비율로 나누면 반올림이 갈려 기준선이 어긋난다.
     */
    @Transactional(readOnly = true)
    public Optional<InstrumentDetail> detail(String code, long splitRatio) {
        if (com.fracta.external.broker.MarketVenue.of(code).overseas()
                && repository.findById(code).isEmpty()) {
            // 해외 종목은 국내 종목마스터에 없다. 마스터는 검색용이고 시세는 별도 경로라
            // 마스터에 없다고 조회를 거부하면 해외 자산을 아예 발행할 수 없다.
            return overseasDetail(code, splitRatio);
        }
        return repository.findById(code).map(master -> {
            Money underlying = marketData.getCurrentPrice(code).price();
            Money reference = PriceConverter.referencePrice(underlying, splitRatio);
            InstrumentEtfReference etf = master.assetKind() == AssetKind.ETF
                    ? etfReference.reference(code)
                            .map(r -> new InstrumentEtfReference(r.nav(), r.premiumRate(), r.trackingError(),
                                    r.lpAskUnits(), r.lpBidUnits()))
                            .orElse(null)
                    : null;
            return new InstrumentDetail(master.code(), master.korName(), master.assetKind(),
                    underlying.amount(), splitRatio, reference.amount(), etf);
        });
    }

    /**
     * 해외 종목 상세. 이름은 시세 응답이 주지 않으므로 코드를 그대로 쓴다 —
     * 마스터가 없는 대신 시세만으로 참조가를 낸다.
     *
     * <p>금액 단위는 센트다({@code NamuhPlugMarketDataAdapter}). 조각 참조가도 같은 단위로
     * 나오므로 화면·발행가가 일관되게 유지된다.
     */
    private Optional<InstrumentDetail> overseasDetail(String code, long splitRatio) {
        Money underlying = marketData.getCurrentPrice(code).price();
        if (underlying.isZero()) {
            return Optional.empty();
        }
        Money reference = PriceConverter.referencePrice(underlying, splitRatio);
        return Optional.of(new InstrumentDetail(code, code, AssetKind.STOCK,
                underlying.amount(), splitRatio, reference.amount(), null));
    }
}
