-- 괴리율 임계치를 자산별로 조정할 수 있게 한다.
--
-- 경고 10% / 자동중단 20% 가 코드 상수로 박혀 있었다. REIT·ETF·부동산의 정상 괴리 범위가
-- 같을 리 없는데 종목별 조정이 불가능했다. 자동 거래중단(TR-08)은 되돌리는 비용이 큰 조치라
-- 임계치가 한 값으로 고정돼 있으면 오작동이든 미작동이든 전부 그 값 탓이 된다.
--
-- NULL 이면 설정 기본값(trading.premium.warn-percent / suspend-percent)을 쓴다.
ALTER TABLE underlying_asset
    ADD COLUMN premium_warn_percent    NUMERIC(6, 2),
    ADD COLUMN premium_suspend_percent NUMERIC(6, 2),
    ADD CONSTRAINT ck_underlying_premium_threshold
        CHECK (
            (premium_warn_percent    IS NULL OR premium_warn_percent    > 0) AND
            (premium_suspend_percent IS NULL OR premium_suspend_percent > 0) AND
            (premium_warn_percent IS NULL OR premium_suspend_percent IS NULL
                OR premium_warn_percent <= premium_suspend_percent)
        );
