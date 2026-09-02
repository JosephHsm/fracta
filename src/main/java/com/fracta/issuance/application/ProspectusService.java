package com.fracta.issuance.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fracta.issuance.domain.Issuance;

/**
 * 투자설명서 업로드 (IS-03).
 * MinIO 업로드는 트랜잭션 밖에서 수행한다 — 트랜잭션 안에서 올리면 롤백돼도 파일이 남는다.
 * (역방향 고아: 업로드 후 attach 실패 시 파일이 남을 수 있다 → 고아 파일 정리는 Phase 9 배치 소재로 문서화)
 */
@Service
public class ProspectusService {

    private final ProspectusStorage storage;
    private final IssuanceService issuanceService;

    public ProspectusService(ProspectusStorage storage, IssuanceService issuanceService) {
        this.storage = storage;
        this.issuanceService = issuanceService;
    }

    /**
     * 투자설명서 원본 바이트. 뷰어(FSD §11.2)가 브라우저에 그대로 내려주려고 쓴다.
     * 아직 업로드되지 않았으면 비어 있다.
     */
    public java.util.Optional<byte[]> download(long issuanceId) {
        String fileKey = issuanceService.get(issuanceId).prospectusFileKey();
        if (fileKey == null || fileKey.isBlank() || !storage.exists(fileKey)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(storage.read(fileKey));
    }

    public String upload(long issuanceId, String filename, byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("빈 파일은 업로드할 수 없다");
        }
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType)
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
        if (!isPdf) {
            throw new IllegalArgumentException("투자설명서는 PDF만 허용한다: " + filename);
        }
        // 존재 검증 (트랜잭션 진입 전)
        Issuance issuance = issuanceService.get(issuanceId);

        String fileKey = "issuance-%d/%s.pdf".formatted(issuance.id(), UUID.randomUUID());
        storage.store(fileKey, content, "application/pdf");            // 1. 커밋 대상 아님 — 먼저 업로드
        issuanceService.attachProspectus(issuanceId, fileKey);         // 2. 트랜잭션: 키 저장 + 이벤트 발행
        return fileKey;
    }
}
