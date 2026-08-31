-- 매 측정 전 초기화 (슈퍼유저) — 동일 조건 보장 (phase-04 §k6 시나리오)
DELETE FROM subscription_order;
UPDATE issuance SET remaining_units = total_units, status = 'SUBSCRIBING'
 WHERE token_symbol = 'FR-BNCH-001';
UPDATE issuance SET allotment_method = 'FCFS' WHERE token_symbol = 'FR-BNCH-001';

DELETE FROM cash_transaction;
UPDATE investor SET cash_balance = 10000000 WHERE email LIKE 'bench-%@bench.local'
                                              AND email <> 'bench-issuer@bench.local';
UPDATE investor SET cash_balance = 0 WHERE email = 'bench-issuer@bench.local';

-- 잔액에 대응하는 외부 입금 기록을 남겨 INV-6(예치금 보존)이 벤치 데이터에서도 성립하게 한다.
INSERT INTO cash_transaction (investor_id, tx_type, amount, balance_after)
SELECT id, 'DEPOSIT', 10000000, 10000000
FROM investor
WHERE email LIKE 'bench-%@bench.local' AND email <> 'bench-issuer@bench.local';
