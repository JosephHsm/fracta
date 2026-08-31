package com.fracta.issuance.application;

/** 투자설명서 저장소 포트 — MinIO 등 외부 스토리지를 추상화한다. */
public interface ProspectusStorage {

    /** 파일을 저장하고 저장 키를 반환한다. 트랜잭션 밖에서 호출할 것. */
    void store(String fileKey, byte[] content, String contentType);

    boolean exists(String fileKey);
}
