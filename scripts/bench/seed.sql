-- 청약 동시성 벤치마크 시드 (슈퍼유저로 1회 실행)
-- 투자자 500명: KYC 완료·성향 5등급·예치금 1,000만원. 로그인은 쓰지 않으므로 password_hash는 더미.

INSERT INTO investor (name, email, password_hash, ci_hash, role, kyc_status,
                      risk_grade, risk_grade_expires_at, cash_balance)
SELECT 'bench-user-' || i,
       'bench-' || i || '@bench.local',
       'x',
       md5(random()::text) || md5(random()::text),
       'INVESTOR', 'VERIFIED', 5, now() + interval '1 year', 10000000
FROM generate_series(1, 500) AS i
ON CONFLICT (email) DO NOTHING;

INSERT INTO investor (name, email, password_hash, ci_hash, role, kyc_status, cash_balance)
VALUES ('bench-issuer', 'bench-issuer@bench.local', 'x',
        md5(random()::text) || md5(random()::text), 'INVESTOR', 'VERIFIED', 0)
ON CONFLICT (email) DO NOTHING;

INSERT INTO underlying_asset (name, asset_type, asset_code, split_ratio, issuer_id)
SELECT '벤치 자산', 'REIT', 'BNCH', 100, id
FROM investor WHERE email = 'bench-issuer@bench.local'
ON CONFLICT (asset_code) DO NOTHING;

-- FSD §8.3 시나리오: 총 발행량 1,000 / 단가 100원 / FCFS
INSERT INTO issuance (asset_id, token_symbol, total_units, unit_price, remaining_units,
                      status, subscription_start_at, subscription_end_at, allotment_method, risk_grade)
SELECT a.id, 'FR-BNCH-001', 1000, 100, 1000,
       'SUBSCRIBING', now() - interval '1 hour', now() + interval '30 day', 'FCFS', 3
FROM underlying_asset a WHERE a.asset_code = 'BNCH'
ON CONFLICT (token_symbol) DO NOTHING;
