package com.fracta.ai;

/**
 * Core 측 AI 포트 (phase-08 §2).
 *
 * <p>구현체는 {@link AiHttpAdapter} 하나지만 포트를 두는 이유는 두 가지다.
 * ① AI 서비스가 없는 환경(단위 테스트·CI)에서 대역을 끼운다.
 * ② 모듈러 모놀리스 규칙상 다른 모듈은 이 인터페이스로만 AI에 접근한다.
 *
 * <p>모델 선택·가드레일은 전부 AI 서비스(Python)의 책임이다. Core는 결과를
 * 받아 감사·메트릭에 반영하고, 실패하면 조용히 비활성화한다 — AI 장애가
 * 청약·주문을 막으면 안 된다.
 */
public interface LlmPort {

    /** 투자설명서 질의응답. 가드레일 차단도 정상 응답으로 돌아온다(blocked=true). */
    ProspectusAnswer askProspectus(long issuanceId, String question, String requestId);

    /** 개발자 어시스턴트. 컨텍스트는 Phase 7의 OpenAPI 스펙이다. */
    DevPortalAnswer askDevPortal(String question, String requestId);

    /** 투자설명서 인덱싱. {@code ProspectusUploadedEvent} 수신 시 호출된다. */
    IndexResult indexProspectus(long issuanceId, String fileKey);

    /** 서킷브레이커 상태를 포함한 가용성. /actuator/health 에 쓰인다. */
    boolean available();
}
