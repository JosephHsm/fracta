package com.fracta.external.broker.instrument;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 종목마스터 {@code m_new_stock.mst} 고정폭 파서.
 *
 * <p>레이아웃 정본은 배포처의 {@code m_new_stock.h}다(인증 불필요). 여기서는 검색에 필요한
 * 필드만 읽는다 — 전체 39개 필드를 다 읽을 이유가 없고, 읽지 않는 필드가 늘수록
 * 레이아웃 개정에 약해진다.
 *
 * <pre>
 *   레코드 237바이트 · cp949 · 우측 공백 패딩 · 파일 헤더 없음
 *   sCode@0:6  sMarket@6:1  sKorName@7:41  sEngName@48:41  sPrePrice@152:7  prdy_avls@174:12
 * </pre>
 *
 * <p>레코드 길이로 나누어떨어지지 않으면 레이아웃이 바뀐 것이다 — 조용히 넘어가지 않고 던진다.
 * 어긋난 오프셋으로 파싱하면 종목코드가 엉뚱하게 섞이는데, 그건 화면에서 알아채기 어렵다.
 */
public final class InstrumentMasterParser {

    static final int RECORD_LENGTH = 237;
    private static final Charset CP949 = Charset.forName("x-windows-949");

    /** 마스터 한 줄. 금액은 원 단위 정수다(마스터가 정수로 준다). */
    public record Row(String code, String market, String korName, String engName,
                      Long prevClose, Long marketCap, AssetKind kind) {
    }

    private InstrumentMasterParser() {
    }

    public static List<Row> parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("종목마스터가 비어 있습니다.");
        }
        if (raw.length % RECORD_LENGTH != 0) {
            throw new IllegalStateException(
                    "레코드 길이(%d)로 나누어떨어지지 않습니다. 레이아웃이 바뀌었을 수 있습니다: 파일 %d바이트"
                            .formatted(RECORD_LENGTH, raw.length));
        }

        int count = raw.length / RECORD_LENGTH;
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int base = i * RECORD_LENGTH;
            String code = field(raw, base, 0, 6);
            if (code.isEmpty()) {
                continue;
            }
            String korName = stripMarker(field(raw, base, 7, 41));
            rows.add(new Row(
                    code,
                    field(raw, base, 6, 1),
                    korName,
                    stripMarker(field(raw, base, 48, 41)),
                    number(field(raw, base, 152, 7)),
                    number(field(raw, base, 174, 12)),
                    AssetKind.classify(korName)));
        }
        return rows;
    }

    /** 우선주·관리종목 표시로 이름 앞에 '*'가 붙는다. 검색어에는 방해만 된다. */
    private static String stripMarker(String name) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == '*') {
            i++;
        }
        return name.substring(i).strip();
    }

    private static String field(byte[] raw, int base, int offset, int length) {
        return new String(raw, base + offset, length, CP949).strip();
    }

    /** 숫자 필드는 공백이거나 부호가 섞일 수 있다. 못 읽으면 null — 검색에 필수 값이 아니다. */
    private static Long number(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text.replace("+", "").strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
