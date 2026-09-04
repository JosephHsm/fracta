-- 매수 주문 자금 홀드 (Phase 6 보강).
--
-- 매도는 접수 시점에 ledger.lock 으로 수량을 선잠금하는데 매수는 대금을 잠그지 않아,
-- 잔고 없는 매수 주문이 호가창에 올라가 결제 실패를 반복시킬 수 있었다.
-- 접수 시점에 예상 체결금액(+수수료)을 홀드하고, 체결분만큼 나눠 환급한다.
--
-- held_amount 는 "아직 환급되지 않은 홀드 잔액"이다. 0 이면 홀드가 남아 있지 않다.
-- INV-6 은 이 값을 미결제 증거금과 같은 항으로 더한다 (SubscriptionInvariantService).
ALTER TABLE trade_order
    ADD COLUMN held_amount BIGINT NOT NULL DEFAULT 0 CHECK (held_amount >= 0);

-- 매도 주문은 대금을 홀드하지 않는다 (수량을 원장에서 잠근다)
ALTER TABLE trade_order
    ADD CONSTRAINT ck_trade_order_hold_side
        CHECK (side = 'BUY' OR held_amount = 0);

-- INV-6 집계: 홀드가 남은 주문만 훑는다
CREATE INDEX idx_trade_order_held ON trade_order (held_amount) WHERE held_amount > 0;
