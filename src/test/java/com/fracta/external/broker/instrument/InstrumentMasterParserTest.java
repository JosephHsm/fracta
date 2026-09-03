package com.fracta.external.broker.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 고정폭 마스터 파싱. 네트워크 없이 레코드를 만들어 검증한다. */
class InstrumentMasterParserTest {

    private static final Charset CP949 = Charset.forName("x-windows-949");

    /** 실제 레이아웃대로 237바이트 레코드를 만든다. */
    private static byte[] record(String code, String market, String korName, String prevClose) {
        byte[] rec = new byte[237];
        Arrays.fill(rec, (byte) ' ');
        put(rec, 0, code, 6);
        put(rec, 6, market, 1);
        put(rec, 7, korName, 41);
        put(rec, 152, prevClose, 7);
        rec[236] = '\n';
        return rec;
    }

    private static void put(byte[] rec, int offset, String value, int length) {
        byte[] bytes = value.getBytes(CP949);
        System.arraycopy(bytes, 0, rec, offset, Math.min(bytes.length, length));
    }

    private static byte[] concat(byte[]... records) {
        byte[] all = new byte[records.length * 237];
        for (int i = 0; i < records.length; i++) {
            System.arraycopy(records[i], 0, all, i * 237, 237);
        }
        return all;
    }

    @Test
    @DisplayName("코드·이름·전일가를 읽고 종류를 분류한다")
    void parsesRecords() {
        var rows = InstrumentMasterParser.parse(concat(
                record("069500", "1", "KODEX 200", "105235"),
                record("365550", "1", "ESR켄달스퀘어리츠", "3100"),
                record("005930", "1", "*삼성전자", "253000")));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).korName()).isEqualTo("KODEX 200");
        assertThat(rows.get(0).kind()).isEqualTo(AssetKind.ETF);
        assertThat(rows.get(0).prevClose()).isEqualTo(105_235L);

        assertThat(rows.get(1).kind()).isEqualTo(AssetKind.REIT);
        // 우선주·관리종목 표시 '*'는 검색을 방해하므로 떼고 저장한다
        assertThat(rows.get(2).korName()).isEqualTo("삼성전자");
        assertThat(rows.get(2).kind()).isEqualTo(AssetKind.STOCK);
    }

    @Test
    @DisplayName("레코드 길이로 나누어떨어지지 않으면 던진다 — 조용히 어긋난 오프셋으로 읽지 않는다")
    void rejectsMisalignedFile() {
        assertThatThrownBy(() -> InstrumentMasterParser.parse(new byte[238]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("레이아웃");
    }

    @Test
    @DisplayName("리츠를 담은 ETF는 ETF다 — 이름이 '리츠'로 끝나도 브랜드 접두가 우선한다")
    void etfHoldingReitsIsStillEtf() {
        assertThat(AssetKind.classify("ACE 싱가포르리츠")).isEqualTo(AssetKind.ETF);
        assertThat(AssetKind.classify("신한알파리츠")).isEqualTo(AssetKind.REIT);
        // 브랜드 접두가 단어 일부이면 ETF가 아니다
        assertThat(AssetKind.classify("SOLUTION아이앤씨")).isEqualTo(AssetKind.STOCK);
    }
}
