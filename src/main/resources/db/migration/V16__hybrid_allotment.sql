-- 균등배정 도입 (HYBRID).
--
-- 조각투자의 존재 이유는 소액 접근성인데 배분이 순수 안분비례라, 경쟁률이 높으면 소액
-- 청약자가 0주를 받았다. 국내 공모주에 균등배정이 도입된 배경이 정확히 이 문제다.
-- 상품 컨셉과 배분 규칙이 반대를 보고 있었다.
--
-- 기존 알고리즘은 그대로 두고 앞단만 붙인다: 총량의 일부를 참여자에게 균등 배분하고,
-- 남은 물량을 기존 ProportionalAllocator 에 넘긴다. 결정론과 Σaᵢ == N 은 유지된다.
ALTER TABLE issuance
    DROP CONSTRAINT IF EXISTS issuance_allotment_method_check;

ALTER TABLE issuance
    ADD CONSTRAINT issuance_allotment_method_check
        CHECK (allotment_method IN ('FCFS', 'PRORATA', 'HYBRID'));

-- 균등 배분에 쓸 총량 비율(%). HYBRID 에서만 의미가 있다.
ALTER TABLE issuance
    ADD COLUMN equal_allotment_percent INT NOT NULL DEFAULT 50
        CHECK (equal_allotment_percent BETWEEN 0 AND 100);
