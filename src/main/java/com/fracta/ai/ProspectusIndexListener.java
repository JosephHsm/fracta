package com.fracta.ai;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fracta.issuance.api.ProspectusUploadedEvent;

/**
 * 투자설명서 업로드 → 인덱싱 트리거 (phase-08 §2).
 *
 * <p>커밋 후에, 그리고 별도 스레드에서 돈다. 두 가지 이유가 있다.
 * ① 인덱싱은 PDF 파싱 + 임베딩이라 수십 초가 걸릴 수 있는데 업로드 응답이 그걸 기다릴 이유가 없다.
 * ② AI 서비스가 죽어 있어도 투자설명서 업로드 자체는 성공해야 한다. 여기서 예외를 올리면
 *    업로드가 실패한 것처럼 보인다.
 *
 * <p>실패한 인덱싱은 재인덱싱 API로 복구한다 — {@code POST /admin/ai/issuances/{id}/index}.
 */
@Component
public class ProspectusIndexListener {

    private static final Logger log = LoggerFactory.getLogger(ProspectusIndexListener.class);

    private final LlmPort llmPort;
    private final Executor executor;

    public ProspectusIndexListener(LlmPort llmPort, @Qualifier("aiIndexExecutor") Executor executor) {
        this.llmPort = llmPort;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProspectusUploaded(ProspectusUploadedEvent event) {
        executor.execute(() -> index(event.issuanceId(), event.fileKey()));
    }

    private void index(long issuanceId, String fileKey) {
        try {
            IndexResult result = llmPort.indexProspectus(issuanceId, fileKey);
            if (result.chunks() == 0) {
                // 스캔본 PDF 등 텍스트 레이어가 없는 경우. 인덱싱은 성공했지만 검색은 안 된다
                log.warn("투자설명서 인덱싱 결과가 비어 있다 — 텍스트 레이어가 없는 PDF일 수 있다: issuanceId={}",
                        issuanceId);
            } else {
                log.info("투자설명서 인덱싱 완료: issuanceId={}, pages={}, chunks={}",
                        issuanceId, result.pages(), result.chunks());
            }
        } catch (AiUnavailableException e) {
            log.warn("투자설명서 인덱싱 실패 — 업로드는 유지된다. 재인덱싱 API로 복구한다: issuanceId={}, {}",
                    issuanceId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("투자설명서 인덱싱 중 예기치 못한 오류: issuanceId={}", issuanceId, e);
        }
    }
}
