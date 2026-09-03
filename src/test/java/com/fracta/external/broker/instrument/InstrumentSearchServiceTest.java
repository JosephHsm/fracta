package com.fracta.external.broker.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;

import com.fracta.common.money.Money;
import com.fracta.external.broker.EtfReferencePort;
import com.fracta.external.broker.MarketDataPort;
import com.fracta.external.broker.Quote;

/**
 * 검색 폴백 정책.
 *
 * <p>핵심은 <b>순서</b>다. 문자로 찾히면 임베딩을 태우지 않는다 — "삼성"처럼 정확히 맞는
 * 질의까지 벡터 검색을 돌리면 느려지고 결과도 흐려진다.
 */
class InstrumentSearchServiceTest {

    private final InstrumentMasterRepository repository = mock(InstrumentMasterRepository.class);
    private final MarketDataPort marketData = mock(MarketDataPort.class);
    private final InstrumentSemanticPort semantic = mock(InstrumentSemanticPort.class);
    private final EtfReferencePort etfReference = mock(EtfReferencePort.class);
    private final InstrumentSearchService service =
            new InstrumentSearchService(repository, marketData, semantic, etfReference);

    private static InstrumentMaster master(String code, String name, AssetKind kind) {
        return new InstrumentMaster(
                new InstrumentMasterParser.Row(code, "1", name, null, 1000L, null, kind));
    }

    @Test
    @DisplayName("문자 검색이 잡히면 의미 검색을 부르지 않는다")
    void textMatchSkipsSemantic() {
        when(repository.search(anyString(), any(), any(Pageable.class)))
                .thenReturn(List.of(master("005930", "삼성전자", AssetKind.STOCK)));

        var hits = service.search("삼성", null, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).matchedBy()).isEqualTo(InstrumentSearchService.MatchType.TEXT);
        verify(semantic, never()).search(anyString(), anyInt());
    }

    @Test
    @DisplayName("문자 검색이 0건이면 의미 검색으로 넘어간다 — '코덱스' → 'KODEX 200'")
    void fallsBackToSemantic() {
        when(repository.search(anyString(), any(), any(Pageable.class))).thenReturn(List.of());
        when(semantic.search(anyString(), anyInt()))
                .thenReturn(List.of(new InstrumentSemanticPort.Hit("069500", "KODEX 200", "ETF", 0.82)));
        when(repository.findById("069500"))
                .thenReturn(Optional.of(master("069500", "KODEX 200", AssetKind.ETF)));

        var hits = service.search("코덱스", null, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).name()).isEqualTo("KODEX 200");
        assertThat(hits.get(0).matchedBy()).isEqualTo(InstrumentSearchService.MatchType.SEMANTIC);
    }

    @Test
    @DisplayName("의미 검색 결과도 종류 필터를 지킨다")
    void semanticRespectsKindFilter() {
        when(repository.search(anyString(), any(), any(Pageable.class))).thenReturn(List.of());
        when(semantic.search(anyString(), anyInt()))
                .thenReturn(List.of(new InstrumentSemanticPort.Hit("005930", "삼성전자", "STOCK", 0.7)));
        when(repository.findById("005930"))
                .thenReturn(Optional.of(master("005930", "삼성전자", AssetKind.STOCK)));

        assertThat(service.search("반도체", AssetKind.ETF, 10)).isEmpty();
    }

    @Test
    @DisplayName("AI 서비스가 죽어도 검색이 죽지 않는다 — 빈 목록으로 끝난다")
    void semanticFailureDegradesGracefully() {
        when(repository.search(anyString(), any(), any(Pageable.class))).thenReturn(List.of());
        when(semantic.search(anyString(), anyInt())).thenReturn(List.of());

        assertThat(service.search("코덱스", null, 10)).isEmpty();
    }

    @Test
    @DisplayName("ETF는 증권사 NAV·괴리율을 함께 준다 — 이중 괴리율의 근거")
    void etfDetailCarriesBrokerReference() {
        when(repository.findById("069500"))
                .thenReturn(Optional.of(master("069500", "KODEX 200", AssetKind.ETF)));
        when(marketData.getCurrentPrice("069500"))
                .thenReturn(new Quote("069500", Money.of(105_235L), java.time.Instant.now()));
        when(etfReference.reference("069500")).thenReturn(Optional.of(
                new EtfReferencePort.EtfReference("069500", 105_235L,
                        new BigDecimal("105291.01"), new BigDecimal("0.2"),
                        new BigDecimal("0.38"), 41_855L, 61_931L)));

        var detail = service.detail("069500", 1000).orElseThrow();

        // 서버가 환산한다 — 화면에서 나누면 반올림이 갈려 호가창 기준선과 어긋난다
        assertThat(detail.referencePrice()).isEqualTo(105L);
        assertThat(detail.etf()).isNotNull();
        assertThat(detail.etf().premiumRate()).isEqualByComparingTo("0.2");
        assertThat(detail.etf().lpBidUnits()).isEqualTo(61_931L);
    }

    @Test
    @DisplayName("ETF가 아니면 증권사 지표를 조회하지 않는다")
    void nonEtfSkipsReferenceLookup() {
        when(repository.findById("365550"))
                .thenReturn(Optional.of(master("365550", "ESR켄달스퀘어리츠", AssetKind.REIT)));
        when(marketData.getCurrentPrice("365550"))
                .thenReturn(new Quote("365550", Money.of(3_100L), java.time.Instant.now()));

        assertThat(service.detail("365550", 10).orElseThrow().etf()).isNull();
        verify(etfReference, never()).reference(anyString());
    }

    @Test
    @DisplayName("빈 질의는 아무것도 부르지 않는다")
    void blankQueryTouchesNothing() {
        assertThat(service.search("   ", null, 10)).isEmpty();
        verify(semantic, never()).search(anyString(), anyInt());
    }
}
