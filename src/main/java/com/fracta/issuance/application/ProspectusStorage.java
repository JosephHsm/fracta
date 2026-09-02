package com.fracta.issuance.application;

/** 투자설명서 저장소 포트 — MinIO 등 외부 스토리지를 추상화한다. */
public interface ProspectusStorage {

    /** 파일을 저장하고 저장 키를 반환한다. 트랜잭션 밖에서 호출할 것. */
    void store(String fileKey, byte[] content, String contentType);

    boolean exists(String fileKey);

    /**
     * 저장된 파일을 그대로 읽는다. 투자설명서 뷰어(FSD §11.2)가 브라우저에 내려주려면 필요하다.
     *
     * <p>MinIO 자격증명을 브라우저에 노출하지 않으려고 presigned URL 대신 서버가 중계한다.
     * 투자설명서는 수 MB 수준이라 중계 비용이 문제되지 않는다.
     */
    byte[] read(String fileKey);
}
