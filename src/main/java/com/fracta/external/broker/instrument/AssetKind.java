package com.fracta.external.broker.instrument;

/**
 * 종목 분류.
 *
 * <p>종목마스터({@code m_new_stock.mst})에 상품유형 필드가 없다 — {@code sGroup}은
 * 재벌그룹 코드이고, 업종코드는 ETF와 일반주식을 갈라주지 않는다. 그래서 이름 규칙으로 나눈다.
 * 국내 ETF는 운용사 브랜드가 접두로 붙고(KODEX·TIGER…), 리츠는 이름이 "리츠"로 끝난다.
 */
public enum AssetKind {
    ETF,
    REIT,
    STOCK;

    /** 국내 ETF 브랜드 접두. 신규 브랜드가 생기면 여기에 추가한다. */
    private static final String[] ETF_BRANDS = {
            "KODEX", "TIGER", "KBSTAR", "ARIRANG", "HANARO", "SOL", "ACE", "PLUS", "RISE",
            "KIWOOM", "KOSEF", "TIMEFOLIO", "WOORI", "BNK", "TREX", "FOCUS", "VITA", "UNICORN",
            "1Q", "히어로즈", "마이다스", "네비게이터", "파워", "에셋플러스", "마이티", "BNKX",
    };

    /**
     * 이름으로 분류한다. <b>ETF를 먼저 본다</b> — "ACE 싱가포르리츠"처럼 리츠를 담은 ETF가 있어서,
     * 리츠를 먼저 보면 ETF가 리츠로 잘못 분류된다.
     */
    public static AssetKind classify(String korName) {
        if (korName == null || korName.isBlank()) {
            return STOCK;
        }
        String name = korName.strip();
        String upper = name.toUpperCase();
        for (String brand : ETF_BRANDS) {
            // 접두 뒤에 공백이나 영문/숫자가 와야 한다. "SOLUTION"이 "SOL"로 걸리면 안 된다.
            if (upper.startsWith(brand)
                    && (upper.length() == brand.length() || !Character.isLetter(upper.charAt(brand.length())))) {
                return ETF;
            }
        }
        return name.endsWith("리츠") ? REIT : STOCK;
    }
}
