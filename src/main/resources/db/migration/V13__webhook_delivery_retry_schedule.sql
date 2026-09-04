-- 웹훅 재시도를 인라인 대기에서 예약으로 바꾼다.
--
-- 기존 워커는 실패할 때마다 그 자리에서 Thread.sleep 으로 1→2→4→8초를 기다렸다.
-- 큐를 한 틱에 순차 처리하므로, 죽은 엔드포인트 하나가 최대 15초씩 잡아먹으며
-- 다른 모든 클라이언트의 웹훅을 줄세웠다. 수신 측 지연이 발송 전체를 막지 않게
-- 하려고 큐를 둔 건데 워커가 같은 방식으로 막히고 있었다.
--
-- 이제 한 번만 시도하고, 실패하면 다음 시도 시각을 적어 두고 즉시 넘어간다.
-- 워커는 매 주기 "시도 시각이 지난 PENDING" 을 쓸어 담아 다시 한 번씩 시도한다.
ALTER TABLE webhook_delivery
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- 재시도 스윕: PENDING 중 시도 시각이 도래한 것만 시각 순으로 읽는다
CREATE INDEX idx_webhook_delivery_due
    ON webhook_delivery (next_attempt_at)
    WHERE status = 'PENDING';
