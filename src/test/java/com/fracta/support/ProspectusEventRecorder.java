package com.fracta.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fracta.issuance.api.ProspectusUploadedEvent;

/** ProspectusUploadedEvent가 AFTER_COMMIT으로 발행되는지 검증하는 테스트 리스너. */
@Component
public class ProspectusEventRecorder {

    private final List<ProspectusUploadedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ProspectusUploadedEvent event) {
        events.add(event);
    }

    public List<ProspectusUploadedEvent> events() {
        return events;
    }
}
